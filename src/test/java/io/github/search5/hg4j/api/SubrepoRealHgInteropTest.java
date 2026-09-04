package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.submodule.HgSubrepoEntry;
import io.github.search5.hg4j.submodule.HgSubrepoParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog 23 (subrepo 카테고리): {@code .hgsub}/{@code .hgsubstate}를 낀 커밋/업데이트가 실제
 * hg CLI의 subrepo 처리와 일치하는지 양방향으로 대조한다.
 *
 * <p>이전까지 gap table의 {@code Subrepositories} 행은 근거 서술 없이 "✅"만 달려 있었다 -- 이
 * 클래스로 처음 실제 hg CLI와 대조해보니, 표와 달리 {@link CommitCommand}에는 subrepo 인식
 * 로직이 전혀 없었다({@code .hgsubstate} 자동 생성/자동 추적, dirty-subrepo 커밋 거부 등 real
 * hg의 핵심 자동화가 전부 빠져 있었음 -- 이 테스트를 추가하며 함께 구현했다).
 */
@Tag("interop")
public class SubrepoRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * 시나리오 1: 실제 hg CLI로 {@code .hgsub}+{@code .hgsubstate}를 낀 저장소를 만들고,
     * hg4j의 {@link HgSubrepoParser}가 그걸 정확히 파싱하는지 확인한다.
     */
    @Test
    public void hg4jParsesRealHgGeneratedHgsubAndHgsubstate(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgTestUtils.nativeRepo(subDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "hello from real hg sub");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(subDir, "add");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub c1");
        String subTip = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");

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

        // 실제 hg clone으로 서브저장소를 parent 작업 디렉터리 안에 넣는다 (real hg의 표준 워크플로우).
        HgTestUtils.hg(tempDir.toFile(), "clone", subDir.getAbsolutePath(), new File(parentDir, "sub").getAbsolutePath());

        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = sub\n");
        HgTestUtils.hg(parentDir, "add", ".hgsub");
        // .hgsubstate는 hg add 없이도 실제 hg가 커밋 시 자동으로 기록한다 (백로그 조사 중 확인).
        HgTestUtils.hg(parentDir, "commit", "-u", "T", "-m", "add subrepo");

        byte[] hgsub = Files.readAllBytes(new File(parentDir, ".hgsub").toPath());
        byte[] hgsubstate = Files.readAllBytes(new File(parentDir, ".hgsubstate").toPath());

        Map<String, HgSubrepoEntry> parsed = HgSubrepoParser.parseSubrepositories(hgsub, hgsubstate);
        assertEquals(1, parsed.size());
        HgSubrepoEntry entry = parsed.get("sub");
        assertNotNull(entry);
        assertEquals("sub", entry.getSourceUrl());
        assertEquals(subTip, entry.getRevision(),
                "실제 hg가 .hgsubstate에 기록한 서브저장소 리비전을 hg4j 파서가 정확히 읽어야 함");
        assertFalse(entry.isGit());
    }

    /**
     * 시나리오 2/3: hg4j {@link CommitCommand}로 서브저장소가 낀 첫 커밋을 만들면, 실제 hg가
     * 이해하는 {@code .hgsubstate}가 자동으로 생성/추적되는지("hg add .hgsubstate" 없이도),
     * 그리고 실제 hg CLI(hg status/hg log/hg manifest)로 열어서 정확히 인식되는지 확인한다.
     */
    @Test
    public void hg4jCommitAutoGeneratesHgsubstateRealHgUnderstands(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgRepository subRepo = Hg.init().setDirectory(subDir).call();
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello from hg4j sub");
        new AddCommand(subRepo).call();
        byte[] subCommit = new CommitCommand(subRepo).setAuthor("T <t@example.com>").setMessage("sub c1").call();
        String subTipHex = new NodeId(subCommit).toHex();

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        // sub를 parent 작업 디렉터리 안으로 clone (hg4j).
        File subInParent = new File(parentDir, "sub");
        Hg.cloneRepository().setSource(subDir.getAbsolutePath()).setDirectory(subInParent).call();

        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = sub\n");
        new AddCommand(parentRepo).call(); // .hgsub만 add -- .hgsubstate는 절대 손으로 add하지 않는다.

        assertFalse(new File(parentDir, ".hgsubstate").exists(),
                "커밋 전에는 아직 .hgsubstate가 없어야 함");

        byte[] parentCommit = new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo").call();

        File hgsubstateFile = new File(parentDir, ".hgsubstate");
        assertTrue(hgsubstateFile.exists(),
                ".hgsub만 add한 뒤 커밋해도 .hgsubstate가 실제 hg처럼 자동으로 생성되어야 함");
        String hgsubstateContent = Files.readString(hgsubstateFile.toPath(), StandardCharsets.UTF_8).trim();
        assertEquals(subTipHex + " sub", hgsubstateContent,
                "자동 생성된 .hgsubstate는 서브저장소의 현재 체크아웃 리비전을 정확히 기록해야 함");

        // 실제 hg CLI로 열어서 인식되는지 확인.
        String status = HgTestUtils.hg(parentDir, "status");
        assertEquals("", status, "커밋 직후에는 실제 hg 기준으로도 워킹 카피가 clean해야 함: " + status);

        String files = HgTestUtils.hg(parentDir, "files");
        assertTrue(files.contains(".hgsubstate") && files.contains(".hgsub"),
                "실제 hg files에 .hgsub/.hgsubstate가 추적된 파일로 나와야 함: " + files);

        String catState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate").trim();
        assertEquals(subTipHex + " sub", catState,
                "실제 hg가 읽는 커밋된 .hgsubstate 내용이 hg4j가 쓴 내용과 정확히 일치해야 함");

        String log = HgTestUtils.hg(parentDir, "log", "--template", "{node}\\n");
        assertTrue(log.contains(new NodeId(parentCommit).toHex()),
                "실제 hg log에 hg4j가 만든 부모 커밋이 정확한 노드 해시로 나와야 함");
    }

    /**
     * 시나리오 3: 서브저장소 자체가 새 리비전으로 바뀐 뒤 부모를 hg4j로 재커밋(-S 방식)하면
     * .hgsubstate가 새 리비전으로 갱신되고, 이 결과를 실제 hg CLI로도 확인한다.
     * 또한 하위 커밋 없이는(recursive 플래그 없이는) 실제 hg와 동일하게 커밋이 거부되는지도
     * 함께 검증한다.
     */
    @Test
    public void hg4jCommitRefusesDirtySubrepoUnlessRecursiveThenUpdatesHgsubstate(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgRepository subRepo = Hg.init().setDirectory(subDir).call();
        Files.writeString(new File(subDir, "hello.txt").toPath(), "v1");
        new AddCommand(subRepo).call();
        byte[] subC1 = new CommitCommand(subRepo).setAuthor("T <t@example.com>").setMessage("sub c1").call();

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        File subInParent = new File(parentDir, "sub");
        Hg.cloneRepository().setSource(subDir.getAbsolutePath()).setDirectory(subInParent).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = sub\n");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo").call();

        // 서브저장소를 dirty하게 만든다 (커밋되지 않은 로컬 변경).
        Files.writeString(new File(subInParent, "hello.txt").toPath(), "v2 (uncommitted)");

        // 재귀 플래그 없이 부모를 커밋하면 실제 hg와 동일하게 거부되어야 한다.
        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("should fail").call());
        assertTrue(ex.getMessage().contains("uncommitted changes in subrepository") && ex.getMessage().contains("sub"),
                "실제 hg의 abort 메시지 형태(\"uncommitted changes in subrepository ...\")를 따라야 함: " + ex.getMessage());

        // 재귀(-S 상당) 플래그를 켜면 서브저장소가 먼저 커밋되고 .hgsubstate가 갱신되어야 한다.
        byte[] parentC2 = new CommitCommand(parentRepo)
                .setAuthor("T <t@example.com>")
                .setMessage("bump sub")
                .setSubrepos(true)
                .call();

        String hgsubstateContent = Files.readString(new File(parentDir, ".hgsubstate").toPath(), StandardCharsets.UTF_8).trim();
        HgRepository subInParentRepo = new HgRepository(subInParent);
        String subNewTipHex = subInParentRepo.getDirstate().getParent1Node().toHex();
        assertNotEquals(new NodeId(subC1).toHex(), subNewTipHex,
                "서브저장소가 재귀 커밋으로 새 리비전을 얻었어야 함");
        assertEquals(subNewTipHex + " sub", hgsubstateContent,
                ".hgsubstate가 서브저장소의 새 리비전으로 갱신되어야 함");

        // 실제 hg CLI로도 확인: 부모/서브 양쪽 다 clean하고, 서브 로그에 새 커밋이 있어야 함.
        assertEquals("", HgTestUtils.hg(parentDir, "status"));
        String subLog = HgTestUtils.hg(subInParent, "log", "--template", "{node} {desc}\\n");
        assertTrue(subLog.contains(subNewTipHex), "실제 hg가 서브저장소의 새 커밋을 인식해야 함: " + subLog);

        String parentCatState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate").trim();
        assertEquals(subNewTipHex + " sub", parentCatState,
                "실제 hg가 읽는 부모 tip의 .hgsubstate도 새 서브 리비전을 담고 있어야 함");

        assertNotNull(parentC2);
    }

    /**
     * 시나리오 4 (반대 방향): 실제 hg로 서브저장소 상태를 두 개의 서로 다른 리비전으로 각각
     * pin한 부모 커밋 두 개를 만들고, hg4j {@link UpdateCommand}로 그 사이를 오가며 서브저장소
     * 워킹 카피 내용이 실제 hg가 만든 상태와 정확히 일치하는지 확인한다.
     */
    @Test
    public void hg4jUpdateCheckoutMatchesRealHgAcrossSubrepoRevisionPins(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgTestUtils.nativeRepo(subDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(subDir, "add");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub v1");
        String subV1 = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");

        Files.writeString(new File(subDir, "hello.txt").toPath(), "v2");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub v2");
        String subV2 = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");

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

        HgTestUtils.hg(tempDir.toFile(), "clone", "-r", subV1, subDir.getAbsolutePath(),
                new File(parentDir, "sub").getAbsolutePath());
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = " + subDir.getAbsolutePath() + "\n");
        HgTestUtils.hg(parentDir, "add", ".hgsub");
        HgTestUtils.hg(parentDir, "commit", "-u", "T", "-m", "pin sub@v1");
        String parentPinV1 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");

        HgTestUtils.hg(new File(parentDir, "sub"), "pull");
        HgTestUtils.hg(new File(parentDir, "sub"), "update", "-r", subV2);
        HgTestUtils.hg(parentDir, "commit", "-S", "-u", "T", "-m", "pin sub@v2");
        String parentPinV2 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");

        HgRepository parentRepo = new HgRepository(parentDir);

        // hg4j로 v1 pin 리비전으로 되돌아가면 서브저장소 내용도 v1이어야 한다.
        new UpdateCommand(parentRepo).setRevision(parentPinV1).setForce(true).call();
        assertEquals("v1", Files.readString(new File(parentDir, "sub/hello.txt").toPath()),
                "hg4j update로 서브 v1 pin 리비전으로 돌아가면 서브저장소 내용도 v1이어야 함");

        // 다시 v2 pin 리비전으로 hg4j update하면 서브저장소도 v2로 전진해야 한다.
        new UpdateCommand(parentRepo).setRevision(parentPinV2).setForce(true).call();
        assertEquals("v2", Files.readString(new File(parentDir, "sub/hello.txt").toPath()),
                "hg4j update로 서브 v2 pin 리비전으로 가면 서브저장소 내용도 v2여야 함");
    }

    /**
     * 시나리오 5 (backlog 23/24, 2026-09-04 최종 결정 반영): {@code .hgsub}에 선언되었지만
     * 로컬에 체크아웃되지 않은 서브저장소 경로가 있을 때, hg4j {@link CommitCommand}가 실제
     * hg처럼 {@code .hgsubstate} 엔트리를 null 리비전({@code 000...0})으로 리셋하는지 확인한다.
     *
     * <p>동일한 시나리오를 실제 hg CLI로도 나란히 재현해 오라클(정답)로 삼고, (a) hg4j가 쓴
     * {@code .hgsubstate} 바이트가 실제 hg가 쓴 바이트와 정확히(byte-for-byte) 일치하는지,
     * (b) 실제 hg CLI로 hg4j의 결과 저장소를 읽었을 때({@code hg status}/{@code hg cat})도
     * 동일한 null 리비전 엔트리로 인식되는지 양방향으로 확인한다. 이전에는 hg4j가 이 경우
     * 기존 {@code .hgsubstate} 값을 그대로 보존하는 의도적 divergence였으나, 사용자 결정에
     * 따라 real hg와 완전히 동일하게 동작하도록 변경되었다.
     */
    @Test
    public void hg4jCommitResetsNotCheckedOutSubrepoToNullRevisionMatchingRealHg(@TempDir Path tempDir) throws Exception {
        File nonExistentSource = tempDir.resolve("no-such-source").toFile();

        // --- 오라클: 실제 hg CLI로 동일한 시나리오를 재현해 정확한 바이트 포맷을 확보한다. ---
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

        // 실제 hg와 동일하게, 선언된 서브저장소 경로("sub")를 로컬에 전혀 체크아웃하지 않는다.
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "sub = " + nonExistentSource.getAbsolutePath() + "\n");
        HgTestUtils.hg(realParentDir, "add", ".hgsub");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "add subrepo not checked out");

        byte[] realHgsubstateBytes = Files.readAllBytes(new File(realParentDir, ".hgsubstate").toPath());
        String expectedLine = NodeId.NULL.toHex() + " sub\n";
        assertEquals(expectedLine, new String(realHgsubstateBytes, StandardCharsets.UTF_8),
                "실제 hg가 만드는 .hgsubstate 라인 포맷을 정확히 확인 (오라클)");
        // 참고용 확인: 실제 hg는 체크아웃되지 않은 서브저장소 경로에도 빈 저장소를 자동 생성한다.
        // (hg4j는 이 자동 생성까지는 재현하지 않는다 -- 이 백로그가 요구하는 것은 .hgsubstate
        // 엔트리 리셋뿐이므로 범위 밖.)
        assertTrue(new File(realParentDir, "sub/.hg").exists(),
                "실제 hg는 체크아웃되지 않은 서브저장소 경로에 빈 저장소를 자동 생성함 (오라클 확인용)");

        // --- hg4j: 동일한 시나리오를 hg4j CommitCommand로 재현한다. ---
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        // "sub"를 로컬에 전혀 체크아웃하지 않은 채로 .hgsub만 선언한다.
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = " + nonExistentSource.getAbsolutePath() + "\n");
        new AddCommand(parentRepo).call(); // .hgsub만 add -- .hgsubstate는 절대 손으로 add하지 않는다.

        assertFalse(new File(parentDir, ".hgsubstate").exists(),
                "커밋 전에는 아직 .hgsubstate가 없어야 함");

        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo not checked out").call();

        File hgsubstateFile = new File(parentDir, ".hgsubstate");
        assertTrue(hgsubstateFile.exists(),
                "체크아웃되지 않은 서브저장소를 선언해도 .hgsubstate는 (null 리비전으로) 자동 생성되어야 함");
        byte[] hg4jHgsubstateBytes = Files.readAllBytes(hgsubstateFile.toPath());

        // (a) hg4j가 쓴 바이트가 실제 hg가 쓴 바이트와 정확히(byte-for-byte) 일치해야 한다.
        assertArrayEquals(realHgsubstateBytes, hg4jHgsubstateBytes,
                "체크아웃되지 않은 서브저장소에 대해 hg4j가 기록한 .hgsubstate 바이트가 실제 hg의 바이트와 정확히 일치해야 함");
        assertEquals(expectedLine, new String(hg4jHgsubstateBytes, StandardCharsets.UTF_8));

        // (b) 실제 hg CLI로 hg4j가 만든 저장소를 읽었을 때도 동일한 null 리비전 엔트리로 인식되어야 한다.
        String status = HgTestUtils.hg(parentDir, "status");
        assertEquals("", status, "실제 hg 기준으로도 커밋 직후 워킹 카피가 clean해야 함: " + status);

        String catState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate").trim();
        assertEquals(NodeId.NULL.toHex() + " sub", catState,
                "실제 hg CLI가 읽는 hg4j 커밋의 .hgsubstate도 null 리비전 엔트리를 담고 있어야 함");
    }

    // ------------------------------------------------------------------------------------------
    // 백로그 32: subrepo 잔여 gap 4건 (2026-09-04) -- 각각 실제 hg CLI(+git)와 나란히 대조 검증.
    // ------------------------------------------------------------------------------------------

    /**
     * Gap #1: {@code CloneCommand}가 부모 저장소를 clone할 때 체크아웃된 tip이 선언한 서브저장소도
     * 재귀적으로 clone하는지 확인한다. 오라클(실제 hg)로 부모+서브 저장소를 만들고 hg4j로
     * {@code clone}한 뒤, 서브저장소 워킹 카피 내용이 실제 hg가 pin한 리비전과 정확히 일치하는지,
     * 그리고 그 결과물을 실제 hg CLI({@code hg verify}/{@code hg log})로도 정상 인식하는지 본다.
     */
    @Test
    public void hg4jCloneRecursivelyClonesSubrepoMatchingRealHg(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgTestUtils.nativeRepo(subDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "hello from real hg sub");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(subDir, "add");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub c1");
        String subTip = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");

        File originParentDir = tempDir.resolve("origin-parent").toFile();
        HgTestUtils.nativeRepo(originParentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(originParentDir, "add");
        HgTestUtils.hg(originParentDir, "commit", "-u", "T", "-m", "parent init");
        HgTestUtils.hg(tempDir.toFile(), "clone", subDir.getAbsolutePath(), new File(originParentDir, "sub").getAbsolutePath());
        Files.writeString(new File(originParentDir, ".hgsub").toPath(), "sub = " + subDir.getAbsolutePath() + "\n");
        HgTestUtils.hg(originParentDir, "add", ".hgsub");
        HgTestUtils.hg(originParentDir, "commit", "-u", "T", "-m", "add subrepo");

        // hg4j CloneCommand로 부모 저장소를 clone -- 서브저장소도 함께 재귀적으로 clone되어야 한다.
        File clonedParentDir = tempDir.resolve("cloned-parent").toFile();
        Hg.cloneRepository().setSource(originParentDir.getAbsolutePath()).setDirectory(clonedParentDir).call();

        File clonedSubDir = new File(clonedParentDir, "sub");
        assertTrue(new File(clonedSubDir, ".hg").exists(),
                "hg4j clone은 부모 tip이 선언한 서브저장소도 재귀적으로 clone해야 함 (real hg `hg clone`과 동일)");
        assertEquals("hello from real hg sub", Files.readString(new File(clonedSubDir, "hello.txt").toPath()),
                "재귀 clone된 서브저장소 워킹 카피 내용이 pin된 리비전 내용과 일치해야 함");

        HgRepository clonedSubRepo = new HgRepository(clonedSubDir);
        assertEquals(subTip, clonedSubRepo.getDirstate().getParent1Node().toHex(),
                "재귀 clone된 서브저장소가 .hgsubstate에 pin된 바로 그 리비전으로 체크아웃되어야 함");

        // 실제 hg CLI로도 hg4j가 재귀 clone한 서브저장소를 정상적으로 읽고 검증할 수 있어야 한다.
        String verifyOut = HgTestUtils.hg(clonedSubDir, "verify");
        assertTrue(verifyOut.contains("0 integrity errors") || !verifyOut.toLowerCase().contains("error"),
                "실제 hg verify가 hg4j로 재귀 clone된 서브저장소를 무결한 것으로 인식해야 함: " + verifyOut);
        String subLog = HgTestUtils.hg(clonedSubDir, "log", "--template", "{node}\\n");
        assertTrue(subLog.contains(subTip), "실제 hg log가 재귀 clone된 서브저장소에서 원본과 같은 커밋을 봐야 함: " + subLog);
    }

    /**
     * Gap #1 + #3 (git 조합): {@code CloneCommand}가 부모를 clone할 때 {@code [git]} 서브저장소도
     * (real git CLI로) 재귀적으로 clone/checkout하는지 확인한다. 오라클(실제 hg + git)로 부모/git
     * 서브저장소를 만들고 hg4j로 {@code clone}한 뒤, git 서브저장소 워킹 카피가 pin된 커밋과
     * 정확히 일치하는지 확인한다.
     */
    @Test
    public void hg4jCloneRecursivelyClonesGitSubrepoMatchingRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isGitInstalled(), "git이 설치되어 있지 않습니다. 건너뜁니다.");

        File gitSubSrc = tempDir.resolve("git-sub-src").toFile();
        gitSubSrc.mkdirs();
        HgTestUtils.git(gitSubSrc, "init", "-q", "-b", "master", ".");
        Files.writeString(new File(gitSubSrc, "g.txt").toPath(), "hello from git sub");
        HgTestUtils.git(gitSubSrc, "add", "g.txt");
        HgTestUtils.git(gitSubSrc, "commit", "-q", "-m", "git commit1");
        String gitSha = HgTestUtils.git(gitSubSrc, "rev-parse", "HEAD");

        File originParentDir = tempDir.resolve("origin-parent").toFile();
        HgTestUtils.nativeRepo(originParentDir, dir -> {
            try {
                Files.writeString(new File(dir, "init.txt").toPath(), "init");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(originParentDir, "add");
        HgTestUtils.hg(originParentDir, "commit", "-u", "T", "-m", "parent init");
        Files.writeString(new File(originParentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.git(tempDir.toFile(), "clone", "-q", gitSubSrc.getAbsolutePath(), new File(originParentDir, "gitsub").getAbsolutePath());
        HgTestUtils.hgGitAllowed(originParentDir, "add", ".hgsub");
        HgTestUtils.hgGitAllowed(originParentDir, "commit", "-u", "T", "-m", "add git subrepo");

        // hg4j CloneCommand로 부모 저장소를 clone -- git 서브저장소도 실제 git CLI를 통해
        // 재귀적으로 clone/checkout되어야 한다.
        File clonedParentDir = tempDir.resolve("cloned-parent").toFile();
        Hg.cloneRepository().setSource(originParentDir.getAbsolutePath()).setDirectory(clonedParentDir).call();

        File clonedGitSubDir = new File(clonedParentDir, "gitsub");
        assertTrue(new File(clonedGitSubDir, ".git").exists(),
                "hg4j clone은 부모 tip이 선언한 git 서브저장소도 실제 git clone으로 재귀 clone해야 함");
        assertEquals("hello from git sub", Files.readString(new File(clonedGitSubDir, "g.txt").toPath()),
                "재귀 clone된 git 서브저장소 워킹 카피 내용이 pin된 커밋 내용과 일치해야 함");
        String clonedHead = HgTestUtils.git(clonedGitSubDir, "rev-parse", "HEAD");
        assertEquals(gitSha, clonedHead,
                "재귀 clone된 git 서브저장소가 .hgsubstate에 pin된 바로 그 git 커밋으로 체크아웃되어야 함");
    }

    /**
     * Gap #2 (raw 삭제): {@code hg remove} 없이 {@code .hgsub} 파일 자체를 디스크에서만 지우고
     * 커밋하면, 실제 hg는 {@code .hgsub} 자체는 추적된 채로("tracked-but-missing") 남기고
     * {@code .hgsubstate}만 빈 내용으로 커밋한다 (오라클로 실측). hg4j도 동일해야 한다.
     */
    @Test
    public void hg4jCommitEmptiesHgsubstateWhenHgsubRawlyDeletedMatchingRealHg(@TempDir Path tempDir) throws Exception {
        // --- 오라클: 실제 hg ---
        File realSubDir = tempDir.resolve("real-sub").toFile();
        HgTestUtils.nativeRepo(realSubDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "hello");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(realSubDir, "add");
        HgTestUtils.hg(realSubDir, "commit", "-u", "T", "-m", "sub c1");

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
        HgTestUtils.hg(tempDir.toFile(), "clone", realSubDir.getAbsolutePath(), new File(realParentDir, "sub").getAbsolutePath());
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "sub = sub\n");
        HgTestUtils.hg(realParentDir, "add", ".hgsub");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "add subrepo");

        Files.delete(new File(realParentDir, ".hgsub").toPath()); // raw delete, no `hg remove`
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "remove hgsub raw");

        byte[] oracleHgsubstate = Files.readAllBytes(new File(realParentDir, ".hgsubstate").toPath());
        assertEquals(0, oracleHgsubstate.length,
                "오라클: 실제 hg는 .hgsub이 raw 삭제되면 .hgsubstate를 빈 내용으로 커밋함");
        String oracleStatus = HgTestUtils.hg(realParentDir, "status");
        assertTrue(oracleStatus.contains("! .hgsub"),
                "오라클: .hgsub 자체는 추적된 채로 missing 상태로 남아야 함: " + oracleStatus);
        String oracleManifest = HgTestUtils.hg(realParentDir, "manifest");
        assertTrue(oracleManifest.contains(".hgsub") && oracleManifest.contains(".hgsubstate"),
                "오라클: .hgsub/.hgsubstate 둘 다 여전히 manifest에 남아있어야 함: " + oracleManifest);

        // --- hg4j 재현 ---
        File subDir = tempDir.resolve("sub").toFile();
        HgRepository subRepo = Hg.init().setDirectory(subDir).call();
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello");
        new AddCommand(subRepo).call();
        new CommitCommand(subRepo).setAuthor("T <t@example.com>").setMessage("sub c1").call();

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        Hg.cloneRepository().setSource(subDir.getAbsolutePath()).setDirectory(new File(parentDir, "sub")).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = sub\n");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo").call();

        Files.delete(new File(parentDir, ".hgsub").toPath()); // raw delete, no RemoveCommand
        assertDoesNotThrow(() ->
                        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("remove hgsub raw").call(),
                "실제 hg처럼 .hgsub이 raw 삭제된 채로도 커밋이 성공해야 함 (missing-file abort가 발생하면 안 됨)");

        byte[] hg4jHgsubstate = Files.readAllBytes(new File(parentDir, ".hgsubstate").toPath());
        assertEquals(0, hg4jHgsubstate.length,
                "hg4j도 .hgsub이 raw 삭제되면 .hgsubstate를 빈 내용으로 커밋해야 함 (오라클과 동일)");

        // 실제 hg CLI로 hg4j 결과물을 읽었을 때도 동일하게 인식되어야 한다.
        String status = HgTestUtils.hg(parentDir, "status");
        assertTrue(status.contains("! .hgsub"),
                "실제 hg 기준으로도 hg4j 결과물에서 .hgsub이 추적된 채 missing으로 보여야 함: " + status);
        String manifest = HgTestUtils.hg(parentDir, "manifest");
        assertTrue(manifest.contains(".hgsub") && manifest.contains(".hgsubstate"),
                "실제 hg manifest에서도 .hgsub/.hgsubstate 둘 다 남아있어야 함: " + manifest);
        String catState = HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate");
        assertEquals("", catState.trim(), "실제 hg가 읽는 hg4j의 .hgsubstate 내용도 빈 문자열이어야 함");
    }

    /**
     * Gap #2 (명시적 제거): {@code hg remove .hgsub}로 명시적으로 제거하고 커밋하면, 실제 hg는
     * 사용자가 {@code hg remove .hgsubstate}를 따로 하지 않아도 {@code .hgsubstate}까지 함께
     * 추적 해제한다 (오라클로 실측: {@code hg cat}이 "no such file in rev"로 실패). hg4j도
     * 동일해야 한다.
     */
    @Test
    public void hg4jCommitRemovesHgsubstateWhenHgsubExplicitlyRemovedMatchingRealHg(@TempDir Path tempDir) throws Exception {
        // --- 오라클: 실제 hg ---
        File realSubDir = tempDir.resolve("real-sub").toFile();
        HgTestUtils.nativeRepo(realSubDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "hello");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(realSubDir, "add");
        HgTestUtils.hg(realSubDir, "commit", "-u", "T", "-m", "sub c1");

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
        HgTestUtils.hg(tempDir.toFile(), "clone", realSubDir.getAbsolutePath(), new File(realParentDir, "sub").getAbsolutePath());
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "sub = sub\n");
        HgTestUtils.hg(realParentDir, "add", ".hgsub");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "add subrepo");

        HgTestUtils.hg(realParentDir, "remove", ".hgsub");
        HgTestUtils.hg(realParentDir, "commit", "-u", "T", "-m", "remove hgsub via hg remove");

        String oracleManifest = HgTestUtils.hg(realParentDir, "manifest");
        assertFalse(oracleManifest.contains(".hgsub"),
                "오라클: 명시적 hg remove .hgsub 후에는 .hgsub도 .hgsubstate도 manifest에 남으면 안 됨: " + oracleManifest);

        // --- hg4j 재현 ---
        File subDir = tempDir.resolve("sub").toFile();
        HgRepository subRepo = Hg.init().setDirectory(subDir).call();
        Files.writeString(new File(subDir, "hello.txt").toPath(), "hello");
        new AddCommand(subRepo).call();
        new CommitCommand(subRepo).setAuthor("T <t@example.com>").setMessage("sub c1").call();

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();

        Hg.cloneRepository().setSource(subDir.getAbsolutePath()).setDirectory(new File(parentDir, "sub")).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = sub\n");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo").call();

        assertTrue(new RemoveCommand(parentRepo).setFile(".hgsub").call(), "hg4j RemoveCommand가 .hgsub 제거에 성공해야 함");
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("remove hgsub via hg remove").call();

        // 실제 hg CLI로 hg4j 결과물을 읽었을 때, .hgsub과 .hgsubstate 둘 다 더 이상 추적되지 않아야 한다.
        String manifest = HgTestUtils.hg(parentDir, "manifest");
        assertFalse(manifest.contains(".hgsub"),
                "실제 hg가 읽는 hg4j 결과물에서도 .hgsub/.hgsubstate가 manifest에서 사라져야 함: " + manifest);
        AssertionError catFailure = assertThrows(AssertionError.class,
                () -> HgTestUtils.hg(parentDir, "cat", "-r", "tip", ".hgsubstate"),
                "실제 hg가 더 이상 추적되지 않는 .hgsubstate를 tip에서 cat하면 실패해야 함 (오라클과 동일)");
        assertNotNull(catFailure);
        String status = HgTestUtils.hg(parentDir, "status", "-A", "--", ".hgsubstate");
        assertTrue(status.startsWith("?"), "실제 hg 기준 .hgsubstate 파일은 이제 추적되지 않는 상태여야 함: " + status);
    }

    /**
     * Gap #3: git 서브저장소({@code [git]} prefix)를 hg4j {@link CommitCommand}로 커밋하면 실제
     * hg의 {@code gitsubrepo.basestate()}(= {@code git rev-parse HEAD})와 정확히 같은 커밋 sha가
     * {@code .hgsubstate}에 기록되는지 확인한다. 오라클은 real hg 7.2(+{@code
     * [subrepos] git:allowed = true})로 실제 git 서브저장소를 만들어 확보한다.
     */
    @Test
    public void hg4jCommitRecordsGitSubrepoStateMatchingRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isGitInstalled(), "git이 설치되어 있지 않습니다. 건너뜁니다.");

        File gitSubSrc = tempDir.resolve("git-sub-src").toFile();
        gitSubSrc.mkdirs();
        HgTestUtils.git(gitSubSrc, "init", "-q", "-b", "master", ".");
        Files.writeString(new File(gitSubSrc, "g.txt").toPath(), "hello");
        HgTestUtils.git(gitSubSrc, "add", "g.txt");
        HgTestUtils.git(gitSubSrc, "commit", "-q", "-m", "git commit1");
        String gitSha1 = HgTestUtils.git(gitSubSrc, "rev-parse", "HEAD");

        // --- 오라클: 실제 hg + 실제 git 서브저장소 ---
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
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.git(tempDir.toFile(), "clone", "-q", gitSubSrc.getAbsolutePath(), new File(realParentDir, "gitsub").getAbsolutePath());
        HgTestUtils.hgGitAllowed(realParentDir, "add", ".hgsub");
        HgTestUtils.hgGitAllowed(realParentDir, "commit", "-u", "T", "-m", "add git subrepo");

        String oracleHgsubstate = Files.readString(new File(realParentDir, ".hgsubstate").toPath()).trim();
        assertEquals(gitSha1 + " gitsub", oracleHgsubstate,
                "오라클: 실제 hg는 git 서브저장소의 .hgsubstate에 `git rev-parse HEAD` sha를 그대로 기록함");

        // --- hg4j 재현 ---
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.git(tempDir.toFile(), "clone", "-q", gitSubSrc.getAbsolutePath(), new File(parentDir, "gitsub").getAbsolutePath());
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add git subrepo").call();

        String hg4jHgsubstate = Files.readString(new File(parentDir, ".hgsubstate").toPath()).trim();
        assertEquals(gitSha1 + " gitsub", hg4jHgsubstate,
                "hg4j도 git 서브저장소의 .hgsubstate에 동일한 git commit sha를 기록해야 함 (오라클과 byte-for-byte 일치)");

        // 서브저장소에서 새 git 커밋을 만들고 부모를 다시 커밋하면 .hgsubstate가 갱신되어야 한다
        // (real hg는 이 경우 -S 없이도 갱신함 -- git dirty()는 "uncommitted changes"만 보고
        // "checked-out revision과 다름"은 안 보기 때문. 실제 hg로 확인된 동작).
        Files.writeString(new File(parentDir, "gitsub/g.txt").toPath(), "hello\nmore");
        HgTestUtils.git(new File(parentDir, "gitsub"), "commit", "-q", "-a", "-m", "git commit2");
        String gitSha2 = HgTestUtils.git(new File(parentDir, "gitsub"), "rev-parse", "HEAD");
        assertNotEquals(gitSha1, gitSha2);

        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("bump git subrepo").call();
        String hg4jHgsubstate2 = Files.readString(new File(parentDir, ".hgsubstate").toPath()).trim();
        assertEquals(gitSha2 + " gitsub", hg4jHgsubstate2,
                "git 서브저장소가 committed-but-different-HEAD 상태이면 -S 없이도 .hgsubstate가 새 sha로 갱신되어야 함 (오라클 동작과 동일)");
    }

    /**
     * Gap #3 (dirty 차단/재귀 커밋): git 서브저장소에 커밋되지 않은 로컬 변경이 있으면 실제 hg와
     * 동일한 메시지로 부모 커밋이 거부되고, {@code --subrepos} 상당 플래그를 켜면 {@code git
     * commit -a}가 대신 실행되어 새 sha가 기록되는지 확인한다 (오라클: 실제 hg + git으로 동일한
     * 시나리오를 나란히 재현).
     */
    @Test
    public void hg4jCommitBlocksThenRecursivelyCommitsDirtyGitSubrepoMatchingRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isGitInstalled(), "git이 설치되어 있지 않습니다. 건너뜁니다.");

        File gitSubSrc = tempDir.resolve("git-sub-src").toFile();
        gitSubSrc.mkdirs();
        HgTestUtils.git(gitSubSrc, "init", "-q", "-b", "master", ".");
        Files.writeString(new File(gitSubSrc, "g.txt").toPath(), "hello");
        HgTestUtils.git(gitSubSrc, "add", "g.txt");
        HgTestUtils.git(gitSubSrc, "commit", "-q", "-m", "git commit1");

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
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.git(tempDir.toFile(), "clone", "-q", gitSubSrc.getAbsolutePath(), new File(realParentDir, "gitsub").getAbsolutePath());
        HgTestUtils.hgGitAllowed(realParentDir, "add", ".hgsub");
        HgTestUtils.hgGitAllowed(realParentDir, "commit", "-u", "T", "-m", "add git subrepo");

        Files.writeString(new File(realParentDir, "gitsub/g.txt").toPath(), "dirty");
        AssertionError oracleBlock = assertThrows(AssertionError.class,
                () -> HgTestUtils.hgGitAllowed(realParentDir, "commit", "-u", "T", "-m", "should fail"));
        assertTrue(oracleBlock.getMessage().contains("uncommitted changes in subrepository \"gitsub\""),
                "오라클: 실제 hg의 git 서브저장소 dirty 차단 메시지: " + oracleBlock.getMessage());

        HgTestUtils.hgGitAllowed(realParentDir, "commit", "-S", "-u", "T", "-m", "recursive commit for git subrepo");
        String oracleGitLog = HgTestUtils.git(new File(realParentDir, "gitsub"), "log", "--oneline");
        assertTrue(oracleGitLog.contains("recursive commit for git subrepo"),
                "오라클: 실제 hg -S가 git 서브저장소에 git commit -a를 실행해야 함: " + oracleGitLog);

        // --- hg4j 재현 ---
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.git(tempDir.toFile(), "clone", "-q", gitSubSrc.getAbsolutePath(), new File(parentDir, "gitsub").getAbsolutePath());
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add git subrepo").call();

        Files.writeString(new File(parentDir, "gitsub/g.txt").toPath(), "dirty");
        HgValidationException blocked = assertThrows(HgValidationException.class,
                () -> new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("should fail").call());
        assertTrue(blocked.getMessage().contains("uncommitted changes in subrepository \"gitsub\"")
                        && blocked.getMessage().contains("--subrepos"),
                "hg4j의 차단 메시지가 실제 hg와 동일한 형태여야 함: " + blocked.getMessage());

        new CommitCommand(parentRepo)
                .setAuthor("T <t@example.com>")
                .setMessage("recursive commit for git subrepo")
                .setSubrepos(true)
                .call();

        String gitLog = HgTestUtils.git(new File(parentDir, "gitsub"), "log", "--oneline");
        assertTrue(gitLog.contains("recursive commit for git subrepo"),
                "hg4j의 -S 상당(setSubrepos(true))도 git 서브저장소에 git commit -a를 실행해야 함: " + gitLog);
        String newSha = HgTestUtils.git(new File(parentDir, "gitsub"), "rev-parse", "HEAD");
        String hgsubstate = Files.readString(new File(parentDir, ".hgsubstate").toPath()).trim();
        assertEquals(newSha + " gitsub", hgsubstate,
                "재귀 커밋 후 .hgsubstate가 git 서브저장소의 새 HEAD sha로 갱신되어야 함");
    }

    /**
     * Gap #3 (로컬 미체크아웃 abort): .hgsub에 git 서브저장소가 선언되었지만 로컬에 체크아웃되지
     * 않은 경우, 실제 hg는 hg 서브저장소와 달리 null 리비전으로 폴백하지 않고 부모 커밋 자체를
     * abort한다 (오라클로 실측). hg4j도 동일해야 한다.
     */
    @Test
    public void hg4jCommitAbortsForNotCheckedOutGitSubrepoMatchingRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isGitInstalled(), "git이 설치되어 있지 않습니다. 건너뜁니다.");

        File gitSubSrc = tempDir.resolve("git-sub-src").toFile();
        gitSubSrc.mkdirs();
        HgTestUtils.git(gitSubSrc, "init", "-q", "-b", "master", ".");
        Files.writeString(new File(gitSubSrc, "g.txt").toPath(), "hello");
        HgTestUtils.git(gitSubSrc, "add", "g.txt");
        HgTestUtils.git(gitSubSrc, "commit", "-q", "-m", "git commit1");

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
        Files.writeString(new File(realParentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        HgTestUtils.hgGitAllowed(realParentDir, "add", ".hgsub");
        // 서브저장소를 로컬에 전혀 clone하지 않은 채로 커밋 시도.
        AssertionError oracleAbort = assertThrows(AssertionError.class,
                () -> HgTestUtils.hgGitAllowed(realParentDir, "commit", "-u", "T", "-m", "add subrepo not checked out"));
        assertTrue(oracleAbort.getMessage().contains("No such file or directory"),
                "오라클: 실제 hg는 로컬에 없는 git 서브저장소에 대해 null 리비전 폴백 없이 abort함: " + oracleAbort.getMessage());

        // --- hg4j 재현 ---
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, "init.txt").toPath(), "init");
        new AddCommand(parentRepo).call();
        new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("parent init").call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "gitsub = [git]" + gitSubSrc.getAbsolutePath() + "\n");
        new AddCommand(parentRepo).call();

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new CommitCommand(parentRepo).setAuthor("T <t@example.com>").setMessage("add subrepo not checked out").call());
        assertTrue(ex.getMessage().contains("No such file or directory"),
                "hg4j도 로컬에 없는 git 서브저장소에 대해 동일하게 abort해야 함 (null 리비전 폴백 없음): " + ex.getMessage());
    }

    /**
     * Gap #4: {@code UpdateCommand}의 재귀 서브저장소 체크아웃이, pin된 리비전이 이미 로컬에
     * 있으면 pull을 시도하지 않아야 한다 (실제 hg의 {@code hgsubrepo._fetch()}가
     * {@code hasunlinkedrev}로 로컬 존재 여부를 먼저 확인하는 것과 동일 -- mercurial/subrepo.py
     * 직접 확인). 서브저장소 소스 경로를 일부러 존재하지 않는 곳으로 지정해, pull이 실제로
     * 시도되면 반드시 실패 로그가 남도록 만들어 간접 검증한다: pin된 리비전이 로컬에 이미 있는
     * 두 리비전 사이를 오갈 때 그런 실패 로그가 전혀 남지 않아야 한다.
     */
    @Test
    public void hg4jUpdateSkipsPullWhenSubrepoRevisionAlreadyLocalMatchingRealHg(@TempDir Path tempDir) throws Exception {
        File subDir = tempDir.resolve("sub").toFile();
        HgTestUtils.nativeRepo(subDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(subDir, "add");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub v1");
        String subV1 = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");
        Files.writeString(new File(subDir, "hello.txt").toPath(), "v2");
        HgTestUtils.hg(subDir, "commit", "-u", "T", "-m", "sub v2");
        String subV2 = HgTestUtils.hg(subDir, "log", "-r", "tip", "--template", "{node}");

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

        // 서브저장소를 FULL로 clone (v1, v2 둘 다 로컬에 존재하게 만듦), v1으로 체크아웃해 pin.
        HgTestUtils.hg(tempDir.toFile(), "clone", subDir.getAbsolutePath(), new File(parentDir, "sub").getAbsolutePath());
        HgTestUtils.hg(new File(parentDir, "sub"), "update", "-r", subV1);
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = " + subDir.getAbsolutePath() + "\n");
        HgTestUtils.hg(parentDir, "add", ".hgsub");
        HgTestUtils.hg(parentDir, "commit", "-u", "T", "-m", "pin sub@v1");
        String parentPinV1 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");

        HgTestUtils.hg(new File(parentDir, "sub"), "update", "-r", subV2);
        HgTestUtils.hg(parentDir, "commit", "-S", "-u", "T", "-m", "pin sub@v2");
        String parentPinV2 = HgTestUtils.hg(parentDir, "log", "-r", "tip", "--template", "{node}");

        // .hgsub의 소스 URL을 존재하지 않는 경로로 바꿔치기 -- pull이 실제로 시도되면 반드시
        // 예외/실패 로그가 남는다 (real hg 소스 확인: hasunlinkedrev로 로컬에 있으면 이 네트워크
        // 시도 자체를 건너뛴다).
        File bogusSource = tempDir.resolve("no-such-source-anymore").toFile();
        HgRepository parentRepo = new HgRepository(parentDir);

        List<LogRecord> captured = new ArrayList<>();
        Handler captureHandler = new Handler() {
            @Override public void publish(LogRecord record) { captured.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger updateLogger = Logger.getLogger(UpdateCommand.class.getName());
        updateLogger.addHandler(captureHandler);
        try {
            // pin 커밋들을 hg4j UpdateCommand로 오가되, .hgsub의 소스가 이제 무효임을 시뮬레이션
            // 하기 위해 실제 파일을 덮어쓴다 (hg4j는 .hgsub 파일의 현재 내용을 그대로 읽는다).
            Files.writeString(new File(parentDir, ".hgsub").toPath(), "sub = " + bogusSource.getAbsolutePath() + "\n");

            new UpdateCommand(parentRepo).setRevision(parentPinV1).setForce(true).call();
            assertEquals("v1", Files.readString(new File(parentDir, "sub/hello.txt").toPath()),
                    "pin된 리비전이 로컬에 이미 있으면 (소스가 무효여도) 정상적으로 체크아웃되어야 함");

            new UpdateCommand(parentRepo).setRevision(parentPinV2).setForce(true).call();
            assertEquals("v2", Files.readString(new File(parentDir, "sub/hello.txt").toPath()),
                    "pin된 리비전이 로컬에 이미 있으면 (소스가 무효여도) 정상적으로 체크아웃되어야 함");
        } finally {
            updateLogger.removeHandler(captureHandler);
        }

        boolean anyPullFailureLogged = captured.stream()
                .anyMatch(r -> r.getMessage() != null && r.getMessage().contains("Failed to pull subrepo"));
        assertFalse(anyPullFailureLogged,
                "리비전이 이미 로컬에 있으면 pull을 아예 시도하지 않아야 함 -- 무효한 소스에 대한 "
                        + "pull 실패 로그가 있으면 안 됨(실제 hg의 hasunlinkedrev 사전 체크와 동일 동작이어야 함)");
    }
}
