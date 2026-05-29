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
    POST_PUSH
}
