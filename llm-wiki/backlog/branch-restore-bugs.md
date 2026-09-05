---
updated: 2026-09-01
status: completed
---

# 워킹 브랜치 복원 버그 모음 (UpdateCommand류 형제 명령 4건 + histedit 저널링)

`hg update`는 대상 리비전이 커밋됐던 브랜치로 작업 브랜치를 전환하는데, `UpdateCommand`를
재사용하지 않고 자체적으로 워킹카피를 재배치하는 다른 포셀린 명령들을 전수 조사해 같은
버그 계열이 있는지 확인한 기록. 4개 중 4개 전부 실제 버그로 확인·수정
(`HisteditCommand`가 가장 심각 — 재작성된 커밋이 조용히 전부 default 브랜치로
뒤바뀌는 데이터 손상급 버그), `GraftCommand`/`RebaseCommand`는 조사 결과 문제 없음으로
확정. `histedit`의 크래시 복구 journal 미적용 항목도 같은 세션 근처에 완료된 관련
항목이라 이 문서에 함께 기록.

## 원문
## `UpdateCommand`류 브랜치 미복원 버그, 형제 명령 4개 전수 조사 후 4건 TDD 수정 (2026-09-01)
`UpdateCommand`를 재사용하지 않고 자체적으로 워킹카피를 재배치하는 다른 포셀린 명령들을
전수 조사해 실제로 같은 버그 계열이 있는지 확인했다. 결과: 4개 중 4개 전부 실제 버그로
확인·TDD(RED→GREEN)로 수정, 2개는 조사 결과 문제 없음으로 확정.

**수정한 것:**
1. **`HisteditCommand` — 가장 심각, 단순 표시 문제가 아니라 히스토리 자체 손상.**
   `commitNewRev()`가 `CommitCommand`를 거치지 않고 changelog 텍스트를 직접 조립하면서
   브랜치 extra 필드를 무조건 생략했다(`secs + " 0\n"` 고정) — non-default 브랜치
   커밋을 histedit로 재작성하면 **재작성된 커밋이 조용히 전부 default 브랜치로
   뒤바뀌는** 데이터 손상급 버그였다. `CommitCommand.getBranchOfRevision()`으로 원본
   커밋의 브랜치를 조회해 그대로 보존하도록 수정, `call()` 종료 시 새 tip 기준으로
   `repository.setBranch(...)`도 추가.
2. **`BisectCommand`** — 이분 탐색 중간 리비전을 실제로 체크아웃하는 "Physical File
   Checkout & Workspace Sync" 로직(주석에도 명시)에 브랜치 전환이 없었음. `UpdateCommand`
   와 동일 패턴으로 수정.
3. **`MergeCommand`의 fast-forward 경로만 해당** — P1이 P2의 조상이라 사실상
   `hg update`와 같은 단일-부모 전진을 수행하는 분기(295번 줄 부근)에 브랜치 전환이
   없었음. **진짜 두 부모짜리 병합 경로(431번 줄 부근)는 조사 결과 문제 없음** — 실제
   hg도 병합 커밋은 현재 브랜치를 유지하므로 그대로 둠.
4. **`StripCommand`** — strip 후 워킹카피 parent를 살아남은 리비전으로 되돌리는
   지점에 브랜치 복원이 없었음(예: feature 브랜치 tip을 strip하면 default 브랜치
   조상으로 돌아가야 하는데 워킹 브랜치는 feature로 남음). 전용 테스트 파일이 아예
   없어서 `StripCommandTest.java`를 새로 만듦.

**문제 없음으로 확정한 것:**
- **`GraftCommand`** — 소스 리비전의 브랜치로 전환하지 않고 현재 브랜치를 유지하는 게
  실제 hg의 정확한 동작(graft는 브랜치를 바꾸지 않음) — 정상.
- **`RebaseCommand`** — 이미 별도 메커니즘으로 원본 커밋의 브랜치를 재커밋에 보존하는
  로직(`backup.branch` 기반)이 올바르게 구현돼 있었음 — 오늘 고친 것과는 무관한
  정상 동작.

4건 모두 RED(실패하는 테스트로 버그 재현) 확인 후 수정해 GREEN 전환, 관련 기존
테스트 전체 재실행으로 회귀 없음 확인.

6. **`UpdateCommand`가 리비전 전환 시 워킹 브랜치명을 복원하지 않던 문제** — TDD로
   수정 완료. 실제 hg는 `hg update`시 대상 리비전이 커밋됐던 브랜치로 작업 브랜치를
   전환하는데, hg4j는 dirstate parent만 바꾸고 `.hg/branch`는 그대로 뒀다.
   `CommitCommand.getBranchOfRevision(Revlog, int)`을 신설해(changelog의
   `branch:<name>` extra 필드를 디코딩하는 로직을 `LogCommand`/`UpdateCommand`에
   중복 구현하던 것 중 하나를 이 공유 헬퍼로 흡수) `UpdateCommand.call()` 마지막에
   `repository.setBranch(...)`를 호출하도록 추가. `UpdateCommand.resolveTargetNodeId()`의
   named-branch-head 탐색도 같은 헬퍼로 재사용하도록 정리.

- ~~**`histedit`의 크래시 복구 journal 미적용**~~ — ✅ **완료(2026-09-01)**.
   `StripCommand`/`CommitCommand`와 동일한 journal + dirstate.backup 스냅샷/롤백
   패턴을 `HisteditCommand`에 이식. 규칙 처리 도중 실패(예: 존재하지 않는 노드로 된
   뒷 규칙)해도 이미 부분적으로 재작성된 커밋/변경된 changelog·manifest·filelog가
   전부 원래 크기로 truncate되고 dirstate도 복원되도록 TDD로 확인
   (`histeditRollsBackAllProgressWhenALaterRuleFails`).
