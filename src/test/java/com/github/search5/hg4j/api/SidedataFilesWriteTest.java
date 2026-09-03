package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for {@code SD_FILES} sidedata <em>write</em> support (backlog 19 in
 * {@code mercurial-spec-compliance-requirement.md} -- decode/read was already implemented for
 * backlog 17; this covers the previously-missing commit-time encode/attach path, via {@link
 * ChangingFiles#encode}/{@link com.github.search5.hg4j.storage.SidedataCodec#serialize} wired
 * into {@link CommitCommand} and {@link com.github.search5.hg4j.storage.Revlog}'s new {@code
 * sidedataContainer} append parameter).
 *
 * <p><b>Newly-discovered adjacent gap (documented, out of this task's scope):</b> hg4j has no
 * path to <em>bootstrap</em> a brand-new {@code exp-changelog-v2} repository from nothing --
 * {@code RevlogIndex} only ever recognizes changelog-v2 by reading the {@code CHANGELOGV2} magic
 * byte off an <em>already-existing</em> docket file (real hg-created or otherwise), never
 * creates one when the file is absent (unlike {@code exp-revlogv2.2}, which {@code
 * DefaultFileStoreEngine} does know how to originate from scratch). This test therefore uses the
 * real local {@code hg} CLI (Mercurial 7.2.2, confirmed Rust-free -- see backlog 17's own note)
 * to create rev0 of a real {@code exp-copies-sidedata-changeset} repository, then has hg4j append
 * rev1 on top of that already-v2 changelog -- exactly the scenario backlog 19 is actually about
 * (an existing changelog-v2 repository gaining copy-tracing sidedata on hg4j-authored commits),
 * just not a repository hg4j originated itself.
 */
@DisplayName("CommitCommand SD_FILES sidedata write path (backlog 19)")
class SidedataFilesWriteTest {

    @TempDir
    Path tempDir;

    private static boolean isLocalHgAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void runHg(File cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("hg");
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        assertEquals(0, exit, "hg " + String.join(" ", args) + " failed:\n" + out);
    }

    @Test
    @DisplayName("hg4j가 실제 changelog-v2+sidedata 저장소에 커밋 시 SD_FILES를 정확히 쓰고, 자체 read 경로로 왕복된다")
    void writesSidFilesReadableByOwnDecodePath() throws Exception {
        Assumptions.assumeTrue(isLocalHgAvailable(), "local hg CLI not available -- required to bootstrap an exp-changelog-v2 repository (hg4j itself cannot yet, see class javadoc)");

        File repoDir = tempDir.resolve("repo").toFile();
        assertTrue(repoDir.mkdirs());
        runHg(repoDir, "--config", "format.exp-use-copies-side-data-changeset=yes", "init", ".");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello world\n");
        runHg(repoDir, "add", "a.txt");
        runHg(repoDir, "commit", "-u", "test@example.com", "-m", "add a.txt",
                "-d", "2026-09-03 00:00:00 +0000");

        HgRepository repo = new HgRepository(repoDir);
        assertTrue(repo.isChangelogV2(), "real hg init with the copies-sidedata format option must produce exp-changelog-v2");
        assertTrue(repo.isSidedataCopies());

        // rev1, entirely written by hg4j: rename a.txt -> b.txt (remove + copied-from-p1 add),
        // add a brand-new untouched file c.txt.
        new CopyCommand(repo).setSource("a.txt").setDestination("b.txt").call();
        new RemoveCommand(repo).setFile("a.txt").setForce(true).call();
        Files.deleteIfExists(new File(repoDir, "a.txt").toPath());
        Files.writeString(new File(repoDir, "c.txt").toPath(), "second file\n");
        new AddCommand(repo).call();
        byte[] rev1 = new CommitCommand(repo).setMessage("rename a to b, add c")
                .setDate(1756857600L, 0).call();

        ChangingFiles cf = new SidedataChangedFilesCommand(repo).setRevision(1).call();
        assertEquals(java.util.Set.of("b.txt", "c.txt"), cf.getAdded());
        assertEquals(java.util.Set.of("a.txt"), cf.getRemoved());
        assertEquals("a.txt", cf.getCopiedFromP1().get("b.txt"));
        assertTrue(cf.getCopiedFromP2().isEmpty());

        // Real hg must independently agree -- confirms hg4j's on-disk bytes (index record
        // sidedata offset/complen/compression-mode fields + the .sda container + the SD_FILES
        // payload itself) are genuinely spec-correct, not just self-consistent with hg4j's own
        // reader.
        Process p = new ProcessBuilder("hg", "debugchangedfiles", "1").directory(repoDir)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "hg debugchangedfiles 1 failed:\n" + out);
        assertTrue(out.contains("removed"), out);
        assertTrue(out.contains("a.txt"), out);
        assertTrue(out.contains("added"), out);
        assertTrue(out.contains("b.txt"), out);
        assertTrue(out.contains("c.txt"), out);

        Process verifyP = new ProcessBuilder("hg", "verify").directory(repoDir).redirectErrorStream(true).start();
        String verifyOut = new String(verifyP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, verifyP.waitFor(), "hg verify failed on hg4j-authored commit:\n" + verifyOut);
    }
}
