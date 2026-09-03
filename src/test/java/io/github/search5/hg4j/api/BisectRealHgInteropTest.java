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

    private static void copyDirectory(Path source, Path target) throws java.io.IOException {
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
                        Files.copy(src, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
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
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
