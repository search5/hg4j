---
updated: 2026-09-04
status: completed
---

# 계획: `core` 패키지 분리 순서 (JGit 정합성 요건 이행)

> [[jgit-parity-requirement]]에서 위임받은 항목. "분리 순서는 니가 알아서, 확인은
> 사용자가 직접" — 이 문서는 Claude가 제안한 순서다.
>
> ✅ **2026-09-04 확인 — 전 단계(Phase 1~12) 실행 완료.** `src/main/java/io/github/search5/hg4j/core/`
> 디렉터리 자체가 더 이상 존재하지 않는다. 실제 코드 기준 확인:
> - Phase 1~11에서 계획한 `dirstate`/`merge`/`util`/`submodule`/`phase`/`obsolete`/`revset`/
>   `bundle`/`lfs`/`gpg`/`storage`/`diff`/`treewalk` 패키지가 전부 `src/main/java/io/github/search5/hg4j/`
>   아래 개별 패키지로 존재.
> - Phase 0(`core.HgLockException` 정리)도 완료 — 전체 소스에서 `errors.HgLockException`
>   하나만 남아있고 `core.HgLockException`은 없음(참조 35건 모두 `errors.HgLockException`).
> - Phase 12(`core` → `lib` 병합)도 완료 — `HgRepository`/`Repository`/`HgRcConfig`/`HgLock`/
>   `HgLockException`이 `lib` 패키지에 있고, 이 문서가 가장 흔한 실수로 지목했던
>   `build.gradle`의 `jacocoTestCoverageVerification` FQCN 목록도 `core.*` 잔재 없이 전부
>   새 패키지 경로로 갱신돼 있음(`io.github.search5.hg4j.dirstate.Dirstate`,
>   `io.github.search5.hg4j.merge.Merge3` 등 확인).
> - 이 분리 작업이 정확히 어느 커밋(들)에서 이뤄졌는지는 여러 커밋에 걸쳐 있어
>   단일 커밋으로 특정하지 않음 — 결과 상태만 확인.
>
> 아래 원 계획 내용은 실행 시점의 설계 근거 기록으로 그대로 보존한다.

## 원칙
1. **저위험(의존성 적음) → 고위험(광범위 참조) 순서로 이동**한다. 코드 자체는 그대로 두고
   `package` 선언 + import 경로만 바꾸는 순수 이동(리네이밍 아님)을 우선한다.
2. **Mercurial 고유 개념은 Mercurial 용어로 패키지명을 짓는다** (JGit에 없는 개념을 억지로
   JGit식 이름에 끼워 맞추지 않음 — [[jgit-parity-requirement]] 결정 사항 참고). 단, JGit에
   정확히 대응하는 패키지가 있으면(`lfs`, `errors` 등) 그 이름을 그대로 쓴다.
3. **각 단계마다 `build.gradle`의 `jacocoTestCoverageVerification` 클래스 목록을 함께
   갱신**해야 한다 — `Dirstate`, `NodeIdUtil`, `SafeFileIO`, `Merge3`, `Bundle2Parser`,
   `ChangegroupParser`가 완전 정규화 클래스명(FQCN)으로 하드코딩되어 있어, 패키지 이동 시
   이 목록을 안 고치면 커버리지 게이트가 조용히 무력화되거나 빌드가 깨진다. **이 부분을
   빠뜨리는 것이 이 리팩토링에서 가장 흔히 발생할 실수**로 예상됨.
4. 각 단계는 별도 커밋으로 — 한 커밋에 여러 패키지를 한꺼번에 옮기지 않는다(리뷰/롤백
   용이성).

## 사전 정리 (Phase 0)
- `core.HgLockException`(레거시, `IOException` 상속)과 `errors.HgLockException`(정식
  checked exception 계층)의 중복 정리 — [[errors]] 문서 참고. `core.HgLockException`을
  참조하는 곳을 전부 `errors.HgLockException`으로 교체 후 `core.HgLockException` 삭제.
  이후 단계에서 `HgLock` 클래스를 옮길 때 이 정리가 끝나 있어야 깔끔하다.

## 단계별 이동 순서
| Phase | 신설 패키지 | 이동 대상 클래스 | 이동 근거 |
|---|---|---|---|
| 1 | `dirstate` | `Dirstate`, `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | 서로만 강하게 결합, 외부에서는 `HgRepository`가 단방향으로만 참조 — 가장 안전 |
| 2 | `merge` | `Merge3` | 순수 알고리즘, 의존 거의 없음 |
| 3 | `util` | `SafeFileIO`, `NodeIdUtil` | 유틸 자체 의존은 낮으나 **참조하는 파일 수가 많아** import 변경 범위가 큼 — 그래서 초반에 처리해 이후 단계 혼선을 줄임 |
| 4 | `submodule` | `HgSubrepoParser`, `HgSubrepoEntry` | 독립적 기능 단위 |
| 5 | `phase` | `PhaseRoots` | 독립적. `api.PhaseCommand`와 이름이 유사하니 문서/주석에 패키지 구분을 명확히 표기 |
| 6 | `obsolete` | `HgObsolescenceParser`, `HgObsMarker` | 독립적 기능 단위, Mercurial 자체 용어(`obsolete`) 사용 |
| 7 | `revset` | `HgRevsetEngine` | `HgRepository`만 참조, 독립적 |
| 8 | `bundle` | `Bundle2Parser`, `ChangegroupParser` | transport와 인접하지만 디스크에도 단독으로 쓰이는 자기완결 포맷이라 별도 패키지가 적절 |
| 9 | `lfs` | `HgLfsManager`, `HgLfsPointer` | **JGit도 정확히 `org.eclipse.jgit.lfs` 패키지를 갖고 있어 이름까지 일치시킬 수 있는 유일한 기회** |
| 9 | `gpg` | `GpgSignature` | JGit의 `org.eclipse.jgit.gpg.bc` 대응 |
| 10 | `storage` | `Revlog`, `RevlogIndex`, `DeltaCodec`, `StoreEngine`, `DefaultFileStoreEngine` | 가장 광범위하게 참조됨 — 마지막으로 이동 |
| 10 | `diff` | `DeltaEngine` | Myers-diff 알고리즘 자체를 storage 포맷 인코딩(`DeltaCodec`)과 분리 |
| 11 | `treewalk` (기존 패키지로 이동, 신설 아님) | `HgTreeFilter` | ✅ **결정됨** (2026-08-31) — Javadoc에 "Inspired by JGit's TreeFilter api"라고 명시되어 있고 이미 `treewalk.PathFilter`를 구현 중. JGit의 대응 개념(`org.eclipse.jgit.treewalk.filter.TreeFilter`)이 `treewalk` 계열에 있으므로 그대로 맞춘다. `core → treewalk` 역방향 의존을 없애는 효과도 있음 |
| 12 | `core` → `lib`로 개명 (Phase 1~11 완료 후 최종 단계) | 잔여 `HgRepository`, `Repository`(인터페이스), `HgRcConfig`, `HgLock`, `HgLockException` | ✅ **결정됨** — 아래 "core→lib 개명" 절 참고 |

## Phase 12: 잔여 `core`를 `lib`로 개명
**이름 충돌 검토 결과: 실제 충돌 없음.** 현재 `lib` 패키지의 클래스는 `NodeId`,
`ProgressMonitor`, `NullProgressMonitor`, `TextProgressMonitor` 4개뿐이고, Phase 1~11
완료 후 `core`에 남는 클래스는 `HgRepository`, `Repository`, `HgRcConfig`, `HgLock`,
`HgLockException` 5개 — **단순 이름이 겹치는 클래스가 하나도 없다.** 따라서 "충돌
해소 전략"이 따로 필요한 게 아니라, 그냥 두 패키지를 합치면 된다.

**추천: 그대로 병합 이동.** 오히려 이렇게 합쳐진 모습이 JGit에 더 가깝다 — JGit의
실제 `org.eclipse.jgit.lib` 패키지 자체가 `Repository`, `ObjectId`(hg4j의 `NodeId`에
대응), `ProgressMonitor`, `Config`류를 전부 한 패키지에 모아둔 구조이기 때문이다.
즉 지금 hg4j의 `lib`(NodeId/ProgressMonitor)와 잔여 `core`(HgRepository 등)를 합치는
것 자체가 JGit 원본 구조를 재현하는 작업이다.

체크리스트:
1. `core/{HgRepository,Repository,HgRcConfig,HgLock,HgLockException}.java`를
   `lib/`로 이동, `package` 선언만 변경 (클래스명 변경 없음).
2. 기존 `lib/{NodeId,ProgressMonitor,NullProgressMonitor,TextProgressMonitor}.java`는
   그대로 둔다 — 이동 대상 아님, 이미 목적지에 있음.
3. 전체 프로젝트에서 `io.github.search5.hg4j.core.HgRepository` 등 완전정규화 import를
   `io.github.search5.hg4j.lib.*`로 일괄 치환 (테스트 코드 포함 — `src/test/java`도
   동일 패키지 하위 구조라 영향 큼).
4. `build.gradle`의 `jacocoTestCoverageVerification`에 `core.*` FQCN이 있다면 `lib.*`로
   갱신 (원칙 3 재확인).
5. 이 시점 이후 `core` 패키지 디렉터리 자체가 비므로 삭제.

## 실행 안 하는 것
- `dirstate` 관련 필드/메서드가 `HgRepository`에 남아있는 것(`getDirstate`,
  `writeDirstate`)은 `Repository` 인터페이스 계약이므로 이동하지 않는다 — 옮기는 건
  구현 클래스(`Dirstate` 등)뿐이다.
- 클래스명 변경은 하지 않는다(패키지 이동만). [[jgit-parity-requirement]] 결정 사항에 따라
  `Hg` 접두어 등 기존 네이밍은 그대로 유지.

## 관련 페이지
- [[jgit-parity-requirement]] — 이 계획의 상위 근거
- [[core]] — 이동 전 현재 상태 전체 클래스 목록
- [[errors]] — Phase 0 사전 정리 상세
