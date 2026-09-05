---
updated: 2026-09-06
status: current
---

# hg4j llm-wiki 인덱스

이 위키를 읽는 규칙은 [[AGENTS.md|AGENTS.md]]에 정의되어 있다. **이 파일을 항상 먼저 읽는다.**

> ⚠️ **신규 기능 개발 전 필독 (요건 2건 + 규칙 1건)**:
> 1. [decisions/jgit-parity-requirement.md](decisions/jgit-parity-requirement.md) — 구조/패키지는
>    JGit과 동일하게, 단 `Hg` 접두어와 Mercurial 고유 개념 이름은 유지(2026-08-31 확정).
>    실행 계획은 [decisions/core-package-split-plan.md](decisions/core-package-split-plan.md).
> 2. [decisions/mercurial-spec-compliance-requirement.md](decisions/mercurial-spec-compliance-requirement.md)
>    — Mercurial 공식 스펙(internals 문서 기준) 완전 준수 요건 **인덱스**(gap table). 상세는
>    각 행이 링크하는 `backlog/*.md` 문서에 있다(2026-09-06 재구조화 — 이 문서 자체는 얇은
>    인덱스로만 유지).
> 3. **새 명령/버그 작업 전에는 [known-bugs-registry.md](known-bugs-registry.md)부터 검색할
>    것** — 이미 알려진 버그를 다른 웨이브가 또 재발견하는 것을 막기 위한 규칙(`AGENTS.md`
>    규칙 10). 68개 명령의 현재 GREEN/RED 상태 구조화 데이터는
>    [matrix-status.md](matrix-status.md) 참고.
>
> **실제로 작업을 시작할 때는 [implementation-plan.md](implementation-plan.md)부터
> 보세요** — 위 두 요건을 하나로 통합해 Phase별 실행 순서/체크리스트/검증 명령까지
> 구체화한 마스터 계획서입니다 (Gemini 등 외부 에이전트에게 그대로 넘길 수 있도록 작성).

## modules/ — 패키지별 구조 (JGit 패키지 1:1 대응 기준으로 분리)
| 페이지 | 요약 |
|---|---|
| [modules/api.md](modules/api.md) | Porcelain 계층 — `Hg` 파사드와 40여 개 `XxxCommand` |
| [modules/transport.md](modules/transport.md) | HTTP/SSH 전송 (JGit `transport` 대응) |
| [modules/treewalk.md](modules/treewalk.md) | 트리 순회 (JGit `treewalk` 대응) |
| [modules/revwalk.md](modules/revwalk.md) | 리비전 그래프 순회 (JGit `revwalk` 대응) |
| [modules/lib.md](modules/lib.md) | 저장소 진입점(`HgRepository`)과 NodeId/ProgressMonitor 등 최상위 공통 구조 (Track A 완료, `core` 병합됨) |
| [modules/errors.md](modules/errors.md) | 예외 계층 (JGit `errors` 대응) |
| [modules/storage.md](modules/storage.md) | Revlog 저장 엔진(`Revlog`/`RevlogIndex`/`DeltaCodec`/`StoreEngine`) — Track A Phase 10에서 분리 |
| [modules/diff.md](modules/diff.md) | Revlog 델타 알고리즘(`DeltaEngine`) — Track A Phase 10에서 `storage`와 함께 분리 |
| [modules/dirstate.md](modules/dirstate.md) | 워킹카피 상태 추적(v1/v2) — Track A Phase 1에서 분리 |
| [modules/merge.md](modules/merge.md) | 3-way 머지 알고리즘(`Merge3`) — Track A Phase 2에서 분리 |
| [modules/util.md](modules/util.md) | 공용 유틸(`NodeIdUtil`/`SafeFileIO`) — Track A Phase 3에서 분리 |
| [modules/submodule.md](modules/submodule.md) | Subrepository(`.hgsub`/`.hgsubstate`) — Track A Phase 4에서 분리 |
| [modules/phase.md](modules/phase.md) | Phase(draft/public/secret) 메타데이터(`PhaseRoots`) — Track A Phase 5에서 분리 |
| [modules/obsolete.md](modules/obsolete.md) | Obsolescence marker(`HgObsMarker`/`HgObsolescenceParser`) — Track A Phase 6에서 분리 |
| [modules/revset.md](modules/revset.md) | Revset 질의 엔진(`HgRevsetEngine`) — Track A Phase 7에서 분리 |
| [modules/bundle.md](modules/bundle.md) | Bundle2/changegroup 파서 — Track A Phase 8에서 분리 |
| [modules/lfs.md](modules/lfs.md) | LFS(Large File Storage) — Track A Phase 9에서 분리 |
| [modules/gpg.md](modules/gpg.md) | 커밋 서명(GPG/OpenPGP) — Track A Phase 9에서 분리 |

## backlog/ — 번호 매겨진 백로그 항목별 상세 문서 (2026-09-06 신설)

`decisions/mercurial-spec-compliance-requirement.md`가 얇은 인덱스로 축소되면서, 각 백로그
번호(또는 관련 번호 묶음)의 발견 경위·근본 원인·수정·검증·관련 커밋은 여기로 이관됐다.
완료된 항목도 삭제하지 않고 상태 표시만 갱신한다(히스토리 보존 — 반복 버그 발견 방지 목적).

| 페이지 | 다루는 백로그 번호 | 요약 |
|---|---|---|
| [backlog/01-resolve-mergestate.md](backlog/01-resolve-mergestate.md) | 1 | ResolveCommand ↔ MergeState 연동 |
| [backlog/wire-protocol-negotiation.md](backlog/wire-protocol-negotiation.md) | 2, 3, 22, 24, 25 | HTTP/SSH 프로토콜 협상, v1 인자 전송 버그, SSH 전송 계층 재작성 |
| [backlog/push-and-concurrency.md](backlog/push-and-concurrency.md) | 13, 33, 38 | Push 증분 처리, SSH checkheads, 동시 push 레이스 |
| [backlog/revlog-storage-formats.md](backlog/revlog-storage-formats.md) | 4, 15, 21, 35, 43 | Revlog v2 일반/persistent-nodemap/fileindex-v1, inline 레이아웃·성장 전환 |
| [backlog/dirstate-v2.md](backlog/dirstate-v2.md) | 5, 37 | Dirstate v2 바이트 레이아웃, 트리 구조 유실 버그(총 5건) |
| [backlog/censor.md](backlog/censor.md) | 6, 7 | Censor 삭제, cg3 censor 지원(추가 2건 발견) |
| [backlog/changegroup-versions.md](backlog/changegroup-versions.md) | 11, 16, 26 | Changegroup cg1~cg5, bundle2 자체 경로 |
| [backlog/treemanifest.md](backlog/treemanifest.md) | 8, 18, 20 | Treemanifest 읽기/쓰기, wireprotocol v2 재귀 tree fetch |
| [backlog/clonebundles.md](backlog/clonebundles.md) | 9, 44 | Clonebundles(44는 서버 매니페스트-없음 응답, 진행 중) |
| [backlog/symlink-handling.md](backlog/symlink-handling.md) | 10, 14 | 심볼릭 링크 lstat 처리 전반(10개+ 파일) |
| [backlog/porcelain-command-exposure.md](backlog/porcelain-command-exposure.md) | 12 | 포셀린 명령 노출 완전성 |
| [backlog/23-core-command-interop-verification.md](backlog/23-core-command-interop-verification.md) | 23 | commit/push/branch/merge/tag(+rebase/shelve/bisect/strip/subrepo) 10개 카테고리 실전 종합 interop 검증 |
| [backlog/sidedata-copy-tracing.md](backlog/sidedata-copy-tracing.md) | 17, 19, 27 | Sidedata SD_FILES, copy-tracing |
| [backlog/narrow-clone-and-lfs.md](backlog/narrow-clone-and-lfs.md) | 28, 30, 31, 40, 42 | Narrow clone, LFS(40/42는 wire-ellipsis·세부 옵션, 진행 중) |
| [backlog/subrepo.md](backlog/subrepo.md) | 32, 41 | Subrepo(Git, SVN 모두 완료) |
| [backlog/requires-format-strings.md](backlog/requires-format-strings.md) | 29 | requires 문자열 재검증 |
| [backlog/misc-command-fixes.md](backlog/misc-command-fixes.md) | 34, 36 | Bisect DAG 검증, Tag 재태깅 가드 |
| [backlog/branch-restore-bugs.md](backlog/branch-restore-bugs.md) | (번호 없음) | update/histedit/bisect/merge/strip 워킹 브랜치 복원 버그 4건 + histedit 저널링 |
| [backlog/39-exhaustive-interop-matrix.md](backlog/39-exhaustive-interop-matrix.md) | 39 | Exhaustive interop matrix(68개 명령) 웨이브 1~5 전체 진행 이력, 40여 건 버그 |

## concepts/ — Mercurial 도메인 개념
| 페이지 | 요약 |
|---|---|
| [concepts/revlog.md](concepts/revlog.md) | 파일별 append-only 히스토리 로그, 델타/압축, 과거 버그 이력 |
| [concepts/dirstate.md](concepts/dirstate.md) | 워킹카피 상태 추적(v1/v2), mtime 캐시, 최근 수정 이력 |
| [concepts/bundle2-changegroup.md](concepts/bundle2-changegroup.md) | push/pull 네트워크 컨테이너 포맷 |
| [concepts/revset.md](concepts/revset.md) | Mercurial revset 질의 언어 자체 구현 |

## decisions/ — 아키텍처 결정 기록 (ADR)
| 페이지 | 요약 |
|---|---|
| [decisions/package-namespace-and-dual-publishing.md](decisions/package-namespace-and-dual-publishing.md) | 패키지명 vs Maven group 분리, Maven Central + Gradle Plugin Portal 이중 배포 |
| [decisions/module-info-disabled.md](decisions/module-info-disabled.md) | JPMS 모듈화 보류(`module-info.java.bak`) |
| [decisions/checked-exception-conversion.md](decisions/checked-exception-conversion.md) | HgException unchecked → checked 전환(BUG-11) |
| [decisions/jgit-parity-requirement.md](decisions/jgit-parity-requirement.md) | ⚠️ **향후 개발 필수 요건** — JGit과 동일한 패키지 구조(단, Hg 접두어·Mercurial 고유 개념명은 유지), 현재 격차표 |
| [decisions/core-package-split-plan.md](decisions/core-package-split-plan.md) | `core` 패키지를 12단계(Phase 0~12)로 나눠 분리·최종 `lib`로 병합하는 실행 계획 (실행 완료) |
| [decisions/mercurial-spec-compliance-requirement.md](decisions/mercurial-spec-compliance-requirement.md) | **얇은 인덱스(2026-09-06 재구조화, 4508줄→약 130줄)** — 스펙 영역별 gap table + 백로그 번호별 문서 목록만 유지, 상세 서술은 전부 위 `backlog/*.md`로 이관. 번호 매겨진 항목 1~44번 완료(25번은 오탐으로 종결; 41 SVN 서브저장소는 2026-09-06 완료), 45번만 미착수. Python 확장 시스템은 **범위 밖 확정** |
| [decisions/test-coverage-95-percent-initiative.md](decisions/test-coverage-95-percent-initiative.md) | JaCoCo BRANCH 커버리지 95% 목표 추진 기록 — 라운드별 TDD 대상 클래스, 확인된 방어적 죽은 코드(unreachable) 목록, 최신 수치. 지속 진행 중(가장 최근에 갱신되는 문서이므로 여기 수치를 중복 기재하지 않음) |
| [decisions/exhaustive-interop-matrix-plan.md](decisions/exhaustive-interop-matrix-plan.md) | **설계만 남긴 축약판(2026-09-06, 844줄→235줄)** — requirement 36개 조합, wire 21개 조합, 68개 명령의 전송관여(8)/로컬전용(60) 분류 설계(§1~3)만 유지. 실제 웨이브별 구현 이력은 [backlog/39-exhaustive-interop-matrix.md](backlog/39-exhaustive-interop-matrix.md)로, 현재 GREEN/RED 데이터는 [matrix-status.md](matrix-status.md)로 이관 — **68/68 완료** |
| [decisions/revlog-v2-support-plan.md](decisions/revlog-v2-support-plan.md) | Revlog v2 지원 — ✅ **2026-09-01 changelog-v2 완료**(실제 hg CLI로 읽기/쓰기/`hg verify` 상호운용 검증), 일반 revlog-v2·persistent-nodemap은 이 환경의 Rust 확장 부재로 의도적 보류 |
| [decisions/wireprotocol-v2-support-plan.md](decisions/wireprotocol-v2-support-plan.md) | wireprotocol v2(프레임+cbor 기반) — ✅ **2026-09-01 전면 재구현 완료**, 이전 구현은 사실상 전부 가짜였음이 드러나 처음부터 다시 작성. 실제 v2 서버 코드가 남아있는 마지막 릴리스(Mercurial 6.0)를 Docker로 띄워 hg4j↔실제 hg 양방향으로 clone까지 검증(현재 배포되는 hg는 6.1부터 이 프로토콜 자체를 제거했음) |
| [decisions/bookmark-full-support-plan.md](decisions/bookmark-full-support-plan.md) | Bookmark commit/update/pull/push 완전 연동 — ✅ **2026-09-01 완료**, 실제 hg CLI 검증 + 데이터 손실 버그 2건 발견·수정 |
| [decisions/journaling-crash-recovery-plan.md](decisions/journaling-crash-recovery-plan.md) | 트랜잭션 저널링·rollback — ✅ **2026-09-01 완료**, pull 후 rollback이 아예 동작 안 하던 갭 발견·수정 |
| [decisions/obsolescence-marker-completeness-plan.md](decisions/obsolescence-marker-completeness-plan.md) | Obsolescence marker — ✅ **2026-09-01 완료**, obsstore 바이너리 포맷 자체가 틀렸던 것을 발견해 전면 재작성(실제 hg와 양방향 검증) |

## 최상위 데이터 문서 (2026-09-06 신설)
| 페이지 | 요약 |
|---|---|
| [known-bugs-registry.md](known-bugs-registry.md) | 이 세션에서 발견한 실제 버그의 클래스/메서드 단위 색인("공유 인프라 계층" + "명령별 버그"). 새 명령/버그 작업 전 필수 검색 대상(`AGENTS.md` 규칙 10) |
| [matrix-status.md](matrix-status.md) | 68개 명령(로컬 60 + wire 8) × requirement/wire 조합의 현재 GREEN/RED 상태, 구조화 표(프로즈 아님 — 총합 off-by-one 방지 목적) |

## sources/ — 원본 조사 스냅샷
| 페이지 | 요약 |
|---|---|
| [sources/2026-08-31-initial-codebase-survey.md](sources/2026-08-31-initial-codebase-survey.md) | llm-wiki 최초 생성 시점의 전체 코드베이스 서베이 |

## 아직 없는 페이지 (필요시 생성)
- `concepts/merge3.md` — 3-way 머지 엔진 상세
- `concepts/journaling-and-crash-recovery.md` — 트랜잭션 저널/자동 롤백
- `concepts/dirstate-v2-binary-layout.md` — 44바이트 노드 포맷 정확한 필드 스펙
- `decisions/ssh-library-abstraction.md` — `SshSessionFactory` 도입 배경

## 프로젝트 기본 정보
- 저장소 루트: `/Users/mzc01-search5/yona-convert/hg4j`
- Java 21 / Gradle 9.4.1, `io.github.search5.hg4j` 좌표로 Maven Central + Gradle Plugin Portal 배포
- 진입점: `io.github.search5.hg4j.api.Hg` (README.md 사용 예제 참고)
