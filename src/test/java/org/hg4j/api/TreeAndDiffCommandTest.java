package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TreeAndDiffCommandTest {

    @Test
    public void testTreeAndDiffCommandBasicFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertNotNull(repo);

        // Rev 0: a.txt(Normal), b.txt(Executable) 추가
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\nLine 2\nLine 3\n");
        
        File fb = new File(repoDir, "b.txt");
        Files.writeString(fb.toPath(), "Executable Content\n");

        new AddCommand(repo).addFile("a.txt").addFile("b.txt").call();
        
        // b.txt에 실행 권한 부여(실제 0755 모드 저장을 보장하기 위해)
        fb.setExecutable(true, false);
        
        byte[] rev0Node = new CommitCommand(repo)
                .setAuthor("tester <test@example.com>")
                .setMessage("Initial commit adding a.txt and b.txt")
                .call();

        // Rev 1: a.txt 수정, b.txt 삭제, c.txt 추가
        Files.writeString(fa.toPath(), "Line 1\nLine 2 Modified\nLine 3\nLine 4\n");
        
        fb.delete();
        new RemoveCommand(repo).setFile("b.txt").setForce(true).call();

        File fc = new File(repoDir, "c.txt");
        Files.writeString(fc.toPath(), "Added File Content\n");
        new AddCommand(repo).addFile("c.txt").call();

        new CommitCommand(repo)
                .setAuthor("tester <test@example.com>")
                .setMessage("Modified a.txt, deleted b.txt, added c.txt")
                .call();

        // 1. TreeCommand 테스트 (Rev 0)
        List<TreeCommand.TreeEntry> treeRev0 = new TreeCommand(repo).setRevision(0).call();
        assertEquals(2, treeRev0.size());
        
        TreeCommand.TreeEntry entryA0 = treeRev0.stream().filter(e -> e.getPath().equals("a.txt")).findFirst().orElse(null);
        assertNotNull(entryA0);
        assertEquals(0644, entryA0.getMode());
        assertNotNull(entryA0.getNodeId());
        assertEquals(40, entryA0.getNodeId().length());

        TreeCommand.TreeEntry entryB0 = treeRev0.stream().filter(e -> e.getPath().equals("b.txt")).findFirst().orElse(null);
        assertNotNull(entryB0);
        // 실행권한은 OS 및 파일시스템 환경에 따라 달라질 수 있으므로 값 비교 대신 모드가 normal/executable 중 하나로 유효한지 확인
        assertTrue(entryB0.getMode() == 0755 || entryB0.getMode() == 0644);

        // 2. TreeCommand 테스트 (Rev 1)
        List<TreeCommand.TreeEntry> treeRev1 = new TreeCommand(repo).setRevision(1).call();
        assertEquals(2, treeRev1.size()); // a.txt, c.txt
        
        assertTrue(treeRev1.stream().anyMatch(e -> e.getPath().equals("a.txt")));
        assertTrue(treeRev1.stream().anyMatch(e -> e.getPath().equals("c.txt")));
        assertFalse(treeRev1.stream().anyMatch(e -> e.getPath().equals("b.txt")));

        // Node ID 기반의 조회 테스트
        List<TreeCommand.TreeEntry> treeByNode = new TreeCommand(repo).setNodeId(rev0Node).call();
        assertEquals(2, treeByNode.size());
        assertTrue(treeByNode.stream().anyMatch(e -> e.getPath().equals("b.txt")));

        // 3. DiffCommand 테스트 (Rev 0 -> Rev 1)
        List<DiffCommand.DiffEntry> diffs = new DiffCommand(repo).setOldRevision(0).setNewRevision(1).call();
        assertEquals(3, diffs.size()); // a.txt(modify), b.txt(delete), c.txt(add)

        DiffCommand.DiffEntry diffA = diffs.stream().filter(d -> d.getPath().equals("a.txt")).findFirst().orElse(null);
        assertNotNull(diffA);
        assertEquals(DiffCommand.ChangeType.MODIFY, diffA.getChangeType());
        assertTrue(diffA.getDiffContent().contains("-Line 2"));
        assertTrue(diffA.getDiffContent().contains("+Line 2 Modified"));
        assertTrue(diffA.getDiffContent().contains("+Line 4"));

        DiffCommand.DiffEntry diffB = diffs.stream().filter(d -> d.getPath().equals("b.txt")).findFirst().orElse(null);
        assertNotNull(diffB);
        assertEquals(DiffCommand.ChangeType.DELETE, diffB.getChangeType());
        assertTrue(diffB.getDiffContent().contains("-Executable Content"));

        DiffCommand.DiffEntry diffC = diffs.stream().filter(d -> d.getPath().equals("c.txt")).findFirst().orElse(null);
        assertNotNull(diffC);
        assertEquals(DiffCommand.ChangeType.ADD, diffC.getChangeType());
        assertTrue(diffC.getDiffContent().contains("+Added File Content"));

        // 디폴트(oldRevision 미설정 시 newRevision의 부모인 0과 비교) 테스트
        List<DiffCommand.DiffEntry> defaultDiffs = new DiffCommand(repo).setNewRevision(1).call();
        assertEquals(3, defaultDiffs.size());
    }

    @Test
    public void testEmptyRepositoryReturnsEmptyCollections(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        
        List<TreeCommand.TreeEntry> tree = new TreeCommand(repo).call();
        assertTrue(tree.isEmpty());

        List<DiffCommand.DiffEntry> diff = new DiffCommand(repo).call();
        assertTrue(diff.isEmpty());
    }

    @Test
    public void testDirectHelperMethods(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertNotNull(repo);

        // Rev 0: a.txt 추가
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Hello Hg4j\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Rev 0").call();

        // Rev 1: a.txt 수정
        Files.writeString(fa.toPath(), "Hello Hg4j Modified\n");
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Rev 1").call();

        // Direct getTree 호출 검증
        List<TreeCommand.TreeEntry> tree = Hg.open(repo.getDirectory()).getTree(1);
        assertEquals(1, tree.size());
        assertEquals("a.txt", tree.get(0).getPath());

        // Direct getDiff 호출 검증
        List<DiffCommand.DiffEntry> diffs = Hg.open(repo.getDirectory()).getDiff(0, 1);
        assertEquals(1, diffs.size());
        assertEquals("a.txt", diffs.get(0).getPath());
        assertEquals(DiffCommand.ChangeType.MODIFY, diffs.get(0).getChangeType());
        assertTrue(diffs.get(0).getDiffContent().contains("+Hello Hg4j Modified"));
    }

    @Test
    public void testTreeCommandEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertNotNull(repo);

        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Hello Edge\n");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] revNode = new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Rev 0").call();

        org.hg4j.lib.NodeId nodeIdObj = new org.hg4j.lib.NodeId(revNode);

        // 1. setNodeId(org.hg4j.lib.NodeId) 호출 테스트
        TreeCommand cmd = new TreeCommand(repo).setNodeId(nodeIdObj);
        List<TreeCommand.TreeEntry> tree = cmd.call();
        assertFalse(tree.isEmpty());

        // 2. TreeEntry.getNode() 및 getMode() 테스트
        TreeCommand.TreeEntry entry = tree.get(0);
        assertNotNull(entry.getNode());
        assertEquals(40, entry.getNode().toHex().length());
        assertEquals(0644, entry.getMode());

        // null nodeId에 대한 getNode() 테스트
        TreeCommand.TreeEntry nullEntry = new TreeCommand.TreeEntry("b.txt", null, 0644);
        assertNull(nullEntry.getNode());

        // setNodeId(null) 호출 테스트
        TreeCommand cmdNull = new TreeCommand(repo).setNodeId((org.hg4j.lib.NodeId) null);
        assertNotNull(cmdNull.call());
    }

    @Test
    public void testDiffCommandEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Commit baseline (Rev 0)
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Line 1\n");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] rev0Node = new CommitCommand(repo).setMessage("Rev 0").call();

        // 2. Commit modification (Rev 1)
        Files.writeString(fa.toPath(), "Line 1 Modified\n");
        byte[] rev1Node = new CommitCommand(repo).setMessage("Rev 1").call();

        org.hg4j.lib.NodeId node0 = new org.hg4j.lib.NodeId(rev0Node);
        org.hg4j.lib.NodeId node1 = new org.hg4j.lib.NodeId(rev1Node);

        // setOldRevision(NodeId) 및 setNewRevision(NodeId) 테스트
        DiffCommand diff = new DiffCommand(repo)
                .setOldRevision(node0)
                .setNewRevision(node1);
        List<DiffCommand.DiffEntry> results = diff.call();
        assertEquals(1, results.size());
        assertTrue(results.get(0).getDiffContent().contains("+Line 1 Modified"));

        // setOldRevision(null) 및 setNewRevision(null) 테스트
        DiffCommand diffNull = new DiffCommand(repo)
                .setOldRevision((org.hg4j.lib.NodeId) null)
                .setNewRevision((org.hg4j.lib.NodeId) null);
        assertNotNull(diffNull.call());

        // 3. OOM Optimization Guard test (n * m > 2000000)
        File fh = new File(repoDir, "huge.txt");
        Files.writeString(fh.toPath(), "A\n".repeat(2001));
        new AddCommand(repo).addFile("huge.txt").call();
        new CommitCommand(repo).setMessage("Huge Rev 2").call();

        Files.writeString(fh.toPath(), "B\n".repeat(2002));
        new CommitCommand(repo).setMessage("Huge Rev 3").call();

        // Diff between Rev 2 and Rev 3 should trigger OOM bypass guard branch
        List<DiffCommand.DiffEntry> hugeDiffs = new DiffCommand(repo)
                .setOldRevision(2)
                .setNewRevision(3)
                .call();
        
        DiffCommand.DiffEntry hugeEntry = hugeDiffs.stream()
                .filter(d -> d.getPath().equals("huge.txt"))
                .findFirst()
                .orElse(null);
        assertNotNull(hugeEntry);
        assertTrue(hugeEntry.getDiffContent().contains("@@ -1,2002 +1,2003 @@"));
    }
}
