package org.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Revlog 데이터 압축 및 해제를 전담하는 컴포넌트 (SRP 분리).
 *
 * <p>지원 형식:
 * <ul>
 *   <li><b>'x' (0x78)</b> — zlib deflate 압축</li>
 *   <li><b>'u'</b> — 비압축 (uncompressed, prefix byte 포함)</li>
 *   <li><b>기타</b> — raw fallback (비압축 헤더 없음)</li>
 * </ul>
 *
 * <p>이 클래스는 상태를 갖지 않으며 모든 메서드는 정적입니다.
 */
public final class DeltaCodec {

    private DeltaCodec() {}

    /**
     * 주어진 데이터를 압축합니다.
     * 압축 후 크기가 원본보다 작으면 zlib 압축 결과를 반환하고,
     * 그렇지 않으면 {@code 'u'} 접두 바이트를 붙인 비압축 형식으로 반환합니다.
     *
     * @param data 압축할 원본 데이터
     * @return 압축된 hunk 바이트 배열
     */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return new byte[0];
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
            // 비압축 형식: 'u' + 원본 데이터
            byte[] uncompressed = new byte[data.length + 1];
            uncompressed[0] = 'u';
            System.arraycopy(data, 0, uncompressed, 1, data.length);
            return uncompressed;
        }
    }

    /**
     * 압축된 hunk를 해제합니다.
     *
     * @param hunk        압축된 바이트 배열 (revlog에서 읽은 raw hunk)
     * @param uncompLen   기대되는 비압축 크기 (힌트 용도)
     * @return 해제된 원본 데이터
     * @throws IOException 압축 해제 실패 시
     */
    public static byte[] decompress(byte[] hunk, int uncompLen) throws IOException {
        if (hunk == null || hunk.length == 0 || uncompLen == 0) {
            return new byte[0];
        }

        byte type = hunk[0];

        if (type == 'x' || type == (byte) 0x78) {
            return decompressZlib(hunk, uncompLen);
        } else if (type == 'u') {
            // 'u' 접두 바이트 이후가 실제 데이터
            return Arrays.copyOfRange(hunk, 1, hunk.length);
        } else {
            // raw fallback — 헤더 없음, 전체를 반환
            return hunk;
        }
    }

    /**
     * zlib 스트림을 해제합니다.
     * Mercurial 특유의 오프셋 변형('x' 접두 바이트 포함 여부)을 자동으로 감지합니다.
     */
    private static byte[] decompressZlib(byte[] hunk, int uncompLen) throws IOException {
        Inflater inflater = new Inflater();

        // zlib 헤더 위치 자동 감지:
        // 1) 시작부터 zlib 헤더인지
        // 2) index 1부터 zlib 헤더인지 (앞에 'x' prefix byte가 붙은 경우)
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
            throw new IOException("Failed to decompress zlib revlog hunk", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
