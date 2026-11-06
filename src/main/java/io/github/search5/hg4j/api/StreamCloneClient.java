package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client side of the legacy stream clone ({@code streamclone.py}: {@code canperformstreamclone},
 * {@code maybeperformlegacystreamclone}, {@code consumev1}): the remote's store files are written as they are, instead of
 * building the repository changeset by changeset. It only happens into an empty repository, with nothing but the whole remote asked for.
 *
 * <p>Locks: the pull holds the working copy lock; the store lock is taken for the whole of the transfer and the requirements update
 * ({@code with repo.lock():}), after the remote answered. A transfer that fails removes what it wrote ({@code repo.transaction('clone')}
 * aborting).</p>
 */
public final class StreamCloneClient {

    /** {@code requirements.STREAM_IGNORABLE_REQUIREMENTS}: these may differ between the remote and the new repository. */
    private static final Set<String> IGNORABLE = Set.of("exp-sparse", "shared", "relshared", "share-safe",
            "dirstate-tracked-key-v1", "dirstate-v2", "dotencode", "exp-very-fragile-and-unsafe-plain-store-encoding", "fncache",
            "fileindex-v1", "store", "persistent-nodemap");
    /** What this client does not know how to lay out. */
    private static final Set<String> UNSUPPORTED = Set.of("exp-changelog-v2", "exp-revlogv2.2", "treemanifest", "fileindex-v1");

    /** What happened, in Mercurial's words. */
    public record Outcome(boolean done, List<String> messages) {
    }

    private StreamCloneClient() {
    }

    /**
     * {@code maybeperformlegacystreamclone}.
     *
     * @param requested {@code --stream} / {@code --no-stream}, {@code null} for the remote's own preference ({@code stream-preferred})
     */
    public static Outcome maybePerform(HgRepository repository, HgRemoteConnection client, Boolean requested)
            throws IOException, HgLockException {
        return maybePerform(repository, client, client.getCapabilities(), requested);
    }

    /** Same, with the capabilities the caller has already read from {@code client}. */
    public static Outcome maybePerform(HgRepository repository, HgRemoteConnection client, List<String> capabilities, Boolean requested)
            throws IOException, HgLockException {
        List<String> messages = new ArrayList<>();
        boolean wanted = requested != null ? requested : capabilities.contains("stream-preferred");
        if (!wanted || changelogRevisions(repository) > 0) {
            return new Outcome(false, messages);
        }
        Set<String> requirements = new TreeSet<>();
        if (capabilities.contains("stream")) {
            requirements.add("revlogv1");
        } else {
            String streamreqs = null;
            for (String c : capabilities) {
                if (c.startsWith("streamreqs=")) {
                    streamreqs = c.substring("streamreqs=".length());
                }
            }
            if (streamreqs == null || streamreqs.isEmpty()) {
                messages.add("warning: stream clone requested but server has them disabled\n");
                return new Outcome(false, messages);
            }
            Set<String> missing = new TreeSet<>();
            for (String r : streamreqs.split(",")) {
                requirements.add(r);
                if (UNSUPPORTED.contains(r)) {
                    missing.add(r);
                }
            }
            if (!missing.isEmpty()) {
                messages.add("warning: stream clone requested but client is missing requirements: " + String.join(", ", missing) + "\n");
                messages.add("(see https://www.mercurial-scm.org/wiki/MissingRequirement for more information)\n");
                return new Outcome(false, messages);
            }
        }
        if (advertisesStreamV2(capabilities)) {
            // canperformstreamclone: when both sides do bundle2 streaming, that is used and the legacy one is not
            byte[] bundle = client.getStreamBundle();
            if (bundle != null) {
                return performV2(repository, bundle, messages);
            }
        }
        messages.add("streaming all changes\n");
        byte[] body = client.streamOut();
        if (body == null) {
            messages.remove(messages.size() - 1);
            return new Outcome(false, messages);
        }
        consumeV1(repository, new ByteArrayInputStream(body), requirements, messages);
        return new Outcome(true, messages);
    }

    /** The remote's {@code bundle2=} capability blob names {@code stream=v2}. */
    static boolean advertisesStreamV2(List<String> capabilities) {
        for (String capability : capabilities) {
            if (capability.startsWith("bundle2=")) {
                String blob = java.net.URLDecoder.decode(capability.substring("bundle2=".length()), StandardCharsets.ISO_8859_1);
                for (String line : blob.split("\n")) {
                    if (line.startsWith("stream=") && List.of(line.substring("stream=".length()).split(",")).contains("v2")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code util.uvarintdecodestream}. */
    private static long uvarint(InputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new HgValidationException("stream ended unexpectedly while reading a number");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    /**
     * The {@code stream2} part of a stream clone bundle read from a file (a clone bundle, one shard of a sharded group), applied under the
     * store lock. {@link Outcome#done()} is false when the bundle has no such part.
     */
    public static Outcome applyBundleFile(HgRepository repository, byte[] bundle) throws IOException, HgLockException {
        return performV2(repository, bundle, new ArrayList<>());
    }

    /** {@code applybundlev2} + {@code consumev2}: the {@code stream2} part of a bundle2 response, applied under the store lock. */
    private static Outcome performV2(HgRepository repository, byte[] bundle, List<String> messages)
            throws IOException, HgLockException {
        io.github.search5.hg4j.bundle.Bundle2.Part part = null;
        for (io.github.search5.hg4j.bundle.Bundle2.Part candidate : io.github.search5.hg4j.bundle.Bundle2.read(bundle)) {
            if (candidate.type.equals("stream2")) {
                part = candidate;
            }
        }
        if (part == null) {
            return new Outcome(false, messages);
        }
        int fileCount = Integer.parseInt(part.param("filecount"));
        long byteCount = Long.parseLong(part.param("bytecount"));
        Set<String> requirements = new TreeSet<>();
        String raw = part.param("requirements");
        if (raw != null && !raw.isEmpty()) {
            for (String r : java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8).split(",")) {
                requirements.add(r);
            }
        }
        Set<String> missing = new TreeSet<>(requirements);
        missing.retainAll(UNSUPPORTED);
        if (!missing.isEmpty()) {
            throw new HgValidationException("unable to apply stream clone: unsupported format: " + String.join(", ", missing));
        }
        InputStream fp = new ByteArrayInputStream(part.payload);
        try (HgLock lock = repository.lockStore()) {
            messages.add(fileCount + " files to transfer, " + bytecount(byteCount) + " of data\n");
            List<File> written = new ArrayList<>();
            Set<String> fncache = new LinkedHashSet<>();
            long total = 0;
            try {
                for (int i = 0; i < fileCount; i++) {
                    int source = fp.read();
                    long nameLength = uvarint(fp);
                    long dataLength = uvarint(fp);
                    byte[] nameBytes = fp.readNBytes((int) nameLength);
                    String name = new String(nameBytes, StandardCharsets.ISO_8859_1);
                    File target;
                    if (source == 'c') {
                        target = new File(new File(repository.getHgDir(), "cache"), name);
                    } else if (source == 's') {
                        target = new File(repository.getStoreDir(), name.startsWith("data/") || name.startsWith("meta/")
                                ? NodeIdUtil.encodeStorePath(name) : name);
                        if (name.startsWith("data/") || name.startsWith("meta/")) {
                            fncache.add(name);
                        }
                    } else {
                        throw new HgValidationException("unexpected response from remote server: unknown file source " + source);
                    }
                    copyTo(fp, target, dataLength, written, name);
                    total += dataLength;
                }
                registerInFncache(repository, fncache);
                writeRequirements(repository, requirements);
            } catch (IOException | RuntimeException failed) {
                for (File f : written) {
                    Files.deleteIfExists(f.toPath());
                }
                throw failed;
            }
            messages.add("transferred " + bytecount(total) + " in " + fileCount + " files\n");
        }
        return new Outcome(true, messages);
    }

    private static void copyTo(InputStream fp, File target, long size, List<File> written, String label) throws IOException {
        target.getParentFile().mkdirs();
        SafeFileIO.breakHardLinks(target);
        written.add(target);
        try (java.io.OutputStream out = Files.newOutputStream(target.toPath())) {
            long remaining = size;
            byte[] buffer = new byte[65536];
            while (remaining > 0) {
                int got = fp.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (got < 0) {
                    throw new HgValidationException("unexpected response from remote server: stream ended inside " + label);
                }
                out.write(buffer, 0, got);
                remaining -= got;
            }
        }
    }

    private static int changelogRevisions(HgRepository repository) throws IOException {
        File index = new File(repository.getStoreDir(), "00changelog.i");
        if (!index.isFile() || index.length() == 0) {
            return 0;
        }
        return repository.getRevlog(index, new File(repository.getStoreDir(), "00changelog.d")).getRevisionCount();
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) {
            throw new HgValidationException("unexpected response from remote server: end of data");
        }
        return sb.toString();
    }

    /** {@code streamclone.decodedir}. */
    static String decodeDir(String path) {
        if (!path.contains(".hg/")) {
            return path;
        }
        return path.replace(".d.hg/", ".d/").replace(".i.hg/", ".i/").replace(".hg.hg/", ".hg/");
    }

    private static void consumeV1(HgRepository repository, InputStream fp, Set<String> streamed, List<String> messages)
            throws IOException, HgLockException {
        String status = line(fp);
        int response;
        try {
            response = Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            throw new HgValidationException("unexpected response from remote server: " + status);
        }
        if (response == 1) {
            throw new HgValidationException("operation forbidden by server");
        } else if (response == 2) {
            throw new HgValidationException("locking the remote repository failed");
        } else if (response != 0) {
            throw new HgValidationException("the server sent an unknown error code");
        }
        String counts = line(fp);
        int fileCount;
        long byteCount;
        try {
            String[] parts = counts.split(" ", 2);
            fileCount = Integer.parseInt(parts[0].trim());
            byteCount = Long.parseLong(parts[1].trim());
        } catch (RuntimeException e) {
            throw new HgValidationException("unexpected response from remote server: " + counts);
        }
        try (HgLock lock = repository.lockStore()) {
            messages.add(fileCount + " files to transfer, " + bytecount(byteCount) + " of data\n");
            List<File> written = new ArrayList<>();
            Set<String> fncache = new LinkedHashSet<>();
            long total = 0;
            try {
                for (int i = 0; i < fileCount; i++) {
                    String header = line(fp);
                    int nul = header.indexOf('\0');
                    long size;
                    try {
                        size = Long.parseLong(header.substring(nul + 1).trim());
                    } catch (RuntimeException e) {
                        throw new HgValidationException("unexpected response from remote server: " + header);
                    }
                    String path = decodeDir(header.substring(0, nul));
                    String relative = path.startsWith("data/") || path.startsWith("meta/") ? NodeIdUtil.encodeStorePath(path) : path;
                    File target = new File(repository.getStoreDir(), relative);
                    target.getParentFile().mkdirs();
                    SafeFileIO.breakHardLinks(target);
                    written.add(target);
                    try (java.io.OutputStream out = Files.newOutputStream(target.toPath())) {
                        long remaining = size;
                        byte[] buffer = new byte[65536];
                        while (remaining > 0) {
                            int got = fp.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (got < 0) {
                                throw new HgValidationException("unexpected response from remote server: stream ended inside " + path);
                            }
                            out.write(buffer, 0, got);
                            remaining -= got;
                        }
                    }
                    total += size;
                    if (path.startsWith("data/") || path.startsWith("meta/")) {
                        fncache.add(path);
                    }
                }
                registerInFncache(repository, fncache);
                writeRequirements(repository, streamed);
            } catch (IOException | RuntimeException failed) {
                for (File f : written) { // repo.transaction('clone') aborting: nothing of the transfer stays
                    Files.deleteIfExists(f.toPath());
                }
                throw failed;
            }
            messages.add("transferred " + bytecount(total) + " in " + fileCount + " files\n");
        }
    }

    private static void registerInFncache(HgRepository repository, Set<String> names) throws IOException {
        File fncache = new File(repository.getStoreDir(), "fncache");
        if (names.isEmpty() || !fncache.exists()) {
            return;
        }
        SafeFileIO.breakHardLinks(fncache);
        Set<String> all = new LinkedHashSet<>(Files.readAllLines(fncache.toPath(), StandardCharsets.ISO_8859_1));
        all.addAll(names);
        all.remove("");
        Files.writeString(fncache.toPath(), String.join("\n", all) + "\n", StandardCharsets.ISO_8859_1);
    }

    /** {@code new_stream_clone_requirements}: the new repository keeps its own choices where they do not matter, and takes the rest. */
    private static void writeRequirements(HgRepository repository, Set<String> streamed) throws IOException {
        File file = new File(repository.getHgDir(), "requires");
        Set<String> result = new TreeSet<>();
        if (file.exists()) {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty() && (IGNORABLE.contains(t) || t.equals("narrowhg-experimental"))) {
                    result.add(t);
                }
            }
        }
        result.addAll(streamed);
        Files.writeString(file.toPath(), String.join("\n", result) + "\n", StandardCharsets.UTF_8);
    }

    /** {@code util.bytecount}. */
    static String bytecount(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format("%.2f GB", bytes / (double) (1L << 30));
        } else if (bytes >= 1L << 20) {
            return String.format("%.2f MB", bytes / (double) (1L << 20));
        } else if (bytes >= 1L << 10) {
            return String.format("%.2f KB", bytes / (double) (1L << 10));
        }
        return bytes + " bytes";
    }
}
