package org.hg4j.lib;

import java.io.PrintWriter;
import java.io.Writer;

/**
 * 텍스트 스트림(Writer)으로 진행 상황을 인쇄 보고하는 기본 모니터 구현체.
 */
public class TextProgressMonitor implements ProgressMonitor {

    private final PrintWriter out;
    private String title = "";
    private int totalWork = UNKNOWN;
    private int completed = 0;
    private boolean cancelled = false;

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

    public synchronized void cancel() {
        this.cancelled = true;
    }
}
