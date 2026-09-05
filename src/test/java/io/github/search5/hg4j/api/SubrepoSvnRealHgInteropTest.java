package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.submodule.HgSubrepoEntry;
import io.github.search5.hg4j.submodule.HgSubrepoParser;
import io.github.search5.hg4j.submodule.SvnSubrepoUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog 41 (SVN subrepo support): {@code [svn]}-prefixed {@code .hgsub}/{@code .hgsubstate}
 * commit/update handling verified side-by-side against a real local Subversion repository
 * ({@code svnadmin create} + {@code file://} access) AND real Mercurial CLI (7.2), mirroring the
 * verification style already established for the git-typed sibling in
 * {@link SubrepoRealHgInteropTest}.
 *
 * <p>Ground truth for every scenario below was first reproduced live against real hg 7.2's own
 * installed {@code mercurial/subrepo.py} ({@code svnsubrepo} class) plus svn 1.14 CLI in this
 * sandbox (see the class/method javadocs on {@link SvnSubrepoUtil} for what was read/observed),
 * before porting the exact same behavior into hg4j.
 */
@Tag("interop")
public class SubrepoSvnRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(HgTestUtils.isSvnInstalled(), "svn/svnadmin is not installed. Skipping.");
    }

    /** Scenario 1: hg4j's {@link HgSubrepoParser} correctly parses a real-hg-generated {@code
     * .hgsub}/{@code .hgsubstate} pair declaring a {@code [svn]} subrepo. */
    @Test
    public void hg4jParsesRealHgGeneratedSvnHgsubAndHgsubstate(@TempDir Path tempDir) throws Exception {
        File svnRepoDir = tempDir.resolve("svnrepo").toFile();
        String svnUrl = HgTestUtils.createSvnRepo(svnRepoDir);

        File parentDir = tempDir.resolve("parent").toFile();
        HgTestUtils.nativeRepo(parentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(parentDir, "add");
        HgTestUtils.hg(parentDir, "commit", "-u", "T", "-m", "parent init");

        File subDir = new File(parentDir, "sub");
        HgTestUtils.svn(parentDir, "checkout", "-q", svnUrl, subDir.getAbsolutePath());
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello from real svn sub");
        HgTestUtils.svn(subDir, "add", "hello.txt");
        String commitOut = HgTestUtils.svn(subDir, "commit", "-m", "svn c1");
        assertTrue(commitOut.contains("Committed revision 1."), "svn commit oracle: " + commitOut);
        HgTestUtils.svn(subDir, "update", "-r", "1");

        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        HgTestUtils.hg(parentDir, "add", ".hgsub");
        HgTestUtils.hgSvnAllowed(parentDir, "commit", "-u", "T", "-m", "add svn subrepo");

        byte[] hgsub = Files.readAllBytes(new File(parentDir, ".hgsub").toPath());
        byte[] hgsubstate = Files.readAllBytes(new File(parentDir, ".hgsubstate").toPath());
        assertEquals("1 sub\n", new String(hgsubstate, StandardCharsets.UTF_8),
                "오라클: 실제 hg가 svn 서브저장소의 .hgsubstate에 plain revision number를 기록해야 함");

        Map<String, HgSubrepoEntry> parsed = HgSubrepoParser.parseSubrepositories(hgsub, hgsubstate);
        assertEquals(1, parsed.size());
        HgSubrepoEntry entry = parsed.get("sub");
        assertNotNull(entry);
        assertEquals(svnUrl, entry.getSourceUrl());
        assertEquals("1", entry.getRevision());
        assertTrue(entry.isSvn(), "hg4j 파서가 [svn] prefix를 인식해야 함");
        assertFalse(entry.isGit());
    }

    /** Scenario 2: hg4j's {@link CommitCommand} auto-generates a {@code .hgsubstate} for a svn
     * subrepo that real hg CLI reads back identically, and that hg4j's own byte-for-byte output
     * matches what real hg itself would have written for the same underlying svn state. */
    @Test
    public void hg4jCommitAutoGeneratesSvnHgsubstateRealHgUnderstands(@TempDir Path tempDir) throws Exception {
        File svnRepoDir = tempDir.resolve("svnrepo").toFile();
        String svnUrl = HgTestUtils.createSvnRepo(svnRepoDir);

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        File subDir = new File(parentDir, "sub");
        HgTestUtils.svn(parentDir, "checkout", "-q", svnUrl, subDir.getAbsolutePath());
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello from hg4j sub");
        HgTestUtils.svn(subDir, "add", "hello.txt");
        String commitOut = HgTestUtils.svn(subDir, "commit", "-m", "svn c1");
        assertTrue(commitOut.contains("Committed revision 1."), commitOut);
        HgTestUtils.svn(subDir, "update", "-r", "1");

        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        new AddCommand(parentRepo).call(); // .hgsub만 add -- .hgsubstate는 절대 손으로 add하지 않는다.

        assertFalse(new File(parentDir, ".hgsubstate").exists(), "커밋 전에는 아직 .hgsubstate가 없어야 함");

        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add svn subrepo").call();

        File hgsubstateFile = new File(parentDir, ".hgsubstate");
        assertTrue(hgsubstateFile.exists(), ".hgsub만 add한 뒤 커밋해도 .hgsubstate가 자동으로 생성되어야 함");
        String content = Files.readString(hgsubstateFile.toPath(), StandardCharsets.UTF_8).trim();
        assertEquals("1 sub", content, "hg4j가 기록한 svn 서브저장소 리비전이 실제 checked-out 리비전과 일치해야 함");

        // 실제 hg CLI로도 인식되는지 확인.
        String status = HgTestUtils.hgSvnAllowed(parentDir, "status");
        assertEquals("", status, "커밋 직후에는 실제 hg 기준으로도 워킹 카피가 clean해야 함: " + status);

        String files = HgTestUtils.hg(parentDir, "files");
        assertTrue(files.contains(".hgsubstate") && files.contains(".hgsub"),
                "실제 hg files에 .hgsub/.hgsubstate가 추적된 파일로 나와야 함: " + files);

        String catState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate").trim();
        assertEquals("1 sub", catState, "실제 hg가 읽는 hg4j의 .hgsubstate 내용이 정확히 일치해야 함");
    }

    /** Scenario 3: a svn subrepo with uncommitted local changes blocks the parent commit unless
     * {@code --subrepos} is used, exactly matching real hg's error message shape; with {@code
     * --subrepos}, hg4j recursively runs {@code svn commit} and records the new revision. */
    @Test
    public void hg4jCommitBlocksThenRecursivelyCommitsDirtySvnSubrepoMatchingRealHg(@TempDir Path tempDir) throws Exception {
        // Two independent svn repositories (one per side) -- svn revision numbers are global per
        // repository, so sharing a single one across the oracle and the hg4j reproduction would
        // make their expected revision numbers diverge from (and collide on already-versioned
        // paths with) each other.
        String svnUrl = HgTestUtils.createSvnRepo(tempDir.resolve("svnrepo-oracle").toFile());

        // --- 오라클: 실제 hg ---
        File realParentDir = tempDir.resolve("real-parent").toFile();
        HgTestUtils.nativeRepo(realParentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(realParentDir, "add");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "parent init");
        File realSubDir = new File(realParentDir, "sub");
        HgTestUtils.svn(realParentDir, "checkout", "-q", svnUrl, realSubDir.getAbsolutePath());
        Files.writeString(new File(realSubDir, "hello.txt").toPath(), "hello");
        HgTestUtils.svn(realSubDir, "add", "hello.txt");
        HgTestUtils.svn(realSubDir, "commit", "-m", "svn c1");
        HgTestUtils.svn(realSubDir, "update", "-r", "1");
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        HgTestUtils.hg(realParentDir, "add", ".hgsub");
        HgTestUtils.hgSvnAllowed(realParentDir, "commit", "-u", "T", "-m", "add svn subrepo");

        Files.writeString(new File(realSubDir, "hello.txt").toPath(), "modified (uncommitted)");
        AssertionError oracleAbort = assertThrows(AssertionError.class,
                () -> HgTestUtils.hgSvnAllowed(realParentDir, "commit", "-u", "T", "-m", "should fail"),
                "오라클: 재귀 플래그 없이 커밋하면 실제 hg도 거부해야 함");
        assertTrue(oracleAbort.getMessage().contains("uncommitted changes in subrepository")
                && oracleAbort.getMessage().contains("sub"), oracleAbort.getMessage());

        HgTestUtils.hgSvnAllowed(realParentDir, "commit", "-S", "-u", "T", "-m", "bump sub");
        String oracleHgsubstate = Files.readString(new File(realParentDir, ".hgsubstate").toPath()).trim();
        assertEquals("2 sub", oracleHgsubstate, "오라클: -S 재귀 커밋 후 svn 서브저장소가 리비전 2로 갱신되어야 함");

        // --- hg4j 재현 (별도의 독립적인 svn 저장소 사용 -- 위 설명 참고) ---
        String hg4jSvnUrl = HgTestUtils.createSvnRepo(tempDir.resolve("svnrepo-hg4j").toFile());

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        File subDir = new File(parentDir, "sub");
        HgTestUtils.svn(parentDir, "checkout", "-q", hg4jSvnUrl, subDir.getAbsolutePath());
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello");
        HgTestUtils.svn(subDir, "add", "hello.txt");
        HgTestUtils.svn(subDir, "commit", "-m", "svn c1 (hg4j copy)");
        HgTestUtils.svn(subDir, "update", "-r", "1");
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + hg4jSvnUrl + "\n");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add svn subrepo").call();

        Files.writeString(new File(subDir, "hello.txt").toPath(), "modified (uncommitted)");
        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("should fail").call());
        assertTrue(ex.getMessage().contains("uncommitted changes in subrepository") && ex.getMessage().contains("sub"),
                "실제 hg의 abort 메시지 형태를 따라야 함: " + ex.getMessage());

        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("bump sub").setSubrepos(true).call();
        String hg4jHgsubstate = Files.readString(new File(parentDir, ".hgsubstate").toPath()).trim();
        assertEquals("2 sub", hg4jHgsubstate,
                "hg4j도 -S 상당 재귀 커밋 후 svn 서브저장소 리비전을 갱신해야 함 (오라클과 동일 패턴: 1 -> 2)");

        // 실제 hg CLI로 hg4j 결과물을 읽었을 때도 인식되어야 한다.
        assertEquals("", HgTestUtils.hgSvnAllowed(parentDir, "status"));
        String catState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate").trim();
        assertEquals("2 sub", catState);
    }

    /** Scenario 4: {@link UpdateCommand} checks a svn subrepo out to whatever revision is pinned
     * by the target parent revision, round-tripping between two different pins exactly like real
     * hg's own {@code hg update} would (oracle: real hg CLI builds both parent commits). */
    @Test
    public void hg4jUpdateCheckoutMatchesRealHgAcrossSvnRevisionPins(@TempDir Path tempDir) throws Exception {
        File svnRepoDir = tempDir.resolve("svnrepo").toFile();
        String svnUrl = HgTestUtils.createSvnRepo(svnRepoDir);

        File seedDir = tempDir.resolve("svn-seed").toFile();
        HgTestUtils.svn(tempDir.toFile(), "checkout", "-q", svnUrl, seedDir.getAbsolutePath());
        Files.writeString(new File(seedDir, "hello.txt").toPath(), "v1");
        HgTestUtils.svn(seedDir, "add", "hello.txt");
        HgTestUtils.svn(seedDir, "commit", "-m", "svn v1"); // rev 1
        Files.writeString(new File(seedDir, "hello.txt").toPath(), "v2");
        HgTestUtils.svn(seedDir, "commit", "-m", "svn v2"); // rev 2

        File parentDir = tempDir.resolve("parent").toFile();
        HgTestUtils.nativeRepo(parentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(parentDir, "add");
        HgTestUtils.hg(parentDir, "commit", "-u", "T", "-m", "parent init");

        File subDir = new File(parentDir, "sub");
        HgTestUtils.svn(parentDir, "checkout", "--force", svnUrl + "@1", subDir.getAbsolutePath());
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        HgTestUtils.hg(parentDir, "add", ".hgsub");
        HgTestUtils.hgSvnAllowed(parentDir, "commit", "-u", "T", "-m", "pin sub@v1");
        String parentPinV1 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");

        HgTestUtils.svn(subDir, "update", "-r", "2");
        HgTestUtils.hgSvnAllowed(parentDir, "commit", "-u", "T", "-m", "pin sub@v2");
        String parentPinV2 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals("v2", Files.readString(new File(subDir, "hello.txt").toPath()));

        HgRepository parentRepo = new HgRepository(parentDir);

        new UpdateCommand(parentRepo).setRevision(parentPinV1).setForce(true).call();
        assertEquals("v1", Files.readString(new File(subDir, "hello.txt").toPath()),
                "hg4j update로 svn v1 pin 리비전으로 돌아가면 서브저장소 내용도 v1이어야 함");

        new UpdateCommand(parentRepo).setRevision(parentPinV2).setForce(true).call();
        assertEquals("v2", Files.readString(new File(subDir, "hello.txt").toPath()),
                "hg4j update로 svn v2 pin 리비전으로 가면 서브저장소 내용도 v2여야 함");
    }

    /** Scenario 5: a {@code [svn]} subrepo declared in {@code .hgsub} but never actually checked
     * out locally aborts the WHOLE parent commit with a raw svn CLI error surfaced through hg4j
     * -- real hg's svn subrepo has no null-revision auto-vivify fallback the way its hg-typed
     * sibling does (oracle: real hg CLI reproduces the exact same abort). */
    @Test
    public void hg4jCommitAbortsWhenSvnSubrepoDeclaredButNotCheckedOutMatchingRealHg(@TempDir Path tempDir) throws Exception {
        File svnRepoDir = tempDir.resolve("svnrepo").toFile();
        String svnUrl = HgTestUtils.createSvnRepo(svnRepoDir);

        // --- 오라클: 실제 hg ---
        File realParentDir = tempDir.resolve("real-parent").toFile();
        HgTestUtils.nativeRepo(realParentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(realParentDir, "add");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "parent init");
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        HgTestUtils.hg(realParentDir, "add", ".hgsub");
        AssertionError oracleAbort = assertThrows(AssertionError.class,
                () -> HgTestUtils.hgSvnAllowed(realParentDir, "commit", "-u", "T", "-m", "add svn subrepo not checked out"));
        assertTrue(oracleAbort.getMessage().toLowerCase().contains("not a working copy")
                        || oracleAbort.getMessage().contains("svn:"),
                "오라클: svn CLI 자체 오류가 그대로 abort 사유로 노출되어야 함: " + oracleAbort.getMessage());
        assertFalse(new File(realParentDir, ".hgsubstate").exists());

        // --- hg4j 재현 ---
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        new AddCommand(parentRepo).call();

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add svn subrepo not checked out").call());
        assertNotNull(ex.getMessage());
        assertFalse(new File(parentDir, ".hgsubstate").exists(),
                "hg4j도 커밋 전체가 abort되어 .hgsubstate가 생성되면 안 됨");
    }

    /** Direct unit-style coverage of {@link SvnSubrepoUtil} against a real local svn repo,
     * exercising the read (status/info) and write (checkout/commit) primitives CommitCommand/
     * UpdateCommand build on top of. */
    @Test
    public void svnSubrepoUtilReadAndWritePrimitivesMatchRealSvnCli(@TempDir Path tempDir) throws Exception {
        File svnRepoDir = tempDir.resolve("svnrepo").toFile();
        String svnUrl = HgTestUtils.createSvnRepo(svnRepoDir);

        File wcDir = tempDir.resolve("wc").toFile();
        assertFalse(SvnSubrepoUtil.isSvnCheckout(wcDir));

        SvnSubrepoUtil.checkoutHead(tempDir.toFile(), svnUrl, wcDir);
        assertTrue(SvnSubrepoUtil.isSvnCheckout(wcDir));
        SvnSubrepoUtil.WcStatus clean = SvnSubrepoUtil.wcChanged(wcDir);
        assertFalse(clean.changed);
        assertFalse(SvnSubrepoUtil.isDirty(wcDir, "0", true));

        Files.writeString(new File(wcDir, "a.txt").toPath(), "content");
        HgTestUtils.svn(wcDir, "add", "a.txt");
        assertTrue(SvnSubrepoUtil.wcChanged(wcDir).changed);
        assertTrue(SvnSubrepoUtil.isDirty(wcDir, "0", true));

        String newRev = SvnSubrepoUtil.commit(wcDir, "add a.txt", svnUrl);
        assertEquals("1", newRev);
        assertFalse(SvnSubrepoUtil.isDirty(wcDir, "1", true));
        String[] revs = SvnSubrepoUtil.wcRevs(wcDir);
        assertEquals("1", revs[0]);
        assertEquals("1", revs[1]);
        assertEquals("1", SvnSubrepoUtil.basestate(wcDir, svnUrl));

        // A second commit, then pin a checkout back to revision 1 via get().
        Files.writeString(new File(wcDir, "a.txt").toPath(), "content v2");
        String newRev2 = SvnSubrepoUtil.commit(wcDir, "modify a.txt", svnUrl);
        assertEquals("2", newRev2);

        File pinnedDir = tempDir.resolve("pinned").toFile();
        SvnSubrepoUtil.get(tempDir.toFile(), svnUrl, "1", pinnedDir);
        assertEquals("content", Files.readString(new File(pinnedDir, "a.txt").toPath()));

        SvnSubrepoUtil.get(tempDir.toFile(), svnUrl, "2", pinnedDir);
        assertEquals("content v2", Files.readString(new File(pinnedDir, "a.txt").toPath()));
    }

    /**
     * Backlog 41 bugfix coverage: {@link SubrepoCommand}'s {@code init} action previously did
     * not understand the {@code [svn]}/{@code [git]} {@code .hgsub} prefixes at all (see the
     * class comment on {@code SubrepoCommand.checkoutGitEntry}/{@code checkoutSvnEntry}) -- it
     * would try to {@code hg clone} the literal prefixed string, and its {@code .hgsubstate}
     * revision parsing assumed a fixed 40-char hash width that a plain svn revision number
     * doesn't have. Verifies both are now fixed: a {@code [svn]}-declared entry pinned to a real
     * svn revision is checked out via {@code svn checkout --force}, matching the content at that
     * exact revision.
     */
    @Test
    public void subrepoCommandInitChecksOutPinnedSvnRevision(@TempDir Path tempDir) throws Exception {
        String svnUrl = HgTestUtils.createSvnRepo(tempDir.resolve("svnrepo").toFile());
        File seedDir = tempDir.resolve("svn-seed").toFile();
        HgTestUtils.svn(tempDir.toFile(), "checkout", "-q", svnUrl, seedDir.getAbsolutePath());
        Files.writeString(new File(seedDir, "hello.txt").toPath(), "v1");
        HgTestUtils.svn(seedDir, "add", "hello.txt");
        HgTestUtils.svn(seedDir, "commit", "-m", "svn v1"); // rev 1
        Files.writeString(new File(seedDir, "hello.txt").toPath(), "v2");
        HgTestUtils.svn(seedDir, "commit", "-m", "svn v2"); // rev 2

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = [svn]" + svnUrl + "\n");
        Files.writeString(new File(parentDir, ".hgsubstate").toPath(), "1 sub\n");

        new SubrepoCommand(parentRepo).setAction("init").call();

        File subDir = new File(parentDir, "sub");
        assertTrue(SvnSubrepoUtil.isSvnCheckout(subDir), "SubrepoCommand가 svn 서브저장소를 체크아웃해야 함");
        assertEquals("v1", Files.readString(new File(subDir, "hello.txt").toPath()),
                "체크아웃된 내용이 .hgsubstate에 pin된 리비전(1)과 일치해야 함, HEAD(리비전 2)가 아니라");
    }

    /** Same bugfix, git side: {@code SubrepoCommand} previously had no {@code [git]} dispatch at
     * all despite CommitCommand/UpdateCommand/MergeCommand/CloneCommand fully supporting it. */
    @Test
    public void subrepoCommandInitChecksOutPinnedGitRevision(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isGitInstalled(), "git이 설치되어 있지 않습니다. 건너뜁니다.");

        File gitSrc = tempDir.resolve("git-src").toFile();
        gitSrc.mkdirs();
        HgTestUtils.git(gitSrc, "init", "-q", "-b", "master", ".");
        Files.writeString(new File(gitSrc, "g.txt").toPath(), "v1");
        HgTestUtils.git(gitSrc, "add", "g.txt");
        HgTestUtils.git(gitSrc, "commit", "-q", "-m", "git v1");
        String sha1 = HgTestUtils.git(gitSrc, "rev-parse", "HEAD");
        Files.writeString(new File(gitSrc, "g.txt").toPath(), "v2");
        HgTestUtils.git(gitSrc, "commit", "-q", "-a", "-m", "git v2");

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSrc.getAbsolutePath() + "\n");
        Files.writeString(new File(parentDir, ".hgsubstate").toPath(), sha1 + " gitsub\n");

        new SubrepoCommand(parentRepo).setAction("init").call();

        File subDir = new File(parentDir, "gitsub");
        assertTrue(new File(subDir, ".git").exists(), "SubrepoCommand가 git 서브저장소를 clone해야 함");
        assertEquals("v1", Files.readString(new File(subDir, "g.txt").toPath()),
                "체크아웃된 내용이 .hgsubstate에 pin된 커밋(v1)과 일치해야 함, HEAD(v2)가 아니라");
    }
}
