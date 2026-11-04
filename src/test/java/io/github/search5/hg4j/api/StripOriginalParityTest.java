package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The same strip run by Mercurial and by hg4j on two copies of one repository: same history, working copy, bookmarks, and a clean verify. */
class StripOriginalParityTest {

    private static String hg(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "hg";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HGPLAIN", "1");
        pb.environment().put("HGUSER", "t");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    private static boolean hgAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(File dir, String name, String text) throws Exception {
        Files.writeString(new File(dir, name).toPath(), text);
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    @Test
    void aStripThatKeepsChangesetsAboveTheCutMatchesMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File orig = tmp.resolve("orig").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "a1", "-d", "2000 0");
        write(orig, "g.txt", "two\n");
        hg(orig, "commit", "-A", "-m", "a2", "-d", "3000 0");
        hg(orig, "bookmark", "mark");
        hg(orig, "update", "0");
        write(orig, "d.txt", "side\n");
        hg(orig, "commit", "-A", "-m", "side", "-d", "4000 0");
        hg(orig, "update", "2");
        copy(orig.toPath(), tmp.resolve("hg"));
        copy(orig.toPath(), tmp.resolve("java"));
        File viaHg = tmp.resolve("hg").toFile();
        File viaJava = tmp.resolve("java").toFile();

        String hgOut = hg(viaHg, "--config", "extensions.strip=", "strip", "-r", "1");
        String node = hg(viaJava, "log", "-r", "1", "-T", "{node}");
        StripCommand strip = new StripCommand(Hg.open(viaJava).getRepository()).setRevision(node);
        strip.call();

        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template), "history; hg said: " + hgOut);
        assertEquals(hg(viaHg, "log", "-r", ".", "-T", "{node}"), hg(viaJava, "log", "-r", ".", "-T", "{node}"), "working copy parent");
        assertEquals(hg(viaHg, "bookmarks"), hg(viaJava, "bookmarks"));
        assertEquals(hg(viaHg, "status"), hg(viaJava, "status"));
        String verify = hg(viaJava, "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);
        assertEquals(hgOut.lines().filter(l -> l.startsWith("saved backup bundle")).count(),
                strip.getMessages().stream().filter(l -> l.startsWith("saved backup bundle")).count());
        assertFalse(new File(viaJava, ".hg/store/journal").exists());
        assertFalse(new File(viaJava, ".hg/store/obsstore").exists());
        assertEquals(new File(viaHg, ".hg/strip-backup").list().length, new File(viaJava, ".hg/strip-backup").list().length,
                "the same files in strip-backup (the backup bundle, no temporary bundle left)");
    }

    private File forkedRepo(Path tmp, String name) throws Exception {
        File dir = tmp.resolve(name).toFile();
        assertTrue(dir.mkdirs());
        hg(dir, "init");
        write(dir, "a.txt", "a\n");
        hg(dir, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(dir, "x1.txt", "1\n");
        hg(dir, "commit", "-A", "-m", "x1", "-d", "2000 0");
        write(dir, "x2.txt", "2\n");
        hg(dir, "commit", "-A", "-m", "x2", "-d", "3000 0");
        hg(dir, "bookmark", "feat");
        hg(dir, "update", "0");
        write(dir, "y.txt", "y\n");
        hg(dir, "commit", "-A", "-m", "y", "-d", "4000 0");
        hg(dir, "bookmark", "other");
        hg(dir, "update", "other");
        return dir;
    }

    private void compare(Path tmp, String label, java.util.function.Consumer<StripCommand> configure, String... hgArgs) throws Exception {
        File original = forkedRepo(tmp, "o-" + label);
        copy(original.toPath(), tmp.resolve("hg-" + label));
        copy(original.toPath(), tmp.resolve("java-" + label));
        File viaHg = tmp.resolve("hg-" + label).toFile();
        File viaJava = tmp.resolve("java-" + label).toFile();
        String[] cmd = new String[hgArgs.length + 3];
        cmd[0] = "--config";
        cmd[1] = "extensions.strip=";
        cmd[2] = "strip";
        System.arraycopy(hgArgs, 0, cmd, 3, hgArgs.length);
        String hgOut = hg(viaHg, cmd);
        StripCommand strip = new StripCommand(Hg.open(viaJava).getRepository());
        configure.accept(strip);
        strip.call();
        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template), label + " history; hg said: " + hgOut);
        assertEquals(hg(viaHg, "bookmarks"), hg(viaJava, "bookmarks"), label + " bookmarks");
        assertEquals(hg(viaHg, "log", "-r", ".", "-T", "{node}"), hg(viaJava, "log", "-r", ".", "-T", "{node}"), label + " working copy");
        assertEquals(hgOut.lines().filter(l -> !l.startsWith("saved backup bundle") && !l.contains("files updated")).toList(),
                strip.getMessages().stream().map(String::strip).filter(l -> !l.startsWith("saved backup bundle")).toList(), label + " messages");
        String verify = hg(viaJava, "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);
    }

    @Test
    void severalRevisionsAndBookmarksMatchMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        compare(tmp, "two", s -> s.setRevision("1").addRevision("3"), "1", "3");
        compare(tmp, "bookmark", s -> s.addBookmark("feat"), "-B", "feat");
        compare(tmp, "revset", s -> s.setRevision("1::2"), "-r", "1::2");
    }

    @Test
    void anEmptyRevisionSetIsRefusedAndTheSoftStripIsNotAvailable(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File repoDir = forkedRepo(tmp, "empty");
        StripCommand soft = new StripCommand(Hg.open(repoDir).getRepository()).setRevision("1").setSoft(true);
        assertThrows(io.github.search5.hg4j.errors.HgValidationException.class, soft::call);
        // `debugstrip` / --permit-empty-revset: nothing to do is success
        new StripCommand(Hg.open(repoDir).getRepository()).addBookmark("feat").setPermitEmptyRevset(true);
    }

    @Test
    void aStripOfAVersion2ChangelogMatchesMercurialAndItsJournalCompletesTheCut(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        String[] v2 = {"--config", "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data"};
        File orig = tmp.resolve("orig").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, concat(v2, "init"));
        for (int i = 0; i < 4; i++) {
            write(orig, "f" + i + ".txt", "content " + i + "\n");
            hg(orig, concat(v2, "commit", "-A", "-m", "c" + i, "-d", (1000 * (i + 1)) + " 0"));
        }
        copy(orig.toPath(), tmp.resolve("hg"));
        copy(orig.toPath(), tmp.resolve("java"));
        copy(orig.toPath(), tmp.resolve("crash"));
        File viaHg = tmp.resolve("hg").toFile();
        File viaJava = tmp.resolve("java").toFile();

        hg(viaHg, concat(v2, "--config", "extensions.strip=", "strip", "-r", "2"));
        new StripCommand(Hg.open(viaJava).getRepository()).setRevision(hg(viaJava, concat(v2, "log", "-r", "2", "-T", "{node}"))).call();
        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, concat(v2, "log", "-T", template)), hg(viaJava, concat(v2, "log", "-T", template)));
        String verify = hg(viaJava, concat(v2, "verify"));
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);

        // a strip that stops after the cut has started: its journal knows where the files end, docket included
        File crash = tmp.resolve("crash").toFile();
        String crashNode = hg(crash, concat(v2, "log", "-r", "2", "-T", "{node}"));
        File phaseroots = new File(crash, ".hg/store/phaseroots");
        Files.writeString(phaseroots.toPath(), "1 abc\n"); // cannot be parsed: fails once the files have been cut
        StripCommand failing = new StripCommand(Hg.open(crash).getRepository()).setRevision(crashNode);
        assertThrows(IllegalArgumentException.class, failing::call);
        Files.delete(phaseroots.toPath());
        new RecoverCommand(Hg.open(crash).getRepository()).call();
        String after = hg(crash, concat(v2, "log", "-T", template));
        assertEquals(hg(viaHg, concat(v2, "log", "-T", template)), after, "recovering completes the cut");
        String crashVerify = hg(crash, concat(v2, "verify"));
        assertFalse(crashVerify.contains("error") || crashVerify.contains("abort"), crashVerify);
    }

    private static String[] concat(String[] first, String... rest) {
        String[] out = new String[first.length + rest.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(rest, 0, out, first.length, rest.length);
        return out;
    }
}
