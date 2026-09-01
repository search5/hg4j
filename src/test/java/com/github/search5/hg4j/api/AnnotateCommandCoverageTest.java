package com.github.search5.hg4j.api;

import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link AnnotateCommand}, targeting branches not exercised by
 * {@link HgAnnotateTest}: constructor validation, missing-path validation, missing-filelog
 * handling, explicit revision selection, out-of-range revision handling, and the huge-file
 * fallback (copy-forward) path used when the O(n*m) LCS table would be too large.
 */
public class AnnotateCommandCoverageTest {

    @TempDir
    File tempDir;

    @Test
    public void testNullRepositoryRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AnnotateCommand(null));
        assertEquals("Repository cannot be null", ex.getMessage());
    }

    @Test
    public void testPathNotSpecifiedThrowsIllegalState() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            AnnotateCommand cmd = hg.annotate();
            IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
            assertEquals("Path must be specified.", ex.getMessage());
        }
    }

    @Test
    public void testMissingFilelogThrowsRevisionNotFound() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            // Repo initialized but the file was never added/committed, so no filelog exists.
            assertThrows(HgRevisionNotFoundException.class,
                    () -> hg.annotate().setPath("never-existed.txt").call());
        }
    }

    @Test
    public void testExplicitRevisionSelectsHistoricalSnapshotNotLatest() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");

            Files.writeString(file.toPath(), "Alice Line\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            Files.writeString(file.toPath(), "Alice Line\nBob Line\n");
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("rev1").call();

            // Explicitly pin to revision 0, even though a later revision (1) exists.
            List<AnnotateCommand.BlameLine> atRev0 = hg.annotate()
                    .setPath("blame.txt")
                    .setRevision(0)
                    .call();

            assertEquals(2, atRev0.size()); // "Alice Line" + trailing empty split
            assertEquals("Alice Line", atRev0.get(0).getContent());
            assertEquals(0, atRev0.get(0).getRevision());
            assertEquals("Alice <alice@example.com>", atRev0.get(0).getAuthor());

            // Default (-1) picks up the latest revision, which differs from the pinned one.
            List<AnnotateCommand.BlameLine> atLatest = hg.annotate()
                    .setPath("blame.txt")
                    .call();
            assertEquals(3, atLatest.size());
            assertEquals("Bob Line", atLatest.get(1).getContent());
            assertEquals(1, atLatest.get(1).getRevision());
        }
    }

    @Test
    public void testRevisionBeyondRangeReturnsEmptyList() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");
            Files.writeString(file.toPath(), "only line\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .setRevision(99)
                    .call();

            assertNotNull(lines);
            assertTrue(lines.isEmpty());
        }
    }

    @Test
    public void testNegativeRevisionOtherThanDefaultReturnsEmptyList() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");
            Files.writeString(file.toPath(), "only line\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            // -1 is the sentinel for "latest"; any other negative value is out of range.
            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .setRevision(-2)
                    .call();

            assertNotNull(lines);
            assertTrue(lines.isEmpty());
        }
    }

    @Test
    public void testHugeFileTransitionUsesCopyForwardFallback() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "big.txt");

            // The DP-based LCS diff is skipped in favor of a plain copy-forward attribution
            // whenever prevLineCount * currLineCount exceeds 2,000,000 (see AnnotateCommand's
            // huge-file guard). Use two entirely disjoint sets of 1500 lines each so the whole
            // second revision is attributed to revision 1, and the product (~2.25M) safely
            // clears the threshold regardless of the +/-1 from the trailing split segment.
            StringBuilder rev0 = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                rev0.append("orig-line-").append(i).append('\n');
            }
            Files.writeString(file.toPath(), rev0.toString());
            hg.add().addFile("big.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            StringBuilder rev1 = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                rev1.append("new-line-").append(i).append('\n');
            }
            Files.writeString(file.toPath(), rev1.toString());
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("rev1").call();

            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("big.txt")
                    .call();

            assertNotNull(lines);
            assertFalse(lines.isEmpty());
            // Every retained line must be attributed to the newest revision (the fallback
            // copy-forward path attributes the whole file to the revision being applied).
            List<Integer> distinctRevisions = lines.stream()
                    .map(AnnotateCommand.BlameLine::getRevision)
                    .distinct()
                    .collect(Collectors.toList());
            assertEquals(List.of(1), distinctRevisions);
            assertEquals("Bob <bob@example.com>", lines.get(0).getAuthor());
            assertEquals("new-line-0", lines.get(0).getContent());
        }
    }

    @Test
    public void testPureInsertionKeepsOriginalLinesAttributedToOriginalRevision() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");

            Files.writeString(file.toPath(), "A\nB\nC\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            // Pure insertion: no existing line changed or removed, a new one added in the middle.
            Files.writeString(file.toPath(), "A\nB\nX\nC\n");
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("rev1 insert").call();

            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .call();

            assertEquals(5, lines.size()); // A,B,X,C + trailing empty
            assertEquals("A", lines.get(0).getContent());
            assertEquals(0, lines.get(0).getRevision());
            assertEquals("B", lines.get(1).getContent());
            assertEquals(0, lines.get(1).getRevision());
            assertEquals("X", lines.get(2).getContent());
            assertEquals(1, lines.get(2).getRevision());
            assertEquals("C", lines.get(3).getContent());
            assertEquals(0, lines.get(3).getRevision());
        }
    }

    @Test
    public void testPureDeletionRetainsAttributionOfSurvivingLines() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");

            Files.writeString(file.toPath(), "A\nB\nC\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            // Pure deletion: remove the middle line, no insertions or modifications elsewhere.
            Files.writeString(file.toPath(), "A\nC\n");
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("rev1 delete").call();

            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .call();

            assertEquals(3, lines.size()); // A,C + trailing empty
            assertEquals("A", lines.get(0).getContent());
            assertEquals(0, lines.get(0).getRevision());
            assertEquals("C", lines.get(1).getContent());
            assertEquals(0, lines.get(1).getRevision());
        }
    }

    @Test
    public void testLinesPrependedBeforeAllOriginalContent() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");

            // No trailing newline: split("\n", -1) yields exactly {"A","B","C"} with no
            // synthetic trailing empty element, so the backward LCS backtrack fully consumes
            // the original lines (index reaches 0) while new lines still remain unmatched at
            // the front -- exercising the i==0-but-j>0 branch of the backtrack loop.
            Files.writeString(file.toPath(), "A\nB\nC");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("rev0").call();

            Files.writeString(file.toPath(), "X\nY\nA\nB\nC");
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("rev1 prepend").call();

            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .call();

            assertEquals(5, lines.size());
            assertEquals("X", lines.get(0).getContent());
            assertEquals(1, lines.get(0).getRevision());
            assertEquals("Y", lines.get(1).getContent());
            assertEquals(1, lines.get(1).getRevision());
            assertEquals("A", lines.get(2).getContent());
            assertEquals(0, lines.get(2).getRevision());
            assertEquals("B", lines.get(3).getContent());
            assertEquals(0, lines.get(3).getRevision());
            assertEquals("C", lines.get(4).getContent());
            assertEquals(0, lines.get(4).getRevision());
        }
    }
}
