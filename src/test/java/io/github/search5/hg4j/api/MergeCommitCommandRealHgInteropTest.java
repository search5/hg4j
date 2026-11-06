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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MergeCommitCommand} against the real {@code hg}: the same two heads are merged once by {@code hg merge} + {@code hg commit}
 * and once by hg4j in a clone, with the same user, date and message. The two merge changesets have to be the same changeset (same
 * node, so the same manifest, file list, parents and extra), and the result has to pass {@code hg verify}.
 */
@Tag("interop")
class MergeCommitCommandRealHgInteropTest {

    private static final String DATE = "1700000000 0";
    private static final List<String> CHANGELOG_V2 =
            List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
    }

    private File init(String name, List<String> initConfig) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        dir.mkdirs();
        List<String> args = new ArrayList<>(List.of("init"));
        for (String c : initConfig) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(dir, args.toArray(new String[0]));
        return dir;
    }

    private static void write(File dir, String path, String text) throws Exception {
        File f = new File(dir, path);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
    }

    private static String commit(File dir, String message) throws Exception {
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "tester", "-d", DATE);
        return HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
    }

    /** Builds the base changeset and the two heads with {@code setup}; returns [base, p1 (the head merged into), p2]. */
    private String[] twoHeads(File dir, Consumer<File> base, Consumer<File> ours, Consumer<File> theirs) throws Exception {
        base.accept(dir);
        String b = commit(dir, "base");
        ours.accept(dir);
        String p1 = commit(dir, "ours");
        HgTestUtils.hg(dir, "update", "-r", b);
        theirs.accept(dir);
        String p2 = commit(dir, "theirs");
        return new String[] {b, p1, p2};
    }

    /**
     * Merges the heads with the real hg in {@code dir} and with hg4j in a clone of it; asserts that the two merge changesets are the
     * same and that the clone verifies. Returns the hg4j clone.
     */
    private File mergeBothWays(File dir, String p1, String p2, List<String> cloneConfig) throws Exception {
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());

        HgTestUtils.hg(dir, "update", "-r", p1);
        HgTestUtils.hg(dir, "merge", "-r", p2, "--tool", "internal:merge");
        HgTestUtils.hg(dir, "commit", "-m", "merge", "-u", "tester", "-d", DATE);
        String expected = HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();

        HgRepository repo = new HgRepository(ours);
        MergeCommitResult result = new MergeCommitCommand(repo)
                .setP1(NodeIdUtil.fromHex(p1)).setP2(NodeIdUtil.fromHex(p2))
                .setUser("tester").setDate(DATE).setMessage("merge").call();

        assertFalse(result.isConflicted(), "conflicts: " + result.getConflicts());
        assertEquals(expected, result.getNodeHex(), "the merge changeset differs from the one of the real hg");
        HgTestUtils.hg(ours, "verify");
        assertEquals(p1 + " " + p2, HgTestUtils.hg(ours, "log", "-r", expected, "--template", "{p1node} {p2node}").trim());
        return ours;
    }

    @Test
    void cleanMergeOfDifferentFilesIsTheSameChangesetAsTheRealHgs() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "a.txt", "a\n"); write(d, "b.txt", "b\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "a.txt", "a changed\n"); write(d, "x.txt", "x\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "b.txt", "b changed\n"); write(d, "y.txt", "y\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        mergeBothWays(dir, h[1], h[2], List.of());
    }

    @Test
    void deletionExecutableBitAndSymlinkChangesMergeLikeTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "c.txt", "c\n"); write(d, "run.sh", "#!/bin/sh\n"); write(d, "keep.txt", "k\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "keep.txt", "k ours\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> {
                    try {
                        Files.delete(new File(d, "c.txt").toPath());
                        new File(d, "run.sh").setExecutable(true);
                        Files.createSymbolicLink(new File(d, "link").toPath(), Path.of("keep.txt"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        assertTrue(HgTestUtils.hg(ours, "manifest", "-r", "tip", "--debug").contains("755 * run.sh"));
    }

    @Test
    void bothSidesEditingDifferentLinesOfOneFileAreMergedLikeTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "f.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "f.txt", "1 ours\n2\n3\n4\n5\n6\n7\n8\n9\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "f.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9 theirs\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        mergeBothWays(dir, h[1], h[2], List.of());
    }

    @Test
    void renameOnOneSideAndAnotherFileChangedOnTheOtherMergeLikeTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "old.txt", "content\n"); write(d, "other.txt", "o\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> {
                    try {
                        HgTestUtils.hg(d, "rename", "old.txt", "new.txt");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                d -> { try { write(d, "other.txt", "o changed\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        // the rename lives in the filelog of new.txt; it is part of the node comparison above
        assertEquals("old.txt", HgTestUtils.hg(ours, "log", "-r", h[1], "--template", "{file_copies}").replaceAll("^new.txt \\((.*)\\)$", "$1"));
    }

    @Test
    void mergeInAChangelogV2RepositoryIsTheSameChangesetAsTheRealHgs() throws Exception {
        changelogV2Merge(CHANGELOG_V2, "exp-changelog-v2");
    }

    @Test
    void mergeInAChangelogV2SidedataRepositoryHasTheSameChangedFilesSidedataAsTheRealHg() throws Exception {
        changelogV2Merge(SIDEDATA, "exp-copies-sidedata-changeset");
    }

    private void changelogV2Merge(List<String> config, String requirement) throws Exception {
        File dir = init("real", config);
        assertTrue(Files.readString(new File(dir, ".hg/store/requires").toPath()).contains(requirement), "the repository must have " + requirement);
        String[] h = twoHeads(dir,
                d -> { try { write(d, "a.txt", "a\n"); write(d, "b.txt", "b\n"); write(d, "gone.txt", "g\n"); write(d, "m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "a.txt", "a changed\n"); write(d, "x.txt", "x\n"); write(d, "m.txt", "1 ours\n2\n3\n4\n5\n6\n7\n8\n9\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> {
                    try {
                        write(d, "b.txt", "b changed\n");
                        write(d, "m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9 theirs\n");
                        Files.delete(new File(d, "gone.txt").toPath());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        File ours = mergeBothWays(dir, h[1], h[2], config);
        assertTrue(Files.readString(new File(ours, ".hg/store/requires").toPath()).contains(requirement));
        String expected = HgTestUtils.hg(dir, "debugchangedfiles", "tip").trim();
        assertEquals(expected, HgTestUtils.hg(ours, "debugchangedfiles", "tip").trim());
        if (requirement.contains("sidedata")) {
            assertTrue(expected.contains("m.txt"), "the merged file must be recorded: " + expected);
        }
    }

    @Test
    void conflictingEditsAreReturnedAsAResultAndNothingIsWritten() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "f.txt", "line\n"); write(d, "g.txt", "g\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "f.txt", "ours\n"); write(d, "g.txt", "g ours\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "f.txt", "theirs\n"); write(d, "g.txt", "g theirs\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());
        long before = new File(ours, ".hg/store/00changelog.i").length();

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(NodeIdUtil.fromHex(h[1])).setP2(NodeIdUtil.fromHex(h[2])).setUser("tester").setMessage("merge").call();

        assertTrue(result.isConflicted());
        assertEquals(List.of("f.txt", "g.txt"), result.getConflicts());
        assertEquals(null, result.getNode());
        assertEquals(before, new File(ours, ".hg/store/00changelog.i").length(), "a conflict must not write anything");
        HgTestUtils.hg(ours, "verify");
    }

    @Test
    void aFileDeletedOnOneSideAndEditedOnTheOtherIsAConflictNotALostEdit() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "f.txt", "line\n"); write(d, "keep.txt", "k\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { Files.delete(new File(d, "f.txt").toPath()); write(d, "keep.txt", "k ours\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "f.txt", "edited\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(NodeIdUtil.fromHex(h[1])).setP2(NodeIdUtil.fromHex(h[2])).setUser("tester").setMessage("merge").call();

        assertTrue(result.isConflicted());
        assertEquals(List.of("f.txt"), result.getConflicts());
    }

    @Test
    void theMergeCanBeUpdatedToAndPushedByTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = twoHeads(dir,
                d -> { try { write(d, "a.txt", "a\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "a.txt", "a ours\n"); } catch (Exception e) { throw new RuntimeException(e); } },
                d -> { try { write(d, "b.txt", "b\n"); } catch (Exception e) { throw new RuntimeException(e); } });
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());
        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(NodeIdUtil.fromHex(h[1])).setP2(NodeIdUtil.fromHex(h[2])).setUser("tester").setMessage("merge").call();

        HgTestUtils.hg(ours, "update", "-r", result.getNodeHex());
        assertEquals("a ours\n", Files.readString(new File(ours, "a.txt").toPath()));
        assertEquals("b\n", Files.readString(new File(ours, "b.txt").toPath()));
        File target = init("target", List.of());
        HgTestUtils.hg(ours, "push", target.getAbsolutePath());
        HgTestUtils.hg(target, "verify");
        assertEquals(1, HgTestUtils.hg(target, "heads", "--template", "{node}\n").trim().split("\n").length);
    }

    @Test
    void aMergeOfAnAncestorIsRefusedUnlessAskedForAndThenKeepsTheTreeOfTheDescendant() throws Exception {
        File dir = init("real", List.of());
        write(dir, "a.txt", "a\n");
        String first = commit(dir, "first");
        write(dir, "a.txt", "a second\n");
        String second = commit(dir, "second");
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());
        HgRepository repo = new HgRepository(ours);

        org.junit.jupiter.api.Assertions.assertThrows(io.github.search5.hg4j.errors.HgValidationException.class,
                () -> new MergeCommitCommand(repo).setP1(NodeIdUtil.fromHex(first)).setP2(NodeIdUtil.fromHex(second)).setUser("tester").setMessage("m").call());

        MergeCommitResult result = new MergeCommitCommand(repo).setAllowAncestorMerge(true)
                .setP1(NodeIdUtil.fromHex(first)).setP2(NodeIdUtil.fromHex(second)).setUser("tester").setDate(DATE).setMessage("no-ff").call();
        assertFalse(result.isConflicted());
        HgTestUtils.hg(ours, "verify");
        assertEquals(first + " " + second,
                HgTestUtils.hg(ours, "log", "-r", result.getNodeHex(), "--template", "{p1node} {p2node}").trim());
        assertEquals(HgTestUtils.hg(ours, "manifest", "-r", second, "--debug"), HgTestUtils.hg(ours, "manifest", "-r", result.getNodeHex(), "--debug"));
    }

    @Test
    void branchAndExtraAreWrittenAndTheDefaultBranchIsThatOfTheFirstParent() throws Exception {
        File dir = init("real", List.of());
        write(dir, "a.txt", "a\n");
        String base = commit(dir, "base");
        HgTestUtils.hg(dir, "branch", "feature");
        write(dir, "f.txt", "f\n");
        String p1 = commit(dir, "on feature");
        HgTestUtils.hg(dir, "update", "-r", base);
        write(dir, "b.txt", "b\n");
        String p2 = commit(dir, "on default");
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());
        HgRepository repo = new HgRepository(ours);

        MergeCommitResult result = new MergeCommitCommand(repo)
                .setP1(NodeIdUtil.fromHex(p1)).setP2(NodeIdUtil.fromHex(p2)).setUser("tester").setMessage("m").putExtra("pr", "12").call();
        assertEquals("feature", HgTestUtils.hg(ours, "log", "-r", result.getNodeHex(), "--template", "{branch}").trim());
        assertEquals("12", HgTestUtils.hg(ours, "log", "-r", result.getNodeHex(), "--template", "{extras % '{value}'}").trim().replaceAll(".*?(12).*", "$1"));
        MergeCommitResult onDefault = new MergeCommitCommand(repo).setBranch("default")
                .setP1(NodeIdUtil.fromHex(p1)).setP2(NodeIdUtil.fromHex(p2)).setUser("tester").setMessage("other").call();
        assertEquals("default", HgTestUtils.hg(ours, "log", "-r", onDefault.getNodeHex(), "--template", "{branch}").trim());
        HgTestUtils.hg(ours, "verify");
    }

    // ---------------------------------------------------------------- renames and copies

    @FunctionalInterface
    private interface Step {
        void run(File dir) throws Exception;
    }

    private static final String NINE = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n";

    private String[] heads(File dir, Step base, Step ours, Step theirs) throws Exception {
        return twoHeads(dir, d -> run(base, d), d -> run(ours, d), d -> run(theirs, d));
    }

    private static void run(Step step, File dir) {
        try {
            step.run(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Step baseWithNineLines() {
        return d -> { write(d, "a.txt", NINE); write(d, "k.txt", "k\n"); write(d, "d/x.txt", "x\n"); write(d, "d/y.txt", "y\n"); };
    }

    @Test
    void renameOnTheFirstSideAndAnEditOfTheOldNameOnTheSecondAreMergedIntoTheNewName() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> HgTestUtils.hg(d, "mv", "a.txt", "b.txt"),
                d -> write(d, "a.txt", NINE.replace("l9", "L9")));
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        assertEquals("b.txt\nd/x.txt\nd/y.txt\nk.txt", HgTestUtils.hg(ours, "manifest", "-r", "tip").trim());
        assertTrue(HgTestUtils.hg(ours, "cat", "-r", "tip", "b.txt").contains("L9"));
    }

    @Test
    void renameOnTheSecondSideAndAnEditOfTheOldNameOnTheFirstAreMergedIntoTheNewName() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> write(d, "a.txt", NINE.replace("l9", "L9")),
                d -> HgTestUtils.hg(d, "mv", "a.txt", "b.txt"));
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        assertEquals("b.txt\nd/x.txt\nd/y.txt\nk.txt", HgTestUtils.hg(ours, "manifest", "-r", "tip").trim());
        assertTrue(HgTestUtils.hg(ours, "cat", "-r", "tip", "b.txt").contains("L9"));
    }

    @Test
    void aCopyOnTheFirstSideAndAnEditOfTheSourceOnTheSecondUpdateBothFiles() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> HgTestUtils.hg(d, "cp", "a.txt", "b.txt"),
                d -> write(d, "a.txt", NINE.replace("l9", "L9")));
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        assertTrue(HgTestUtils.hg(ours, "cat", "-r", "tip", "a.txt").contains("L9"));
        assertTrue(HgTestUtils.hg(ours, "cat", "-r", "tip", "b.txt").contains("L9"));
    }

    @Test
    void aRenameWithItsOwnEditAndAnEditOfTheOldNameOnTheOtherSideAreMergedByLine() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> { HgTestUtils.hg(d, "mv", "a.txt", "b.txt"); write(d, "b.txt", NINE.replace("l1", "L1")); },
                d -> write(d, "a.txt", NINE.replace("l9", "L9")));
        File ours = mergeBothWays(dir, h[1], h[2], List.of());
        String merged = HgTestUtils.hg(ours, "cat", "-r", "tip", "b.txt");
        assertTrue(merged.startsWith("L1\n") && merged.contains("L9"), merged);
    }

    @Test
    void divergentRenamesKeepBothNewNamesLikeTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> HgTestUtils.hg(d, "mv", "a.txt", "b.txt"),
                d -> HgTestUtils.hg(d, "mv", "a.txt", "c.txt"));
        mergeBothWays(dir, h[1], h[2], List.of());
    }

    @Test
    void aRenameOnOneSideAndADeletionOfTheOldNameOnTheOtherKeepTheNewNameLikeTheRealHg() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> HgTestUtils.hg(d, "mv", "a.txt", "b.txt"),
                d -> HgTestUtils.hg(d, "rm", "a.txt"));
        mergeBothWays(dir, h[1], h[2], List.of());
    }

    @Test
    void renamesInAChangelogV2SidedataRepositoryHaveTheSameChangedFilesSidedataAsTheRealHg() throws Exception {
        for (boolean renameOnFirst : new boolean[] {true, false}) {
            File dir = init(renameOnFirst ? "real-first" : "real-second", SIDEDATA);
            Step rename = d -> HgTestUtils.hg(d, "mv", "a.txt", "b.txt");
            Step edit = d -> write(d, "a.txt", NINE.replace("l9", "L9"));
            String[] h = heads(dir, baseWithNineLines(), renameOnFirst ? rename : edit, renameOnFirst ? edit : rename);
            File ours = mergeBothWays(dir, h[1], h[2], SIDEDATA);
            assertEquals(HgTestUtils.hg(dir, "debugchangedfiles", "tip").trim(), HgTestUtils.hg(ours, "debugchangedfiles", "tip").trim());
            assertTrue(ours.renameTo(tempDir.resolve("done-" + renameOnFirst).toFile()), "the clone is moved away for the next round");
        }
    }

    @Test
    void anEditAfterARenameThatOverlapsTheOtherSidesEditIsAConflictOfTheNewName() throws Exception {
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> { HgTestUtils.hg(d, "mv", "a.txt", "b.txt"); write(d, "b.txt", NINE.replace("l9", "mine")); },
                d -> write(d, "a.txt", NINE.replace("l9", "theirs")));
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());
        long before = new File(ours, ".hg/store/00changelog.i").length();

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(NodeIdUtil.fromHex(h[1])).setP2(NodeIdUtil.fromHex(h[2])).setUser("tester").setMessage("merge").call();

        assertTrue(result.isConflicted());
        assertEquals(List.of("b.txt"), result.getConflicts());
        assertEquals(before, new File(ours, ".hg/store/00changelog.i").length());
    }

    @Test
    void aFileAddedToADirectoryTheOtherSideRenamedIsReportedAsAConflictNotLeftBehind() throws Exception {
        // the real hg moves d/z.txt to e/z.txt (directory rename); hg4j does not follow directory renames and says so
        File dir = init("real", List.of());
        String[] h = heads(dir, baseWithNineLines(),
                d -> HgTestUtils.hg(d, "mv", "d", "e"),
                d -> { write(d, "d/z.txt", "z\n"); HgTestUtils.hg(d, "add", "d/z.txt"); });
        File ours = tempDir.resolve("hg4j-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), ours.getAbsolutePath());

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(NodeIdUtil.fromHex(h[1])).setP2(NodeIdUtil.fromHex(h[2])).setUser("tester").setMessage("merge").call();

        assertTrue(result.isConflicted());
        assertEquals(List.of("d/z.txt"), result.getConflicts());
    }
}
