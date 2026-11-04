package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@code .hg/rebasestate}: the file Mercurial 7.2 wrote for a rebase of two changesets that stopped on a conflict. */
class RebaseStateTest {

    private static final String REAL = "6dff1289d40159605e890e465b3c13ffaa7beb71\n\n0000000000000000000000000000000000000000\n0\n0\n0\nmark\n"
            + "af3ce28cd68776780ac91a241e439cf46fbecb07:-1:6dff1289d40159605e890e465b3c13ffaa7beb71\n"
            + "06606a1dba48c20ffa342960a6f24ce26687dd94:-1:6dff1289d40159605e890e465b3c13ffaa7beb71\n";

    @Test
    void theFileMercurialWroteIsReadAndWrittenBackIdentically() throws Exception {
        RebaseState s = RebaseState.parse(REAL.getBytes(StandardCharsets.UTF_8));
        assertEquals("6dff1289d40159605e890e465b3c13ffaa7beb71", s.originalWd);
        assertEquals(RebaseState.NULL_HEX, s.external);
        assertFalse(s.collapse || s.keep || s.keepBranches);
        assertEquals("mark", s.activeBookmark);
        assertEquals(List.of("af3ce28cd68776780ac91a241e439cf46fbecb07", "06606a1dba48c20ffa342960a6f24ce26687dd94"), s.todo());
        assertEquals(Set.of(), s.skipped());
        assertEquals(REAL, new String(s.toBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void anActiveBookmarkLineThatLooksLikeAStateIsReadAsAState() throws Exception {
        // files of old versions have no bookmark line: line 6 is already a state line (81 characters with a colon)
        String old = "6dff1289d40159605e890e465b3c13ffaa7beb71\n\n0000000000000000000000000000000000000000\n1\n0\n1\n"
                + "af3ce28cd68776780ac91a241e439cf46fbecb07:" + "bb".repeat(20) + "\n";
        RebaseState s = RebaseState.parse(old.getBytes());
        assertTrue(s.collapse && s.keepBranches && !s.keep);
        assertEquals("", s.activeBookmark);
        assertEquals(1, s.state.size());
    }

    @Test
    void theDestinationOfAnOldFileComesFromLineOne() throws Exception {
        String legacy = "aa".repeat(20) + "\n" + "bb".repeat(20) + "\n" + RebaseState.NULL_HEX + "\n0\n0\n0\n\n" + "cc".repeat(20) + ":-1\n";
        RebaseState s = RebaseState.parse(legacy.getBytes());
        assertEquals("bb".repeat(20), s.state.get("cc".repeat(20)).destNode());
    }

    @Test
    void legacyStatesAreSkippedAndAnIncompleteFileIsRefused() throws Exception {
        String text = "aa".repeat(20) + "\n\n" + RebaseState.NULL_HEX + "\n0\n0\n0\n\n" + "cc".repeat(20) + ":-2:" + "dd".repeat(20) + "\n";
        assertEquals(0, RebaseState.parse(text.getBytes()).state.size());
        assertEquals(".hg/rebasestate is incomplete",
                assertThrows(HgValidationException.class, () -> RebaseState.parse(("aa".repeat(20) + "\n\n" + RebaseState.NULL_HEX + "\n0\n").getBytes())).getMessage());
    }

    @Test
    void skippedChangesetsAreWorkedOutAgainFromTheState() throws Exception {
        String dest = "dd".repeat(20);
        String a = "a1".repeat(20);
        String b = "b1".repeat(20);
        String c = "c1".repeat(20);
        // a was found to be in the destination already: its new node is the destination itself (seen), b was rebased, c is to do
        String text = a + "\n\n" + RebaseState.NULL_HEX + "\n0\n0\n0\n\n" + a + ":" + dest + ":" + dest + "\n" + b + ":" + "e1".repeat(20) + ":" + dest
                + "\n" + c + ":-1:" + dest + "\n";
        RebaseState s = RebaseState.parse(text.getBytes());
        assertEquals(Set.of(a), s.skipped());
        assertEquals(List.of(c), s.todo());
        // collapsing never skips
        String collapsing = text.replace(RebaseState.NULL_HEX + "\n0\n", RebaseState.NULL_HEX + "\n1\n");
        assertEquals(Set.of(), RebaseState.parse(collapsing.getBytes()).skipped());
    }
}
