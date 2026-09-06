package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * 백로그 23 (strip 카테고리): {@code hg4j}의 {@link StripCommand}가 만든 결과물을 실제
 * {@code hg verify}/{@code hg log}/{@code hg cat}으로 대조 검증한다 -- 기존
 * {@code StripCommandTest}/{@code StripCommandCoverageTest}는 hg4j 자체 왕복만
 * 검증했으므로 이 항목 기준으로는 "미검증"이었다.
 */
@Tag("interop")
public class StripRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * StripCommand.truncateRevlog()의 .d(데이터) 파일 truncate는 정확한 오프셋이 아니라
     * "datFile.length() * keepCount / (keepCount+1)" 라는 근사치 추정을 쓴다(주석에 "Safe
     * estimation fallback"이라고 적혀 있음). 리비전마다 콘텐츠 크기가 크게 다르면 이 추정이
     * 실제 오프셋보다 작게 나와 살아남아야 할 리비전의 델타 바이트 일부를 잘라버릴 수 있다
     * -- 이 테스트는 일부러 리비전 크기를 크게 들쭉날쭉하게 만들어 그 근사가 실제로 데이터를
     * 파괴하는지 real hg verify로 확인한다.
     */
    @Test
    public void stripWithUnevenRevisionSizesLeavesVerifiableRepo(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        File hgrc = new File(repoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), "[format]\nusezstd = false\nrevlog-compression = zlib\n");

        File f = new File(repoDir, "a.txt");
        // rev0: small
        Files.writeString(f.toPath(), "x".repeat(50));
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("rev0-small").call();

        // rev1: large (forces .d offset for rev2 to be way past the naive "half" estimate)
        Files.writeString(f.toPath(), "y".repeat(50_000));
        new CommitCommand(repo).setAuthor("T").setMessage("rev1-large").call();

        // rev2: small again -- this is the revision we want to KEEP after stripping rev3
        Files.writeString(f.toPath(), "z".repeat(60));
        new CommitCommand(repo).setAuthor("T").setMessage("rev2-small-to-keep").call();

        // rev3: the one we strip
        Files.writeString(f.toPath(), "w".repeat(70));
        new CommitCommand(repo).setAuthor("T").setMessage("rev3-to-strip").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelogBefore = repo.getRevlog(clIdx, clDat);
        assertEquals(4, changelogBefore.getRevisionCount());
        String rev2NodeHex = NodeIdUtil.toHex(changelogBefore.getIndexRecord(2).getNodeId());

        new StripCommand(repo).setRevision("3").call();

        // 1. real hg verify must find the resulting repository completely healthy.
        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertTrue(verifyOut.contains("0 integrity errors")
                        || verifyOut.toLowerCase().contains("checked"),
                "Real hg verify must report a clean repository after strip, got: " + verifyOut);

        // 2. real hg log must see exactly 3 revisions (0,1,2), with rev2 as tip.
        String log = HgTestUtils.hg(repoDir, "log", "--template", "{rev}:{node}\\n");
        List<String> lines = List.of(log.split("\n"));
        assertEquals(3, lines.size(), "3 revisions must remain after stripping rev3; got:\n" + log);

        // 3. Critically: rev2's *content* (the surviving revision right before the
        // stripped one) must still be byte-for-byte intact -- this is exactly what the
        // approximate .d truncate estimate could corrupt.
        String catOut = HgTestUtils.hg(repoDir, "cat", "-r", rev2NodeHex, "a.txt");
        assertEquals("z".repeat(60), catOut,
                "rev2's file content must survive strip uncorrupted -- an approximate .d truncate could clip it");
    }

    @Test
    public void stripMiddleRevisionKeepsAncestorsVerifiable(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n");

        File f = new File(repoDir, "a.txt");
        for (int i = 0; i < 5; i++) {
            Files.writeString(f.toPath(), "content-" + i + "-".repeat(200 + i * 137));
            new AddCommand(repo).call();
            new CommitCommand(repo).setAuthor("T").setMessage("rev" + i).call();
        }

        new StripCommand(repo).setRevision("2").call();

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertTrue(verifyOut.contains("0 integrity errors") || verifyOut.toLowerCase().contains("checked"),
                "Real hg verify must be clean after stripping a middle revision, got: " + verifyOut);

        String log = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\\n");
        assertEquals(2, log.split("\n").length, "Only rev0,rev1 should remain");

        for (int i = 0; i < 2; i++) {
            String content = HgTestUtils.hg(repoDir, "cat", "-r", String.valueOf(i), "a.txt");
            assertEquals("content-" + i + "-".repeat(200 + i * 137), content,
                    "rev" + i + " content must be intact after strip");
        }
    }
}
