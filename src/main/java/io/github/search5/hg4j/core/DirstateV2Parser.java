package io.github.search5.hg4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

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
        
        // Detect old hg4j relative offset compatibility
        int dataOffset = 0;
        int nodeSize = DirstateV2Node.NODE_SIZE;
        if (rootStart == 0 && rootCount * nodeSize <= bytes.length) {
            int lastNodeOffset = (rootCount - 1) * nodeSize;
            if (lastNodeOffset >= 0 && lastNodeOffset + nodeSize <= bytes.length) {
                int pathOffset = buffer.getInt(lastNodeOffset + 30);
                int pathLen = buffer.getShort(lastNodeOffset + 34) & 0xFFFF;
                if (rootCount * nodeSize + pathOffset + pathLen == bytes.length) {
                    dataOffset = rootCount * nodeSize;
                }
            }
        }

        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        for (int i = rootCount - 1; i >= 0; i--) {
            stack.push(rootStart + i * nodeSize);
        }

        while (!stack.isEmpty()) {
            int nodeOffset = stack.pop();
            DirstateV2Node node = new DirstateV2Node(buffer, nodeOffset);

            int pathOffset = node.getPathOffset();
            int pathLen = node.getPathLen() & 0xFFFF;

            if (dataOffset + pathOffset + pathLen > buffer.capacity()) {
                throw new io.github.search5.hg4j.errors.HgCorruptDataException("Data block overflow for node at offset: " + nodeOffset);
            }

            byte[] pathBytes = new byte[pathLen];
            int originalPos = buffer.position();
            buffer.position(dataOffset + pathOffset);
            buffer.get(pathBytes);
            buffer.position(originalPos);

            String currentPath = new String(pathBytes, StandardCharsets.UTF_8);

            char state = node.getState();
            if (state != '\0' && state != 'd') {
                decoded.addEntry(currentPath, new Dirstate.Entry(state, node.getMode(), node.getSize(), node.getMtime(), node.getMtimeNanoseconds()));
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
                    throw new io.github.search5.hg4j.errors.HgCorruptDataException("Children segment overflow for node at offset: " + nodeOffset);
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
            throw new io.github.search5.hg4j.errors.HgCorruptDataException("Invalid dirstate-v2 data: content is null");
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
            int pathOffset = tempBuf.getInt(lastNodeOffset + 30);
            int pathLen = tempBuf.getShort(lastNodeOffset + 34) & 0xFFFF;
            
            if (pathOffset + pathLen == bytes.length) {
                nodeCount = n;
                break;
            }
        }

        // Fallback to relative path offset for old hg4j compat
        if (nodeCount == 0) {
            for (int n = 1; n * nodeSize <= bytes.length; n++) {
                int lastNodeOffset = (n - 1) * nodeSize;
                int pathOffset = tempBuf.getInt(lastNodeOffset + 30);
                int pathLen = tempBuf.getShort(lastNodeOffset + 34) & 0xFFFF;
                int dataOffset = n * nodeSize;
                
                if (dataOffset + pathOffset + pathLen == bytes.length) {
                    nodeCount = n;
                    break;
                }
            }
        }

        if (nodeCount == 0) {
            throw new io.github.search5.hg4j.errors.HgCorruptDataException("Malformed dirstate-v2 data: cannot resolve node count and layout");
        }

        return parse(bytes, 0, nodeCount);
    }
}
