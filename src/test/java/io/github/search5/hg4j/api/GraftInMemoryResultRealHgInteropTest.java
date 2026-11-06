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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GraftInMemory#execute()} against {@code hg graft --to}: the same changesets, and a conflict where the original aborts. */
@Tag("interop")
class GraftInMemoryResultRealHgInteropTest {

    private static final String DATE = "1700000000 0";

    @TempDir
    Path tempDir;

    private File dir;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
    }

    private String commit(String file, String text, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), text);
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "t", "-d", DATE);
        return HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
    }

    private File cloneWithoutWorkingCopy() throws Exception {
        File c = tempDir.resolve("hg4j").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", dir.getAbsolutePath(), c.getAbsolutePath());
        return c;
    }

    @Test
    void theGraftedChangesetsAreTheOnesTheRealHgMakes() throws Exception {
        dir = tempDir.resolve("real").toFile();
        dir.mkdirs();
        HgTestUtils.hg(dir, "init");
        String base = commit("a.txt", "a\n", "base");
        String c1 = commit("x.txt", "x\n", "c1");
        String c2 = commit("a.txt", "a2\n", "c2");
        HgTestUtils.hg(dir, "update", "-r", base);
        String target = commit("t.txt", "t\n", "target");
        File ours = cloneWithoutWorkingCopy();

        HgTestUtils.hg(dir, "graft", "--to", target, "-r", c1, "-r", c2, "-u", "u", "-d", DATE);
        // `graft --to` does not move the working copy: the new changesets are the children of the target, one after the other
        String first = HgTestUtils.hg(dir, "log", "-r", "children(" + target + ")", "--template", "{node}").trim();
        String second = HgTestUtils.hg(dir, "log", "-r", "children(" + first + ")", "--template", "{node}").trim();

        GraftResult result = new GraftInMemory(new HgRepository(ours)).setTo(target).addRevision(c1).addRevision(c2)
                .setUser("u").setDate(DATE).execute();
        assertEquals(List.of(first, second), result.getNodeHexes());
        HgTestUtils.hg(ours, "verify");
    }

    @Test
    void aConflictIsWhereTheRealHgAborts() throws Exception {
        dir = tempDir.resolve("real").toFile();
        dir.mkdirs();
        HgTestUtils.hg(dir, "init");
        String base = commit("a.txt", "a\n", "base");
        String edit = commit("a.txt", "edit\n", "edit");
        HgTestUtils.hg(dir, "update", "-r", base);
        String target = commit("a.txt", "clash\n", "target");
        File ours = cloneWithoutWorkingCopy();

        String failure;
        try {
            HgTestUtils.hg(dir, "graft", "--to", target, "-r", edit, "-u", "u", "-d", DATE);
            failure = "";
        } catch (Throwable e) {
            failure = String.valueOf(e.getMessage());
        }
        assertTrue(failure.contains("cannot graft in memory: merge conflicts"), failure);

        GraftResult result = new GraftInMemory(new HgRepository(ours)).setTo(target).addRevision(edit)
                .setUser("u").setDate(DATE).execute();
        assertTrue(result.isConflicted());
        assertEquals(List.of("a.txt"), result.getConflicts());
    }
}
