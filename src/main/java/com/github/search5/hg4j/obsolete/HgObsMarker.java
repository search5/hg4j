package com.github.search5.hg4j.obsolete;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Represents a single Obsolescence Marker (Evolve mechanism).
 * Marks a predecessor revision as "obsolete" and maps it to optional successor revisions.
 */
public final class HgObsMarker {
    private final byte[] predecessor;
    private final List<byte[]> successors;
    private final int flags;
    private final Map<String, String> metadata;

    public HgObsMarker(byte[] predecessor, List<byte[]> successors, int flags, Map<String, String> metadata) {
        if (predecessor == null || predecessor.length != 20) {
            throw new IllegalArgumentException("Predecessor node must be exactly 20 bytes");
        }
        this.predecessor = predecessor.clone();
        this.successors = successors != null ? successors : List.of();
        this.flags = flags;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public byte[] getPredecessor() {
        return predecessor.clone();
    }

    public List<byte[]> getSuccessors() {
        return successors.stream().map(byte[]::clone).toList();
    }

    public int getFlags() {
        return flags;
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HgObsMarker that = (HgObsMarker) o;
        return flags == that.flags &&
                Arrays.equals(predecessor, that.predecessor) &&
                successorsEqual(this.successors, that.successors) &&
                metadata.equals(that.metadata);
    }

    private static boolean successorsEqual(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(predecessor);
        result = 31 * result + successors.size();
        result = 31 * result + flags;
        result = 31 * result + metadata.hashCode();
        return result;
    }

    /**
     * obsstore(FM1, version=1) 포맷으로 마커 하나를 append한다.
     *
     * <p>실제 hg CLI(`--config experimental.evolution.createmarkers=true`)로 amend를 수행해
     * 얻은 실제 obsstore 바이트를 {@code mercurial.obsolete._readmarkers()}로 직접 디코딩해
     * 검증된 레이아웃이다(2026-09-01). 이전 구현은 파일 버전 바이트가 아예 없고 필드 순서도
     * 완전히 달라서, 실제 hg가 이 파일을 읽으면 즉시 깨진 것으로 인식했다.</p>
     *
     * <p>고정 헤더(19바이트, {@code mercurial/obsolete.py}의 {@code _fm1fixed = '>IdhHBBB'}):
     * totalsize(I,4) + date_secs(d,8) + tz_minutes(h,2) + flags(H,2) + numsuc(B,1) +
     * numpar(B,1) + nummeta(B,1). 이어서 predecessor(20B) + successors(20B*numsuc) +
     * (parents는 numpar=3="기록 안 함"으로 항상 생략) + metapair 길이표(2B*nummeta) +
     * 메타데이터 원본 바이트.</p>
     */
    public static void writeMarker(File storeDir, byte[] predecessor, List<byte[]> successors, String operation) throws IOException {
        File obsstoreFile = new File(storeDir, "obsstore");
        boolean writeVersionByte = !obsstoreFile.exists() || obsstoreFile.length() == 0;

        List<byte[]> succList = successors != null ? successors : List.of();
        int numsuc = succList.size();
        final int NUMPAR_NONE = 3; // _fm1parentnone: 부모 정보를 기록하지 않음
        final int NODE_SIZE = 20;  // sha1 (usingsha256 플래그 미사용)

        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("operation", operation != null ? operation : "amend");
        meta.put("user", "hg4j");

        int fixedSize = 19; // I(4)+d(8)+h(2)+H(2)+B(1)+B(1)+B(1)
        int nodesSection = NODE_SIZE * (1 + numsuc); // predecessor + successors만, parents는 생략
        int metaPairsSection = 2 * meta.size();
        int metaBytesLen = 0;
        for (Map.Entry<String, String> e : meta.entrySet()) {
            metaBytesLen += e.getKey().getBytes(StandardCharsets.UTF_8).length
                    + e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        int totalSize = fixedSize + nodesSection + metaPairsSection + metaBytesLen;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(totalSize);
        buf.putDouble(System.currentTimeMillis() / 1000.0);
        buf.putShort((short) 0); // tz(분 단위) — 단순화를 위해 UTC로 기록
        buf.putShort((short) 0); // flags — sha256 미사용
        buf.put((byte) numsuc);
        buf.put((byte) NUMPAR_NONE);
        buf.put((byte) meta.size());
        buf.put(predecessor, 0, NODE_SIZE);
        for (byte[] succ : succList) {
            buf.put(succ, 0, NODE_SIZE);
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            buf.put((byte) e.getKey().getBytes(StandardCharsets.UTF_8).length);
            buf.put((byte) e.getValue().getBytes(StandardCharsets.UTF_8).length);
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            buf.put(e.getKey().getBytes(StandardCharsets.UTF_8));
            buf.put(e.getValue().getBytes(StandardCharsets.UTF_8));
        }

        try (FileOutputStream out = new FileOutputStream(obsstoreFile, true)) {
            if (writeVersionByte) {
                out.write(1); // _fm1version
            }
            out.write(buf.array());
            out.getFD().sync();
        }
    }
}
