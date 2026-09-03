package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for treemanifest <em>write</em> support (backlog 18 in
 * {@code mercurial-spec-compliance-requirement.md} -- read/recursive-expansion was already
 * implemented for backlog 8; this covers the previously-unimplemented commit-time split into
 * per-directory {@code meta/<dir>/00manifest.i} revisions).
 *
 * <p>Verification: build a brand-new {@code treemanifest} repository entirely through hg4j's own
 * {@link CommitCommand} (nested files across several directories, then a second commit touching
 * only some of them to exercise parent-linkage across an update), confirm hg4j's own {@link
 * io.github.search5.hg4j.treewalk.ManifestTreeIterator} read path round-trips it correctly, that
 * the expected {@code meta/<dir>/00manifest.i} files actually exist, and -- the real proof --
 * hand the repository to a real Mercurial ({@code hg-rust-7.2.4}, built from
 * {@code docker/hg-rust-7.2.4/Dockerfile}; treemanifest itself needs no Rust extension, this
 * image is simply already available and is a strict superset of a plain build) and confirm
 * {@code hg verify}/{@code hg log}/{@code hg cat} all succeed against hg4j's own output.
 */
@DisplayName("CommitCommand treemanifest write path (backlog 18)")
class TreeManifestWriteTest {

    @TempDir
    Path tempDir;

    private HgRepository initTreemanifestRepo(File repoDir) throws IOException {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        // Unlike persistent-nodemap (a store/data-format flag), real hg keeps `treemanifest` in
        // the TOP-LEVEL .hg/requires, not .hg/store/requires -- confirmed against the real
        // Rust-Mercurial-produced fixture at src/test/resources/fixtures/treemanifest/hg/requires
        // (only a top-level requires file exists there, no separate store/requires at all; a
        // real hg-rust-7.2.4 `hg verify` against a repo with it placed in store/requires instead
        // fails with `assert self._treeondisk` in mercurial/manifest.py's dirlog(), confirming
        // real hg's requirement-set union genuinely distinguishes the two files for this flag).
        File requiresFile = new File(repoDir, ".hg/requires");
        List<String> baseLines = Files.readAllLines(requiresFile.toPath());

        // real hg's share-safe format (this repo declares `store` in .hg/requires) expects
        // .hg/store/requires to exist too -- hg4j's own Hg.init() does not create it yet (a
        // separate pre-existing gap, not this task's scope); mirror the base set there so real hg
        // has a consistent baseline, same workaround used by NodeMapFileWriterTest.
        File storeDir = new File(repoDir, ".hg/store");
        Files.createDirectories(storeDir.toPath());
        Files.write(new File(storeDir, "requires").toPath(), baseLines);

        List<String> lines = new ArrayList<>(baseLines);
        lines.add("treemanifest");
        Files.write(requiresFile.toPath(), lines);
        return new HgRepository(repoDir); // re-open so isTreemanifest() reflects the just-written requires
    }

    private void write(File repoDir, String relPath, String content) throws IOException {
        File f = new File(repoDir, relPath);
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Test
    @DisplayName("중첩 디렉터리 여러 개를 커밋하면 meta/<dir>/00manifest.i가 생기고, 자체 읽기 경로로 왕복 정확하다")
    void nestedDirectoriesProduceSubmanifestsAndRoundTrip() throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = initTreemanifestRepo(repoDir);
        assertTrue(repo.isTreemanifest());

        write(repoDir, "a.txt", "root file\n");
        write(repoDir, "sub/b.txt", "sub file\n");
        write(repoDir, "sub/deep/c.txt", "deep file\n");
        write(repoDir, "sub2/d.txt", "sub2 file\n");
        new AddCommand(repo).call();
        byte[] commit1 = new CommitCommand(repo).setMessage("initial nested tree").call();

        assertTrue(new File(repoDir, ".hg/store/meta/sub/00manifest.i").isFile());
        assertTrue(new File(repoDir, ".hg/store/meta/sub/deep/00manifest.i").isFile());
        assertTrue(new File(repoDir, ".hg/store/meta/sub2/00manifest.i").isFile());

        Map<String, String> mf = repo.getManifestAtCommit(commit1);
        assertEquals(4, mf.size());
        assertTrue(mf.containsKey("a.txt"));
        assertTrue(mf.containsKey("sub/b.txt"));
        assertTrue(mf.containsKey("sub/deep/c.txt"));
        assertTrue(mf.containsKey("sub2/d.txt"));

        // Second commit: touch one deeply-nested file and add a new root file, leave "sub2"
        // completely untouched -- exercises that unmodified-subtree parent-linkage (subp1 lookup
        // via collectDirNodes) still resolves correctly across an update, not just a fresh tree.
        write(repoDir, "sub/deep/c.txt", "deep file v2\n");
        write(repoDir, "e.txt", "second root file\n");
        new AddCommand(repo).call();
        byte[] commit2 = new CommitCommand(repo).setMessage("touch deep + add root").call();

        Map<String, String> mf2 = repo.getManifestAtCommit(commit2);
        assertEquals(5, mf2.size());
        assertTrue(mf2.containsKey("e.txt"));
        assertNotEquals(mf.get("sub/deep/c.txt"), mf2.get("sub/deep/c.txt"), "c.txt content changed, its filelog node must differ");
        assertEquals(mf.get("sub2/d.txt"), mf2.get("sub2/d.txt"), "untouched sub2/d.txt's filelog node must be unchanged");
    }

    @Test
    @DisplayName("실제 hg-rust-7.2.4가 hg4j로 커밋된 treemanifest 저장소를 verify/log/cat 성공한다")
    void realHgAcceptsHg4jWrittenTreemanifest() throws Exception {
        Assumptions.assumeTrue(isDockerImageAvailable(), "hg-rust-7.2.4 Docker image not available -- build it via `docker build -t hg-rust-7.2.4 docker/hg-rust-7.2.4` to run this test");

        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = initTreemanifestRepo(repoDir);

        write(repoDir, "a.txt", "root file\n");
        write(repoDir, "sub/b.txt", "sub file\n");
        write(repoDir, "sub/deep/c.txt", "deep file\n");
        write(repoDir, "sub2/d.txt", "sub2 file\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial nested tree").call();

        write(repoDir, "sub/deep/c.txt", "deep file v2\n");
        write(repoDir, "e.txt", "second root file\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("touch deep + add root").call();

        ProcessBuilder verifyPb = new ProcessBuilder("docker", "run", "--rm", "-v", repoDir.getAbsolutePath() + ":/repo",
                "hg-rust-7.2.4", "hg", "-R", "/repo", "verify");
        Process verifyProc = verifyPb.start();
        String verifyOut = readAll(verifyProc.getInputStream()) + readAll(verifyProc.getErrorStream());
        assertEquals(0, verifyProc.waitFor(), "real hg verify failed on hg4j-written treemanifest repo:\n" + verifyOut);

        ProcessBuilder logPb = new ProcessBuilder("docker", "run", "--rm", "-v", repoDir.getAbsolutePath() + ":/repo",
                "hg-rust-7.2.4", "hg", "-R", "/repo", "log", "--template", "{rev}:{node|short}\\n");
        Process logProc = logPb.start();
        String logOut = readAll(logProc.getInputStream());
        assertEquals(0, logProc.waitFor(), "real hg log failed:\n" + readAll(logProc.getErrorStream()));
        assertEquals(2, logOut.strip().split("\n").length, "real hg should see both commits:\n" + logOut);

        // `hg cat` on a file living inside the deepest nested directory forces real hg to walk
        // the tree manifest all the way down through both `meta/sub/00manifest.i` and
        // `meta/sub/deep/00manifest.i` -- the strongest available interop signal that hg4j wrote
        // structurally correct, real-hg-readable per-directory revisions, not just a root
        // manifest that happens to parse.
        ProcessBuilder catPb = new ProcessBuilder("docker", "run", "--rm", "-v", repoDir.getAbsolutePath() + ":/repo",
                "hg-rust-7.2.4", "hg", "-R", "/repo", "cat", "-r", "0", "sub/deep/c.txt");
        Process catProc = catPb.start();
        String catOut = readAll(catProc.getInputStream());
        assertEquals(0, catProc.waitFor(), "real hg cat failed:\n" + readAll(catProc.getErrorStream()));
        assertEquals("deep file\n", catOut);

        ProcessBuilder catPb2 = new ProcessBuilder("docker", "run", "--rm", "-v", repoDir.getAbsolutePath() + ":/repo",
                "hg-rust-7.2.4", "hg", "-R", "/repo", "cat", "-r", "1", "sub2/d.txt");
        Process catProc2 = catPb2.start();
        String catOut2 = readAll(catProc2.getInputStream());
        assertEquals(0, catProc2.waitFor(), "real hg cat (untouched subtree) failed:\n" + readAll(catProc2.getErrorStream()));
        assertEquals("sub2 file\n", catOut2, "real hg must still resolve an untouched-this-commit subdirectory file correctly");
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
