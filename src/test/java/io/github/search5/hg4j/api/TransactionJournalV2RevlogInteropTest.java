package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal of a transaction on a repository with the version 2 revlog format (docket files, and companion files for index, data
 * and sidedata). The journal is written in the original's format, plus {@code journal.truncateonly} for the companion files that
 * have to be cut back to their size even when it is 0, because the docket still names them.
 *
 * <p>In this format the original's own rollback removes such an empty companion (observed with {@code kill -9} in a real
 * {@code hg commit}: the next {@code hg verify} aborts with "No such file or directory ...sda"), so its {@code recover} is not the
 * oracle here. The test checks that hg4j's recover puts every store file back to the bytes it had, and that the real {@code hg}
 * verifies the result; what the original's recover makes of the same journal is printed for the record. The plain {@code hg} on the
 * PATH cannot open the format, the Rust build can.</p>
 */
@Tag("interop")
class TransactionJournalV2RevlogInteropTest {

    @TempDir
    Path tempDir;

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
                if (Files.isRegularFile(p) && !rel.equals("lock") && !rel.startsWith("journal")) {
                    files.put(rel, Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    @Test
    void hg4jRecoverPutsAVersion2RevlogRepositoryBackToTheBytesItHad() throws Exception {
        String rustHg = System.getProperty("user.home") + "/.cache/hg4j/hg-rust-7.2.4/bin/hg";
        Assumptions.assumeTrue(new File(rustHg).canExecute(), "the Rust hg build is required for the version 2 revlog format");
        File dir = tempDir.resolve("v2").toFile();
        dir.mkdirs();
        assertEquals(0, run(rustHg, dir, "init", "--config", "experimental.revlogv2=enable-unstable-format-and-corrupt-my-data").code());
        for (String content : new String[] {"one\n", "two\n"}) {
            Files.writeString(new File(dir, "a.txt").toPath(), content);
            assertEquals(0, run(rustHg, dir, "add", "a.txt").code());
            assertEquals(0, run(rustHg, dir, "commit", "-m", "c", "-u", "dev", "-d", "1700000000 0").code());
        }
        assertEquals(0, run(rustHg, dir, "verify").code());

        HgRepository repo = new HgRepository(dir);
        File store = repo.getStoreDir();
        Map<String, byte[]> before = snapshot(store);
        File changelogIndex = new File(store, "00changelog.i");
        File manifestIndex = new File(store, "00manifest.i");
        File filelogIndex = new File(store, "data/a.txt.i");
        assertTrue(new RevlogIndex(filelogIndex).isV2(), "the repository is expected to use version 2 revlogs");

        // a transaction that tracks the revlogs, appends to every file it tracked, and never closes: what a crash leaves
        StoreRollback transaction = new StoreRollback(repo);
        List<File> tracked = new ArrayList<>();
        for (File index : new File[] {changelogIndex, manifestIndex, filelogIndex}) {
            transaction.trackRevlog(index, null);
            RevlogIndex probe = new RevlogIndex(index);
            tracked.add(probe.getResolvedIndexFile());
            tracked.add(probe.getResolvedDataFile());
            tracked.add(probe.getResolvedSidedataFile());
            tracked.add(index);
        }
        for (File f : tracked) {
            if (f != null) {
                Files.write(f.toPath(), new byte[] {1, 2, 3, 4}, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            }
        }
        File journal = new File(store, "journal");
        File truncateOnly = new File(store, "journal.truncateonly");
        assertTrue(journal.exists());
        assertTrue(new File(store, "journal.backupfiles").exists(), "the dockets are backed up through journal.backupfiles");
        assertTrue(truncateOnly.exists(), "the companion files are listed as truncate-only");
        System.out.println("V2-RESULT journal: " + Files.readString(journal.toPath()).replace('\0', '@').replace('\n', '|'));
        System.out.println("V2-RESULT backupfiles: " + Files.readString(new File(store, "journal.backupfiles").toPath()).replace('\0', '@').replace('\n', '|'));
        System.out.println("V2-RESULT truncateonly: " + Files.readString(truncateOnly.toPath()).replace('\n', '|'));

        // for the record: the original's recover on a copy of the interrupted repository
        File copy = tempDir.resolve("v2copy").toFile();
        Files.walk(dir.toPath()).forEach(src -> {
            try {
                Path dst = copy.toPath().resolve(dir.toPath().relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Result theirs = run(rustHg, copy, "recover");
        Result theirsVerify = run(rustHg, copy, "verify");
        System.out.println("V2-RESULT original's recover on a copy: exit " + theirs.code() + " :: " + theirs.output().replace('\n', '|'));
        System.out.println("V2-RESULT original's verify after that: exit " + theirsVerify.code() + " :: " + theirsVerify.output().replace('\n', '|'));

        // hg4j's recover
        RecoverCommand.RecoverResult recovered = new RecoverCommand(new HgRepository(dir)).call();
        assertTrue(recovered.wasInterrupted());
        assertTrue(recovered.isSuccess());
        assertFalse(journal.exists());
        assertFalse(truncateOnly.exists(), "journal.truncateonly left behind");
        assertFalse(new File(store, "journal.backupfiles").exists(), "journal.backupfiles left behind");
        Map<String, byte[]> after = snapshot(store);
        assertEquals(before.keySet(), after.keySet(), "the set of store files differs after recover");
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            assertArrayEquals(e.getValue(), after.get(e.getKey()), "store file differs after recover: " + e.getKey());
        }
        Result verify = run(rustHg, dir, "verify");
        System.out.println("V2-RESULT verify after hg4j recover: exit " + verify.code() + " :: " + verify.output().replace('\n', '|'));
        assertEquals(0, verify.code(), verify.output());
    }
}
