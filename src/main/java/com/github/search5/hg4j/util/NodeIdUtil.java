package com.github.search5.hg4j.util;
import com.github.search5.hg4j.storage.Revlog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;


/**
 * Common utility methods for handling Mercurial NodeIDs and Hexadecimal representations.
 */
public final class NodeIdUtil {

    private NodeIdUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Converts a byte array to its hexadecimal String representation.
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
                    // 실제 스펙: 3글자든(aux/con/prn/nul) 4글자(com1..9/lpt1..9)든 항상
                    // 세 번째 문자(인덱스 2)만 이스케이프한다 — 4글자 이름에서 끝의 숫자를
                    // 이스케이프하는 것이 아니다.
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
}
