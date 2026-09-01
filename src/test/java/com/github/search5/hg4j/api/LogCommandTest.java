package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.treewalk.HgTreeFilter;

public class LogCommandTest {

    @Test
    public void testLogCommandFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Log of empty repo should be empty
        List<HgCommit> emptyLog = new LogCommand(repo).call();
        assertTrue(emptyLog.isEmpty());

        // 2. Commit a file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Log");
        new AddCommand(repo).call();
        new CommitCommand(repo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit")
                .call();

        // 3. Commit another file
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Hello Log 2");
        new AddCommand(repo).call();
        new CommitCommand(repo)
                .setAuthor("Bob <bob@example.com>")
                .setMessage("Second commit\nWith body")
                .call();

        // 4. Run log command
        List<HgCommit> log = new LogCommand(repo).call();
        assertEquals(2, log.size());

        // Standard hg log is usually newest first or we can support chronological.
        // Let's assume the command returns them sorted by revision (newest first, i.e., index 0 is revision 1, index 1 is revision 0)
        // because hg log shows newest commits first by default. Let's design it to return newest first.
        HgCommit c1 = log.get(0); // second commit
        HgCommit c2 = log.get(1); // first commit

        assertEquals(1, c1.getRevision());
        assertEquals("Bob <bob@example.com>", c1.getAuthor());
        assertEquals("Second commit\nWith body", c1.getMessage());
        assertTrue(c1.getFiles().contains("b.txt"));
        assertNotNull(c1.getNodeId());
        assertNotNull(c1.getManifestNodeId());
        assertTrue(c1.getTimestamp() > 0);

        assertEquals(0, c2.getRevision());
        assertEquals("Alice <alice@example.com>", c2.getAuthor());
        assertEquals("First commit", c2.getMessage());
        assertTrue(c2.getFiles().contains("a.txt"));
        assertNotNull(c2.getNodeId());
        assertNotNull(c2.getManifestNodeId());
        assertTrue(c2.getTimestamp() > 0);
    }

    @Test
    public void testLogCommandParsingEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        // 1. Malformed commit: no newline at all
        changelog.appendRevision("NoNewline".getBytes(), -1, -1, new byte[20], new byte[20], 0);

        // 2. Malformed commit: only one newline
        changelog.appendRevision("ManifestHex\nAuthor".getBytes(), -1, -1, new byte[20], new byte[20], 1);

        // 3. Malformed commit: only two newlines
        changelog.appendRevision("ManifestHex\nAuthor\nDateLine".getBytes(), -1, -1, new byte[20], new byte[20], 2);

        // 4. Commit with space in date but invalid format (NumberFormatException)
        changelog.appendRevision("0000000000000000000000000000000000000000\nAuthor\ninvalid date\n\nMessage".getBytes(), -1, -1, new byte[20], new byte[20], 3);

        // 5. Commit with no space in date but invalid format
        changelog.appendRevision("0000000000000000000000000000000000000000\nAuthor\ninvaliddate\n\nMessage".getBytes(), -1, -1, new byte[20], new byte[20], 4);

        // 6. Commit with no double newline at all
        changelog.appendRevision("0000000000000000000000000000000000000000\nAuthor\n12345678 0\nNoDoubleNLMessage".getBytes(), -1, -1, new byte[20], new byte[20], 5);

        List<HgCommit> log = new LogCommand(repo).call();
        
        // Malformed commits 1, 2, 3 are skipped in parsing because of structural formats.
        // Commits 4, 5, 6 should be successfully returned.
        assertEquals(3, log.size());

        // Revision 5: no double newline at all
        HgCommit cNoDoubleNL = log.get(0);
        assertEquals("Author", cNoDoubleNL.getAuthor());
        assertEquals("NoDoubleNLMessage", cNoDoubleNL.getMessage());

        // Revision 4: invalid date (no space)
        HgCommit cNoSpaceDate = log.get(1);
        assertEquals(0, cNoSpaceDate.getTimestamp());

        // Revision 3: invalid date (with space)
        HgCommit cSpaceDate = log.get(2);
        assertEquals(0, cSpaceDate.getTimestamp());

        // 7. Commit with NO files (date followed directly by double newline and message)
        changelog.appendRevision("0000000000000000000000000000000000000000\nAuthor\n12345678 0\n\nNoFilesMessage".getBytes(), -1, -1, new byte[20], new byte[20], 6);
        
        List<HgCommit> log2 = new LogCommand(repo).call();
        assertEquals(4, log2.size());
        
        HgCommit cNoFiles = log2.get(0); // newest first
        assertEquals(0, cNoFiles.getFiles().size(), "Should have no files");
        assertEquals("NoFilesMessage", cNoFiles.getMessage(), "Message should NOT contain leading newline");
    }

    @Test
    public void testLogCommandTreeFilter(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit 0: src/a.txt
        File srcDir = new File(repoDir, "src");
        srcDir.mkdirs();
        File fa = new File(srcDir, "a.txt");
        Files.writeString(fa.toPath(), "Hello Src");
        new AddCommand(repo).addFile("src/a.txt").call();
        new CommitCommand(repo).setMessage("Commit Src").call();

        // Commit 1: doc/b.txt
        File docDir = new File(repoDir, "doc");
        docDir.mkdirs();
        File fb = new File(docDir, "b.txt");
        Files.writeString(fb.toPath(), "Hello Doc");
        new AddCommand(repo).addFile("doc/b.txt").call();
        new CommitCommand(repo).setMessage("Commit Doc").call();

        // Without filter: should return both commits
        List<HgCommit> commitsAll = new LogCommand(repo).call();
        assertEquals(2, commitsAll.size());

        // With filter (only "src/"): should only return Commit 0
        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(List.of("src/"), List.of());
        List<HgCommit> commitsFiltered = new LogCommand(repo).setTreeFilter(filter).call();

        assertEquals(1, commitsFiltered.size());
        assertEquals("Commit Src", commitsFiltered.get(0).getMessage().trim());
        assertTrue(commitsFiltered.get(0).getFiles().contains("src/a.txt"));
    }

    @Test
    public void treeFilterExcludesCommitsWithNoMatchingFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("only a.txt").call();

        HgTreeFilter filter =
                HgTreeFilter.createPathPrefixFilter(List.of("nomatch/"), List.of());
        assertTrue(new LogCommand(repo).setTreeFilter(filter).call().isEmpty());
    }

    @Test
    public void reportsNonDefaultBranchFromExtraField(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("on feature branch").call();

        List<HgCommit> log = new LogCommand(repo).call();
        assertEquals(1, log.size());
        assertEquals("feature", log.get(0).getBranch());
    }

    @Test
    public void followAncestorsLimitsLogToAncestorsOfStartRev(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setMessage("rev0").call(); // rev 0

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v2");
        new CommitCommand(repo).setMessage("rev1").call(); // rev 1, child of rev0

        // Fork a second, unrelated child of rev0.
        new UpdateCommand(repo).setRevision("0").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "other branch");
        new AddCommand(repo).addFile("b.txt").call();
        new CommitCommand(repo).setMessage("rev2").call(); // rev 2, sibling of rev1

        List<HgCommit> restricted = new LogCommand(repo).setFollowAncestors(true).setStartRev("1").call();
        assertEquals(2, restricted.size(), "Should only include rev1 and its ancestor rev0, not the unrelated rev2");
        assertTrue(restricted.stream().anyMatch(c -> c.getRevision() == 0));
        assertTrue(restricted.stream().anyMatch(c -> c.getRevision() == 1));
        assertTrue(restricted.stream().noneMatch(c -> c.getRevision() == 2));
    }
}
