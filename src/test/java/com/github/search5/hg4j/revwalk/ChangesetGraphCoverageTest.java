package com.github.search5.hg4j.revwalk;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link ChangesetGraph}, targeting branches/lines left uncovered by
 * {@link ChangesetGraphTest}: sort-order/filter mutators, {@code -1}/null-parent edge cases,
 * duplicate-node revisits, the single-argument convenience overloads (which delegate through
 * {@code getRevlogLookup()} against a real {@link Revlog}), and RevFilter gating in both the
 * default BFS and TOPO traversal paths.
 */
@DisplayName("ChangesetGraph — coverage gap tests (edge cases, filters, real-hg cross-check)")
public class ChangesetGraphCoverageTest {

    private ChangesetGraph graph;
    private Map<Integer, int[]> mockParents;
    private Function<Integer, int[]> parentLookup;

    @BeforeEach
    void setUp() {
        graph = new ChangesetGraph(null);
        mockParents = new HashMap<>();
        parentLookup = rev -> mockParents.getOrDefault(rev, new int[]{-1, -1});
    }

    private void mockRevisionRecord(int rev, int p1, int p2) {
        mockParents.put(rev, new int[]{p1, p2});
    }

    // ---------------------------------------------------------------
    // setSortOrder / setRevFilter / getRevFilter mutators
    // ---------------------------------------------------------------

    @Test
    @DisplayName("setSortOrder(null) resets to SortOrder.DEFAULT")
    void testSetSortOrderNullResetsToDefault() {
        graph.setSortOrder(SortOrder.TOPO);
        assertEquals(SortOrder.TOPO, graph.getSortOrder());

        graph.setSortOrder(null);
        assertEquals(SortOrder.DEFAULT, graph.getSortOrder());
    }

    @Test
    @DisplayName("setRevFilter(null) resets to RevFilter.ALL")
    void testSetRevFilterNullResetsToAll() {
        graph.setRevFilter(null);
        assertSame(RevFilter.ALL, graph.getRevFilter());
    }

    @Test
    @DisplayName("setRevFilter stores and getRevFilter returns the exact custom filter instance")
    void testSetRevFilterCustomFilterIsStored() {
        RevFilter evenOnly = (rev, cl) -> rev % 2 == 0;
        graph.setRevFilter(evenOnly);
        assertSame(evenOnly, graph.getRevFilter());
    }

    // ---------------------------------------------------------------
    // getAllAncestors edge cases
    // ---------------------------------------------------------------

    @Test
    @DisplayName("getAllAncestors(-1) returns an empty set (sentinel revision is skipped)")
    void testGetAllAncestorsStartRevMinusOneReturnsEmpty() {
        Set<Integer> ancestors = graph.getAllAncestors(-1, parentLookup);
        assertTrue(ancestors.isEmpty());
    }

    @Test
    @DisplayName("getAllAncestors treats a null parents array as \"no parents\" instead of throwing")
    void testGetAllAncestorsNullParentsArray() {
        Function<Integer, int[]> nullParentLookup = rev -> null;
        Set<Integer> ancestors = graph.getAllAncestors(5, nullParentLookup);
        assertEquals(Set.of(5), ancestors);
    }

    @Test
    @DisplayName("getAllAncestors deduplicates a shared ancestor reached via two diamond paths")
    void testGetAllAncestorsDiamondDeduplicatesSharedAncestor() {
        // 0 -> 1 -> 3
        // 0 -> 2 -> 3   (0 is reachable twice: via 1 and via 2)
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);
        mockRevisionRecord(3, 1, 2);

        Set<Integer> ancestors = graph.getAllAncestors(3, parentLookup);
        assertEquals(Set.of(0, 1, 2, 3), ancestors);
    }

    // ---------------------------------------------------------------
    // lazyAncestors: single-arg overload, RevFilter gating, dfs edge cases
    // ---------------------------------------------------------------

    @Test
    @DisplayName("lazyAncestors(int) single-arg overload delegates to the two-arg overload via getRevlogLookup()")
    void testLazyAncestorsSingleArgDelegates() {
        // changelog is null, so getRevlogLookup() falls back to {-1,-1} for every revision.
        Iterator<Integer> it = graph.lazyAncestors(0);
        List<Integer> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        assertEquals(List.of(0), result);
    }

    @Test
    @DisplayName("lazyAncestors default BFS order still traverses through a filtered-out node's parents, but does not yield it")
    void testLazyAncestorsDefaultBfsRespectsRevFilterExclusion() {
        // 0 -> 1 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 2, -1);

        graph.setSortOrder(SortOrder.DEFAULT);
        graph.setRevFilter((rev, cl) -> rev != 2);

        Iterator<Integer> it = graph.lazyAncestors(3, parentLookup);
        List<Integer> result = new ArrayList<>();
        it.forEachRemaining(result::add);

        assertFalse(result.contains(2), "rev 2 is excluded by the filter");
        assertEquals(Set.of(0, 1, 3), new HashSet<>(result));
        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("lazyAncestors TOPO order filters the buffered topological list, excluding a filtered-out rev")
    void testLazyAncestorsTopoRespectsRevFilterExclusion() {
        // 0 -> 1 -> 2 -> 3
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 2, -1);

        graph.setSortOrder(SortOrder.TOPO);
        graph.setRevFilter((rev, cl) -> rev != 2);

        Iterator<Integer> it = graph.lazyAncestors(3, parentLookup);
        List<Integer> result = new ArrayList<>();
        it.forEachRemaining(result::add);

        assertEquals(List.of(3, 1, 0), result);
    }

    @Test
    @DisplayName("lazyAncestors TOPO with startRev=-1 yields an empty iterator (dfs's u==-1 guard)")
    void testLazyAncestorsTopoStartRevMinusOneYieldsEmpty() {
        graph.setSortOrder(SortOrder.TOPO);
        Iterator<Integer> it = graph.lazyAncestors(-1, parentLookup);
        assertFalse(it.hasNext());
    }

    @Test
    @DisplayName("lazyAncestors TOPO treats a null parents array as a leaf node instead of throwing (dfs's parents!=null guard)")
    void testLazyAncestorsTopoNullParentsArray() {
        graph.setSortOrder(SortOrder.TOPO);
        Function<Integer, int[]> nullParentLookup = rev -> null;
        Iterator<Integer> it = graph.lazyAncestors(5, nullParentLookup);
        List<Integer> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        assertEquals(List.of(5), result);
    }

    // ---------------------------------------------------------------
    // isAncestor edge cases: cache reuse, pruning, revisit, null parents
    // ---------------------------------------------------------------

    @Test
    @DisplayName("isAncestor reuses its internal cache on a repeated query with the same arguments")
    void testIsAncestorReusesCacheOnRepeatedQuery() {
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 2, -1);

        assertTrue(graph.isAncestor(1, 3, parentLookup));
        // Second call with identical (ancestor, descendant) hits the memoized cache branch.
        assertTrue(graph.isAncestor(1, 3, parentLookup));
    }

    @Test
    @DisplayName("isAncestor prunes a branch once it walks below the candidate ancestor's revision number")
    void testIsAncestorPrunesBelowAncestorRevision() {
        // 0 -> 1, 0 -> 2 (siblings sharing parent 0)
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 0, -1);

        // 1 is not an ancestor of 2: traversal from 2 reaches 0, which is < ancestor(1) and is pruned.
        assertFalse(graph.isAncestor(1, 2, parentLookup));
    }

    @Test
    @DisplayName("isAncestor's visited-set skips re-expanding a node reached twice through a diamond")
    void testIsAncestorSkipsRevisitedDiamondNode() {
        // 0 -> 1 -> 2 -> 4
        //      1 -> 3 -> 4   (node 1 is reachable twice while searching from 4)
        mockRevisionRecord(0, -1, -1);
        mockRevisionRecord(1, 0, -1);
        mockRevisionRecord(2, 1, -1);
        mockRevisionRecord(3, 1, -1);
        mockRevisionRecord(4, 2, 3);

        assertTrue(graph.isAncestor(0, 4, parentLookup));
    }

    @Test
    @DisplayName("isAncestor treats a null parents array as a dead end instead of throwing")
    void testIsAncestorNullParentsArrayDoesNotThrow() {
        mockRevisionRecord(0, -1, -1);
        Function<Integer, int[]> lookupWithNullAt2 = rev -> rev == 2 ? null : parentLookup.apply(rev);

        assertFalse(graph.isAncestor(0, 2, lookupWithNullAt2));
    }

    // ---------------------------------------------------------------
    // getLcaCandidates edge cases
    // ---------------------------------------------------------------

    @Test
    @DisplayName("getLcaCandidates(rev, rev) short-circuits to a singleton set")
    void testGetLcaCandidatesSameRevisionReturnsSingleton() {
        assertEquals(Set.of(7), graph.getLcaCandidates(7, 7, parentLookup));
    }

    @Test
    @DisplayName("getLcaCandidates treats a null parents array as a dead end instead of throwing")
    void testGetLcaCandidatesNullParentsArrayDoesNotThrow() {
        Function<Integer, int[]> nullParentLookup = rev -> null;
        Set<Integer> candidates = graph.getLcaCandidates(1, 2, nullParentLookup);
        assertTrue(candidates.isEmpty());
    }

    // ---------------------------------------------------------------
    // Real-hg-backed integration test for the single-arg convenience overloads
    // (getAllAncestors(int), lazyAncestors(int), isAncestor(int,int), getLcaCandidates(int,int)),
    // which delegate through getRevlogLookup() against a genuine on-disk Revlog.
    // ---------------------------------------------------------------

    @Tag("interop")
    @Test
    @DisplayName("single-arg overloads against a real hg repo match native `hg log -r ancestors(...)`")
    void testSingleArgOverloadsAgainstRealHgRepository(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(),
                "Native Mercurial (hg) is not installed. Skipping real-hg cross-check.");

        File repoDir = tempDir.resolve("repo").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");

        // rev 0: common base
        Files.writeString(repoDir.toPath().resolve("f.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "rev0");

        // rev 1: linear child of rev0
        Files.writeString(repoDir.toPath().resolve("f.txt"), "base\nbranchA\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "rev1");

        // rev 2: sibling of rev1, also a child of rev0 (new head)
        HgTestUtils.hg(repoDir, "update", "-r", "0");
        Files.writeString(repoDir.toPath().resolve("g.txt"), "branchB\n");
        HgTestUtils.hg(repoDir, "add", "g.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "rev2");

        // rev 3: merge of rev1 and rev2
        HgTestUtils.hg(repoDir, "merge", "-r", "1");
        HgTestUtils.hg(repoDir, "commit", "-m", "merge");

        File storeDir = new File(repoDir, ".hg/store");
        Revlog changelog = new Revlog(new File(storeDir, "00changelog.i"), new File(storeDir, "00changelog.d"));
        ChangesetGraph realGraph = new ChangesetGraph(changelog);

        String ancestorsOutput = HgTestUtils.hg(repoDir, "log", "-r", "ancestors(3)", "--template", "{rev} ");
        Set<Integer> expectedAncestors = Arrays.stream(ancestorsOutput.trim().split("\\s+"))
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
        assertEquals(Set.of(0, 1, 2, 3), expectedAncestors, "sanity-check the constructed repo shape");

        // getAllAncestors(int) single-arg overload
        assertEquals(expectedAncestors, realGraph.getAllAncestors(3));

        // isAncestor(int,int) single-arg overload
        assertTrue(realGraph.isAncestor(0, 3));
        assertTrue(realGraph.isAncestor(1, 3));
        assertTrue(realGraph.isAncestor(2, 3));
        assertFalse(realGraph.isAncestor(1, 2), "rev1 and rev2 are siblings, neither is the other's ancestor");

        // getLcaCandidates(int,int) single-arg overload
        assertEquals(Set.of(0), realGraph.getLcaCandidates(1, 2));

        // lazyAncestors(int) single-arg overload
        Iterator<Integer> it = realGraph.lazyAncestors(3);
        List<Integer> lazyResult = new ArrayList<>();
        it.forEachRemaining(lazyResult::add);
        assertEquals(expectedAncestors, new HashSet<>(lazyResult));
    }
}
