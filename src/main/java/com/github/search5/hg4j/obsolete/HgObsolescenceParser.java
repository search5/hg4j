package com.github.search5.hg4j.obsolete;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Mercurial's obsolescence store (obsstore) binary format.
 * Enables integration with the evolve revision history system.
 *
 * <p>지원 포맷: FM1(version=1) — 실제 hg CLI(7.2, 기본 obsstore 포맷)로 생성한 실제
 * obsstore 파일을 {@code mercurial.obsolete._readmarkers()}로 직접 디코딩해 검증됨
 * (2026-09-01). FM0(version=0, 레거시)은 지원하지 않는다 — 실제 근거:
 * {@code mercurial/obsolete.py}의 {@code formats} 매핑, {@code _fm1fixed = '>IdhHBBB'}.</p>
 */
public final class HgObsolescenceParser {

    private static final int FM1_VERSION = 1;
    private static final int FM1_PARENT_NONE = 3;
    private static final int FLAG_USING_SHA256 = 1 << 2; // usingsha256 (mercurial/obsolete.py)

    /**
     * Decodes the raw obsstore binary payload into a list of obsolescence markers.
     * 파일 첫 바이트는 포맷 버전이다 — 데이터 자체에 포함되지 않는다.
     *
     * @param bytes raw binary contents of obsstore
     * @return list of parsed markers
     * @throws IOException if parsing fails or invalid/unsupported format is detected
     */
    public static List<HgObsMarker> parse(byte[] bytes) throws IOException {
        List<HgObsMarker> markers = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return markers;
        }

        int version = bytes[0] & 0xFF;
        if (version != FM1_VERSION) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException(
                    "Unsupported obsstore format version: " + version + " (only FM1/version=1 is supported)");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1).order(ByteOrder.BIG_ENDIAN);
        try {
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 19) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: incomplete fixed header");
                }
                int recordStart = buffer.position();
                int totalSize = buffer.getInt();
                double dateSecs = buffer.getDouble();
                short tzMinutes = buffer.getShort();
                int flags = buffer.getShort() & 0xFFFF;
                int numsuc = buffer.get() & 0xFF;
                int numpar = buffer.get() & 0xFF;
                int nummeta = buffer.get() & 0xFF;

                int nodeSize = (flags & FLAG_USING_SHA256) != 0 ? 32 : 20;

                byte[] predecessor = new byte[20];
                readNode(buffer, predecessor, nodeSize);

                List<byte[]> successors = new ArrayList<>(numsuc);
                for (int i = 0; i < numsuc; i++) {
                    byte[] succ = new byte[20];
                    readNode(buffer, succ, nodeSize);
                    successors.add(succ);
                }

                if (numpar != FM1_PARENT_NONE) {
                    // 부모 정보는 hg4j의 HgObsMarker 모델에서 다루지 않으므로 건너뛴다.
                    buffer.position(buffer.position() + nodeSize * numpar);
                }

                int[] metaLens = new int[nummeta * 2];
                for (int i = 0; i < nummeta * 2; i++) {
                    metaLens[i] = buffer.get() & 0xFF;
                }
                Map<String, String> metadata = new LinkedHashMap<>();
                for (int i = 0; i < nummeta; i++) {
                    int keyLen = metaLens[i * 2];
                    int valLen = metaLens[i * 2 + 1];
                    byte[] keyBytes = new byte[keyLen];
                    buffer.get(keyBytes);
                    byte[] valBytes = new byte[valLen];
                    buffer.get(valBytes);
                    metadata.put(new String(keyBytes, StandardCharsets.UTF_8), new String(valBytes, StandardCharsets.UTF_8));
                }

                // totalSize는 이 레코드의 선언된 전체 길이(자기 자신의 4바이트 포함) — 다음
                // 레코드 시작 지점과 일치하는지 무결성 체크만 하고, 실제 파싱은 필드 단위로 이미
                // 끝났다.
                int consumed = buffer.position() - recordStart;
                if (consumed != totalSize) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException(
                            "obsstore record size mismatch: declared=" + totalSize + " actual=" + consumed);
                }

                markers.add(new HgObsMarker(predecessor, successors, flags, metadata));
            }
        } catch (com.github.search5.hg4j.errors.HgCorruptDataException e) {
            throw e;
        } catch (Exception e) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Failed to parse obsstore binary content", e);
        }

        return markers;
    }

    private static void readNode(ByteBuffer buffer, byte[] dest20, int nodeSize) {
        if (nodeSize == 20) {
            buffer.get(dest20);
        } else {
            byte[] tmp = new byte[nodeSize];
            buffer.get(tmp);
            System.arraycopy(tmp, 0, dest20, 0, 20);
        }
    }
}
