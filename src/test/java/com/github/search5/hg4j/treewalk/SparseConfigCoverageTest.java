package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused unit tests for {@link SparseConfig}, exercising branches not already hit by
 * {@link SparseConfigInteropTest}: {@code parse(null)}, repeated {@code [include]} sections
 * (allowed by real hg's {@code sparse.py} {@code parseconfig()} — only an {@code [include]}
 * *after* an {@code [exclude]} is rejected), the "no {@code .hg/sparse} file" short-circuit in
 * {@link SparseConfig#resolveForRevision}, diamond/cyclic {@code %include} graphs (duplicate
 * queueing, the visited-set dedupe, and a profile back-referencing an already-visited profile),
 * a profile that exists in the repository but only *after* the queried revision (real hg's
 * {@code ManifestLookupError} case triggered via revision lookup rather than a wholly untracked
 * path), and every branch combination of {@link SparseConfig#toPathFilter()}.
 */
public class SparseConfigCoverageTest {

    // ---- parse(): branches not covered by SparseConfigInteropTest ----------------------------

    @Test
    public void parseNullRawYieldsEmptyConfig() throws Exception {
        SparseConfig cfg = SparseConfig.parse(null);
        assertTrue(cfg.includes.isEmpty());
        assertTrue(cfg.excludes.isEmpty());
        assertTrue(cfg.profiles.isEmpty());
    }

    @Test
    public void repeatedIncludeSectionsAreMergedNotRejected() throws Exception {
        // mercurial/sparse.py parseconfig() only aborts when [include] follows [exclude]
        // (`havesection and current != includes`); two [include] sections in a row leave
        // `current == includes` already, so the check is false and both merge normally.
        SparseConfig cfg = SparseConfig.parse("[include]\nfoo\n[include]\nbar\n");
        assertEquals(Set.of("foo", "bar"), cfg.includes);
    }

    // ---- resolveForRevision(): no .hg/sparse file -------------------------------------------

    @Test
    public void resolveForRevisionWithNoSparseFileReturnsEmptyConfig(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "x");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        assertFalse(new File(repoDir, ".hg/sparse").exists());
        SparseConfig resolved = SparseConfig.resolveForRevision(repo, 0);
        assertTrue(resolved.includes.isEmpty());
        assertTrue(resolved.excludes.isEmpty());
        assertTrue(resolved.profiles.isEmpty());
    }

    // ---- resolveForRevision(): diamond graph (dedupe + already-visited back-reference) --------

    @Test
    public void resolveForRevisionHandlesDiamondProfileGraph(@TempDir Path tempDir) throws Exception {
        // root --> A --> C --> X --> C (back-reference, already visited)
        // root --> B --> C (second path onto C, queued twice before C is ever processed)
        //
        // This single graph exercises:
        //  - the same profile being queued twice (by A and B) before either dequeue, so the
        //    second dequeue hits the "already visited, skip" continue;
        //  - a profile (X) discovering a sub-profile (C) that is already in `visited`, so the
        //    `!visited.contains(subProfile)` guard suppresses re-queueing it.
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repoDir, "A.sparse").toPath(), "[include]\na_inc\n%include C.sparse\n");
        Files.writeString(new File(repoDir, "B.sparse").toPath(), "[include]\nb_inc\n%include C.sparse\n");
        Files.writeString(new File(repoDir, "C.sparse").toPath(), "[include]\nc_inc\n%include X.sparse\n");
        Files.writeString(new File(repoDir, "X.sparse").toPath(), "[include]\nx_inc\n%include C.sparse\n");
        hg.add().addFile("A.sparse").call();
        hg.add().addFile("B.sparse").call();
        hg.add().addFile("C.sparse").call();
        hg.add().addFile("X.sparse").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(new File(repoDir, ".hg/sparse").toPath(),
                "[include]\nroot_inc\n%include A.sparse\n%include B.sparse\n");

        SparseConfig resolved = SparseConfig.resolveForRevision(repo, 0);
        assertEquals(Set.of("root_inc", "a_inc", "b_inc", "c_inc", "x_inc", ".hg*"), resolved.includes);
        assertTrue(resolved.excludes.isEmpty());
        assertEquals(Set.of("A.sparse", "B.sparse", "C.sparse", "X.sparse"), resolved.profiles);
    }

    @Test
    public void resolveForRevisionWithExcludeOnlyDoesNotAutoIncludeHgStar(@TempDir Path tempDir) throws Exception {
        // Real hg's patternsforrev() only does `if includes: includes.add(b'.hg*')` - an
        // exclude-only sparse profile (no includes at all, from the root file or any %include'd
        // profile) must leave the resolved include set empty, not seed it with just ".hg*".
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "x");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(new File(repoDir, ".hg/sparse").toPath(), "[exclude]\na.txt\n");

        SparseConfig resolved = SparseConfig.resolveForRevision(repo, 0);
        assertTrue(resolved.includes.isEmpty(), "no includes anywhere means .hg* must not be auto-added");
        assertEquals(Set.of("a.txt"), resolved.excludes);
    }

    // ---- resolveForRevision(): profile tracked, but only after the queried revision ----------

    @Test
    public void profileAddedAfterQueriedRevisionIsSkippedNotFatal(@TempDir Path tempDir) throws Exception {
        // Unlike SparseConfigInteropTest#missingProfileIsSkippedNotFatal (where the profile path
        // was never tracked at all, so its filelog index file itself does not exist), here the
        // profile *does* exist in the repository's history - just not yet at the revision we
        // query - exercising readTrackedFileAtRevision's per-revision linkRev scan running to
        // exhaustion and returning null, rather than the earlier "index file missing" check.
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "x");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c0 - no profile yet").call();

        Files.writeString(new File(repoDir, ".hg/sparse").toPath(), "[include]\na.txt\n%include profile.sparse\n");

        Files.writeString(new File(repoDir, "profile.sparse").toPath(), "[include]\nb.txt\n");
        hg.add().addFile("profile.sparse").call();
        hg.commit().setAuthor("T").setMessage("c1 - adds profile").call();

        SparseConfig resolvedAtRev0 = assertDoesNotThrow(() -> SparseConfig.resolveForRevision(repo, 0));
        assertEquals(Set.of("a.txt", ".hg*"), resolvedAtRev0.includes,
                "profile.sparse is not yet in the manifest at rev 0, so its rules must not merge");
        assertTrue(resolvedAtRev0.profiles.contains("profile.sparse"),
                "the attempted (even if unresolved) profile name is still reported, matching real hg");

        // Sanity check: at rev 1 (where the profile was actually added) it does resolve.
        SparseConfig resolvedAtRev1 = SparseConfig.resolveForRevision(repo, 1);
        assertTrue(resolvedAtRev1.includes.contains("b.txt"));
    }

    // ---- toPathFilter(): every combination of empty/non-empty includes & excludes ------------

    @Test
    public void pathFilterAcceptsEverythingWhenSparseIsInactive() throws Exception {
        SparseConfig cfg = SparseConfig.parse("");
        PathFilter filter = cfg.toPathFilter();
        assertTrue(filter.accept("anything/at/all.txt"));
        assertTrue(filter.accept(""));
    }

    @Test
    public void pathFilterWithIncludesOnlyRejectsNonMatchingPaths() throws Exception {
        SparseConfig cfg = SparseConfig.parse("[include]\na/*.txt\n");
        PathFilter filter = cfg.toPathFilter();
        assertTrue(filter.accept("a/1.txt"));
        assertFalse(filter.accept("b/1.txt"));
    }

    @Test
    public void pathFilterWithExcludesOnlyRejectsOnlyExcludedPaths() throws Exception {
        SparseConfig cfg = SparseConfig.parse("[exclude]\nb/skip.txt\n");
        PathFilter filter = cfg.toPathFilter();
        assertFalse(filter.accept("b/skip.txt"));
        assertTrue(filter.accept("other.txt"));
    }

    @Test
    public void pathFilterWithBothIncludesAndExcludesCombinesRules() throws Exception {
        SparseConfig cfg = SparseConfig.parse("[include]\na/*.txt\n[exclude]\na/skip.txt\n");
        PathFilter filter = cfg.toPathFilter();
        assertTrue(filter.accept("a/1.txt"), "included and not excluded");
        assertFalse(filter.accept("a/skip.txt"), "included but specifically excluded");
        assertFalse(filter.accept("b/1.txt"), "not included at all");
    }
}
