package io.github.search5.hg4j.bundle;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which entries of a clonebundles manifest a client uses ({@code mercurial/bundlecaches.py}: {@code parse_clonebundle_manifest_line},
 * {@code filterclonebundleentries}, {@code sortclonebundleentries}, {@code best_clonebundles}), including the shape rules: a clone with
 * a store shape takes the bundles of that shape, or, from a group of sharded bundles, one bundle per shard of a set of shards it needs,
 * the one with the top level files first.
 */
public final class ClonebundleSelection {

    /**
     * Name of the bundlespec parameter naming the shard a bundle holds.
     */
    public static final String SHARD_ID = "shard-id";
    /**
     * Name of the bundlespec parameter holding the fingerprint of the store shape of a bundle.
     */
    public static final String STORE_FINGERPRINT = "store-fingerprint";
    /**
     * Name of the bundlespec parameter identifying the group of sharded bundles a bundle belongs to.
     */
    public static final String BUNDLE_GROUP_ID = "bundle-group-id";
    /**
     * Name of the bundlespec parameter marking the bundle of a group that holds the top level files.
     */
    public static final String BUNDLE_GROUP_TOP_LEVEL = "bundle-group-top-level";
    /**
     * Name of the bundlespec parameter listing the delta compression methods of a bundle.
     */
    public static final String DELTA_COMPRESSION = "delta-compression";

    private static final List<String> SCHEMES = List.of("http://", "https://", "largefile://", "peer-bundle-cache://");
    private static final Set<String> COMPRESSIONS = Set.of("none", "gzip", "bzip2", "zstd");
    /** `bundlecaches._bundlespeccontentopts` keys. */
    private static final Set<String> VERSIONS = Set.of("v1", "v2", "v3", "streamv2", "streamv3-exp", "packed1", "bundle2");
    private static final Set<String> V1_COMPRESSIONS = Set.of("gzip", "bzip2", "none");
    private static final Set<String> BOOLEAN_PARAMS = Set.of("obsolescence", "obsolescence-mandatory", "phases", "changegroup",
            "tagsfnodescache", "revbranchcache");
    private static final Set<String> MANDATORY_PARAMS = Set.of("requirements", "store-fingerprint", "stream", "shard-id",
            "bundle-group-id", "bundle-group-top-level");
    /** `KNOWN_BUNDLE_SPEC_PARAMS`: what a mandatory (upper-case) parameter may be named. */
    private static final Set<String> KNOWN_PARAMS = new java.util.HashSet<>(java.util.stream.Stream.of(
            BOOLEAN_PARAMS, MANDATORY_PARAMS, Set.of("cg.version", "shape", "cg.delta-compression"))
            .flatMap(Set::stream).toList());
    private static final Set<String> STREAM_IGNORABLE_REQUIREMENTS = Set.of("store", "fncache", "dotencode", "share-safe",
            "shared", "relshared", "exp-sharesafe", "bookmarksinstore", "dirstate-v2", "dirstate-tracked-hint-v1");
    private static final Set<String> SUPPORTED_REQUIREMENTS = Set.of("revlogv1", "generaldelta", "sparserevlog",
            "revlog-compression-zstd", "treemanifest", "persistent-nodemap", "exp-revlogv2.2", "exp-changelog-v2",
            "plain-encoding", "narrowheads", "exp-copies-sidedata-changeset", "fileindex-v1",
            "store", "fncache", "dotencode", "share-safe", "dirstate-v2");
    private static final Set<String> SUPPORTED_DELTA_COMPRESSION = Set.of("none", "zlib", "zstd");
    private static final Pattern DIGEST = Pattern.compile("^[a-z0-9]+:[0-9a-f]+(,[a-z0-9]+:[0-9a-f]+)*$");
    private static final Map<String, Integer> DIGEST_SIZES = Map.of("sha256", 64, "sha512", 128);

    private ClonebundleSelection() {
    }

    /** A parsed manifest line: its attributes and, when there is a {@code BUNDLESPEC}, the parameters of that. */
    public static final class Entry {
        private final Map<String, String> attrs;
        private final List<String> deltaCompression;
        private final Map<String, String> specParams;
        private final boolean hasSpec;
        private final boolean stream;

        Entry(Map<String, String> attrs, List<String> deltaCompression, Map<String, String> specParams, boolean hasSpec, boolean stream) {
            this.attrs = attrs;
            this.deltaCompression = deltaCompression;
            this.specParams = specParams;
            this.hasSpec = hasSpec;
            this.stream = stream;
        }

        /**
         * Returns the bundle URL.
         * @return the value of the {@code URL} attribute
         */
        public String url() {
            return attrs.get("URL");
        }

        /**
         * Returns the manifest attributes of the line, including {@code URL}. The map is returned as stored, not copied.
         */
        public Map<String, String> attrs() {
            return attrs;
        }

        /** The value of a bundlespec parameter; {@code null} when there is no bundlespec, no such parameter, or an empty value. */
        public String specParam(String name) {
            String v = specParams.get(name);
            return v == null || v.isEmpty() ? null : v;
        }

        /**
         * Returns whether the entry's bundlespec describes a stream clone bundle.
         */
        public boolean isStreamClone() {
            return stream;
        }
    }

    /** {@code stringutil.parsebool}. */
    private static Boolean parseBool(String s) {
        switch (s.toLowerCase()) {
            case "1": case "yes": case "true": case "on": case "always":
                return Boolean.TRUE;
            case "0": case "no": case "false": case "off": case "never":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private static String unquote(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * One line of the manifest: {@code <URL> key=value ...}. {@code null} for a blank line and for a line whose {@code BUNDLESPEC} this
     * client cannot read (the reason goes to {@code debug}).
     */
    public static Entry parseLine(String line, List<String> debug) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length == 0 || fields[0].isEmpty()) {
            return null;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("URL", fields[0]);
        List<String> deltaCompression = null;
        Map<String, String> params = new HashMap<>();
        boolean hasSpec = false;
        boolean stream = false;
        for (int i = 1; i < fields.length; i++) {
            int eq = fields[i].indexOf('=');
            String key = unquote(fields[i].substring(0, eq));
            String value = unquote(fields[i].substring(eq + 1));
            attrs.put(key, value);
            if (key.equals("BUNDLESPEC")) {
                String head = value;
                String paramText = "";
                int semi = value.indexOf(';');
                if (semi >= 0) {
                    head = value.substring(0, semi);
                    paramText = value.substring(semi + 1);
                }
                int dash = head.indexOf('-');
                if (dash < 0) {
                    debug.add("invalid bundle specification; must be prefixed with compression: " + value + "\n");
                    return null;
                }
                String compression = head.substring(0, dash);
                String version = head.substring(dash + 1);
                if (!COMPRESSIONS.contains(compression) || !VERSIONS.contains(version)) {
                    debug.add("filtering " + attrs.get("URL") + " because unsupported bundle spec: " + value + "\n");
                    return null;
                }
                if (version.equals("v1") && !V1_COMPRESSIONS.contains(compression)) {
                    debug.add("filtering " + attrs.get("URL") + " because unsupported bundle spec: compression engine " + compression
                            + " is not supported on v1 bundles\n");
                    return null;
                }
                params = new HashMap<>();
                if (!paramText.isEmpty()) {
                    for (String p : paramText.split(";")) {
                        int pe = p.indexOf('=');
                        if (pe < 0) {
                            debug.add("invalid bundle specification: missing \"=\" in parameter: " + p + "\n");
                            return null;
                        }
                        String pk = unquote(p.substring(0, pe));
                        String pv = unquote(p.substring(pe + 1));
                        boolean upper = pk.equals(pk.toUpperCase()) && !pk.equals(pk.toLowerCase());
                        boolean lower = pk.equals(pk.toLowerCase());
                        if (!upper && !lower) {
                            debug.add("invalid bundle specification: parameter name must be either all uppercase (mandatory) or all lowercase"
                                    + " (advisory): " + pk + "\n");
                            return null;
                        }
                        String name = pk.toLowerCase();
                        if (upper && !KNOWN_PARAMS.contains(name)) {
                            debug.add("filtering " + attrs.get("URL") + " because unsupported bundle spec: unsupported mandatory bundle"
                                    + " specification parameter: " + name + "\n");
                            return null;
                        }
                        try {
                            if (name.equals("shard-id")) {
                                io.github.search5.hg4j.shape.Fingerprint.fromText(io.github.search5.hg4j.shape.Fingerprint.Kind.SHARD, pv);
                            } else if (name.equals("store-fingerprint")) {
                                io.github.search5.hg4j.shape.Fingerprint.fromText(io.github.search5.hg4j.shape.Fingerprint.Kind.SHAPE, pv);
                            } else if (BOOLEAN_PARAMS.contains(name) && parseBool(pv) == null) {
                                debug.add("parameter " + name + " should be a boolean ('" + pv + "')\n");
                                return null;
                            }
                        } catch (io.github.search5.hg4j.errors.HgValidationException invalid) {
                            debug.add("parameter " + name + " should be a " + (name.equals("shard-id") ? "shard" : "shape")
                                    + " fingerprint ('" + pv + "')\n");
                            return null;
                        }
                        params.put(name, pv);
                    }
                }
                String requirements = params.get("requirements");
                if (requirements != null) {
                    Set<String> relevant = new java.util.TreeSet<>(List.of(requirements.split(",")));
                    relevant.removeAll(STREAM_IGNORABLE_REQUIREMENTS);
                    relevant.removeAll(SUPPORTED_REQUIREMENTS);
                    if (!relevant.isEmpty()) {
                        debug.add("filtering " + attrs.get("URL") + " because unsupported bundle spec: missing support for repository"
                                + " features: " + String.join(", ", relevant) + "\n");
                        return null;
                    }
                }
                String streamParam = params.get("stream");
                String effective = version;
                if ("v2".equals(streamParam)) {
                    effective = "streamv2";
                } else if ("v3-exp".equals(streamParam)) {
                    effective = "streamv3-exp";
                }
                hasSpec = true;
                attrs.put("COMPRESSION", compression);
                String dc = params.get(DELTA_COMPRESSION);
                if (dc != null) {
                    deltaCompression = List.of(dc.split(","));
                }
                attrs.put("VERSION", effective.equals("streamv2") || effective.equals("streamv3-exp") ? "v2" : version);
                // isstreamclonespec: uncompressed, and either the packed format or a version 2 wire format carrying a stream
                stream = compression.equals("none") && (effective.equals("packed1") || effective.equals("streamv2") || effective.equals("streamv3-exp"));
            }
        }
        return new Entry(attrs, deltaCompression, params, hasSpec, stream);
    }

    /** {@code parseclonebundlesmanifest}. */
    public static List<Entry> parseManifest(String text, List<String> debug) {
        List<Entry> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            Entry e = parseLine(line, debug);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * {@code filterclonebundleentries}.
     *
     * @param streamRequested {@code true} (stream clone wanted), {@code false} (not wanted), {@code null} (either)
     * @param storeFingerprints the texts of the store fingerprints the clone asks for, {@code null} for a clone of everything
     * @param shardsSets the sets of shard fingerprints (texts) that make a usable group, {@code null} when none were asked for
     */
    public static List<Entry> filter(List<Entry> entries, Boolean streamRequested, boolean pullBundles, List<String> storeFingerprints,
                                     List<Set<String>> shardsSets, List<String> debug) {
        List<Entry> kept = new ArrayList<>();
        Map<String, Set<String>> shardsGroups = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String url = entry.attrs.get("URL");
            if (url == null) {
                debug.add("filtering entry with no url\n");
                continue;
            }
            if (!pullBundles && SCHEMES.stream().noneMatch(url::startsWith)) {
                debug.add("filtering " + url + " because not a supported clonebundle scheme\n");
                continue;
            }
            String unknown = null;
            if (entry.deltaCompression != null) {
                for (String c : entry.deltaCompression) {
                    if (!SUPPORTED_DELTA_COMPRESSION.contains(c)) {
                        unknown = c;
                        break;
                    }
                }
            }
            if (unknown != null) {
                debug.add("filtering " + url + " because delta-compression is not supported: " + unknown);
                continue;
            }
            String entryStoreFp = entry.specParam(STORE_FINGERPRINT);
            String shardId = entry.specParam(SHARD_ID);
            boolean hasShardId = shardId != null;
            if (storeFingerprints == null) {
                if (hasShardId) {
                    debug.add("filtering " + url + " because it is sharded bundle\n");
                    continue;
                } else if (entryStoreFp != null) {
                    debug.add("filtering " + url + " because it uses a store-shape\n");
                    continue;
                }
            } else {
                if (hasShardId) {
                    if (shardsSets == null || shardsSets.isEmpty()) {
                        debug.add("filtering " + url + " because no shards were requested\n");
                        continue;
                    }
                } else if (entryStoreFp == null) {
                    debug.add("filtering " + url + " because it does not use store-shape\n");
                    continue;
                } else if (!storeFingerprints.contains(entryStoreFp)) {
                    debug.add("filtering " + url + " because its store-shape is not the requested one; " + entryStoreFp + " not in ("
                            + String.join(", ", storeFingerprints) + ")\n");
                    continue;
                }
            }
            if (!entry.hasSpec) {
                if (streamRequested != null && streamRequested) {
                    debug.add("filtering " + url + " because cannot determine if a stream clone bundle\n");
                    continue;
                }
            } else if (entry.stream) {
                if (streamRequested != null && !streamRequested) {
                    debug.add("filtering " + url + " because it is a stream clonebundle\n");
                    continue;
                }
            } else if (streamRequested != null && streamRequested) {
                debug.add("filtering " + url + " because it is not a stream clonebundle\n");
                continue;
            }
            if (entry.attrs.containsKey("REQUIREDRAM")) {
                // the memory of the machine is not known to this client: nothing is held back for it
            }
            String digest = entry.attrs.get("DIGEST");
            if (digest != null) {
                if (!DIGEST.matcher(digest).matches()) {
                    debug.add("filtering " + url + " due to a bad DIGEST attribute\n");
                    continue;
                }
                int supported = 0;
                Map<String, String> seen = new HashMap<>();
                boolean broken = false;
                for (String d : digest.split(",")) {
                    String[] ad = d.split(":");
                    String algo = ad[0];
                    String value = ad[1];
                    if (!seen.containsKey(algo)) {
                        seen.put(algo, value);
                    } else if (!seen.get(algo).equals(value)) {
                        debug.add("filtering " + url + " due to conflicting " + algo + " digests\n");
                        supported = 0;
                        broken = true;
                        break;
                    }
                    Integer size = DIGEST_SIZES.get(algo);
                    if (size == null) {
                        continue;
                    }
                    if (value.length() != size) {
                        debug.add("filtering " + url + " due to a bad " + algo + " digest\n");
                        supported = 0;
                        broken = true;
                        break;
                    }
                    supported++;
                }
                if (!broken && supported == 0) {
                    debug.add("filtering " + url + " due to lack of supported digest\n");
                }
                if (supported == 0) {
                    continue;
                }
            }
            if (hasShardId) {
                String group = entry.specParam(BUNDLE_GROUP_ID);
                if (group == null) {
                    debug.add("filtering " + url + " because it has shard-id without bundle-group-id\n");
                    continue;
                }
                shardsGroups.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(shardId);
            }
            kept.add(entry);
        }
        // the groups that hold every shard of at least one of the requested sets
        Map<String, Set<String>> validGroups = new HashMap<>();
        if (shardsSets != null) {
            for (Map.Entry<String, Set<String>> group : shardsGroups.entrySet()) {
                for (Set<String> valid : shardsSets) {
                    if (group.getValue().containsAll(valid)) {
                        validGroups.put(group.getKey(), valid);
                        break;
                    }
                }
            }
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : kept) {
            String group = entry.specParam(BUNDLE_GROUP_ID);
            String url = entry.attrs.get("URL");
            String shardId = entry.specParam(SHARD_ID);
            if (group == null) {
                result.add(entry);
            } else if (!validGroups.containsKey(group)) {
                debug.add("filtering " + url + " because bundle group " + group + " is missing some required shards\n");
            } else if (!validGroups.get(group).contains(shardId)) {
                debug.add("filtering " + url + " because shard " + shardId + " is not requested\n");
            } else {
                result.add(entry);
            }
        }
        return result;
    }

    /** {@code sortclonebundleentries}: the entries ordered by {@code ui.clonebundleprefers} ({@code key=value} items), stable. */
    public static List<Entry> sort(List<String> prefers, List<Entry> entries) {
        if (prefers == null || prefers.isEmpty()) {
            return new ArrayList<>(entries);
        }
        List<String[]> items = new ArrayList<>();
        for (String p : prefers) {
            int eq = p.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("invalid ui.clonebundleprefers item: " + p
                        + "\n(each comma separated item should be key=value pairs)");
            }
            items.add(new String[] {p.substring(0, eq), p.substring(eq + 1)});
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> compareByPrefers(items, a, b));
        return sorted;
    }

    private static int compareByPrefers(List<String[]> prefers, Entry a, Entry b) {
        for (String[] pref : prefers) {
            String key = pref[0];
            String wanted = pref[1];
            String av = a.attrs.get(key);
            String bv = b.attrs.get(key);
            if (av != null && bv == null && av.equals(wanted)) {
                return -1;
            }
            if (bv != null && av == null && bv.equals(wanted)) {
                return 1;
            }
            if (av == null || bv == null) {
                continue;
            }
            if (av.equals(bv)) {
                continue;
            }
            if (av.equals(wanted)) {
                return -1;
            }
            if (bv.equals(wanted)) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * {@code best_clonebundles}: the entries to use. Usually the first after sorting; when that is a sharded bundle, one bundle for each
     * shard of its group, the one with the top level files first.
     */
    public static List<Entry> best(List<String> prefers, List<Entry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("no entries");
        }
        List<Entry> sorted = sort(prefers, entries);
        Entry first = sorted.get(0);
        String group = first.specParam(BUNDLE_GROUP_ID);
        if (group == null) {
            return List.of(first);
        }
        List<Entry> result = new ArrayList<>();
        Entry top = null;
        Set<String> seen = new HashSet<>();
        for (Entry e : sorted) {
            if (group.equals(e.specParam(BUNDLE_GROUP_ID))) {
                String shard = e.specParam(SHARD_ID);
                if (seen.add(shard)) {
                    if (e.specParam(BUNDLE_GROUP_TOP_LEVEL) != null) {
                        if (top != null) {
                            throw new IllegalStateException("two top level bundles in group " + group);
                        }
                        top = e;
                    } else {
                        result.add(e);
                    }
                }
            }
        }
        if (top == null) {
            throw new IllegalStateException("no top level bundle in group " + group);
        }
        result.add(0, top);
        return Collections.unmodifiableList(result);
    }
}
