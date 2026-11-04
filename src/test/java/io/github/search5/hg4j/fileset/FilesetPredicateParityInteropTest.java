package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파일셋 계획 3단계: every predicate selects the same files as in Mercurial 7.2.4, in two revisions and in the working directory
 * (where files are modified, added, removed, deleted, unknown, ignored and clean), with the same refusals for bad arguments.
 */
@Tag("interop")
class FilesetPredicateParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;

    private record Real(int exit, String out, String err) {
    }

    private Real hg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "ui.username=t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo).start();
        java.util.concurrent.CompletableFuture<String> err = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                return "";
            }
        });
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Real(p.waitFor(), out, err.get());
    }

    private void write(String name, byte[] data) throws Exception {
        File f = new File(repo, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), data);
    }

    private void write(String name, String text) throws Exception {
        write(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private void run(String... args) throws Exception {
        Real r = hg(args);
        assertEquals(0, r.exit(), String.join(" ", args) + ": " + r.err() + r.out());
    }

    @BeforeEach
    void history() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        run("init");
        write(".hgignore", "syntax: glob\n*.log\nbuild\nsyntax: re\n^tmp/\n");
        write("a.txt", "alpha\n");
        write("b.bin", new byte[] {0, 'x', 1});
        write("run.sh", "#!/bin/sh\n");
        new File(repo, "run.sh").setExecutable(true);
        Files.createSymbolicLink(new File(repo, "link").toPath(), Path.of("a.txt"));
        write("dos.txt", "a\r\nb\r\n");
        write("unix.txt", "a\nb\n");
        write("mac.txt", "a\rb\r");
        write("mixed.txt", "a\r\nb\nc\r");
        write("big.dat", "x".repeat(5000));
        write("latin1.txt", new byte[] {'c', 'a', 'f', (byte) 0xe9, '\n'});
        write("utf8.txt", "café\n");
        write("con.txt", "reserved\n");
        write("bad:name.txt", "colon\n");
        write("trail.", "dot\n");
        write("grepme.txt", "hello World 123\n");
        write("sub/d.txt", "deep\n");
        write("empty.txt", "");
        run("add");
        run("commit", "-m", "r0");
        write("a.txt", "alpha2\n");
        run("rm", "run.sh");
        run("cp", "a.txt", "a_copy.txt");
        run("mv", "unix.txt", "moved.txt");
        write("new.txt", "new\n");
        run("add", "new.txt");
        new File(repo, "b.bin").setExecutable(true);
        run("commit", "-m", "r1");
        // the working directory
        write("dos.txt", "a\r\nb\r\nmodified\r\n");
        write("added.txt", "added\n");
        run("add", "added.txt");
        run("rm", "mac.txt");
        Files.delete(new File(repo, "latin1.txt").toPath());
        write("untracked.txt", "unknown\n");
        write("x.log", "ignored by glob\n");
        write("build/out.o", "ignored dir\n");
        write("tmp/foo", "ignored by re\n");
        run("cp", "grepme.txt", "grep_copy.txt");
    }

    private static final String[] EXPRESSIONS = {
            "binary()", "exec()", "symlink()", "tracked()", "copied()", "portable()", "hgignore()",
            "size('>100')", "size('<10')", "size('0')", "size('5k')", "size('1k - 10k')", "size('>=5000')", "size('<=5000')", "size(4k)",
            "grep('hello')", "grep('World \\\\d+')", "grep('^alpha')", "grep('[aeiou]{2}')", "grep('caf')", "grep('\\\\r\\\\n')", "grep('')",
            "encoding('utf-8')", "encoding('ascii')", "encoding('latin1')", "encoding('utf8')",
            "eol('dos')", "eol('win')", "eol('unix')", "eol('mac')", "eol('other')",
            "modified()", "added()", "removed()", "deleted()", "missing()", "clean()", "unknown()", "ignored()",
            "modified() or added()", "modified() and not binary()", "clean() and size('>10')", "removed() or deleted() or unknown()",
            "added() - copied()", "not clean()", "(modified() or added()) and **.txt", "unknown() and *.txt", "ignored() and path:build",
            "binary() or exec()", "exec() and not symlink()", "size('>1') and not binary()", "grep('a') and eol('dos')",
            "revs('0', added())", "revs('1', modified())", "revs('0:1', added())", "revs('all()', removed())", "revs('none()', added())",
            "revs('.', clean())", "revs('0', **.txt)", "revs('tip', copied())",
            "status('0', '1', added())", "status('0', '1', modified())", "status('0', '1', removed())", "status('0', '1', clean())",
            "status('1', '0', added())", "status('0', '1', **.txt)",
            "subrepo()", "subrepo('x')", "subrepo(path:x)",
            "**.txt and size('>3')", "path:sub", "set:binary()", "binary() and set:size('>1')",
    };

    private static final String[] REFUSED = {
            "binary(a)", "exec(1)", "symlink(a)", "tracked(a)", "copied(a)", "portable(a)", "hgignore(a)", "modified(a)", "added(a)",
            "removed(a)", "deleted(a)", "missing(a)", "clean(a)", "unknown(a)", "ignored(a)", "resolved(a)", "unresolved(a)",
            "size()", "size('x')", "size(1,2)", "size('>')", "size('1x')", "grep()", "grep('(')", "grep('[')", "grep('*')", "grep('a**')",
            "grep(')')", "grep('\\\\q')", "grep('a|*')", "encoding()", "encoding('nope')", "eol()", "revs('0')", "revs()", "revs(0, 1, 2)",
            "status('0')", "status('', '1', added())", "status('0', '', added())", "status('0','1')", "subrepo(a, b)", "lfs()",
            "revs(added(), added())", "status(added(), '1', added())", "revs('nope', added())", "status('nope', '1', added())",
    };

    /** What hg selects among the files of a revision, or its refusal. */
    private Object realRevision(String rev, String expression) throws Exception {
        Real r = hg("files", "-r", rev, "-T", "{path}\n", "--", "set:" + expression);
        return result(r);
    }

    /** What hg selects among all files its status knows, or its refusal. */
    private Object realWorking(String expression) throws Exception {
        Real r = hg("status", "-A", "-n", "--", "set:" + expression);
        return result(r);
    }

    private Object result(Real r) {
        if (r.exit() > 1) {
            List<String> lines = new ArrayList<>(r.err().lines().toList());
            lines.removeIf(l -> l.contains("Warning") || l.contains("remod.compile") || l.startsWith("(see"));
            String first = lines.isEmpty() ? "" : lines.get(0).replaceFirst("^hg: ", "");
            return first;
        }
        TreeSet<String> files = new TreeSet<>();
        r.out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
        return files;
    }

    private Object ours(String revision, String expression, List<String> universe) {
        try {
            RepositoryFilesetContext ctx = new RepositoryFilesetContext(new HgRepository(repo), revision);
            Matcher m = Fileset.compile(ctx, "", expression);
            TreeSet<String> files = new TreeSet<>();
            for (String f : universe) {
                if (m.matches(f)) {
                    files.add(f);
                }
            }
            return files;
        } catch (java.io.UncheckedIOException e) {
            return refusal(e.getCause());
        } catch (Exception e) {
            return refusal(e);
        }
    }

    private static String refusal(Throwable e) {
        if (e instanceof FilesetParseException) {
            return e.getMessage();
        }
        if (e instanceof io.github.search5.hg4j.errors.HgValidationException) {
            return "abort: " + e.getMessage();
        }
        return "EXCEPTION " + e;
    }

    private List<String> universeOfRevision(String rev) throws Exception {
        return new ArrayList<>(((TreeSet<String>) realRevision(rev, "path:.")));
    }

    private List<String> universeOfWorkingDirectory() throws Exception {
        Real r = hg("status", "-A", "-n");
        List<String> files = new ArrayList<>();
        r.out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
        return files;
    }

    @Test
    void everyPredicateSelectsLikeMercurialInTwoRevisions() throws Exception {
        for (String rev : new String[] {"0", "1"}) {
            List<String> universe = universeOfRevision(rev);
            assertTrue(universe.size() > 10, universe.toString());
            for (String e : EXPRESSIONS) {
                assertEquals(realRevision(rev, e), ours(rev, e, universe), "set:" + e + " in revision " + rev);
            }
        }
    }

    @Test
    void everyPredicateSelectsLikeMercurialInTheWorkingDirectory() throws Exception {
        List<String> universe = universeOfWorkingDirectory();
        assertTrue(universe.contains("untracked.txt") && universe.contains("x.log") && universe.contains("latin1.txt"), universe.toString());
        for (String e : EXPRESSIONS) {
            assertEquals(realWorking(e), ours(null, e, universe), "set:" + e + " in the working directory");
        }
    }

    @Test
    void badArgumentsAreRefusedWithMercurialsWords() throws Exception {
        List<String> universe = universeOfRevision("1");
        for (String e : REFUSED) {
            assertEquals(realRevision("1", e), ours("1", e, universe), "set:" + e);
        }
    }

    @Test
    void resolvedAndUnresolvedFollowTheMergeState() throws Exception {
        File merge = tempDir.resolve("merge").toFile();
        merge.mkdirs();
        File saved = repo;
        repo = merge;
        run("init");
        write("f", "base\n");
        write("g", "base\n");
        write("h", "base\n");
        run("add");
        run("commit", "-m", "base");
        write("f", "left\n");
        write("g", "left\n");
        run("commit", "-m", "left");
        run("update", "0");
        write("f", "right\n");
        write("g", "right\n");
        run("commit", "-m", "right");
        Real merged = hg("--config", "ui.merge=internal:fail", "merge");
        assertEquals(1, merged.exit(), merged.out() + merged.err());
        run("resolve", "-m", "g");
        List<String> universe = universeOfWorkingDirectory();
        for (String e : new String[] {"resolved()", "unresolved()", "resolved() or unresolved()", "unresolved() and f", "not resolved()"}) {
            assertEquals(realWorking(e), ours(null, e, universe), "set:" + e);
        }
        assertEquals(new TreeSet<>(List.of("g")), realWorking("resolved()"), "the fixture marks g resolved");
        assertEquals(new TreeSet<>(List.of("f")), realWorking("unresolved()"), "and leaves f unresolved");
        repo = saved;
    }
}
