package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import io.github.search5.hg4j.api.HgHook;
import java.util.LinkedHashMap;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.transport.HgRemoteConnection.NarrowScope;
import java.nio.file.Files;

/**
 * Server-side implementations of real hg's wireprotocol v1 commands (transport-agnostic —
 * corresponds to JGit's {@code UploadPack}/{@code ReceivePack}), verified against
 * {@code mercurial/wireprotov1server.py} in Mercurial 6.0. Each method returns a {@link
 * Wire1Response}; transport-specific glue ({@code HgHttpWireServer}, {@code HgSshWireServer})
 * applies the real HTTP or SSH framing on top of it.
 *
 * <p>Most of the actual repository-query/bundle-building logic here is <b>not</b> reimplemented —
 * it delegates to {@link HgLocalClient} (heads/getbundle/changegroup/listkeys/pushkey/push), which
 * already implements exactly this logic for the {@code file://} peer role and is already covered
 * by existing tests, and to {@code Wire2Commands}'s small shared helpers ({@code readListKeys}/
 * {@code applyPushkey}/{@code changelog}) rather than duplicating them a third time.</p>
 */
public final class Wire1Commands {
    private Wire1Commands() {
    }

    /**
     * The capability tokens a repository-independent server advertises (default configuration): kept for callers that have no
     * repository at hand. The line a real server sends is {@link #capabilitiesString(HgRepository)}.
     */
    public static String capabilitiesString() {
        return String.join(" ", capabilityTokens(null, false));
    }

    /** The {@code capabilities} line of an SSH (or transport-neutral) server for {@code repo}. */
    public static String capabilitiesString(HgRepository repo) {
        return String.join(" ", capabilityTokens(repo, false));
    }

    /** The {@code capabilities} line of an HTTP server for {@code repo}: the same plus the HTTP-only tokens. */
    public static String capabilitiesStringHttp(HgRepository repo) {
        return String.join(" ", capabilityTokens(repo, true));
    }

    /** Engines the HTTP server compresses with, in its order of preference ({@code server.compressionengines}). */
    public static List<String> httpCompressionEngines(HgRepository repo) {
        String configured = repo == null ? null : repo.getConfig().get("server", "compressionengines", null);
        List<String> engines = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return List.of("zstd", "zlib");
        }
        for (String name : configured.split("[,\\s]+")) {
            if (List.of("zstd", "zlib", "bzip2", "none").contains(name) && !engines.contains(name)) {
                engines.add(name);
            }
        }
        return engines;
    }

    private static boolean boolConfig(HgRepository repo, String section, String key, boolean fallback) {
        String raw = repo == null ? null : repo.getConfig().get(section, key, null);
        if (raw == null) {
            return fallback;
        }
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1": case "yes": case "true": case "on": case "always":
                return true;
            case "0": case "no": case "false": case "off": case "never":
                return false;
            default:
                return fallback;
        }
    }

    /**
     * The capability tokens, after {@code wireprotov1server._capabilities} and {@code bundle_caps.get_repo_caps} of 7.2.4
     * (verified against a real server, see {@code WireServerParityInteropTest}); the line is sorted like the original's.
     * Every token is one this server backs with behaviour: the bundle2 parts listed are all produced or consumed, see
     * {@link Bundle2Pull} and {@link Bundle2Push}. The narrow getbundle arguments) is advertised only like the original does: {@code exp-narrow-1} with {@code experimental.narrow}, and the
     * development-tree {@code heads_fingerprints} / {@code exp-shape-1} with their own settings. No hg4j-only token is advertised.
     */
    private static java.util.SortedSet<String> capabilityTokens(HgRepository repo, boolean http) {
        java.util.SortedSet<String> tokens = new java.util.TreeSet<>(List.of(
                "lookup", "branchmap", "pushkey", "known", "getbundle", "unbundlehash", "batch", "changegroupsubset",
                "unbundle=HG10GZ,HG10BZ,HG10UN"));
        if (boolConfig(repo, "experimental", "narrow", false)) {
            tokens.add("exp-narrow-1"); // as the original, only when narrow is switched on
        }
        if (boolConfig(repo, "experimental", "bundle2-advertise", true)) {
            tokens.add(bundle2CapsToken(repo));
        }
        if (repo != null && io.github.search5.hg4j.lfs.HgLfsConfig.isExtensionEnabled(repo)) {
            // hgext/lfs/wrapper.py: "lfs-serve" when LFS is required by the repository (the client then MUST load the
            // extension), and "lfs" whenever the extension is on
            if (io.github.search5.hg4j.lfs.HgLfsConfig.requiresLfs(repo)) {
                tokens.add("lfs-serve");
            }
            tokens.add("lfs");
        }
        if (repo != null) {
            tokens.addAll(StreamClone.capabilityTokens(repo));
            if (HeadsBuckets.advertised(repo)) {
                tokens.add(HeadsBuckets.CAPABILITY);
            }
            if (shapesAdvertised(repo)) {
                tokens.add("exp-shape-1");
            }
            if (new File(repo.getHgDir(), "clonebundles.manifest").exists()) {
                // real hg's clonebundles extension adds BOTH tokens when a manifest exists
                tokens.add("clonebundles");
                tokens.add("clonebundles_manifest");
            }
        }
        if (http) {
            tokens.add("httpheader=1024");
            if (boolConfig(repo, "experimental", "httppostargs", false)) {
                tokens.add("httppostargs");
            }
            tokens.add("httpmediatype=0.1rx,0.1tx,0.2tx");
            List<String> engines = httpCompressionEngines(repo);
            if (!engines.isEmpty()) {
                tokens.add("compression=" + String.join(",", engines));
            }
        }
        return tokens;
    }

    /**
     * {@code bundle2=<quoted blob>}: one capability per line. {@code stream=v2} is offered only when this server can really
     * stream the repository (the original offers it whenever {@code server.uncompressed} and {@code server.bundle2.stream}
     * are on, and then aborts the request for layouts it cannot stream).
     */
    private static String bundle2CapsToken(HgRepository repo) {
        List<String> lines = new ArrayList<>(List.of("HG20", "bookmarks", "changegroup=01,02,03"));
        if (repo == null || "check-related".equals(repo.getConfig().get("server", "concurrent-push-mode", "check-related"))) {
            lines.add("checkheads=related");
        }
        if (boolConfig(repo, "storage", "revlog.exchange-compressed-delta", true)) {
            lines.add("delta-compression=none,zlib,zstd");
        }
        lines.addAll(List.of("digests=md5,sha1,sha512", "error=abort,unsupportedcontent,pushraced,pushkey", "hgtagsfnodes",
                "listkeys", "phases=heads", "pushkey", "remote-changegroup=http,https"));
        try {
            if (repo != null && boolConfig(repo, "server", "bundle2.stream", true) && StreamClone.allowServerGeneration(repo)) {
                lines.add("stream=v2");
            }
        } catch (IOException e) {
            // cannot tell whether the repository can be streamed: do not promise it
        }
        return "bundle2=" + java.net.URLEncoder.encode(String.join("\n", lines), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Whether {@code exp-shape-1} is advertised: with {@code experimental.advertise-shapes}, as the original (default off). */
    static boolean shapesAdvertised(HgRepository repo) {
        // wireprotov1server._capabilities: inside the `experimental.narrow` block
        return boolConfig(repo, "experimental", "narrow", false) && boolConfig(repo, "experimental", "advertise-shapes", false);
    }

    /**
     * Answers the {@code capabilities} command: the space-separated capability tokens of the repository, without
     * the HTTP-only tokens (see {@link #capabilitiesHttp(HgRepository)}).
     * @param repo the served repository; must not be null
     * @return a bytes response
     */
    public static Wire1Response capabilities(HgRepository repo) {
        return Wire1Response.bytes(capabilitiesString(repo).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code capabilities} as an HTTP server answers it (with the HTTP-only tokens). */
    public static Wire1Response capabilitiesHttp(HgRepository repo) {
        return Wire1Response.bytes(capabilitiesStringHttp(repo).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code hello} as an HTTP server answers it. */
    public static Wire1Response helloHttp(HgRepository repo) {
        return Wire1Response.bytes(("capabilities: " + capabilitiesStringHttp(repo) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg's SSH handshake bootstrap command; not used over HTTP (which uses {@code ?cmd=capabilities} directly). */
    public static Wire1Response hello(HgRepository repo) {
        return Wire1Response.bytes(("capabilities: " + capabilitiesString(repo) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Real hg's legacy {@code clonebundles} command (7.2.4): the manifest without {@code peer-bundle-cache://} entries
     * and without entries whose bundlespec has a {@code store-fingerprint}, see {@link ClonebundlesManifest}. Empty when
     * there is no manifest. Before 2026-11-03 the file was sent verbatim.
     */
    public static Wire1Response clonebundles(HgRepository repo) throws IOException {
        return Wire1Response.bytes(ClonebundlesManifest.legacyAnswer(ClonebundlesManifest.getManifestLines(repo)));
    }

    /**
     * Real hg's {@code clonebundles_manifest} (7.2.4): every manifest line as it is; the client decides what it can use.
     * (The development tree's {@code mandatory_params}/{@code store_fingerprint} arguments are not in a release yet.)
     */
    public static Wire1Response clonebundlesManifest(HgRepository repo, Map<String, String> args) throws IOException {
        return Wire1Response.bytes(ClonebundlesManifest.manifestAnswer(ClonebundlesManifest.getManifestLines(repo)));
    }

    /**
     * {@code heads_buckets} (development tree after 7.2.4): the heads' bucket fingerprints and the heads the client does not
     * already know, see {@link HeadsBuckets}. The optional {@code cached} argument carries the fingerprints the client
     * remembers.
     */
    public static Wire1Response headsBuckets(HgRepository repo, Map<String, String> args) throws IOException {
        return Wire1Response.bytes(HeadsBuckets.encodedBucketInfo(repo, HeadsBuckets.parseCached(args.get("cached"))));
    }

    /** The SSH {@code hello} line with the heads bucket fingerprints, or null. */
    public static byte[] headsBucketsHelloLine(HgRepository repo) throws IOException {
        return HeadsBuckets.encodeHello(repo);
    }

    /**
     * Real hg's {@code store_shape} ({@code wireprotov1server.store_shape}): the patterns, shape fingerprint and sets of shard fingerprints
     * of the shape of {@code names} (comma separated), so a narrow client can check that it computes the same fingerprint. A name that is
     * not a shape answers {@code SHAPE_NOT_FOUND} with that name. The sets also include those of the saved previous configs in which the
     * shape had the same fingerprint, so that bundles generated before a resharding stay usable.
     */
    public static Wire1Response storeShape(HgRepository repo, Map<String, String> args) throws IOException {
        List<String> names = List.of(args.getOrDefault("names", "").split(",", -1));
        io.github.search5.hg4j.shape.StoreShards shards = io.github.search5.hg4j.shape.StoreShards.fromRepository(repo);
        io.github.search5.hg4j.shape.Shape shape;
        try {
            shape = shards.shape(names);
        } catch (io.github.search5.hg4j.shape.ShapeException e) {
            if (e.kind() == io.github.search5.hg4j.shape.ShapeException.Kind.UNKNOWN_PROFILE) {
                return Wire1Response.bytes(io.github.search5.hg4j.shape.ShapeWire.notFound(List.of(e.subject())));
            }
            throw e;
        }
        List<java.util.Set<io.github.search5.hg4j.shape.Fingerprint>> groups = new ArrayList<>(shards.shardFingerprintsForShape(shape));
        for (java.util.Set<io.github.search5.hg4j.shape.Fingerprint> previous
                : io.github.search5.hg4j.shape.ShapeConfigStore.previousShardsSets(repo, names, shape.storeFingerprint())) {
            if (!groups.contains(previous)) {
                groups.add(previous);
            }
        }
        return Wire1Response.bytes(io.github.search5.hg4j.shape.ShapeWire.ok(shape.storeFingerprint(), groups, shape.serializePatterns()));
    }

    /**
     * Real hg's {@code get_cached_bundle_inline}: sends one bundle of {@code server.peer-bundle-cache-root} (a directory
     * under {@code .hg}) as an unframed stream: the length as an unsigned varint, then the file. Used for manifest
     * entries with the {@code peer-bundle-cache://} scheme. The {@code pretransmit-inline-clone-bundle} hook is not
     * supported.
     */
    public static Wire1Response getCachedBundleInline(HgRepository repo, Map<String, String> args) throws IOException {
        String root = repo.getConfig().get("server", "peer-bundle-cache-root", null);
        if (root == null || root.isEmpty()) {
            throw new IOException("server.peer-bundle-cache-root is not set");
        }
        String path = args.getOrDefault("path", "");
        File rootDir = new File(repo.getHgDir(), root);
        File bundle = new File(rootDir, path);
        if (!bundle.exists()) {
            throw new IOException("clonebundle " + path + " does not exist");
        }
        // audit: the bundle must stay below the bundle root once symlinks and ".." are resolved
        if (!bundle.getCanonicalPath().startsWith(rootDir.getCanonicalPath())) {
            throw new IOException("clonebundle " + path + " is using an illegal path");
        }
        byte[] content = Files.readAllBytes(bundle.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 10);
        long length = content.length;
        while (length >= 0x80) { // util.uvarintencode: 7 bits per byte, low group first, high bit = more follow
            out.write((int) ((length & 0x7f) | 0x80));
            length >>>= 7;
        }
        out.write((int) length);
        out.write(content);
        return Wire1Response.streamUncompressed(out.toByteArray());
    }

    /**
     * Real hg's {@code heads}: the topological heads, NEWEST revision first (what {@code repo.heads()} returns).
     * An empty repository answers with the null node, as real hg does.
     */
    public static Wire1Response heads(HgRepository repo) throws IOException {
        List<String> heads = new ArrayList<>(new HgLocalClient(repo).getHeads()); // ascending revision order
        java.util.Collections.reverse(heads);
        String line = heads.isEmpty() ? "0".repeat(40) + "\n" : String.join(" ", heads) + "\n";
        return Wire1Response.bytes(line.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * {@code between} for a server that has no repository (a refused connection, see {@code HgSshWireServer#rejectConnection}): one
     * empty line for every pair, as for nodes the repository does not know.
     *
     * @param args the request arguments; {@code pairs} holds the space separated {@code top-bottom} pairs
     * @return an empty answer without {@code pairs}, otherwise one {@code \n} per pair
     */
    public static Wire1Response between(Map<String, String> args) {
        String pairsArg = args.getOrDefault("pairs", "");
        if (pairsArg.isBlank()) {
            return Wire1Response.bytes(new byte[0]);
        }
        StringBuilder sb = new StringBuilder();
        for (String ignored : pairsArg.trim().split(" ")) {
            sb.append('\n');
        }
        return Wire1Response.bytes(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's legacy bisection-based discovery command. Critically, real hg's SSH client
     * ({@code mercurial/sshpeer.py}'s {@code _performhandshake}) sends {@code hello} immediately
     * followed by {@code between} with a single {@code (nullid, nullid)} pair as its bootstrap
     * handshake, and then scans the response stream specifically for the literal bytes {@code
     * "1\n\n"} — the {@code between} response to that exact pair — to both flush any SSH login
     * banner noise and confirm it's talking to a real hg server before it will parse anything
     * else (including the {@code hello} response that was sent moments earlier!). Not producing
     * exactly that marker means the client scans forever and hangs — confirmed by reproducing
     * this exact hang against a real hg 7.2.2 client before this fix.
     *
     * <p>Real hg's {@code repo.between()} (see {@code localrepo.py}) returns one entry per
     * requested pair; for a pair where both nodes are equal (as the null-null handshake probe
     * always is) the entry is empty, so the whole response degenerates to one {@code "\n"} per
     * pair — exactly {@code "\n"} for the single-pair handshake case, matching the required
     * {@code "1\n\n"} wire response. The real bisection algorithm for a genuine non-equal pair is
     * not implemented (real hg's `between` is superseded by {@code heads}/{@code known} for
     * actual discovery in every modern codepath — this command's only remaining real use is the
     * handshake probe above); every pair here just reports "no intermediate nodes found".</p>
     */
    public static Wire1Response between(HgRepository repo, Map<String, String> args) throws IOException {
        String pairsArg = args.getOrDefault("pairs", "");
        if (pairsArg.isEmpty()) {
            return Wire1Response.bytes(new byte[0]);
        }
        Revlog changelog = RepoSupport.changelog(repo);
        StringBuilder sb = new StringBuilder();
        // Real hg's localrepo.between(): from `top` walk first parents towards `bottom`, keeping the nodes at
        // distances 1, 2, 4, 8, ... (the legacy bisection discovery). One line per pair, nodes space separated.
        for (String pair : pairsArg.split(" ", -1)) {
            String[] ends = pair.split("-", -1);
            int rev = ends.length == 2 && ends[0].length() == 40 ? changelog.findRevision(NodeIdUtil.fromHex(ends[0])) : -1;
            byte[] bottom = ends.length == 2 && ends[1].length() == 40 ? NodeIdUtil.fromHex(ends[1]) : new byte[20];
            List<String> picked = new ArrayList<>();
            int i = 0;
            int f = 1;
            while (rev >= 0 && !java.util.Arrays.equals(java.util.Arrays.copyOf(changelog.getIndexRecord(rev).getNodeId(), 20), bottom)) {
                int parent = changelog.getIndexRecord(rev).getParent1();
                if (i == f) {
                    picked.add(NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId()));
                    f *= 2;
                }
                rev = parent;
                i++;
            }
            sb.append(String.join(" ", picked)).append('\n');
        }
        return Wire1Response.bytes(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's legacy {@code branches}: for each requested node (default the tip) walk first parents until a
     * merge or a root, answering {@code <start> <end> <p1 of end> <p2 of end>} per node (localrepo.branches()).
     * Only the pre-1.x discovery protocol uses it.
     */
    public static Wire1Response branches(HgRepository repo, Map<String, String> args) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        List<String> nodes = splitOrEmpty(args.get("nodes"));
        List<Integer> starts = new ArrayList<>();
        if (nodes.isEmpty()) {
            if (changelog.getRevisionCount() > 0) {
                starts.add(changelog.getRevisionCount() - 1);
            }
        } else {
            for (String hex : nodes) {
                int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
                if (rev < 0) {
                    throw new io.github.search5.hg4j.errors.HgRevisionNotFoundException(hex);
                }
                starts.add(rev);
            }
        }
        String nul = "0".repeat(40);
        StringBuilder sb = new StringBuilder();
        for (int start : starts) {
            int n = start;
            while (true) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(n);
                int p1 = rec.getParent1();
                int p2 = rec.getParent2();
                if (p2 != -1 || p1 == -1) {
                    sb.append(NodeIdUtil.toHex(changelog.getIndexRecord(start).getNodeId())).append(' ')
                            .append(NodeIdUtil.toHex(rec.getNodeId())).append(' ')
                            .append(p1 == -1 ? nul : NodeIdUtil.toHex(changelog.getIndexRecord(p1).getNodeId())).append(' ')
                            .append(p2 == -1 ? nul : NodeIdUtil.toHex(changelog.getIndexRecord(p2).getNodeId())).append('\n');
                    break;
                }
                n = p1;
            }
        }
        return Wire1Response.bytes(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's {@code debugwireargs}: echoes {@code one two three four five}, where {@code three} and {@code four}
     * are the optional arguments and {@code five} is never sent over the wire, hence always {@code None}. Used to
     * test argument passing.
     */
    public static Wire1Response debugwireargs(Map<String, String> args) {
        String text = String.join(" ", args.getOrDefault("one", ""), args.getOrDefault("two", ""),
                args.getOrDefault("three", "None"), args.getOrDefault("four", "None"), "None");
        return Wire1Response.bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers the {@code known} command: one character per requested node, {@code '1'} if the repository has the
     * node and it is visible in the served view (the null node always counts as known) and {@code '0'} otherwise.
     * @param repo the served repository; must not be null
     * @param args the command arguments; {@code nodes} holds the space-separated hex nodes (missing means none)
     * @return a bytes response with the digit string
     * @throws IOException if the changelog cannot be read
     */
    public static Wire1Response known(HgRepository repo, Map<String, String> args) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        String nodesArg = args.getOrDefault("nodes", "");
        StringBuilder sb = new StringBuilder();
        if (!nodesArg.isBlank()) {
            for (String hex : nodesArg.trim().split("\\s+")) {
                byte[] node = NodeIdUtil.fromHex(hex);
                // localrepo.known: the null node is "known" (its revision is -1, not missing), even in an empty repository
                boolean isKnown = NodeIdUtil.isAllZero(node)
                        || (changelog.getRevisionCount() > 0 && changelog.findRevision(node) != -1
                        && !io.github.search5.hg4j.transport.ServedView.isHidden(repo, node));
                sb.append(isKnown ? '1' : '0');
            }
        }
        return Wire1Response.bytes(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's response is {@code "1 <hex>\n"} on success, {@code "0 <error>\n"} on failure --
     * and {@code <error>} is whatever message the underlying lookup failure actually produced
     * ({@code mercurial/wireprotov1server.py}'s {@code lookup()} catches any exception from {@code
     * repo.lookup()} and reports {@code stringutil.forcebytestr(inst)} verbatim), not a single
     * hardcoded string: a genuinely missing revision raises a different error than an ambiguous
     * short hex prefix (real hg's {@code revlog.py} raises the distinct {@code
     * AmbiguousPrefixLookupError} for the latter), and real hg's response text differs
     * accordingly.
     */
    public static Wire1Response lookup(HgRepository repo, Map<String, String> args) throws IOException {
        RevisionLookup.Outcome outcome = RevisionLookup.resolve(repo, args.get("key"));
        if ("tip".equals(args.get("key"))) {
            // the tip of the served view: the highest revision that is not hidden
            Revlog changelog = RepoSupport.changelog(repo);
            boolean[] hidden = io.github.search5.hg4j.transport.ServedView.hidden(repo, changelog);
            if (hidden != null) {
                int rev = changelog.getRevisionCount() - 1;
                while (rev >= 0 && hidden[rev]) {
                    rev--;
                }
                if (rev >= 0) {
                    outcome = RevisionLookup.Outcome.found(changelog.getIndexRecord(rev).getNodeId());
                }
            }
        }
        if (outcome.node() != null && io.github.search5.hg4j.transport.ServedView.isHidden(repo, outcome.node())) {
            // a secret changeset is not served; the full hash is reported as filtered, anything else as unknown (as Mercurial does)
            String key = args.get("key");
            outcome = RevisionLookup.Outcome.failed(key != null && key.matches("[0-9a-f]{4,40}")
                    ? "filtered revision '" + key + "' (not in 'served' subset)" : "unknown revision '" + key + "'");
        }
        String line = outcome.node() != null
                ? "1 " + NodeIdUtil.toHex(outcome.node()) + "\n"
                : "0 " + outcome.message() + "\n";
        return Wire1Response.bytes(line.getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg encodes each namespace entry as {@code key\tvalue}, newline-separated (mercurial/pushkey.py's {@code encodekeys}). */
    public static Wire1Response listkeys(HgRepository repo, Map<String, String> args) throws IOException {
        String namespace = args.get("namespace");
        Map<String, String> keys = new TreeMap<>(RepoSupport.readListKeys(repo, namespace));
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : keys.entrySet()) {
            lines.add(e.getKey() + '\t' + e.getValue());
        }
        // joined by '\n' with NO trailing newline, like pushkey.encodekeys
        return Wire1Response.bytes(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg's response is {@code "<0-or-1>\n<output>"}; {@code output} is always empty here (no server-side hooks to capture). */
    public static Wire1Response pushkey(HgRepository repo, Map<String, String> args) throws IOException {
        return pushkey(repo, args, List.of(), List.of());
    }

    /**
     * Same as {@link #pushkey(HgRepository, Map)}, but also runs server-side {@link HgHook}
     * callbacks around applying the incoming pushkey (yona-wiki P3-21/P3-22 — real hg's pushkey
     * wire command is the ONLY place a bookmark move actually happens, so this is the equivalent
     * hook point to {@link #unbundle(HgRepository, byte[], Map, List, List)}'s
     * {@code preChangegroupHooks}/{@code postChangegroupHooks} for "which ref moved from where to
     * where", which {@code unbundle}'s own hooks never see — {@code unbundle} only ever sees raw
     * changeset nodes, never bookmark/ref names). A pre-hook rejection surfaces through the same
     * {@code "0\n"} failure response a genuine {@link RepoSupport#applyPushkey} failure (e.g. a
     * concurrent-update race on {@code old}) would — real hg's own pushkey wire response never
     * distinguishes "hook declined" from "compare-and-swap failed" either (both are a bare
     * {@code 0}), so this keeps parity rather than inventing a new response shape.
     *
     * <p>The context map handed to every hook carries the same information a JGit {@code
     * ReceiveCommand} would for an equivalent ref update: {@code namespace} (only
     * {@code "bookmarks"} is a real ref move today — {@link RepoSupport#applyPushkey} itself
     * already no-ops any other namespace), {@code key} (the bookmark name), {@code old}/{@code
     * new} (node hex, or {@code ""} for a bookmark being created/deleted respectively — never
     * {@code null}, matching real hg's own wire encoding of an absent node), and {@code
     * repository}.</p>
     */
    public static Wire1Response pushkey(HgRepository repo, Map<String, String> args,
                                         List<HgHook> prePushkeyHooks,
                                         List<HgHook> postPushkeyHooks) throws IOException {
        String namespace = args.get("namespace");
        String key = args.get("key");
        String oldVal = args.getOrDefault("old", "");
        String newVal = args.getOrDefault("new", "");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("namespace", namespace);
        context.put("key", key);
        context.put("old", oldVal);
        context.put("new", newVal);
        context.put("repository", repo);

        for (HgHook hook : prePushkeyHooks) {
            if (!hook.run(context)) {
                return Wire1Response.bytes("0\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
        return applyPushkeyAndRunPostHooks(repo, args, context, postPushkeyHooks);
    }

    /**
     * The {@code pushkey} of a bundle2 part. The original's {@code repo.pushkey} catches the {@code HookAbort} of a refusing
     * {@code prepushkey} hook and answers {@code False}, so a refusal is the plain "not updated" answer here as well (a mandatory
     * part then fails with {@code error:pushkey}, an advisory one is answered with {@code return=0}).
     */
    static Wire1Response pushkeyFromPart(HgRepository repo, Map<String, String> args, List<HgHook> prePushkeyHooks,
                                         List<HgHook> postPushkeyHooks) throws IOException {
        return pushkey(repo, args, prePushkeyHooks, postPushkeyHooks);
    }

    private static Wire1Response applyPushkeyAndRunPostHooks(HgRepository repo, Map<String, String> args,
                                                             Map<String, Object> context, List<HgHook> postPushkeyHooks) throws IOException {

        boolean ok = RepoSupport.applyPushkey(repo, args.get("namespace"), args.get("key"), args.get("old"), args.get("new"));
        if (ok) {
            for (HgHook hook : postPushkeyHooks) {
                hook.run(context);
            }
        }
        return Wire1Response.bytes((ok ? "1\n" : "0\n").getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's {@code branchmap}: one line per named branch, {@code <url-quoted branch> <head> <head> ...}, the
     * lines joined by {@code \n} WITHOUT a trailing newline (mercurial/wireprotoserver's
     * {@code encodebranchcache}). Closed heads are included. Before 2026-11-03 this listed only the repository's
     * CURRENT branch with every head of the repository, which is wrong as soon as there is a second branch.
     */
    public static Wire1Response branchmap(HgRepository repo) throws IOException {
        Map<String, List<String>> byBranch = new TreeMap<>(new HgLocalClient(repo).getBranchHeads());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byBranch.entrySet()) {
            lines.add(encode(e.getKey()) + " " + String.join(" ", e.getValue()));
        }
        return Wire1Response.bytes(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** Same wire shape as {@code getbundle}/{@code changegroupsubset} — real hg's {@code changegroup} command just fixes {@code heads} to the repo's current heads. */
    public static Wire1Response changegroup(HgRepository repo, Map<String, String> args) throws IOException {
        List<String> roots = splitOrEmpty(args.get("roots"));
        byte[] bundle = stripHg10Prefix(new HgLocalClient(repo).getChangegroup(roots));
        return Wire1Response.stream(bundle);
    }

    /**
     * Answers the {@code changegroupsubset} command with a changegroup stream of the changesets between the given bases
     * and heads.
     * @param repo the served repository; must not be null
     * @param args the command arguments; {@code bases} and {@code heads} hold space-separated hex nodes (missing means none)
     * @return a stream response without the standalone-file bundle prefix
     * @throws IOException if the changegroup cannot be built
     */
    public static Wire1Response changegroupsubset(HgRepository repo, Map<String, String> args) throws IOException {
        List<String> bases = splitOrEmpty(args.get("bases"));
        List<String> heads = splitOrEmpty(args.get("heads"));
        byte[] bundle = stripHg10Prefix(new HgLocalClient(repo).getBundleFromRoots(bases, heads));
        return Wire1Response.stream(bundle);
    }

    /**
     * {@code HgLocalClient.getBundle()}'s {@code "HG10UN"} 6-byte prefix is a <em>standalone .hg
     * file</em> convention (matches what {@code hg bundle}/{@code readbundle()} expect on disk) —
     * it is not part of the real wire {@code getbundle}/{@code changegroup}/{@code
     * changegroupsubset} response, which is either a genuine bundle2 envelope (starting {@code
     * HG20}) or bare cg1 chunks with no magic header at all (real hg's own {@code _callhttp}
     * streams {@code exchange.getbundlechunks()}'s raw generator output directly). Sending the
     * prefix as-is corrupts the stream for a real hg client: it reads the 4 magic bytes as a
     * chunk-length header and aborts with "stream ended unexpectedly, expected &lt;huge
     * number&gt;" (confirmed against real hg 7.2.2 as the client before this fix). hg4j's own
     * {@link io.github.search5.hg4j.transport.HgRemoteClient}/{@code FetchCommand} still expect
     * the prefix (their own long-standing self-consistent convention) — this stripping applies
     * only to the real wire-protocol path, not to {@code HgLocalClient}'s own {@code file://} role.
     */
    private static byte[] stripHg10Prefix(byte[] bundle) {
        if (bundle.length >= 6 && bundle[0] == 'H' && bundle[1] == 'G' && bundle[2] == '1' && bundle[3] == '0') {
            return Arrays.copyOfRange(bundle, 6, bundle.length);
        }
        return bundle;
    }

    /**
     * {@code exchange.getbundlechunks}'s {@code store_shape_with_fingerprint} ({@code names:fingerprint}): the client says which shape it was
     * cloned with and what fingerprint it computed for it. The shape is looked up here ({@code narrowspec.patterns_for_shape}), the
     * fingerprints must agree, and its patterns become the {@code path:} patterns of an ordinary narrow request ({@code to_legacy_patterns}:
     * the empty path is {@code path:.}).
     */
    static NarrowScope shapeScope(HgRepository repo, String spec) throws IOException {
        String[] parts = spec.split(":", -1);
        if (parts.length != 2) {
            throw new IOException("invalid store shape fingerprint: '" + spec + "'");
        }
        io.github.search5.hg4j.shape.Fingerprint fingerprint;
        try {
            fingerprint = io.github.search5.hg4j.shape.Fingerprint.fromText(io.github.search5.hg4j.shape.Fingerprint.Kind.SHAPE, parts[1]);
        } catch (io.github.search5.hg4j.errors.HgValidationException e) {
            throw new IOException("invalid store shape fingerprint: '" + parts[1] + "'");
        }
        List<String> names = List.of(parts[0].split(",", -1));
        io.github.search5.hg4j.shape.Shape shape;
        try {
            shape = io.github.search5.hg4j.shape.StoreShards.fromRepository(repo).shape(names);
        } catch (io.github.search5.hg4j.shape.ShapeException e) {
            if (e.kind() == io.github.search5.hg4j.shape.ShapeException.Kind.UNKNOWN_PROFILE) {
                throw new IOException("shape not found on remote: '" + e.subject() + "'");
            }
            throw e;
        }
        io.github.search5.hg4j.shape.Fingerprint server = shape.storeFingerprint();
        if (!server.equals(fingerprint)) {
            throw new IOException("fingerprint mismatch for shape '" + String.join(",", names) + "'\n(hint: server: " + server.toText()
                    + ", client: " + fingerprint.toText() + ")");
        }
        io.github.search5.hg4j.shape.ShardTreeNode.Patterns patterns = shape.patterns();
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        patterns.includes().forEach(p -> includes.add("path:" + (p.isEmpty() ? "." : p)));
        patterns.excludes().forEach(p -> excludes.add("path:" + (p.isEmpty() ? "." : p)));
        java.util.Collections.sort(includes);
        java.util.Collections.sort(excludes);
        return new NarrowScope(includes, excludes);
    }

    /**
     * Backlog item 40: narrow clone wire arguments. Real hg's {@code getbundle} command declares
     * no fixed args at all ({@code @wireprotocommand(b'getbundle', b'*')}) -- {@code narrow},
     * {@code includepats} and {@code excludepats} just ride along in the same generic args map as
     * {@code common}/{@code heads}/{@code bundlecaps} (verified 2026-09-06 against Mercurial 7.2's
     * {@code mercurial/wireprototypes.py}/{@code wireprotov1peer.py}: {@code includepats}/{@code
     * excludepats} are core {@code GETBUNDLE_ARGUMENTS} csv fields; {@code narrow} itself is a
     * boolean the bundled {@code narrow} extension adds to that same dict when loaded -- hg4j has
     * no extension system, so it always understands it, the same way it always advertises {@code
     * exp-narrow-1} below). A real hg client encodes the boolean as the literal string {@code "1"}
     * (or omits the key/sends {@code "0"} for false) -- anything present and non-{@code "0"} is
     * treated as true here, matching real hg's own decode ({@code bool(value)} on a non-empty
     * string is always true, so real hg peers never actually send a literal {@code "0"} for a
     * false narrow flag; they simply omit the key, exactly like {@code includepats}/{@code
     * excludepats} below).
     */
    public static Wire1Response getbundle(HgRepository repo, Map<String, String> args) throws IOException {
        List<String> common = splitOrEmpty(args.get("common"));
        List<String> heads = splitOrEmpty(args.get("heads"));
        List<String> bundleCaps = args.containsKey("bundlecaps")
                ? Arrays.asList(args.get("bundlecaps").split(","))
                : null;
        NarrowScope narrowScope = null;
        String narrowArg = args.get("narrow");
        if (narrowArg != null && !narrowArg.equals("0") && !narrowArg.isEmpty()) {
            narrowScope = new NarrowScope(
                    splitCsvOrEmpty(args.get("includepats")),
                    splitCsvOrEmpty(args.get("excludepats")));
        }
        String shapeSpec = args.get("store_shape_with_fingerprint");
        if (shapeSpec != null) {
            narrowScope = shapeScope(repo, shapeSpec);
        }
        Map<String, List<String>> clientCaps = Bundle2Pull.clientCaps(bundleCaps);
        boolean withoutChangegroup = "0".equals(args.get("cg")) && !clientCaps.isEmpty();
        byte[] bundle;
        if (withoutChangegroup) {
            // `cg=0`: the client only wants the other parts (bookmarks, phases, ...), no changegroup
            bundle = new io.github.search5.hg4j.bundle.Bundle2.Writer().toBytes(null);
        } else {
            bundle = stripHg10Prefix(new HgLocalClient(repo).getBundle(common, heads, bundleCaps, narrowScope));
        }
        if (io.github.search5.hg4j.bundle.Bundle2.isBundle2(bundle) && Bundle2Pull.wantsExtraParts(args, clientCaps)) {
            bundle = Bundle2Pull.addParts(repo, bundle, args, heads, clientCaps);
        }
        return "True".equals(args.get("stream")) || "1".equals(args.get("stream"))
                ? Wire1Response.streamPreferUncompressed(bundle) : Wire1Response.stream(bundle);
    }

    /**
     * Applies an incoming push. Delegates entirely to {@link HgLocalClient#push}, which already
     * implements this for the {@code file://} peer role (bundle1/bundle2 container detection,
     * bare-repo dirstate preservation, transactional apply via {@code PullCommand.applyBundle}).
     *
     * <p>Response format is real hg's {@code pushres}/{@code pusherr} convention: {@code
     * "<0-or-1>\n<message>"} where the leading digit means <b>"were new revisions actually
     * added"</b>, not "did the request succeed" — {@code 0} covers both "genuinely nothing to
     * push" and a real error (real hg's client shows the message either way and exits non-zero
     * for {@code 0}; it does not otherwise distinguish the two cases in this response). Confirmed
     * against real hg 7.2.2 as the client: sending {@code "0\n"} on success made real hg print the
     * success message via {@code remote: ...} but still exit non-zero, since it read the leading
     * {@code 0} as "nothing landed".</p>
     */
    public static Wire1Response unbundle(HgRepository repo, byte[] bundleBytes, Map<String, String> args) throws IOException, HgLockException {
        return unbundle(repo, bundleBytes, args, List.of(), List.of());
    }

    /**
     * Same as {@link #unbundle(HgRepository, byte[], Map)}, but also runs server-side {@link
     * io.github.search5.hg4j.api.HgHook} callbacks around applying the incoming changegroup — see
     * {@link HgLocalClient#pushWithHooks}. A pre-hook rejection surfaces through the same {@code
     * "0\n<message>"} error path a genuine apply failure would.
     */
    public static Wire1Response unbundle(HgRepository repo, byte[] bundleBytes, Map<String, String> args,
                                          List<HgHook> preChangegroupHooks,
                                          List<HgHook> postChangegroupHooks) throws IOException, HgLockException {
        return unbundle(repo, bundleBytes, args, preChangegroupHooks, postChangegroupHooks, List.of(), List.of());
    }

    /**
     * Same, with the pushkey hooks that also apply to the {@code pushkey} and {@code bookmarks} parts of a bundle2 push
     * (a bookmark moved through a bundle2 part must be subject to the same rules as one moved with the {@code pushkey} command).
     */
    public static Wire1Response unbundle(HgRepository repo, byte[] bundleBytes, Map<String, String> args,
                                          List<HgHook> preChangegroupHooks,
                                          List<HgHook> postChangegroupHooks,
                                          List<HgHook> prePushkeyHooks,
                                          List<HgHook> postPushkeyHooks) throws IOException, HgLockException {
        return unbundle(repo, bundleBytes, args, preChangegroupHooks, postChangegroupHooks, prePushkeyHooks, postPushkeyHooks, false);
    }

    /**
     * Same; {@code captureOutput} is true for HTTP, where the original answers with what the server printed while applying the push
     * ({@code adding changesets}, ..., {@code added 1 changesets with ...}), as {@code output} parts of a bundle2 answer or after the
     * result code of a bundle1 one. Over SSH it goes to the server's stderr instead.
     */
    public static Wire1Response unbundle(HgRepository repo, byte[] bundleBytes, Map<String, String> args,
                                          List<HgHook> preChangegroupHooks,
                                          List<HgHook> postChangegroupHooks,
                                          List<HgHook> prePushkeyHooks,
                                          List<HgHook> postPushkeyHooks,
                                          boolean captureOutput) throws IOException, HgLockException {
        return unbundle(repo, bundleBytes, args, preChangegroupHooks, List.of(), postChangegroupHooks, prePushkeyHooks,
                postPushkeyHooks, captureOutput);
    }

    /** Same, with the {@code pretxnchangegroup} hooks (run after the changegroup is applied; a refusal undoes everything). */
    public static Wire1Response unbundle(HgRepository repo, byte[] bundleBytes, Map<String, String> args,
                                          List<HgHook> preChangegroupHooks,
                                          List<HgHook> preTxnChangegroupHooks,
                                          List<HgHook> postChangegroupHooks,
                                          List<HgHook> prePushkeyHooks,
                                          List<HgHook> postPushkeyHooks,
                                          boolean captureOutput) throws IOException, HgLockException {
        if (io.github.search5.hg4j.bundle.Bundle2.isBundle2(bundleBytes)) {
            return Wire1Response.streamUncompressed(Bundle2Push.process(repo, bundleBytes, splitOrEmpty(args.get("heads")),
                    preChangegroupHooks, preTxnChangegroupHooks, postChangegroupHooks, prePushkeyHooks, postPushkeyHooks, captureOutput));
        }
        // A bundle1 push (an HG10xx body). Like the original, a refusal other than a push race is only reported on the server's
        // own stderr (here: the log) and answered with the result code 0; a push race is answered with the message itself.
        List<String> heads = splitOrEmpty(args.get("heads"));
        try {
            int countBefore = RepoSupport.changelog(repo).getRevisionCount();
            HgLocalClient.PushResult result = new HgLocalClient(repo).pushWithHooks(
                    bundleBytes, heads, preChangegroupHooks, preTxnChangegroupHooks, postChangegroupHooks, null);
            if (result.status.startsWith("no changes found")) {
                return Wire1Response.pushResult(0);
            }
            int delta = Bundle2Push.deltaHeads(repo, countBefore);
            return Wire1Response.pushResult(delta < 0 ? delta - 1 : delta + 1,
                    "adding changesets\nadding manifests\nadding file changes\n" + Bundle2Push.summaryLine(result, delta));
        } catch (io.github.search5.hg4j.errors.HgPushRacedException e) {
            return Wire1Response.pushError(String.valueOf(e.getMessage()));
        } catch (io.github.search5.hg4j.errors.HgHookRejectedException e) {
            // pretxnchangegroup: the push was applied and undone again; what the server printed while doing so goes with the answer
            java.util.logging.Logger.getLogger(Wire1Commands.class.getName())
                    .log(java.util.logging.Level.WARNING, "bundle1 push refused: " + e.getMessage());
            if (!e.isRolledBack()) {
                return Wire1Response.pushResult(0, e.getHookOutput());
            }
            return Wire1Response.pushResult(0, "adding changesets\nadding manifests\nadding file changes\n"
                    + e.getHookOutput() + "transaction abort!\nrollback completed\n");
        } catch (IOException | RuntimeException | HgLockException e) {
            java.util.logging.Logger.getLogger(Wire1Commands.class.getName())
                    .log(java.util.logging.Level.WARNING, "bundle1 push refused: " + e.getMessage(), e);
            return Wire1Response.pushResult(0);
        }
    }

    /**
     * The first thing the original's {@code unbundle} does, before it reads the payload: compare the client's {@code heads}
     * argument with the current heads. Null when they agree (or the client forces), else the {@code pusherr} answer.
     */
    public static Wire1Response preCheckUnbundleHeads(HgRepository repo, Map<String, String> args) throws IOException {
        try {
            new HgLocalClient(repo).checkWireHeads(splitOrEmpty(args.get("heads")), "preparing changes");
            return null;
        } catch (io.github.search5.hg4j.errors.HgPushRacedException e) {
            return Wire1Response.pushError(String.valueOf(e.getMessage()));
        }
    }

    /** Commands this server implements in v1 (the {@link #dispatch} table plus {@code batch}, {@code unbundle}). */
    private static final java.util.Set<String> KNOWN_COMMANDS = java.util.Set.of(
            "capabilities", "hello", "heads", "between", "branches", "debugwireargs", "known", "lookup", "listkeys",
            "pushkey", "branchmap", "changegroup", "changegroupsubset", "getbundle", "clonebundles",
            "clonebundles_manifest", "stream_out", "heads_buckets", "store_shape", "get_cached_bundle_inline",
            "batch", "unbundle");

    /**
     * Tells whether the command is implemented by this server in protocol v1.
     * @param cmd the command name
     * @return true if the command is implemented
     */
    public static boolean isKnownCommand(String cmd) {
        return KNOWN_COMMANDS.contains(cmd);
    }

    /** Commands that change the repository: real hg's HTTP server serves them only to a POST request. */
    public static boolean requiresPush(String cmd) {
        return "unbundle".equals(cmd) || "pushkey".equals(cmd);
    }

    /**
     * Dispatches a single named v1 command with its arguments — the shared table both direct
     * {@code ?cmd=X} routing (HTTP/SSH glue) and {@link #batch} (which dispatches each
     * sub-command through here too) use. {@code unbundle} is deliberately excluded: it needs a
     * raw request body rather than string args, so transport glue calls {@link #unbundle} directly.
     */
    public static Wire1Response dispatch(HgRepository repo, String cmd, Map<String, String> args) throws IOException {
        return switch (cmd) {
            case "capabilities" -> capabilities(repo);
            case "hello" -> hello(repo);
            case "heads" -> heads(repo);
            case "between" -> between(repo, args);
            case "branches" -> branches(repo, args);
            case "debugwireargs" -> debugwireargs(args);
            case "known" -> known(repo, args);
            case "lookup" -> lookup(repo, args);
            case "listkeys" -> listkeys(repo, args);
            case "pushkey" -> pushkey(repo, args);
            case "branchmap" -> branchmap(repo);
            case "changegroup" -> changegroup(repo, args);
            case "changegroupsubset" -> changegroupsubset(repo, args);
            case "getbundle" -> getbundle(repo, args);
            case "clonebundles" -> clonebundles(repo);
            case "clonebundles_manifest" -> clonebundlesManifest(repo, args);
            case "stream_out" -> StreamClone.streamOut(repo);
            case "heads_buckets" -> headsBuckets(repo, args);
            case "store_shape" -> storeShape(repo, args);
            case "get_cached_bundle_inline" -> getCachedBundleInline(repo, args);
            default -> Wire1Response.oobError("unsupported command: " + cmd);
        };
    }

    /**
     * Real hg's HTTP peer batches multiple discovery calls (typically {@code heads}+{@code known}
     * during clone/pull) into a single {@code ?cmd=batch} round trip <b>unconditionally</b>
     * whenever it queues more than one command through its command executor — this happens
     * regardless of whether the server advertised the {@code batch} capability, so this is not
     * optional for real-hg-as-client interop. Request format: {@code cmds=<op> <k>=<v>,<k>=<v>;
     * <op> ...} (verified against {@code mercurial/wireprotov1peer.py}'s {@code
     * encodebatchcmds}/{@code wireprotov1server.py}'s {@code batch()}); response is the same
     * {@code ;}-joined, per-command-escaped shape, one entry per sub-command in request order.
     * Only {@link Wire1Response.Kind#BYTES} results can be batched (real hg's own server asserts
     * this too) — {@code getbundle}/{@code changegroup}/{@code unbundle} are never batched by a
     * real client since they're the actual bulk data transfer, not discovery.
     */
    public static Wire1Response batch(HgRepository repo, Map<String, String> args) throws IOException {
        String cmds = args.getOrDefault("cmds", "");
        List<String> results = new ArrayList<>();
        if (!cmds.isEmpty()) {
            for (String pair : cmds.split(";", -1)) {
                int sp = pair.indexOf(' ');
                String op = sp == -1 ? pair : pair.substring(0, sp);
                String argsRaw = sp == -1 ? "" : pair.substring(sp + 1);

                Map<String, String> subArgs = new LinkedHashMap<>();
                if (!argsRaw.isEmpty()) {
                    for (String kv : argsRaw.split(",")) {
                        if (kv.isEmpty()) continue;
                        int eq = kv.indexOf('=');
                        subArgs.put(unescapeBatchArg(kv.substring(0, eq)), unescapeBatchArg(kv.substring(eq + 1)));
                    }
                }

                Wire1Response sub = dispatch(repo, op, subArgs);
                byte[] payload = sub.getKind() == Wire1Response.Kind.BYTES ? sub.getPayload() : new byte[0];
                results.add(escapeBatchArg(new String(payload, StandardCharsets.ISO_8859_1)));
            }
        }
        return Wire1Response.bytes(String.join(";", results).getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Order matters: {@code :} must be escaped first so later replacements' colons aren't re-escaped. */
    private static String escapeBatchArg(String plain) {
        return plain.replace(":", ":c").replace(",", ":o").replace(";", ":s").replace("=", ":e");
    }

    /** Reverses {@link #escapeBatchArg} in the opposite order, matching real hg's {@code unescapebatcharg}. */
    private static String unescapeBatchArg(String escaped) {
        return escaped.replace(":e", "=").replace(":s", ";").replace(":o", ",").replace(":c", ":");
    }

    private static List<String> splitOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.trim().split("\\s+")));
    }

    /**
     * Splits a real hg "csv" wire arg type (comma-joined, per {@code
     * wireprototypes.GETBUNDLE_ARGUMENTS}'s {@code includepats}/{@code excludepats}) -- unlike
     * {@link #splitOrEmpty}, which handles the space-joined "nodes" type used by
     * {@code common}/{@code heads}/{@code roots}.
     */
    private static List<String> splitCsvOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(",")));
    }

    private static String encode(String s) {
        // Real hg's encoding.tolocal()/fromlocal() is a no-op for plain ASCII branch names, which
        // is all hg4j itself ever produces.
        return s;
    }
}
