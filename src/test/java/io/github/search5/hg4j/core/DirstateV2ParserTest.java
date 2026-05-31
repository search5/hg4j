package io.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip integration tests for the dirstate-v2 parser and serializer.
 */
public class DirstateV2ParserTest {

    @Test
    public void testDirstateV2RoundTrip() throws Exception {
        // Given: Configure test parent hashes
        byte[] parent1 = new byte[20];
        byte[] parent2 = new byte[20];
        for (int i = 0; i < 20; i++) {
            parent1[i] = (byte) (i + 1);
            parent2[i] = (byte) (20 - i);
        }

        // Prepare hierarchical file entries for testing
        Map<String, Dirstate.Entry> originalEntries = new HashMap<>();
        originalEntries.put("a.txt", new Dirstate.Entry('n', 0100644, 100, 1680000000L));
        originalEntries.put("src/b.txt", new Dirstate.Entry('a', 0100755, 200, 1680000001L));
        originalEntries.put("src/main/c.txt", new Dirstate.Entry('m', 0100644, 300, 1680000002L));
        originalEntries.put("doc/readme.md", new Dirstate.Entry('r', 0, 0, 0)); // removed file

        // When: Serialize into dirstate-v2 binary using DirstateV2Serializer
        byte[] v2Bytes = DirstateV2Serializer.serialize(originalEntries);
        assertNotNull(v2Bytes);
        assertTrue(v2Bytes.length > 12); // Must be larger than the header

        // When: Parse and decode the binary using DirstateV2Parser
        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(v2Bytes);

        // Then: Assert that the entry map matches completely without data loss or corruption
        Map<String, Dirstate.Entry> decodedEntries = decoded.getEntries();
        assertEquals(originalEntries.size(), decodedEntries.size());

        for (Map.Entry<String, Dirstate.Entry> expected : originalEntries.entrySet()) {
            String path = expected.getKey();
            Dirstate.Entry expEntry = expected.getValue();
            Dirstate.Entry decEntry = decodedEntries.get(path);

            assertNotNull(decEntry, "Path not found in decoded dirstate: " + path);
            assertEquals(expEntry.getState(), decEntry.getState(), "State mismatch for " + path);
            assertEquals(expEntry.getMode(), decEntry.getMode(), "Mode mismatch for " + path);
            assertEquals(expEntry.getSize(), decEntry.getSize(), "Size mismatch for " + path);
            assertEquals(expEntry.getTime(), decEntry.getTime(), "Time mismatch for " + path);
        }
    }

    @Test
    public void testParseNullData_throwsHgCorruptDataException() {
        DirstateV2Parser parser = new DirstateV2Parser();
        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class, () -> parser.parse(null));
    }

    @Test
    public void testParseEmptyData_returnsEmptyDirstate() throws Exception {
        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate d = parser.parse(new byte[0]);
        assertNotNull(d);
        assertTrue(d.isV2());
        assertTrue(d.getEntries().isEmpty());
    }

    @Test
    public void testParseMalformedLayout_throwsHgCorruptDataException() {
        DirstateV2Parser parser = new DirstateV2Parser();
        // 10 bytes of invalid data
        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class, () -> parser.parse(new byte[10]));
    }

    @Test
    public void testParseDataBlockOverflow_throwsHgCorruptDataException() throws Exception {
        DirstateV2Parser parser = new DirstateV2Parser();
        
        // DirstateV2Node.NODE_SIZE = 44 bytes.
        byte[] malformedBytes = new byte[44];
        
        // pathOffset (offset 16): 100
        malformedBytes[16] = 0;
        malformedBytes[17] = 0;
        malformedBytes[18] = 0;
        malformedBytes[19] = 100;
        
        // pathLen (offset 20): 50
        malformedBytes[20] = 0;
        malformedBytes[21] = 50;
        
        // pathOffset + pathLen = 100 + 50 = 150.
        // Set bytes.length exactly to 150 bytes to pass nodeLayout verification
        byte[] fullBytes = new byte[150];
        System.arraycopy(malformedBytes, 0, fullBytes, 0, 44);
        
        // Manipulate pathLen to 55 to exceed the capacity
        fullBytes[20] = 0;
        fullBytes[21] = 55;
        
        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class, () -> parser.parse(fullBytes));
    }
}

