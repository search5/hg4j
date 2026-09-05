---
name: exhaustive-interop-matrix-plan
updated: 2026-09-05
status: **완료(2026-09-05, wave 1~5)**. requirement 매트릭스(36개 조합,
  native 6 + Docker 30)는 로컬/저장소 전용 대상 60개 명령 전부에, wire
  매트릭스(21개 조합, HTTP 18 + SSH 3)는 전송 관여 대상 8개 명령 전부에
  적용 완료 — 합계 68개 명령(§3의 "67개"라는 원래 소제목은 §3-2 목록이
  실제로는 60개를 나열하고 있었다는 걸 뒤늦게 발견한 표기 오류였음, 최종
  병합 시점에 프로그램적으로 재확인) 전부 양방향(쓰기 포함) GREEN. 그
  과정에서 진짜 hg4j 프로덕션 버그 40건 이상 발견·수정(모두 즉시 완전
  수정, 범위 축소 없음) — 가장 파급력 컸던 것들: `DeltaCodec
  .decompressZstd`의 델타-리비전 버퍼 크기 버그(4개 병렬 wave가 서로
  독립적으로 발견할 만큼 일반적인 real-world zstd 저장소 다수에 영향),
  `BackoutCommand`/`PurgeCommand`의 데이터 손실 버그, `IncomingCommand`가
  콘텐츠 있는 real hg 서버 어디에도 완전히 깨져 있던 버그,
  `DirstateV2Node`의 exec/symlink 플래그 상호배타 처리 버그(dirstate-v2
  심볼릭 링크를 다루는 여러 기존 명령에 공유). 상세 이력은 §4의 웨이브별
  기록 및 [[mercurial-spec-compliance-requirement]] 백로그 #39 참고.
  백로그 29~36과는 별개 축(개별 기능 gap이 아니라 "조합 공간을
  체계적으로 훑는 인프라" 자체가 목적)이었으나, 이 매트릭스가 직접 새
  백로그(#37, #38)를 낳았다는 점에서 서로 피드백 관계에 있었다.
---

# 요건: 포셀린 명령 x wire protocol 조합 x requirement 조합 exhaustive interop 매트릭스

> **배경** (2026-09-04, 대화 중 도출). "지금 코드가 실무 활용 가능한 수준이냐"는 질문에
> 답하면서, 이 프로젝트의 검증 이력 자체가 문제였다: 백로그 1~21 완료 → 재검증에서
> 22~25 발견 → 22~25 완료 → 재검증에서 26~28 발견 → 26~28 완료 → 재검증에서 29~36
> 발견, 총 5라운드 연속으로 "끝났다"고 여긴 지점마다 실제 gap이 더 나왔다. 근본 원인은
> 지금까지의 검증이 "사람이 특정 영역을 골라서 판다 → 실버그가 나온다"는 반응적
> (reactive) 발견 구조라 구조적으로 수렴하지 않는다는 것. 이 문서는 그 대안으로
> "모든 포셀린 명령 x 모든 wire protocol 조합 x 모든 requirement 조합"을 사람이 매번
> 새로 떠올릴 필요 없이 기계적으로 훑는 매트릭스를 설계한다.
>
> **범위 결정** (2026-09-04, `AskUserQuestion`으로 사용자 확인): 말 그대로의 전수
> 교차(68개 명령 x 모든 wire 조합 x 모든 requirement 조합)는 수만 개의 조합이 나오고
> 그중 다수가 hg 스펙상 성립 자체가 안 되거나(예: `persistent-nodemap`은
> Rust 확장 없이는 저장소 생성 자체가 안 됨) 명령과 아예 무관한(예: `TagCommand`는
> wire protocol과 무관) 조합이라 실행 낭비가 크다는 점을 근거로, **"의미 있는 조합만"**
> (권장안)으로 확정 — 명령을 "전송 관여"/"로컬·저장소 전용" 두 카테고리로 나누고, 각
> 카테고리에 실제로 적용되는 축(wire 매트릭스 vs requirement 매트릭스)만 곱한다.

## 1. Requirement 매트릭스 (로컬·저장소 전용 명령에 적용)

> **2026-09-04 수정**: 최초 버전은 general-v2/fileindex-v1/persistent-nodemap 3개를
> "Docker가 필요하다"는 이유로 매트릭스에서 통째로 제외했었다 — 사용자 지적으로
> 정정. `localhost/hg-rust-7.2.4` 이미지(이미 빌드돼 있음, `docker/hg-rust-7.2.4/
> Dockerfile`)를 실제로 띄워(`docker run -d --rm hg-rust-7.2.4 sleep infinity`)
> 컨테이너 내부에서 `hg init --config ...`를 직접 실측한 결과를 반영해 **4번째 축
> (storage-확장)을 정식으로 추가**했다. "Docker가 있어야 한다"는 이 축 자체를 매트릭스
> 밖으로 밀어낼 이유가 아니라 각 셀의 실행 조건(native vs Docker)일 뿐이다.

### 1-1. 4개 축과 상호 제약 (2026-09-04, `hg-rust-7.2.4` 컨테이너 내부 실측)

| 축 | 선택지 | config 키 |
|---|---|---|
| dirstate | v1(기본) / v2 | `format.use-dirstate-v2=yes` |
| changelog | v1(기본) / changelog-v2 / changelog-v2+sidedata-copies | `format.exp-use-changelog-v2=...` (+`format.exp-use-copies-side-data-changeset=yes`가 changelog-v2를 자동 암시) |
| manifest | flat(기본) / treemanifest | `experimental.treemanifest=1` |
| storage-확장 | none(기본) / persistent-nodemap / fileindex-v1 / general-v2 | 아래 참고 |

storage-확장 축의 config 키와 자동 암시/상호배타 관계(전부 컨테이너 내부 실측,
2026-09-04):
- `persistent-nodemap`: `format.use-persistent-nodemap=true` 단독 지정 가능(revlogv1
  그대로 유지, fileindex-v1은 안 붙음).
- `fileindex-v1`: `format.use-fileindex-v1=yes` — 지정하면 **`persistent-nodemap`이
  자동으로 함께 켜짐**(둘을 분리할 방법이 없음, requires에 둘 다 나타남).
  **`experimental.treemanifest`와는 상호 배타**(`abort: cannot create repository
  with 'format.use-fileindex-v1' and 'experimental.treemanifest' both enabled
  since they are incompatible with each other`) — 즉 manifest 축이 flat일 때만
  유효.
- `general-v2`(`experimental.revlogv2=enable-unstable-format-and-corrupt-my-data`):
  **`fileindex-v1`+`persistent-nodemap`을 둘 다 자동으로 함께 켬**(같은 이유로
  treemanifest와도 상호 배타 — fileindex-v1을 통해 간접 전파).
- 이 4가지 값 모두 dirstate/changelog 두 축과는 자유롭게 조합됨(실측: `generalv2-
  dirstatev2`, `generalv2-changelogv2`, `generalv2-changelogv2-sidedata`,
  `pnodemap-dirstatev2`, `pnodemap-changelogv2`, `fileindex-dirstatev2`,
  `fileindex-changelogv2` 전부 OK).

manifest x storage-확장의 유효한 짝은 6개(flat 4개 + tree 2개, tree+fileindex-v1/
tree+general-v2는 무효)뿐이므로, 전체 유효 조합 수는:

**dirstate(2) x changelog(3) x [manifest x storage-확장 유효 짝(6)] = 36개**

> **2026-09-04 추가 정정** (native-12조합 담당 fork가 TDD 진행 중 발견): 위
> "storage-확장 none일 때 dirstate-v2는 native로 가능"이라는 §1-1 최초 서술이
> 틀렸다 — `format.use-dirstate-v2=yes`도 이 호스트 순정 파이썬 `hg` 7.2.2로는
> `abort: accessing 'dirstate-v2' repository without associated fast
> implementation`로 저장소 생성 자체가 실패한다. **dirstate-v2도 Rust 확장
> (Docker)이 있어야 하는 4번째 storage-확장급 제약이었다.** §1-2에서 native/Docker
> 경계를 이 사실대로 다시 나눈다(총 36개는 변하지 않음, native/Docker 분할만
> 6/30으로 정정).

### 1-2. 실행 환경 구분 (같은 매트릭스 안의 속성일 뿐, 별도 매트릭스가 아님)

| storage-확장 | dirstate | 실행 환경 |
|---|---|---|
| none | v1만 | 이 호스트 순정 파이썬 `hg` 7.2.2로 즉시 실행(Docker 불필요) |
| none | v2 | Docker 필요(아래 재정정 참고) |
| persistent-nodemap / fileindex-v1 / general-v2 | v1 또는 v2 | `hg-rust-7.2.4` 컨테이너 내부에서만 저장소 생성 가능(순정 파이썬 `hg`는 `abort: accessing '...' repository without associated fast implementation`로 실패, 2026-09-04 재확인) |

> **2026-09-04 재정정**(native-6조합 담당 fork의 TDD 결과): `format.use-dirstate-v2=
> yes`도 이 호스트 순정 파이썬 `hg`로는 `abort: accessing 'dirstate-v2' repository
> without associated fast implementation`로 실패한다 — dirstate-v2는 storage-확장
> 4종과 마찬가지로 Docker(Rust 확장)가 있어야 한다. 그래서 진짜 native 조합은
> **dirstate=v1 고정 × changelog(3) × manifest(2) = 6개뿐**이고, 나머지 30개
> (dirstate=v2인 6개 + storage-확장이 none이 아닌 24개)는 전부 Docker가 필요하다.

native 6개 + Docker 필요 30개(dirstate-v2×changelog×manifest 6개 + persistent-nodemap
2x3x2=12개 + fileindex-v1 2x3x1=6개[flat만] + general-v2 2x3x1=6개[flat만]) = **36개**.

### 1-3. 전체 36개 조합표 (기존 테스트 커버리지 매핑 포함)

| # | dirstate | changelog | manifest | storage-확장 | 실행환경 | 기존 커버리지 |
|---|---|---|---|---|---|---|
| 1 | v1 | v1 | flat | none | **native** | `RequirementMatrixCoreRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04 완료 |
| 2 | v1 | v1 | tree | none | **native** | `RequirementMatrixCoreRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04 완료. 기존 `TreemanifestRealFixtureTest`(읽기 방향만)와 별개로 완전 재검증됨 |
| 3 | v1 | changelog-v2 | flat | none | **native** | `RequirementMatrixCoreRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04 완료(작업 중 `Revlog.appendRevisionV2`/`RevlogIndex` zstd/zlib 코덱 버그 2건 발견·수정). 기존 `ChangelogV2BootstrapTest`(읽기 방향만)와 별개로 완전 재검증됨 |
| 4 | v1 | changelog-v2 | tree | none | **native** | `RequirementMatrixCoreRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04 완료 |
| 5 | v1 | changelog-v2+sidedata | flat | none | **native** | `RequirementMatrixCoreRoundTripTest`(신규 양방향) + 기존 `SidedataFilesWriteTest`/`PullSidedataRealHgInteropTest`(이미 쓰기+양방향) — 이중 커버, 2026-09-04 |
| 6 | v1 | changelog-v2+sidedata | tree | none | **native** | `RequirementMatrixCoreRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04 완료 |
| 7 | v2(dirstate) | v1 | flat | none | Docker | `RequirementMatrixDockerRoundTripTest` — 양방향(쓰기 포함) GREEN, 2026-09-04. 기존 `DirstateV2RealFixtureTest`(읽기 방향)도 라이브 쓰기 검증 참조 추가 |
| 8~12 | v2(dirstate) x (v1/changelog-v2/changelog-v2+sidedata) x (flat/tree), storage-확장=none | — | — | Docker | 위와 동일: 양방향(쓰기 포함) GREEN, 2026-09-04 |
| 13~24 | v1/v2 x 3changelog x flat/tree | — | persistent-nodemap | Docker | `RequirementMatrixDockerRoundTripTest` — 12개 전부 양방향(쓰기 포함) GREEN |
| 25 | v1 | v1 | flat | fileindex-v1 | Docker | `RequirementMatrixDockerRoundTripTest`(양방향 GREEN) + 기존 `FileIndexTest`/`NodeMapFileFixtureTest`/`NodeMapFileWriterTest`/`RevlogV2GeneralParserTest`에 라이브 쓰기 검증(`realHgRustAcceptsHg4jWrittenGeneralV2Repository`) 추가 완료 |
| 26~30 | 나머지 5개(dirstate/changelog 조합 x fileindex-v1, flat 고정) | — | — | Docker | 5개 전부 양방향(쓰기 포함) GREEN |
| 31 | v1 | v1 | flat | general-v2 | Docker | `RequirementMatrixDockerRoundTripTest`(양방향 GREEN) + 기존 `RevlogV2GeneralParserTest`에 라이브 쓰기 검증 추가 완료(#25와 같은 커밋으로 보강) |
| 32~36 | 나머지 5개(dirstate/changelog 조합 x general-v2, flat 고정) | — | — | Docker | 5개 전부 양방향(쓰기 포함) GREEN |

**요약(2026-09-04 갱신, 전부 완료)**: native 6개(#1~6) + Docker 30개(#7~36) =
**requirement 매트릭스 36개 조합 전부 양방향(쓰기 포함) GREEN**. Docker 30개 쓰기
방향에서 처음 발견됐던 dirstate=v2 18개 SKIP은 **백로그 #37 근본 원인 규명·수정으로
전부 해소**됨 — real hg의 dirstate-v2 리더가 자식 노드 배열을 basename 오름차순
이진 탐색으로 찾는데 hg4j `DirstateV2Serializer`가 정렬 없이 썼던 것이 원인
(`hg-rust-7.2.4` 컨테이너의 실제 Rust 소스 직접 대조로 확정), 정렬 로직 추가로
수정. fileindex-v1/general-v2 셀(#25/#31)의 기존 정적 fixture 테스트 4개(`FileIndexTest`
등)도 라이브 쓰기 검증으로 보강 완료. **requirement 매트릭스 자체는 이제 100% 완료**
(60개 로컬 명령 중 commit/log/status/cat 4개에 한해서 — 나머지 56개 명령으로 확장하는
것은 별도 과제로 남음, §4 참고).

### 1-4. 제외: narrowhg-experimental

`narrowhg-experimental` requirement는 `hg init` 시점이 아니라 `hg clone --narrow`
시점에만 붙는 "clone 속성"이라, 위 3축과 같은 방식으로 조합되지 않는다 — narrow는
기존 `NarrowCloneRealHgInteropTest`/신규 백로그 30번(narrow wire-level 재통합)이
별도로 다룬다.

## 2. Wire protocol 매트릭스 (전송 관여 명령에 적용)

기존 `HgHttpV1NegotiationForcingInteropTest`가 이미 각 축을 **개별로** 강제하는
테스트를 갖고 있다(예: 압축만 zlib로 강제, tier만 legacy-GET으로 강제) — 이 매트릭스는
그 축들을 **교차**시켜서 조합 상호작용까지 검증한다는 점이 다르다.

| 축 | 선택지 | 강제 방법 |
|---|---|---|
| HTTP arg 전송 tier | `httppostargs` / `httpheader=N` / legacy GET | `--config experimental.httppostargs=True` / 기본값 / `CapabilityStrippingHttpProxy`로 `httpheader=` 제거 |
| 압축 | `zlib` / `zstd` / `none` | `--config server.compressionengines=<engine>` |
| bundle2 | on(기본) / off(legacy cg1 강제) | 기본 / `CapabilityStrippingHttpProxy`로 `bundle2=` 제거(백로그 26번 수정의 반대 방향 검증) |
| 전송 | HTTP / SSH | `RealHgServeSupport`(HTTP) / `HgSshClient`+임베디드 SSHD(SSH) |

HTTP: tier(3) x 압축(3) x bundle2(2) = **18개 조합**.
SSH: 압축(3)만 — SSH에는 arg 전송 tier 구분이 없고(HTTP 전용 개념), 현재 테스트
인프라로 SSH 쪽 bundle2 off 강제가 검증된 바 없어 **압축 3개 조합**만 우선
포함(bundle2 off + SSH 조합은 인프라 확장이 더 필요해 별도 후속 항목으로 남김).

HTTP 18 + SSH 3 = **21개 조합**.

### 2-1. 기존 테스트 커버리지 매핑 (2026-09-04)

`HgHttpV1NegotiationForcingInteropTest`는 각 축을 **개별로** 강제하면서 나머지
축은 real hg 서버의 기본값에 맡긴다 — 그 기본값(서버가 `experimental.httppostargs`
없이 기본 실행되면 `httpheader=` tier로 떨어지고, 압축 엔진 우선순위상 zstd C
확장이 있으면 zstd가 선택됨, bundle2는 항상 on)을 근거로 각 기존 테스트가 실제로
어느 셀을 이미 지나갔는지 역산하면:

| 기존 테스트 | 실제로 커버한 셀(tier, 압축, bundle2) |
|---|---|
| `httppostargsForcedAdvertisedAndUsedForRealPullAndPush` | (httppostargs, zstd, on) |
| `legacyGetTierForcedWhenNeitherHttppostargsNorHttpheaderAdvertised` | (legacy-GET, zstd, on) |
| `compressionZlibForcedRealRoundTrip` | (httpheader, zlib, on) |
| `compressionZstdForcedRealRoundTrip` | (httpheader, zstd, on) — `HgHttpV1LiveServerInteropTest`류 기본 경로와 중복 |
| `compressionNoneForcedRealRoundTrip` | (httpheader, none, on) |
| `unbundlehashOffForcedRealHttpPushStillSucceeds` | 매트릭스 3축 밖(4번째 축인 `unbundlehash`는 별도 관심사) |

18개 HTTP 셀 중 5개가 이미 지나갔고, 13개는 아직 아무도 조합해본 적이 없다 — 특히
**"legacy-GET tier + bundle2 off"**, **"httppostargs + none 압축"** 같은 조합은
개별 축 테스트로는 절대 드러나지 않는다(각 테스트가 한 축만 튀기고 나머지는 기본값을
쓰기 때문). SSH 3셀(압축만)은 `HgSshClientRealHgInteropTest`/
`HgSshWireServerRealHgInteropTest`가 기본 압축 설정으로 이미 왕복을 검증했지만
zlib/none을 SSH에서 개별로 강제한 테스트는 없다 — 사실상 SSH는 (기본압축)
1셀만 커버, 나머지 2셀 미커버.

## 3. 명령 분류 (전체 68개 포셀린 명령)

### 3-1. 전송 관여 (wire 매트릭스 적용 대상, 8개)
`CloneCommand`, `PullCommand`, `PushCommand`, `FetchCommand`, `IncomingCommand`,
`OutgoingCommand`, `ClonebundlesCommand`, `NarrowCloneCommand`.

### 3-2. 로컬/저장소 전용 (requirement 매트릭스 적용 대상, 60개)
`AddCommand`, `AddremoveCommand`, `AmendCommand`, `AnnotateCommand`, `ArchiveCommand`,
`BackoutCommand`, `BisectCommand`, `BookmarkCommand`, `BranchCommand`, `BranchesCommand`,
`BundleCommand`(로컬 파일 산출 — wire 아님), `CatCommand`, `CensorCommand`,
`CommitCommand`, `CopyCommand`, `DescribeCommand`, `DiffCommand`, `ExportCommand`,
`FilesCommand`, `ForgetCommand`, `GcCommand`, `GraftCommand`, `GrepCommand`,
`HeadsCommand`, `HisteditCommand`, `IdentifyCommand`, `ImportCommand`, `InitCommand`,
`LocateCommand`, `LogCommand`, `ManifestCommand`, `MergeCommand`, `ParentsCommand`,
`PathsCommand`, `PhaseCommand`, `PurgeCommand`, `RebaseCommand`, `RecoverCommand`,
`RemoveCommand`, `RenameCommand`, `ResolveCommand`, `RevertCommand`, `RevsetCommand`,
`RollbackCommand`, `RootCommand`, `ShelveCommand`, `SidedataChangedFilesCommand`,
`StatusCommand`, `StripCommand`, `SubrepoCommand`, `SummaryCommand`, `TagCommand`,
`TagsCommand`, `TipCommand`, `TreeCommand`, `TreeMergeCommand`,
`UnbundleCommand`(로컬 파일 입력 — wire 아님), `UpdateCommand`, `VerifyCommand`,
`WorktreeCommand`.

60개를 36개 requirement 조합 전부에 매번 적용하면 2,160개 케이스라 한 번에
구현하기엔 너무 크다 — 아래 "구현 우선순위" 절 참고.


## 4. 구현 우선순위 및 진행 상황

**완료(2026-09-06)**. 웨이브 1~5에 걸친 상세 진행 이력(15개 이상의 병렬 에이전트,
발견·수정한 40건 이상의 실제 버그, 병합 시 겪은 함정들)은
[[backlog/39-exhaustive-interop-matrix]]로 이관됐다 — 이 문서(설계 문서)는 이관 후
§1~3(축/조합/명령 분류 설계)만 남기고 대폭 축약했다.

현재 GREEN/RED 상태 자체는 프로즈가 아니라 `matrix-status.md`에 구조화된 표로
유지한다.

## 관련 페이지
- [[backlog/39-exhaustive-interop-matrix]] — 이 매트릭스를 실제로 채운 웨이브별 진행
  이력 전체
- `matrix-status.md` — 68개 명령의 현재 GREEN/RED 상태(구조화 데이터)
- `known-bugs-registry.md` — 이 캠페인에서 발견한 버그들의 클래스/메서드 단위 색인
- [[mercurial-spec-compliance-requirement]] — 백로그 인덱스(백로그 #39가 이 문서와
  대응)
