package io.github.search5.hg4j.dirstate;

import io.github.search5.hg4j.errors.HgCorruptDataException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read access to the tree of a dirstate-v2 data file, in place: the file is mapped (or held as bytes) and nodes are decoded when they are
 * asked for, the way the Rust reader works ({@code dirstate/dirstate_map.rs}, {@code on_disk.rs}). Nothing is copied into a map up front, so
 * a repository with many files costs memory only for what is looked at.
 *
 * <p>The layout is that of {@code dirstate-v2.txt}: nodes are {@value DirstateV2Node#NODE_SIZE} bytes, the children of a directory are
 * a contiguous array sorted by the bytes of their base name, and every offset is relative to the start of the data file.</p>
 */
final class DirstateV2Tree {

    private final ByteBuffer data;
    private final int rootStart;
    private final int rootCount;
    private final int entryCount;
    private final int copySourceCount;

    DirstateV2Tree(ByteBuffer data, int rootStart, int rootCount, int entryCount, int copySourceCount) {
        this.data = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.rootStart = rootStart;
        this.rootCount = rootCount;
        this.entryCount = entryCount;
        this.copySourceCount = copySourceCount;
    }

    ByteBuffer data() {
        return data;
    }

    int rootStart() {
        return rootStart;
    }

    int rootCount() {
        return rootCount;
    }

    /** The number of nodes that hold an entry (the docket says). */
    int entryCount() {
        return entryCount;
    }

    private void check(int offset, int length) throws HgCorruptDataException {
        if (offset < 0 || length < 0 || (long) offset + length > data.capacity()) {
            throw new HgCorruptDataException("dirstate-v2 data points outside the file: " + offset + "+" + length);
        }
    }

    private int pathStart(int node) {
        return data.getInt(node);
    }

    private int pathLength(int node) {
        return data.getShort(node + 4) & 0xFFFF;
    }

    private int basenameStart(int node) {
        return data.getShort(node + 6) & 0xFFFF;
    }

    private int childrenStart(int node) {
        return data.getInt(node + 14);
    }

    private int childrenCount(int node) {
        return data.getInt(node + 18);
    }

    private String fullPath(int node) throws HgCorruptDataException {
        int start = pathStart(node);
        int length = pathLength(node);
        check(start, length);
        byte[] bytes = new byte[length];
        data.get(start, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Compares the base name of a node with a path component, byte by byte as unsigned values. */
    private int compareBaseName(int node, byte[] component) {
        int start = pathStart(node) + basenameStart(node);
        int length = pathLength(node) - basenameStart(node);
        int common = Math.min(length, component.length);
        for (int i = 0; i < common; i++) {
            int a = data.get(start + i) & 0xFF;
            int b = component[i] & 0xFF;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return Integer.compare(length, component.length);
    }

    /** The node of {@code path}, or {@code -1}. */
    private int find(String path) throws HgCorruptDataException {
        int start = rootStart;
        int count = rootCount;
        int node = -1;
        for (String component : path.split("/", -1)) {
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            check(start, count * DirstateV2Node.NODE_SIZE);
            int low = 0;
            int high = count - 1;
            node = -1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int candidate = start + mid * DirstateV2Node.NODE_SIZE;
                int order = compareBaseName(candidate, bytes);
                if (order == 0) {
                    node = candidate;
                    break;
                } else if (order < 0) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            if (node < 0) {
                return -1;
            }
            start = childrenStart(node);
            count = childrenCount(node);
        }
        return node;
    }

    private Dirstate.Entry entryOf(int node) {
        DirstateV2Node view = new DirstateV2Node(data, node);
        char state = view.getState();
        if (state == '\0' || state == 'd') {
            return null; // a directory without an entry of its own
        }
        return new Dirstate.Entry(state, view.getMode(), view.getSize(), view.getMtime(), view.getMtimeNanoseconds());
    }

    /** The entry of {@code path}, {@code null} when the tree has none. */
    Dirstate.Entry get(String path) throws HgCorruptDataException {
        int node = find(path);
        return node < 0 ? null : entryOf(node);
    }

    boolean contains(String path) throws HgCorruptDataException {
        return get(path) != null;
    }

    /** Every entry, in tree order (depth first, children by the bytes of their names). */
    Iterator<Map.Entry<String, Dirstate.Entry>> entries() {
        return new Iterator<>() {
            private final Deque<int[]> stack = new ArrayDeque<>(); // {next node offset, nodes left}
            private Map.Entry<String, Dirstate.Entry> next;

            {
                stack.push(new int[] {rootStart, rootCount});
                advance();
            }

            private void advance() {
                next = null;
                try {
                    while (next == null && !stack.isEmpty()) {
                        int[] top = stack.peek();
                        if (top[1] == 0) {
                            stack.pop();
                            continue;
                        }
                        int node = top[0];
                        top[0] += DirstateV2Node.NODE_SIZE;
                        top[1]--;
                        check(node, DirstateV2Node.NODE_SIZE);
                        int children = childrenCount(node);
                        Dirstate.Entry entry = entryOf(node);
                        String path = null;
                        if (entry != null) {
                            path = fullPath(node);
                        }
                        if (children > 0) {
                            stack.push(new int[] {childrenStart(node), children});
                        }
                        if (entry != null) {
                            next = Map.entry(path, entry);
                        }
                    }
                } catch (HgCorruptDataException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Map.Entry<String, Dirstate.Entry> next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                Map.Entry<String, Dirstate.Entry> result = next;
                advance();
                return result;
            }
        };
    }

    /** The copy sources, by destination path; the docket says how many there are, so a tree without any is not walked. */
    Map<String, String> copies() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (copySourceCount == 0) {
            return out;
        }
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {rootStart, rootCount});
        while (!stack.isEmpty()) {
            int[] top = stack.peek();
            if (top[1] == 0) {
                stack.pop();
                continue;
            }
            int node = top[0];
            top[0] += DirstateV2Node.NODE_SIZE;
            top[1]--;
            check(node, DirstateV2Node.NODE_SIZE);
            int copyLength = data.getShort(node + 12) & 0xFFFF;
            if (copyLength > 0) {
                int copyStart = data.getInt(node + 8);
                check(copyStart, copyLength);
                byte[] bytes = new byte[copyLength];
                data.get(copyStart, bytes);
                out.put(fullPath(node), new String(bytes, StandardCharsets.UTF_8));
            }
            if (childrenCount(node) > 0) {
                stack.push(new int[] {childrenStart(node), childrenCount(node)});
            }
        }
        return out;
    }
}
