package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import java.util.List;
import java.util.Arrays;

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

    @Test
    public void slowPathReportsFileAbsentFromBothDiskAndDirstateAsUntrackedNotRemoved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "vanished.txt");
        Files.writeString(f.toPath(), "will vanish from disk and dirstate");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add vanished.txt").call();

        assertTrue(f.delete());
        Dirstate dirstate = repo.getDirstate();
        dirstate.removeEntry("vanished.txt");
        repo.writeDirstate(dirstate);

        // TreeWalk.getState() defaults to '?' for a tree the path is untracked in, so once
        // the path is untracked in the working tree (inWorking == false), the "workingState
        // == '?'" branch always fires first, regardless of whether the path is still present
        // in the parent manifest (inParent). A "tracked in parent, absent from both disk and
        // dirstate" file therefore surfaces as Untracked, not Removed -- Removed is reserved
        // for paths the working tree still knows about (via dirstate state 'r' or a disk-file
        // check), matching the fast path's dirstate-driven view of "removed".
        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slow.getUntracked().contains("vanished.txt"),
                "File present in parent manifest but absent from both disk and dirstate is reported Untracked, got: " + slow);
        assertFalse(slow.getRemoved().contains("vanished.txt"));
    }

    @Test
    public void racyCheckWithNoCommitsTreatsSizeMtimeMatchAsCleanBecauseNoParentToCompare(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "orphan.txt");
        String content = "content with no real commit backing it";
        Files.writeString(f.toPath(), content);
        long diskTime = f.lastModified() / 1000;

        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("orphan.txt", new Dirstate.Entry('n', 0644, content.length(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("orphan.txt"),
                "With no commits at all, dirstate parent is null so racy check has nothing to compare against and must fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("orphan.txt"));
    }

    @Test
    public void racyCheckWhenChangelogIndexMissingTreatsAsCleanEvenWithFakeParentHash(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "orphan2.txt");
        String content = "content with a fake parent hash but no changelog file";
        Files.writeString(f.toPath(), content);
        long diskTime = f.lastModified() / 1000;

        Dirstate dirstate = repo.getDirstate();
        byte[] fakeParent = new byte[20];
        Arrays.fill(fakeParent, (byte) 0x7A);
        dirstate.setParents(fakeParent, new byte[20]);
        dirstate.addEntry("orphan2.txt", new Dirstate.Entry('n', 0644, content.length(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        assertFalse(new File(repo.getStoreDir(), "00changelog.i").exists(),
                "precondition: no commit has ever happened, so store/00changelog.i must not exist");

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("orphan2.txt"),
                "Missing changelog index must make the racy check fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("orphan2.txt"));
    }

    @Test
    public void racyCheckWhenParentHashNotInChangelogTreatsAsCleanEvenThoughHistoryExists(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "real.txt");
        Files.writeString(f.toPath(), "real committed content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add real.txt").call();

        String rewritten = "different content entirely";
        Files.writeString(f.toPath(), rewritten);
        long diskTime = f.lastModified() / 1000;

        Dirstate dirstate = repo.getDirstate();
        Dirstate.Entry entry = dirstate.getEntries().get("real.txt");
        dirstate.addEntry("real.txt", new Dirstate.Entry(entry.getState(), entry.getMode(), rewritten.length(), diskTime));
        byte[] bogusParent = new byte[20];
        Arrays.fill(bogusParent, (byte) 0xFF);
        dirstate.setParents(bogusParent, new byte[20]);
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("real.txt"),
                "A dirstate parent hash absent from the changelog must make the racy check fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("real.txt"));
    }

    @Test
    public void racyCheckWhenPathAbsentFromParentManifestTreatsAsCleanDespiteMatchingSizeAndTime(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File real = new File(repoDir, "real2.txt");
        Files.writeString(real.toPath(), "the only file actually committed");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add real2.txt").call();

        File ghost = new File(repoDir, "ghost.txt");
        String ghostContent = "never committed, only fabricated in dirstate";
        Files.writeString(ghost.toPath(), ghostContent);
        long diskTime = ghost.lastModified() / 1000;

        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("ghost.txt", new Dirstate.Entry('n', 0644, ghostContent.length(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("ghost.txt"),
                "A path with a valid parent commit but absent from that commit's manifest must fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("ghost.txt"));
    }

    @Test
    public void racyCheckSwallowsCorruptedFilelogDataAndFallsBackToCleanOnSizeMatch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        // Backlog #35: pre-touch the filelog index as an already-existing (empty) file so hg4j
        // treats it as "reopening" (non-inline) rather than "brand new" (inline), giving this
        // test a real, separate .d file to zero out.
        File corruptFilelogIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "corrupt.txt");
        corruptFilelogIdx.getParentFile().mkdirs();
        corruptFilelogIdx.createNewFile();
        File f = new File(repoDir, "corrupt.txt");
        String content = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789content long enough to compress";
        Files.writeString(f.toPath(), content);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add corrupt.txt").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "corrupt.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flDat.exists(), "precondition: filelog data file must exist after commit");
        Files.write(flDat.toPath(), new byte[0]);

        long diskTime = f.lastModified() / 1000;
        Dirstate dirstate = repo.getDirstate();
        Dirstate.Entry entry = dirstate.getEntries().get("corrupt.txt");
        dirstate.addEntry("corrupt.txt", new Dirstate.Entry(entry.getState(), entry.getMode(), entry.getSize(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = assertDoesNotThrow(() -> new StatusCommand(repo).call(),
                "A corrupted filelog during the racy-write content comparison must be swallowed, not propagated");
        assertTrue(status.getClean().contains("corrupt.txt"),
                "Corrupted filelog content comparison must fall back to clean on a matching size/mtime, got: " + status.getModified());
        assertFalse(status.getModified().contains("corrupt.txt"));
    }

    @Test
    public void racyCheckSwallowsCorruptedFilelogDataInSlowPathAndFallsBackToCleanOnSizeMatch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        // Backlog #35: pre-touch the filelog index as an already-existing (empty) file so hg4j
        // treats it as "reopening" (non-inline) rather than "brand new" (inline), giving this
        // test a real, separate .d file to zero out.
        File corrupt2FilelogIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "corrupt2.txt");
        corrupt2FilelogIdx.getParentFile().mkdirs();
        corrupt2FilelogIdx.createNewFile();
        File f = new File(repoDir, "corrupt2.txt");
        String content = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789content long enough to compress too";
        Files.writeString(f.toPath(), content);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add corrupt2.txt").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "corrupt2.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flDat.exists(), "precondition: filelog data file must exist after commit");
        Files.write(flDat.toPath(), new byte[0]);

        long diskTime = f.lastModified() / 1000;
        Dirstate dirstate = repo.getDirstate();
        Dirstate.Entry entry = dirstate.getEntries().get("corrupt2.txt");
        dirstate.addEntry("corrupt2.txt", new Dirstate.Entry(entry.getState(), entry.getMode(), entry.getSize(), diskTime));
        repo.writeDirstate(dirstate);
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status status = assertDoesNotThrow(() -> new StatusCommand(repo).setTreeFilter(customFilter).call(),
                "A corrupted filelog during the slow path's racy-write content comparison must be swallowed, not propagated");
        assertTrue(status.getClean().contains("corrupt2.txt"),
                "Corrupted filelog content comparison must fall back to clean on a matching size/mtime, got: " + status.getModified());
        assertFalse(status.getModified().contains("corrupt2.txt"));
    }

    @Test
    public void racyCheckWhenFilelogIndexMissingTreatsAsCleanDespiteValidParentHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "noindex.txt");
        Files.writeString(f.toPath(), "content backed by a manifest entry with no filelog index file");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add noindex.txt").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "noindex.txt");
        assertTrue(flIdx.exists(), "precondition: filelog index must exist right after commit");
        assertTrue(flIdx.delete());

        long diskTime = f.lastModified() / 1000;
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("noindex.txt"),
                "A manifest entry whose filelog index file is missing must make the racy check fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("noindex.txt"));
    }

    @Test
    public void racyCheckWhenFilelogRevisionMissingTreatsAsCleanDespiteValidParentHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        // Backlog #35: pre-touch the filelog index as an already-existing (empty) file so hg4j
        // treats it as "reopening" (non-inline) rather than "brand new" (inline), giving this
        // test a real, separate .d file to zero out alongside the index.
        File norevFilelogIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "norev.txt");
        norevFilelogIdx.getParentFile().mkdirs();
        norevFilelogIdx.createNewFile();
        File f = new File(repoDir, "norev.txt");
        Files.writeString(f.toPath(), "content whose filelog will be emptied out after commit");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add norev.txt").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "norev.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists() && flDat.exists(), "precondition: filelog must exist right after commit");
        Files.write(flIdx.toPath(), new byte[0]);
        Files.write(flDat.toPath(), new byte[0]);
        // CommitCommand already populated repo's per-file Revlog cache with the real,
        // pre-truncation filelog; without invalidating it, getRevlog() below would keep
        // handing back that stale (still valid) revision instead of the emptied one on disk.
        repo.clearRevlogCache();

        long diskTime = f.lastModified() / 1000;
        assertTrue(new File(repo.getHgDir(), "dirstate").setLastModified(diskTime * 1000));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("norev.txt"),
                "A manifest entry whose filelog no longer contains the referenced revision must make the racy check fall back to clean, got: " + status.getModified());
        assertFalse(status.getModified().contains("norev.txt"));
    }

    private static class FirstReadStripsEntryRepository extends HgRepository {
        private final String pathToStrip;
        private boolean firstCall = true;

        FirstReadStripsEntryRepository(File directory, String pathToStrip) {
            super(directory);
            this.pathToStrip = pathToStrip;
        }

        @Override
        public synchronized Dirstate getDirstate() throws IOException {
            Dirstate dirstate = super.getDirstate();
            if (firstCall) {
                firstCall = false;
                dirstate.removeEntry(pathToStrip);
            }
            return dirstate;
        }
    }

    @Test
    public void slowPathTrustsUnionPresenceWithoutRecheckWhenOuterDirstateSnapshotIsMissingTheEntry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository initRepo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "stale.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(initRepo).call();
        new CommitCommand(initRepo).setMessage("add stale.txt").call();

        // Actually modify the file's size on disk after the commit: if the dEntry-null
        // short-circuit at the end of StatusCommand's slow path were NOT taken, this would
        // be reported Modified via the normal size/mtime comparison.
        Files.writeString(f.toPath(), "hello world, this is now a different size entirely");

        // Simulate two internal dirstate reads within a single call() racing against a
        // concurrent dirstate rewrite: the outer read (used for the final per-path lookup)
        // is missing the entry, while the WorkingDirTreeIterator's own (later) read still
        // has it with state 'n' -- reproducing the only way the dEntry-null branch is reached.
        FirstReadStripsEntryRepository repo = new FirstReadStripsEntryRepository(repoDir, "stale.txt");

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status status = new StatusCommand(repo).setTreeFilter(customFilter).call();

        assertTrue(status.getClean().contains("stale.txt"),
                "When the outer dirstate snapshot is missing an entry the working iterator still sees, "
                        + "status must trust the union presence and report clean without comparing content, got: " + status);
        assertFalse(status.getModified().contains("stale.txt"));
    }

    @Test
    public void setTreeFilterNullIsANoOpAndKeepsTheFastPath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "untracked.txt").toPath(), "hello");

        Status status = new StatusCommand(repo).setTreeFilter(null).call();
        assertEquals(1, status.getUntracked().size());
        assertTrue(status.getUntracked().contains("untracked.txt"),
                "setTreeFilter(null) must be a no-op, leaving the default ALL filter (fast path) in effect");
    }

    @Test
    public void dirstateStateMIsTreatedLikeStateNForCleanDetection(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "merged.txt");
        Files.writeString(f.toPath(), "merge marker content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add merged.txt").call();

        Dirstate dirstate = repo.getDirstate();
        Dirstate.Entry entry = dirstate.getEntries().get("merged.txt");
        dirstate.addEntry("merged.txt", new Dirstate.Entry('m', entry.getMode(), entry.getSize(), entry.getTime()));
        repo.writeDirstate(dirstate);

        Status fast = new StatusCommand(repo).call();
        assertTrue(fast.getClean().contains("merged.txt"),
                "A dirstate entry marked 'm' (merge) with matching size/mtime must be treated as clean just like 'n', got: " + fast.getModified());

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slow.getClean().contains("merged.txt"));
    }

    @Test
    public void directoryReplacingATrackedFileIsReportedRemoved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "becomesdir.txt");
        Files.writeString(f.toPath(), "will be replaced by a directory of the same name");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add becomesdir.txt").call();

        assertTrue(f.delete());
        assertTrue(f.mkdir());

        Status fast = new StatusCommand(repo).call();
        assertTrue(fast.getRemoved().contains("becomesdir.txt"),
                "A tracked path replaced on disk by a directory is neither a symlink nor a regular file and must be Removed, got: " + fast);

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slow.getRemoved().contains("becomesdir.txt"));
    }

    @Test
    public void mtimeOnlyChangeWithUnchangedSizeIsReportedModifiedByTheCheapCheck(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "touched.txt");
        Files.writeString(f.toPath(), "unchanged content, only the mtime moves");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add touched.txt").call();

        long originalTime = f.lastModified() / 1000;
        long bumpedTime = (originalTime + 1000) * 1000;
        assertTrue(f.setLastModified(bumpedTime));
        assertEquals(originalTime + 1000, f.lastModified() / 1000);

        Status fast = new StatusCommand(repo).call();
        assertTrue(fast.getModified().contains("touched.txt"),
                "A tracked file whose mtime changed with unchanged size must be Modified via the cheap size/mtime check alone, got: " + fast.getClean());

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status slow = new StatusCommand(repo).setTreeFilter(customFilter).call();
        assertTrue(slow.getModified().contains("touched.txt"));
    }

    @Test
    public void slowPathTreatsUnresolvableDirstateParentAsEmptyParentManifest(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "existing.txt");
        Files.writeString(f.toPath(), "committed content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add existing.txt").call();

        Dirstate dirstate = repo.getDirstate();
        byte[] bogusParent = new byte[20];
        Arrays.fill(bogusParent, (byte) 0xEE);
        dirstate.setParents(bogusParent, new byte[20]);
        repo.writeDirstate(dirstate);

        HgTreeFilter customFilter = HgTreeFilter.fromPathFilter(p -> true);
        Status status = new StatusCommand(repo).setTreeFilter(customFilter).call();

        // With a dirstate parent hash that resolves to no revision in a non-empty changelog,
        // the slow path falls back to treating the parent manifest as empty (parentRev stays
        // ""), so "existing.txt" is neither in the parent (inParent=false) nor freshly Added
        // (its dirstate state is 'n', not 'a') -- it lands in none of the status buckets.
        assertFalse(status.getAdded().contains("existing.txt"));
        assertFalse(status.getModified().contains("existing.txt"));
        assertFalse(status.getRemoved().contains("existing.txt"));
        assertFalse(status.getClean().contains("existing.txt"));
        assertFalse(status.getUntracked().contains("existing.txt"));
    }
}
