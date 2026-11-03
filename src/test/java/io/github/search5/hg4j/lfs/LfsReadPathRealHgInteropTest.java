package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.ArchiveCommand;
import io.github.search5.hg4j.api.CatCommand;
import io.github.search5.hg4j.api.DiffCommand;
import io.github.search5.hg4j.api.GrepCommand;
import io.github.search5.hg4j.api.Status;
import io.github.search5.hg4j.api.StatusCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 2: every read command must return the REAL content of an LFS-tracked file, like real hg's
 * flag processor ({@code readfromstore}) does, instead of the 100-odd-byte pointer text. The repository is created by real hg
 * ({@code lfs.track = size(">100")}), so the pointer/blob layout under test is the original's.
 */
@Tag("interop")
class LfsReadPathRealHgInteropTest {

    @TempDir
    File dir;

    private byte[] v1;
    private byte[] v2;

    @BeforeEach
    void realHgLfsRepository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        hg("init");
        // v1 is text (x-is-binary 0), the binary file carries NUL bytes.
        v1 = ("needle first line\n" + "filler line\n".repeat(60)).getBytes(StandardCharsets.UTF_8);
        v2 = ("needle first line\n" + "filler line\n".repeat(60) + "changed tail line\n").getBytes(StandardCharsets.UTF_8);
        byte[] bin = new byte[3000];
        for (int i = 0; i < bin.length; i++) {
            bin[i] = (byte) (i * 31);
        }
        Files.write(new File(dir, "big.txt").toPath(), v1);
        Files.write(new File(dir, "blob.bin").toPath(), bin);
        Files.writeString(new File(dir, "small.txt").toPath(), "tiny\n");
        hg("add");
        hg("commit", "-m", "first", "-u", "t");
        Files.write(new File(dir, "big.txt").toPath(), v2);
        hg("commit", "-m", "second", "-u", "t");
        assertTrue(hg("debugdata", "-c", "0").length() > 0);
        // sanity: the filelog really holds a pointer
        assertTrue(hg("debugdata", "big.txt", "0").contains("git-lfs.github.com"), "oracle must have stored a pointer");
    }

    private String hg(String... args) throws Exception {
        String[] full = new String[args.length + 2];
        full[0] = "--config";
        full[1] = "extensions.lfs=";
        System.arraycopy(args, 0, full, 2, args.length);
        if (args[0].equals("init")) {
            return HgTestUtils.hg(dir, "--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")", "init");
        }
        String[] withTrack = new String[full.length + 2];
        withTrack[0] = "--config";
        withTrack[1] = "lfs.track=size(\">100\")";
        System.arraycopy(full, 0, withTrack, 2, full.length);
        return HgTestUtils.hg(dir, withTrack);
    }

    private HgRepository open() throws Exception {
        return new HgRepository(dir);
    }

    @Test
    void catReturnsTheRealContent() throws Exception {
        HgRepository repo = open();
        assertArrayEquals(v1, new CatCommand(repo).setFile("big.txt").setRevision("0").call());
        assertArrayEquals(v2, new CatCommand(repo).setFile("big.txt").setRevision("1").call());
    }

    @Test
    void grepSearchesTheRealContent() throws Exception {
        List<GrepCommand.GrepResult> hits = new GrepCommand(open()).setQuery("changed tail").call();
        assertEquals(1, hits.size(), "pointer text would never contain the searched line");
        assertEquals("big.txt", hits.get(0).path);
        assertEquals("changed tail line", hits.get(0).lineContent);
    }

    @Test
    void diffShowsTheRealChange() throws Exception {
        List<DiffCommand.DiffEntry> entries = new DiffCommand(open()).setOldRevision(0).setNewRevision(1).call();
        String big = entries.stream().filter(e -> e.getPath().equals("big.txt")).findFirst().orElseThrow().getDiffContent();
        assertTrue(big.contains("+changed tail line"), big);
        assertFalse(big.contains("oid sha256"), big);
    }

    @Test
    void archiveWritesTheRealContent() throws Exception {
        File out = new File(dir.getParentFile(), dir.getName() + "-archive");
        new ArchiveCommand(open()).setRevision("1").setDestination(out).setType("files").call();
        assertArrayEquals(v2, Files.readAllBytes(new File(out, "big.txt").toPath()));
        assertEquals(3000, Files.readAllBytes(new File(out, "blob.bin").toPath()).length);
    }

    @Test
    void statusIsCleanForUntouchedLfsFilesEvenWhenTheStatIsAmbiguous() throws Exception {
        // The commits above happened within the same second, so hg's dirstate records an ambiguous stat and status has to
        // compare content: the stored pointer must not be compared with the real bytes.
        Status status = new StatusCommand(open()).call();
        assertTrue(status.getModified().isEmpty(), "modified: " + status.getModified());
        assertTrue(status.getClean().contains("big.txt"));
        assertEquals("", hg("status"), "oracle agrees the tree is clean");
    }

    @Test
    void statusSeesARealEditOfAnLfsFile() throws Exception {
        Files.write(new File(dir, "big.txt").toPath(), (new String(v2, StandardCharsets.UTF_8) + "edit\n").getBytes(StandardCharsets.UTF_8));
        Status status = new StatusCommand(open()).call();
        assertEquals(java.util.Set.of("big.txt"), new java.util.HashSet<>(status.getModified()));
        assertEquals("M big.txt", hg("status"));
    }
}
