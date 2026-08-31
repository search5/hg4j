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
| [modules/core.md](modules/core.md) | Plumbing 계층 — HgRepository, Dirstate, Revlog, Bundle2, Revset 엔진 등 (⚠️ 12단계로 분리 예정, 최종적으로 core→lib 병합 — core-package-split-plan 참고) |
| [modules/api.md](modules/api.md) | Porcelain 계층 — `Hg` 파사드와 40여 개 `XxxCommand` |
| [modules/transport.md](modules/transport.md) | HTTP/SSH 전송 (JGit `transport` 대응) |
| [modules/treewalk.md](modules/treewalk.md) | 트리 순회 (JGit `treewalk` 대응) |
| [modules/revwalk.md](modules/revwalk.md) | 리비전 그래프 순회 (JGit `revwalk` 대응) |
| [modules/lib.md](modules/lib.md) | NodeId/ProgressMonitor (현재는 범위 축소, core-package-split-plan Phase 12로 잔여 core와 병합 예정) |
| [modules/errors.md](modules/errors.md) | 예외 계층 (JGit `errors` 대응) |

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
| [decisions/core-package-split-plan.md](decisions/core-package-split-plan.md) | `core` 패키지를 12단계(Phase 0~12)로 나눠 분리·최종 `lib`로 병합하는 실행 계획 (아직 미실행) |
| [decisions/mercurial-spec-compliance-requirement.md](decisions/mercurial-spec-compliance-requirement.md) | ⚠️ **향후 개발 필수 요건** — Mercurial 공식 스펙(internals 문서) 항목별 완전 준수, 현재 준수 상태표. Revlog v2 · wireprotocol v2 **필수 구현 확정**, Python 확장 시스템은 **범위 밖 확정** |
| [decisions/revlog-v2-support-plan.md](decisions/revlog-v2-support-plan.md) | Revlog v2(persistent nodemap/sidedata/docket) 추가 지원 실행 계획 (1차 조사, 미실행) |
| [decisions/wireprotocol-v2-support-plan.md](decisions/wireprotocol-v2-support-plan.md) | wireprotocol v2(cbor 기반) 추가 지원 실행 계획 (1차 조사, 미실행) |

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
