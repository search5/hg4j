package org.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgRepositoryTest {

    @Test
    public void testBranchReadException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getHgDir().mkdirs();

        // Create .hg/branch as a directory to force IOException during reading
        File branchDir = new File(repo.getHgDir(), "branch");
        branchDir.mkdirs();

        // reading should fail with UncheckedIOException
        assertThrows(UncheckedIOException.class, repo::getBranch);
    }

    @Test
    public void testInvalidHgIgnoreSyntax(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getHgDir().mkdirs();

        // Write .hgignore with invalid syntax pattern and dynamic syntaxes
        File ignoreFile = new File(repoDir, ".hgignore");
        Files.writeString(ignoreFile.toPath(), "syntax: glob\n*[invalid_glob\nsyntax: regexp\n[invalid_regex\nvalid_file.txt");

        // Should not crash and successfully skip invalid lines, but track valid patterns
        assertFalse(repo.isIgnored("some_other_file.txt"));
    }

    @Test
    public void testRebuildDirstateEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getHgDir().mkdirs();

        // 1. Dirstate file has v2 magic, but changelog doesn't exist.
        File dirstateFile = new File(repo.getHgDir(), "dirstate");
        
        // Write V2 data file
        File dataFile = new File(repo.getHgDir(), "dirstate.d.123456");
        byte[] v2DataHeader = new byte[12];
        v2DataHeader[7] = 12; // nodesOffset = 12
        v2DataHeader[11] = 12; // dataOffset = 12
        Files.write(dataFile.toPath(), v2DataHeader);

        // Write V2 Docket file (Strict 122+ bytes)
        byte[] v2Magic = "dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] uidBytes = "123456".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(docketSize).order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.put(v2Magic);
        buf.put(new byte[32]); // p1
        buf.put(new byte[32]); // p2
        buf.put(new byte[44]); // tree metadata
        buf.putInt(12); // dataLength = 12
        buf.put((byte) uidBytes.length);
        buf.put(uidBytes);
        Files.write(dirstateFile.toPath(), buf.array());

        Dirstate dirstate = repo.getDirstate();
        assertTrue(dirstate.isV2(), "Should preserve original v2 format during self-healing");
        assertArrayEquals(new byte[20], dirstate.getParent1());

        // 2. Dirstate file has v2 magic, changelog exists but is empty.
        File storeDir = repo.getStoreDir();
        storeDir.mkdirs();
        File clIdx = new File(storeDir, "00changelog.i");
        Files.write(clIdx.toPath(), new byte[0]);

        Dirstate dirstate2 = repo.getDirstate();
        assertTrue(dirstate2.isV2());
        assertArrayEquals(new byte[20], dirstate2.getParent1());
    }

    @Test
    public void testScanDirectoryWithNonExistent(@TempDir Path tempDir) {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        
        // Scan on empty repo should be empty
        List<String> files = repo.scanWorkingCopy();
        assertTrue(files.isEmpty());
    }

    @Test
    public void testWriteDirstateNullValidation(@TempDir Path tempDir) {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        assertThrows(IllegalArgumentException.class, () -> {
            repo.writeDirstate(null);
        });
    }
}
