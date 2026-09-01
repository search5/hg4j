package com.github.search5.hg4j.obsolete;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * FM1(version=1) obsstore 포맷 파싱 검증. 실제 hg CLI로 생성한 진짜 obsstore 바이트를 쓰는
 * 회귀 검증은 {@link HgObsolescenceRealHgInteropTest} 참고 — 여기서는 파서 자체의 정상/오류
 * 경로를 {@link HgObsMarker#writeMarker}(FM1 스펙 그대로 구현됨)로 만든 데이터로 검증한다.
 */
public class HgObsolescenceTest {

    @Test
    public void testObsstoreParsingSuccess(@TempDir Path tempDir) throws IOException {
        byte[] predecessor = new byte[20];
        predecessor[0] = 0x12;
        predecessor[19] = 0x34;

        byte[] successor1 = new byte[20];
        successor1[0] = 0x56;
        byte[] successor2 = new byte[20];
        successor2[0] = 0x78;

        File storeDir = tempDir.toFile();
        HgObsMarker.writeMarker(storeDir, predecessor, List.of(successor1, successor2), "evolve-test");

        byte[] raw = Files.readAllBytes(new File(storeDir, "obsstore").toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertNotNull(markers);
        assertEquals(1, markers.size());

        HgObsMarker marker = markers.get(0);
        assertArrayEquals(predecessor, marker.getPredecessor());
        assertEquals(2, marker.getSuccessors().size());
        assertArrayEquals(successor1, marker.getSuccessors().get(0));
        assertArrayEquals(successor2, marker.getSuccessors().get(1));
        assertEquals("hg4j", marker.getMetadata().get("user"));
        assertEquals("evolve-test", marker.getMetadata().get("operation"));

        // Equals/HashCode coverage
        HgObsMarker clone = new HgObsMarker(predecessor, List.of(successor1, successor2), marker.getFlags(), marker.getMetadata());
        assertEquals(clone, marker);
        assertEquals(clone.hashCode(), marker.hashCode());
    }

    @Test
    public void testObsstoreParsingMultipleMarkersAppended(@TempDir Path tempDir) throws IOException {
        File storeDir = tempDir.toFile();
        byte[] p1 = new byte[20];
        p1[0] = 1;
        byte[] s1 = new byte[20];
        s1[0] = 2;
        byte[] p2 = new byte[20];
        p2[0] = 3;

        HgObsMarker.writeMarker(storeDir, p1, List.of(s1), "amend");
        HgObsMarker.writeMarker(storeDir, p2, List.of(), "prune"); // successor 0개 (prune)

        byte[] raw = Files.readAllBytes(new File(storeDir, "obsstore").toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(2, markers.size());
        assertArrayEquals(p1, markers.get(0).getPredecessor());
        assertEquals(1, markers.get(0).getSuccessors().size());
        assertArrayEquals(p2, markers.get(1).getPredecessor());
        assertEquals(0, markers.get(1).getSuccessors().size());
        assertEquals("prune", markers.get(1).getMetadata().get("operation"));
    }

    @Test
    public void testObsstoreParsingEmptyReturnsEmpty() throws IOException {
        assertTrue(HgObsolescenceParser.parse(null).isEmpty());
        assertTrue(HgObsolescenceParser.parse(new byte[0]).isEmpty());
    }

    @Test
    public void testObsstoreParsingUnsupportedVersionThrows() {
        byte[] badVersion = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00}; // version byte 0 = FM0, 미지원
        assertThrows(HgCorruptDataException.class,
                () -> HgObsolescenceParser.parse(badVersion));
    }

    @Test
    public void testObsstoreParsingTruncatedThrows() {
        // 버전 바이트만 있고 고정 헤더(19바이트)가 다 안 옴
        byte[] badBytes = new byte[]{0x01, 0x00, 0x00};
        assertThrows(HgCorruptDataException.class,
                () -> HgObsolescenceParser.parse(badBytes));
    }
}
