package io.github.search5.hg4j.transport.wireprotov2;

import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import io.github.search5.hg4j.util.SafeFileIO;
import java.util.Arrays;

/**
 * Server-side implementations of real hg's wireprotocol v2 commands, verified against
 * {@code mercurial/wireprotov2server.py} in Mercurial 6.0 (the last release with a working v2
 * server — the protocol was removed entirely starting with 6.1). Each method returns the list
 * of response objects a real handler would {@code yield}; {@link Wire2Transport#buildCommandResponseFrames}
 * takes care of the {@code {status: ok}} envelope and frame chunking.
 *
 * <p>Implements: {@code capabilities}, {@code heads}, {@code known}, {@code listkeys},
 * {@code lookup}, {@code pushkey}, {@code branchmap}, {@code changesetdata}, {@code manifestdata},
 * {@code filesdata}. Not implemented: {@code filedata} (single-path variant of {@code filesdata})
 * and {@code rawstorefiledata} (raw revlog byte streaming) — both are optional/advanced and no
 * client needs them to complete a pull. There is also no push/unbundle-equivalent command,
 * because real hg's v2, as actually shipped, never had one (it stayed a read-only, "under active
 * development" protocol before being abandoned).</p>
 *
 * <p>Simplifications relative to real hg, chosen because they stay wire-compatible while cutting
 * a large amount of complexity: {@code manifestdata}/{@code changesetdata}/{@code filesdata}
 * always send full revision text for the {@code revision} field (never a delta against a
 * previous node) — real hg's own emitrevisions() heuristically picks either, and a spec-compliant
 * client must handle full text regardless. {@code filesdata}'s {@code pathfilter} argument is
 * accepted but not applied (all touched paths are always returned) — narrowing is an optimization,
 * not a correctness requirement. {@code manifestdata}'s {@code tree} argument must be empty
 * (hg4j only has flat manifests, matching every repository this library creates).</p>
 */
public final class Wire2Commands {
    private static final byte[] NULL_NODE = new byte[20];

    private Wire2Commands() {
    }

    public static final String NAMESPACE = "exp-http-v2-0003";

    // ==================== capabilities descriptor ====================

    /** Shape matches real hg's {@code httpv2apidescriptor}/{@code _capabilitiesv2}. */
    public static Map<String, Object> namespaceDescriptor() {
        Map<String, Object> commands = new LinkedHashMap<>();
        commands.put("capabilities", commandInfo(Map.of(), List.of("pull")));
        commands.put("heads", commandInfo(Map.of(), List.of("pull")));
        commands.put("known", commandInfo(Map.of("nodes", argInfo("list", false)), List.of("pull")));
        commands.put("listkeys", commandInfo(Map.of("namespace", argInfo("bytes", true)), List.of("pull")));
        commands.put("lookup", commandInfo(Map.of("key", argInfo("bytes", true)), List.of("pull")));
        commands.put("branchmap", commandInfo(Map.of(), List.of("pull")));
        commands.put("pushkey", commandInfo(Map.of(
                "namespace", argInfo("bytes", true),
                "key", argInfo("bytes", true),
                "old", argInfo("bytes", true),
                "new", argInfo("bytes", true)), List.of("push")));
        commands.put("changesetdata", commandInfo(Map.of(
                "revisions", argInfo("list", true),
                "fields", argInfo("set", false)), List.of("pull")));
        commands.put("manifestdata", commandInfo(Map.of(
                "nodes", argInfo("list", true),
                "haveparents", argInfo("bool", false),
                "fields", argInfo("set", false),
                "tree", argInfo("bytes", true)), List.of("pull")));
        commands.put("filesdata", commandInfo(Map.of(
                "revisions", argInfo("list", true),
                "haveparents", argInfo("bool", false),
                "fields", argInfo("set", false),
                "pathfilter", argInfo("dict", false)), List.of("pull")));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("commands", commands);
        descriptor.put("framingmediatypes", List.of(Wire2Transport.FRAMINGTYPE));
        return descriptor;
    }

    private static Map<String, Object> commandInfo(Map<String, Object> args, List<String> permissions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("args", args);
        m.put("permissions", permissions);
        return m;
    }

    private static Map<String, Object> argInfo(String type, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("required", required);
        return m;
    }

    // ==================== simple commands ====================

    public static List<Object> capabilities() {
        return List.of(namespaceDescriptor());
    }

    public static List<Object> heads(HgRepository repo) throws IOException {
        Revlog changelog = changelog(repo);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return List.of(List.of());
        }
        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
        }
        List<Object> heads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                heads.add(changelog.getIndexRecord(i).getNodeId());
            }
        }
        return List.of(heads);
    }

    public static List<Object> known(HgRepository repo, Map<String, Object> args) throws IOException {
        Revlog changelog = changelog(repo);
        List<Object> nodes = Cbor.asList(args.get("nodes"));
        StringBuilder sb = new StringBuilder();
        for (Object n : nodes) {
            byte[] node = Cbor.asBytes(n);
            boolean isKnown = node != null && changelog.getRevisionCount() > 0 && changelog.findRevision(node) != -1;
            sb.append(isKnown ? '1' : '0');
        }
        return List.of(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    public static List<Object> listkeys(HgRepository repo, Map<String, Object> args) throws IOException {
        String namespace = Cbor.asString(args.get("namespace"));
        Map<String, String> keys = readListKeys(repo, namespace);
        Map<String, Object> result = new LinkedHashMap<>(keys);
        return List.of(result);
    }

    public static List<Object> lookup(HgRepository repo, Map<String, Object> args) throws IOException {
        String key = Cbor.asString(args.get("key"));
        Revlog changelog = changelog(repo);
        byte[] node = NodeIdUtil.resolveRevision(changelog, key);
        if (node == null) {
            throw new HgProtocolException("wireprotov2", "unknown revision: " + key);
        }
        return List.of(Arrays.copyOf(node, 20));
    }

    public static List<Object> pushkey(HgRepository repo, Map<String, Object> args) throws IOException {
        String namespace = Cbor.asString(args.get("namespace"));
        String key = Cbor.asString(args.get("key"));
        String oldVal = Cbor.asString(args.get("old"));
        String newVal = Cbor.asString(args.get("new"));
        boolean ok = applyPushkey(repo, namespace, key, oldVal, newVal);
        return List.of(ok);
    }

    public static List<Object> branchmap(HgRepository repo) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        String branch = repo.getBranch();
        Revlog changelog = changelog(repo);
        if (changelog.getRevisionCount() > 0) {
            List<Object> nodes = new ArrayList<>();
            for (Object o : Cbor.asList(heads(repo).get(0))) {
                nodes.add(o);
            }
            result.put(branch == null || branch.isEmpty() ? "default" : branch, nodes);
        }
        return List.of(result);
    }

    // ==================== changesetdata / manifestdata / filesdata ====================

    public static List<Object> changesetdata(HgRepository repo, Map<String, Object> args) throws IOException {
        Revlog changelog = changelog(repo);
        List<byte[]> outgoing = resolveRevisions(changelog, Cbor.asList(args.get("revisions")));
        Set<String> fields = stringSet(Cbor.asList(args.get("fields")));

        List<Object> result = new ArrayList<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("totalitems", (long) outgoing.size());
        result.add(header);

        Map<String, List<String>> nodeBookmarks = fields.contains("bookmarks") ? bookmarksByNodeHex(repo) : Map.of();

        for (byte[] node : outgoing) {
            int rev = changelog.findRevision(node);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("node", node);

            if (fields.contains("parents")) {
                d.put("parents", parentNodes(changelog, rev));
            }
            if (fields.contains("phase")) {
                d.put("phase", phaseString(repo, changelog, node));
            }
            if (fields.contains("bookmarks")) {
                List<String> marks = nodeBookmarks.get(NodeIdUtil.toHex(node));
                if (marks != null) {
                    d.put("bookmarks", marks);
                }
            }

            List<byte[]> following = new ArrayList<>();
            List<String> followingNames = new ArrayList<>();
            if (fields.contains("revision")) {
                following.add(changelog.getRevisionContent(rev));
                followingNames.add("revision");
            }
            emitRecordWithFollowing(result, d, followingNames, following);
        }
        return result;
    }

    public static List<Object> manifestdata(HgRepository repo, Map<String, Object> args) throws IOException {
        String tree = Cbor.asString(args.get("tree"));
        // A non-empty `tree` selects a treemanifest subdirectory's own submanifest revlog
        // (`meta/<tree>/00manifest.i`) instead of the root `00manifest.i` -- matches real hg's
        // wireprotov2server.py manifestdata command (client side already needs this, see
        // HgRemoteClientV2.getBundle()'s recursive tree fetch, backlog item 20). hg4j's own
        // repositories are always flat (backlog item 8), so this path only activates for a
        // genuine treemanifest repository being served.
        Revlog manifest;
        if (tree == null || tree.isEmpty()) {
            manifest = repo.getManifestRevlog();
        } else {
            File subIdx = new File(repo.getStoreDir(), "meta/" + tree + "/00manifest.i");
            File subDat = new File(repo.getStoreDir(), "meta/" + tree + "/00manifest.d");
            if (!subIdx.isFile()) {
                throw new HgProtocolException("wireprotov2", "unknown tree: " + tree);
            }
            manifest = repo.getRevlog(subIdx, subDat);
        }
        Set<String> fields = stringSet(Cbor.asList(args.get("fields")));
        List<Object> nodesArg = Cbor.asList(args.get("nodes"));

        List<Object> result = new ArrayList<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("totalitems", (long) nodesArg.size());
        result.add(header);

        for (Object n : nodesArg) {
            byte[] node = Cbor.asBytes(n);
            int rev = manifest.findRevision(node);
            if (rev == -1) {
                throw new HgProtocolException("wireprotov2", "unknown node: " + NodeIdUtil.toHex(node));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("node", node);
            if (fields.contains("parents")) {
                d.put("parents", parentNodes(manifest, rev));
            }
            List<byte[]> following = new ArrayList<>();
            List<String> followingNames = new ArrayList<>();
            if (fields.contains("revision")) {
                following.add(manifest.getRevisionContent(rev));
                followingNames.add("revision");
            }
            emitRecordWithFollowing(result, d, followingNames, following);
        }
        return result;
    }

    public static List<Object> filesdata(HgRepository repo, Map<String, Object> args) throws IOException {
        Revlog changelog = changelog(repo);
        List<byte[]> outgoing = resolveRevisions(changelog, Cbor.asList(args.get("revisions")));
        Set<String> fields = stringSet(Cbor.asList(args.get("fields")));

        // path -> (fnode hex -> linknode bytes); order-preserving, matches manifest walk order.
        Map<String, Map<String, byte[]>> fnodes = new TreeMap<>();
        for (byte[] node : outgoing) {
            Map<String, String> manifestEntries = repo.getManifestAtCommit(node);
            for (Map.Entry<String, String> e : manifestEntries.entrySet()) {
                String path = e.getKey();
                String hex = e.getValue();
                String cleanHex = hex.length() > 40 ? hex.substring(0, 40) : hex;
                Map<String, byte[]> byFnode = fnodes.computeIfAbsent(path, k -> new LinkedHashMap<>());
                byFnode.putIfAbsent(cleanHex, node);
            }
        }

        List<Object> result = new ArrayList<>();
        Map<String, Object> header = new LinkedHashMap<>();
        long totalItems = 0;
        for (Map<String, byte[]> v : fnodes.values()) totalItems += v.size();
        header.put("totalpaths", (long) fnodes.size());
        header.put("totalitems", totalItems);
        result.add(header);

        for (Map.Entry<String, Map<String, byte[]>> pathEntry : fnodes.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, byte[]> byFnode = pathEntry.getValue();

            Map<String, Object> pathHeader = new LinkedHashMap<>();
            pathHeader.put("path", path);
            pathHeader.put("totalitems", (long) byFnode.size());
            result.add(pathHeader);

            File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
            if (!flIdx.exists()) {
                continue;
            }
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repo.getRevlog(flIdx, flDat);

            for (Map.Entry<String, byte[]> fe : byFnode.entrySet()) {
                byte[] fnode = NodeIdUtil.fromHex(fe.getKey());
                byte[] linknode = fe.getValue();
                int rev = filelog.findRevision(fnode);
                if (rev == -1) {
                    continue;
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("node", fnode);
                if (fields.contains("parents")) {
                    d.put("parents", parentNodes(filelog, rev));
                }
                if (fields.contains("linknode")) {
                    d.put("linknode", linknode);
                }
                List<byte[]> following = new ArrayList<>();
                List<String> followingNames = new ArrayList<>();
                if (fields.contains("revision")) {
                    following.add(filelog.getRevisionContent(rev));
                    followingNames.add("revision");
                }
                emitRecordWithFollowing(result, d, followingNames, following);
            }
        }
        return result;
    }

    // ==================== helpers ====================

    private static void emitRecordWithFollowing(List<Object> out, Map<String, Object> record,
                                                  List<String> followingNames, List<byte[]> followingBytes) {
        if (!followingBytes.isEmpty()) {
            List<Object> fieldsFollowing = new ArrayList<>();
            for (int i = 0; i < followingBytes.size(); i++) {
                fieldsFollowing.add(List.of(followingNames.get(i), (long) followingBytes.get(i).length));
            }
            record.put("fieldsfollowing", fieldsFollowing);
        }
        out.add(record);
        for (byte[] b : followingBytes) {
            out.add(b);
        }
    }

    private static List<Object> parentNodes(Revlog revlog, int rev) throws IOException {
        Revlog.IndexRecord rec = revlog.getIndexRecord(rev);
        byte[] p1 = rec.getParent1() != -1 ? revlog.getIndexRecord(rec.getParent1()).getNodeId() : NULL_NODE;
        byte[] p2 = rec.getParent2() != -1 ? revlog.getIndexRecord(rec.getParent2()).getNodeId() : NULL_NODE;
        return List.of(p1, p2);
    }

    private static String phaseString(HgRepository repo, Revlog changelog, byte[] node) throws IOException {
        PhaseRoots phaseRoots = repo.getPhaseRoots();
        PhaseRoots.Phase phase = phaseRoots.getPhase(new NodeId(node), changelog);
        switch (phase) {
            case DRAFT:
                return "draft";
            case SECRET:
                return "secret";
            default:
                return "public";
        }
    }

    private static Set<String> stringSet(List<Object> raw) {
        Set<String> result = new LinkedHashSet<>();
        for (Object o : raw) {
            result.add(Cbor.asString(o));
        }
        return result;
    }

    /**
     * Resolves a wire protocol v2 "revisions" specifier list into a topologically sorted list of
     * changeset nodes, matching real hg's {@code resolvenodes}. Supports {@code changesetexplicit}
     * ({@code nodes: [...]}) and {@code changesetdagrange} ({@code roots: [...], heads: [...]}
     * — ancestors of heads excluding ancestors of roots). {@code changesetexplicitdepth} is not
     * implemented (no client here needs it).
     */
    static List<byte[]> resolveRevisions(Revlog changelog, List<Object> specs) throws IOException {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<byte[]> result = new ArrayList<>();

        for (Object specObj : specs) {
            Map<String, Object> spec = Cbor.asMap(specObj);
            String type = Cbor.asString(spec.get("type"));
            if ("changesetexplicit".equals(type)) {
                for (Object n : Cbor.asList(spec.get("nodes"))) {
                    byte[] node = Cbor.asBytes(n);
                    String hex = NodeIdUtil.toHex(node);
                    if (seen.add(hex)) {
                        result.add(node);
                    }
                }
            } else if ("changesetdagrange".equals(type)) {
                List<Object> rootsArg = Cbor.asList(spec.get("roots"));
                List<Object> headsArg = Cbor.asList(spec.get("heads"));
                for (byte[] node : resolveDagRange(changelog, rootsArg, headsArg)) {
                    String hex = NodeIdUtil.toHex(node);
                    if (seen.add(hex)) {
                        result.add(node);
                    }
                }
            } else {
                throw new HgProtocolException("wireprotov2", "unsupported revision specifier type: " + type);
            }
        }
        return result;
    }

    private static List<byte[]> resolveDagRange(Revlog changelog, List<Object> rootsArg, List<Object> headsArg) throws IOException {
        int count = changelog.getRevisionCount();
        boolean[] excluded = new boolean[count];
        for (Object r : rootsArg) {
            byte[] node = Cbor.asBytes(r);
            int rev = node != null ? changelog.findRevision(node) : -1;
            if (rev != -1) {
                excluded[rev] = true;
            }
        }
        for (int i = count - 1; i >= 0; i--) {
            if (excluded[i]) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                if (rec.getParent1() != -1) excluded[rec.getParent1()] = true;
                if (rec.getParent2() != -1) excluded[rec.getParent2()] = true;
            }
        }

        boolean[] included = new boolean[count];
        Deque<Integer> stack = new ArrayDeque<>();
        for (Object h : headsArg) {
            byte[] node = Cbor.asBytes(h);
            int rev = node != null ? changelog.findRevision(node) : -1;
            if (rev != -1) {
                stack.push(rev);
            }
        }
        while (!stack.isEmpty()) {
            int rev = stack.pop();
            if (included[rev] || excluded[rev]) {
                continue;
            }
            included[rev] = true;
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            if (rec.getParent1() != -1) stack.push(rec.getParent1());
            if (rec.getParent2() != -1) stack.push(rec.getParent2());
        }

        List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (included[i]) {
                result.add(changelog.getIndexRecord(i).getNodeId());
            }
        }
        return result;
    }

    private static Map<String, List<String>> bookmarksByNodeHex(HgRepository repo) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : readListKeys(repo, "bookmarks").entrySet()) {
            result.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        return result;
    }

    public static Map<String, String> readListKeys(HgRepository repo, String namespace) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if ("bookmarks".equals(namespace)) {
            File bkFile = new File(repo.getHgDir(), "bookmarks");
            if (bkFile.exists()) {
                for (String line : Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int spaceIdx = line.indexOf(' ');
                    if (spaceIdx != -1) {
                        map.put(line.substring(spaceIdx + 1).trim(), line.substring(0, spaceIdx).trim());
                    }
                }
            }
        } else if ("phases".equals(namespace)) {
            PhaseRoots phaseRoots = repo.getPhaseRoots();
            Revlog changelog = changelog(repo);
            for (int i = 0; i < changelog.getRevisionCount(); i++) {
                byte[] node = changelog.getIndexRecord(i).getNodeId();
                PhaseRoots.Phase phase = phaseRoots.getPhase(new NodeId(node), changelog);
                if (phase != PhaseRoots.Phase.PUBLIC) {
                    map.put(NodeIdUtil.toHex(node), String.valueOf(phase.getValue()));
                }
            }
        }
        return map;
    }

    public static boolean applyPushkey(HgRepository repo, String namespace, String key, String oldVal, String newVal) throws IOException {
        if (!"bookmarks".equals(namespace)) {
            return false;
        }
        Map<String, String> bookmarks = readListKeys(repo, "bookmarks");
        String currentVal = bookmarks.getOrDefault(key, "");
        String old = oldVal != null ? oldVal : "";
        String updated = newVal != null ? newVal : "";
        if (!currentVal.equals(old)) {
            return false;
        }
        if (updated.isEmpty()) {
            bookmarks.remove(key);
        } else {
            bookmarks.put(key, updated);
        }
        File bkFile = new File(repo.getHgDir(), "bookmarks");
        if (bookmarks.isEmpty()) {
            Files.deleteIfExists(bkFile.toPath());
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : bookmarks.entrySet()) {
                sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
            }
            SafeFileIO.writeStringAtomic(bkFile, sb.toString());
        }
        return true;
    }

    public static Revlog changelog(HgRepository repo) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        return repo.getRevlog(clIdx, clDat);
    }
}
