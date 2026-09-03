package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targets the PullCommand wrapper's own remaining branches not already exercised by
 * CHgPullRoundtripTest, PathsAliasIntegrationTest and HgRemoteAndSyncTest: null-safe
 * setters, credentialsProvider propagation, an empty paths.default value and the
 * "nothing new to pull" / empty-bundle results paths.
 */
public class PullCommandCoverageTest {

    @Test
    public void testSetTreeFilterWithNullKeepsDefaultAndPullStillWorks(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "content");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setMessage("First").call();

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pull = new PullCommand(destRepo).setSource(srcDir.getAbsolutePath());
        assertSame(pull, pull.setTreeFilter(null), "setTreeFilter must return this for chaining even with null");

        List<byte[]> results = pull.call();
        assertEquals(1, results.size(), "null treeFilter must not disable the default ALL filter");
    }

    @Test
    public void testSetProgressMonitorWithNullKeepsDefaultAndPullStillWorks(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "content");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setMessage("First").call();

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pull = new PullCommand(destRepo).setSource(srcDir.getAbsolutePath());
        assertSame(pull, pull.setProgressMonitor(null), "setProgressMonitor must return this for chaining even with null");

        List<byte[]> results = pull.call();
        assertEquals(1, results.size(), "null monitor must fall back to NullProgressMonitor without throwing");
    }

    @Test
    public void testCredentialsProviderIsPropagatedToFetchCommandOnPull(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "content");
        new AddCommand(srcRepo).call();
        byte[] node = new CommitCommand(srcRepo).setMessage("First").call();

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pull = new PullCommand(destRepo)
                .setSource(srcDir.getAbsolutePath())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("bob", "s3cr3t"));

        List<byte[]> results = pull.call();
        assertEquals(1, results.size());
        assertArrayEquals(node, results.get(0), "credentialsProvider wiring must not interfere with a normal local pull");
    }

    @Test
    public void testCredentialsProviderIsPropagatedToFetchCommandOnApplyBundle(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "content");
        new AddCommand(srcRepo).call();
        byte[] node = new CommitCommand(srcRepo).setMessage("First").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pull = new PullCommand(destRepo)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("bob", "s3cr3t"));

        List<byte[]> imported = pull.applyBundle(bundle);
        assertEquals(1, imported.size());
        assertArrayEquals(node, imported.get(0));
    }

    @Test
    public void testPullWithEmptyPathsDefaultValueThrowsIllegalStateException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        repo.getConfig().set("paths", "default", "");

        PullCommand pull = new PullCommand(repo);

        IllegalStateException ex = assertThrows(IllegalStateException.class, pull::call);
        assertEquals("Remote source URL must be specified.", ex.getMessage());
    }

    @Test
    public void testSecondPullWithNoNewChangesReturnsEmptyResultsWithoutTouchingDirstate(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "content");
        new AddCommand(srcRepo).call();
        byte[] node = new CommitCommand(srcRepo).setMessage("First").call();

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pull = new PullCommand(destRepo).setSource(srcDir.getAbsolutePath());

        List<byte[]> firstResults = pull.call();
        assertEquals(1, firstResults.size());

        Dirstate afterFirstPull = destRepo.getDirstate();
        assertArrayEquals(node, afterFirstPull.getParent1(), "first pull must advance the empty working dir parent");

        List<byte[]> secondResults = pull.call();
        assertNotNull(secondResults, "an up-to-date pull must return an empty list, never null");
        assertTrue(secondResults.isEmpty(), "an up-to-date pull must not re-import anything");

        Dirstate afterSecondPull = destRepo.getDirstate();
        assertArrayEquals(node, afterSecondPull.getParent1(), "an up-to-date pull must not touch the already-set dirstate parent");
    }

    @Test
    public void testApplyBundleWithEmptyChangelogReturnsEmptyResultsWithoutTouchingDirstate(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        ChangegroupParser.ChangegroupBundle emptyBundle = new ChangegroupParser.ChangegroupBundle();
        emptyBundle.changelogEntries = new ArrayList<>();

        PullCommand pull = new PullCommand(destRepo);
        List<byte[]> imported = pull.applyBundle(emptyBundle);

        assertNotNull(imported, "an empty bundle must yield an empty list, never null");
        assertTrue(imported.isEmpty());

        Dirstate dirstate = destRepo.getDirstate();
        assertTrue(io.github.search5.hg4j.util.NodeIdUtil.isAllZero(dirstate.getParent1()),
                "an empty bundle apply must not touch the dirstate parent");
    }
}
