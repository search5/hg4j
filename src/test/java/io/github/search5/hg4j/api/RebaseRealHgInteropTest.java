package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
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

        // original source commit is gone from the store (hg4j physically strips it)
        String logAll = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(logAll.contains(sourceNode), "물리적으로 strip된 원본 커밋은 더 이상 존재하면 안 됨");
    }

    /**
     * hg4j RebaseCommand는 매 cherry-pick마다 obsolescence marker를 무조건 남긴다(evolution
     * 활성화 여부와 무관). 실제 hg는 이 marker의 존재 자체를, evolution을 켜지 않은 일반 클라이언트
     * 입장에서 "이례적인 상황"으로 보고 명령을 실행할 때마다 경고를 찍는다(마커를 지우거나 무시하지
     * 않고, 그냥 매번 stdout에 경고 줄을 추가함 -- 스크립트로 `hg log`/`hg cat` 등의 출력을 파싱하는
     * 도구가 있다면 이 경고 줄 때문에 깨질 수 있다). evolution을 쓸 생각이 전혀 없는 사용자가 평범한
     * `hg rebase` 대응 동작을 hg4j에 기대했다면 예상 못한 부작용이다. 실제 hg 자신은 evolution이
     * 꺼져 있으면 rebase 때 marker를 아예 만들지 않으므로 이런 경고가 나지 않는다.
     */
    @Test
    public void plainRealHgCommandsWarnAfterHg4jRebase(@TempDir Path tempDir) throws Exception {
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
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = p.waitFor();

        assertEquals(0, exit, "명령 자체는 성공해야 함(경고일 뿐, abort 아님): " + output);
        assertTrue(output.contains("\"obsolete\" feature not enabled but"),
                "hg4j가 남긴 marker 때문에 evolution을 쓰지 않는 평범한 hg 명령에도 경고가 섞여 나와야 함:\n" + output);
    }

    /**
     * <b>아키텍처 갭 문서화(고치지 않음 -- 조정자/사용자 확인 필요, 상세는 최종 보고 참고).</b>
     *
     * <p>실제 hg는 rebase 중 source와 target이 같은 파일의 같은 부분을 다르게 고쳤으면 3-way
     * merge를 시도하고, 정말 겹치면 conflict marker를 남기고 exit code 1로 멈춰
     * {@code hg resolve}/{@code hg rebase --continue}를 요구한다(이 파일 맨 위에서 실제 hg 7.2로
     * 직접 재현: {@code hg rebase -s 2 -d 1} → exit 1, {@code <<<<<<< dest ... ======= ... >>>>>>>
     * source} 마커, {@code hg resolve --list}에 "U f.txt").
     *
     * <p>hg4j {@link RebaseCommand#cherryPickBackup}는 이런 3-way merge를 전혀 하지 않는다 --
     * target을 체크아웃한 뒤 원본 커밋이 갖고 있던 파일 내용을 무조건 덮어쓸 뿐이다(3-way merge
     * 로직 자체가 코드에 없음, {@link MergeCommand}가 쓰는 {@link
     * io.github.search5.hg4j.merge.Merge3}를 전혀 참조하지 않음). 그 결과 이 테스트처럼 target이
     * 같은 파일을 다르게 수정한 상태에서 rebase하면, target의 수정 내용이 아무 경고도 충돌
     * 표시도 없이 조용히 사라지고 source의 내용으로 완전히 덮어써진다 -- silent data loss.
     *
     * <p>{@code --continue}/{@code --abort} 메서드 자체도 {@link RebaseCommand}에 없다(항상
     * 원자적으로 전체를 끝내거나 예외 시 물리적 rollback하는 all-or-nothing 설계라 애초에 "중단된
     * 상태"가 존재하지 않음 -- 하지만 그 all-or-nothing 설계가 가능한 이유가 바로 이 테스트가
     * 보여주는 "충돌을 아예 감지 안 함"이다).
     */
    @Test
    public void conflictingEditIsSilentlyOverwrittenInsteadOfDetectedAsConflict(@TempDir Path tempDir) throws Exception {
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

        // Real hg would refuse this with a conflict (verified live above / in this file's class
        // javadoc). hg4j completes it without error or any conflict signal.
        byte[] rebasedNode = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceNode))
                .setTarget(NodeIdUtil.fromHex(targetNode))
                .call();
        String rebasedHex = NodeIdUtil.toHex(rebasedNode);

        String cat = hgEvolution(repoDir, "cat", "-r", rebasedHex, "f.txt");
        assertEquals("line1-source", cat.trim(),
                "현재 동작 문서화용 assert: target의 'line1-target' 수정이 흔적도 없이 사라지고 " +
                "source 내용으로 조용히 덮어써진다 (실제 hg라면 여기서 conflict로 멈춰야 함). " +
                "이 assert가 깨진다면 동작이 바뀐 것이니 이 테스트와 최종 보고를 함께 갱신할 것.");

        String noResolveList = hgEvolution(repoDir, "resolve", "--list");
        assertEquals("", noResolveList.trim(), "hg4j는 애초에 충돌을 감지하지 않으므로 남는 미해결 파일도 없음");
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
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + java.util.Arrays.toString(args) + " failed with exit code " + code + ": " + out);
        }
        return out;
    }

    /**
     * hg4j RebaseCommand가 남기는 obsolescence marker(원본 -> rebase된 노드)를 실제 hg
     * {@code hg debugobsolete}/{@code hg log --hidden}이 어떻게 보는지 확인한다. hg4j
     * RebaseCommand는 원본 리비전을 물리적으로 strip하면서 동시에 obsolescence marker를
     * 남긴다 -- 원본 노드가 changelog에 더 이상 존재하지 않으므로, 실제 hg 관점에서 이 marker가
     * 가리키는 predecessor는 "hidden revision"이 아니라 "unknown revision"이 된다는 것이
     * 이 테스트가 확인하려는 것이다(실제 hg의 evolution 기반 rebase --keep 없는 기본 strip
     * 동작과는 의미가 다름 -- 실제 hg가 evolution 없이 rebase하면 obsmarker를 아예 안 남기고
     * strip만 하고, evolution이 켜져 있으면 strip 없이 obsmarker만 남긴다. hg4j는 이 둘을
     * 동시에 하고 있어 marker가 가리키는 대상이 존재하지 않는 상태가 된다).
     */
    @Test
    public void obsoleteMarkerAfterRebaseStripPointsAtNodeGoneFromChangelog(@TempDir Path tempDir) throws Exception {
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
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "experimental.evolution=all", "debugobsolete");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String debugObsolete = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = p.waitFor();
        assertEquals(0, exit, "debugobsolete 자체는 파싱에 성공해야 함:\n" + debugObsolete);
        assertTrue(debugObsolete.contains(sourceNode), "marker의 predecessor에 원본 노드가 있어야 함:\n" + debugObsolete);

        // 하지만 원본 노드는 changelog에서 물리적으로 사라졌으므로, `hg log --hidden`으로도
        // 찾을 수 없다(evolution의 "hidden"이 아니라 완전히 strip된 것) -- 이는 실제 hg의
        // "evolution 켜짐 = strip 없이 숨김" 의미론과 다르다.
        ProcessBuilder pbLog = new ProcessBuilder("hg", "--config", "experimental.evolution=all",
                "log", "--hidden", "-r", sourceNode, "--template", "{node}");
        pbLog.directory(repoDir);
        pbLog.redirectErrorStream(true);
        Process pLog = pbLog.start();
        String logHiddenOutput = new String(pLog.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int logExit = pLog.waitFor();

        assertNotEquals(0, logExit,
                "hg4j가 원본 리비전을 물리적으로 strip하므로, --hidden으로도 실제 hg가 그 노드를 찾지 " +
                "못해야 한다(찾아지면 이 테스트의 가정이 깨진 것이니 재검토 필요): " + logHiddenOutput);
        assertTrue(logHiddenOutput.contains("unknown revision"),
                "실제 hg는 evolution 기반 hidden이 아니라 unknown revision 오류를 내야 함: " + logHiddenOutput);
    }
}
