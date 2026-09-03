package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.merge.MergeState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Assumptions;

public class HgResolveTest {

    @TempDir
    File tempDir;

    private byte[] createRealConflict() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(tempDir, "conflict.txt");
        Files.writeString(f.toPath(), "line1\n");
        hg.add().addFile("conflict.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(f.toPath(), "line1-A\n");
        byte[] other = hg.commit().setAuthor("T").setMessage("c2A").call();

        hg.update().setRevision("0").call();
        Files.writeString(f.toPath(), "line1-B\n");
        hg.commit().setAuthor("T").setMessage("c2B").call();

        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(other).call();
        assertTrue(result.isConflicted(), "Setup must actually produce a real conflict");
        return other;
    }

    @Test
    public void listShowsTheConflictedFileAsUnresolved() throws Exception {
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);

        Map<String, Boolean> listStates = new ResolveCommand(repo).list(true).call();
        assertEquals(1, listStates.size());
        assertFalse(listStates.get("conflict.txt"));
    }

    @Test
    public void markResolvedThenUnresolvedRoundTripsThroughState2() throws Exception {
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);

        Map<String, Boolean> afterResolve = new ResolveCommand(repo)
                .setFile("conflict.txt").markResolved(true).call();
        assertEquals(1, afterResolve.size());
        assertTrue(afterResolve.get("conflict.txt"));
        assertTrue(new ResolveCommand(repo).list(true).call().get("conflict.txt"));

        Map<String, Boolean> afterUnresolve = new ResolveCommand(repo)
                .setFile("conflict.txt").markUnresolved(true).call();
        assertFalse(afterUnresolve.get("conflict.txt"));
        assertFalse(new ResolveCommand(repo).list(true).call().get("conflict.txt"));
    }

    @Test
    public void listReturnsEmptyWhenThereIsNoMergeInProgress() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        assertTrue(new ResolveCommand(repo).list(true).call().isEmpty());
    }

    @Test
    public void markResolvedThrowsWhenThereIsNoMergeInProgress() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        assertThrows(HgValidationException.class, () ->
                new ResolveCommand(repo).setFile("nope.txt").markResolved(true).call());
    }

    @Test
    public void markResolvedThrowsForAFileNotPartOfTheCurrentMerge() throws Exception {
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);
        assertThrows(HgValidationException.class, () ->
                new ResolveCommand(repo).setFile("unrelated.txt").markResolved(true).call());
    }

    @Test
    public void constructorRejectsNullRepository() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ResolveCommand(null));
        assertEquals("Repository cannot be null", ex.getMessage());
    }

    @Test
    public void setFileWithoutMarkFlagsIsIgnoredAndListReturnsFullState() throws Exception {
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);

        // fileToMark set, but neither markResolved() nor markUnresolved() requested: the
        // "fileToMark != null && (markResolved || markUnresolved)" guard must short-circuit
        // to false here (unlike the all-flags-set cases already covered above) and fall
        // through to the plain list-building path.
        Map<String, Boolean> result = new ResolveCommand(repo).setFile("conflict.txt").list(true).call();
        assertEquals(1, result.size());
        assertFalse(result.get("conflict.txt"));
    }

    @Test
    public void markResolvedWithListTrueInSameCallReturnsFullStateMap() throws Exception {
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);

        // Unlike markResolvedThenUnresolvedRoundTripsThroughState2() (which calls list()
        // separately), requesting list(true) in the SAME call as markResolved(true) must
        // skip the single-entry early return and fall through to the full states map.
        Map<String, Boolean> result = new ResolveCommand(repo)
                .setFile("conflict.txt").markResolved(true).list(true).call();
        assertEquals(1, result.size());
        assertTrue(result.get("conflict.txt"));
    }

    @Test
    public void listTreatsEntryWithNoFieldsAsUnresolvedAndRecognizesResolvedPathConflict() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        File stateFile = new File(repo.getHgDir(), "merge/state2");

        MergeState ms = new MergeState();
        ms.local = new byte[20];
        ms.other = new byte[20];
        // A path with no fields at all (e.g. a legacy/foreign record hg4j doesn't fully
        // understand): isResolved() must treat this as unresolved via its "fields.isEmpty()"
        // branch, not the "fields == null" one (every key in mergeState.state always maps to
        // a real, non-null list — see ResolveCommand.isResolved()).
        ms.state.put("blank.txt", new ArrayList<>());
        // A path conflict already marked resolved ("pr"): isResolved() must recognize
        // MergeState.RESOLVED_PATH, not just MergeState.RESOLVED.
        ms.state.put("dir", new ArrayList<>(Arrays.asList(MergeState.RESOLVED_PATH, "dir~1", "dir")));
        ms.write(stateFile);

        Map<String, Boolean> listed = new ResolveCommand(repo).list(true).call();
        assertEquals(2, listed.size());
        assertFalse(listed.get("blank.txt"));
        assertTrue(listed.get("dir"));
    }

    @Tag("interop")
    @Test
    public void resolvedStateIsVisibleToRealHgResolveList() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(),
                "Native Mercurial (hg) is not installed. Skipping.");
        createRealConflict();
        HgRepository repo = new HgRepository(tempDir);

        new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();

        String nativeResolveList = HgTestUtils.hg(tempDir, "resolve", "--list");
        assertEquals("R conflict.txt", nativeResolveList.trim());
    }
}
