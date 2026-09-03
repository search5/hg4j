package io.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.search5.hg4j.util.NodeIdUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code persistent-nodemap} requirement의 {@code .n} docket + 트라이 데이터 파일 파싱 검증.
 *
 * <p>픽스처는 실제 Rust 확장 포함 Mercurial 7.2.4(Docker {@code hg-rust-7.2.4})로
 * {@code format.use-persistent-nodemap=true} 저장소에 40회 커밋해서 얻은 진짜 바이트다 (이
 * 환경의 시스템 {@code /usr/bin/hg} 7.2.2는 Rust 확장이 없어 이 포맷의 저장소 자체를 만들지
 * 못한다). 상세 생성 방법/바이트 단위 대조 결과는
 * {@code src/test/resources/fixtures/persistent-nodemap/README.md} 참고.</p>
 */
@DisplayName("NodeMapFile (persistent-nodemap .n docket + trie) — verified against real hg-generated fixtures")
class NodeMapFileFixtureTest {

    @TempDir
    Path tempDir;

    private File setupRealFixture() throws IOException {
        copyFixture("00changelog.i", "00changelog.i");
        copyFixture("00changelog.n", "00changelog.n");
        copyFixture("00changelog-db83bf64.nd", "00changelog-db83bf64.nd");
        return tempDir.resolve("00changelog.i").toFile();
    }

    private void copyFixture(String resourceName, String targetName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/persistent-nodemap/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<Integer, byte[]> loadGroundTruthRevs() throws IOException {
        Map<Integer, byte[]> revs = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/persistent-nodemap/revs.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split("\\s+");
                revs.put(Integer.parseInt(parts[0]), NodeIdUtil.fromHex(parts[1]));
            }
        }
        return revs;
    }

    @Test
    @DisplayName("실제 docket(62바이트 S_VERSION+S_HEADER+uid+tip_node)을 정확히 파싱한다")
    void parsesRealDocketHeader() throws IOException {
        File idxFile = setupRealFixture();

        NodeMapFile nodeMap = NodeMapFile.tryLoad(idxFile);

        assertNotNull(nodeMap, "실제 hg가 만든 유효한 .n/.nd 픽스처는 로드에 성공해야 함");
        assertEquals(39, nodeMap.getTipRev(), "40개 커밋(rev 0..39) 중 tip_rev는 39");
        assertEquals(1088, nodeMap.getDataLength(), "17블록 * 64바이트 = 1088");
        assertEquals(64, nodeMap.getDataUnused());
        assertEquals("f55ecffa87e48e1d1d3ec8d0860986c83a90ff2e", NodeIdUtil.toHex(nodeMap.getTipNode()));
    }

    @Test
    @DisplayName("실제 트라이 데이터로 40개 리비전 전부를 노드 해시 -> 리비전 번호로 정확히 조회한다")
    void resolvesAllRealRevisionsFromTrie() throws IOException {
        File idxFile = setupRealFixture();
        Map<Integer, byte[]> groundTruth = loadGroundTruthRevs();
        assertEquals(40, groundTruth.size());

        NodeMapFile nodeMap = NodeMapFile.tryLoad(idxFile);
        assertNotNull(nodeMap);

        for (Map.Entry<Integer, byte[]> entry : groundTruth.entrySet()) {
            Integer found = nodeMap.findRevision(entry.getValue());
            assertEquals(entry.getKey(), found,
                    "node " + NodeIdUtil.toHex(entry.getValue()) + " should resolve to rev " + entry.getKey());
        }
    }

    @Test
    @DisplayName("존재하지 않는 노드 해시는 트라이에서 조회되지 않는다 (root 블록에 해당 니블 자체가 없는 경우)")
    void unknownNodeIsNotFound() throws IOException {
        File idxFile = setupRealFixture();
        NodeMapFile nodeMap = NodeMapFile.tryLoad(idxFile);
        assertNotNull(nodeMap);

        // 실제 hg가 만든 루트 블록(hex 대조로 확인됨, README.md 참고)에는 첫 니블 '9'로
        // 시작하는 리비전이 하나도 없다 -- 즉시 NO_ENTRY(-1)로 끝나 확실한 miss가 된다.
        // (반대로 임의의 바이트를 고르면, 트라이는 실제로 존재하는 다른 노드와 우연히 접두사가
        // 겹칠 경우 그 리비전을 반환할 수 있다 -- 이는 트라이 자체의 문서화된 특성이며
        // NodeMapFile#findRevision의 javadoc과 RevlogIndex의 후속 전체-노드 검증이 다루는
        // 지점이다. 이 테스트는 순수 트라이 워크 자체의 no-entry 케이스만 확인한다.)
        byte[] bogus = new byte[20];
        java.util.Arrays.fill(bogus, (byte) 0x99);
        assertNull(nodeMap.findRevision(bogus));
    }

    @Test
    @DisplayName(".n 파일이 없으면 null을 반환하고(정상 fallback 신호), 예외를 던지지 않는다")
    void missingDocketReturnsNull() throws IOException {
        File idxFile = tempDir.resolve("00changelog.i").toFile();
        Files.write(idxFile.toPath(), new byte[64]); // 존재는 하지만 .n 짝은 없음
        assertNull(NodeMapFile.tryLoad(idxFile));
    }
}
