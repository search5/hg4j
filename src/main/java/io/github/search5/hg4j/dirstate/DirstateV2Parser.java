package io.github.search5.hg4j.dirstate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * High-performance native parser for Mercurial dirstate-v2 binary format.
 */
public class DirstateV2Parser {

    /**
     * Parses dirstate-v2 binary content into a standard Dirstate instance using absolute tree traversal.
     *
     * @param bytes raw binary content
     * @param rootStart start offset of root nodes
     * @param rootCount number of root nodes
     * @return decoded Dirstate object
     * @throws IOException if format is invalid
     */
    public Dirstate parse(byte[] bytes, int rootStart, int rootCount) throws IOException {
        Dirstate decoded = new Dirstate();
        decoded.setV2(true);
        if (bytes == null || bytes.length == 0) {
            return decoded;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int nodeSize = DirstateV2Node.NODE_SIZE;

        // Real hg's on-disk format (mercurial/dirstateutils/v2.py) always stores path_start/
        // copy_source_start/children_start as offsets absolute to the start of this data file —
        // there is no separate "node table" block followed by a "path data" block; paths and
        // node structs are interleaved (each directory's children's paths are written just
        // before that directory's packed node structs). Verified against a real captured
        // Mercurial 6.0 dirstate-v2 fixture: a leaf node's path_start pointed directly at byte 0
        // of the data file with no additional shift needed.
        int dataOffset = 0;

        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = rootCount - 1; i >= 0; i--) {
            stack.push(rootStart + i * nodeSize);
        }

        while (!stack.isEmpty()) {
            int nodeOffset = stack.pop();
            DirstateV2Node node = new DirstateV2Node(buffer, nodeOffset);

            int pathOffset = node.getPathOffset();
            int pathLen = node.getPathLen() & 0xFFFF;

            if (dataOffset + pathOffset + pathLen > buffer.capacity()) {
                throw new HgCorruptDataException("Data block overflow for node at offset: " + nodeOffset);
            }

            byte[] pathBytes = new byte[pathLen];
            int originalPos = buffer.position();
            buffer.position(dataOffset + pathOffset);
            buffer.get(pathBytes);
            buffer.position(originalPos);

            String currentPath = new String(pathBytes, StandardCharsets.UTF_8);

            char state = node.getState();
            if (state != '\0' && state != 'd') {
                // Real hg's dirstate-v2 marks an entry "possibly dirty" (the same concept as v1's
                // size=-1/mtime=0xFFFFFFFF sentinels, see StatusCommand's AMBIGUOUS_TIME/
                // nonNormalSize handling) by simply clearing the HAS_MODE_AND_SIZE/HAS_MTIME flag
                // bits rather than writing a sentinel value -- DirstateV2Node.getSize()/getMtime()
                // return a literal 0 in that case (verified live against a real-hg-committed
                // dirstate-v2 repo, 2026-09-05, backlog #39 wave 4: a file committed within the
                // same wall-clock second as the dirstate save gets flags with both bits cleared).
                // Passing that literal 0 straight into the shared Dirstate.Entry (as this used to)
                // collapses "no cached stat available" into "cached size/mtime is exactly 0",
                // which StatusCommand's fast path then reads as a real recorded size/mtime and
                // reports as modified even when byte-identical to the parent -- confirmed live via
                // BackoutCommand's own precondition check tripping on every dirstate-v2 Docker
                // combo. Translate the flag-absence into the same v1-style sentinels every other
                // reader (StatusCommand) and writer (RevertCommand/BackoutCommand/ShelveCommand)
                // already share, so both dirstate versions carry identical "possibly dirty"
                // semantics up through the rest of hg4j.
                int flags = node.getFlags() & 0xFFFF;
                boolean hasModeAndSize = (flags & DirstateV2Node.HAS_MODE_AND_SIZE) != 0;
                boolean hasMtime = (flags & DirstateV2Node.HAS_MTIME) != 0;
                int size = hasModeAndSize ? node.getSize() : -1;
                long mtime = hasMtime ? node.getMtime() : 0xFFFFFFFFL;
                int nanos = hasMtime ? node.getMtimeNanoseconds() : 0;
                decoded.addEntry(currentPath, new Dirstate.Entry(state, node.getMode(), size, mtime, nanos));
            }

            int copyOffset = node.getCopySourceOffset();
            int copyLen = node.getCopySourceLen() & 0xFFFF;
            if (copyLen > 0 && copyOffset + copyLen <= buffer.capacity()) {
                byte[] copyBytes = new byte[copyLen];
                int copyOriginalPos = buffer.position();
                buffer.position(copyOffset);
                buffer.get(copyBytes);
                buffer.position(copyOriginalPos);
                String copySrc = new String(copyBytes, StandardCharsets.UTF_8);
                decoded.addCopy(currentPath, copySrc);
            }

            int childrenStart = node.getChildrenStart();
            int childrenCount = node.getChildrenCount();
            if (childrenCount > 0) {
                if (childrenStart + childrenCount * DirstateV2Node.NODE_SIZE > buffer.capacity()) {
                    throw new HgCorruptDataException("Children segment overflow for node at offset: " + nodeOffset);
                }
                for (int i = childrenCount - 1; i >= 0; i--) {
                    stack.push(childrenStart + i * DirstateV2Node.NODE_SIZE);
                }
            }
        }

        return decoded;
    }

    /**
     * Parses dirstate-v2 binary content into a standard Dirstate instance (Legacy fallback compatibility).
     *
     * @param bytes raw binary content
     * @return decoded Dirstate object
     * @throws IOException if format is invalid
     */
    public Dirstate parse(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new HgCorruptDataException("Invalid dirstate-v2 data: content is null");
        }

        if (bytes.length == 0) {
            Dirstate decoded = new Dirstate();
            decoded.setV2(true);
            return decoded;
        }

        int nodeCount = 0;
        int nodeSize = DirstateV2Node.NODE_SIZE;
        ByteBuffer tempBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        
        // Try detecting with absolute path offset
        for (int n = 1; n * nodeSize <= bytes.length; n++) {
            int lastNodeOffset = (n - 1) * nodeSize;
            int pathOffset = tempBuf.getInt(lastNodeOffset + 0);
            int pathLen = tempBuf.getShort(lastNodeOffset + 4) & 0xFFFF;
            // DirstateV2Serializer writes each node's copy-source bytes (if any) immediately
            // after that node's own path bytes in the data block, so the true end of the data
            // block for the last node is copy_source_start + copy_source_len whenever that node
            // carries a copy source -- not path_start + path_len, which only covers the path.
            int copySourceOffset = tempBuf.getInt(lastNodeOffset + 8);
            int copySourceLen = tempBuf.getShort(lastNodeOffset + 12) & 0xFFFF;
            int expectedEnd = copySourceLen > 0 ? (copySourceOffset + copySourceLen) : (pathOffset + pathLen);

            if (expectedEnd == bytes.length) {
                nodeCount = n;
                break;
            }
        }

        // Fallback to relative path offset for old hg4j compat
        if (nodeCount == 0) {
            for (int n = 1; n * nodeSize <= bytes.length; n++) {
                int lastNodeOffset = (n - 1) * nodeSize;
                int pathOffset = tempBuf.getInt(lastNodeOffset + 0);
                int pathLen = tempBuf.getShort(lastNodeOffset + 4) & 0xFFFF;
                int copySourceOffset = tempBuf.getInt(lastNodeOffset + 8);
                int copySourceLen = tempBuf.getShort(lastNodeOffset + 12) & 0xFFFF;
                int dataOffset = n * nodeSize;
                int expectedEnd = dataOffset + (copySourceLen > 0 ? (copySourceOffset + copySourceLen) : (pathOffset + pathLen));

                if (expectedEnd == bytes.length) {
                    nodeCount = n;
                    break;
                }
            }
        }

        if (nodeCount == 0) {
            throw new HgCorruptDataException("Malformed dirstate-v2 data: cannot resolve node count and layout");
        }

        return parse(bytes, 0, nodeCount);
    }
}
