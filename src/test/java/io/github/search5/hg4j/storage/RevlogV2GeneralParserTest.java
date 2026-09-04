package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.nio.charset.StandardCharsets;

/**
 * General (non-changelog) revlog v2 (`exp-revlogv2.2`, applied here to a filelog: {@code a.txt})
 * docket/index parsing — verified against a real hg-generated fixture.
 *
 * <p>Generated with a Rust-extension-enabled real Mercurial 7.2.4 build (see
 * {@code docker/hg-rust-7.2.4/Dockerfile} — the plain pure-Python `hg` on this machine refuses to
 * create `exp-revlogv2.2`/`persistent-nodemap` repositories at all: "accessing `fileindex`
 * repository without associated fast implementation"), via
 * {@code hg --config experimental.revlogv2=enable-unstable-format-and-corrupt-my-data
 * --config format.use-persistent-nodemap=true init}, two commits to {@code a.txt} ("hello\n" then
 * "hello world\n"). See src/test/resources/fixtures/revlogv2-general/README.md.</p>
 */
@DisplayName("Revlog v2 general (exp-revlogv2.2, manifest/filelog) docket/index parsing — verified against real hg-generated fixtures")
public class RevlogV2GeneralParserTest {

    @TempDir
    Path tempDir;

    private void copyFixture(String resourceName, String targetName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/revlogv2-general/data-a.txt/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File setupRealFixture() throws IOException {
        copyFixture("docket.i", "a.txt.i");
        copyFixture("data.idx", "a.txt-c225d8ca.idx");
        copyFixture("data.dat", "a.txt-dd463be9.dat");
        copyFixture("data.sda", "a.txt-b2e14389.sda");
        return tempDir.resolve("a.txt.i").toFile();
    }

    @Test
    @DisplayName("REVLOGV2(0xDEAD) 매직의 59바이트 S_HEADER를 정확히 파싱하고 changelog-v2(0xD34D)와 구별한다")
    void testParseRealDocketHeader() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        assertTrue(index.isV2(), "REVLOGV2 매직(0xDEAD)이 감지되어야 함");
        assertFalse(index.isChangelogV2(), "0xDEAD는 changelog-v2(0xD34D)가 아니라 일반 revlog v2여야 함");
        assertEquals(0xDEAD, index.getVersionHeader() & 0xFFFF);
        assertEquals(192, index.getDocketIndexEnd(), "index_end는 2레코드*96바이트=192");
        assertEquals(18, index.getDocketDataEnd(), "data_end는 실제 데이터 총합 \"hello\\n\"(6)+\"hello world\\n\"(12)=18");
    }

    @Test
    @DisplayName("docket의 UUID로 실제 index/data 파일을 정확히 찾아 연결한다")
    void testResolvesCompanionFilesByUuid() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        assertEquals("a.txt-c225d8ca.idx", index.getResolvedIndexFile().getName());
        assertEquals("a.txt-dd463be9.dat", index.getResolvedDataFile().getName());
        assertTrue(index.getResolvedIndexFile().exists());
        assertTrue(index.getResolvedDataFile().exists());
    }

    @Test
    @DisplayName("일반 v2 INDEX_ENTRY_V2 레코드의 baseRev/linkRev/parent가 changelog-v2와 달리 명시적으로 저장된다")
    void testParsesExplicitBaseRevAndLinkRevAndParents() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        assertEquals(2, index.getRevisionCount());

        Revlog.IndexRecord rec0 = index.getIndexRecord(0);
        assertEquals(0, rec0.getOffset());
        assertEquals(6, rec0.getCompLen());
        assertEquals(6, rec0.getUncompLen());
        assertEquals(-1, rec0.getParent1());
        assertEquals(-1, rec0.getParent2());
        assertEquals(0, rec0.getLinkRev());

        Revlog.IndexRecord rec1 = index.getIndexRecord(1);
        assertEquals(6, rec1.getOffset(), "rev1의 데이터 오프셋은 rev0의 압축 길이(6) 바로 뒤");
        assertEquals(12, rec1.getCompLen());
        assertEquals(12, rec1.getUncompLen());
        assertEquals(0, rec1.getParent1(), "rev1의 parent는 rev0");
        assertEquals(-1, rec1.getParent2());
        assertEquals(1, rec1.getLinkRev());

        // 실제 `hg log --debug`/`hg manifest --debug` 출력과 대조된 노드 id
        assertEquals(0, index.findRevision(NodeIdUtil.fromHex(
                "2c186c8c5bc0df5af5b951afe407d803f9e6b8c9".substring(0, 40))));
        assertEquals(1, index.findRevision(NodeIdUtil.fromHex(
                "faa62ea5d798c6624f63d25f2e64f1c107815f20")));
    }

    @Test
    @DisplayName("end-to-end: Revlog를 통해 실제 (비압축 PLAIN) 리비전 내용을 올바르게 복원한다")
    void testRevlogReadsRealContentEndToEnd() throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "a.txt.d"));

        assertEquals(2, revlog.getRevisionCount());

        byte[] raw0 = revlog.getRawRevisionContent(0);
        assertEquals("hello\n", new String(raw0, StandardCharsets.UTF_8));

        byte[] raw1 = revlog.getRawRevisionContent(1);
        assertEquals("hello world\n", new String(raw1, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Revlog.appendRevision()으로 일반 v2 저장소에 새 리비전을 쓰면 실제 디스크에 영속화되고 재오픈해도 읽힌다")
    void testAppendRevisionPersistsToDiskAndSurvivesReopen() throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "a.txt.d"));
        assertEquals(2, revlog.getRevisionCount());

        byte[] p1Node = NodeIdUtil.fromHex("faa62ea5d798c6624f63d25f2e64f1c107815f20");
        byte[] p2Node = new byte[20];
        byte[] content = "third revision content\n".getBytes(StandardCharsets.UTF_8);
        // linkRev (2, the third changeset) is deliberately different from rev (2, the third
        // filelog revision) is a coincidence here -- use a distinct value (5) to prove linkRev is
        // actually threaded through and not silently synthesized as rev, unlike changelog-v2.
        revlog.appendRevision(content, 1, -1, p1Node, p2Node, 5);

        assertEquals(3, revlog.getRevisionCount());
        assertArrayEquals(content, revlog.getRawRevisionContent(2));
        assertEquals(5, revlog.getIndexRecord(2).getLinkRev(),
                "general v2 must store the real linkRev, not synthesize it from rev like changelog-v2 does");
        assertEquals(1, revlog.getIndexRecord(2).getParent1());

        // 새 Revlog 인스턴스로 다시 열어서 디스크에 실제로 반영됐는지 확인 (in-memory 캐시 우회)
        Revlog reopened = new Revlog(docket, new File(tempDir.toFile(), "a.txt.d"));
        assertEquals(3, reopened.getRevisionCount(), "docket의 index_end/data_end가 갱신되어 재오픈 시에도 3개로 보여야 함");
        assertArrayEquals(content, reopened.getRawRevisionContent(2), "재오픈 후에도 새로 쓴 리비전 내용이 올바르게 복원돼야 함");
        assertEquals(5, reopened.getIndexRecord(2).getLinkRev());
    }

    /**
     * Write-direction gap this file never covered: every test above only ever READS a static,
     * pre-captured general-v2 fixture (or hg4j's own reopen of a file hg4j itself wrote). This
     * closes that gap for the write direction specifically -- hg4j commits into a FRESH,
     * live, general-v2 repository (which auto-implies {@code fileindex-v1}/{@code
     * persistent-nodemap} together, per this session's own empirical probing of {@code
     * hg-rust-7.2.4}, see {@code llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §1-1) and
     * real, Rust-enabled Mercurial verifies/reads it back. Mirrors {@link
     * NodeMapFileWriterTest#realHgRustAcceptsHg4jWrittenNodemap} (persistent-nodemap ALONE), but
     * for the general-v2 combination specifically -- a materially different on-disk code path in
     * {@code Revlog}/{@code RevlogIndex} (96-byte {@code INDEX_ENTRY_V2} records, explicit
     * baseRev/linkRev/parents, fileindex list/meta/tree) exercised nowhere else against a live
     * real-hg oracle. Uses the same one-shot {@code docker run --rm} pattern as that test (a
     * single docker invocation AFTER all hg4j commits finish, not interleaved with them) --
     * deliberately avoiding the interleaved-{@code ProcessBuilder} write-corruption pattern the
     * sibling {@code RequirementMatrixDockerRoundTripTest} had to root-cause and work around via a
     * dedicated subprocess.
     */
    @Test
    @Tag("interop")
    @DisplayName("hg4j가 새로 쓴 general-v2(+fileindex-v1+persistent-nodemap) 커밋을 real Rust hg가 검증/조회한다 (쓰기 방향)")
    void realHgRustAcceptsHg4jWrittenGeneralV2Repository(@TempDir Path liveDir) throws Exception {
        Assumptions.assumeTrue(isDockerImageAvailable(), "hg-rust-7.2.4 Docker image not available -- build it via `docker build -t hg-rust-7.2.4 docker/hg-rust-7.2.4` to run this test");

        File repoDir = liveDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // hg4j never turns general-v2 on by default -- declare it exactly like a real
        // `hg --config experimental.revlogv2=... init` would (that config auto-adds
        // fileindex-v1+persistent-nodemap too, per this session's own probing), matching the
        // `.hg/store/requires` bootstrap pattern already established by
        // NodeMapFileWriterTest#realHgRustAcceptsHg4jWrittenNodemap for the plain
        // persistent-nodemap case.
        File storeDir = new File(repoDir, ".hg/store");
        Files.createDirectories(storeDir.toPath());
        List<String> lines = new ArrayList<>(Files.readAllLines(new File(repoDir, ".hg/requires").toPath()));
        lines.add("exp-revlogv2.2");
        lines.add("fileindex-v1");
        lines.add("persistent-nodemap");
        Files.write(new File(storeDir, "requires").toPath(), lines);
        repo = new HgRepository(repoDir);

        for (int i = 1; i <= 3; i++) {
            Files.writeString(new File(repoDir, "a.txt").toPath(), "line " + i + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("commit " + i).call();
        }

        ProcessBuilder verifyPb = new ProcessBuilder("docker", "run", "--rm", "-v",
                repoDir.getAbsolutePath() + ":/repo", "hg-rust-7.2.4", "hg", "-R", "/repo", "verify");
        Process verifyProc = verifyPb.start();
        String verifyOut = new String(verifyProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                + new String(verifyProc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, verifyProc.waitFor(), "real hg verify failed on hg4j-written general-v2 repo:\n" + verifyOut);

        ProcessBuilder catPb = new ProcessBuilder("docker", "run", "--rm", "-v",
                repoDir.getAbsolutePath() + ":/repo", "hg-rust-7.2.4", "hg", "-R", "/repo", "cat", "-r", "tip", "a.txt");
        Process catProc = catPb.start();
        String catOut = new String(catProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, catProc.waitFor());
        assertEquals("line 1\nline 2\nline 3\n", catOut, "real hg cat must reproduce hg4j's committed content exactly");
    }

    private static boolean isDockerImageAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", "hg-rust-7.2.4").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
