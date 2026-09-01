package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.treewalk.HgTreeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for {@link DiffCommand}, closing gaps left by
 * {@code TreeAndDiffCommandTest}: null tree filter handling, IOException
 * fallbacks in the NodeId-based setters, the zero-revision changelog guard,
 * the (currently unreachable from call()) loadManifest helper, and the
 * getFileContent defensive branches (missing filelog / node not found),
 * plus additional unified-diff shapes to exercise the LCS backtracking
 * branches in generateUnifiedDiff.
 */
public class DiffCommandCoverageTest {

    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = DiffCommand.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    private static int getIntField(Object target, String name) throws Exception {
        Field f = DiffCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    // ---- setTreeFilter(null) keeps the existing filter (defensive branch) ----

    @Test
    public void testSetTreeFilterNullKeepsDefault(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        Files.writeString(fa.toPath(), "Line 1 Modified\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        DiffCommand cmd = new DiffCommand(repo).setOldRevision(0).setNewRevision(1);
        // Passing null must not clear/replace the default ALL filter.
        DiffCommand returned = cmd.setTreeFilter(null);
        assertSame(cmd, returned);

        List<DiffCommand.DiffEntry> diffs = cmd.call();
        assertEquals(1, diffs.size());
        assertEquals("a.txt", diffs.get(0).getPath());
    }

    // ---- setOldRevision(NodeId) / setNewRevision(NodeId) IOException fallback ----

    @Test
    public void testSetOldRevisionNodeIdFallsBackOnIOException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Corrupt the store so that opening 00changelog.i as a revlog throws IOException:
        // make it a directory instead of a regular file.
        File storeDir = repo.getStoreDir();
        assertTrue(storeDir.exists());
        File clIdx = new File(storeDir, "00changelog.i");
        assertTrue(clIdx.mkdirs());

        NodeId dummyNode = new NodeId(new byte[20]);
        DiffCommand cmd = new DiffCommand(repo).setOldRevision(dummyNode);

        assertEquals(-1, getIntField(cmd, "oldRevision"));
    }

    @Test
    public void testSetNewRevisionNodeIdFallsBackOnIOException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File storeDir = repo.getStoreDir();
        File clIdx = new File(storeDir, "00changelog.i");
        assertTrue(clIdx.mkdirs());

        NodeId dummyNode = new NodeId(new byte[20]);
        DiffCommand cmd = new DiffCommand(repo).setNewRevision(dummyNode);

        assertEquals(-1, getIntField(cmd, "newRevision"));
    }

    // ---- call(): changelog file exists but has zero revisions ----

    @Test
    public void testCallReturnsEmptyWhenChangelogHasZeroRevisions(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Simulate a repository whose changelog revlog files exist (e.g. pre-created by
        // some tooling) but contain no revisions yet, distinct from the "no repo at all"
        // case where 00changelog.i is entirely absent.
        File storeDir = repo.getStoreDir();
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        assertTrue(clIdx.createNewFile());
        assertTrue(clDat.createNewFile());

        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo).call();
        assertTrue(diffs.isEmpty());
    }

    // ---- call(): default newRevision (-1) resolves to tip on a non-empty repo ----

    @Test
    public void testCallDefaultsNewRevisionToTip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        Files.writeString(fa.toPath(), "Line 1 Modified\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        // Neither old nor new revision explicitly set: new defaults to tip (rev 1),
        // old defaults to tip's parent (rev 0).
        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo).call();
        assertEquals(1, diffs.size());
        assertEquals("a.txt", diffs.get(0).getPath());
        assertEquals(DiffCommand.ChangeType.MODIFY, diffs.get(0).getChangeType());
    }

    // ---- loadManifest(Revlog, int): not reachable from call(), exercised via reflection ----

    @Test
    public void testLoadManifestNegativeRevisionReturnsEmptyMap(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        var changelog = repo.getRevlog(clIdx, clDat);

        DiffCommand cmd = new DiffCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "loadManifest",
                new Class<?>[]{com.github.search5.hg4j.storage.Revlog.class, int.class},
                changelog, -1);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testLoadManifestRevisionBeyondCountReturnsEmptyMap(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        var changelog = repo.getRevlog(clIdx, clDat);

        DiffCommand cmd = new DiffCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "loadManifest",
                new Class<?>[]{com.github.search5.hg4j.storage.Revlog.class, int.class},
                changelog, 99);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testLoadManifestValidRevisionReturnsManifest(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        var changelog = repo.getRevlog(clIdx, clDat);

        DiffCommand cmd = new DiffCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "loadManifest",
                new Class<?>[]{com.github.search5.hg4j.storage.Revlog.class, int.class},
                changelog, 0);
        assertTrue(result.containsKey("a.txt"));
    }

    // ---- getFileContent(String, String): defensive branches via reflection ----

    @Test
    public void testGetFileContentReturnsEmptyWhenFilelogMissing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        DiffCommand cmd = new DiffCommand(repo);
        byte[] result = (byte[]) invokePrivate(cmd, "getFileContent",
                new Class<?>[]{String.class, String.class},
                "does/not/exist.txt", "0".repeat(40));
        assertEquals(0, result.length);
    }

    @Test
    public void testGetFileContentReturnsEmptyWhenNodeNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        DiffCommand cmd = new DiffCommand(repo);
        // a.txt's filelog exists, but this node id was never stored in it.
        byte[] result = (byte[]) invokePrivate(cmd, "getFileContent",
                new Class<?>[]{String.class, String.class},
                "a.txt", "f".repeat(40));
        assertEquals(0, result.length);
    }

    // ---- generateUnifiedDiff: additional shapes to exercise LCS backtracking branches ----

    @Test
    public void testDiffBothFilesEmptyProducesNoHunkLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "empty.txt");
        Files.writeString(fa.toPath(), "");
        new AddCommand(repo).addFile("empty.txt").call();
        byte[] rev0 = new CommitCommand(repo).setMessage("Rev 0 add empty").call();

        // Force a MODIFY entry even though both old and new content decode to "": flip
        // the file to non-empty and back so the filelog gets a second (empty) revision
        // with a different node id.
        Files.writeString(fa.toPath(), "temp\n");
        new CommitCommand(repo).setMessage("Rev 1 temp content").call();
        Files.writeString(fa.toPath(), "");
        byte[] rev2 = new CommitCommand(repo).setMessage("Rev 2 back to empty").call();

        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo)
                .setOldRevision(new NodeId(rev0))
                .setNewRevision(new NodeId(rev2))
                .call();

        DiffCommand.DiffEntry entry = diffs.stream()
                .filter(d -> d.getPath().equals("empty.txt"))
                .findFirst().orElse(null);
        assertNotNull(entry);
        assertEquals(DiffCommand.ChangeType.MODIFY, entry.getChangeType());
        assertTrue(entry.getDiffContent().contains("@@ -1,0 +1,0 @@"));
    }

    @Test
    public void testDiffWithDeletionsInMiddleAndTrailingAddition(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "multi.txt");
        Files.writeString(fa.toPath(), "one\ntwo\nthree\nfour\nfive\n");
        new AddCommand(repo).addFile("multi.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        // Remove "two" and "three" from the middle, keep "one", "four", "five", and
        // append a brand-new trailing line. This forces the backward LCS scan through
        // a run of deletions immediately followed by more common lines (not just at the
        // very start/end), exercising the dp-comparison branches on both sides of ties.
        Files.writeString(fa.toPath(), "one\nfour\nfive\nsix\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo).setOldRevision(0).setNewRevision(1).call();
        DiffCommand.DiffEntry entry = diffs.stream()
                .filter(d -> d.getPath().equals("multi.txt"))
                .findFirst().orElse(null);
        assertNotNull(entry);
        String content = entry.getDiffContent();
        assertTrue(content.contains("-two"));
        assertTrue(content.contains("-three"));
        assertTrue(content.contains("+six"));
        assertTrue(content.contains(" one"));
        assertTrue(content.contains(" four"));
        assertTrue(content.contains(" five"));
    }

    @Test
    public void testDiffWithLeadingAdditionsThenCommonSuffix(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "prefix.txt");
        Files.writeString(fa.toPath(), "alpha\nbeta\n");
        new AddCommand(repo).addFile("prefix.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        // Prepend two brand-new lines before the existing common content, exercising
        // the pure-insertion backtracking branch while i is still > 0 (old lines
        // remain to be matched further back), rather than i == 0 throughout.
        Files.writeString(fa.toPath(), "zero\nnegativeone\nalpha\nbeta\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo).setOldRevision(0).setNewRevision(1).call();
        DiffCommand.DiffEntry entry = diffs.stream()
                .filter(d -> d.getPath().equals("prefix.txt"))
                .findFirst().orElse(null);
        assertNotNull(entry);
        String content = entry.getDiffContent();
        assertTrue(content.contains("+zero"));
        assertTrue(content.contains("+negativeone"));
        assertTrue(content.contains(" alpha"));
        assertTrue(content.contains(" beta"));
    }

    @Test
    public void testDiffCommandTreeFilterEmptyFilterExcludesAll(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("Rev 0").call();

        Files.writeString(fa.toPath(), "Line 1 Modified\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        HgTreeFilter noneFilter = HgTreeFilter.createPathPrefixFilter(List.of("nomatch/"), List.of());

        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo)
                .setOldRevision(0)
                .setNewRevision(1)
                .setTreeFilter(noneFilter)
                .call();
        assertTrue(diffs.isEmpty());
    }
}
