package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for bootstrapping a brand-new {@code exp-changelog-v2} repository from nothing (adjacent
 * gap discovered while implementing backlog item 19 — {@code mercurial-spec-compliance-requirement.md}
 * — hg4j could only ever <em>recognize</em> an already-existing changelog-v2 docket by its magic
 * byte, never originate one via {@code Hg.init()} + a first commit, unlike {@code
 * exp-revlogv2.2} which {@code DefaultFileStoreEngine}/{@code RevlogIndex} already knew how to
 * create from scratch).
 *
 * <p>{@code RevlogIndex.initializeNewV2Docket(true)} now bootstraps a CHANGELOGV2-magic docket
 * (byte-for-byte the same empty-docket shape as the already-verified general-v2 bootstrap, just a
 * different magic value and {@code isChangelogV2()}=true), and {@code DefaultFileStoreEngine}
 * requests it specifically for {@code 00changelog.i} when {@code repository.isChangelogV2()} is
 * set and the file doesn't exist yet.
 */
@DisplayName("RevlogIndex/DefaultFileStoreEngine — exp-changelog-v2 bootstrap from scratch (adjacent gap)")
class ChangelogV2BootstrapTest {

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

    private HgRepository initChangelogV2Repo(File repoDir) throws IOException {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = new File(repoDir, ".hg/store");
        Files.createDirectories(storeDir.toPath());
        List<String> lines = new ArrayList<>(Files.readAllLines(new File(repoDir, ".hg/requires").toPath()));
        lines.add("exp-changelog-v2");
        Files.write(new File(storeDir, "requires").toPath(), lines);
        return new HgRepository(repoDir);
    }

    @Test
    @DisplayName("hg4j가 처음부터 exp-changelog-v2 저장소를 만들고 자체 read 경로로 왕복된다")
    void bootstrapsFreshChangelogV2RepositoryAndRoundTrips() throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = initChangelogV2Repo(repoDir);
        assertTrue(repo.isChangelogV2());

        File clIdx = new File(repoDir, ".hg/store/00changelog.i");
        assertFalse(clIdx.exists(), "precondition: no changelog written yet");

        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello\n");
        new AddCommand(repo).call();
        byte[] rev0 = new CommitCommand(repo).setMessage("first commit").setDate(1756857600L, 0).call();

        assertTrue(clIdx.isFile(), "bootstrapping the docket must have created 00changelog.i");
        Revlog changelog = repo.getRevlog(clIdx, new File(repoDir, ".hg/store/00changelog.d"));
        assertTrue(changelog.getIndex().isV2());
        assertTrue(changelog.getIndex().isChangelogV2());
        assertEquals(1, changelog.getRevisionCount());
        assertArrayEquals(rev0, java.util.Arrays.copyOf(changelog.getIndexRecord(0).getNodeId(), rev0.length));

        // Second commit -- proves appendRevisionV2 correctly extends a docket hg4j itself
        // bootstrapped (parent linkage, docket index_end/data_end bookkeeping), not just an
        // already-real-hg-created one.
        Files.writeString(new File(repoDir, "b.txt").toPath(), "world\n");
        new AddCommand(repo).call();
        byte[] rev1 = new CommitCommand(repo).setMessage("second commit").setDate(1756857700L, 0).call();
        Revlog changelog2 = repo.getRevlog(clIdx, new File(repoDir, ".hg/store/00changelog.d"));
        assertEquals(2, changelog2.getRevisionCount());
        assertArrayEquals(rev1, java.util.Arrays.copyOf(changelog2.getIndexRecord(1).getNodeId(), rev1.length));
    }

    @Test
    @DisplayName("실제 hg가 hg4j로 처음부터 만든 exp-changelog-v2 저장소를 verify/log 성공한다")
    void realHgAcceptsHg4jBootstrappedChangelogV2Repository() throws Exception {
        Assumptions.assumeTrue(isLocalHgAvailable(), "local hg CLI not available");

        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = initChangelogV2Repo(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first commit").setDate(1756857600L, 0).call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "world\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("second commit").setDate(1756857700L, 0).call();

        Process verifyP = new ProcessBuilder("hg", "verify").directory(repoDir).redirectErrorStream(true).start();
        String verifyOut = new String(verifyP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, verifyP.waitFor(), "hg verify failed on hg4j-bootstrapped changelog-v2 repo:\n" + verifyOut);

        Process logP = new ProcessBuilder("hg", "log", "--template", "{rev}:{node|short} {desc}\\n")
                .directory(repoDir).redirectErrorStream(true).start();
        String logOut = new String(logP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, logP.waitFor(), "hg log failed:\n" + logOut);
        String[] lines = logOut.strip().split("\n");
        assertEquals(2, lines.length, "real hg should see both commits:\n" + logOut);
        assertTrue(logOut.contains("second commit"));
        assertTrue(logOut.contains("first commit"));
    }
}
