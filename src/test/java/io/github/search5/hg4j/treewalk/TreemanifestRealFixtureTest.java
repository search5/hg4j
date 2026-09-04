package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recursive treemanifest ({@code experimental.treemanifest=1}) flattening, verified against a
 * real repository built with Docker Mercurial 6.0 (pure Python, no Rust extensions — treemanifest
 * needs none). See src/test/resources/fixtures/treemanifest/README.md for the exact commands,
 * node hashes, and raw manifest bytes (obtained via {@code hg debugdata}/{@code hg debugindex
 * --debug}) this test is built from.
 *
 * <p>The fixture repo has 3 commits with a 2-level-deep nested directory structure:
 * <pre>
 * rev0: a.txt, sub/b.txt, sub/deep/c.txt
 * rev1: a.txt (modified), sub/b.txt (modified)
 * rev2: + sub2/d.txt
 * </pre>
 * with separate {@code meta/&lt;dir&gt;/00manifest.i} sub-manifest revlogs for {@code sub/},
 * {@code sub/deep/}, and {@code sub2/} — exactly the layout {@link ManifestTreeIterator} must
 * recursively expand via its {@code t} (subdirectory-pointer) flag handling.
 */
@DisplayName("treemanifest 재귀적 펼침 — 실제 Docker Mercurial 6.0 픽스처로 검증")
public class TreemanifestRealFixtureTest {

    private static final String REV0_CHANGESET = "9cd0fbb1eaf3a47cdee68e508a7d1b3fb524f452";
    private static final String REV1_CHANGESET = "26072b563966a6daa8e78160db4f4bc05bd0d0fa";
    private static final String REV2_CHANGESET = "d09f2bbaed180fe6992489d2b2c11363a34e3ccf";

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws IOException, URISyntaxException {
        tempRepoDir = Files.createTempDirectory("hg4j_treemanifest_fixture_").toFile();
        copyFixtureHgDir(new File(tempRepoDir, ".hg").toPath());
        repository = new HgRepository(tempRepoDir);
    }

    @AfterEach
    public void tearDown() throws Exception {
        repository.close();
        deleteRecursively(tempRepoDir);
    }

    private void copyFixtureHgDir(Path destHgDir) throws IOException, URISyntaxException {
        URL resourceUrl = getClass().getResource("/fixtures/treemanifest/hg");
        assertTrue(resourceUrl != null, "treemanifest fixture resource missing: /fixtures/treemanifest/hg");
        Path sourceDir = new File(resourceUrl.toURI()).toPath();
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destHgDir.resolve(sourceDir.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destHgDir.resolve(sourceDir.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private Map<String, String> expectedManifest(int rev) {
        Map<String, String> expected = new HashMap<>();
        // path -> file node hex (all plain 644 files, no flag suffix in getManifestAtCommit's
        // hex+flag encoding since flag is "" for all of them)
        if (rev == 0) {
            expected.put("a.txt", "2c186c8c5bc0df5af5b951afe407d803f9e6b8c9");
            expected.put("sub/b.txt", "cc68520d565d6565e36765b4ff03f05c5f57d080");
            expected.put("sub/deep/c.txt", "955d535bc0132492b4e999bd8024f776a8242b25");
        } else if (rev == 1) {
            expected.put("a.txt", "c093b423870d8b2114889160cb1dee55fd1cca9b");
            expected.put("sub/b.txt", "f15e686805cc5feaa9c87472965c59681c96df0c");
            expected.put("sub/deep/c.txt", "955d535bc0132492b4e999bd8024f776a8242b25");
        } else if (rev == 2) {
            expected.put("a.txt", "c093b423870d8b2114889160cb1dee55fd1cca9b");
            expected.put("sub/b.txt", "f15e686805cc5feaa9c87472965c59681c96df0c");
            expected.put("sub/deep/c.txt", "955d535bc0132492b4e999bd8024f776a8242b25");
            expected.put("sub2/d.txt", "f55729ef0544575a598e7a746756782f6dcf3a3a");
        } else {
            throw new IllegalArgumentException("no fixture data for rev " + rev);
        }
        return expected;
    }

    @Test
    @DisplayName("getManifestAtCommit()이 각 커밋마다 t-플래그 서브트리를 재귀적으로 펼쳐 flat map을 만든다")
    public void testGetManifestAtCommitFlattensNestedTreemanifest() throws IOException {
        String[] changesets = {REV0_CHANGESET, REV1_CHANGESET, REV2_CHANGESET};
        for (int rev = 0; rev <= 2; rev++) {
            byte[] commitNode = NodeIdUtil.fromHex(changesets[rev]);
            Map<String, String> manifest = repository.getManifestAtCommit(commitNode);

            Map<String, String> expected = expectedManifest(rev);
            assertEquals(expected.size(), manifest.size(), "rev" + rev + ": entry count mismatch, got " + manifest.keySet());
            for (Map.Entry<String, String> e : expected.entrySet()) {
                assertEquals(e.getValue(), manifest.get(e.getKey()),
                        "rev" + rev + ": node id mismatch for " + e.getKey());
            }
            // No directory-pointer entries ("sub", "sub2" without a trailing file name) must leak
            // into the flattened result.
            assertTrue(!manifest.containsKey("sub"), "rev" + rev + ": bare directory pointer 'sub' must not appear");
            assertTrue(!manifest.containsKey("sub2"), "rev" + rev + ": bare directory pointer 'sub2' must not appear");
            assertTrue(!manifest.containsKey("sub/deep"), "rev" + rev + ": bare directory pointer 'sub/deep' must not appear");
        }
    }

    @Test
    @DisplayName("ManifestWalk이 2단계 중첩 디렉터리를 완전한 상대경로로 펼쳐 순회한다")
    public void testManifestWalkExpandsNestedPaths() throws IOException {
        ManifestWalk mw = new ManifestWalk(repository, String.valueOf(2));
        List<ManifestWalk.Entry> entries = mw.getEntries();

        Map<String, String> byPath = new HashMap<>();
        for (ManifestWalk.Entry e : entries) {
            byPath.put(e.getPath(), e.getNodeIdHex());
        }
        assertEquals(expectedManifest(2), byPath);
    }

    @Test
    @DisplayName("LogCommand으로 각 커밋의 changeset을 조회할 수 있다 (매니페스트 파싱이 커밋 히스토리 탐색을 막지 않음을 확인)")
    public void testLogCommandSeesAllThreeCommitsOnTreemanifestRepo() throws Exception {
        List<HgCommit> revisions = new LogCommand(repository).call();
        assertEquals(3, revisions.size(), "3 commits expected in the treemanifest fixture");
    }

    /**
     * Write-direction gap this fixture-only file never covered: {@code
     * RequirementMatrixCoreRoundTripTest}'s own treemanifest write-direction case only commits a
     * single root-level file, so it never actually exercises hg4j's {@code CommitCommand} writing
     * a NEW nested sub-manifest revlog from scratch (as opposed to this class's other tests, which
     * only ever read a manifest tree real hg already wrote). This closes that gap: hg4j writes a
     * commit with a 2-level-deep nested directory structure (mirroring the fixture's own layout)
     * into a fresh, live, real-hg-initialized treemanifest repo (no Docker needed -- {@code
     * experimental.treemanifest=1} needs no Rust extension, confirmed elsewhere in this session),
     * and real hg's own CLI must accept it via {@code verify}/{@code cat} on the nested paths.
     */
    @Test
    @Tag("interop")
    @DisplayName("hg4j가 새로 쓴 중첩 treemanifest 커밋을 실제 hg CLI가 인식한다 (쓰기 방향, 이 파일이 커버 안 하던 gap)")
    public void hg4jWritesNestedTreemanifestCommitAndRealHgAcceptsIt(@TempDir Path liveDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        File repoDir = liveDir.resolve("live-repo").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init", "--config", "experimental.treemanifest=1");

        HgRepository liveRepo = new HgRepository(repoDir);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "root");
        Files.createDirectories(repoDir.toPath().resolve("sub/deep"));
        Files.writeString(repoDir.toPath().resolve("sub/b.txt"), "one level deep");
        Files.writeString(repoDir.toPath().resolve("sub/deep/c.txt"), "two levels deep");
        Files.createDirectories(repoDir.toPath().resolve("sub2"));
        Files.writeString(repoDir.toPath().resolve("sub2/d.txt"), "sibling subtree");
        new AddCommand(liveRepo).call();
        byte[] node = new CommitCommand(liveRepo).setAuthor("hg4j").setMessage("nested treemanifest write").call();
        String hg4jHex = NodeIdUtil.toHex(node);

        String realTipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(hg4jHex, realTipHex, "real hg's tip must be the hg4j-written nested-treemanifest commit");

        assertEquals("root", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "a.txt"));
        assertEquals("one level deep", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub/b.txt"));
        assertEquals("two levels deep", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub/deep/c.txt"));
        assertEquals("sibling subtree", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub2/d.txt"));

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors on hg4j's nested treemanifest write: " + verify);
    }
}
