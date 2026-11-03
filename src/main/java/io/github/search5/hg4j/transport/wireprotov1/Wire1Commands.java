package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
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
 * by existing tests, and to {@link Wire2Commands}'s small shared helpers ({@code readListKeys}/
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
        return boolConfig(repo, "experimental", "advertise-shapes", false);
    }

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
     * {@code store_shape} (development tree after 7.2.4): information about a named narrow-clone "store shape". hg4j
     * defines no shapes, so every name is unknown: the answer is code 2 ({@code SHAPE_NOT_FOUND}) with the first unknown name,
     * exactly what a server with the feature enabled but no shape configured replies. Header {@code >BHII}
     * (code, size of the unknown-names block, 0, 0), then the block: {@code u32 count, u32 length per name, the names}.
     */
    public static Wire1Response storeShape(HgRepository repo, Map<String, String> args) {
        String first = args.getOrDefault("names", "").split(",", -1)[0];
        byte[] name = first.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer block = java.nio.ByteBuffer.allocate(8 + name.length).order(java.nio.ByteOrder.BIG_ENDIAN);
        block.putInt(1).putInt(name.length).put(name);
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(11 + block.capacity()).order(java.nio.ByteOrder.BIG_ENDIAN);
        out.put((byte) 2).putShort((short) block.capacity()).putInt(0).putInt(0).put(block.array());
        return Wire1Response.bytes(out.array());
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
        Revlog changelog = Wire2Commands.changelog(repo);
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
        Revlog changelog = Wire2Commands.changelog(repo);
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

    public static Wire1Response known(HgRepository repo, Map<String, String> args) throws IOException {
        Revlog changelog = Wire2Commands.changelog(repo);
        String nodesArg = args.getOrDefault("nodes", "");
        StringBuilder sb = new StringBuilder();
        if (!nodesArg.isBlank()) {
            for (String hex : nodesArg.trim().split("\\s+")) {
                byte[] node = NodeIdUtil.fromHex(hex);
                // localrepo.known: the null node is "known" (its revision is -1, not missing), even in an empty repository
                boolean isKnown = NodeIdUtil.isAllZero(node)
                        || (changelog.getRevisionCount() > 0 && changelog.findRevision(node) != -1);
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
        String line = outcome.node() != null
                ? "1 " + NodeIdUtil.toHex(outcome.node()) + "\n"
                : "0 " + outcome.message() + "\n";
        return Wire1Response.bytes(line.getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg encodes each namespace entry as {@code key\tvalue}, newline-separated (mercurial/pushkey.py's {@code encodekeys}). */
    public static Wire1Response listkeys(HgRepository repo, Map<String, String> args) throws IOException {
        String namespace = args.get("namespace");
        Map<String, String> keys = new TreeMap<>(Wire2Commands.readListKeys(repo, namespace));
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
     * {@code "0\n"} failure response a genuine {@link Wire2Commands#applyPushkey} failure (e.g. a
     * concurrent-update race on {@code old}) would — real hg's own pushkey wire response never
     * distinguishes "hook declined" from "compare-and-swap failed" either (both are a bare
     * {@code 0}), so this keeps parity rather than inventing a new response shape.
     *
     * <p>The context map handed to every hook carries the same information a JGit {@code
     * ReceiveCommand} would for an equivalent ref update: {@code namespace} (only
     * {@code "bookmarks"} is a real ref move today — {@link Wire2Commands#applyPushkey} itself
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

        boolean ok = Wire2Commands.applyPushkey(repo, namespace, key, args.get("old"), args.get("new"));
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

    public static Wire1Response changegroupsubset(HgRepository repo, Map<String, String> args) throws IOException {
        List<String> bases = splitOrEmpty(args.get("bases"));
        List<String> heads = splitOrEmpty(args.get("heads"));
        byte[] bundle = stripHg10Prefix(new HgLocalClient(repo).getBundle(bases, heads, null));
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
        if (io.github.search5.hg4j.bundle.Bundle2.isBundle2(bundleBytes)) {
            return Wire1Response.streamUncompressed(Bundle2Push.process(repo, bundleBytes, splitOrEmpty(args.get("heads")),
                    preChangegroupHooks, postChangegroupHooks, prePushkeyHooks, postPushkeyHooks));
        }
        List<String> heads = splitOrEmpty(args.get("heads"));

        // 백로그 26번: capabilitiesString()이 이제 bundle2=를 광고하므로, 실제 hg 클라이언트는
        // push할 때도 (getbundle과 마찬가지로 remote.capable('bundle2') 하나로 양쪽 방향이 다
        // 갈리는 real hg 자신의 규칙, mercurial/exchange.py의 _forcebundle1 실측) 더는 맨 cg
        // 바이트가 아니라 HG20/bundle2 봉투로 body를 보내고, 그 경우 응답도 반드시 bundle2
        // 봉투([reply:changegroup]/[error:abort] 파트)여야 한다 -- 예전의 평문 "N\n<message>"
        // 그대로 돌려주면 실제 hg 클라이언트는 그걸 bundle2 스트림으로 파싱하려다
        // "abort: not a Mercurial bundle"로 즉시 깨진다(실측, 2026-09-04: 이 광고를 추가하자
        // 기존 push interop 테스트가 바로 이 메시지로 재현됨).
        boolean isBundle2Request = bundleBytes != null && bundleBytes.length >= 4
                && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '2' && bundleBytes[3] == '0';
        int changegroupPartId = -1;
        if (isBundle2Request) {
            try {
                changegroupPartId = Bundle2Parser
                        .extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes))
                        .changegroupPartId;
            } catch (Exception noChangegroupPart) {
                // 실제 hg 스펙(bundle2 파트는 changegroup 하나로 고정돼 있지 않음): 예를 들어
                // 북마크만 옮기는 push는 changegroup 파트 자체가 없을 수 있다 -- 이 경우 아래
                // pushWithHooks(bundleBytes...)가 어차피 changegroup 파싱을 다시 시도해 같은
                // 이유로 "변경 없음"으로 처리되므로, 여기서는 그냥 "회신할 changegroup 파트
                // 없음"으로만 기록해두고 계속 진행한다.
                changegroupPartId = -1;
            }
        }

        try {
            HgLocalClient.PushResult result = new HgLocalClient(repo).pushWithHooks(
                    bundleBytes, heads, preChangegroupHooks, postChangegroupHooks);
            boolean added = !result.status.startsWith("no changes found");
            if (isBundle2Request) {
                if (changegroupPartId >= 0) {
                    int cgResult = added ? Math.max(1, result.importedNodeHexes.size()) : 0;
                    return Wire1Response.streamUncompressed(Bundle2Parser
                            .buildChangegroupReplyBundle2(changegroupPartId, cgResult));
                }
                return Wire1Response.streamUncompressed(Bundle2Parser.buildEmptyBundle2Reply());
            }
            return Wire1Response.bytes(((added ? "1\n" : "0\n") + result.status).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (isBundle2Request) {
                return Wire1Response.streamUncompressed(Bundle2Parser
                        .buildErrorAbortBundle2(String.valueOf(e.getMessage())));
            }
            return Wire1Response.bytes(("0\n" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Commands this server implements in v1 (the {@link #dispatch} table plus {@code batch}, {@code unbundle}). */
    private static final java.util.Set<String> KNOWN_COMMANDS = java.util.Set.of(
            "capabilities", "hello", "heads", "between", "branches", "debugwireargs", "known", "lookup", "listkeys",
            "pushkey", "branchmap", "changegroup", "changegroupsubset", "getbundle", "clonebundles",
            "clonebundles_manifest", "stream_out", "heads_buckets", "store_shape", "get_cached_bundle_inline",
            "batch", "unbundle");

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
