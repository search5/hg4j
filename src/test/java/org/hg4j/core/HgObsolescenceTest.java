package org.hg4j.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgObsolescenceTest {

    @Test
    public void testObsstoreParsingSuccess() throws IOException {
        // Create custom obsstore mock binary buffer
        byte[] predecessor = new byte[20];
        predecessor[0] = 0x12;
        predecessor[19] = 0x34;

        byte[] successor1 = new byte[20];
        successor1[0] = 0x56;

        byte[] successor2 = new byte[20];
        successor2[0] = 0x78;

        String metadataStr = "user\0tester\0note\0evolve test\0";
        byte[] metaBytes = metadataStr.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(20 + 1 + 40 + 1 + 2 + metaBytes.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(predecessor);
        buffer.put((byte) 2); // successorsCount
        buffer.put(successor1);
        buffer.put(successor2);
        buffer.put((byte) 0x01); // flags
        buffer.putShort((short) metaBytes.length); // metaLen
        buffer.put(metaBytes);

        List<HgObsMarker> markers = HgObsolescenceParser.parse(buffer.array());
        assertNotNull(markers);
        assertEquals(1, markers.size());

        HgObsMarker marker = markers.get(0);
        assertArrayEquals(predecessor, marker.getPredecessor());
        assertEquals(2, marker.getSuccessors().size());
        assertArrayEquals(successor1, marker.getSuccessors().get(0));
        assertArrayEquals(successor2, marker.getSuccessors().get(1));
        assertEquals(0x01, marker.getFlags());
        assertEquals("tester", marker.getMetadata().get("user"));
        assertEquals("evolve test", marker.getMetadata().get("note"));

        // Equals/HashCode coverage
        HgObsMarker clone = new HgObsMarker(predecessor, List.of(successor1, successor2), 0x01, marker.getMetadata());
        assertEquals(clone, marker);
        assertEquals(clone.hashCode(), marker.hashCode());
    }

    @Test
    public void testObsstoreParsingEmptyReturnsEmpty() throws IOException {
        assertTrue(HgObsolescenceParser.parse(null).isEmpty());
        assertTrue(HgObsolescenceParser.parse(new byte[0]).isEmpty());
    }

    @Test
    public void testObsstoreParsingCorruptSegmentsThrows() {
        // Less than 20 bytes for predecessor
        byte[] badBytes = new byte[10];
        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgObsolescenceParser.parse(badBytes);
        });

        // Missing successors bytes
        ByteBuffer buffer = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[20]);
        buffer.put((byte) 5); // count is 5, but buffer ends immediately

        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgObsolescenceParser.parse(buffer.array());
        });
    }
}
