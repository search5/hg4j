package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end (repository-level) test for {@link SidedataChangedFilesCommand} against a real
 * {@code hg}-generated changelog-v2 + copies-sidedata fixture (see {@code
 * src/test/resources/fixtures/sidedata-copytracing/README.md}). Every expectation here was
 * verified against the same source repository's real {@code hg debugchangedfiles &lt;rev&gt;}
 * output.
 */
@DisplayName("SidedataChangedFilesCommand — end-to-end against a real hg-generated repository")
public class SidedataChangedFilesCommandTest {

    @TempDir
    Path tempDir;

    private void copyFixture(String resourceName, File target) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sidedata-copytracing/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HgRepository setupRealRepository() throws IOException {
        File repoDir = tempDir.toFile();
        File storeDir = new File(repoDir, ".hg/store");
        assertTrue(storeDir.mkdirs());

        copyFixture("docket.i", new File(storeDir, "00changelog.i"));
        copyFixture("data.idx", new File(storeDir, "00changelog-53306201.idx"));
        copyFixture("data.dat", new File(storeDir, "00changelog-eb558592.dat"));
        copyFixture("data.sda", new File(storeDir, "00changelog-2ddfce29.sda"));

        Files.writeString(new File(storeDir, "requires").toPath(),
                "dotencode\nexp-changelog-v2\nexp-copies-sidedata-changeset\nfncache\ngeneraldelta\n"
                        + "revlog-compression-zstd\nrevlogv1\nsparserevlog\nstore\n");
        Files.writeString(new File(repoDir, ".hg/requires").toPath(), "share-safe\n");

        return new HgRepository(repoDir);
    }

    @Test
    @DisplayName("rev1: b.txt는 a.txt로부터 p1 copy로 기록됨 — 실제 `hg debugchangedfiles 1` 출력(\"added p1: b.txt, a.txt;\")과 일치")
    void reportsRenameAsCopyFromP1() throws IOException {
        HgRepository repo = setupRealRepository();

        ChangingFiles files = new SidedataChangedFilesCommand(repo).setRevision(1).call();

        assertEquals("a.txt", files.getCopySource("b.txt"));
        assertTrue(files.getRemoved().contains("a.txt"));
        assertTrue(files.getAdded().contains("b.txt"));
    }

    @Test
    @DisplayName("rev2: d.txt는 b.txt로부터 p1 copy로 기록됨 — 실제 `hg debugchangedfiles 2` 출력(\"added p1: d.txt, b.txt;\")과 일치, c.txt는 copy 아님")
    void reportsExplicitCopyFromP1() throws IOException {
        HgRepository repo = setupRealRepository();

        ChangingFiles files = new SidedataChangedFilesCommand(repo).setRevision(2).call();

        assertEquals("b.txt", files.getCopySource("d.txt"));
        assertNull(files.getCopySource("c.txt"), "c.txt was freshly added, not copied");
        assertTrue(files.getAdded().containsAll(java.util.Set.of("c.txt", "d.txt")));
    }

    @Test
    @DisplayName("rev0: 첫 커밋이라 copy가 전혀 없음")
    void firstRevisionHasNoCopies() throws IOException {
        HgRepository repo = setupRealRepository();

        ChangingFiles files = new SidedataChangedFilesCommand(repo).setRevision(0).call();

        assertTrue(files.getCopiedFromP1().isEmpty());
        assertTrue(files.getCopiedFromP2().isEmpty());
        assertEquals(java.util.Set.of("a.txt"), files.getAdded());
    }
}
