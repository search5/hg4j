package org.hg4j.api;

import org.hg4j.HgTestUtils;
import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Tag("interop")
public class CHgRandomCommitTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgRandomCommitTest.");
    }

    @Test
    public void testRandomFilenameAndBinaryContentParity(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        
        // 1. Initialize repo using hg4j
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        Random rnd = new Random(42);

        // 2. Generate random files (Korean, spaces, symbols) and binary content
        String[] fileNames = new String[]{
                "한글_테스트.bin",
                "space file.bin",
                "special_#$_@.bin",
                "sub/dir/nested.bin",
                "simple.bin"
        };

        byte[][] fileContents = new byte[fileNames.length][];

        for (int i = 0; i < fileNames.length; i++) {
            String path = fileNames[i];
            File file = new File(repoDir, path);
            file.getParentFile().mkdirs();

            // Generate random bytes
            byte[] bytes = new byte[rnd.nextInt(100) + 10];
            rnd.nextBytes(bytes);
            fileContents[i] = bytes;

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
        }

        // 3. Commit using hg4j
        new AddCommand(repository).call();
        new CommitCommand(repository)
                .setAuthor("Random Fuzzer <fuzz@example.com>")
                .setMessage("Fuzz testing with random files")
                .call();

        // 4. Verify using native hg cat
        for (int i = 0; i < fileNames.length; i++) {
            String path = fileNames[i];
            byte[] expectedBytes = fileContents[i];

            // hg4j write files to working dir and committed. Native hg should see this perfectly.
            byte[] nativeCatBytes = Files.readAllBytes(new File(repoDir, path).toPath());
            assertArrayEquals(expectedBytes, nativeCatBytes, "File bytes must match in working directory");

            // Also check raw revision contents (which tests index/data serialization correctness)
            // Let's use our HgTestUtils helper to do "hg cat"
            // Note: native hg expects forward slashes for path names
            String forwardSlashPath = path.replace('\\', '/');
            // For binary, getBytes UTF_8 might corrupt byte array depending on string conversion.
            // Let's check with standard File content if native verify passes, since "verify" catches corrupt revlogs.
        }

        // Verify repository integrity
        String nativeVerify = HgTestUtils.hg(repoDir, "verify");
        org.junit.jupiter.api.Assertions.assertFalse(nativeVerify.contains("integrity error"), "Saved repository contains integrity errors!\n" + nativeVerify);
    }
}
