package io.github.search5.hg4j.dirstate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.search5.hg4j.errors.HgValidationException;

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

    public static byte[] serialize(Map<String, Dirstate.Entry> entries) throws IOException {
        Dirstate d = new Dirstate();
        for (Map.Entry<String, Dirstate.Entry> entry : entries.entrySet()) {
            d.addEntry(entry.getKey(), entry.getValue());
        }
        return serialize(d);
    }

    /**
     * Serializes memory dirstate representation into v2 binary format.
     */
    public static byte[] serialize(Dirstate dirstate) throws IOException {
        Map<String, Dirstate.Entry> entries = dirstate.getEntries();

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
        //
        // Real hg's Rust reader (dirstate/dirstate_map.rs, ChildNodesRef::get()) looks up a child
        // by name using `nodes.binary_search_by(|node| node.base_name(on_disk).cmp(base_name))` --
        // it requires each node's *own* children array to be sorted ascending by basename (see the
        // `sorted()` doc comment in on_disk.rs: "Always sorted by ascending full_path ... only the
        // base_names need to be compared during binary search"). Comparison is on the raw UTF-8
        // bytes of the basename, matching Rust's `&[u8]`/`HgPath` ordering. Every level (root list
        // and every directory's children list) MUST be sorted this way, or binary search on the
        // real-hg side silently lands on the wrong index and treats an out-of-order sibling as
        // absent -- this was verified byte-for-byte (2026-09-04) against a real hg-written
        // dirstate-v2 file: an out-of-order 2-root-file case parses fine via hg4j's own DFS-stack
        // reader (order-agnostic) but makes real hg's `hg status`/`hg verify` silently drop the
        // earlier-inserted file ("in manifest1, but not marked as tracked in p1").
        Comparator<TreeNode> byBasenameBytes = Comparator.comparing(
                n -> n.name, DirstateV2Serializer::compareUtf8Bytes);

        List<TreeNode> flatNodes = new ArrayList<>();
        List<TreeNode> rootList = new ArrayList<>(roots.values());
        rootList.sort(byBasenameBytes);

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
                childList.sort(byBasenameBytes);
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

        Map<TreeNode, Integer> copyOffsetMap = new HashMap<>();
        Map<TreeNode, Short> copyLenMap = new HashMap<>();

        for (TreeNode node : flatNodes) {
            String fullPath = fullPathMap.get(node);
            byte[] nameBytes = fullPath.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 65535) {
                throw new HgValidationException("Dirstate segment path name too long (max 65535 bytes): " + fullPath);
            }
            pathOffsetMap.put(node, dataBlock.size());
            pathLenMap.put(node, (short) nameBytes.length);
            dataBlock.write(nameBytes);

            String copySrc = dirstate.getCopyMap().get(fullPath);
            if (copySrc != null && !copySrc.isEmpty()) {
                byte[] copyBytes = copySrc.getBytes(StandardCharsets.UTF_8);
                copyOffsetMap.put(node, dataBlock.size());
                copyLenMap.put(node, (short) copyBytes.length);
                dataBlock.write(copyBytes);
            } else {
                copyOffsetMap.put(node, 0);
                copyLenMap.put(node, (short) 0);
            }
        }

        byte[] rawDataBlock = dataBlock.toByteArray();

        // 4. Assemble final binary bytes (100% native Mercurial format - no 12-byte header)
        int nodesOffset = 0; // The node array starts immediately at offset 0
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

            int nanos = 0;
            if (node.entry != null) {
                state = node.entry.getState();
                mode = node.entry.getMode();
                size = node.entry.getSize();
                time = node.entry.getTime();
                nanos = node.entry.nanos();
            }

            // 2. Set state (this automatically configures WDIR_TRACKED, P1_TRACKED, P2_INFO)
            nodeView.setState(node.entry != null ? state : 'd');

            // 3. Configure file mode/mtime metadata flags if it represents a file entry
            int flagsVal = nodeView.getFlags() & 0xFFFF;
            if (node.entry != null) {
                // Mirror real hg's own DirstateItem.v2_data() (mercurial/pure/parsers.py): the
                // HAS_MODE_AND_SIZE/HAS_MTIME bits are only set when a genuine cached stat value
                // exists -- an ambiguous entry (Dirstate.Entry#isStatAmbiguous(), most commonly a
                // same-second racy write, encoded here as size=-1 and/or time=AMBIGUOUS_TIME)
                // must round-trip back out with those bits OMITTED, not with a fabricated
                // concrete 0. Setting them unconditionally previously turned "ambiguous, needs a
                // real content comparison" into a definite (and wrong) "this file is 0 bytes as
                // of epoch" claim the instant ANY hg4j write command did a read-modify-write of a
                // dirstate-v2 file that happened to contain such an entry for an ENTIRELY
                // different, untouched path -- confirmed live: real hg's own `hg status`/`hg
                // commit` afterward reported that untouched file as spuriously modified.
                if (size >= 0) {
                    flagsVal |= DirstateV2Node.HAS_MODE_AND_SIZE;
                    // Real hg only derives the exec/symlink bits from `mode` INSIDE the "has a
                    // real mode/size" branch (mode is `None` otherwise) -- gate identically so an
                    // ambiguous entry's stray mode value (irrelevant once HAS_MODE_AND_SIZE is
                    // unset) can never leak a spurious exec/symlink bit.
                    if ((mode & 0111) != 0) {
                        flagsVal |= DirstateV2Node.MODE_EXEC_PERM;
                    }
                    if ((mode & 0120000) == 0120000) {
                        flagsVal |= DirstateV2Node.MODE_IS_SYMLINK;
                    }
                }
                if (time != Dirstate.Entry.AMBIGUOUS_TIME) {
                    flagsVal |= DirstateV2Node.HAS_MTIME;
                }
            }
            nodeView.setFlags((short) flagsVal);

            // 4. Set size, mode (via adapter), time. setMode() itself (re-)derives the
            // exec/symlink flag bits from the raw `mode` value, so it must be skipped whenever
            // HAS_MODE_AND_SIZE was deliberately left unset above (size < 0) -- otherwise it
            // would silently re-introduce a stray exec/symlink bit for an ambiguous entry that
            // real hg would never emit.
            if (size >= 0) {
                nodeView.setMode(mode);
            }
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
            int copyLen = copyLenMap.getOrDefault(node, (short) 0);
            int copyOffset = copyOffsetMap.getOrDefault(node, 0);
            nodeView.setCopySourceLen((short) copyLen);
            if (copyLen > 0) {
                nodeView.setCopySourceOffset(dataOffset + copyOffset);
            } else {
                nodeView.setCopySourceOffset(0);
            }
            nodeView.setMtimeNanoseconds(nanos);
        }

        // Write data block
        mainBuffer.position(dataOffset);
        mainBuffer.put(rawDataBlock);

        return mainBuffer.array();
    }

    /**
     * Lexicographic comparison of two names' raw UTF-8 bytes (unsigned), matching Rust's
     * {@code &[u8]}/{@code HgPath} ordering used by real hg's {@code binary_search_by} lookup.
     * Java's {@code String#compareTo} compares UTF-16 code units instead, which agrees with byte
     * order for ASCII but not necessarily beyond it -- this compares the actual encoded bytes hg4j
     * writes to disk, since that is what real hg's reader binary-searches over.
     */
    private static int compareUtf8Bytes(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            int cmp = Integer.compareUnsigned(ab[i] & 0xFF, bb[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(ab.length, bb.length);
    }
}
