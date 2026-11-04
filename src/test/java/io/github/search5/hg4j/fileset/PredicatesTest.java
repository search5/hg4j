package io.github.search5.hg4j.fileset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parts of the predicates that need no repository: size expressions, Windows file names, Python regex mistakes. */
class PredicatesTest {

    @Test
    void sizeExpressionsMeanWhatMercurialDocuments() throws Exception {
        assertTrue(Sizes.matcher("1k").test(1024) && Sizes.matcher("1k").test(2047) && !Sizes.matcher("1k").test(2048));
        assertTrue(Sizes.matcher("< 20k").test(20479) && !Sizes.matcher("< 20k").test(20480));
        assertTrue(Sizes.matcher(">= .5MB").test(524288) && !Sizes.matcher(">= .5MB").test(524287));
        assertTrue(Sizes.matcher("4k - 1MB").test(4096) && Sizes.matcher("4k - 1MB").test(1048576));
        assertEquals(2252, Sizes.toInt("2.2kb"));
        assertEquals(6291456, Sizes.toInt("6M"));
        FilesetParseException bad = assertThrows(FilesetParseException.class, () -> Sizes.toInt("x"));
        assertEquals("parse error: couldn't parse size: x", bad.getMessage());
    }

    @Test
    void windowsFileNamesAsMercurialsDoctests() {
        assertNull(Predicates.checkWinFilename("just/a/normal/path"));
        assertEquals("filename contains 'con', which is reserved on Windows", Predicates.checkWinFilename("foo/bar/con.xml"));
        assertEquals("filename contains 'con', which is reserved on Windows", Predicates.checkWinFilename("foo/con.xml/bar"));
        assertNull(Predicates.checkWinFilename("foo/bar/xml.con"));
        assertEquals("filename contains 'AUX', which is reserved on Windows", Predicates.checkWinFilename("foo/bar/AUX/bla.txt"));
        assertEquals("filename contains ':', which is reserved on Windows", Predicates.checkWinFilename("foo/bar/bla:.txt"));
        assertEquals("filename contains '\\x07', which is invalid on Windows", Predicates.checkWinFilename("foo/bar/b\u0007la.txt"));
        assertEquals("filename ends with ' ', which is not allowed on Windows", Predicates.checkWinFilename("foo/bar/bla "));
        assertNull(Predicates.checkWinFilename("../bar"));
        assertEquals("filename ends with '\\', which is invalid on Windows", Predicates.checkWinFilename("foo\\"));
        assertEquals("directory name ends with '\\', which is invalid on Windows", Predicates.checkWinFilename("foo\\/bar"));
    }

    @Test
    void pythonRegexMistakesReadLikePython() {
        assertEquals("missing ), unterminated subpattern at position 0", PythonRegex.describeError("("));
        assertEquals("unterminated character set at position 0", PythonRegex.describeError("["));
        assertEquals("nothing to repeat at position 0", PythonRegex.describeError("*"));
        assertEquals("multiple repeat at position 2", PythonRegex.describeError("a**"));
        assertEquals("unbalanced parenthesis at position 0", PythonRegex.describeError(")"));
        assertEquals("bad escape \\q at position 0", PythonRegex.describeError("\\q"));
        assertEquals("bad escape (end of pattern) at position 0", PythonRegex.describeError("\\"));
        assertNull(PythonRegex.describeError("a(b|c)+[x-z]\\d{2,3}?"));
    }
}
