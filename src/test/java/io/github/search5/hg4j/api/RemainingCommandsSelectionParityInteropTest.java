package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파일셋 계획 4단계 잔여: {@code locate}, {@code archive}, {@code grep} and {@code annotate} select files exactly like real hg does when they
 * are given patterns, {@code -I}, {@code -X} and {@code set:} expressions, from the root and from a subdirectory.
 */
@Tag("interop")
class RemainingCommandsSelectionParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;

    private record Real(int exit, String out, String err) {
    }

    private Real hg(File cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "ui.username=t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd).start();
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

    private void write(String name, String text) throws Exception {
        File f = new File(repo, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
    }

    private void run(String... args) throws Exception {
        Real r = hg(repo, args);
        assertEquals(0, r.exit(), String.join(" ", args) + ": " + r.err() + r.out());
    }

    @BeforeEach
    void history() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        run("init");
        String[] files = {"a.txt", "b.py", "c.PY", "src/main.c", "src/util.h", "src/sub/deep.c", "src/sub/x.txt", "docs/readme.md",
                "docs/guide/intro.md", "lib/foo1.txt", "lib/sp ace.txt", "tests/test_a.py", "x/y/z/w.txt"};
        for (String f : files) {
            write(f, f + "\n");
        }
        run("add");
        run("commit", "-m", "r0");
        write("a.txt", "a.txt\nchanged in r1\n");
        write("src/main.c", "src/main.c\nchanged in r1\n");
        write("docs/guide/intro.md", "docs/guide/intro.md\nchanged in r1\n");
        write("src/new.c", "new in r1\n");
        run("add", "src/new.c");
        run("rm", "b.py");
        run("commit", "-m", "r1");
        write("lib/foo1.txt", "lib/foo1.txt\nchanged in r2\n");
        write("tests/test_a.py", "tests/test_a.py\nchanged in r2\n");
        run("commit", "-m", "r2");
        // the working directory: modified, added, removed, deleted, unknown
        write("a.txt", "changed in the working directory\n");
        write("src/sub/x.txt", "changed in the working directory\n");
        write("added.txt", "added\n");
        run("add", "added.txt");
        run("rm", "docs/readme.md");
        write("untracked.py", "unknown\n");
        write("src/untracked.c", "unknown\n");
    }

    private static final String[] PATTERNS = {
            "glob:*.py", "glob:**.py", "glob:src/*", "glob:src/**", "path:src", "path:src/sub", "a.txt", "src", "src/main.c", ".",
            "rootfilesin:src", "relglob:*.c", "relglob:deep.c", "re:.*\\.py$", "relre:\\.c$", "filepath:a.txt", "path:nonexistent",
            "set:**.c", "set:size('>20')", "set:modified() or added()", "set:binary()", "glob:{a,b}.*",
    };

    private static final String[] EXTRA = {"-I", "-X"};

    /** All argument lists: the pattern as an argument, as -I, as -X. */
    private List<List<String>> variants() {
        List<List<String>> all = new ArrayList<>();
        all.add(List.of());
        for (String p : PATTERNS) {
            all.add(List.of(p));
            all.add(List.of("-I", p));
            all.add(List.of("-X", p));
        }
        all.add(List.of("path:src", "-X", "glob:**.h"));
        all.add(List.of("glob:**", "-I", "path:src", "-X", "path:src/sub"));
        all.add(List.of("path:src", "path:docs"));
        return all;
    }

    private static String[] patternsOf(List<String> args, String flag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals(flag)) {
                out.add(args.get(++i));
            }
        }
        return out.toArray(String[]::new);
    }

    private static String[] argumentsOf(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-I") || args.get(i).equals("-X")) {
                i++;
            } else {
                out.add(args.get(i));
            }
        }
        return out.toArray(String[]::new);
    }

    private static TreeSet<String> lines(String text) {
        TreeSet<String> set = new TreeSet<>();
        text.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(set::add);
        return set;
    }

    private List<String> realArgs(List<String> head, List<String> v, boolean withArguments) {
        List<String> real = new ArrayList<>(head);
        for (String p : patternsOf(v, "-I")) {
            real.addAll(List.of("-I", p));
        }
        for (String p : patternsOf(v, "-X")) {
            real.addAll(List.of("-X", p));
        }
        return real;
    }

    @Test
    void locateSelectsLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                for (String rev : new String[] {"1", null}) {
                    List<String> head = new ArrayList<>(List.of("locate", "-f"));
                    if (rev != null) {
                        head.addAll(List.of("-r", rev));
                    }
                    List<String> real = realArgs(head, v, true);
                    real.add("--");
                    real.addAll(List.of(argumentsOf(v)));
                    Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                    // -f prints complete paths
                    String root = repo.getCanonicalPath() + "/";
                    TreeSet<String> relative = new TreeSet<>();
                    lines(r.out()).forEach(l -> relative.add(l.startsWith(root) ? l.substring(root.length()) : l));
                    Object expected = r.exit() > 1 ? "refused" : relative;
                    Object actual;
                    try {
                        LocateCommand ours = new LocateCommand(new HgRepository(repo)).setPatterns(argumentsOf(v))
                                .setInclude(patternsOf(v, "-I")).setExclude(patternsOf(v, "-X")).setCwd(cwd);
                        if (rev != null) {
                            ours.setRevision(rev);
                        }
                        actual = new TreeSet<>(ours.call());
                    } catch (Exception e) {
                        actual = "refused";
                    }
                    assertEquals(expected, actual, "locate " + v + " rev " + rev + " from '" + cwd + "'");
                }
            }
        }
    }

    private Map<String, String> filesUnder(File dir) throws Exception {
        Map<String, String> files = new TreeMap<>();
        if (dir.exists()) {
            try (var walk = Files.walk(dir.toPath())) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = dir.toPath().relativize(p).toString();
                    if (!rel.equals(".hg_archival.txt")) {
                        files.put(rel, Files.readString(p));
                    }
                }
            }
        }
        return files;
    }

    @Test
    void archiveHoldsTheSelectedFilesLikeMercurial() throws Exception {
        int n = 0;
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                if (argumentsOf(v).length > 0) {
                    continue; // `hg archive` takes only a destination and -I/-X
                }
                File realDest = tempDir.resolve("archive-real-" + n).toFile();
                File ownDest = tempDir.resolve("archive-own-" + n).toFile();
                n++;
                List<String> real = realArgs(List.of("archive", "-t", "files", "-r", "1"), v, false);
                real.add("--");
                real.add(realDest.getAbsolutePath());
                Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                Object expected = r.exit() > 1 ? "refused" : filesUnder(realDest);
                Object actual;
                try {
                    new ArchiveCommand(new HgRepository(repo)).setRevision("1").setType("files").setDestination(ownDest)
                            .setInclude(patternsOf(v, "-I")).setExclude(patternsOf(v, "-X")).setCwd(cwd).call();
                    actual = filesUnder(ownDest);
                } catch (Exception e) {
                    actual = "refused";
                }
                assertEquals(expected, actual, "archive " + v + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void grepSearchesTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                List<String> real = realArgs(List.of("grep", "-r", "all()", "-n"), v, true);
                real.add("--");
                real.add("a\\.txt|changed");
                real.addAll(List.of(argumentsOf(v)));
                Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                Object expected;
                if (r.exit() > 1) {
                    expected = "refused";
                } else {
                    TreeSet<String> found = new TreeSet<>();
                    for (String line : r.out().split("\n")) {
                        if (!line.isBlank()) {
                            found.add(line); // path:rev:line:text
                        }
                    }
                    expected = found;
                }
                Object actual;
                try {
                    TreeSet<String> found = new TreeSet<>();
                    for (GrepCommand.GrepResult g : new GrepCommand(new HgRepository(repo)).setQuery("a\\.txt|changed").setRevisions("all()")
                            .setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I")).setExclude(patternsOf(v, "-X")).setCwd(cwd).call()) {
                        found.add(g.path + ":" + g.linkRevision + ":" + g.lineNumber + ":" + g.lineContent);
                    }
                    actual = found;
                } catch (Exception e) {
                    actual = "refused";
                }
                assertEquals(expected, actual, "grep " + v + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void annotateAnnotatesTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                if (argumentsOf(v).length == 0) {
                    continue; // `hg annotate` needs a FILE
                }
                List<String> real = realArgs(List.of("annotate", "-r", "2", "-n", "-f"), v, true);
                real.add("--");
                real.addAll(List.of(argumentsOf(v)));
                Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                Object expected;
                if (r.exit() > 1) {
                    expected = "refused";
                } else {
                    TreeSet<String> found = new TreeSet<>();
                    for (String line : r.out().split("\n")) {
                        if (!line.isBlank()) {
                            found.add(line.strip()); // rev path: text
                        }
                    }
                    expected = found;
                }
                Object actual;
                try {
                    TreeSet<String> found = new TreeSet<>();
                    new AnnotateCommand(new HgRepository(repo)).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd(cwd).callSelected("2").forEach((path, blame) -> {
                                for (AnnotateCommand.BlameLine line : blame) {
                                    found.add(line.getRevision() + " " + path + ": " + line.getContent());
                                }
                            });
                    actual = found;
                } catch (Exception e) {
                    actual = "refused";
                }
                assertEquals(expected, actual, "annotate " + v + " from '" + cwd + "'");
            }
        }
    }
}
