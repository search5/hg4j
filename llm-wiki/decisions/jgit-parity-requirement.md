---
updated: 2026-09-04
status: current
---

# 요건: 향후 신규 기능은 JGit과 완전히 동일한 코드 네이밍/구조/패키지 구조를 따른다

> **이 페이지는 사용자가 직접 지시한 개발 요건이다.** (2026-08-31, 대화 중 명시)
> "추가 기능 개발 시 JGit과 완전히 같은 코드 네이밍/구조/패키지 구조를 가져야 한다."
> README에 이미 명시된 "Modeled after the architectural philosophy of JGit"이라는
> 느슨한 지향점을, **문자 그대로의 강제 요건**으로 격상한 것으로 이해할 것.

## 적용 범위
- **패키지 구조**: 새로 패키지를 만들 때는 반드시 JGit의 동명(또는 최근접) 패키지가
  존재하는지 먼저 확인하고 그 이름/계층을 따른다.
- **클래스 네이밍**: 새 클래스를 만들 때는 JGit에 대응 클래스가 있으면 `Hg` 접두어를 붙여
  이름을 따르고(예: JGit `Repository` → hg4j `HgRepository`), 최소한 역할이 한눈에
  대응되도록 짓는다 — 접두어 유지 및 예외 범위는 아래 "결정된 사항" 참고.
- **디렉터리/파일 구조**: 이 llm-wiki 자체도 이 요건의 적용 대상이다 — 여러 패키지를 한
  문서에 합쳐 기록하지 않는다 (아래 "이번에 고친 것" 참고).

## 현재(2026-08-31) hg4j ↔ JGit 패키지 격차표
JGit(`org.eclipse.jgit`) 최상위 패키지 기준으로 조사한 결과:

| JGit 패키지 | 역할 | hg4j 현황 | 격차 |
|---|---|---|---|
| `api` | 고수준 포셀린 API | `api` ✅ | 이름 일치 |
| `lib` | 저장소 핵심 데이터 구조(Repository, ObjectId, Config...) | `core`에 대부분 존재, `lib`엔 `NodeId`/`ProgressMonitor`만 | ⚠️ **이름은 있으나 책임 범위가 다름** — [[lib]] 참고 |
| `transport` | 원격 통신 | `transport` ✅ | 이름 일치, 클래스 대응도 양호 — [[transport]] 참고 |
| `revwalk` | 리비전 그래프 순회 | `revwalk` — 단 핵심 클래스명이 `ChangesetGraph`/`SortOrder`로 `RevWalk`/`RevSort`와 불일치 | ⚠️ [[revwalk]] 참고 |
| `treewalk` | 트리 순회 | `treewalk` ✅, 단 `filter` 서브패키지 미분리 | ⚠️ [[treewalk]] 참고 |
| `dircache` | 인덱스/스테이징 관리 | **별도 패키지 없음** — `Dirstate`가 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `storage` | 저장소 백엔드(파일/DFS) | **별도 패키지 없음** — `StoreEngine`/`DefaultFileStoreEngine`이 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `merge` | 브랜치 병합 로직 | **별도 패키지 없음** — `Merge3`가 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `diff` | 델타/diff 알고리즘 | **별도 패키지 없음** — `DeltaEngine`/`DeltaCodec`이 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `util` | 범용 유틸리티 | **별도 패키지 없음** — `NodeIdUtil`/`SafeFileIO`가 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `ignore` | ignore 패턴 처리 | **별도 패키지 없음** — 로직이 `HgRepository` 내부 메서드(`loadIgnorePatterns`, `isIgnored`)로 존재 | ❌ 패키지 자체가 없음, 클래스로도 분리 안 됨 |
| `hooks` | 훅 실행 | `HgHook`/`HgHookType`/`ProcessHook`이 `api`에 존재 | ⚠️ 패키지 위치가 JGit과 다름(JGit은 `hooks` 별도 패키지) |
| `submodule` | 서브모듈/서브저장소 | **별도 패키지 없음** — `HgSubrepoParser`/`HgSubrepoEntry`가 `core`에 존재 | ❌ 패키지 자체가 없음 |
| `attributes` | `.gitattributes` 처리 | 대응 기능 없음 (Mercurial `.hgeol` 등 미구현) | N/A — 기능 자체 미구현이라 구조 논의 대상 아님 |
| `errors` | 예외 계층 | `errors` ✅ | 이름/계층 형태 일치 — [[errors]] 참고 |

**요약**: `core` 패키지 하나가 JGit의 `lib` + `storage` + `dircache` + `merge` + `diff` +
`util` + `submodule` 7개 패키지 역할을 전부 떠안고 있는 것이 가장 큰 구조적 격차다.
사용자가 말한 "일부 합쳐져서 기록된 부분"은 1차적으로는 이 llm-wiki가 여러 패키지를 한
문서에 합쳐 적었던 것을 가리키지만, 근본 원인은 **실제 소스 코드의 `core` 패키지 자체가
JGit 기준 여러 패키지를 이미 합쳐놓은 상태**라는 점이다.

## 이번에 고친 것 (위키 측)
- 기존 `modules/transport-treewalk-revwalk.md` 1개 파일을 `modules/transport.md`,
  `modules/treewalk.md`, `modules/revwalk.md`, `modules/lib.md`, `modules/errors.md`
  5개로 분리 — JGit 패키지 경계와 1:1 대응하도록 재구성.
- `core.md`는 아직 분리하지 않았다 — 실제 소스 코드가 `core` 하나로 합쳐져 있는 현재
  상태를 있는 그대로 반영한 것. **소스 코드 리팩토링(패키지 분리)이 실행되기 전까지는
  위키만 먼저 쪼개지 않는다** (문서가 실제 코드보다 앞서 나가면 오히려 혼란을 유발하므로).

## 결정된 사항 (2026-08-31, 사용자 확정 답변)
1. **`Hg` 접두어는 유지한다.** 클래스명을 JGit과 문자 그대로 동일하게 맞추지 않는다 —
   `HgRepository`, `HgLockException` 등 기존 접두어 관례를 그대로 유지. "완전히 동일한
   네이밍" 요건은 **패키지 구조/책임 분리**에 대한 것이지, 클래스명 접두어 제거를
   의미하지 않는 것으로 확정.
2. **머큐리얼 고유 개념까지 JGit과 억지로 1:1 매칭할 필요는 없다.** `ChangesetGraph`,
   `SortOrder` 같은 이름은 리네이밍하지 않는다. Mercurial 고유 용어(changeset, revset,
   phase, obsolescence, bundle 등)를 표현하는 클래스/패키지는 **Mercurial 자체 용어를
   그대로 쓰는 것이 정답**이며, JGit에 대응 개념이 없거나 이름이 다르다고 해서 격차로
   취급하지 않는다. → [[revwalk]] 문서의 "리네이밍 후보" 표기는 철회됨.
3. **`core` 패키지 분리 순서는 위임받음** — 구체적 단계별 계획은
   [[core-package-split-plan]] 참고. 실행 여부/각 단계 결과 확인은 사용자가 직접 한다.

## 이 요건이 실제로 요구하는 것 (범위 재확정)
- ✅ 요구: `core` 패키지처럼 여러 책임이 뭉쳐 있는 구조를 JGit처럼 **관심사별 패키지로
  분리**하는 것. `api`/`transport`/`treewalk`/`revwalk`/`errors`처럼 이미 JGit과 대응되는
  패키지는 그 경계를 유지/강화하는 것.
- ❌ 요구하지 않음: 클래스명에서 `Hg` 접두어 제거, Mercurial 고유 개념(changeset, revset,
  phase, bundle, obsolescence 등)의 이름을 JGit 용어로 바꾸는 것.

## 2026-09-04 업데이트: `core` 패키지 분리 완료, 격차표 재확인
[[core-package-split-plan]]의 전 단계(Phase 0~12)가 실행 완료되어, 위 "현재(2026-08-31)
hg4j ↔ JGit 패키지 격차표"에서 "❌ 패키지 자체가 없음"으로 표시했던 항목들이 대부분
해소됐다. `core` 패키지 자체가 더 이상 존재하지 않는다. 실제 코드 기준 재확인:

| JGit 패키지 | 2026-08-31 상태 | 2026-09-04 상태 |
|---|---|---|
| `lib` | `core`에 대부분 존재 | ✅ 해소 — `Repository`/`HgRepository`/`HgRcConfig`/`HgLock`/`NodeId`/`ProgressMonitor`가 `lib` 패키지로 병합됨(Phase 12) |
| `dircache` | 패키지 자체 없음 | ⚠️ **부분 해소** — `Dirstate`가 이제 `dirstate` 독립 패키지로 분리됨(Phase 1). 다만 JGit은 이 개념을 `dircache`로 부르고 hg4j는 Mercurial 자체 용어 `dirstate`를 쓰므로 이름 자체는 여전히 다름 — 이건 격차가 아니라 위 "결정된 사항 2"에 따라 의도된 것(Mercurial 고유 개념은 Mercurial 용어 유지) |
| `storage` | 패키지 자체 없음 | ✅ 해소 — `StoreEngine`/`DefaultFileStoreEngine`/`Revlog`/`RevlogIndex`/`FileIndex`가 `storage` 패키지로 분리됨(Phase 10) |
| `merge` | 패키지 자체 없음 | ✅ 해소 — `Merge3`가 `merge` 패키지로 분리됨(Phase 2) |
| `diff` | 패키지 자체 없음 | ✅ 해소 — `DeltaEngine`이 `diff` 패키지로 분리됨(Phase 10) |
| `util` | 패키지 자체 없음 | ✅ 해소 — `SafeFileIO`/`NodeIdUtil`이 `util` 패키지로 분리됨(Phase 3) |
| `submodule` | 패키지 자체 없음 | ✅ 해소 — `HgSubrepoParser`/`HgSubrepoEntry`가 `submodule` 패키지로 분리됨(Phase 4) |
| `ignore` | 패키지 자체 없음, 클래스로도 분리 안 됨 | ❌ **미해소 그대로** — `loadIgnorePatterns()`/`isIgnored()`가 여전히 `lib.HgRepository` 내부 메서드로만 존재. 이 계획 범위 밖(core-package-split-plan에 `ignore` 관련 Phase가 아예 없었음) |
| `hooks` | `api`에 위치(JGit과 다른 위치) | ⚠️ **미해소 그대로** — `HgHook`/`HgHookType`/`ProcessHook`이 여전히 `api` 패키지. core-package-split-plan 대상이 아니었음(애초에 `core`가 아니라 `api`에 있었으므로) |
| `attributes` | 대응 기능 없음 | 변화 없음 — 여전히 N/A |

**남은 격차는 2개뿐**: `ignore`(패키지 분리 안 됨)와 `hooks`(JGit과 다른 위치). 둘 다
`core-package-split-plan`의 계획 범위에 없었던 항목이라 별도 후속 결정이 필요하면
새 계획 문서를 만들어야 한다 — 이 문서 자체에서 새로 승격하지는 않는다(범위 확장은
사용자 판단 필요).

## 관련 페이지
- [[lib]], [[transport]], [[treewalk]], [[revwalk]], [[errors]], [[storage]], [[merge]],
  [[diff]], [[util]], [[submodule]], [[dirstate]] — 현재 구조 상세 (2026-09-04부터 `core`
  링크는 제거됨 — 패키지가 더 이상 존재하지 않음)
- [[core-package-split-plan]] — 이 격차표를 해소한 실행 계획, 완료 확인
- [[package-namespace-and-dual-publishing]] — 과거에도 네임스페이스를 손댄 이력이 있어
  참고할 것 (그때는 배포 좌표 문제, 이번엔 구조적 정합성 문제로 성격이 다름)
