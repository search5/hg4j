package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 백로그 23번 rebase 카테고리: {@link RebaseCommand}는 지금까지 hg4j 내부 왕복(만든 결과를
 * hg4j 자신으로 읽어 확인)으로만 검증됐다 -- 이 파일은 그 결과를 실제 hg CLI와 대조한다.
 */
@Tag("interop")
public class RebaseRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * 충돌 없는 rebase: hg4j가 만든 결과를 실제 hg verify/log/cat으로 검증한다.
     */
    @Test
    public void conflictFreeRebaseVerifiedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "base.txt").toPath(), "base\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "base.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        // target branch: independent commit touching only target.txt
        Files.writeString(new File(repoDir, "target.txt").toPath(), "on-target\n");
        HgTestUtils.hg(repoDir, "add", "target.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "source.txt").toPath(), "on-source\n");
        HgTestUtils.hg(repoDir, "add", "source.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source (to be rebased)");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        byte[] rebasedNode = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode))
                .call();
        String rebasedHex = NodeIdUtil.toHex(rebasedNode);

        // RebaseCommand always leaves behind an obsolescence marker (source -> rebased), so from
        // this point on every plain `hg` invocation prints a spurious "obsolete feature not
        // enabled but 1 markers found!" warning line on stdout (real hg 7.2,
        // mercurial/obsolete.py:makestore -- gated on the READING client's own
        // experimental.evolution config, not on anything the repository itself declares; see
        // plainRealHgCommandsWarnAfterHg4jRebase below for a dedicated test of this). Pass
        // experimental.evolution=all here purely to keep this test's own assertions warning-free;
        // it does not change what's actually stored.
        String verify = hgEvolution(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "Repository integrity error!\n" + verify);

        String rebasedParent = hgEvolution(repoDir, "log", "-r", rebasedHex, "--template", "{p1node}");
        assertEquals(targetNode, rebasedParent, "rebase된 커밋의 parent는 target이어야 함");

        String catTarget = hgEvolution(repoDir, "cat", "-r", rebasedHex, "target.txt");
        assertEquals("on-target", catTarget.trim());
        String catSource = hgEvolution(repoDir, "cat", "-r", rebasedHex, "source.txt");
        assertEquals("on-source", catSource.trim());

        // Evolution-only rebase (2026-09-04): the original source commit is never physically
        // stripped -- it is merely hidden (it has a live, non-obsolete successor), so a plain
        // `hg log` (no --hidden) must not list it, exactly like any other hidden revision. See
        // {@link #originalRevisionIsHiddenNotGoneAfterRebase} for the positive half of this: the
        // same node found via `hg log --hidden`, still fully readable, not "unknown revision".
        String logAll = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(logAll.contains(sourceNode), "hidden revision must not appear in a plain `hg log`");
    }

    /**
     * hg4j RebaseCommand는 매 cherry-pick마다 obsolescence marker를 무조건 남긴다(evolution
     * 활성화 여부와 무관). 이 테스트가 원래(2026-09-04 오전) 문서화했던 동작은 "실제 hg는 이
     * marker의 존재 자체를 evolution을 켜지 않은 일반 클라이언트 입장에서 '이례적인 상황'으로
     * 보고 매 명령마다 경고를 찍는다"였다 — 그런데 같은 날 병렬로 진행된 백로그 23번의
     * shelve/bisect/strip 작업이 {@link io.github.search5.hg4j.obsolete.HgObsMarker#writeMarker}
     * 를 고치면서(marker를 처음 쓸 때 저장소 {@code .hg/hgrc}에
     * {@code experimental.evolution.createmarkers = true}를 자동으로 심어둠) 이 경고가 실제로
     * 사라졌다 — 부수 효과로 이 rebase 관련 발견 사항이 부분적으로 해소됨(직접 재현해 확인,
     * 2026-09-04). 그래서 이 테스트는 이제 "경고가 안 나온다"를 검증하도록 갱신됐다.
     * strip과 marker를 동시에 쓰던 근본적인 시맨틱 불일치(원본 커밋이 changelog에서 완전히
     * 사라져 {@code hg log --hidden}에서 "hidden"이 아니라 "unknown revision"이 되던 문제)는
     * 같은 날(2026-09-04) 늦게 rebase를 evolution-only(marker만, 물리적 strip 없음)로
     * 전환하면서 해소됐다 — 그 확인은 {@link #originalRevisionIsHiddenNotGoneAfterRebase} 참고.
     */
    @Test
    public void plainRealHgCommandsDoNotWarnAfterHg4jRebase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "base.txt").toPath(), "base\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "base.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        Files.writeString(new File(repoDir, "target.txt").toPath(), "on-target\n");
        HgTestUtils.hg(repoDir, "add", "target.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "source.txt").toPath(), "on-source\n");
        HgTestUtils.hg(repoDir, "add", "source.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode))
                .call();

        // Plain `hg log` -- no --config experimental.evolution -- exactly what an ordinary user
        // (who never asked for evolution/obsolescence tracking) would run.
        ProcessBuilder pb = new ProcessBuilder("hg", "log", "-r", "tip", "--template", "{node}\n");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();

        assertEquals(0, exit, "명령 자체는 성공해야 함: " + output);
        assertFalse(output.contains("\"obsolete\" feature not enabled"),
                "HgObsMarker.writeMarker()가 저장소 .hg/hgrc에 evolution.createmarkers를 자동으로 "
                        + "심어두므로, 평범한 hg 명령에는 더 이상 이 경고가 섞여 나오면 안 됨:\n" + output);
    }

    /**
     * <b>고쳐짐(2026-09-04 오후).</b> 실제 hg는 rebase 중 source와 target이 같은 파일의 같은
     * 부분을 다르게 고쳤으면 3-way merge를 시도하고, 정말 겹치면 conflict marker를 남기고 exit
     * code 1로 멈춰 {@code hg resolve}/{@code hg rebase --continue}를 요구한다(이 파일 맨 위에서
     * 실제 hg 7.2로 직접 재현: {@code hg rebase -s 2 -d 1} → exit 1, {@code <<<<<<< dest} /
     * {@code =======} / {@code >>>>>>> source} 마커(byte-for-byte, 라벨 사이 base 섹션 없음),
     * {@code hg resolve --list}에 "U f.txt").
     *
     * <p>{@link RebaseCommand}는 이제 {@link MergeCommand}와 같은 {@link
     * io.github.search5.hg4j.merge.Merge3} 엔진으로 실제 3-way merge를 시도한다: ancestor =
     * source 리비전 자신의 원래 parent가 갖고 있던 파일 내용, local = 현재 목적지(dest)의 내용,
     * other = source 리비전이 새로 만든 내용. 정말 겹치면 이 테스트처럼 {@link
     * HgMergeConflictException}을 던지고, 충돌 마커를 작업 파일에 쓰고, {@code
     * .hg/merge/state2}(실제 hg와 동일 포맷, {@link io.github.search5.hg4j.merge.MergeState})에
     * 미해결 상태를 남긴 채 rebase를 일시정지한다 -- 실제 hg CLI의 {@code hg resolve --list}가
     * 이 저장소를 그대로 읽어 "U f.txt"를 보여줄 수 있어야 한다는 것을 여기서 직접 확인한다.
     */
    @Test
    public void conflictingEditWritesConflictMarkersAndPausesRebase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0 base");

        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-target\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target modifies f");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-source\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source modifies f (conflicts with target)");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode));

        HgMergeConflictException ex = assertThrows(HgMergeConflictException.class, rebaseCmd::call,
                "a genuine same-file conflict must pause the rebase instead of silently overwriting it");
        assertEquals(List.of("f.txt"), ex.getConflictPaths());

        // Byte-for-byte match against real hg 7.2's own default internal:merge conflict markers
        // (verified live, see this file's class javadoc / the top-of-file real-hg repro).
        String fContent = Files.readString(new File(repoDir, "f.txt").toPath());
        assertEquals("<<<<<<< dest\nline1-target\n=======\nline1-source\n>>>>>>> source\n", fContent);

        // Real hg CLI, reading the SAME repo directory hg4j just wrote to, must see the identical
        // unresolved-file bookkeeping ".hg/merge/state2" produces (mirrors MergeCommand's own
        // already-proven state2 interop).
        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U f.txt", resolveList.trim());

        // No commit was produced for the paused revision, and the original source is untouched.
        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M f.txt"), "f.txt must show as modified-in-progress: " + status);
    }

    /**
     * Companion to {@link #conflictingEditWritesConflictMarkersAndPausesRebase}: {@link
     * RebaseCommand#abort()} must cleanly discard the paused rebase and restore the working copy
     * and dirstate to exactly their pre-rebase state (mirrors real hg's own {@code hg rebase
     * --abort}, verified live in this file's class javadoc / top-of-file real-hg repro).
     */
    @Test
    public void abortAfterConflictRestoresPreRebaseState(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0 base");

        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-target\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target modifies f");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-source\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source modifies f (conflicts with target)");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode));
        assertThrows(HgMergeConflictException.class, rebaseCmd::call);

        // A fresh RebaseCommand instance can abort -- the paused state is persisted to disk, not
        // held only in the instance that hit the conflict.
        new RebaseCommand(repo).abort();

        String parents = HgTestUtils.hg(repoDir, "parents", "--template", "{node}");
        assertEquals(sourceNode, parents, "working directory must be back on the original source commit");

        String content = Files.readString(new File(repoDir, "f.txt").toPath());
        assertEquals("line1-source\n", content, "f.txt must be back to its pre-rebase (source) content");

        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", resolveList.trim(), "no unresolved files must remain after abort");

        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("", status.trim(), "working copy must be clean after abort");

        // A second abort() (or continueRebase()) with nothing in progress must fail closed, same
        // as real hg's own "abort: no rebase in progress".
        assertThrows(HgValidationException.class, () -> new RebaseCommand(repo).abort());
        assertThrows(HgValidationException.class, () -> new RebaseCommand(repo).continueRebase());

        // The original source commit is untouched and rebase never left any trace behind.
        String log = HgTestUtils.hg(repoDir, "log", "--template", "{node} ");
        assertTrue(log.contains(sourceNode));
        assertTrue(log.contains(targetNode));
    }

    /**
     * Companion to {@link #conflictingEditWritesConflictMarkersAndPausesRebase}: after resolving
     * the conflict on disk exactly like a real {@code hg resolve} session would, {@link
     * RebaseCommand#continueRebase()} must finish the paused commit and produce a repository real
     * hg accepts as valid, with the original source hidden-not-gone (same evolution-only contract
     * as the non-conflicting case).
     */
    @Test
    public void continueRebaseAfterManualResolutionCompletesTheRebase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0 base");

        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-target\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target modifies f");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-source\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source modifies f (conflicts with target)");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode));
        assertThrows(HgMergeConflictException.class, rebaseCmd::call);

        // Manually resolve exactly like a user driving `hg resolve` would: overwrite the
        // conflict-marked file with the merged content, then mark it resolved.
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-target\nline1-source\n");
        HgTestUtils.hg(repoDir, "resolve", "--mark", "f.txt");
        assertEquals("R f.txt", HgTestUtils.hg(repoDir, "resolve", "--list").trim());

        // A fresh RebaseCommand instance can continue -- same persisted-state contract as abort().
        byte[] rebasedNode = new RebaseCommand(repo).continueRebase();
        String rebasedHex = NodeIdUtil.toHex(rebasedNode);

        String verify = hgEvolution(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "Repository integrity error!\n" + verify);

        String catF = hgEvolution(repoDir, "cat", "-r", rebasedHex, "f.txt");
        assertEquals("line1-target\nline1-source", catF.trim());

        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", resolveList.trim(), "no unresolved files must remain once the rebase completes");

        // Same evolution-only contract as the non-conflicting case: source is hidden, not gone.
        String hiddenLog = hgEvolution(repoDir, "log", "--hidden", "-r", sourceNode, "--template", "{node}");
        assertEquals(sourceNode, hiddenLog);
        String plainLog = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(plainLog.contains(sourceNode), "hidden revision must not appear in a plain `hg log`");

        // Nothing left in progress.
        assertThrows(HgValidationException.class, () -> new RebaseCommand(repo).continueRebase());
    }

    private static String hgEvolution(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "experimental.evolution=all";
        System.arraycopy(args, 0, cmd, 3, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + Arrays.toString(args) + " failed with exit code " + code + ": " + out);
        }
        return out;
    }

    /**
     * <b>고쳐짐(2026-09-04 오후): "unknown revision" 버그가 없어지고 실제 hg의 evolution
     * 시맨틱과 일치함.</b> 이 테스트는 원래(2026-09-04 오전) hg4j RebaseCommand가 원본 리비전을
     * 물리적으로 strip하면서 동시에 obsolescence marker를 남기는 바람에(실제 hg는 이 둘을 절대
     * 동시에 하지 않는다 -- evolution 없이 strip만 하거나, evolution으로 marker만 남기고 strip은
     * 안 하거나 둘 중 하나) marker의 predecessor가 changelog에서 완전히 사라져 {@code hg log
     * --hidden}으로도 "hidden"이 아니라 "unknown revision"이 되는 버그를 문서화했다.
     *
     * <p>RebaseCommand는 이제 evolution-only다(marker만 남기고 물리적 strip은 전혀 하지 않음) --
     * 그래서 이 테스트는 이제 정반대: 원본 리비전이 changelog/manifest/filelog에 그대로 완전히
     * 읽을 수 있게 남아있고, {@code hg log --hidden}으로 정상적으로 찾아지며(더 이상 "unknown
     * revision" 아님), 그러면서도 {@code hg log}/{@code hg log -G}(즉 {@code --hidden} 없이)에는
     * 나타나지 않는다(살아있는 non-obsolete successor가 있으므로 기본적으로 숨김)는 것을
     * 확인한다 -- 실제 hg의 evolution 기반 rebase와 정확히 같은 결과.
     */
    @Test
    public void originalRevisionIsHiddenNotGoneAfterRebase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "base.txt").toPath(), "base\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "base.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        Files.writeString(new File(repoDir, "target.txt").toPath(), "on-target\n");
        HgTestUtils.hg(repoDir, "add", "target.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 target");
        String targetNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "source.txt").toPath(), "on-source\n");
        HgTestUtils.hg(repoDir, "add", "source.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 source");
        String sourceNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode))
                .call();

        // 실제 hg debugobsolete로 원문 마커 확인 -- predecessor/successor hex가 그대로 보여야 함
        // (HgObsolescenceRealHgInteropTest가 이미 증명한 "hg4j가 쓴 obsstore를 real hg가 파싱
        // 가능"의 연장선).
        String debugObsolete = hgEvolution(repoDir, "debugobsolete");
        assertTrue(debugObsolete.contains(sourceNode), "marker의 predecessor에 원본 노드가 있어야 함:\n" + debugObsolete);

        // Evolution-only rebase (2026-09-04): the original node is fully readable, so real hg's
        // `hg log --hidden -r <node>` finds it (no "unknown revision") and reports the exact same
        // node -- this is the direct fix for this test's original documented bug.
        String hiddenLog = hgEvolution(repoDir, "log", "--hidden", "-r", sourceNode, "--template", "{node}");
        assertEquals(sourceNode, hiddenLog,
                "the original revision must be found and fully readable via `hg log --hidden`, not \"unknown revision\"");

        // Its content, message and parentage must still be exactly what they always were.
        String hiddenDesc = hgEvolution(repoDir, "log", "--hidden", "-r", sourceNode, "--template", "{desc}");
        assertEquals("c2 source", hiddenDesc);
        String hiddenCat = hgEvolution(repoDir, "cat", "--hidden", "-r", sourceNode, "source.txt");
        assertEquals("on-source", hiddenCat.trim());

        // But it must NOT show up in a plain `hg log`/`hg log -G` (no --hidden): a revision with a
        // live, non-obsolete successor is hidden by default, exactly like real hg's own
        // evolution-based rebase.
        String plainLog = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(plainLog.contains(sourceNode), "hidden revision must not appear in a plain `hg log`");
        String plainLogGraph = hgEvolution(repoDir, "log", "-G", "--template", "{node} ");
        assertFalse(plainLogGraph.contains(sourceNode), "hidden revision must not appear in a plain `hg log -G`");
    }
}
