package io.github.search5.hg4j.lib;

import org.junit.jupiter.api.Test;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying NodeId value type immutability, encapsulation, format, and exception handling.
 */
public class NodeIdTest {

    @Test
    public void testValidNodeIdCreation() {
        byte[] raw = new byte[20];
        Arrays.fill(raw, (byte) 0xAB);

        NodeId nodeId = new NodeId(raw);

        // Ensure immutability (verify defensive copying)
        raw[0] = 0x00;
        assertNotEquals((byte) 0x00, nodeId.getBytes()[0]);
        assertEquals((byte) 0xAB, nodeId.getBytes()[0]);

        // Verify immutability of getBytes() copy
        byte[] extracted = nodeId.getBytes();
        extracted[0] = 0x00;
        assertEquals((byte) 0xAB, nodeId.getBytes()[0]);
    }

    @Test
    public void testEqualsContractIncludingNullAndDifferentType() {
        byte[] raw = new byte[20];
        Arrays.fill(raw, (byte) 0xAB);
        NodeId a = new NodeId(raw);
        NodeId sameContent = new NodeId(raw.clone());

        byte[] otherRaw = new byte[20];
        Arrays.fill(otherRaw, (byte) 0xCD);
        NodeId differentContent = new NodeId(otherRaw);

        assertEquals(a, a, "identity must be equal");
        assertEquals(a, sameContent, "same 20 bytes must be equal");
        assertEquals(a.hashCode(), sameContent.hashCode());
        assertNotEquals(a, differentContent, "different bytes must not be equal");
        assertNotEquals(a, null, "must not equal null");
        assertNotEquals(a, "not a NodeId", "must not equal an instance of a different class");
    }

    @Test
    public void testInvalidBytesLength() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId(new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(new byte[21]));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(null));
    }

    @Test
    public void testHexConversionRoundTrip() {
        String hex = "abcdef0123456789abcdef0123456789abcdef01";
        NodeId nodeId = NodeId.fromHex(hex);

        assertEquals(hex, nodeId.toHex());
        assertFalse(nodeId.isNull());
    }

    @Test
    public void testInvalidHexStrings() {
        // Length violation
        assertThrows(IllegalArgumentException.class, () -> NodeId.fromHex("abc"));
        assertThrows(IllegalArgumentException.class, () -> NodeId.fromHex(null));
        // Character set violation (not hexadecimal)
        assertThrows(IllegalArgumentException.class, () -> NodeId.fromHex("abcdef0123456789abcdef0123456789abcdef0g"));
    }

    @Test
    public void testNullNodeId() {
        NodeId nullNode = NodeId.NULL;
        assertTrue(nullNode.isNull());
        assertEquals("0000000000000000000000000000000000000000", nullNode.toHex());
    }

    @Test
    public void testEqualsAndHashCode() {
        String hex1 = "abcdef0123456789abcdef0123456789abcdef01";
        String hex2 = "abcdef0123456789abcdef0123456789abcdef01";
        String hex3 = "1234567890123456789012345678901234567890";

        NodeId n1 = NodeId.fromHex(hex1);
        NodeId n2 = NodeId.fromHex(hex2);
        NodeId n3 = NodeId.fromHex(hex3);

        assertEquals(n1, n2);
        assertNotEquals(n1, n3);
        assertNotEquals(null, n1);
        assertNotEquals("abc", n1);

        assertEquals(n1.hashCode(), n2.hashCode());
        assertNotEquals(n1.hashCode(), n3.hashCode());
    }

    @Test
    public void testCompareTo() {
        NodeId n1 = NodeId.fromHex("0000000000000000000000000000000000000001");
        NodeId n2 = NodeId.fromHex("0000000000000000000000000000000000000002");

        assertTrue(n1.compareTo(n2) < 0);
        assertTrue(n2.compareTo(n1) > 0);
        assertEquals(0, n1.compareTo(NodeId.fromHex("0000000000000000000000000000000000000001")));
    }

    @Test
    public void testToStringShortening() {
        String hex = "abcdef0123456789abcdef0123456789abcdef01";
        NodeId nodeId = NodeId.fromHex(hex);
        assertEquals("abcdef012345", nodeId.toString());
    }
}
