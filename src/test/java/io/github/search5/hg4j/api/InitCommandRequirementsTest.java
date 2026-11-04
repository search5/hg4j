package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The optional store formats {@link InitCommand} can switch on, and the combination it refuses. */
class InitCommandRequirementsTest {

    @TempDir
    Path tempDir;

    private List<String> requirementsOf(InitCommand init) throws Exception {
        init.setDirectory(tempDir.resolve("repo").toFile()).call().close();
        return Files.readAllLines(tempDir.resolve("repo/.hg/requires"));
    }

    @Test
    void theFileIndexImpliesThePersistentNodemap() throws Exception {
        List<String> requires = requirementsOf(new InitCommand().setFileIndexV1(true));
        assertTrue(requires.contains("fileindex-v1"), requires.toString());
        assertTrue(requires.contains("persistent-nodemap"), requires.toString());
    }

    @Test
    void theGeneralV2FormatImpliesTheFileIndexToo() throws Exception {
        List<String> requires = requirementsOf(new InitCommand().setGeneralV2(true));
        assertTrue(requires.contains("exp-revlogv2.2"), requires.toString());
        assertTrue(requires.contains("fileindex-v1"), requires.toString());
        assertTrue(requires.contains("persistent-nodemap"), requires.toString());
    }

    @Test
    void treemanifestAndTheFileIndexExcludeEachOther() {
        HgValidationException fileIndex = assertThrows(HgValidationException.class,
                () -> new InitCommand().setDirectory(tempDir.resolve("a").toFile()).setTreemanifest(true).setFileIndexV1(true).call());
        assertTrue(fileIndex.getMessage().contains("format.use-fileindex-v1"), fileIndex.getMessage());
        HgValidationException general = assertThrows(HgValidationException.class,
                () -> new InitCommand().setDirectory(tempDir.resolve("b").toFile()).setTreemanifest(true).setGeneralV2(true).call());
        assertTrue(general.getMessage().contains("experimental.revlogv2"), general.getMessage());
    }
}
