package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shards and profiles a repository declares in {@code .hg/store/server-shapes} ({@code narrow/shape.rs StoreShards}); see
 * {@code llm-wiki/concepts/store-shapes.md} for the rules this class follows. Shard names are sorted by plain string order wherever the
 * original sorts them, so results do not depend on hash order.
 */
public final class StoreShards {

    /** Store file in which a repository declares its shards and shapes. */
    public static final String SHAPES_FILE = "server-shapes";
    static final String HG_FILES_SHARD = ".hg-files";
    private static final List<String> HG_FILES_SHARD_PATHS = List.of(".hgignore", ".hgtags", ".hgsub", ".hgsubstate");
    private static final Pattern ONLY_VERSION = Pattern.compile("\\Aversion\\s*=\\s*(\\d+)\\z");

    /** A validated shard: its paths, what it requires (sorted, always with {@code .hg-files}), and whether it is a shape. */
    public record Shard(String name, List<String> paths, List<String> requires, boolean shape, List<String> readWriteAccess) {
    }

    /** The standalone view of a shard: its own paths only, with its fingerprint and matcher. */
    public static final class ShardShape {
        private final Shape fake;
        private final boolean topLevel;

        ShardShape(Shape fake, boolean topLevel) {
            this.fake = fake;
            this.topLevel = topLevel;
        }

        /** True for the shard bundled with the top level files (changelog, manifest, caches). */
        public boolean topLevel() {
            return topLevel;
        }

        /**
         * Creates a matcher over the shard's own paths.
         *
         * @return a new matcher
         */
        public ShapeMatcher matcher() {
            return fake.matcher();
        }

        /** The shape fingerprint of the shard's paths, as a shard fingerprint. */
        public Fingerprint fingerprint() throws HgValidationException {
            return fake.storeFingerprint().asShard();
        }

        /**
         * Returns the shard's name.
         *
         * @return the shard name
         */
        public String name() {
            return fake.names().get(0);
        }
    }

    /** One line of {@link #listShards()}. */
    public record ShardListing(Fingerprint shardId, List<String> readWriteAccess) {
    }

    private final Map<String, Shard> shards;
    private final Map<String, Shard> pathToShard;

    private StoreShards(Map<String, Shard> shards, Map<String, Shard> pathToShard) {
        this.shards = shards;
        this.pathToShard = pathToShard;
    }

    // ---------------------------------------------------------------- loading

    /** The shards of a repository: its {@code server-shapes} file, or just the implicit shards when it has none. */
    public static StoreShards fromRepository(HgRepository repo) throws IOException {
        File file = new File(repo.getStoreDir(), SHAPES_FILE);
        if (!file.isFile()) {
            return fromConfig(new Config(0, List.of(), List.of()));
        }
        return fromBytes(Files.readAllBytes(file.toPath()));
    }

    /** Parses and validates a {@code server-shapes} document. */
    public static StoreShards fromBytes(byte[] data) throws HgValidationException {
        Config config;
        try {
            config = Config.parse(new String(data, StandardCharsets.UTF_8));
        } catch (MiniToml.TomlError | Config.FieldError e) {
            // a document that only fails because of an unknown version is reported as such
            Matcher m = ONLY_VERSION.matcher(new String(data, StandardCharsets.UTF_8));
            if (m.find()) {
                throw ShapeException.unknownVersion(Long.parseLong(m.group(1)));
            }
            throw ShapeException.parse(e.getMessage());
        }
        // a config without shards makes no sense, but having no config at all (fromRepository) is fine
        if (config.shards().isEmpty()) {
            throw ShapeException.noShards();
        }
        return fromConfig(config);
    }

    /** One {@code [[shards]]} entry. */
    record ShardsEntry(String name, List<String> paths, List<String> readWriteAccess) {
    }

    /** One {@code [[profiles]]} entry. */
    record ProfilesEntry(String name, List<String> includes, boolean publicProfile) {
    }

    /** The {@code server-shapes} document. */
    record Config(long version, List<ShardsEntry> shards, List<ProfilesEntry> profiles) {

        static final class FieldError extends Exception {
            FieldError(String message) {
                super(message);
            }
        }

        static Config parse(String text) throws MiniToml.TomlError, FieldError {
            Map<String, Object> root = MiniToml.parse(text);
            if (!(root.get("version") instanceof Long version)) {
                throw new FieldError(root.containsKey("version") ? "invalid type for `version`, expected an integer" : "missing field `version`");
            }
            if (version < 0 || version > 0xFFFFFFFFL) {
                throw new FieldError("invalid value for `version`: out of range for u32");
            }
            List<ShardsEntry> shards = new ArrayList<>();
            if (!(root.get("shards") instanceof List<?> shardTables)) {
                throw new FieldError(root.containsKey("shards") ? "invalid type for `shards`, expected a sequence" : "missing field `shards`");
            }
            for (Object table : shardTables) {
                Map<?, ?> t = asTable(table, "shards");
                checkKeys(t, Set.of("name", "paths", "read-write-access"), "shards");
                shards.add(new ShardsEntry(string(t, "name", "shards"), strings(t, "paths", "shards", true), strings(t, "read-write-access", "shards", false)));
            }
            List<ProfilesEntry> profiles = new ArrayList<>();
            if (root.containsKey("profiles")) {
                if (!(root.get("profiles") instanceof List<?> profileTables)) {
                    throw new FieldError("invalid type for `profiles`, expected a sequence");
                }
                for (Object table : profileTables) {
                    Map<?, ?> t = asTable(table, "profiles");
                    checkKeys(t, Set.of("name", "includes", "public"), "profiles");
                    boolean isPublic = false;
                    if (t.containsKey("public")) {
                        if (!(t.get("public") instanceof Boolean b)) {
                            throw new FieldError("invalid type for `public`, expected a boolean");
                        }
                        isPublic = b;
                    }
                    profiles.add(new ProfilesEntry(string(t, "name", "profiles"), strings(t, "includes", "profiles", true), isPublic));
                }
            }
            return new Config(version, shards, profiles);
        }

        private static Map<?, ?> asTable(Object value, String where) throws FieldError {
            if (value instanceof Map<?, ?> m) {
                return m;
            }
            throw new FieldError("invalid type in `" + where + "`, expected a table");
        }

        private static void checkKeys(Map<?, ?> table, Set<String> allowed, String where) throws FieldError {
            for (Object key : table.keySet()) {
                if (!allowed.contains(key)) {
                    throw new FieldError("unknown field `" + key + "` in `" + where + "`, expected one of " + new TreeSet<>(allowed));
                }
            }
        }

        private static String string(Map<?, ?> table, String key, String where) throws FieldError {
            Object value = table.get(key);
            if (value == null) {
                throw new FieldError("missing field `" + key + "` in `" + where + "`");
            }
            if (!(value instanceof String s)) {
                throw new FieldError("invalid type for `" + key + "`, expected a string");
            }
            return s;
        }

        private static List<String> strings(Map<?, ?> table, String key, String where, boolean required) throws FieldError {
            Object value = table.get(key);
            if (value == null) {
                if (required) {
                    throw new FieldError("missing field `" + key + "` in `" + where + "`");
                }
                return null;
            }
            if (!(value instanceof List<?> list)) {
                throw new FieldError("invalid type for `" + key + "`, expected a sequence");
            }
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new FieldError("invalid type in `" + key + "`, expected a string");
                }
                out.add(s);
            }
            return out;
        }
    }

    /** An internal shard config before validation. */
    private record ShardConfig(String name, boolean shape, List<String> paths, List<String> requires, List<String> readWriteAccess) {
    }

    /** {@code StoreShards::from_config}. */
    static StoreShards fromConfig(Config config) throws HgValidationException {
        if (config.version() != 0) {
            throw ShapeException.unknownVersion(config.version());
        }
        List<ShardConfig> configs = new ArrayList<>();
        for (ShardsEntry entry : config.shards()) {
            if (entry.paths().isEmpty()) {
                throw ShapeException.shardMissingPaths(entry.name());
            }
            configs.add(new ShardConfig(entry.name(), false, entry.paths(), null, entry.readWriteAccess()));
        }
        for (ProfilesEntry entry : config.profiles()) {
            if (entry.includes().isEmpty()) {
                throw ShapeException.profileMissingIncludes(entry.name());
            }
            configs.add(new ShardConfig(entry.name(), entry.publicProfile(), null, entry.includes(), null));
        }
        int userProvided = configs.size();
        configs.add(new ShardConfig("base", false, List.of(""), null, null));
        List<String> allNames = new ArrayList<>();
        for (ShardConfig c : configs) {
            allNames.add(c.name());
        }
        allNames.sort(Comparator.naturalOrder());
        configs.add(new ShardConfig("full", true, null, allNames, null));
        // special files every shard includes: having a shape without them is an anti-feature
        configs.add(new ShardConfig(HG_FILES_SHARD, false, HG_FILES_SHARD_PATHS, null, null));

        Map<String, Shard> pathToShard = new LinkedHashMap<>();
        Map<String, Shard> validated = new LinkedHashMap<>();
        for (int i = 0; i < configs.size(); i++) {
            validateShard(configs.get(i), i < userProvided, pathToShard, validated);
        }
        // a second pass over the shards in name order: every requirement has to exist
        for (Shard shard : new TreeMap<>(validated).values()) {
            for (String include : shard.requires()) {
                if (!validated.containsKey(include)) {
                    throw ShapeException.unknownInclude(shard.name(), include);
                }
            }
        }
        StoreShards result = new StoreShards(validated, pathToShard);
        result.allShapes(); // generating every shape catches the remaining errors (cycles, bad paths)
        return result;
    }

    /** {@code HgPath::check_state} as {@code errors.rs} words it. @return the detail, or null for a valid path */
    private static String checkState(String path) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.startsWith("/")) {
            return "invalid HgPath '" + ShardTreeNode.byteList(path) + "': has a leading slash.";
        }
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return "invalid HgPath '" + ShardTreeNode.byteList(path) + "': contains null byte at pos " + i + ".";
            }
            if (bytes[i] == '/' && i > 0 && bytes[i - 1] == '/') {
                return "invalid HgPath '" + ShardTreeNode.byteList(path) + "': consecutive slashes at pos " + i + ".";
            }
        }
        return null;
    }

    private static void validateName(String name) throws ShapeException {
        if (name.isEmpty()) {
            throw ShapeException.emptyName();
        }
        if (name.chars().allMatch(c -> c == '.' || c == '-')) {
            throw ShapeException.dotOrHyphenOnly(name);
        }
        if (!name.matches("[-.a-z0-9]+")) {
            throw ShapeException.invalidName(name);
        }
    }

    private static void validateShard(ShardConfig config, boolean userProvided, Map<String, Shard> pathToShard, Map<String, Shard> shards)
            throws ShapeException {
        String name = config.name();
        if (userProvided && (name.equals("base") || name.equals("full") || name.equals(HG_FILES_SHARD))) {
            throw ShapeException.reserved(name);
        }
        List<String> hgPaths = new ArrayList<>();
        if (config.paths() != null) {
            for (String path : config.paths()) {
                String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                String problem = checkState(stripped);
                if (problem != null) {
                    throw ShapeException.invalidPath(stripped, problem);
                }
                hgPaths.add(stripped);
            }
        }
        List<String> requires = new ArrayList<>();
        if (!name.equals(HG_FILES_SHARD)) {
            requires.add(HG_FILES_SHARD);
        }
        if (config.requires() != null) {
            for (String requirement : config.requires()) {
                validateName(requirement);
                requires.add(requirement);
            }
        }
        requires.sort(Comparator.naturalOrder());
        validateName(name);
        if (config.readWriteAccess() != null) {
            for (String value : config.readWriteAccess()) {
                try {
                    validateName(value);
                } catch (ShapeException invalid) {
                    throw ShapeException.invalidReadWriteAccess(name, value);
                }
            }
        }
        List<String> access = null;
        if (config.readWriteAccess() != null) {
            access = new ArrayList<>(config.readWriteAccess());
            access.sort(Comparator.naturalOrder());
        }
        Shard shard = new Shard(name, List.copyOf(hgPaths), List.copyOf(requires), config.shape(), access == null ? null : List.copyOf(access));
        for (String path : hgPaths) {
            if (pathToShard.put(path, shard) != null) {
                throw ShapeException.pathInMultipleShards(path);
            }
        }
        if (shards.put(name, shard) != null) {
            throw ShapeException.duplicate(name);
        }
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Returns the validated shards.
     *
     * @return an unmodifiable snapshot of the shards by name, sorted by name
     */
    public Map<String, Shard> shards() {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(shards));
    }

    /** Every user facing shard as a shape, by name. */
    public List<Shape> allShapes() throws HgValidationException {
        List<Shape> out = new ArrayList<>();
        for (Shard shard : new TreeMap<>(shards).values()) {
            if (shard.shape()) {
                out.add(newShape(List.of(shard.name()), List.of(shard)));
            }
        }
        return out;
    }

    /**
     * The shape combining these names: it covers every file that any of them covers. Order and duplicates do not matter; a name that does
     * not exist or is a shard (not a shape) is an error.
     */
    public Shape shape(List<String> names) throws HgValidationException {
        if (names.isEmpty()) {
            throw ShapeException.noProfileNames();
        }
        List<String> shardNames = ShapeNames.canonical(names);
        List<Shard> chosen = new ArrayList<>();
        for (String name : shardNames) {
            Shard shard = shards.get(name);
            if (shard == null || !shard.shape()) {
                throw ShapeException.unknownProfile(name);
            }
            chosen.add(shard);
        }
        return newShape(shardNames, chosen);
    }

    private Shape newShape(List<String> names, List<Shard> chosen) throws HgValidationException {
        List<Shard> expanded = new ArrayList<>();
        for (Shard shard : chosen) {
            expanded.add(shard);
            expanded.addAll(dependencies(shard).values());
        }
        return new Shape(names, treeFromShards(expanded));
    }

    private ShardTreeNode treeFromShards(List<Shard> chosen) throws HgValidationException {
        Set<String> shardPaths = new LinkedHashSet<>();
        for (Shard shard : chosen) {
            shardPaths.addAll(shard.paths());
            for (Shard dependency : dependencies(shard).values()) {
                shardPaths.addAll(dependency.paths());
            }
        }
        if (shardPaths.isEmpty()) {
            throw new IllegalStateException("a shape without any path");
        }
        try {
            return ShardTreeNode.fromPaths(pathToShard.keySet(), shardPaths);
        } catch (HgValidationException e) {
            throw ShapeException.invalidPath("", e.getMessage());
        }
    }

    /** All the shards {@code shard} requires, recursively. A requirement that leads back to a shard being visited is a cycle. */
    Map<String, Shard> dependencies(Shard shard) throws ShapeException {
        Map<String, Shard> acc = new LinkedHashMap<>();
        dependenciesFrom(shard, new ArrayList<>(), acc);
        return acc;
    }

    private void dependenciesFrom(Shard shard, List<String> from, Map<String, Shard> acc) throws ShapeException {
        for (String name : shard.requires()) {
            if (from.contains(name)) {
                List<String> cycle = new ArrayList<>(from);
                cycle.add(name);
                throw ShapeException.cycle(cycle);
            }
            Shard sub = shards.get(name);
            acc.put(name, sub);
            List<String> path = new ArrayList<>(from);
            path.add(name);
            dependenciesFrom(sub, path, acc);
        }
    }

    // ---------------------------------------------------------------- standalone shards

    /** The standalone shard, or null when it has no paths (a profile). */
    public ShardShape shardShape(Shard shard) throws HgValidationException {
        if (shard.paths().isEmpty()) {
            return null;
        }
        ShardTreeNode tree;
        try {
            tree = ShardTreeNode.fromPaths(pathToShard.keySet(), new HashSet<>(shard.paths()));
        } catch (HgValidationException e) {
            throw ShapeException.invalidPath("", e.getMessage());
        }
        return new ShardShape(new Shape(List.of(shard.name()), tree), shard.name().equals(HG_FILES_SHARD));
    }

    /** The standalone fingerprint of every shard that has paths, with its {@code read-write-access}, sorted by the digest. */
    public List<ShardListing> listShards() throws HgValidationException {
        List<ShardListing> out = new ArrayList<>();
        for (Shard shard : shards.values()) {
            ShardShape standalone = shardShape(shard);
            if (standalone != null) {
                out.add(new ShardListing(standalone.fingerprint(), shard.readWriteAccess()));
            }
        }
        out.sort((a, b) -> Arrays.compareUnsigned(a.shardId().digest(), b.shardId().digest()));
        return out;
    }

    /** Enough information per shard (in name order, those with paths) to generate sharded bundles. */
    public List<ShardShape> shardedBundleInfo() throws HgValidationException {
        List<ShardShape> out = new ArrayList<>();
        for (Shard shard : new TreeMap<>(shards).values()) {
            ShardShape standalone = shardShape(shard);
            if (standalone != null) {
                out.add(standalone);
            }
        }
        return out;
    }

    /**
     * The fingerprints of every standalone shard of {@code shape}, to match against sharded bundles. A single set for now; the list leaves room
     * for several states of a shape.
     */
    public List<Set<Fingerprint>> shardFingerprintsForShape(Shape shape) throws HgValidationException {
        Map<String, Shard> dependent = new LinkedHashMap<>();
        for (String name : shape.names()) {
            Shard shard = shards.get(name);
            if (shard == null) {
                throw ShapeException.unknownProfile(name);
            }
            dependent.putAll(dependencies(shard));
            dependent.put(name, shard);
        }
        Set<Fingerprint> fingerprints = new LinkedHashSet<>();
        for (Shard shard : dependent.values()) {
            ShardShape standalone = shardShape(shard);
            if (standalone != null) {
                fingerprints.add(standalone.fingerprint());
            }
        }
        return List.of(fingerprints);
    }

    // ---------------------------------------------------------------- comparing two configs

    private Map<String, Fingerprint> shapeFingerprints() throws HgValidationException {
        Map<String, Fingerprint> out = new HashMap<>();
        for (Shape shape : allShapes()) {
            Fingerprint fingerprint = shape.storeFingerprint();
            for (String name : shape.names()) {
                out.put(name, fingerprint);
            }
        }
        return out;
    }

    private static List<String> changedNames(Map<String, Fingerprint> old, Map<String, Fingerprint> updated) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, Fingerprint> e : old.entrySet()) {
            if (!e.getValue().equals(updated.get(e.getKey()))) {
                names.add(e.getKey());
            }
        }
        for (Map.Entry<String, Fingerprint> e : updated.entrySet()) {
            if (!e.getValue().equals(old.get(e.getKey()))) {
                names.add(e.getKey());
            }
        }
        return new ArrayList<>(names);
    }

    /** The shapes added, removed, or changed (by fingerprint) between this and {@code updated}. */
    public List<String> changedShapes(StoreShards updated) throws HgValidationException {
        return changedNames(shapeFingerprints(), updated.shapeFingerprints());
    }

    /** The shapes that exist in both and whose fingerprint changed. */
    public List<String> redefinedShapes(StoreShards updated) throws HgValidationException {
        Map<String, Fingerprint> old = shapeFingerprints();
        Map<String, Fingerprint> next = updated.shapeFingerprints();
        List<String> out = new ArrayList<>();
        for (String name : changedNames(old, next)) {
            if (old.containsKey(name) && next.containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private Map<Fingerprint, String> shardFingerprints() throws HgValidationException {
        Map<Fingerprint, String> out = new HashMap<>();
        for (Shard shard : new TreeMap<>(shards).values()) {
            ShardShape standalone = shardShape(shard);
            if (standalone != null) {
                out.put(standalone.fingerprint(), shard.name());
            }
        }
        return out;
    }

    /**
     * The shards whose standalone fingerprint appears or disappears between this and {@code updated}. A pure rename does not show up:
     * non-shape shard names need not be stable; the names are only there for the message.
     */
    public List<String> changedShards(StoreShards updated) throws HgValidationException {
        Map<Fingerprint, String> old = shardFingerprints();
        Map<Fingerprint, String> next = updated.shardFingerprints();
        Set<String> names = new TreeSet<>();
        for (Map.Entry<Fingerprint, String> e : old.entrySet()) {
            if (!next.containsKey(e.getKey())) {
                names.add(e.getValue());
            }
        }
        for (Map.Entry<Fingerprint, String> e : next.entrySet()) {
            if (!old.containsKey(e.getKey())) {
                names.add(e.getValue());
            }
        }
        return new ArrayList<>(names);
    }
}
