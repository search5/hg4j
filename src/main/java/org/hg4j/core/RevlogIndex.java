package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Revlog의 인덱스 (.i) 파일을 관리하고 파싱하는 책임을 가지는 클래스.
 */
public class RevlogIndex {

    private final File idxFile;
    private final List<Revlog.IndexRecord> records = new ArrayList<>();
    private final Map<ByteBuffer, Integer> nodeMap = new HashMap<>();
    private boolean inline = false;

    public RevlogIndex(File idxFile) throws IOException {
        this.idxFile = idxFile;
        if (idxFile.exists()) {
            loadIndex();
        }
    }

    private synchronized void loadIndex() throws IOException {
        long len = idxFile.length();
        if (len == 0) return;
        if (len < 64) {
            throw new org.hg4j.errors.HgCorruptDataException("Invalid revlog index: too short");
        }

        try (FileChannel channel = FileChannel.open(idxFile.toPath(), StandardOpenOption.READ)) {
            ByteBuffer headerBuf = ByteBuffer.allocate(64);
            int revision = 0;
            
            while (true) {
                headerBuf.clear();
                while (headerBuf.hasRemaining()) {
                    if (channel.read(headerBuf) == -1) {
                        break;
                    }
                }
                if (headerBuf.position() < 64) {
                    break;
                }
                headerBuf.flip();

                long offsetFlags = headerBuf.getLong();
                long offset;
                int flags;

                if (revision == 0) {
                    int formatFlags = (int) (offsetFlags >>> 48);
                    int version = (int) ((offsetFlags >>> 32) & 0xFFFF);
                    if (version != 1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Unsupported revlog version: " + version);
                    }
                    this.inline = (formatFlags & 0x0001) != 0;
                    offset = 0;
                    flags = (int) (offsetFlags & 0xFFFF);
                } else {
                    offset = offsetFlags >>> 16;
                    flags = (int) (offsetFlags & 0xFFFF);
                }

                int compLen = headerBuf.getInt();
                int uncompLen = headerBuf.getInt();
                int baseRev = headerBuf.getInt();
                int linkRev = headerBuf.getInt();
                int parent1 = headerBuf.getInt();
                int parent2 = headerBuf.getInt();
                byte[] nodeId = new byte[32];
                headerBuf.get(nodeId);

                records.add(new Revlog.IndexRecord(revision, offset, flags, compLen, uncompLen,
                        baseRev, linkRev, parent1, parent2, nodeId));

                byte[] clippedNode = Arrays.copyOf(nodeId, 20);
                nodeMap.put(ByteBuffer.wrap(clippedNode), revision);

                if (inline) {
                    long currentPos = channel.position();
                    long targetPos = currentPos + compLen;
                    if (targetPos > channel.size()) {
                        throw new org.hg4j.errors.HgCorruptDataException("Truncated inline revlog data at revision " + revision);
                    }
                    channel.position(targetPos);
                }
                revision++;
            }
        }
    }

    public synchronized int getRevisionCount() {
        return records.size();
    }

    public synchronized Revlog.IndexRecord getIndexRecord(int rev) {
        if (rev < 0 || rev >= records.size()) {
            throw new IndexOutOfBoundsException("Revision out of bounds: " + rev);
        }
        return records.get(rev);
    }

    public synchronized int findRevision(byte[] nodeId) {
        if (nodeId == null) return -1;
        byte[] clippedNode = new byte[20];
        System.arraycopy(nodeId, 0, clippedNode, 0, Math.min(nodeId.length, 20));
        Integer rev = nodeMap.get(ByteBuffer.wrap(clippedNode));
        return rev != null ? rev : -1;
    }

    public boolean isInline() {
        return inline;
    }

    public synchronized void addRecord(Revlog.IndexRecord record) {
        records.add(record);
        byte[] clippedNode = Arrays.copyOf(record.getNodeId(), 20);
        nodeMap.put(ByteBuffer.wrap(clippedNode), record.getRevision());
    }
}
