package io.github.search5.hg4j.lib;

/**
 * JGit-style interface for reporting progress of, and requesting cancellation of, a long-running
 * or network-bound operation.
 *
 * @apiNote Passed to networked porcelain commands ({@code CloneCommand}, {@code FetchCommand},
 *     {@code PullCommand}) so a caller (e.g. a CLI or UI) can render progress and let the user
 *     cancel mid-transfer. Use {@link NullProgressMonitor#INSTANCE} when progress reporting isn't
 *     needed, or {@link TextProgressMonitor} to print progress to a {@link
 *     java.io.Writer}.
 */
public interface ProgressMonitor {
    /** Sentinel for {@link #start} when the total amount of work is not known in advance. */
    int UNKNOWN = -1;

    /**
     * Called once, before any {@link #update}, to announce the start of a unit of work.
     *
     * @param title short description of the work (e.g. "receiving changesets")
     * @param totalWork the total amount of work expected, or {@link #UNKNOWN} if not known
     */
    void start(String title, int totalWork);

    /**
     * Called repeatedly as work progresses.
     *
     * @param completed the number of additional work units completed since the last call
     */
    void update(int completed);

    /** Called once, after the last {@link #update}, to announce completion. */
    void end();

    /**
     * Polled periodically by the running operation to decide whether to abort early.
     *
     * @return {@code true} if the caller requested cancellation
     */
    boolean isCancelled();
}
