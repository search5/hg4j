package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hg bundle FILE [DEST]}: without {@code --base} and {@code --all} the bundle holds what the destination (the given one, else
 * {@code paths.default-push}, else {@code paths.default}) does not have, as the original works it out.
 */
class BundleCommandDestinationTest {

    @TempDir
    Path tempDir;

    private File sourceDir;
    private HgRepository source;
    private File destDir;
    private HgRepository dest;
    private byte[] base;
    private byte[] change;

    private byte[] commit(HgRepository repo, File dir, String file, String text, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), text);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** dest has base; source has base and one more changeset. */
    @BeforeEach
    void repositories() throws Exception {
        destDir = tempDir.resolve("dest").toFile();
        dest = Hg.init().setDirectory(destDir).call();
        base = commit(dest, destDir, "a.txt", "a\n", "base");
        sourceDir = tempDir.resolve("source").toFile();
        source = Hg.init().setDirectory(sourceDir).call();
        new PullCommand(source).setSource(destDir.getAbsolutePath()).call();
        new UpdateCommand(source).setRevision(NodeIdUtil.toHex(base)).call();
        change = commit(source, sourceDir, "b.txt", "b\n", "change");
    }

    private File out() {
        return tempDir.resolve("out.hg").toFile();
    }

    @Test
    void theDestinationGivenDecidesWhatTheBundleHolds() throws Exception {
        int count = new BundleCommand(source).setOutputFile(out()).setDestination(destDir.getAbsolutePath()).call();
        assertEquals(1, count);
        // applying it to the destination brings exactly the changeset the destination lacks
        assertEquals(1, new UnbundleCommand(dest).setBundleFile(out()).call().size());
    }

    @Test
    void nothingToBundleWritesNoFile() throws Exception {
        new UnbundleCommand(dest).setBundleFile(new BundleCommandHelper().bundleAll(source, out())).call();
        File second = tempDir.resolve("second.hg").toFile();
        assertEquals(0, new BundleCommand(source).setOutputFile(second).setDestination(destDir.getAbsolutePath()).call());
        assertFalse(second.exists());
    }

    @Test
    void withoutBaseAllOrDestinationThePathsOfTheRepositoryAreUsed() throws Exception {
        Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(), "[paths]\ndefault = " + destDir.getAbsolutePath() + "\n");
        HgRepository reopened = new HgRepository(sourceDir);
        assertEquals(1, new BundleCommand(reopened).setOutputFile(out()).call());
    }

    @Test
    void defaultPushIsUsedBeforeDefault() throws Exception {
        File empty = tempDir.resolve("empty").toFile();
        Hg.init().setDirectory(empty).call();
        Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(),
                "[paths]\ndefault = " + destDir.getAbsolutePath() + "\ndefault-push = " + empty.getAbsolutePath() + "\n");
        // the empty default-push has nothing, so both changesets are outgoing
        assertEquals(2, new BundleCommand(new HgRepository(sourceDir)).setOutputFile(out()).call());
    }

    @Test
    void aPathNameOfTheConfigurationCanBeTheDestination() throws Exception {
        Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(), "[paths]\nupstream = " + destDir.getAbsolutePath() + "\n");
        assertEquals(1, new BundleCommand(new HgRepository(sourceDir)).setOutputFile(out()).setDestination("upstream").call());
    }

    @Test
    void withoutAnyDestinationItIsAConfigError() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new BundleCommand(source).setOutputFile(out()).call());
        assertTrue(e.getMessage().contains("default repository not configured!"), e.getMessage());
    }

    @Test
    void allIncludesEverythingAndIgnoresBase() throws Exception {
        assertEquals(2, new BundleCommand(source).setOutputFile(out()).setAll(true).call());
        // `--all --base X`: the base is ignored ("ignoring --base because --all was specified")
        assertEquals(2, new BundleCommand(source).setOutputFile(tempDir.resolve("o2.hg").toFile()).setAll(true)
                .setBaseRevision(NodeIdUtil.toHex(base)).call());
    }

    @Test
    void allAndBaseAreIncompatibleWithADestination() {
        IllegalStateException all = assertThrows(IllegalStateException.class, () -> new BundleCommand(source).setOutputFile(out())
                .setAll(true).setDestination(destDir.getAbsolutePath()).call());
        assertEquals("abort: --all is incompatible with specifying destinations", all.getMessage());
        IllegalStateException baseGiven = assertThrows(IllegalStateException.class, () -> new BundleCommand(source).setOutputFile(out())
                .setBaseRevision(NodeIdUtil.toHex(base)).setDestination(destDir.getAbsolutePath()).call());
        assertEquals("abort: --base is incompatible with specifying destinations", baseGiven.getMessage());
    }

    @Test
    void anUnrelatedDestinationIsRefusedUnlessForced() throws Exception {
        File other = tempDir.resolve("other").toFile();
        HgRepository unrelated = Hg.init().setDirectory(other).call();
        commit(unrelated, other, "x.txt", "x\n", "unrelated");
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> new BundleCommand(source).setOutputFile(out()).setDestination(other.getAbsolutePath()).call());
        assertEquals("repository is unrelated", e.getMessage());
        assertEquals(2, new BundleCommand(source).setOutputFile(out()).setDestination(other.getAbsolutePath()).setForce(true).call());
    }

    @Test
    void theRevisionLimitsWhatIsOutgoing() throws Exception {
        byte[] second = commit(source, sourceDir, "c.txt", "c\n", "second");
        assertEquals(1, new BundleCommand(source).setOutputFile(out()).setDestination(destDir.getAbsolutePath())
                .setRevision(NodeIdUtil.toHex(change)).call());
        assertEquals(2, new BundleCommand(source).setOutputFile(tempDir.resolve("both.hg").toFile())
                .setDestination(destDir.getAbsolutePath()).setRevision(NodeIdUtil.toHex(second)).call());
    }

    /** Writes a bundle of the whole repository and returns the file. */
    private static final class BundleCommandHelper {
        File bundleAll(HgRepository repo, File file) throws Exception {
            new BundleCommand(repo).setOutputFile(file).setAll(true).call();
            return file;
        }
    }
}
