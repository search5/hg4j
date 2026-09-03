package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BundleCommand}, the local-file-writing counterpart of {@code hg bundle}.
 * Behaviors asserted here were verified against real Mercurial 7.2 CLI on scratch repos before
 * being implemented -- see {@link BundleCommand}'s class javadoc for the exact commands run and
 * their output. {@link #realHgReadsBackABundleProducedByHg4j} and {@link
 * #hg4jReadsBackABundleProducedByRealHg} are the round-trip-with-the-real-CLI tests, matching how
 * {@link CensorRealHgInteropTest} verifies cross-implementation compatibility elsewhere in this
 * codebase.
 */
public class BundleCommandTest {

    // -- Argument validation --------------------------------------------------------------------

    @Test
    public void callThrowsWhenOutputFileNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        BundleCommand cmd = new BundleCommand(repo).setBaseRevision("null");
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Output file"));
    }

    @Test
    public void callThrowsWhenBaseRevisionNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File out = tempDir.resolve("out.hg").toFile();
        BundleCommand cmd = new BundleCommand(repo).setOutputFile(out);
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("setBaseRevision"));
    }

    @Test
    public void baseRevisionNotFoundThrowsIOException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(repo, "a.txt", "v1", "first");
        File out = tempDir.resolve("out.hg").toFile();
        BundleCommand cmd = new BundleCommand(repo).setOutputFile(out).setBaseRevision("deadbeefdead");
        assertThrows(IOException.class, cmd::call);
    }

    @Test
    public void revisionNotFoundThrowsIOException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(repo, "a.txt", "v1", "first");
        File out = tempDir.resolve("out.hg").toFile();
        BundleCommand cmd = new BundleCommand(repo).setOutputFile(out).setBaseRevision("null").setRevision("deadbeefdead");
        assertThrows(IOException.class, cmd::call);
    }

    // -- Empty-selection behavior (matches real hg's "no changes found", no file written) --------

    @Test
    public void emptyRepositoryReturnsZeroAndWritesNoFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File out = tempDir.resolve("out.hg").toFile();
        int count = new BundleCommand(repo).setOutputFile(out).setBaseRevision("null").call();
        assertEquals(0, count);
        assertFalse(out.exists());
    }

    @Test
    public void baseAtTipReturnsZeroAndWritesNoFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(repo, "a.txt", "v1", "first");
        File out = tempDir.resolve("out.hg").toFile();
        int count = new BundleCommand(repo).setOutputFile(out).setBaseRevision("tip").call();
        assertEquals(0, count);
        assertFalse(out.exists());
    }

    // -- Full-repository bundling ("null" base sentinel == hg's -a/--base null) -------------------

    @Test
    public void nullBaseSentinelBundlesEveryChangeset(@TempDir Path tempDir) throws Exception {
        HgRepository srcRepo = Hg.init().setDirectory(tempDir.resolve("src").toFile()).call();
        writeAndCommit(srcRepo, "a.txt", "v1", "first");
        writeAndCommit(srcRepo, "a.txt", "v2", "second");
        writeAndCommit(srcRepo, "a.txt", "v3", "third");

        File out = tempDir.resolve("out.hg").toFile();
        int count = new BundleCommand(srcRepo).setOutputFile(out).setBaseRevision("null").call();
        assertEquals(3, count);
        assertTrue(out.exists());

        HgRepository dstRepo = Hg.init().setDirectory(tempDir.resolve("dst").toFile()).call();
        List<byte[]> imported = new UnbundleCommand(dstRepo).setBundleFile(out).call();
        assertEquals(3, imported.size());
        assertEquals(3, new LogCommand(dstRepo).call().size());
    }

    // -- Incremental base excludes the base's ancestors, includes ALL its descendants -------------

    @Test
    public void baseRevisionExcludesItsAncestorsAndIncludesAllDescendantBranches(@TempDir Path tempDir) throws Exception {
        // r0 is independently re-created (same author/content/message) in both repos below, and
        // the bundle-apply step needs its node hash to match byte-for-byte across the two -- node
        // hashing folds in the commit date, which CommitCommand defaults to wall-clock "now" when
        // unset. Under a fast/isolated run the handful of intervening steps between the two r0
        // commits (r1/r2 commits, writing the bundle) reliably finish inside the same second, so
        // this coincidentally passed; under load (e.g. the full suite) that gap can cross a
        // 1-second boundary and the two r0s silently diverge, breaking the bundle-apply's delta
        // base match with a genuine (not flaky) HgCorruptDataException hash mismatch. Pin an
        // explicit, identical date on both to make this test deterministic regardless of timing.
        long fixedDateSecs = 1756857600L; // 2026-09-03T00:00:00Z, arbitrary but fixed
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] r0 = writeAndCommit(repo, "a.txt", "v1", "r0", fixedDateSecs);
        writeAndCommit(repo, "a.txt", "v2", "r1 (linear child of r0)");

        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(r0, new byte[20]);
        repo.writeDirstate(dirstate);
        writeAndCommit(repo, "b.txt", "branch", "r2 (sibling branch off r0)");

        File out = tempDir.resolve("out.hg").toFile();
        int count = new BundleCommand(repo).setOutputFile(out).setBaseRevision("0").call();
        assertEquals(2, count, "both r1 and r2 descend from r0 and must both be included");

        // Apply against a destination that already has r0, matching what an incremental bundle is
        // for: it must contain enough context (r0 as an existing common ancestor) to reconstruct
        // both descendant branches without needing r0's own bytes in the bundle.
        HgRepository dstRepo = Hg.init().setDirectory(tempDir.resolve("dst").toFile()).call();
        writeAndCommit(dstRepo, "a.txt", "v1", "r0", fixedDateSecs);
        List<byte[]> imported = new UnbundleCommand(dstRepo).setBundleFile(out).call();
        assertEquals(2, imported.size());
        assertEquals(3, new LogCommand(dstRepo).call().size());
    }

    // -- setRevision(-r) restricts to ancestors of that revision only, excluding sibling branches --

    @Test
    public void revisionRestrictsToItsOwnAncestryExcludingSiblingBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] r0 = writeAndCommit(repo, "a.txt", "v1", "r0");
        writeAndCommit(repo, "a.txt", "v2", "r1 (sibling branch, must be excluded)");

        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(r0, new byte[20]);
        repo.writeDirstate(dirstate);
        byte[] r2 = writeAndCommit(repo, "b.txt", "branch", "r2 (child of r0, the -r target)");

        File out = tempDir.resolve("out.hg").toFile();
        int count = new BundleCommand(repo).setOutputFile(out)
                .setBaseRevision("null")
                .setRevision(io.github.search5.hg4j.util.NodeIdUtil.toHex(r2))
                .call();
        assertEquals(2, count, "only r0 and r2 are ancestors of r2; r1 must be excluded");

        HgRepository dstRepo = Hg.init().setDirectory(tempDir.resolve("dst").toFile()).call();
        List<byte[]> imported = new UnbundleCommand(dstRepo).setBundleFile(out).call();
        assertEquals(2, imported.size());
        List<HgCommit> dstLog = new LogCommand(dstRepo).call();
        assertEquals(2, dstLog.size());
        for (HgCommit c : dstLog) {
            assertFalse(c.getMessage().contains("sibling branch"), "excluded sibling branch leaked into the bundle");
        }
    }

    // -- Round trip with the real hg CLI (matching the correctness bar CensorRealHgInteropTest and
    //    RevlogV2ParserTest already hold themselves to) --------------------------------------------

    @Tag("interop")
    @Test
    public void realHgReadsBackABundleProducedByHg4j(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        HgRepository srcRepo = Hg.init().setDirectory(tempDir.resolve("src").toFile()).call();
        writeAndCommit(srcRepo, "a.txt", "v1", "first");
        writeAndCommit(srcRepo, "a.txt", "v2", "second");
        writeAndCommit(srcRepo, "a.txt", "v3", "third");

        File out = tempDir.resolve("hg4j-bundle.hg").toFile();
        int count = new BundleCommand(srcRepo).setOutputFile(out).setBaseRevision("null").call();
        assertEquals(3, count);

        File nativeDst = tempDir.resolve("native-dst").toFile();
        nativeDst.mkdirs();
        HgTestUtils.hg(nativeDst, "init");
        String log = HgTestUtils.hg(nativeDst, "unbundle", out.getAbsolutePath());
        assertTrue(log.contains("added 3 changesets"), "real hg must accept hg4j's bundle file: " + log);

        String heads = HgTestUtils.hg(nativeDst, "log", "--template", "{rev}:{desc}\\n");
        assertTrue(heads.contains("first"));
        assertTrue(heads.contains("second"));
        assertTrue(heads.contains("third"));
    }

    @Tag("interop")
    @Test
    public void hg4jReadsBackABundleProducedByRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        File nativeSrc = tempDir.resolve("native-src").toFile();
        HgRepository nativeRepo = HgTestUtils.nativeRepo(nativeSrc, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "v1\n");
                HgTestUtils.hg(dir, "add", "a.txt");
                HgTestUtils.hg(dir, "commit", "-m", "first");
                Files.writeString(new File(dir, "a.txt").toPath(), "v2\n");
                HgTestUtils.hg(dir, "commit", "-m", "second");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        File out = tempDir.resolve("native-bundle.hg").toFile();
        HgTestUtils.hg(nativeSrc, "bundle", "--all", "--type", "none-v1", out.getAbsolutePath());
        assertTrue(out.exists());

        HgRepository dstRepo = Hg.init().setDirectory(tempDir.resolve("hg4j-dst").toFile()).call();
        List<byte[]> imported = new UnbundleCommand(dstRepo).setBundleFile(out).call();
        assertEquals(2, imported.size());
        assertEquals(2, new LogCommand(dstRepo).call().size());
    }

    // -- Compressed bundle1 formats (gzip-v1 / bzip2-v1): real hg reads back what hg4j writes -----

    @Tag("interop")
    @Test
    public void realHgReadsBackAGzipV1BundleProducedByHg4j(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        HgRepository srcRepo = Hg.init().setDirectory(tempDir.resolve("src").toFile()).call();
        writeAndCommit(srcRepo, "a.txt", "v1", "first");
        writeAndCommit(srcRepo, "a.txt", "v2", "second");

        File out = tempDir.resolve("hg4j-gzip.hg").toFile();
        int count = new BundleCommand(srcRepo).setOutputFile(out).setBaseRevision("null")
                .setType(BundleCommand.BundleType.GZIP_V1).call();
        assertEquals(2, count);

        byte[] header = Files.readAllBytes(out.toPath());
        assertEquals("HG10GZ", new String(header, 0, 6, java.nio.charset.StandardCharsets.US_ASCII),
                "gzip-v1 bundle must start with the HG10GZ container header");

        File nativeDst = tempDir.resolve("native-dst").toFile();
        nativeDst.mkdirs();
        HgTestUtils.hg(nativeDst, "init");
        String log = HgTestUtils.hg(nativeDst, "unbundle", out.getAbsolutePath());
        assertTrue(log.contains("added 2 changesets"), "real hg must accept hg4j's gzip-v1 bundle file: " + log);
    }

    @Tag("interop")
    @Test
    public void realHgReadsBackABzip2V1BundleProducedByHg4j(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        HgRepository srcRepo = Hg.init().setDirectory(tempDir.resolve("src").toFile()).call();
        writeAndCommit(srcRepo, "a.txt", "v1", "first");
        writeAndCommit(srcRepo, "a.txt", "v2", "second");

        File out = tempDir.resolve("hg4j-bzip2.hg").toFile();
        int count = new BundleCommand(srcRepo).setOutputFile(out).setBaseRevision("null")
                .setType("bzip2-v1").call();
        assertEquals(2, count);

        byte[] header = Files.readAllBytes(out.toPath());
        assertEquals("HG10BZ", new String(header, 0, 6, java.nio.charset.StandardCharsets.US_ASCII),
                "bzip2-v1 bundle must start with the HG10BZ container header");

        File nativeDst = tempDir.resolve("native-dst").toFile();
        nativeDst.mkdirs();
        HgTestUtils.hg(nativeDst, "init");
        String log = HgTestUtils.hg(nativeDst, "unbundle", out.getAbsolutePath());
        assertTrue(log.contains("added 2 changesets"), "real hg must accept hg4j's bzip2-v1 bundle file: " + log);
    }

    // -- hg4j reads back a real-hg-produced gzip-v1 bundle (round trip in the other direction) ----

    @Tag("interop")
    @Test
    public void hg4jReadsBackAGzipV1BundleProducedByRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        File nativeSrc = tempDir.resolve("native-src").toFile();
        HgTestUtils.nativeRepo(nativeSrc, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "v1\n");
                HgTestUtils.hg(dir, "add", "a.txt");
                HgTestUtils.hg(dir, "commit", "-m", "first");
                Files.writeString(new File(dir, "a.txt").toPath(), "v2\n");
                HgTestUtils.hg(dir, "commit", "-m", "second");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        File out = tempDir.resolve("native-gzip.hg").toFile();
        HgTestUtils.hg(nativeSrc, "bundle", "--all", "--type", "gzip-v1", out.getAbsolutePath());
        assertTrue(out.exists());

        HgRepository dstRepo = Hg.init().setDirectory(tempDir.resolve("hg4j-dst").toFile()).call();
        List<byte[]> imported = new UnbundleCommand(dstRepo).setBundleFile(out).call();
        assertEquals(2, imported.size());
        assertEquals(2, new LogCommand(dstRepo).call().size());
    }

    private static byte[] writeAndCommit(HgRepository repo, String fileName, String content, String message)
            throws IOException, HgLockException {
        File f = new File(repo.getDirectory(), fileName);
        boolean isNew = !f.exists();
        Files.writeString(f.toPath(), content);
        if (isNew) {
            new AddCommand(repo).call();
        }
        return new CommitCommand(repo).setMessage(message).setAuthor("tester").call();
    }

    /** Like {@link #writeAndCommit(HgRepository, String, String, String)} but with an explicit,
     * fixed commit date -- use whenever two independently-created commits (in different repos)
     * must hash identically, since node hashing folds in the date and CommitCommand otherwise
     * defaults to wall-clock "now". */
    private static byte[] writeAndCommit(HgRepository repo, String fileName, String content, String message, long dateSecs)
            throws IOException, HgLockException {
        File f = new File(repo.getDirectory(), fileName);
        boolean isNew = !f.exists();
        Files.writeString(f.toPath(), content);
        if (isNew) {
            new AddCommand(repo).call();
        }
        return new CommitCommand(repo).setMessage(message).setAuthor("tester").setDate(dateSecs, 0).call();
    }
}
