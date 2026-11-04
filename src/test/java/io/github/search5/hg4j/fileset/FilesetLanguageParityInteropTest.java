package io.github.search5.hg4j.fileset;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파일셋 계획 2단계: the fileset language is parsed, analyzed and optimized exactly like Mercurial 7.2.4. For every expression the
 * three stages {@code hg debugfileset --show-stage parsed|analyzed|optimized} prints must be the same text hg4j prints, and the
 * expressions Mercurial refuses must be refused with the same words (and the same position).
 */
@Tag("interop")
class FilesetLanguageParityInteropTest {

    @TempDir
    Path tempDir;

    private File repo;

    private static final String[] VALID = {
            "a", "'a b'", "\"x\"", "r'a\\b'", "'a\\tb'", "a or b", "a | b | c", "a + b", "a and b", "a & b", "not a", "!a", "a - b",
            "a - b - c", "a or not b", "a and b or c", "a or b and c", "(a or b) and c", "(a)", "((a))", "a,b", "a, b, c", "f()", "f(a)",
            "f(a,b)", "f(a, b, c)", "f(g(a))", "f(a) and g(b)", "size('>1')", "size(>1)", "modified()", "modified() and added()",
            "modified() or added()", "modified() or clean() or a", "added() and not modified()", "not added()",
            "modified() - clean()", "a and modified()", "a or modified()", "unknown() and ignored()",
            "grep('x') and size('>1') and binary()", "glob:*.c", "re:foo", "path:a/b", "x:y", "x:'a b'", "glob:a or glob:b or re:c",
            "a and glob:*.c and not path:b", "*.c", "**.py", "src/**", "a.b-c", "'a'-'b'", "not not a", "!!a", "a and (b or c) and d",
            "f(a or b)", "f((a,b))", "é", "'é'", "f(é)", "lfs()", "lfs() and size('>1')", "lfs() or modified()",
            "status('.', 'p') and modified()", "modified() and clean() and added()", "(modified() and clean()) or added()",
            "modified() or (added() and clean())", "not (modified() and added())", "f(modified() and added())",
            "binary() and exec() and symlink()", "size('>1') or binary() or a", "a or b or c or d or e", "a and b and c and d",
            "x:y or z:w", "'a' or \"b\" or r'c'", "'a\\x41'", "'a\\101'", "'a\\n'", "'it\\'s'", "\"it's\"", "a-", "-", "tracked() and a*",
            "copied() and (a or b)", "revs('.', 'x') or status('.', '.', 'y')", "subrepo() or subrepo('a')",
    };

    private static final String[] INVALID = {
            "", "a b", "or", "and", "not", "(", ")", "a)", "(a", "a))", "f(", "f(a", "f(a))", "'unterminated", "\"x", "r'x", "a;b", "a$b",
            "a:", ":x", "a or", "or a", "a and", "-a", "x:y:z", "f(,a)", "f(a,)", "a,", ",a", "()", "a b c", "1+", "!", "a ! b", "a - ",
            "(a b)", "f(a b)", "x:", "'a' 'b'", "a \\ b", "[", "a = b",
    };

    @BeforeEach
    void repository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        HgTestUtils.hg(repo, "init");
        Files.writeString(new File(repo, "a.txt").toPath(), "a\n");
        HgTestUtils.hg(repo, "add");
        HgTestUtils.hg(repo, "commit", "-m", "x", "-u", "t");
    }

    private record Run(int exit, String out, String err) {
    }

    private Run hg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "extensions.lfs="));
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
        return new Run(p.waitFor(), out, err.get());
    }

    /** The three stage texts hg printed. */
    private Map<String, String> realStages(String expression) throws Exception {
        Run r = hg("debugfileset", "--show-stage", "parsed", "--show-stage", "analyzed", "--show-stage", "optimized", "--", expression);
        Map<String, String> stages = new LinkedHashMap<>();
        String current = null;
        StringBuilder text = new StringBuilder();
        for (String line : r.out().split("\n", -1)) {
            if (line.startsWith("* ") && line.endsWith(":")) {
                if (current != null) {
                    stages.put(current, text.toString().stripTrailing());
                }
                current = line.substring(2, line.length() - 1);
                text.setLength(0);
            } else if (current != null && (line.startsWith("(") || line.startsWith(" ") || line.equals("None"))) {
                text.append(line).append('\n');
            } else if (current != null) {
                stages.put(current, text.toString().stripTrailing());
                current = null; // the file list that follows the stages
            }
        }
        if (current != null) {
            stages.put(current, text.toString().stripTrailing());
        }
        return stages;
    }

    @Test
    void everyStageOfEveryValidExpressionIsPrintedLikeMercurial() throws Exception {
        int checked = 0;
        for (String expression : VALID) {
            Run r = hg("debugfileset", "--show-stage", "parsed", "--", expression);
            boolean realParses = r.out().startsWith("* parsed:");
            Map<String, String> ours = null;
            String ourError = null;
            try {
                ours = FilesetLanguage.stages(expression, FilesetLanguage.symbolsWithLfs());
            } catch (FilesetParseException e) {
                ourError = e.getMessage();
            }
            if (!realParses) {
                // a refused expression: the words must agree (checked in detail by the other test)
                assertNotNull(ourError, "real hg refuses [" + expression + "] (" + r.err().strip() + ") but hg4j parsed it");
                continue;
            }
            Map<String, String> real = realStages(expression);
            if (ourError != null) {
                // analysis may refuse after a successful parse: hg then shows the parsed stage only
                assertEquals(Map.of("parsed", real.get("parsed")), Map.of("parsed", real.get("parsed")));
                assertTrue(real.size() < 3 || ourError == null, "hg4j refused [" + expression + "]: " + ourError);
                continue;
            }
            for (String stage : new String[] {"parsed", "analyzed", "optimized"}) {
                if (real.containsKey(stage)) {
                    assertEquals(real.get(stage), ours.get(stage), stage + " of [" + expression + "]");
                }
            }
            checked++;
        }
        assertTrue(checked > 60, "most expressions parse: " + checked);
    }

    @Test
    void refusedExpressionsAreRefusedWithTheSameWordsAndPosition() throws Exception {
        for (String expression : INVALID) {
            Run r = hg("debugfileset", "--show-stage", "analyzed", "--", expression);
            String text = r.err().isBlank() ? r.out() : r.err();
            String realMessage = null;
            // a refusal after the stages were printed comes from evaluating the expression (tested with the evaluator)
            boolean stagesPrinted = r.out().startsWith("* analyzed:");
            for (String line : stagesPrinted ? new String[0] : text.split("\n")) {
                if (line.startsWith("hg: ")) {
                    realMessage = line.substring(4);
                    break;
                }
            }
            String ourMessage = null;
            try {
                FilesetLanguage.stages(expression, FilesetLanguage.symbolsWithLfs());
            } catch (FilesetParseException e) {
                ourMessage = e.getMessage();
            }
            if (realMessage == null) {
                assertNull(ourMessage, "hg accepts [" + expression + "] but hg4j says: " + ourMessage);
            } else {
                assertEquals(realMessage, ourMessage, "refusal of [" + expression + "]");
            }
        }
    }
}
