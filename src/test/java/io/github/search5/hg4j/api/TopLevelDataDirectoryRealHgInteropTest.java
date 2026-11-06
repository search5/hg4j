package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files under top-level {@code data/} and {@code meta/} directories: real hg reads what hg4j wrote, and the store layout is the same. */
@Tag("interop")
class TopLevelDataDirectoryRealHgInteropTest {

    @TempDir
    Path tempDir;

    @Test
    void realHgReadsAndVerifiesFilesUnderTopLevelDataAndMetaDirectories() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        for (String p : new String[] {"data/x.txt", "meta/m.txt", "dh/z.txt"}) {
            File f = new File(dir, p);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), "hello " + p + "\n");
        }
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("m").setAuthor("t").call();
        assertEquals("hello data/x.txt", HgTestUtils.hg(dir, "cat", "-r", "tip", "data/x.txt"));
        assertEquals("hello meta/m.txt", HgTestUtils.hg(dir, "cat", "-r", "tip", "meta/m.txt"));
        assertTrue(HgTestUtils.hg(dir, "verify").contains("checked 1 changesets"));
        // real hg's own commit lays the store out the same way
        File real = tempDir.resolve("real").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", real.getAbsolutePath());
        for (String p : new String[] {"data/x.txt", "meta/m.txt", "dh/z.txt"}) {
            File f = new File(real, p);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), "hello " + p + "\n");
        }
        HgTestUtils.hg(real, "add");
        HgTestUtils.hg(real, "commit", "-m", "m", "-u", "t");
        for (String p : new String[] {"data/data/x.txt.i", "data/meta/m.txt.i", "data/dh/z.txt.i"}) {
            assertTrue(new File(real, ".hg/store/" + p).exists(), p);
            assertTrue(new File(dir, ".hg/store/" + p).exists(), p);
        }
    }
}
