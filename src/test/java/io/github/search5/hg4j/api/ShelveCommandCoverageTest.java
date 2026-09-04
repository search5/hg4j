package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for ShelveCommand's less common branches: files missing from disk
 * without being formally removed, a missing/corrupted store, dirstate parent fallback,
 * symlink shelve/unshelve round trips, pre-first-commit shelving, and legacy/edge-case
 * state-file parsing during unshelve.
 */
public class ShelveCommandCoverageTest {

    private static File filelogIndex(HgRepository repository, String path) {
        return CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
    }

    private static File filelogData(File flIdx) {
        return new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
    }

    @Test
    public void shelveSkipsFilesMarkedAddedOrCleanThatAreMissingFromDiskWithoutHgRemove(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        // Committed "clean" file whose entry stays 'n' in the dirstate, but the file is
        // deleted straight from disk without going through RemoveCommand (e.g. rm outside hg).
        File clean = new File(repoDir, "clean.txt");
        Files.writeString(clean.toPath(), "clean content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();
        Files.delete(clean.toPath());

        // Added file that is also missing from disk before it was ever committed.
        File added = new File(repoDir, "added.txt");
        Files.writeString(added.toPath(), "added content");
        new AddCommand(repository).addFile("added.txt").call();
        Files.delete(added.toPath());

        new ShelveCommand(repository).setName("missing-files").call();

        assertFalse(new File(repository.getHgDir(), "shelved/missing-files.state").exists(),
                "Files missing from disk without an explicit hg remove must be skipped, leaving nothing to shelve");
    }

    @Test
    public void shelveHandlesARemovedEntryWhenNoBaselineChangelogExistsYet(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        // Directly craft a 'removed' dirstate entry without ever having created a commit,
        // so 00changelog.i does not exist yet -- getBaselineContent() must degrade gracefully.
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("ghost.txt", new Dirstate.Entry('r', 0, 0, 0));
        repository.writeDirstate(dirstate);

        new ShelveCommand(repository).setName("no-changelog").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "no-changelog.state").exists());
        String patch = Files.readString(new File(shelvedDir, "no-changelog.patch").toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("ghost.txt"));
    }

    @Test
    public void shelveGeneratesEmptyBaselineWhenRemovedFilesFilelogIsMissingFromStore(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File file = new File(repoDir, "orphan.txt");
        Files.writeString(file.toPath(), "orphan content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        // Simulate a corrupted/incomplete store: the filelog backing the committed file is gone.
        File flIdx = filelogIndex(repository, "orphan.txt");
        File flDat = filelogData(flIdx);
        Files.deleteIfExists(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());

        new RemoveCommand(repository).setFile("orphan.txt").setForce(true).call();

        new ShelveCommand(repository).setName("orphan-filelog").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "orphan-filelog.state").exists(),
                "Shelve must still succeed even though the removed file's filelog is missing");
    }

    @Test
    public void shelveTextDiffToleratesCorruptedFilelogDataButRevertStillSurfacesIt(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File removedFile = new File(repoDir, "removed.txt");
        Files.writeString(removedFile.toPath(), "removed baseline");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        // Keep the .i index (so the manifest lookup and filelog-exists checks succeed) but
        // delete the .d data file, which makes reading the revision content throw.
        Files.deleteIfExists(filelogData(filelogIndex(repository, "removed.txt")).toPath());

        new RemoveCommand(repository).setFile("removed.txt").setForce(true).call();

        // generateDiff()'s getBaselineContent() call is wrapped in a try/catch and falls back
        // to an empty baseline instead of crashing while building the human-readable .patch
        // text. But restoring removed.txt's original bytes back onto disk (so the working
        // copy is clean after the removal is shelved away) genuinely needs that data, so the
        // corruption must still surface as a failure from the overall shelve() call.
        IOException ex = assertThrows(IOException.class,
                () -> new ShelveCommand(repository).setName("corrupt-data").call());
        assertTrue(ex.getMessage().contains("does not exist"));

        // The .patch file is written before the failing revert step, and it must reflect the
        // tolerant empty-baseline fallback rather than having propagated the read failure.
        File patchFile = new File(repository.getHgDir(), "shelved/corrupt-data.patch");
        String patch = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("@@ -1,0 +0,0 @@"), "Diff header must reflect the empty fallback baseline, not crash");
    }

    @Test
    public void shelveAndUnshelveRoundTripsANewlyAddedSymlinkNotYetInAnyCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        // Baseline commit of an unrelated file, so the shelved symlink is "added" relative
        // to a real prior commit (exercises the "not in latest manifest -> delete" path).
        File other = new File(repoDir, "other.txt");
        Files.writeString(other.toPath(), "other content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        File target = new File(repoDir, "target.txt");
        Files.writeString(target.toPath(), "target content");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repository).addFile("link.txt").call();

        new ShelveCommand(repository).setName("add-symlink").call();

        assertFalse(Files.exists(link.toPath()) || Files.isSymbolicLink(link.toPath()),
                "The newly added symlink must be removed from disk after shelving");

        new ShelveCommand(repository).setName("add-symlink").setUnshelve(true).call();

        assertTrue(Files.isSymbolicLink(link.toPath()),
                "Unshelving must restore the file as an actual symlink, not a regular file containing the target path");
        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString());
        assertEquals('a', repository.getDirstate().getEntries().get("link.txt").getState());
    }

    @Test
    public void shelveAndUnshelveRoundTripsAModifiedSymlinkTarget(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File originalTarget = new File(repoDir, "target.txt");
        Files.writeString(originalTarget.toPath(), "target content");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("commit symlink").call();

        // Re-point the symlink at a differently-named (and differently-sized), but still
        // existing, target so the size-based modification check trips deterministically
        // without the temp-commit's "tracked file not found on disk" check rejecting a
        // dangling symlink.
        File renamedTarget = new File(repoDir, "renamed_target.txt");
        Files.writeString(renamedTarget.toPath(), "renamed content");
        Files.delete(link.toPath());
        Files.createSymbolicLink(link.toPath(), Path.of("renamed_target.txt"));

        new ShelveCommand(repository).setName("mod-symlink").call();

        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString(),
                "Shelving must restore the symlink to its committed target");

        new ShelveCommand(repository).setName("mod-symlink").setUnshelve(true).call();

        assertEquals("renamed_target.txt", Files.readSymbolicLink(link.toPath()).toString(),
                "Unshelving must restore the pending symlink retarget, not a regular file with the path as text");
    }

    @Test
    public void unshelveThrowsWhenNoShelveExistsForGivenName(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        ShelveCommand cmd = new ShelveCommand(repository).setName("does-not-exist").setUnshelve(true);
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Shelve file not found"));
    }

    @Test
    public void unshelveHandlesLegacyTwoTokenStateFormatWithoutModeField(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File file = new File(repoDir, "legacy.txt");
        Files.writeString(file.toPath(), "legacy baseline");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        Files.writeString(file.toPath(), "legacy modified");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("legacy.txt", new Dirstate.Entry('m', 0644, 15, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        new ShelveCommand(repository).setName("legacy").call();

        // Rewrite the state file dropping the trailing mode token to simulate the older
        // two-token ("path state") on-disk format that unshelve must still be able to parse.
        File shelvedDir = new File(repository.getHgDir(), "shelved");
        File stateFile = new File(shelvedDir, "legacy.state");
        List<String> lines = Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8);
        StringBuilder rewritten = new StringBuilder();
        for (String line : lines) {
            String[] tokens = line.split(" ");
            if (tokens.length == 3) {
                rewritten.append(tokens[0]).append(" ").append(tokens[1]).append("\n");
            } else {
                rewritten.append(line).append("\n");
            }
        }
        Files.writeString(stateFile.toPath(), rewritten.toString(), StandardCharsets.UTF_8);

        new ShelveCommand(repository).setName("legacy").setUnshelve(true).call();

        assertEquals("legacy modified", Files.readString(file.toPath()));
    }

    @Test
    public void unshelveDefaultsToModifiedStateWhenFileMissingFromStateMetadata(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File keep = new File(repoDir, "keep.txt");
        Files.writeString(keep.toPath(), "keep baseline");
        File other = new File(repoDir, "other.txt");
        Files.writeString(other.toPath(), "other baseline");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        Files.writeString(keep.toPath(), "keep modified");
        Files.writeString(other.toPath(), "other modified");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("keep.txt", new Dirstate.Entry('m', 0644, 13, System.currentTimeMillis() / 1000));
        dirstate.addEntry("other.txt", new Dirstate.Entry('m', 0644, 14, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        new ShelveCommand(repository).setName("missing-meta").call();

        // Drop the "other.txt" line from the state file (and decrement its count) while
        // leaving the .hg bundle untouched, so the bundle still carries a fileGroup whose
        // path has no entry in fileStates -- unshelve must default that file's state to 'm'.
        File shelvedDir = new File(repository.getHgDir(), "shelved");
        File stateFile = new File(shelvedDir, "missing-meta.state");
        List<String> lines = Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8);
        StringBuilder rewritten = new StringBuilder();
        int count = 0;
        List<String> fileLines = lines.subList(5, lines.size());
        for (String line : fileLines) {
            if (!line.startsWith("other.txt ")) {
                count++;
            }
        }
        for (int i = 0; i < 4; i++) {
            rewritten.append(lines.get(i)).append("\n");
        }
        rewritten.append(count).append("\n");
        for (String line : fileLines) {
            if (!line.startsWith("other.txt ")) {
                rewritten.append(line).append("\n");
            }
        }
        Files.writeString(stateFile.toPath(), rewritten.toString(), StandardCharsets.UTF_8);

        new ShelveCommand(repository).setName("missing-meta").setUnshelve(true).call();

        assertEquals("keep modified", Files.readString(keep.toPath()));
        assertEquals("other modified", Files.readString(other.toPath()),
                "File missing from state metadata must still be restored, defaulting to modified state");
        // Since 2026-09-04, unshelve's own default-'m'-when-missing-from-metadata fallback (still
        // exercised above, for the throwaway restore commit's content) no longer leaks into the
        // FINAL dirstate: the pending-change classification is now recomputed generically by
        // diffing the restored/rebased commit's manifest against the true working-directory
        // parent's, which reports a tracked-and-modified file as 'n' -- the same state a real
        // tracked-modified file always has (real hg reports "M" for it via its own dirstate-vs-
        // disk racy check, not a special dirstate character), not hg4j's old ad hoc 'm' passthrough.
        assertEquals('n', repository.getDirstate().getEntries().get("other.txt").getState());
    }

    @Test
    public void shelveAndUnshelveRoundTripBeforeAnyBaselineCommitExists(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File added = new File(repoDir, "brand_new.txt");
        Files.writeString(added.toPath(), "brand new content");
        new AddCommand(repository).addFile("brand_new.txt").call();

        new ShelveCommand(repository).setName("pre-commit").call();

        assertFalse(added.exists(), "Added file must be removed from disk when there is no baseline commit to revert to");
        assertTrue(repository.getDirstate().getEntries().isEmpty() || !repository.getDirstate().getEntries().containsKey("brand_new.txt"));

        new ShelveCommand(repository).setName("pre-commit").setUnshelve(true).call();

        assertEquals("brand new content", Files.readString(added.toPath()));
        assertEquals('a', repository.getDirstate().getEntries().get("brand_new.txt").getState());
    }

    @Test
    public void shelveAndUnshelveHandleDiffTextWhenContentEndsWithTrailingNewline(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File removed = new File(repoDir, "removed.txt");
        Files.writeString(removed.toPath(), "removed line one\nremoved line two\n");
        File modified = new File(repoDir, "modified.txt");
        Files.writeString(modified.toPath(), "old line one\nold line two\n");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        new RemoveCommand(repository).setFile("removed.txt").call();

        Files.writeString(modified.toPath(), "new line one\nnew line two\n");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("modified.txt", new Dirstate.Entry('m', 0644, 26, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        File added = new File(repoDir, "added.txt");
        Files.writeString(added.toPath(), "added line one\nadded line two\n");
        new AddCommand(repository).addFile("added.txt").call();

        new ShelveCommand(repository).setName("trailing-nl").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        String patch = Files.readString(new File(shelvedDir, "trailing-nl.patch").toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("@@ -1,2 +0,0 @@"), "Removed-file hunk header must count exactly 2 lines, not a phantom trailing blank line");
        assertTrue(patch.contains("@@ -1,2 +1,2 @@"), "Modified-file hunk header must count exactly 2/2 lines");
        assertTrue(patch.contains("@@ -0,0 +1,2 @@"), "Added-file hunk header must count exactly 2 lines");

        new ShelveCommand(repository).setName("trailing-nl").setUnshelve(true).call();
        assertEquals("new line one\nnew line two\n", Files.readString(modified.toPath()));
        assertEquals("added line one\nadded line two\n", Files.readString(added.toPath()));
        assertFalse(removed.exists());
    }

    @Test
    public void shelveHandlesARemovedEntryWhenChangelogExistsButHasNoRevisionsYet(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        // 00changelog.i present on disk (unlike the "no changelog file at all" case) but with
        // zero revisions in it, so getBaselineContent()'s p1-not-found fallback
        // (lastRev = getRevisionCount() - 1) must itself still be negative, exercising the
        // "still nothing to fall back to" guard rather than the "no changelog file" early exit.
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        Files.write(clIdx.toPath(), new byte[0]);

        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("ghost.txt", new Dirstate.Entry('r', 0, 0, 0));
        repository.writeDirstate(dirstate);

        new ShelveCommand(repository).setName("empty-changelog").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "empty-changelog.state").exists());
        String patch = Files.readString(new File(shelvedDir, "empty-changelog.patch").toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("@@ -1,0 +0,0 @@"),
                "An existing but revision-less changelog must fall back to an empty baseline, not crash");
    }

    @Test
    public void shelveFallsBackToEmptyBaselineWhenCommittedNodeIdCannotBeFoundInTheFilelog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File removed = new File(repoDir, "removed.txt");
        Files.writeString(removed.toPath(), "original content");
        File other = new File(repoDir, "other.txt");
        Files.writeString(other.toPath(), "totally different content, different length");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        // Splice a sibling file's filelog onto removed.txt's on-disk filelog. The manifest
        // still names removed.txt's ORIGINAL node id, but the physical filelog backing that
        // path no longer contains a revision with that id -- getBaselineContent() must
        // recognize the lookup miss (NodeIdUtil.findRevisionByNodeId returning -1) and return
        // null rather than mis-reading an unrelated revision.
        File flIdx = filelogIndex(repository, "removed.txt");
        File flDat = filelogData(flIdx);
        File otherFlIdx = filelogIndex(repository, "other.txt");
        File otherFlDat = filelogData(otherFlIdx);
        Files.copy(otherFlIdx.toPath(), flIdx.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(otherFlDat.toPath(), flDat.toPath(), StandardCopyOption.REPLACE_EXISTING);
        repository.clearRevlogCache();

        new RemoveCommand(repository).setFile("removed.txt").setForce(true).call();

        new ShelveCommand(repository).setName("swapped-filelog").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "swapped-filelog.state").exists());
        String patch = Files.readString(new File(shelvedDir, "swapped-filelog.patch").toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("@@ -1,0 +0,0 @@"),
                "Baseline must fall back to empty when the committed node id can no longer be found in the filelog");
    }

    @Test
    public void shelveTextDiffToleratesCorruptedFilelogDataForAModifiedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File modified = new File(repoDir, "modified.txt");
        Files.writeString(modified.toPath(), "original content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        // Same corruption technique as the removed-file case above, but this time the corrupted
        // file is being MODIFIED rather than removed, exercising generateDiff()'s separate
        // try/catch around getBaselineContent() for the 'm'/'n' branch (the 'r' branch has its
        // own, already-covered catch).
        Files.deleteIfExists(filelogData(filelogIndex(repository, "modified.txt")).toPath());

        Files.writeString(modified.toPath(), "new content");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("modified.txt", new Dirstate.Entry('m', 0644, 11, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        IOException ex = assertThrows(IOException.class,
                () -> new ShelveCommand(repository).setName("corrupt-mod").call());
        assertTrue(ex.getMessage().contains("does not exist"));

        File patchFile = new File(repository.getHgDir(), "shelved/corrupt-mod.patch");
        String patch = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("@@ -1,0 +1,1 @@"),
                "Diff header must reflect the empty fallback baseline for the modified file, not crash");
    }

    @Test
    public void stripRevisionsIgnoresACorruptedFilelogListedInFncache(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "baseline content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        Files.writeString(file.toPath(), "modified content");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("a.txt", new Dirstate.Entry('m', 0644, 17, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        // Register a bogus fncache entry that points at a directory instead of a real filelog
        // index. stripRevisionsFrom()'s per-filelog truncation loop (run as part of a normal
        // shelve) must swallow the resulting failure to open it and still finish shelving the
        // real, unrelated change.
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        List<String> existing = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
        File bogusIdx = new File(repository.getStoreDir(), "data/bogus.i");
        bogusIdx.mkdirs();
        List<String> updated = new ArrayList<>(existing);
        updated.add("data/bogus.i");
        Files.write(fncacheFile.toPath(), String.join("\n", updated).getBytes(StandardCharsets.UTF_8));

        new ShelveCommand(repository).setName("bogus-fncache").call();

        assertEquals("baseline content", Files.readString(file.toPath()),
                "Shelve must still succeed and revert the working copy even though an unrelated fncache entry is corrupted");
        assertTrue(new File(repository.getHgDir(), "shelved/bogus-fncache.state").exists());

        new ShelveCommand(repository).setName("bogus-fncache").setUnshelve(true).call();
        assertEquals("modified content", Files.readString(file.toPath()));
    }

    @Test
    public void shelveFallsBackToEmptyBaselineWhenThePathIsNotInTheLatestManifestAtAll(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File other = new File(repoDir, "other.txt");
        Files.writeString(other.toPath(), "other content");
        new AddCommand(repository).call();
        new CommitCommand(repository).setMessage("baseline").call();

        // "ghost.txt" was never part of any commit, so a real manifest walk over the latest
        // commit (which does exist, unlike the two changelog-fallback cases above) finishes
        // without ever matching it -- getBaselineContent() must fall through to its final
        // `return null` rather than short-circuiting earlier.
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("ghost.txt", new Dirstate.Entry('r', 0, 0, 0));
        repository.writeDirstate(dirstate);

        new ShelveCommand(repository).setName("path-not-in-manifest").call();

        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "path-not-in-manifest.state").exists());
        String patch = Files.readString(new File(shelvedDir, "path-not-in-manifest.patch").toPath(), StandardCharsets.UTF_8);
        assertTrue(patch.contains("ghost.txt"));
        assertTrue(patch.contains("@@ -1,0 +0,0 @@"),
                "A path absent from the latest manifest must fall back to an empty baseline, not crash");
    }

    @Test
    public void unshelveFallsBackToARegularFileWhenTheStoredSymlinkTargetCannotBeParsedAsAPath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();

        File targetFile = new File(repoDir, "MARKERTARGET");
        Files.writeString(targetFile.toPath(), "target content");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("MARKERTARGET"));
        new AddCommand(repository).addFile("link.txt").call();

        new ShelveCommand(repository).setName("bad-symlink-target").call();

        // The shelved .hg bundle stores the symlink target as raw, uncompressed bytes
        // (writeEntryChunk() writes entry.delta verbatim), so a same-length byte substitution
        // keeps every chunk-length prefix in the bundle valid while making the recovered target
        // string unparsable as a Path (POSIX rejects an embedded NUL byte). This exercises
        // performUnshelve()'s createSymbolicLink() failure fallback to a plain file, not just
        // the happy-path symlink restore already covered elsewhere.
        File hgBundleFile = new File(repository.getHgDir(), "shelved/bad-symlink-target.hg");
        byte[] bundleBytes = Files.readAllBytes(hgBundleFile.toPath());
        byte[] marker = "MARKERTARGET".getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(bundleBytes, marker);
        assertTrue(idx >= 0, "Bundle must contain the raw shelved symlink target bytes");
        bundleBytes[idx + 3] = 0;
        Files.write(hgBundleFile.toPath(), bundleBytes);

        new ShelveCommand(repository).setName("bad-symlink-target").setUnshelve(true).call();

        assertFalse(Files.isSymbolicLink(link.toPath()),
                "A target that cannot be parsed as a Path must fall back to a regular file, not crash");
        assertEquals("MAR\u0000ERTARGET", Files.readString(link.toPath(), StandardCharsets.UTF_8));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
