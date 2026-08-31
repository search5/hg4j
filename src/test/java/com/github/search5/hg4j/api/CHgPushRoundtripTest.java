package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("interop")
public class CHgPushRoundtripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgPushRoundtripTest.");
    }

    @Test
    public void testNativeHgPushRoundtrip(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote_repo").toFile();
        File localDir = tempDir.resolve("local_repo").toFile();

        // 1. Setup native hg remote repository (empty)
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            // just initialize
        });

        // Allow push/unbundle in the remote repo config
        File hgrc = new File(remoteDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), "[web]\nallow_push = *\npush_ssl = false\n");

        // 2. Initialize local repository via hg4j
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepository = new HgRepository(localDir);

        // 3. Commit files locally in hg4j
        File f1 = new File(localDir, "f1.txt");
        Files.writeString(f1.toPath(), "Hello push interop file 1\n");
        new AddCommand(localRepository).call();
        new CommitCommand(localRepository)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("Push test commit 1")
                .call();

        File f2 = new File(localDir, "f2.txt");
        Files.writeString(f2.toPath(), "Hello push interop file 2\n");
        new AddCommand(localRepository).call();
        new CommitCommand(localRepository)
                .setAuthor("Bob <bob@example.com>")
                .setMessage("Push test commit 2")
                .call();

        // 4. Push local changes to remote native hg repo
        String pushResult = new PushCommand(localRepository)
                .setDestination(remoteDir.getAbsolutePath())
                .call();

        assertNotNull(pushResult);

        // 5. Verify the pushed revisions in the remote native hg repo
        String nativeLog = HgTestUtils.hg(remoteDir, "log", "--template", "{node}|{desc}|{author}\n");
        String[] nativeLines = nativeLog.split("\n");

        assertEquals(2, nativeLines.length, "Remote native repo must have exactly 2 committed changesets after push");

        // Remote log shows newest first
        String[] fields0 = nativeLines[0].split("\\|");
        assertEquals("Push test commit 2", fields0[1]);
        assertEquals("Bob <bob@example.com>", fields0[2]);

        String[] fields1 = nativeLines[1].split("\\|");
        assertEquals("Push test commit 1", fields1[1]);
        assertEquals("Alice <alice@example.com>", fields1[2]);

        // 6. Verify native hg verify
        String nativeVerify = HgTestUtils.hg(remoteDir, "verify");
        assertFalse(nativeVerify.contains("integrity error"), "Saved repository contains integrity errors!\n" + nativeVerify);
    }
}
