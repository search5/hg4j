package org.hg4j.api;

import org.hg4j.core.HgRepository;
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
            // ResolveCommand를 기동하여 unresolved 상태 마킹
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
}
