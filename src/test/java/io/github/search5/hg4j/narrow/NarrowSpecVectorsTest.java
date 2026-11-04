package io.github.search5.hg4j.narrow;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.match.Matcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NarrowSpec} against the real {@code mercurial.narrowspec} of Mercurial 7.2 (vectors made with it, see
 * {@code fixtures/shape/README.md}): pattern normalization and its messages, {@code format}, {@code parseconfig}, {@code restrictpatterns}
 * and the matcher over 12 paths.
 */
class NarrowSpecVectorsTest {

    private static String str(String field) {
        return new String(HexFormat.of().parseHex(field.substring(1)), StandardCharsets.UTF_8);
    }

    private static List<String> list(String field) {
        List<String> out = new ArrayList<>();
        if (!field.isEmpty()) {
            for (String item : field.split(",", -1)) {
                out.add(str(item));
            }
        }
        return out;
    }

    private static List<String[]> lines(char type) throws Exception {
        try (InputStream in = NarrowSpecVectorsTest.class.getResourceAsStream("/fixtures/shape/narrowspec-vectors.tsv")) {
            List<String[]> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", -1);
                if (f[0].charAt(0) == type) {
                    out.add(Arrays.copyOfRange(f, 1, f.length));
                }
            }
            return out;
        }
    }

    @Test
    void normalizingAPatternGivesHgsResultOrHgsMessage() throws Exception {
        List<String[]> cases = lines('N');
        assertFalse(cases.isEmpty());
        for (String[] f : cases) {
            String input = str(f[0]);
            String expected = str(f[2]);
            try {
                Set<String> parsed = NarrowSpec.parsePatterns(List.of(input)); // normalizepattern + validatepatterns
                assertEquals("ok", f[1], "'" + input + "' should be refused with: " + expected);
                assertEquals(expected, parsed.iterator().next(), "'" + input + "'");
            } catch (HgValidationException e) {
                assertNotEquals("ok", f[1], "'" + input + "' should be accepted as " + expected + " but: " + e.getMessage());
                String message = e.getMessage();
                int hint = message.indexOf("\n(hint:");
                assertEquals(expected, hint >= 0 ? message.substring(0, hint) : message, "'" + input + "'");
            }
        }
    }

    @Test
    void formatMatchesHg() throws Exception {
        for (String[] f : lines('F')) {
            assertEquals(str(f[2]), NarrowSpec.format(list(f[0]), list(f[1])), f[0] + " / " + f[1]);
        }
    }

    @Test
    void parseConfigMatchesHg() throws Exception {
        for (String[] f : lines('P')) {
            String text = str(f[0]);
            try {
                NarrowSpec.Patterns parsed = NarrowSpec.parseConfig(text, new ArrayList<>());
                assertEquals("ok", f[1], text);
                assertEquals(new TreeSet<>(list(f[2])), new TreeSet<>(parsed.includes()), text);
                assertEquals(new TreeSet<>(list(f[3])), new TreeSet<>(parsed.excludes()), text);
            } catch (HgValidationException e) {
                assertEquals("abort", f[1], text + " -> " + e.getMessage());
                String message = e.getMessage();
                int hint = message.indexOf("\n(hint:");
                assertEquals(str(f[2]), hint >= 0 ? message.substring(0, hint) : message, text);
            }
        }
    }

    @Test
    void aSlashLedPatternIsDroppedWithAWarning() throws Exception {
        List<String> warnings = new ArrayList<>();
        NarrowSpec.Patterns parsed = NarrowSpec.parseConfig("[include]\n/path:a\npath:b\n", warnings);
        assertEquals(Set.of("path:b"), parsed.includes());
        assertEquals(List.of("warning: narrow profile cannot use paths starting with /, ignoring /path:a\n"), warnings);
    }

    @Test
    void restrictPatternsMatchesHg() throws Exception {
        for (String[] f : lines('R')) {
            NarrowSpec.Restricted r = NarrowSpec.restrictPatterns(list(f[0]), list(f[1]), list(f[2]), list(f[3]));
            String context = String.join(" | ", f);
            assertEquals(new TreeSet<>(list(f[4])), new TreeSet<>(r.includes()), context);
            assertEquals(new TreeSet<>(list(f[5])), new TreeSet<>(r.excludes()), context);
            assertEquals(list(f[6]), r.invalidIncludes(), context);
        }
    }

    @Test
    void theMatcherMatchesHgOnEveryPath() throws Exception {
        List<String> files = list(lines('X').get(0)[0]);
        assertEquals(12, files.size());
        List<String[]> cases = lines('M');
        assertEquals(8, cases.size());
        for (String[] f : cases) {
            Matcher matcher = NarrowSpec.match(list(f[0]), list(f[1]));
            StringBuilder got = new StringBuilder();
            for (String file : files) {
                got.append(matcher.matches(file) ? '1' : '0');
            }
            assertEquals(f[2], got.toString(), f[0] + " / " + f[1] + " over " + files);
        }
    }

    @Test
    void legacyPatternsOfAShapeAndTheStaleMessage() throws Exception {
        NarrowSpec.Patterns legacy = NarrowSpec.toLegacyPatterns(new LinkedHashSet<>(List.of("", "a")), new LinkedHashSet<>(List.of("b")));
        assertEquals(Set.of("path:.", "path:a"), legacy.includes());
        assertEquals(Set.of("path:b"), legacy.excludes());
        assertTrue(NarrowSpec.isWorkingCopyStale(new NarrowSpec.Patterns(Set.of("path:a"), Set.of()), new NarrowSpec.Patterns(Set.of("path:b"), Set.of())));
        assertFalse(NarrowSpec.isWorkingCopyStale(legacy, legacy));
        assertEquals("working copy's narrowspec is stale\n(hint: run 'hg tracked --update-working-copy')", NarrowSpec.STALE_MESSAGE);
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    @Test
    void anUpdateRefusesAStaleWorkingCopyNarrowspec() throws Exception {
        java.io.File dir = tempDir.resolve("narrow").toFile();
        io.github.search5.hg4j.lib.HgRepository repo = io.github.search5.hg4j.api.Hg.init().setDirectory(dir).call();
        java.nio.file.Files.writeString(new java.io.File(repo.getStoreDir(), "narrowspec").toPath(), "[include]\npath:a\n");
        // no narrowspec.dirstate: hg reads it as empty, which is not the store's
        IOException stale = assertThrows(IOException.class, () -> io.github.search5.hg4j.treewalk.HgTreeFilter.loadForWorkingCopy(repo));
        assertEquals(NarrowSpec.STALE_MESSAGE, stale.getMessage());
        java.nio.file.Files.writeString(new java.io.File(repo.getHgDir(), "narrowspec.dirstate").toPath(), "[include]\npath:a\n");
        assertTrue(io.github.search5.hg4j.treewalk.HgTreeFilter.loadForWorkingCopy(repo).accept("a/x"));
        java.nio.file.Files.writeString(new java.io.File(repo.getStoreDir(), "narrowspec").toPath(), "[include]\npath:a\npath:b\n");
        assertThrows(IOException.class, () -> io.github.search5.hg4j.treewalk.HgTreeFilter.loadForWorkingCopy(repo));
        repo.close();
    }
}
