package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requirements that hg4j writes itself (or that real hg writes for shared repositories) must not make {@code Hg.open} refuse the repository. */
class HgOpenWrittenRequirementsTest {

    @TempDir
    Path tempDir;

    private static boolean refusedAsUnsupported(File dir) throws Exception {
        try {
            Hg.open(dir).close();
            return false;
        } catch (HgValidationException e) {
            return e.getMessage().contains("unsupported repository requirement");
        } catch (Exception other) {
            // a different failure (for example a missing sharedpath) is not the requirement check
            return false;
        }
    }

    private File repositoryWith(String requirement) throws Exception {
        File dir = tempDir.resolve(requirement).toFile();
        Hg.init().setDirectory(dir).call().close();
        Files.writeString(new File(dir, ".hg/requires").toPath(), requirement + "\n", StandardOpenOption.APPEND);
        return dir;
    }

    @Test
    void lfsIsAccepted() throws Exception {
        File dir = repositoryWith("lfs");
        Hg.open(dir).close();
    }

    @Test
    void sharedAndRelsharedAreNotRefusedAsUnsupported() throws Exception {
        assertFalse(refusedAsUnsupported(repositoryWith("shared")));
        assertFalse(refusedAsUnsupported(repositoryWith("relshared")));
    }

    @Test
    void anUnknownRequirementIsStillRefused() throws Exception {
        assertTrue(refusedAsUnsupported(repositoryWith("no-such-requirement")));
    }
}
