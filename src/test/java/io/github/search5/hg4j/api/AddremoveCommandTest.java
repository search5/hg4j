package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AddremoveCommandTest {

    /**
     * An already-tracked, untouched file must not be re-added -- {@code call()}'s
     * {@code !dirstate.getEntries().containsKey(relPath)} guard must take its "already tracked"
     * (false) branch for it, distinct from {@link TrackCMissingCommandsInteropTest}'s scenario
     * where every scanned file is genuinely new.
     */
    @Test
    public void doesNotReAddAnAlreadyTrackedUntouchedFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "tracked.txt").toPath(), "already committed");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("init").call();

        Files.writeString(new File(tempDir.toFile(), "new.txt").toPath(), "brand new");

        List<String> affected = new AddremoveCommand(repo).call();

        assertEquals(List.of("A new.txt"), affected);
    }

    /**
     * A file already marked removed ('r') before {@code call()} runs must be skipped
     * ({@code state == 'r' -> continue}), not re-processed into the affected list a second time.
     */
    @Test
    public void skipsAFileAlreadyMarkedRemoved(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File gone = new File(tempDir.toFile(), "gone.txt");
        Files.writeString(gone.toPath(), "will be deleted");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("init").call();

        Files.delete(gone.toPath());

        List<String> firstPass = new AddremoveCommand(repo).call();
        assertEquals(List.of("R gone.txt"), firstPass);

        // Second pass: gone.txt is already state 'r' in the dirstate snapshot, so it must be
        // skipped via the `continue`, not reported again.
        List<String> secondPass = new AddremoveCommand(repo).call();
        assertTrue(secondPass.isEmpty(), "already-removed entry must not be reported again: " + secondPass);
    }
}
