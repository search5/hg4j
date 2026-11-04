package io.github.search5.hg4j.dirstate;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Computes what has to be appended to a dirstate-v2 data file to describe the changes made to an entry map, the way the Rust writer does
 * ({@code on_disk.rs}, {@code DirstateMapWriteMode::Append}): the nodes of changed paths and the arrays that contain them are written anew
 * at the end of the file, every other node is copied as it is (its offsets still point into the old part), and the old arrays become
 * unreachable bytes, which are counted so that a later write can decide that the file is worth replacing.
 */
final class DirstateV2Appender {

    /** What the append produced. */
    record Result(byte[] data, int rootStart, int rootCount, long unreachableAdded) {}

    private static final int NODE = DirstateV2Node.NODE_SIZE;

    private static final class Trie {
        final Map<String, Trie> kids = new LinkedHashMap<>();
    }

    private final DirstateV2Tree tree;
    private final ByteBuffer old;
    private final long baseLength;
    private final Function<String, Dirstate.Entry> currentEntry;
    private final Map<String, String> currentCopies;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long unreachable;

    DirstateV2Appender(DirstateV2Tree tree, long baseLength, Function<String, Dirstate.Entry> currentEntry,
            Map<String, String> currentCopies) {
        this.tree = tree;
        this.old = tree.data();
        this.baseLength = baseLength;
        this.currentEntry = currentEntry;
        this.currentCopies = currentCopies;
    }

    Result append(Set<String> changedPaths) {
        Trie root = new Trie();
        for (String path : changedPaths) {
            Trie at = root;
            for (String component : path.split("/", -1)) {
                at = at.kids.computeIfAbsent(component, k -> new Trie());
            }
        }
        if (root.kids.isEmpty()) {
            return new Result(new byte[0], tree.rootStart(), tree.rootCount(), 0);
        }
        int[] array = rebuild("", tree.rootStart(), tree.rootCount(), root);
        return new Result(out.toByteArray(), array[0], array[1], unreachable);
    }

    private long here() {
        return baseLength + out.size();
    }

    private int appendBytes(byte[] bytes) {
        int at = (int) here();
        out.write(bytes, 0, bytes.length);
        return at;
    }

    private static int compare(byte[] a, byte[] b) {
        int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            int c = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private byte[] baseName(int node) {
        int start = old.getInt(node) + (old.getShort(node + 6) & 0xFFFF);
        int length = (old.getShort(node + 4) & 0xFFFF) - (old.getShort(node + 6) & 0xFFFF);
        byte[] bytes = new byte[length];
        old.get(start, bytes);
        return bytes;
    }

    /** Writes the array for the directory {@code dir}; returns {start, count}. */
    private int[] rebuild(String dir, int oldStart, int oldCount, Trie changes) {
        record Slot(byte[] name, byte[] struct) {}
        List<Slot> slots = new ArrayList<>();
        java.util.Set<String> handled = new TreeSet<>();
        for (int i = 0; i < oldCount; i++) {
            int node = oldStart + i * NODE;
            byte[] name = baseName(node);
            String nameText = new String(name, StandardCharsets.UTF_8);
            Trie child = changes.kids.get(nameText);
            byte[] struct;
            if (child == null) {
                struct = new byte[NODE];
                old.get(node, struct);
            } else {
                handled.add(nameText);
                struct = rebuildNode(dir, nameText, node, child);
            }
            if (struct != null) {
                slots.add(new Slot(name, struct));
            }
        }
        for (Map.Entry<String, Trie> added : changes.kids.entrySet()) {
            if (handled.contains(added.getKey())) {
                continue;
            }
            byte[] struct = rebuildNode(dir, added.getKey(), -1, added.getValue());
            if (struct != null) {
                slots.add(new Slot(added.getKey().getBytes(StandardCharsets.UTF_8), struct));
            }
        }
        if (oldCount > 0) {
            unreachable += (long) oldCount * NODE;
        }
        slots.sort(Comparator.comparing(Slot::name, DirstateV2Appender::compare));
        if (slots.isEmpty()) {
            return new int[] {0, 0};
        }
        ByteBuffer array = ByteBuffer.allocate(slots.size() * NODE);
        for (Slot slot : slots) {
            array.put(slot.struct());
        }
        return new int[] {appendBytes(array.array()), slots.size()};
    }

    /** The new struct of a changed node, or {@code null} when the node is gone. */
    private byte[] rebuildNode(String dir, String name, int oldNode, Trie changes) {
        String path = dir.isEmpty() ? name : dir + "/" + name;
        int childStart = 0;
        int childCount = 0;
        if (oldNode >= 0) {
            childStart = old.getInt(oldNode + 14);
            childCount = old.getInt(oldNode + 18);
        }
        if (!changes.kids.isEmpty()) {
            int[] array = rebuild(path, childStart, childCount, changes);
            childStart = array[0];
            childCount = array[1];
        }
        Dirstate.Entry entry = currentEntry.apply(path);
        String copy = currentCopies.get(path);
        if (copy != null && copy.isEmpty()) {
            copy = null;
        }
        if (entry == null && childCount == 0 && copy == null) {
            if (oldNode >= 0) {
                unreachable += (old.getShort(oldNode + 4) & 0xFFFF) + (old.getShort(oldNode + 12) & 0xFFFF);
            }
            return null;
        }
        ByteBuffer struct = ByteBuffer.allocate(NODE).order(ByteOrder.BIG_ENDIAN);
        DirstateV2Node view = new DirstateV2Node(struct, 0);
        DirstateV2Serializer.fillEntryFields(view, entry);
        // path
        if (oldNode >= 0) {
            view.setPathOffset(old.getInt(oldNode));
            view.setPathLen(old.getShort(oldNode + 4));
        } else {
            byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
            view.setPathOffset(appendBytes(bytes));
            view.setPathLen((short) bytes.length);
        }
        view.setBasenameStart((short) (path.lastIndexOf('/') + 1));
        // copy source
        int oldCopyLen = oldNode >= 0 ? old.getShort(oldNode + 12) & 0xFFFF : 0;
        if (copy == null) {
            unreachable += oldCopyLen;
        } else {
            byte[] bytes = copy.getBytes(StandardCharsets.UTF_8);
            boolean same = false;
            if (oldCopyLen == bytes.length) {
                byte[] was = new byte[oldCopyLen];
                old.get(old.getInt(oldNode + 8), was);
                same = java.util.Arrays.equals(was, bytes);
            }
            if (same) {
                view.setCopySourceOffset(old.getInt(oldNode + 8));
            } else {
                unreachable += oldCopyLen;
                view.setCopySourceOffset(appendBytes(bytes));
            }
            view.setCopySourceLen((short) bytes.length);
        }
        // children and their counts
        view.setChildrenStart(childStart);
        view.setChildrenCount(childCount);
        int descendants = 0;
        int tracked = 0;
        if (childCount > 0) {
            ByteBuffer source = childStart >= baseLength ? ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.BIG_ENDIAN) : old;
            int base = childStart >= baseLength ? (int) (childStart - baseLength) : childStart;
            for (int i = 0; i < childCount; i++) {
                int child = base + i * NODE;
                DirstateV2Node childView = new DirstateV2Node(source, child);
                char state = childView.getState();
                if (state != '\0') {
                    descendants++;
                    if (state != 'r') {
                        tracked++;
                    }
                }
                descendants += childView.getDescendantsWithEntryCount();
                tracked += childView.getTrackedDescendants();
            }
        }
        view.setDescendantsWithEntryCount(descendants);
        view.setTrackedDescendants(tracked);
        return struct.array();
    }
}
