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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code hg bundle FILE [DEST]} against the real {@code hg}: the same changesets in the same bundle bytes. */
@Tag("interop")
class BundleDestinationRealHgInteropTest {

    private static final String DATE = "1700000000 0";

    @TempDir
    Path tempDir;

    private File dest;
    private File source;

    private void commit(File dir, String file, String text, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), text);
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "t", "-d", DATE);
    }

    /** dest has base; source cloned from it has two more changesets. */
    @BeforeEach
    void repositories() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        dest = tempDir.resolve("dest").toFile();
        dest.mkdirs();
        HgTestUtils.hg(dest, "init");
        commit(dest, "a.txt", "a\n", "base");
        source = tempDir.resolve("source").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-q", dest.getAbsolutePath(), source.getAbsolutePath());
        commit(source, "b.txt", "b\n", "one");
        commit(source, "a.txt", "a2\n", "two");
    }

    private byte[] ours(BundleCommand command) throws Exception {
        File out = tempDir.resolve("ours.hg").toFile();
        Files.deleteIfExists(out.toPath());
        command.setOutputFile(out).call();
        return Files.readAllBytes(out.toPath());
    }

    @Test
    void theDestinationGivenGivesTheSameBundle() throws Exception {
        File out = tempDir.resolve("real.hg").toFile();
        HgTestUtils.hg(source, "bundle", "-t", "none-v1", out.getAbsolutePath(), dest.getAbsolutePath());
        assertArrayEquals(Files.readAllBytes(out.toPath()),
                ours(new BundleCommand(new HgRepository(source)).setDestination(dest.getAbsolutePath())));
    }

    @Test
    void theDefaultPathOfTheRepositoryIsTheDestinationWhenNoneIsGiven() throws Exception {
        // `hg clone` wrote [paths] default = dest
        File out = tempDir.resolve("real.hg").toFile();
        HgTestUtils.hg(source, "bundle", "-t", "none-v1", out.getAbsolutePath());
        assertArrayEquals(Files.readAllBytes(out.toPath()), ours(new BundleCommand(new HgRepository(source))));
    }

    @Test
    void aRevisionLimitsTheBundleLikeTheRealHg() throws Exception {
        File out = tempDir.resolve("real.hg").toFile();
        HgTestUtils.hg(source, "bundle", "-t", "none-v1", "-r", "1", out.getAbsolutePath(), dest.getAbsolutePath());
        assertArrayEquals(Files.readAllBytes(out.toPath()),
                ours(new BundleCommand(new HgRepository(source)).setDestination(dest.getAbsolutePath()).setRevision("1")));
    }

    @Test
    void allGivesTheSameBundleAndIgnoresBase() throws Exception {
        File out = tempDir.resolve("real.hg").toFile();
        HgTestUtils.hg(source, "bundle", "-t", "none-v1", "--all", out.getAbsolutePath());
        assertArrayEquals(Files.readAllBytes(out.toPath()), ours(new BundleCommand(new HgRepository(source)).setAll(true)));
        assertEquals(3, new BundleCommand(new HgRepository(source)).setOutputFile(tempDir.resolve("o.hg").toFile()).setAll(true)
                .setBaseRevision("0").call());
    }
}
