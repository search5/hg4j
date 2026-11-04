package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code admin::chainsaw-update}: the locks are broken first, then wlock and lock are held for recovery, pull, purge and update. */
class ChainsawUpdateCommandTest {

    @TempDir
    Path tempDir;

    private File sourceDir;
    private final String[] nodes = new String[3];

    @BeforeEach
    void source() throws Exception {
        sourceDir = tempDir.resolve("source").toFile();
        try (HgRepository src = Hg.init().setDirectory(sourceDir).call()) {
            for (int i = 0; i < 3; i++) {
                Files.writeString(new File(sourceDir, "f.txt").toPath(), "v" + i + "\n");
                new AddCommand(src).call();
                nodes[i] = NodeIdUtil.toHex(new CommitCommand(src).setMessage("c" + i).setAuthor("t").call());
            }
        }
    }

    private ChainsawUpdateCommand chainsaw(File dest, String rev) {
        return new ChainsawUpdateCommand().setRevision(rev).setSource(sourceDir.getAbsolutePath()).setDestination(dest.getAbsolutePath());
    }

    private static int revisions(File dir) throws Exception {
        try (HgRepository r = new HgRepository(dir)) {
            return r.getRevlog(new File(r.getStoreDir(), "00changelog.i"), new File(r.getStoreDir(), "00changelog.d")).getRevisionCount();
        }
    }

    @Test
    void aMissingDestinationIsCreatedPulledInFullAndUpdated() throws Exception {
        File dest = tempDir.resolve("dest").toFile();
        ChainsawUpdateCommand command = chainsaw(dest, nodes[1]);
        command.call();
        assertEquals(List.of("no such directory: \"" + dest.getAbsolutePath() + "\"\n", "creating repository at \"" + dest.getAbsolutePath() + "\"\n",
                "recovering after interrupted transaction, if any\n", "pulling from " + sourceDir.getAbsolutePath() + "\n",
                "updating to revision '" + nodes[1] + "'\n",
                "chainsaw-update to revision '" + nodes[1] + "' for repository at '" + dest.getAbsolutePath() + "' done\n"), command.getMessages());
        assertEquals(3, revisions(dest), "the first clone is full by default");
        assertEquals("v1\n", Files.readString(new File(dest, "f.txt").toPath()));
    }

    @Test
    void theMinimalInitialCloneOnlyPullsTheRevision() throws Exception {
        File dest = tempDir.resolve("minimal").toFile();
        chainsaw(dest, nodes[1]).setInitialCloneMinimal(true).call();
        assertEquals(2, revisions(dest));
        assertEquals("v1\n", Files.readString(new File(dest, "f.txt").toPath()));
    }

    @Test
    void aNonRepositoryDirectoryAtTheDestinationIsRemoved() throws Exception {
        File dest = tempDir.resolve("junk").toFile();
        dest.mkdirs();
        Files.writeString(new File(dest, "precious.txt").toPath(), "gone\n");
        ChainsawUpdateCommand command = chainsaw(dest, nodes[2]);
        command.call();
        assertEquals("removed non-repository file or directory at \"" + dest.getAbsolutePath() + "\"", command.getMessages().get(0));
        assertFalse(new File(dest, "precious.txt").exists());
        assertEquals("v2\n", Files.readString(new File(dest, "f.txt").toPath()));
    }

    @Test
    void leftOverLocksAreBrokenBeforeAnythingIsTaken() throws Exception {
        File dest = tempDir.resolve("locked").toFile();
        chainsaw(dest, nodes[0]).call();
        // locks left by a process that is gone, as a CI job killed in the middle leaves them
        Files.createSymbolicLink(new File(dest, ".hg/wlock").toPath(), Path.of("somehost:999999"));
        Files.createSymbolicLink(new File(dest, ".hg/store/lock").toPath(), Path.of("somehost:999999"));
        ChainsawUpdateCommand command = chainsaw(dest, nodes[2]);
        command.call();
        List<String> messages = command.getMessages();
        assertEquals("loaded repository at \"" + dest.getAbsolutePath() + "\"\n", messages.get(0));
        assertEquals("had to break store lock\n", messages.get(1));
        assertEquals("had to break working copy lock\n", messages.get(2));
        assertEquals("recovering after interrupted transaction, if any\n", messages.get(3));
        assertEquals("v2\n", Files.readString(new File(dest, "f.txt").toPath()));
        assertFalse(new File(dest, ".hg/wlock").exists(), "released at the end");
        assertFalse(new File(dest, ".hg/store/lock").exists());
    }

    @Test
    void localChangesUnknownAndIgnoredFilesAreAllThrownAway() throws Exception {
        File dest = tempDir.resolve("dirty").toFile();
        chainsaw(dest, nodes[2]).call();
        Files.writeString(new File(dest, "f.txt").toPath(), "edited locally\n");
        Files.writeString(new File(dest, "unknown.txt").toPath(), "u\n");
        Files.writeString(new File(dest, ".hgignore").toPath(), "ignored.o\n");
        Files.writeString(new File(dest, "ignored.o").toPath(), "o\n");
        new File(dest, "emptydir").mkdirs();
        chainsaw(dest, nodes[1]).call();
        assertEquals("v1\n", Files.readString(new File(dest, "f.txt").toPath()));
        assertFalse(new File(dest, "unknown.txt").exists());
        assertFalse(new File(dest, "ignored.o").exists());
        assertFalse(new File(dest, "emptydir").exists());
    }

    @Test
    void keepingTheIgnoredFilesWhenAsked() throws Exception {
        File dest = tempDir.resolve("keep").toFile();
        chainsaw(dest, nodes[2]).call();
        Files.writeString(new File(dest, ".hgignore").toPath(), "ignored.o\n");
        Files.writeString(new File(dest, "ignored.o").toPath(), "o\n");
        Files.writeString(new File(dest, "unknown.txt").toPath(), "u\n");
        chainsaw(dest, nodes[2]).setPurgeIgnored(false).call();
        assertTrue(new File(dest, "ignored.o").exists(), "--no-purge-ignored keeps the object files");
        assertFalse(new File(dest, "unknown.txt").exists());
    }

    @Test
    void missingArgumentsAreRefusedWithHgsMessages() {
        assertEquals("specify a target revision with --rev",
                assertThrows(HgValidationException.class, () -> new ChainsawUpdateCommand().setSource("s").setDestination("d").call()).getMessage());
        assertEquals("specify a pull path with --source",
                assertThrows(HgValidationException.class, () -> new ChainsawUpdateCommand().setRevision("r").setDestination("d").call()).getMessage());
        assertEquals("specify a repo path with --dest",
                assertThrows(HgValidationException.class, () -> new ChainsawUpdateCommand().setRevision("r").setSource("s").call()).getMessage());
    }
}
