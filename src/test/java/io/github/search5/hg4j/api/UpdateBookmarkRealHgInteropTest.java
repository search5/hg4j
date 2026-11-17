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

    /** Builds the same history in two repositories by running the same hg commands in both, then compares a bare {@code hg update}. */
    private void bare(Path tmp, String[][] setup) throws Exception {
        Path real = tmp.resolve("real");
        Path mine = tmp.resolve("mine");
        for (Path repo : new Path[]{real, mine}) {
            hg(tmp, "init", repo.getFileName().toString());
            int n = 0;
            for (String[] cmd : setup) {
                if (cmd[0].equals("commit")) {
                    Files.writeString(repo.resolve("f" + (n % 3)), "v" + n + "\n");
                    hg(repo, "commit", "-Aqm", "c" + n, "-u", "t", "-d", n + " 0");
                    n++;
                } else {
                    // "@N" stands for the node of revision N; debugobsolete needs the markers enabled
                    java.util.List<String> args = new java.util.ArrayList<>();
                    if (cmd[0].equals("debugobsolete")) {
                        args.add("--config");
                        args.add("experimental.evolution=all");
                    }
                    for (String a : cmd) {
                        args.add(a.startsWith("@") ? hg(repo, "log", "--hidden", "-r", a.substring(1), "-T", "{node}").trim() : a);
                    }
                    hg(repo, args.toArray(new String[0]));
                }
            }
        }
        hg(real, "update", "-q");
        new UpdateCommand(new HgRepository(mine.toFile())).call();
        compareAfterUpdate(real, mine);
    }

    private void compareAfterUpdate(Path real, Path mine) throws Exception {
        assertEquals(hg(real, "status", "-A"), hg(mine, "status", "-A"), "the working copy");
        try (var l1 = Files.list(real); var l2 = Files.list(mine)) {
            assertEquals(l1.map(x -> x.getFileName().toString()).filter(n -> !n.equals(".hg")).sorted().toList(),
                    l2.map(x -> x.getFileName().toString()).filter(n -> !n.equals(".hg")).sorted().toList(), "the files on disk");
        }
        assertEquals(hg(real, "log", "-r", ".", "-T", "{rev}"), hg(mine, "log", "-r", ".", "-T", "{rev}"), "the working directory parent");
        assertEquals(hg(real, "bookmarks"), hg(mine, "bookmarks"), "the bookmarks");
        assertEquals(hg(real, "branch"), hg(mine, "branch"), "the branch");
        assertEquals(active(real), active(mine), "the active bookmark");
    }

    @Test
    void bareUpdateGoesToTheBranchHead(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"commit"}, {"update", "-q", "0"}});
    }

    @Test
    void bareUpdateTakesTheTipmostOfTwoHeads(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"update", "-q", "0"}, {"commit"}, {"update", "-q", "0"}});
    }

    @Test
    void bareUpdateStaysOnTheBranch(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"branch", "-q", "b2"}, {"commit"}, {"update", "-q", "default"}, {"commit"}, {"update", "-q", "b2"},
                {"update", "-q", "1"}});
    }

    @Test
    void bareUpdateMovesTheActiveBookmarkAlong(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"bookmark", "m"}, {"commit"}, {"update", "-q", "0"}, {"bookmark", "-r", "0", "-f", "m"},
                {"update", "-q", "m"}});
    }

    @Test
    void bareUpdateGoesToTheActiveBookmarkAwayFromTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"bookmark", "-r", "1", "m"}, {"update", "-q", "0"}, {"bookmark", "-r", "1", "-f", "m"},
                {"update", "-q", "m"}, {"update", "-q", "0"}});
    }

    @Test
    void bareUpdateDeletesTheDivergentBookmarkItPassed(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"bookmark", "m"}, {"commit"}, {"bookmark", "-r", "1", "m@other"}, {"update", "-q", "0"},
                {"update", "-q", "m"}});
    }

    @Test
    void bareUpdateOfAnEmptyWorkingDirectoryGoesToTheTipmostHead(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"update", "-q", "null"}});
    }

    @Test
    void bareUpdateOfAnObsoleteParentGoesToItsSuccessor(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"update", "-q", "0"}, {"commit"}, {"debugobsolete", "@1", "@2"},
                {"update", "-q", "--hidden", "1"}});
    }

    @Test
    void bareUpdateOfAPrunedParentGoesToItsClosestLiveAncestor(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"commit"}, {"debugobsolete", "@2"}, {"debugobsolete", "@1"},
                {"update", "-q", "--hidden", "2"}});
    }

    @Test
    void bareUpdateOfASplitParentGoesToTheTipmostSuccessor(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"update", "-q", "0"}, {"commit"}, {"update", "-q", "0"}, {"commit"},
                {"debugobsolete", "@1", "@2", "@3"}, {"update", "-q", "--hidden", "1"}});
    }

    @Test
    void bareUpdateOfAnObsoleteParentMovesTheActiveBookmark(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"update", "-q", "0"}, {"commit"}, {"update", "-q", "--hidden", "1"},
                {"bookmark", "m"}, {"debugobsolete", "@1", "@2"}});
    }

    @Test
    void bareUpdateOfAnObsoleteParentWithChildrenStaysOnTheBranchStep(@TempDir Path tmp) throws Exception {
        bare(tmp, new String[][]{{"commit"}, {"commit"}, {"commit"}, {"debugobsolete", "@1"}, {"update", "-q", "--hidden", "1"}});
    }

    @Test
    void updateToNullEmptiesTheWorkingCopy(@TempDir Path tmp) throws Exception {
        for (String name : new String[]{"real", "mine"}) {
            Path repo = tmp.resolve(name);
            hg(tmp, "init", name);
            Files.writeString(repo.resolve("a"), "a\n");
            hg(repo, "commit", "-Aqm", "c0", "-u", "t", "-d", "0 0");
            hg(repo, "branch", "-q", "b2");
            Files.createDirectories(repo.resolve("d"));
            Files.writeString(repo.resolve("d/b"), "b\n");
            hg(repo, "commit", "-Aqm", "c1", "-u", "t", "-d", "1 0");
            hg(repo, "bookmark", "m");
        }
        hg(tmp.resolve("real"), "update", "-q", "null");
        new UpdateCommand(new HgRepository(tmp.resolve("mine").toFile())).setRevision("null").call();
        assertEquals("-1", hg(tmp.resolve("mine"), "log", "-r", ".", "-T", "{rev}"));
        assertEquals(hg(tmp.resolve("real"), "branch"), hg(tmp.resolve("mine"), "branch"));
        assertEquals(hg(tmp.resolve("real"), "bookmarks"), hg(tmp.resolve("mine"), "bookmarks"));
        assertEquals(active(tmp.resolve("real")), active(tmp.resolve("mine")));
        compareAfterUpdate(tmp.resolve("real"), tmp.resolve("mine"));
    }

    @Test
    void bareUpdateWhenEveryAncestorIsObsoleteGoesToNull(@TempDir Path tmp) throws Exception {
        Path real = tmp.resolve("real");
        Path mine = tmp.resolve("mine");
        for (Path repo : new Path[]{real, mine}) {
            hg(tmp, "init", repo.getFileName().toString());
            for (int n = 0; n < 2; n++) {
                Files.writeString(repo.resolve("f" + n), "v\n");
                hg(repo, "commit", "-Aqm", "c" + n, "-u", "t", "-d", n + " 0");
            }
            for (int r = 0; r < 2; r++) {
                hg(repo, "--config", "experimental.evolution=all", "debugobsolete", hg(repo, "log", "--hidden", "-r", "" + r, "-T", "{node}").trim());
            }
        }
        hg(real, "update", "-q", "--hidden");
        new UpdateCommand(new HgRepository(mine.toFile())).call();
        assertEquals(hg(real, "log", "--hidden", "-r", ".", "-T", "{rev}"), hg(mine, "log", "--hidden", "-r", ".", "-T", "{rev}"));
        compareAfterUpdate(real, mine);
    }
}
