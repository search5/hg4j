package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server side of the legacy streaming clone (the {@code stream_out} wire command and the {@code stream} /
 * {@code streamreqs=} / {@code stream-preferred} capabilities): a port of {@code streamclone.py}
 * ({@code allowservergeneration}, {@code streamed_requirements}, {@code generatev1wireproto}) of Mercurial 7.2.4.
 *
 * <p>The stream is the store's revlog files verbatim: {@code 0\n}, {@code <files> <bytes>\n}, then per file
 * {@code <name>\0<size>\n} followed by exactly {@code size} bytes. Names are the logical store path, "partially encoded"
 * ({@code encodedir}), which is also how {@code .hg/store/fncache} lists them; the client re-encodes them for its own
 * store.</p>
 *
 * <h2>What is supported, and what deliberately is not</h2>
 * Only stores whose file set can be enumerated and sent correctly without further study: classic revlogs listed in
 * {@code fncache}. Not offered (no capability, and {@code stream_out} answers {@code 1}, "stream generation not
 * allowed", like real hg does when it may not stream): {@code exp-changelog-v2}, {@code exp-revlogv2.2},
 * {@code fileindex-v1}, {@code treemanifest}, and repositories without {@code fncache}. They need docket/index handling
 * that is not implemented or not verified. {@code persistent-nodemap}, the default of a Rust-enabled hg, IS supported: its
 * {@code .n} docket and {@code -<uid>.nd} data files are streamed like any other store file. A store larger than {@link #MAX_BYTES} is
 * refused as well because responses are built in memory.
 */
public final class StreamClone {

    /** requirements that do not matter to a client applying a stream (requirements.py STREAM_IGNORABLE_REQUIREMENTS). */
    private static final Set<String> STREAM_IGNORABLE = Set.of("exp-sparse", "shared", "relshared", "share-safe",
            "dirstate-tracked-key-v1", "dirstate-v2", "dotencode", "exp-very-fragile-and-unsafe-plain-store-encoding", "fncache", "fileindex-v1", "store",
            "persistent-nodemap");
    /** requirements whose store layout this implementation cannot stream correctly. */
    private static final Set<String> UNSUPPORTED_LAYOUT = Set.of("exp-changelog-v2", "exp-revlogv2.2",
            "fileindex-v1", "treemanifest");
    /** The revlog files of the store root (changelog, manifest): index, data, nodemap docket and nodemap data. */
    private static final java.util.regex.Pattern ROOT_REVLOG_FILE =
            java.util.regex.Pattern.compile("(00changelog|00manifest)(\\.i|\\.d|\\.n|-[0-9a-f]+\\.nd)");
    /** The response is built in memory; refuse (as "not allowed") above this size. */
    static final long MAX_BYTES = 256L * 1024 * 1024;

    private StreamClone() {
    }

    /** {@code streamed_requirements}: what the new clone will have to support. */
    static Set<String> streamedRequirements(HgRepository repo) {
        Set<String> reqs = new TreeSet<>(repo.getRequirements());
        reqs.removeAll(STREAM_IGNORABLE);
        return reqs;
    }

    /** Whether this implementation can stream this repository at all (layout), independent of the server config. */
    static boolean layoutSupported(HgRepository repo) {
        Set<String> reqs = repo.getRequirements();
        if (!reqs.contains("fncache")) {
            return false;
        }
        for (String r : reqs) {
            if (UNSUPPORTED_LAYOUT.contains(r)) {
                return false;
            }
        }
        return true;
    }

    /** {@code allowservergeneration}: layout supported, {@code server.uncompressed} (default true), no secret changesets unless allowed. */
    static boolean allowServerGeneration(HgRepository repo) throws IOException {
        if (!layoutSupported(repo)) {
            return false;
        }
        if (!isTrue(repo.getConfig().get("server", "uncompressed", "true"))) {
            return false;
        }
        if (hasSecret(repo)) {
            return isTrue(repo.getConfig().get("server", "uncompressedallowsecret", "false"));
        }
        return true;
    }

    /** The capability tokens real hg's {@code _capabilities} adds for streaming clone, or none when it is not allowed. */
    static List<String> capabilityTokens(HgRepository repo) {
        List<String> tokens = new ArrayList<>();
        try {
            if (!allowServerGeneration(repo)) {
                return tokens;
            }
        } catch (IOException e) {
            return tokens; // cannot tell: do not promise a stream
        }
        if (isTrue(repo.getConfig().get("server", "preferuncompressed", "false"))) {
            tokens.add("stream-preferred");
        }
        Set<String> required = streamedRequirements(repo);
        if (required.isEmpty() || required.equals(Set.of("revlogv1"))) {
            tokens.add("stream");
        } else {
            tokens.add("streamreqs=" + String.join(",", required));
        }
        return tokens;
    }

    /**
     * {@code stream_out}: {@code 1\n} when generation is not allowed, {@code 2\n} when the store cannot be locked, else
     * the stream.
     */
    static Wire1Response streamOut(HgRepository repo) throws IOException {
        if (!allowServerGeneration(repo)) {
            return Wire1Response.streamUncompressed("1\n".getBytes(StandardCharsets.US_ASCII));
        }
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (HgLock ignored = repo.lockStore()) { // a consistent snapshot of the store
            for (String wireName : storeFileNames(repo)) {
                // store-root files (00changelog.*, 00manifest.*) are plain names; only data/ paths go through the store encoding
                File file = new File(repo.getStoreDir(),
                        wireName.startsWith("data/") ? NodeIdUtil.encodeFname(wireName) : wireName);
                long size = file.isFile() ? file.length() : 0;
                if (size > 0) { // real hg skips empty files
                    entries.add(new Entry(wireName, file, size));
                    total += size;
                }
            }
            if (total > MAX_BYTES) {
                return Wire1Response.streamUncompressed("1\n".getBytes(StandardCharsets.US_ASCII));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(total + 4096, Integer.MAX_VALUE - 16));
            out.write("0\n".getBytes(StandardCharsets.US_ASCII));
            out.write((entries.size() + " " + total + "\n").getBytes(StandardCharsets.US_ASCII));
            for (Entry e : entries) {
                out.write(e.wireName.getBytes(StandardCharsets.ISO_8859_1));
                out.write(0);
                out.write((e.size + "\n").getBytes(StandardCharsets.US_ASCII));
                out.write(Files.readAllBytes(e.file.toPath()));
            }
            return Wire1Response.streamUncompressed(out.toByteArray());
        } catch (HgLockException e) {
            return Wire1Response.streamUncompressed("2\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** The content of a bundle2 {@code stream2} part. */
    public record Stream2(int fileCount, long byteCount, byte[] payload, String requirements) {
    }

    /**
     * {@code generatev2} + {@code addpartbundlestream2}: for every store file a {@code 's'} source marker, the name length and the
     * data length as unsigned varints (7 bits per byte, low bits first), the name and the data. Files of the
     * {@code cache/} directory, which the original also copies, are not sent: they are only an optimisation of the receiver.
     *
     * @throws IOException when streaming is not allowed (the same messages the original aborts with) or the store is locked
     */
    static Stream2 stream2(HgRepository repo) throws IOException {
        return stream2(repo, null);
    }

    /**
     * The files one shard of a sharded bundle holds ({@code narrow_info}): the {@code data/} revlogs whose file the shard's matcher
     * selects; the top level shard ({@code match_top_level_entries}, the one named {@code .hg-files}) also has the root revlogs, the
     * phase roots and the caches.
     */
    public record ShardSelection(java.util.function.Predicate<String> matchesFile, boolean topLevel) {
    }

    /** The file a {@code data/} store name belongs to: {@code data/foo/a.i} is {@code foo/a}. */
    static String filelogPath(String wireName) {
        String name = wireName.substring("data/".length());
        for (String ext : new String[] {".i", ".d", ".nd", ".n"}) {
            if (name.endsWith(ext)) {
                String stripped = name.substring(0, name.length() - ext.length());
                int dash = ext.equals(".nd") ? stripped.lastIndexOf('-') : -1;
                return dash > 0 ? stripped.substring(0, dash) : stripped;
            }
        }
        return name;
    }

    public static Stream2 stream2(HgRepository repo, ShardSelection shard) throws IOException {
        if (!allowServerGeneration(repo)) {
            throw new IOException("stream data requested but server does not allow this feature");
        }
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (HgLock ignored = repo.lockStore()) {
            List<String> names = new ArrayList<>(dataFileNames(repo));
            if (shard != null) {
                names.removeIf(n -> !shard.matchesFile().test(filelogPath(n)));
            }
            if ((shard == null || shard.topLevel()) && !RepoSupport.isPublishing(repo) && new File(repo.getStoreDir(), "phaseroots").isFile()) {
                names.add("phaseroots"); // top_entries yields it before the manifest and the changelog
            }
            if (shard == null || shard.topLevel()) {
                names.addAll(rootFileNames(repo));
            }
            for (String wireName : names) {
                File file = new File(repo.getStoreDir(),
                        wireName.startsWith("data/") ? NodeIdUtil.encodeFname(wireName) : wireName);
                if (file.isFile()) {
                    entries.add(new Entry(wireName, file, file.length()));
                    total += file.length();
                }
            }
            // the cache files a clone is worth having (cacheutil.cachetocopy), those that exist, after the store; names are
            // relative to .hg/cache and are marked 'c'
            for (String name : shard != null && !shard.topLevel() ? List.<String>of() : cacheFilesToCopy()) {
                File file = new File(new File(repo.getHgDir(), "cache"), name);
                if (file.isFile()) {
                    entries.add(new Entry(name, file, file.length(), 'c'));
                    total += file.length();
                }
            }
            if (total > MAX_BYTES) {
                throw new IOException("the store is larger than this server streams (" + MAX_BYTES + " bytes)");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(total + 4096, Integer.MAX_VALUE - 16));
            for (Entry e : entries) {
                byte[] name = e.wireName.getBytes(StandardCharsets.ISO_8859_1);
                out.write(e.source);
                writeUvarint(out, name.length);
                writeUvarint(out, e.size);
                out.write(name);
                out.write(Files.readAllBytes(e.file.toPath()));
            }
            String requirements = java.net.URLEncoder.encode(String.join(",", streamedRequirements(repo)), StandardCharsets.US_ASCII)
                    .replace("+", "%20");
            return new Stream2(entries.size(), total, out.toByteArray(), requirements);
        } catch (HgLockException e) {
            throw new IOException("unable to lock the store for streaming: " + e.getMessage(), e);
        }
    }

    /**
     * {@code cacheutil.cachetocopy}: the branch, revision-branch, tags and tag-filenode caches, in the original's order (the
     * repoview subsets from the one others depend on to the dependents: base, immutable, served, visible).
     */
    private static List<String> cacheFilesToCopy() {
        List<String> subsets = List.of("base", "immutable", "served", "visible");
        List<String> names = new ArrayList<>();
        names.add("branch2");
        subsets.forEach(subset -> names.add("branch2-" + subset));
        names.add("branch3");
        subsets.forEach(subset -> names.add("branch3-" + subset));
        names.add("rbc-names-v2");
        names.add("rbc-revs-v2");
        names.add("tags2");
        subsets.forEach(subset -> names.add("tags2-" + subset));
        names.add("hgtagsfnodes1");
        return names;
    }

    /** {@code util.uvarintencode}. */
    private static void writeUvarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while (v >= 0x80) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private record Entry(String wireName, File file, long size, char source) {
        Entry(String wireName, File file, long size) {
            this(wireName, file, size, 's');
        }

    }

    /**
     * The files of a stream, in the order the original sends them ({@code store.walk}): the {@code data/} revlogs, grouped by
     * revlog and sorted by their decoded name, then the root manifest and the changelog. Inside a revlog the files go
     * {@code .n}, {@code .nd}, {@code .d}, anything else, {@code .i} ({@code _ext_key}: the index comes last).
     * Names are the decoded ("unencoded") store paths, as sent on the wire.
     */
    private static List<String> storeFileNames(HgRepository repo) throws IOException {
        List<String> names = new ArrayList<>(dataFileNames(repo));
        names.addAll(rootFileNames(repo));
        return names;
    }

    /** {@code data_entries}: every {@code data/} revlog file named in {@code fncache}, grouped by revlog and sorted. */
    private static List<String> dataFileNames(HgRepository repo) throws IOException {
        java.util.TreeMap<String, Set<String>> byRevlog = new java.util.TreeMap<>();
        File fncache = new File(repo.getStoreDir(), "fncache");
        if (fncache.isFile()) {
            for (String line : Files.readAllLines(fncache.toPath(), StandardCharsets.ISO_8859_1)) {
                String name = decodeDir(line.strip());
                if (name.startsWith("data/")
                        && (name.endsWith(".i") || name.endsWith(".d") || name.endsWith(".n") || name.endsWith(".nd"))) {
                    byRevlog.computeIfAbsent(revlogPrefix(name), k -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        return orderedFiles(byRevlog);
    }

    /** {@code top_entries} for the revlogs of the store root: the manifest first, then the changelog. */
    private static List<String> rootFileNames(HgRepository repo) {
        java.util.TreeMap<String, Set<String>> manifest = new java.util.TreeMap<>();
        java.util.TreeMap<String, Set<String>> changelog = new java.util.TreeMap<>();
        File[] root = repo.getStoreDir().listFiles();
        if (root != null) {
            for (File f : root) {
                String name = f.getName();
                if (f.isFile() && ROOT_REVLOG_FILE.matcher(name).matches()) {
                    (name.startsWith("00manifest") ? manifest : changelog)
                            .computeIfAbsent(revlogPrefix(name), k -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        List<String> names = new ArrayList<>(orderedFiles(manifest));
        names.addAll(orderedFiles(changelog));
        return names;
    }

    private static List<String> orderedFiles(java.util.TreeMap<String, Set<String>> byRevlog) {
        List<String> out = new ArrayList<>();
        for (Set<String> files : byRevlog.values()) {
            List<String> sorted = new ArrayList<>(files);
            sorted.sort(java.util.Comparator.comparingInt(StreamClone::extensionRank).thenComparing(java.util.Comparator.naturalOrder()));
            out.addAll(sorted);
        }
        return out;
    }

    /** {@code _split_revlog_ext}: a nodemap data file ({@code -<id>.nd}) is separated at the dash, any other at the last dot. */
    private static String revlogPrefix(String file) {
        char separator = file.endsWith(".nd") || file.endsWith(".idx") || file.endsWith(".sda") ? '-' : '.';
        return file.substring(0, file.lastIndexOf(separator));
    }

    /** {@code _ext_key}. */
    private static int extensionRank(String file) {
        if (file.endsWith(".n")) {
            return 0;
        } else if (file.endsWith(".nd")) {
            return 10;
        } else if (file.endsWith(".d")) {
            return 20;
        } else if (file.endsWith(".i")) {
            return 50;
        }
        return 40;
    }

    /** Inverse of {@code store.encodedir}: {@code .hg.hg/} -> {@code .hg/}, {@code .i.hg/} -> {@code .i/}, {@code .d.hg/} -> {@code .d/}. */
    static String decodeDir(String path) {
        return path.replace(".d.hg/", ".d/").replace(".i.hg/", ".i/").replace(".hg.hg/", ".hg/");
    }

    private static boolean hasSecret(HgRepository repo) throws IOException {
        PhaseRoots roots = repo.getPhaseRoots();
        Revlog changelog = RepoSupport.changelog(repo);
        for (NodeId root : roots.getRoots(PhaseRoots.Phase.SECRET)) {
            if (changelog.findRevision(root.getBytes()) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrue(String raw) {
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1": case "yes": case "true": case "on": case "always":
                return true;
            default:
                return false;
        }
    }
}
