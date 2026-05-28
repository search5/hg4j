package org.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * High-performance serializer to output binary dirstate-v2 format compliant with Mercurial.
 */
public class DirstateV2Serializer {

    private static final byte[] MAGIC = {(byte) 0xd8, (byte) 0x1c, (byte) 0x8c, (byte) 0xf5,
                                         (byte) 0xfe, (byte) 0x73, (byte) 0x0f, (byte) 0x14};

    private static class TreeNode {
        final String name;
        Dirstate.Entry entry;
        final Map<String, TreeNode> children = new LinkedHashMap<>();

        TreeNode(String name) {
            this.name = name;
        }
    }

    /**
     * Serializes memory dirstate representation into v2 binary format.
     */
    public static byte[] serialize(byte[] parent1, byte[] parent2, Map<String, Dirstate.Entry> entries) throws IOException {
        if (parent1 == null || parent1.length != 20 || parent2 == null || parent2.length != 20) {
            throw new IllegalArgumentException("Parents must be exactly 20 bytes");
        }

        // 1. Build directory hierarchical tree structure
        Map<String, TreeNode> roots = new LinkedHashMap<>();
        for (Map.Entry<String, Dirstate.Entry> item : entries.entrySet()) {
            String path = item.getKey();
            Dirstate.Entry entry = item.getValue();
            String[] segments = path.split("/");

            TreeNode current = null;
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                if (i == 0) {
                    current = roots.computeIfAbsent(seg, TreeNode::new);
                } else {
                    current = current.children.computeIfAbsent(seg, TreeNode::new);
                }
            }
            if (current != null) {
                current.entry = entry;
            }
        }

        // 2. Continuous BFS serialization layout mapping
        // Package children into flat list sequentially so children starts align side by side
        List<TreeNode> flatNodes = new ArrayList<>();
        List<TreeNode> rootList = new ArrayList<>(roots.values());
        
        // Add root nodes first
        flatNodes.addAll(rootList);

        Queue<TreeNode> queue = new LinkedList<>(rootList);
        int currentChildIndex = flatNodes.size();

        Map<TreeNode, Integer> nodeToIndexMap = new HashMap<>();
        for (int i = 0; i < flatNodes.size(); i++) {
            nodeToIndexMap.put(flatNodes.get(i), i);
        }

        Map<TreeNode, Integer> childrenStartMap = new HashMap<>();
        Map<TreeNode, Integer> childrenCountMap = new HashMap<>();

        while (!queue.isEmpty()) {
            TreeNode parent = queue.poll();
            if (!parent.children.isEmpty()) {
                List<TreeNode> childList = new ArrayList<>(parent.children.values());
                childrenStartMap.put(parent, currentChildIndex);
                childrenCountMap.put(parent, childList.size());

                for (TreeNode child : childList) {
                    flatNodes.add(child);
                    nodeToIndexMap.put(child, flatNodes.size() - 1);
                    queue.offer(child);
                }
                currentChildIndex += childList.size();
            } else {
                childrenStartMap.put(parent, 0);
                childrenCountMap.put(parent, 0);
            }
        }

        int nodeCount = flatNodes.size();

        // 3. Serialize data block (filenames) and calculate offset mappings
        ByteArrayOutputStream dataBlock = new ByteArrayOutputStream();
        Map<TreeNode, Integer> pathOffsetMap = new HashMap<>();
        Map<TreeNode, Short> pathLenMap = new HashMap<>();

        for (TreeNode node : flatNodes) {
            byte[] nameBytes = node.name.getBytes(StandardCharsets.UTF_8);
            pathOffsetMap.put(node, dataBlock.size());
            pathLenMap.put(node, (short) nameBytes.length);
            dataBlock.write(nameBytes);
        }

        byte[] rawDataBlock = dataBlock.toByteArray();

        // 4. Assemble final binary bytes
        int nodesOffset = 60; // magic (8) + parents (40) + control header (12)
        int dataOffset = nodesOffset + nodeCount * DirstateV2Node.NODE_SIZE;
        int totalSize = dataOffset + rawDataBlock.length;

        ByteBuffer mainBuffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        
        // Write magic
        mainBuffer.put(MAGIC);
        // Write parents
        mainBuffer.put(parent1);
        mainBuffer.put(parent2);
        // Write header control
        mainBuffer.putInt(nodeCount);
        mainBuffer.putInt(nodesOffset);
        mainBuffer.putInt(dataOffset);

        // Write nodes table
        for (int i = 0; i < nodeCount; i++) {
            TreeNode node = flatNodes.get(i);
            int offset = nodesOffset + i * DirstateV2Node.NODE_SIZE;

            // Write state, mode, size, time
            char state = '\0';
            int mode = 0;
            int size = 0;
            long time = 0;

            if (node.entry != null) {
                state = node.entry.getState();
                mode = node.entry.getMode();
                size = node.entry.getSize();
                time = node.entry.getTime();
            } else {
                // intermediate directory layout state
                state = 'd';
            }

            mainBuffer.put(offset + 0, (byte) state);
            mainBuffer.put(offset + 1, (byte) 0); // flags
            mainBuffer.putInt(offset + 4, mode);
            mainBuffer.putInt(offset + 8, size);
            mainBuffer.putInt(offset + 12, (int) (time & 0xFFFFFFFFL));
            mainBuffer.putInt(offset + 16, pathOffsetMap.get(node));
            mainBuffer.putShort(offset + 20, pathLenMap.get(node));
            mainBuffer.putInt(offset + 24, childrenStartMap.getOrDefault(node, 0));
            mainBuffer.putInt(offset + 28, childrenCountMap.getOrDefault(node, 0));
        }

        // Write data block
        mainBuffer.position(dataOffset);
        mainBuffer.put(rawDataBlock);

        return mainBuffer.array();
    }
}
