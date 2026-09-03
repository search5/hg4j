package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RollbackCommandTest {

    @Test
    public void testRollbackCommand(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        // 1. Commit 1
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content 1");
        hg.add().addFile("a.txt").call();
        byte[] commit1 = hg.commit().setMessage("Commit 1").call();
        String hex1 = toHex(commit1).substring(0, 40);

        // 2. Commit 2
        Files.writeString(f1.toPath(), "content 2");
        byte[] commit2 = hg.commit().setMessage("Commit 2").call();
        String hex2 = toHex(commit2).substring(0, 40);

        // 2차 커밋 완료 후 저장소의 커밋 개수가 2개임을 확인
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(2, changelog.getRevisionCount());

        // 3. Rollback 실행 (Commit 2를 되돌림)
        hg.rollback();

        // 4. 검증: 저장소의 커밋 개수가 다시 1개로 줄어들고 1차 커밋 노드가 복구되었는지 확인
        repo.clearRevlogCache();
        changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(1, changelog.getRevisionCount());
        assertEquals(hex1, toHex(changelog.getIndexRecord(0).getNodeId()).substring(0, 40));

        // dirstate 부모도 Commit 1로 되돌아갔는지 확인
        byte[] parent1 = repo.getDirstate().getParent1();
        assertEquals(hex1, toHex(parent1).substring(0, 40));
    }

    @Test
    public void testRollbackThrowsWhenNoUndoFileExists(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RollbackCommand(repo).call());
        assertTrue(ex.getMessage().contains("No rollback information available"));
    }

    @Test
    public void testRollbackDeletesFileWhenOrigSizeIsZeroAndSkipsMalformedLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File storeDir = repo.getStoreDir();
        File toDelete = new File(storeDir, "should-be-deleted.i");
        Files.writeString(toDelete.toPath(), "some bytes that will be removed entirely");
        assertTrue(toDelete.exists());

        File toTruncate = new File(storeDir, "should-be-truncated.i");
        Files.writeString(toTruncate.toPath(), "0123456789");

        File missingTarget = new File(storeDir, "does-not-exist.i");

        File undoFile = new File(storeDir, "undo");
        Files.writeString(undoFile.toPath(),
                "\n" +
                "line-without-a-tab-should-be-skipped\n" +
                "should-be-deleted.i\t0\n" +
                "should-be-truncated.i\t4\n" +
                "does-not-exist.i\t3\n");

        new RollbackCommand(repo).call();

        assertFalse(toDelete.exists(), "origSize=0 항목은 파일이 완전히 삭제되어야 함");
        assertEquals(4, toTruncate.length(), "origSize>0이고 파일이 존재하면 해당 크기로 truncate되어야 함");
        assertFalse(missingTarget.exists(), "원래 존재하지 않던 파일은 truncate 시도 없이 무시되어야 함");
        assertFalse(undoFile.exists(), "rollback 완료 후 undo 파일은 정리되어야 함");
    }

    @Test
    public void testRollbackRestoresBookmarksFromBackupAndClearsCurrentPointer(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File storeDir = repo.getStoreDir();
        File undoFile = new File(storeDir, "undo");
        Files.writeString(undoFile.toPath(), "");

        File hgDir = new File(repoDir, ".hg");
        File bookmarksFile = new File(hgDir, "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "stale-bookmark-content");

        File undoBookmarks = new File(hgDir, "undo.backup.bookmarks");
        Files.writeString(undoBookmarks.toPath(), "deadbeef00000000000000000000000000000000 restored-bookmark");

        File curBkFile = new File(hgDir, "bookmarks.current");
        Files.writeString(curBkFile.toPath(), "active-bookmark");
        assertTrue(curBkFile.exists());

        new RollbackCommand(repo).call();

        assertEquals("deadbeef00000000000000000000000000000000 restored-bookmark",
                Files.readString(bookmarksFile.toPath()),
                "bookmarks 백업이 존재하면 그 내용으로 복원되어야 함");
        assertFalse(curBkFile.exists(), "bookmarks.current 포인터는 항상 정리되어야 함");
        assertFalse(undoBookmarks.exists(), "rollback 완료 후 undo.backup.bookmarks는 정리되어야 함");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
