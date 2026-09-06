package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Backlog #29 ("requires 파일 세부 문자열 커버리지 재검증"). {@link Hg#open(File)} has its own,
 * SEPARATE requirement-string allowlist (a {@code SUPPORTED} set literal) from
 * {@link HgRepository}'s {@code loadRequires()}/{@code readRequiresFile()} -- the two were never
 * kept in sync. This test proves (2026-09-04) that {@code Hg.open()}'s allowlist was stale enough
 * to reject the vast majority of real hg-created repositories outright:
 * <ul>
 *   <li>{@code "revlog-compression-zstd"} (the exact string real hg 7.2 writes for its DEFAULT
 *   compression engine, confirmed via a bare {@code hg init} with no special config) was listed
 *   as just {@code "revlog-compression"} (missing the {@code -zstd} suffix) -- an exact-match
 *   allowlist check against the wrong string.</li>
 *   <li>{@code "sparserevlog"} (present in EVERY real hg 7.2 repo's {@code store/requires} by
 *   default) was entirely absent from the allowlist.</li>
 *   <li>{@code "narrowspec"} was listed, but the real requirement string
 *   {@link NarrowCloneCommand} itself writes is {@code "narrowhg-experimental"} -- {@code
 *   "narrowspec"} is actually the on-disk *filename* of the narrowspec data file, not the
 *   requirement token (a copy/paste mix-up).</li>
 *   <li>None of the 6 advanced-format requirement strings that {@link HgRepository}'s OWN {@code
 *   loadRequires()} already fully understands and toggles behavior for ({@code
 *   exp-changelog-v2}, {@code exp-revlogv2.2}, {@code persistent-nodemap}, {@code fileindex-v1},
 *   {@code treemanifest}, {@code exp-copies-sidedata-changeset}) were in {@code Hg.open()}'s
 *   allowlist -- so even hg4j's own already-supported changelog-v2/treemanifest/sidedata-copies
 *   repositories were rejected by the {@code Hg.open()} front door, despite {@code new
 *   HgRepository(dir)} (bypassing the gate) handling them correctly.</li>
 * </ul>
 * Net effect before the fix: {@code Hg.open()} on a completely vanilla {@code hg init} repository
 * (no special config at all) threw {@code HgValidationException}. Fixed by syncing {@code
 * Hg.open()}'s {@code SUPPORTED} set with the real, empirically-confirmed requirement strings.
 */
@Tag("interop")
public class HgOpenRequirementValidationTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /** The core regression: a plain, un-configured `hg init` repository must open via Hg.open(). */
    @Test
    public void hgOpenAcceptsAVanillaRealHgRepository(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("vanilla").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        // HgTestUtils.hg() forces zlib (no revlog-compression-* marker at all); re-init without
        // that override to also exercise the real hg 7.2 DEFAULT (zstd) marker string.
        File defaultRepoDir = tempDir.resolve("vanilla-default").toFile();
        new ProcessBuilder("hg", "init", defaultRepoDir.getAbsolutePath()).inheritIO().start().waitFor();

        assertDoesNotThrow(() -> Hg.open(repoDir).close(),
                "Hg.open() must accept a real-hg-created repository with hg4j's forced zlib config");
        assertDoesNotThrow(() -> Hg.open(defaultRepoDir).close(),
                "Hg.open() must accept a real-hg-created repository under its OWN default config "
                        + "(zstd compression + sparserevlog -- both present in every real hg 7.2 repo by default)");
    }

    @Test
    public void hgOpenAcceptsRevlogCompressionZstdAndSparserevlog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new ProcessBuilder("hg", "init", repoDir.getAbsolutePath()).inheritIO().start().waitFor();

        String storeRequires = Files.readString(repoDir.toPath().resolve(".hg/store/requires"));
        assertTrue(storeRequires.contains("revlog-compression-zstd"),
                "sanity: real hg 7.2's default store/requires must contain revlog-compression-zstd, got: " + storeRequires);
        assertTrue(storeRequires.contains("sparserevlog"),
                "sanity: real hg 7.2's default store/requires must contain sparserevlog, got: " + storeRequires);

        assertDoesNotThrow(() -> Hg.open(repoDir).close());
    }

    @Test
    public void hgOpenAcceptsEachAdvancedFormatRequirementHgRepositoryAlreadySupports(@TempDir Path tempDir) throws Exception {
        List<String> advancedRequirements = List.of(
                "exp-changelog-v2",
                "exp-revlogv2.2",
                "persistent-nodemap",
                "fileindex-v1",
                "treemanifest",
                "exp-copies-sidedata-changeset"
        );
        int i = 0;
        for (String req : advancedRequirements) {
            File repoDir = tempDir.resolve("repo-" + (i++)).toFile();
            repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
            Files.writeString(repoDir.toPath().resolve(".hg/store/requires"), req + "\n",
                    StandardOpenOption.APPEND);
            assertDoesNotThrow(() -> Hg.open(repoDir).close(),
                    "Hg.open() must accept the already-hg4j-supported requirement: " + req);
        }
    }

    @Test
    public void hgOpenAcceptsRealNarrowRequirementString(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        // The real requirement token NarrowCloneCommand itself writes -- NOT "narrowspec" (that is
        // the on-disk filename of the narrowspec data file, a different thing).
        Files.writeString(repoDir.toPath().resolve(".hg/requires"), "narrowhg-experimental\n",
                StandardOpenOption.APPEND);
        assertDoesNotThrow(() -> Hg.open(repoDir).close());
    }

    /** Hg.open() must still reject a genuinely unrecognized requirement -- the existing gate's
     *  actual purpose (see HgPorcelainAndExceptionsTest#testHgOpenAndCoreExceptionFlows). */
    @Test
    public void hgOpenStillRejectsAGenuinelyUnknownRequirement(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve(".hg/requires"), "totally-made-up-requirement-xyz\n",
                StandardOpenOption.APPEND);
        assertThrows(HgValidationException.class, () -> Hg.open(repoDir));
    }
}
