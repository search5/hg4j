package io.github.search5.hg4j.obsolete;

import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ObsUtilTest {

    private static byte[] node(int n) {
        byte[] b = new byte[20];
        b[19] = (byte) n;
        return b;
    }

    private static String hex(int n) {
        return NodeIdUtil.toHex(node(n));
    }

    private static ObsStore.Marker marker(int pre, int... sucs) {
        List<byte[]> list = new ArrayList<>();
        for (int s : sucs) {
            list.add(node(s));
        }
        return new ObsStore.Marker(node(pre), list, 0, new TreeMap<>(), 0.0, 0, null);
    }

    private static List<List<String>> sets(Set<Integer> known, int initial, ObsStore.Marker... markers) {
        Map<String, List<ObsStore.Marker>> by = ObsUtil.bySuccessor(List.of(markers));
        return ObsUtil.successorsSets(by, h -> known.contains(Integer.parseInt(h.substring(38), 16)), hex(initial));
    }

    @Test
    void aChangesetWithoutMarkersIsItsOwnSuccessor() {
        assertEquals(List.of(List.of(hex(1))), sets(Set.of(1), 1));
    }

    @Test
    void aReplacementChainEndsAtTheLastKnownChangeset() {
        assertEquals(List.of(List.of(hex(3))), sets(Set.of(1, 2, 3), 1, marker(1, 2), marker(2, 3)));
    }

    @Test
    void aPrunedChangesetHasNoSuccessorsSet() {
        assertEquals(List.of(), sets(Set.of(1), 1, marker(1)));
    }

    @Test
    void aSuccessorThatIsNotKnownLocallyCountsAsPruned() {
        assertEquals(List.of(), sets(Set.of(1), 1, marker(1, 9)));
    }

    @Test
    void aSplitGivesOneSetOfSeveralChangesets() {
        assertEquals(List.of(List.of(hex(2), hex(3))), sets(Set.of(1, 2, 3), 1, marker(1, 2, 3)));
    }

    @Test
    void aDivergenceGivesOneSetPerMarker() {
        List<List<String>> result = sets(Set.of(1, 2, 3), 1, marker(1, 2), marker(1, 3));
        assertEquals(2, result.size());
        assertEquals(Set.of(List.of(hex(2)), List.of(hex(3))), Set.copyOf(result));
    }

    @Test
    void anObsolescenceCycleDoesNotLoopForever() {
        assertEquals(List.of(), sets(Set.of(1, 2), 1, marker(1, 2), marker(2, 1)));
    }
}
