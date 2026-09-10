package io.github.search5.hg4j.lib;

/**
 * No-op {@link ProgressMonitor} that reports no progress and is never cancelled.
 *
 * @apiNote The default passed by networked commands ({@code CloneCommand}, {@code
 *     FetchCommand}, {@code PullCommand}) when the caller doesn't supply its own {@link
 *     ProgressMonitor}; use {@link #INSTANCE} rather than constructing a new one.
 */
public class NullProgressMonitor implements ProgressMonitor {
    /** The single shared instance — this class has no state, so one instance suffices. */
    public static final NullProgressMonitor INSTANCE = new NullProgressMonitor();

    private NullProgressMonitor() {}

    @Override public void start(String title, int totalWork) {}
    @Override public void update(int completed) {}
    @Override public void end() {}
    @Override public boolean isCancelled() { return false; }
}
