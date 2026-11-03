package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 3: which files go to the LFS store ({@code lfs.track}, the deprecated {@code lfs.threshold}, a {@code .hglfs}
 * file) is decided exactly like real hg. For every configuration the same files are committed by real hg and by hg4j, and for
 * each file the two must agree on whether the filelog holds a pointer.
 */
@Tag("interop")
class LfsTrackRealHgInteropTest {

    @TempDir
    Path tempDir;

    private static final Map<String, Integer> FILES = new LinkedHashMap<>();

    static {
        FILES.put("a.bin", 50);
        FILES.put("b.bin", 500);
        FILES.put("c.txt", 500);
        FILES.put("d.tar.gz", 20);
        FILES.put("assets/e.dat", 10);
        FILES.put("assets/sub/f.dat", 10);
        FILES.put("assetsX/g.dat", 10);
        FILES.put("h.txt", 5000);
    }

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
    }

    private static void writeFiles(File root, String hglfs) throws Exception {
        for (Map.Entry<String, Integer> file : FILES.entrySet()) {
            File target = new File(root, file.getKey());
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), ("x".repeat(file.getValue() - 1) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (hglfs != null) {
            Files.writeString(new File(root, ".hglfs").toPath(), hglfs);
        }
    }

    /** Which files real hg stored as a pointer, for the given extra {@code --config} settings and optional {@code .hglfs}. */
    private Map<String, Boolean> realHg(String name, String hglfs, String... configs) throws Exception {
        File repo = tempDir.resolve("real-" + name).toFile();
        repo.mkdirs();
        String[] base = new String[2 + configs.length * 2];
        base[0] = "--config";
        base[1] = "extensions.lfs=";
        for (int i = 0; i < configs.length; i++) {
            base[2 + i * 2] = "--config";
            base[3 + i * 2] = configs[i];
        }
        HgTestUtils.hg(repo, concat(base, "init"));
        writeFiles(repo, hglfs);
        HgTestUtils.hg(repo, concat(base, "add"));
        HgTestUtils.hg(repo, concat(base, "commit", "-m", "m", "-u", "t"));
        Map<String, Boolean> stored = new TreeMap<>();
        for (String path : FILES.keySet()) {
            stored.put(path, HgTestUtils.hg(repo, concat(base, "debugdata", path, "0")).contains("git-lfs.github.com/spec"));
        }
        return stored;
    }

    private Map<String, Boolean> hg4j(String name, String hglfs, String... configs) throws Exception {
        File dir = tempDir.resolve("hg4j-" + name).toFile();
        new InitCommand().setDirectory(dir).call();
        StringBuilder hgrc = new StringBuilder("[lfs]\n");
        for (String config : configs) {
            hgrc.append(config.substring(config.indexOf('.') + 1)).append('\n'); // "lfs.track=x" -> "track=x"
        }
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), hgrc.toString());
        HgRepository repo = new HgRepository(dir);
        writeFiles(dir, hglfs);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("m").setAuthor("t").call();
        HgRepository reopened = new HgRepository(dir);
        Map<String, Boolean> stored = new TreeMap<>();
        for (String path : FILES.keySet()) {
            File index = CommitCommand.getFilelogIndex(reopened.getStoreDir(), path);
            File data = new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d");
            Revlog filelog = reopened.getRevlog(index, data);
            stored.put(path, filelog.isExtStored(0));
        }
        return stored;
    }

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private void assertSameDecision(String name, String hglfs, String... configs) throws Exception {
        Map<String, Boolean> real = realHg(name, hglfs, configs);
        assertTrue(real.containsValue(true), "scenario " + name + " must track something, or it proves nothing: " + real);
        assertEquals(real, hg4j(name, hglfs, configs), "scenario " + name);
    }

    @Test
    void sizeExpression() throws Exception {
        assertSameDecision("size-gt", null, "lfs.track=size(\">100\")");
    }

    @Test
    void extensionPattern() throws Exception {
        assertSameDecision("ext", null, "lfs.track=**.bin");
        assertSameDecision("ext-multi", null, "lfs.track=**.tar.gz");
    }

    @Test
    void pathPatternMatchesDirectoriesOnly() throws Exception {
        // path:assets must take assets/e.dat and assets/sub/f.dat but not assetsX/g.dat
        assertSameDecision("path", null, "lfs.track=path:assets");
    }

    @Test
    void combinedExpressions() throws Exception {
        assertSameDecision("and-or", null, "lfs.track=(**.bin & size(\">100\")) | **.tar.gz");
        assertSameDecision("minus", null, "lfs.track=all() - **.txt");
        assertSameDecision("not", null, "lfs.track=!**.bin");
        assertSameDecision("word-ops", null, "lfs.track=**.txt and not size(\">1000\")");
    }

    @Test
    void sizeRangesAndUnits() throws Exception {
        assertSameDecision("range", null, "lfs.track=size(\"100-600\")");
        assertSameDecision("le", null, "lfs.track=size(\"<=50\")");
        assertSameDecision("ge", null, "lfs.track=size(\">=500\")");
        assertSameDecision("kb-range", null, "lfs.track=size(\"4kb-5kb\")");
        assertSameDecision("exact", null, "lfs.track=size(\"500\")");
    }

    @Test
    void deprecatedThresholdIsOrAddedToTrack() throws Exception {
        assertSameDecision("threshold", null, "lfs.threshold=100");
        assertSameDecision("threshold-and-track", null, "lfs.track=**.tar.gz", "lfs.threshold=1000");
    }

    @Test
    void hglfsFileTakesPrecedenceAndFirstMatchingRuleWins() throws Exception {
        String hglfs = "[track]\n**.bin = size(\">100\")\npath:assets = all()\n** = none()\n";
        assertSameDecision("hglfs", hglfs);
        // .hglfs overrides the configuration
        assertSameDecision("hglfs-overrides", hglfs, "lfs.track=**.txt");
    }

    @Test
    void emptyHglfsTracksNothing() throws Exception {
        Map<String, Boolean> real = realHg("hglfs-empty", "", "lfs.track=all()");
        assertFalse(real.containsValue(true), "oracle: an empty .hglfs tracks nothing even though lfs.track=all(): " + real);
        assertEquals(real, hg4j("hglfs-empty", "", "lfs.track=all()"));
    }

    @Test
    void invalidTrackExpressionIsRefusedWithTheOriginalsMessage() throws Exception {
        AssertionError real = assertThrows(AssertionError.class, () -> realHg("bad", null, "lfs.track=foo"));
        assertTrue(real.getMessage().contains("unsupported file pattern"), real.getMessage());
        HgValidationException ours = assertThrows(HgValidationException.class, () -> hg4j("bad", null, "lfs.track=foo"));
        assertTrue(ours.getMessage().contains("unsupported file pattern"), ours.getMessage());
    }

    @Test
    void invalidHglfsNamesTheFile() throws Exception {
        String bad = "[track]\nfoo = all()\n";
        AssertionError real = assertThrows(AssertionError.class, () -> realHg("bad-hglfs", bad));
        assertTrue(real.getMessage().contains("parse error in .hglfs"), real.getMessage());
        HgValidationException ours = assertThrows(HgValidationException.class, () -> hg4j("bad-hglfs", bad));
        assertTrue(ours.getMessage().contains("parse error in .hglfs"), ours.getMessage());
    }
}
