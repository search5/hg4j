package com.github.search5.hg4j.revwalk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangesetGraph — Unit tests for DAG operations and LCA calculation (Pure Java)")
public class ChangesetGraphTest {

    private ChangesetGraph graph;
    private Map<Integer, int[]> mockParents;
    private Function<Integer, int[]> parentLookup;

    @BeforeEach
    void setUp() {
        // Even if Revlog is null, we test the parentLookup function-based overload, so it is safe
        graph = new ChangesetGraph(null);
        mockParents = new HashMap<>();
        parentLookup = rev -> mockParents.getOrDefault(rev, new int[]{-1, -1});
    }

    private void mockRevisionRecord(int rev, int p1, int p2) {
        mockParents.put(rev, new int[]{p1, p2});
    }

    @Test
    @DisplayName("Verify ancestor relationship and LCA calculation in a simple linear commit graph")
    void testLinearGraph() {
        // Graph structure: 0 -> 1 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 2, -1);

        // 1. Verify getAllAncestors
        Set<Integer> ancestors = graph.getAllAncestors(3, parentLookup);
        assertNotNull(ancestors);
        assertEquals(4, ancestors.size());
        assertTrue(ancestors.containsAll(Set.of(0, 1, 2, 3)));

        // 2. Verify isAncestor
        assertTrue(graph.isAncestor(1, 3, parentLookup));
        assertTrue(graph.isAncestor(2, 2, parentLookup));
        assertFalse(graph.isAncestor(3, 1, parentLookup));
        assertFalse(graph.isAncestor(99, 3, parentLookup)); // Arbitrary revision with no ancestors

        // 3. Verify LCA Candidates
        Set<Integer> lca = graph.getLcaCandidates(2, 3, parentLookup);
        assertNotNull(lca);
        assertEquals(1, lca.size());
        assertTrue(lca.contains(2));
    }

    @Test
    @DisplayName("Verify LCA calculation in a branched graph structure")
    void testBranchedGraph() {
        // Graph structure: 0 -> 1, 0 -> 2
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);

        Set<Integer> lca = graph.getLcaCandidates(1, 2, parentLookup);
        assertNotNull(lca);
        assertEquals(1, lca.size());
        assertTrue(lca.contains(0));
    }

    @Test
    @DisplayName("Verify multiple LCA calculation in a criss-cross merge graph structure")
    void testCrissCrossGraph() {
        // Graph structure:
        // 0 -> 1 -> 3
        // 0 -> 2 -> 4
        // 1 -> 4 (Parents of 4 are 2 and 1)
        // 2 -> 3 (Parents of 3 are 1 and 2)
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);
        mockRevisionRecord(3, 1, 2); // parents: 1, 2
        mockRevisionRecord(4, 2, 1); // parents: 2, 1

        // Common ancestors are {0, 1, 2}, but 0 is an ancestor of 1 and 2, so it should be removed.
        // Thus, the best LCA candidates should be {1, 2}.
        Set<Integer> lca = graph.getLcaCandidates(3, 4, parentLookup);
        assertNotNull(lca);
        assertEquals(2, lca.size());
        assertTrue(lca.containsAll(Set.of(1, 2)));
    }

    @Test
    @DisplayName("Verify that children are always returned before parents when SortOrder.TOPO is set")
    void testTopologicalSortOrder() {
        // Graph structure:
        // 0 -> 1 -> 3
        // 0 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);
        mockRevisionRecord(3, 1, 2);

        // 1. Verify DEFAULT sorting (basic BFS order, etc.)
        graph.setSortOrder(SortOrder.DEFAULT);
        assertEquals(SortOrder.DEFAULT, graph.getSortOrder());
        
        java.util.Iterator<Integer> defaultIt = graph.lazyAncestors(3, parentLookup);
        java.util.List<Integer> defaultList = new java.util.ArrayList<>();
        defaultIt.forEachRemaining(defaultList::add);
        assertFalse(defaultList.isEmpty());

        // 2. Verify TOPO sorting (topological order where children always precede parents)
        graph.setSortOrder(SortOrder.TOPO);
        assertEquals(SortOrder.TOPO, graph.getSortOrder());

        java.util.Iterator<Integer> topoIt = graph.lazyAncestors(3, parentLookup);
        java.util.List<Integer> topoList = new java.util.ArrayList<>();
        topoIt.forEachRemaining(topoList::add);

        // Result should be in the form of [3, 2, 1, 0] or [3, 1, 2, 0]
        assertEquals(4, topoList.size());
        
        int idx3 = topoList.indexOf(3);
        int idx2 = topoList.indexOf(2);
        int idx1 = topoList.indexOf(1);
        int idx0 = topoList.indexOf(0);

        // Verify the topological invariants that a child must always precede its parents
        assertTrue(idx3 < idx1, "Child 3 must precede parent 1.");
        assertTrue(idx3 < idx2, "Child 3 must precede parent 2.");
        assertTrue(idx3 < idx0, "Child 3 must precede ancestor 0.");
        assertTrue(idx1 < idx0, "Child 1 must precede parent 0.");
        assertTrue(idx2 < idx0, "Child 2 must precede parent 0.");
    }
}
