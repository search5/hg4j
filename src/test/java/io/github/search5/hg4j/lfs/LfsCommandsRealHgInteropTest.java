package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.BackoutCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.ExportCommand;
import io.github.search5.hg4j.api.GraftCommand;
import io.github.search5.hg4j.api.HisteditCommand;
import io.github.search5.hg4j.api.MergeCommand;
import io.github.search5.hg4j.api.RebaseCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 2: the history-rewriting commands read and write LFS files too. The repository is built by real hg with
 * {@code lfs.track = size(">100")}: rev 0 is the base, rev 1 edits the LFS file {@code big.txt}, rev 2 (a second head on top of
 * rev 0) edits the small {@code other.txt}. Each test runs one hg4j command and lets REAL hg judge the outcome ({@code hg cat},
 * {@code hg verify}).
 */
@Tag("interop")
class LfsCommandsRealHgInteropTest {

    private static final String TRACK = "lfs.track=size(\">100\")";

    @TempDir
    File dir;

    private String v1;
    private String v2;

    @BeforeEach
    void realHgHistory() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        v1 = "needle first line\n" + "filler line\n".repeat(60);
        v2 = v1 + "changed tail line\n";
        hg("init");
        trackLargeFiles(dir);
        write("big.txt", v1);
        write("other.txt", "other\n");
        hg("add");
        hg("commit", "-m", "base", "-u", "t");
        write("big.txt", v2);
        hg("commit", "-m", "edit lfs file", "-u", "t");
        hg("update", "0");
        write("other.txt", "other edited\n");
        hg("commit", "-m", "edit small file", "-u", "t");
        assertTrue(hg("debugdata", "big.txt", "1").contains("git-lfs.github.com"), "oracle must have stored a pointer");
    }

    /** hg4j reads {@code lfs.track} from the repository's hgrc (real hg gets it on the command line too), so write it there. */
    private static void trackLargeFiles(File repo) throws Exception {
        Files.writeString(new File(repo, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n");
    }

    private String hg(String... args) throws Exception {
        String[] full = new String[args.length + 4];
        full[0] = "--config";
        full[1] = "extensions.lfs=";
        full[2] = "--config";
        full[3] = TRACK;
        System.arraycopy(args, 0, full, 4, args.length);
        return HgTestUtils.hg(dir, full);
    }

    private void write(String name, String text) throws Exception {
        Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private HgRepository open() throws Exception {
        return new HgRepository(dir);
    }

    /** Real hg's judgment of the repository after an hg4j command: it verifies, and the tip holds the expected content. */
    private void assertRealHgAccepts(String expectedBig) throws Exception {
        assertTrue(hg("verify").contains("checked"), "real hg verify must accept the result");
        assertEquals(expectedBig.trim(), hg("cat", "-r", "tip", "big.txt"));
    }

    private byte[] node(int rev) throws Exception {
        return NodeIdUtil.fromHex(hg("log", "-r", String.valueOf(rev), "--template", "{node}"));
    }

    @Test
    void exportShowsTheRealChangeOfAnLfsFile() throws Exception {
        String patch = new ExportCommand(open()).setRevision("1").call();
        assertTrue(patch.contains("+changed tail line"), patch);
        assertFalse(patch.contains("oid sha256"), patch);
    }

    @Test
    void backoutRestoresTheEarlierLfsContent() throws Exception {
        hg("update", "1");
        new BackoutCommand(open()).setRevision("1").setMessage("backout").setAuthor("t").call();
        assertRealHgAccepts(v1);
    }

    @Test
    void graftCarriesTheLfsEditToAnotherHead() throws Exception {
        new GraftCommand(open()).setSource("1").call();
        assertRealHgAccepts(v2);
        assertEquals("other edited", hg("cat", "-r", "tip", "other.txt"));
    }

    @Test
    void mergeCombinesTheLfsEditWithTheOtherHead() throws Exception {
        new MergeCommand(open()).setRevision(1).call();
        assertEquals(v2.trim(), new String(Files.readAllBytes(new File(dir, "big.txt").toPath()), StandardCharsets.UTF_8).trim());
        new CommitCommand(open()).setMessage("merge").setAuthor("t").call();
        assertRealHgAccepts(v2);
    }

    @Test
    void rebaseMovesTheLfsEditOntoAnotherHead() throws Exception {
        new RebaseCommand(open()).setSource(node(1)).setTarget(node(2)).call();
        hg("update", "tip");
        assertRealHgAccepts(v2);
    }

    @Test
    void histeditRewritesAnLfsFileAsAPointerAgain() throws Exception {
        // Dropping the commit that introduced v1 gives v2 a different file parent, so histedit has to write a NEW filelog revision
        // (the same parent and content would just find the existing node).
        File sub = new File(dir, "hist");
        assertTrue(sub.mkdirs());
        String[] cfg = {"--config", "extensions.lfs=", "--config", TRACK};
        java.util.function.Function<String[], String> hgSub = args -> {
            try {
                String[] all = new String[cfg.length + args.length];
                System.arraycopy(cfg, 0, all, 0, cfg.length);
                System.arraycopy(args, 0, all, cfg.length, args.length);
                return HgTestUtils.hg(sub, all);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        hgSub.apply(new String[] {"init"});
        trackLargeFiles(sub);
        Files.writeString(new File(sub, "small.txt").toPath(), "small\n");
        hgSub.apply(new String[] {"add"});
        hgSub.apply(new String[] {"commit", "-m", "r0", "-u", "t"});
        Files.writeString(new File(sub, "big.txt").toPath(), v1);
        hgSub.apply(new String[] {"add"});
        hgSub.apply(new String[] {"commit", "-m", "r1", "-u", "t"});
        Files.writeString(new File(sub, "big.txt").toPath(), v2);
        hgSub.apply(new String[] {"commit", "-m", "r2", "-u", "t"});
        String[] nodes = hgSub.apply(new String[] {"log", "--template", "{node} "}).trim().split(" ");

        new HisteditCommand(new HgRepository(sub))
                .addRule(HisteditCommand.Action.PICK, nodes[2])
                .addRule(HisteditCommand.Action.DROP, nodes[1])
                .addRule(HisteditCommand.Action.PICK, nodes[0])
                .call();

        assertTrue(hgSub.apply(new String[] {"verify"}).contains("checked"), "real hg verify must accept the rewritten history");
        assertEquals(v2.trim(), hgSub.apply(new String[] {"cat", "-r", "tip", "big.txt"}));
        // big.txt had two revisions (v1, v2); the rewrite appended the third, and it must be a pointer again
        assertTrue(hgSub.apply(new String[] {"debugdata", "big.txt", "2"}).contains("git-lfs.github.com"),
                "the rewritten revision must be LFS-stored");
    }
}
