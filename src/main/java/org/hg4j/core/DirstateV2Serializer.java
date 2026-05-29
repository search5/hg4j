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

    private static class TreeNode {
        final String name;
        Dirstate.Entry entry;
        final Map<String, TreeNode> children = new LinkedHashMap<>();

        TreeNode(String name) {
            this.name = name;
        }

        int getDescendantsWithEntryCount() {
            int count = 0;
            for (TreeNode child : children.values()) {
                if (child.entry != null) {
                    count++;
                }
                count += child.getDescendantsWithEntryCount();
            }
            return count;
        }

        int getTrackedDescendantsCount() {
            int count = 0;
            for (TreeNode child : children.values()) {
                if (child.entry != null && child.entry.getState() != 'r') {
                    count++;
                }
                count += child.getTrackedDescendantsCount();
            }
            return count;
        }
    }

    /**
     * Serializes memory dirstate representation into v2 binary format.
     */
    public static byte[] serialize(Map<String, Dirstate.Entry> entries) throws IOException {

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
        List<TreeNode> flatNodes = new ArrayList<>();
        List<TreeNode> rootList = new ArrayList<>(roots.values());
        
        flatNodes.addAll(rootList);

        Queue<TreeNode> queue = new LinkedList<>(rootList);

        Map<TreeNode, Integer> childrenStartMap = new HashMap<>();
        Map<TreeNode, Integer> childrenCountMap = new HashMap<>();
        Map<TreeNode, String> fullPathMap = new HashMap<>();

        for (TreeNode root : rootList) {
            fullPathMap.put(root, root.name);
        }

        while (!queue.isEmpty()) {
            TreeNode parent = queue.poll();
            if (!parent.children.isEmpty()) {
                List<TreeNode> childList = new ArrayList<>(parent.children.values());
                childrenStartMap.put(parent, flatNodes.size() * DirstateV2Node.NODE_SIZE); // bytes offset to child nodes
                childrenCountMap.put(parent, childList.size());

                for (TreeNode child : childList) {
                    fullPathMap.put(child, fullPathMap.get(parent) + "/" + child.name);
                    flatNodes.add(child);
                    queue.offer(child);
                }
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
            String fullPath = fullPathMap.get(node);
            byte[] nameBytes = fullPath.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 65535) {
                throw new org.hg4j.errors.HgValidationException("Dirstate segment path name too long (max 65535 bytes): " + fullPath);
            }
            pathOffsetMap.put(node, dataBlock.size());
            pathLenMap.put(node, (short) nameBytes.length);
            dataBlock.write(nameBytes);
        }

        byte[] rawDataBlock = dataBlock.toByteArray();

        // 4. Assemble final binary bytes (100% native Mercurial format - no 12-byte header)
        int nodesOffset = 0; // 0번 오프셋부터 바로 노드 배열 시작
        int dataOffset = nodeCount * DirstateV2Node.NODE_SIZE;
        int totalSize = dataOffset + rawDataBlock.length;

        ByteBuffer mainBuffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        // Write nodes table
        for (int i = 0; i < nodeCount; i++) {
            TreeNode node = flatNodes.get(i);
            int offset = nodesOffset + i * DirstateV2Node.NODE_SIZE;

            DirstateV2Node nodeView = new DirstateV2Node(mainBuffer, offset);

            // 1. Basic properties
            char state = '\0';
            int mode = 0;
            int size = 0;
            long time = 0;

            if (node.entry != null) {
                state = node.entry.getState();
                mode = node.entry.getMode();
                size = node.entry.getSize();
                time = node.entry.getTime();
            }

            // 2. Set state (this automatically configures WDIR_TRACKED, P1_TRACKED, P2_INFO)
            nodeView.setState(node.entry != null ? state : 'd');

            // 3. Configure file mode/mtime metadata flags if it represents a file entry
            int flagsVal = nodeView.getFlags() & 0xFFFF;
            if (node.entry != null) {
                flagsVal |= DirstateV2Node.HAS_MODE_AND_SIZE;
                flagsVal |= DirstateV2Node.HAS_MTIME;

                // Executable or Symlink flags conversion
                if ((mode & 0111) != 0) {
                    flagsVal |= DirstateV2Node.MODE_EXEC_PERM;
                }
                if ((mode & 0120000) == 0120000) {
                    flagsVal |= DirstateV2Node.MODE_IS_SYMLINK;
                }
            }
            nodeView.setFlags((short) flagsVal);

            // 4. Set size, mode (via adapter), time
            nodeView.setMode(mode);
            nodeView.setSize(size);
            nodeView.setMtime(time);

            // 5. Paths & tree structures
            nodeView.setPathOffset(dataOffset + pathOffsetMap.get(node));
            nodeView.setPathLen(pathLenMap.get(node));
            nodeView.setChildrenStart(childrenStartMap.getOrDefault(node, 0));
            nodeView.setChildrenCount(childrenCountMap.getOrDefault(node, 0));

            // 6. Basename start calculation
            String fullPath = fullPathMap.get(node);
            int lastSlash = fullPath.lastIndexOf('/');
            short basenameStart = (short) (lastSlash == -1 ? 0 : lastSlash + 1);
            nodeView.setBasenameStart(basenameStart);

            // 7. Extra V2 specific metadata fields
            nodeView.setDescendantsWithEntryCount(node.getDescendantsWithEntryCount());
            nodeView.setTrackedDescendants(node.getTrackedDescendantsCount());
            nodeView.setCopySourceLen((short) 0);
            nodeView.setCopySourceOffset(0);
            nodeView.setMtimeNanoseconds(0);
        }

        // Write data block
        mainBuffer.position(dataOffset);
        mainBuffer.put(rawDataBlock);

        return mainBuffer.array();
    }
}
