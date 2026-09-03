package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for the {@link PhaseCommand} porcelain wrapper itself, complementing
 * the PhaseRoots storage-layer tests and the roundtrip case in PorcelainExtraCommandsTest.
 */
public class PhaseCommandCoverageTest {

    private HgRepository initRepoWithCommit(File repoDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").setAuthor("dev").call();
        return repo;
    }

    @Test
    public void testCall_unresolvableRevision_throwsIOException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = initRepoWithCommit(tempDir.toFile());

        PhaseCommand cmd = new PhaseCommand(repo).setRevision("ffffffffffff");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Revision not found in repository"));
    }

    @Test
    public void testSetPhase_addsNewRootWhilePreservingUnrelatedExistingEntry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, "tip");
        String targetHex = NodeIdUtil.toHex(nodeBytes);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        String unrelatedHex = "0".repeat(40);
        Files.write(phaseRootsFile.toPath(), List.of("1 " + unrelatedHex), StandardCharsets.UTF_8);

        int result = new PhaseCommand(repo).setRevision("tip").setPhase(2).call();
        assertEquals(2, result);

        List<String> lines = Files.readAllLines(phaseRootsFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.contains("1 " + unrelatedHex), "Unrelated existing entry must be preserved untouched");
        assertTrue(lines.contains("2 " + targetHex), "New forced-phase entry must be appended for a revision not yet in phaseroots");
    }

    @Test
    public void testCall_query_defaultsToPublicWhenTargetAbsentFromPhaseRoots(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        String unrelatedHex = "0".repeat(40);
        Files.write(phaseRootsFile.toPath(), List.of("1 " + unrelatedHex), StandardCharsets.UTF_8);

        int ph = new PhaseCommand(repo).setRevision("tip").call();
        assertEquals(0, ph, "A revision absent from phaseroots must default to public (0)");
    }

    @Test
    public void testCall_query_returnsStoredNonPublicPhaseWhenFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, "tip");
        String targetHex = NodeIdUtil.toHex(nodeBytes);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        Files.write(phaseRootsFile.toPath(), List.of("2 " + targetHex), StandardCharsets.UTF_8);

        int ph = new PhaseCommand(repo).setRevision("tip").call();
        assertEquals(2, ph, "A revision registered as secret in phaseroots must be reported as secret (2)");
    }

    @Test
    public void testCall_query_whenPhaseRootsFileAbsent_defaultsToPublic(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        Files.deleteIfExists(phaseRootsFile.toPath());
        assertFalse(phaseRootsFile.exists());

        int ph = new PhaseCommand(repo).setRevision("tip").call();
        assertEquals(0, ph, "A missing phaseroots file must be treated as empty and default to public (0)");
    }

    @Test
    public void testSetPhase_skipsBlankAndMalformedLinesInPhaseRoots(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, "tip");
        String targetHex = NodeIdUtil.toHex(nodeBytes);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        String unrelatedHex = "0".repeat(40);
        Files.write(phaseRootsFile.toPath(), List.of("", "malformed-single-token", "1 " + unrelatedHex), StandardCharsets.UTF_8);

        int result = new PhaseCommand(repo).setRevision("tip").setPhase(1).call();
        assertEquals(1, result);

        List<String> lines = Files.readAllLines(phaseRootsFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "Blank line skipped and malformed single-token line dropped, unrelated entry kept, new entry appended");
        assertTrue(lines.contains("1 " + unrelatedHex));
        assertTrue(lines.contains("1 " + targetHex));
    }

    @Test
    public void testCall_query_skipsBlankAndMalformedLinesInPhaseRoots(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithCommit(repoDir);

        File phaseRootsFile = new File(repo.getStoreDir(), "phaseroots");
        String unrelatedHex = "0".repeat(40);
        Files.write(phaseRootsFile.toPath(), List.of("", "malformed-single-token", "1 " + unrelatedHex), StandardCharsets.UTF_8);

        int ph = new PhaseCommand(repo).setRevision("tip").call();
        assertEquals(0, ph, "Blank/malformed lines must be skipped without matching, falling through to the public default");
    }
}
