package io.github.search5.hg4j.gpg;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts the OpenPGP key blocks from a GnuPG 2.1+ keybox file ({@code pubring.kbx}).
 *
 * <p>Bouncy Castle cannot read this format: handed a keybox it returns an EMPTY key ring without any
 * error (verified against BC 1.78), which would make every signature look like it came from an unknown
 * key. So the container is parsed here and only the contained plain OpenPGP key blocks are passed on.</p>
 *
 * <h2>Layout (verified byte by byte against files written by gpg 2.4.8)</h2>
 * A keybox is a sequence of blobs. Every blob starts with {@code u32 length} (big endian, including these
 * four bytes) and {@code u8 type}.
 * <ul>
 *   <li>type 1, header blob, must be first: {@code u8 version, u16 flags, "KBXf"} at offsets 5, 6, 8.</li>
 *   <li>type 2, OpenPGP blob: {@code u8 version, u16 flags} at offsets 5, 6, then {@code u32 keyblock
 *       offset} at 8 and {@code u32 keyblock length} at 12. The offset is relative to the start of the
 *       blob; the key block is a plain OpenPGP packet sequence (public key, user ids, signatures,
 *       subkeys).</li>
 *   <li>type 0 (empty) and type 3 (X.509) are skipped; they hold no OpenPGP key.</li>
 * </ul>
 * The per-blob checksum and the key info table are not needed to read the key block and are not checked.
 */
final class KeyboxReader {

    private static final int HEADER_BLOB = 1;
    private static final int OPENPGP_BLOB = 2;
    private static final int X509_BLOB = 3;

    private KeyboxReader() {
    }

    /** The blocks found plus what was deliberately skipped, so the caller can report it. */
    record Contents(List<byte[]> keyBlocks, int skippedX509Blobs, int skippedUnknownBlobs) {
    }

    static boolean looksLikeKeybox(byte[] data) {
        return data.length >= 12 && data[4] == HEADER_BLOB
                && Arrays.equals(Arrays.copyOfRange(data, 8, 12), "KBXf".getBytes(StandardCharsets.US_ASCII));
    }

    static Contents read(byte[] data, String name) throws HgSigFormatException {
        if (!looksLikeKeybox(data)) {
            throw new HgSigFormatException(name + " is not a GnuPG keybox (no 'KBXf' header blob)");
        }
        ByteBuffer buf = ByteBuffer.wrap(data); // big endian by default
        List<byte[]> blocks = new ArrayList<>();
        int x509 = 0;
        int unknown = 0;
        int pos = 0;
        int index = 0;
        while (pos < data.length) {
            if (data.length - pos < 5) {
                throw new HgSigFormatException(name + ": truncated blob header at offset " + pos);
            }
            long length = buf.getInt(pos) & 0xFFFFFFFFL;
            int type = data[pos + 4] & 0xFF;
            if (length < 5 || pos + length > data.length) {
                throw new HgSigFormatException(name + ": blob #" + index + " at offset " + pos
                        + " has an impossible length " + length + " (file size " + data.length + ")");
            }
            int blobLength = (int) length;
            switch (type) {
                case OPENPGP_BLOB -> blocks.add(keyBlock(data, buf, pos, blobLength, name, index));
                case X509_BLOB -> x509++;
                case 0, HEADER_BLOB -> { /* empty or header: nothing to read */ }
                default -> unknown++;
            }
            pos += blobLength;
            index++;
        }
        return new Contents(blocks, x509, unknown);
    }

    private static byte[] keyBlock(byte[] data, ByteBuffer buf, int blobPos, int blobLength, String name, int index)
            throws HgSigFormatException {
        if (blobLength < 20) {
            throw new HgSigFormatException(name + ": OpenPGP blob #" + index + " is too short (" + blobLength + ")");
        }
        long offset = buf.getInt(blobPos + 8) & 0xFFFFFFFFL;
        long length = buf.getInt(blobPos + 12) & 0xFFFFFFFFL;
        if (offset < 20 || offset + length > blobLength) {
            throw new HgSigFormatException(name + ": OpenPGP blob #" + index + " has a key block (offset " + offset
                    + ", length " + length + ") outside the blob (length " + blobLength + ")");
        }
        int start = blobPos + (int) offset;
        return Arrays.copyOfRange(data, start, start + (int) length);
    }
}
