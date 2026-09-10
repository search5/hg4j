package io.github.search5.hg4j.lib;

import java.io.PrintWriter;
import java.io.Writer;

/**
 * A {@link ProgressMonitor} that prints progress as plain text lines to a {@link Writer}.
 *
 * @apiNote A ready-made monitor for callers (e.g. a CLI wrapper around {@code CloneCommand}/
 *     {@code FetchCommand}/{@code PullCommand}) that just want human-readable progress output,
 *     without implementing {@link ProgressMonitor} themselves. Wrap {@code new
 *     OutputStreamWriter(System.out)} to print to the console. Call {@link #cancel()} from
 *     another thread (e.g. in response to a signal) to make {@link #isCancelled()} start
 *     returning {@code true}.
 */
public class TextProgressMonitor implements ProgressMonitor {

    private final PrintWriter out;
    private String title = "";
    private int totalWork = UNKNOWN;
    private int completed = 0;
    private boolean cancelled = false;

    /**
     * Creates a monitor that writes progress lines to the given writer.
     *
     * @param writer destination for the printed progress lines
     */
    public TextProgressMonitor(Writer writer) {
        this.out = new PrintWriter(writer);
    }

    @Override
    public synchronized void start(String title, int totalWork) {
        this.title = title != null ? title : "";
        this.totalWork = totalWork;
        this.completed = 0;
        this.cancelled = false;

        out.print(this.title + ": start");
        if (totalWork != UNKNOWN) {
            out.print(" (total " + totalWork + ")");
        }
        out.println();
        out.flush();
    }

    @Override
    public synchronized void update(int completed) {
        this.completed += completed;
        out.print(title + ": " + this.completed);
        if (totalWork != UNKNOWN) {
            out.print(" / " + totalWork);
        }
        out.println();
        out.flush();
    }

    @Override
    public synchronized void end() {
        out.println(title + ": completed");
        out.flush();
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Requests cancellation; a subsequent {@link #isCancelled()} call returns {@code true}. */
    public synchronized void cancel() {
        this.cancelled = true;
    }
}
