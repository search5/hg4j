package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.errors.HgPushRacedException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server side of a bundle2 {@code unbundle} (a push), after {@code mercurial/bundle2.py} and
 * {@code mercurial/bundle2_part_handlers.py} of 7.2.4.
 *
 * <p>Parts understood: {@code replycaps}, {@code check:heads}, {@code check:updated-heads}, {@code check:bookmarks},
 * {@code check:phases}, {@code changegroup}, {@code pushkey}, {@code bookmarks}, {@code phase-heads}; the advisory
 * {@code output}, {@code pushvars}, {@code hgtagsfnodes} and {@code cache:rev-branch-cache} are accepted and ignored. A
 * <em>mandatory</em> part of any other type is refused with {@code error:unsupportedcontent}, never skipped, so a push is not
 * partly applied without the client knowing.</p>
 *
 * <p>The checks run under the store lock, before anything changes. The changegroup is applied by
 * {@link HgLocalClient#pushWithHooks}; the {@code pushkey}, {@code bookmarks} and {@code phase-heads} parts are applied after it.
 * Unlike the original these are separate steps, not one transaction: a failure in a later part leaves the changegroup applied.</p>
 */
final class Bundle2Push {

    private static final Set<String> IGNORED = Set.of("output", "pushvars", "hgtagsfnodes", "cache:rev-branch-cache");
    private static final Set<String> HANDLED = Set.of("replycaps", "check:heads", "check:updated-heads", "check:bookmarks",
            "check:phases", "changegroup", "remote-changegroup", "pushkey", "bookmarks", "phase-heads");
    private static final String RACED = "remote repository changed while pushing - please try again";

    private Bundle2Push() {
    }

    /** The reply bundle (an {@code HG20} stream) for an incoming push bundle. */
    static byte[] process(HgRepository repo, byte[] bundleBytes, List<String> wireHeads, List<HgHook> preChangegroup,
                          List<HgHook> preTxnChangegroup, List<HgHook> postChangegroup, List<HgHook> prePushkey,
                          List<HgHook> postPushkey, boolean captureOutput) throws IOException {
        Bundle2.Writer reply = new Bundle2.Writer();
        // `output` parts the reply holds so far, which an error answer keeps (the original's salvageoutput)
        List<Object[]> outputs = new ArrayList<>();
        StringBuilder finalOutput = new StringBuilder();
        List<Bundle2.Part> parts;
        try {
            parts = Bundle2.read(bundleBytes);
        } catch (IOException e) {
            if (captureOutput) {
                reply.addPart("output", false, Map.of(), Map.of(), new byte[0]);
            }
            reply.addPart("error:abort", true, Map.of("message", String.valueOf(e.getMessage())), Map.of(), new byte[0]);
            return reply.toBytes(null);
        }

        for (Bundle2.Part part : parts) {
            if (part.mandatory && !HANDLED.contains(part.type) && !IGNORED.contains(part.type)) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("parttype", part.type);
                // wireprotov1server.unbundle: every error part is a mandatory part with mandatory parameters
                reply = new Bundle2.Writer();
                if (captureOutput) {
                    reply.addPart("output", false, Map.of(), Map.of(), new byte[0]);
                }
                reply.addPart("error:unsupportedcontent", true, params, Map.of(), new byte[0]);
                return reply.toBytes(null);
            }
        }

        // What the repository looked like before; the push is undone to this if it fails after something was changed
        Snapshot snapshot = Snapshot.take(repo);
        boolean changegroupApplied = false;
        boolean partsApplied = false;
        String abortNoteFor = null;   // id of the changegroup part whose application the original would have rolled back
        boolean abortLines = false;   // whether the original would have printed "transaction abort!" / "rollback completed"
        try {
            Bundle2.Part changegroup = first(parts, "changegroup");
            List<Bundle2.Part> checks = new ArrayList<>();
            for (Bundle2.Part part : parts) {
                if (part.type.startsWith("check:")) {
                    checks.add(part);
                }
            }
            io.github.search5.hg4j.api.FetchCommand.PostLockValidator validator = () -> {
                for (Bundle2.Part check : checks) {
                    runCheck(repo, check);
                }
            };

            // Everything that can refuse the push is checked before anything is changed, so that a refusal leaves nothing behind. The
            // original would have applied the changegroup of the bundle first (it comes before these parts) and rolled it back;
            // that it did is what it tells the client, so the same is reported here.
            int firstChangegroupIndex = -1;
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).type.equals("changegroup") || parts.get(i).type.equals("remote-changegroup")) {
                    firstChangegroupIndex = i;
                    break;
                }
            }
            for (int i = 0; i < parts.size(); i++) {
                Bundle2.Part part = parts.get(i);
                try {
                    switch (part.type) {
                        case "pushkey" -> prepareLater(repo, part, prePushkey);
                        case "bookmarks" -> prepareBookmarks(repo, part, prePushkey);
                        default -> { }
                    }
                } catch (IOException | RuntimeException e) {
                    if (firstChangegroupIndex >= 0 && firstChangegroupIndex < i) {
                        abortNoteFor = String.valueOf(parts.get(firstChangegroupIndex).id);
                        abortLines = true;
                    }
                    throw e;
                }
            }

            int changegroupResult = 0;
            if (changegroup != null) {
                String version = changegroup.params.getOrDefault("version", "01");
                if (!List.of("01", "02", "03", "04", "05").contains(version)) {
                    // the original fails inside its changegroup lookup (a KeyError); a clear refusal is better than applying garbage
                    throw new IOException("unsupported changegroup version " + version);
                }
                int countBefore = RepoSupport.changelog(repo).getRevisionCount();
                HgLocalClient.PushResult result;
                try {
                    result = new HgLocalClient(repo).pushWithHooks(
                            bundleBytes, wireHeads, preChangegroup, preTxnChangegroup, postChangegroup, validator);
                } catch (io.github.search5.hg4j.errors.HgHookRejectedException e) {
                    abortNoteFor = String.valueOf(changegroup.id);
                    abortLines = e.isRolledBack();
                    throw e;
                }
                changegroupApplied = true;
                boolean added = !result.status.startsWith("no changes found");
                int delta = deltaHeads(repo, countBefore);
                changegroupResult = added ? (delta < 0 ? delta - 1 : delta + 1) : 0;
                Map<String, String> params = new LinkedHashMap<>();
                params.put("in-reply-to", String.valueOf(changegroup.id));
                params.put("return", String.valueOf(changegroupResult));
                reply.addPart("reply:changegroup", false, Map.of(), params, new byte[0]);
                if (captureOutput) {
                    addOutput(reply, outputs, String.valueOf(changegroup.id), "adding changesets\nadding manifests\nadding file changes\n");
                    finalOutput.append(summaryLine(result, delta));
                }
            }
            for (Bundle2.Part remote : parts) {
                if (!remote.type.equals("remote-changegroup")) {
                    continue;
                }
                byte[] fetched = fetchRemoteChangegroup(remote);
                int remoteCountBefore = RepoSupport.changelog(repo).getRevisionCount();
                HgLocalClient.PushResult result;
                try {
                    result = new HgLocalClient(repo).pushWithHooks(
                            fetched, List.of(), preChangegroup, preTxnChangegroup, postChangegroup, changegroup == null ? validator : null);
                } catch (io.github.search5.hg4j.errors.HgHookRejectedException e) {
                    abortNoteFor = String.valueOf(remote.id);
                    abortLines = e.isRolledBack();
                    throw e;
                }
                changegroupApplied = true;
                int remoteDelta = deltaHeads(repo, remoteCountBefore);
                int returned = result.status.startsWith("no changes found") ? 0 : (remoteDelta < 0 ? remoteDelta - 1 : remoteDelta + 1);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("in-reply-to", String.valueOf(remote.id));
                params.put("return", String.valueOf(returned));
                reply.addPart("reply:changegroup", false, Map.of(), params, new byte[0]);
                if (captureOutput) {
                    addOutput(reply, outputs, String.valueOf(remote.id), "adding changesets\nadding manifests\nadding file changes\n");
                    finalOutput.append(summaryLine(result, remoteDelta));
                }
                changegroup = changegroup == null ? remote : changegroup;
            }
            if (changegroup == null && !checks.isEmpty()) {
                try (HgLock lock = repo.lockStore(repo.resolvePushLockTimeoutMs())) {
                    validator.validate();
                }
            }

            partsApplied = parts.stream().anyMatch(p -> p.type.equals("pushkey") || p.type.equals("bookmarks") || p.type.equals("phase-heads"));
            for (Bundle2.Part part : parts) {
                switch (part.type) {
                    case "pushkey" -> applyPushkey(repo, part, reply, List.of(), postPushkey);
                    case "bookmarks" -> applyBookmarks(repo, part, List.of(), postPushkey);
                    case "phase-heads" -> applyPhaseHeads(repo, part);
                    default -> { }
                }
            }
            if (captureOutput) {
                reply.addPart("output", false, Map.of(), Map.of(), finalOutput.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            if (changegroupApplied || partsApplied) {
                // a failure after the repository was changed: put it back (the original aborts its transaction)
                try {
                    undo(repo, snapshot, changegroupApplied);
                } catch (IOException | io.github.search5.hg4j.errors.HgLockException undoFailure) {
                    e.addSuppressed(undoFailure);
                }
                abortLines = true;
                if (abortNoteFor == null && changegroupApplied) {
                    Bundle2.Part cg = first(parts, "changegroup") != null ? first(parts, "changegroup") : first(parts, "remote-changegroup");
                    abortNoteFor = cg == null ? null : String.valueOf(cg.id);
                }
            }
            reply = salvaged(outputs, captureOutput, abortNoteFor, abortLines);
            if (e instanceof HgPushRacedException) {
                reply.addPart("error:pushraced", true, Map.of("message", String.valueOf(e.getMessage())), Map.of(), new byte[0]);
            } else if (e instanceof PushkeyFailed failed) {
                reply.addPart("error:pushkey", true, Map.of("in-reply-to", failed.params.get("in-reply-to")), failed.advisory(), new byte[0]);
            } else {
                reply.addPart("error:abort", true, Map.of("message", String.valueOf(e.getMessage())), Map.of(), new byte[0]);
            }
        }
        return reply.toBytes(null);
    }

    /**
     * {@code remote-changegroup}: the client names a bundle (bundle1 only) to download from an {@code http} or {@code https}
     * URL, with its size and digests; a mismatch of either aborts the push.
     */
    private static byte[] fetchRemoteChangegroup(Bundle2.Part part) throws IOException {
        String url = part.param("url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IOException("remote-changegroup does not support " + (url == null ? "missing" : url.split(":")[0]) + " urls");
        }
        Map<String, String> digests = new LinkedHashMap<>();
        String types = part.params.getOrDefault("digests", "");
        for (String type : types.isEmpty() ? new String[0] : types.split(" ")) {
            String value = part.param("digest:" + type);
            if (value == null) {
                throw new IOException("remote-changegroup: missing \"digest:" + type + "\" param for digest type " + type);
            }
            digests.put(type, value);
        }
        byte[] data;
        try {
            java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("remote-changegroup: HTTP " + response.statusCode() + " from " + url);
            }
            data = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        }
        String size = part.param("size");
        if (size != null && Long.parseLong(size) != data.length) {
            throw new IOException("size mismatch: expected " + size + ", got " + data.length);
        }
        for (Map.Entry<String, String> e : digests.entrySet()) {
            String algorithm = switch (e.getKey()) {
                case "md5" -> "MD5";
                case "sha1" -> "SHA-1";
                case "sha512" -> "SHA-512";
                default -> throw new IOException("remote-changegroup: unknown digest type " + e.getKey());
            };
            try {
                String actual = NodeIdUtil.toHex(java.security.MessageDigest.getInstance(algorithm).digest(data));
                if (!actual.equalsIgnoreCase(e.getValue())) {
                    throw new IOException(e.getKey() + " mismatch: expected " + e.getValue() + ", got " + actual);
                }
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IOException(algorithm + " is not available", ex);
            }
        }
        if (Bundle2.isBundle2(data)) {
            throw new IOException(url + ": not a bundle version 1.0");
        }
        return data;
    }

    /**
     * The value {@code changegroup.apply} returns and a client reads from {@code reply:changegroup}: {@code 1} when the number of
     * heads is unchanged, {@code 1 + n} when {@code n} heads were added, {@code -1 - n} when {@code n} were removed (a head that
     * closes its branch does not count as added). Never 0, which means "nothing applied".
     */
    static int changegroupReturn(HgRepository repo, int countBefore) throws IOException {
        int delta = deltaHeads(repo, countBefore);
        return delta < 0 ? delta - 1 : delta + 1;
    }

    /** How many heads the repository gained (negative: lost) since it had {@code countBefore} revisions; closing heads do not count. */
    static int deltaHeads(HgRepository repo, int countBefore) throws IOException {
        repo.clearRevlogCache();
        Revlog changelog = RepoSupport.changelog(repo);
        int countAfter = changelog.getRevisionCount();
        boolean[] hasChildBefore = new boolean[countAfter];
        boolean[] hasChildAfter = new boolean[countAfter];
        for (int rev = 0; rev < countAfter; rev++) {
            Revlog.IndexRecord record = changelog.getIndexRecord(rev);
            for (int parent : new int[]{record.getParent1(), record.getParent2()}) {
                if (parent >= 0) {
                    hasChildAfter[parent] = true;
                    if (rev < countBefore) {
                        hasChildBefore[parent] = true;
                    }
                }
            }
        }
        // an empty repository has one head, the null revision, which the first changeset replaces
        int delta = countBefore == 0 ? -1 : 0;
        for (int rev = 0; rev < countAfter; rev++) {
            boolean headAfter = !hasChildAfter[rev];
            boolean headBefore = rev < countBefore && !hasChildBefore[rev];
            if (headAfter && !headBefore) {
                delta += closesBranch(changelog, rev) ? 0 : 1;
            } else if (headBefore && !headAfter) {
                delta -= 1;
            }
        }
        return delta;
    }

    /** Whether the changeset's extra data has {@code close=1} ({@code hg commit --close-branch}). */
    private static boolean closesBranch(Revlog changelog, int rev) throws IOException {
        String text = new String(changelog.getRevisionContent(rev), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", 4);
        if (lines.length < 3) {
            return false;
        }
        String dateLine = lines[2];
        int space = dateLine.indexOf(' ', dateLine.indexOf(' ') + 1);
        if (space < 0) {
            return false;
        }
        for (String extra : dateLine.substring(space + 1).split("\0")) {
            if (extra.equals("close:1")) {
                return true;
            }
        }
        return false;
    }

    /** Adds an {@code output} part answering part {@code inReplyTo}. */
    private static void addOutput(Bundle2.Writer reply, List<Object[]> outputs, String inReplyTo, String text) {
        reply.addPart("output", false, Map.of(), Map.of("in-reply-to", inReplyTo), text.getBytes(StandardCharsets.UTF_8));
        outputs.add(new Object[]{inReplyTo, text});
    }

    /**
     * The start of an error answer: the {@code output} parts the reply already had, then the (here empty) output of the
     * transaction that was abandoned, which the original always appends when it captures output.
     */
    private static Bundle2.Writer salvaged(List<Object[]> outputs, boolean captureOutput, String abortNoteFor, boolean abortLines) {
        Bundle2.Writer writer = new Bundle2.Writer();
        if (captureOutput) {
            boolean listed = false;
            for (Object[] output : outputs) {
                listed |= output[0].equals(abortNoteFor);
                writer.addPart("output", false, Map.of(), Map.of("in-reply-to", (String) output[0]),
                        ((String) output[1]).getBytes(StandardCharsets.UTF_8));
            }
            if (abortNoteFor != null && !listed) {
                // the changegroup was (or in the original would have been) applied before the push was refused
                writer.addPart("output", false, Map.of(), Map.of("in-reply-to", abortNoteFor),
                        "adding changesets\nadding manifests\nadding file changes\n".getBytes(StandardCharsets.UTF_8));
            }
            writer.addPart("output", false, Map.of(), Map.of(),
                    (abortLines ? "transaction abort!\nrollback completed\n" : "").getBytes(StandardCharsets.UTF_8));
        }
        return writer;
    }

    /** The files a failed push has to put back, read before anything is changed. */
    private static final class Snapshot {
        private final java.io.File[] files;
        private final byte[][] contents;

        private Snapshot(java.io.File[] files, byte[][] contents) {
            this.files = files;
            this.contents = contents;
        }

        static Snapshot take(HgRepository repo) throws IOException {
            java.io.File[] files = {
                    new java.io.File(repo.getHgDir(), "bookmarks"),
                    new java.io.File(repo.getStoreDir(), "phaseroots"),
                    new java.io.File(repo.getHgDir(), "dirstate")};
            byte[][] contents = new byte[files.length][];
            for (int i = 0; i < files.length; i++) {
                contents[i] = files[i].isFile() ? java.nio.file.Files.readAllBytes(files[i].toPath()) : null;
            }
            return new Snapshot(files, contents);
        }

        void restore() throws IOException {
            for (int i = 0; i < files.length; i++) {
                if (contents[i] == null) {
                    java.nio.file.Files.deleteIfExists(files[i].toPath());
                } else {
                    java.nio.file.Files.write(files[i].toPath(), contents[i]);
                }
            }
        }
    }

    /** Puts the repository back: the changegroup is rolled back with its undo information, then the small files are restored. */
    private static void undo(HgRepository repo, Snapshot snapshot, boolean changegroupApplied)
            throws IOException, io.github.search5.hg4j.errors.HgLockException {
        if (changegroupApplied) {
            try (HgLock lock = repo.lockStore(repo.resolvePushLockTimeoutMs())) {
                new io.github.search5.hg4j.api.RollbackCommand(repo).call();
            }
        }
        snapshot.restore();
        repo.clearRevlogCache();
    }

    /** The line the original prints when a push has been applied: {@code added N changesets with M changes to K files (+n heads)}. */
    static String summaryLine(HgLocalClient.PushResult result, int deltaHeads) {
        int changesets = result.importedNodeHexes.size();
        if (changesets == 0 && result.fileRevisions == 0 && result.files == 0) {
            return "";
        }
        String heads = deltaHeads == 0 ? "" : String.format(" (%+d heads)", deltaHeads);
        return "added " + changesets + " changesets with " + result.fileRevisions + " changes to " + result.files + " files" + heads + "\n";
    }

    private static Bundle2.Part first(List<Bundle2.Part> parts, String type) {
        for (Bundle2.Part part : parts) {
            if (part.type.equals(type)) {
                return part;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ checks

    private static void runCheck(HgRepository repo, Bundle2.Part part) throws IOException {
        switch (part.type) {
            case "check:heads" -> {
                // the changegroup path compares them too (HgLocalClient); this keeps a push without changegroup correct
                if (!sameHeads(nodes(part.payload), currentHeads(repo))) {
                    throw new HgPushRacedException(RACED);
                }
            }
            case "check:updated-heads" -> {
                Set<String> heads = currentHeads(repo);
                for (String node : nodes(part.payload)) {
                    if (!heads.contains(node)) {
                        throw new HgPushRacedException(RACED);
                    }
                }
            }
            case "check:bookmarks" -> checkBookmarks(repo, part.payload);
            case "check:phases" -> checkPhases(repo, part.payload);
            default -> { }
        }
    }

    private static boolean sameHeads(Set<String> expected, Set<String> current) {
        Set<String> a = new TreeSet<>(expected);
        Set<String> b = new TreeSet<>(current);
        a.remove(NodeIdUtil.toHex(new byte[20]));
        b.remove(NodeIdUtil.toHex(new byte[20]));
        return a.equals(b);
    }

    private static Set<String> currentHeads(HgRepository repo) throws IOException {
        return new TreeSet<>(new HgLocalClient(repo).getHeads());
    }

    private static Set<String> nodes(byte[] payload) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i + 20 <= payload.length; i += 20) {
            out.add(NodeIdUtil.toHex(java.util.Arrays.copyOfRange(payload, i, i + 20)));
        }
        return out;
    }

    private static void checkBookmarks(HgRepository repo, byte[] payload) throws IOException {
        Map<String, String> current = RepoSupport.readListKeys(repo, "bookmarks");
        for (Map.Entry<String, byte[]> e : decodeBookmarks(payload).entrySet()) {
            String book = e.getKey();
            String expected = e.getValue() == null ? null : NodeIdUtil.toHex(e.getValue());
            String actual = current.get(book);
            if (java.util.Objects.equals(expected, actual)) {
                continue;
            }
            String prefix = RACED + " (bookmark \"" + book + "\" ";
            if (expected == null) {
                throw new HgPushRacedException(prefix + "set on " + actual.substring(0, 12) + ", expected missing)");
            } else if (actual == null) {
                throw new HgPushRacedException(prefix + "is missing, expected " + expected.substring(0, 12) + ")");
            }
            throw new HgPushRacedException(prefix + "move from " + expected.substring(0, 12) + " to " + actual.substring(0, 12) + ")");
        }
    }

    private static void checkPhases(HgRepository repo, byte[] payload) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        PhaseRoots roots = repo.getPhaseRoots();
        String[] names = {"public", "draft", "secret"};
        for (Map.Entry<Integer, List<byte[]>> e : decodePhases(payload).entrySet()) {
            for (byte[] node : e.getValue()) {
                int actual = roots.getPhase(new NodeId(node), changelog).getValue();
                if (actual != e.getKey()) {
                    String hex = NodeIdUtil.toHex(node);
                    throw new HgPushRacedException(RACED + " (" + hex.substring(0, 12) + " is " + names[actual]
                            + " expected " + names[e.getKey()] + ")");
                }
            }
        }
    }

    // --------------------------------------------------------- binary payloads

    /** {@code bookmarks.binarydecode}: {@code >20sH} then the name; the working-directory id (20 bytes of 0xff) means "no such bookmark". */
    static Map<String, byte[]> decodeBookmarks(byte[] payload) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        ByteBuffer b = ByteBuffer.wrap(payload);
        while (b.hasRemaining()) {
            if (b.remaining() < 22) {
                throw new IOException("truncated bookmarks part");
            }
            byte[] node = new byte[20];
            b.get(node);
            int length = b.getShort() & 0xFFFF;
            if (b.remaining() < length) {
                throw new IOException("truncated bookmarks part");
            }
            byte[] name = new byte[length];
            b.get(name);
            out.put(new String(name, StandardCharsets.UTF_8), isWdirId(node) ? null : node);
        }
        return out;
    }

    private static boolean isWdirId(byte[] node) {
        for (byte b : node) {
            if (b != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** {@code phases.binarydecode}: {@code >i20s} pairs, grouped by phase. */
    static Map<Integer, List<byte[]>> decodePhases(byte[] payload) throws IOException {
        Map<Integer, List<byte[]>> out = new LinkedHashMap<>();
        for (int i = 0; i <= 2; i++) {
            out.put(i, new ArrayList<>());
        }
        ByteBuffer b = ByteBuffer.wrap(payload);
        while (b.remaining() >= 24) {
            int phase = b.getInt();
            byte[] node = new byte[20];
            b.get(node);
            if (!out.containsKey(phase)) {
                throw new IOException("unknown phase " + phase + " in phase-heads part");
            }
            out.get(phase).add(node);
        }
        if (b.hasRemaining()) {
            throw new IOException("truncated phase-heads part");
        }
        return out;
    }

    // ------------------------------------------------------------- applying

    /** Failure of a mandatory {@code pushkey} part, reported as {@code error:pushkey}. */
    private static final class PushkeyFailed extends RuntimeException {
        final Map<String, String> params = new LinkedHashMap<>();

        PushkeyFailed(Bundle2.Part part) {
            super("pushkey failed");
            params.put("in-reply-to", String.valueOf(part.id));
            for (String key : List.of("namespace", "key", "new", "old")) {
                if (part.params.containsKey(key)) {
                    params.put(key, part.params.get(key));
                }
            }
        }

        /** The parameters besides {@code in-reply-to}, which are advisory. */
        Map<String, String> advisory() {
            Map<String, String> rest = new LinkedHashMap<>(params);
            rest.remove("in-reply-to");
            return rest;
        }
    }

    /** Checks a {@code pushkey} part ahead of time: its {@code prepushkey} hooks, and for a mandatory part whether it can succeed. */
    private static void prepareLater(HgRepository repo, Bundle2.Part part, List<HgHook> pre) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        for (String key : List.of("namespace", "key", "old", "new")) {
            args.put(key, part.params.getOrDefault(key, ""));
        }
        runPreHooks(repo, args, pre);
        if (part.mandatory && !RepoSupport.canApplyPushkey(repo, args.get("namespace"), args.get("key"), args.get("old"), args.get("new"))) {
            throw new PushkeyFailed(part);
        }
    }

    /**
     * The same for a {@code bookmarks} part: no divergent names and the {@code prepushkey} hooks for every change. That its nodes
     * exist is checked when it is applied, because the changegroup of the same bundle may be what brings them.
     */
    private static void prepareBookmarks(HgRepository repo, Bundle2.Part part, List<HgHook> pre) throws IOException {
        Map<String, byte[]> changes = decodeBookmarks(part.payload);
        Map<String, String> current = RepoSupport.readListKeys(repo, "bookmarks");
        for (Map.Entry<String, byte[]> e : changes.entrySet()) {
            if (e.getKey().contains("@")) {
                throw new IOException("cannot accept divergent bookmark " + e.getKey() + "!");
            }
            Map<String, String> args = new LinkedHashMap<>();
            args.put("namespace", "bookmarks");
            args.put("key", e.getKey());
            args.put("old", current.getOrDefault(e.getKey(), ""));
            args.put("new", e.getValue() == null ? "" : NodeIdUtil.toHex(e.getValue()));
            runPreHooks(repo, args, pre);
        }
    }

    private static void runPreHooks(HgRepository repo, Map<String, String> args, List<HgHook> pre) throws IOException {
        Map<String, Object> context = new LinkedHashMap<>(args);
        context.put("repository", repo);
        for (HgHook hook : pre) {
            if (!hook.run(context)) {
                throw new IOException("prepushkey hook failed");
            }
        }
    }

    private static void applyPushkey(HgRepository repo, Bundle2.Part part, Bundle2.Writer reply,
                                     List<HgHook> pre, List<HgHook> post) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        for (String key : List.of("namespace", "key", "old", "new")) {
            args.put(key, part.params.getOrDefault(key, ""));
        }
        boolean ok = new String(Wire1Commands.pushkeyFromPart(repo, args, pre, post).getPayload(), StandardCharsets.US_ASCII).startsWith("1");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("in-reply-to", String.valueOf(part.id));
        params.put("return", ok ? "1" : "0");
        reply.addPart("reply:pushkey", true, Map.of(), params, new byte[0]);
        if (part.mandatory && !ok) {
            throw new PushkeyFailed(part);
        }
    }

    private static void applyBookmarks(HgRepository repo, Bundle2.Part part, List<HgHook> pre, List<HgHook> post) throws IOException {
        Map<String, byte[]> changes = decodeBookmarks(part.payload);
        for (String book : changes.keySet()) {
            if (book.contains("@")) {
                throw new IOException("cannot accept divergent bookmark " + book + "!");
            }
        }
        Map<String, String> current = RepoSupport.readListKeys(repo, "bookmarks");
        for (Map.Entry<String, byte[]> e : changes.entrySet()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("namespace", "bookmarks");
            args.put("key", e.getKey());
            args.put("old", current.getOrDefault(e.getKey(), ""));
            args.put("new", e.getValue() == null ? "" : NodeIdUtil.toHex(e.getValue()));
            String answer = new String(Wire1Commands.pushkeyFromPart(repo, args, pre, post).getPayload(), StandardCharsets.US_ASCII);
            if (!answer.startsWith("1")) {
                throw new IOException("could not update bookmark " + e.getKey());
            }
        }
    }

    /** {@code phases.updatephases}: moves each listed head (and so its ancestors) to the given phase if that lowers it. */
    private static void applyPhaseHeads(HgRepository repo, Bundle2.Part part) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        PhaseRoots roots = repo.getPhaseRoots();
        for (Map.Entry<Integer, List<byte[]>> e : decodePhases(part.payload).entrySet()) {
            for (byte[] node : e.getValue()) {
                if (changelog.findRevision(node) < 0 || roots.getPhase(new NodeId(node), changelog).getValue() <= e.getKey()) {
                    continue;
                }
                io.github.search5.hg4j.api.Hg.wrap(repo).phase().setRevision(NodeIdUtil.toHex(node)).setPhase(e.getKey()).call();
            }
        }
    }
}
