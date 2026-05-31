package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Tag("interop")
public class CHgCatTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgCatTest.");
    }

    @Test
    public void testNativeCatVsHg4jCat(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                // 1. Empty file
                File emptyFile = new File(dir, "empty.txt");
                emptyFile.createNewFile();
                HgTestUtils.hg(dir, "add", "empty.txt");

                // 2. English text
                File englishFile = new File(dir, "hello.txt");
                Files.writeString(englishFile.toPath(), "Hello, hg4j! This is an interop test.");
                HgTestUtils.hg(dir, "add", "hello.txt");

                // 3. Korean text
                File koreanFile = new File(dir, "korean.txt");
                Files.writeString(koreanFile.toPath(), "안녕하세요, hg4j 라이브러리 상호운용성 검증.");
                HgTestUtils.hg(dir, "add", "korean.txt");

                // 4. Binary bytes
                File binaryFile = new File(dir, "binary.bin");
                byte[] binaryContent = new byte[]{0x00, 0x01, 0x02, (byte)0x80, (byte)0xff, 0x09, 0x0a};
                try (FileOutputStream fos = new FileOutputStream(binaryFile)) {
                    fos.write(binaryContent);
                }
                HgTestUtils.hg(dir, "add", "binary.bin");

                HgTestUtils.hg(dir, "commit", "-m", "Add files of various formats");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Test English text
        byte[] expectedEnglish = HgTestUtils.hg(repoDir, "cat", "-r", "0", "hello.txt").getBytes(StandardCharsets.UTF_8);
        byte[] actualEnglish = new CatCommand(repository).setRevision("0").setFile("hello.txt").call();
        assertArrayEquals(expectedEnglish, actualEnglish);

        // Test Korean text
        byte[] expectedKorean = Files.readAllBytes(new File(repoDir, "korean.txt").toPath());
        byte[] actualKorean = new CatCommand(repository).setRevision("0").setFile("korean.txt").call();
        assertArrayEquals(expectedKorean, actualKorean);

        // Test Empty file
        byte[] expectedEmpty = new byte[0];
        byte[] actualEmpty = new CatCommand(repository).setRevision("0").setFile("empty.txt").call();
        assertArrayEquals(expectedEmpty, actualEmpty);

        // Test Binary file
        byte[] expectedBinary = Files.readAllBytes(new File(repoDir, "binary.bin").toPath());
        byte[] actualBinary = new CatCommand(repository).setRevision("0").setFile("binary.bin").call();
        assertArrayEquals(expectedBinary, actualBinary);
    }
}
