package io.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for field mapping and verification of the dirstate-v2 binary node layout.
 *
 * <p>Offsets and flag bit values below are the real ones, verified against Mercurial 6.0's
 * {@code mercurial/dirstateutils/v2.py} ({@code NODE = struct.Struct('>LHHLHLLLLHlll')}) and
 * {@code mercurial/pure/parsers.py} ({@code DIRSTATE_V2_*} constants) — see
 * {@link DirstateV2Node}'s class doc for how this was cross-checked against a real fixture.</p>
 */
public class DirstateV2LayoutTest {

    @Test
    public void testSingleNodeParsingAndModification() {
        // Given: Prepare 44 bytes of dirstate-v2 node binary data
        byte[] buffer = new byte[44];
        ByteBuffer wrapper = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);

        // Write node field information at the real NODE struct offsets:
        // >LHHLHLLLLHlll -> path_start(0,L) path_len(4,H) basename_start(6,H)
        // copy_source_start(8,L) copy_source_len(12,H) children_start(14,L) children_count(18,L)
        // descendants_with_entry(22,L) tracked_descendants(26,L) flags(30,H) size(32,l)
        // mtime_s(36,l) mtime_ns(40,l)
        wrapper.putInt(0, 500);              // path_start: 500
        wrapper.putShort(4, (short) 12);     // path_len: 12 bytes
        wrapper.putShort(6, (short) 4);      // basename_start: 4
        wrapper.putInt(8, 600);              // copy_source_start: 600
        wrapper.putShort(12, (short) 5);     // copy_source_len: 5 bytes
        wrapper.putInt(14, 10);              // children_start: index 10
        wrapper.putInt(18, 2);               // children_count: 2
        wrapper.putInt(22, 20);              // descendants_with_entry: 20
        wrapper.putInt(26, 15);              // tracked_descendants: 15
        // flags: WDIR_TRACKED(1) | P1_TRACKED(2) | MODE_EXEC_PERM(8) | HAS_MODE_AND_SIZE(1024)
        // | HAS_MTIME(2048) -> state 'n', executable
        wrapper.putShort(30, (short) (1 | 2 | 8 | 1024 | 2048));
        wrapper.putInt(32, 12345);           // size: 12345 bytes
        wrapper.putInt(36, 1680000000);      // mtime_s: epoch seconds
        wrapper.putInt(40, 999);             // mtime_ns: 999

        // When: Bind DirstateV2Node structure mapper
        DirstateV2Node node = new DirstateV2Node(buffer, 0);

        // Then: Assert decoded binary values against expected fields
        assertEquals('n', node.getState());
        assertEquals((short) (1 | 2 | 8 | 1024 | 2048), node.getFlags());
        assertEquals(0100755, node.getMode()); // executable
        assertEquals(12345, node.getSize());
        assertEquals(1680000000L, node.getMtime());
        assertEquals(500, node.getPathOffset());
        assertEquals(12, node.getPathLen());
        assertEquals(4, node.getBasenameStart());
        assertEquals(600, node.getCopySourceOffset());
        assertEquals(5, node.getCopySourceLen());
        assertEquals(10, node.getChildrenStart());
        assertEquals(2, node.getChildrenCount());
        assertEquals(20, node.getDescendantsWithEntryCount());
        assertEquals(15, node.getTrackedDescendants());
        assertEquals(999, node.getMtimeNanoseconds());

        // When: Modify node information using the Java API
        node.setState('a');                 // 'a' (added) -> WDIR_TRACKED (0x01)
        node.setMode(0644);                 // 0644 (normal file) -> no exec/symlink flags
        node.setSize(9999);
        node.setMtime(1700000000L);
        node.setPathOffset(800);
        node.setPathLen((short) 15);
        node.setBasenameStart((short) 6);
        node.setCopySourceOffset(700);
        node.setCopySourceLen((short) 7);
        node.setChildrenStart(30);
        node.setChildrenCount(4);
        node.setDescendantsWithEntryCount(40);
        node.setTrackedDescendants(35);
        node.setMtimeNanoseconds(888);

        // Then: Cross-verify that modifications are correctly reflected in the original byte
        // buffer at the real struct offsets.
        assertEquals(800, wrapper.getInt(0));
        assertEquals((short) 15, wrapper.getShort(4));
        assertEquals((short) 6, wrapper.getShort(6));
        assertEquals(700, wrapper.getInt(8));
        assertEquals((short) 7, wrapper.getShort(12));
        assertEquals(30, wrapper.getInt(14));
        assertEquals(4, wrapper.getInt(18));
        assertEquals(40, wrapper.getInt(22));
        assertEquals(35, wrapper.getInt(26));
        // setState()/setMode() only ever touch their own specific bits (WDIR_TRACKED/P1_TRACKED/
        // P2_INFO and MODE_EXEC_PERM/MODE_IS_SYMLINK respectively) -- HAS_MODE_AND_SIZE(1024) and
        // HAS_MTIME(2048) from the original setup are left untouched, same as real hg's
        // DirstateItem.v2_data(), which sets each flag bit independently based on which fields
        // are non-null. Final: WDIR_TRACKED(1) | HAS_MODE_AND_SIZE(1024) | HAS_MTIME(2048).
        assertEquals((short) (1 | 1024 | 2048), wrapper.getShort(30));
        assertEquals(9999, wrapper.getInt(32));
        assertEquals(1700000000L, wrapper.getInt(36) & 0xFFFFFFFFL);
        assertEquals(888, wrapper.getInt(40));
    }

    @Test
    public void testConstructor_nullBuffer_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new DirstateV2Node((ByteBuffer) null, 0));
        assertEquals("Backing buffer cannot be null", ex.getMessage());
    }

    @Test
    public void testConstructor_negativeOffset_throwsIndexOutOfBoundsException() {
        ByteBuffer buffer = ByteBuffer.allocate(DirstateV2Node.NODE_SIZE);
        assertThrows(IndexOutOfBoundsException.class, () -> new DirstateV2Node(buffer, -1));
    }

    @Test
    public void testConstructor_offsetPlusNodeSizeExceedsCapacity_throwsIndexOutOfBoundsException() {
        ByteBuffer buffer = ByteBuffer.allocate(DirstateV2Node.NODE_SIZE);
        assertThrows(IndexOutOfBoundsException.class,
                () -> new DirstateV2Node(buffer, DirstateV2Node.NODE_SIZE - 1));
    }

    @Test
    public void testGetMode_symlinkFlag_returnsSymlinkMode() {
        byte[] buffer = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(buffer, 0);
        node.setState('n');
        node.setFlags((short) (node.getFlags() | DirstateV2Node.MODE_IS_SYMLINK));

        assertEquals(0120000, node.getMode());
    }

    @Test
    public void testSetMode_symlinkMode_setsSymlinkFlagAndClearsExecFlag() {
        byte[] buffer = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(buffer, 0);
        node.setState('n');
        node.setMode(0100755); // start executable
        assertEquals(0100755, node.getMode());

        node.setMode(0120777); // symlink mode (S_IFLNK | permission bits)

        assertEquals(0120000, node.getMode());
        assertEquals(0, node.getFlags() & DirstateV2Node.MODE_EXEC_PERM);
        assertEquals(DirstateV2Node.MODE_IS_SYMLINK, node.getFlags() & DirstateV2Node.MODE_IS_SYMLINK);
    }

    @Test
    public void testGetState_removedViaP2InfoOnly_returnsRemoved() {
        byte[] buffer = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(buffer, 0);
        node.setFlags((short) DirstateV2Node.P2_INFO);

        assertEquals('r', node.getState());
    }

    @Test
    public void testGetState_intermediateDirectory_returnsNulChar() {
        byte[] buffer = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(buffer, 0);
        node.setFlags((short) 0);

        assertEquals('\0', node.getState());
        assertEquals(0, node.getMode());
    }

    @Test
    public void testGetSizeAndMtime_flagsNotSet_returnAmbiguousSentinels() {
        // Backlog #39 wave 4 (2026-09-05): real hg's own DirstateItem.from_v2_data
        // (mercurial/pure/parsers.py) treats an absent HAS_MODE_AND_SIZE/HAS_MTIME bit as "no
        // meaningful cached value, a full content comparison is required" -- NOT as a literal
        // size/mtime of zero. Returning a concrete 0 (as this test used to assert) silently
        // turned "ambiguous, needs lookup" into a definite (and wrong) "this file is 0 bytes as
        // of epoch", corrupting any OTHER untouched dirstate-v2 entry that happened to be
        // ambiguous the instant any hg4j write command did a read-modify-write of the dirstate.
        // See Dirstate.Entry#isStatAmbiguous() for the shared sentinel convention this now
        // matches (dirstate-v1 already used size=-1/AMBIGUOUS_TIME for the same concept).
        byte[] buffer = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(buffer, 0);
        node.setState('n');
        node.setSize(12345);
        node.setMtime(999999L);
        node.setMtimeNanoseconds(42);

        assertEquals(-1, node.getSize());
        assertEquals(Dirstate.Entry.AMBIGUOUS_TIME, node.getMtime());
        assertEquals(0, node.getMtimeNanoseconds());
    }
}
