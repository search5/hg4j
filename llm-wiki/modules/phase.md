---
updated: 2026-08-31
status: current
---

# 모듈: phase (`com.github.search5.hg4j.phase`)

Mercurial phase(draft/public/secret) 메타데이터 관리 패키지. [[core-package-split-plan]]
Phase 5에서 분리됨.

> ⚠️ **이름 혼동 주의**: 포셀린 계층의 `api.PhaseCommand`(`hg phase` 명령)와 이름이
> 비슷하지만 다른 클래스다. `PhaseCommand`는 `api`에 그대로 남아있고 이 패키지로
> 옮기지 않았다 — Plumbing(`phase.PhaseRoots`)과 Porcelain(`api.PhaseCommand`)의
> 역할 구분.

## 클래스
- **`PhaseRoots`**: `.hg/phaseroots` 파일을 파싱/관리. `Phase` enum(`PUBLIC`, `DRAFT`,
  `SECRET`)을 정의하고, 특정 리비전이 어느 phase에 속하는지 판정.

## 연동 확인 (2026-08-31 전수 감사)
`api.CommitCommand`가 새 커밋을 기본 `DRAFT`로 설정하고, `api.PushCommand`가 `secret`
phase 커밋의 push를 차단하는 것까지 실제로 연동돼 있음을 코드로 확인 — [[bookmark-full-support-plan]]과
달리 phase는 반쪽짜리가 아니라 정상 구현.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/phase/`
