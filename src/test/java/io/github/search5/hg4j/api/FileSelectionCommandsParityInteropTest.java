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
 * 파일셋 계획 4a단계: {@code files}, {@code status}, {@code diff}, {@code log} and {@code cat} select files exactly like real hg
 * does when they are given patterns, {@code -I}, {@code -X} and {@code set:} expressions, from the root and from a subdirectory.
 */
@Tag("interop")
class FileSelectionCommandsParityInteropTest {

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

    @Test
    void filesSelectsLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                for (String rev : new String[] {"1", null}) {
                    List<String> cmd = new ArrayList<>(List.of("files", "-T", "{path}\n"));
                    if (rev != null) {
                        cmd.addAll(List.of("-r", rev));
                    }
                    cmd.add("--");
                    // options must precede the arguments
                    List<String> real = new ArrayList<>(List.of("files", "-T", "{path}\n"));
                    if (rev != null) {
                        real.addAll(List.of("-r", rev));
                    }
                    for (String p : patternsOf(v, "-I")) {
                        real.addAll(List.of("-I", p));
                    }
                    for (String p : patternsOf(v, "-X")) {
                        real.addAll(List.of("-X", p));
                    }
                    real.add("--");
                    real.addAll(List.of(argumentsOf(v)));
                    Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                    Object expected = r.exit() > 1 ? "refused" : lines(r.out());
                    FilesCommand ours = new FilesCommand(new HgRepository(repo)).setPatterns(argumentsOf(v))
                            .setInclude(patternsOf(v, "-I")).setExclude(patternsOf(v, "-X")).setCwd(cwd);
                    if (rev != null) {
                        ours.setRevision(rev);
                    }
                    Object actual;
                    try {
                        actual = new TreeSet<>(ours.call());
                    } catch (Exception e) {
                        actual = "refused";
                    }
                    assertEquals(expected, actual, "files " + v + " rev " + rev + " from '" + cwd + "'");
                }
            }
        }
    }

    /** {@code hg status -A} as category letter to files; deleted counts as removed, ignored is left out. */
    private Map<String, TreeSet<String>> realStatus(String cwd, List<String> v) throws Exception {
        List<String> real = new ArrayList<>(List.of("status", "-A", "-T", "{status} {path}\n"));
        for (String p : patternsOf(v, "-I")) {
            real.addAll(List.of("-I", p));
        }
        for (String p : patternsOf(v, "-X")) {
            real.addAll(List.of("-X", p));
        }
        real.add("--");
        real.addAll(List.of(argumentsOf(v)));
        Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
        if (r.exit() > 1) {
            return null;
        }
        Map<String, TreeSet<String>> map = new TreeMap<>();
        for (String line : r.out().split("\n")) {
            if (line.length() < 3) {
                continue;
            }
            String letter = line.substring(0, 1);
            if (letter.equals("I")) {
                continue;
            }
            if (letter.equals("!")) {
                letter = "R";
            }
            map.computeIfAbsent(letter, k -> new TreeSet<>()).add(line.substring(2));
        }
        return map;
    }

    @Test
    void statusSelectsLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                Map<String, TreeSet<String>> expected = realStatus(cwd, v);
                Map<String, TreeSet<String>> actual;
                try {
                    Status s = new StatusCommand(new HgRepository(repo)).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd(cwd).call();
                    actual = new TreeMap<>();
                    put(actual, "M", s.getModified());
                    put(actual, "A", s.getAdded());
                    put(actual, "R", s.getRemoved());
                    put(actual, "C", s.getClean());
                    put(actual, "?", s.getUntracked());
                } catch (Exception e) {
                    actual = null;
                }
                assertEquals(expected, actual, "status " + v + " from '" + cwd + "'");
            }
        }
    }

    private static void put(Map<String, TreeSet<String>> map, String letter, java.util.Set<String> files) {
        if (!files.isEmpty()) {
            map.put(letter, new TreeSet<>(files));
        }
    }

    @Test
    void diffSelectsLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                List<String> real = new ArrayList<>(List.of("diff", "-r", "0", "-r", "2", "--git"));
                for (String p : patternsOf(v, "-I")) {
                    real.addAll(List.of("-I", p));
                }
                for (String p : patternsOf(v, "-X")) {
                    real.addAll(List.of("-X", p));
                }
                real.add("--");
                real.addAll(List.of(argumentsOf(v)));
                Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                Object expected;
                if (r.exit() > 1) {
                    expected = "refused";
                } else {
                    TreeSet<String> files = new TreeSet<>();
                    r.out().lines().filter(l -> l.startsWith("diff --git ")).forEach(l -> files.add(l.substring(l.lastIndexOf(" b/") + 3)));
                    expected = files;
                }
                Object actual;
                try {
                    TreeSet<String> files = new TreeSet<>();
                    new DiffCommand(new HgRepository(repo)).setOldRevision(0).setNewRevision(2).setPatterns(argumentsOf(v))
                            .setInclude(patternsOf(v, "-I")).setExclude(patternsOf(v, "-X")).setCwd(cwd).call()
                            .forEach(d -> files.add(d.getPath()));
                    actual = files;
                } catch (Exception e) {
                    actual = "refused";
                }
                assertEquals(expected, actual, "diff " + v + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void logSelectsRevisionsLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (List<String> v : variants()) {
                List<String> real = new ArrayList<>(List.of("log", "-T", "{rev}\n", "-r", "0:2"));
                for (String p : patternsOf(v, "-I")) {
                    real.addAll(List.of("-I", p));
                }
                for (String p : patternsOf(v, "-X")) {
                    real.addAll(List.of("-X", p));
                }
                real.add("--");
                real.addAll(List.of(argumentsOf(v)));
                Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), real.toArray(String[]::new));
                Object expected = r.exit() > 1 ? "refused" : lines(r.out());
                Object actual;
                try {
                    TreeSet<String> revs = new TreeSet<>();
                    for (HgCommit c : new LogCommand(new HgRepository(repo)).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd(cwd).call()) {
                        revs.add(String.valueOf(c.getRevision()));
                    }
                    actual = revs;
                } catch (Exception e) {
                    actual = "refused";
                }
                assertEquals(expected, actual, "log " + v + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void catPrintsTheSelectedFilesLikeMercurial() throws Exception {
        for (List<String> v : variants()) {
            if (v.isEmpty()) {
                continue; // `hg cat` without a file is refused
            }
            File out = tempDir.resolve("cat-out").toFile();
            if (out.exists()) {
                try (var walk = Files.walk(out.toPath())) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            List<String> real = new ArrayList<>(List.of("cat", "-r", "1", "--output", out.getAbsolutePath() + "/%p"));
            for (String p : patternsOf(v, "-I")) {
                real.addAll(List.of("-I", p));
            }
            for (String p : patternsOf(v, "-X")) {
                real.addAll(List.of("-X", p));
            }
            real.add("--");
            real.addAll(List.of(argumentsOf(v)));
            Real r = hg(repo, real.toArray(String[]::new));
            Object expected;
            if (r.exit() > 1) {
                expected = "refused";
            } else {
                Map<String, String> files = new TreeMap<>();
                if (out.exists()) {
                    try (var walk = Files.walk(out.toPath())) {
                        for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                            files.put(out.toPath().relativize(p).toString(), Files.readString(p));
                        }
                    }
                }
                expected = files;
            }
            Object actual;
            try {
                Map<String, String> files = new TreeMap<>();
                new CatCommand(new HgRepository(repo)).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                        .setExclude(patternsOf(v, "-X")).setRevision("1").callAll()
                        .forEach((path, data) -> files.put(path, new String(data, StandardCharsets.UTF_8)));
                actual = files;
            } catch (Exception e) {
                actual = "refused";
            }
            assertEquals(expected, actual, "cat " + v);
        }
    }
}
