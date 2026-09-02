package com.github.search5.hg4j.bundle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.github.luben.zstd.ZstdInputStream;
import com.github.search5.hg4j.errors.HgCorruptDataException;
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
        String extractedVersion = "01";

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
            
            // Part ID (4 bytes) - Skip
            cursor += 4;

            // Part parameters counts
            int mandatoryCount = headerBlock[cursor++] & 0xFF;
            int advisoryCount = headerBlock[cursor++] & 0xFF;
            
            boolean isChangegroup = "CHANGEGROUP".equalsIgnoreCase(partName);
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
                }
            }
        }

        if (cgOut.size() == 0) {
            throw new HgCorruptDataException("No CHANGEGROUP part found in the bundle2 stream.");
        }

        ExtractedBundle2 result = new ExtractedBundle2();
        result.changegroupBytes = cgOut.toByteArray();
        result.cgVersion = extractedVersion;
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
}
