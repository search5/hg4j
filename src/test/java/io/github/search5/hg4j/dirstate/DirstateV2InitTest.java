package io.github.search5.hg4j.dirstate;
import io.github.search5.hg4j.lib.HgRepository;

import io.github.search5.hg4j.api.Hg;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DirstateV2InitTest {

    @Test
    public void testDirstateV2InitializationAndCommit() throws Exception {
        File tempRepoDir = Files.createTempDirectory("hg4j_dirstate_v2_init_").toFile();
        try {
            // 1. Initialize with dirstate-v2 enabled
            HgRepository repo = Hg.init()
                    .setDirectory(tempRepoDir)
                    .setDirstateV2(true)
                    .call();

            assertNotNull(repo);

            // 2. Verify requires file contains 'dirstate-v2'
            File requiresFile = new File(repo.getHgDir(), "requires");
            assertTrue(requiresFile.exists());
            List<String> requirements = Files.readAllLines(requiresFile.toPath());
            assertTrue(requirements.contains("dirstate-v2"));

            // 3. Create a dummy file, add and commit to write dirstate
            File sample = new File(tempRepoDir, "sample.txt");
            Files.writeString(sample.toPath(), "Hello V2 Dirstate", StandardCharsets.UTF_8);

            try (Hg hg = Hg.open(tempRepoDir)) {
                hg.add().addFile("sample.txt").call();
                hg.commit().setAuthor("tester <tester@example.com>").setMessage("Commit V2").call();
            }

            // 4. Verify dirstate docket has magic 'dirstate-v2\n'
            File dirstateFile = new File(repo.getHgDir(), "dirstate");
            assertTrue(dirstateFile.exists());
            byte[] dirstateBytes = Files.readAllBytes(dirstateFile.toPath());
            assertTrue(dirstateBytes.length >= 12);
            String magic = new String(dirstateBytes, 0, 12, StandardCharsets.US_ASCII);
            assertEquals("dirstate-v2\n", magic);

            // 5. Read back dirstate and check entries
            Dirstate dirstate = repo.getDirstate();
            assertTrue(dirstate.isV2());
            assertTrue(dirstate.getEntries().containsKey("sample.txt"));
            assertEquals('n', dirstate.getEntries().get("sample.txt").getState());

        } finally {
            deleteDirRecursively(tempRepoDir);
        }
    }

    @Test
    public void testDirstateV1ByDefault() throws Exception {
        File tempRepoDir = Files.createTempDirectory("hg4j_dirstate_v1_init_").toFile();
        try {
            // 1. Initialize with default (dirstate-v1)
            HgRepository repo = Hg.init()
                    .setDirectory(tempRepoDir)
                    .call();

            assertNotNull(repo);

            // 2. Verify requires file does NOT contain 'dirstate-v2'
            File requiresFile = new File(repo.getHgDir(), "requires");
            assertTrue(requiresFile.exists());
            List<String> requirements = Files.readAllLines(requiresFile.toPath());
            assertFalse(requirements.contains("dirstate-v2"));

            // 3. Create a dummy file, add and commit to write dirstate
            File sample = new File(tempRepoDir, "sample.txt");
            Files.writeString(sample.toPath(), "Hello V1 Dirstate", StandardCharsets.UTF_8);

            try (Hg hg = Hg.open(tempRepoDir)) {
                hg.add().addFile("sample.txt").call();
                hg.commit().setAuthor("tester <tester@example.com>").setMessage("Commit V1").call();
            }

            // 4. Verify dirstate docket does NOT have magic 'dirstate-v2\n'
            File dirstateFile = new File(repo.getHgDir(), "dirstate");
            assertTrue(dirstateFile.exists());
            byte[] dirstateBytes = Files.readAllBytes(dirstateFile.toPath());
            assertTrue(dirstateBytes.length >= 40);
            String magic = new String(dirstateBytes, 0, 12, StandardCharsets.US_ASCII);
            assertNotEquals("dirstate-v2\n", magic);

            // 5. Read back dirstate and check entries
            Dirstate dirstate = repo.getDirstate();
            assertFalse(dirstate.isV2());
            assertTrue(dirstate.getEntries().containsKey("sample.txt"));
            assertEquals('n', dirstate.getEntries().get("sample.txt").getState());

        } finally {
            deleteDirRecursively(tempRepoDir);
        }
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }
}
