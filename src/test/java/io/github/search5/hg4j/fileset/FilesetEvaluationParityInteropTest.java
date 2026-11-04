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
 * 파일셋 계획 2단계: expressions made of patterns and the operators select the same files as in Mercurial 7.2.4 ({@code hg files
 * "set:..."}), also from a subdirectory, and the refusals of the evaluation read the same, including the "did you mean" hints.
 */
@Tag("interop")
class FilesetEvaluationParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;
    private List<String> tracked;

    private static final String[] FILES = {"a.txt", "b.py", "c.PY", ".hidden", "README", "src/main.c", "src/util.h", "src/sub/deep.c",
            "src/sub/x.txt", "docs/readme.md", "docs/guide/intro.md", "lib/foo1.txt", "lib/sp ace.txt", "tests/test_a.py", "x/y/z/w.txt"};

    private record Real(int exit, String out, String err) {
    }

    private Real hg(File cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg"));
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

    @BeforeEach
    void repository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        HgTestUtils.hg(repo, "init");
        for (String f : FILES) {
            File file = new File(repo, f);
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), f + "\n");
        }
        HgTestUtils.hg(repo, "add");
        HgTestUtils.hg(repo, "commit", "-m", "files", "-u", "t");
        tracked = List.of(FILES);
    }

    /** The files hg lists, or the first line of its refusal (with the hint line when there is one). */
    private Object real(String cwd, String expression) throws Exception {
        Real r = hg(cwd.isEmpty() ? repo : new File(repo, cwd), "files", "-T", "{path}\n", "--", "set:" + expression);
        if (r.exit() > 1) {
            List<String> lines = new ArrayList<>(r.err().lines().toList());
            lines.removeIf(l -> l.contains("Warning") || l.contains("remod.compile"));
            return String.join("\n", lines).replaceFirst("^hg: ", "");
        }
        TreeSet<String> files = new TreeSet<>();
        r.out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
        return files;
    }

    private Object ours(String cwd, String expression) {
        try {
            Matcher m = Fileset.compile(new RepositoryFilesetContext(new HgRepository(repo), null), cwd, expression);
            TreeSet<String> files = new TreeSet<>();
            for (String f : tracked) {
                if (m.matches(f)) {
                    files.add(f);
                }
            }
            return files;
        } catch (FilesetParseException e) {
            return e.getMessage() + (e.getHint() == null ? "" : "\n(" + e.getHint() + ")");
        } catch (io.github.search5.hg4j.errors.HgValidationException e) {
            return "abort: " + e.getMessage(); // an error that is no parse error is an abort
        } catch (Exception e) {
            return "EXCEPTION " + e;
        }
    }

    private static final String[] EXPRESSIONS = {
            "*.py", "**.py", "glob:*.c", "glob:**.c", "re:\\.py$", "path:src", "path:src/sub", "a.txt", "'a.txt'", "\"src/main.c\"",
            "relglob:*.c", "rootfilesin:src", "filepath:a.txt", "relpath:src", "*", "**", "src/*", "src/**", "docs/**/*.md",
            "*.py or *.c", "**.py or **.c or path:docs", "**.py and path:tests", "**.py and not path:tests", "**.py - path:tests",
            "**.c - path:src/sub", "(**.c or **.h) and path:src", "not **.py", "!path:src", "not not path:src",
            "path:src and path:src/sub", "path:src - (path:src/sub or **.h)", "**.py | **.c", "**.py + **.c", "**.py & path:tests",
            "(path:src)", "((path:src))", "path:. - **", "none", "x:y", "glob:*.py or re:.*\\.c$",
            "'sp ace'", "lib/'sp ace.txt'", "path:'lib/sp ace.txt'", "set:*.py", "set:path:src and set:**.c",
            "*.PY", "a.* or b.*", "?.txt", "{a,b}.*", "[ab].*", ".hidden",
    };

    private static final String[] REFUSED = {
            "()", "(a,b)", "a,b", "foo()", "foo(a)", "modifed()", "siz('>1')", "binry()", "exe()", "grp('x')", "tracked(a)b", "foo:bar",
            "bar:'x'", "path:", "x:y:z", "f(", "-a", "a b", "re:[", "re:(", "listfile:/no/such/file",
    };

    @Test
    void expressionsOfPatternsAndOperatorsSelectLikeMercurial() throws Exception {
        for (String cwd : new String[] {"", "src"}) {
            for (String e : EXPRESSIONS) {
                assertEquals(real(cwd, e), ours(cwd, e), "set:" + e + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void refusalsReadLikeMercurials() throws Exception {
        for (String e : REFUSED) {
            Object expected = real("", e);
            Object actual = ours("", e);
            assertEquals(expected, actual, "set:" + e);
        }
    }
}
