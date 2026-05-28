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
}
