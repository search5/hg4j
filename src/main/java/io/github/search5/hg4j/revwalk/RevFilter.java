package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.core.Revlog;

/**
 * JGit의 RevFilter에 상응하는 리비전 필터 인터페이스.
 * 리비전 그래프 탐색 시 특정 리비전을 포함할지 여부를 판정합니다.
 */
@FunctionalInterface
public interface RevFilter {
    /**
     * 지정한 리비전이 필터 조건에 부합하여 결과에 포함되어야 하는지 판정합니다.
     */
    boolean include(int revision, Revlog changelog);

    /**
     * 모든 리비전을 포함하는 기본 필터.
     */
    RevFilter ALL = (rev, cl) -> true;

    /**
     * 어떠한 리비전도 포함하지 않는 기본 필터.
     */
    RevFilter NONE = (rev, cl) -> false;

    /**
     * 다른 필터와의 AND 조합을 생성합니다.
     */
    default RevFilter and(RevFilter other) {
        if (other == null) return this;
        return (rev, cl) -> this.include(rev, cl) && other.include(rev, cl);
    }

    /**
     * 다른 필터와의 OR 조합을 생성합니다.
     */
    default RevFilter or(RevFilter other) {
        if (other == null) return this;
        return (rev, cl) -> this.include(rev, cl) || other.include(rev, cl);
    }

    /**
     * 현재 필터의 부정(NOT) 필터를 생성합니다.
     */
    default RevFilter negate() {
        return (rev, cl) -> !this.include(rev, cl);
    }
}
