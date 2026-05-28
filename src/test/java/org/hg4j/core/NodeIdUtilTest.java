package org.hg4j.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NodeIdUtilTest {

    @Test
    public void testHexConversions() {
        byte[] original = new byte[]{ 0x00, 0x01, 0x0f, (byte) 0xff };
        String hex = NodeIdUtil.toHex(original);
        assertEquals("00010fff", hex);

        byte[] restored = NodeIdUtil.fromHex(hex);
        assertArrayEquals(original, restored);

        assertEquals("", NodeIdUtil.toHex(null));
        assertArrayEquals(new byte[0], NodeIdUtil.fromHex(null));
        assertArrayEquals(new byte[0], NodeIdUtil.fromHex(""));
    }

    @Test
    public void testIsAllZero() {
        assertTrue(NodeIdUtil.isAllZero(new byte[20]));
        assertTrue(NodeIdUtil.isAllZero(null));
        assertFalse(NodeIdUtil.isAllZero(new byte[]{ 0, 0, 1, 0 }));
    }
}
