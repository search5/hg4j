package io.github.search5.hg4j.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import io.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * Component dedicated to revlog data compression and decompression (SRP separation).
 *
 * <p>Supported formats, matching real Mercurial's revlog compression-header bytes
 * (see {@code mercurial/interfaces/compression.py} and {@code mercurial/revlog.py}
 * {@code Revlog.compress()}/{@code decompress()}):
 * <ul>
 *   <li><b>'x' (0x78)</b> — zlib deflate compression (REVLOG_COMP_ZLIB). The header byte
 *       doubles as the zlib stream's own CMF byte, so the hunk itself IS the zlib stream.</li>
 *   <li><b>0x00</b> — REVLOG_COMP_NONE: raw data, returned verbatim (including the leading
 *       0x00 byte). Used only when the uncompressed payload itself happens to start with
 *       0x00 and compression did not help.</li>
 *   <li><b>0x28</b> — REVLOG_COMP_ZSTD: Zstd compression. 0x28 is also the first byte of
 *       Zstd's own frame magic number, so the hunk itself IS the Zstd frame.</li>
 *   <li><b>'u'</b> — uncompressed (includes prefix byte, stripped on decompress)</li>
 *   <li><b>Other</b> — raw fallback (no uncompressed header)</li>
 * </ul>
 *
 * <p>This class is stateless and all methods are static.
 */
public final class DeltaCodec {

    private DeltaCodec() {}

    /**
     * Compresses the given data.
     *
     * @param data The raw data to compress
     * @return The compressed hunk byte array (default zlib deflate)
     */
    public static byte[] compress(byte[] data) throws IOException {
        return compress(data, false);
    }

    /**
     * Compresses the given data. Uses Zstd compression if useZstd is true.
     * If the compressed size is smaller than the original, returns the compressed result;
     * otherwise, returns the data in an uncompressed format with a {@code 'u'} prefix byte.
     *
     * @param data The raw data to compress
     * @param useZstd Whether to use Zstd compression
     * @return The compressed hunk byte array
     */
    public static byte[] compress(byte[] data, boolean useZstd) throws IOException {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        if (useZstd) {
            byte[] compressed = Zstd.compress(data);
            if (compressed.length < data.length) {
                return compressed;
            } else {
                // Uncompressed format: 'u' + raw data
                byte[] uncompressed = new byte[data.length + 1];
                uncompressed[0] = 'u';
                System.arraycopy(data, 0, uncompressed, 1, data.length);
                return uncompressed;
            }
        }

        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[1024];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                baos.write(buf, 0, count);
            }
        } finally {
            deflater.end();
        }
        byte[] compressed = baos.toByteArray();

        if (compressed.length < data.length) {
            return compressed;
        } else {
            // Uncompressed format: 'u' + raw data
            byte[] uncompressed = new byte[data.length + 1];
            uncompressed[0] = 'u';
            System.arraycopy(data, 0, uncompressed, 1, data.length);
            return uncompressed;
        }
    }

    /**
     * Decompresses the compressed hunk.
     *
     * @param hunk        The compressed byte array (raw hunk read from revlog)
     * @param uncompLen   The expected uncompressed size (used as a hint)
     * @return The decompressed raw data
     * @throws IOException If decompression fails
     */
    public static byte[] decompress(byte[] hunk, int uncompLen) throws IOException {
        if (hunk == null || hunk.length == 0 || uncompLen == 0) {
            return new byte[0];
        }

        byte type = hunk[0];

        if (type == 'x') {
            return decompressZlib(hunk, uncompLen);
        } else if (type == 0x00) {
            // REVLOG_COMP_NONE: raw data, returned verbatim (including this leading 0x00
            // byte). Real hg never prepends 0x00 in front of an actual Zstd frame or any
            // other compressed payload — see mercurial/revlog.py Revlog.decompress():
            // `elif t == b'\0': return data`.
            return hunk;
        } else if (type == 0x28) {
            // REVLOG_COMP_ZSTD: 0x28 is also the first byte of Zstd's own frame magic
            // number, so the hunk itself is the Zstd frame — decode it directly.
            return decompressZstd(hunk, uncompLen);
        } else if (type == 'u') {
            // The actual data follows the 'u' prefix byte
            return Arrays.copyOfRange(hunk, 1, hunk.length);
        } else {
            // raw fallback - no header, return the entire array
            return hunk;
        }
    }

    /**
     * Decompresses a Zstd-compressed revlog hunk (header byte {@code 0x28}).
     *
     * <p>2026-09-05 (backlog #39 wave 5, found independently by two parallel agents working
     * {@code GcCommand} and {@code VerifyCommand}'s requirement-matrix expansion): {@code
     * uncompLen} (the index record's {@code rawsize} field) is only a HINT, and for a delta
     * revision it is the WRONG size to trust blindly -- per the revlog format, that field always
     * holds the fully-reconstructed FULLTEXT's length (what this revision decodes to after the
     * whole delta chain is applied), not the length of THIS hunk's own decompressed bytes. A
     * genuine delta revision (real hg's own {@code mdiff}-style hunk encoding: a sequence of
     * {@code [start][end][length][data]} records describing what changed) decompresses to a
     * payload that is normally much SMALLER than the fulltext it patches -- confirmed live via a
     * `hg-rust-7.2.4` container (a 154-byte manifest fulltext's rev-1 delta decompressed to well
     * under 154 bytes, and independently cross-checked by hand-decompressing the same hunk bytes
     * with Python's own {@code zstandard} library). Blindly allocating {@code new byte[uncompLen]}
     * and handing back that full-sized array regardless of how many bytes zstd actually wrote into
     * it silently appended trailing zero-byte padding after the real delta data -- {@link
     * DeltaEngine#applyDelta} then parsed those zero bytes as a bogus extra {@code
     * start=0,end=0,length=0} hunk and rejected it with exactly the {@code "Invalid delta hunk
     * offsets: start=0, end=0"} symptom this fix resolves. Fixed by trusting the zstd frame's OWN
     * embedded content-size header ({@link Zstd#getFrameContentSize(byte[])} -- every hunk real hg
     * writes embeds this, the same source of truth {@code Revlog#decompressSidedataChunk} already
     * relies on for an analogous reason) as the destination buffer size instead, falling back to
     * {@code uncompLen} only when the frame legitimately declares no content size (the zstd-jni
     * sentinel is negative for "unknown"/"error", hence the {@code >= 0} check rather than
     * {@code > 0} -- a genuinely empty-content frame is a valid, if unusual, size of zero) -- and,
     * belt-and-braces, always trimming the returned array down to zstd's own reported
     * actual-bytes-written count afterward regardless of which size was used to allocate it.
     *
     * <p>zstd-jni's {@code Zstd.decompress} can either return an error code (testable via
     * {@link Zstd#isError(long)}) or throw an unchecked {@link ZstdException} for malformed
     * or truncated input, depending on where the failure occurs. Both are normalized here into
     * {@link HgCorruptDataException}, matching how {@link #decompressZlib} reports zlib errors.
     *
     * <p>Backlog #39 (2026-09-05): {@code uncompLen} is NOT a reliable destination-buffer size for
     * a DELTA-encoded revision (the common case for any generaldelta v1 revlog -- manifests and
     * filelogs are delta-chained by default, real hg only ever writes a full/fulltext hunk for the
     * chain's own base revision). The revlog index's {@code uncompressed_len} field always records
     * the length of this revision's fully-RECONSTRUCTED text (after the whole delta chain is
     * applied), never the length of what is actually stored (and therefore decompressed) in THIS
     * hunk -- for a delta revision, what's actually zstd-compressed here is the bdiff patch itself
     * (a 12-byte-per-hunk header + replacement bytes), whose own byte length is essentially
     * unrelated to the final fulltext size. Passing the fulltext size as the destination buffer
     * size crashed zstd-jni with "Destination buffer is too small" on perfectly valid,
     * real-hg-written delta revisions -- caught 2026-09-05 by
     * RequirementMatrixDiffDockerRoundTripTest (whose Docker-container hg CLI uses real zstd
     * compression with no forced zlib override, unlike every native-side {@code
     * RequirementMatrix*CoreRoundTripTest}, which forces zlib and therefore never exercised this
     * exact zstd+delta combination; hg4j's own writers also never delta-encode on the way out, so
     * no hg4j-authored fixture had ever hit this either). Real hg's own zstd decompressor (a
     * streaming {@code zstd.ZstdDecompressor().decompressobj()}) never needs to know the output
     * size upfront at all; the fix here instead reads the size zstd's own frame header embeds
     * (present for any frame written by a one-shot {@code compress()} call, which is how every
     * revlog zstd frame -- delta or full -- is written) via {@link Zstd#getFrameContentSize(byte[])},
     * exactly like {@link Revlog#getSidedata(int)}'s own COMP_MODE_DEFAULT branch already does for
     * the identical reason. Falls back to the caller-supplied {@code uncompLen} only on the
     * defensive/unexpected case where the frame doesn't declare a content size at all.
     */
    private static byte[] decompressZstd(byte[] hunk, int uncompLen) throws HgCorruptDataException {
        long frameSize = Zstd.getFrameContentSize(hunk);
        int destSize = frameSize >= 0 ? (int) frameSize : uncompLen;
        byte[] dest = new byte[destSize];
        try {
            long result = Zstd.decompress(dest, hunk);
            if (Zstd.isError(result)) {
                throw new HgCorruptDataException("Failed to decompress zstd revlog hunk: " + Zstd.getErrorName(result));
            }
            if (result != dest.length) {
                // Defensive: even with a frame-size-derived destination, truncate to exactly
                // what zstd reports it wrote rather than trusting either size blindly.
                dest = Arrays.copyOf(dest, (int) result);
            }
        } catch (ZstdException e) {
            throw new HgCorruptDataException("Failed to decompress zstd revlog hunk", e);
        }
        return dest;
    }

    /**
     * Decompresses the zlib stream.
     * Automatically detects Mercurial-specific offset variations (presence of 'x' prefix byte).
     */
    private static byte[] decompressZlib(byte[] hunk, int uncompLen) throws IOException {
        Inflater inflater = new Inflater();

        // Automatically detect zlib header position:
        // 1) Whether the zlib header starts from index 0
        // 2) Whether the zlib header starts from index 1 (when prepended with an 'x' prefix byte)
        boolean zlibFromStart = false;
        boolean zlibFromIndex1 = false;

        if (hunk.length >= 2) {
            int cmf = hunk[0] & 0xFF;
            int flg = hunk[1] & 0xFF;
            if ((cmf * 256 + flg) % 31 == 0 && (cmf & 0x0F) == 8) {
                zlibFromStart = true;
            }
        }
        if (!zlibFromStart && hunk.length >= 3) {
            int cmf = hunk[1] & 0xFF;
            int flg = hunk[2] & 0xFF;
            if ((cmf * 256 + flg) % 31 == 0 && (cmf & 0x0F) == 8) {
                zlibFromIndex1 = true;
            }
        }

        if (zlibFromStart) {
            inflater.setInput(hunk, 0, hunk.length);
        } else if (zlibFromIndex1) {
            inflater.setInput(hunk, 1, hunk.length - 1);
        } else if (hunk.length > 1 && hunk[1] == 'x') {
            inflater.setInput(hunk, 1, hunk.length - 1);
        } else {
            inflater.setInput(hunk, 0, hunk.length);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(uncompLen, 64));
        byte[] buf = new byte[1024];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0 && inflater.needsInput()) {
                    // Ran out of input before the stream (and its trailer) finished decoding:
                    // an incomplete/truncated zlib stream. Real hg's zlib.decompress() raises
                    // zlib.error("Error -5 ... incomplete or truncated stream") in this case
                    // rather than silently returning a partial result, so mirror that here.
                    throw new HgCorruptDataException("Truncated or incomplete zlib revlog hunk");
                }
                out.write(buf, 0, count);
            }
        } catch (DataFormatException e) {
            throw new HgCorruptDataException("Failed to decompress zlib revlog hunk", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
