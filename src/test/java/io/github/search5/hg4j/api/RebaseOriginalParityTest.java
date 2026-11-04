package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same rebase run by Mercurial and by hg4j on two copies of one repository ends in the same history: same nodes (so same parents,
 * messages, users, dates and branches), the same working copy parent, and the state files gone.
 */
class RebaseOriginalParityTest {

    private static String editorOverride;

    private static String hg(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 4];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "extensions.rebase=";
        cmd[3] = "--config";
        // no editor, no pager, no interactive merge tool
        String[] full = new String[cmd.length + 2 + args.length - 0];
        int i = 0;
        full[i++] = "hg";
        full[i++] = "--config";
        full[i++] = "extensions.rebase=";
        full[i++] = "--config";
        full[i++] = "ui.merge=internal:merge";
        full[i++] = "--config";
        full[i++] = "ui.interactive=false";
        for (String a : args) {
            full[i++] = a;
        }
        String[] exec = java.util.Arrays.copyOf(full, i);
        ProcessBuilder pb = new ProcessBuilder(exec).directory(dir).redirectErrorStream(true);
        pb.environment().put("HGPLAIN", "1");
        pb.environment().put("HGUSER", "t");
        pb.environment().put("HGEDITOR", editorOverride != null ? editorOverride : "true"); // by default an editor that leaves the proposed message as it is
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

    /**
     * base - src1 - src2 (feature work, different files) and base - dest; rebase src1 onto dest. Returns the two logs.
     */
    private void scenario(Path tmp, String name, String setup, String hgArgs, boolean viaHg4j, String hg4jSourceRev, String hg4jTargetRev,
                          boolean keepBranches) throws Exception {
        // not used directly
    }

    @Test
    void aLinearStackAndAMergeRebaseToTheSameHistoryAsMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File orig = tmp.resolve("orig").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "src1\n\nsecond paragraph", "-d", "2000 0");
        write(orig, "g.txt", "two\n");
        hg(orig, "commit", "-A", "-m", "src2", "-d", "3000 0");
        hg(orig, "update", "0");
        write(orig, "d.txt", "dest\n");
        hg(orig, "commit", "-A", "-m", "dest", "-d", "4000 0");
        hg(orig, "update", "2");

        Path viaHg = tmp.resolve("viahg");
        Path viaJava = tmp.resolve("viajava");
        copy(orig.toPath(), viaHg);
        copy(orig.toPath(), viaJava);

        String hgOut = hg(viaHg.toFile(), "rebase", "-s", "1", "-d", "3");

        HgRepository repo = Hg.open(viaJava.toFile()).getRepository();
        String log = hg(viaJava.toFile(), "log", "-T", "{rev}:{node}\n");
        String src = log.lines().filter(l -> l.startsWith("1:")).findFirst().orElseThrow().substring(2);
        String dest = log.lines().filter(l -> l.startsWith("3:")).findFirst().orElseThrow().substring(2);
        RebaseCommand cmd = new RebaseCommand(repo).setSource(NodeIdUtil.fromHex(src)).setTarget(NodeIdUtil.fromHex(dest));
        cmd.call();

        assertEquals(hg(viaHg.toFile(), "debugdata", "-c", "2") + hg(viaHg.toFile(), "debugdata", "-m", "2"),
                hg(viaJava.toFile(), "debugdata", "-c", "2") + hg(viaJava.toFile(), "debugdata", "-m", "2"), "changelog and manifest text");
        String template = "{rev}:{node} {p1node} {p2node} {branch} {author} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg.toFile(), "log", "-T", template), hg(viaJava.toFile(), "log", "-T", template), "history; hg said: " + hgOut);
        assertEquals(hg(viaHg.toFile(), "log", "-r", ".", "-T", "{node}"), hg(viaJava.toFile(), "log", "-r", ".", "-T", "{node}"), "working copy parent");
        assertEquals(hg(viaHg.toFile(), "status"), hg(viaJava.toFile(), "status"));
        assertFalse(new File(viaJava.toFile(), ".hg/rebasestate").exists());
        String verify = hg(viaJava.toFile(), "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);
    }

    /** base - src1(f=X) - src2(g) and base - dest(f=Y): src1 conflicts on f.txt. */
    private File conflictRepo(Path tmp, String name) throws Exception {
        File dir = tmp.resolve(name).toFile();
        assertTrue(dir.mkdirs());
        hg(dir, "init");
        write(dir, "f.txt", "a\nb\nc\n");
        hg(dir, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(dir, "f.txt", "a\nX\nc\n");
        hg(dir, "commit", "-m", "src1", "-d", "2000 0");
        write(dir, "g.txt", "g\n");
        hg(dir, "commit", "-A", "-m", "src2", "-d", "3000 0");
        hg(dir, "update", "0");
        write(dir, "f.txt", "a\nY\nc\n");
        hg(dir, "commit", "-m", "dest", "-d", "4000 0");
        hg(dir, "update", "2");
        return dir;
    }

    private static String nodeOfRev(File dir, int rev) throws Exception {
        return hg(dir, "log", "-r", String.valueOf(rev), "-T", "{node}").trim();
    }

    private static String listing(File dir) throws Exception {
        return hg(dir, "log", "-T", "{rev}:{node} {p1node} {desc|json}\n") + "wc=" + hg(dir, "log", "-r", ".", "-T", "{node}")
                + "\n" + hg(dir, "status");
    }

    @Test
    void aConflictStopsWithTheStateFileMercurialWritesAndAbortRestoresEverything(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File viaHg = conflictRepo(tmp, "a");
        copy(viaHg.toPath(), tmp.resolve("b"));
        File viaJava = tmp.resolve("b").toFile();
        String before = listing(viaHg);

        String out = hg(viaHg, "rebase", "-s", "1", "-d", "3");
        assertTrue(out.contains("unresolved conflicts"), out);
        RebaseCommand cmd = new RebaseCommand(Hg.open(viaJava).getRepository())
                .setSource(NodeIdUtil.fromHex(nodeOfRev(viaJava, 1))).setTarget(NodeIdUtil.fromHex(nodeOfRev(viaJava, 3)));
        assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, cmd::call);

        assertEquals(Files.readString(new File(viaHg, ".hg/rebasestate").toPath()), Files.readString(new File(viaJava, ".hg/rebasestate").toPath()),
                ".hg/rebasestate");
        assertEquals(Files.readString(new File(viaHg, "f.txt").toPath()), Files.readString(new File(viaJava, "f.txt").toPath()), "conflict markers");
        assertEquals(hg(viaHg, "resolve", "-l"), hg(viaJava, "resolve", "-l"));

        hg(viaHg, "rebase", "--abort");
        new RebaseCommand(Hg.open(viaJava).getRepository()).abort();
        assertEquals(listing(viaHg), listing(viaJava), "after abort, hg vs hg4j");
        assertEquals(before.replace("wc=", "wc="), listing(viaHg).replace("? f.txt.orig\n", ""), "hg after abort is the start");
        assertFalse(new File(viaJava, ".hg/rebasestate").exists());
        assertFalse(new File(viaJava, ".hg/merge").exists() && new File(viaJava, ".hg/merge").list().length > 0);
    }

    @Test
    void continueAfterResolvingEndsInTheSameHistoryAsMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File viaHg = conflictRepo(tmp, "a");
        copy(viaHg.toPath(), tmp.resolve("b"));
        File viaJava = tmp.resolve("b").toFile();

        hg(viaHg, "rebase", "-s", "1", "-d", "3");
        HgRepository repo = Hg.open(viaJava).getRepository();
        assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, () -> new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(nodeOfRev(viaJava, 1))).setTarget(NodeIdUtil.fromHex(nodeOfRev(viaJava, 3))).call());

        write(viaHg, "f.txt", "a\nXY\nc\n");
        write(viaJava, "f.txt", "a\nXY\nc\n");
        hg(viaHg, "resolve", "-m", "f.txt");
        new ResolveCommand(repo).setFile("f.txt").markResolved(true).call();

        String out = hg(viaHg, "rebase", "--continue");
        new RebaseCommand(repo).continueRebase();
        String template = "{rev}:{node} {p1node} {p2node} {branch} {author} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template), "history; hg said: " + out);
        assertEquals(hg(viaHg, "log", "-r", ".", "-T", "{node}"), hg(viaJava, "log", "-r", ".", "-T", "{node}"));
        assertEquals(hg(viaHg, "status"), hg(viaJava, "status"));
        assertFalse(new File(viaJava, ".hg/rebasestate").exists());
        String verify = hg(viaJava, "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);
    }

    @Test
    void stopKeepsWhatWasDoneAndGoesBackToTheStartLikeMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File viaHg = conflictRepo(tmp, "a");
        copy(viaHg.toPath(), tmp.resolve("b"));
        File viaJava = tmp.resolve("b").toFile();

        hg(viaHg, "rebase", "-s", "1", "-d", "3", "--keep");
        HgRepository repo = Hg.open(viaJava).getRepository();
        assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, () -> new RebaseCommand(repo).setKeep(true)
                .setSource(NodeIdUtil.fromHex(nodeOfRev(viaJava, 1))).setTarget(NodeIdUtil.fromHex(nodeOfRev(viaJava, 3))).call());
        assertEquals(Files.readString(new File(viaHg, ".hg/rebasestate").toPath()), Files.readString(new File(viaJava, ".hg/rebasestate").toPath()));

        hg(viaHg, "rebase", "--stop");
        new RebaseCommand(repo).stop();
        assertEquals(listing(viaHg), listing(viaJava));
        assertFalse(new File(viaJava, ".hg/rebasestate").exists());

        // without --keep the original refuses to stop
        File plainHg = conflictRepo(tmp, "c");
        hg(plainHg, "rebase", "-s", "1", "-d", "3");
        String refused = hg(plainHg, "rebase", "--stop");
        assertTrue(refused.contains("cannot remove original changesets with unrebased descendants"), refused);
    }

    /** Each way of choosing the changesets (-s, -b, -r, with the working copy at different places) gives Mercurial's history and messages. */
    @Test
    void theWaysOfChoosingWhatToRebaseMatchMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File orig = tmp.resolve("orig").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "src1", "-d", "2000 0");
        write(orig, "g.txt", "two\n");
        hg(orig, "commit", "-A", "-m", "src2", "-d", "3000 0");
        write(orig, "h.txt", "three\n");
        hg(orig, "commit", "-A", "-m", "src3", "-d", "3500 0");
        hg(orig, "update", "0");
        write(orig, "d.txt", "dest\n");
        hg(orig, "commit", "-A", "-m", "dest", "-d", "4000 0");
        // revs: 0 base, 1 src1, 2 src2, 3 src3, 4 dest
        hg(orig, "bookmark", "-r", "2", "bmA");
        hg(orig, "tag", "-l", "-r", "3", "tg1");

        String[][] cases = {
                {"-s", "2", "-d", "4"},
                {"-r", "2", "-d", "4"},
                {"-r", "1+3", "-d", "4"},
                {"-b", "3", "-d", "4"},
                {"-b", "4", "-d", "4"},
                {"-s", "1", "-d", "0"},
                {"-r", "0", "-d", "4"},
                {"-s", "2", "-d", "1"},
                {"-s", "1"},
                {"-r", "1+2+3"},
                {"-b", "3"},
                {"-s", "4"},
                {"-s", "1", "-d", "4", "--keep"},
                {"-s", "2", "-d", "4", "--keep"},
                {"-s", "1", "-d", "4", "--collapse"},
                {"-s", "1", "-d", "4", "--collapse", "-m", "joined"},
                {"-r", "1+2", "-d", "4", "--collapse", "-m", "two of three"},
        };
        String template = "{rev}:{node} {p1node} {p2node} {branch} {date|hgdate} {desc|json}\n";
        int n = 0;
        int compared = 0;
        for (String[] c : cases) {
            Path viaHg = tmp.resolve("hg" + n);
            Path viaJava = tmp.resolve("java" + n);
            n++;
            copy(orig.toPath(), viaHg);
            copy(orig.toPath(), viaJava);
            hg(viaHg.toFile(), "update", "3");
            hg(viaJava.toFile(), "update", "3");
            String hgOut = hg(viaHg.toFile(), concat("rebase", c));

            RebaseCommand cmd = new RebaseCommand(Hg.open(viaJava.toFile()).getRepository());
            for (int i = 0; i < c.length; i += 2) {
                switch (c[i]) {
                    case "-s" -> cmd.addSourceRevset(c[i + 1]);
                    case "-b" -> cmd.addBaseRevset(c[i + 1]);
                    case "-r" -> cmd.addRevisionRevset(c[i + 1]);
                    case "--collapse" -> {
                        cmd.setCollapse(true);
                        i--; // a flag has no value
                    }
                    case "--keep" -> {
                        cmd.setKeep(true);
                        i--;
                    }
                    case "-m" -> cmd.setMessage(c[i + 1]);
                    default -> cmd.setDestination(c[i + 1]);
                }
            }
            try {
                cmd.call();
            } catch (io.github.search5.hg4j.errors.HgValidationException e) {
                assertTrue(hgOut.contains("abort"), String.join(" ", c) + ": hg4j refused (" + e.getMessage() + "), hg said " + hgOut);
                continue;
            }
            String label = String.join(" ", c);
            compared++;
            assertEquals(hg(viaHg.toFile(), "bookmarks"), hg(viaJava.toFile(), "bookmarks"), label + " bookmarks");
            assertEquals(hg(viaHg.toFile(), "log", "-T", template), hg(viaJava.toFile(), "log", "-T", template), label + "; hg said: " + hgOut);
            assertEquals(hg(viaHg.toFile(), "log", "-r", ".", "-T", "{node}"), hg(viaJava.toFile(), "log", "-r", ".", "-T", "{node}"), label + " working copy");
            String hgLines = hgOut.lines().filter(l -> !l.startsWith("saved backup bundle")).collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(hgLines, String.join("", cmd.getMessages()).strip(), label + " messages");
        }
        assertTrue(compared >= 13, "cases where both ran: " + compared);
    }

    private static String[] concat(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    @Test
    void aCollapseThatStopsOnAConflictContinuesAndAbortsLikeMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        for (String how : new String[] {"continue", "abort"}) {
            File viaHg = conflictRepo(tmp, "a" + how);
            copy(viaHg.toPath(), tmp.resolve("b" + how));
            File viaJava = tmp.resolve("b" + how).toFile();
            String before = listing(viaHg);

            hg(viaHg, "rebase", "-s", "1", "-d", "3", "--collapse", "-m", "all in one");
            HgRepository repo = Hg.open(viaJava).getRepository();
            assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, () -> new RebaseCommand(repo).setCollapse(true)
                    .setMessage("all in one").addSourceRevset("1").setDestination("3").call());
            assertEquals(Files.readString(new File(viaHg, ".hg/rebasestate").toPath()), Files.readString(new File(viaJava, ".hg/rebasestate").toPath()),
                    ".hg/rebasestate");
            assertEquals(Files.readString(new File(viaHg, ".hg/last-message.txt").toPath()),
                    Files.readString(new File(viaJava, ".hg/last-message.txt").toPath()), "last-message.txt");
            if (how.equals("continue")) {
                write(viaHg, "f.txt", "a\nXY\nc\n");
                write(viaJava, "f.txt", "a\nXY\nc\n");
                hg(viaHg, "resolve", "-m", "f.txt");
                new ResolveCommand(repo).setFile("f.txt").markResolved(true).call();
                hg(viaHg, "rebase", "--continue");
                new RebaseCommand(repo).continueRebase();
                String template = "{rev}:{node} {p1node} {p2node} {date|hgdate} {desc|json}\n";
                assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template), "history after continue");
                assertEquals(hg(viaHg, "status"), hg(viaJava, "status"));
            } else {
                hg(viaHg, "rebase", "--abort");
                new RebaseCommand(repo).abort();
                assertEquals(listing(viaHg), listing(viaJava));
            }
            assertFalse(new File(viaJava, ".hg/rebasestate").exists());
            assertFalse(new File(viaJava, ".hg/last-message.txt").exists());
        }
    }

    @Test
    void oneTransactionForTheWholeRebaseGivesMercurialsHistoryAndStateFile(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        // a clean stack
        File orig = tmp.resolve("orig").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "src1", "-d", "2000 0");
        write(orig, "g.txt", "two\n");
        hg(orig, "commit", "-A", "-m", "src2", "-d", "3000 0");
        hg(orig, "update", "0");
        write(orig, "d.txt", "dest\n");
        hg(orig, "commit", "-A", "-m", "dest", "-d", "4000 0");
        copy(orig.toPath(), tmp.resolve("hg"));
        copy(orig.toPath(), tmp.resolve("java"));
        File viaHg = tmp.resolve("hg").toFile();
        File viaJava = tmp.resolve("java").toFile();
        hg(viaHg, "--config", "rebase.singletransaction=true", "rebase", "-s", "1", "-d", "3");
        new RebaseCommand(Hg.open(viaJava).getRepository()).setSingleTransaction(true).addSourceRevset("1").setDestination("3").call();
        String template = "{rev}:{node} {p1node} {p2node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template));
        assertFalse(new File(viaJava, ".hg/store/journal").exists());
        String verify = hg(viaJava, "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);

        // a conflict closes the transaction: what was done stays, the state file is written then
        File c1 = conflictRepo(tmp, "c1");
        copy(c1.toPath(), tmp.resolve("c2"));
        File c2 = tmp.resolve("c2").toFile();
        hg(c1, "--config", "rebase.singletransaction=true", "rebase", "-s", "1", "-d", "3");
        assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, () -> new RebaseCommand(Hg.open(c2).getRepository())
                .setSingleTransaction(true).addSourceRevset("1").setDestination("3").call());
        assertEquals(Files.readString(new File(c1, ".hg/rebasestate").toPath()), Files.readString(new File(c2, ".hg/rebasestate").toPath()));
        assertFalse(new File(c2, ".hg/store/journal").exists());
    }

    @Test
    void aFailureInTheMiddleOfOneTransactionTakesBackEveryCommit(@TempDir Path tmp) throws Exception {
        File dir = tmp.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        write(dir, "a.txt", "a\n");
        new AddCommand(repo).call();
        byte[] base = new CommitCommand(repo).setAuthor("t").setMessage("base").call();
        write(dir, "f.txt", "one\n");
        new AddCommand(repo).call();
        byte[] src1 = new CommitCommand(repo).setAuthor("t").setMessage("src1").call();
        write(dir, "g.txt", "two\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("t").setMessage("src2").call();
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).setForce(true).call();
        write(dir, "d.txt", "dest\n");
        new AddCommand(repo).call();
        byte[] dest = new CommitCommand(repo).setAuthor("t").setMessage("dest").call();
        // an untracked directory where the second changeset must write a file
        Files.createDirectories(new File(dir, "g.txt").toPath());
        Files.writeString(new File(dir, "g.txt/x").toPath(), "x");

        io.github.search5.hg4j.storage.Revlog before = new io.github.search5.hg4j.storage.Revlog(new File(repo.getStoreDir(), "00changelog.i"),
                new File(repo.getStoreDir(), "00changelog.d"));
        int count = before.getRevisionCount();
        assertThrows(Exception.class, () -> new RebaseCommand(repo).setSingleTransaction(true).setSource(src1).setTarget(dest).call());
        io.github.search5.hg4j.storage.Revlog after = new io.github.search5.hg4j.storage.Revlog(new File(repo.getStoreDir(), "00changelog.i"),
                new File(repo.getStoreDir(), "00changelog.d"));
        assertEquals(count, after.getRevisionCount(), "the commit of the first changeset is taken back with the failed transaction");
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
        assertTrue(new File(repo.getHgDir(), "rebasestate").isFile(), "the state written at the start stays so that --abort works");
    }

    @Test
    void aDryRunSaysWhatMercurialSaysAndLeavesEverythingAsItWas(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        // a clean stack, and a conflicting one
        for (String kind : new String[] {"clean", "conflict", "clean-collapse", "conflict-collapse"}) {
            File viaHg;
            if (kind.startsWith("conflict")) {
                viaHg = conflictRepo(tmp, "x" + kind);
            } else {
                viaHg = tmp.resolve("x" + kind).toFile();
                assertTrue(viaHg.mkdirs());
                hg(viaHg, "init");
                write(viaHg, "a.txt", "a\n");
                hg(viaHg, "commit", "-A", "-m", "base", "-d", "1000 0");
                write(viaHg, "f.txt", "one\n");
                hg(viaHg, "commit", "-A", "-m", "src1", "-d", "2000 0");
                write(viaHg, "g.txt", "two\n");
                hg(viaHg, "commit", "-A", "-m", "src2", "-d", "3000 0");
                hg(viaHg, "update", "0");
                write(viaHg, "d.txt", "dest\n");
                hg(viaHg, "commit", "-A", "-m", "dest", "-d", "4000 0");
                hg(viaHg, "update", "2");
            }
            copy(viaHg.toPath(), tmp.resolve("y" + kind));
            File viaJava = tmp.resolve("y" + kind).toFile();
            write(viaHg, "dirty.txt", "not tracked\n");
            write(viaJava, "dirty.txt", "not tracked\n");
            String before = listing(viaJava);
            String storeBefore = hg(viaJava, "log", "-T", "{rev}:{node}\n");

            boolean collapse = kind.endsWith("-collapse");
            String hgOut = collapse ? hg(viaHg, "rebase", "-n", "--collapse", "-s", "1", "-d", "3") : hg(viaHg, "rebase", "-n", "-s", "1", "-d", "3");
            RebaseCommand cmd = new RebaseCommand(Hg.open(viaJava).getRepository()).setDryRun(true).setCollapse(collapse).addSourceRevset("1").setDestination("3");
            cmd.call();
            assertEquals(hgOut.strip(), String.join("", cmd.getMessages()).strip(), kind);
            assertEquals(before, listing(viaJava), kind + ": nothing changed");
            assertEquals(storeBefore, hg(viaJava, "log", "-T", "{rev}:{node}\n"));
            assertFalse(new File(viaJava, ".hg/rebasestate").exists());
            assertFalse(new File(viaJava, ".hg/store/journal").exists());
            assertEquals("", hg(viaJava, "verify").contains("error") ? "verify failed" : "");
        }
    }

    @Test
    void confirmAppliesTheRebaseOnlyWhenAnswered(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File orig = tmp.resolve("o").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "src1", "-d", "2000 0");
        hg(orig, "update", "0");
        write(orig, "d.txt", "dest\n");
        hg(orig, "commit", "-A", "-m", "dest", "-d", "4000 0");
        hg(orig, "update", "1");
        copy(orig.toPath(), tmp.resolve("no"));
        copy(orig.toPath(), tmp.resolve("yes"));
        copy(orig.toPath(), tmp.resolve("real"));
        String start = listing(tmp.resolve("no").toFile());

        RebaseCommand no = new RebaseCommand(Hg.open(tmp.resolve("no").toFile()).getRepository()).setConfirm(() -> false)
                .addSourceRevset("1").setDestination("2");
        no.call();
        assertEquals(start, listing(tmp.resolve("no").toFile()));
        assertTrue(String.join("", no.getMessages()).startsWith("starting in-memory rebase\nrebasing "));
        assertTrue(String.join("", no.getMessages()).contains("rebase completed successfully\n"));

        new RebaseCommand(Hg.open(tmp.resolve("yes").toFile()).getRepository()).setConfirm(() -> true)
                .addSourceRevset("1").setDestination("2").call();
        hg(tmp.resolve("real").toFile(), "rebase", "-s", "1", "-d", "2");
        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(tmp.resolve("real").toFile(), "log", "-T", template), hg(tmp.resolve("yes").toFile(), "log", "-T", template));
    }

    @Test
    void aChangesetWhoseChangesAreAlreadyInTheDestinationIsSkippedLikeMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File orig = tmp.resolve("o").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "b.txt", "same\n");
        hg(orig, "commit", "-A", "-m", "src1", "-d", "2000 0");
        hg(orig, "bookmark", "onsrc1");
        write(orig, "c.txt", "more\n");
        hg(orig, "commit", "-A", "-m", "src2", "-d", "3000 0");
        hg(orig, "update", "0");
        write(orig, "b.txt", "same\n");
        hg(orig, "commit", "-A", "-m", "dest has b", "-d", "4000 0");
        hg(orig, "update", "2");
        copy(orig.toPath(), tmp.resolve("hg"));
        copy(orig.toPath(), tmp.resolve("java"));
        File viaHg = tmp.resolve("hg").toFile();
        File viaJava = tmp.resolve("java").toFile();

        String hgOut = hg(viaHg, "rebase", "-s", "1", "-d", "3");
        RebaseCommand cmd = new RebaseCommand(Hg.open(viaJava).getRepository()).addSourceRevset("1").setDestination("3");
        cmd.call();
        assertEquals(hgOut.lines().filter(l -> !l.startsWith("saved backup bundle")).collect(java.util.stream.Collectors.joining("\n")),
                String.join("", cmd.getMessages()).strip());
        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template));
        assertEquals(hg(viaHg, "bookmarks"), hg(viaJava, "bookmarks"));
        assertEquals(hg(viaHg, "log", "-r", ".", "-T", "{node}"), hg(viaJava, "log", "-r", ".", "-T", "{node}"));
        String verify = hg(viaJava, "verify");
        assertFalse(verify.contains("error") || verify.contains("abort"), verify);
    }

    @Test
    void theEditorIsRunForACollapseWithoutAMessageAndForEditLikeMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File script = tmp.resolve("editor.sh").toFile();
        Files.writeString(script.toPath(), "#!/bin/sh\nsed -i '1s/.*/EDITED/' \"$1\"\n");
        assertTrue(script.setExecutable(true));
        File orig = tmp.resolve("o").toFile();
        assertTrue(orig.mkdirs());
        hg(orig, "init");
        write(orig, "a.txt", "a\n");
        hg(orig, "commit", "-A", "-m", "base", "-d", "1000 0");
        write(orig, "f.txt", "one\n");
        hg(orig, "commit", "-A", "-m", "src1", "-d", "2000 0");
        write(orig, "g.txt", "two\n");
        hg(orig, "commit", "-A", "-m", "src2", "-d", "3000 0");
        hg(orig, "update", "0");
        write(orig, "d.txt", "dest\n");
        hg(orig, "commit", "-A", "-m", "dest", "-d", "4000 0");
        hg(orig, "update", "2");
        java.util.function.UnaryOperator<String> sameEditor = text -> "EDITED" + text.substring(text.indexOf('\n') < 0 ? text.length() : text.indexOf('\n'));
        String template = "{rev}:{node} {p1node} {date|hgdate} {desc|json}\n";
        editorOverride = script.getPath();
        try {
            for (String mode : new String[] {"collapse", "edit"}) {
                copy(orig.toPath(), tmp.resolve("hg-" + mode));
                copy(orig.toPath(), tmp.resolve("java-" + mode));
                File viaHg = tmp.resolve("hg-" + mode).toFile();
                File viaJava = tmp.resolve("java-" + mode).toFile();
                RebaseCommand cmd = new RebaseCommand(Hg.open(viaJava).getRepository()).addSourceRevset("1").setDestination("3")
                        .setMessageEditor(sameEditor);
                if (mode.equals("collapse")) {
                    hg(viaHg, "rebase", "-s", "1", "-d", "3", "--collapse");
                    cmd.setCollapse(true);
                } else {
                    hg(viaHg, "rebase", "-s", "1", "-d", "3", "-e");
                    cmd.setEdit(true);
                }
                cmd.call();
                assertEquals(hg(viaHg, "log", "-T", template), hg(viaJava, "log", "-T", template), mode);
            }
        } finally {
            editorOverride = null;
        }
    }
}
