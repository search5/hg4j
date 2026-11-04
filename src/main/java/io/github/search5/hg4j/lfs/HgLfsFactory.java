package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import java.io.IOException;
import java.util.Map;

/**
 * The single seam between hg4j's commands and LFS (JGit's {@code LfsFactory}). Commands never test for LFS themselves: they ask
 * the factory to turn content into a stored form on the way in ({@link #applyCleanFilter}) and back into content on the way out
 * ({@link #applySmudgeFilter}). The default is {@link BuiltinHgLfs}.
 */
public abstract class HgLfsFactory {

    private static volatile HgLfsFactory instance = new BuiltinHgLfs();

    /**
     * Returns the factory in use.
     *
     * @return the current factory, {@link BuiltinHgLfs} unless replaced; never {@code null}
     */
    public static HgLfsFactory getInstance() {
        return instance;
    }

    /**
     * Replaces the factory in use, for the whole JVM. The field is volatile, so the change is visible to all threads.
     *
     * @param factory the new factory; must not be {@code null}
     * @throws IllegalArgumentException if {@code factory} is {@code null}
     */
    public static void setInstance(HgLfsFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory cannot be null");
        }
        instance = factory;
    }

    /** Whether this implementation can work at all (JGit's {@code isAvailable}). */
    public abstract boolean isAvailable();

    /**
     * Result of {@link #applyCleanFilter}: the bytes to put into the filelog, and whether they are an LFS pointer.
     *
     * @param stored the bytes the filelog stores (the content itself, or the serialized pointer)
     * @param pointer the pointer when the content went to the LFS store, otherwise {@code null}
     */
    public record Cleaned(byte[] stored, HgLfsPointer pointer) {
        /**
         * Tells whether the content went to the LFS store.
         *
         * @return {@code true} if {@code pointer} is not {@code null}
         */
        public boolean isLfs() {
            return pointer != null;
        }
    }

    /**
     * Decides whether {@code content} of {@code path} is kept in the LFS store, and if so stores it and returns the pointer.
     *
     * @param copyMeta rename/copy metadata ({@code copy}, {@code copyrev}) folded into the pointer as {@code x-hg-*}, or null
     */
    public abstract Cleaned applyCleanFilter(HgRepository repository, String path, byte[] content,
                                             Map<String, String> copyMeta) throws IOException;

    /** Turns a filelog's stored bytes back into the content; {@code stored} is returned unchanged unless {@code extStored}. */
    public abstract byte[] applySmudgeFilter(HgRepository repository, String path, byte[] stored, boolean extStored)
            throws IOException;

    /**
     * The content of revision {@code rev} of a file's {@code filelog}: the stored bytes, or for an LFS revision the real content
     * the pointer stands for. Commands that show or search file content read through this instead of
     * {@link Revlog#getRevisionContent}, which returns the stored bytes (the pointer text for LFS).
     */
    public final byte[] readContent(HgRepository repository, String path, Revlog filelog, int rev) throws IOException {
        if (rev == -1) {
            return filelog.getRevisionContent(rev); // the null revision: empty content, as Revlog defines it
        }
        return applySmudgeFilter(repository, path, filelog.getRevisionContent(rev), filelog.isExtStored(rev));
    }

    /**
     * Fetches the blobs of every LFS revision in {@code revisions} that is not stored locally yet, with one batch request, so a
     * checkout does not ask the server once per file (real hg's {@code fileprefetchhooks}). Does nothing when no server is
     * configured: the later read then reports the missing object itself.
     *
     * @param revisions filelog to revision number pairs that are about to be read
     */
    public void prefetch(HgRepository repository, java.util.List<java.util.Map.Entry<Revlog, Integer>> revisions) throws IOException {
        prefetch(repository, revisions, null);
    }

    /**
     * Same, naming the file of each revision ({@code paths}, parallel to {@code revisions}, may be {@code null}) so that a
     * failure reads {@code LFS server error for "<path>": ...} like real hg's.
     */
    public void prefetch(HgRepository repository, java.util.List<java.util.Map.Entry<Revlog, Integer>> revisions,
                         java.util.List<String> paths) throws IOException {
        java.util.List<HgLfsPointer> pointers = new java.util.ArrayList<>();
        java.util.Map<String, String> filenames = new java.util.HashMap<>();
        for (int i = 0; i < revisions.size(); i++) {
            java.util.Map.Entry<Revlog, Integer> revision = revisions.get(i);
            if (revision.getKey().isExtStored(revision.getValue())) {
                HgLfsPointer pointer = HgLfsPointer.parse(revision.getKey().getRawRevisionContent(revision.getValue()));
                pointers.add(pointer);
                if (paths != null) {
                    filenames.putIfAbsent(pointer.getOid(), paths.get(i));
                }
            }
        }
        if (pointers.isEmpty()) {
            return;
        }
        String url = HgLfsManager.resolveServerUrl(repository);
        if (url == null) {
            return;
        }
        try {
            new HgLfsManager(repository.getHgDir(), repository.getConfig()).fetchObjects(pointers, url, filenames);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching LFS objects", e);
        }
    }
}
