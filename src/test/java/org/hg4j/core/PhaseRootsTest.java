package org.hg4j.core;

import org.hg4j.lib.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PhaseRoots — Phase 메타데이터 제어 단위 테스트")
public class PhaseRootsTest {

    @TempDir
    Path tempDir;

    private File createTempFile(String content) throws IOException {
        File file = tempDir.resolve("phaseroots").toFile();
        if (content != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    // 부모 조회를 위한 헬퍼 맵 기반 함수
    private Function<NodeId, NodeId[]> createParentLookup(Map<NodeId, NodeId[]> parentMap) {
        return node -> parentMap.getOrDefault(node, new NodeId[0]);
    }

    @Test
    @DisplayName("파일이 없는 경우 모든 노드는 기본적으로 PUBLIC")
    void testGetPhase_noFile() throws IOException {
        File file = new File(tempDir.toFile(), "non_existent_phaseroots");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        NodeId node = NodeId.fromHex("a".repeat(40));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(node, n -> new NodeId[0]));
    }

    @Test
    @DisplayName("올바른 phaseroots 파일 파싱 및 직접 지정 노드 Phase 검증")
    void testGetPhase_directRoots() throws IOException {
        String hexDraft = "1".repeat(40);
        String hexSecret = "2".repeat(40);

        String fileContent = "1 " + hexDraft + "\n" + "2 " + hexSecret + "\n";
        File file = createTempFile(fileContent);

        PhaseRoots phaseRoots = new PhaseRoots(file);

        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(NodeId.fromHex(hexSecret), n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(NodeId.fromHex("0".repeat(40)), n -> new NodeId[0]));
    }

    @Test
    @DisplayName("계층 구조 상에서 조상의 Phase가 자식으로 올바르게 전파되는지 검증")
    void testGetPhase_inheritance() throws IOException {
        NodeId nRoot = NodeId.fromHex("0".repeat(40));     // Public Root
        NodeId nDraftRoot = NodeId.fromHex("1".repeat(40)); // Draft Root
        NodeId nDraftChild = NodeId.fromHex("a".repeat(40)); // Child of Draft Root
        NodeId nSecretRoot = NodeId.fromHex("2".repeat(40)); // Secret Root
        NodeId nSecretChild = NodeId.fromHex("b".repeat(40)); // Child of Secret Root
        NodeId nMergeChild = NodeId.fromHex("c".repeat(40)); // Merge Child (Draft Parent + Secret Parent)

        // 부모 관계 정의
        Map<NodeId, NodeId[]> parents = new HashMap<>();
        parents.put(nDraftChild, new NodeId[]{nDraftRoot});
        parents.put(nSecretChild, new NodeId[]{nSecretRoot});
        parents.put(nMergeChild, new NodeId[]{nDraftChild, nSecretChild});

        String fileContent = "1 " + nDraftRoot.toHex() + "\n" + "2 " + nSecretRoot.toHex() + "\n";
        File file = createTempFile(fileContent);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        Function<NodeId, NodeId[]> lookup = createParentLookup(parents);

        // 1. 조상이 없는 순수 노드는 PUBLIC
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(nRoot, lookup));

        // 2. Draft Root의 자손은 DRAFT 상속
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(nDraftChild, lookup));

        // 3. Secret Root의 자손은 SECRET 상속
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nSecretChild, lookup));

        // 4. Draft 부모와 Secret 부모를 모두 둔 자식은 더 엄격한 SECRET 상속
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nMergeChild, lookup));
    }

    @Test
    @DisplayName("setPhase로 Phase 변경 시 메모리 갱신 및 파일 직렬화 확인")
    void testSetPhase_andSerialization() throws IOException {
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        NodeId n1 = NodeId.fromHex("1".repeat(40));
        NodeId n2 = NodeId.fromHex("2".repeat(40));

        // 최초 상태는 PUBLIC
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(n1, n -> new NodeId[0]));

        // Phase 갱신
        phaseRoots.setPhase(n1, PhaseRoots.Phase.DRAFT, n -> new NodeId[0]);
        phaseRoots.setPhase(n2, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);

        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(n1, n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(n2, n -> new NodeId[0]));

        // 파일 쓰기 동기화 검증을 위해 새로운 인스턴스로 다시 로드
        PhaseRoots reloaded = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.DRAFT, reloaded.getPhase(n1, n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, reloaded.getPhase(n2, n -> new NodeId[0]));

        // 파일 텍스트 콘텐츠에 올바른 문자열 형식으로 저장되었는지 검증
        String savedContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(savedContent.contains("1 " + n1.toHex()));
        assertTrue(savedContent.contains("2 " + n2.toHex()));
    }
}
