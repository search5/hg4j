package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.gpg.GpgSignature;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;

public class CommitCommandTest {

    @Test
    public void testCommitWithInvalidParentNode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            
            // a.txt 추가 및 첫 커밋
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Hello");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit 1").call();

            // dirstate에 존재하지 않는 parent2 설정
            Dirstate dirstate = repo.getDirstate();
            byte[] fakeParent2 = new byte[20];
            fakeParent2[0] = 9; // fake hash
            dirstate.setParents(dirstate.getParent1(), fakeParent2);
            repo.writeDirstate(dirstate);

            // 커밋 시 parent2가 없으므로 HgRevisionNotFoundException 발생해야 함
            File f2 = new File(repoDir, "b.txt");
            Files.writeString(f2.toPath(), "Hello 2");
            new AddCommand(repo).call();
            
            assertThrows(HgRevisionNotFoundException.class, () -> {
                new CommitCommand(repo).setMessage("Commit 2").call();
            });
        }
    }

    @Test
    public void testFirstCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. Create a file and add it
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Hello First Commit");

            new AddCommand(repo).call();

            // 2. Perform commit
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("Tester <test@example.com>")
                    .setMessage("Initial commit")
                    .call();

            assertNotNull(commitNode);
            assertEquals(20, commitNode.length);

            // 3. Verify dirstate parents and states updated
            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(commitNode, dirstate.getParent1());
            
            Map<String, Dirstate.Entry> entries = dirstate.getEntries();
            assertEquals(1, entries.size());
            Dirstate.Entry entry = entries.get("a.txt");
            assertNotNull(entry);
            assertEquals('n', entry.getState());

            // 4. Verify changelog
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            assertTrue(clIdx.exists());

            Revlog clRevlog = new Revlog(clIdx, clDat);
            assertEquals(1, clRevlog.getRevisionCount());
            
            byte[] clContent = clRevlog.getRevisionContent(0);
            String clText = new String(clContent, StandardCharsets.UTF_8);
            assertTrue(clText.contains("Initial commit"));
            assertTrue(clText.contains("Tester <test@example.com>"));
            assertTrue(clText.contains("a.txt"));
            int expectedOffset = -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
            assertTrue(clText.contains(" " + expectedOffset));

            // 5. Verify manifest
            File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
            File mfDat = new File(repo.getStoreDir(), "00manifest.d");
            assertTrue(mfIdx.exists());

            Revlog mfRevlog = new Revlog(mfIdx, mfDat);
            assertEquals(1, mfRevlog.getRevisionCount());

            byte[] mfContent = mfRevlog.getRevisionContent(0);
            String mfText = new String(mfContent, StandardCharsets.UTF_8);
            assertTrue(mfText.contains("a.txt"));

            // 6. Verify filelog created in store/data
            File filelogIdx = new File(repo.getStoreDir(), "data/a.txt.i");
            File filelogDat = new File(repo.getStoreDir(), "data/a.txt.d");
            assertTrue(filelogIdx.exists());

            Revlog filelog = new Revlog(filelogIdx, filelogDat);
            assertEquals(1, filelog.getRevisionCount());
            assertArrayEquals("Hello First Commit".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));
        }
    }

    @Test
    public void testSecondCommitAndModifications(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // Commit 1
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Version 1");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit 1").call();

            // Modify file a.txt, and create b.txt
            Files.writeString(f1.toPath(), "Version 2");
            
            File f2 = new File(repoDir, "b.txt");
            Files.writeString(f2.toPath(), "New File B");
            
            // Add b.txt
            new AddCommand(repo).call();

            // Commit 2
            byte[] commitNode2 = new CommitCommand(repo).setMessage("Commit 2").call();
            assertNotNull(commitNode2);

            // Verify dirstate parent updated to Commit 2
            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(commitNode2, dirstate.getParent1());

            // Verify filelog of a.txt has 2 revisions
            File flIdx = new File(repo.getStoreDir(), "data/a.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/a.txt.d");
            Revlog filelogA = new Revlog(flIdx, flDat);
            assertEquals(2, filelogA.getRevisionCount());
            assertArrayEquals("Version 1".getBytes(StandardCharsets.UTF_8), filelogA.getRevisionContent(0));
            assertArrayEquals("Version 2".getBytes(StandardCharsets.UTF_8), filelogA.getRevisionContent(1));

            // Verify b.txt has 1 revision
            File flIdxB = new File(repo.getStoreDir(), "data/b.txt.i");
            File flDatB = new File(repo.getStoreDir(), "data/b.txt.d");
            Revlog filelogB = new Revlog(flIdxB, flDatB);
            assertEquals(1, filelogB.getRevisionCount());
            assertArrayEquals("New File B".getBytes(StandardCharsets.UTF_8), filelogB.getRevisionContent(0));
        }
    }

    @Test
    public void testCommitThrowsExceptionWhenNoMessage(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            CommitCommand commit = new CommitCommand(repo);
            assertThrows(IllegalStateException.class, commit::call);
        }
    }

    @Test
    public void testCommitBlocksOnUnresolvedConflicts(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. Create a file and commit
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Clean Content\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Initial").call();

            // 2. Put file in 'm' state manually (simulating a conflict after merge)
            Dirstate dirstate = repo.getDirstate();
            dirstate.addEntry("a.txt", new Dirstate.Entry('m', 0644, 100, System.currentTimeMillis() / 1000));
            repo.writeDirstate(dirstate);

            // 3. Inject unresolved conflict markers
            Files.writeString(f1.toPath(), "<<<<<<< Yours\nMy modification\n=======\nTheir modification\n>>>>>>> Theirs\n");

            // 4. Try to commit and assert it is blocked
            CommitCommand commitCmd = new CommitCommand(repo).setMessage("Trying to commit conflict");
            IllegalStateException ex = assertThrows(IllegalStateException.class, commitCmd::call);
            assertTrue(ex.getMessage().contains("Commit blocked: Unresolved merge conflicts"));
        }
    }

    @Test
    public void testMultiCommitDeltaChain(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            File f1 = new File(repoDir, "chain.txt");

            // 1회 커밋 (리비전 0)
            Files.writeString(f1.toPath(), "Revision 0: Hello world!\nLine 2\nLine 3\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit 0").call();

            // 2회 커밋 (리비전 1 - 델타 생성)
            Files.writeString(f1.toPath(), "Revision 1: Hello world!\nLine 2 changed\nLine 3\n");
            new CommitCommand(repo).setMessage("Commit 1").call();

            // 3회 커밋 (리비전 2 - 델타 연쇄 생성)
            Files.writeString(f1.toPath(), "Revision 2: Hello world!\nLine 2 changed\nLine 3 changed too!\n");
            new CommitCommand(repo).setMessage("Commit 2").call();

            // 4회 커밋 (리비전 3)
            Files.writeString(f1.toPath(), "Revision 3: Hello world!\nLine 2 changed\nLine 3 changed too!\nFinal Line!\n");
            new CommitCommand(repo).setMessage("Commit 3").call();

            // 저장소에 기록된 파일로그 델타 및 원문 데이터 검증
            File flIdx = new File(repo.getStoreDir(), "data/chain.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/chain.txt.d");
            assertTrue(flIdx.exists());

            Revlog filelog = new Revlog(flIdx, flDat);
            assertEquals(4, filelog.getRevisionCount());

            // 각 리비전의 복원 데이터가 오프셋 손상 없이 정상적으로 일치하는지 정밀 확인
            assertArrayEquals("Revision 0: Hello world!\nLine 2\nLine 3\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));
            assertArrayEquals("Revision 1: Hello world!\nLine 2 changed\nLine 3\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(1));
            assertArrayEquals("Revision 2: Hello world!\nLine 2 changed\nLine 3 changed too!\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(2));
            assertArrayEquals("Revision 3: Hello world!\nLine 2 changed\nLine 3 changed too!\nFinal Line!\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(3));
        }
    }

    @Test
    public void testNativeHgVerifyIntegration(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. 4회 연속 커밋 파일 생성 및 트래킹
            File f1 = new File(repoDir, "chain.txt");
            Files.writeString(f1.toPath(), "Rev 0\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit 0").call();

            Files.writeString(f1.toPath(), "Rev 0\nRev 1\n");
            new CommitCommand(repo).setMessage("Commit 1").call();

            Files.writeString(f1.toPath(), "Rev 0\nRev 1\nRev 2\n");
            new CommitCommand(repo).setMessage("Commit 2").call();

            Files.writeString(f1.toPath(), "Rev 0\nRev 1\nRev 2\nRev 3\n");
            new CommitCommand(repo).setMessage("Commit 3").call();

            // 2. 한글 파일명, 공백 포함 파일명, Windows 예약어 파일명 생성 및 커밋
            File korFile = new File(repoDir, "안녕 하세요.txt");
            Files.writeString(korFile.toPath(), "한글 파일 및 공백 파일 정합성 테스트 데이터");

            File auxFile = new File(repoDir, "aux.txt");
            Files.writeString(auxFile.toPath(), "Windows 예약어 aux 검증");

            File conFile = new File(repoDir, "CON.txt");
            Files.writeString(conFile.toPath(), "Windows 대문자 예약어 CON 검증");

            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit with non-ASCII and Reserved names").call();

            // 3. 서브프로세스로 네이티브 hg verify 구동하여 무결성 정밀 검증
            Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping native hg verify integration test.");

            String output = HgTestUtils.hg(repoDir, "verify");

            System.out.println("=== hg verify Output ===");
            System.out.println(output);
            System.out.println("========================");

            // 네이티브 hg verify가 어떠한 에러 출력물도 없이 성공했음을 단언하여 실증 검증 완료시킵니다.
            assertFalse(output.contains("integrity error"), "Saved repository contains integrity errors!\n" + output);
            assertFalse(output.contains("damaged"), "Repository damaged!\n" + output);
            assertFalse(output.contains("failed"), "Verify check failed!\n" + output);
            if (output.contains("integrity errors encountered")) {
                assertTrue(output.contains("0 integrity errors encountered"), "Must report 0 integrity errors.\n" + output);
            }
        }
    }

    @Test
    public void testCommitTransactionRollback(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // Write initial file and commit
            File f1 = new File(repoDir, "test_rollback.txt");
            Files.writeString(f1.toPath(), "initial content");
            new AddCommand(repo).call();
            byte[] firstCommit = new CommitCommand(repo).setMessage("Commit 1").call();

            // Make another file tracking dirstate entry untracked but non-existent on disk to trigger IOException
            Dirstate d = repo.getDirstate();
            d.addEntry("non_existent_file.txt", new Dirstate.Entry('a', 0644, 100, System.currentTimeMillis() / 1000));
            repo.writeDirstate(d);
            
            // This commit should fail with untracked file not found on disk, triggering the rollback transaction!
            CommitCommand cmd = new CommitCommand(repo).setMessage("This must fail");
            assertThrows(IOException.class, cmd::call);
            
            // Verify dirstate restored atomically to Commit 1
            Dirstate restoredDirstate = repo.getDirstate();
            assertArrayEquals(firstCommit, restoredDirstate.getParent1());
            assertNotNull(restoredDirstate.getEntries().get("non_existent_file.txt"));
        }
    }

    @Test
    public void testCommitValidationAndUnresolvedConflicts(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. Missing message exception
            CommitCommand cmd = new CommitCommand(repo);
            assertThrows(IllegalStateException.class, cmd::call);

            // 2. Author setting boundary
            cmd.setAuthor(null);
            cmd.setAuthor("");
            
            // 3. Unresolved conflicts exception test
            File f1 = new File(repoDir, "conflict.txt");
            Files.writeString(f1.toPath(), "<<<<<<<\n=======\n>>>>>>>");
            new AddCommand(repo).call();
            
            Dirstate d = repo.getDirstate();
            d.addEntry("conflict.txt", new Dirstate.Entry('m', 0644, 20, System.currentTimeMillis() / 1000));
            repo.writeDirstate(d);
            
            CommitCommand conflictCmd = new CommitCommand(repo).setMessage("This must fail due to conflicts");
            assertThrows(IllegalStateException.class, conflictCmd::call);
        }
    }

    @Test
    public void testCommitFncacheAndChangelogSorting(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // Add non-ASCII, dotfile, and UPPERCASE files to check fncache and changelog sorting
            File f1 = new File(repoDir, ".hgignore");
            Files.writeString(f1.toPath(), "syntax: glob\n*.tmp\n");
            File f2 = new File(repoDir, "README.MD");
            Files.writeString(f2.toPath(), "Some readme");
            File f3 = new File(repoDir, "한글.txt");
            Files.writeString(f3.toPath(), "한글내용");

            new AddCommand(repo).call();
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("Tester <test@example.com>")
                    .setMessage("Regression test commit")
                    .call();

            assertNotNull(commitNode);

            // 1. Verify changelog has sorted files
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            byte[] clContent = clRevlog.getRevisionContent(0);
            String clText = new String(clContent, StandardCharsets.UTF_8);
            
            // Find list of files in changelog
            String[] lines = clText.split("\n");
            List<String> filesInChangelog = new ArrayList<>();
            boolean inFilesSection = false;
            for (String line : lines) {
                if (line.isEmpty()) {
                    if (inFilesSection) {
                        break;
                    }
                    continue;
                }
                if (line.contains("Regression test commit")) {
                    break;
                }
                if (line.equals("Tester <test@example.com>")) {
                    inFilesSection = true;
                    continue;
                }
                // Skip the timezone/extra line (using \u0000 separator as split is on \n, matching raw text)
                if (inFilesSection && line.matches("^\\d+ -?\\d+( branch:.*)?$")) {
                    continue;
                }
                if (inFilesSection) {
                    filesInChangelog.add(line);
                }
            }
            
            // Assert that the files in changelog are sorted alphabetically
            assertEquals(3, filesInChangelog.size());
            assertEquals(".hgignore", filesInChangelog.get(0));
            assertEquals("README.MD", filesInChangelog.get(1));
            assertEquals("한글.txt", filesInChangelog.get(2));

            // 2. Verify fncache contains raw paths as per native hg specifications
            File fncacheFile = new File(repo.getStoreDir(), "fncache");
            assertTrue(fncacheFile.exists());
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            
            assertTrue(fncachePaths.contains("data/.hgignore.i"));
            // .d 파일은 fncache에 등록되지 않음 (실제 hg 동작과 동일)
            assertFalse(fncachePaths.contains("data/.hgignore.d"), "fncache should not contain .d paths");
            assertTrue(fncachePaths.contains("data/README.MD.i"));
            assertTrue(fncachePaths.contains("data/한글.txt.i"));
        }
    }

    @Test
    public void testTransactionAutoRollbackAfterCrash(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        
        long origClIdxLen;
        File clIdx;
        File journalFile;
        File dirstateBackupFile;

        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. First successful commit
            File f1 = new File(repoDir, "stable.txt");
            Files.writeString(f1.toPath(), "Stable Content");
            new AddCommand(repo).call();
            byte[] commitNode1 = new CommitCommand(repo).setMessage("Stable commit").call();
            assertNotNull(commitNode1);

            // Record file paths and original sizes
            clIdx = new File(repo.getStoreDir(), "00changelog.i");
            origClIdxLen = clIdx.length();

            // 2. Simulate subsequent partial write and Crash (write to journal manual metadata)
            // Let's manually append some garbage to changelog.i mimicking a half-completed commit
            long targetSize = origClIdxLen + 100;
            Files.write(clIdx.toPath(), new byte[100], StandardOpenOption.APPEND);
            assertEquals(targetSize, clIdx.length());

            // Create mock journal simulating dirstate, fncache and changelog.i changes
            // 실제 hg journal 포맷: .hg/ 기준 상대 경로 (store/... 형식)
            journalFile = new File(repo.getStoreDir(), "journal");
            List<String> journalEntries = Arrays.asList(
                "dirstate",
                "store/00changelog.i " + origClIdxLen
            );
            Files.write(journalFile.toPath(), journalEntries, StandardCharsets.UTF_8);

            // Create mock backup files
            File dirstateFile = new File(repoDir, ".hg/dirstate");
            dirstateBackupFile = new File(repoDir, ".hg/dirstate.backup");
            Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath());
        }

        // 3. Open repository again, and trigger rollback via store lock acquisition
        try (HgRepository crashedRepo = new HgRepository(repoDir)) {
            try (HgLock lock = crashedRepo.lockStore()) {
                // lockStore() will trigger checkAndPerformAutoRollback
            }
        }

        // 4. Verify that changelog.i is rolled back to original size and journal is deleted
        assertEquals(origClIdxLen, clIdx.length());
        assertFalse(journalFile.exists());
        assertFalse(dirstateBackupFile.exists());
    }

    @Test
    public void testTransactionAutoRollbackRetainsJournalOnFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File journalFile;

        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // Simulate crash with invalid journal content (causes NumberFormatException on size parse)
            journalFile = new File(repo.getStoreDir(), "journal");
            List<String> journalEntries = Arrays.asList(
                ".hg/store/00changelog.i NOT_A_NUMBER"
            );
            Files.write(journalFile.toPath(), journalEntries, StandardCharsets.UTF_8);
        }

        // Open repository again, and trigger auto-rollback via store lock acquisition
        try (HgRepository crashedRepo = new HgRepository(repoDir)) {
            try {
                crashedRepo.lockStore().close();
            } catch (Exception ignored) {}
        }

        // The journal should NOT have been deleted because the parsing failed and rollback was incomplete!
        assertTrue(journalFile.exists());
    }

    @Test
    public void testNamedBranchCommitAndLog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // 1. Create a file, write content, and add it
            File f1 = new File(repoDir, "branchfile.txt");
            Files.writeString(f1.toPath(), "Named Branch Content");
            new AddCommand(repo).call();

            // 2. Set repository branch to a custom named branch
            repo.setBranch("feature-cool-stuff");

            // 3. Commit
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("Developer <dev@example.com>")
                    .setMessage("Add cool features")
                    .call();

            assertNotNull(commitNode);

            // 4. Verify standard changelog content matches  branch:feature-cool-stuff
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            byte[] rawChangelog = clRevlog.getRevisionContent(0);
            String text = new String(rawChangelog, StandardCharsets.UTF_8);

            // The date/tz line must contain ' branch:feature-cool-stuff'
            assertTrue(text.contains(" branch:feature-cool-stuff"), "Changelog should contain Mercurial style extra branch info");

            // 5. Verify that LogCommand correctly parses the branch name
            List<HgCommit> logs = new LogCommand(repo).call();
            assertEquals(1, logs.size());
            HgCommit entry = logs.get(0);
            assertEquals("feature-cool-stuff", entry.getBranch(), "Parsed branch name must match");
            assertEquals("Developer <dev@example.com>", entry.getAuthor());
            assertEquals("Add cool features", entry.getMessage());
        }
    }

    @Test
    public void closeBranchWritesTheCloseExtraOnTheDefaultBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            new CommitCommand(repo)
                    .setAuthor("dev <dev@example.com>")
                    .setMessage("close default")
                    .setCloseBranch(true)
                    .call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            String text = new String(clRevlog.getRevisionContent(0), StandardCharsets.UTF_8);

            // Real hg never writes a "branch:" extra for the default branch (see
            // getBranchOfRevision's javadoc), so a default-branch close commit's extras block must
            // be exactly " close:1" -- no leading "branch:default" and no '\0' separator.
            assertTrue(text.contains(" close:1"), "Changelog must record the close extra: " + text);
            assertFalse(text.contains("branch:"), "Default branch must never be written explicitly");

            assertTrue(CommitCommand.isRevisionClosingBranch(clRevlog, 0));
        }
    }

    @Test
    public void closeBranchCombinedWithANamedBranchWritesBothExtrasSortedAndNullSeparated(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();
            repo.setBranch("feature");

            new CommitCommand(repo)
                    .setAuthor("dev <dev@example.com>")
                    .setMessage("close feature")
                    .setCloseBranch(true)
                    .call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            String text = new String(clRevlog.getRevisionContent(0), StandardCharsets.UTF_8);

            // Real hg's changelog.encodeextra sorts extras by key and joins with '\0' -- "branch"
            // sorts before "close" alphabetically.
            assertTrue(text.contains(" branch:feature\0close:1"),
                    "Extras must be null-separated and sorted (branch before close): " + text);

            assertEquals("feature", CommitCommand.getBranchOfRevision(clRevlog, 0));
            assertTrue(CommitCommand.isRevisionClosingBranch(clRevlog, 0));
        }
    }

    @Test
    public void regularCommitIsNotReportedAsClosingItsBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            new CommitCommand(repo).setAuthor("dev <dev@example.com>").setMessage("plain").call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            assertFalse(CommitCommand.isRevisionClosingBranch(clRevlog, 0));
        }
    }

    @Test
    public void testDecodeExtraKeyRoundtrip() {
        String original = "backslash\\newline\\nand\\colon:";
        String encoded = CommitCommand.encodeExtraKey(original);
        String decoded = CommitCommand.decodeExtraKey(encoded);
        assertEquals(original, decoded, "Extra key roundtrip encoding/decoding should be identical");
    }

    @Test
    public void testFindUnescapedColonEdgeCases() {
        // 실제 hg(mercurial.changelog.decodeextra)는 콜론을 전혀 이스케이프하지 않고
        // 그냥 첫 번째 콜론에서 key:value를 나눈다(Python str.split(':', 1)) —
        // "이스케이프된 콜론"이라는 개념 자체가 없다(2026-09-01 실제 hg로 확인).
        assertEquals(-1, CommitCommand.findUnescapedColon(null));
        assertEquals(-1, CommitCommand.findUnescapedColon(""));
        assertEquals(-1, CommitCommand.findUnescapedColon("nocolonhere"));
        assertEquals(8, CommitCommand.findUnescapedColon("escaped\\:colon"));
        assertEquals(9, CommitCommand.findUnescapedColon("escaped\\\\:colon"));
        assertEquals(3, CommitCommand.findUnescapedColon("abc:def:ghi"));
    }

    @Test
    public void testDecodeExtraKeyNullAndEdgeCases() {
        assertEquals("", CommitCommand.decodeExtraKey(null));
        assertEquals("", CommitCommand.decodeExtraKey(""));
        // normal
        assertEquals("abc", CommitCommand.decodeExtraKey("abc"));
        // escape character at the very end
        assertEquals("abc\\", CommitCommand.decodeExtraKey("abc\\"));
        // unescaped normal character after backslash
        assertEquals("abc\\x", CommitCommand.decodeExtraKey("abc\\x"));
        // 실제 hg는 콜론을 이스케이프하지 않으므로 "\:"의 백슬래시는 그대로 남는다.
        assertEquals("a\nb\rc\0d\\:e\\f", CommitCommand.decodeExtraKey("a\\nb\\rc\\0d\\:e\\\\f"));
    }

    @Test
    public void testCommitThrowsExceptionWhenEmptyMessage(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            CommitCommand commit = new CommitCommand(repo).setMessage("");
            assertThrows(IllegalStateException.class, commit::call);
        }
    }

    @Test
    public void testCommitThrowsHgRevisionNotFoundException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            
            // 1. Create a dummy file and add
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "Content");
            new AddCommand(repo).call();
            
            // 2. Write invalid NodeId into Dirstate parents
            Dirstate d = repo.getDirstate();
            byte[] fakeHash = new byte[20];
            fakeHash[0] = 0x12; // fake SHA-1
            d.setParents(new NodeId(fakeHash), NodeId.NULL);
            repo.writeDirstate(d);
            
            // 3. Commit should fail with HgRevisionNotFoundException
            CommitCommand commit = new CommitCommand(repo).setMessage("This must fail due to fake parent revision");
            assertThrows(HgRevisionNotFoundException.class, commit::call);
        }
    }

    @Test
    public void testPreCommitHookRejectionAbortsCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            AtomicReference<Map<String, Object>> capturedCtx = new AtomicReference<>();
            CommitCommand cmd = new CommitCommand(repo)
                    .setAuthor("dev <dev@example.com>")
                    .setMessage("should be rejected")
                    .registerPreCommitHook(ctx -> {
                        capturedCtx.set(ctx);
                        return false;
                    });

            HgValidationException ex = assertThrows(HgValidationException.class, cmd::call);
            assertTrue(ex.getMessage().contains("Pre-commit hook"));
            assertNotNull(capturedCtx.get());
            assertEquals("dev <dev@example.com>", capturedCtx.get().get("author"));
            assertEquals("should be rejected", capturedCtx.get().get("message"));
            assertSame(repo, capturedCtx.get().get("repository"));

            // Hook rejection must abort before any storage work happens.
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            assertFalse(clIdx.exists(), "Changelog must not be created when pre-commit hook rejects");
        }
    }

    @Test
    public void testPreCommitHookApprovalAllowsCommitToProceed(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            AtomicInteger invocations = new AtomicInteger();
            byte[] commitNode = new CommitCommand(repo)
                    .setMessage("approved commit")
                    .registerPreCommitHook(ctx -> {
                        invocations.incrementAndGet();
                        return true;
                    })
                    .call();

            assertNotNull(commitNode);
            assertEquals(1, invocations.get());
        }
    }

    @Test
    public void testMultiplePreCommitHooksStopAtFirstRejection(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            List<String> order = new ArrayList<>();
            CommitCommand cmd = new CommitCommand(repo)
                    .setMessage("multi-hook")
                    .registerPreCommitHook(ctx -> { order.add("first"); return true; })
                    .registerPreCommitHook(ctx -> { order.add("second-rejects"); return false; })
                    .registerPreCommitHook(ctx -> { order.add("third-never-runs"); return true; });

            assertThrows(HgValidationException.class, cmd::call);
            assertEquals(Arrays.asList("first", "second-rejects"), order);
        }
    }

    @Test
    public void testPostCommitHookReceivesCommitContext(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            AtomicReference<Map<String, Object>> capturedCtx = new AtomicReference<>();
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("dev <dev@example.com>")
                    .setMessage("post hook context")
                    .registerPostCommitHook(ctx -> {
                        capturedCtx.set(ctx);
                        return true;
                    })
                    .call();

            assertNotNull(capturedCtx.get());
            assertEquals("dev <dev@example.com>", capturedCtx.get().get("author"));
            assertEquals("post hook context", capturedCtx.get().get("message"));
            assertArrayEquals(commitNode, (byte[]) capturedCtx.get().get("commitNode"));
            assertSame(repo, capturedCtx.get().get("repository"));
        }
    }

    @Test
    public void testPostCommitHookExceptionDoesNotFailCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            AtomicInteger secondHookInvocations = new AtomicInteger();
            byte[] commitNode = new CommitCommand(repo)
                    .setMessage("post hook throws")
                    .registerPostCommitHook(ctx -> { throw new IOException("hook boom"); })
                    .registerPostCommitHook(ctx -> {
                        secondHookInvocations.incrementAndGet();
                        return true;
                    })
                    .call();

            assertNotNull(commitNode);
            // Despite the first post-commit hook throwing, later hooks still run and the commit
            // itself succeeds -- post-commit failures are only logged, never propagated.
            assertEquals(1, secondHookInvocations.get());

            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(commitNode, dirstate.getParent1());
        }
    }

    @Test
    public void testGpgSignatureIsStoredInChangelogMetadata(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            GpgSignature signature = new GpgSignature(
                    "-----BEGIN PGP SIGNATURE-----\nabc\n-----END PGP SIGNATURE-----", "ABCDEF1234567890");
            new CommitCommand(repo)
                    .setMessage("signed commit")
                    .setGpgSignature(signature)
                    .call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            Map<String, String> meta = clRevlog.getRevisionMetadata(0);

            assertEquals(signature.toAsciiArmored().replace("\n", "\\n"), meta.get("gpgsig"));
            assertEquals("ABCDEF1234567890", meta.get("gpgfingerprint"));
        }
    }

    @Test
    public void testSymlinkTrackedFileRecordsLFlagAndTargetPathAsContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File targetFile = new File(repoDir, "target.txt");
            Files.writeString(targetFile.toPath(), "target content");

            File linkFile = new File(repoDir, "link.txt");
            Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));

            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("symlink commit").call();

            File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
            File mfDat = new File(repo.getStoreDir(), "00manifest.d");
            Revlog mfRevlog = new Revlog(mfIdx, mfDat);
            String mfText = new String(mfRevlog.getRevisionContent(0), StandardCharsets.UTF_8);
            String linkEntryLine = Arrays.stream(mfText.split("\n"))
                    .filter(line -> line.startsWith("link.txt\0"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(linkEntryLine.endsWith("l"), "Symlink manifest entry must carry the 'l' flag: " + linkEntryLine);

            File flIdx = new File(repo.getStoreDir(), "data/link.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/link.txt.d");
            Revlog filelog = new Revlog(flIdx, flDat);
            assertArrayEquals("target.txt".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get("link.txt");
            assertEquals("target.txt".getBytes(StandardCharsets.UTF_8).length, entry.getSize(),
                    "Symlink dirstate size must be the length of the link target string, not the resolved file size");
        }
    }

    /**
     * The unchanged-file ('n' state) fast path compares the dirstate's recorded size against
     * {@code File#length()} -- which, for a symlink, follows the link and returns the *target
     * file's* size rather than the symlink's own target-path-string length (the convention this
     * codebase otherwise uses consistently, e.g. AddCommand/CopyCommand/GraftCommand/
     * RebaseCommand's own commit-adjacent code). Real hg only cares whether the symlink's own
     * target string changed, never the resolved file's size, so touching the resolved file's
     * content (without touching the symlink itself) must not create a spurious new filelog
     * revision for the symlink on the next commit.
     */
    @Test
    public void testUnrelatedTargetFileSizeChangeDoesNotSpuriouslyRecommitAnUntouchedSymlink(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File targetFile = new File(repoDir, "target.txt");
            Files.writeString(targetFile.toPath(), "short");

            File linkFile = new File(repoDir, "link.txt");
            Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));

            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

            File flIdx = new File(repo.getStoreDir(), "data/link.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/link.txt.d");
            Revlog filelog = repo.getRevlog(flIdx, flDat);
            assertEquals(1, filelog.getRevisionCount());
            byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();

            // Grow the target file substantially -- the symlink's own text ("target.txt") is
            // completely untouched, only the file it happens to point at changes size.
            Files.writeString(targetFile.toPath(), "a much, much longer replacement body");
            // touch() the symlink's own mtime forward past the commit's tx-start second, so the
            // M-2 racy-write guard can't mask this by short-circuiting on a stale mtime.
            Thread.sleep(1100);

            Files.writeString(new File(repoDir, "other.txt").toPath(), "unrelated change");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("v2").setAuthor("dev").call();

            Revlog filelogAfter = repo.getRevlog(flIdx, flDat);
            assertEquals(1, filelogAfter.getRevisionCount(),
                    "the symlink's own target string never changed, so no new filelog revision should have been committed");
            assertArrayEquals(originalNode, filelogAfter.getIndexRecord(0).getNodeId(),
                    "the symlink's filelog node must be untouched by an unrelated change to the file it points at");
        }
    }

    @Test
    public void testExecutableFileRecordsXFlagInManifestAndDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File script = new File(repoDir, "run.sh");
            Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n");
            assertTrue(script.setExecutable(true, false), "Test setup requires setting the executable bit");

            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("executable commit").call();

            File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
            File mfDat = new File(repo.getStoreDir(), "00manifest.d");
            Revlog mfRevlog = new Revlog(mfIdx, mfDat);
            String mfText = new String(mfRevlog.getRevisionContent(0), StandardCharsets.UTF_8);
            String entryLine = Arrays.stream(mfText.split("\n"))
                    .filter(line -> line.startsWith("run.sh\0"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(entryLine.endsWith("x"), "Executable manifest entry must carry the 'x' flag: " + entryLine);

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get("run.sh");
            assertEquals(0755, entry.getMode());
        }
    }

    @Test
    public void testActiveBookmarkAdvancesToNewCommitNode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "v1");
            new AddCommand(repo).call();
            byte[] firstCommit = new CommitCommand(repo).setMessage("Commit 1").call();

            // `hg bookmark NAME` without an explicit -r target makes the new bookmark active,
            // tracking the current working-copy parent (see BookmarkCommand.call()).
            Map<String, String> bookmarks = new BookmarkCommand(repo).setBookmarkName("feature").call();
            assertEquals(NodeIdUtil.toHex(firstCommit), bookmarks.get("feature"));
            assertEquals("feature", new BookmarkCommand(repo).getActiveBookmark());

            Files.writeString(f1.toPath(), "v2");
            byte[] secondCommit = new CommitCommand(repo).setMessage("Commit 2").call();

            Map<String, String> bookmarksAfter = new BookmarkCommand(repo).call();
            assertEquals(NodeIdUtil.toHex(secondCommit), bookmarksAfter.get("feature"),
                    "Active bookmark must advance to follow the new commit");
        }
    }

    @Test
    public void testRenameCommitRecordsCopyMetadataInFilelog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File original = new File(repoDir, "orig.txt");
            Files.writeString(original.toPath(), "original content");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("Commit orig").call();

            File flIdxOrig = new File(repo.getStoreDir(), "data/orig.txt.i");
            File flDatOrig = new File(repo.getStoreDir(), "data/orig.txt.d");
            Revlog origFilelog = new Revlog(flIdxOrig, flDatOrig);
            String origHex = NodeIdUtil.toHex(origFilelog.getIndexRecord(0).getNodeId());

            new RenameCommand(repo).setSource("orig.txt").setTarget("renamed.txt").call();
            new CommitCommand(repo).setMessage("Commit rename").call();

            File flIdx = new File(repo.getStoreDir(), "data/renamed.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/renamed.txt.d");
            Revlog filelog = new Revlog(flIdx, flDat);
            Map<String, String> meta = filelog.getRevisionMetadata(0);

            assertEquals("orig.txt", meta.get("copy"));
            assertEquals(origHex, meta.get("copyrev"));
            assertArrayEquals("original content".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));

            // The source path must be gone from the new manifest -- it was renamed away.
            File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
            File mfDat = new File(repo.getStoreDir(), "00manifest.d");
            Revlog mfRevlog = new Revlog(mfIdx, mfDat);
            String mfText = new String(mfRevlog.getRevisionContent(1), StandardCharsets.UTF_8);
            assertFalse(mfText.contains("orig.txt\0"), "Renamed-away source path must not remain in the new manifest");
            assertTrue(mfText.contains("renamed.txt\0"));
        }
    }
}
