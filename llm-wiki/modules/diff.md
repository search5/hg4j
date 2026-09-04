---
updated: 2026-08-31
status: current
---

# 모듈: diff (`io.github.search5.hg4j.diff`)

Revlog 델타 알고리즘 전용 패키지. [[core-package-split-plan]] Phase 10에서 `storage`와
함께 분리됨(SRP 분리 목적 — 델타 적용/생성 로직을 저장 엔진과 분리).

## 클래스
- **`DeltaEngine`**: 델타를 베이스 텍스트에 적용해 새 텍스트를 복원하는 `applyDelta()`를
  비롯한 revlog 델타 알고리즘 담당.

## 관련 페이지
- [[revlog]](concepts/revlog.md) — 델타/압축이 관여하는 상위 개념
- `modules/storage.md` — 이 클래스를 소비하는 저장 엔진

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/diff/`
