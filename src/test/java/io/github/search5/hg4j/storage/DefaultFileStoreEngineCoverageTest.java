package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted coverage for {@link DefaultFileStoreEngine}, focused on the two branches not exercised
 * elsewhere: {@code getManifestAtCommit}'s "commit not found" throw (line 31/32), and the
 * executable/symlink flag ternary (line 40), which needs a manifest entry of each kind
 * (plain/executable/symlink) to flip all four branch outcomes.
 */
public class DefaultFileStoreEngineCoverageTest {

    @Test
    public void testGetManifestAtCommitThrowsWhenCommitNodeIdNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "hello\n");
            new AddCommand(repo).addFile("a.txt").call();
            new CommitCommand(repo).setMessage("c1").call();

            DefaultFileStoreEngine engine = new DefaultFileStoreEngine();
            byte[] unknownNodeId = new byte[20];
            unknownNodeId[0] = (byte) 0xAB; // not present in the changelog -> findRevisionByNodeId returns -1

            IOException ex = assertThrows(IOException.class,
                    () -> engine.getManifestAtCommit(repo, unknownNodeId));
            assertTrue(ex.getMessage().contains("Commit revision not found"));
        }
    }

    @Test
    public void testGetManifestAtCommitFlagsPlainExecutableAndSymlinkEntries(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File plain = new File(repoDir, "plain.txt");
            Files.writeString(plain.toPath(), "plain content", StandardCharsets.UTF_8);

            File script = new File(repoDir, "run.sh");
            Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n", StandardCharsets.UTF_8);
            assertTrue(script.setExecutable(true), "test requires setting the executable bit to work on this platform");

            File link = new File(repoDir, "link.txt");
            Files.createSymbolicLink(link.toPath(), new File("plain.txt").toPath());

            new AddCommand(repo).addFile("plain.txt").addFile("run.sh").addFile("link.txt").call();
            new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("mixed flags").call();

            Dirstate dirstate = repo.getDirstate();
            byte[] commitNodeId = dirstate.getParent1();

            DefaultFileStoreEngine engine = new DefaultFileStoreEngine();
            Map<String, String> manifest = engine.getManifestAtCommit(repo, commitNodeId);

            assertEquals(3, manifest.size());
            // Plain file: neither executable nor symlink -> empty flag suffix (already covered
            // elsewhere, kept here for a complete before/after picture of all three flag outcomes).
            assertTrue(manifest.get("plain.txt").endsWith(""));
            assertTrue(!manifest.get("plain.txt").endsWith("x") && !manifest.get("plain.txt").endsWith("l"));
            // Executable file -> "x" suffix.
            assertTrue(manifest.get("run.sh").endsWith("x"));
            // Symlink -> "l" suffix.
            assertTrue(manifest.get("link.txt").endsWith("l"));
        }
    }
}
