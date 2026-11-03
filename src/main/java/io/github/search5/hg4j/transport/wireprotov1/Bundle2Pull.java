package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The parts of a bundle2 {@code getbundle} answer besides the changegroup, after {@code mercurial/exchange.py} of 7.2.4
 * ({@code _getbundlebookmarkpart}, {@code _getbundlelistkeysparts}, {@code _getbundlephasespart},
 * {@code _getbundletagsfnodes}), in the order the original adds them: bookmarks, listkeys, phase-heads, hgtagsfnodes.
 */
final class Bundle2Pull {

    private Bundle2Pull() {
    }

    /** The {@code key -> values} of the client's {@code bundle2=} capability blob (empty when it sent none). */
    static Map<String, List<String>> clientCaps(List<String> bundleCaps) {
        Map<String, List<String>> caps = new LinkedHashMap<>();
        if (bundleCaps == null) {
            return caps;
        }
        for (String token : bundleCaps) {
            if (token.startsWith("bundle2=")) {
                String blob = URLDecoder.decode(token.substring("bundle2=".length()), StandardCharsets.ISO_8859_1);
                for (String line : blob.split("\n")) {
                    String[] kv = line.split("=", 2);
                    caps.put(kv[0], kv.length > 1 && !kv[1].isEmpty() ? Arrays.asList(kv[1].split(",")) : List.of());
                }
            }
        }
        return caps;
    }

    private static boolean truthy(String value) {
        return value != null && !value.isEmpty() && !"0".equals(value);
    }

    /** True if the request asks for anything this class adds (or switches the changegroup off). */
    static boolean wantsExtraParts(Map<String, String> args, Map<String, List<String>> caps) {
        return truthy(args.get("stream")) || truthy(args.get("bookmarks")) || (args.get("listkeys") != null && !args.get("listkeys").isEmpty())
                || truthy(args.get("phases")) || caps.containsKey("hgtagsfnodes");
    }

    static byte[] addParts(HgRepository repo, byte[] bundle, Map<String, String> args, List<String> heads,
                           Map<String, List<String>> caps) throws IOException {
        Bundle2.Writer extra = new Bundle2.Writer(Bundle2.read(bundle).size());

        if (truthy(args.get("stream"))) {
            // _getbundlestream2: runs before the changegroup part in the original; the client asks for no changegroup (cg=0)
            // together with a stream, so the order in the bundle is the same
            if (!caps.containsKey("stream")) {
                throw new IOException("stream data requested but supported streaming clone versions were not specified");
            }
            if (!caps.get("stream").contains("v2")) {
                throw new IOException("no common supported version with the client: v2; " + String.join(",", caps.get("stream")));
            }
            StreamClone.Stream2 stream = StreamClone.stream2(repo);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("bytecount", String.valueOf(stream.byteCount()));
            params.put("filecount", String.valueOf(stream.fileCount()));
            params.put("requirements", stream.requirements());
            extra.addPart("stream2", true, params, Map.of(), stream.payload());
        }

        if (truthy(args.get("bookmarks"))) {
            if (!caps.containsKey("bookmarks")) {
                throw new IOException("no common bookmarks exchange method");
            }
            byte[] data = encodeBookmarks(RepoSupport.readListKeys(repo, "bookmarks"));
            if (data.length > 0) {
                extra.addPart("bookmarks", true, Map.of(), Map.of(), data);
            }
        }
        String listkeys = args.get("listkeys");
        if (listkeys != null && !listkeys.isEmpty()) {
            for (String namespace : listkeys.split(",")) {
                byte[] data = Wire1Commands.listkeys(repo, Map.of("namespace", namespace)).getPayload();
                extra.addPart("listkeys", true, Map.of("namespace", namespace), Map.of(), data);
            }
        }
        if (truthy(args.get("phases"))) {
            if (!caps.containsKey("phases") || !caps.get("phases").contains("heads")) {
                throw new IOException("no common phases exchange method");
            }
            extra.addPart("phase-heads", true, Map.of(), Map.of(), phaseHeads(repo, heads));
        }
        if (caps.containsKey("hgtagsfnodes") && (args.get("cg") == null || truthy(args.get("cg")))) {
            byte[] data = tagsFnodes(repo, heads);
            if (data.length > 0) {
                extra.addPart("hgtagsfnodes", false, Map.of(), Map.of(), data);
            }
        }
        return Bundle2.appendParts(bundle, extra);
    }

    /** {@code bookmarks.binaryencode}: {@code >20sH} and the name, for each bookmark in file order. */
    static byte[] encodeBookmarks(Map<String, String> bookmarks) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> e : bookmarks.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(NodeIdUtil.fromHex(e.getValue()));
            out.writeBytes(ByteBuffer.allocate(2).putShort((short) name.length).array());
            out.writeBytes(name);
        }
        return out.toByteArray();
    }

    /** {@code phases.binaryencode} of the {@code phase -> sorted heads} mapping built the way {@code _getbundlephasespart} does. */
    static byte[] phaseHeads(HgRepository repo, List<String> requestedHeads) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        List<String> heads = requestedHeads.isEmpty() ? new ArrayList<>(new HgLocalClient(repo).getHeads()) : requestedHeads;
        List<TreeSet<String>> byPhase = List.of(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        if (RepoSupport.isPublishing(repo)) {
            byPhase.get(0).addAll(heads);
        } else {
            PhaseRoots roots = repo.getPhaseRoots();
            for (String head : heads) {
                byPhase.get(roots.getPhase(new NodeId(NodeIdUtil.fromHex(head)), changelog).getValue()).add(head);
            }
            if (!byPhase.get(1).isEmpty()) {
                // heads(only(draftheads, publicheads) and public()): the public changesets reachable from the draft heads but
                // not from the public ones, minus those that have a child in that set
                BitSet viaDraft = ancestors(changelog, byPhase.get(1));
                BitSet viaPublic = ancestors(changelog, byPhase.get(0));
                BitSet only = (BitSet) viaDraft.clone();
                only.andNot(viaPublic);
                BitSet candidates = new BitSet();
                for (int r = only.nextSetBit(0); r >= 0; r = only.nextSetBit(r + 1)) {
                    byte[] node = changelog.getIndexRecord(r).getNodeId();
                    if (roots.getPhase(new NodeId(node), changelog).getValue() == 0) {
                        candidates.set(r);
                    }
                }
                BitSet hasChildInSet = new BitSet();
                for (int r = candidates.nextSetBit(0); r >= 0; r = candidates.nextSetBit(r + 1)) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    if (rec.getParent1() >= 0) hasChildInSet.set(rec.getParent1());
                    if (rec.getParent2() >= 0) hasChildInSet.set(rec.getParent2());
                }
                for (int r = candidates.nextSetBit(0); r >= 0; r = candidates.nextSetBit(r + 1)) {
                    if (!hasChildInSet.get(r)) {
                        byPhase.get(0).add(NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId()));
                    }
                }
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int phase = 0; phase <= 2; phase++) {
            // sorted by the binary node, which is the order of the hex strings
            for (String head : byPhase.get(phase)) {
                out.writeBytes(ByteBuffer.allocate(4).putInt(phase).array());
                out.writeBytes(NodeIdUtil.fromHex(head));
            }
        }
        return out.toByteArray();
    }

    private static BitSet ancestors(Revlog changelog, Iterable<String> heads) throws IOException {
        BitSet seen = new BitSet();
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        for (String head : heads) {
            int rev = changelog.findRevision(NodeIdUtil.fromHex(head));
            if (rev >= 0 && !seen.get(rev)) {
                seen.set(rev);
                work.add(rev);
            }
        }
        while (!work.isEmpty()) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(work.poll());
            for (int parent : new int[]{rec.getParent1(), rec.getParent2()}) {
                if (parent >= 0 && !seen.get(parent)) {
                    seen.set(parent);
                    work.add(parent);
                }
            }
        }
        return seen;
    }

    /** {@code repo.heads()}: the heads, newest revision first. */
    private static List<String> headsNewestFirst(Revlog changelog) {
        int[] revs = HeadsBuckets.headRevisions(changelog, changelog.getRevisionCount());
        List<String> heads = new ArrayList<>();
        for (int i = revs.length - 1; i >= 0; i--) {
            heads.add(NodeIdUtil.toHex(changelog.getIndexRecord(revs[i]).getNodeId()));
        }
        return heads;
    }

    /**
     * {@code addparttagsfnodescache}: pairs of a head's node and the {@code .hgtags} file node cached for it in
     * {@code .hg/cache/hgtagsfnodes1}, only for heads that have a valid cache entry (nothing is computed, like the original).
     */
    static byte[] tagsFnodes(HgRepository repo, List<String> requestedHeads) throws IOException {
        File cache = new File(repo.getHgDir(), "cache/hgtagsfnodes1");
        if (!cache.isFile()) {
            return new byte[0];
        }
        byte[] raw = Files.readAllBytes(cache.toPath());
        Revlog changelog = RepoSupport.changelog(repo);
        List<String> heads = requestedHeads.isEmpty() ? headsNewestFirst(changelog) : requestedHeads;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String head : heads) {
            byte[] node = NodeIdUtil.fromHex(head);
            int rev = changelog.findRevision(node);
            if (rev < 0) {
                continue;
            }
            int offset = rev * 24;
            if (offset + 24 > raw.length) {
                continue;
            }
            byte[] record = Arrays.copyOfRange(raw, offset, offset + 24);
            boolean missing = true;
            for (byte b : record) {
                if (b != (byte) 0xFF) {
                    missing = false;
                    break;
                }
            }
            if (missing || !Arrays.equals(Arrays.copyOfRange(record, 0, 4), Arrays.copyOfRange(node, 0, 4))) {
                continue;
            }
            out.writeBytes(node);
            out.writeBytes(Arrays.copyOfRange(record, 4, 24));
        }
        return out.toByteArray();
    }
}
