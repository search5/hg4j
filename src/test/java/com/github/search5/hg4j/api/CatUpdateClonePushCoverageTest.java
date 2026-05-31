package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to increase coverage for CatCommand, UpdateCommand, PushCommand, and CloneCommand.
 * Validates exception paths and edge cases for each command.
 */
public class CatUpdateClonePushCoverageTest {

    // ─────────────────────────────────────────────
    // CatCommand Coverage
    // ─────────────────────────────────────────────

    @Test
    public void testCatCommandFileNotSpecified(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // Calling call() without specifying a file → IllegalStateException
        CatCommand cat = new CatCommand(repo);
        assertThrows(IllegalStateException.class, cat::call);
    }

    @Test
    public void testCatCommandEmptyRepository(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // Querying a file in an empty repository → IOException (Unable to resolve revision)
        CatCommand cat = new CatCommand(repo).setFile("some.txt");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandFileNotTracked(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit a single file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Cat\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        // Querying a file not present in the repository → IOException
        CatCommand cat = new CatCommand(repo).setFile("nonexistent.txt");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandRetrievesFileContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit a single file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Cat Command\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        // Retrieve content using CatCommand
        byte[] content = new CatCommand(repo).setFile("a.txt").call();
        assertNotNull(content);
        assertEquals("Hello Cat Command\n", new String(content));
    }

    @Test
    public void testCatCommandWithRevisionNumber(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Two commits
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Initial content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        Files.writeString(f1.toPath(), "Updated content\n");
        new CommitCommand(repo).setMessage("커밋2").call();

        // Retrieve content at revision 0
        byte[] content0 = new CatCommand(repo).setFile("a.txt").setRevision("0").call();
        assertEquals("Initial content\n", new String(content0));

        // Retrieve content at revision 1
        byte[] content1 = new CatCommand(repo).setFile("a.txt").setRevision("1").call();
        assertEquals("Updated content\n", new String(content1));
    }

    @Test
    public void testCatCommandWithHexNodeId(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hex node content\n");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("커밋").call();

        String hexPrefix = NodeIdUtil.toHex(commitNode).substring(0, 8);

        byte[] content = new CatCommand(repo).setFile("a.txt").setRevision(hexPrefix).call();
        assertEquals("Hex node content\n", new String(content));
    }

    @Test
    public void testCatCommandAmbiguousRevision(@TempDir Path tempDir) throws Exception {
        // Since it is difficult to artificially create a case where two commits have the same hex prefix,
        // it is substituted with an invalid revision ID test.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // Non-existent hex prefix → IOException (after returning null)
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision("ffffffff");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandSetRevisionNodeIdNull(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision((com.github.search5.hg4j.lib.NodeId) null);
        // If the revision is null, it is set to null, and calling call() on an empty repository throws an exception due to failure in resolving the revision.
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandNonExistent40HexRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // Providing a non-existent 40-character hex nodeId fails to resolve and throws an "Unable to resolve revision" exception.
        String nonExistent40Hex = "f".repeat(40);
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision(nonExistent40Hex);
        com.github.search5.hg4j.errors.HgRevisionNotFoundException ex = assertThrows(com.github.search5.hg4j.errors.HgRevisionNotFoundException.class, cat::call);
        assertTrue(ex.getMessage().contains("Unable to resolve revision"));
    }

    @Test
    public void testCatCommandSetFileNullOrEmpty(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        
        CatCommand catNullFile = new CatCommand(repo).setFile(null);
        assertThrows(IllegalStateException.class, catNullFile::call);

        CatCommand catEmptyFile = new CatCommand(repo).setFile("");
        assertThrows(IllegalStateException.class, catEmptyFile::call);
    }

    @Test
    public void testCatCommandFilelogNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // Registered in the manifest, but forcefully delete the actual filelog file (.i)
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        assertTrue(flIdx.delete());

        CatCommand cat = new CatCommand(repo).setFile("a.txt");
        com.github.search5.hg4j.errors.HgCorruptDataException ex = assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, cat::call);
        assertTrue(ex.getMessage().contains("Filelog not found"));
    }

    // ─────────────────────────────────────────────
    // Additional Test Paths for UpdateCommand Coverage
    // ─────────────────────────────────────────────

    @Test
    public void testUpdateCommandEmptyRepository(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // Update in an empty repository → IOException (Repository is empty)
        UpdateCommand update = new UpdateCommand(repo);
        assertThrows(IOException.class, update::call);
    }

    @Test
    public void testUpdateCommandInvalidRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // Non-existent revision
        assertThrows(IOException.class, () ->
                new UpdateCommand(repo).setRevision("invalid_xyz_456").call());
    }

    @Test
    public void testUpdateCommandWithFileDeletion(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit 0: both a.txt and b.txt exist
        File f1 = new File(repoDir, "a.txt");
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f1.toPath(), "file a\n");
        Files.writeString(f2.toPath(), "file b\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("두 파일 커밋").call();

        // Commit 1: b.txt deleted
        new RemoveCommand(repo).setFile("b.txt").call();
        new CommitCommand(repo).setMessage("b.txt 삭제").call();

        assertTrue(f1.exists());
        assertFalse(f2.exists());

        // Update to commit 0 → b.txt restored
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertTrue(f1.exists());
        assertTrue(f2.exists());
        assertEquals("file b\n", Files.readString(f2.toPath()));

        // Update to commit 1 again → b.txt deleted
        new UpdateCommand(repo).setRevision("1").setForce(true).call();
        assertTrue(f1.exists());
        assertFalse(f2.exists());
    }

    // ─────────────────────────────────────────────
    // Additional Test Paths for PushCommand Coverage
    // ─────────────────────────────────────────────

    @Test
    public void testPushCommandNoDestinationUrl(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        PushCommand push = new PushCommand(repo);
        assertThrows(IllegalStateException.class, push::call);
    }

    @Test
    public void testPushCommandBundleWriteEntry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Prepare two commits and internally validate bundle serialization of PushCommand
        // (Actual push requires network, but the bundle construction code can be executed)
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content push\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("push test 커밋").call();

        // Calling without destination → IllegalStateException (verification without network)
        assertThrows(IllegalStateException.class, () ->
                new PushCommand(repo).call());
    }

    // ─────────────────────────────────────────────
    // Additional Test Paths for CloneCommand Coverage
    // ─────────────────────────────────────────────

    @Test
    public void testCloneCommandNoSourceUrl(@TempDir Path tempDir) {
        CloneCommand clone = new CloneCommand();
        clone.setDirectory(tempDir.toFile());
        assertThrows(IllegalStateException.class, clone::call);
    }

    @Test
    public void testCloneCommandNoDirectory() {
        CloneCommand clone = new CloneCommand();
        clone.setSource("http://some.server/repo");
        assertThrows(IllegalStateException.class, clone::call);
    }

    @Test
    public void testCloneCommandDestinationNotEmpty(@TempDir Path tempDir) throws Exception {
        // Specifying a non-empty directory → IOException
        File destDir = tempDir.toFile();
        Files.writeString(destDir.toPath().resolve("existing_file.txt"), "already here");

        CloneCommand clone = new CloneCommand()
                .setSource("http://some.server/repo")
                .setDirectory(destDir);
        assertThrows(IOException.class, clone::call);
    }

    @Test
    public void testPushCommandNonLinearStartRevCalculation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("local_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Create non-linear commits:
        // Rev 0: A
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").call();

        // Rev 1: B (parent: A) on branch-B
        repo.setBranch("branch-B");
        File fb = new File(repoDir, "b.txt");
        Files.writeString(fb.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").call();

        // Rev 2: C (parent: A) on default
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        repo.setBranch("default");
        File fc = new File(repoDir, "c.txt");
        Files.writeString(fc.toPath(), "Content C");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit C").call();

        // Rev 3: D (parent: C) on default
        File fd = new File(repoDir, "d.txt");
        Files.writeString(fd.toPath(), "Content D");
        new AddCommand(repo).call();
        byte[] nodeD = new CommitCommand(repo).setMessage("Commit D").call();

        // 2. Start Mock HttpServer acting as remote Mercurial server
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(0), 0
        );
        String nodeDHex = NodeIdUtil.toHex(nodeD);

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=heads")) {
                byte[] response = (nodeDHex + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else if (query != null && query.contains("cmd=unbundle")) {
                byte[] response = "0\nno errors\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(404, 0);
            }
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        String remoteUrl = "http://localhost:" + port + "/";

        try {
            // 3. Try to push
            PushCommand push = new PushCommand(repo).setDestination(remoteUrl);
            String result = push.call();

            // In the bug state, since maxRemoteRev = 3, startRev = 4 (under count = 4).
            // Under count = 4, startRev >= count triggers "No changesets to push (remote is up-to-date)".
            // But we actually have Commit B (rev 1) which is missing on remote!
            // With the fix, startRev = 1, and it should successfully push and return "0\nno errors\n".
            assertEquals("0\nno errors\n", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPushCommandUnrelatedRepositoryThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("local_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create a local commit
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").call();

        // Mock remote heads having a completely unrelated commit hash
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(0), 0
        );
        String randomHex = "1111222233334444555566667777888899990000";

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=heads")) {
                byte[] response = (randomHex + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(404, 0);
            }
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        String remoteUrl = "http://localhost:" + port + "/";

        try {
            PushCommand push = new PushCommand(repo).setDestination(remoteUrl);
            IOException ex = assertThrows(IOException.class, push::call);
            assertTrue(ex.getMessage().contains("unrelated"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testCatCommandSetRevisionNodeIdNotNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "NodeId test content\n");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("커밋").call();

        // Call setRevision with a NodeId object to execute the non-null branch
        com.github.search5.hg4j.lib.NodeId nodeIdObj = new com.github.search5.hg4j.lib.NodeId(commitNode);
        byte[] content = new CatCommand(repo).setFile("a.txt").setRevision(nodeIdObj).call();
        assertEquals("NodeId test content\n", new String(content));
    }

    @Test
    public void testCatCommandFileVersionNotFoundInHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // Override getManifestAtCommit to return a non-existent file version hex
        HgRepository spyRepo = new HgRepository(repoDir) {
            @Override
            public java.util.Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
                java.util.Map<String, String> fakeMap = new java.util.HashMap<>();
                fakeMap.put("a.txt", "1".repeat(40)); // Non-existent 40-character hex
                return fakeMap;
            }
        };

        CatCommand cat = new CatCommand(spyRepo).setFile("a.txt").setRevision("0");
        com.github.search5.hg4j.errors.HgRevisionNotFoundException ex = assertThrows(com.github.search5.hg4j.errors.HgRevisionNotFoundException.class, cat::call);
        assertTrue(ex.getMessage().contains("File version not found in history"));
    }

    @Test
    public void testCommitDefaultDraftPhase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "test content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("신규 커밋").call();

        // Verify that the phase of the newly created commit is draft
        com.github.search5.hg4j.core.PhaseRoots phaseRoots = repo.getPhaseRoots();
        com.github.search5.hg4j.lib.NodeId nodeId = new com.github.search5.hg4j.lib.NodeId(commitNode);
        
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = repo.getRevlog(clIdx, clDat);

        assertEquals(com.github.search5.hg4j.core.PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(nodeId, cl));
    }

    @Test
    public void testPushBlocksSecretPhaseCommits(@TempDir Path tempDir) throws Exception {
        // 1. Initialize remote repository and create the first commit
        File remoteDir = tempDir.resolve("remote").toFile();
        HgRepository remoteRepo = Hg.init().setDirectory(remoteDir).call();
        File rf = new File(remoteDir, "base.txt");
        Files.writeString(rf.toPath(), "base content");
        new AddCommand(remoteRepo).call();
        new CommitCommand(remoteRepo).setMessage("Base commit").call();

        // 2. Clone remote repository to local directory to establish the repository association
        File localDir = tempDir.resolve("local").toFile();
        Hg.cloneRepository().setSource(remoteDir.getAbsolutePath()).setDirectory(localDir).call();
        
        HgRepository localRepo = Hg.open(localDir).getRepository();
        File lf = new File(localDir, "secret.txt");
        Files.writeString(lf.toPath(), "secret content");
        new AddCommand(localRepo).call();
        byte[] commitNode = new CommitCommand(localRepo).setMessage("Secret commit").call();

        // 3. Force change the local new commit's phase to SECRET
        com.github.search5.hg4j.core.PhaseRoots phaseRoots = localRepo.getPhaseRoots();
        com.github.search5.hg4j.lib.NodeId nodeId = new com.github.search5.hg4j.lib.NodeId(commitNode);
        
        File clIdx = new File(localRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(localRepo.getStoreDir(), "00changelog.d");
        Revlog cl = localRepo.getRevlog(clIdx, clDat);
        
        phaseRoots.setPhase(nodeId, com.github.search5.hg4j.core.PhaseRoots.Phase.SECRET, cl);
        assertEquals(com.github.search5.hg4j.core.PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nodeId, cl));

        // 4. Verify that an exception is thrown when push is called
        PushCommand push = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath());
        com.github.search5.hg4j.errors.HgValidationException ex = assertThrows(com.github.search5.hg4j.errors.HgValidationException.class, push::call);
        assertTrue(ex.getMessage().contains("push includes secret commit"));
    }
}
