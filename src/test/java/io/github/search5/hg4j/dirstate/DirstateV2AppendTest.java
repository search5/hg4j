package io.github.search5.hg4j.dirstate;

import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dirstate-v2 writer appends to the data file like the Rust one: a real hg reads what hg4j appended, hg4j reads what a real hg
 * appended, the data file keeps its name until too much of it is unreachable, and a crash between the data and the docket loses nothing.
 */
class DirstateV2AppendTest {

    @BeforeAll
    static void needRustHg() {
        assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    private static String hg(Path repo, String... args) throws Exception {
        return LocalHg.run(repo, args);
    }

    private Path newRepo(Path dir) throws Exception {
        Path repo = dir.resolve("r");
        Files.createDirectories(repo);
        hg(repo, "init", "--config", "format.use-dirstate-v2=1");
        Files.createDirectories(repo.resolve("a/b"));
        for (String f : new String[] {"a/b/f", "a/g", "h", "z"}) {
            Files.writeString(repo.resolve(f), f + "\n");
        }
        // enough files that one change leaves most of the data file reachable
        for (int dirIndex = 0; dirIndex < 10; dirIndex++) {
            Files.createDirectories(repo.resolve("d" + dirIndex));
            for (int i = 0; i < 40; i++) {
                Files.writeString(repo.resolve("d" + dirIndex + "/file" + i), "x" + i + "\n");
            }
        }
        hg(repo, "add");
        hg(repo, "commit", "-m", "base", "-u", "t");
        return repo;
    }

    private static File docket(Path repo) {
        return repo.resolve(".hg/dirstate").toFile();
    }

    private static String uid(Path repo) throws Exception {
        byte[] d = Files.readAllBytes(docket(repo).toPath());
        return new String(d, 125, d[124] & 0xFF, StandardCharsets.US_ASCII);
    }

    private static long dataLength(Path repo) throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(docket(repo).toPath())).getInt(120);
    }

    private static Dirstate load(Path repo) throws Exception {
        Dirstate d = new Dirstate();
        d.read(docket(repo));
        return d;
    }

    @Test
    void appendedChangesAreReadByTheRealHgAndTheDataFileKeepsItsName(@TempDir Path dir) throws Exception {
        Path repo = newRepo(dir);
        String uid = uid(repo);
        long before = dataLength(repo);

        Dirstate d = load(repo);
        Files.createDirectories(repo.resolve("a/c"));
        Files.writeString(repo.resolve("a/c/new"), "n\n");
        d.addEntry("a/c/new", new Dirstate.Entry('a', 0, -1, Dirstate.Entry.AMBIGUOUS_TIME));
        d.addCopy("a/c/new", "a/g");
        d.removeEntry("z");
        d.write(docket(repo));

        assertEquals(uid, uid(repo), "an append keeps the data file");
        assertTrue(dataLength(repo) > before);
        String listing = hg(repo, "debugdirstate");
        assertTrue(listing.contains("a/c/new"), listing);
        assertTrue(hg(repo, "debugdirstate").lines().noneMatch(l -> l.endsWith(" z")), "z was removed from the dirstate");
        assertEquals("a/g", hg(repo, "status", "-C", "a/c/new").lines().skip(1).findFirst().orElse("").trim());
        assertTrue(hg(repo, "status", "-A").contains("A a/c/new"));
    }

    @Test
    void whatTheRealHgAppendsIsReadBack(@TempDir Path dir) throws Exception {
        Path repo = newRepo(dir);
        Dirstate d = load(repo);
        d.addEntry("h", new Dirstate.Entry('n', 0644, 2, 1_700_000_000L));
        d.write(docket(repo));
        Files.writeString(repo.resolve("a/b/second"), "s\n");
        hg(repo, "add", "a/b/second");
        hg(repo, "forget", "z");
        Dirstate again = load(repo);
        assertEquals('a', again.getEntries().get("a/b/second").getState());
        assertEquals('r', again.getEntries().get("z").getState());
        assertEquals(hg(repo, "debugdirstate").lines().count(), again.getEntries().size());
        hg(repo, "verify"); // the dirstate that results from both writers agrees with the manifest
    }

    @Test
    void theFileIsReplacedOnceHalfOfItIsUnreachable(@TempDir Path dir) throws Exception {
        Path repo = newRepo(dir);
        String first = uid(repo);
        boolean replaced = false;
        for (int i = 0; i < 80 && !replaced; i++) {
            Dirstate d = load(repo);
            d.addEntry("a/b/f", new Dirstate.Entry('n', 0644, 10 + i, 1_700_000_000L + i));
            d.write(docket(repo));
            replaced = !uid(repo).equals(first);
        }
        assertTrue(replaced, "the unreachable ratio of 0.5 must lead to a new data file");
        long files = Arrays.stream(repo.resolve(".hg").toFile().list()).filter(n -> n.startsWith("dirstate.")).count();
        assertEquals(1, files, "the old data file is removed");
        hg(repo, "debugdirstate");
        Map<String, Dirstate.Entry> entries = load(repo).getEntries();
        assertEquals(404, entries.size());
    }

    @Test
    void bytesPastTheValidLengthAreIgnoredAndTruncatedByTheNextWrite(@TempDir Path dir) throws Exception {
        Path repo = newRepo(dir);
        File data = repo.resolve(".hg/dirstate." + uid(repo)).toFile();
        long valid = dataLength(repo);
        Files.write(data.toPath(), concat(Files.readAllBytes(data.toPath()), new byte[] {9, 9, 9, 9, 9}));
        Dirstate d = load(repo);
        assertEquals(404, d.getEntries().size(), "the garbage after the valid length is not read");
        d.addEntry("h", new Dirstate.Entry('n', 0644, 3, 1_700_000_001L));
        d.write(docket(repo));
        assertEquals(dataLength(repo), Files.size(data.toPath()), "nothing is left past the valid length");
        assertTrue(dataLength(repo) >= valid);
        assertEquals(404, load(repo).getEntries().size());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
