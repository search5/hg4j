package org.hg4j.api;

/**
 * Mercurial SCM 훅의 종류를 정의하는 열거형입니다.
 */
public enum HgHookType {
    /**
     * 커밋 트랜잭션 수행 전 검증 훅 (검증 실패 시 커밋 취소 가능)
     */
    PRE_COMMIT,
    
    /**
     * 커밋 완료 후 후처리 훅
     */
    POST_COMMIT,
    
    /**
     * 원격 푸시 전 동작하는 훅 (푸시 취소 가능)
     */
    PRE_PUSH,
    
    /**
     * 푸시 완료 후 동작하는 훅
     */
    POST_PUSH,

    /**
     * Graft(체리픽) 완료 후 동작하는 훅
     */
    POST_GRAFT,

    /**
     * Rebase 완료 후 동작하는 훅
     */
    POST_REBASE,

    /**
     * Merge 완료 후 동작하는 훅
     */
    POST_MERGE,

    /**
     * Update(체크아웃) 완료 후 동작하는 훅
     */
    POST_UPDATE,

    /**
     * Merge 실행 전 검증 훅 (거부 가능)
     */
    PRE_MERGE,

    /**
     * Update 실행 전 검증 훅 (거부 가능)
     */
    PRE_UPDATE,

    /**
     * Rebase 실행 전 검증 훅 (거부 가능)
     */
    PRE_REBASE,

    /**
     * Strip 완료 후 동작하는 훅
     */
    POST_STRIP,

    /**
     * Amend 완료 후 동작하는 훅
     */
    POST_AMEND,

    /**
     * Tag 생성 전 검증 훅 (거부 가능)
     */
    PRE_TAG,

    /**
     * Tag 생성 후 동작하는 훅
     */
    POST_TAG,

    /**
     * Changegroup 수신 완료 후 동작하는 훅
     */
    CHANGEGROUP
}
