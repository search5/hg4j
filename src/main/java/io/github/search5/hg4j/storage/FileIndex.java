package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/**
 * Reader/writer for the {@code fileindex-v1} requirement's on-disk structure — a persistent radix
 * trie (docket + list/meta/tree companion files, magic {@code "fileindex-v1"}) that real hg uses
 * in an {@code exp-revlogv2.2} repository <em>instead of</em> {@code fncache} to record which
 * tracked-file store paths exist, so a reader doesn't have to walk the store directory tree or
 * consult a flat text list to discover them.
 *
 * <p>Byte layout ported directly from {@code mercurial/store_utils/file_index_util.py} (real
 * Mercurial 7.2.4 source) and cross-checked against a real Mercurial 7.2.4 build with the Rust
 * extensions enabled (the plain pure-Python {@code hg} on this machine can't even create a
 * repository with this requirement — see {@code docker/hg-rust-7.2.4/Dockerfile}). See
 * src/test/resources/fixtures/revlogv2-general/README.md for the exact fixture this was
 * cross-checked against.</p>
 *
 * <p><strong>Write strategy — always a full rebuild, never the incremental copy-on-write append
 * real hg's own {@code MutableTree.insert()} does against a base tree.</strong> Real hg's
 * algorithm keeps growing the tree file by appending new/copied nodes and only periodically
 * "vacuums" (fully rebuilds) it once enough of it has become unreachable garbage. hg4j instead
 * always does what that vacuum path does: read every currently-tracked path, add the new ones,
 * and write brand-new list/meta/tree files + a docket with fresh random UUIDs (matching the same
 * {@code {radix}-{uuid}} convention individual revlog dockets use). The trie-building algorithm
 * itself ({@link TrieBuilder}) IS the real {@code MutableTree.insert()}/{@code serialize()}
 * algorithm running with no base tree (equivalent to real hg's own {@code MutableTree(base=None)}
 * case, per that class's own doctests) — only the "always rebuild instead of incrementally
 * appending to a base" policy differs, so the byte format produced is exactly what real hg's own
 * vacuum operation would produce for the same path set.</p>
 */
public final class FileIndex {

    private static final byte[] MARKER = "fileindex-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int DOCKET_FIXED_SIZE = MARKER.length + 4 * 3 + 8 * 3 + 4 * 3; // 60
    private static final int UID_SIZE = 8;
    private static final long ROOT_TOKEN = 0;

    private FileIndex() {
    }

    /**
     * Immutable snapshot of a store's fileindex (docket bytes + the exact companion files it
     * currently references), for callers -- like {@code CommitCommand} -- that need to roll a
     * failed transaction back to a prior state. {@link #writeTrackedPaths} deletes an old
     * generation's companion files as soon as the new docket lands, so a caller that might still
     * need to restore the old generation must snapshot it first, before calling
     * {@code writeTrackedPaths}.
     */
    public static final class Snapshot {
        private final byte[] docketBytes; // null if no fileindex docket existed
        private final Map<String, byte[]> companionFiles; // filename -> full bytes

        private Snapshot(byte[] docketBytes, Map<String, byte[]> companionFiles) {
            this.docketBytes = docketBytes;
            this.companionFiles = companionFiles;
        }
    }

    /** Captures the current on-disk state of {@code storeDir}'s fileindex so it can later be
     * restored via {@link #restore}, even after a subsequent {@link #writeTrackedPaths} call has
     * deleted the old generation's companion files. */
    public static Snapshot snapshot(File storeDir) throws IOException {
        File docketFile = new File(storeDir, "fileindex");
        if (!docketFile.exists()) {
            return new Snapshot(null, Collections.emptyMap());
        }
        byte[] docketBytes = Files.readAllBytes(docketFile.toPath());
        Docket docket = Docket.parse(docketBytes);
        Map<String, byte[]> companionFiles = new LinkedHashMap<>();
        for (String name : companionFileNames(docket)) {
            File f = new File(storeDir, name);
            if (f.exists()) {
                companionFiles.put(name, Files.readAllBytes(f.toPath()));
            }
        }
        return new Snapshot(docketBytes, companionFiles);
    }

    /** Restores {@code storeDir}'s fileindex to the state captured by {@code snapshot}, removing
     * any companion files the current (about-to-be-rolled-back) generation left behind that
     * aren't part of the snapshot. */
    public static void restore(File storeDir, Snapshot snapshot) throws IOException {
        File docketFile = new File(storeDir, "fileindex");
        Set<String> currentCompanionNames = new LinkedHashSet<>();
        if (docketFile.exists()) {
            try {
                currentCompanionNames.addAll(companionFileNames(Docket.parse(Files.readAllBytes(docketFile.toPath()))));
            } catch (IOException ignored) {
                // Current docket is corrupt/partial -- nothing useful to clean up by name from it.
            }
        }

        if (snapshot.docketBytes == null) {
            Files.deleteIfExists(docketFile.toPath());
        } else {
            SafeFileIO.writeAtomic(docketFile, snapshot.docketBytes);
            for (Map.Entry<String, byte[]> e : snapshot.companionFiles.entrySet()) {
                SafeFileIO.writeAtomic(new File(storeDir, e.getKey()), e.getValue());
            }
        }

        for (String name : currentCompanionNames) {
            if (!snapshot.companionFiles.containsKey(name)) {
                new File(storeDir, name).delete();
            }
        }
    }

    private static List<String> companionFileNames(Docket docket) {
        return Arrays.asList(
                "fileindex-list." + docket.listFileId,
                "fileindex-meta." + docket.metaFileId,
                "fileindex-tree." + docket.treeFileId);
    }

    /** Reads the currently-tracked store paths from {@code storeDir}'s fileindex, or an empty set
     * if no {@code fileindex} docket exists there yet. */
    public static Set<String> readTrackedPaths(File storeDir) throws IOException {
        File docketFile = new File(storeDir, "fileindex");
        if (!docketFile.exists()) {
            return new LinkedHashSet<>();
        }
        byte[] docketBytes = Files.readAllBytes(docketFile.toPath());
        Docket docket = Docket.parse(docketBytes);

        byte[] listBytes = readCompanion(storeDir, "list", docket.listFileId, docket.listFileSize);
        byte[] metaBytes = readCompanion(storeDir, "meta", docket.metaFileId, docket.metaFileSize);

        Set<String> paths = new LinkedHashSet<>();
        int entrySize = 8; // >IHH
        int count = metaBytes.length / entrySize;
        for (int token = 1; token < count; token++) { // token 0 is the reserved root entry
            ByteBuffer buf = ByteBuffer.wrap(metaBytes, token * entrySize, entrySize);
            int offset = buf.getInt();
            int length = buf.getShort() & 0xFFFF;
            if (length == 0) {
                continue;
            }
            paths.add(new String(listBytes, offset, length, StandardCharsets.UTF_8));
        }
        return paths;
    }

    /**
     * Rewrites the fileindex so it tracks exactly {@code paths} (a full replacement, not a merge
     * — callers pass the complete current tracked-path set). No-op (leaves any existing fileindex
     * untouched) if {@code paths} is empty and no fileindex exists yet, matching a freshly
     * `hg init`'d repository having no fileindex file at all until the first commit.
     */
    public static void writeTrackedPaths(File storeDir, Collection<String> paths) throws IOException {
        File docketFile = new File(storeDir, "fileindex");
        if (paths.isEmpty() && !docketFile.exists()) {
            return;
        }

        List<String> sorted = new ArrayList<>(new TreeSet<>(paths));

        // Build the list file (NUL-terminated UTF-8 path buffer) and meta array (token 0 = the
        // reserved empty root entry; matches Metadata.from_path(b"", 0) in file_index_util.py).
        ByteArrayOutputStream listOut = new ByteArrayOutputStream();
        List<int[]> metaEntries = new ArrayList<>(); // {offset, length, dirnameLength}
        metaEntries.add(new int[]{0, 0, 0});
        Map<String, Integer> tokenByPath = new LinkedHashMap<>();
        int token = 1;
        for (String path : sorted) {
            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            int offset = listOut.size();
            listOut.write(pathBytes, 0, pathBytes.length);
            listOut.write(0); // NUL terminator
            int slash = path.lastIndexOf('/');
            int dirnameLength = slash < 0 ? 0 : slash;
            metaEntries.add(new int[]{offset, pathBytes.length, dirnameLength});
            tokenByPath.put(path, token);
            token++;
        }
        byte[] listBytes = listOut.toByteArray();

        ByteBuffer metaBuf = ByteBuffer.allocate(metaEntries.size() * 8);
        for (int[] entry : metaEntries) {
            metaBuf.putInt(entry[0]);
            metaBuf.putShort((short) entry[1]);
            metaBuf.putShort((short) entry[2]);
        }
        byte[] metaBytes = metaBuf.array();

        TrieBuilder trie = new TrieBuilder();
        for (Map.Entry<String, Integer> e : tokenByPath.entrySet()) {
            trie.insert(e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
        }
        byte[] treeBytes = trie.serialize();
        long treeRootPointer = 0; // always a fresh rebuild, so the root always starts the file

        SecureRandom rnd = new SecureRandom();
        String listUid = randomUid(rnd);
        String metaUid = randomUid(rnd);
        String treeUid = randomUid(rnd);

        File newListFile = new File(storeDir, "fileindex-list." + listUid);
        File newMetaFile = new File(storeDir, "fileindex-meta." + metaUid);
        File newTreeFile = new File(storeDir, "fileindex-tree." + treeUid);
        SafeFileIO.writeAtomic(newListFile, listBytes);
        SafeFileIO.writeAtomic(newMetaFile, metaBytes);
        SafeFileIO.writeAtomic(newTreeFile, treeBytes);

        byte[] docketBytes = Docket.serialize(listBytes.length, metaBytes.length, treeBytes.length,
                listUid, metaUid, treeUid, treeRootPointer, 0);

        // Discover old companion files (previous UUIDs) before overwriting the docket, so they
        // can be removed once the new ones are safely on disk -- real hg would keep them around
        // as GC-able "garbage" entries; hg4j just deletes them immediately, which is safe since
        // the new docket is written last and nothing else references the old UUIDs once it lands.
        Docket oldDocket = docketFile.exists() ? Docket.parse(Files.readAllBytes(docketFile.toPath())) : null;

        SafeFileIO.writeAtomic(docketFile, docketBytes);

        if (oldDocket != null) {
            deleteIfDifferent(storeDir, "fileindex-list.", oldDocket.listFileId, listUid);
            deleteIfDifferent(storeDir, "fileindex-meta.", oldDocket.metaFileId, metaUid);
            deleteIfDifferent(storeDir, "fileindex-tree.", oldDocket.treeFileId, treeUid);
        }
    }

    private static void deleteIfDifferent(File storeDir, String prefix, String oldUid, String newUid) {
        if (oldUid.equals(newUid)) {
            return;
        }
        new File(storeDir, prefix + oldUid).delete();
    }

    private static byte[] readCompanion(File storeDir, String kind, String uid, int usedSize) throws IOException {
        File f = new File(storeDir, "fileindex-" + kind + "." + uid);
        if (!f.exists()) {
            throw new HgCorruptDataException("fileindex companion file missing: " + f);
        }
        byte[] full = Files.readAllBytes(f.toPath());
        if (usedSize > full.length) {
            throw new HgCorruptDataException("fileindex companion file shorter than docket claims: " + f);
        }
        return usedSize == full.length ? full : Arrays.copyOf(full, usedSize);
    }

    private static String randomUid(SecureRandom rnd) {
        byte[] raw = new byte[UID_SIZE / 2];
        rnd.nextBytes(raw);
        StringBuilder sb = new StringBuilder(UID_SIZE);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** The 60-byte fixed docket header (marker + 3 file sizes + 3 UUIDs + tree root/unused/flags)
     * followed by an (always-empty, for hg4j's writer) garbage list. */
    private static final class Docket {
        int listFileSize;
        int metaFileSize;
        String listFileId;
        String metaFileId;
        String treeFileId;

        static Docket parse(byte[] data) throws IOException {
            if (data.length < DOCKET_FIXED_SIZE) {
                throw new HgCorruptDataException("file index docket too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte[] marker = new byte[MARKER.length];
            buf.get(marker);
            if (!Arrays.equals(marker, MARKER)) {
                throw new HgCorruptDataException("file index docket has wrong marker");
            }
            Docket d = new Docket();
            d.listFileSize = buf.getInt();
            d.metaFileSize = buf.getInt();
            buf.getInt(); // tree_file_size -- unused by the reader (tree is read fully by uid)
            d.listFileId = readUid(buf);
            d.metaFileId = readUid(buf);
            d.treeFileId = readUid(buf);
            // tree_root_pointer, tree_unused_bytes, reserved_flags: not needed to read tokens
            return d;
        }

        static byte[] serialize(int listFileSize, int metaFileSize, int treeFileSize,
                                 String listUid, String metaUid, String treeUid,
                                 long treeRootPointer, long treeUnusedBytes) {
            ByteBuffer buf = ByteBuffer.allocate(DOCKET_FIXED_SIZE + 8); // + empty garbage list header
            buf.put(MARKER);
            buf.putInt(listFileSize);
            buf.putInt(metaFileSize);
            buf.putInt(treeFileSize);
            buf.put(listUid.getBytes(StandardCharsets.US_ASCII));
            buf.put(metaUid.getBytes(StandardCharsets.US_ASCII));
            buf.put(treeUid.getBytes(StandardCharsets.US_ASCII));
            buf.putInt((int) treeRootPointer);
            buf.putInt((int) treeUnusedBytes);
            buf.putInt(0); // reserved_flags
            buf.putInt(0); // garbage list: num_entries
            buf.putInt(0); // garbage list: path_buf_size
            return buf.array();
        }

        private static String readUid(ByteBuffer buf) {
            byte[] uid = new byte[UID_SIZE];
            buf.get(uid);
            return new String(uid, StandardCharsets.US_ASCII);
        }
    }

    /**
     * Builds a compressed radix trie and serializes it to a tree-file buffer, following real hg's
     * {@code MutableTree} algorithm (file_index_util.py) run with no base tree — i.e. exactly the
     * {@code MutableTree(base=None)} case from that class's own doctests. Ported field-for-field:
     * {@code MutableTreeNode} → {@link Node}, {@code MutableTreeChild} → {@link Child},
     * {@code insert()} and {@code serialize()} are direct translations.
     */
    private static final class TrieBuilder {
        private final List<Node> nodes = new ArrayList<>();

        TrieBuilder() {
            nodes.add(new Node(ROOT_TOKEN, new byte[0])); // index 0: root, always present
        }

        void insert(byte[] path, long token) {
            if (path.length == 0) {
                throw new IllegalArgumentException("empty path");
            }
            Node node = nodes.get(0);
            int position = 0;
            while (true) {
                Child child = node.findChild(path[position] & 0xFF);
                if (child == null) {
                    break;
                }
                int childIndex = child.nodePointer;
                Node childNode = nodes.get(childIndex);
                byte[] label = childNode.label;
                int length = commonPrefixLength(path, position, label);
                if (length != label.length) {
                    childNode.label = Arrays.copyOfRange(label, length, label.length);
                    Node intermediate = new Node(token, Arrays.copyOfRange(path, position, position + length));
                    int intermediateIndex = nodes.size();
                    nodes.add(intermediate);
                    intermediate.children.add(new Child(childNode.label[0] & 0xFF, childIndex));
                    child.nodePointer = intermediateIndex;
                    node = intermediate;
                    position += length;
                    break;
                }
                node = childNode;
                position += label.length;
                if (position == path.length) {
                    break;
                }
            }
            int remainderStart = position;
            while (remainderStart < path.length) {
                int n = Math.min(path.length - remainderStart, 255);
                byte[] label = Arrays.copyOfRange(path, remainderStart, remainderStart + n);
                remainderStart += n;
                node.children.add(new Child(label[0] & 0xFF, nodes.size()));
                Node fresh = new Node(token, label);
                nodes.add(fresh);
                node = fresh;
            }
            node.token = token;
        }

        byte[] serialize() {
            GrowableBuffer buffer = new GrowableBuffer();
            Deque<long[]> stack = new ArrayDeque<>(); // {nodeIndex, fixupOffset}
            stack.push(new long[]{0, 0});
            while (!stack.isEmpty()) {
                long[] top = stack.pop();
                int index = (int) top[0];
                int fixupOffset = (int) top[1];
                if (index != 0) {
                    buffer.patchInt(fixupOffset, buffer.size());
                }
                Node node = nodes.get(index);
                buffer.appendInt((int) node.token);
                buffer.appendByte(node.label.length);
                buffer.appendByte(node.children.size());
                for (Child c : node.children) {
                    buffer.appendByte(c.firstChar);
                }
                for (Child c : node.children) {
                    Node childNode = nodes.get(c.nodePointer);
                    if (childNode.children.isEmpty()) {
                        buffer.appendInt((int) (0x80000000L | childNode.token));
                    } else {
                        int childFixupOffset = buffer.size();
                        stack.push(new long[]{c.nodePointer, childFixupOffset});
                        buffer.appendInt(0xFFFFFFFF); // placeholder, patched when this child is popped
                    }
                }
            }
            return buffer.toByteArray();
        }

        private static int commonPrefixLength(byte[] path, int pathOffset, byte[] label) {
            int n = Math.min(path.length - pathOffset, label.length);
            int i = 0;
            while (i < n && path[pathOffset + i] == label[i]) {
                i++;
            }
            return i;
        }

        private static final class Node {
            long token;
            byte[] label;
            final List<Child> children = new ArrayList<>();

            Node(long token, byte[] label) {
                this.token = token;
                this.label = label;
            }

            Child findChild(int firstChar) {
                for (Child c : children) {
                    if (c.firstChar == firstChar) {
                        return c;
                    }
                }
                return null;
            }
        }

        private static final class Child {
            final int firstChar;
            int nodePointer;

            Child(int firstChar, int nodePointer) {
                this.firstChar = firstChar;
                this.nodePointer = nodePointer;
            }
        }
    }

    /** Minimal growable byte buffer supporting patching an already-written 4-byte int (needed for
     * the tree serializer's forward-reference pointer fixups) -- {@code ByteArrayOutputStream}
     * cannot patch previously written bytes. */
    private static final class GrowableBuffer {
        private byte[] data = new byte[256];
        private int size = 0;

        int size() {
            return size;
        }

        void appendByte(int value) {
            ensureCapacity(size + 1);
            data[size++] = (byte) value;
        }

        void appendInt(int value) {
            ensureCapacity(size + 4);
            data[size++] = (byte) (value >>> 24);
            data[size++] = (byte) (value >>> 16);
            data[size++] = (byte) (value >>> 8);
            data[size++] = (byte) value;
        }

        void patchInt(int offset, int value) {
            data[offset] = (byte) (value >>> 24);
            data[offset + 1] = (byte) (value >>> 16);
            data[offset + 2] = (byte) (value >>> 8);
            data[offset + 3] = (byte) value;
        }

        private void ensureCapacity(int needed) {
            if (needed > data.length) {
                int newLen = data.length * 2;
                while (newLen < needed) {
                    newLen *= 2;
                }
                data = Arrays.copyOf(data, newLen);
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
