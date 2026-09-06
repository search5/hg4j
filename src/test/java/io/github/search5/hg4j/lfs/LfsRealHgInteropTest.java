package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.RenameCommand;
import io.github.search5.hg4j.api.AnnotateCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.sun.net.httpserver.HttpServer;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.nio.file.StandardOpenOption;

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
 * <p><b>백로그 42(2026-09-06) 추가 -- 위에서 "범위 밖"으로 남겼던 3가지를 마저 구현</b>:
 * (1) rename과 LFS 임계값을 동시에 넘는 파일의 copy-tracing -- real hg의 {@code
 * hgext/lfs/wrapper.py} {@code writetostore}/{@code readfromstore}를 그대로 재현: 별도
 * {@code \x01\n...\x01\n} 메타데이터 블록 대신 포인터 자체의 {@code x-hg-copy}/{@code
 * x-hg-copyrev} 필드로 접어 넣고, 파일노드 해시는 메타데이터로 감싼 실제 바이트 기준으로
 * 계산한다(둘 다 실제 {@code hg mv} + LFS 커밋을 재현해 바이트 단위로 대조 확인,
 * {@link Revlog#wrapMetadata}/{@link HgLfsPointer#getExtra()} 참고).
 * {@link #hg4jFollowsRenameAcrossARealHgLfsCommitAndMatchesRealHgAnnotate}/
 * {@link #realHgFollowsRenameAcrossAnLfsFileHg4jCommitted}가 양방향으로 검증한다.
 * (2) {@code [lfs] url} override -- real hg의 기본 유추는 {@code <remote>/info/lfs}가 아니라
 * {@code <remote>/.git/info/lfs}였다(2026-09-06, {@code hg clone -v}로 실측 확인 후 발견한
 * 기존 hg4j 버그, 웹훅으로 별도 보고 -- {@link HgLfsManager#resolveServerUrl} 참고).
 * {@link #hg4jFetchesLfsBlobFromRealHgServeViaGitInfoLfsConvention}이 실제 {@code hg serve}
 * 상대로 이 규칙이 맞는지 검증한다. (3) {@code lfs.usercache}/{@code
 * experimental.lfs.disableusercache} -- real hg가 real hg 자신의 커밋으로 채운 usercache를
 * hg4j의 {@link HgLfsManager}가 그대로 읽어내는지, disableusercache일 때 real hg 자신도
 * usercache를 안 만드는지 {@link #hg4jReadsAUsercacheBlobRealHgPopulatedViaLfsUsercacheConfig}/
 * {@link #disableusercacheStopsRealHgFromPopulatingTheUsercache}로 검증한다.
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
                StandardOpenOption.APPEND);

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
                StandardOpenOption.APPEND);

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
                StandardOpenOption.APPEND);

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
        new InitCommand().setDirectory(repoDir).call();
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

    /**
     * Backlog 42 sub-item 1 (copy-tracing): real hg commits an LFS-tracked file, then {@code hg
     * mv}s it and edits it in the SAME commit -- {@code hg annotate -c} on the result correctly
     * attributes the surviving lines to the pre-rename commit and the new line to the rename
     * commit (confirmed live above in this session). hg4j's {@link AnnotateCommand} on that same
     * real-hg-produced repository must match real hg's own attribution exactly.
     */
    @Test
    public void hg4jFollowsRenameAcrossARealHgLfsCommitAndMatchesRealHgAnnotate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = all()\n[experimental]\nlfs.disableusercache = True\n",
                StandardOpenOption.APPEND);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "line1\nline2\nline3\n");
        HgTestUtils.hg(repoDir, "add", "a.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add a");

        HgTestUtils.hg(repoDir, "mv", "a.txt", "b.txt");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "line1\nline2\nline3\nline4\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "rename a to b, add line4");

        // Sanity: this really did go through the LFS pointer path with copy metadata folded in.
        HgRepository repo = new HgRepository(repoDir);
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "b.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog bFilelog = repo.getRevlog(flIdx, flDat);
        assertTrue(bFilelog.isExtStored(0), "real hg must have LFS-flagged b.txt's own revision 0");
        Map<String, String> meta = bFilelog.getRevisionMetadata(0);
        assertEquals("a.txt", meta.get("copy"), "hg4j must recover real hg's x-hg-copy field via getRevisionMetadata");
        assertNotNull(meta.get("copyrev"));

        List<AnnotateCommand.BlameLine> lines = new AnnotateCommand(repo).setPath("b.txt").call();
        assertEquals(List.of("line1", "line2", "line3", "line4"),
                lines.stream().map(AnnotateCommand.BlameLine::getContent).toList());

        String realHgAnnotate = HgTestUtils.hg(repoDir, "--config", "extensions.lfs=", "annotate", "-c", "b.txt");
        // Real hg's own attribution, parsed from "<short-hash>: <line>" -- rev0's changeset for
        // lines 1-3, rev1's changeset for line 4.
        String[] realLines = realHgAnnotate.split("\n");
        assertEquals(4, realLines.length);
        String rev0Hash = realLines[0].split(":")[0].trim();
        String rev1Hash = realLines[3].split(":")[0].trim();
        assertNotEquals(rev0Hash, rev1Hash, "sanity: the rename+edit commit must be a different changeset");
        for (int i = 0; i < 3; i++) {
            assertTrue(realLines[i].startsWith(rev0Hash), "real hg attributes line " + (i + 1) + " to the pre-rename commit");
        }
        assertTrue(realLines[3].startsWith(rev1Hash), "real hg attributes the new line to the rename commit");

        // Cross-check hg4j's own linkrev-based attribution lands on the SAME two changesets real
        // hg named above (translated through hg4j's own changelog to get comparable short hashes).
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        String hg4jRev0Hash = NodeIdUtil.toHex(changelog.getIndexRecord(lines.get(0).getRevision()).getNodeId()).substring(0, rev0Hash.length());
        String hg4jRev1Hash = NodeIdUtil.toHex(changelog.getIndexRecord(lines.get(3).getRevision()).getNodeId()).substring(0, rev1Hash.length());
        assertEquals(rev0Hash, hg4jRev0Hash, "hg4j's line 1-3 attribution must name the same changeset real hg did");
        assertEquals(rev1Hash, hg4jRev1Hash, "hg4j's line 4 attribution must name the same changeset real hg did");
    }

    /**
     * Backlog 42 sub-item 1, reverse direction: hg4j commits an LFS-tracked file via {@link
     * CommitCommand}, renames it via {@link RenameCommand}, edits and commits again -- real hg's
     * OWN {@code hg annotate}/{@code hg log --follow}/{@code hg verify} on that hg4j-produced
     * repository must see the rename correctly (proving hg4j's pointer's {@code x-hg-copy}/{@code
     * x-hg-copyrev} fields and hash-basis computation are exactly what real hg itself expects).
     */
    @Test
    public void realHgFollowsRenameAcrossAnLfsFileHg4jCommitted(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n");

        HgRepository repo = new HgRepository(repoDir);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "alpha\nbeta\ngamma\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("hg4j").setMessage("add a").call();

        new RenameCommand(repo).setSource("a.txt").setTarget("b.txt").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "alpha\nbeta\ngamma\ndelta\n");
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rename a to b, add delta").call();

        String realHgAnnotate = HgTestUtils.hg(repoDir, "--config", "extensions.lfs=", "annotate", "-c", "b.txt");
        String[] realLines = realHgAnnotate.split("\n");
        assertEquals(4, realLines.length);
        assertEquals("alpha", realLines[0].substring(realLines[0].indexOf(':') + 2));
        assertEquals("delta", realLines[3].substring(realLines[3].indexOf(':') + 2));
        String rev0Hash = realLines[0].split(":")[0].trim();
        String rev1Hash = realLines[3].split(":")[0].trim();
        assertNotEquals(rev0Hash, rev1Hash);
        for (int i = 0; i < 3; i++) {
            assertTrue(realLines[i].startsWith(rev0Hash), "real hg must attribute line " + (i + 1) + " to the add-a commit");
        }

        String realHgFollow = HgTestUtils.hg(repoDir, "--config", "extensions.lfs=", "log", "--follow", "b.txt",
                "--template", "{rev}\\n");
        assertEquals("1\n0", realHgFollow.trim().replace("\r\n", "\n"),
                "real hg log --follow must cross the rename boundary hg4j recorded, seeing both revisions");

        String realHgVerify = HgTestUtils.hg(repoDir, "--config", "extensions.lfs=", "verify");
        assertFalse(realHgVerify.toLowerCase().contains("error:"),
                "real hg verify (lfs-aware) must find no errors in an hg4j-committed rename+LFS repository: " + realHgVerify);
    }

    /**
     * Backlog 42 sub-item 2: hg4j's {@link HgLfsManager#resolveServerUrl} derives {@code
     * <remote>/.git/info/lfs} (NOT the old, wrong {@code <remote>/info/lfs}) when no explicit
     * {@code [lfs] url} is configured -- verified here by having hg4j fetch a real LFS blob from
     * an ACTUAL {@code hg serve} instance using exactly that derived URL, end to end (batch API +
     * download), matching real hg's own {@code -v} "lfs: assuming remote store: ..." derivation
     * confirmed live earlier this session.
     */
    @Test
    public void hg4jFetchesLfsBlobFromRealHgServeViaGitInfoLfsConvention(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgTestUtils.nativeRepo(serverRepoDir, dir -> {
        });
        // Isolation: disableusercache on the SERVER side too -- the initial `hg commit` below
        // runs as a real hg process which, without this, populates the real shared
        // ~/.cache/lfs / $XDG_CACHE_HOME/lfs on this host (found live while writing this test).
        Files.writeString(new File(serverRepoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = all()\n[web]\npush_ssl = false\n"
                        + "[experimental]\nlfs.disableusercache = true\n",
                StandardOpenOption.APPEND);
        byte[] originalContent = new byte[3000];
        new Random(2026).nextBytes(originalContent);
        Files.write(new File(serverRepoDir, "big.bin").toPath(), originalContent);
        HgTestUtils.hg(serverRepoDir, "add", "big.bin");
        HgTestUtils.hg(serverRepoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Process serveProcess = new ProcessBuilder("hg", "serve", "-p", String.valueOf(port),
                "--config", "web.push_ssl=false")
                .directory(serverRepoDir)
                .redirectErrorStream(true)
                .start();
        try {
            waitForPort(port);

            File clientDir = tempDir.resolve("client_repo").toFile();
            new InitCommand().setDirectory(clientDir).call();
            // Isolation: disable the usercache for this test's client repo so the assertions
            // below only ever see this test's own per-repo local store, never whatever a
            // concurrently-running agent (or an earlier manual run) may have left in the real
            // shared ~/.cache/lfs / $XDG_CACHE_HOME/lfs on this host.
            Files.writeString(new File(clientDir, ".hg/hgrc").toPath(),
                    "[paths]\ndefault = http://127.0.0.1:" + port + "\n"
                            + "[experimental]\nlfs.disableusercache = true\n");
            HgRepository clientRepo = new HgRepository(clientDir);

            String derivedUrl = HgLfsManager.resolveServerUrl(clientRepo);
            assertEquals("http://127.0.0.1:" + port + "/.git/info/lfs", derivedUrl,
                    "must derive the same .git/info/lfs convention real hg's own -v log line showed live");

            String oid = sha256Hex(originalContent);
            HgLfsPointer pointer = new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, originalContent.length);
            HgLfsManager clientManager = new HgLfsManager(clientRepo.getHgDir(), clientRepo.getConfig());
            assertFalse(clientManager.isCached(pointer));

            clientManager.fetchObject(pointer, derivedUrl);

            assertTrue(clientManager.isCached(pointer));
            assertArrayEquals(originalContent, clientManager.getCachedObject(pointer),
                    "hg4j must fetch the exact bytes from the real hg serve instance via the .git/info/lfs URL");
        } finally {
            serveProcess.destroy();
            serveProcess.waitFor();
        }
    }

    /**
     * Backlog 42 sub-item 2, override direction: an explicit {@code [lfs] url} must win outright
     * over the default derivation, with no further path adjustment appended -- confirmed live
     * this session ({@code hg clone --config lfs.url=<custom>} attempts the batch call against
     * exactly {@code <custom>/objects/batch}). Verified here against a controlled mock server
     * (not real hg) since the point under test is hg4j's OWN config-precedence logic, which the
     * live confirmation already pinned down for real hg's side of the same contract.
     */
    @Test
    public void lfsUrlConfigOverridesTheDefaultGitInfoLfsDerivation(@TempDir Path tempDir) throws Exception {
        byte[] payload = "override-wins content".getBytes(StandardCharsets.UTF_8);
        String oid = sha256Hex(payload);

        HttpServer overrideServer = HttpServer.create(new InetSocketAddress(0), 0);
        int overridePort = overrideServer.getAddress().getPort();
        overrideServer.createContext("/customlfs/objects/batch", exchange -> {
            String body = "{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":" + payload.length
                    + ",\"actions\":{\"download\":{\"href\":\"http://127.0.0.1:" + overridePort + "/customlfs/blob\"}}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.git-lfs+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        overrideServer.createContext("/customlfs/blob", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        // The WRONG (default-derivation) endpoint must never be hit.
        overrideServer.createContext("/.git/info/lfs/objects/batch", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        overrideServer.start();
        try {
            File dir = tempDir.resolve("repo").toFile();
            new InitCommand().setDirectory(dir).call();
            // Isolation: disable the usercache here too (see the equivalent comment in
            // hg4jFetchesLfsBlobFromRealHgServeViaGitInfoLfsConvention) -- this test only cares
            // about which URL got hit, not usercache behavior.
            Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                    "[paths]\ndefault = http://127.0.0.1:" + overridePort + "\n"
                            + "[lfs]\nurl = http://127.0.0.1:" + overridePort + "/customlfs\n"
                            + "[experimental]\nlfs.disableusercache = true\n");
            HgRepository repo = new HgRepository(dir);

            assertEquals("http://127.0.0.1:" + overridePort + "/customlfs", HgLfsManager.resolveServerUrl(repo));

            HgLfsPointer pointer = new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, payload.length);
            byte[] resolved = HgLfsManager.resolveContent(repo, pointer.serialize(), true, "big.bin");
            assertArrayEquals(payload, resolved, "hg4j must have fetched from the override URL, not the default derivation");
        } finally {
            overrideServer.stop(0);
        }
    }

    /**
     * Backlog 42 sub-item 3: real hg's {@code [lfs] usercache} config (confirmed live this
     * session: {@code hg commit} with a custom {@code usercache} path populates the blob directly
     * at {@code <path>/<oid[0:2]>/<oid[2:]>}, no extra "lfs" segment) -- a REAL hg process
     * populates a usercache directory, then hg4j's own {@link HgLfsManager} (constructed with the
     * matching config) must read that real-hg-populated blob back, even with the per-repo local
     * store deleted, proving hg4j's usercache fallback path is byte-compatible with real hg's.
     */
    @Test
    public void hg4jReadsAUsercacheBlobRealHgPopulatedViaLfsUsercacheConfig(@TempDir Path tempDir) throws Exception {
        File usercacheDir = tempDir.resolve("shared_usercache").toFile();
        assertTrue(usercacheDir.mkdirs());

        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = all()\nusercache = " + usercacheDir.getAbsolutePath() + "\n",
                StandardOpenOption.APPEND);

        byte[] originalContent = "content real hg puts in a custom usercache dir".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(repoDir, "big.bin").toPath(), originalContent);
        HgTestUtils.hg(repoDir, "add", "big.bin");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        String oid = sha256Hex(originalContent);
        File expectedUsercacheBlob = new File(new File(usercacheDir, oid.substring(0, 2)), oid.substring(2));
        assertTrue(expectedUsercacheBlob.exists(), "sanity: real hg must have populated the configured usercache path directly");

        // Delete the per-repo local store's copy so the usercache is the ONLY place hg4j could
        // possibly find it -- proving the usercache fallback path itself, not just the local store.
        HgRepository repo = new HgRepository(repoDir);
        HgLfsManager repoOnlyManager = new HgLfsManager(repo.getHgDir());
        File localCopy = repoOnlyManager.getLocalPath(oid);
        assertTrue(localCopy.exists());
        assertTrue(localCopy.delete());

        HgLfsManager cacheAwareManager = new HgLfsManager(repo.getHgDir(), repo.getConfig());
        assertEquals(usercacheDir.getCanonicalFile(), cacheAwareManager.getUserCacheDir().getCanonicalFile());
        HgLfsPointer pointer = new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, originalContent.length);
        assertTrue(cacheAwareManager.isCached(pointer), "hg4j must recognize real hg's usercache-only blob as cached");
        assertArrayEquals(originalContent, cacheAwareManager.getCachedObject(pointer),
                "hg4j must read the exact bytes real hg wrote into the shared usercache");
        assertTrue(localCopy.exists(), "hg4j's read must have opportunistically backfilled the per-repo local store");
    }

    /**
     * Backlog 42 sub-item 3, disable direction: confirmed live this session that real hg with
     * {@code experimental.lfs.disableusercache = yes} writes ONLY to the per-repo local store and
     * never touches the usercache directory at all. hg4j's {@link HgLfsManager} constructed with
     * the same config must likewise resolve NO usercache directory (so it can never accidentally
     * write into a shared cache the user explicitly turned off).
     */
    @Test
    public void disableusercacheStopsRealHgFromPopulatingTheUsercache(@TempDir Path tempDir) throws Exception {
        File usercacheDir = tempDir.resolve("should_stay_empty").toFile();
        assertTrue(usercacheDir.mkdirs());

        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
        });
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = all()\nusercache = " + usercacheDir.getAbsolutePath()
                        + "\n[experimental]\nlfs.disableusercache = yes\n",
                StandardOpenOption.APPEND);

        Files.write(new File(repoDir, "big.bin").toPath(), "disabled usercache content".getBytes(StandardCharsets.UTF_8));
        HgTestUtils.hg(repoDir, "add", "big.bin");
        HgTestUtils.hg(repoDir, "commit", "-u", "tester", "-m", "add big lfs file");

        try (var stream = Files.list(usercacheDir.toPath())) {
            assertTrue(stream.findAny().isEmpty(), "real hg must not have written anything into the disabled usercache dir");
        }

        HgRcConfig hg4jConfig = new HgRcConfig();
        hg4jConfig.parse("[lfs]\nusercache = " + usercacheDir.getAbsolutePath()
                + "\n[experimental]\nlfs.disableusercache = yes\n");
        HgLfsManager manager = new HgLfsManager(new File(repoDir, ".hg"), hg4jConfig);
        assertNull(manager.getUserCacheDir(), "hg4j must resolve NO usercache directory when disableusercache is set, "
                + "even though [lfs] usercache also names one");
    }

    private static void waitForPort(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("hg serve never opened port " + port, last);
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
