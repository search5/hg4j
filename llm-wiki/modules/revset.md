---
updated: 2026-08-31
status: current
---

# 모듈: revset (`io.github.search5.hg4j.revset`)

Mercurial revset 질의 언어 엔진 패키지. [[core-package-split-plan]] Phase 7에서 분리됨.

## 클래스
- **`HgRevsetEngine`**: `draft()`, `author(tester)`, `parents(rev)` 및 AND/OR 조합
  같은 복합 revset 표현식을 평가하는 고성능 질의 엔진.

## 도메인 개념 문서
질의 언어 문법, 지원 함수 목록, 파싱 전략 등 상세는 [[revset]](concepts/revset.md) 참고 —
이 페이지는 패키지 구조만 다룬다.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/revset/`
