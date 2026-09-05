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
        // Backlog #35: hg4j now defaults small new v1 filelogs to inline (matching real hg, which
        // has no size-threshold split logic in hg4j -- it's purely "new file starts inline") --
        // this test's premise (copying b.txt's .d onto a.txt's) needs a real, separate .d file to
        // exist, so pre-touch b.txt's filelog index as an already-existing (empty) file BEFORE the
        // commit creates it, which makes hg4j treat it as "reopening" (non-inline) rather than
        // "brand new" (inline).
        File bFilelogIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "b.txt");
        bFilelogIdx.getParentFile().mkdirs();
        bFilelogIdx.createNewFile();
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
    public void testBackoutRemovesFileAddedByTheBackedOutRevision(@TempDir Path tempDir) throws Exception {
        // Backlog #39 wave 4: BackoutCommand now enforces real hg's own precondition that the
        // working copy be clean before backing out (scmutil.bail_if_changed), so the previous
        // version of this test -- which staged an uncommitted `hg remove` before calling backout,
        // to reach an internal "file already gone from disk" branch inside a now-removed
        // RemoveCommand delegation -- no longer reflects a reachable, real-hg-compatible scenario.
        // This instead verifies the same end result (backing out the revision that added a file
        // deletes it) through the command's normal, real-hg-compatible direct-revert path.
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

        byte[] c3 = new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call();
        assertNotNull(c3);
        assertFalse(added.exists(), "added.txt must be deleted by backing out the revision that added it");
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
