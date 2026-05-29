package org.hg4j.revwalk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangesetGraph — DAG 그래프 연산 및 LCA 산출 단위 테스트 (순수 자바)")
public class ChangesetGraphTest {

    private ChangesetGraph graph;
    private Map<Integer, int[]> mockParents;
    private Function<Integer, int[]> parentLookup;

    @BeforeEach
    void setUp() {
        // Revlog가 null이어도 parentLookup 함수 기반 오버로드를 테스트하므로 문제없음
        graph = new ChangesetGraph(null);
        mockParents = new HashMap<>();
        parentLookup = rev -> mockParents.getOrDefault(rev, new int[]{-1, -1});
    }

    private void mockRevisionRecord(int rev, int p1, int p2) {
        mockParents.put(rev, new int[]{p1, p2});
    }

    @Test
    @DisplayName("단순 선형 커밋 그래프에서 조상 관계 및 LCA 산출 검증")
    void testLinearGraph() {
        // 그래프 구조: 0 -> 1 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 2, -1);

        // 1. getAllAncestors 검증
        Set<Integer> ancestors = graph.getAllAncestors(3, parentLookup);
        assertNotNull(ancestors);
        assertEquals(4, ancestors.size());
        assertTrue(ancestors.containsAll(Set.of(0, 1, 2, 3)));

        // 2. isAncestor 검증
        assertTrue(graph.isAncestor(1, 3, parentLookup));
        assertTrue(graph.isAncestor(2, 2, parentLookup));
        assertFalse(graph.isAncestor(3, 1, parentLookup));
        assertFalse(graph.isAncestor(99, 3, parentLookup)); // 조상이 없는 임의 리비전

        // 3. LCA Candidates 검증
        Set<Integer> lca = graph.getLcaCandidates(2, 3, parentLookup);
        assertNotNull(lca);
        assertEquals(1, lca.size());
        assertTrue(lca.contains(2));
    }

    @Test
    @DisplayName("분기 그래프 구조에서 LCA 산출 검증")
    void testBranchedGraph() {
        // 그래프 구조: 0 -> 1, 0 -> 2
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);

        Set<Integer> lca = graph.getLcaCandidates(1, 2, parentLookup);
        assertNotNull(lca);
        assertEquals(1, lca.size());
        assertTrue(lca.contains(0));
    }

    @Test
    @DisplayName("크리스-크로스 (Criss-Cross) 머지 그래프 구조에서 다중 LCA 산출 검증")
    void testCrissCrossGraph() {
        // 그래프 구조:
        // 0 -> 1 -> 3
        // 0 -> 2 -> 4
        // 1 -> 4 (4의 부모는 2와 1)
        // 2 -> 3 (3의 부모는 1와 2)
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);
        mockRevisionRecord(3, 1, 2); // parents: 1, 2
        mockRevisionRecord(4, 2, 1); // parents: 2, 1

        // 공통 조상은 {0, 1, 2} 이나, 0은 1과 2의 조상이므로 제거되어야 함.
        // 따라서 베스트 LCA 후보는 {1, 2} 2개여야 함.
        Set<Integer> lca = graph.getLcaCandidates(3, 4, parentLookup);
        assertNotNull(lca);
        assertEquals(2, lca.size());
        assertTrue(lca.containsAll(Set.of(1, 2)));
    }

    @Test
    @DisplayName("위상 정렬(SortOrder.TOPO) 설정 시 자식이 부모보다 항상 먼저 반환되는지 검증")
    void testTopologicalSortOrder() {
        // 그래프 구조:
        // 0 -> 1 -> 3
        // 0 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);
        mockRevisionRecord(3, 1, 2);

        // 1. DEFAULT 정렬 검증 (기본 BFS 순서 등)
        graph.setSortOrder(SortOrder.DEFAULT);
        assertEquals(SortOrder.DEFAULT, graph.getSortOrder());
        
        java.util.Iterator<Integer> defaultIt = graph.lazyAncestors(3, parentLookup);
        java.util.List<Integer> defaultList = new java.util.ArrayList<>();
        defaultIt.forEachRemaining(defaultList::add);
        assertFalse(defaultList.isEmpty());

        // 2. TOPO 정렬 검증 (자식이 부모보다 항상 앞서는 위상 정렬 순서)
        graph.setSortOrder(SortOrder.TOPO);
        assertEquals(SortOrder.TOPO, graph.getSortOrder());

        java.util.Iterator<Integer> topoIt = graph.lazyAncestors(3, parentLookup);
        java.util.List<Integer> topoList = new java.util.ArrayList<>();
        topoIt.forEachRemaining(topoList::add);

        // 결과는 [3, 2, 1, 0] 또는 [3, 1, 2, 0] 형태여야 함
        assertEquals(4, topoList.size());
        
        int idx3 = topoList.indexOf(3);
        int idx2 = topoList.indexOf(2);
        int idx1 = topoList.indexOf(1);
        int idx0 = topoList.indexOf(0);

        // 자식이 부모보다 항상 먼저 와야 한다는 위상적 제약 조건(Invariants)을 수학적으로 완벽 검증
        assertTrue(idx3 < idx1, "자식 3은 부모 1보다 먼저 와야 합니다.");
        assertTrue(idx3 < idx2, "자식 3은 부모 2보다 먼저 와야 합니다.");
        assertTrue(idx3 < idx0, "자식 3은 조상 0보다 먼저 와야 합니다.");
        assertTrue(idx1 < idx0, "자식 1은 부모 0보다 먼저 와야 합니다.");
        assertTrue(idx2 < idx0, "자식 2은 부모 0보다 먼저 와야 합니다.");
    }
}
