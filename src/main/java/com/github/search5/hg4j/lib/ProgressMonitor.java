package com.github.search5.hg4j.lib;

/**
 * JGit 스타일의 비동기 및 대형 작업 진행 모니터링 인터페이스.
 */
public interface ProgressMonitor {
    int UNKNOWN = -1;

    void start(String title, int totalWork);

    void update(int completed);

    void end();

    boolean isCancelled();
}
