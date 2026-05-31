package io.github.search5.hg4j.revwalk;

/**
 * ChangesetGraph의 순회 순서를 정의하는 열거형입니다.
 */
public enum SortOrder {
    /**
     * 기본 BFS 순서
     */
    DEFAULT,
    
    /**
     * 자식이 부모보다 항상 먼저 오는 토폴로지 정렬 순서
     */
    TOPO
}
