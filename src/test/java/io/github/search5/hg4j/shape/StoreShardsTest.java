package io.github.search5.hg4j.shape;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage B of the shape port: the configuration, the shards and the shapes. These are the unit tests of {@code narrow/shape.rs}
 * ({@code valid_store_shards}, {@code test_config_invalid}, {@code test_dependencies}, {@code test_patterns}, {@code test_matcher_simple},
 * the change tests, ...) carried over one for one: they are the specification of that code.
 */
class StoreShardsTest {

    private static final String VALID_CONFIG =
            "version = 0\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"foo-files\"\n" +
            "paths = [\"foo\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"stone-files\"\n" +
            "paths = [\"stone\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"lizard\"\n" +
            "paths = [\"stone/liz/ard\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"lizzie\"\n" +
            "paths = [\"stone/liz/zie\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"borden-files\"\n" +
            "paths = [\"stone/liz/zie/bor/den\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"mummy-files\"\n" +
            "paths = [\"stone/mummy\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"chamber-files\"\n" +
            "paths = [\"stone/mummy/chamber\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"inner\"\n" +
            "paths = [\"stone/mummy/chamber/inner\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"bar-files\"\n" +
            "paths = [\"bar\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"baz\"\n" +
            "paths = [\"bar/baz\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"bazik-files\"\n" +
            "paths = [\"bar/baz/ik\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"baziku\"\n" +
            "paths = [\"bar/baz/ik/u\"]\n" +
            "\n" +
            "[[shards]]\n" +
            "name = \"nested-files\"\n" +
            "paths = [\"some/super/nested/file\"]\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"foo\"\n" +
            "includes = [\"foo-files\", \"foo-deps\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"foo-deps\"\n" +
            "includes = [\"baz\", \"base\"]\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"stone\"\n" +
            "includes = [\"stone-files\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"borden\"\n" +
            "includes = [\"borden-files\", \"stone\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"mummy\"\n" +
            "includes = [\"mummy-files\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"chamber\"\n" +
            "includes = [\"chamber-files\", \"stone\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"bar\"\n" +
            "includes = [\"bar-files\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"bazik\"\n" +
            "includes = [\"bazik-files\", \"bar\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"nested\"\n" +
            "includes = [\"nested-files\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"baron\"\n" +
            "includes = [\"baron-parts\"]\n" +
            "public = true\n" +
            "\n" +
            "[[profiles]]\n" +
            "name = \"baron-parts\"\n" +
            "includes = [\"bar\", \"baziku\"]\n" +
            "\n";

    private static final List<String> ALL_SHARD_NAMES = List.of(".hg-files", "bar", "bar-files", "baron", "baron-parts", "base", "baz",
            "bazik", "bazik-files", "baziku", "borden", "borden-files", "chamber", "chamber-files", "foo", "foo-deps", "foo-files", "full",
            "inner", "lizard", "lizzie", "mummy", "mummy-files", "nested", "nested-files", "stone", "stone-files");
    private static final List<String> ALL_SHAPE_NAMES = List.of("bar", "baron", "bazik", "borden", "chamber", "foo", "full", "mummy",
            "nested", "stone");

    private static StoreShards valid() throws Exception {
        StoreShards shards = StoreShards.fromBytes(VALID_CONFIG.getBytes(StandardCharsets.UTF_8));
        assertEquals(ALL_SHARD_NAMES, new ArrayList<>(shards.shards().keySet()));
        List<String> shapeNames = new ArrayList<>();
        for (Shape shape : shards.allShapes()) {
            shapeNames.add(String.join(",", shape.names()));
        }
        assertEquals(ALL_SHAPE_NAMES, shapeNames);
        for (String name : List.of("foo-deps", "baron-parts")) { // only usable through the shapes that include them
            assertEquals(ShapeException.Kind.UNKNOWN_PROFILE, assertThrows(ShapeException.class, () -> shards.shape(List.of(name))).kind());
        }
        return shards;
    }

    @Test
    void theValidConfigLoadsWithTheImplicitShardsAndShapes() throws Exception {
        valid();
    }

    @Test
    void everyShardsPatternsSurviveSerialization() throws Exception {
        StoreShards shards = valid();
        for (StoreShards.Shard shard : shards.shards().values()) {
            if (!shard.shape()) {
                continue;
            }
            Shape shape = shards.shape(List.of(shard.name()));
            ShardTreeNode.Patterns back = ShardTreeNode.deserializePatterns(shape.serializePatterns());
            assertEquals(shape.patterns().includes(), back.includes(), shard.name());
            assertEquals(shape.patterns().excludes(), back.excludes(), shard.name());
        }
    }

    private static void assertConfigError(String config, ShapeException.Kind kind, String subject) {
        ShapeException e = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(config.getBytes(StandardCharsets.UTF_8)), config);
        assertEquals(kind, e.kind(), config + " -> " + e.getMessage());
        if (subject != null) {
            assertEquals(subject, e.subject(), config);
        }
    }

    @Test
    void invalidConfigurationsAreReportedWithTheRightKind() {
        String one = "version=0\n[[shards]]\nname=\"%s\"\npaths=[\"%s\"]\n";
        assertConfigError(one.formatted("full", ""), ShapeException.Kind.RESERVED_NAME, "full");
        assertConfigError(one.formatted("base", ""), ShapeException.Kind.RESERVED_NAME, "base");
        assertConfigError(one.formatted(".hg-files", ""), ShapeException.Kind.RESERVED_NAME, ".hg-files");
        assertConfigError("version=999\n[[shards]]\nname=\"babar\"\npaths=[\"babar\"]\n", ShapeException.Kind.UNKNOWN_VERSION, "999");
        assertConfigError(one.formatted("Babar", "babar"), ShapeException.Kind.INVALID_SHARD_NAME, "Babar");
        assertConfigError(one.formatted("", "babar"), ShapeException.Kind.EMPTY_SHARD_NAME, null);
        assertConfigError(one.formatted(".", "babar"), ShapeException.Kind.DOT_OR_HYPHEN_ONLY_SHARD_NAME, ".");
        assertConfigError(one.formatted("-", "babar"), ShapeException.Kind.DOT_OR_HYPHEN_ONLY_SHARD_NAME, "-");
        assertConfigError(one.formatted("-.-", "babar"), ShapeException.Kind.DOT_OR_HYPHEN_ONLY_SHARD_NAME, "-.-");
        assertConfigError("version=0\n[[shards]]\nname=\"duplicate\"\npaths=[\"babar\"]\n[[shards]]\nname=\"duplicate\"\npaths=[\"babar2\"]\n",
                ShapeException.Kind.DUPLICATE_SHARD, "duplicate");
        assertConfigError("version=0\n[[shards]]\nname=\"too-little\"\npaths=[]\n", ShapeException.Kind.SHARD_MISSING_PATHS, "too-little");
        assertConfigError("version=0\n[[shards]]\nname=\"s\"\npaths=[\"s\"]\n[[profiles]]\nname=\"too-little\"\nincludes=[]\n",
                ShapeException.Kind.PROFILE_MISSING_INCLUDES, "too-little");
        assertConfigError("version=0\n[[shards]]\nname=\"myshard\"\npaths=[\"secret\"]\nread-write-access=[\"ok\", \"not ok\"]\n",
                ShapeException.Kind.INVALID_READ_WRITE_ACCESS, "myshard/not ok");
        assertConfigError("version=0\n[[shards]]\nname=\"myshard\"\npaths=[\"secret\"]\n[[shards]]\nname=\"myshard2\"\npaths=[\"secret\"]\n",
                ShapeException.Kind.PATH_IN_MULTIPLE_SHARDS, "secret");
        // one trailing slash is stripped, a second one is an error (found when the shapes are generated)
        assertConfigError("version=0\n[[shards]]\nname=\"myshard\"\npaths=[\"badpath//\"]\n", ShapeException.Kind.INVALID_PATH, null);
        assertConfigError("version=0\n[[shards]]\nname=\"myshard\"\npaths=[\"badpath//bad\"]\n", ShapeException.Kind.INVALID_PATH, null);
        String profiles = "version=0\nshards=[]\n";
        // these go straight to the configuration code in Rust (no shard needed); the document needs one shard to load
        String withShard = "version=0\n[[shards]]\nname=\"s\"\npaths=[\"s\"]\n";
        assertConfigError(withShard + "[[profiles]]\nname=\"private\"\nincludes=[\"base\", \"unknown\"]\n",
                ShapeException.Kind.UNKNOWN_INCLUDE, "private/unknown");
        assertConfigError(withShard + "[[profiles]]\nname=\"recursive\"\nincludes=[\"recursive\"]\n",
                ShapeException.Kind.CYCLE_IN_SHARDS, "recursive->recursive");
        assertConfigError(withShard + "[[profiles]]\nname=\"cyclic1\"\nincludes=[\"cyclic2\"]\n[[profiles]]\nname=\"cyclic2\"\nincludes=[\"cyclic3\"]\n"
                        + "[[profiles]]\nname=\"cyclic3\"\nincludes=[\"cyclic4\"]\n[[profiles]]\nname=\"cyclic4\"\nincludes=[\"cyclic1\"]\n",
                ShapeException.Kind.CYCLE_IN_SHARDS, "cyclic1->cyclic2->cyclic3->cyclic4->cyclic1");
        assertConfigError(profiles + "[[profiles]]\nname=\"default\"\nincludes=[\"base\"]\npublic=true\n", ShapeException.Kind.NO_SHARDS, null);
    }

    @Test
    void errorMessagesAreTheOnesHgPrints() {
        String doc = "version=0\n[[shards]]\nname=\"myshard\"\npaths=[\"%s\"]\n";
        ShapeException leading = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(doc.formatted("/abs").getBytes()));
        assertEquals("`server-shapes` contains an invalid path: invalid HgPath '[47, 97, 98, 115]': has a leading slash.", leading.getMessage());
        ShapeException consecutive = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(doc.formatted("badpath//bad").getBytes()));
        assertEquals("`server-shapes` contains an invalid path: invalid HgPath '[98, 97, 100, 112, 97, 116, 104, 47, 47, 98, 97, 100]': "
                + "consecutive slashes at pos 8.", consecutive.getMessage());
        ShapeException ends = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(doc.formatted("badpath//").getBytes()));
        assertEquals("`server-shapes` contains an invalid path: path 'badpath/': ends with a slash", ends.getMessage());
        ShapeException reserved = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(doc.replace("myshard", "full").getBytes()));
        assertEquals("shard name 'full' is reserved", reserved.getMessage());
        ShapeException cycle = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(
                ("version=0\n[[shards]]\nname=\"s\"\npaths=[\"s\"]\n[[profiles]]\nname=\"r\"\nincludes=[\"r\"]\n").getBytes()));
        assertEquals("shards form a cycle: r->r", cycle.getMessage());
        ShapeException noShards = assertThrows(ShapeException.class, () -> StoreShards.fromBytes("version=0\nshards=[]\n".getBytes()));
        assertEquals("`server-shapes` must include at least one shard", noShards.getMessage());
        ShapeException version = assertThrows(ShapeException.class, () -> StoreShards.fromBytes("version = 7".getBytes()));
        assertEquals("unknown `server-shapes` version '7'", version.getMessage());
        ShapeException syntax = assertThrows(ShapeException.class, () -> StoreShards.fromBytes("version = 0\n[[shards]\n".getBytes()));
        assertEquals(ShapeException.Kind.PARSE_ERROR, syntax.kind());
        assertTrue(syntax.getMessage().startsWith("error parsing `server-shapes`:\n"), syntax.getMessage());
        ShapeException unknownField = assertThrows(ShapeException.class, () -> StoreShards.fromBytes(
                "version = 0\n[[shards]]\nname=\"a\"\npaths=[\"a\"]\nextra=1\n".getBytes()));
        assertEquals(ShapeException.Kind.PARSE_ERROR, unknownField.kind());
    }

    private static List<String> dependencies(StoreShards shards, String name) throws Exception {
        return new ArrayList<>(new TreeSet<>(shards.dependencies(shards.shards().get(name)).keySet()));
    }

    @Test
    void dependenciesAreRecursive() throws Exception {
        StoreShards shards = valid();
        assertEquals(List.of(".hg-files", "bar-files"), dependencies(shards, "bar"));
        assertEquals(List.of(".hg-files", "base", "baz", "foo-deps", "foo-files"), dependencies(shards, "foo"));
        assertEquals(List.of(".hg-files", "bar", "bar-files", "baron-parts", "baziku"), dependencies(shards, "baron"));
        List<String> full = new ArrayList<>(ALL_SHARD_NAMES);
        full.remove("full");
        assertEquals(full, dependencies(shards, "full"));
    }

    private static void assertPatterns(StoreShards shards, String name, List<String> includes, List<String> excludes) throws Exception {
        ShardTreeNode.Patterns patterns = shards.shape(List.of(name)).patterns();
        assertEquals(includes, new ArrayList<>(patterns.includes()), "wrong includes for shape " + name);
        assertEquals(excludes, new ArrayList<>(patterns.excludes()), "wrong excludes for shape " + name);
    }

    @Test
    void everyShapeHasTheExpectedPatterns() throws Exception {
        StoreShards s = valid();
        List<String> special = List.of(".hgignore", ".hgsub", ".hgsubstate", ".hgtags");
        assertPatterns(s, "full", List.of(""), List.of());
        assertPatterns(s, "bar", plus(special, "bar"), List.of("", "bar/baz"));
        assertPatterns(s, "baron", plus(special, "bar", "bar/baz/ik/u"), List.of("", "bar/baz"));
        assertPatterns(s, "bazik", plus(special, "bar", "bar/baz/ik"), List.of("", "bar/baz", "bar/baz/ik/u"));
        assertPatterns(s, "foo", List.of("", "bar/baz"), List.of("bar", "bar/baz/ik", "some/super/nested/file", "stone"));
        assertPatterns(s, "stone", plus(special, "stone"), List.of("", "stone/liz/ard", "stone/liz/zie", "stone/mummy"));
        assertPatterns(s, "borden", plus(special, "stone", "stone/liz/zie/bor/den"),
                List.of("", "stone/liz/ard", "stone/liz/zie", "stone/mummy"));
        assertPatterns(s, "mummy", plus(special, "stone/mummy"), List.of("", "stone/mummy/chamber"));
        assertPatterns(s, "chamber", plus(special, "stone", "stone/mummy/chamber"),
                List.of("", "stone/liz/ard", "stone/liz/zie", "stone/mummy", "stone/mummy/chamber/inner"));
        assertPatterns(s, "nested", plus(special, "some/super/nested/file"), List.of(""));
    }

    private static List<String> plus(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(more));
        return out;
    }

    private static final String[][] FILES_TO_SHAPES = {
            {".hgignore", "*"},
            {".hgsub", "*"},
            {".hgsubstate", "*"},
            {".hgtags", "*"},
            {"babar/v", "full", "foo"},
            {"bar/ba/v", "full", "bar", "baron", "bazik"},
            {"barbar/v", "full", "foo"},
            {"bar/baz/f", "full", "foo"},
            {"bar/baz/g", "full", "foo"},
            {"bar/baz/i", "full", "foo"},
            {"bar/baz/blu/toto", "full", "foo"},
            {"bar/bazar", "full", "bar", "baron", "bazik"},
            {"bar/baz/ik/U/j", "full", "bazik"},
            {"bar/baz/ik/U/k", "full", "bazik"},
            {"bar/baz/ik/U/m", "full", "bazik"},
            {"bar/baz/ik/h", "full", "bazik"},
            {"bar/baz/ik/i", "full", "bazik"},
            {"bar/baz/ik/o/l", "full", "bazik"},
            {"bar/baz/ik/u/j", "full", "baron"},
            {"bar/baz/ik/u/k", "full", "baron"},
            {"bar/baz/ik/u/m", "full", "baron"},
            {"bar/baz/ik/u/klm/foo", "full", "baron"},
            {"bar/baz/ik/ups", "full", "bazik"},
            {"bar/baz/ik/ups/klm/bar", "full", "bazik"},
            {"bar/d", "full", "bar", "baron", "bazik"},
            {"bar/e", "full", "bar", "baron", "bazik"},
            {"foo/a", "full", "foo"},
            {"foo/b", "full", "foo"},
            {"foo/c", "full", "foo"},
            {"foo/y", "full", "foo"},
            {"stone/foo", "full", "stone", "borden", "chamber"},
            {"stone/liz", "full", "stone", "borden", "chamber"},
            {"stone/mummy", "full", "mummy"},
            {"stone/mummy/return", "full", "mummy"},
            {"stone/mummy/chamber", "full", "chamber"},
            {"stone/mummy/chamber/inner", "full"},
            {"stone/mummy/chamber/babar", "full", "chamber"},
            {"stone/mummy/chamber/inner/babar", "full"},
            {"stone/liz/chamber/babar", "full", "stone", "borden", "chamber"},
            {"stone/liz/chamber", "full", "stone", "borden", "chamber"},
            {"stone/liz/chamber/inner", "full", "stone", "borden", "chamber"},
            {"stone/liz/chamber/inner/babar", "full", "stone", "borden", "chamber"},
            {"stone/liz/foo", "full", "stone", "borden", "chamber"},
            {"stone/liz/babar", "full", "stone", "borden", "chamber"},
            {"stone/liz/ard", "full"},
            {"stone/liz/ard/babar", "full"},
            {"stone/liz/on", "full", "stone", "borden", "chamber"},
            {"stone/liz/on/babar", "full", "stone", "borden", "chamber"},
            {"stone/liz/zie", "full"},
            {"stone/liz/zie/babar", "full"},
            {"stone/liz/zie/bor", "full"},
            {"stone/liz/zie/bor/den", "full", "borden"},
            {"stone/liz/zie/bor/den/babar", "full", "borden"},
            {"oops", "full", "foo"},
            {"some/super/other/nested", "full", "foo"},
            {"w", "full", "foo"},
            {"y", "full", "foo"},
            {"z", "full", "foo"}
    };

    @Test
    void everyShapeMatchesTheRightFiles() throws Exception {
        StoreShards shards = valid();
        for (String[] row : FILES_TO_SHAPES) {
            List<String> expected = new ArrayList<>(row[1].equals("*") ? ALL_SHAPE_NAMES : Arrays.asList(row).subList(1, row.length));
            expected.sort(String::compareTo);
            List<String> matching = new ArrayList<>();
            for (String name : ALL_SHAPE_NAMES) {
                if (shards.shape(List.of(name)).matcher().matches(row[0])) {
                    matching.add(name);
                }
            }
            assertEquals(expected, matching, "file '" + row[0] + "' doesn't match the expected shapes");
        }
    }

    @Test
    void deepestPrefixNodes() throws Exception {
        StoreShards s = valid();
        ShardTreeNode full = s.shape(List.of("full")).tree();
        for (String path : List.of("", "root", "bar/baz")) {
            assertEquals("", full.deepestPrefixNode(path).path());
            assertTrue(full.deepestPrefixNode(path).included());
        }
        ShardTreeNode bar = s.shape(List.of("bar")).tree();
        assertEquals("", bar.deepestPrefixNode("root").path());
        assertFalse(bar.deepestPrefixNode("root").included());
        assertEquals("bar", bar.deepestPrefixNode("bar/test").path());
        assertTrue(bar.deepestPrefixNode("bar/test").included());
        assertEquals("bar/baz", bar.deepestPrefixNode("bar/baz/nested/deeper").path());
        assertFalse(bar.deepestPrefixNode("bar/baz/nested/deeper").included());
        ShardTreeNode baron = s.shape(List.of("baron")).tree();
        assertEquals("bar", baron.deepestPrefixNode("bar/bazik").path());
        assertEquals("bar/baz/ik/u", baron.deepestPrefixNode("bar/baz/ik/u").path());
        assertTrue(baron.deepestPrefixNode("bar/baz/ik/u").included());
        assertEquals("bar/baz", baron.deepestPrefixNode("bar/baz/ik/ui").path());
        assertEquals("stone", s.shape(List.of("stone")).tree().deepestPrefixNode("stone/liz").path());
        assertEquals("stone/liz/zie", s.shape(List.of("borden")).tree().deepestPrefixNode("stone/liz/zie/bor").path());
        assertFalse(s.shape(List.of("borden")).tree().deepestPrefixNode("stone/liz/zie/bor").included());
        assertEquals("stone/mummy/chamber", s.shape(List.of("mummy")).tree().deepestPrefixNode("stone/mummy/chamber").path());
    }

    @Test
    void visitChildrenSetOfTheBarAndBazikShapes() throws Exception {
        StoreShards s = valid();
        ShapeMatcher bar = s.shape(List.of("bar")).matcher();
        ShapeMatcher.Visit root = bar.visitChildrenSet("");
        assertEquals(ShapeMatcher.Visit.Kind.SET, root.kind());
        assertEquals(Set.of("bar", ".hgignore", ".hgsubstate", ".hgtags", ".hgsub"), root.children());
        assertEquals(ShapeMatcher.Visit.Kind.RECURSIVE, bar.visitChildrenSet("bar").kind());
        assertEquals(ShapeMatcher.Visit.Kind.RECURSIVE, bar.visitChildrenSet("bar/file").kind());
        assertEquals(ShapeMatcher.Visit.Kind.EMPTY, bar.visitChildrenSet("other").kind());
    }

    @Test
    void canonicalNamesAndShapesFromNames() throws Exception {
        assertEquals(List.of("babar", "celeste"), ShapeNames.canonical(List.of("celeste", "babar", "celeste")));
        assertEquals(List.of(), ShapeNames.canonical(List.of()));
        assertEquals("Zephir", assertThrows(Exception.class, () -> ShapeNames.canonical(List.of("babar", "Zephir", ""))).getMessage()
                .replaceAll(".*'(Zephir)'.*", "$1"));
        assertEquals("shard names must not be empty",
                assertThrows(Exception.class, () -> ShapeNames.canonical(List.of("babar", "", "Zephir"))).getMessage());

        StoreShards s = valid();
        assertEquals(ShapeException.Kind.UNKNOWN_PROFILE, assertThrows(ShapeException.class, () -> s.shape(List.of("zz"))).kind());
        assertEquals("baz", assertThrows(ShapeException.class, () -> s.shape(List.of("baz"))).subject()); // a shard is not a shape
        Shape both = s.shape(List.of("mummy", "bar"));
        Shape bar = s.shape(List.of("bar"));
        Shape mummy = s.shape(List.of("mummy"));
        Shape same = s.shape(List.of("bar", "mummy", "bar"));
        assertEquals(both.names(), same.names());
        assertEquals(both.storeFingerprint(), same.storeFingerprint());
        for (String file : List.of("bar/file", "stone/mummy/file")) {
            assertTrue(both.matcher().matches(file), file);
            assertTrue(bar.matcher().matches(file) || mummy.matcher().matches(file), file);
        }
        for (String file : List.of("foo/file", "bar/baz/file", "stone/mummy/chamber/file")) {
            assertFalse(both.matcher().matches(file), file);
        }
        assertNotEquals(both.storeFingerprint(), bar.storeFingerprint());
        assertNotEquals(both.storeFingerprint(), mummy.storeFingerprint());
        assertEquals("zz", assertThrows(ShapeException.class, () -> s.shape(List.of("bar", "zz"))).subject());
        assertEquals("baz", assertThrows(ShapeException.class, () -> s.shape(List.of("bar", "baz"))).subject());
        assertEquals(ShapeException.Kind.NO_PROFILE_NAMES, assertThrows(ShapeException.class, () -> s.shape(List.of())).kind());
    }

    @Test
    void shardFingerprintsForShapes() throws Exception {
        StoreShards s = valid();
        List<Set<Fingerprint>> baron = s.shardFingerprintsForShape(s.shape(List.of("baron")));
        List<Set<Fingerprint>> mummy = s.shardFingerprintsForShape(s.shape(List.of("mummy")));
        List<Set<Fingerprint>> both = s.shardFingerprintsForShape(s.shape(List.of("baron", "mummy")));
        assertEquals(1, both.size());
        Set<Fingerprint> expected = new LinkedHashSet<>(baron.get(0));
        expected.addAll(mummy.get(0));
        assertEquals(expected, both.get(0));
        assertTrue(both.get(0).size() > baron.get(0).size());
        Shape outsider = Shape.fromPatterns(Set.of(), Set.of());
        ShapeException e = assertThrows(ShapeException.class, () -> s.shardFingerprintsForShape(outsider));
        assertEquals(ShapeException.Kind.UNKNOWN_PROFILE, e.kind());
        assertEquals("hg-internal", e.subject());
        for (Fingerprint fingerprint : both.get(0)) {
            assertEquals(Fingerprint.Kind.SHARD, fingerprint.kind());
        }
    }

    private static final String SIMPLE = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n[[profiles]]\nname = \"default\"\nincludes = [\"base\"]\npublic = true\n";

    private interface Change {
        List<String> of(StoreShards from, StoreShards to) throws Exception;
    }

    private static List<String> changed(Change f, String old, String updated) throws Exception {
        StoreShards a = StoreShards.fromBytes(old.getBytes(StandardCharsets.UTF_8));
        StoreShards b = StoreShards.fromBytes(updated.getBytes(StandardCharsets.UTF_8));
        List<String> forwards = f.of(a, b);
        assertEquals(forwards, f.of(b, a), "the same in both directions");
        return forwards;
    }

    private static List<String> changedShapes(String old, String updated) throws Exception {
        return changed(StoreShards::changedShapes, old, updated);
    }

    private static List<String> changedShards(String old, String updated) throws Exception {
        return changed(StoreShards::changedShards, old, updated);
    }

    @Test
    void unchangedConfigs() throws Exception {
        assertEquals(List.of(), changedShapes(SIMPLE, SIMPLE));
        assertEquals(List.of(), changedShards(SIMPLE, SIMPLE));
        String reformatted = "version = 0\n# this is the default shape\n[[profiles]]\nname = \"default\"\nincludes = [\"base\"]\npublic = true\n"
                + "[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n";
        assertEquals(List.of(), changedShapes(SIMPLE, reformatted));
        assertEquals(List.of(), changedShards(SIMPLE, reformatted));
        String a = "version = 0\n[[shards]]\nname = \"foo-files\"\npaths = [\"foo\", \"bar\"]\n[[profiles]]\nname = \"foo\"\nincludes = [\"foo-files\"]\npublic = true\n";
        String b = a.replace("[\"foo\", \"bar\"]", "[\"bar\", \"foo\"]");
        assertEquals(List.of(), changedShapes(a, b));
        assertEquals(List.of(), changedShards(a, b));
    }

    @Test
    void addingAShape() throws Exception {
        String shape = SIMPLE + "[[profiles]]\nname = \"everything\"\nincludes = [\"foo\", \"default\"]\npublic = true\n";
        assertEquals(List.of("everything"), changedShapes(SIMPLE, shape));
        assertEquals(List.of(), changedShards(SIMPLE, shape));
        String hidden = SIMPLE + "[[profiles]]\nname = \"everything\"\nincludes = [\"foo\", \"default\"]\n";
        assertEquals(List.of(), changedShapes(SIMPLE, hidden));
        assertEquals(List.of(), changedShards(SIMPLE, hidden));
    }

    @Test
    void addingAndRemovingAShard() throws Exception {
        String added = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar\"\npaths = [\"bar\"]\n"
                + "[[profiles]]\nname = \"default\"\nincludes = [\"base\"]\npublic = true\n";
        assertEquals(List.of("default"), changedShapes(SIMPLE, added));
        assertEquals(List.of("bar", "base"), changedShards(SIMPLE, added)); // base owned the files under bar until now
        String adjusted = added.replace("includes = [\"base\"]", "includes = [\"base\", \"bar\"]");
        assertEquals(List.of(), changedShapes(SIMPLE, adjusted));
        assertEquals(List.of("bar", "base"), changedShards(SIMPLE, adjusted));

        String removed = "version = 0\n[[shards]]\nname = \"bar\"\npaths = [\"bar\"]\n[[profiles]]\nname = \"default\"\nincludes = [\"base\"]\npublic = true\n";
        assertEquals(List.of("default"), changedShapes(added, removed));
        assertEquals(List.of("base", "foo"), changedShards(added, removed));
        String removeShape = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar\"\npaths = [\"bar\"]\n";
        assertEquals(List.of("default"), changedShapes(added, removeShape));
        assertEquals(List.of(), changedShards(added, removeShape));
        String removeShard = "version = 0\n[[shards]]\nname = \"bar\"\npaths = [\"bar\"]\n";
        assertEquals(List.of(), changedShapes(removeShape, removeShard));
        assertEquals(List.of("base", "foo"), changedShards(removeShape, removeShard));
    }

    @Test
    void changingAShardAndRenamingOne() throws Exception {
        String old = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\", \"bar\"]\n[[shards]]\nname = \"baz\"\npaths = [\"baz\"]\n";
        String updated = old.replace("[\"foo\", \"bar\"]", "[\"foo\"]");
        assertEquals(List.of("base", "foo"), changedShards(old, updated));
        String foo = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n";
        assertEquals(List.of(), changedShards(foo, foo.replace("name = \"foo\"", "name = \"renamed\"")), "a pure rename is not a reshard");
    }

    @Test
    void readWriteAccessIsSortedAndDoesNotChangeTheShard() throws Exception {
        String old = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\nread-write-access = [\"release.managers\", \"foo-team\"]\n";
        StoreShards s = StoreShards.fromBytes(old.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("foo-team", "release.managers"), s.shards().get("foo").readWriteAccess());
        String updated = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n";
        assertEquals(List.of(), changedShards(old, updated));
        assertEquals(List.of(), changedShapes(old, updated));
    }

    @Test
    void listShardsAndBundleInfo() throws Exception {
        StoreShards s = valid();
        List<StoreShards.ShardListing> listed = s.listShards();
        for (int i = 1; i < listed.size(); i++) {
            assertTrue(java.util.Arrays.compareUnsigned(listed.get(i - 1).shardId().digest(), listed.get(i).shardId().digest()) <= 0, "sorted by digest");
        }
        assertEquals(15, listed.size(), "the shards that have paths: profiles and full have none");
        List<StoreShards.ShardShape> info = s.shardedBundleInfo();
        assertEquals(15, info.size());
        List<String> names = new ArrayList<>();
        for (StoreShards.ShardShape shape : info) {
            names.add(shape.name());
        }
        assertEquals(names.stream().sorted().toList(), names, "in name order");
        assertEquals(List.of(".hg-files"), info.stream().filter(StoreShards.ShardShape::topLevel).map(StoreShards.ShardShape::name).toList());
        // the standalone .hg-files shard matches the four special files and nothing else
        StoreShards.ShardShape hgFiles = info.get(0);
        assertEquals(".hg-files", hgFiles.name());
        assertTrue(hgFiles.matcher().matches(".hgtags"));
        assertFalse(hgFiles.matcher().matches("foo/a"));
        // a nested shard carves its paths out of the outer one
        StoreShards.ShardShape stoneFiles = info.stream().filter(i -> i.name().equals("stone-files")).findFirst().orElseThrow();
        assertTrue(stoneFiles.matcher().matches("stone/other"));
        assertFalse(stoneFiles.matcher().matches("stone/mummy/x"));
        assertEquals(Fingerprint.Kind.SHARD, stoneFiles.fingerprint().kind());
    }

    @Test
    void aRepositoryWithoutAnyConfigHasJustTheImplicitShards() throws Exception {
        StoreShards s = StoreShards.fromBytes("version = 0\n[[shards]]\nname = \"a\"\npaths = [\"a\"]\n".getBytes());
        assertEquals(List.of(".hg-files", "a", "base", "full"), new ArrayList<>(s.shards().keySet()));
    }
}
