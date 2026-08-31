---
updated: 2026-08-31
status: current
---

# hg4j llm-wiki 인덱스

이 위키를 읽는 규칙은 [[AGENTS.md|AGENTS.md]]에 정의되어 있다. **이 파일을 항상 먼저 읽는다.**

> ⚠️ **신규 기능 개발 전 필독 (요건 2건)**:
> 1. [decisions/jgit-parity-requirement.md](decisions/jgit-parity-requirement.md) — 구조/패키지는
>    JGit과 동일하게, 단 `Hg` 접두어와 Mercurial 고유 개념 이름은 유지(2026-08-31 확정).
>    실행 계획은 [decisions/core-package-split-plan.md](decisions/core-package-split-plan.md).
> 2. [decisions/mercurial-spec-compliance-requirement.md](decisions/mercurial-spec-compliance-requirement.md)
>    — Mercurial 공식 스펙(internals 문서 기준) 완전 준수 요건과 현재 항목별 준수 상태표.
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
| [decisions/mercurial-spec-compliance-requirement.md](decisions/mercurial-spec-compliance-requirement.md) | Mercurial 공식 스펙(internals 문서) 항목별 완전 준수, 현재 준수 상태표. **Track B-1~B-5 전부 2026-09-01 구현 완료**(changelog-v2/wireprotocol v2/Bookmark/저널링·rollback/Obsolescence marker) — 실제 hg CLI로 검증하는 과정에서 obsstore 포맷 오류, changegroup(cg1) 델타 베이스 규칙 오류 등 사전 버그 다수 발견·수정. Python 확장 시스템은 **범위 밖 확정** |
| [decisions/revlog-v2-support-plan.md](decisions/revlog-v2-support-plan.md) | Revlog v2 지원 — ✅ **2026-09-01 changelog-v2 완료**(실제 hg CLI로 읽기/쓰기/`hg verify` 상호운용 검증), 일반 revlog-v2·persistent-nodemap은 이 환경의 Rust 확장 부재로 의도적 보류 |
| [decisions/wireprotocol-v2-support-plan.md](decisions/wireprotocol-v2-support-plan.md) | wireprotocol v2(프레임+cbor 기반) — ✅ **2026-09-01 전면 재구현 완료**, 이전 구현은 사실상 전부 가짜였음이 드러나 처음부터 다시 작성. 실제 v2 서버 코드가 남아있는 마지막 릴리스(Mercurial 6.0)를 Docker로 띄워 hg4j↔실제 hg 양방향으로 clone까지 검증(현재 배포되는 hg는 6.1부터 이 프로토콜 자체를 제거했음) |
| [decisions/bookmark-full-support-plan.md](decisions/bookmark-full-support-plan.md) | Bookmark commit/update/pull/push 완전 연동 — ✅ **2026-09-01 완료**, 실제 hg CLI 검증 + 데이터 손실 버그 2건 발견·수정 |
| [decisions/journaling-crash-recovery-plan.md](decisions/journaling-crash-recovery-plan.md) | 트랜잭션 저널링·rollback — ✅ **2026-09-01 완료**, pull 후 rollback이 아예 동작 안 하던 갭 발견·수정 |
| [decisions/obsolescence-marker-completeness-plan.md](decisions/obsolescence-marker-completeness-plan.md) | Obsolescence marker — ✅ **2026-09-01 완료**, obsstore 바이너리 포맷 자체가 틀렸던 것을 발견해 전면 재작성(실제 hg와 양방향 검증) |

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
- 진입점: `com.github.search5.hg4j.api.Hg` (README.md 사용 예제 참고)
