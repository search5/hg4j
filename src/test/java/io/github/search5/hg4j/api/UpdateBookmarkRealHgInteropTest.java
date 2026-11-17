package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The active bookmark after {@code hg update REV} (mercurial/cmd_impls/update.py {@code update_totally}): the name given decides, not the
 * bookmarks that the checked out changeset happens to have.
 */
@Tag("interop")
public class UpdateBookmarkRealHgInteropTest {

    @BeforeAll
    static void checkLocalHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    private static String hg(Path cwd, String... args) throws Exception {
        return LocalHg.run(cwd, args);
    }

    /** Two changesets; m1 on 0, m2 on 1; the working copy at 1 with m2 active. */
    private static Path makeRepo(Path root, String name) throws Exception {
        Path repo = root.resolve(name);
        hg(root, "init", name);
        Files.writeString(repo.resolve("a"), "a\n");
        hg(repo, "commit", "-Aqm", "c1", "-u", "t", "-d", "0 0");
        Files.writeString(repo.resolve("a"), "b\n");
        hg(repo, "commit", "-qm", "c2", "-u", "t", "-d", "1 0");
        hg(repo, "bookmark", "-r", "0", "m1");
        hg(repo, "bookmark", "m2");
        return repo;
    }

    private static String active(Path repo) throws Exception {
        Path f = repo.resolve(".hg/bookmarks.current");
        return Files.exists(f) ? Files.readString(f).trim() : "";
    }

    private void check(Path tmp, String start, String revisionGiven) throws Exception {
        Path real = makeRepo(tmp, "real");
        Path mine = makeRepo(tmp, "mine");
        if (start != null) {
            hg(real, "update", "-q", start);
            hg(mine, "update", "-q", start);
        }
        String revReal = revisionGiven.equals("NODE0") ? hg(real, "log", "-r", "0", "-T", "{node}") : revisionGiven;
        String revMine = revisionGiven.equals("NODE0") ? hg(mine, "log", "-r", "0", "-T", "{node}") : revisionGiven;
        hg(real, "update", "-q", revReal);
        new UpdateCommand(new HgRepository(mine.toFile())).setRevision(revMine).call();
        assertEquals(active(real), active(mine), "active bookmark after update " + revisionGiven + " from " + start);
    }

    @Test
    void updateToABookmarkNameActivatesIt(@TempDir Path tmp) throws Exception {
        check(tmp, null, "m1");
    }

    @Test
    void updateToTheNodeOfABookmarkDeactivates(@TempDir Path tmp) throws Exception {
        check(tmp, null, "NODE0");
    }

    @Test
    void updateToARevisionNumberDeactivates(@TempDir Path tmp) throws Exception {
        check(tmp, null, "0");
    }

    @Test
    void updateToTheCurrentBookmarkKeepsItActive(@TempDir Path tmp) throws Exception {
        check(tmp, null, "m2");
    }

    @Test
    void updateToABranchNameDeactivates(@TempDir Path tmp) throws Exception {
        check(tmp, null, "default");
    }
}
