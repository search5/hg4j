package org.hg4j.api;

import java.io.IOException;
import java.util.Map;

/**
 * JGit 스타일의 Mercurial SCM 훅 인터페이스입니다.
 * 자바 애플리케이션 내부에서 SCM 트랜잭션 수립 전후의 이벤트를 가로채어 검증하거나 후처리를 가동할 수 있습니다.
 */
@FunctionalInterface
public interface HgHook {
    /**
     * SCM 동작 중 특정 훅 시점에 트리거됩니다.
     *
     * @param context 훅 실행에 필요한 SCM 컨텍스트 데이터 (예: "author", "message", "commitNode", "repository" 등)
     * @return true 이면 진행 허가, false 이면 진행 차단 및 트랜잭션 중단(예외 유발)
     * @throws IOException I/O 오류 또는 훅 실행 실패 시
     */
    boolean run(Map<String, Object> context) throws IOException;
}
