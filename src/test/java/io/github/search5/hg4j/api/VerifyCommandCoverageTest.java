package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link VerifyCommand}, focused on branches not already exercised by
 * VerifyCommandTest: the empty-repository fast path, missing-manifest detection, manifest
 * integrity mismatches, merge-commit (parent2 != -1) traversal, fncache line filtering /
 * dangling-entry detection, delta-chain-cycle read failures, and the "failed to open
 * filelog" / "critical repository read failure" outer-catch paths.
 *
 * <p>All real-hg-verified claims below were checked against real {@code hg verify} (v7.2) on a
 * scratch repository under /tmp: a freshly-{@code hg init}'d repository with zero commits
 * verifies clean (exit 0, "checked 0 changesets"), while deleting {@code 00manifest.i} from a
 * repository that already has changelog revisions makes real hg report "0: empty or missing
 * manifest" and exit 1.
 */
public class VerifyCommandCoverageTest {

    private static void writeBigEndianInt(File file, long offset, int value) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(offset);
            raf.write((value >>> 24) & 0xFF);
            raf.write((value >>> 16) & 0xFF);
            raf.write((value >>> 8) & 0xFF);
            raf.write(value & 0xFF);
        }
    }

    /**
     * Corrupts a non-inline revlog with (at least) 2 revisions so that revision 0's baseRev
     * points to revision 1 and revision 1's baseRev points back to revision 0 -- a 2-cycle that
     * neither self-terminates as a fulltext record nor can ever resolve, forcing
     * {@code Revlog#getRawRevisionContent} to detect the cycle and throw {@code
     * HgCorruptDataException} (mirrors RevlogCoverageTest's
     * testDeltaChainCycleIsDetectedAndReportedAsCorruption, but applied to a real committed
     * revlog rather than a synthetic one). The baseRev field sits at byte offset 16 within each
     * 64-byte index record (offset_flags:8, compLen:4, uncompLen:4, baseRev:4, ...).
     */
    private static void corruptIntoDeltaCycle(File idxFile) throws Exception {
        writeBigEndianInt(idxFile, 0L * 64 + 16, 1);
        writeBigEndianInt(idxFile, 1L * 64 + 16, 0);
    }

    // ---------------------------------------------------------------------
    // Constructor null-guard
    // ---------------------------------------------------------------------

    @Test
    public void testConstructorRejectsNullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new VerifyCommand(null));
    }

    // ---------------------------------------------------------------------
    // Empty repository fast path (no changelog/manifest ever created)
    // ---------------------------------------------------------------------

    @Test
    public void testFreshlyInitializedRepositoryWithNoCommitsHasNoVerifyErrors(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // A brand-new repository has no 00changelog.i / 00manifest.i / fncache at all until the
        // first commit -- confirmed identical against real hg: `hg init` alone produces only
        // .hg/store/requires, and `hg verify` on it reports "checked 0 changesets" with exit 0.
        assertFalse(new File(repo.getStoreDir(), "00changelog.i").exists());
        assertFalse(new File(repo.getStoreDir(), "00manifest.i").exists());

        List<String> errors = new VerifyCommand(repo).call();
        assertTrue(errors.isEmpty(), "A never-committed repository must verify clean, but got: " + errors);
    }

    // ---------------------------------------------------------------------
    // Missing manifest while changelog has revisions -- real bug found & fixed
    // ---------------------------------------------------------------------

    @Test
    public void testMissingManifestWithChangelogRevisionsIsReportedAsError(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        assertTrue(mfIdx.exists());
        Files.delete(mfIdx.toPath());
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Files.deleteIfExists(mfDat.toPath());

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertFalse(errors.isEmpty(), "A missing manifest with existing changelog history must be reported");
            assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("manifest")),
                    "Error must mention the missing manifest: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Manifest integrity mismatch (mirrors VerifyCommandTest's changelog case)
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyCorruptManifestDetectsErrors(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        assertTrue(mfIdx.exists());
        // Corrupt the stored node id (offset 32), exactly as VerifyCommandTest does for the
        // changelog case.
        try (RandomAccessFile raf = new RandomAccessFile(mfIdx, "rw")) {
            raf.seek(32);
            raf.write(new byte[]{9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0});
        }

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertFalse(errors.isEmpty(), "Verify must detect corrupt manifest data");
            assertTrue(errors.stream().anyMatch(e -> e.contains("manifest integrity mismatch")),
                    "Error must specify manifest integrity mismatch, got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Merge commit -- exercises the parent2 != -1 branch in changelog/manifest traversal
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyHealthyMergeCommitCoversParent2Branch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours change").call();

        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        repo.writeDirstate(dirstate);

        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        new CommitCommand(repo).setMessage("Theirs change").call();

        new MergeCommand(repo).setNodeId(yoursNode).call();
        byte[] mergeCommitNode = new CommitCommand(repo)
                .setAuthor("Merger <merger@example.com>")
                .setMessage("Merged branch Yours")
                .call();
        assertNotNull(mergeCommitNode);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = new Revlog(clIdx, clDat);
        Revlog.IndexRecord mergeRec = changelog.getIndexRecord(3);
        assertNotEquals(-1, mergeRec.getParent1());
        assertNotEquals(-1, mergeRec.getParent2(), "Merge commit's changelog record must have a real second parent");

        List<String> errors = new VerifyCommand(repo).call();
        assertTrue(errors.isEmpty(), "A healthy merge commit must still verify clean, but got: " + errors);
    }

    // ---------------------------------------------------------------------
    // fncache line filtering: blank lines and non-".i" entries must be skipped, not treated as
    // dangling filelog references.
    // ---------------------------------------------------------------------

    @Test
    public void testFncacheSkipsBlankAndNonIndexLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        List<String> original = Files.readAllLines(fncacheFile.toPath());
        assertTrue(original.contains("data/a.txt.i"));

        List<String> withNoise = new ArrayList<>(original);
        withNoise.add("");
        withNoise.add("   ");
        withNoise.add("data/a.txt.d"); // real data file, but not a ".i" entry -- must be skipped
        Files.write(fncacheFile.toPath(), withNoise);

        try (HgRepository reopened = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(reopened).call();
            assertTrue(errors.isEmpty(),
                    "Blank lines and non-\".i\" fncache entries must be silently skipped, but got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // fncache entry pointing at a filelog index that does not exist on disk
    // ---------------------------------------------------------------------

    @Test
    public void testFncacheEntryPointingToMissingFilelogIsReported(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        List<String> lines = new ArrayList<>(Files.readAllLines(fncacheFile.toPath()));
        lines.add("data/ghost.txt.i");
        Files.write(fncacheFile.toPath(), lines);

        try (HgRepository reopened = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(reopened).call();
            assertTrue(errors.stream().anyMatch(e -> e.contains("filelog index not found for fncache entry: data/ghost.txt.i")),
                    "A dangling fncache entry must be reported, got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Filelog delta-chain cycle -> per-revision "failed to read revision" catch
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyReportsFilelogDeltaChainCycleAsReadFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "v1\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        Files.writeString(f.toPath(), "v1\nv2\n");
        hg.commit().setMessage("Second").call();
        repo.close();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        corruptIntoDeltaCycle(flIdx);

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertTrue(errors.stream().anyMatch(e -> e.contains("a.txt") && e.contains("failed to read revision")),
                    "A delta-chain cycle in a filelog must surface as a read failure, got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Changelog / manifest delta-chain cycle -> per-revision "failed to read ... revision" catch
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyReportsChangelogAndManifestDeltaChainCycleAsReadFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "v1\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        Files.writeString(f.toPath(), "v1\nv2\n");
        hg.commit().setMessage("Second").call();
        repo.close();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        corruptIntoDeltaCycle(clIdx);
        corruptIntoDeltaCycle(mfIdx);

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertTrue(errors.stream().anyMatch(e -> e.contains("failed to read changelog revision")),
                    "A delta-chain cycle in the changelog must surface as a read failure, got: " + errors);
            assertTrue(errors.stream().anyMatch(e -> e.contains("failed to read manifest revision")),
                    "A delta-chain cycle in the manifest must surface as a read failure, got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Filelog index file exists but its header is unreadable -> "failed to open filelog"
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyReportsFailedToOpenFilelogForTruncatedIndex(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        // Truncate below the minimum 64-byte record size so RevlogIndex's constructor-time
        // loadIndex() throws "Invalid revlog index: too short" the moment VerifyCommand asks
        // the repository to open this filelog.
        try (var channel = Files.newByteChannel(flIdx.toPath(), StandardOpenOption.WRITE)) {
            channel.truncate(10);
        }

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("failed to open filelog data/a.txt.i")),
                    "A filelog whose index cannot even be opened must be reported distinctly, got: " + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Changelog index file exists but is unreadable at the top level -> outer
    // "critical repository read failure" catch (repository.getRevlog() itself throws, outside
    // any per-revision try block).
    // ---------------------------------------------------------------------

    @Test
    public void testVerifyReportsCriticalRepositoryReadFailureForTruncatedChangelog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        assertTrue(clIdx.exists());
        try (var channel = Files.newByteChannel(clIdx.toPath(), StandardOpenOption.WRITE)) {
            channel.truncate(10);
        }

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertEquals(1, errors.size(), "A changelog that fails to open at all must abort with a single top-level error: " + errors);
            assertTrue(errors.get(0).startsWith("critical repository read failure"), "Got: " + errors.get(0));
        }
    }
}
