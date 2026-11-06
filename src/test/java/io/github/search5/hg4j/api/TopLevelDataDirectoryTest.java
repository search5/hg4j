package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A tracked path may start with {@code data/} or {@code meta/}: the store prefix is added to it like to any other path. */
class TopLevelDataDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void theStorePathOfALogicalPathAlwaysGetsTheDataPrefix() {
        assertEquals("data/data/x.txt.i", NodeIdUtil.encodeFname("data/x.txt.i"));
        assertEquals("data/meta/x.txt.i", NodeIdUtil.encodeFname("meta/x.txt.i"));
        assertEquals("data/dh/z.txt.i", NodeIdUtil.encodeFname("dh/z.txt.i"));
        assertEquals("meta/sub/00manifest.i", NodeIdUtil.encodeStorePath("meta/sub/00manifest.i"));
    }

    @Test
    void filesUnderATopLevelDataDirectoryAreCommittedAndRead() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        for (String p : new String[] {"data/x.txt", "other/y.txt", "dh/z.txt", "meta/m.txt"}) {
            File f = new File(dir, p);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), "hello " + p + "\n");
        }
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setMessage("m").setAuthor("t").call();
        assertEquals(true, new File(dir, ".hg/store/data/data/x.txt.i").exists());
        for (String p : new String[] {"data/x.txt", "other/y.txt", "dh/z.txt", "meta/m.txt"}) {
            byte[] content = new CatCommand(new HgRepository(dir)).setFile(p).setRevision(NodeIdUtil.toHex(node)).call();
            assertEquals("hello " + p + "\n", new String(content));
        }
    }
}
