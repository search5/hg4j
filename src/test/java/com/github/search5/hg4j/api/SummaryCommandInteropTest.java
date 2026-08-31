package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.phase.PhaseRoots;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg summary}에 해당하는 {@link SummaryCommand}를 실제 hg CLI(`hg parents`, `hg branch`,
 * `hg bookmarks`, `hg status`, `hg phase`)와 대조 검증한다.
 */
@Tag("interop")
public class SummaryCommandInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void emptyRepositoryHasNoParentsAndDefaultBranch(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertTrue(info.parents().isEmpty());
        assertEquals("default", info.branch());
        assertNull(info.activeBookmark());
        assertFalse(info.mergeInProgress());
        assertEquals(PhaseRoots.Phase.PUBLIC, info.currentPhase());
    }

    @Test
    public void singleParentMatchesRealHg(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setAuthor("T").setMessage("c1").call();

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(1, info.parents().size());
        SummaryCommand.ParentInfo p = info.parents().get(0);
        assertEquals(0, p.revision());
        assertEquals(NodeIdUtil.toHex(c1), p.node());
        assertEquals("c1", p.description());
        assertEquals(PhaseRoots.Phase.DRAFT, info.currentPhase());

        String nativeParent = HgTestUtils.hg(repoDir, "parents", "--template", "{node} {desc}");
        assertTrue(nativeParent.startsWith(p.node()));
        assertTrue(nativeParent.endsWith("c1"));

        String nativePhase = HgTestUtils.hg(repoDir, "phase", "-r", ".");
        assertTrue(nativePhase.contains("draft"), "실제 hg의 초기 커밋 phase도 draft여야 함: " + nativePhase);
    }

    @Test
    public void activeBookmarkAndStatusCountsMatchRealHg(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();
        hg.bookmark().setBookmarkName("mymark").call();

        // modified + added + removed + unknown 유발
        Files.writeString(a.toPath(), "one-modified");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "new tracked");
        hg.add().addFile("b.txt").call();
        Files.writeString(new File(repoDir, "c.txt").toPath(), "untracked");

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals("mymark", info.activeBookmark());
        assertEquals(1, info.modified());
        assertEquals(1, info.added());
        assertEquals(0, info.removed());
        assertEquals(1, info.unknown());

        String nativeStatus = HgTestUtils.hg(repoDir, "status");
        assertTrue(nativeStatus.contains("M a.txt"));
        assertTrue(nativeStatus.contains("A b.txt"));
        assertTrue(nativeStatus.contains("? c.txt"));

        // 실제 hg는 "hg bookmarks --active"라는 옵션이 없다 — 활성 bookmark는 목록에서
        // '*' 표시로 나타난다.
        String nativeBookmarks = HgTestUtils.hg(repoDir, "bookmarks");
        assertTrue(nativeBookmarks.contains("* mymark"), "실제 hg 목록에서 mymark가 활성(*)이어야 함: " + nativeBookmarks);
    }

    @Test
    public void mergeInProgressReportsTwoParents(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "base");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(f.toPath(), "branch-a");
        byte[] c2a = hg.commit().setAuthor("T").setMessage("c2a").call();

        hg.update().setRevision("0").call();
        Files.writeString(f.toPath(), "branch-b");
        hg.commit().setAuthor("T").setMessage("c2b").call();

        new MergeCommand(repo).setNodeId(c2a).call();

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(2, info.parents().size());
        assertTrue(info.mergeInProgress());

        String nativeParents = HgTestUtils.hg(repoDir, "parents", "--template", "{node}\\n");
        assertEquals(2, nativeParents.trim().split("\n").length);
    }

    @Test
    public void nonDefaultBranchIsReported(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        repo.setBranch("feature");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals("feature", info.branch());

        String nativeBranch = HgTestUtils.hg(repoDir, "branch");
        assertEquals("feature", nativeBranch.trim());
    }
}
