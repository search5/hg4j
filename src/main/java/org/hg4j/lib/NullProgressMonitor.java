package org.hg4j.lib;

/**
 * 아무런 진행률 보고도 하지 않는 기본 No-Op 구현체.
 */
public class NullProgressMonitor implements ProgressMonitor {
    public static final NullProgressMonitor INSTANCE = new NullProgressMonitor();

    private NullProgressMonitor() {}

    @Override public void start(String title, int totalWork) {}
    @Override public void update(int completed) {}
    @Override public void end() {}
    @Override public boolean isCancelled() { return false; }
}
