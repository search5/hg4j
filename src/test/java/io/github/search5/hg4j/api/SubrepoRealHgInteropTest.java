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
import java.util.Map;

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
}
