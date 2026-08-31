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
            
            assertThrows(com.github.search5.hg4j.errors.HgRevisionNotFoundException.class, () -> {
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
            int expectedOffset = -java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
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
            org.junit.jupiter.api.Assumptions.assumeTrue(com.github.search5.hg4j.HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping native hg verify integration test.");

            String output = com.github.search5.hg4j.HgTestUtils.hg(repoDir, "verify");

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
            java.util.List<String> filesInChangelog = new java.util.ArrayList<>();
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
            java.util.List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            
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
            Files.write(clIdx.toPath(), new byte[100], java.nio.file.StandardOpenOption.APPEND);
            assertEquals(targetSize, clIdx.length());

            // Create mock journal simulating dirstate, fncache and changelog.i changes
            // 실제 hg journal 포맷: .hg/ 기준 상대 경로 (store/... 형식)
            journalFile = new File(repo.getStoreDir(), "journal");
            java.util.List<String> journalEntries = java.util.Arrays.asList(
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
            try (com.github.search5.hg4j.lib.HgLock lock = crashedRepo.lockStore()) {
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
            java.util.List<String> journalEntries = java.util.Arrays.asList(
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
            java.util.List<com.github.search5.hg4j.api.HgCommit> logs = new LogCommand(repo).call();
            assertEquals(1, logs.size());
            com.github.search5.hg4j.api.HgCommit entry = logs.get(0);
            assertEquals("feature-cool-stuff", entry.getBranch(), "Parsed branch name must match");
            assertEquals("Developer <dev@example.com>", entry.getAuthor());
            assertEquals("Add cool features", entry.getMessage());
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
        // null input
        assertEquals(-1, CommitCommand.findUnescapedColon(null));
        // empty input
        assertEquals(-1, CommitCommand.findUnescapedColon(""));
        // no colon
        assertEquals(-1, CommitCommand.findUnescapedColon("nocolonhere"));
        // escaped colon
        assertEquals(-1, CommitCommand.findUnescapedColon("escaped\\:colon"));
        // escaped backslash followed by colon
        assertEquals(9, CommitCommand.findUnescapedColon("escaped\\\\:colon"));
        // multiple colons
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
        // escaped values
        assertEquals("a\nb\rc\0d:e\\f", CommitCommand.decodeExtraKey("a\\nb\\rc\\0d\\:e\\\\f"));
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
            d.setParents(new com.github.search5.hg4j.lib.NodeId(fakeHash), com.github.search5.hg4j.lib.NodeId.NULL);
            repo.writeDirstate(d);
            
            // 3. Commit should fail with HgRevisionNotFoundException
            CommitCommand commit = new CommitCommand(repo).setMessage("This must fail due to fake parent revision");
            assertThrows(com.github.search5.hg4j.errors.HgRevisionNotFoundException.class, commit::call);
        }
    }
}
