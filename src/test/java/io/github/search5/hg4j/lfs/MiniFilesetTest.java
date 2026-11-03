package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiniFilesetTest {

    private static boolean m(String expr, String path, long size) throws Exception {
        return MiniFileset.compile(expr).matches(path, size);
    }

    @Test
    void sizeUnitsFollowMercurialUtilSizetoint() throws Exception {
        assertEquals(30, MiniFileset.sizeToInt("30"));
        assertEquals(2252, MiniFileset.sizeToInt("2.2kb")); // the doctest of util.sizetoint
        assertEquals(6291456, MiniFileset.sizeToInt("6M"));
        assertEquals(1 << 30, MiniFileset.sizeToInt("1gb"));
        assertThrows(HgValidationException.class, () -> MiniFileset.sizeToInt("lots"));
    }

    @Test
    void anExactSizeWithAUnitCoversTheWholeRoundedRange() throws Exception {
        // max(4k) = 5k - 1: size("4k") matches 4096 up to 5119
        assertTrue(m("size(\"4k\")", "f", 4096));
        assertTrue(m("size(\"4k\")", "f", 5119));
        assertFalse(m("size(\"4k\")", "f", 5120));
        assertFalse(m("size(\"4k\")", "f", 4095));
    }

    @Test
    void comparisonsAndRanges() throws Exception {
        assertTrue(m("size(\">10\")", "f", 11));
        assertFalse(m("size(\">10\")", "f", 10));
        assertTrue(m("size(\">=10\")", "f", 10));
        assertTrue(m("size(\"<10\")", "f", 9));
        assertTrue(m("size(\"<=10\")", "f", 10));
        assertTrue(m("size(\"5-10\")", "f", 5));
        assertTrue(m("size(\"5-10\")", "f", 10));
        assertFalse(m("size(\"5-10\")", "f", 11));
    }

    @Test
    void pathPatternNeedsADirectoryBoundary() throws Exception {
        assertTrue(m("path:assets", "assets", 0));
        assertTrue(m("path:assets", "assets/a.dat", 0));
        assertFalse(m("path:assets", "assetsX/a.dat", 0));
        assertTrue(m("\"path:my dir\"", "my dir/x", 0));
    }

    @Test
    void operatorPrecedenceAndSpellings() throws Exception {
        // and binds tighter than or: a | (b & c)
        assertTrue(m("**.a | **.b & size(\">5\")", "x.a", 1));
        assertFalse(m("**.a | **.b & size(\">5\")", "x.b", 1));
        assertTrue(m("**.a + **.b", "x.b", 1));
        assertTrue(m("**.b or **.a", "x.b", 1));
        assertFalse(m("not **.b", "x.b", 1));
        assertTrue(m("!**.b", "x.a", 1));
        assertTrue(m("all() - **.b", "x.a", 1));
        assertFalse(m("all() - **.b", "x.b", 1));
        assertTrue(m("(none() | all())", "x", 1));
    }

    @Test
    void extensionPatternRejectsGlobCharacters() {
        HgValidationException e = assertThrows(HgValidationException.class, () -> MiniFileset.compile("**.t*r"));
        assertTrue(e.getMessage().contains("reserved character: *"), e.getMessage());
    }

    @Test
    void errorsNameTheProblem() {
        assertTrue(assertThrows(HgValidationException.class, () -> MiniFileset.compile("foo")).getMessage()
                .contains("unsupported file pattern: foo"));
        assertTrue(assertThrows(HgValidationException.class, () -> MiniFileset.compile("unknown()")).getMessage()
                .contains("unknown identifier: unknown"));
        assertTrue(assertThrows(HgValidationException.class, () -> MiniFileset.compile("size()")).getMessage()
                .contains("size requires an expression"));
        assertTrue(assertThrows(HgValidationException.class, () -> MiniFileset.compile("**.a, **.b")).getMessage()
                .contains("can't use a list"));
        assertTrue(assertThrows(HgValidationException.class, () -> MiniFileset.compile("size(\">1")).getMessage()
                .contains("unterminated string"));
        assertThrows(HgValidationException.class, () -> MiniFileset.compile("**.a )"));
        assertThrows(HgValidationException.class, () -> MiniFileset.compile("kind:thing"));
    }

    @Test
    void hglfsRulesKeepFileOrderAndOnlyReadTheTrackSection() throws Exception {
        var rules = HgLfsTracker.trackRules("# comment\n[other]\nx = y\n\n[track]\n**.bin = size(\">1\")\n; note\nall() = none()\n");
        assertEquals(2, rules.size());
        assertEquals("**.bin", rules.get(0)[0]);
        assertEquals("size(\">1\")", rules.get(0)[1]);
        assertEquals("all()", rules.get(1)[0]);
    }
}
