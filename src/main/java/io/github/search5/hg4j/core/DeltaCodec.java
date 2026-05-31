package io.github.search5.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import com.github.luben.zstd.Zstd;

/**
 * Component dedicated to revlog data compression and decompression (SRP separation).
 *
 * <p>Supported formats:
 * <ul>
 *   <li><b>'x' (0x78)</b> — zlib deflate compression</li>
 *   <li><b>'u'</b> — uncompressed (includes prefix byte)</li>
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

        if (type == 'x' || type == (byte) 0x78) {
            return decompressZlib(hunk, uncompLen);
        } else if (type == 0x00 && hunk.length >= 5 && hunk[1] == 0x28 && hunk[2] == (byte) 0xB5 && hunk[3] == 0x2F && hunk[4] == (byte) 0xFD) {
            // Mercurial V2/Zstd standard: 0x00 prefix + Zstd magic (28 B5 2F FD)
            byte[] dest = new byte[uncompLen];
            byte[] rawZstd = Arrays.copyOfRange(hunk, 1, hunk.length);
            Zstd.decompress(dest, rawZstd);
            return dest;
        } else if (hunk.length >= 4 && hunk[0] == 0x28 && hunk[1] == (byte) 0xB5 && hunk[2] == 0x2F && hunk[3] == (byte) 0xFD) { // Zstd magic raw fallback
            byte[] dest = new byte[uncompLen];
            Zstd.decompress(dest, hunk);
            return dest;
        } else if (type == 'u') {
            // The actual data follows the 'u' prefix byte
            return Arrays.copyOfRange(hunk, 1, hunk.length);
        } else {
            // raw fallback - no header, return the entire array
            return hunk;
        }
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
                    break;
                }
                out.write(buf, 0, count);
            }
        } catch (DataFormatException e) {
            throw new io.github.search5.hg4j.errors.HgCorruptDataException("Failed to decompress zlib revlog hunk", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
