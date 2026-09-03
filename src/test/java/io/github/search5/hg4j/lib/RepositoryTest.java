package io.github.search5.hg4j.lib;

import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.api.Hg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Repository#open(File)}, the static factory not otherwise exercised as a
 * direct entry point elsewhere in the suite.
 */
public class RepositoryTest {

    @Test
    public void openThrowsForNullDirectory() {
        assertThrows(IllegalArgumentException.class, () -> Repository.open(null));
    }

    @Test
    public void openThrowsWhenNoHgDirectoryExists(@TempDir Path tempDir) {
        assertThrows(HgRepositoryNotFoundException.class, () -> Repository.open(tempDir.toFile()));
    }

    /**
     * {@code .hg} existing but being a plain file (not a directory) is a distinct branch from
     * "doesn't exist at all" -- {@code !hgDir.isDirectory()} only matters once
     * {@code hgDir.exists()} is already true.
     */
    @Test
    public void openThrowsWhenHgPathIsAnExistingPlainFile(@TempDir Path tempDir) throws Exception {
        File hgAsFile = new File(tempDir.toFile(), ".hg");
        Files.writeString(hgAsFile.toPath(), "not a directory");

        assertThrows(HgRepositoryNotFoundException.class, () -> Repository.open(tempDir.toFile()));
    }

    @Test
    public void openSucceedsForARealRepository(@TempDir Path tempDir) throws Exception {
        Hg.init().setDirectory(tempDir.toFile()).call();

        try (Repository repo = Repository.open(tempDir.toFile())) {
            assertNotNull(repo);
            assertEquals(tempDir.toFile().getCanonicalFile(), repo.getDirectory().getCanonicalFile());
        }
    }
}
