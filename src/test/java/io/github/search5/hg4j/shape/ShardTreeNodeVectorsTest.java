package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage A of the shape port, checked against the Python implementation in Mercurial 7.2 ({@code mercurial.shape}): 400 generated cases of
 * includes and excludes (see {@code fixtures/shape/README.md}) give the same fingerprint, the same patterns block and the same answer to
 * "does the matcher select this path" for about 40 paths each. The doctest values of {@code shape.py} are checked as well.
 */
class ShardTreeNodeVectorsTest {

    private static Set<String> items(String field) {
        Set<String> out = new LinkedHashSet<>();
        if (!field.isEmpty()) {
            for (String item : field.split(",", -1)) {
                out.add(new String(HexFormat.of().parseHex(item.substring(1)), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<String> lines() throws IOException {
        try (InputStream in = ShardTreeNodeVectorsTest.class.getResourceAsStream("/fixtures/shape/tree-vectors.tsv")) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    @Test
    void everyGeneratedCaseGivesPythonsFingerprintBlockAndMatches() throws Exception {
        int cases = 0;
        for (String line : lines()) {
            String[] f = line.split("\t", -1);
            Set<String> includes = items(f[0]);
            Set<String> excludes = items(f[1]);
            ShardTreeNode tree = ShardTreeNode.fromPatterns(includes, excludes);
            String context = "includes=" + includes + " excludes=" + excludes + " tree=" + tree;
            assertEquals(f[2], tree.fingerprint().toText(), context);
            assertEquals(f[3], HexFormat.of().formatHex(tree.serializePatterns()), context);
            ShapeMatcher matcher = new ShapeMatcher(tree);
            List<String> samples = new ArrayList<>(items(f[4]));
            // a set drops duplicate samples, the vector keeps them: compare by position over the original order
            String[] raw = f[4].isEmpty() ? new String[0] : f[4].split(",", -1);
            assertEquals(raw.length, f[5].length(), "one answer per sample");
            for (int i = 0; i < raw.length; i++) {
                String sample = new String(HexFormat.of().parseHex(raw[i].substring(1)), StandardCharsets.UTF_8);
                assertEquals(f[5].charAt(i) == '1', matcher.matches(sample), "'" + sample + "' in " + context);
            }
            cases++;
        }
        assertEquals(400, cases);
    }

    @Test
    void thePatternsBlockRoundTrips() throws Exception {
        for (String line : lines()) {
            String[] f = line.split("\t", -1);
            ShardTreeNode tree = ShardTreeNode.fromPatterns(items(f[0]), items(f[1]));
            ShardTreeNode.Patterns flat = tree.flat();
            ShardTreeNode.Patterns back = ShardTreeNode.deserializePatterns(HexFormat.of().parseHex(f[3]));
            assertEquals(flat.includes(), back.includes());
            assertEquals(flat.excludes(), back.excludes());
        }
    }

    @Test
    void theDoctestValuesOfShapePy() throws Exception {
        ShardTreeNode node = ShardTreeNode.fromPatterns(Set.of("foo", "foo-x"), Set.of("foo/bar"));
        assertEquals("shape00-c1e75c57728686409c683838de48531c7705eeca5e8bb8d14fe8f9f7cbd0888b", node.fingerprint().toText());
        byte[] block = ShardTreeNode.serializePatterns(Set.of("foo", "foo-x"), Set.of("", "foo/bar"));
        assertEquals("00000004" + "0000" + "8003" + "0007" + "8005" + "666f6f" + "666f6f2f626172" + "666f6f2d78", HexFormat.of().formatHex(block));
        assertArrayEquals(new byte[]{0, 0, 0, 0}, ShardTreeNode.serializePatterns(Set.of(), Set.of()));
    }

    private static String deserializeError(byte[] data) {
        try {
            ShardTreeNode.deserializePatterns(data);
            return "no error";
        } catch (HgValidationException e) {
            return e.getMessage();
        }
    }

    @Test
    void deserializingBadBlocksFailsWithPythonsMessages() {
        assertEquals("error deserializing shape: invalid length (expected 4, got 3)", deserializeError(new byte[]{0, 0, 1}));
        assertEquals("error deserializing shape: invalid length (expected 8, got 6)", deserializeError(new byte[]{0, 0, 0, 2, (byte) 0x80, 3}));
        assertEquals("error deserializing shape: invalid length (expected 9, got 8)",
                deserializeError(new byte[]{0, 0, 0, 1, (byte) 0x80, 3, 'f', 'o'}));
        assertEquals("error deserializing shape: invalid length (expected 9, got 10)",
                deserializeError(new byte[]{0, 0, 0, 1, (byte) 0x80, 3, 'f', 'o', 'o', 'o'}));
        assertEquals("error deserializing shape: invalid length (expected 4, got 5)", deserializeError(new byte[]{0, 0, 0, 0, 'f'}));
        assertEquals("error deserializing shape: invalid path '/f'", deserializeError(new byte[]{0, 0, 0, 1, 0, 2, '/', 'f'}));
        assertEquals("no error", deserializeError(new byte[]{0, 0, 0, 0}));
    }

    @Test
    void fingerprintTextAndBinaryForms() throws Exception {
        byte[] ab = new byte[32];
        java.util.Arrays.fill(ab, (byte) 0xab);
        Fingerprint shape = new Fingerprint(Fingerprint.Kind.SHAPE, 0, ab);
        assertEquals("shape00-" + "ab".repeat(32), shape.toText());
        assertEquals("shard00-" + "ab".repeat(32), shape.asShard().toText());
        assertNotEquals(shape, shape.asShard());
        assertEquals("shape12-" + "01".repeat(32), new Fingerprint(Fingerprint.Kind.SHAPE, 12, new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}).toText());
        assertEquals("shape255-01010101", new Fingerprint(Fingerprint.Kind.SHAPE, 255, new byte[]{1, 1, 1, 1}).toText());
        assertEquals("shape42-01010101", new Fingerprint(Fingerprint.Kind.SHAPE, 42, new byte[]{1, 1, 1, 1}).toText());
        assertEquals(shape, Fingerprint.fromBytes(Fingerprint.Kind.SHAPE, shape.toBytes()));
        assertEquals(shape, Fingerprint.fromText(Fingerprint.Kind.SHAPE, "shape00-" + "ab".repeat(32)));
        assertEquals(new Fingerprint(Fingerprint.Kind.SHAPE, 42, new byte[48]).version(),
                Fingerprint.fromText(Fingerprint.Kind.SHAPE, "shape42-" + "00".repeat(48)).version());
        for (String invalid : List.of("", "abab", "shape00-", "shape00-" + "ab".repeat(31), "shape00-" + "ab".repeat(33),
                "shape00-" + "AB".repeat(32), "shape42-", "shape42-abc", "shape00-" + "ab".repeat(32) + " ", "shape00:" + "ab".repeat(32),
                "shape-" + "ab".repeat(32), "shape0-" + "ab".repeat(32), "shape256-" + "ab".repeat(32), "shape012-" + "ab".repeat(32),
                "00-" + "ab".repeat(32), "shard00-" + "ab".repeat(32))) {
            assertThrows(HgValidationException.class, () -> Fingerprint.fromText(Fingerprint.Kind.SHAPE, invalid), invalid);
        }
        HgValidationException wrongKind = assertThrows(HgValidationException.class, () -> Fingerprint.fromText(Fingerprint.Kind.SHARD, "shape00-abab"));
        assertEquals("not a shard fingerprint: shape00-abab", wrongKind.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.fromBytes(Fingerprint.Kind.SHAPE, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.fromBytes(Fingerprint.Kind.SHAPE, new byte[32]), "version 0 needs 32 bytes");
    }

    @Test
    void shapeNamesFollowTheRules() throws Exception {
        assertEquals(List.of("default"), ShapeNames.parse("default"));
        assertEquals(List.of("babar", "celeste"), ShapeNames.parse("celeste,babar,celeste"));
        assertEquals("shard names must not be empty", assertThrows(HgValidationException.class, () -> ShapeNames.parse("babar,,celeste")).getMessage());
        assertEquals("invalid shard name '-.': missing lowercase alphanumeric character",
                assertThrows(HgValidationException.class, () -> ShapeNames.validate("-.")).getMessage());
        assertEquals("invalid shard name 'Babar': only lowercase alphanumeric, hyphen or dot are accepted",
                assertThrows(HgValidationException.class, () -> ShapeNames.validate("Babar")).getMessage());
        assertEquals("invalid shard name 'Celeste': only lowercase alphanumeric, hyphen or dot are accepted",
                assertThrows(HgValidationException.class, () -> ShapeNames.parse("babar,Celeste")).getMessage());
    }

    @Test
    void theMatcherVisitsLikeRust() throws Exception {
        // shape "bar" of the Rust test config, before the config code exists: .hg-files paths included next to bar, bar/baz excluded
        ShardTreeNode tree = ShardTreeNode.fromPaths(
                List.of("", ".hgignore", ".hgtags", ".hgsub", ".hgsubstate", "bar", "bar/baz"),
                Set.of(".hgignore", ".hgtags", ".hgsub", ".hgsubstate", "bar"));
        ShapeMatcher matcher = new ShapeMatcher(tree);
        ShapeMatcher.Visit root = matcher.visitChildrenSet("");
        assertEquals(ShapeMatcher.Visit.Kind.SET, root.kind());
        assertEquals(Set.of("bar", ".hgignore", ".hgsubstate", ".hgtags", ".hgsub"), root.children());
        assertEquals(ShapeMatcher.Visit.Kind.RECURSIVE, matcher.visitChildrenSet("bar").kind());
        assertEquals(ShapeMatcher.Visit.Kind.RECURSIVE, matcher.visitChildrenSet("bar/file").kind());
        assertEquals(ShapeMatcher.Visit.Kind.EMPTY, matcher.visitChildrenSet("other").kind());
        assertEquals(ShapeMatcher.Visit.Kind.EMPTY, matcher.visitChildrenSet("bar/baz").kind());
        assertTrue(matcher.matches("bar/x"));
        assertFalse(matcher.matches("bar/baz/y"));
        assertFalse(matcher.matches(""));
        assertFalse(matcher.always());
    }
}
