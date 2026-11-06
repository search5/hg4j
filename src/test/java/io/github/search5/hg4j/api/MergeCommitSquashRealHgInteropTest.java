package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link MergeCommitCommand#setSquash(boolean)} against the real {@code hg}: {@code hg merge}, {@code hg debugsetparents P1} and
 * {@code hg commit} give a changeset with the merged tree and one parent. hg4j has to create the same changeset (same node) in a clone.
 */
@Tag("interop")
class MergeCommitSquashRealHgInteropTest {

    private static final String DATE = "1700000000 0";
    private static final List<String> SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
    }

    private File init(List<String> initConfig) throws Exception {
        File dir = tempDir.resolve("real").toFile();
        dir.mkdirs();
        List<String> args = new ArrayList<>(List.of("init"));
        for (String c : initConfig) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(dir, args.toArray(new String[0]));
        return dir;
    }

    private static void write(File dir, String path, String text) {
        try {
            File f = new File(dir, path);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String commit(File dir, String message) throws Exception {
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "tester", "-d", DATE);
        return HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
    }

    private interface Edit {
        void apply(File dir) throws Exception;
    }

    private static Edit edit(Edit e) {
        return e;
    }

    /** base, then p1 = base + ours, p2 = base + theirs; returns [base, p1, p2]. */
    private String[] twoHeads(File dir, Edit base, Edit ours, Edit theirs) throws Exception {
        base.apply(dir);
        String b = commit(dir, "base");
        ours.apply(dir);
        String p1 = commit(dir, "ours");
        HgTestUtils.hg(dir, "update", "-r", b);
        theirs.apply(dir);
        String p2 = commit(dir, "theirs");
        return new String[] {b, p1, p2};
    }

    /** Squashes with the real hg in {@code dir} and with hg4j in a clone; the changesets have to be the same. Returns the clone. */
    private File squashBothWays(File dir, String p1, String p2) throws Exception {
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());

        HgTestUtils.hg(dir, "update", "-r", p1);
        HgTestUtils.hg(dir, "merge", "-r", p2, "--tool", "internal:merge");
        HgTestUtils.hg(dir, "debugsetparents", p1);
        HgTestUtils.hg(dir, "commit", "-m", "squash", "-u", "tester", "-d", DATE);
        String expected = HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();

        return compare(ours, p1, p2, expected, false);
    }

    private File compare(File ours, String p1, String p2, String expected, boolean allowAncestor) throws Exception {
        HgRepository repo = new HgRepository(ours);
        MergeCommitResult result = new MergeCommitCommand(repo)
                .setP1(NodeIdUtil.fromHex(p1)).setP2(NodeIdUtil.fromHex(p2)).setSquash(true).setAllowAncestorMerge(allowAncestor)
                .setUser("tester").setDate(DATE).setMessage("squash").call();
        assertFalse(result.isConflicted(), "conflicts: " + result.getConflicts());
        assertEquals(expected, result.getNodeHex(), "the squash changeset differs from the one of the real hg");
        HgTestUtils.hg(ours, "verify");
        assertEquals(p1 + " " + "0".repeat(40), HgTestUtils.hg(ours, "log", "-r", expected, "--template", "{p1node} {p2node}").trim());
        return ours;
    }

    @Test
    void differentFilesOnBothSidesSquashLikeTheRealHg() throws Exception {
        File dir = init(List.of());
        String[] h = twoHeads(dir,
                edit(d -> { write(d, "a.txt", "a\n"); write(d, "b.txt", "b\n"); }),
                edit(d -> { write(d, "a.txt", "a changed\n"); write(d, "x.txt", "x\n"); }),
                edit(d -> { write(d, "b.txt", "b changed\n"); write(d, "y.txt", "y\n"); }));
        squashBothWays(dir, h[1], h[2]);
    }

    @Test
    void bothSidesEditingDifferentLinesOfOneFileSquashLikeTheRealHg() throws Exception {
        File dir = init(List.of());
        String[] h = twoHeads(dir,
                edit(d -> write(d, "f.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n")),
                edit(d -> write(d, "f.txt", "1 ours\n2\n3\n4\n5\n6\n7\n8\n9\n")),
                edit(d -> write(d, "f.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9 theirs\n")));
        squashBothWays(dir, h[1], h[2]);
    }

    @Test
    void deletionExecutableBitAndSymlinkSquashLikeTheRealHg() throws Exception {
        File dir = init(List.of());
        String[] h = twoHeads(dir,
                edit(d -> { write(d, "c.txt", "c\n"); write(d, "run.sh", "#!/bin/sh\n"); write(d, "keep.txt", "k\n"); }),
                edit(d -> write(d, "keep.txt", "k ours\n")),
                edit(d -> {
                    Files.delete(new File(d, "c.txt").toPath());
                    new File(d, "run.sh").setExecutable(true);
                    Files.createSymbolicLink(new File(d, "link").toPath(), Path.of("keep.txt"));
                }));
        squashBothWays(dir, h[1], h[2]);
    }

    @Test
    void renameOnTheOtherSideWithAChangeOfTheOldNameSquashesLikeTheRealHg() throws Exception {
        File dir = init(List.of());
        String[] h = twoHeads(dir,
                edit(d -> { write(d, "old.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n"); write(d, "o.txt", "o\n"); }),
                edit(d -> write(d, "old.txt", "1 ours\n2\n3\n4\n5\n6\n7\n8\n9\n")),
                edit(d -> { HgTestUtils.hg(d, "rename", "old.txt", "new.txt"); write(d, "new.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9 theirs\n"); }));
        squashBothWays(dir, h[1], h[2]);
    }

    @Test
    void renameOnOurSideSquashesLikeTheRealHg() throws Exception {
        File dir = init(List.of());
        String[] h = twoHeads(dir,
                edit(d -> { write(d, "old.txt", "content\n"); write(d, "other.txt", "o\n"); }),
                edit(d -> HgTestUtils.hg(d, "rename", "old.txt", "new.txt")),
                edit(d -> write(d, "other.txt", "o changed\n")));
        squashBothWays(dir, h[1], h[2]);
    }

    @Test
    void aDescendantAsSecondParentSquashesToItsTreeOnlyWhenAncestorMergeIsAllowed() throws Exception {
        File dir = init(List.of());
        write(dir, "a.txt", "a\n");
        String p1 = commit(dir, "p1");
        write(dir, "a.txt", "a2\n");
        write(dir, "n.txt", "n\n");
        String mid = commit(dir, "mid");
        write(dir, "a.txt", "a3\n");
        String p2 = commit(dir, "p2");
        File clone = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), clone.getAbsolutePath());

        HgTestUtils.hg(dir, "update", "-r", p1);
        HgTestUtils.hg(dir, "revert", "--all", "--no-backup", "-r", p2);
        HgTestUtils.hg(dir, "commit", "-A", "-m", "squash", "-u", "tester", "-d", DATE);
        String expected = HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
        // `hg merge` refuses this (nothing to merge), so does the squash unless the hg4j extension is switched on
        org.junit.jupiter.api.Assertions.assertThrows(io.github.search5.hg4j.errors.HgValidationException.class,
                () -> new MergeCommitCommand(new HgRepository(clone)).setP1(NodeIdUtil.fromHex(p1)).setP2(NodeIdUtil.fromHex(p2))
                        .setSquash(true).setUser("tester").setDate(DATE).setMessage("squash").call());
        compare(clone, p1, p2, expected, true);
    }

    @Test
    void squashInAChangelogV2SidedataRepositoryHasTheSameChangedFilesSidedata() throws Exception {
        File dir = init(SIDEDATA);
        String[] h = twoHeads(dir,
                edit(d -> { write(d, "old.txt", "content\n"); write(d, "o.txt", "o\n"); write(d, "gone.txt", "g\n"); }),
                edit(d -> write(d, "o.txt", "o ours\n")),
                edit(d -> {
                    HgTestUtils.hg(d, "rename", "old.txt", "new.txt");
                    HgTestUtils.hg(d, "remove", "gone.txt");
                    write(d, "added.txt", "x\n");
                }));
        File ours = squashBothWays(dir, h[1], h[2]);
        String tip = HgTestUtils.hg(ours, "log", "-r", "tip", "--template", "{node}").trim();
        // hg's own view of the changed files is part of the node (sidedata is stored with the revision); compare it as well
        assertEquals(HgTestUtils.hg(dir, "debugchangedfiles", ".").trim(), HgTestUtils.hg(ours, "debugchangedfiles", tip).trim());
    }
}
