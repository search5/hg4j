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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파일셋 계획 4b단계: {@code add}, {@code forget}, {@code remove} and {@code revert} work on the files patterns select. The same
 * command with the same patterns runs with real hg in one copy of a repository and with hg4j in another; afterwards the status
 * of every file, the files on the disk (including {@code .orig} backups) and their contents must be the same.
 */
@Tag("interop")
class MutatingSelectionParityInteropTest {

    @TempDir
    Path tempDir;

    private File original;
    private int copies;

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
        File f = new File(original, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
    }

    private void run(String... args) throws Exception {
        Real r = hg(original, args);
        assertEquals(0, r.exit(), String.join(" ", args) + ": " + r.err() + r.out());
    }

    @BeforeEach
    void repository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        original = tempDir.resolve("original").toFile();
        original.mkdirs();
        run("init");
        write(".hgignore", "syntax: glob\n*.log\n");
        for (String f : new String[] {"a.txt", "b.py", "c.PY", "src/main.c", "src/util.h", "src/sub/deep.c", "src/sub/x.txt",
                "docs/readme.md", "docs/guide/intro.md", "lib/foo1.txt", "lib/sp ace.txt", "tests/test_a.py", "x/y/z/w.txt"}) {
            write(f, f + "\n");
        }
        run("add");
        run("commit", "-m", "base");
        write("a.txt", "modified\n");
        write("src/main.c", "modified\n");
        write("docs/guide/intro.md", "modified\n");
        run("rm", "b.py");
        Files.delete(new File(original, "lib/foo1.txt").toPath());
        write("newadded.txt", "added\n");
        write("src/newadded.c", "added\n");
        run("add", "newadded.txt", "src/newadded.c");
        write("untracked.py", "unknown\n");
        write("src/untracked.c", "unknown\n");
        write("lib/untracked.txt", "unknown\n");
        write("x.log", "ignored\n");
        write("src/y.log", "ignored\n");
    }

    private File copy() throws Exception {
        File target = tempDir.resolve("copy" + copies++).toFile();
        Process p = new ProcessBuilder("cp", "-a", original.getAbsolutePath(), target.getAbsolutePath()).start();
        assertEquals(0, p.waitFor());
        return target;
    }

    /** Status of every file plus what is on the disk. */
    private Map<String, String> state(File dir) throws Exception {
        Map<String, String> state = new TreeMap<>();
        Real status = hg(dir, "status", "-A", "-T", "{status} {path}\n");
        for (String line : status.out().split("\n")) {
            if (line.length() > 2) {
                state.put("status " + line.substring(2), line.substring(0, 1));
            }
        }
        try (var walk = Files.walk(dir.toPath())) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = dir.toPath().relativize(p).toString();
                if (!rel.startsWith(".hg/") && !rel.equals(".hg")) {
                    state.put("disk " + rel, Files.readString(p));
                }
            }
        }
        return state;
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

    private static final List<List<String>> VARIANTS = List.of(
            List.of("a.txt"), List.of("glob:*.py"), List.of("glob:**.py"), List.of("path:src"), List.of("path:src/sub"), List.of("src"),
            List.of("glob:**.c"), List.of("glob:**.h"), List.of("glob:**.log"), List.of("x.log"), List.of("src/y.log"), List.of("."),
            List.of("path:lib"), List.of("rootfilesin:src"), List.of("relglob:*.txt"), List.of("re:.*\\.md$"), List.of("filepath:a.txt"),
            List.of("set:modified()"), List.of("set:added()"), List.of("set:size('>10')"), List.of("path:src", "-X", "glob:**.c"),
            List.of("glob:**", "-I", "path:docs"), List.of("path:nonexistent"), List.of("b.py"), List.of("lib/foo1.txt"),
            List.of("newadded.txt", "src/untracked.c"));

    private void compare(String command, List<String> extraFlags, String cwd, Consumer<Object[]> ours) throws Exception {
        for (List<String> v : VARIANTS) {
            File realRepo = copy();
            File ownRepo = copy();
            List<String> real = new ArrayList<>(List.of(command));
            real.addAll(extraFlags);
            for (String p : patternsOf(v, "-I")) {
                real.addAll(List.of("-I", p));
            }
            for (String p : patternsOf(v, "-X")) {
                real.addAll(List.of("-X", p));
            }
            real.add("--");
            real.addAll(List.of(argumentsOf(v)));
            hg(cwd.isEmpty() ? realRepo : new File(realRepo, cwd), real.toArray(String[]::new));
            try {
                ours.accept(new Object[] {new HgRepository(ownRepo), v, cwd});
            } catch (Exception e) {
                // a refusal leaves everything as it was, like hg's non-zero exit
            }
            assertEquals(state(realRepo), state(ownRepo), command + " " + extraFlags + " " + v + " from '" + cwd + "'");
        }
    }

    @Test
    void addAddsTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            compare("add", List.of(), cwd, a -> {
                try {
                    @SuppressWarnings("unchecked") List<String> v = (List<String>) a[1];
                    new AddCommand((HgRepository) a[0]).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd((String) a[2]).call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void forgetForgetsTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            compare("forget", List.of(), cwd, a -> {
                try {
                    @SuppressWarnings("unchecked") List<String> v = (List<String>) a[1];
                    new ForgetCommand((HgRepository) a[0]).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd((String) a[2]).callSelected();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void removeRemovesTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (boolean force : new boolean[] {false, true}) {
                compare("remove", force ? List.of("-f") : List.of(), cwd, a -> {
                    try {
                        @SuppressWarnings("unchecked") List<String> v = (List<String>) a[1];
                        new RemoveCommand((HgRepository) a[0]).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                                .setExclude(patternsOf(v, "-X")).setCwd((String) a[2]).setForce(force).callSelected();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @Test
    void revertRestoresTheSelectedFilesLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            compare("revert", List.of(), cwd, a -> {
                try {
                    @SuppressWarnings("unchecked") List<String> v = (List<String>) a[1];
                    new RevertCommand((HgRepository) a[0]).setPatterns(argumentsOf(v)).setInclude(patternsOf(v, "-I"))
                            .setExclude(patternsOf(v, "-X")).setCwd((String) a[2]).callSelected();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
