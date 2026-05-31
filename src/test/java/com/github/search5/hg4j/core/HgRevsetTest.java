package com.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgRevsetTest {

    @TempDir
    File tempDir;

    @Test
    public void testRevsetEngineValidation() throws Exception {
        // 1. Initialize repository
        com.github.search5.hg4j.core.HgRepository repo = com.github.search5.hg4j.api.Hg.init().setDirectory(tempDir).call();
        
        try (com.github.search5.hg4j.api.Hg hg = com.github.search5.hg4j.api.Hg.wrap(repo)) {
            // Write some revisions
            File file = new File(tempDir, "a.txt");
            Files.writeString(file.toPath(), "Revision 1 text");
            hg.add().addFile("a.txt").call();
            byte[] c1 = hg.commit().setAuthor("Developer Alice").setMessage("First commit").call();
            
            Files.writeString(file.toPath(), "Revision 2 text");
            hg.add().addFile("a.txt").call();
            byte[] c2 = hg.commit().setAuthor("Developer Bob").setMessage("Second commit").call();

            HgRevsetEngine engine = new HgRevsetEngine(repo);

            // Test Single Revision match by int and Hex NodeId
            List<Integer> rev0 = engine.query("0");
            assertEquals(1, rev0.size());
            assertEquals(0, rev0.get(0));

            List<Integer> rev1Hex = engine.query(NodeIdUtil.toHex(c2));
            assertEquals(1, rev1Hex.size());
            assertEquals(1, rev1Hex.get(0));

            // Test author match
            List<Integer> aliceRevs = engine.query("author(\"Alice\")");
            assertEquals(1, aliceRevs.size());
            assertEquals(0, aliceRevs.get(0));

            List<Integer> bobRevs = engine.query("author(Bob)");
            assertEquals(1, bobRevs.size());
            assertEquals(1, bobRevs.get(0));

            // Test parents match
            List<Integer> parentOf1 = engine.query("parents(1)");
            assertEquals(1, parentOf1.size());
            assertEquals(0, parentOf1.get(0));

            // Test Logical AND
            List<Integer> andResult = engine.query("author(Alice) and author(Bob)");
            assertTrue(andResult.isEmpty());

            List<Integer> andResultSelf = engine.query("0 and author(Alice)");
            assertEquals(1, andResultSelf.size());
            assertEquals(0, andResultSelf.get(0));

            // Test Logical OR
            List<Integer> orResult = engine.query("author(Alice) or author(Bob)");
            assertEquals(2, orResult.size());
            assertTrue(orResult.contains(0));
            assertTrue(orResult.contains(1));

            // Test empty query
            assertTrue(engine.query("").isEmpty());
            assertTrue(engine.query(null).isEmpty());
        }
    }
}
