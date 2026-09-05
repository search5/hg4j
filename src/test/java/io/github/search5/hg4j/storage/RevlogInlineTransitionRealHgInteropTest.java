package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CloneCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog #43 -- real hg's {@code revlog.py} {@code _enforceinlinesize()} converts an inline v1
 * revlog (backlog #35: hg4j's new-revlog default) to the separate-{@code .i}/{@code .d} layout
 * once its cumulative data crosses {@code _maxinline} (131072 bytes). Live-tested against real hg
 * 7.2 directly (not just source reading): a repo committing successively larger blobs
 * (10000/30000/50000/70000 bytes) keeps {@code f.bin} inline through the first three commits and
 * splits into a {@code .d} file right after the fourth (cumulative 160000 bytes, crossing the
 * threshold); a SINGLE first commit whose content alone already exceeds 131072 bytes splits
 * immediately rather than ever landing on disk as an oversized inline revlog. Real hg's read path
 * doesn't care about the on-disk layout's size at all (it only branches on the inline format-flag
 * bit read from revision 0's header), so a hg4j-written revlog that never performed this
 * conversion (the pre-fix bug) was a real-hg-COMPATIBILITY gap (an oversized inline revlog is
 * something a byte-perfect-compatible implementation must never produce, and it can also throw off
 * tools/heuristics tuned around real hg's own size expectations), not a data-corruption bug --
 * real hg's own {@code hg verify}/{@code hg cat}/{@code hg clone} against such an
 * (artificially-produced) file were confirmed to still succeed. See the class-level comment in
 * {@link Revlog#splitInlineToNonInline()}'s call site ({@link Revlog#enforceInlineSize(int)}).
 */
@Tag("interop")
public class RevlogInlineTransitionRealHgInteropTest {

    private static final int[] GROWING_SIZES = {10000, 30000, 50000, 70000};

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * hg4j commits a file whose content grows across commits until the cumulative inline data
     * crosses 131072 bytes. Real hg's own {@code hg verify} and {@code hg debugindex} on the
     * resulting repository must be clean and must show the same revision-count/layout real hg
     * itself would have produced (split .d file after the 4th commit, non-inline .i shrunk to
     * {@code revCount * 64} bytes) -- confirmed byte-for-byte against the live real-hg 7.2
     * reproduction in llm-wiki backlog #43.
     */
    @Test
    public void hg4jCommittingAGrowingFileTransitionsToNonInlineJustLikeRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File dataFile = new File(repoDir, "f.bin");
        Random rnd = new Random(43);
        List<byte[]> contents = new ArrayList<>();
        for (int i = 0; i < GROWING_SIZES.length; i++) {
            byte[] content = new byte[GROWING_SIZES[i]];
            rnd.nextBytes(content);
            contents.add(content);
            Files.write(dataFile.toPath(), content);
            new AddCommand(repo).call();
            new CommitCommand(repo).setAuthor("hg4j").setMessage("size " + GROWING_SIZES[i]).call();
        }

        File filelogIdx = new File(new File(repoDir, ".hg/store/data"), "f.bin.i");
        File filelogDat = new File(new File(repoDir, ".hg/store/data"), "f.bin.d");
        Revlog readBack = new Revlog(filelogIdx, filelogDat);
        assertFalse(readBack.isInline(),
                "cumulative data (160000 bytes across 4 commits) crossed 131072 -- the filelog must "
                        + "have transitioned to non-inline, matching real hg 7.2's own live-tested behavior");
        assertEquals((long) GROWING_SIZES.length * 64L, filelogIdx.length(),
                "post-transition .i file must hold only fixed 64-byte records");
        assertTrue(filelogDat.exists() && filelogDat.length() > 0);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors: " + verify);
        assertFalse(verify.contains("not in fncache"), "real hg verify must not flag f.bin: " + verify);

        String debugIndex = HgTestUtils.hg(repoDir, "debugindex", "f.bin");
        String[] lines = debugIndex.trim().split("\n");
        assertEquals(GROWING_SIZES.length + 1, lines.length, // +1 header line
                "hg debugindex must report exactly " + GROWING_SIZES.length + " revisions: " + debugIndex);

        for (int i = 0; i < contents.size(); i++) {
            assertArrayEquals(contents.get(i), readBack.getRevisionContent(i),
                    "hg4j's own readback of revision " + i + " must match what was committed");
        }
    }

    /**
     * Real hg itself produces the growing-file repository (so the on-disk transition is 100%
     * real-hg-authored, not hg4j's), and hg4j clones it -- confirming hg4j's READ path already
     * correctly handles a real, non-inline (post-transition) revlog regardless of how it came to
     * be split, and every revision's content round-trips correctly.
     */
    @Test
    public void hg4jClonesARealHgProducedRevlogThatAlreadyTransitionedToNonInline(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("realhg-source").toFile();
        assertTrue(sourceDir.mkdirs());
        HgTestUtils.hg(tempDir.toFile(), "init", sourceDir.getAbsolutePath());

        Random rnd = new Random(43);
        List<byte[]> contents = new ArrayList<>();
        File dataFile = new File(sourceDir, "f.bin");
        for (int size : GROWING_SIZES) {
            byte[] content = new byte[size];
            rnd.nextBytes(content);
            contents.add(content);
            Files.write(dataFile.toPath(), content);
            HgTestUtils.hg(sourceDir, "commit", "-A", "-m", "size " + size,
                    "--config", "ui.username=realhg");
        }

        // Confirm real hg itself actually produced the non-inline split (sanity check on the
        // fixture, not on hg4j).
        File realFilelogDat = new File(new File(sourceDir, ".hg/store/data"), "f.bin.d");
        assertTrue(realFilelogDat.exists(), "real hg must have split f.bin into a .d file by now");

        File destDir = tempDir.resolve("hg4j-dest").toFile();
        HgRepository dest = new CloneCommand().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        File clonedIdx = new File(new File(destDir, ".hg/store/data"), "f.bin.i");
        File clonedDat = new File(new File(destDir, ".hg/store/data"), "f.bin.d");
        assertTrue(clonedIdx.exists());
        Revlog cloned = new Revlog(clonedIdx, clonedDat);
        assertFalse(cloned.isInline(), "cloning a real-hg-produced non-inline filelog must preserve non-inline");
        assertEquals(GROWING_SIZES.length, cloned.getRevisionCount());
        for (int i = 0; i < contents.size(); i++) {
            assertArrayEquals(contents.get(i), cloned.getRevisionContent(i),
                    "hg4j must correctly read revision " + i + " of a real-hg-produced, already-split revlog");
        }

        String verify = HgTestUtils.hg(destDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify on the hg4j clone destination must be clean: " + verify);
    }
}
