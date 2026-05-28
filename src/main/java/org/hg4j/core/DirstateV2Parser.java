package org.hg4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * High-performance native parser for Mercurial dirstate-v2 binary format.
 */
public class DirstateV2Parser {

    private static final byte[] MAGIC = {(byte) 0xd8, (byte) 0x1c, (byte) 0x8c, (byte) 0xf5,
                                         (byte) 0xfe, (byte) 0x73, (byte) 0x0f, (byte) 0x14};

    /**
     * Parses dirstate-v2 binary content into a standard Dirstate instance.
     *
     * @param bytes raw binary content
     * @return decoded Dirstate object
     * @throws IOException if format is invalid
     */
    public Dirstate parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 60) {
            throw new IOException("Invalid dirstate-v2: content too short");
        }

        // 1. Magic bytes verification
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                throw new IOException("Invalid dirstate-v2 magic signature");
            }
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        // 2. Decode parents
        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        buffer.position(8);
        buffer.get(p1);
        buffer.get(p2);

        // 3. Decode header control fields
        int nodeCount = buffer.getInt(48);
        int nodesOffset = buffer.getInt(52);
        int dataOffset = buffer.getInt(56);

        if (nodesOffset < 60 || dataOffset < nodesOffset || dataOffset > bytes.length) {
            throw new IOException("Malformed dirstate-v2 offset configuration");
        }

        Dirstate decoded = new Dirstate();
        decoded.setV2(true);
        decoded.setParents(p1, p2);

        if (nodeCount == 0) {
            return decoded;
        }

        // 4. Wrap all nodes in layout wrappers
        List<DirstateV2Node> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new DirstateV2Node(buffer, nodesOffset + i * DirstateV2Node.NODE_SIZE));
        }

        // 5. Dynamic root node resolution
        // Find node indexes that are not children of any other node.
        Set<Integer> childrenIndexes = new HashSet<>();
        for (DirstateV2Node node : nodes) {
            int start = node.getChildrenStart();
            int count = node.getChildrenCount();
            for (int k = 0; k < count; k++) {
                childrenIndexes.add(start + k);
            }
        }

        List<Integer> rootIndexes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            if (!childrenIndexes.contains(i)) {
                rootIndexes.add(i);
            }
        }

        // 6. DFS Tree Traversal to reconstruct absolute file paths and entries
        for (int rootIndex : rootIndexes) {
            parseNode(decoded, nodes, buffer, dataOffset, rootIndex, "");
        }

        return decoded;
    }

    private void parseNode(Dirstate decoded, List<DirstateV2Node> nodes, ByteBuffer buffer, 
                           int dataOffset, int nodeIndex, String parentPath) throws IOException {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            throw new IOException("Node index out of bounds: " + nodeIndex);
        }

        DirstateV2Node node = nodes.get(nodeIndex);
        int pathOffset = node.getPathOffset();
        int pathLen = node.getPathLen() & 0xFFFF; // unsigned short

        if (dataOffset + pathOffset + pathLen > buffer.capacity()) {
            throw new IOException("Data block overflow for node segment: " + nodeIndex);
        }

        byte[] pathBytes = new byte[pathLen];
        int originalPos = buffer.position();
        buffer.position(dataOffset + pathOffset);
        buffer.get(pathBytes);
        buffer.position(originalPos);

        String segmentName = new String(pathBytes, StandardCharsets.UTF_8);
        String currentPath = parentPath.isEmpty() ? segmentName : parentPath + "/" + segmentName;

        char state = node.getState();
        if (state != '\0' && state != 'd') { // 'd' represents intermediate directories
            decoded.addEntry(currentPath, new Dirstate.Entry(state, node.getMode(), node.getSize(), node.getMtime()));
        }

        int childrenStart = node.getChildrenStart();
        int childrenCount = node.getChildrenCount();
        for (int i = 0; i < childrenCount; i++) {
            parseNode(decoded, nodes, buffer, dataOffset, childrenStart + i, currentPath);
        }
    }
}
