package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Uploads the LFS blobs of the revisions a push or a bundle sends (JGit's {@code LfsPrePushHook}; real hg's {@code prepush}
 * and {@code uploadblobsfromrevs}). The server must have a blob before it receives a pointer to it.
 */
public final class HgLfsPrePush {

    private HgLfsPrePush() {
    }

    /** The pointers of the LFS revisions among {@code revisions} (filelog, revision number), one per object id, sorted by it. */
    public static List<HgLfsPointer> extractPointers(List<Map.Entry<Revlog, Integer>> revisions) throws IOException {
        Map<String, HgLfsPointer> byOid = new TreeMap<>();
        for (Map.Entry<Revlog, Integer> revision : revisions) {
            if (revision.getKey().isExtStored(revision.getValue())) {
                HgLfsPointer pointer = HgLfsPointer.parse(revision.getKey().getRawRevisionContent(revision.getValue()));
                byOid.put(pointer.getOid(), pointer);
            }
        }
        return new ArrayList<>(byOid.values());
    }

    /**
     * Uploads the blobs behind {@code revisions} to the LFS store of {@code destination}, choosing the store like real hg's
     * {@code blobstore.remote()}: {@code [lfs] url} if set, else {@code <destination>/.git/info/lfs} for an http(s) destination.
     * By scheme of that address: {@code http}/{@code https} a Git LFS server, {@code file} a plain directory, {@code null} nothing.
     * With no address at all, uploading is refused ({@code lfs.url needs to be configured}) when there is a blob to upload; any
     * other scheme is refused ({@code lfs: unknown url scheme}) whenever the repository uses LFS.
     */
    public static void upload(HgRepository repository, String destination, List<Map.Entry<Revlog, Integer>> revisions)
            throws IOException {
        String url = HgLfsManager.resolveRemoteUrl(repository, destination);
        String scheme = url == null ? null : schemeOf(url);
        boolean usesLfs = repository.getRequirements().contains("lfs");
        if (usesLfs && scheme != null && !List.of("http", "https", "file", "null").contains(scheme)) {
            throw new io.github.search5.hg4j.lfs.errors.HgLfsException("lfs: unknown url scheme: " + scheme);
        }
        List<HgLfsPointer> pointers = extractPointers(revisions);
        if (pointers.isEmpty() || "null".equals(scheme)) {
            return;
        }
        if (scheme == null) {
            throw new io.github.search5.hg4j.lfs.errors.HgLfsException("lfs.url needs to be configured");
        }
        HgLfsManager manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
        if (scheme.equals("file")) {
            copyToDirectory(repository, manager, pointers, url);
            return;
        }
        try {
            manager.uploadObjects(pointers, url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while uploading LFS objects", e);
        }
    }

    private static String schemeOf(String url) {
        int colon = url.indexOf(':');
        return colon > 0 ? url.substring(0, colon).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** Real hg's {@code file:} store: every blob is read (and verified) from the local store and written into a directory. */
    private static void copyToDirectory(HgRepository repository, HgLfsManager manager, List<HgLfsPointer> pointers, String url)
            throws IOException {
        String path = java.net.URI.create(url).getPath();
        java.io.File directory = new java.io.File(path).isAbsolute() ? new java.io.File(path)
                : new java.io.File(new java.io.File(repository.getHgDir(), "lfs"), path);
        for (HgLfsPointer pointer : pointers) {
            byte[] blob;
            try {
                blob = manager.getCachedObject(pointer);
            } catch (IOException missingOrWrongSize) {
                throw new io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException(
                        "detected corrupt lfs object: " + pointer.getOid());
            }
            if (!HgLfsPointer.sha256Hex(blob).equals(pointer.getOid())) {
                throw new io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException(
                        "detected corrupt lfs object: " + pointer.getOid());
            }
            java.io.File target = new java.io.File(new java.io.File(directory, pointer.getOid().substring(0, 2)),
                    pointer.getOid().substring(2));
            target.getParentFile().mkdirs();
            java.nio.file.Files.write(target.toPath(), blob);
        }
    }
}
