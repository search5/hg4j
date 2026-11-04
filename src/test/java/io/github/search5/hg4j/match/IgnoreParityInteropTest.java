package io.github.search5.hg4j.match;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Status;
import io.github.search5.hg4j.api.StatusCommand;
import io.github.search5.hg4j.fileset.FileStatus;
import io.github.search5.hg4j.fileset.RepositoryFilesetContext;
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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파일셋 계획 4단계: the files {@code .hgignore} hides are the same as in Mercurial 7.2.4, for every syntax the file knows
 * ({@code syntax: glob/regexp/rootglob}, comments, escapes, {@code include:}, {@code subinclude:}, a {@code ui.ignore} file).
 */
@Tag("interop")
class IgnoreParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;

    private static final String[] FILES = {"keep.txt", "a.log", "b.tmp", "src/main.c", "src/main.o", "src/sub/x.log", "build/out.o",
            "build/sub/deep.o", "docs/readme.md", "docs/notes.txt~", ".hidden", "lib/foo.pyc", "lib/bar.py", "#autosave#", "tmp/file",
            "a/tmp/file", "x/y/z.orig", "with space.txt", "UPPER.LOG"};

    private record Real(int exit, String out, String err) {
    }

    private Real hg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg"));
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
    }

    private static final String[] IGNORE_FILES = {
            "",
            "syntax: glob\n*.log\n*.tmp\nbuild\n",
            "syntax: regexp\n\\.log$\n^build/\n",
            "syntax: re\n\\.o$\n",
            "syntax: glob\n*.o\nsyntax: regexp\n^tmp/\n",
            "# a comment\nsyntax: glob\n*.log # trailing comment\n\n   \n*.tmp\n",
            "syntax: glob\nsrc/*.o\n",
            "syntax: glob\n**/*.o\n",
            "syntax: glob\nbuild/**\n",
            "syntax: rootglob\ntmp/*\n",
            "syntax: glob\ntmp\n",
            "syntax: glob\n*~\n\\#*\\#\n",
            "syntax: glob\n{*.log,*.tmp}\n",
            "syntax: glob\n?.tmp\n[ab].*\n",
            "syntax: regexp\n(?i)\\.log$\n",
            "glob:*.log\nre:\\.tmp$\nrootglob:build\n",
            "syntax: glob\n.hidden\nsyntax: regexp\n^lib/.*\\.pyc$\n",
            "syntax: nonsense\n*.log\n",
            "syntax: glob\nwith space.txt\n",
            "relre:\\.orig$\nrelglob:*.pyc\n",
            "syntax: glob\n*.log\nsyntax: re\nsrc/main\\.o\nsyntax: glob\ndocs\n",
    };

    private TreeSet<String> realUnknown() throws Exception {
        TreeSet<String> set = new TreeSet<>();
        hg("status", "-u", "-n").out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(set::add);
        return set;
    }

    private TreeSet<String> realIgnored() throws Exception {
        TreeSet<String> set = new TreeSet<>();
        hg("status", "-i", "-n").out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(set::add);
        return set;
    }

    @Test
    void hgignoreHidesTheSameFilesAsMercurial() throws Exception {
        for (String content : IGNORE_FILES) {
            Files.writeString(new File(repo, ".hgignore").toPath(), content);
            HgRepository repository = new HgRepository(repo);
            Real probe = hg("status", "-A");
            if (probe.exit() > 1) {
                // an invalid pattern file: both refuse with the same words
                java.io.IOException refused = assertThrows(java.io.IOException.class,
                        () -> RepositoryFilesetContext.workingDirectoryStatus(repository, Matchers.always(), true, false, true),
                        ".hgignore:\n" + content);
                String root = repo.getAbsolutePath();
                String abort = probe.err().lines().filter(l -> l.startsWith("abort: ")).findFirst().orElse("").substring(7);
                assertEquals(abort.replace(root, "<ROOT>"), refused.getMessage().replace(root, "<ROOT>"), ".hgignore:\n" + content);
                continue;
            }
            FileStatus status = RepositoryFilesetContext.workingDirectoryStatus(repository, Matchers.always(), true, false, true);
            TreeSet<String> unknown = new TreeSet<>(status.unknown());
            unknown.remove(".hgignore");
            TreeSet<String> realUnknown = realUnknown();
            realUnknown.remove(".hgignore");
            assertEquals(realUnknown, unknown, "unknown files with .hgignore:\n" + content);
            assertEquals(realIgnored(), new TreeSet<>(status.ignored()), "ignored files with .hgignore:\n" + content);
            // and the command that lists untracked files agrees
            Status listed = new StatusCommand(repository).call();
            TreeSet<String> untracked = new TreeSet<>(listed.getUntracked());
            untracked.remove(".hgignore");
            assertEquals(realUnknown, untracked, "StatusCommand untracked with .hgignore:\n" + content);
        }
    }

    @Test
    void aFileWithoutSyntaxLinesIsReadAsRegularExpressionsAndAnInvalidOneIsRefused() throws Exception {
        Files.writeString(new File(repo, ".hgignore").toPath(), "*.log\n");
        Real real = hg("status", "-A");
        assertTrue(real.exit() > 0 && real.err().contains("invalid pattern (relre): *.log"), real.err());
        HgRepository repository = new HgRepository(repo);
        java.io.IOException refused = assertThrows(java.io.IOException.class,
                () -> new io.github.search5.hg4j.fileset.RepositoryFilesetContext(repository, null).ignoreMatcher());
        assertEquals(real.err().strip().replaceFirst("^abort: ", "").replace(repo.getCanonicalPath(), "<ROOT>").replace(repo.getAbsolutePath(), "<ROOT>"),
                refused.getMessage().replace(repo.getCanonicalPath(), "<ROOT>").replace(repo.getAbsolutePath(), "<ROOT>"));
        Files.writeString(new File(repo, ".hgignore").toPath(), "\\.log$\n");
        assertTrue(new HgRepository(repo).isIgnored("a.log"));
    }

    @Test
    void includeAndSubincludeLinesPullInOtherFiles() throws Exception {
        Files.writeString(new File(repo, "extra.rc").toPath(), "syntax: glob\n*.log\n");
        Files.writeString(new File(repo, "src/local.rc").toPath(), "syntax: glob\n*.o\n");
        for (String content : new String[] {"include:extra.rc\n", "subinclude:src/local.rc\n", "include:extra.rc\nsubinclude:src/local.rc\n",
                "include:missing.rc\n"}) {
            Files.writeString(new File(repo, ".hgignore").toPath(), content);
            TreeSet<String> expected = realUnknown();
            expected.removeAll(List.of(".hgignore", "extra.rc", "src/local.rc"));
            FileStatus status = RepositoryFilesetContext.workingDirectoryStatus(new HgRepository(repo), Matchers.always(), false, false, true);
            TreeSet<String> actual = new TreeSet<>(status.unknown());
            actual.removeAll(List.of(".hgignore", "extra.rc", "src/local.rc"));
            assertEquals(expected, actual, "unknown files with .hgignore:\n" + content);
        }
    }

    @Test
    void aUiIgnoreFileAddsToTheIgnoredFiles() throws Exception {
        File extra = tempDir.resolve("extra-ignore").toFile();
        Files.writeString(extra.toPath(), "syntax: glob\n*.tmp\n");
        Files.writeString(new File(repo, ".hg/hgrc").toPath(), "[ui]\nignore = " + extra.getAbsolutePath() + "\n");
        Files.writeString(new File(repo, ".hgignore").toPath(), "syntax: glob\n*.log\n");
        TreeSet<String> expected = realUnknown();
        expected.remove(".hgignore");
        TreeSet<String> actual = new TreeSet<>(RepositoryFilesetContext
                .workingDirectoryStatus(new HgRepository(repo), Matchers.always(), false, false, true).unknown());
        actual.remove(".hgignore");
        assertEquals(expected, actual);
    }
}
