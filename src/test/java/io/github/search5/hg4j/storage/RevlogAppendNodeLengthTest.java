package io.github.search5.hg4j.storage;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@code appendRevision} hands back a 20 byte node both for a new revision and for one that already exists (same parents and content). */
class RevlogAppendNodeLengthTest {

    @TempDir
    Path tempDir;

    @Test
    void aNewAndAnExistingRevisionBothReturnTwentyBytes() throws Exception {
        try (HgRepository repo = Hg.init().setDirectory(tempDir.resolve("r").toFile()).call()) {
            File index = new File(repo.getStoreDir(), "data/f.txt.i");
            index.getParentFile().mkdirs();
            Revlog filelog = repo.getRevlog(index, new File(repo.getStoreDir(), "data/f.txt.d"));
            byte[] first = filelog.appendRevision("content\n".getBytes(), -1, -1, new byte[20], new byte[20], 0);
            assertEquals(20, first.length);
            byte[] again = filelog.appendRevision("content\n".getBytes(), -1, -1, new byte[20], new byte[20], 1);
            assertEquals(20, again.length, "the revision exists: its node, not the 32 byte index field");
            assertArrayEquals(first, again);
            assertEquals(1, filelog.getRevisionCount(), "no duplicate");
        }
    }
}
