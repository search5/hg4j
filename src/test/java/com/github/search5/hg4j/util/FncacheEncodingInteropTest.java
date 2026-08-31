package com.github.search5.hg4j.util;

import com.github.search5.hg4j.HgTestUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 hg가 까다로운 파일명(대문자, 앞자리 점, Windows 예약어, com/lpt+숫자, 매우 긴 경로)에
 * 대해 {@code .hg/store/}에 실제로 만들어내는 온디스크 경로와 hg4j의
 * {@link NodeIdUtil#encodeFname(String)}이 산출하는 경로를 대조 검증한다.
 */
@Tag("interop")
public class FncacheEncodingInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void hg4jStoreEncodingMatchesRealHgOnDiskLayout(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "MyFile.txt").toPath(), "x");
                Files.writeString(new File(dir, ".hidden").toPath(), "x");
                Files.writeString(new File(dir, "aux.txt").toPath(), "x");
                Files.writeString(new File(dir, "con").toPath(), "x");
                Files.writeString(new File(dir, "com1.log").toPath(), "x");
                Files.writeString(new File(dir, "com5").toPath(), "x");

                File longDir = new File(dir, "verylongdirectory_name_padding_to_exceed_the_threshold_for_store_path_hash_encoding_test");
                longDir.mkdirs();
                String longName = "a".repeat(150) + ".txt";
                Files.writeString(new File(longDir, longName).toPath(), "x");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c1");

        File fncacheFile = new File(repoDir, ".hg/store/fncache");
        List<String> entries = Files.readAllLines(fncacheFile.toPath());
        assertEquals(7, entries.size(), "fncache should list all 7 tracked filelog index entries: " + entries);

        for (String entry : entries) {
            assertTrue(entry.endsWith(".i"), "unexpected fncache entry: " + entry);
            String logicalPath = entry.substring("data/".length(), entry.length() - 2); // strip "data/" and ".i"

            String hg4jEncoded = NodeIdUtil.encodeFname(logicalPath + ".i");
            File expectedOnDisk = new File(repoDir, ".hg/store/" + hg4jEncoded);
            assertTrue(expectedOnDisk.exists(),
                    "hg4j encodeFname(\"" + logicalPath + ".i\") = \"" + hg4jEncoded
                            + "\" but real hg did not create that file (entry: " + entry + ")");
        }
    }
}
