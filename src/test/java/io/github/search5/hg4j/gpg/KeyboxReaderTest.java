package io.github.search5.hg4j.gpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Corruption handling of {@link KeyboxReader}. The layout itself is verified against real gpg-written
 * files in {@code HgSigsGpgInteropTest}; here only the guards are exercised, with hand-built blobs.
 */
public class KeyboxReaderTest {

    /** A header blob exactly as gpg writes it: length 32, type 1, version 1, flags 2, "KBXf", zero padding. */
    private static byte[] header() {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(32).put((byte) 1).put((byte) 1).putShort((short) 2).put("KBXf".getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }

    private static byte[] openPgpBlob(int declaredLength, int keyBlockOffset, int keyBlockLength, byte[] payload) {
        // the array is always as long as it really is; declaredLength is only what the blob CLAIMS
        ByteBuffer b = ByteBuffer.allocate(20 + payload.length);
        b.putInt(declaredLength).put((byte) 2).put((byte) 1).putShort((short) 0)
                .putInt(keyBlockOffset).putInt(keyBlockLength).putShort((short) 1).putShort((short) 28);
        b.position(20);
        b.put(payload);
        return b.array();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        ByteBuffer b = ByteBuffer.allocate(n);
        for (byte[] p : parts) {
            b.put(p);
        }
        return b.array();
    }

    @Test
    @DisplayName("헤더 blob 만 있는 키박스는 키 0개이고 오류가 아니다")
    void headerOnly_isEmpty() throws Exception {
        KeyboxReader.Contents c = KeyboxReader.read(header(), "t.kbx");
        assertTrue(c.keyBlocks().isEmpty());
    }

    @Test
    @DisplayName("OpenPGP blob 의 키블록을 오프셋과 길이대로 잘라낸다")
    void openPgpBlob_keyBlockIsSliced() throws Exception {
        byte[] payload = {0x11, 0x22, 0x33};
        byte[] data = concat(header(), openPgpBlob(23, 20, 3, payload));
        KeyboxReader.Contents c = KeyboxReader.read(data, "t.kbx");
        assertEquals(1, c.keyBlocks().size());
        assertArrayEquals(payload, c.keyBlocks().get(0));
    }

    @Test
    @DisplayName("X.509 blob 은 건너뛰고 개수를 알려 준다")
    void x509Blob_isSkippedAndCounted() throws Exception {
        ByteBuffer x = ByteBuffer.allocate(8);
        x.putInt(8).put((byte) 3).put((byte) 0).putShort((short) 0);
        KeyboxReader.Contents c = KeyboxReader.read(concat(header(), x.array()), "t.kbx");
        assertEquals(1, c.skippedX509Blobs());
        assertTrue(c.keyBlocks().isEmpty());
    }

    @Test
    @DisplayName("KBXf 헤더가 없으면 키박스가 아니라는 예외")
    void notAKeybox_throws() {
        HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                () -> KeyboxReader.read(new byte[40], "t.kbx"));
        assertTrue(ex.getMessage().contains("not a GnuPG keybox"), ex.getMessage());
    }

    @Test
    @DisplayName("파일 크기를 넘는 blob 길이는 예외")
    void blobLongerThanFile_throws() {
        byte[] data = concat(header(), openPgpBlob(5000, 20, 3, new byte[]{1, 2, 3}));
        HgSigFormatException ex = assertThrows(HgSigFormatException.class, () -> KeyboxReader.read(data, "t.kbx"));
        assertTrue(ex.getMessage().contains("impossible length"), ex.getMessage());
    }

    @Test
    @DisplayName("blob 밖을 가리키는 키블록은 예외")
    void keyBlockOutsideBlob_throws() {
        byte[] data = concat(header(), openPgpBlob(23, 20, 4000, new byte[]{1, 2, 3}));
        HgSigFormatException ex = assertThrows(HgSigFormatException.class, () -> KeyboxReader.read(data, "t.kbx"));
        assertTrue(ex.getMessage().contains("outside the blob"), ex.getMessage());
    }

    @Test
    @DisplayName("끝이 잘린 blob 헤더는 예외")
    void truncatedBlobHeader_throws() {
        byte[] data = concat(header(), new byte[]{0, 0, 0});
        assertThrows(HgSigFormatException.class, () -> KeyboxReader.read(data, "t.kbx"));
    }

    @Test
    @DisplayName("키박스를 OpenPGP 키 파일로 잘못 넘기면 원인을 알려 주는 예외(BC는 오류 없이 빈 결과를 낸다)")
    void keyboxPassedAsKeyFile_isRejectedWithHint() {
        HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                () -> GpgKeyring.fromPublicKeys("pubring.kbx", header()));
        assertTrue(ex.getMessage().contains("keybox"), ex.getMessage());
    }

    @Test
    @DisplayName("키가 하나도 없는 비어 있지 않은 입력은 예외(빈 키링으로 위장하지 않음)")
    void garbageInput_isRejected() {
        assertThrows(HgSigFormatException.class,
                () -> GpgKeyring.fromPublicKeys("junk", "this is not a key".getBytes(StandardCharsets.UTF_8)));
    }
}
