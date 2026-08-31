package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track C에서 누락됐던 코어 포셀린 명령들(root/tip/parents/forget/addremove/backout/
 * unbundle)을 실제 hg CLI와 대조 검증한다.
 */
@Tag("interop")
public class TrackCMissingCommandsInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void testRootMatchesRepositoryDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        String hg4jRoot = new RootCommand(repo).call();
        String nativeRoot = HgTestUtils.hg(repoDir, "root");
        assertEquals(new File(nativeRoot).getCanonicalPath(), new File(hg4jRoot).getCanonicalPath());
    }

    @Test
    public void testTipMatchesRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        byte[] tipNode = new TipCommand(repo).call();
        assertArrayEquals(java.util.Arrays.copyOf(c2, 20), tipNode);

        String nativeTip = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(nativeTip, NodeIdUtil.toHex(tipNode));
    }

    @Test
    public void testParentsMatchesRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setAuthor("T").setMessage("c1").call();

        List<String> parents = new ParentsCommand(repo).call();
        assertEquals(1, parents.size());
        assertEquals(NodeIdUtil.toHex(c1), parents.get(0));

        String nativeParent = HgTestUtils.hg(repoDir, "parents", "--template", "{node}");
        assertEquals(nativeParent, parents.get(0));
    }

    @Test
    public void testForgetKeepsFileOnDiskButUntracks(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        new ForgetCommand(repo).setFile("a.txt").call();

        assertTrue(f.exists(), "forget은 작업 사본 파일을 지우면 안 됨");
        Status status = new StatusCommand(repo).call();
        assertTrue(status.getRemoved().contains("a.txt"));

        String nativeStatus = HgTestUtils.hg(repoDir, "status");
        assertTrue(nativeStatus.contains("R a.txt") || nativeStatus.contains("? a.txt"),
                "실제 hg도 forget 이후 상태를 인식해야 함: " + nativeStatus);
    }

    @Test
    public void testAddremoveHandlesNewAndMissingFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // a.txt를 삭제(수동)하고 b.txt를 새로 만든 뒤 addremove
        Files.delete(a.toPath());
        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");

        List<String> affected = new AddremoveCommand(repo).call();
        assertTrue(affected.contains("A b.txt"));
        assertTrue(affected.contains("R a.txt"));

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getAdded().contains("b.txt"));
        assertTrue(status.getRemoved().contains("a.txt"));
    }

    @Test
    public void testUnbundleAppliesRealHgBundle(@TempDir Path tempDir) throws Exception {
        // 1. 실제 hg로 원본 저장소 생성 + 번들 파일 생성
        File sourceDir = tempDir.resolve("source").toFile();
        HgTestUtils.nativeRepo(sourceDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "content");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(sourceDir, "add");
        HgTestUtils.hg(sourceDir, "commit", "-u", "T", "-m", "c1");
        File bundleFile = tempDir.resolve("out.hg").toFile();
        HgTestUtils.hg(sourceDir, "bundle", "--all", bundleFile.getAbsolutePath());
        assertTrue(bundleFile.exists());

        // 2. hg4j 빈 저장소에 unbundle
        File targetDir = tempDir.resolve("target").toFile();
        HgRepository target = Hg.init().setDirectory(targetDir).call();
        List<byte[]> imported = new UnbundleCommand(target).setBundleFile(bundleFile).call();
        assertEquals(1, imported.size());

        File clIdx = new File(target.getStoreDir(), "00changelog.i");
        File clDat = new File(target.getStoreDir(), "00changelog.d");
        assertEquals(1, target.getRevlog(clIdx, clDat).getRevisionCount());
    }

    @Test
    public void testTipOnEmptyRepositoryReturnsNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertNull(new TipCommand(repo).call());
        assertEquals(-1, new TipCommand(repo).getRevisionNumber());
    }

    @Test
    public void testTipWithExistingButEmptyChangelogFileReturnsNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        clIdx.getParentFile().mkdirs();
        assertTrue(clIdx.createNewFile(), "changelog 인덱스 파일이 존재하지만 리비전은 0개인 상황을 재현");

        assertNull(new TipCommand(repo).call());
    }

    @Test
    public void testTipRevisionNumberMatchesRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        hg.commit().setAuthor("T").setMessage("c2").call();

        assertEquals(1, new TipCommand(repo).getRevisionNumber());
        String nativeTipRev = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{rev}");
        assertEquals("1", nativeTipRev);
    }

    @Test
    public void testForgetRejectsNullOrEmptyFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertThrows(IllegalStateException.class, () -> new ForgetCommand(repo).call());
        assertThrows(IllegalStateException.class, () -> new ForgetCommand(repo).setFile("").call());
    }

    @Test
    public void testForgetRejectsUntrackedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertThrows(com.github.search5.hg4j.errors.HgValidationException.class,
                () -> new ForgetCommand(repo).setFile("nope.txt").call());
    }

    @Test
    public void testForgetOnAddedNotYetCommittedFileFullyUntracks(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "one");
        hg.add().addFile("a.txt").call();

        new ForgetCommand(repo).setFile("a.txt").call();

        assertTrue(f.exists(), "forget은 작업 사본 파일을 지우면 안 됨");
        assertFalse(repo.getDirstate().getEntries().containsKey("a.txt"),
                "커밋된 적 없는 파일을 forget하면 dirstate에서 완전히 제거되어야 함");
    }

    @Test
    public void testUnbundleAppliesHg10UnGzAndBzFormats(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgTestUtils.nativeRepo(sourceDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "content");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(sourceDir, "add");
        HgTestUtils.hg(sourceDir, "commit", "-u", "T", "-m", "c1");

        for (String bundleType : new String[]{"none-v1", "gzip-v1", "bzip2-v1"}) {
            File bundleFile = tempDir.resolve("out-" + bundleType + ".hg").toFile();
            HgTestUtils.hg(sourceDir, "bundle", "--all", "--type=" + bundleType, bundleFile.getAbsolutePath());
            assertTrue(bundleFile.exists());

            File targetDir = tempDir.resolve("target-" + bundleType).toFile();
            HgRepository target = Hg.init().setDirectory(targetDir).call();
            List<byte[]> imported = new UnbundleCommand(target).setBundleFile(bundleFile).call();
            assertEquals(1, imported.size(), "bundle type " + bundleType + " should import exactly one changeset");
        }
    }

    @Test
    public void testUnbundleRejectsMissingOrUnrecognizedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(IllegalStateException.class,
                () -> new UnbundleCommand(repo).setBundleFile(new File(tempDir.toFile(), "nope.hg")).call());

        File garbage = tempDir.resolve("garbage.hg").toFile();
        Files.writeString(garbage.toPath(), "not a bundle at all");
        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class,
                () -> new UnbundleCommand(repo).setBundleFile(garbage).call());
    }

    @Test
    public void testBackoutRevertsChangeAndCreatesNewCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "original");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(f.toPath(), "changed");
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        byte[] c3 = new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call();
        assertNotNull(c3);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        assertEquals(3, repo.getRevlog(clIdx, clDat).getRevisionCount());
        assertEquals("original", Files.readString(f.toPath()), "backout 후 파일 내용이 c1 시점으로 돌아와야 함");

        String nativeLog = HgTestUtils.hg(repoDir, "log", "--template", "{desc}\\n");
        assertTrue(nativeLog.contains("Backed out changeset"));
    }

    @Test
    public void testBackoutRejectsEmptyRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertThrows(IllegalStateException.class, () -> new BackoutCommand(repo).setAuthor("T").call());
        assertThrows(IllegalStateException.class, () -> new BackoutCommand(repo).setRevision("").setAuthor("T").call());
    }

    @Test
    public void testBackoutRootCommitRemovesAllItsFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "root content");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setAuthor("T").setMessage("c1").call();

        new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c1)).setAuthor("T").call();

        assertFalse(f.exists(), "부모가 없는 root 커밋을 backout하면 그 커밋에서 추가된 파일이 제거되어야 함");
        // backout이 제거를 이미 새 커밋으로 반영했으므로 status는 깨끗해야 하고(대기 중인
        // 변경 없음), 실제 hg에서도 tip 시점에 추적되는 파일이 없어야 한다.
        String nativeStatus = HgTestUtils.hg(repoDir, "status");
        assertEquals("", nativeStatus.trim());
    }

    @Test
    public void testBackoutRejectsUnknownRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        assertThrows(com.github.search5.hg4j.errors.HgValidationException.class,
                () -> new BackoutCommand(repo).setRevision("deadbeef".repeat(5)).setAuthor("T").call());
    }

    @Test
    public void testBackoutRejectsMergeChangeset(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File a = new File(repoDir, "a.txt");
        File b = new File(repoDir, "b.txt");
        Files.writeString(a.toPath(), "base-a");
        Files.writeString(b.toPath(), "base-b");
        hg.add().addFile("a.txt").call();
        hg.add().addFile("b.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // 서로 다른 파일을 고치는 두 브랜치 — 충돌 없이 깨끗하게 병합되어야 함.
        Files.writeString(a.toPath(), "branch-a change");
        byte[] c2a = hg.commit().setAuthor("T").setMessage("c2a").call();

        hg.update().setRevision("0").call();
        Files.writeString(b.toPath(), "branch-b change");
        hg.commit().setAuthor("T").setMessage("c2b").call();

        MergeCommand.MergeResult mergeResult = new MergeCommand(repo).setNodeId(c2a).call();
        assertFalse(mergeResult.isConflicted(), "서로 다른 파일을 고친 브랜치는 충돌 없이 병합돼야 함");
        byte[] mergeNode = hg.commit().setAuthor("T").setMessage("merge").call();

        assertThrows(com.github.search5.hg4j.errors.HgValidationException.class,
                () -> new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(mergeNode)).setAuthor("T").call());
    }

    @Test
    public void testBackoutRemovesFileAddedByTargetAndRestoresFileDeletedByTarget(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File keep = new File(repoDir, "keep.txt");
        File toDelete = new File(repoDir, "toDelete.txt");
        Files.writeString(keep.toPath(), "keep");
        Files.writeString(toDelete.toPath(), "will be deleted by target");
        hg.add().addFile("keep.txt").call();
        hg.add().addFile("toDelete.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // c2: toDelete.txt를 삭제하고 added.txt를 새로 추가
        new RemoveCommand(repo).setFile("toDelete.txt").call();
        File added = new File(repoDir, "added.txt");
        Files.writeString(added.toPath(), "added by c2");
        hg.add().addFile("added.txt").call();
        byte[] c2 = hg.commit().setAuthor("T").setMessage("c2").call();

        new BackoutCommand(repo).setRevision(NodeIdUtil.toHex(c2)).setAuthor("T").call();

        assertFalse(added.exists(), "backout는 대상 리비전에서 추가된 파일을 제거해야 함");
        assertTrue(toDelete.exists(), "backout는 대상 리비전에서 삭제된 파일을 복원해야 함");
        assertEquals("will be deleted by target", Files.readString(toDelete.toPath()));

        String nativeStatus = HgTestUtils.hg(repoDir, "status");
        assertFalse(nativeStatus.contains("added.txt"));
    }

    @Test
    public void testBackoutWithCustomMessage(@TempDir Path tempDir) throws Exception {
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
                .setMessage("custom backout message").call();
        assertNotNull(c3);

        String nativeLog = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("custom backout message", nativeLog);
    }
}
