package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
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
final class StreamClone {

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
                        wireName.startsWith("data/") ? NodeIdUtil.encodeFname(decodeDir(wireName)) : wireName);
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

    private record Entry(String wireName, File file, long size) {
    }

    /**
     * The revlog files of the store root (found by scanning the directory, like real hg), then every {@code data/} revlog
     * file named in {@code fncache}, each name once.
     */
    private static List<String> storeFileNames(HgRepository repo) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        File[] root = repo.getStoreDir().listFiles();
        if (root != null) {
            List<String> found = new ArrayList<>();
            for (File f : root) {
                if (f.isFile() && ROOT_REVLOG_FILE.matcher(f.getName()).matches()) {
                    found.add(f.getName());
                }
            }
            java.util.Collections.sort(found);
            names.addAll(found);
        }
        File fncache = new File(repo.getStoreDir(), "fncache");
        if (fncache.isFile()) {
            for (String line : Files.readAllLines(fncache.toPath(), StandardCharsets.ISO_8859_1)) {
                String name = line.strip();
                if (name.startsWith("data/")
                        && (name.endsWith(".i") || name.endsWith(".d") || name.endsWith(".n") || name.endsWith(".nd"))) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** Inverse of {@code store.encodedir}: {@code .hg.hg/} -> {@code .hg/}, {@code .i.hg/} -> {@code .i/}, {@code .d.hg/} -> {@code .d/}. */
    static String decodeDir(String path) {
        return path.replace(".d.hg/", ".d/").replace(".i.hg/", ".i/").replace(".hg.hg/", ".hg/");
    }

    private static boolean hasSecret(HgRepository repo) throws IOException {
        PhaseRoots roots = repo.getPhaseRoots();
        Revlog changelog = Wire2Commands.changelog(repo);
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
