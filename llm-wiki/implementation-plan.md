---
updated: 2026-08-31
status: current
audience: cross-agent handoff (Gemini 등 이 대화 맥락이 없는 외부 에이전트)
---

# hg4j 구현 계획서 (Gemini용 핸드오프 문서)

## 이 문서를 읽는 방법 (반드시 먼저 읽을 것)

**당신(구현을 맡은 에이전트)은 이 대화의 맥락이 없다고 가정하고 이 문서를 씁니다.**
아래 내용만으로 작업을 시작할 수 있어야 합니다. 부족한 배경지식은 이 저장소의
`llm-wiki/` 디렉터리에 전부 있으니, 각 항목 옆에 붙은 참고 링크를 따라가면 됩니다
(`llm-wiki/index.md`가 전체 카탈로그입니다).

- 이 저장소는 **hg4j** — Mercurial(hg)을 순수 Java로 재구현한 라이브러리입니다.
  진입점: `com.github.search5.hg4j.api.Hg`.
- 작업 전 반드시 `git status`로 현재 상태를 확인하세요. 이 문서가 만들어진 시점의
  HEAD는 `64b1521`입니다.
- **각 Phase(작업 단위)는 반드시 별도 커밋으로 분리하세요.** 한 커밋에 여러 Phase를
  섞지 마세요 — 리뷰와 롤백이 어려워집니다.
- **각 Phase가 끝나면 반드시 아래 명령으로 검증하세요**:
  ```bash
  ./gradlew clean test jacocoTestCoverageVerification
  ```
  이 명령이 실패하면 다음 Phase로 넘어가지 마세요. (커버리지 게이트가 조용히 깨지는
  경우가 있으니 `BUILD SUCCESSFUL` 문구를 반드시 확인하세요.)
- **패키지 이동은 순수 이동입니다. 클래스명/메서드명/로직은 바꾸지 마세요.**
  `package` 선언 한 줄 + import 경로만 바뀝니다. 리네이밍이 필요해 보이는 것이 있어도
  이 계획서에 명시되지 않은 리네이밍은 하지 마세요.
- 테스트 파일은 **파일명이 클래스명과 항상 일치하지 않을 수 있습니다.** 이동하기 전에
  반드시 파일을 열어 어떤 클래스를 실제로 import/테스트하는지 확인하세요. 아래 각
  Phase의 "테스트 파일" 목록은 명명 규칙 기반 추정치이며, 확인되지 않은 항목은
  ⚠️로 표시해뒀습니다.

## 이 계획서의 배경 (요약)
사용자가 이 라이브러리에 다음 2가지 요건을 확정했습니다:
1. **패키지 구조를 JGit(`org.eclipse.jgit`)과 정합시킬 것** — 단, 클래스명의 `Hg` 접두어는
   유지하고, Mercurial 고유 개념(changeset, revset, phase, obsolescence, bundle 등)의
   이름을 JGit 식으로 억지로 바꾸지 않는다.
2. **Mercurial 공식 스펙을 완전히 준수할 것** — 단, Python 확장(extensions) 시스템은
   범위에서 제외한다.

이 문서는 위 두 요건을 실행하기 위한 **하나로 통합된 작업 목록**입니다. 더 깊은 배경
(왜 이런 결정을 했는지, 격차 분석의 근거)은 `llm-wiki/decisions/jgit-parity-requirement.md`
와 `llm-wiki/decisions/mercurial-spec-compliance-requirement.md`에 있습니다.

## 전체 작업 순서 (Track A를 Track B보다 먼저 끝낼 것)
1. **Track A: 패키지 구조 재정렬** (Phase 0~12) — 기계적 이동 작업, 새 기능 없음.
   먼저 끝내야 Track B 작업의 diff가 깨끗해집니다.
2. **Track B: Mercurial 스펙 준수 강화** (Revlog v2, Wireprotocol v2) — 신규 기능 개발.
3. **Track C: 검증 백로그** — "구현은 됐는데 세부 규칙이 맞는지 확인 안 된" 항목들을
   점검하고 필요시 고치는 작업. 우선순위는 사용자와 상의 후 진행할 것 (아래 참고).

---

# Track A — 패키지 구조 재정렬 (JGit 정합성)

참고 문서: `llm-wiki/decisions/core-package-split-plan.md` (이 Track의 원본, 더 자세한
근거가 있음), `llm-wiki/decisions/jgit-parity-requirement.md`.

## 공통 주의사항
- `build.gradle`의 `jacocoTestCoverageVerification` 블록(약 119~145번째 줄)에 아래
  6개 클래스가 **완전정규화 클래스명(FQCN) 문자열**로 하드코딩되어 있습니다. 해당
  클래스를 옮기는 Phase에서 **반드시 이 문자열도 함께 고치세요.** 안 고치면 커버리지
  게이트가 조용히 무력화되거나(더 나쁜 경우) 빌드가 실패합니다.

  | 기존 FQCN | 새 FQCN (이동 후) | 해당 Phase |
  |---|---|---|
  | `com.github.search5.hg4j.core.Dirstate` | `com.github.search5.hg4j.dirstate.Dirstate` | Phase 1 |
  | `com.github.search5.hg4j.core.NodeIdUtil` | `com.github.search5.hg4j.util.NodeIdUtil` | Phase 3 |
  | `com.github.search5.hg4j.core.SafeFileIO` | `com.github.search5.hg4j.util.SafeFileIO` | Phase 3 |
  | `com.github.search5.hg4j.core.Merge3` | `com.github.search5.hg4j.merge.Merge3` | Phase 2 |
  | `com.github.search5.hg4j.core.Bundle2Parser` | `com.github.search5.hg4j.bundle.Bundle2Parser` | Phase 8 |
  | `com.github.search5.hg4j.core.ChangegroupParser` | `com.github.search5.hg4j.bundle.ChangegroupParser` | Phase 8 |

  (`com.github.search5.hg4j.api.*`로 시작하는 나머지 항목들은 이번 작업과 무관하니
  건드리지 마세요.)

- 클래스를 옮길 때 **메인 소스**(`src/main/java/com/github/search5/hg4j/core/Xxx.java`)와
  **테스트 소스**(`src/test/java/com/github/search5/hg4j/core/XxxTest.java`)를 같은
  Phase 안에서 같이 옮기세요. 새 패키지 디렉터리는 `src/main/java/.../<새패키지>/`와
  `src/test/java/.../<새패키지>/` 양쪽에 만들어야 합니다.
- 이동 후에는 프로젝트 전체(`src/main`, `src/test`)에서 옛 패키지 경로로 된 import문을
  검색해서 전부 갱신하세요:
  ```bash
  grep -rl "com.github.search5.hg4j.core.<클래스명>" src/
  ```

## Phase 0 — 사전 정리: `HgLockException` 이원화 해소
- **현재 상태**: `core/HgLockException.java`(레거시, `IOException` 상속)와
  `errors/HgLockException.java`(정식, `HgException` 상속)가 동시에 존재합니다.
  `errors` 쪽 Javadoc에 "기존 core.HgLockException에 대응하는 도메인 예외 레이어
  래퍼"라고 명시되어 있어, 우연한 충돌이 아니라 의도된 어댑터 관계입니다.
- **작업**:
  1. `grep -rl "core.HgLockException\|import com.github.search5.hg4j.core.HgLockException"  src/` 로 참조처를 전부 찾는다.
  2. 모든 참조를 `com.github.search5.hg4j.errors.HgLockException`으로 교체한다
     (생성자 시그니처가 다르니 — `core` 판은 `(String message)`/`(String message, Throwable cause)`,
     `errors` 판은 `(String lockName, String message)` — 호출부를 단순 치환이 아니라
     실제로 맞는 인자를 넘기도록 고쳐야 합니다).
  3. `src/main/java/com/github/search5/hg4j/core/HgLockException.java` 삭제.
  4. 테스트: `src/test/java/com/github/search5/hg4j/core/HgLockExceptionTest.java`를 열어
     어느 쪽 `HgLockException`을 테스트하는지 확인 후, `core` 판을 테스트하던 부분이면
     `errors` 판 기준으로 테스트를 다시 작성(또는 `errors` 패키지의 기존 테스트와 통합).
- **검증**: `./gradlew clean test jacocoTestCoverageVerification`

## Phase 1 — `dirstate` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `Dirstate.java`, `DirstateV2Parser.java`, `DirstateV2Serializer.java`, `DirstateV2Node.java` |
| 이동할 테스트 | `DirstateTest.java`, `DirstateV2InitTest.java`, `DirstateV2LayoutTest.java`, `DirstateV2ParserTest.java` |
| 새 패키지 | `com.github.search5.hg4j.dirstate` |
| 근거 | 서로만 강하게 결합, `HgRepository`가 단방향으로만 참조 — 가장 안전 |
| 참고 | `llm-wiki/concepts/dirstate.md` |

## Phase 2 — `merge` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `Merge3.java` |
| 이동할 테스트 | `Merge3Test.java` |
| 새 패키지 | `com.github.search5.hg4j.merge` |
| 근거 | 순수 알고리즘, 의존 거의 없음 |
| build.gradle | FQCN 갱신 표 참고 |

## Phase 3 — `util` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `SafeFileIO.java`, `NodeIdUtil.java` |
| 이동할 테스트 | `SafeFileIOTest.java`, `NodeIdUtilTest.java` |
| 새 패키지 | `com.github.search5.hg4j.util` |
| 근거 | 유틸 자체 의존은 낮지만 **참조하는 파일 수가 많음** — import 변경 범위가 크므로 초반에 처리 |
| build.gradle | FQCN 갱신 표 참고 |

## Phase 4 — `submodule` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `HgSubrepoParser.java`, `HgSubrepoEntry.java` |
| 이동할 테스트 | `HgSubrepoTest.java` |
| 새 패키지 | `com.github.search5.hg4j.submodule` |
| 근거 | 독립적 기능 단위 |

## Phase 5 — `phase` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `PhaseRoots.java` |
| 이동할 테스트 | `PhaseRootsTest.java` |
| 새 패키지 | `com.github.search5.hg4j.phase` |
| 주의 | `api.PhaseCommand`(포셀린 명령)와 이름이 비슷하니 혼동하지 말 것 — `PhaseCommand`는 `api`에 그대로 둔다, 옮기지 않는다 |

## Phase 6 — `obsolete` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `HgObsolescenceParser.java`, `HgObsMarker.java` |
| 이동할 테스트 | `HgObsolescenceTest.java` |
| 새 패키지 | `com.github.search5.hg4j.obsolete` |

## Phase 7 — `revset` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `HgRevsetEngine.java` |
| 이동할 테스트 | `HgRevsetTest.java` |
| 새 패키지 | `com.github.search5.hg4j.revset` |
| 참고 | `llm-wiki/concepts/revset.md` |

## Phase 8 — `bundle` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `Bundle2Parser.java`, `ChangegroupParser.java` |
| 이동할 테스트 | ⚠️ **core 테스트 디렉터리에 전용 테스트 파일이 안 보임.** `src/test/java/.../api/CHgPushRoundtripTest.java`, `CHgPullRoundtripTest.java` 등 통합 테스트에서 간접적으로만 다뤄지는 것으로 추정 — 이동 전 `grep -rl "Bundle2Parser\|ChangegroupParser" src/test/`로 실제 참조처를 직접 찾아서 확인할 것 |
| 새 패키지 | `com.github.search5.hg4j.bundle` |
| build.gradle | FQCN 갱신 표 참고 |
| 참고 | `llm-wiki/concepts/bundle2-changegroup.md` |

## Phase 9 — `lfs`, `gpg` 패키지 신설
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 (lfs) | `HgLfsManager.java`, `HgLfsPointer.java` → `com.github.search5.hg4j.lfs` |
| 이동할 테스트 (lfs) | `HgLfsTest.java` |
| 이동할 메인 클래스 (gpg) | `GpgSignature.java` → `com.github.search5.hg4j.gpg` |
| 이동할 테스트 (gpg) | ⚠️ **`GpgSignatureTest.java`가 `core` 테스트 디렉터리가 아니라 `api` 테스트 디렉터리에 있습니다** (`src/test/java/.../api/GpgSignatureTest.java`). 이것도 같이 옮길지, 그대로 둘지 확인 후 결정 — 그대로 둔다면 그 이유(예: api 계층 통합 테스트 성격)를 커밋 메시지에 남길 것 |
| 근거 | JGit이 정확히 `org.eclipse.jgit.lfs` 패키지를 갖고 있어 이름까지 일치 가능. gpg는 JGit의 `org.eclipse.jgit.gpg.bc` 대응 |

## Phase 10 — `storage`, `diff` 패키지 신설 (가장 광범위, 마지막에 진행)
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 (storage) | `Revlog.java`, `RevlogIndex.java`, `DeltaCodec.java`, `StoreEngine.java`, `DefaultFileStoreEngine.java` → `com.github.search5.hg4j.storage` |
| 이동할 테스트 (storage) | `RevlogTest.java`, `RevlogIndexTest.java`, `DeltaCodecTest.java` |
| 이동할 메인 클래스 (diff) | `DeltaEngine.java` → `com.github.search5.hg4j.diff` |
| 이동할 테스트 (diff) | `DeltaEngineTest.java` |
| ⚠️ 소속 재확인 필요한 테스트 | `PerformanceBenchmarkTest.java`, `JournalingCrashRecoveryTest.java`, `EscapeAndMetadataTest.java`, `MercurialUncoveredAndPerfTest.java` — 이름만으로는 `storage`/`diff` 전용인지 여러 패키지에 걸친 통합 테스트인지 불명확. 파일을 열어 import 대상을 확인하고, 여러 새 패키지의 클래스를 동시에 테스트한다면 **옮기지 말고 원래 위치(또는 별도의 통합 테스트 패키지)에 남겨둘 것** |
| build.gradle | FQCN 갱신 표 참고 |
| 참고 | `llm-wiki/concepts/revlog.md` (과거 이 영역 버그 이력 다수 — BUG-01/02/04/10) |

## Phase 11 — `HgTreeFilter` → `treewalk`로 이동
| 항목 | 값 |
|---|---|
| 이동할 메인 클래스 | `HgTreeFilter.java` (기존 `core` → 기존 `treewalk` 패키지, 새 패키지 아님) |
| 이동할 테스트 | `HgTreeFilterTest.java` |
| 근거 | Javadoc에 "Inspired by JGit's TreeFilter api" 명시 + 이미 `treewalk.PathFilter` 구현 중. `core → treewalk` 역방향 의존 제거 효과도 있음 |

## Phase 12 — 잔여 `core`를 `lib`로 병합
- **이 시점에 `core`에 남아있어야 할 것**: `HgRepository.java`, `Repository.java`,
  `HgRcConfig.java`, `HgLock.java`, `HgLockException.java`(Phase 0에서 이미 삭제됐어야
  함 — 즉 이 시점엔 원래 없어야 함). 만약 이 목록과 다른 클래스가 `core`에 남아있다면
  Phase 1~11을 다시 확인하세요.
- **대상 패키지 `lib`의 기존 내용**: `NodeId.java`, `ProgressMonitor.java`,
  `NullProgressMonitor.java`, `TextProgressMonitor.java` — 이름이 겹치는 클래스 없음,
  충돌 회피 로직 불필요, 그냥 합치면 됩니다.
- **작업**:
  1. `HgRepository.java`, `Repository.java`, `HgRcConfig.java`, `HgLock.java`를
     `src/main/java/com/github/search5/hg4j/lib/`로 이동, `package` 선언만
     `com.github.search5.hg4j.lib`로 변경.
  2. 대응 테스트(`HgRepositoryTest.java`, `HgRcConfigTest.java`, `HgLockTest.java`)도
     `src/test/java/.../lib/`로 이동.
  3. 전체 프로젝트에서 `com.github.search5.hg4j.core.HgRepository` 등 남은 import를
     `com.github.search5.hg4j.lib.*`로 일괄 치환.
  4. 이 시점에 `src/main/java/com/github/search5/hg4j/core/` 디렉터리가 완전히
     비어야 합니다 — 디렉터리 삭제.
  5. ⚠️ `HgRemoteClientTest.java`, `HgSshClientTest.java`,
     `HgRemoteMockAndServeExtensionTest.java`가 **`core` 테스트 디렉터리에 있는데
     실제로는 `transport` 패키지의 `HgRemoteClient`/`HgSshClient`를 테스트하는 것으로
     보입니다** (이미 `transport` 테스트 디렉터리에도 `HgRemoteClientStreamTest.java`,
     `HgSshClientTransportTest.java`가 별도로 존재). `core` 디렉터리를 비우기 전에 이
     3개 파일을 열어 실제 테스트 대상을 확인하고, `transport` 테스트 디렉터리로
     옮기거나(중복이면 기존 파일과 통합) 적절히 정리하세요 — 이 발견은 이번 계획서
     작성 중 처음 확인된 것이라 사전 조사가 더 필요합니다.
- **Track A 완료 기준**: `src/main/java/com/github/search5/hg4j/core/` 디렉터리가 더 이상
  존재하지 않고, `./gradlew clean test jacocoTestCoverageVerification`이 통과.

---

# Track B — Mercurial 스펙 준수 강화

참고 문서: `llm-wiki/decisions/mercurial-spec-compliance-requirement.md`,
`llm-wiki/decisions/revlog-v2-support-plan.md`,
`llm-wiki/decisions/wireprotocol-v2-support-plan.md`.

**Track A를 완전히 끝낸 뒤 시작하세요.** 구조 변경과 신규 기능 개발을 같은 시기에
섞으면 리뷰가 어려워집니다.

## B-1. Revlog v2 지원 (필수 확정)
- **목표**: 저장소의 `requires` 파일에 v2 관련 requirement(`persistent-nodemap`,
  `revlogv2`/`exp-revlogv2` 계열, `sidedata`)가 있으면 v2로 읽고 쓴다. 없으면 기존
  v1 그대로 — **v1을 대체하는 게 아니라 병행 지원**.
- **1단계(필수 선행)**: 코딩 시작 전에 Mercurial 소스(`mercurial/revlog.py`,
  `mercurial/revlogutils/` 하위)를 직접 읽고 docket 파일의 정확한 바이너리 레이아웃,
  persistent nodemap 파일 포맷, sidedata 슬롯 구조를 확정하세요. **이 계획서에 적힌
  설명은 1차 조사 수준이라 바이트 단위로 신뢰하면 안 됩니다** — 부정확한 정보로 파서를
  짜면 이 프로젝트가 과거에 반복했던 종류의 버그(BUG-01/02/10, delta 오프셋/매직바이트
  오감지)가 재발합니다.
- **영향받는 클래스** (Track A 완료 후 기준): `storage.Revlog`, `storage.RevlogIndex`,
  `storage.DeltaCodec`, `lib.HgRepository`(`loadRequires()`에 v2 requirement 문자열
  인식 추가).
- **픽스처 필요**: 실제 `hg` CLI(README 기준 버전 v7.2.2)로 persistent-nodemap을 켜서
  만든 저장소를 `src/test/resources/fixtures/`에 추가해야 실질적 검증이 가능합니다.
  현재 픽스처(`simple-3commits.bundle` 등)는 전부 번들 파일이라 revlog v2 검증에는
  부족합니다.
- **완료 기준**: v1 저장소와 v2 저장소 모두에서 기존 porcelain 명령(`log`, `status`,
  `commit` 등)이 동일하게 동작하는 라운드트립 테스트가 통과.

## B-2. Wireprotocol v2 지원 (필수 확정)
- **목표**: 서버가 HTTP `/api/*` 공간에 v2를 노출하면 v2(CBOR 기반)를 쓰고, 아니면
  기존 v1(HTTP `?cmd=`/SSH)로 폴백.
- **선행 과제(가장 먼저 처리)**: **CBOR 인코딩/디코딩 라이브러리가 `build.gradle`에
  전혀 없습니다.** `dependencies` 블록에 CBOR 라이브러리(예: Jackson
  `jackson-dataformat-cbor`)를 추가하는 것부터 시작하세요. 라이선스/기존 의존성과의
  호환은 실제 도입 시점에 재확인.
- **1단계(필수 선행)**: `hg help internals.wireprotocolv2`, `hg help
  internals.wireprotocolrpc` 원문 전체와 `mercurial/wireprotov2*.py` 소스를 직접
  대조해 정확한 커맨드 목록/프레이밍 규칙을 확정하세요. 이 계획서의 설명도 1차
  조사 수준입니다.
- **영향받는 클래스**: `transport` 패키지 전체 — 신규 v2 클라이언트 클래스 추가
  (v1 클래스와 병존, 구체적 클래스 분리 방식은 착수 시 설계), `TransportProtocol`에
  capability 협상 갱신.
- **완료 기준**: v2를 노출하는 테스트 서버(`HgWireServer`에 v2 지원 추가 필요 여부도
  검토)를 대상으로 기존 push/pull/fetch 테스트가 v1/v2 양쪽에서 통과.

---

# Track C — 검증 백로그 (우선순위는 사용자 확인 후 진행)

아래는 `llm-wiki/decisions/mercurial-spec-compliance-requirement.md`의 gap table에서
"확인 필요"로 남아있는 항목들입니다. **Revlog v2/Wireprotocol v2와 달리 아직 "반드시
지금 구현하라"는 명시적 지시는 없었습니다** — "완전 준수"라는 큰 방향에는 포함되지만,
착수 순서는 사용자에게 먼저 확인받으세요.

| 항목 | 확인할 것 | 관련 클래스 |
|---|---|---|
| requires 파일 커버리지 | `HgRepository.loadRequires()`가 인식하는 requirement 문자열 목록을 최신 Mercurial 것과 대조 | `lib.HgRepository`(Track A 완료 후) |
| fncache 인코딩 | 최근 수정 이력(`56b1988`)이 있었던 영역 — 회귀 여부 재확인 | `storage.StoreEngine`, `storage.DefaultFileStoreEngine` |
| Changelog extra 필드/다중 부모 인코딩 | 실제 hg 저장소와 바이트 단위 비교 | `storage.Revlog`, `api.CommitCommand`/`LogCommand` |
| Changegroup cg1/cg2/cg3 버전별 차이 | 트리매니페스트/censor 지원 여부까지 커버하는지 | `bundle.ChangegroupParser` |
| Obsolescence marker 쓰기 경로 | 파싱만 있고 쓰기(마커 생성)가 없다면 추가 | `obsolete.HgObsolescenceParser`, `obsolete.HgObsMarker` |
| Bundle1(레거시 `HG10UN/GZ/BZ`) | 미구현으로 추정 — 실제 필요 여부(구버전 서버 호환용) 사용자 확인 | 신규 클래스 필요 시 `bundle` 패키지 |
| Censor(민감정보 삭제) | 미구현으로 추정 — 구현 범위/우선순위 사용자 확인 | 신규 기능 |
| Sparse checkout 설정 파일(`.hg/sparse`) | 파싱 로직 존재 여부 확인 | `treewalk.SparsePathFilter` |
| hgrc `%include`/`%unset` 등 세부 지시자 | 커버리지 확인 | `lib.HgRcConfig`(Track A 완료 후) |
| Merge state 영속화(`.hg/merge/state2`) | 별도 클래스가 없다면 재개 가능한 머지가 실제로 동작하는지 확인 | `api.ResolveCommand`, `api.MergeCommand` |

각 항목을 조사한 뒤에는 해당 내용을 `llm-wiki/decisions/mercurial-spec-compliance-requirement.md`
의 gap table에 반영해서 "확인 필요" 표시를 "✅ 확인됨" 또는 "❌ 미구현, 구현 필요"로
갱신하세요 — 이 문서(implementation-plan.md)가 아니라 그 문서를 갱신하는 것이 맞습니다
(이 문서는 실행 계획, 그 문서는 스펙 준수 현황판).

---

# 작업 완료 후 할 일
1. 이 문서의 각 Phase/항목을 완료할 때마다, 관련 `llm-wiki/decisions/*.md`와
   `llm-wiki/modules/*.md`의 `status`/내용을 실제 코드와 일치하도록 갱신하세요
   (예: Track A 완료 후 `modules/core.md`는 더 이상 존재하지 않는 패키지를 설명하게
   되므로 전체 재작성 필요).
2. `llm-wiki/log.md`에 완료한 작업을 한 줄로 기록하세요 (형식은 기존 로그 참고).
3. README.md의 "Revlog (v1)" 등 구식 표기도 Track B 완료 후 갱신 대상입니다.
