package com.github.search5.hg4j.lib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for {@link HgRcConfig}, exercising branches not hit by
 * {@link HgRcConfigTest}: empty/null content, line continuations, {@code ;} comments,
 * {@code %include}/{@code %unset} edge cases, malformed lines, and the
 * getter/setter/save no-op and error paths.
 */
public class HgRcConfigCoverageTest {

    @TempDir
    File tempDir;

    @Test
    public void parseNullContentIsNoOp() {
        HgRcConfig config = new HgRcConfig();
        config.parse(null);
        assertNull(config.getUsername());
    }

    @Test
    public void parseEmptyContentIsNoOp() {
        HgRcConfig config = new HgRcConfig();
        config.parse("");
        assertNull(config.getUsername());
    }

    @Test
    public void continuationLineAppendsToPreviousValue() {
        String ini = "[ui]\n" +
                "message = first line\n" +
                "  second line\n" +
                "  third line\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("first line\nsecond line\nthird line", config.get("ui", "message"));
    }

    @Test
    public void whitespaceOnlyLineDoesNotContinueAndDoesNotBreakParsing() {
        // A line consisting only of whitespace must not be treated as a continuation
        // (trim().isEmpty() branch) and must not corrupt subsequent parsing.
        String ini = "[ui]\n" +
                "username = Base\n" +
                "   \n" +
                "verbose = True\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Base", config.get("ui", "username"));
        assertEquals("True", config.get("ui", "verbose"));
    }

    @Test
    public void semicolonCommentsAreIgnored() {
        String ini = "[ui]\n" +
                "; this is a comment\n" +
                "username = Semi Tester\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Semi Tester", config.get("ui", "username"));
    }

    @Test
    public void includeDirectiveWithNoPathIsIgnored() throws Exception {
        String ini = "[ui]\n" +
                "username = Before\n" +
                "%include\n" +
                "%include   \n" +
                "username2 = After\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini, tempDir);
        assertEquals("Before", config.get("ui", "username"));
        assertEquals("After", config.get("ui", "username2"));
    }

    @Test
    public void includeWithAbsolutePathIsUsedAsIs() throws Exception {
        File includedFile = new File(tempDir, "abs-included.rc");
        Files.writeString(includedFile.toPath(), "[extra]\nfromAbsInclude = yes\n");

        // baseDir is intentionally a different, unrelated directory to prove the
        // absolute include path is honored verbatim rather than joined with baseDir.
        File otherDir = new File(tempDir, "unrelated");
        assertTrue(otherDir.mkdir());

        String ini = "%include " + includedFile.getAbsolutePath() + "\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini, otherDir);
        assertEquals("yes", config.get("extra", "fromAbsInclude"));
    }

    @Test
    public void relativeIncludeWithoutBaseDirIsSkipped() {
        // parse(String) delegates to parse(content, null); a relative %include cannot
        // be resolved without a base directory and must be silently skipped.
        String ini = "[ui]\nusername = Keep\n%include relative-only.rc\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Keep", config.get("ui", "username"));
        assertNull(config.get("extra", "fromInclude"));
    }

    @Test
    public void includeOfMissingFileIsSilentlyIgnored() throws Exception {
        String ini = "[ui]\nusername = Still Here\n%include does-not-exist.rc\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini, tempDir);
        assertEquals("Still Here", config.get("ui", "username"));
    }

    @Test
    public void includeOfUnreadableFileIsSilentlyIgnored() throws Exception {
        File unreadable = new File(tempDir, "unreadable.rc");
        Files.writeString(unreadable.toPath(), "[extra]\nshouldNotAppear = yes\n");
        assertTrue(unreadable.setReadable(false, false), "test requires ability to revoke read permission");
        try {
            String ini = "[ui]\nusername = Survives\n%include unreadable.rc\n";
            HgRcConfig config = new HgRcConfig();
            config.parse(ini, tempDir);
            assertEquals("Survives", config.get("ui", "username"));
            assertNull(config.get("extra", "shouldNotAppear"));
        } finally {
            unreadable.setReadable(true, false);
        }
    }

    @Test
    public void unsetWithEmptyNameIsIgnored() {
        String ini = "[ui]\nusername = Kept\n%unset\n%unset   \n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Kept", config.get("ui", "username"));
    }

    @Test
    public void unsetBeforeAnySectionIsIgnored() {
        String ini = "%unset username\n[ui]\nusername = Set After\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Set After", config.get("ui", "username"));
    }

    @Test
    public void malformedSectionHeaderWithoutClosingBracketIsIgnored() {
        String ini = "[ui\nusername = Ignored Section Header\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        // No section was ever opened, so the key=value line has no current section
        // and must be dropped rather than crash.
        assertNull(config.get("ui", "username"));
    }

    @Test
    public void keyValueLineBeforeAnySectionIsIgnored() {
        String ini = "orphan = value\n[ui]\nusername = Real\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Real", config.get("ui", "username"));
    }

    @Test
    public void lineWithoutEqualsSignOutsideDirectivesIsIgnored() {
        String ini = "[ui]\nusername = Real\njust some garbage text\nverbose = True\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini);
        assertEquals("Real", config.get("ui", "username"));
        assertEquals("True", config.get("ui", "verbose"));
    }

    @Test
    public void getWithDefaultReturnsActualValueWhenPresent() {
        HgRcConfig config = new HgRcConfig();
        config.set("ui", "username", "Actual Value");
        assertEquals("Actual Value", config.get("ui", "username", "default_val"));
    }

    @Test
    public void setWithNullSectionOrKeyIsNoOp() {
        HgRcConfig config = new HgRcConfig();
        config.set(null, "key", "value");
        config.set("section", null, "value");
        assertNull(config.get("section", "key"));
        assertNull(config.get(null, "key"));
    }

    @Test
    public void saveWithNullFileThrows() {
        HgRcConfig config = new HgRcConfig();
        assertThrows(IllegalArgumentException.class, () -> config.save(null));
    }

    @Test
    public void saveSkipsEmptySections() throws Exception {
        HgRcConfig config = new HgRcConfig();
        // Populate then fully unset the section so it exists in the map but is empty.
        config.set("empty", "temp", "value");
        config.set("empty", "temp", null);
        config.set("ui", "username", "Present");

        File saveFile = new File(tempDir, "saved_hgrc");
        config.save(saveFile);

        String written = Files.readString(saveFile.toPath());
        assertFalse(written.contains("[empty]"), "empty sections must not be written");
        assertTrue(written.contains("[ui]"));
    }

    @Test
    public void loadOfDirectoryIsNoOp() throws Exception {
        HgRcConfig config = new HgRcConfig();
        // tempDir exists but is not a file; load() must return without throwing.
        config.load(tempDir);
        assertNull(config.getUsername());
    }

    @Test
    public void loadOfNullFileIsNoOp() throws Exception {
        HgRcConfig config = new HgRcConfig();
        config.load(null);
        assertNull(config.getUsername());
    }

    @Test
    public void relativeIncludeWithRelativeBaseDirIsStillAttempted() {
        // When baseDir itself is a relative File (not baseDir == null), the joined
        // include path stays relative too (File(relativeParent, child).isAbsolute()
        // is false), which exercises the includeFile.isAbsolute()==false /
        // baseDir!=null==true combination distinct from the null-baseDir case above.
        File relativeBaseDir = new File("hgrc-coverage-relative-basedir-" + System.nanoTime());
        String ini = "[ui]\nusername = Kept\n%include child.rc\n";
        HgRcConfig config = new HgRcConfig();
        config.parse(ini, relativeBaseDir);
        assertEquals("Kept", config.get("ui", "username"));
    }
}
