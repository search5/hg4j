package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lib.ProgressMonitor;

/**
 * Where the LFS transfers of the current thread report: the progress of the bytes ({@code lfs uploading} / {@code lfs
 * downloading}, in bytes, like Mercurial's progress topics) and, once a transfer is done, the line Mercurial prints
 * ({@code lfs: uploaded 2 files (1.46 KB)}) through {@link ProgressMonitor#message}.
 *
 * <pre>
 * try (var bound = HgLfsProgress.bind(monitor)) {
 *     ... any command that may move LFS objects ...
 * }
 * </pre>
 */
public final class HgLfsProgress implements AutoCloseable {

    private static final ThreadLocal<ProgressMonitor> CURRENT = new ThreadLocal<>();

    private final ProgressMonitor previous;

    private HgLfsProgress(ProgressMonitor previous) {
        this.previous = previous;
    }

    /** Binds {@code monitor} (may be {@code null}: no reporting) to the current thread until the result is closed. */
    public static HgLfsProgress bind(ProgressMonitor monitor) {
        HgLfsProgress bound = new HgLfsProgress(CURRENT.get());
        if (monitor == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(monitor);
        }
        return bound;
    }

    static ProgressMonitor current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
