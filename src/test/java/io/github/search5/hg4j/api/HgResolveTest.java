package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HgResolveTest {

    @TempDir
    File tempDir;

    @Test
    public void testConflictResolveStateManagement() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            // Run ResolveCommand and mark as unresolved status
            Map<String, Boolean> states = hg.resolve()
                    .setFile("conflict.txt")
                    .markUnresolved(true)
                    .call();

            assertNotNull(states);
            assertEquals(1, states.size());
            assertFalse(states.get("conflict.txt")); // unresolved -> false

            // mark as resolved
            Map<String, Boolean> statesResolved = hg.resolve()
                    .setFile("conflict.txt")
                    .markResolved(true)
                    .call();

            assertTrue(statesResolved.get("conflict.txt")); // resolved -> true

            // Read list
            Map<String, Boolean> listStates = hg.resolve().list(true).call();
            assertEquals(1, listStates.size());
            assertTrue(listStates.get("conflict.txt"));
        }
    }

    @Test
    public void testResolveStateStandardFormattingAndListing() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            // mark unresolved
            hg.resolve().setFile("a.txt").markUnresolved(true).call();
            // mark resolved
            hg.resolve().setFile("b.txt").markResolved(true).call();

            // verify that file is written standardly (.hg/merge/state)
            File stateFile = new File(tempDir, ".hg/merge/state");
            assertTrue(stateFile.exists());
            
            String content = java.nio.file.Files.readString(stateFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // first two lines are parent hex hashes (typically 40 zeros in fresh repo)
            assertEquals(40, lines[0].length());
            assertEquals(40, lines[1].length());
            
            // remaining lines should be in "u/r path" standard format
            assertEquals("u a.txt", lines[2]);
            assertEquals("r b.txt", lines[3]);

            // test list(false) returns only the targeted file's status
            Map<String, Boolean> targeted = hg.resolve().setFile("a.txt").markResolved(true).list(false).call();
            assertEquals(1, targeted.size());
            assertTrue(targeted.get("a.txt"));

            // test list(true) returns all states
            Map<String, Boolean> allStates = hg.resolve().list(true).call();
            assertEquals(2, allStates.size());
            assertTrue(allStates.get("a.txt"));
            assertTrue(allStates.get("b.txt"));
        }
    }
}
