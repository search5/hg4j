package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.github.search5.hg4j.dirstate.Dirstate;
import static io.github.search5.hg4j.lib.NodeId.NULL;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Assertions;

/**
 * 백로그 23 (bisect 카테고리): {@code hg4j}의 {@link BisectCommand}가 실제 {@code hg
 * bisect good/bad}와 "나란히" 같은 커밋 그래프를 이분 탐색해 같은 culprit(원인 리비전)에
 * 도달하는지 검증한다 -- 기존 {@code BisectCommandTest}/{@code BisectCommandCoverageTest}는
 * hg4j 자체 왕복만 검증했으므로 이 항목 기준으로는 "미검증"이었다.
 *
 * <p>두 워크가 서로 다른 워킹 디렉터리(각각 hg4j / real hg가 checkout을 갱신)를 갖도록
 * 저장소를 복제한 뒤, 매 스텝마다 "다음 후보의 flag.txt 내용을 읽어 good/bad를 판정"하는
 * 완전히 동일한 오라클로 두 워크를 독립적으로 진행시킨다.</p>
 */
@Tag("interop")
public class BisectRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static String hexAt(Revlog changelog, int rev) {
        return NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId());
    }

    @Test
    public void bisectConvergesToSameCulpritAsRealHg(@TempDir Path tempDir) throws Exception {
        File origDir = tempDir.resolve("orig").toFile();
        new InitCommand().setDirectory(origDir).call();
        HgRepository origRepo = new HgRepository(origDir);
        Files.writeString(new File(origDir, ".hg/hgrc").toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n");

        // 15 revisions (0..14): flag.txt flips from "0" to "1" starting at rev 9 -- the
        // regression's true culprit real hg's own bisect should land on too.
        int totalRevs = 15;
        int culpritRev = 9;
        List<String> nodeHexes = new ArrayList<>();
        for (int i = 0; i < totalRevs; i++) {
            String flag = (i < culpritRev) ? "0" : "1";
            Files.writeString(new File(origDir, "flag.txt").toPath(), flag);
            Files.writeString(new File(origDir, "note.txt").toPath(), "rev" + i);
            new AddCommand(origRepo).call();
            byte[] node = new CommitCommand(origRepo).setAuthor("T").setMessage("rev" + i).call();
            nodeHexes.add(NodeIdUtil.toHex(node));
        }

        File clIdx = new File(origRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(origRepo.getStoreDir(), "00changelog.d");
        Revlog changelog = origRepo.getRevlog(clIdx, clDat);
        assertEquals(totalRevs, changelog.getRevisionCount());

        String goodHex = hexAt(changelog, 0);
        String badHex = hexAt(changelog, totalRevs - 1);
        String expectedCulpritHex = hexAt(changelog, culpritRev);

        // Clone the finished repository into two independent working copies: one driven by
        // hg4j's own BisectCommand, one driven by the real hg CLI.
        File hg4jDir = tempDir.resolve("hg4j-walk").toFile();
        File nativeDir = tempDir.resolve("native-walk").toFile();
        copyDirectory(origDir.toPath(), hg4jDir.toPath());
        copyDirectory(origDir.toPath(), nativeDir.toPath());

        // ---- hg4j walk ----
        HgRepository hg4jRepo = new HgRepository(hg4jDir);
        byte[] good = NodeIdUtil.fromHex(goodHex);
        byte[] bad = NodeIdUtil.fromHex(badHex);
        Revlog hg4jChangelog = hg4jRepo.getRevlog(
                new File(hg4jRepo.getStoreDir(), "00changelog.i"),
                new File(hg4jRepo.getStoreDir(), "00changelog.d"));

        List<Integer> hg4jCandidates = new ArrayList<>();
        int goodRev = 0;
        int badRev = totalRevs - 1;
        int guard = 0;
        while (badRev - goodRev > 1 && guard++ < totalRevs + 2) {
            byte[] candidateNode = new BisectCommand(hg4jRepo).setGood(good).setBad(bad).next();
            int candidateRev = hg4jChangelog.findRevision(candidateNode);
            hg4jCandidates.add(candidateRev);

            String flagContent = Files.readString(new File(hg4jDir, "flag.txt").toPath()).trim();
            if ("0".equals(flagContent)) {
                good = candidateNode;
                goodRev = candidateRev;
            } else {
                bad = candidateNode;
                badRev = candidateRev;
            }
        }
        String hg4jCulpritHex = NodeIdUtil.toHex(bad);

        // ---- real hg walk (identical good/bad-by-flag.txt oracle) ----
        HgTestUtils.hg(nativeDir, "bisect", "--reset");
        HgTestUtils.hg(nativeDir, "bisect", "--good", goodHex);
        String out = HgTestUtils.hg(nativeDir, "bisect", "--bad", badHex);

        List<Integer> nativeCandidates = new ArrayList<>();
        String culpritLine = null;
        int nativeGuard = 0;
        while (!out.contains("The first bad revision is:") && !out.contains("The first bad changeset is:")
                && nativeGuard++ < totalRevs + 2) {
            Integer testingRev = parseTestingRev(out);
            if (testingRev != null) {
                nativeCandidates.add(testingRev);
            }
            String flagContent = Files.readString(new File(nativeDir, "flag.txt").toPath()).trim();
            out = HgTestUtils.hg(nativeDir, "bisect", "0".equals(flagContent) ? "--good" : "--bad");
        }
        culpritLine = out;

        // 1. Both walks must agree on exactly which revision is the culprit ...
        assertEquals(expectedCulpritHex, hg4jCulpritHex,
                "hg4j's own bisect walk must converge on the actual injected-bug revision");
        assertTrue(culpritLine.contains(expectedCulpritHex.substring(0, 12))
                        || culpritLine.contains(culpritRev + ":"),
                "Real hg bisect must report the same culprit revision (rev " + culpritRev + " / "
                        + expectedCulpritHex + "), got:\n" + culpritLine);

        // 2. ... and must have tested the exact same sequence of candidate revisions along the
        // way (proof the two bisection algorithms, not just their final answer, agree).
        assertEquals(nativeCandidates, hg4jCandidates,
                "hg4j and real hg must pick the identical sequence of bisect candidates given the same good/bad oracle");
    }

    /**
     * 백로그 34: merge 커밋이 있는 실제 DAG(branch A/B가 각각 진행하다 합쳐지는 히스토리)에서
     * hg4j {@link BisectCommand}가 real hg의 {@code hg bisect}와 동일한 culprit·동일한 후보
     * 시퀀스로 수렴하는지 검증한다. 지금까지는 {@link #bisectConvergesToSameCulpritAsRealHg}
     * 하나뿐이었고 그건 순수 선형 히스토리라 merge 커밋의 두 부모(getParent2 경로)를 전혀
     * 타지 않았다.
     *
     * <p>토폴로지: rev0(root, flag=0) 이후 두 브랜치로 분기 -- 브랜치 A(rev1,rev2)는
     * branchA.txt만 건드려 flag.txt는 rev0 그대로("0")이고, 브랜치 B(rev3,rev4,rev5)는
     * rev4에서 flag.txt를 "1"로 바꾼다(진짜 culprit). rev6이 두 브랜치 tip(rev2, rev5)을
     * 합치는 실제 merge 커밋(3-way 자동 병합, 충돌 없음 -- flag.txt는 B쪽에서만 바뀌었으므로).
     * rev7이 merge 이후 한 커밋 더 진행. good=rev0, bad=rev7로 bisect하면 그 사이의 전체
     * DAG(브랜치 A의 두 커밋 포함)가 탐색 대상이 된다.</p>
     */
    @Test
    public void bisectConvergesToSameCulpritAsRealHgAcrossMergeCommit(@TempDir Path tempDir) throws Exception {
        File origDir = tempDir.resolve("orig-merge").toFile();
        new InitCommand().setDirectory(origDir).call();
        HgRepository origRepo = new HgRepository(origDir);
        Files.writeString(new File(origDir, ".hg/hgrc").toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n");

        // rev0: root.
        Files.writeString(new File(origDir, "flag.txt").toPath(), "0");
        new AddCommand(origRepo).call();
        byte[] rev0 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev0").call();

        // Branch A (rev1, rev2): never touches flag.txt.
        Files.writeString(new File(origDir, "branchA.txt").toPath(), "a1");
        new AddCommand(origRepo).call();
        byte[] rev1 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev1").call();
        Files.writeString(new File(origDir, "branchA.txt").toPath(), "a2");
        byte[] rev2 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev2").call();

        // Fork branch B off rev0.
        Dirstate forkDirstate = origRepo.getDirstate();
        forkDirstate.setParents(rev0, NULL.getBytes());
        origRepo.writeDirstate(forkDirstate);
        // branchA.txt is left physically on disk (still dirstate-tracked from the rev1/rev2
        // commits) rather than deleted -- deleting it here trips CommitCommand's merge-time
        // "tracked file must exist on disk" guard down at rev6. Matching the same working
        // pattern already proven in TagRealHgInteropTest's merge-commit test: harmless for this
        // test's purpose (branchA.txt never affects the flag.txt oracle bisect actually reads).

        Files.writeString(new File(origDir, "branchB.txt").toPath(), "b1");
        new AddCommand(origRepo).call();
        byte[] rev3 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev3").call();
        // rev4: the real culprit -- flag.txt flips to "1" here, only on branch B.
        Files.writeString(new File(origDir, "flag.txt").toPath(), "1");
        Files.writeString(new File(origDir, "branchB.txt").toPath(), "b2");
        byte[] rev4 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev4").call();
        Files.writeString(new File(origDir, "branchB.txt").toPath(), "b3");
        byte[] rev5 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev5").call();

        // rev6: real merge commit (parents rev2, rev5) -- checkout branch A's tip, merge branch
        // B's tip in (non-conflicting 3-way: only branch B touched flag.txt), commit.
        new UpdateCommand(origRepo).setRevision(NodeIdUtil.toHex(rev2)).setForce(true).call();
        new MergeCommand(origRepo).setNodeId(rev5).call();
        byte[] rev6 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev6 merge").call();

        // rev7: one more commit past the merge.
        Files.writeString(new File(origDir, "note.txt").toPath(), "post-merge");
        new AddCommand(origRepo).call();
        byte[] rev7 = new CommitCommand(origRepo).setAuthor("T").setMessage("rev7").call();

        File clIdx = new File(origRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(origRepo.getStoreDir(), "00changelog.d");
        Revlog changelog = origRepo.getRevlog(clIdx, clDat);
        assertEquals(8, changelog.getRevisionCount());

        // Sanity: rev6 must genuinely be a 2-parent merge of rev2/rev5, and the merged working
        // copy must have auto-resolved flag.txt to branch B's "1" (proof this is a real,
        // content-correct merge, not a synthetic parent-pointer fake).
        int rev6Idx = changelog.findRevision(rev6);
        Revlog.IndexRecord rev6Rec = changelog.getIndexRecord(rev6Idx);
        assertEquals(changelog.findRevision(rev2), rev6Rec.getParent1());
        assertEquals(changelog.findRevision(rev5), rev6Rec.getParent2());
        assertEquals("1", Files.readString(new File(origDir, "flag.txt").toPath()).trim());
        assertEquals("a2", Files.readString(new File(origDir, "branchA.txt").toPath()).trim());
        assertEquals("b3", Files.readString(new File(origDir, "branchB.txt").toPath()).trim());

        String goodHex = NodeIdUtil.toHex(rev0);
        String badHex = NodeIdUtil.toHex(rev7);
        String expectedCulpritHex = NodeIdUtil.toHex(rev4);

        File hg4jDir = tempDir.resolve("hg4j-walk-merge").toFile();
        File nativeDir = tempDir.resolve("native-walk-merge").toFile();
        copyDirectory(origDir.toPath(), hg4jDir.toPath());
        copyDirectory(origDir.toPath(), nativeDir.toPath());

        // ---- hg4j walk ----
        HgRepository hg4jRepo = new HgRepository(hg4jDir);
        byte[] good = NodeIdUtil.fromHex(goodHex);
        byte[] bad = NodeIdUtil.fromHex(badHex);
        Revlog hg4jChangelog = hg4jRepo.getRevlog(
                new File(hg4jRepo.getStoreDir(), "00changelog.i"),
                new File(hg4jRepo.getStoreDir(), "00changelog.d"));

        List<Integer> hg4jCandidates = new ArrayList<>();
        int guard = 0;
        int maxSteps = changelog.getRevisionCount() + 2;
        while (guard++ < maxSteps) {
            byte[] candidateNode = new BisectCommand(hg4jRepo).setGood(good).setBad(bad).next();
            int candidateRev = hg4jChangelog.findRevision(candidateNode);
            hg4jCandidates.add(candidateRev);

            String flagContent = Files.readString(new File(hg4jDir, "flag.txt").toPath()).trim();
            if ("0".equals(flagContent)) {
                good = candidateNode;
            } else {
                bad = candidateNode;
            }
            int goodRev = hg4jChangelog.findRevision(good);
            int badRev = hg4jChangelog.findRevision(bad);
            if (isAdjacentAlongEveryPath(hg4jChangelog, goodRev, badRev)) {
                break;
            }
        }
        String hg4jCulpritHex = NodeIdUtil.toHex(bad);

        // ---- real hg walk (identical good/bad-by-flag.txt oracle) ----
        HgTestUtils.hg(nativeDir, "bisect", "--reset");
        HgTestUtils.hg(nativeDir, "bisect", "--good", goodHex);
        String out = HgTestUtils.hg(nativeDir, "bisect", "--bad", badHex);

        List<Integer> nativeCandidates = new ArrayList<>();
        int nativeGuard = 0;
        while (!out.contains("The first bad revision is:") && !out.contains("The first bad changeset is:")
                && nativeGuard++ < maxSteps) {
            Integer testingRev = parseTestingRev(out);
            if (testingRev != null) {
                nativeCandidates.add(testingRev);
            }
            String flagContent = Files.readString(new File(nativeDir, "flag.txt").toPath()).trim();
            out = HgTestUtils.hg(nativeDir, "bisect", "0".equals(flagContent) ? "--good" : "--bad");
        }
        String culpritLine = out;

        assertEquals(expectedCulpritHex, hg4jCulpritHex,
                "hg4j's own bisect walk must converge on the actual injected-bug revision across the merge DAG");
        assertTrue(culpritLine.contains(expectedCulpritHex.substring(0, 12))
                        || culpritLine.contains(changelog.findRevision(rev4) + ":"),
                "Real hg bisect must report the same culprit revision across the merge DAG, got:\n" + culpritLine);
        assertEquals(nativeCandidates, hg4jCandidates,
                "hg4j and real hg must pick the identical sequence of bisect candidates across a merge-commit DAG");
    }

    /** True once good/bad are adjacent enough along every DAG path that no further candidate exists. */
    private static boolean isAdjacentAlongEveryPath(Revlog changelog, int goodRev, int badRev) throws IOException {
        int min = Math.min(goodRev, badRev);
        int max = Math.max(goodRev, badRev);
        for (int i = min + 1; i < max; i++) {
            // Any revision strictly between good and bad that is both a descendant of good and
            // an ancestor of bad is still a live candidate -- reuse the exact reachability rule
            // BisectCommand itself uses so this guard agrees with what next() would still offer.
            if (isDescendantOf(changelog, i, min, max) && isAncestorOf(changelog, i, min, max)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDescendantOf(Revlog changelog, int rev, int min, int max) throws IOException {
        boolean[] seen = new boolean[max + 1];
        seen[min] = true;
        for (int i = min + 1; i <= max; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if ((p1 >= min && p1 <= max && seen[p1]) || (p2 >= min && p2 <= max && seen[p2])) {
                seen[i] = true;
            }
        }
        return seen[rev];
    }

    private static boolean isAncestorOf(Revlog changelog, int rev, int min, int max) throws IOException {
        boolean[] seen = new boolean[max + 1];
        seen[max] = true;
        for (int i = max; i > min; i--) {
            if (!seen[i]) continue;
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 >= min) seen[p1] = true;
            if (p2 >= min) seen[p2] = true;
        }
        return seen[rev];
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    if (Files.isSymbolicLink(src)) {
                        Files.createSymbolicLink(dest, Files.readSymbolicLink(src));
                    } else {
                        Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
    }

    private static Integer parseTestingRev(String out) {
        // Real hg prints e.g. "Testing changeset 8:353bd840d879 \"rev8\" (3 changesets remaining, ~1 tests)"
        int idx = out.indexOf("Testing changeset ");
        if (idx == -1) return null;
        int start = idx + "Testing changeset ".length();
        int colon = out.indexOf(':', start);
        if (colon == -1) return null;
        return Integer.parseInt(out.substring(start, colon).trim());
    }

    private static void assertTrue(boolean cond, String msg) {
        Assertions.assertTrue(cond, msg);
    }
}
