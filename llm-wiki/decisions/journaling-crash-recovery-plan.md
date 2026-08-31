---
updated: 2026-09-01
status: completed
---

# 계획: 트랜잭션 저널링 및 크래시 복구 (`recover`/`rollback`) 완전 지원 계획

> [[mercurial-spec-compliance-requirement]]에서 Track B-4로 승격된 항목의 실행 계획.
>
> ✅ **2026-09-01 구현 완료** — `RollbackCommand` 및 실제 hg CLI 상호운용 검증
> (`RollbackRealHgInteropTest`). 완료 과정에서 실제 갭 발견: `CommitCommand`만 undo
> 정보를 기록해서 **pull 직후에는 rollback이 전혀 동작하지 않았다** — `hg rollback`의
> 가장 흔한 실사용 시나리오("잘못된 브랜치를 pull했다")인데도. `FetchCommand`도 성공한
> fetch마다 undo 정보를 남기도록 수정. Remove/Rename/Merge/Strip의 journal 확장은 이미
> 구현돼 있음을 코드로 확인(Strip은 journal+undo 둘 다 정상). `histedit`는 아직 journal
> 미적용 — Track C로 하향(빈도 낮은 destructive 경로).
>
> ⚠️ **2026-08-31 정정**: 이 문서의 최초 버전은 "저널링 메커니즘 자체가 전혀 없다"고
> 결론 내렸으나, 이는 `grep "journal\."`(점 포함)로 검색해 실제 파일명 `journal`(확장자
> 없음)을 놓친 **감사 오류**였다. 실제로는 `CommitCommand`/`FetchCommand`/`RebaseCommand`에
> 저널 기반 크래시 복구가 이미 구현돼 있고, `HgRepository.checkAndPerformAutoRollback()`이
> 락 획득 시 자동으로 미완료 저널을 되돌린다(실제 Mercurial의 "다음 명령 실행 시 자동
> recover"와 동일한 방식). 아래 "현재 구현 상태"를 정확한 재조사 결과로 교체했다 —
> **신규 기능 개발이 아니라 이미 있는 패턴을 나머지 쓰기 경로로 확장하고 `rollback`
> (직전 트랜잭션 명시적 되돌리기)만 추가하는 작업**으로 범위가 줄어든다.

## 목표
Mercurial은 저장소 변경을 트랜잭션으로 감싸 두 가지를 보장한다:
1. **크래시 복구(recover)**: 쓰기 도중 죽으면 다음에 저장소를 열 때(락 획득 시) 자동으로
   미완료 트랜잭션을 되돌린다 — `.hg/store/journal`에 각 파일의 트랜잭션 시작 시점 길이를
   기록해두고, truncate로 복원.
2. **명시적 되돌리기(rollback)**: 사용자가 `hg rollback`으로 마지막으로 성공한 트랜잭션
   *하나*를 명시적으로 되돌린다 — `.hg/store/undo.*`에 직전 상태 스냅샷을 보관.

hg4j는 **(1)은 이미 부분 구현돼 있고, (2)는 전혀 없다.**

## 공식 근거
- `hg help internals.transaction` — 저널/undo 파일 포맷과 트랜잭션 커밋/롤백 프로토콜.
- mercurial-scm.org 위키 관련 문서 — journal 파일이 감싸는 대상(store 내 각 파일의
  append 이전 길이를 기록해 truncate로 되돌리는 방식).

## 현재 구현 상태 (2026-08-31 재조사로 정정)
| 항목 | 상태 | 근거 |
|---|---|---|
| `.hg/store/journal` 쓰기(크래시 복구용) | ✅ 부분 구현 | `CommitCommand.appendToJournal()`, `FetchCommand`(동일 패턴), `RebaseCommand`(자체 저널 작성)에 실제 구현. `AmendCommand`/`GraftCommand`/`PullCommand`는 각각 `CommitCommand`/`FetchCommand`에 위임해 간접 커버됨 |
| 저널 자동 복구(락 획득 시) | ✅ 구현됨 | `HgRepository.lockStore()` → `checkAndPerformAutoRollback()`가 leftover `journal` 파일을 읽어 dirstate/fncache/각 revlog 파일을 원래 길이로 truncate 복원. 실제 hg의 "다음 명령에서 자동 recover"와 동일한 방식 |
| 저널 커버리지가 없는 쓰기 경로 | ❌ 미커버 | `RemoveCommand`, `RenameCommand`, `MergeCommand`, `StripCommand`, `HisteditCommand`에서 `journal` 참조 0건 — 이 명령들 도중 죽으면 여전히 무방비 (특히 `StripCommand`는 히스토리를 파괴적으로 재작성하므로 위험도가 가장 높음) |
| `.hg/store/undo.*` 쓰기(rollback용 스냅샷) | ❌ 없음 | 전체 소스에서 `undo` 관련 파일 쓰기 코드 0건 |
| 명시적 `hg recover` 포셀린 명령 | ⚠️ 사실상 불필요 | 자동 복구가 락 획득 시 항상 수행되므로 실제 hg와 동등한 효과. 다만 `Hg.java`에 사용자가 명시적으로 트리거할 수 있는 파사드 메서드는 없음(부가 기능 수준) |
| `hg rollback` 대응 명령 | ❌ 없음 | `RollbackCommand` 클래스 없음, `Hg.java`에 `rollback()` 메서드 없음 |

**결론**: "완전히 없다"는 이전 결론은 틀렸다. **크래시 복구는 핵심 쓰기 경로(commit/fetch/pull/rebase/amend/graft)에서 이미 동작**하고, 나머지 파괴적 명령(특히 `strip`)으로 확장하는 작업과, 완전히 별도 기능인 `rollback`(undo.\* 기반)을 추가하는 작업만 남는다. 데이터 무결성 문제라는 점에서 Track B 지위는 유지하되, 작업 범위는 축소.

## 단계별 계획
| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | 기존 저널 패턴(`CommitCommand.appendToJournal()`/`journalFile` 관례)을 `RemoveCommand`, `RenameCommand`, `MergeCommand`, `HisteditCommand`, `StripCommand`에 확장 적용 — 파괴적 연산인 `StripCommand`를 최우선으로 | 각 Command 수정 |
| 2 | 저널 로직 자체는 여러 Command에 복붙돼 있음(`CommitCommand`/`FetchCommand`/`RebaseCommand` 각각 자체 구현) — 공통 유틸/클래스로 추출할지 판단 (중복 제거 대 최소 변경 트레이드오프) | 필요 시 공용 `JournalWriter` 유틸 |
| 3 | `.hg/store/undo.*` 스냅샷 작성 로직 신설: 트랜잭션이 **성공적으로** 끝날 때마다 직전 상태를 `undo.*`에 보관(현재 저널은 실패 복구용이라 성공 시 삭제됨 — rollback용은 별도 보관 필요) | `undo.*` 쓰기 경로 |
| 4 | `RollbackCommand`/`Hg.rollback()` 신설: `undo.*`가 있으면 그걸로 복원, dirstate/bookmark/phase까지 일관되게 되돌림 | `RollbackCommand.java` |
| 5 | 크래시 시뮬레이션 테스트: `strip`/`merge`/`rename` 등 신규 커버 대상 도중 강제 종료 후 재오픈 시 자동 복구되는지 검증. `rollback` 라운드트립 테스트 | 통합 테스트 |

## 코드 영향 범위 (현재 구조 기준)
- 저널 확장 대상: `api.RemoveCommand`, `api.RenameCommand`, `api.MergeCommand`,
  `api.StripCommand`, `api.HisteditCommand`
- 이미 구현된 것(참고용, 손댈 필요 없음): `api.CommitCommand`, `api.FetchCommand`,
  `api.RebaseCommand`, `lib.HgRepository.checkAndPerformAutoRollback()`
- `rollback` 신규 구현: 신규 `api.RollbackCommand`, `api.Hg`에 `rollback()` 파사드 추가

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 상위 근거 (Track B-4로 승격, 2026-08-31)
- [[index]] — "아직 없는 페이지"에 있던 `journaling-and-crash-recovery` 개념 문서가
  이 계획 실행 후에는 `concepts/journaling-and-crash-recovery.md`로 실제 작성 가능해짐
