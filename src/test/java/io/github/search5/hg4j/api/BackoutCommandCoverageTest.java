package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link BackoutCommand}, focused on the defensive validation
 * branches inside {@code writeFileContentByNode} that {@code TrackCMissingCommandsInteropTest}
 * does not exercise: a missing filelog on disk (store corruption / manual removal) and a
 * filelog that exists but lacks the specific file revision being restored.
 */
public class BackoutCommandCoverageTest {

    @Test
    public void testBackoutFailsWhenFilelogMissingForModifiedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "v1");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(f.toPath(), "v2");
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        // 저장소를 손상시켜 a.txt의 filelog(.i/.d)를 완전히 제거한다 — backout이 부모 시점
        // 콘텐츠를 복원하려 할 때 filelog 자체가 없는 상황을 재현한다.
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists(), "테스트 전제: filelog index가 존재해야 함");
        Files.deleteIfExists(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());
        repo.clearRevlogCache();

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call());
        assertTrue(ex.getMessage().contains("Filelog not found for path: a.txt"), ex.getMessage());
    }

    @Test
    public void testBackoutFailsWhenFileRevisionNotFoundInFilelog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File a = new File(repoDir, "a.txt");
        File b = new File(repoDir, "b.txt");
        Files.writeString(a.toPath(), "a-v1");
        Files.writeString(b.toPath(), "b-v1");
        hg.add().addFile("a.txt").call();
        hg.add().addFile("b.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(a.toPath(), "a-v2");
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        // b.txt의 filelog를 a.txt의 filelog 자리에 덮어써서, a.txt 경로의 filelog는
        // "존재는 하지만" backout이 복원하려는 (a.txt의) 파일 리비전 노드는 담고 있지 않은
        // 상태를 재현한다.
        File aIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File aDat = new File(aIdx.getPath().substring(0, aIdx.getPath().length() - 2) + ".d");
        File bIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "b.txt");
        File bDat = new File(bIdx.getPath().substring(0, bIdx.getPath().length() - 2) + ".d");
        assertTrue(bIdx.exists() && bDat.exists(), "테스트 전제: b.txt filelog가 존재해야 함");
        Files.copy(bIdx.toPath(), aIdx.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(bDat.toPath(), aDat.toPath(), StandardCopyOption.REPLACE_EXISTING);
        repo.clearRevlogCache();

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call());
        assertTrue(ex.getMessage().contains("File revision not found in filelog: a.txt"), ex.getMessage());
    }

    @Test
    public void testBackoutSkipsRemovalWhenAddedFileAlreadyGoneFromDisk(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File keep = new File(repoDir, "keep.txt");
        Files.writeString(keep.toPath(), "keep");
        hg.add().addFile("keep.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        File added = new File(repoDir, "added.txt");
        Files.writeString(added.toPath(), "added by c2");
        hg.add().addFile("added.txt").call();
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        // 대상 리비전이 added.txt를 추가했지만, backout 시점에는 이미(외부 요인으로) 디스크에서
        // 사라진 상태 — RemoveCommand 호출을 건너뛰는 분기(diskFile.exists() == false)를 재현.
        // dirstate도 미리 removed로 갱신해 둔다(디스크에 파일이 없으므로 force 없이도 안전하게
        // 처리됨) — 그래야 backout 마지막 단계의 CommitCommand가 "추적 중인데 디스크에 없는
        // 파일"로 걸리지 않는다.
        assertTrue(added.delete(), "테스트 전제: added.txt를 미리 지울 수 있어야 함");
        new RemoveCommand(repo).setFile("added.txt").call();

        byte[] c3 = new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call();
        assertNotNull(c3);
        assertFalse(added.exists(), "added.txt는 여전히 디스크에 없어야 함");
    }

    @Test
    public void testBackoutWithEmptyMessageFallsBackToDefault(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "original");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();
        Files.writeString(f.toPath(), "changed");
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        byte[] c3 = new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T")
                .setMessage("").call();
        assertNotNull(c3);

        String hex = NodeIdUtil.toHex(c2).substring(0, 12);
        // setMessage("")는 빈 문자열이므로 기본 메시지("Backed out changeset <hex>")로 대체돼야 함.
        String nativeLog = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Backed out changeset " + hex, nativeLog);
    }
}
