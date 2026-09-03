package io.github.search5.hg4j.obsolete;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of {@link HgObsMarker}'s own methods (constructor validation, equals/hashCode
 * branches, writeMarker's file-existence and null-default branches) — the fields exercised only
 * indirectly, and incompletely, by {@link HgObsolescenceTest} and
 * {@link HgObsolescenceParserCoverageTest} via round-tripping through the parser.
 */
public class HgObsMarkerCoverageTest {

    private static byte[] node(int firstByte) {
        byte[] n = new byte[20];
        n[0] = (byte) firstByte;
        return n;
    }

    @Test
    public void testConstructorRejectsNullPredecessor() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HgObsMarker(null, List.of(), 0, Map.of()));
        assertEquals("Predecessor node must be exactly 20 bytes", ex.getMessage());
    }

    @Test
    public void testConstructorRejectsWrongLengthPredecessor() {
        byte[] tooShort = new byte[19];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HgObsMarker(tooShort, List.of(), 0, Map.of()));
        assertEquals("Predecessor node must be exactly 20 bytes", ex.getMessage());
    }

    @Test
    public void testConstructorDefaultsNullSuccessorsAndMetadata() {
        HgObsMarker marker = new HgObsMarker(node(0x01), null, 0, null);
        assertTrue(marker.getSuccessors().isEmpty());
        assertTrue(marker.getMetadata().isEmpty());
    }

    @Test
    public void testEqualsSameReference() {
        HgObsMarker marker = new HgObsMarker(node(0x01), List.of(node(0x02)), 0, Map.of("k", "v"));
        assertEquals(marker, marker);
    }

    @Test
    public void testEqualsNullAndDifferentClass() {
        HgObsMarker marker = new HgObsMarker(node(0x01), List.of(), 0, Map.of());
        assertFalse(marker.equals(null));
        assertFalse(marker.equals("not a marker"));
    }

    @Test
    public void testEqualsFalseWhenFlagsDiffer() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(), 0, Map.of());
        HgObsMarker b = new HgObsMarker(node(0x01), List.of(), 1, Map.of());
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsFalseWhenPredecessorDiffers() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(), 0, Map.of());
        HgObsMarker b = new HgObsMarker(node(0x02), List.of(), 0, Map.of());
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsFalseWhenSuccessorCountDiffers() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(node(0x02)), 0, Map.of());
        HgObsMarker b = new HgObsMarker(node(0x01), List.of(), 0, Map.of());
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsFalseWhenSuccessorContentDiffers() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(node(0x02)), 0, Map.of());
        HgObsMarker b = new HgObsMarker(node(0x01), List.of(node(0x03)), 0, Map.of());
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsFalseWhenMetadataDiffers() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(), 0, Map.of("op", "amend"));
        HgObsMarker b = new HgObsMarker(node(0x01), List.of(), 0, Map.of("op", "prune"));
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsTrueForEquivalentSuccessorContent() {
        HgObsMarker a = new HgObsMarker(node(0x01), List.of(node(0x02), node(0x03)), 5, Map.of("op", "amend"));
        HgObsMarker b = new HgObsMarker(node(0x01), List.of(node(0x02), node(0x03)), 5, Map.of("op", "amend"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testWriteMarkerCreatesVersionByteWhenObsstoreFileIsEmptyButExists(@TempDir Path tempDir) throws IOException {
        File storeDir = tempDir.toFile();
        File obsstoreFile = new File(storeDir, "obsstore");
        assertTrue(obsstoreFile.createNewFile());
        assertEquals(0, obsstoreFile.length());

        HgObsMarker.writeMarker(storeDir, node(0x01), List.of(), "prune");

        byte[] raw = Files.readAllBytes(obsstoreFile.toPath());
        assertEquals(1, raw[0], "version byte must still be written for a pre-existing empty file");

        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
    }

    @Test
    public void testWriteMarkerNullSuccessorsTreatedAsEmpty(@TempDir Path tempDir) throws IOException {
        File storeDir = tempDir.toFile();
        HgObsMarker.writeMarker(storeDir, node(0x01), null, "prune");

        byte[] raw = Files.readAllBytes(new File(storeDir, "obsstore").toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
        assertTrue(markers.get(0).getSuccessors().isEmpty());
    }

    @Test
    public void testWriteMarkerNullOperationDefaultsToAmend(@TempDir Path tempDir) throws IOException {
        File storeDir = tempDir.toFile();
        HgObsMarker.writeMarker(storeDir, node(0x01), List.of(), null);

        byte[] raw = Files.readAllBytes(new File(storeDir, "obsstore").toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
        assertEquals("amend", markers.get(0).getMetadata().get("operation"));
    }

    @Test
    public void testGetPredecessorAndSuccessorsReturnDefensiveCopies() {
        byte[] predecessor = node(0x01);
        byte[] successor = node(0x02);
        HgObsMarker marker = new HgObsMarker(predecessor, List.of(successor), 0, Map.of());

        byte[] returnedPredecessor = marker.getPredecessor();
        returnedPredecessor[0] = (byte) 0xFF;
        assertEquals(0x01, marker.getPredecessor()[0], "mutating the returned array must not affect internal state");

        List<byte[]> returnedSuccessors = marker.getSuccessors();
        returnedSuccessors.get(0)[0] = (byte) 0xFF;
        assertEquals(0x02, marker.getSuccessors().get(0)[0], "mutating a returned successor array must not affect internal state");
    }

    @Test
    public void testGetMetadataIsImmutable() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("operation", "amend");
        HgObsMarker marker = new HgObsMarker(node(0x01), List.of(), 0, metadata);

        assertThrows(UnsupportedOperationException.class, () -> marker.getMetadata().put("x", "y"));
    }
}
