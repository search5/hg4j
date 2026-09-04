package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog #35 (small revlogs written by hg4j are always non-inline, so real hg's {@code hg
 * verify} reports "not in fncache" for what a real reference install would have kept inline).
 * Covers the specific write path this backlog's investigation found still broken after {@link
 * Revlog}'s constructor started defaulting new v1 filelogs/manifests to inline:
 * {@link Revlog#appendChangeGroupEntry}, used by {@code FetchCommand}/{@code PullCommand} to
 * apply a changegroup -- as opposed to {@link Revlog#appendRevision}, used by local {@code
 * CommitCommand} and already covered elsewhere.
 */
@Tag("interop")
public class RevlogInlineWriteRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * hg4j commits a small file locally (exercises {@link Revlog#appendRevision}), then a SEPARATE
     * hg4j repository pulls it (exercises {@link Revlog#appendChangeGroupEntry}, the path this
     * backlog's investigation found still hand-rolled a non-inline-only write). Real hg's own
     * {@code verify} on the PULL DESTINATION must be clean of "not in fncache" -- the specific
     * symptom of a filelog real hg would have kept inline but hg4j split into a separate {@code .d}.
     */
    @Test
    public void hg4jPullAppliedFilelogIsInlineAndRealHgVerifyIsCleanOfFncacheWarning(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgRepository source = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(sourceDir.toPath().resolve("a.txt"), "small file content");
        new AddCommand(source).call();
        new CommitCommand(source).setAuthor("hg4j").setMessage("c0").call();

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository dest = new InitCommand().setDirectory(destDir).call();
        new PullCommand(dest).setSource(sourceDir.getAbsolutePath()).call();
        // A bare pull only advances the dirstate PARENT pointer (PullCommand's own "auto-advance
        // from empty" convenience) without checking any file out -- `hg verify`'s dirstate
        // cross-check then flags every manifest entry as "not marked as tracked in p1", an
        // unrelated pre-existing quirk of pull-into-empty-repo, not what this test is about. An
        // explicit update actually checks the file out so `hg verify`'s dirstate check is clean.
        new UpdateCommand(dest).call();

        // The filelog hg4j's own FetchCommand/appendChangeGroupEntry just wrote into `dest` must be
        // inline -- read it back via a fresh Revlog to see what actually landed on disk.
        File filelogIdx = new File(new File(destDir, ".hg/store/data"), "a.txt.i");
        assertTrue(filelogIdx.exists(), "pull must have created a.txt's filelog: " + filelogIdx);
        Revlog readBack = new Revlog(filelogIdx, new File(filelogIdx.getParentFile(), "a.txt.d"));
        assertTrue(readBack.isInline(),
                "a small filelog written via appendChangeGroupEntry (pull apply) must be inline, matching real hg");

        String catOut = HgTestUtils.hg(destDir, "cat", "-r", "tip", "a.txt");
        assertEquals("small file content", catOut);

        String verify = HgTestUtils.hg(destDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors: " + verify);
        assertFalse(verify.contains("not in fncache"),
                "real hg verify must not report a.txt's filelog as missing from fncache "
                        + "(that warning means hg4j wrote it non-inline when real hg would have kept it inline): " + verify);
    }
}
