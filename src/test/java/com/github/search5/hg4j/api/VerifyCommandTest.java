package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VerifyCommandTest {

    @Test
    public void testVerifyHealthyRepository(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        // 1. Create healthy commits
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "Content 1");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();

        Files.writeString(f.toPath(), "Content 2");
        hg.commit().setMessage("Second").call();

        // 2. Verify should return no errors
        List<String> errors = hg.verify().call();
        assertTrue(errors.isEmpty(), "Healthy repository must have 0 verification errors, but got: " + errors);
    }

    @Test
    public void testVerifyCorruptChangelogDetectsErrors(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "Content 1");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();

        // Close repository to flush cached files
        repo.close();

        // 1. Artificially corrupt the 00changelog.i file (overwrite expected node id bytes with garbage)
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        assertTrue(clIdx.exists(), "Changelog index must exist");

        // Node ID of the first revision starts at offset 32 inside the first 64-byte index record (for revlog v1 format)
        try (RandomAccessFile raf = new RandomAccessFile(clIdx, "rw")) {
            raf.seek(32);
            raf.write(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9}); // overwrite with corrupt bytes
        }

        // Re-open repo and verify
        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertFalse(errors.isEmpty(), "Verify must detect corrupt data errors");
            assertTrue(errors.get(0).contains("integrity mismatch") || errors.get(0).contains("failed to read"),
                       "Error message must specify integrity mismatch or reading failure, got: " + errors.get(0));
        }
    }

    /**
     * 2026-09-01 이전에는 클래스 Javadoc이 "changelog, manifest, and all filelogs"를
     * 검사한다고 주장했지만 실제로는 filelog를 전혀 검사하지 않아서, 파일 콘텐츠가
     * 손상돼도 "정상"으로 보고하는 거짓 양성이 있었다. filelog 손상이 실제로 잡히는지
     * 검증한다.
     */
    @Test
    public void testVerifyCorruptFilelogDetectsErrors(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "Content 1");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("First").call();
        repo.close();

        File flIdx = new File(repo.getStoreDir(), "data/a.txt.i");
        assertTrue(flIdx.exists(), "Filelog index must exist: " + flIdx);
        try (RandomAccessFile raf = new RandomAccessFile(flIdx, "rw")) {
            raf.seek(32);
            raf.write(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        }

        try (HgRepository corruptRepo = new HgRepository(repoDir)) {
            List<String> errors = new VerifyCommand(corruptRepo).call();
            assertFalse(errors.isEmpty(), "Verify must detect filelog corruption");
            assertTrue(errors.stream().anyMatch(e -> e.contains("a.txt") && e.contains("integrity mismatch")),
                    "Error must reference the corrupted filelog: " + errors);
        }
    }
}
