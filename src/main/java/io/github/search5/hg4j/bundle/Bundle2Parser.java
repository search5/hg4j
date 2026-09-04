package io.github.search5.hg4j.bundle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.github.luben.zstd.ZstdInputStream;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Lightweight, production-grade parser for decoding the Mercurial bundle2 (HG20) container format.
 * Dynamically resolves stream-level compression (zlib deflate) and extracts the inner CHANGEGROUP payload.
 */
public class Bundle2Parser {
    private static final Logger LOGGER = Logger.getLogger(Bundle2Parser.class.getName());

    /**
     * Structure representing the result of bundle2 extraction.
     */
    public static class ExtractedBundle2 {
        public byte[] changegroupBytes;
        public String cgVersion = "01"; // Default to cg1 if not specified
        /** The wire part id (real hg's {@code partid}, a small sequential integer the SENDER
         * assigned) of the {@code CHANGEGROUP} part this was extracted from -- needed by a
         * server building a bundle2 reply (backlog item 26) to stamp the reply's {@code
         * reply:changegroup} part with the matching {@code in-reply-to} param real hg's own
         * {@code op.records.getreplies(cgpart.id)} keys off of. {@code -1} if not captured. */
        public int changegroupPartId = -1;
        /** Backlog item 38 ("PushRaced"-equivalent server-side race re-check): the raw 20-byte
         * head node ids from the incoming push's {@code check:heads} part, one per element -- real
         * hg's client ({@code exchange.py}'s {@code _pushb2ctxcheckheads}) embeds this whenever
         * the push isn't {@code --force} and has something to push, so the SERVER (this bundle2
         * envelope's receiver) can re-validate, after it has actually taken the store lock, that
         * its current heads still match what the client computed the push against ({@code
         * mercurial/bundle2_part_handlers.py}'s {@code handlecheckheads()}: {@code
         * sorted(heads) != sorted(op.repo.heads())} raises {@code error.PushRaced}). {@code null}
         * if no {@code check:heads} part was present (a {@code --force} push, or a push with
         * nothing new). */
        public List<byte[]> checkHeadsRaw;
    }

    /**
     * Parses a bundle2 input stream and extracts the inner changegroup bundle bytes.
     * Supports 'HG20' magic, zlib/gzip compression decoding, and CHANGEGROUP part payload assembly.
     *
     * @param in the raw input stream of the bundle2 data
     * @return the extracted changegroup bundle raw bytes (compatible with bundle1/changegroup parser)
     * @throws IOException if parsing fails or invalid bundle2 format is encountered
     */
    public static byte[] extractChangegroup(InputStream in) throws IOException {
        return extractChangegroupDetailed(in).changegroupBytes;
    }

    /**
     * Detailed bundle2 extraction that retrieves both the changegroup bytes and its version parameter.
     */
    public static ExtractedBundle2 extractChangegroupDetailed(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        
        // 1. Read Magic "HG20"
        byte[] magic = new byte[4];
        dis.readFully(magic);
        String magicStr = new String(magic, StandardCharsets.US_ASCII);
        if (!"HG20".equals(magicStr)) {
            throw new HgCorruptDataException("Unsupported bundle magic: " + magicStr + ". Expected HG20.");
        }

        // 2. Stream Level Parameters Size — 실제 스펙(mercurial/bundle2.py의
        // _fstreamparamsize = '>i')은 4바이트 부호 있는 정수다. 2바이트로 읽으면 실제
        // hg가 만든 번들(예: 기본 bzip2 압축이 적용된 `hg bundle` 출력)의 스트림 파라미터를
        // 잘못 파싱해 EOFException으로 깨진다(2026-09-01 발견·수정).
        int paramsSize = dis.readInt();
        String compression = null;
        if (paramsSize > 0) {
            byte[] paramBytes = new byte[paramsSize];
            dis.readFully(paramBytes);
            String params = new String(paramBytes, StandardCharsets.UTF_8);
            for (String param : params.split(" ")) {
                if (param.startsWith("Compression=")) {
                    compression = param.substring("Compression=".length());
                }
            }
        }

        // 3. Setup decompressor if needed
        InputStream payloadStream = dis;
        if (compression != null && !compression.isEmpty()) {
            if ("GZ".equalsIgnoreCase(compression)) {
                // HG20 GZ uses zlib deflate compression.
                payloadStream = new InflaterInputStream(dis);
            } else if ("BZ".equalsIgnoreCase(compression)) {
                // Bzip2 compression
                payloadStream = new BZip2CompressorInputStream(dis);
            } else if ("ZS".equalsIgnoreCase(compression)) {
                // Zstandard compression
                payloadStream = new ZstdInputStream(dis);
            } else {
                throw new HgCorruptDataException("Unsupported bundle2 compression: " + compression);
            }
        }

        DataInputStream pdis = new DataInputStream(payloadStream);
        ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
        ByteArrayOutputStream checkHeadsOut = new ByteArrayOutputStream();
        boolean sawCheckHeadsPart = false;
        String extractedVersion = "01";
        int changegroupPartId = -1;

        // 4. Parse Payload Parts
        while (true) {
            int partHeaderSize = pdis.readInt();
            if (partHeaderSize == 0) {
                // EOF of bundle2
                break;
            }

            // Read the header block
            byte[] headerBlock = new byte[partHeaderSize];
            pdis.readFully(headerBlock);
            
            // Parse part header
            int cursor = 0;
            int nameSize = headerBlock[cursor++] & 0xFF;
            String partName = new String(headerBlock, cursor, nameSize, StandardCharsets.US_ASCII);
            LOGGER.log(Level.FINE, "[DEBUG BUNDLE2] Parsed partName: ''{0}'', partHeaderSize: {1}, nameSize: {2}", new Object[]{partName, partHeaderSize, nameSize});
            cursor += nameSize;

            int partId = ((headerBlock[cursor] & 0xFF) << 24) | ((headerBlock[cursor + 1] & 0xFF) << 16)
                    | ((headerBlock[cursor + 2] & 0xFF) << 8) | (headerBlock[cursor + 3] & 0xFF);
            cursor += 4;

            // Part parameters counts
            int mandatoryCount = headerBlock[cursor++] & 0xFF;
            int advisoryCount = headerBlock[cursor++] & 0xFF;
            
            boolean isChangegroup = "CHANGEGROUP".equalsIgnoreCase(partName);
            if (isChangegroup) {
                changegroupPartId = partId;
            }
            // Backlog item 38: real hg's own part type name, verbatim -- see
            // mercurial/bundle2_part_handlers.py's `@parthandler(b'check:heads')`.
            boolean isCheckHeads = "check:heads".equalsIgnoreCase(partName);
            if (isCheckHeads) {
                sawCheckHeadsPart = true;
            }
            int paramCount = mandatoryCount + advisoryCount;

            // 실제 스펙(mercurial/bundle2.py): 파라미터는 "먼저 (keylen,vallen) 쌍
            // paramCount개를 전부 읽고, 그 다음에 실제 key/value 바이트들을 순서대로
            // 읽는" 구조다 — key/value를 매 파라미터마다 번갈아 읽는 구조가 아니다
            // (2026-09-01 발견·수정 — 이전 코드는 파라미터가 하나라도 있으면 실제 hg가
            // 만든 번들에서 ArrayIndexOutOfBoundsException으로 깨졌다).
            int[] keyLens = new int[paramCount];
            int[] valLens = new int[paramCount];
            for (int i = 0; i < paramCount; i++) {
                keyLens[i] = headerBlock[cursor++] & 0xFF;
                valLens[i] = headerBlock[cursor++] & 0xFF;
            }
            for (int i = 0; i < paramCount; i++) {
                String paramName = new String(headerBlock, cursor, keyLens[i], StandardCharsets.US_ASCII);
                cursor += keyLens[i];

                byte[] paramValBytes = new byte[valLens[i]];
                System.arraycopy(headerBlock, cursor, paramValBytes, 0, valLens[i]);
                cursor += valLens[i];

                if (isChangegroup && "version".equalsIgnoreCase(paramName)) {
                    extractedVersion = new String(paramValBytes, StandardCharsets.US_ASCII).trim();
                }
            }

            // 5. Read Part Payload Chunks
            while (true) {
                int chunkSize = pdis.readInt();
                if (chunkSize == 0) {
                    // End of this part's payload
                    break;
                }
                if (chunkSize == -1) {
                    // Interrupt (nested part starts)
                    throw new HgCorruptDataException("Nested stream interrupts are not supported in this lightweight parser.");
                }
                
                byte[] chunkData = new byte[chunkSize];
                pdis.readFully(chunkData);

                if (isChangegroup) {
                    cgOut.write(chunkData);
                } else if (isCheckHeads) {
                    checkHeadsOut.write(chunkData);
                }
            }
        }

        if (cgOut.size() == 0) {
            throw new HgCorruptDataException("No CHANGEGROUP part found in the bundle2 stream.");
        }

        ExtractedBundle2 result = new ExtractedBundle2();
        result.changegroupBytes = cgOut.toByteArray();
        result.cgVersion = extractedVersion;
        result.changegroupPartId = changegroupPartId;
        if (sawCheckHeadsPart) {
            // Real hg's own wire encoding (bundle2_part_handlers.py's handlecheckheads(): `h =
            // inpart.read(20); while len(h) == 20: heads.append(h)`) is just the raw 20-byte node
            // ids back to back, with no length prefix or separator.
            byte[] raw = checkHeadsOut.toByteArray();
            List<byte[]> heads = new ArrayList<>(raw.length / 20);
            for (int off = 0; off + 20 <= raw.length; off += 20) {
                byte[] node = new byte[20];
                System.arraycopy(raw, off, node, 0, 20);
                heads.add(node);
            }
            result.checkHeadsRaw = heads;
        }
        return result;
    }

    /**
     * Builds the wire value for the client's {@code bundlecaps} getbundle/pull request parameter
     * so that a real hg server actually negotiates bundle2 and picks a changegroup version from
     * {@code changegroupVersionsCsv} (e.g. {@code "01,02,03,04,05"}) — rather than always silently
     * falling back to legacy bundle1/cg1 no matter what versions are listed.
     *
     * <p><b>Real hg spec, verified against Mercurial 7.2.2 (2026-09-03) by directly capturing a
     * real {@code hg clone} HTTP request through a logging proxy</b>: the wire protocol's {@code
     * bundlecaps} argument is typed {@code scsv} ({@code wireprototypes.GETBUNDLE_ARGUMENTS}) —
     * the server splits its value on top-level commas into a set of tokens. {@code
     * exchange.bundle2requested()} only checks whether any token starts with {@code "HG2"}, but
     * the changegroup version LIST is read from a completely different place:  {@code
     * urlutil.b2_caps_from_bundle_caps()} looks only at tokens starting with the literal {@code
     * "bundle2="} prefix, percent-decodes the rest, and parses THAT as newline-separated {@code
     * capability=value1,value2,...} lines ({@code decode_b2_caps}) — the real hg client's own
     * captured request value looked like {@code HG20,bundle2=HG20%250Abookmarks%250Achangegroup
     * %253D01%252C02%252C03%250A...} (double percent-encoded: once by {@code urlreq.quote()}
     * around the blob itself, exactly like this method's {@link #pythonQuote}, and a second time
     * by whatever transport-level form/query encoding wraps the whole {@code bundlecaps} value —
     * for HTTP that second pass is already handled by {@code HgRemoteClient}'s existing {@code
     * URLEncoder.encode()} call, so callers must NOT double-encode here).
     *
     * <p>A flat top-level {@code "changegroup=01,02,03"} token (what hg4j used prior to
     * 2026-09-03) is invisible to {@code b2_caps_from_bundle_caps()} — {@code
     * getbundlechunks()} then finds {@code usebundle2=True} (from the bare {@code "HG20"} token)
     * but an empty {@code b2caps}, and in practice real hg's {@code hg serve} was observed to fall
     * all the way back to a legacy, unversioned bundle1 changegroup stream instead — meaning no
     * hg4j client has ever actually been able to negotiate cg2/cg3 (let alone cg4/cg5) with a real
     * hg HTTP server before this fix, regardless of what version list was advertised.
     *
     * @return a comma-joined pair of top-level bundlecaps tokens: {@code "HG20"} (satisfies {@code
     *         bundle2requested()}) and {@code "bundle2=<percent-encoded blob>"} (carries the
     *         changegroup version list real hg's {@code version = max(intersection)} rule reads).
     *         Callers still need to append any further tokens (e.g. {@code "compression=..."})
     *         themselves, joined with a further comma — the overall {@code bundlecaps} wire value
     *         is comma-separated at the top level, NOT space-separated.
     */
    public static String buildChangegroupBundleCaps(String changegroupVersionsCsv) {
        return "HG20," + buildBundle2CapsToken(changegroupVersionsCsv);
    }

    /**
     * Like {@link #buildChangegroupBundleCaps} but returns only the {@code "bundle2=<blob>"}
     * token by itself — for a caller (e.g. {@code FetchCommand}) that builds its {@code
     * bundleCaps} as a {@code List<String>} of separate tokens and wants to add the bare {@code
     * "HG20"} token as its own list element (matching real hg's own client's exact 2-token shape,
     * captured 2026-09-03) rather than pre-joined into one string.
     */
    public static String buildBundle2CapsToken(String changegroupVersionsCsv) {
        String blob = "HG20\nchangegroup=" + changegroupVersionsCsv;
        return "bundle2=" + pythonQuote(blob);
    }

    /**
     * Server-side mirror of {@link #buildBundle2CapsToken}/real hg's {@code
     * urlutil.b2_caps_from_bundle_caps()} + {@code decode_b2_caps()} — decodes the changegroup
     * version list a CLIENT advertised in its own {@code bundlecaps} request argument (backlog
     * item 26: {@code HgLocalClient#getBundle} needs this to actually negotiate a changegroup
     * version instead of hardcoding cg1).
     *
     * <p>Real hg spec (verified against Mercurial 7.2.2, 2026-09-04, by instrumenting {@code
     * Wire1Commands.getbundle} and reading a real {@code hg clone}'s actual request): {@code
     * bundlecaps} is a top-level-comma-separated set of tokens (already split by the caller, one
     * token per list element here); the ONE token starting with the literal {@code "bundle2="}
     * prefix carries a percent-encoded blob of newline-separated {@code capability=v1,v2,...}
     * lines (same shape {@link #buildBundle2CapsToken} produces), and the {@code changegroup} line
     * within it (if present) lists the versions the CLIENT is prepared to accept as incoming data
     * — this is the list real hg's own {@code exchange.py}'s {@code
     * _getbundlechangegrouppart(version = max(cgversions ∩ supportedoutgoingversions(repo)))} rule
     * reads, symmetric with how a real hg SERVER decides what to send on a plain pull (the
     * server's OWN advertised {@code changegroup=} capability value is never consulted for this —
     * it's only used for the opposite, push/unbundle direction). A missing {@code bundle2=} token,
     * or one whose blob has no {@code changegroup} line, means "no explicit request" (real hg
     * defaults {@code version} to {@code "01"} in that case) — this method returns an empty list
     * for both, letting the caller apply that same default.
     *
     * @return the requested version tokens (e.g. {@code ["01","02","03"]}) in the order they were
     *         listed, or an empty list if no {@code changegroup=} entry was found anywhere
     */
    public static List<String> decodeChangegroupVersions(List<String> bundleCaps) {
        if (bundleCaps == null) {
            return List.of();
        }
        for (String cap : bundleCaps) {
            if (cap == null || !cap.startsWith("bundle2=")) {
                continue;
            }
            String blob = pythonUnquote(cap.substring("bundle2=".length()));
            for (String line : blob.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = pythonUnquote(line.substring(0, eq));
                if (!"changegroup".equals(key)) {
                    continue;
                }
                String val = line.substring(eq + 1);
                List<String> versions = new java.util.ArrayList<>();
                for (String v : val.split(",", -1)) {
                    if (!v.isEmpty()) {
                        versions.add(pythonUnquote(v));
                    }
                }
                return versions;
            }
        }
        return List.of();
    }

    /**
     * Real hg spec (see {@link #decodeChangegroupVersions}): {@code
     * exchange.bundle2requested(bundlecaps)} is {@code any(cap.startswith("HG2") for cap in
     * bundlecaps)} — whether the response must be wrapped in a bundle2 (HG20) container at all
     * (independent of which changegroup version ends up chosen: with no such token, real hg
     * hardcodes cg1 and never wraps the response in bundle2, regardless of any {@code
     * changegroup=} list a caller might have also sent).
     */
    public static boolean requestsBundle2(List<String> bundleCaps) {
        if (bundleCaps == null) {
            return false;
        }
        for (String cap : bundleCaps) {
            if (cap != null && cap.startsWith("HG2")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Percent-decodes exactly like Python's {@code urllib.parse.unquote(s)} on an ASCII-range
     * blob (the inverse of {@link #pythonQuote}): every {@code %XX} escape becomes the raw byte;
     * everything else passes through unchanged. Deliberately NOT {@code java.net.URLDecoder} —
     * that treats {@code +} as an encoded space (form-encoding semantics), which is wrong for a
     * blob {@link #pythonQuote} produced (space is escaped there as {@code %20}, and a literal
     * {@code +} — none appear in practice, but correctness shouldn't depend on that — must stay
     * a literal {@code +}).
     */
    private static String pythonUnquote(String s) {
        byte[] chars = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(chars.length);
        int i = 0;
        while (i < chars.length) {
            int c = chars[i] & 0xFF;
            if (c == '%' && i + 2 < chars.length) {
                int hi = Character.digit((char) chars[i + 1], 16);
                int lo = Character.digit((char) chars[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out.write(c);
            i++;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Percent-encodes exactly like Python's {@code urllib.parse.quote(s)} (default {@code
     * safe='/'}): every byte except {@code A-Za-z0-9_.~-} and {@code /} becomes {@code %XX}
     * (uppercase hex). Deliberately NOT {@code java.net.URLEncoder} — that encodes space as
     * {@code +} (form-encoding, wrong here) and escapes {@code /} (also wrong here).
     */
    private static String pythonQuote(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '~' || c == '-' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /**
     * Wraps raw changegroup bytes (as produced by {@link ChangegroupParser#writeBundle}) into a
     * minimal, uncompressed HG20/bundle2 envelope with a single mandatory {@code CHANGEGROUP}
     * part carrying the given {@code version} param — exactly what real hg's {@code unbundle}
     * command needs to apply a cg4/cg5 bundle (real hg's own {@code bundle2_part_handlers.py}
     * {@code handlechangegroup} only actually requires the {@code version} param; {@code
     * nbchanges}/{@code treemanifest}/{@code targetphase}/sidedata params are all optional).
     *
     * <p>Byte-for-byte structure verified against a real bundle produced by Mercurial 7.2.2's own
     * {@code mercurial.bundle2.bundle20} (2026-09-03): {@code "HG20"} + stream-params-size(int32,
     * 0 here) + partHeaderSize(int32) + [nameSize(1B) name partId(4B) mandatoryCount(1B)
     * advisoryCount(1B) (keyLen,valLen) pairs... key bytes... value bytes...] + payload
     * chunkSize(int32, payload length WITHOUT a self-inclusive +4 — unlike the inner changegroup
     * chunk framing) + payload bytes + terminal chunk(int32 0) + final partHeaderSize(int32 0).
     */
    public static byte[] wrapChangegroupInBundle2(byte[] changegroupBytes, String version) throws IOException {
        byte[] partName = "CHANGEGROUP".getBytes(StandardCharsets.US_ASCII);
        byte[] paramKey = "version".getBytes(StandardCharsets.US_ASCII);
        byte[] paramVal = version.getBytes(StandardCharsets.US_ASCII);

        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
        header.write(partName.length);
        header.write(partName);
        header.write(new byte[]{0, 0, 0, 0}); // part id (unused by a standalone file-level unbundle)
        header.write(1); // mandatoryCount
        header.write(0); // advisoryCount
        header.write(paramKey.length);
        header.write(paramVal.length);
        header.write(paramKey);
        header.write(paramVal);
        byte[] headerBytes = header.toByteArray();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        writeInt32(out, 0); // stream params size (no compression)
        writeInt32(out, headerBytes.length);
        out.write(headerBytes);
        writeInt32(out, changegroupBytes.length); // payload chunk size (NOT self-inclusive)
        out.write(changegroupBytes);
        writeInt32(out, 0); // end of this part's payload
        writeInt32(out, 0); // end of bundle2 stream (no more parts)
        return out.toByteArray();
    }

    private static void writeInt32(java.io.OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Builds a minimal, uncompressed HG20/bundle2 "reply" envelope containing a single {@code
     * reply:changegroup} part -- what a server (backlog item 26: {@code Wire1Commands#unbundle})
     * must send back for a push whose request body was itself a bundle2 envelope. Real hg's own
     * client only sends a bundle2-framed push (and therefore only accepts/expects a bundle2-framed
     * REPLY) once the server has advertised the {@code bundle2=} capability at all -- turning that
     * capability on for {@link #decodeChangegroupVersions}'s sake (getbundle version negotiation)
     * unavoidably also switches every real-hg-client push onto this path (verified 2026-09-04:
     * before this method existed, a real {@code hg push} against an hg4j server that had just
     * started advertising {@code bundle2=} failed client-side with "abort: not a Mercurial bundle"
     * trying to parse hg4j's old plain-text {@code "1\n<status>"} reply as a bundle2 stream).
     *
     * <p>Mirrors real hg's own {@code bundle2_part_handlers.handlechangegroup} reply exactly
     * (verified against Mercurial 7.2.2 source): a {@code reply:changegroup} part with two
     * advisory params, {@code in-reply-to} (the wire part id of the CLIENT's own {@code
     * changegroup} request part -- see {@link ExtractedBundle2#changegroupPartId}) and {@code
     * return} (an integer; real hg's own client reads this into {@code pushop.cgresult} --
     * {@code 0} means "nothing added" and makes {@code hg push} exit with "nothing to push",
     * any nonzero value means "something landed" and is otherwise not interpreted numerically).
     */
    public static byte[] buildChangegroupReplyBundle2(int inReplyToPartId, int returnValue) throws IOException {
        byte[] key1 = "in-reply-to".getBytes(StandardCharsets.US_ASCII);
        byte[] val1 = Integer.toString(inReplyToPartId).getBytes(StandardCharsets.US_ASCII);
        byte[] key2 = "return".getBytes(StandardCharsets.US_ASCII);
        byte[] val2 = Integer.toString(returnValue).getBytes(StandardCharsets.US_ASCII);
        return wrapZeroPayloadPart("reply:changegroup", 0, new byte[][]{key1, key2}, new byte[][]{val1, val2});
    }

    /**
     * Builds a minimal, uncompressed HG20/bundle2 stream with NO parts at all -- a trivially
     * valid empty reply, used when an incoming bundle2 push carried no {@code changegroup} part
     * to reply to at all (e.g. a bookmark-only push with no new changesets).
     */
    public static byte[] buildEmptyBundle2Reply() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        writeInt32(out, 0); // stream params size
        writeInt32(out, 0); // no parts at all
        return out.toByteArray();
    }

    /**
     * Builds a minimal, uncompressed HG20/bundle2 stream carrying a single {@code error:abort}
     * part with a mandatory {@code message} param -- exactly real hg's own {@code
     * wireprotov1server.unbundle}'s exception-to-wire-response conversion for an {@code
     * error.Abort} raised while applying a bundle2 push (verified against Mercurial 7.2.2
     * source, 2026-09-04). Real hg's client-side {@code bundle2.processbundle} recognizes this
     * part type and raises {@code AbortFromPart(message)}, which {@code exchange._pushbundle2}
     * reports as {@code "remote: <message>"} -- the bundle2-era equivalent of this server's
     * legacy {@code "0\n<message>"} plain-text error response.
     */
    public static byte[] buildErrorAbortBundle2(String message) throws IOException {
        byte[] key = "message".getBytes(StandardCharsets.US_ASCII);
        byte[] val = message.getBytes(StandardCharsets.UTF_8);
        return wrapZeroPayloadPart("error:abort", 1, new byte[][]{key}, new byte[][]{val});
    }

    /**
     * Shared builder for a single-part, zero-payload-chunk bundle2 stream (used by the reply/
     * error helpers above) -- header layout matches {@link #wrapChangegroupInBundle2} exactly,
     * minus the payload chunk itself (a params-only part has none: real hg's own wire format
     * still requires the payload chunk stream to be present but lets it be immediately
     * zero-terminated, i.e. "no payload chunks at all").
     *
     * @param mandatoryCount how many of the leading entries in {@code paramKeys}/{@code
     *     paramVals} are mandatory (the rest, i.e. the trailing entries, are advisory) --
     *     matches real hg's own header layout, where mandatory params are always listed first
     */
    private static byte[] wrapZeroPayloadPart(String partTypeName, int mandatoryCount,
                                               byte[][] paramKeys, byte[][] paramVals) throws IOException {
        byte[] partName = partTypeName.getBytes(StandardCharsets.US_ASCII);
        int advisoryCount = paramKeys.length - mandatoryCount;

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(partName.length);
        header.write(partName);
        header.write(new byte[]{0, 0, 0, 0}); // this reply part's own id -- unused by a client, which only reads the "in-reply-to" PARAM value
        header.write(mandatoryCount);
        header.write(advisoryCount);
        for (int i = 0; i < paramKeys.length; i++) {
            header.write(paramKeys[i].length);
            header.write(paramVals[i].length);
        }
        for (int i = 0; i < paramKeys.length; i++) {
            header.write(paramKeys[i]);
            header.write(paramVals[i]);
        }
        byte[] headerBytes = header.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        writeInt32(out, 0); // stream params size (no compression)
        writeInt32(out, headerBytes.length);
        out.write(headerBytes);
        writeInt32(out, 0); // no payload chunks at all
        writeInt32(out, 0); // end of bundle2 stream (no more parts)
        return out.toByteArray();
    }
}
