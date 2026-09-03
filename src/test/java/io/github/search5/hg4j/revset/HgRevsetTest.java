package io.github.search5.hg4j.revset;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.BookmarkCommand;
import io.github.search5.hg4j.api.BranchCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.MergeCommand;
import io.github.search5.hg4j.api.PhaseCommand;
import io.github.search5.hg4j.api.TagCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import java.util.TreeSet;

public class HgRevsetTest {

    @TempDir
    File tempDir;

    @Test
    public void testRevsetEngineValidation() throws Exception {
        // 1. Initialize repository
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
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

    @Test
    public void testRevsetEngineDagAndMetadataFunctions() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        File a = new File(tempDir, "a.txt");
        Files.writeString(a.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] c0 = new CommitCommand(repo)
                .setAuthor("Alice <alice@example.com>").setMessage("initial commit").call();

        Files.writeString(a.toPath(), "v2");
        byte[] c1 = new CommitCommand(repo)
                .setAuthor("Bob <bob@example.com>").setMessage("second commit").call();

        // Fork a second child of c0 on a named branch.
        new UpdateCommand(repo).setRevision("0").call();
        new BranchCommand(repo).setBranchName("feature").call();
        File b = new File(tempDir, "b.txt");
        Files.writeString(b.toPath(), "feature content");
        new AddCommand(repo).addFile("b.txt").call();
        byte[] c2 = new CommitCommand(repo)
                .setAuthor("Alice <alice@example.com>").setMessage("feature commit with a special keyword").call();

        // Merge the feature branch back into default (parent1=c1, parent2=c2).
        // Note: UpdateCommand does not currently restore the working branch to match
        // the target revision (unlike real hg), so it is set explicitly here.
        new UpdateCommand(repo).setRevision("1").call();
        new BranchCommand(repo).setBranchName("default").call();
        new MergeCommand(repo).setRevision(2).call();
        new CommitCommand(repo)
                .setAuthor("Bob <bob@example.com>").setMessage("merge feature into default").call();

        new TagCommand(repo).setTagName("v1.0").setNodeId(c1).setCommit(false).call();
        new BookmarkCommand(repo).setBookmarkName("mywork").setNodeId(c2).call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        assertEquals(List.of(3), engine.query("heads()"));
        assertEquals(List.of(3), engine.query("merge()"));
        assertEquals(List.of(0, 1, 2, 3), engine.query("descendants(0)"));
        assertEquals(List.of(0, 1, 2, 3), engine.query("ancestors(3)"));
        assertEquals(new TreeSet<>(List.of(1, 2)), new TreeSet<>(engine.query("children(0)")));
        assertEquals(List.of(1, 2), engine.query("parents(3)"));
        assertEquals(List.of(1), engine.query("tag(\"v1.0\")"));
        assertEquals(List.of(2), engine.query("bookmark(\"mywork\")"));
        assertEquals(List.of(2), engine.query("keyword(\"special\")"));
        assertEquals(List.of(2), engine.query("file(\"b.txt\")"));
        assertEquals(List.of(2), engine.query("branch(\"feature\")"));
        assertEquals(new TreeSet<>(List.of(0, 1, 3)), new TreeSet<>(engine.query("branch(\"default\")")));
        assertEquals(List.of(2), engine.query("not branch(\"default\")"));
        assertEquals(4, engine.query("all()").size());
        assertEquals(4, engine.query("all").size());

        // All freshly committed revisions default to the draft phase.
        assertEquals(List.of(0, 1, 2, 3), engine.query("draft()"));
        new PhaseCommand(repo).setRevision("0").setPhase(0).call(); // PUBLIC
        assertEquals(List.of(0), engine.query("public()"));
        new PhaseCommand(repo).setRevision("3").setPhase(2).call(); // SECRET
        assertEquals(List.of(3), engine.query("secret()"));

        List<Integer> byAuthor = engine.query("sort(descendants(0), \"author\")");
        assertEquals(4, byAuthor.size());
        assertTrue(byAuthor.indexOf(0) < byAuthor.indexOf(1), "Alice's rev0 should sort before Bob's rev1");
        assertTrue(byAuthor.indexOf(2) < byAuthor.indexOf(1), "Alice's rev2 should sort before Bob's rev1");

        assertEquals(List.of(1, 2), engine.query("sort(children(0), \"rev\")"));
        assertEquals(2, engine.query("sort(children(0), \"date\")").size());

        assertEquals(List.of(0, 1), engine.query("limit(descendants(0), 2)"));

        List<Integer> userBob = engine.query("user(Bob)");
        assertEquals(new TreeSet<>(List.of(1, 3)), new TreeSet<>(userBob));
    }
}
