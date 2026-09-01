package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgValidationException;
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
