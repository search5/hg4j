package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog 28 (LFS / narrow clone 카테고리): gap table의 "LFS (largefiles)" 행이 hg4j끼리만
 * 검증하는 {@link HgLfsTest}만 근거로 "✅"였던 것을, 실제 hg CLI(7.2, {@code hgext.lfs} 확장)와
 * 대조해 처음 검증한다.
 *
 * <p>대조 과정에서 발견한 실제 버그: {@link HgLfsManager#getLocalPath} 가 Git-LFS 스타일의
 * 2단계 샤딩({@code objects/XX/YY/ZZZZ...})을 쓰고 있었는데, 실제 hg의 {@code hgext/lfs}
 * ({@code blobstore.py}의 {@code lfsvfs.join()}: "split the path at first two characters, like:
 * XX/XXXXX...")는 1단계 샤딩({@code objects/XX/YYYYY...})만 쓴다 -- hg4j와 실제 hg가 같은
 * {@code .hg/store/lfs/objects/} 디렉터리를 공유해도 서로 blob을 절대 찾지 못하는 버그였다.
 * 이 테스트를 추가하며 수정했다.
 *
 * <p>포인터 파일 텍스트 포맷 자체({@code version}/{@code oid sha256:}/{@code size} 줄, 알파벳
 * 순으로 뒤따르는 부가 필드)는 실제 hg가 만든 걸 그대로 {@link HgLfsPointer#parse}로 파싱해서
 * 맞는 것으로 확인했다 -- 별도 수정 불필요.
 *
 * <p><b>백로그 31(2026-09-04) 추가</b>: {@code CommitCommand}/{@code UpdateCommand}에 LFS
 * 커밋/체크아웃 파이프라인을 연동했다 -- 커밋 시 {@code [lfs] threshold}를 넘는 파일(rename/
 * copy 메타데이터가 없는 경우로 범위 한정, 아래 참고)은 실제 바이트 대신 LFS 포인터를
 * filelog에 쓰고 {@code REVIDX_EXTSTORED} 플래그를 세팅하며, 체크아웃 시 그 플래그를 보고
 * 포인터를 실제 바이트로 되돌린다. {@link #hg4jChecksOutRealHgLfsCommitWithFullContent}/
 * {@link #hg4jCommitsLfsFileAndRealHgSeesFullContent}가 이 파이프라인을 real hg CLI와
 * 양방향으로 검증한다.
 *
 * <p><b>범위 밖으로 남긴 것(정직하게 기록)</b>: rename/copy와 LFS 임계값을 동시에 넘는
 * 파일(포인터에 real hg의 {@code x-hg-*} copy-tracing 메타데이터를 접어 넣는 것까지는
 * 구현 안 함 -- 그런 파일은 그냥 일반 경로로 커밋됨), 원격 LFS 서버 URL을 {@code [paths]
 * default}에서 그대로 유추하는 것(실제 hg처럼 서버별 override({@code [lfs] url})는
 * 지원 안 함), {@code .hgrc}의 {@code lfs.disableusercache} 등 세부 옵션.
 */
@Tag("interop")
public class LfsRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isLfsExtensionAvailable(), "Native hg's lfs extension is not available. Skipping.");
    }

    private static boolean isLfsExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.lfs=", "version").start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 실제 hg CLI로 LFS 파일을 커밋한 뒤:
     * 1) filelog에 저장된 포인터 텍스트를 hg4j의 {@link Revlog}로 직접 읽어
     *    {@link HgLfsPointer#parse}가 정확히 파싱하는지,
     * 2) 실제 hg가 로컬에 캐시해둔 blob을 hg4j의 {@link HgLfsManager#getLocalPath}가 정확히
     *    같은 경로로 계산해 {@link HgLfsManager#getCachedObject}로 원본과 동일한 바이트를
     *    읽어내는지 검증한다.
     */
    @Test
    public void hg4jReadsRealHgLfsPointerAndResolvesRealHgBlobStore(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n",
                java.nio.file.StandardOpenOption.APPEND);

        byte[] originalContent = new byte[4096];
        new Random(42).nextBytes(originalContent);
        Files.write(new File(repoDir, "big.bin").toPath(), originalContent);

        HgTestUtils.hg(repoDir, "add", "big.bin");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        String expectedOid = sha256Hex(originalContent);

        // 1. Read the raw filelog content (the pointer text hg wrote instead of the blob itself)
        // directly via hg4j's own Revlog reader -- exactly how a real hg4j checkout/cat would see it.
        HgRepository repo = new HgRepository(repoDir);
        File flIndex = CommitCommand.getFilelogIndex(repo.getStoreDir(), "big.bin");
        File flData = new File(flIndex.getPath().substring(0, flIndex.getPath().length() - 2) + ".d");
        assertTrue(flIndex.exists(), "filelog index for big.bin must exist");
        Revlog filelog = repo.getRevlog(flIndex, flData);
        assertEquals(1, filelog.getRevisionCount());
        byte[] pointerText = filelog.getRevisionContent(0);

        String pointerAsText = new String(pointerText, StandardCharsets.UTF_8);
        assertTrue(pointerAsText.startsWith("version https://git-lfs.github.com/spec/v1\n"),
                "real hg's LFS pointer file must start with the git-lfs spec v1 version line");

        HgLfsPointer pointer = HgLfsPointer.parse(pointerText);
        assertEquals("https://git-lfs.github.com/spec/v1", pointer.getVersion());
        assertEquals(expectedOid, pointer.getOid());
        assertEquals(originalContent.length, pointer.getSize());

        // 2. Confirm hg4j's local blob path scheme now matches real hg's (hgext/lfs/blobstore.py
        // lfsvfs.join(): single two-character shard, "XX/YYYY...", not two-level Git-LFS sharding).
        HgLfsManager manager = new HgLfsManager(repo.getHgDir());
        File expectedRealHgBlobPath = new File(repo.getStoreDir(),
                "lfs/objects/" + expectedOid.substring(0, 2) + "/" + expectedOid.substring(2));
        assertTrue(expectedRealHgBlobPath.exists(), "sanity check: real hg must have written the blob at this path");
        assertEquals(expectedRealHgBlobPath.getCanonicalFile(), manager.getLocalPath(pointer.getOid()).getCanonicalFile(),
                "hg4j's local LFS object path must match real hg's on-disk layout exactly");

        assertTrue(manager.isCached(pointer), "hg4j must recognize the blob real hg already stored locally as cached");
        byte[] resolved = manager.getCachedObject(pointer);
        assertArrayEquals(originalContent, resolved, "hg4j must resolve the exact same blob content real hg committed");
    }

    /**
     * Byte-level direction check: hg4j writes a blob into the local LFS store via
     * {@link HgLfsManager#cacheObject}, and real hg's own {@code hg cat} on a *different* clone
     * that is missing that same blob (deleted for the test) is repaired by dropping hg4j's write
     * at the exact expected path -- proving the on-disk format/path hg4j produces is something
     * real hg actually reads, not just something hg4j itself can read back.
     */
    @Test
    public void realHgReadsBlobCachedByHgLfsManager(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n",
                java.nio.file.StandardOpenOption.APPEND);

        byte[] originalContent = new byte[2048];
        new Random(7).nextBytes(originalContent);
        Files.write(new File(repoDir, "big.bin").toPath(), originalContent);
        HgTestUtils.hg(repoDir, "add", "big.bin");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        String oid = sha256Hex(originalContent);
        HgRepository repo = new HgRepository(repoDir);
        File realHgBlobPath = new File(repo.getStoreDir(), "lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2));
        assertTrue(realHgBlobPath.exists());

        // Delete real hg's own copy of the blob (but keep the pointer-only filelog revision and
        // the working copy checkout untouched), then have hg4j's HgLfsManager re-populate the
        // exact same store from a byte-identical payload.
        assertTrue(realHgBlobPath.delete());
        // (lfs.disableusercache is set above, so there is no separate user-wide cache copy to
        // worry about here -- the repo-local store is the only place real hg will look.)

        HgLfsManager manager = new HgLfsManager(repo.getHgDir());
        HgLfsPointer pointer = new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, originalContent.length);
        manager.cacheObject(pointer, originalContent);

        byte[] catOutput = hgCatRawBytes(repoDir, "cat", "big.bin");
        assertArrayEquals(originalContent, catOutput,
                "real hg's own \"hg cat\" must be able to read the blob hg4j wrote into the shared local LFS store");
    }

    /**
     * Pipeline test (backlog 31), checkout direction: real hg commits an LFS file, hg4j's
     * {@link UpdateCommand} (after the working-copy file is deleted, forcing a real rewrite) must
     * restore the exact original bytes -- not the pointer text.
     */
    @Test
    public void hg4jChecksOutRealHgLfsCommitWithFullContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n",
                java.nio.file.StandardOpenOption.APPEND);

        byte[] originalContent = new byte[4096];
        new Random(99).nextBytes(originalContent);
        Files.write(new File(repoDir, "big.bin").toPath(), originalContent);
        HgTestUtils.hg(repoDir, "add", "big.bin");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        HgRepository repo = new HgRepository(repoDir);
        File workingFile = new File(repoDir, "big.bin");
        assertTrue(workingFile.delete(), "must delete the working copy so UpdateCommand is forced to rewrite it");

        new UpdateCommand(repo).setForce(true).call();

        assertTrue(workingFile.exists(), "hg4j's UpdateCommand must recreate big.bin");
        byte[] restored = Files.readAllBytes(workingFile.toPath());
        assertArrayEquals(originalContent, restored,
                "hg4j must check out the real LFS blob content, not the pointer text");
        assertFalse(new String(restored, StandardCharsets.UTF_8).startsWith("version https://git-lfs"),
                "sanity: the restored file must not be the raw pointer text");
    }

    /**
     * Pipeline test (backlog 31), commit direction: hg4j's {@link CommitCommand} writes a file
     * past {@code [lfs] threshold} as an LFS pointer + REVIDX_EXTSTORED, and real hg's own
     * {@code hg cat} (with the lfs extension enabled, so it dereferences the pointer transparently)
     * must read back the exact original bytes.
     */
    @Test
    public void hg4jCommitsLfsFileAndRealHgSeesFullContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new io.github.search5.hg4j.api.InitCommand().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n");

        HgRepository repo = new HgRepository(repoDir);
        byte[] originalContent = new byte[8192];
        new Random(123).nextBytes(originalContent);
        Files.write(new File(repoDir, "big.bin").toPath(), originalContent);
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setAuthor("hg4j").setMessage("add big lfs file").call();
        assertNotNull(commitNode);

        // Sanity: hg4j itself must have written a pointer (REVIDX_EXTSTORED), not the raw bytes.
        File flIndex = CommitCommand.getFilelogIndex(repo.getStoreDir(), "big.bin");
        File flData = new File(flIndex.getPath().substring(0, flIndex.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIndex, flData);
        assertTrue(filelog.isExtStored(0), "hg4j must flag the LFS revision with REVIDX_EXTSTORED");
        byte[] pointerText = filelog.getRevisionContent(0);
        assertTrue(new String(pointerText, StandardCharsets.UTF_8).startsWith("version https://git-lfs.github.com/spec/v1\n"),
                "hg4j's filelog content for the LFS revision must be the pointer text");

        String expectedOid = sha256Hex(originalContent);
        File blobPath = new File(repo.getStoreDir(), "lfs/objects/" + expectedOid.substring(0, 2) + "/" + expectedOid.substring(2));
        assertTrue(blobPath.exists(), "hg4j must have cached the real bytes in the local LFS blob store");

        byte[] realHgCatOutput = hgCatRawBytes(repoDir, "--config", "extensions.lfs=", "cat", "-r", "0", "big.bin");
        assertArrayEquals(originalContent, realHgCatOutput,
                "real hg's own `hg cat` (lfs-aware) must dereference hg4j's pointer to the exact original bytes");

        String realHgVerify = HgTestUtils.hg(repoDir, "--config", "extensions.lfs=", "verify");
        assertFalse(realHgVerify.toLowerCase().contains("error:"),
                "real hg verify (lfs-aware) must find no errors in an hg4j-committed LFS repository: " + realHgVerify);
    }

    /** Like {@link HgTestUtils#hg} but returns the raw stdout bytes losslessly (no UTF-8/trim round-trip),
     * which matters here since the LFS payload is arbitrary binary data. */
    private static byte[] hgCatRawBytes(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "hg";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        Process p = pb.start();
        byte[] out;
        try (InputStream is = p.getInputStream()) {
            out = is.readAllBytes();
        }
        byte[] err;
        try (InputStream es = p.getErrorStream()) {
            err = es.readAllBytes();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + String.join(" ", args) + " failed with exit code " + code
                    + ": " + new String(err, StandardCharsets.UTF_8));
        }
        return out;
    }
}
