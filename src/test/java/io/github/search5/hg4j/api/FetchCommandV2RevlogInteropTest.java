package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a changegroup (what a push and a pull do) to a repository with the version 2 revlog format. Like the original's
 * transaction, the apply keeps the dockets and the sizes of the companion files, so a failure after the data was appended (here: a
 * {@code pretxnchangegroup} that rejects) puts every store file back to the bytes it had. Before, the journal held only the sizes
 * of the docket files, which never change, and the companion files stayed grown.
 */
@Tag("interop")
class FetchCommandV2RevlogInteropTest {

    @TempDir
    Path tempDir;

    private static String rustHg() {
        return System.getProperty("user.home") + "/.cache/hg4j/hg-rust-7.2.4/bin/hg";
    }

    private record Result(int code, String output) {
    }

    private static Result run(String hg, File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(hg));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        return new Result(p.waitFor(), out);
    }

    private static Map<String, byte[]> snapshot(File storeDir) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        try (var walk = Files.walk(storeDir.toPath())) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String rel = storeDir.toPath().relativize(p).toString();
                if (Files.isRegularFile(p) && !rel.equals("lock")) {
                    files.put(rel, Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    @Test
    void aRejectedApplyPutsEveryStoreFileOfAVersion2RepositoryBackToItsBytes() throws Exception {
        Assumptions.assumeTrue(new File(rustHg()).canExecute(), "the Rust hg build is required for the version 2 revlog format");
        // the plain hg cannot open the version 2 format: the changesets come from a classic repository with the same first changeset
        File classic = tempDir.resolve("classic").toFile();
        assertEquals(0, run("hg", tempDir.toFile(), "init", classic.getPath()).code());
        // a v2 repository and a classic one share a changeset when it is exported and imported, so the first changeset comes by bundle
        Files.writeString(new File(classic, "a.txt").toPath(), "one\n");
        run("hg", classic, "add", "a.txt");
        assertEquals(0, run("hg", classic, "commit", "-m", "c", "-u", "dev", "-d", "1700000000 0").code());
        for (String content : new String[] {"two\n", "three\n"}) {
            Files.writeString(new File(classic, "a.txt").toPath(), content);
            assertEquals(0, run("hg", classic, "commit", "-m", "more", "-u", "dev", "-d", "1700000100 0").code());
        }
        File bundleFile = tempDir.resolve("all.hg").toFile();
        assertEquals(0, run("hg", classic, "bundle", "--all", "--type", "none-v1", bundleFile.getPath()).code());
        byte[] bytes = Files.readAllBytes(bundleFile.toPath());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(bytes, 6, bytes.length - 6), "01");

        File target = tempDir.resolve("target").toFile();
        target.mkdirs();
        assertEquals(0, run(rustHg(), target, "init", "--config", "experimental.revlogv2=enable-unstable-format-and-corrupt-my-data").code());
        HgRepository repo = new HgRepository(target);
        File store = repo.getStoreDir();
        Map<String, byte[]> before = snapshot(store);

        IOException failure = assertThrows(IOException.class,
                () -> new FetchCommand(repo).applyBundle(bundle, 1000, null, imported -> {
                    throw new IOException("pretxnchangegroup rejected");
                }));
        assertTrue(failure.getMessage().contains("rejected"), failure.getMessage());

        Map<String, byte[]> after = snapshot(store);
        assertEquals(before.keySet(), after.keySet(), "the same files remain; after=" + after.keySet());
        for (String name : before.keySet()) {
            assertArrayEquals(before.get(name), after.get(name), name + " is back to its bytes");
        }
        assertFalse(new File(store, "journal").exists());
        Result verify = run(rustHg(), target, "verify");
        assertEquals(0, verify.code(), verify.output());
    }

    @Test
    void anApplyThatSucceedsLeavesAVersion2RepositoryTheRealHgVerifies() throws Exception {
        Assumptions.assumeTrue(new File(rustHg()).canExecute(), "the Rust hg build is required for the version 2 revlog format");
        File classic = tempDir.resolve("classic").toFile();
        assertEquals(0, run("hg", tempDir.toFile(), "init", classic.getPath()).code());
        for (String content : new String[] {"one\n", "two\n", "three\n"}) {
            Files.writeString(new File(classic, "a.txt").toPath(), content);
            run("hg", classic, "add", "a.txt");
            assertEquals(0, run("hg", classic, "commit", "-m", "c", "-u", "dev", "-d", "1700000100 0").code());
        }
        File bundleFile = tempDir.resolve("all.hg").toFile();
        assertEquals(0, run("hg", classic, "bundle", "--all", "--type", "none-v1", bundleFile.getPath()).code());
        byte[] bytes = Files.readAllBytes(bundleFile.toPath());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(bytes, 6, bytes.length - 6), "01");

        File target = tempDir.resolve("target").toFile();
        target.mkdirs();
        assertEquals(0, run(rustHg(), target, "init", "--config", "experimental.revlogv2=enable-unstable-format-and-corrupt-my-data").code());
        HgRepository repo = new HgRepository(target);
        assertEquals(3, new FetchCommand(repo).applyBundle(bundle, 1000, null, null).size());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
        Result verify = run(rustHg(), target, "verify");
        assertEquals(0, verify.code(), verify.output());
        assertEquals(3, run(rustHg(), target, "log", "-T", "x").output().length());
    }
}
