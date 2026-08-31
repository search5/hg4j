package com.github.search5.hg4j.lib;
import com.github.search5.hg4j.dirstate.Dirstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgRepositoryTest {

    /**
     * 실제 {@code hg} CLI(Mercurial 7.2)로
     * {@code hg --config format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data init}
     * 후 커밋해서 얻은 실제 {@code .hg/store/requires} 내용 (RevlogV2ParserTest의 픽스처와 동일 저장소).
     * v2 관련 requirement는 {@code .hg/requires}가 아니라 {@code .hg/store/requires}에
     * 기록된다는 것까지 실측으로 확인됐다 — 반드시 두 파일 다 읽어야 한다.
     */
    @Test
    public void testLoadRequiresRecognizesRealChangelogV2Requirement(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        File storeDir = new File(hgDir, "store");
        storeDir.mkdirs();
        Files.writeString(new File(hgDir, "requires").toPath(), "share-safe\n");
        Files.writeString(new File(storeDir, "requires").toPath(),
                "dotencode\nexp-changelog-v2\nfncache\ngeneraldelta\nrevlog-compression-zstd\nrevlogv1\nsparserevlog\nstore\n");

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertTrue(repo.isChangelogV2(), "exp-changelog-v2가 .hg/store/requires에서 인식되어야 함");
            assertFalse(repo.isRevlogV2());
            assertFalse(repo.isPersistentNodemap());
        }
    }

    @Test
    public void testLoadRequiresRecognizesRevlogV2AndPersistentNodemap(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        File storeDir = new File(hgDir, "store");
        storeDir.mkdirs();
        Files.writeString(new File(storeDir, "requires").toPath(),
                "exp-revlogv2.2\npersistent-nodemap\nfncache\nstore\n");

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertTrue(repo.isRevlogV2());
            assertTrue(repo.isPersistentNodemap());
            assertFalse(repo.isChangelogV2());
        }
    }

    @Test
    public void testHgrcPathsAliasResolution(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        hgDir.mkdirs();
        
        // Write [paths] default alias to hgrc
        String hgrcContent = "[paths]\ndefault = http://example.com/repo\nother-path = ssh://hg@example.com/repo2\n";
        Files.writeString(new File(hgDir, "hgrc").toPath(), hgrcContent);

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertEquals("http://example.com/repo", repo.getConfig().getPath("default"));
            assertEquals("ssh://hg@example.com/repo2", repo.getConfig().getPath("other-path"));
            assertNull(repo.getConfig().getPath("non-existent"));
        }
    }

    @Test
    public void testBranchReadException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();

            // Create .hg/branch as a directory to force IOException during reading
            File branchDir = new File(repo.getHgDir(), "branch");
            branchDir.mkdirs();

            // reading should fail with UncheckedIOException
            assertThrows(UncheckedIOException.class, repo::getBranch);
        }
    }

    @Test
    public void testInvalidHgIgnoreSyntax(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();

            // Write .hgignore with invalid syntax pattern and dynamic syntaxes
            File ignoreFile = new File(repoDir, ".hgignore");
            Files.writeString(ignoreFile.toPath(), "syntax: glob\n*[invalid_glob\nsyntax: regexp\n[invalid_regex\nvalid_file.txt");

            // Should not crash and successfully skip invalid lines, but track valid patterns
            assertFalse(repo.isIgnored("some_other_file.txt"));
        }
    }

    @Test
    public void testRebuildDirstateEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
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
    }

    @Test
    public void testScanDirectoryWithNonExistent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            // Scan on empty repo should be empty
            List<String> files = repo.scanWorkingCopy();
            assertTrue(files.isEmpty());
        }
    }

    @Test
    public void testWriteDirstateNullValidation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertThrows(IllegalArgumentException.class, () -> {
                repo.writeDirstate(null);
            });
        }
    }

    @Test
    public void testRebuildDirstateFromManifestV1WithFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        // 1. Repository 초기화 및 파일 커밋
        try (HgRepository repo = com.github.search5.hg4j.api.Hg.init().setDirectory(repoDir).call()) {
            
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Hello fulltext\n");
            new com.github.search5.hg4j.api.AddCommand(repo).addFile("a.txt").call();
            new com.github.search5.hg4j.api.CommitCommand(repo).setMessage("commit1").call();

            // 2. dirstate 파일을 강제로 손상된 데이터로 덮어쓰기
            File dirstateFile = new File(repo.getHgDir(), "dirstate");
            assertTrue(dirstateFile.exists());
            Files.writeString(dirstateFile.toPath(), "corrupted dirstate"); 

            // 3. dirstate 로드 시도 -> 실패 후 rebuildDirstateFromManifest가 자동 호출됨
            Dirstate dirstate = repo.getDirstate();

            // 4. 단언: a.txt가 dirstate 엔트리에 정상적으로 복구되어 들어가 있어야 함
            assertNotNull(dirstate);
            assertFalse(dirstate.isV2());
            Dirstate.Entry entry = dirstate.getEntries().get("a.txt");
            assertNotNull(entry);
            assertEquals('n', entry.getState());
        }
    }

    @Test
    public void testHgIgnoreBraceExpansion(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();

            // Write .hgignore with glob syntax using brace expansion, wildcard '?', and directory wildcard '**'
            File ignoreFile = new File(repoDir, ".hgignore");
            Files.writeString(ignoreFile.toPath(), "syntax: glob\n*.{xml,json}\n???.txt\nsrc/**/config.cfg\n");

            // Should ignore .xml and .json but not .txt
            assertTrue(repo.isIgnored("test.xml"));
            assertTrue(repo.isIgnored("data/config.json"));
            assertFalse(repo.isIgnored("readme.txt"));

            // Test wildcard '?' (exactly 3 chars + .txt)
            assertTrue(repo.isIgnored("abc.txt"));
            assertFalse(repo.isIgnored("abcd.txt"));

            // Test directory wildcard '**'
            assertTrue(repo.isIgnored("src/a/b/config.cfg"));
            assertFalse(repo.isIgnored("src/config.cfg"));
        }
    }
}
