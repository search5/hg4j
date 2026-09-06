package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Transport;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Real hg wireprotocol v2 client: capability discovery via the {@code X-HgUpgrade-1}/
 * {@code X-HgProto-1} handshake, then per-command execution over the frame-based
 * {@code application/mercurial-exp-framing-0006} transport at
 * {@code <apibase><namespace>/<ro|rw>/<command>}. Verified end-to-end against a real Mercurial
 * 6.0 server (the last release with a working v2 implementation — removed entirely in 6.1) using
 * {@code capabilities}/{@code heads}/{@code known}/{@code listkeys}/{@code lookup}/
 * {@code pushkey}/{@code branchmap}/{@code changesetdata}/{@code manifestdata}/{@code filesdata}.
 *
 * <p>Real v2 has no changegroup/getbundle/unbundle-style bulk-transfer or push command at all —
 * it is (and, as actually shipped, always was) a read-only, per-object protocol. {@link #getBundle}
 * therefore reconstructs an equivalent {@code HG10UN} changegroup byte stream — the same format
 * {@link HgLocalClient#getBundle} produces — from the {@code changesetdata}/{@code manifestdata}/
 * {@code filesdata} primitives, so the rest of hg4j's pull pipeline
 * ({@link io.github.search5.hg4j.bundle.ChangegroupParser}, {@code FetchCommand}) works
 * unchanged regardless of which wire protocol version fetched the data. {@link #push} always
 * fails — there is nothing on a real v2 server it could call.</p>
 */
public class HgRemoteClientV2 implements HgRemoteConnection {

    private final String baseUrl;
    private int connectTimeout = 10000;
    private int readTimeout = 30000;
    private String username;
    private String password;
    private boolean forceTls = false;
    private Proxy proxy = Proxy.NO_PROXY;

    private String apibase;
    private String namespace;
    private int requestIdCounter = 1;

    public HgRemoteClientV2(String url) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Injects an apibase/namespace already discovered by a caller (e.g. {@link HgRemoteClient}'s
     * own v1-capabilities-request-turned-upgrade-handshake) so {@link #ensureDiscovered()} skips
     * a redundant second discovery round-trip.
     */
    void primeDiscovery(String apibase, String namespace) {
        this.apibase = apibase;
        this.namespace = namespace;
    }

    public void setTimeouts(int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public void setCredentialsProvider(CredentialsProvider provider) {
        if (provider != null) {
            CredentialItem.Username u = new CredentialItem.Username();
            CredentialItem.Password p = new CredentialItem.Password();
            if (provider.get(this.baseUrl, u, p)) {
                setCredentials(u.getValue(), p.getValue() != null ? new String(p.getValue()) : null);
            }
        }
    }

    public void setForceTls(boolean forceTls) {
        this.forceTls = forceTls;
    }

    public void setProxy(Proxy proxy) {
        if (proxy != null) {
            this.proxy = proxy;
        }
    }

    private synchronized int nextRequestId() {
        int id = requestIdCounter;
        requestIdCounter = (requestIdCounter % 0xFFFF) + 1;
        return id;
    }

    /**
     * Performs the real hg capability-discovery handshake: {@code GET <baseUrl>/?cmd=capabilities}
     * with {@code X-HgUpgrade-1: exp-http-v2-0003} and {@code X-HgProto-1: cbor}. The server
     * responds (if {@code experimental.web.apiserver} is enabled) with
     * {@code {apibase, apis: {<namespace>: {...}}, v1capabilities}} — real hg's actual shape,
     * not the flat {@code {commands: {...}}} this class used before it was verified against a
     * live server.
     */
    private synchronized void ensureDiscovered() throws IOException {
        if (namespace != null) {
            return;
        }
        String url = baseUrl + "/?cmd=capabilities";
        HttpURLConnection conn = openConnection(url, "GET");
        conn.setRequestProperty("X-HgUpgrade-1", Wire2Commands.NAMESPACE);
        conn.setRequestProperty("X-HgProto-1", "cbor");

        byte[] body = readResponseBody(conn, url);
        List<Object> objs = Cbor.decodeAll(body);
        if (objs.isEmpty()) {
            throw new HgProtocolException(url, "Empty capabilities discovery response");
        }
        Map<String, Object> descriptor = Cbor.asMap(objs.get(0));
        String discoveredApibase = Cbor.asString(descriptor.get("apibase"));
        Map<String, Object> apis = Cbor.asMap(descriptor.get("apis"));
        if (discoveredApibase == null || !apis.containsKey(Wire2Commands.NAMESPACE)) {
            throw new HgProtocolException(url, "Remote server does not advertise wireprotocol v2 (" + Wire2Commands.NAMESPACE + ")");
        }
        this.apibase = discoveredApibase;
        this.namespace = Wire2Commands.NAMESPACE;
    }

    private List<Object> executeCommand(String command, Map<String, Object> args, String permission) throws IOException {
        ensureDiscovered();
        String url = baseUrl + "/" + apibase + namespace + "/" + permission + "/" + command;
        byte[] requestBody = Wire2Transport.buildCommandRequest(nextRequestId(), command, args);

        HttpURLConnection conn = openConnection(url, "POST");
        conn.setRequestProperty("Accept", Wire2Transport.FRAMINGTYPE);
        conn.setRequestProperty("Content-Type", Wire2Transport.FRAMINGTYPE);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody);
        }

        byte[] responseBody = readResponseBody(conn, url);
        return Wire2Transport.readCommandResponse(Wire2Transport.toStream(responseBody));
    }

    private HttpURLConnection openConnection(String url, String method) throws IOException {
        if (forceTls && !url.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + url);
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection(proxy);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }
        return conn;
    }

    private byte[] readResponseBody(HttpURLConnection conn, String url) throws IOException {
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new HgAuthException(url, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new HgProtocolException(url, "Remote server returned HTTP " + status + " for URL: " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        List<Object> resp = executeCommand("capabilities", Map.of(), "ro");
        List<String> caps = new ArrayList<>();
        if (!resp.isEmpty()) {
            Map<String, Object> descriptor = Cbor.asMap(resp.get(0));
            Map<String, Object> commands = Cbor.asMap(descriptor.get("commands"));
            caps.addAll(commands.keySet());
        }
        return caps;
    }

    @Override
    public List<String> getHeads() throws IOException {
        List<Object> resp = executeCommand("heads", Map.of(), "ro");
        List<String> heads = new ArrayList<>();
        if (!resp.isEmpty()) {
            for (Object n : Cbor.asList(resp.get(0))) {
                byte[] node = Cbor.asBytes(n);
                if (node != null) {
                    heads.add(NodeIdUtil.toHex(node));
                }
            }
        }
        return heads;
    }

    @Override
    public String known(List<String> nodes) throws IOException {
        List<Object> nodeBytes = new ArrayList<>();
        if (nodes != null) {
            for (String hex : nodes) {
                nodeBytes.add(NodeIdUtil.fromHex(hex));
            }
        }
        List<Object> resp = executeCommand("known", Map.of("nodes", nodeBytes), "ro");
        if (resp.isEmpty()) {
            return "";
        }
        byte[] result = Cbor.asBytes(resp.get(0));
        return result != null ? new String(result, StandardCharsets.US_ASCII) : "";
    }

    @Override
    public Map<String, String> listKeys(String namespace) throws IOException {
        List<Object> resp = executeCommand("listkeys", Map.of("namespace", namespace), "ro");
        Map<String, String> result = new LinkedHashMap<>();
        if (!resp.isEmpty()) {
            for (Map.Entry<String, Object> e : Cbor.asMap(resp.get(0)).entrySet()) {
                result.put(e.getKey(), Cbor.asString(e.getValue()));
            }
        }
        return result;
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("namespace", namespace);
        args.put("key", key);
        args.put("old", oldVal != null ? oldVal : "");
        args.put("new", newVal != null ? newVal : "");
        List<Object> resp = executeCommand("pushkey", args, "rw");
        return !resp.isEmpty() && Boolean.TRUE.equals(resp.get(0));
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        return getBundle(roots, null, null);
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        List<String> targetHeads = (heads != null && !heads.isEmpty()) ? heads : getHeads();
        List<Object> rootBytes = new ArrayList<>();
        if (common != null) {
            for (String c : common) {
                if (c != null && !NULL_HEX.equals(c)) {
                    rootBytes.add(NodeIdUtil.fromHex(c));
                }
            }
        }
        List<Object> headBytes = new ArrayList<>();
        for (String h : targetHeads) {
            headBytes.add(NodeIdUtil.fromHex(h));
        }
        if (headBytes.isEmpty()) {
            return new byte[0];
        }

        Map<String, Object> dagRangeSpec = new LinkedHashMap<>();
        dagRangeSpec.put("type", "changesetdagrange");
        dagRangeSpec.put("roots", rootBytes);
        dagRangeSpec.put("heads", headBytes);
        List<Object> revisions = List.of(dagRangeSpec);

        Map<String, Object> csArgs = new LinkedHashMap<>();
        csArgs.put("revisions", revisions);
        csArgs.put("fields", List.of("parents", "revision"));
        List<Object> csResp = executeCommand("changesetdata", csArgs, "ro");
        if (csResp.isEmpty()) {
            return new byte[0];
        }
        List<Map<String, Object>> csRecords = Wire2Transport.decodeRecordsWithFollowing(csResp.subList(1, csResp.size()), null);
        if (csRecords.isEmpty()) {
            return new byte[0];
        }

        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        List<byte[]> manifestNodesInOrder = new ArrayList<>();
        LinkedHashSet<String> manifestNodeHexes = new LinkedHashSet<>();
        // manifest node hex -> the changeset node that references it (real hg's "linknode" for a
        // manifest entry — NOT the manifest's own node; matches HgLocalClient.getBundle, where
        // mfEntry.cs is the referencing changelog node, since a manifest revlog's link points at
        // the changelog, unlike the changelog itself which links to its own node).
        Map<String, byte[]> manifestLinkNode = new LinkedHashMap<>();
        LinkedHashSet<String> touchedPaths = new LinkedHashSet<>();

        // Incremental pull (rootBytes non-empty): the changesetdagrange query above deliberately
        // excludes ancestors of `common` (real hg's discovery.outgoing() semantics, see
        // resolvenodes()/changesetdagrange in mercurial/wireprotov2server.py), so csRecords starts
        // with the FIRST genuinely new changeset. But the receiving side
        // (Revlog.appendChangeGroupEntry's cg1 branch) decodes each new revlog entry's delta
        // purely positionally -- against whatever already physically occupies the immediately
        // preceding slot in that same revlog (matching real hg's cg1 packer,
        // forcedeltaparentprev=True) -- and for the very first new changelog/manifest/file entry
        // of an incremental pull, that preceding slot holds the common root the client already
        // has. Seeding the delta chains below with an empty array (as before) made the very first
        // decoded delta produce garbage on any second/incremental pull, tripping the SHA-1
        // node-hash check in Revlog.appendChangeGroupEntry with HgCorruptDataException. Fetch that
        // root's own full text for the changelog, its manifest, and every file it already tracks
        // straight from the server instead -- since it's common, both sides already agree on its
        // bytes and hash -- and use those as the seeds.
        byte[] prevClContent = new byte[0];
        byte[] rootManifestSeed = new byte[0];
        Map<String, byte[]> rootFileContentByPath = new LinkedHashMap<>();
        if (!rootBytes.isEmpty()) {
            byte[] rootNode = (byte[]) rootBytes.get(0);
            Map<String, Object> rootSpec = new LinkedHashMap<>();
            rootSpec.put("type", "changesetexplicit");
            rootSpec.put("nodes", List.of((Object) rootNode));
            List<Object> rootRevisions = List.of(rootSpec);

            Map<String, Object> rootCsArgs = new LinkedHashMap<>();
            rootCsArgs.put("revisions", rootRevisions);
            rootCsArgs.put("fields", List.of("revision"));
            List<Object> rootCsResp = executeCommand("changesetdata", rootCsArgs, "ro");
            List<Map<String, Object>> rootCsRecords = rootCsResp.isEmpty() ? List.of()
                    : Wire2Transport.decodeRecordsWithFollowing(rootCsResp.subList(1, rootCsResp.size()), null);
            byte[] rootClText = rootCsRecords.isEmpty() ? null : Cbor.asBytes(rootCsRecords.get(0).get("revision"));
            if (rootClText == null) {
                throw new HgProtocolException("wireprotov2", "could not fetch fulltext of common root "
                        + NodeIdUtil.toHex(rootNode) + " needed to seed incremental changegroup delta chain");
            }
            prevClContent = rootClText;

            String rootClAsText = new String(rootClText, StandardCharsets.UTF_8);
            int nl = rootClAsText.indexOf('\n');
            if (nl >= 40) {
                byte[] rootManifestNode = NodeIdUtil.fromHex(rootClAsText.substring(0, 40));
                Map<String, Object> rootMfArgs = new LinkedHashMap<>();
                rootMfArgs.put("nodes", List.of((Object) rootManifestNode));
                rootMfArgs.put("fields", List.of("revision"));
                rootMfArgs.put("tree", "");
                List<Object> rootMfResp = executeCommand("manifestdata", rootMfArgs, "ro");
                List<Map<String, Object>> rootMfRecords = rootMfResp.isEmpty() ? List.of()
                        : Wire2Transport.decodeRecordsWithFollowing(rootMfResp.subList(1, rootMfResp.size()), null);
                if (!rootMfRecords.isEmpty()) {
                    byte[] rmf = Cbor.asBytes(rootMfRecords.get(0).get("revision"));
                    if (rmf != null) {
                        rootManifestSeed = rmf;
                    }
                }
            }

            Map<String, Object> rootFilesArgs = new LinkedHashMap<>();
            rootFilesArgs.put("revisions", rootRevisions);
            rootFilesArgs.put("haveparents", false);
            rootFilesArgs.put("fields", List.of("revision"));
            List<Object> rootFilesResp = executeCommand("filesdata", rootFilesArgs, "ro");
            if (!rootFilesResp.isEmpty()) {
                List<Map<String, Object>> rootFilesRecords = Wire2Transport.decodeRecordsWithFollowing(
                        rootFilesResp.subList(1, rootFilesResp.size()), r -> r.containsKey("path") && !r.containsKey("node"));
                String currentPath = null;
                for (Map<String, Object> rec : rootFilesRecords) {
                    if (rec.containsKey("path") && !rec.containsKey("node")) {
                        currentPath = Cbor.asString(rec.get("path"));
                        continue;
                    }
                    if (currentPath == null) {
                        continue;
                    }
                    byte[] revisionText = Cbor.asBytes(rec.get("revision"));
                    if (revisionText != null) {
                        rootFileContentByPath.put(currentPath, revisionText);
                    }
                }
            }
        }
        for (Map<String, Object> rec : csRecords) {
            byte[] node = Cbor.asBytes(rec.get("node"));
            List<Object> parents = Cbor.asList(rec.get("parents"));
            byte[] revisionText = Cbor.asBytes(rec.get("revision"));

            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = node;
            entry.p1 = parents.size() > 0 ? Cbor.asBytes(parents.get(0)) : new byte[20];
            entry.p2 = parents.size() > 1 ? Cbor.asBytes(parents.get(1)) : new byte[20];
            entry.cs = node;
            entry.delta = Revlog.createDelta(prevClContent, revisionText);
            bundle.changelogEntries.add(entry);
            prevClContent = revisionText;

            String text = new String(revisionText, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            if (lines.length > 0 && lines[0].length() >= 40) {
                String manifestHex = lines[0].substring(0, 40);
                manifestLinkNode.putIfAbsent(manifestHex, node);
                if (manifestNodeHexes.add(manifestHex)) {
                    manifestNodesInOrder.add(NodeIdUtil.fromHex(manifestHex));
                }
            }
            for (int i = 3; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) break;
                touchedPaths.add(line);
            }
        }

        boolean hasTreeManifest = false;
        if (!manifestNodesInOrder.isEmpty()) {
            List<ChangegroupParser.ChangeGroupEntry> rootEntries = new ArrayList<>();
            Map<String, byte[]> fullTextByNodeHex = new LinkedHashMap<>();
            byte[] prevMfContent = rootManifestSeed;
            // dir path (bare, no trailing slash; e.g. "sub" or "sub/deep") -> its distinct node
            // hexes referenced from any root manifest revision fetched this pull, in first-seen
            // order (each root manifest revision's 't'-flagged entries name that subdirectory's
            // OWN submanifest revision at that point in history -- exactly mirroring how
            // manifestNodesInOrder itself was seeded from each changelog revision's own manifest
            // reference above).
            Map<String, LinkedHashSet<String>> subdirNodeHexesInOrder = new LinkedHashMap<>();

            List<Map<String, Object>> mfArgsResults = fetchManifestData(manifestNodesInOrder, "", List.of("parents", "revision"));
            for (Map<String, Object> rec : mfArgsResults) {
                byte[] node = Cbor.asBytes(rec.get("node"));
                List<Object> parents = Cbor.asList(rec.get("parents"));
                byte[] revisionText = resolveFullText(rec, fullTextByNodeHex);
                fullTextByNodeHex.put(NodeIdUtil.toHex(node), revisionText);

                ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
                entry.node = node;
                entry.p1 = parents.size() > 0 ? Cbor.asBytes(parents.get(0)) : new byte[20];
                entry.p2 = parents.size() > 1 ? Cbor.asBytes(parents.get(1)) : new byte[20];
                entry.cs = manifestLinkNode.get(NodeIdUtil.toHex(node));
                entry.delta = Revlog.createDelta(prevMfContent, revisionText);
                rootEntries.add(entry);
                prevMfContent = revisionText;

                for (ManifestTreeIterator.Entry me : ManifestTreeIterator.parseManifestContent(revisionText)) {
                    if (me.isTreeDir()) {
                        String childHex = NodeIdUtil.toHex(me.getNodeId());
                        subdirNodeHexesInOrder.computeIfAbsent(me.getPath(), k -> new LinkedHashSet<>()).add(childHex);
                        // A subdirectory revision's own linknode isn't separately exposed by the
                        // wire2 manifestdata response -- approximate it with whichever changeset's
                        // root manifest entry first pointed at it (same real hg semantics: the
                        // linkrev is "the first changeset for which this content is correct",
                        // which for cg1-style positional decoding on the receiving end
                        // (Revlog.appendChangeGroupEntry) only needs to resolve to *some* valid
                        // changelog revision at or after this content's introduction).
                        manifestLinkNode.putIfAbsent(childHex, entry.cs);
                    }
                }
            }

            if (subdirNodeHexesInOrder.isEmpty()) {
                bundle.manifestEntries.addAll(rootEntries);
            } else {
                // Real Mercurial's manifestdata/tree wireprotocol v2 command lets a treemanifest
                // server hand back any subdirectory's own submanifest revlog history -- fetch each
                // one discovered (breadth-first: a subdirectory's own content can itself reference
                // further-nested subdirectories) and assemble the cg3/cg4/cg5-capable
                // `manifestGroups` envelope instead of the flat `manifestEntries` list, matching
                // real hg's own on-the-wire representation for treemanifest changegroups
                // (mercurial/changegroup.py generatemanifests()). hg4j's own repositories are
                // always flat (see backlog item 8), so this path only ever activates when pulling
                // from a genuine third-party treemanifest server -- there is no seeding from a
                // pull's common root for these per-directory delta chains (unlike the root
                // changelog/manifest/files paths above): a subdirectory being incrementally
                // extended still gets a full from-empty delta chain for its own history, which is
                // correct (not wrong bytes) but not maximally efficient. Documented, not fixed --
                // see backlog item 20 in mercurial-spec-compliance-requirement.md.
                hasTreeManifest = true;
                bundle.manifestGroups = new ArrayList<>();
                ChangegroupParser.ManifestGroup rootGroup = new ChangegroupParser.ManifestGroup();
                rootGroup.path = "";
                rootGroup.entries = rootEntries;
                bundle.manifestGroups.add(rootGroup);

                ArrayDeque<String> queue = new ArrayDeque<>(subdirNodeHexesInOrder.keySet());
                Set<String> queued = new HashSet<>(subdirNodeHexesInOrder.keySet());
                while (!queue.isEmpty()) {
                    String dir = queue.poll();
                    List<Object> dirNodes = new ArrayList<>();
                    for (String hex : subdirNodeHexesInOrder.get(dir)) {
                        dirNodes.add(NodeIdUtil.fromHex(hex));
                    }
                    List<Map<String, Object>> dirRecords = fetchManifestData(dirNodes, dir, List.of("parents", "revision"));

                    ChangegroupParser.ManifestGroup mg = new ChangegroupParser.ManifestGroup();
                    mg.path = dir;
                    mg.entries = new ArrayList<>();
                    Map<String, byte[]> dirFullTextByNodeHex = new LinkedHashMap<>();
                    byte[] prevDirContent = new byte[0];
                    for (Map<String, Object> rec : dirRecords) {
                        byte[] node = Cbor.asBytes(rec.get("node"));
                        List<Object> parents = Cbor.asList(rec.get("parents"));
                        byte[] revisionText = resolveFullText(rec, dirFullTextByNodeHex);
                        dirFullTextByNodeHex.put(NodeIdUtil.toHex(node), revisionText);

                        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
                        entry.node = node;
                        entry.p1 = parents.size() > 0 ? Cbor.asBytes(parents.get(0)) : new byte[20];
                        entry.p2 = parents.size() > 1 ? Cbor.asBytes(parents.get(1)) : new byte[20];
                        entry.cs = manifestLinkNode.get(NodeIdUtil.toHex(node)); // see linknode note above -- approximated the same way for every nesting level
                        entry.delta = Revlog.createDelta(prevDirContent, revisionText);
                        mg.entries.add(entry);
                        prevDirContent = revisionText;

                        for (ManifestTreeIterator.Entry me : ManifestTreeIterator.parseManifestContent(revisionText)) {
                            if (me.isTreeDir()) {
                                String childDir = dir + "/" + me.getPath();
                                String childHex = NodeIdUtil.toHex(me.getNodeId());
                                subdirNodeHexesInOrder.computeIfAbsent(childDir, k -> new LinkedHashSet<>()).add(childHex);
                                manifestLinkNode.putIfAbsent(childHex, entry.cs);
                                if (queued.add(childDir)) {
                                    queue.add(childDir);
                                }
                            }
                        }
                    }
                    bundle.manifestGroups.add(mg);
                }
            }
        }

        if (!touchedPaths.isEmpty()) {
            Map<String, Object> filesArgs = new LinkedHashMap<>();
            filesArgs.put("revisions", revisions);
            filesArgs.put("haveparents", false);
            filesArgs.put("fields", List.of("parents", "linknode", "revision"));
            List<Object> filesResp = executeCommand("filesdata", filesArgs, "ro");
            List<Map<String, Object>> filesRecords = Wire2Transport.decodeRecordsWithFollowing(
                    filesResp.subList(1, filesResp.size()), r -> r.containsKey("path") && !r.containsKey("node"));

            ChangegroupParser.FileGroup currentGroup = null;
            byte[] prevFlContent = new byte[0];
            Map<String, byte[]> fullTextByNodeHex = new LinkedHashMap<>();
            for (Map<String, Object> rec : filesRecords) {
                if (rec.containsKey("path") && !rec.containsKey("node")) {
                    if (currentGroup != null) {
                        bundle.fileGroups.add(currentGroup);
                    }
                    currentGroup = new ChangegroupParser.FileGroup();
                    currentGroup.path = Cbor.asString(rec.get("path"));
                    currentGroup.entries = new ArrayList<>();
                    // Incremental pull: if this file already had a revision as of the common root,
                    // seed with that fulltext (fetched above into rootFileContentByPath) instead of
                    // an empty array -- same positional cg1 delta-base rule as the changelog/
                    // manifest seeding above. A path absent from that map is genuinely new as of
                    // this pull, so an empty array is correct for it.
                    prevFlContent = rootFileContentByPath.getOrDefault(currentGroup.path, new byte[0]);
                    fullTextByNodeHex.clear(); // delta bases are scoped to a single file's revlog
                    continue;
                }
                if (currentGroup == null) {
                    continue;
                }
                byte[] node = Cbor.asBytes(rec.get("node"));
                List<Object> parents = Cbor.asList(rec.get("parents"));
                byte[] linknode = Cbor.asBytes(rec.get("linknode"));
                byte[] revisionText = resolveFullText(rec, fullTextByNodeHex);
                fullTextByNodeHex.put(NodeIdUtil.toHex(node), revisionText);

                ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
                entry.node = node;
                entry.p1 = parents.size() > 0 ? Cbor.asBytes(parents.get(0)) : new byte[20];
                entry.p2 = parents.size() > 1 ? Cbor.asBytes(parents.get(1)) : new byte[20];
                entry.cs = linknode != null ? linknode : node;
                entry.delta = Revlog.createDelta(prevFlContent, revisionText);
                currentGroup.entries.add(entry);
                prevFlContent = revisionText;
            }
            if (currentGroup != null) {
                bundle.fileGroups.add(currentGroup);
            }
        }

        if (!hasTreeManifest) {
            return serializeHg10un(bundle);
        }
        // A treemanifest changegroup needs the tree-capable envelope (root manifest group +
        // per-directory subgroups, see `manifestGroups` above) -- cg1 (HG10UN) has no such
        // envelope at all, so switch to cg4 (already implemented by
        // ChangegroupParser.writeBundle) wrapped in a minimal bundle2 (HG20) container (real hg's
        // legacy bundle1/HG10 wrapper is definitionally cg1-only), matching the wrapping already
        // used elsewhere for cg2+ changegroups (Bundle2Parser.wrapChangegroupInBundle2).
        ByteArrayOutputStream cg4Out = new ByteArrayOutputStream();
        ChangegroupParser.writeBundle(cg4Out, bundle, "04");
        return Bundle2Parser.wrapChangegroupInBundle2(cg4Out.toByteArray(), "04");
    }

    /**
     * Issues one {@code manifestdata} wire2 command for the given node list against the manifest
     * revlog rooted at {@code tree} ({@code ""} for the top-level {@code 00manifest.i}, or a bare
     * repo-root-relative directory path for a treemanifest subdirectory's own submanifest revlog,
     * e.g. {@code "sub"} or {@code "sub/deep"} -- no trailing slash, matching the on-the-wire
     * {@code t}-flagged manifest entry path convention already used for local tree reading in
     * {@link ManifestTreeIterator}) and decodes the response into per-record maps.
     */
    private List<Map<String, Object>> fetchManifestData(List<?> nodes, String tree, List<String> fields) throws IOException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("nodes", nodes);
        args.put("fields", fields);
        args.put("tree", tree);
        List<Object> resp = executeCommand("manifestdata", args, "ro");
        if (resp.isEmpty()) {
            return List.of();
        }
        return Wire2Transport.decodeRecordsWithFollowing(resp.subList(1, resp.size()), null);
    }

    /**
     * Resolves a {@code manifestdata}/{@code filesdata} record's revision content. Real hg's
     * {@code emitrevisions} storage-layer helper is free to send either the full text (under the
     * {@code revision} field) or, for storage efficiency, a delta against another node already
     * in this same response batch (under {@code delta}, with the base node in
     * {@code deltabasenode}) — {@code changesetdata} never does this (changelog revisions are
     * always sent in full), but manifest/file revisions routinely do. {@code fullTextByNodeHex}
     * accumulates every already-resolved full text in this batch so a delta's base can be found.
     */
    private static byte[] resolveFullText(Map<String, Object> rec, Map<String, byte[]> fullTextByNodeHex) throws IOException {
        byte[] revision = Cbor.asBytes(rec.get("revision"));
        if (revision != null) {
            return revision;
        }
        byte[] delta = Cbor.asBytes(rec.get("delta"));
        byte[] baseNode = Cbor.asBytes(rec.get("deltabasenode"));
        if (delta == null || baseNode == null) {
            throw new HgProtocolException("wireprotov2", "revision record has neither 'revision' nor 'delta'+'deltabasenode': " + rec.keySet());
        }
        byte[] baseText = fullTextByNodeHex.get(NodeIdUtil.toHex(baseNode));
        if (baseText == null) {
            throw new HgProtocolException("wireprotov2", "delta base " + NodeIdUtil.toHex(baseNode) + " not found among already-fetched revisions in this batch");
        }
        return Revlog.applyDelta(baseText, delta);
    }

    private static final String NULL_HEX = "0000000000000000000000000000000000000000";

    private static byte[] serializeHg10un(ChangegroupParser.ChangegroupBundle bundle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                writeEntryChunk(dos, entry);
            }
            writeTerminalChunk(dos);
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                writeEntryChunk(dos, entry);
            }
            writeTerminalChunk(dos);
            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                writePathChunk(dos, fg.path);
                for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);
            }
            writeTerminalChunk(dos);
        }
        return baos.toByteArray();
    }

    private static void writeEntryChunk(DataOutputStream dos, ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private static void writePathChunk(DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(4 + pathBytes.length);
        dos.write(pathBytes);
    }

    private static void writeTerminalChunk(DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        throw new HgProtocolException(baseUrl,
                "Real Mercurial wireprotocol v2 has no push/unbundle command (it is read-only, "
                        + "as actually shipped through Mercurial 6.0 before the protocol was removed); "
                        + "use a v1 (bundle2) remote to push.");
    }

    @Override
    public void close() throws IOException {
        // HTTP connections managed per-request; nothing to release.
    }
}
