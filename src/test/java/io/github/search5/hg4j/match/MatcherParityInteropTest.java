package io.github.search5.hg4j.match;

import io.github.search5.hg4j.HgTestUtils;
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
 * 파일셋 계획 1단계: the pattern matcher selects the same files as Mercurial 7.2.4. Patterns of every kind are given to
 * {@code hg files} as arguments, as {@code -I} and as {@code -X}, from the repository root and from a subdirectory, and the
 * matcher built by {@link Matchers} is applied to the same list of tracked files. Invalid patterns must be refused with the same
 * words.
 */
@Tag("interop")
class MatcherParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;
    private List<String> tracked;

    private static final String[] FILES = {
            "a.txt", "b.py", "c.PY", ".hidden", "README", "a.b.c",
            "src/main.c", "src/util.h", "src/sub/deep.c", "src/sub/x.txt",
            "docs/readme.md", "docs/guide/intro.md", "docs/guide/img.png",
            "lib/foo[1].txt", "lib/foo1.txt", "lib/sp ace.txt", "lib/ünï.txt", "lib/a.b.c", "lib/.dot",
            "tests/test_a.py", "tests/data/d1.bin", "x/y/z/w.txt"};

    private record Real(int exit, String output, String error) {
    }

    private Real realHg(File cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "ui.username=t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd).start();
        // read both streams concurrently: a full pipe would block the process
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
        tracked = new ArrayList<>(List.of(FILES));
    }

    /** What real hg lists for the arguments from {@code cwd}, or the abort text. */
    private Object real(String cwd, List<String> args) throws Exception {
        List<String> full = new ArrayList<>(List.of("files", "-T", "{path}\n"));
        full.addAll(args);
        File dir = cwd.isEmpty() ? repo : new File(repo, cwd);
        Real r = realHg(dir, full.toArray(String[]::new));
        if (r.exit() > 1) {
            String text = r.error() + r.output();
            int at = text.indexOf("abort: ");
            String msg = at >= 0 ? text.substring(at + 7) : text;
            msg = msg.lines().findFirst().orElse("").strip();
            return msg.replace(repo.getCanonicalPath(), "<ROOT>").replace(repo.getAbsolutePath(), "<ROOT>");
        }
        TreeSet<String> files = new TreeSet<>();
        r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
        return files;
    }

    private Object ours(String cwd, List<String> patterns, List<String> include, List<String> exclude) {
        try {
            Matcher m = Matchers.match(repo, cwd, patterns, include, exclude, "relpath", null, null);
            TreeSet<String> files = new TreeSet<>();
            for (String f : tracked) {
                if (m.matches(f)) {
                    files.add(f);
                }
            }
            return files;
        } catch (Exception e) {
            return e.getMessage().replace(repo.getAbsolutePath(), "<ROOT>");
        }
    }

    private static final String[] PATTERNS = {
            "glob:*.py", "glob:**.py", "glob:src/*", "glob:src/**", "glob:*/*.c", "glob:docs/**/*.md", "glob:lib/foo[1].txt",
            "glob:lib/foo[[]1].txt", "glob:{a,b}.txt", "glob:{src,docs}/*", "glob:?.txt", "glob:[ab].*", "glob:[!a]*", "glob:.hidden",
            "glob:*", "glob:**", "glob:**/deep.c", "glob:src/**/x.txt", "glob:lib/sp?ce.txt", "glob:lib/*.txt", "glob:x/**", "glob:[",
            "path:src", "path:src/sub", "path:a.txt", "path:.", "path:nonexistent", "path:src/", "path:lib/sp ace.txt", "path:./src",
            "filepath:a.txt", "filepath:src", "filepath:src/main.c",
            "rootfilesin:src", "rootfilesin:.", "rootfilesin:docs/guide", "rootfilesin:x",
            "relglob:*.c", "relglob:deep.c", "relglob:*.PY", "relglob:sub/*.c", "relglob:*.txt", "relglob:foo?1?.txt",
            "relpath:src", "relpath:.", "relpath:src/sub",
            "re:.*\\.py$", "re:^src/", "re:\\.(c|h)$", "re:(?i).*\\.py$", "re:.*", "re:^$",
            "relre:\\.c$", "relre:^lib", "relre:(?i)readme", "relre:y/z",
            "rootglob:src/*.c", "rootglob:*.py",
            "a.txt", "src", "src/main.c", ".", "src/sub/", "nonexistent",
            "re:[", "re:(", "re:*", "relre:(", "re:(?P<n>a)(?P=n)",
            "relpath:../outside", "listfile:no-such-list", "listfile0:no-such-list",
    };

    @Test
    void patternsGivenAsArgumentsSelectLikeMercurialFromTheRootAndFromASubdirectory() throws Exception {
        for (String cwd : new String[]{"", "src", "src/sub"}) {
            for (String p : PATTERNS) {
                assertEquals(real(cwd, List.of(p)), ours(cwd, List.of(p), null, null), "argument " + p + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void includePatternsRestrictLikeMercurial() throws Exception {
        for (String cwd : new String[]{"", "src"}) {
            for (String p : PATTERNS) {
                assertEquals(real(cwd, List.of("-I", p)), ours(cwd, null, List.of(p), null), "-I " + p + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void excludePatternsRemoveLikeMercurial() throws Exception {
        for (String cwd : new String[]{"", "src"}) {
            for (String p : PATTERNS) {
                assertEquals(real(cwd, List.of("-X", p)), ours(cwd, null, null, List.of(p)), "-X " + p + " from '" + cwd + "'");
            }
        }
    }

    @Test
    void patternsIncludeAndExcludeCombine() throws Exception {
        String[][] combos = {
                {"path:src", "glob:**.c", "glob:**/sub/**"}, {"glob:**.py", "path:tests", "glob:tests/**"},
                {"relglob:*.txt", "path:lib", "glob:lib/f*"}, {"path:.", "relglob:*.md", "path:docs/guide"},
                {"re:.*", "glob:src/**", "relglob:*.h"}, {"rootfilesin:src", "glob:**", "glob:**.h"}};
        for (String[] c : combos) {
            assertEquals(real("", List.of(c[0], "-I", c[1], "-X", c[2])), ours("", List.of(c[0]), List.of(c[1]), List.of(c[2])),
                    String.join(" | ", c));
        }
        // several arguments are a union, several -I are a union as well
        assertEquals(real("", List.of("path:src", "glob:*.py", "rootfilesin:docs")),
                ours("", List.of("path:src", "glob:*.py", "rootfilesin:docs"), null, null));
        assertEquals(real("", List.of("-I", "glob:*.py", "-I", "path:src/sub")),
                ours("", null, List.of("glob:*.py", "path:src/sub"), null));
    }

    @Test
    void aListFileExpandsToItsPatterns() throws Exception {
        File list = tempDir.resolve("patterns.txt").toFile();
        Files.writeString(list.toPath(), "path:src/sub\nglob:*.py\n\nrelglob:*.md\n");
        Files.writeString(tempDir.resolve("patterns0.txt"), "path:docs\0glob:*.PY\0");
        assertEquals(real("", List.of("listfile:" + list)), ours("", List.of("listfile:" + list), null, null));
        assertEquals(real("", List.of("listfile0:" + tempDir.resolve("patterns0.txt"))),
                ours("", List.of("listfile0:" + tempDir.resolve("patterns0.txt")), null, null));
    }

    @Test
    void anIncludeFileUsesItsSyntaxLines() throws Exception {
        Files.writeString(new File(repo, "pats.rc").toPath(), "# a comment\nsyntax: glob\n*.c\nsrc/*.h  # trailing comment\n"
                + "syntax: regexp\n\\.md$\nsyntax: rootglob\ntests/*.py\nre:^lib/a\\.\nglob:x/**\n");
        assertEquals(real("", List.of("include:pats.rc")), ours("", List.of("include:pats.rc"), null, null));
    }

    @Test
    void aSubincludeIsReadRelativeToItsDirectory() throws Exception {
        // `hg files` aborts with an internal error on a subinclude pattern; `hg status -A` accepts it
        Files.writeString(new File(repo, "src/sub.rc").toPath(), "syntax: glob\n*.c\n");
        Real r = realHg(repo, "status", "-A", "-n", "subinclude:src/sub.rc");
        TreeSet<String> expected = new TreeSet<>();
        r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(expected::add);
        assertEquals(expected, ours("", List.of("subinclude:src/sub.rc"), null, null));
    }
}
