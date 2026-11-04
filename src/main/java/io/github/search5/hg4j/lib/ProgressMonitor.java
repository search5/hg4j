package io.github.search5.hg4j.lib;

/**
 * JGit 스타일의 비동기 및 대형 작업 진행 모니터링 인터페이스.
 */
public interface ProgressMonitor {
    /**
     * Value for {@code totalWork} when the amount of work is not known in advance.
     */
    int UNKNOWN = -1;

    /**
     * Called when an operation begins.
     * @param title a short description of the operation
     * @param totalWork the number of work units, or {@link #UNKNOWN}
     */
    void start(String title, int totalWork);

    /**
     * Reports progress.
     * @param completed the number of work units finished since the previous call (not a running total)
     */
    void update(int completed);

    /**
     * Called when the operation has finished.
     */
    void end();

    /**
     * Asks whether the user requested cancellation; long operations poll this and stop early.
     * @return {@code true} if the operation should stop
     */
    boolean isCancelled();

    /**
     * A line of text the operation would print on the console (e.g. Mercurial's {@code lfs: uploaded 1 files (600 bytes)}).
     * Ignored by default.
     */
    default void message(String text) {
    }
}
