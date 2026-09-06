package io.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgValidationException;
import java.nio.charset.StandardCharsets;

/**
 * Targeted coverage tests for {@link DirstateV2Serializer}, filling in the lines/branches that
 * {@link DirstateV2ParserTest}, {@link DirstateV2ParserCoverageTest} and the other existing
 * suites that happen to call {@code serialize(...)} do not exercise:
 * <ul>
 *     <li>the implicit default constructor (the class is used purely via its static
 *     {@code serialize(...)} methods elsewhere, so no test ever instantiates it directly)</li>
 *     <li>the over-length segment-path guard ({@code nameBytes.length > 65535}), which throws
 *     {@link HgValidationException}</li>
 *     <li>a copy-source entry that is present in the copy map but maps to an empty string --
 *     the {@code copySrc != null && !copySrc.isEmpty()} guard's "present but empty" branch</li>
 *     <li>a symlink-mode entry ({@code mode & 0120000 == 0120000}), which sets
 *     {@link DirstateV2Node#MODE_IS_SYMLINK}</li>
 * </ul>
 * See the class-level report for one branch this suite deliberately does not attempt to cover:
 * the {@code current != null} false-branch at line 78, which is unreachable dead code (see the
 * top-level task report for the reasoning).
 */
public class DirstateV2SerializerCoverageTest {

    @Test
    public void testDefaultConstructor_isUsable() {
        // DirstateV2Serializer is a static-method-only utility class with no declared
        // constructor, so javac emits an implicit public no-arg one. Nothing else in the
        // codebase ever calls `new DirstateV2Serializer()`; instantiate it directly here purely
        // to exercise that generated constructor for coverage purposes.
        DirstateV2Serializer serializer = new DirstateV2Serializer();
        assertNotNull(serializer);
    }

    /**
     * Backlog #37 regression (2026-09-04): real hg's Rust reader looks up a child node within its
     * parent's children array via {@code binary_search_by(|node| node.base_name(on_disk).cmp
     * (base_name))} (dirstate/dirstate_map.rs) -- it REQUIRES each node's own children (root nodes
     * included) to be sorted ascending by basename bytes, not merely self-consistent offsets. Root
     * caused byte-for-byte (2026-09-04) against a real hg-written dirstate-v2 file: hg4j wrote root
     * nodes in {@code LinkedHashMap} insertion order (here, "seed.txt" before "hg4j.txt" -- NOT
     * ascending, since 'h' &lt; 's') and, while hg4j's own DFS-stack reader parsed it back fine
     * (order-agnostic), real hg's {@code hg status}/{@code hg verify} silently could not find
     * "seed.txt" via binary search and reported it as "not marked as tracked in p1". Fixed by
     * sorting every level (root list and each directory's children list) by the UTF-8 bytes of the
     * basename before serializing.
     */
    @Test
    public void testSerialize_rootNodesSortedByBasenameBytesRegardlessOfInsertionOrder() throws Exception {
        Dirstate dirstate = new Dirstate();
        // Insertion order deliberately descending ('s' > 'h') -- the bug only manifested when
        // insertion order disagreed with sort order.
        dirstate.addEntry("seed.txt", new Dirstate.Entry('n', 0100644, 5, 1680000000L));
        dirstate.addEntry("hg4j.txt", new Dirstate.Entry('n', 0100644, 13, 1680000001L));

        byte[] data = DirstateV2Serializer.serialize(dirstate);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Both entries are root-level (no "/"), 2 nodes, node table starts at offset 0.
        String name0 = readNodeBasename(buf, data, 0);
        String name1 = readNodeBasename(buf, data, DirstateV2Node.NODE_SIZE);

        assertEquals("hg4j.txt", name0, "root nodes must be sorted ascending by basename bytes");
        assertEquals("seed.txt", name1, "root nodes must be sorted ascending by basename bytes");
    }

    @Test
    public void testSerialize_nestedChildrenSortedByBasenameBytesRegardlessOfInsertionOrder() throws Exception {
        Dirstate dirstate = new Dirstate();
        // Same descending-insertion-order setup, one level deeper (siblings under "dir/").
        dirstate.addEntry("dir/z.txt", new Dirstate.Entry('n', 0100644, 1, 1680000000L));
        dirstate.addEntry("dir/a.txt", new Dirstate.Entry('n', 0100644, 1, 1680000001L));

        byte[] data = DirstateV2Serializer.serialize(dirstate);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Root: single "dir" node at offset 0.
        DirstateV2Node dirNode = new DirstateV2Node(buf, 0);
        int childrenStart = dirNode.getChildrenStart();
        int childrenCount = dirNode.getChildrenCount();
        assertEquals(2, childrenCount);

        String child0 = readNodeBasename(buf, data, childrenStart);
        String child1 = readNodeBasename(buf, data, childrenStart + DirstateV2Node.NODE_SIZE);
        assertEquals("a.txt", child0, "nested children must be sorted ascending by basename bytes too");
        assertEquals("z.txt", child1, "nested children must be sorted ascending by basename bytes too");
    }

    private static String readNodeBasename(ByteBuffer buf, byte[] data, int nodeOffset) {
        DirstateV2Node node = new DirstateV2Node(buf, nodeOffset);
        int pathOffset = node.getPathOffset();
        int pathLen = node.getPathLen() & 0xFFFF;
        int basenameStart = node.getBasenameStart() & 0xFFFF;
        String fullPath = new String(data, pathOffset, pathLen, StandardCharsets.UTF_8);
        return fullPath.substring(basenameStart);
    }

    @Test
    public void testSerialize_pathSegmentOver65535Bytes_throwsHgValidationException() {
        // A single top-level path segment (no "/" in it) whose UTF-8 byte length exceeds the
        // 65535-byte cap that dirstate-v2's 16-bit path_len field can represent.
        String hugeName = "a".repeat(70000);

        Dirstate dirstate = new Dirstate();
        dirstate.addEntry(hugeName, new Dirstate.Entry('n', 0100644, 10, 1680000000L));

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> DirstateV2Serializer.serialize(dirstate));
        assertTrue(ex.getMessage().contains("too long"));
    }

    @Test
    public void testSerialize_copySourcePresentButEmpty_isTreatedAsNoCopySource() throws Exception {
        // A copy-map entry whose value is the empty string (as opposed to absent entirely) must
        // be treated the same as "no copy source" -- covering the `!copySrc.isEmpty()` branch
        // evaluating false while copySrc is non-null.
        Dirstate dirstate = new Dirstate();
        dirstate.addEntry("b.txt", new Dirstate.Entry('n', 0100644, 10, 1680000000L));
        dirstate.addCopy("b.txt", "");

        byte[] bytes = DirstateV2Serializer.serialize(dirstate);
        assertNotNull(bytes);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        DirstateV2Node node = new DirstateV2Node(buf, 0);
        assertEquals(0, node.getCopySourceLen(), "empty copy source string must not be recorded");
        assertEquals(0, node.getCopySourceOffset());

        // Round-trip through the real parser to double check no bogus copy entry appears.
        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(bytes);
        assertFalse(decoded.getCopyMap().containsKey("b.txt"));
    }

    @Test
    public void testSerialize_symlinkMode_setsSymlinkFlag() throws Exception {
        // mode & 0120000 == 0120000 (S_IFLNK) must set DirstateV2Node.MODE_IS_SYMLINK,
        // independent of / taking priority over the executable-bit flag.
        Dirstate dirstate = new Dirstate();
        dirstate.addEntry("link", new Dirstate.Entry('n', 0120777, 5, 1680000000L));

        byte[] bytes = DirstateV2Serializer.serialize(dirstate);
        assertNotNull(bytes);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        DirstateV2Node node = new DirstateV2Node(buf, 0);
        assertEquals(0120000, node.getMode(), "symlink mode must round-trip as S_IFLNK");

        int flags = node.getFlags() & 0xFFFF;
        assertNotEquals(0, flags & DirstateV2Node.MODE_IS_SYMLINK);
    }
}
