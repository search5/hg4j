package com.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for field mapping and verification of the dirstate-v2 binary node layout.
 */
public class DirstateV2LayoutTest {

    @Test
    public void testSingleNodeParsingAndModification() {
        // Given: Prepare 44 bytes of dirstate-v2 node binary data
        byte[] buffer = new byte[44];
        ByteBuffer wrapper = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);

        // Write node field information (Offset-based binary writing aligned with Mercurial native specifications)
        wrapper.putInt(0, 10);              // children_start: index 10
        wrapper.putInt(4, 2);               // children_count: 2
        wrapper.putInt(8, 20);              // descendants_with_entry: 20
        wrapper.putInt(12, 15);             // tracked_descendants: 15
        wrapper.putShort(16, (short) 0x3B); // flags: 0x3B (WDIR_TRACKED | P1_TRACKED | MODE_EXEC_PERM | HAS_MODE_AND_SIZE | HAS_MTIME) -> state 'n', executable
        wrapper.putInt(18, 12345);          // size: 12345 bytes
        wrapper.putInt(22, 1680000000);    // mtime: epoch seconds
        wrapper.putInt(26, 999);            // mtime_nanoseconds: 999
        wrapper.putInt(30, 500);            // path_offset: 500
        wrapper.putShort(34, (short) 12);   // path_len: 12 bytes
        wrapper.putShort(36, (short) 4);    // basename_start: 4
        wrapper.putInt(38, 600);            // copy_source_offset: 600
        wrapper.putShort(42, (short) 5);    // copy_source_len: 5 bytes

        // When: Bind DirstateV2Node structure mapper
        DirstateV2Node node = new DirstateV2Node(buffer, 0);

        // Then: Assert decoded binary values against expected fields
        assertEquals('n', node.getState());
        assertEquals((short) 0x3B, node.getFlags());
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
        node.setMode(0644);                 // 0644 (normal file) -> flags = 0x01
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

        // Then: Cross-verify that modifications are correctly reflected in the original byte buffer based on Mercurial native specifications
        assertEquals(30, wrapper.getInt(0));
        assertEquals(4, wrapper.getInt(4));
        assertEquals(40, wrapper.getInt(8));
        assertEquals(35, wrapper.getInt(12));
        assertEquals((short) 0x19, wrapper.getShort(16)); // state 'a' & normal file -> flags = 0x19 (WDIR_TRACKED | HAS_MODE_AND_SIZE | HAS_MTIME)
        assertEquals(9999, wrapper.getInt(18));
        assertEquals(1700000000L, wrapper.getInt(22) & 0xFFFFFFFFL);
        assertEquals(888, wrapper.getInt(26));
        assertEquals(800, wrapper.getInt(30));
        assertEquals((short) 15, wrapper.getShort(34));
        assertEquals((short) 6, wrapper.getShort(36));
        assertEquals(700, wrapper.getInt(38));
        assertEquals((short) 7, wrapper.getShort(42));
    }
}
