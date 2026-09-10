package io.github.search5.hg4j.util;
import io.github.search5.hg4j.storage.Revlog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.io.ByteArrayOutputStream;


/**
 * Common utility methods for handling Mercurial NodeIDs and Hexadecimal representations.
 *
 * @apiNote Two distinct groups of static helpers live here. (1) Node ID / hex conversion and
 *     lookup ({@link #toHex}, {@link #fromHex}, {@link #isAllZero}, {@link
 *     #findRevisionByNodeId}, {@link #resolveRevision}, {@link #computeNodeId}, {@link
 *     #computeUnbundleHeadsWireValue}) used throughout the transport layer and by most porcelain
 *     commands that accept a user-supplied revision string. (2) Store path encoding ({@link
 *     #encodeFname}, {@link #decodeStoreDataPath}) implementing real hg's {@code
 *     store._pathencode}/{@code _hashencode} scheme, used by {@code CommitCommand}/{@code
 *     FetchCommand} when writing revlogs and by {@code
 *     io.github.search5.hg4j.api.GrepCommand} when enumerating tracked files directly from the
 *     on-disk store.
 */
public final class NodeIdUtil {

    private NodeIdUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Converts a byte array to its hexadecimal String representation.
     *
     * @apiNote The standard way to render a raw node ID for wire protocol messages (used
     *     throughout {@code HgLocalClient}, {@code HgRemoteClientV2}, {@code Wire1Commands},
     *     {@code Wire2Commands}) and for merge-state bookkeeping ({@code MergeState}). Returns
     *     {@code ""} for {@code null} rather than throwing, so callers building log/error
     *     messages don't need a null check first.
     * @param bytes the raw bytes to render, typically a 20-byte node ID; may be {@code null}
     * @return the lowercase hexadecimal encoding of {@code bytes}, or {@code ""} if {@code bytes}
     *     is {@code null}
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Converts a hexadecimal String to its byte array representation.
     *
     * @apiNote The counterpart to {@link #toHex}, used to decode node IDs received over the
     *         wire (e.g. in {@code HgRemoteClientV2}, {@code Wire1Commands}) and revision
     *         references resolved from a revset ({@code HgRevsetEngine}).
     * @param hex the hexadecimal string to decode; {@code null} or empty yields an empty array
     * @return the decoded bytes, one per pair of hex digits
     * @throws IllegalArgumentException if {@code hex} has an odd length or contains a
     *         non-hexadecimal character
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have an even length: " + hex);
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character in string: " + hex);
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    /**
     * Checks if a 20-byte array represents a null (all-zero) node ID.
     *
     * @apiNote Used to detect the "no parent" / "null revision" sentinel node ID (Mercurial's
     *         {@code nullid}), e.g. by {@code StatusCommand}, {@code GraftCommand}, {@code
     *         BackoutCommand}, {@code AnnotateCommand}, and {@code IdentifyCommand} when deciding
     *         whether a changeset has a given parent at all. Returns {@code true} for {@code
     *         null}, treating "no array" the same as "all zero".
     * @param bytes the byte array to check, typically a 20-byte node ID; may be {@code null}
     * @return {@code true} if every byte is zero, or {@code bytes} is {@code null}
     */
    public static boolean isAllZero(byte[] bytes) {
        if (bytes == null) return true;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    /**
     * Searches for a revision index in a Revlog using its 20-byte Node ID.
     *
     * @apiNote A null-safe convenience wrapper around {@link Revlog#findRevision}, used by
     *         {@code DefaultFileStoreEngine} and porcelain commands that navigate history by
     *         node ID (e.g. {@code LogCommand}, {@code CatCommand}, {@code ManifestCommand},
     *         {@code GraftCommand}, {@code TreeMergeCommand}).
     * @param revlog the revlog to search; may be {@code null}
     * @param nodeId the 20-byte node ID to look up; may be {@code null}
     * @return the revision number, or {@code -1} if either argument is {@code null} or the node
     *         ID is not present in the revlog
     */
    public static int findRevisionByNodeId(Revlog revlog, byte[] nodeId) {
        if (revlog == null || nodeId == null) {
            return -1;
        }
        return revlog.findRevision(nodeId);
    }

    private static final String WINDOWS_SPECIAL_CHARS = "\\:*?\"<>|";
    private static final int STORE_MAX_PATH_LEN = 120;
    private static final int STORE_DIR_PREFIX_LEN = 8;
    private static final int STORE_MAX_SHORT_DIRS_LEN = 8 * (STORE_DIR_PREFIX_LEN + 1) - 4; // 68

    private static boolean isReservedStoreByte(int b) {
        if (b <= 31 || b >= 126) {
            return true;
        }
        return WINDOWS_SPECIAL_CHARS.indexOf((char) b) != -1;
    }

    /**
     * Real hg's {@code store._encodedir}: guards against a directory literally ending in
     * {@code .i}/{@code .d}/{@code .hg} being confused with a revlog file/backup when the store
     * is scanned, by suffixing such directory names with an extra {@code .hg}.
     */
    private static String encodeDir(String path) {
        return path.replace(".hg/", ".hg.hg/").replace(".i/", ".i.hg/").replace(".d/", ".d.hg/");
    }

    /**
     * Real hg's {@code store._encodefname} (reversible): uppercase ASCII letters become
     * {@code _x}, a literal {@code _} doubles, and reserved/control/high bytes become
     * {@code ~xx}. Operating byte-wise (not char-wise) keeps multi-byte UTF-8 sequences intact —
     * each of their bytes is {@code >= 126} and gets independently {@code ~xx}-escaped, which is
     * exactly how real hg round-trips non-ASCII filenames.
     */
    private static String encodeFnameBytes(byte[] input) {
        StringBuilder sb = new StringBuilder(input.length);
        for (byte raw : input) {
            int b = raw & 0xFF;
            if (isReservedStoreByte(b)) {
                sb.append(String.format("~%02x", b));
            } else if (b == '_') {
                sb.append("__");
            } else if (b >= 'A' && b <= 'Z') {
                sb.append('_').append((char) (b + 32));
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    /**
     * Real hg's {@code store.lowerencode} (non-reversible, used only inside the long-path hash
     * scheme): uppercase ASCII letters are simply lowercased (no {@code _} marker), reserved
     * bytes still become {@code ~xx}.
     */
    private static String lowerEncodeBytes(byte[] input) {
        StringBuilder sb = new StringBuilder(input.length);
        for (byte raw : input) {
            int b = raw & 0xFF;
            if (isReservedStoreByte(b)) {
                sb.append(String.format("~%02x", b));
            } else if (b >= 'A' && b <= 'Z') {
                sb.append((char) (b + 32));
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    /**
     * Real hg's {@code store._auxencode}, applied to already {@code _encodefname}/{@code
     * lowerencode}-d path components (so a component like Windows-reserved {@code aux} is only
     * ever recognized in its lowercase form — an originally-uppercase {@code AUX} was already
     * turned into {@code _a_u_x} by {@link #encodeFnameBytes}, which is not itself reserved,
     * matching real hg exactly). Escapes a leading {@code .}/space (dotencode), a Windows
     * reserved device name appearing as the basename before the first {@code .}, and a trailing
     * {@code .}/space.
     */
    private static List<String> auxEncode(List<String> parts, boolean dotEncode) {
        List<String> result = new ArrayList<>(parts.size());
        for (String n : parts) {
            if (n.isEmpty()) {
                result.add(n);
                continue;
            }
            if (dotEncode && (n.charAt(0) == '.' || n.charAt(0) == ' ')) {
                n = String.format("~%02x", (int) n.charAt(0)) + n.substring(1);
            } else {
                int dot = n.indexOf('.');
                int l = dot == -1 ? n.length() : dot;
                boolean winres3 = l == 3 && isWinReserved3(n);
                boolean winres4 = l == 4 && n.charAt(3) >= '1' && n.charAt(3) <= '9' && isWinReserved4Prefix(n);
                if (winres3 || winres4) {
                    // Real spec: whether the name is 3 characters (aux/con/prn/nul) or 4
                    // (com1..9/lpt1..9), only the third character (index 2) is ever escaped --
                    // not the trailing digit of the 4-character form.
                    n = n.substring(0, 2) + String.format("~%02x", (int) n.charAt(2)) + n.substring(3);
                }
            }
            if (!n.isEmpty() && (n.charAt(n.length() - 1) == '.' || n.charAt(n.length() - 1) == ' ')) {
                char last = n.charAt(n.length() - 1);
                n = n.substring(0, n.length() - 1) + String.format("~%02x", (int) last);
            }
            result.add(n);
        }
        return result;
    }

    private static boolean isWinReserved3(String n) {
        String p = n.substring(0, 3);
        return p.equals("aux") || p.equals("con") || p.equals("prn") || p.equals("nul");
    }

    private static boolean isWinReserved4Prefix(String n) {
        String p = n.substring(0, 3);
        return p.equals("com") || p.equals("lpt");
    }

    /**
     * Real hg's {@code store._hashencode}: the non-reversible fallback used once the default
     * encoding of a path exceeds {@value #STORE_MAX_PATH_LEN} bytes. Keeps up to
     * {@value #STORE_DIR_PREFIX_LEN} characters of each lowercased directory component (bounded
     * overall by {@value #STORE_MAX_SHORT_DIRS_LEN}), appends as much of the (lowercased)
     * filename as still fits, then the full sha1 of the pre-hash path and the original
     * extension — so two different long paths practically never collide even though the
     * human-readable prefix is truncated.
     */
    private static String hashEncode(String dirEncodedPath, boolean dotEncode) {
        String digest = toHex(sha1(dirEncodedPath.getBytes(StandardCharsets.UTF_8)));

        String afterPrefix = dirEncodedPath.substring(5); // "data/" or "meta/", both 5 bytes
        String lowered = lowerEncodeBytes(afterPrefix.getBytes(StandardCharsets.UTF_8));
        List<String> le = new ArrayList<>(Arrays.asList(lowered.split("/", -1)));
        List<String> parts = auxEncode(le, dotEncode);

        String basename = parts.get(parts.size() - 1);
        int dotIdx = basename.lastIndexOf('.');
        String ext = dotIdx != -1 ? basename.substring(dotIdx) : "";

        List<String> sdirs = new ArrayList<>();
        int sdirsLen = 0;
        for (int i = 0; i < parts.size() - 1; i++) {
            String p = parts.get(i);
            String d = p.length() > STORE_DIR_PREFIX_LEN ? p.substring(0, STORE_DIR_PREFIX_LEN) : p;
            if (!d.isEmpty() && (d.charAt(d.length() - 1) == '.' || d.charAt(d.length() - 1) == ' ')) {
                d = d.substring(0, d.length() - 1) + "_";
            }
            int t;
            if (sdirsLen == 0) {
                t = d.length();
            } else {
                t = sdirsLen + 1 + d.length();
                if (t > STORE_MAX_SHORT_DIRS_LEN) {
                    break;
                }
            }
            sdirs.add(d);
            sdirsLen = t;
        }
        String dirs = String.join("/", sdirs);
        if (!dirs.isEmpty()) {
            dirs = dirs + "/";
        }

        String res = "dh/" + dirs + digest + ext;
        int spaceLeft = STORE_MAX_PATH_LEN - res.length();
        if (spaceLeft > 0) {
            String filler = basename.length() > spaceLeft ? basename.substring(0, spaceLeft) : basename;
            res = "dh/" + dirs + filler + digest + ext;
        }
        return res;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Encodes a logical filelog/manifest path to its on-disk Mercurial store path, matching real
     * hg's {@code store._pathencode} (the 'dotencode' scheme — the default requirement since hg
     * 1.7, and always present in repositories this library creates). Equivalent to
     * {@code store.py}'s sequence: {@code encodedir}, then {@code _encodefname} + {@code
     * _auxencode}, falling back to the {@code dh/}-prefixed hashed form of {@link #hashEncode}
     * once either the raw or the encoded path exceeds {@value #STORE_MAX_PATH_LEN} bytes.
     * @param relPath the logical filelog/manifest path, e.g. {@code "dir/b.txt"}; a path already
     *     prefixed with {@code "data/"} or {@code "meta/"} is used as-is, otherwise {@code "data/"}
     *     is prepended
     * @return the on-disk store-relative path corresponding to {@code relPath}
     */
    public static String encodeFname(String relPath) {
        String logicalPath = (relPath.startsWith("data/") || relPath.startsWith("meta/")) ? relPath : "data/" + relPath;
        String dirEncoded = encodeDir(logicalPath);

        if (logicalPath.getBytes(StandardCharsets.UTF_8).length > STORE_MAX_PATH_LEN) {
            return hashEncode(dirEncoded, true);
        }

        String ef = encodeFnameBytes(dirEncoded.getBytes(StandardCharsets.UTF_8));
        List<String> parts = new ArrayList<>(Arrays.asList(ef.split("/", -1)));
        String result = String.join("/", auxEncode(parts, true));

        if (result.length() > STORE_MAX_PATH_LEN) {
            return hashEncode(dirEncoded, true);
        }
        return result;
    }

    /**
     * Reverses {@link #encodeFname}'s ordinary (non hash-encoded) path -- given a store-relative
     * path exactly as found on disk under {@code store/data/} (e.g. {@code "data/dir/b.txt.i"},
     * from a plain recursive directory walk), returns the original logical repository-relative
     * path (e.g. {@code "dir/b.txt"}).
     *
     * <p>The counterpart {@link io.github.search5.hg4j.api.GrepCommand} needs to enumerate
     * tracked files without {@code fncache}: a {@code format.use-fileindex-v1=yes} repository's
     * {@code store/requires} lists {@code store} but neither {@code fncache} nor {@code
     * dotencode}, and has no {@code fncache} file at all (its own {@code fileindex}/{@code
     * fileindex-list}/{@code fileindex-tree} sidecar files serve the same "which paths exist"
     * purpose internally, in a format this library does not otherwise need to parse), so the
     * on-disk {@code data/} tree itself -- walked directly -- is the only available enumeration
     * source.
     *
     * <p>Every character {@link #encodeFnameBytes} or {@link #auxEncode} can introduce
     * ({@code __}, {@code _<lowercase>}, {@code ~xx}) is unambiguous and reversible in a single
     * left-to-right pass, in any order the two encoding layers happened to apply them in -- both
     * layers write the exact same {@code ~xx} textual escape for a raw byte, and {@code
     * auxEncode} only ever adds more such escapes around the already-{@code
     * encodeFnameBytes}-encoded text, never reinterprets an existing one -- so a single decode
     * pass over the raw escape/marker syntax is exact regardless of which pass introduced which
     * marker. Hash-encoded ({@code dh/}-prefixed, {@link #hashEncode}) paths are NOT
     * reversible (real hg's own hash scheme is intentionally one-way) and are returned verbatim,
     * matching real hg's own inability to recover the original name from one either --
     * unreachable in practice here since {@code fileindex-v1}/{@code general-v2} lack the
     * {@code fncache} requirement typically paired with needing the long-path hash fallback in
     * the first place, and the paths this method is applied to come directly from a real
     * filesystem walk, so they were short enough to exist as literal directories/files already.
     * @param storeRelPath a store-relative path as found on disk under {@code store/data/}, e.g.
     *     {@code "data/dir/b.txt.i"}
     * @return the original logical repository-relative path, e.g. {@code "dir/b.txt"}; a
     *     hash-encoded ({@code dh/}-prefixed) path is returned verbatim, since it is not reversible
     */
    public static String decodeStoreDataPath(String storeRelPath) {
        String noExt = storeRelPath.endsWith(".i") ? storeRelPath.substring(0, storeRelPath.length() - 2) : storeRelPath;
        String prefixStripped = (noExt.startsWith("data/") || noExt.startsWith("meta/")) ? noExt.substring(5) : noExt;
        if (prefixStripped.startsWith("dh/")) {
            return prefixStripped; // hash-encoded -- not reversible, return as-is (see javadoc)
        }
        byte[] decodedBytes = decodeFnameBytes(prefixStripped);
        String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
        return decoded.replace(".hg.hg/", ".hg/").replace(".i.hg/", ".i/").replace(".d.hg/", ".d/");
    }

    /** Byte-wise inverse of {@link #encodeFnameBytes} (and, transparently, {@link #auxEncode}'s
     * additional {@code ~xx} escapes -- see {@link #decodeStoreDataPath}). */
    private static byte[] decodeFnameBytes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '~' && i + 2 < n) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            if (c == '_' && i + 1 < n) {
                char next = s.charAt(i + 1);
                if (next == '_') {
                    out.write('_');
                    i += 2;
                    continue;
                } else if (next >= 'a' && next <= 'z') {
                    out.write(Character.toUpperCase(next));
                    i += 2;
                    continue;
                }
            }
            out.write(c);
            i += 1;
        }
        return out.toByteArray();
    }

    /**
     * Orders strings by their raw UTF-8 byte sequence rather than by Java's UTF-16 {@code char}
     * order, matching real hg's path sort order (Python compares the encoded {@code bytes}
     * paths).
     *
     * @apiNote Used wherever hg4j needs to sort file paths or other strings in exactly the order
     *     real hg would, e.g. for stable diff/status output and manifest-tree comparisons —
     *     see the usages in {@code TreeMergeCommand}, {@code BackoutCommand}, {@code
     *     StatusCommand}, {@code LocateCommand}, {@code ChangingFiles}, and {@code
     *     MergeCommitCommand}. Plain Java {@link String#compareTo} diverges from this for
     *     non-ASCII paths because it compares UTF-16 code units, not UTF-8 bytes.
     */
    public static final Comparator<String> UTF8_STRING_COMPARATOR = (s1, s2) -> {
        byte[] b1 = s1.getBytes(StandardCharsets.UTF_8);
        byte[] b2 = s2.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(b1.length, b2.length);
        for (int i = 0; i < len; i++) {
            int v1 = b1[i] & 0xFF;
            int v2 = b2[i] & 0xFF;
            if (v1 != v2) {
                return v1 - v2;
            }
        }
        return b1.length - b2.length;
    };

    /**
     * Resolves a user-supplied revision reference — empty/{@code null}/{@code "tip"}, a decimal
     * revision number, or a (possibly abbreviated) hex node ID prefix — against a changelog,
     * returning the matching full node ID.
     *
     * @apiNote The shared revision-argument resolver behind most porcelain commands that accept
     *     a {@code -r}/revision string (e.g. {@code CatCommand}, {@code LogCommand}, {@code
     *     ManifestCommand}, {@code GraftCommand}) and the wire protocol command handlers ({@code
     *     Wire1Commands}, {@code Wire2Commands}) resolving a client-supplied revision.
     * @param changelog the repository's changelog revlog to resolve against
     * @param revStr the user-supplied reference; empty, {@code null}, or {@code "tip"} means the
     *     latest revision
     * @return the resolved 20-byte node ID, or {@code null} if {@code revStr} means "tip" but the
     *     changelog is empty, or no hex-prefix match was found
     * @throws IOException if {@code revStr} is an ambiguous hex prefix matching more than one
     *     revision
     */
    public static byte[] resolveRevision(Revlog changelog, String revStr) throws IOException {
        if (revStr == null || revStr.isEmpty() || "tip".equalsIgnoreCase(revStr)) {
            int count = changelog.getRevisionCount();
            if (count == 0) return null;
            return changelog.getIndexRecord(count - 1).getNodeId();
        }
        try {
            int rev = Integer.parseInt(revStr);
            if (rev >= 0 && rev < changelog.getRevisionCount()) {
                return changelog.getIndexRecord(rev).getNodeId();
            }
        } catch (NumberFormatException ignored) {}

        List<byte[]> matches = changelog.getIndex().findByHexPrefix(revStr);
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new IOException("Ambiguous revision identifier: " + revStr);
        }
        return matches.get(0);
    }

    /**
     * Computes a Mercurial node ID: {@code sha1(min(p1, p2) + max(p1, p2) + content)}, where
     * {@code p1}/{@code p2} are lexicographically sorted (padded to 20 zero bytes when absent)
     * before hashing, matching real hg's {@code revlog.hash()}.
     *
     * @apiNote Used by {@code HisteditCommand} and {@code VerifyCommand} to recompute a
     *     changeset/manifest/file revision's node ID from its content and parents — e.g. to
     *     verify stored data matches its expected hash, or to predict the node ID a rewritten
     *     revision will get before it is actually written.
     * @param content the revision's raw (fulltext) content
     * @param p1 first parent's 20-byte node ID, or {@code null} for no first parent
     * @param p2 second parent's 20-byte node ID, or {@code null} for no second parent
     * @return the resulting 32-byte array whose first 20 bytes are the SHA-1 node ID (the
     *     remaining 12 bytes are zero-padded, matching the storage layout elsewhere in hg4j)
     */
    public static byte[] computeNodeId(byte[] content, byte[] p1, byte[] p2) {
        byte[] p1Node = new byte[20];
        if (p1 != null) {
            System.arraycopy(p1, 0, p1Node, 0, Math.min(p1.length, 20));
        }
        byte[] p2Node = new byte[20];
        if (p2 != null) {
            System.arraycopy(p2, 0, p2Node, 0, Math.min(p2.length, 20));
        }

        // Compare and sort lexicographically
        byte[] first = p1Node;
        byte[] second = p2Node;
        boolean swap = false;
        for (int i = 0; i < 20; i++) {
            int v1 = first[i] & 0xFF;
            int v2 = second[i] & 0xFF;
            if (v1 != v2) {
                if (v1 > v2) {
                    swap = true;
                }
                break;
            }
        }
        if (swap) {
            first = p2Node;
            second = p1Node;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(first);
            md.update(second);
            md.update(content);
            byte[] hash = md.digest();

            byte[] nodeId = new byte[32];
            System.arraycopy(hash, 0, nodeId, 0, 20);
            return nodeId;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 digest not available", e);
        }
    }

    /**
     * Real hg's {@code unbundlehash} wire-encoding optimization for {@code unbundle}'s {@code
     * heads} argument ({@code mercurial/wireprotov1peer.py}'s {@code unbundle()}):
     * <pre>
     * if heads != [b'force'] and self.capable(b'unbundlehash'):
     *     heads = [b'hashed', sha1(b''.join(sorted(heads))).digest()]
     * </pre>
     * When the server advertises {@code unbundlehash}, a SHA1 digest of the sorted, concatenated
     * raw (20-byte) head node ids is sent instead of the literal list — the server's own {@code
     * exchange.py} {@code check_heads()} accepts a push whose digest matches its actual current
     * heads exactly as if the literal list had been sent, so this is purely a wire-size
     * optimization, not a behavior change. Falls back to the literal {@code heads} list (returned
     * as-is) whenever the capability isn't present, {@code heads} is empty, or it's literally the
     * {@code ["force"]} sentinel (real hg never hashes a force-push) — transport-agnostic, shared
     * by both {@code HgRemoteClient} (HTTP) and {@code HgSshClient} (SSH).
     *
     * @param heads hex head node ids the caller believes are the remote's current heads
     * @param serverSupportsUnbundleHash whether the negotiated capabilities include {@code unbundlehash}
     * @return either {@code heads} unchanged, the single-element hex-encoded force sentinel, or
     *         the 2-element {@code [hashed-sentinel-hex, digest-hex]} wire tokens
     */
    public static List<String> computeUnbundleHeadsWireValue(List<String> heads, boolean serverSupportsUnbundleHash) {
        if (heads == null || heads.isEmpty()) {
            return heads == null ? List.of() : heads;
        }
        if (heads.size() == 1 && "force".equals(heads.get(0))) {
            // Real hg's own wireprotov1peer.py `unbundle()` runs the literal `[b'force']`
            // sentinel through `wireprototypes.encodelist()` exactly like a genuine head list --
            // `encodelist` is a blind `hex()` over every element, with NO special-casing for
            // `b'force'` -- so the wire value real hg actually sends is `hex(b'force')`
            // ("666f726365"), not the bare ASCII word. Sending the literal word "force" to a real
            // hg server instead breaks its `wireprototypes.decodelist()` (`bin("force")` -- 'o'/'r'
            // are not hex digits -- raises inside the server, surfacing to hg4j's client as a
            // garbled/empty framed response). The receiving side (Wire1Commands/HgLocalClient)
            // must recognize this SAME hex-encoded form -- see FORCE_SENTINEL_HEX there.
            return List.of(toHex("force".getBytes(StandardCharsets.US_ASCII)));
        }
        if (!serverSupportsUnbundleHash) {
            return heads;
        }
        try {
            List<byte[]> raw = new ArrayList<>();
            for (String h : heads) {
                raw.add(fromHex(h));
            }
            raw.sort(NodeIdUtil::compareBytesUnsigned);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (byte[] n : raw) {
                sha1.update(n);
            }
            String hashedSentinel = toHex("hashed".getBytes(StandardCharsets.US_ASCII));
            String digestHex = toHex(sha1.digest());
            return List.of(hashedSentinel, digestHex);
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            // heads weren't valid hex node ids, or SHA-1 is somehow unavailable -- fall back to
            // the always-correct literal list rather than fail the push over an optimization.
            return heads;
        }
    }

    private static int compareBytesUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = a[i] & 0xFF, bi = b[i] & 0xFF;
            if (ai != bi) {
                return ai - bi;
            }
        }
        return a.length - b.length;
    }
}
