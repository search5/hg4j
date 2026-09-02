package com.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgValidationException;

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
