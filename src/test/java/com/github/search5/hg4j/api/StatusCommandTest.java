package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.treewalk.HgTreeFilter;
import java.util.List;

public class StatusCommandTest {

    @Test
    public void testStatusCommandFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial status of empty repo
        Status st0 = new StatusCommand(repo).call();
        assertTrue(st0.getAdded().isEmpty());
        assertTrue(st0.getModified().isEmpty());
        assertTrue(st0.getRemoved().isEmpty());
        assertTrue(st0.getClean().isEmpty());
        assertTrue(st0.getUntracked().isEmpty());

        // 2. Create an untracked file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Status");
        
        Status st1 = new StatusCommand(repo).call();
        assertTrue(st1.getAdded().isEmpty());
        assertEquals(1, st1.getUntracked().size());
        assertTrue(st1.getUntracked().contains("a.txt"));

        // 3. Add the file
        new AddCommand(repo).call();

        Status st2 = new StatusCommand(repo).call();
        assertEquals(1, st2.getAdded().size());
        assertTrue(st2.getAdded().contains("a.txt"));
        assertTrue(st2.getUntracked().isEmpty());

        // 4. Commit the file
        new CommitCommand(repo).setMessage("Commit a").call();

        Status st3 = new StatusCommand(repo).call();
        assertTrue(st3.getAdded().isEmpty());
        assertEquals(1, st3.getClean().size());
        assertTrue(st3.getClean().contains("a.txt"));

        // 5. Modify the file
        Files.writeString(f1.toPath(), "Hello Status Modified");

        Status st4 = new StatusCommand(repo).call();
        assertTrue(st4.getClean().isEmpty());
        assertEquals(1, st4.getModified().size());
        assertTrue(st4.getModified().contains("a.txt"));

        // 6. Remove the file (simulated)
        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("a.txt", new Dirstate.Entry('r', 0644, 0, 0));
        repo.writeDirstate(dirstate);
        assertTrue(f1.delete()); // delete from disk

        Status st5 = new StatusCommand(repo).call();
        assertTrue(st5.getModified().isEmpty());
        assertEquals(1, st5.getRemoved().size());
        assertTrue(st5.getRemoved().contains("a.txt"));
    }

    @Test
    public void testStatusCommandTreeFilter(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create src/a.txt (untracked) and doc/b.txt (untracked)
        File srcDir = new File(repoDir, "src");
        srcDir.mkdirs();
        File fa = new File(srcDir, "a.txt");
        Files.writeString(fa.toPath(), "Hello Src");

        File docDir = new File(repoDir, "doc");
        docDir.mkdirs();
        File fb = new File(docDir, "b.txt");
        Files.writeString(fb.toPath(), "Hello Doc");

        // Status without filter
        Status stAll = new StatusCommand(repo).call();
        assertEquals(2, stAll.getUntracked().size());

        // Status with filter (only "src/")
        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(List.of("src/"), List.of());
        Status stFiltered = new StatusCommand(repo).setTreeFilter(filter).call();

        assertEquals(1, stFiltered.getUntracked().size());
        assertTrue(stFiltered.getUntracked().contains("src/a.txt"));
        assertFalse(stFiltered.getUntracked().contains("doc/b.txt"));
    }

    @Test
    public void testFastPathVsSlowPath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. 다양한 상태의 파일 구성 단계
        
        // Clean, Modified, Removed 파일은 처음에 생성
        File f2 = new File(repoDir, "clean.txt");
        Files.writeString(f2.toPath(), "clean content");

        File f3 = new File(repoDir, "modified.txt");
        Files.writeString(f3.toPath(), "modified content");

        File f4 = new File(repoDir, "removed.txt");
        Files.writeString(f4.toPath(), "removed content");

        // 이 세 파일들을 추적하기 위해 add
        new AddCommand(repo).call();
        // 커밋하여 clean 상태로 변경
        new CommitCommand(repo).setMessage("Initial").call();

        // 커밋 후에 added.txt 파일 생성 및 add -> Added 상태
        File f1 = new File(repoDir, "added.txt");
        Files.writeString(f1.toPath(), "added content");
        new AddCommand(repo).addFile("added.txt").call();

        // f3 수정 -> Modified 상태
        Files.writeString(f3.toPath(), "modified content - changed");

        // f4 dirstate 'r'로 변경 및 디스크에서 물리 삭제 -> Removed 상태
        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("removed.txt", new Dirstate.Entry('r', 0644, 0, 0));
        repo.writeDirstate(dirstate);
        assertTrue(f4.delete());

        // untracked.txt 파일 생성 (add하지 않음) -> Untracked 상태
        File f5 = new File(repoDir, "untracked.txt");
        Files.writeString(f5.toPath(), "untracked content");

        // 2. Fast Path로 Status 호출 (treeFilter == ALL)
        Status fastStatus = new StatusCommand(repo).setTreeFilter(HgTreeFilter.ALL).call();

        // 3. Slow Path로 Status 호출 (custom filter를 써서 treeFilter == ALL 조건 우회)
        HgTreeFilter customFilter = new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                return true;
            }
        };
        Status slowStatus = new StatusCommand(repo).setTreeFilter(customFilter).call();

        // 4. 두 Status 결과 비교 및 완벽한 일치 증명
        assertEquals(slowStatus.getAdded(), fastStatus.getAdded());
        assertEquals(slowStatus.getModified(), fastStatus.getModified());
        assertEquals(slowStatus.getRemoved(), fastStatus.getRemoved());
        assertEquals(slowStatus.getClean(), fastStatus.getClean());
        assertEquals(slowStatus.getUntracked(), fastStatus.getUntracked());

        // 개별 리스트 내용 확인
        assertTrue(fastStatus.getAdded().contains("added.txt"));
        assertTrue(fastStatus.getModified().contains("modified.txt"));
        assertTrue(fastStatus.getRemoved().contains("removed.txt"));
        assertTrue(fastStatus.getClean().contains("clean.txt"));
        assertTrue(fastStatus.getUntracked().contains("untracked.txt"));
    }

    @Test
    public void detectsPhysicallyDeletedFileStillMarkedNormalAsRemoved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "gone.txt");
        Files.writeString(f.toPath(), "will be deleted");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add gone.txt").call();

        assertTrue(f.delete()); // physically deleted without updating dirstate (state stays 'n')

        Status fast = new StatusCommand(repo).call();
        assertEquals(1, fast.getRemoved().size());
        assertTrue(fast.getRemoved().contains("gone.txt"));

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertEquals(1, slow.getRemoved().size());
        assertTrue(slow.getRemoved().contains("gone.txt"));
    }

    @Test
    public void detectsRacilyModifiedFileWhenSizeAndMtimeMatchButContentDiffers(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "racy.txt");
        String original = "0123456789";
        Files.writeString(f.toPath(), original);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add racy.txt").call();

        // Overwrite with different content of the exact same byte length, then force the
        // dirstate entry and the dirstate file's own mtime to (falsely) agree with the disk
        // file's size/mtime, so the cheap size/mtime check alone would call it "clean" and
        // only the racy-write content comparison against the filelog can catch the change.
        String rewritten = "9876543210";
        Files.writeString(f.toPath(), rewritten);
        long diskTime = f.lastModified() / 1000;

        Dirstate dirstate = repo.getDirstate();
        Dirstate.Entry entry = dirstate.getEntries().get("racy.txt");
        dirstate.addEntry("racy.txt", new Dirstate.Entry(entry.getState(), entry.getMode(), rewritten.length(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status fast = new StatusCommand(repo).call();
        assertTrue(fast.getModified().contains("racy.txt"), "Fast path must detect the racy write via filelog content comparison");
        assertFalse(fast.getClean().contains("racy.txt"));

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slow.getModified().contains("racy.txt"), "Slow path must detect the racy write via filelog content comparison");
        assertFalse(slow.getClean().contains("racy.txt"));
    }

    @Test
    public void unchangedSymlinkWhoseTargetContentIsLongerThanItsOwnPathStringIsClean(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // The symlink's own "content" (its target path string, e.g. "target.txt") is much
        // shorter than the file it points to — this is exactly the mismatch that a
        // File.length()-based (link-following) size check gets wrong.
        Files.writeString(new File(repoDir, "target.txt").toPath(),
                "this is a much longer target file content than the link's own path string");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add symlink").call();

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("link.txt"),
                "Untouched symlink must be reported clean regardless of its target's content size, got: " + status.getModified());
        assertFalse(status.getModified().contains("link.txt"));

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slowStatus = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slowStatus.getClean().contains("link.txt"));
    }

    @Test
    public void symlinkRetargetedToADifferentPathIsReportedModified(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        Files.writeString(new File(repoDir, "much-longer-name.txt").toPath(), "b");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("a.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add symlink to a.txt").call();

        Files.delete(link.toPath());
        Files.createSymbolicLink(link.toPath(), Path.of("much-longer-name.txt"));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getModified().contains("link.txt"),
                "Symlink re-pointed at a different target must be reported modified, got clean=" + status.getClean());
    }
}
