package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayInputStream;
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
     * Real v1 capability tokens hg4j's server side can actually back. Kept independent of {@link
     * HgLocalClient#getCapabilities()} (that list serves a different, simpler purpose — the
     * {@code file://} local peer role — and changing it could affect unrelated call sites).
     */
    public static String capabilitiesString() {
        // httpheader=1024: tells the client to send argument-bearing v1 commands (getbundle,
        // pushkey, ...) as a GET with args split across X-HgArg-N request headers rather than a
        // legacy query string -- HgHttpWireServer#handleV1Command reassembles them. Matches real
        // hg's own default server advertisement (confirmed via a real hg --debug clone capture).
        //
        // bundle2=...: backlog item 26 -- without this token, a real hg client's own
        // remote.capable('bundle2') check (mercurial/exchanges/peer.py: exact-match OR any cap
        // starting with "bundle2=") comes back false, which forces the client onto its legacy
        // bundle1-only pull path (exchange.py's _forcebundle1/_pullchangeset): it calls
        // getbundle() with NO bundlecaps argument at all (empty set), so no changegroup version
        // list is ever sent and hg4j's server can never negotiate anything beyond cg1 -- verified
        // directly (2026-09-04) by instrumenting Wire1Commands.getbundle and observing a real `hg
        // clone`'s request args before this fix. Advertising bundle2 here makes the client go
        // through _pullbundle2 instead, which DOES send its own changegroup=01,02,03 (a real hg
        // 7.2 client's own default incoming-version list; cg4/cg5 are never offered by client
        // unless its own repo/config wants them) nested in a bundle2= blob -- see
        // HgLocalClient#getBundle for the server-side decode/negotiate/response-wrapping logic
        // this enables. The blob's own advertised list ("01,02,03,04,05") only needs to be
        // non-empty and truthful about what hg4j's OWN unbundle/push-apply path can accept --
        // real hg's server-side getbundle version selection never reads the SERVER's own
        // advertised capability value for the pull direction, only the CLIENT's (see
        // Bundle2Parser#decodeChangegroupVersions's doc).
        // exp-narrow-1 (backlog item 40): real hg's NARROWCAP token (mercurial/wireprototypes.py),
        // appended by wireprotov1server._capabilities() whenever the server has the bundled
        // `narrow` extension loaded -- unconditionally, not gated on the specific repo being a
        // narrow clone itself (confirmed 2026-09-06: `hg --config extensions.narrow=
        // --config experimental.narrow=True serve` advertises it for every repo it serves).
        // hg4j has no extension system and always understands the narrow getbundle args (see
        // Wire1Commands#getbundle), so it always advertises this, the same reasoning as
        // bundle2=/httpheader= above.
        return "lookup changegroupsubset branchmap pushkey known getbundle batch httpheader=1024 "
                + "unbundle=HG10UN,HG10GZ exp-narrow-1 "
                + io.github.search5.hg4j.bundle.Bundle2Parser.buildBundle2CapsToken("01,02,03,04,05");
    }

    /**
     * The {@code capabilities} line actually served for a specific repository -- adds the {@code
     * clonebundles} token when (and only when) {@code .hg/clonebundles.manifest} exists, matching
     * real hg's own conditional advertisement (real hg additionally requires the server-side {@code
     * clonebundles} extension to be enabled; hg4j has no extension system, so the manifest file's
     * presence alone is the equivalent signal).
     */
    public static String capabilitiesString(HgRepository repo) {
        String base = capabilitiesString();
        File manifest = new File(repo.getHgDir(), "clonebundles.manifest");
        return manifest.exists() ? base + " clonebundles" : base;
    }

    public static Wire1Response capabilities(HgRepository repo) {
        return Wire1Response.bytes(capabilitiesString(repo).getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg's SSH handshake bootstrap command; not used over HTTP (which uses {@code ?cmd=capabilities} directly). */
    public static Wire1Response hello(HgRepository repo) {
        return Wire1Response.bytes(("capabilities: " + capabilitiesString(repo) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Real hg's {@code clonebundles} wire command: the server repository's {@code
     * .hg/clonebundles.manifest} file content, verbatim (empty if the file doesn't exist -- real hg
     * itself only ever routes here when the capability above was advertised, but nothing stops a
     * client from asking anyway).
     */
    public static Wire1Response clonebundles(HgRepository repo) throws IOException {
        File manifest = new File(repo.getHgDir(), "clonebundles.manifest");
        byte[] body = manifest.exists() ? java.nio.file.Files.readAllBytes(manifest.toPath()) : new byte[0];
        return Wire1Response.bytes(body);
    }

    public static Wire1Response heads(HgRepository repo) throws IOException {
        List<String> heads = new HgLocalClient(repo).getHeads();
        String line = heads.isEmpty() ? "\n" : String.join(" ", heads) + "\n";
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

    public static Wire1Response known(HgRepository repo, Map<String, String> args) throws IOException {
        Revlog changelog = Wire2Commands.changelog(repo);
        String nodesArg = args.getOrDefault("nodes", "");
        StringBuilder sb = new StringBuilder();
        if (!nodesArg.isBlank()) {
            for (String hex : nodesArg.trim().split("\\s+")) {
                boolean isKnown = changelog.getRevisionCount() > 0
                        && changelog.findRevision(NodeIdUtil.fromHex(hex)) != -1;
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
        String key = args.get("key");
        Revlog changelog = Wire2Commands.changelog(repo);
        byte[] node;
        String failureMessage;
        try {
            node = NodeIdUtil.resolveRevision(changelog, key);
            failureMessage = "unknown revision '" + key + "'";
        } catch (Exception e) {
            node = null;
            failureMessage = e.getMessage();
        }
        String line = node != null
                ? "1 " + NodeIdUtil.toHex(node) + "\n"
                : "0 " + failureMessage + "\n";
        return Wire1Response.bytes(line.getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg encodes each namespace entry as {@code key\tvalue}, newline-separated (mercurial/pushkey.py's {@code encodekeys}). */
    public static Wire1Response listkeys(HgRepository repo, Map<String, String> args) throws IOException {
        String namespace = args.get("namespace");
        Map<String, String> keys = new TreeMap<>(Wire2Commands.readListKeys(repo, namespace));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : keys.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return Wire1Response.bytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg's response is {@code "<0-or-1>\n<output>"}; {@code output} is always empty here (no server-side hooks to capture). */
    public static Wire1Response pushkey(HgRepository repo, Map<String, String> args) throws IOException {
        boolean ok = Wire2Commands.applyPushkey(repo, args.get("namespace"), args.get("key"), args.get("old"), args.get("new"));
        return Wire1Response.bytes((ok ? "1\n" : "0\n").getBytes(StandardCharsets.US_ASCII));
    }

    public static Wire1Response branchmap(HgRepository repo) throws IOException {
        String branch = repo.getBranch();
        if (branch == null || branch.isEmpty()) {
            branch = "default";
        }
        List<String> heads = new HgLocalClient(repo).getHeads();
        String line = heads.isEmpty() ? "" : encode(branch) + " " + String.join(" ", heads) + "\n";
        return Wire1Response.bytes(line.getBytes(StandardCharsets.UTF_8));
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
        io.github.search5.hg4j.transport.HgRemoteConnection.NarrowScope narrowScope = null;
        String narrowArg = args.get("narrow");
        if (narrowArg != null && !narrowArg.equals("0") && !narrowArg.isEmpty()) {
            narrowScope = new io.github.search5.hg4j.transport.HgRemoteConnection.NarrowScope(
                    splitCsvOrEmpty(args.get("includepats")),
                    splitCsvOrEmpty(args.get("excludepats")));
        }
        byte[] bundle = stripHg10Prefix(new HgLocalClient(repo).getBundle(common, heads, bundleCaps, narrowScope));
        return Wire1Response.stream(bundle);
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
                changegroupPartId = io.github.search5.hg4j.bundle.Bundle2Parser
                        .extractChangegroupDetailed(new java.io.ByteArrayInputStream(bundleBytes))
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
                    return Wire1Response.streamUncompressed(io.github.search5.hg4j.bundle.Bundle2Parser
                            .buildChangegroupReplyBundle2(changegroupPartId, cgResult));
                }
                return Wire1Response.streamUncompressed(io.github.search5.hg4j.bundle.Bundle2Parser.buildEmptyBundle2Reply());
            }
            return Wire1Response.bytes(((added ? "1\n" : "0\n") + result.status).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (isBundle2Request) {
                return Wire1Response.streamUncompressed(io.github.search5.hg4j.bundle.Bundle2Parser
                        .buildErrorAbortBundle2(String.valueOf(e.getMessage())));
            }
            return Wire1Response.bytes(("0\n" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
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
            case "between" -> between(args);
            case "known" -> known(repo, args);
            case "lookup" -> lookup(repo, args);
            case "listkeys" -> listkeys(repo, args);
            case "pushkey" -> pushkey(repo, args);
            case "branchmap" -> branchmap(repo);
            case "changegroup" -> changegroup(repo, args);
            case "changegroupsubset" -> changegroupsubset(repo, args);
            case "getbundle" -> getbundle(repo, args);
            case "clonebundles" -> clonebundles(repo);
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
