package io.github.search5.hg4j.dirstate;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The rules of {@code dirstate.changing_files} / {@code changing_parents}: wlock required, exclusive, nested, written once, thrown away on failure. */
class DirstateContextTest {

    private static HgRepository repo(Path dir) throws Exception {
        return Hg.init().setDirectory(dir.toFile()).call();
    }

    private static Dirstate.Entry entry() {
        return new Dirstate.Entry('a', 0, -1, Dirstate.Entry.AMBIGUOUS_TIME);
    }

    @Test
    void requiresTheWorkingCopyLock(@TempDir Path dir) throws Exception {
        try (HgRepository repo = repo(dir)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> repo.changingFiles(() -> null));
            assertEquals("trying to change the dirstate without holding the wlock", e.getMessage());
        }
    }

    @Test
    void theTwoKindsExcludeEachOther(@TempDir Path dir) throws Exception {
        try (HgRepository repo = repo(dir); HgLock lock = repo.lockWorkingCopy()) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> repo.changingFiles(() -> repo.changingParents(() -> null)));
            assertTrue(e.getMessage().contains("\"changing_parents\" dirstate-changing context while a \"changing_files\" is already open"));
            repo.changingFiles(() -> repo.changingFiles(() -> null)); // nesting the same kind is fine
        }
    }

    @Test
    void theDirstateIsWrittenOnceWhenTheOutermostContextEnds(@TempDir Path dir) throws Exception {
        try (HgRepository repo = repo(dir); HgLock lock = repo.lockWorkingCopy()) {
            File docket = new File(dir.toFile(), ".hg/dirstate");
            repo.changingFiles(() -> {
                Dirstate d = repo.getDirstate();
                d.addEntry("one", entry());
                repo.writeDirstate(d);
                repo.changingFiles(() -> {
                    Dirstate inner = repo.getDirstate();
                    assertSame(d, inner, "one dirstate object in a context");
                    inner.addEntry("two", entry());
                    repo.writeDirstate(inner);
                    return null;
                });
                assertEquals(0, readOnDisk(docket).getEntries().size(), "nothing on disk before the outermost context ends");
                return null;
            });
            assertEquals(2, readOnDisk(docket).getEntries().size());
        }
    }

    @Test
    void anExceptionThrowsTheChangesAway(@TempDir Path dir) throws Exception {
        try (HgRepository repo = repo(dir); HgLock lock = repo.lockWorkingCopy()) {
            File docket = new File(dir.toFile(), ".hg/dirstate");
            repo.changingFiles(() -> {
                Dirstate d = repo.getDirstate();
                d.addEntry("kept", entry());
                repo.writeDirstate(d);
                return null;
            });
            assertThrows(IOException.class, () -> repo.changingFiles(() -> {
                Dirstate d = repo.getDirstate();
                d.addEntry("lost", entry());
                repo.writeDirstate(d);
                throw new IOException("boom");
            }));
            Dirstate disk = readOnDisk(docket);
            assertNotNull(disk.getEntries().get("kept"));
            assertNull(disk.getEntries().get("lost"));
            // an exception in an inner context invalidates the outer one even when the outer one catches it
            repo.changingFiles(() -> {
                Dirstate d = repo.getDirstate();
                d.addEntry("outer", entry());
                repo.writeDirstate(d);
                try {
                    repo.changingFiles(() -> {
                        throw new IOException("inner");
                    });
                } catch (IOException expected) {
                    // swallowed on purpose
                }
                return null;
            });
            assertNull(readOnDisk(docket).getEntries().get("outer"), "the context was invalidated by the inner failure");
        }
    }

    @Test
    void outsideAContextEveryWriteGoesToDisk(@TempDir Path dir) throws Exception {
        try (HgRepository repo = repo(dir)) {
            Dirstate d = repo.getDirstate();
            d.addEntry("direct", entry());
            repo.writeDirstate(d);
            assertNotNull(readOnDisk(new File(dir.toFile(), ".hg/dirstate")).getEntries().get("direct"));
        }
    }

    private static Dirstate readOnDisk(File docket) throws IOException {
        Dirstate d = new Dirstate();
        if (docket.exists() && Files.size(docket.toPath()) > 0) {
            d.read(docket);
        }
        return d;
    }
}
