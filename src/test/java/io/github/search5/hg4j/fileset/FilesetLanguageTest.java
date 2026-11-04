package io.github.search5.hg4j.fileset;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The language without needing hg: the trees Mercurial prints, the refusals, the hints. */
class FilesetLanguageTest {

    private static final Map<String, FilesetLanguage.Symbol> SYMBOLS = FilesetLanguage.coreSymbols();

    @Test
    void parsedAnalyzedAndOptimizedTreesOfAnExpression() throws Exception {
        Map<String, String> stages = FilesetLanguage.stages("a or (b and not c) - d:e", SYMBOLS);
        assertEquals("(or\n  (symbol 'a')\n  (minus\n    (group\n      (and\n        (symbol 'b')\n        (not\n          (symbol 'c'))))\n"
                + "    (kindpat\n      (symbol 'd')\n      (symbol 'e'))))", stages.get("parsed"));
        assertTrue(stages.get("analyzed").startsWith("(or\n  (symbol 'a')\n  (and\n    (and"));
        assertTrue(stages.get("optimized").contains("(patterns"));
    }

    @Test
    void chainsOfOrAreFlat() throws Exception {
        assertEquals("(or\n  (symbol 'a')\n  (symbol 'b')\n  (symbol 'c'))", FilesetLanguage.prettyFormat(FilesetLanguage.parse("a or b or c")));
    }

    @Test
    void statusPredicatesGetAStatusHintWhenTwoSidesNeedIt() throws Exception {
        String analyzed = FilesetLanguage.stages("modified() and added()", SYMBOLS).get("analyzed");
        assertTrue(analyzed.startsWith("(withstatus"), analyzed);
        assertTrue(analyzed.contains("(string 'modified added')"), analyzed);
    }

    @Test
    void refusalsCarryMercurialsWordsAndPositions() {
        assertEquals("parse error at 4: not a prefix: end", message("foo("));
        assertEquals("parse error at 2: invalid token", message("a b"));
        assertEquals("parse error at 0: not a prefix: or", message("or"));
        assertEquals("parse error at 1: unterminated string", message("'abc"));
        assertEquals("parse error at 2: unexpected token: end", message("(a"));
        assertEquals("parse error: can't use negate operator in this context", message("-a"));
        assertEquals("parse error: not a symbol", message("x:y:z"));
        assertEquals("parse error at 1: syntax error", message("a;b"));
    }

    private static String message(String expression) {
        try {
            FilesetLanguage.stages(expression, SYMBOLS);
            return "no error";
        } catch (FilesetParseException e) {
            return e.getMessage();
        }
    }

    @Test
    void similarNamesAreSuggested() {
        FilesetParseException one = Fileset.unknownIdentifier("modifed", SYMBOLS.keySet());
        assertEquals("parse error: unknown identifier: modifed", one.getMessage());
        assertEquals("did you mean one of copied, modified?", one.getHint()); // as hg prints it
        assertNull(Fileset.unknownIdentifier("zzzzzz", SYMBOLS.keySet()).getHint());
    }

    @Test
    void difflibRatioMatchesPython() {
        assertEquals(0.9333333333333333, Difflib.ratio("modifed", "modified"), 1e-12); // python: SequenceMatcher(None, "modifed", "modified").ratio()
        assertEquals(1.0, Difflib.ratio("", ""), 0);
        assertEquals(0.0, Difflib.ratio("abc", "xyz"), 0);
    }
}
