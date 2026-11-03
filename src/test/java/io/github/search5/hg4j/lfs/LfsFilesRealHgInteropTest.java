package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.VerifyCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 7: the LFS files of a changeset ({@code lfs()} fileset, {@code lfs_files} template of real hg) and the
 * {@code verify} behavior for an LFS blob that is not stored locally.
 */
@Tag("interop")
class LfsFilesRealHgInteropTest {

    @TempDir
    Path tempDir;

    private File repo;
    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private String hg(File dir, String... args) throws Exception {
        return HgTestUtils.hg(dir, concat(LFS, args));
    }

    private void write(String name, int size, long seed) throws Exception {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Files.write(new File(repo, name).toPath(), data);
    }

    /**
     * rev 0: adds big.bin (LFS) and small.txt; rev 1: changes big.bin, adds other.bin (LFS); rev 2: removes big.bin;
     * rev 3: changes small.txt only.
     */
    @BeforeEach
    void realHgHistory() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        hg(repo, "init");
        write("big.bin", 500, 1);
        Files.writeString(new File(repo, "small.txt").toPath(), "small\n");
        hg(repo, "add");
        hg(repo, "commit", "-m", "r0", "-u", "t");
        write("big.bin", 520, 2);
        write("other.bin", 600, 3);
        hg(repo, "add");
        hg(repo, "commit", "-m", "r1", "-u", "t");
        hg(repo, "rm", "big.bin");
        hg(repo, "commit", "-m", "r2", "-u", "t");
        Files.writeString(new File(repo, "small.txt").toPath(), "small again\n");
        hg(repo, "commit", "-m", "r3", "-u", "t");
    }

    @Test
    void changedFilesMatchTheLfsFilesTemplate() throws Exception {
        HgRepository repository = new HgRepository(repo);
        for (int rev = 0; rev <= 3; rev++) {
            // "{file} {lfsoid}" per LFS file the changeset touched; a removed one has no oid
            String template = "{lfs_files % '{file} {lfsoid}\\n'}";
            Map<String, String> expected = new TreeMap<>();
            for (String line : hg(repo, "log", "-r", String.valueOf(rev), "--template", template).lines().toList()) {
                String[] parts = line.strip().split(" ", 2);
                expected.put(parts[0], parts.length > 1 ? parts[1].strip() : "");
            }
            Map<String, String> actual = new TreeMap<>();
            for (Map.Entry<String, HgLfsPointer> entry : HgLfsFiles.changedBy(repository, rev, true).entrySet()) {
                actual.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().getOid());
            }
            assertEquals(expected, actual, "lfs_files of revision " + rev);
        }
        assertFalse(HgLfsFiles.changedBy(repository, 2, true).isEmpty(), "rev 2 removed an LFS file, so it must be listed");
        assertNull(HgLfsFiles.changedBy(repository, 2, true).get("big.bin"), "the removed file has no pointer");
        assertTrue(HgLfsFiles.changedBy(repository, 2, false).isEmpty(), "without removed files there is nothing to list");
        assertTrue(HgLfsFiles.changedBy(repository, 3, true).isEmpty(), "rev 3 touched no LFS file");
    }

    @Test
    void theManifestsLfsFilesMatchTheLfsFileset() throws Exception {
        HgRepository repository = new HgRepository(repo);
        for (int rev = 0; rev <= 3; rev++) {
            TreeSet<String> expected = new TreeSet<>(hg(repo, "files", "-r", String.valueOf(rev), "set:lfs()").lines()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList());
            SortedMap<String, HgLfsPointer> actual = HgLfsFiles.inManifest(repository, rev);
            assertEquals(expected, new TreeSet<>(actual.keySet()), "lfs() at revision " + rev);
        }
    }

    /** A copy of the history in which only the pointers exist: real hg pushes with {@code lfs.url=null:} send no blob. */
    private File repoWithoutBlobs() throws Exception {
        File lacking = tempDir.resolve("lacking").toFile();
        lacking.mkdirs();
        hg(lacking, "init");
        hg(repo, "--config", "lfs.url=null:", "push", lacking.getAbsolutePath());
        return lacking;
    }

    @Test
    void verifyReportsABlobThatIsNotStoredLikeRealHgAndSkipsOnRequest() throws Exception {
        File lacking = repoWithoutBlobs();
        AssertionError real = assertThrows(AssertionError.class, () -> hg(lacking, "verify"));
        List<String> realErrors = real.getMessage().lines().filter(line -> line.contains("unpacking")).map(String::strip).toList();
        assertFalse(realErrors.isEmpty(), real.getMessage());

        List<String> ours = new VerifyCommand(new HgRepository(lacking)).call();
        List<String> oursUnpacking = ours.stream().filter(line -> line.contains("unpacking")).toList();
        assertEquals(realErrors.size(), oursUnpacking.size(), "one error per revision without its blob: " + ours);
        // after "<file>@<rev>: " the text is the original's
        for (String error : realErrors) {
            String tail = error.substring(error.indexOf("unpacking"));
            assertTrue(oursUnpacking.stream().anyMatch(line -> line.endsWith(tail)), tail + " missing in " + ours);
        }

        // --no-lfs and verify.skipflags: a blob that is not here is not read
        assertEquals(List.of(), new VerifyCommand(new HgRepository(lacking)).setSkipLfs(true).call());
        assertTrue(hg(lacking, "--config", "verify.skipflags=8192", "verify").contains("checked 4 changesets"));
        Files.writeString(new File(lacking, ".hg/hgrc").toPath(), "[verify]\nskipflags = 8192\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(List.of(), new VerifyCommand(new HgRepository(lacking)).call(), "verify.skipflags with EXTSTORED skips too");
    }

    /**
     * 의도적 차이 포팅 6번: {@code hg files "set:EXPR"} is answered by {@code FilesCommand#setFileset}, including the {@code lfs()}
     * predicate, for every revision and for the working copy.
     */
    @Test
    void filesCommandEvaluatesFilesetsWithLfsLikeRealHg() throws Exception {
        write("wd.bin", 700, 9);
        hg(repo, "add", "wd.bin"); // an added, uncommitted file that is not in LFS yet
        HgRepository repository = new HgRepository(repo);
        String[] expressions = {
                "lfs()", "not lfs()", "lfs() and **.bin", "lfs() or **.txt", "(**.bin or **.txt) - lfs()", "lfs() and not lfs()", "lfs() and size(\">550\")",
                "size(\"<100\")", "(lfs() or **.txt) and not path:other.bin",
        };
        for (String expression : expressions) {
            for (String rev : new String[]{"0", "1", "2", "3", null}) {
                List<String> realArgs = new java.util.ArrayList<>(List.of("files"));
                if (rev != null) {
                    realArgs.add("-r");
                    realArgs.add(rev);
                }
                realArgs.add("set:" + expression);
                List<String> expected;
                try {
                    expected = hg(repo, realArgs.toArray(String[]::new)).lines().map(String::strip)
                            .filter(line -> !line.isEmpty()).sorted().toList();
                } catch (AssertionError noMatch) {
                    // `hg files` exits with 1 when nothing matches
                    assertTrue(noMatch.getMessage().contains("exit code 1"), noMatch.getMessage());
                    expected = List.of();
                }
                io.github.search5.hg4j.api.FilesCommand command = new io.github.search5.hg4j.api.FilesCommand(repository)
                        .setFileset(expression);
                if (rev != null) {
                    command.setRevision(rev);
                }
                assertEquals(expected, command.call(), "set:" + expression + " at " + (rev == null ? "the working copy" : "revision " + rev));
            }
        }
        // the same through setPattern("set:...")
        assertEquals(List.of("other.bin"), new io.github.search5.hg4j.api.FilesCommand(repository).setRevision("3")
                .setPattern("set:lfs()").call());
    }
}
