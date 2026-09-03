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
import java.util.List;
import java.util.Map;

import io.github.search5.hg4j.util.NodeIdUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RevlogIndex}가 실제 hg가 만든 persistent-nodemap({@code .n}) 트라이를 붙였을 때
 * 가속 조회 경로(트라이 우선, 없으면 기존 순차 스캔 fallback)로 정확히 배선됐는지 검증.
 *
 * <p>같은 실제 hg 생성 {@code 00changelog.i}에 대해, 트라이를 붙이지 않은 기존 순차 스캔
 * 경로와 트라이를 붙인 가속 경로 둘 다로 40개 리비전을 전부 조회해서 결과가 완전히
 * 일치함을 확인한다. 픽스처 상세는 {@code src/test/resources/fixtures/persistent-nodemap/README.md}.</p>
 */
@DisplayName("RevlogIndex persistent-nodemap accelerated findRevision — matches sequential-scan fallback")
class RevlogIndexPersistentNodeMapTest {

    @TempDir
    Path tempDir;

    private void copyFixture(String resourceName, String targetName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/persistent-nodemap/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File setupRealFixture() throws IOException {
        copyFixture("00changelog.i", "00changelog.i");
        copyFixture("00changelog.n", "00changelog.n");
        copyFixture("00changelog-db83bf64.nd", "00changelog-db83bf64.nd");
        return tempDir.resolve("00changelog.i").toFile();
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
    @DisplayName("트라이를 붙이면 로드 즉시 nodeMap 구축이 지연(deferred)되고, findRevision 결과는 순차 스캔과 100% 일치한다")
    void acceleratedLookupMatchesSequentialScan() throws IOException {
        File idxFile = setupRealFixture();
        Map<Integer, byte[]> groundTruth = loadGroundTruthRevs();
        assertEquals(40, groundTruth.size());

        // 기존 경로: 트라이 없이 항상 순차 스캔으로 nodeMap을 즉시 전부 구축.
        RevlogIndex sequential = new RevlogIndex(idxFile);
        assertFalse(sequential.isNodeMapDeferred(), "트라이 미부착 시 deferred 상태일 수 없음");

        // 가속 경로: 실제 hg가 만든 트라이를 부착.
        NodeMapFile nodeMap = NodeMapFile.tryLoad(idxFile);
        assertNotNull(nodeMap);
        RevlogIndex accelerated = new RevlogIndex(idxFile, nodeMap);
        assertTrue(accelerated.isNodeMapDeferred(),
                "신선한(non-stale) 트라이가 있으면 로드 시 전체 스캔을 건너뛰고 지연 상태가 돼야 함");

        for (Map.Entry<Integer, byte[]> entry : groundTruth.entrySet()) {
            int expectedRev = entry.getKey();
            byte[] node = entry.getValue();
            assertEquals(expectedRev, sequential.findRevision(node), "sequential mismatch at rev " + expectedRev);
            assertEquals(expectedRev, accelerated.findRevision(node), "accelerated mismatch at rev " + expectedRev);
        }

        // 존재하지 않는 노드는 두 경로 모두 -1.
        byte[] bogus = new byte[20];
        java.util.Arrays.fill(bogus, (byte) 0x77);
        assertEquals(-1, sequential.findRevision(bogus));
        assertEquals(-1, accelerated.findRevision(bogus));

        // 가속 경로는 findRevision만 쓰는 동안 계속 deferred 상태를 유지해야 함(실제로 스캔을
        // 건너뛰고 있다는 증거) — findByHexPrefix를 호출하는 순간에만 materialize 된다.
        assertTrue(accelerated.isNodeMapDeferred(), "findRevision만으로는 전체 스캔이 유발되면 안 됨");
    }

    @Test
    @DisplayName("findByHexPrefix 호출 시 deferred 상태가 materialize되고 접두사 조회도 정확하다")
    void hexPrefixLookupMaterializesDeferredMapAndStaysCorrect() throws IOException {
        File idxFile = setupRealFixture();
        Map<Integer, byte[]> groundTruth = loadGroundTruthRevs();
        NodeMapFile nodeMap = NodeMapFile.tryLoad(idxFile);
        RevlogIndex accelerated = new RevlogIndex(idxFile, nodeMap);
        assertTrue(accelerated.isNodeMapDeferred());

        byte[] rev0Node = groundTruth.get(0);
        String fullHex = NodeIdUtil.toHex(rev0Node);
        List<byte[]> matches = accelerated.findByHexPrefix(fullHex.substring(0, 12));

        assertFalse(accelerated.isNodeMapDeferred(), "findByHexPrefix는 deferred 맵을 materialize해야 함");
        assertEquals(1, matches.size());
        assertEquals(fullHex, NodeIdUtil.toHex(matches.get(0)));

        // materialize 이후에도 findRevision은 여전히 정확해야 함(트라이/해시맵 두 경로 모두 살아있음).
        for (Map.Entry<Integer, byte[]> entry : groundTruth.entrySet()) {
            assertEquals(entry.getKey(), accelerated.findRevision(entry.getValue()));
        }
    }

    @Test
    @DisplayName("stale한(tip_rev가 실제 revisionCount와 안 맞는) 트라이는 조용히 무시되고 정확한 순차 스캔으로 fallback한다")
    void staleTrieFallsBackSafely() throws IOException {
        File idxFile = setupRealFixture();
        Map<Integer, byte[]> groundTruth = loadGroundTruthRevs();
        NodeMapFile freshNodeMap = NodeMapFile.tryLoad(idxFile);
        assertNotNull(freshNodeMap);

        // 트라이를 일부러 잘라내(rev 39가 아니라 20번째까지만 커버한다고 우기는) "stale" 시나리오를
        // 흉내내기 위해, tip_rev/tip_node가 다른 새 인덱스 파일(더 짧은)로 검증한다: 실제 hg가 만든
        // 40리비전 트라이(tipRev=39)를, 앞부분만 잘라낸(20리비전) 인덱스 파일에 붙이면 트라이가
        // "미래"를 가리키므로 stale로 취급돼야 한다.
        byte[] fullIndexBytes = Files.readAllBytes(idxFile.toPath());
        File truncatedIdx = tempDir.resolve("truncated.i").toFile();
        Files.write(truncatedIdx.toPath(), java.util.Arrays.copyOf(fullIndexBytes, 20 * 64));

        RevlogIndex accelerated = new RevlogIndex(truncatedIdx, freshNodeMap);
        assertFalse(accelerated.isNodeMapDeferred(),
                "트라이의 tip_rev(39)가 실제 리비전 수(20)와 안 맞으면 가속 경로를 쓰면 안 됨");
        assertEquals(20, accelerated.getRevisionCount());

        for (int rev = 0; rev < 20; rev++) {
            assertEquals(rev, accelerated.findRevision(groundTruth.get(rev)),
                    "stale-trie fallback이 여전히 정확해야 함 (rev " + rev + ")");
        }
    }
}
