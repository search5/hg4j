package org.hg4j.core;

import org.hg4j.lib.NodeId;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and writes the Mercurial binary .hg/dirstate file.
 */
public class Dirstate {

    private NodeId parent1 = NodeId.NULL;
    private NodeId parent2 = NodeId.NULL;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> copyMap = new LinkedHashMap<>();
    private boolean isV2 = false;

    public Map<String, String> getCopyMap() {
        return copyMap;
    }

    public void addCopy(String dest, String src) {
        copyMap.put(dest, src);
    }

    public boolean isV2() {
        return isV2;
    }

    public void setV2(boolean v2) {
        this.isV2 = v2;
    }

    public record Entry(char state, int mode, int size, long time, int nanos) {
        public Entry(char state, int mode, int size, long time) {
            this(state, mode, size, time, 0);
        }

        public char getState() {
            return state;
        }

        public int getMode() {
            return mode;
        }

        public int getSize() {
            return size;
        }

        public long getTime() {
            return time;
        }

        public int getNanos() {
            return nanos;
        }
    }

    public byte[] getParent1() {
        return parent1.getBytes();
    }

    public byte[] getParent2() {
        return parent2.getBytes();
    }

    public NodeId getParent1Node() {
        return parent1;
    }

    public NodeId getParent2Node() {
        return parent2;
    }

    public void setParents(NodeId p1, NodeId p2) {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }
        this.parent1 = p1;
        this.parent2 = p2;
    }

    public void setParents(byte[] p1, byte[] p2) {
        setParents(new NodeId(p1), new NodeId(p2));
    }

    public Map<String, Entry> getEntries() {
        return entries;
    }

    public void addEntry(String path, Entry entry) {
        entries.put(path, entry);
    }

    public void removeEntry(String path) {
        entries.remove(path);
    }

    public void read(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new org.hg4j.errors.HgCorruptDataException("Invalid dirstate file: content cannot be null");
        }

        if (bytes.length < 40) {
            throw new org.hg4j.errors.HgCorruptDataException("Invalid dirstate file: must be at least 40 bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        buf.get(p1);
        buf.get(p2);
        this.parent1 = new NodeId(p1);
        this.parent2 = new NodeId(p2);

        entries.clear();
        while (buf.hasRemaining()) {
            if (buf.remaining() < 17) {
                throw new org.hg4j.errors.HgCorruptDataException("Truncated dirstate entry header");
            }
            char state = (char) buf.get();
            int mode = buf.getInt();
            int size = buf.getInt();
            long time = buf.getInt() & 0xFFFFFFFFL; // Parse as unsigned 32-bit int
            int pathLen = buf.getInt();

            if (pathLen < 0 || buf.remaining() < pathLen) {
                throw new org.hg4j.errors.HgCorruptDataException("Truncated dirstate entry path");
            }

            byte[] pathBytes = new byte[pathLen];
            buf.get(pathBytes);
            String path = new String(pathBytes, StandardCharsets.UTF_8);
            entries.put(path, new Entry(state, mode, size, time));
        }
    }

    public void read(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("Dirstate file does not exist");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());

        // dirstate-v2 docket magic bytes 감지 (정식 12바이트 스펙)
        if (bytes.length >= 12) {
            String magicStr = new String(bytes, 0, 12, StandardCharsets.US_ASCII);
            if ("dirstate-v2\n".equals(magicStr)) {
                ByteBuffer docketBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

                // parents (오프셋 12에서 32바이트, 오프셋 44에서 32바이트)
                byte[] p1_32 = new byte[32];
                byte[] p2_32 = new byte[32];
                docketBuf.position(12);
                docketBuf.get(p1_32);
                docketBuf.get(p2_32);

                // parents의 유효 노드 ID는 앞 20바이트임
                byte[] p1 = new byte[20];
                byte[] p2 = new byte[20];
                System.arraycopy(p1_32, 0, p1, 0, 20);
                System.arraycopy(p2_32, 0, p2, 0, 20);

                // data_length (오프셋 120에서 4바이트 int)
                int dataLength = docketBuf.getInt(120);

                // uid_size (오프셋 124에서 1바이트)
                int uidSize = docketBuf.get(124) & 0xFF;

                // uid (오프셋 125부터 uidSize 바이트)
                byte[] uidBytes = new byte[uidSize];
                docketBuf.position(125);
                docketBuf.get(uidBytes);
                String uid = new String(uidBytes, StandardCharsets.US_ASCII);

                // .hg/dirstate.d.<uid> 데이터 파일 로드
                File dataFile = new File(file.getParentFile(), "dirstate.d." + uid);
                if (!dataFile.exists()) {
                    throw new org.hg4j.errors.HgCorruptDataException("Dirstate-v2 data file not found for uid: " + uid);
                }
                byte[] dataBytes = Files.readAllBytes(dataFile.toPath());
                if (dataBytes.length != dataLength) {
                    throw new org.hg4j.errors.HgCorruptDataException("Dirstate-v2 data file length mismatch. Expected " + dataLength + " but got " + dataBytes.length);
                }

                this.isV2 = true;
                DirstateV2Parser parser = new DirstateV2Parser();
                int rootStart = docketBuf.getInt(76);
                int rootCount = docketBuf.getInt(80);
                Dirstate parsed = parser.parse(dataBytes, rootStart, rootCount);
                this.parent1 = new NodeId(p1);
                this.parent2 = new NodeId(p2);
                this.entries.clear();
                this.entries.putAll(parsed.getEntries());
                return;
            }
        }

        // Fallback to v1 parse
        read(bytes);
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(parent1.getBytes());
            out.write(parent2.getBytes());
            ByteBuffer buf = ByteBuffer.allocate(17);
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                byte[] pathBytes = item.getKey().getBytes(StandardCharsets.UTF_8);
                Entry entry = item.getValue();
                buf.clear();
                buf.put((byte) entry.getState());
                buf.putInt(entry.getMode());
                buf.putInt(entry.getSize());
                buf.putInt((int) (entry.getTime() & 0xFFFFFFFFL)); // Mask safely to 32-bit for serialization
                buf.putInt(pathBytes.length);
                out.write(buf.array());
                out.write(pathBytes);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream should not throw IOException
            throw new RuntimeException("Serialization failed unexpectedly", e);
        }
        return out.toByteArray();
    }

    public void write(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }

        if (isV2) {
            // W-LEAK: 쓰기 전에 기존의 docket 파일(`.hg/dirstate`)이 존재한다면,
            // 그것을 읽어 old uid를 확인해야 한다!
            String oldUid = null;
            if (file.exists()) {
                try {
                    byte[] oldBytes = Files.readAllBytes(file.toPath());
                    if (oldBytes.length >= 12) {
                        String oldMagic = new String(oldBytes, 0, 12, StandardCharsets.US_ASCII);
                        if ("dirstate-v2\n".equals(oldMagic)) {
                            ByteBuffer oldBuf = ByteBuffer.wrap(oldBytes).order(ByteOrder.BIG_ENDIAN);
                            int oldUidSize = oldBuf.get(124) & 0xFF;
                            byte[] oldUidBytes = new byte[oldUidSize];
                            oldBuf.position(125);
                            oldBuf.get(oldUidBytes);
                            oldUid = new String(oldUidBytes, StandardCharsets.US_ASCII);
                        }
                    }
                } catch (Exception ignored) {
                    // 예전 파일이 손상되었거나 파싱할 수 없는 상태라면 무시
                }
            }

            // 1. 데이터 파일 내용 직렬화
            byte[] dataBytes = DirstateV2Serializer.serialize(this);

            // 2. 고유 UID 생성 (임의의 UUID 형태)
            String uid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            // 3. 데이터 파일 .hg/dirstate.d.<uid> 쓰기
            File dataFile = new File(file.getParentFile(), "dirstate.d." + uid);
            SafeFileIO.writeAtomic(dataFile, dataBytes);

            // 4. Docket 바이트 조립
            byte[] uidBytes = uid.getBytes(StandardCharsets.US_ASCII);
            int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
            ByteBuffer docketBuf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);

            // Magic (12바이트): "dirstate-v2\n"
            byte[] v2Magic = "dirstate-v2\n".getBytes(StandardCharsets.US_ASCII);
            docketBuf.put(v2Magic);

            // P1 (32바이트, 20바이트 해시 + 12바이트 0패딩)
            byte[] p1_32 = new byte[32];
            System.arraycopy(parent1.getBytes(), 0, p1_32, 0, 20);
            docketBuf.put(p1_32);

            // P2 (32바이트, 20바이트 해시 + 12바이트 0패딩)
            byte[] p2_32 = new byte[32];
            System.arraycopy(parent2.getBytes(), 0, p2_32, 0, 20);
            docketBuf.put(p2_32);

            // Tree Metadata (44바이트): root_nodes (start=0 [4B] + count=rootCount [4B]) + nodes_with_entry_count [4B] + nodes_with_copy_source_count [4B] + 28바이트 0패딩
            java.util.Set<String> rootSegments = new java.util.HashSet<>();
            for (String path : entries.keySet()) {
                int slashIdx = path.indexOf('/');
                if (slashIdx == -1) {
                    rootSegments.add(path);
                } else {
                    rootSegments.add(path.substring(0, slashIdx));
                }
            }
            int rootCount = rootSegments.size();

            byte[] treeMetadataBytes = new byte[44];
            ByteBuffer metaBuf = ByteBuffer.wrap(treeMetadataBytes).order(ByteOrder.BIG_ENDIAN);
            metaBuf.putInt(0); // root_nodes children_start
            metaBuf.putInt(rootCount); // root_nodes children_count
            metaBuf.putInt(entries.size()); // nodes_with_entry_count
            metaBuf.putInt(0); // nodes_with_copy_source_count

            docketBuf.put(treeMetadataBytes);

            // Data length (4바이트 int)
            docketBuf.putInt(dataBytes.length);

            // UID Size (1바이트)
            docketBuf.put((byte) uidBytes.length);

            // UID (가변)
            docketBuf.put(uidBytes);

            // 5. Docket 파일 쓰기
            SafeFileIO.writeAtomic(file, docketBuf.array());

            // 6. W-LEAK: 예전 데이터 파일 삭제
            if (oldUid != null && !oldUid.equals(uid)) {
                File oldDataFile = new File(file.getParentFile(), "dirstate.d." + oldUid);
                Files.deleteIfExists(oldDataFile.toPath());
            }
            return;
        }

        // v1 쓰기
        byte[] bytes = serialize();
        SafeFileIO.writeAtomic(file, bytes);
    }
}
