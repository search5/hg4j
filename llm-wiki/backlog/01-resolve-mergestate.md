---
updated: 2026-09-01
status: completed
---

# 백로그 1: ResolveCommand의 MergeState(state2) 연동

`ResolveCommand`가 레거시 방식이 아니라 실제 hg의 `.hg/merge/state2` 바이너리 포맷
기반 `merge.MergeState`를 쓰도록 전면 재작성한 항목.

## 원문
1. ~~**`ResolveCommand`가 새 `MergeState`(state2)를 안 씀**~~ — ✅ **완료(2026-09-01)**.
   `ResolveCommand`를 `.hg/merge/state2`(`MergeState`) 기반으로 전면 재작성해
   `MergeCommand`가 남긴 실제 충돌 상태를 list/markResolved/markUnresolved로 조작할 수
   있도록 배관을 이었다. `MergeState`에 `markUnresolved`/`hasFile`/`isActive` 신설.
   TDD 6건 전부 GREEN(실제 hg CLI 대조 interop 테스트 포함).
