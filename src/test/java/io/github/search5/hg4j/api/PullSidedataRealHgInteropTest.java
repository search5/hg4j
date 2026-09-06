package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgLocalClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog item 26, part 2: verifies that applying a cg5 changegroup actually carries {@code
 * SD_FILES} copy-tracing sidedata (backlog 19) into the destination repository's own {@code .sda}
 * file, readable back via {@link SidedataChangedFilesCommand} -- not merely that {@link
 * ChangegroupParser} can parse {@code entry.sidedata} out of the wire bytes (that part was already
 * covered before this backlog item), but that {@code FetchCommand.applyBundle} (which {@code
 * PullCommand} and every real transport's receive path funnel through) actually persists it.
 *
 * <p>Real hg is involved in the one place hg4j genuinely cannot originate this format itself yet
 * (a documented, separate gap -- see {@link SidedataFilesWriteTest}'s class javadoc): bootstrapping
 * a brand-new {@code exp-copies-sidedata-changeset} repository from nothing. Both the source and
 * destination repository directories here are {@code hg --config
 * format.exp-use-copies-side-data-changeset=yes init}-created (empty) real-hg repositories; every
 * commit is then done entirely by hg4j (matching {@link SidedataFilesWriteTest}'s already-verified
 * pattern), and the changegroup itself is built and applied via hg4j's own production code:
 * <ol>
 * <li>hg4j commits rev0 (add {@code a.txt}) and rev1 (rename {@code a.txt} to {@code b.txt}, add
 * {@code c.txt}) directly into the source repo.</li>
 * <li>{@link HgLocalClient#getBundle} is called with the exact {@code bundleCaps} shape {@code
 * FetchCommand} builds for a real pull (backlog item 26 part 1's negotiation logic) -- since the
 * source repo has {@code exp-copies-sidedata-changeset}, this negotiates version {@code "05"} and
 * the returned bytes carry rev1's sidedata.
 *
 * <p>(A full {@code PullCommand} HTTP round trip between two hg4j endpoints was deliberately NOT
 * used here: {@link io.github.search5.hg4j.transport.HgRemoteClient} auto-upgrades to wireprotocol
 * v2 whenever the server advertises it -- which {@code HgHttpWireServer} always does -- so an
 * hg4j-to-hg4j HTTP pull never actually exercises the v1 {@code getbundle}/cg5 path this backlog
 * item is about; calling {@code getBundle}/{@code applyBundle} directly is what a v1 transport
 * (real hg's own HTTP/SSH client, or hg4j's SSH client) actually does under the hood, without a
 * same-process-only test needing to also stand up a full real socket-based transport.)</li>
 * <li>The returned bytes are decoded exactly like a real transport would ({@link
 * Bundle2Parser#extractChangegroupDetailed} then {@link ChangegroupParser#parseBundle}) and
 * applied via {@code FetchCommand.applyBundle} -- the same method {@code PullCommand} and {@code
 * HgLocalClient#pushWithHooks} both call.</li>
 * <li>Because the destination repo's own {@code requires} already declare {@code
 * exp-changelog-v2}/{@code exp-copies-sidedata-changeset} (from its real-hg bootstrap) before the
 * apply ever touches it, {@code DefaultFileStoreEngine} originates its (so far nonexistent) {@code
 * 00changelog.i} as a v2/changelog-v2 docket the first time {@code applyBundle} calls {@code
 * repository.getRevlog(...)} for it -- so the fix this test exists for ({@code
 * Revlog#appendChangeGroupEntry} routing a v2-format revlog through {@code appendRevisionV2}
 * instead of always writing v1-shaped index records) is exactly what gets exercised.</li>
 * </ol>
 */
@Tag("interop")
public class PullSidedataRealHgInteropTest {

    private static void runHg(File cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("hg");
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        assertEquals(0, exit, "hg " + String.join(" ", args) + " failed:\n" + out);
    }

    @Test
    @DisplayName("real-hg-bootstrapped exp-copies-sidedata-changeset repos: HgLocalClient#getBundle + FetchCommand#applyBundle carry SD_FILES sidedata through cg5 into the destination's own .sda")
    void applyingCg5BundlePersistsSidedataIntoDestinationSda(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        assertTrue(serverRepoDir.mkdirs());
        runHg(serverRepoDir, "--config", "format.exp-use-copies-side-data-changeset=yes", "init", ".");

        HgRepository serverRepo = new HgRepository(serverRepoDir);
        assertTrue(serverRepo.isChangelogV2(), "real hg init with the copies-sidedata format option must produce exp-changelog-v2");
        assertTrue(serverRepo.isSidedataCopies());

        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello world\n");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("add a.txt").setAuthor("test@example.com")
                .setDate(1756857000L, 0).call();

        new CopyCommand(serverRepo).setSource("a.txt").setDestination("b.txt").call();
        new RemoveCommand(serverRepo).setFile("a.txt").setForce(true).call();
        Files.deleteIfExists(new File(serverRepoDir, "a.txt").toPath());
        Files.writeString(new File(serverRepoDir, "c.txt").toPath(), "second file\n");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("rename a to b, add c").setAuthor("test@example.com")
                .setDate(1756857600L, 0).call();

        // Sanity: the source commit's sidedata itself must already be correct (this is backlog
        // 19's own, previously-verified write path -- re-asserted here only so a failure below
        // can be localized to the getBundle/applyBundle side, not the source commit).
        ChangingFiles sourceCf = new SidedataChangedFilesCommand(serverRepo).setRevision(1).call();
        assertEquals(Set.of("b.txt", "c.txt"), sourceCf.getAdded());
        assertEquals(Set.of("a.txt"), sourceCf.getRemoved());
        assertEquals("a.txt", sourceCf.getCopiedFromP1().get("b.txt"));

        // Exactly FetchCommand's own bundlecaps construction for a bundle2-capable remote (see
        // FetchCommand#call) -- backlog item 26 part 1's negotiation then picks version "05"
        // since both this list and the source repo support it.
        List<String> bundleCaps = List.of(
                "HG20",
                Bundle2Parser.buildBundle2CapsToken("01,02,03,04,05"),
                "compression=GZ,BZ,ZS");
        byte[] bundleBytes = new HgLocalClient(serverRepo).getBundle(List.of(), null, bundleCaps);

        assertTrue(bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G'
                        && bundleBytes[2] == '2' && bundleBytes[3] == '0',
                "requesting bundle2 must produce an HG20-framed response");
        Bundle2Parser.ExtractedBundle2 extracted =
                Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
        assertEquals("05", extracted.cgVersion,
                "max(intersection) of both sides' advertised changegroup lists must be 05");

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(extracted.changegroupBytes), extracted.cgVersion);
        boolean anyChangelogEntryCarriesSidedata = bundle.changelogEntries.stream()
                .anyMatch(e -> e.sidedata != null);
        assertTrue(anyChangelogEntryCarriesSidedata,
                "the cg5 bytes getBundle() produced must actually carry rev1's SD_FILES sidedata, "
                        + "not just be version-05-shaped with an empty payload");

        File destRepoDir = tempDir.resolve("dest_repo").toFile();
        assertTrue(destRepoDir.mkdirs());
        runHg(destRepoDir, "--config", "format.exp-use-copies-side-data-changeset=yes", "init", ".");
        HgRepository destRepo = new HgRepository(destRepoDir);
        assertTrue(destRepo.isChangelogV2(), "destination must already be exp-changelog-v2 before the apply -- this test targets the apply-time SIDEDATA WRITE path, not bootstrapping a v2 repo from a pull (a separate, documented gap)");

        new FetchCommand(destRepo).applyBundle(bundle);

        destRepo.clearRevlogCache();
        ChangingFiles pulledCf = new SidedataChangedFilesCommand(destRepo).setRevision(1).call();
        assertEquals(Set.of("b.txt", "c.txt"), pulledCf.getAdded(),
                "applied repo's rev1 SD_FILES 'added' set must match the source -- proves entry.sidedata from the cg5 changegroup was actually persisted, not dropped");
        assertEquals(Set.of("a.txt"), pulledCf.getRemoved());
        assertEquals("a.txt", pulledCf.getCopiedFromP1().get("b.txt"),
                "the copy-tracing relationship itself (b.txt copied from a.txt) must survive the apply -- this is the actual payload backlog 19 exists to carry");
        assertTrue(pulledCf.getCopiedFromP2().isEmpty());

        // Real hg must independently agree that the applied repository's on-disk bytes are
        // spec-correct, exactly like SidedataFilesWriteTest does for the local-commit path --
        // confirms this is genuinely real-hg-readable sidedata, not just self-consistent with
        // hg4j's own reader.
        Process p = new ProcessBuilder("hg", "debugchangedfiles", "1").directory(destRepoDir)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "hg debugchangedfiles 1 failed on the applied repo:\n" + out);
        assertTrue(out.contains("removed") && out.contains("a.txt"), out);
        assertTrue(out.contains("added") && out.contains("b.txt") && out.contains("c.txt"), out);

        Process verifyP = new ProcessBuilder("hg", "verify").directory(destRepoDir).redirectErrorStream(true).start();
        String verifyOut = new String(verifyP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, verifyP.waitFor(), "hg verify failed on the applied repo:\n" + verifyOut);
    }
}
