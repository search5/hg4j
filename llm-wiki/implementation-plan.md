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

## 전체 작업 순서 (Track A 완료, 현재 Track B 진행 가능)
1. **Track A: 패키지 구조 재정렬** (Phase 0~12) — **완료됨**. 패키지 분할 및 재정렬 구조가 소스 코드에 전부 반영되었습니다.
2. **Track B: Mercurial 스펙 준수 강화** (B-1 Revlog v2, B-2 Wireprotocol v2, B-3
   Bookmark 완전 지원, B-4 트랜잭션 저널링/크래시 복구, B-5 Obsolescence marker 생성
   경로 완성) — B-1/B-2는 신규 기능 개발, B-3~B-5는 이미 부분 구현된 기능을 완성하는
   작업(2026-08-31 전수 감사로 추가 확정). 현재 착수 단계입니다.
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

## B-1. Revlog v2 지원 — changelog-v2 (2026-09-01 완료, 실제 hg CLI 상호운용 검증됨)
- **상태**: ✅ **changelog-v2(`exp-changelog-v2`) 완료.** 2026-08-31 반려 이후, 이 머신에
  실제 설치된 `hg` CLI(Mercurial 7.2)와 그 Python 소스를 직접 사용해 재구현 —
  추측이 아니라 실측. 실제 hg로 만든 changelog-v2 저장소를 hg4j로 읽고, hg4j로 새
  리비전을 쓴 뒤 그 저장소를 실제 `hg log`/`hg verify`로 열어 정확히 인식되는 것까지
  확인함(`hg verify` integrity error 0건). 상세: [decisions/revlog-v2-support-plan.md](decisions/revlog-v2-support-plan.md).
- **미완료로 명시적으로 남긴 범위**: 일반 revlog-v2(`exp-revlogv2.2`, 매니페스트/파일로그)와
  persistent-nodemap(`.n` 트라이 파일)은 **의도적으로 미착수**. 이 환경의 hg 바이너리는
  Rust 확장이 없어 이 두 기능을 켜면 `abort: accessing ... without associated fast
  implementation`으로 저장소 생성 자체가 안 됨 — 검증할 방법이 없는 채로 구현하면
  changelog-v2와 같은 반려 사유(추측 기반 구현)가 재발하므로 보류. 바이트 레이아웃은
  Mercurial 소스에서 확인했으나(`INDEX_ENTRY_V2 = >Qiiiiii20s12xQiB19x`) hg4j 코드에는
  반영하지 않음. Rust 포함 `hg` 바이너리를 구할 수 있는 환경에서 이어서 작업할 것 —
  방법론(실제 저장소 생성 → hexdump/python struct 대조 → TDD → 실제 hg로 상호운용
  재검증)은 changelog-v2와 동일하게 따르면 됨.
- **영향받는 클래스(실제 반영됨)**: `storage.RevlogIndex`(v2 docket/index 파싱,
  `getIndexRecord()` v2 분기, `updateV2DocketSizes()`), `storage.Revlog`(v2 datFile
  자동 해석, `appendRevisionV2()`), `lib.HgRepository`(`.hg/store/requires`까지 읽도록
  `loadRequires()` 확장, `isChangelogV2()`/`isRevlogV2()`/`isPersistentNodemap()`).
- **픽스처**: `src/test/resources/fixtures/revlogv2-changelog/`에 실제 hg가 생성한
  docket(.i)/index(.idx)/data(.dat)/sidedata(.sda) 4개 파일 및 검증값 기록된 README.md.
- **완료 기준**: `RevlogV2ParserTest`(실제 hg 픽스처로 읽기 6종 검증, 쓰기 후 재오픈까지
  검증) 및 `HgRepositoryTest`의 requires 인식 테스트 통과, 전체 스위트
  `./gradlew clean test jacocoTestCoverageVerification` BUILD SUCCESSFUL(직접 실행 확인).

## B-2. Wireprotocol v2 지원 (2026-09-01 재검토·수정 완료 — 단, 근본적 검증 한계 있음)
- **상태**: ✅ CBOR 의존성(Jackson) 추가, `HgRemoteClientV2`/`CborFrameParser`/
  `HgWireServer` v2 서빙 구현. **2026-09-01 재검토에서 두 가지를 발견·수정**:
  1. 이 환경(및 사실상 최신 Mercurial 전반)에는 **wireprotocol v2를 실제로 서빙하는
     서버 코드가 Mercurial 자체에 없다** — `mercurial/wireprotov2server.py`가 존재하지
     않고, 실제 v1 디스패처(`wireprotoserver.py`)에도 `application/mercurial-cbor`나
     `/api/` 코드가 0건. `hg help internals.wireprotocolv2`가 "experimental and under
     active development"라고 문서화해뒀지만 실제 구현은 개발이 중단된 것으로 보임.
     **따라서 revlog v2(B-1)와 달리 이 기능은 실제 hg 서버와 상호운용 검증이 원천적으로
     불가능하다** — hg4j의 v2 클라이언트/서버 쌍끼리 자기 자신을 검증하는 것이 유일한
     방법.
  2. 그럼에도 유일하게 존재하는 근거 문서(`hg help internals.wireprotocolv2`)와 대조한
     결과 실제 구현이 스펙과 어긋난 부분을 발견해 정정: `capabilities` 응답이 v1 스타일
     평면 리스트(`{"capabilities":[...]}`)였는데, 실제 스펙은 중첩 맵
     (`{"commands": {<name>: {"args":..., "permissions":...}}, "framingmediatypes":[...]}`)
     — 정정 완료. 서버 측 `changegroup`/`getbundle`/`listkeys`/`pushkey`가 전부 스텁이라
     뭘 요청해도 `{"status":0}`만 돌려주고 있었음 — `HgLocalClient`(이미 검증된 구현체)에
     위임하도록 수정해 실제로 동작하게 함.
  3. **알려진 한계(의도적으로 미해결)**: 실제 `hg help internals.wireprotocolrpc`(hgrpc)
     스펙은 8바이트 바이너리 프레임 헤더(24비트 length + 16비트 request ID + 8비트
     stream ID + 8비트 stream flags + 4비트 type + 4비트 flags)로 여러 요청/스트림을
     하나의 파이프에 다중화하는 프로토콜을 정의한다. hg4j는 이 프레임 봉투 없이 단순
     "HTTP POST에 CBOR 하나, 응답도 CBOR 하나"로 근사했다 — 실제 hgrpc 프로토콜의 정식
     구현은 아니다. 검증할 실제 서버가 없는 상태에서 복잡한 다중화 프로토콜을 만드는 건
     검증 안 된 추측을 쌓는 것이라 판단해 보류함.
- **검증**: `HgHttpTransportV2RoundtripTest`에 capabilities/heads뿐 아니라
  `getbundle`(실제 changegroup 바이트 전송·파싱)과 `listkeys`/`pushkey`(실제 bookmark
  조회·갱신)까지 실제로 동작하는지 검증하는 테스트 추가 — 이전엔 이 커맨드들이 테스트조차
  안 돼 있어서 스텁 상태가 안 들켰음.

## B-3. Bookmark 완전 지원 (2026-09-01 완료 — 실제 hg CLI 상호운용 검증 + 심각한 버그 2건 발견·수정)
- **상태**: ✅ commit 자동 전진, update 활성화/비활성화, pull/push 동기화 전부 구현·
  실제 `hg` CLI와 상호운용 검증 완료(`BookmarkRealHgInteropTest`, 6개 테스트 —
  fast-forward, 진짜 divergence, 원격 push/pull 전부 실제 hg로 대조).
- **검토 중 발견한 심각한 사전 버그 2건(둘 다 수정)**:
  1. **데이터 손실 버그**: `FetchCommand`의 예전 bookmark 병합 로직이 "원격이 가리키는
     노드를 로컬이 갖고 있으면 무조건 덮어쓰기"만 해서, 로컬에서 독자적으로 이동시킨
     bookmark가 pull 한 번에 조용히 사라질 수 있었다. `mercurial/bookmarks.py`의
     `comparebookmarks()`/`validdest()`를 참고해 ancestor 기반 fast-forward/진짜
     divergence 구분 로직(`BookmarkCommand.mergeFromRemote()`)으로 교체.
  2. **동기화 누락 버그**: `FetchCommand.call()`의 "새 changeset 없음" 조기 리턴 경로들이
     bookmark/phase 동기화 자체를 건너뛰어서, 커밋 없이 bookmark만 이동한 원격을
     pull해도 전혀 반영이 안 됐다 — 흔한 실사용 시나리오인데도. 모든 조기 리턴 경로에서
     동기화가 실행되도록 수정.
  3. **파생 발견(Track C 승격, 별도 항목 참고)**: 위 버그를 재현하다가 cg1 changegroup의
     델타 베이스 규칙 자체가 틀려 있던 것도 함께 발견·수정함(다중 head 저장소 pull 시
     콘텐츠가 깨지는 문제) — [[mercurial-spec-compliance-requirement]]의 Changegroup
     항목 참고.
- **영향받는 클래스**: `api.CommitCommand`, `api.UpdateCommand`, `api.PullCommand`,
  `api.PushCommand`, `api.FetchCommand`, `api.BookmarkCommand`(`mergeFromRemote()` 신설).

## B-4. 트랜잭션 저널링 / 크래시 복구 완성 (2026-09-01 완료 — 실제 hg CLI 상호운용 검증)
- **상태**: ✅ `RollbackCommand` 구현 확인, 실제 hg 대조 검증(`RollbackRealHgInteropTest`).
- **발견한 실제 갭(수정 완료)**: `CommitCommand`만 undo 정보를 기록해서 **pull 직후에는
  rollback이 아예 동작하지 않았다** — `hg rollback`의 가장 흔한 실사용 시나리오("잘못된
  브랜치를 pull했다")인데도. `FetchCommand`도 성공적인 fetch마다 undo 정보를 기록하도록
  수정, 실제 hg pull→hg4j rollback 시나리오로 검증.
- **Remove/Rename/Merge/Strip의 저널 확장**: 이미 구현돼 있음(코드 확인) — Strip의 경우
  journal+undo 정보 둘 다 정상 기록되는 것을 코드 레벨로 확인. Histedit는 아직 journal
  미적용 — Track C로 하향(빈도 낮은 destructive 경로, 우선순위 사용자 확인 필요).

## B-5. Obsolescence Marker 생성 경로 완성 (2026-09-01 완료 — obsstore 바이너리 포맷 자체가 틀렸던 것 발견·전면 수정)
- **상태**: ✅ Rebase/Graft/Histedit/Strip 전부 `HgObsMarker.writeMarker()` 호출 확인.
  **그런데 그 `writeMarker()`/`HgObsolescenceParser`가 구현한 obsstore 바이너리 포맷 자체가
  실제 Mercurial과 완전히 달랐다** — 파일 버전 바이트가 아예 없고, 필드 순서·크기도 전부
  틀림. 실제 hg가 만든 obsstore는 hg4j가 읽으면 즉시 파싱 실패, hg4j가 만든 obsstore는
  실제 hg가 읽으면 깨진 것으로 인식될 상황이었다(둘 다 실제 재현·확인).
- **수정**: 실제 hg CLI(`--config experimental.evolution.createmarkers=true`)로 amend를
  수행해 얻은 진짜 obsstore 바이트를 `mercurial.obsolete._readmarkers()`(Python 표준
  구현)로 직접 디코딩해 FM1(version=1) 포맷을 확인 — 고정 헤더 19바이트
  (`_fm1fixed = '>IdhHBBB'`: totalsize+date+tz+flags+numsuc+numpar+nummeta) + predecessor
  + successors + (parents는 생략) + 메타데이터. `HgObsMarker.writeMarker()`와
  `HgObsolescenceParser.parse()`를 이 스펙대로 전면 재작성.
- **검증**: `HgObsolescenceRealHgInteropTest` — (1) 실제 hg가 만든 obsstore 픽스처를
  hg4j로 파싱해 정확한 값 확인, (2) hg4j가 obsstore에 쓴 마커를 실제 `hg debugobsolete`로
  읽어 정상 인식되는지 확인 — 양방향 다 통과.
- **영향받는 클래스**: `obsolete.HgObsMarker`, `obsolete.HgObsolescenceParser`.

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
| Bundle1(레거시 `HG10UN/GZ/BZ`) | 미구현으로 추정 — 실제 필요 여부(구버전 서버 호환용) 사용자 확인 | 신규 클래스 필요 시 `bundle` 패키지 |
| Censor(민감정보 삭제) | 미구현으로 추정 — 구현 범위/우선순위 사용자 확인 | 신규 기능 |
| Sparse checkout 설정 파일(`.hg/sparse`) | 파싱 로직 존재 여부 확인 | `treewalk.SparsePathFilter` |
| hgrc `%include`/`%unset` 등 세부 지시자 | 커버리지 확인 | `lib.HgRcConfig`(Track A 완료 후) |
| Merge state `state2` 업그레이드 | **확인됨(2026-08-31)**: `ResolveCommand`가 레거시 v1 `.hg/merge/state`만 쓰고 최신 `state2`는 안 씀 — 재개 자체는 되므로 우선순위 낮음, `state2`로 확장할지 결정 | `api.ResolveCommand` |
| 누락된 코어 포셀린 명령 (`forget`/`backout`/`addremove`/`verify`/`root`/`summary`/`tip`/`parents`) | **확인됨(2026-08-31)**: 대응 클래스/`Hg` 파사드 메서드 전무. 어떤 것부터 추가할지, 라이브러리 성격상 필요 없는 것(`root`/`tip`처럼 다른 API로 대체 가능한 것)은 뭘 제외할지 사용자 확인 필요 | 신규 `XxxCommand` 클래스들 |
| `[paths]` 별칭이 pull/push에 연결 안 됨 | **확인됨(2026-08-31)**: `HgRcConfig.getPath()`로 읽기는 가능하나 `PullCommand`/`PushCommand`가 호출을 안 해서 "default" 같은 별칭 사용 불가 — 라이브러리 사용 맥락상 필요한 기능인지 사용자 확인(CLI 편의 기능 성격이 강함) | `api.PullCommand`, `api.PushCommand`, `lib.HgRcConfig` |

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
