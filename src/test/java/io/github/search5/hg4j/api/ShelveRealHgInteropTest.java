package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 백로그 23 (shelve 카테고리): shelve -> 다른 작업 -> unshelve 왕복이 실제 hg가 만든
 * shelve와 상호운용되는지 검증한다 (hg4j가 만든 shelve를 real hg가 unshelve할 수 있는지,
 * 그리고 그 반대도) -- 기존 {@code ShelveCommandTest}/{@code ShelveCommandCoverageTest}는
 * hg4j 자체 왕복만 검증했으므로 이 항목 기준으로는 "미검증"이었다.
 *
 * <p><b>2026-09-04 갱신:</b> {@code ShelveCommand.performUnshelve()}가 real hg의 실제
 * {@code _dounshelve()} 알고리즘(임시 커밋 + rebase + uncommit + strip)으로 재작성되면서,
 * 이 클래스 상단(아래)의 세 테스트는 "충돌도, 그 사이 다른 커밋도 없는 가장 단순한 왕복"만
 * 검증한다 -- 그 이후 추가된 {@code unshelveRebasesOntoAnUnrelatedInterveningCommit}/
 * {@code unshelveWithConflictingInterveningCommitPausesResolvesAndContinues}/
 * {@code unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable}가 각각 "그 사이 커밋된
 * 히스토리 위로의 실제 rebase"와 "충돌 일시정지 -&gt; 해결 -&gt; continue"/"abort" 두
 * 시나리오를 커버한다(상세는 {@link ShelveCommand}의 클래스 javadoc과 최종 보고 참고).</p>
 */
@Tag("interop")
public class ShelveRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static void configureRepoForRealHg(File repoDir) throws Exception {
        File hgrc = new File(repoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n[ui]\nusername = Test User <test@example.com>\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * hg4j가 만든 shelve를 real hg {@code hg unshelve}가 그대로 복원할 수 있는지: 파일 수정+
     * 신규 파일 추가를 hg4j로 shelve한 뒤, real hg CLI로 unshelve해서 워킹 카피 내용이
     * 정확히 복원되는지 확인한다.
     */
    @Test
    public void hg4jShelveCanBeUnshelvedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        // Dirty the working copy: modify an existing file, add a new one.
        Files.writeString(a.toPath(), "line1\nline2\n");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "brand new file\n");
        new AddCommand(repo).call();

        new ShelveCommand(repo).setName("default").call();

        // Working copy must be back to the clean c1 state after shelving.
        assertEquals("line1\n", Files.readString(a.toPath()));
        assertFalse(b.exists());

        // "shelve -> other work -> unshelve": leave an unrelated untracked file lying around in
        // between, matching the task's round-trip scenario without moving the working parent
        // (which would additionally require real hg's rebase-based unshelve path -- out of scope
        // here, see the class javadoc).
        Files.writeString(new File(repoDir, "unrelated.txt").toPath(), "other work\n");

        String unshelveOut = HgTestUtils.hg(repoDir, "unshelve");

        assertEquals("line1\nline2\n", Files.readString(a.toPath()),
                "Real hg unshelve must restore a.txt's shelved modification. Output was:\n" + unshelveOut);
        assertTrue(b.exists(), "Real hg unshelve must restore the newly-added b.txt. Output was:\n" + unshelveOut);
        assertEquals("brand new file\n", Files.readString(b.toPath()));

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show as modified after unshelve, got:\n" + status);
        assertTrue(status.contains("A b.txt"), "b.txt must show as added after unshelve, got:\n" + status);

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyOut.contains("integrity error"), "Repo must remain verify-clean: " + verifyOut);
    }

    /**
     * 그 반대 방향: real hg {@code hg shelve}가 만든 shelve를 hg4j의 {@code
     * ShelveCommand.setUnshelve(true)}가 복원할 수 있는지.
     */
    @Test
    public void realHgShelveCanBeUnshelvedByHg4j(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c1");

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\nline2\n");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "brand new file\n");
        HgTestUtils.hg(repoDir, "add", "b.txt");

        HgTestUtils.hg(repoDir, "shelve", "-n", "default");

        // Working copy must be back to the clean c1 state after real hg's own shelve.
        assertEquals("line1\n", Files.readString(a.toPath()));
        assertFalse(b.exists());

        // "shelve -> other work -> unshelve" (see the other direction's test for why this stays
        // an untracked, unrelated file rather than moving the working parent).
        Files.writeString(new File(repoDir, "unrelated.txt").toPath(), "other work\n");

        new ShelveCommand(repo).setName("default").setUnshelve(true).call();

        assertEquals("line1\nline2\n", Files.readString(a.toPath()),
                "hg4j unshelve must restore a.txt's real-hg-shelved modification");
        assertTrue(b.exists(), "hg4j unshelve must restore the newly-added b.txt");
        assertEquals("brand new file\n", Files.readString(b.toPath()));

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show as modified after hg4j unshelve, got:\n" + status);
        assertTrue(status.contains("A b.txt"), "b.txt must show as added after hg4j unshelve, got:\n" + status);
    }

    /**
     * A shelved file REMOVAL (as opposed to add/modify) round trip, hg4j-made shelve unshelved by
     * real hg -- removals take a different code path in both {@code performShelve()} (no filelog
     * entry at all, represented purely via the shelved commit's manifest omitting the path) and
     * the manifest-diff-based no-{@code .state} reconstruction real hg's own shelves need.
     */
    @Test
    public void hg4jShelveOfARemovedFileCanBeUnshelvedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        new RemoveCommand(repo).setFile("a.txt").call();
        Files.deleteIfExists(a.toPath());

        new ShelveCommand(repo).setName("default").call();

        // Working copy must be back to the clean c1 state (a.txt restored) after shelving.
        assertTrue(a.exists(), "a.txt must be restored to disk once its removal is shelved away");
        assertEquals("line1\n", Files.readString(a.toPath()));

        HgTestUtils.hg(repoDir, "unshelve");

        assertFalse(a.exists(), "Real hg unshelve must re-apply the shelved removal of a.txt");
        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("R a.txt"), "a.txt must show as removed after unshelve, got:\n" + status);
    }

    /**
     * The first scenario hg4j's unshelve genuinely needs the real rebase-based algorithm for: an
     * UNRELATED commit lands on the working directory parent between shelving and unshelving, so
     * the restored shelve commit's rebase step actually does something (as opposed to the no-op
     * "rebase onto the parent it's already on" every other test in this class exercises).
     */
    @Test
    public void unshelveRebasesOntoAnUnrelatedInterveningCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0 base").call();

        Files.writeString(a.toPath(), "line1\nline2\n");
        new ShelveCommand(repo).setName("default").call();
        assertEquals("line1\n", Files.readString(a.toPath()), "working copy must be back to c0 after shelving");

        // An unrelated commit lands on top of c0 before unshelving.
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "unrelated new file\n");
        new AddCommand(repo).call();
        byte[] interveningCommit = new CommitCommand(repo).setAuthor("T").setMessage("c1 unrelated").call();

        new ShelveCommand(repo).setName("default").setUnshelve(true).call();

        // The shelved change must land on top of the intervening commit, not vanish or silently
        // reset it: b.txt (added by c1) is still present, a.txt carries the shelved modification,
        // and the working directory parent stays exactly c1 (unshelve only ever adds PENDING
        // changes on top -- it must never leave a new commit checked out).
        assertEquals("line1\nline2\n", Files.readString(a.toPath()));
        assertTrue(b.exists(), "the intervening commit's own file must survive unshelving");
        assertEquals("unrelated new file\n", Files.readString(b.toPath()));
        assertEquals(NodeIdUtil.toHex(interveningCommit), NodeIdUtil.toHex(repo.getDirstate().getParent1()),
                "working directory parent must remain the intervening commit after unshelve");

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show as modified after unshelve, got:\n" + status);
        assertFalse(status.contains("b.txt"), "b.txt is already committed and untouched, must not appear in status, got:\n" + status);

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyOut.contains("integrity error"), "Repo must remain verify-clean: " + verifyOut);

        // The throwaway restore/rebase commit(s) this unshelve built internally must be gone
        // entirely -- verified against real hg CLI (2026-09-04): real hg's own unshelve builds
        // them inside a transaction it then aborts, so nothing (not even a hidden/obsolete
        // revision) is left behind. Only c0 and c1 remain.
        String logAll = HgTestUtils.hg(repoDir, "log", "--template", "{rev} ");
        assertEquals("1 0", logAll.trim(), "no extra revisions may remain after unshelve completes");
    }

    /**
     * The second new scenario: the intervening commit CONFLICTS with the shelved change (same
     * line, different edits) -- unshelve's rebase step must pause exactly like a real
     * {@code hg rebase} conflict would, with real-hg-compatible conflict markers and
     * {@code .hg/merge/state2} bookkeeping a real {@code hg resolve --list} can read, and
     * {@link ShelveCommand#unshelveContinue()} must complete it after manual resolution -- driven
     * from a FRESH {@link ShelveCommand} instance, proving the paused state is fully persisted to
     * disk rather than held only in the instance that hit the conflict.
     */
    @Test
    public void unshelveWithConflictingInterveningCommitPausesResolvesAndContinues(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File f = new File(repoDir, "f.txt");
        Files.writeString(f.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0 base").call();

        Files.writeString(f.toPath(), "line1-shelved\n");
        new ShelveCommand(repo).setName("default").call();
        assertEquals("line1\n", Files.readString(f.toPath()));

        // A conflicting commit changes the exact same line differently.
        Files.writeString(f.toPath(), "line1-intervening\n");
        new CommitCommand(repo).setAuthor("T").setMessage("c1 conflicting").call();

        ShelveCommand unshelveCmd = new ShelveCommand(repo).setName("default").setUnshelve(true);
        HgMergeConflictException ex = assertThrows(HgMergeConflictException.class, unshelveCmd::call,
                "a genuine same-line conflict must pause unshelve instead of silently overwriting it");
        assertEquals(List.of("f.txt"), ex.getConflictPaths());

        // Byte-for-byte match against real hg 7.2's own default internal:merge conflict markers
        // (already verified live against real hg in RebaseRealHgInteropTest -- unshelve's rebase
        // step reuses that exact same RebaseCommand machinery).
        assertEquals("<<<<<<< dest\nline1-intervening\n=======\nline1-shelved\n>>>>>>> source\n",
                Files.readString(f.toPath()));

        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U f.txt", resolveList.trim());
        String statusDuring = HgTestUtils.hg(repoDir, "status");
        assertTrue(statusDuring.contains("M f.txt"), "f.txt must show as modified-in-progress: " + statusDuring);

        // Resolve exactly like a user driving `hg resolve` would.
        Files.writeString(f.toPath(), "line1-intervening\nline1-shelved\n");
        HgTestUtils.hg(repoDir, "resolve", "--mark", "f.txt");

        new ShelveCommand(repo).unshelveContinue();

        assertEquals("line1-intervening\nline1-shelved\n", Files.readString(f.toPath()));
        assertEquals("", HgTestUtils.hg(repoDir, "resolve", "--list").trim(), "no unresolved files must remain");

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M f.txt"), "f.txt must show as modified after unshelve --continue, got:\n" + status);

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyOut.contains("integrity error"), "Repo must remain verify-clean: " + verifyOut);

        // No trace of the shelve/unshelve scaffolding remains, and no extra revisions were left
        // behind.
        assertFalse(new File(repo.getHgDir(), "shelved/default.state").exists());
        assertFalse(new File(repo.getHgDir(), "rebasestate-hg4j").exists());
        assertFalse(new File(repo.getHgDir(), "shelvedstate-hg4j").exists());
        assertEquals("1 0", HgTestUtils.hg(repoDir, "log", "--template", "{rev} ").trim());
    }

    /**
     * The third new scenario: {@link ShelveCommand#unshelveAbort()} must cleanly discard a
     * paused-on-conflict unshelve and restore the working copy/dirstate to exactly their
     * pre-unshelve state -- unlike a completed unshelve, the shelve itself must remain untouched
     * so a later unshelve attempt can still use it (mirrors real hg's own {@code hg unshelve
     * --abort}, which never deletes the shelf).
     */
    @Test
    public void unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File f = new File(repoDir, "f.txt");
        Files.writeString(f.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0 base").call();

        Files.writeString(f.toPath(), "line1-shelved\n");
        new ShelveCommand(repo).setName("default").call();

        Files.writeString(f.toPath(), "line1-intervening\n");
        byte[] interveningCommit = new CommitCommand(repo).setAuthor("T").setMessage("c1 conflicting").call();

        ShelveCommand unshelveCmd = new ShelveCommand(repo).setName("default").setUnshelve(true);
        assertThrows(HgMergeConflictException.class, unshelveCmd::call);

        // Abort from a fresh instance -- same persisted-state contract as unshelveContinue().
        new ShelveCommand(repo).unshelveAbort();

        // Working copy/dirstate must be back to exactly the pre-unshelve (post-intervening-commit)
        // state.
        assertEquals("line1-intervening\n", Files.readString(f.toPath()));
        assertEquals(NodeIdUtil.toHex(interveningCommit), NodeIdUtil.toHex(repo.getDirstate().getParent1()));
        assertEquals("", HgTestUtils.hg(repoDir, "status").trim());
        assertEquals("", HgTestUtils.hg(repoDir, "resolve", "--list").trim());

        // The shelve itself must remain untouched/usable, unlike a completed unshelve.
        assertTrue(new File(repo.getHgDir(), "shelved/default.state").exists());
        assertTrue(new File(repo.getHgDir(), "shelved/default.hg").exists());
        assertFalse(new File(repo.getHgDir(), "rebasestate-hg4j").exists());
        assertFalse(new File(repo.getHgDir(), "shelvedstate-hg4j").exists());

        // No extra revisions left behind either.
        assertEquals("1 0", HgTestUtils.hg(repoDir, "log", "--template", "{rev} ").trim());
        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyOut.contains("integrity error"), "Repo must remain verify-clean: " + verifyOut);

        // The shelve must still be usable: retrying unshelve reproduces the exact same conflict
        // rather than erroring out over stale/corrupted state.
        HgMergeConflictException retryEx = assertThrows(HgMergeConflictException.class,
                () -> new ShelveCommand(repo).setName("default").setUnshelve(true).call());
        assertEquals(List.of("f.txt"), retryEx.getConflictPaths());
        new ShelveCommand(repo).unshelveAbort();
    }
}
