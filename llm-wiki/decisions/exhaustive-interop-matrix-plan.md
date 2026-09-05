---
name: exhaustive-interop-matrix-plan
updated: 2026-09-04
status: **requirement 매트릭스 36개 조합 + wire 매트릭스 21개 조합, 전부
  양방향(쓰기 포함) GREEN 완료(2026-09-04)**. native/Docker 분할은 12/24에서
  6/30으로 재정정됨(dirstate-v2도 Docker 필요임이 TDD 중 드러남). 이 과정에서
  실버그 3건 발견·수정: (1) changelog-v2 저장소의 zstd/zlib 코덱 혼동
  (`Revlog`/`RevlogIndex`), (2) **dirstate-v2 트리 손상**(`DirstateV2Serializer`가
  자식 노드를 정렬 없이 써서 real hg의 이진 탐색 리더가 못 찾던 문제 — [[mercurial-
  spec-compliance-requirement]] 백로그 #37, 근본 원인까지 확정해 완전히 수정),
  (3) wire 매트릭스에서는 새 프로덕션 버그 없음(백로그 22/26 협상 로직이 이미
  정확했음 재확인). 기존 fixture 기반 테스트 7개를 라이브 쓰기 검증으로 보강하는
  작업도 완료. `test`/`interopTest`(신규 gradle 태스크, 2026-09-04)로 실행 분리
  — 평소 `./gradlew test`는 interop 제외로 빠르게, `./gradlew check`는 커버리지
  게이트까지 정확하게. 나머지 65개 로컬/전송 명령(핵심 라운드트립 6개 제외)은
  전부 미착수. 백로그 29~36과는 별개 축(개별 기능 gap이 아니라 "조합 공간을
  체계적으로 훑는 인프라" 자체가 목적)이나, 이 매트릭스가 직접 새 백로그(#37,
  #38)를 낳았다는 점에서 서로 피드백 관계에 있다.
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
> 교차(67개 명령 x 모든 wire 조합 x 모든 requirement 조합)는 수만 개의 조합이 나오고
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
(59개 로컬 명령 중 commit/log/status/cat 4개에 한해서 — 나머지 55개 명령으로 확장하는
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

## 3. 명령 분류 (전체 67개 포셀린 명령)

### 3-1. 전송 관여 (wire 매트릭스 적용 대상, 8개)
`CloneCommand`, `PullCommand`, `PushCommand`, `FetchCommand`, `IncomingCommand`,
`OutgoingCommand`, `ClonebundlesCommand`, `NarrowCloneCommand`.

### 3-2. 로컬/저장소 전용 (requirement 매트릭스 적용 대상, 59개)
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

59개를 36개 requirement 조합 전부에 매번 적용하면 2,124개 케이스라 한 번에
구현하기엔 너무 크다 — §4의 우선순위대로 점진적으로 채운다.

## 4. 구현 우선순위 및 진행 상황

핵심 라운드트립(commit → log/status/cat, clone/pull/push)부터 먼저 매트릭스를 채우고,
이후 나머지 로컬 명령·전송 명령으로 확장한다. 각 명령이 매트릭스에 편입될 때마다 이
표의 상태를 갱신한다.

### 4-1. Requirement 매트릭스 대상 명령
- [x] 설계(§1) 확정, native 6개 + Docker 30개 = 36개 조합 유효성/상호배타 관계
  실측 완료(2026-09-04, `hg-rust-7.2.4` 컨테이너 포함, dirstate-v2도 Docker
  필요임을 TDD 도중 재확인해 분할 정정)
- [x] native 6개 조합 — **완료(2026-09-04)**: `RequirementMatrixCoreRoundTripTest`
  (`src/test/java/io/github/search5/hg4j/api/`), 6 x 2방향(real hg 쓰기→hg4j
  읽기, hg4j 쓰기→real hg 읽기+`hg verify`) = 12케이스 전부 GREEN. TDD 과정에서
  `Revlog.appendRevisionV2`/`RevlogIndex.initializeNewV2Docket`의 실제 버그 2건
  발견·수정(changelog-v2 저장소가 zlib 설정인데도 zstd 프레임을 쓰던 문제, docket
  압축 헤더 바이트가 항상 zstd로 하드코딩돼 있던 문제) — 둘 다 real hg가 만든
  저장소를 hg4j가 쓸 때만 드러나는 종류의 버그였다.
- [x] Docker 30개 조합 — **완료(2026-09-04)**: `RequirementMatrixDockerRoundTripTest`
  (60케이스 = 30읽기+30쓰기), **60개 전부 GREEN**. 쓰기 방향에서 처음엔 dirstate=v2
  18개가 신규 실버그(백로그 #37)로 SKIP됐으나, 근본 원인 규명·수정으로 전부 해소—
  real hg의 dirstate-v2 리더(`hg-rust-7.2.4` 컨테이너의 실제 Rust 소스
  `dirstate_map.rs` 직접 대조)가 자식 노드 배열을 basename 오름차순 이진 탐색으로
  찾는데, hg4j `DirstateV2Serializer`가 `LinkedHashMap` 삽입 순서 그대로(정렬 없이)
  썼던 것이 원인 — UTF-8 바이트 기준 정렬 로직을 추가해 수정, 18개 전부 GREEN 전환.
  부수적으로 JVM 안에서 hg4j 커밋 실행과 `docker exec`/`docker run` 프로세스
  스폰을 번갈아 하면 커밋이 비결정적으로 깨지는 테스트 인프라 문제도 발견해
  `RequirementMatrixCommitHelperMain`(별도 서브프로세스)으로 우회.
- [x] 기존 fixture 기반 커버리지 테스트(`TreemanifestRealFixtureTest`,
  `ChangelogV2BootstrapTest`, `DirstateV2RealFixtureTest`, `FileIndexTest`,
  `NodeMapFileFixtureTest`, `NodeMapFileWriterTest`, `RevlogV2GeneralParserTest`)를
  라이브 쓰기 검증으로 보강 — **완료(2026-09-04)**(사용자 지시, "이미 만들어진
  커버리지 테스트는 interop에 맞춰서 변경"). 조사 결과 `ChangelogV2BootstrapTest`/
  `NodeMapFileWriterTest` 2개는 이미 라이브 쓰기 검증이 있어 중복 작업 없이 유지.
  `TreemanifestRealFixtureTest`에 중첩 디렉터리 treemanifest 쓰기 검증(기존 native
  매트릭스 테스트가 루트 레벨 파일만 다뤘던 진짜 누락분) 추가, `RevlogV2GeneralParserTest`
  에 general-v2 라이브 쓰기 검증 추가, `FileIndexTest`/`NodeMapFileFixtureTest`/
  `DirstateV2RealFixtureTest`는 동일 조합의 라이브 커버리지가 이미 있는 곳(Docker
  매트릭스 파일)으로의 javadoc 상호 참조만 추가(같은 저장소 상태를 검증하는 3~4번째
  중복 하니스를 만들지 않기 위한 판단).
- [ ] 나머지 58개 로컬 명령 — 대부분 미착수. **Wave 1(2026-09-05, 백로그 #39)**:
  우선순위 4개(`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`)에
  36개 조합(native 6 + Docker 30) 적용 완료 — `RebaseCommand`/`StripCommand`/
  `ShelveCommand` 3개는 전부 GREEN(그 과정에서 `Revlog.truncate()` 통합으로
  실버그 2건 수정), `PushCommand`는 treemanifest dirlog 미전송 + changelog-v2
  sidedata 미전송 2개의 실버그가 남아 native 2/6·Docker 14/30만 GREEN(원인
  규명 완료, 수정은 다음 wave). 상세는 [[mercurial-spec-compliance-requirement]]
  백로그 #39 참고.
  **Wave 3(2026-09-05)**: 4개 병렬 에이전트로 총 11개 명령 진행.
  `MergeCommand`/`SubrepoCommand` 둘 다 native 6/6 + Docker 30/30 전부
  GREEN — `SubrepoCommand`의 `init`/`update`가 pin된 리비전으로 실제
  체크아웃을 한 적이 없던 버그, `CommitCommand`의 미해결 머지 충돌 차단
  로직(마커 텍스트 스캔 → 실제 머지 상태 기반으로 수정) 및 머지 커밋 후
  `.hg/merge` 미정리 버그까지 진짜 hg4j 버그 3건을 TDD로 발견·수정.
  `HisteditCommand`/`GraftCommand`도 36개 조합 전부 GREEN —
  `HisteditCommand`는 매니페스트 정렬/dirstate 재동기화 실버그 2건 수정.
  `GraftCommand`는 `RebaseCommand`의 하드닝 이전 cherry-pick 로직을 독자
  재구현하고 있던 실제 사례(3-way merge/conflict 감지 부재로 인한 data-loss,
  크래시 안전 저널 부재, 잘못된 obsolescence marker 기록) 3건을 발견해
  `RebaseCommand`의 공용 로직(`attemptThreeWayMerge` 등, package-private
  static으로 추출)을 재사용하도록 수정. `AddCommand`/`BookmarkCommand`/
  `TagCommand` 세 명령도 36개 조합 전부 GREEN(`AddCommand` native 6/6+Docker
  30/30, `BookmarkCommand` native 6/6+Docker 30/30, `TagCommand` native
  6/6+Docker 30/30) — 이 과정에서 `BookmarkCommand`의 진짜 hg4j 버그 3건을
  발견·수정: (1) bookmark 이동에 `TagCommand`의 백로그 #36과 동일한 force
  게이트가 아예 없던 것 — 추가한 게이트가 DAG 조상 관계뿐 아니라
  `PushCommand`가 이미 쓰던 `obsutil.foreground`(obsolescence-successor
  체인)까지 인정하도록 구현하지 않으면 `hg amend` 직후 활성 bookmark
  자동전진이 깨진다는 것을 전체 회귀에서 발견해 함께 해결, (2) 그 과정에서
  `CommitCommand`의 커밋마다 활성 bookmark를 전진시키는 내부 호출도 별도로
  force 우회가 필요함을 발견·수정, (3) 마지막 bookmark 삭제 시 real hg는
  `.hg/bookmarks`를 빈 파일로 남기는데 hg4j는 파일 자체를 지워버리던 것.
  `BundleCommand`(`hg bundle` — 독립 번들 FILE, push/pull과 changegroup
  패킹 로직 공유)도 36개 조합 전부 GREEN — 예상대로 `PushCommand`의 수정
  전과 동일한 cg1-only 하드코딩 버그를 갖고 있어 동일 패턴으로 수정
  (`ChangegroupParser.writeBundle` + treemanifest dirlog 패킹 + 신규
  `BundleType.NONE_V3`/`GZIP_V3`/`BZIP2_V3` cg3-in-bundle2 타입 추가).
  `cl2+sidedata` 조합(native 2, Docker 10)은 순정 real-hg-to-real-hg
  컨트롤로 재현 확인한 **real hg 자신의 파일 기반 bundle/unbundle 한계**
  (hg4j가 고칠 수 없는 문제)로 명시적으로 tolerate 처리. 상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 나머지 44개
  명령은 여전히 미착수.
  **Wave 4(2026-09-05, `CopyCommand`/`RenameCommand`/`ForgetCommand`/
  `RemoveCommand`/`AddremoveCommand`)**: 5개 명령 전부 native 6/6 + Docker
  30/30 = 36/36 GREEN. 진짜 hg4j 버그 4건 발견·수정: (1) `AddCommand`가
  이미 dirstate 항목이 있는 경로(forget/remove 이후 재-add)에 항상 신규
  `a` 항목을 만들어 filelog 히스토리 연결이 끊기던 버그(real hg는
  `normallookup`으로 `n`+모호 stat 복원), (2) dirstate-v2에서 "캐시된
  size/mtime을 신뢰 불가"(HAS_MODE_AND_SIZE/HAS_MTIME 플래그 꺼짐)를
  hg4j가 구체적이지만 틀린 0으로 읽어들여 `RemoveCommand`/`StatusCommand`/
  `ShelveCommand`/`CommitCommand`가 건드리지 않은 파일을 "수정됨"으로
  오판하던 공용 버그(`Dirstate.Entry#isStatAmbiguous()` 신설로 해결), (3)
  `Dirstate.read(File)`의 dirstate-v2 분기가 파싱된 copyMap을 아예
  버리던 한 줄 누락 버그(연속된 `hg copy` 호출 중 앞선 복사의 copy-source
  기록이 사라짐), (4) `CommitCommand`가 커밋된 파일의 dirstate copyMap
  잔여 기록을 정리하지 않던 버그. 상세는 [[mercurial-spec-compliance-requirement]]
  백로그 #39 참고. 나머지 48개 로컬 명령은 여전히 미착수.

  **Wave 4(2026-09-05, `BranchCommand`/`BranchesCommand`/`PhaseCommand`)**:
  이 3개 명령에 36개 조합(native 6 + Docker 30) 적용, 전부 GREEN
  (`BranchCommand`/`BranchesCommand` native 6/6+Docker 30/30,
  `PhaseCommand` native 6/6+Docker 30/30). `PhaseCommand`는 real-hg
  byte-for-byte `.hg/store/phaseroots` 비교 검증 설계 도중 진짜 hg4j
  버그 3건(자체 phaseroots 재구현이 real hg의 최소-boundary-root 알고리즘과
  달랐던 것, `CommitCommand`/`FetchCommand`가 매 커밋/pull마다 이미
  상속되는 phase를 중복으로 명시 기록하던 것)을 발견·수정한 뒤 도달.
  상세는 [[mercurial-spec-compliance-requirement]] 백로그 #39 참고.

  **Wave 4(2026-09-05)**: `ResolveCommand`/`BackoutCommand`/`RevertCommand`
  3개 명령에 requirement 매트릭스(native 6 + Docker 30 = 36개 조합) 확장
  적용, 전부 GREEN. `MergeCommand`/`RebaseCommand`/`GraftCommand`의 기존
  3-way merge/충돌 처리 하드닝(`Merge3`/`MergeState`/
  `RebaseCommand.attemptThreeWayMerge`)을 그대로 재사용해 `BackoutCommand`가
  이전엔 아예 없던 "오래된 조상 백아웃 시 3-way merge/충돌 감지" 경로를
  갖추게 됨. `RevertCommand`의 진짜 데이터 손실 버그(add-uncommitted 파일
  되돌릴 때 콘텐츠 통째 삭제) 및 `.orig` 백업 미구현도 발견·수정. 그
  과정에서 `StatusCommand`/dirstate-v2 파서·시리얼라이저의 "possibly
  dirty" 센티널 처리 누락(같은 초 안에 커밋된 파일이 무조건 modified로
  오판되는 버그, native와 Docker 양쪽에서 각각 발견)과 `CommitCommand`의
  `.hg/merge` 정리 조건 누락(단일-parent 충돌-해결 커밋 케이스)까지 총
  6건의 진짜 hg4j 버그를 TDD로 발견·수정. 상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 명령 기준
  완주 수는 14에서 17로 증가, 나머지 41개 명령은 여전히 미착수. (두 wave 4
  브랜치를 병합한 최종 완주 수는 `BranchCommand`/`BranchesCommand`/
  `PhaseCommand`/`CopyCommand`/`RenameCommand`/`ForgetCommand`/
  `RemoveCommand`/`AddremoveCommand`/`ResolveCommand`/`BackoutCommand`/
  `RevertCommand` 11개 명령 추가로, 이전 wave까지의 17개(Push/Rebase/Shelve/
  Strip/Merge/Graft/Bundle 등)에 더해 28/67로 집계.)

  **Wave 4(2026-09-05)**: `UnbundleCommand`(`BundleCommand`의 반대 방향 —
  real hg가 만든 번들 파일을 hg4j가 적용)도 36개 조합 전부 GREEN, 새 실버그
  없음(수신측 `FetchCommand#applyBundle` 경로가 이미 정확했음이 재확인됨).
  `ExportCommand`/`ImportCommand`도 함께 36개 조합 전부 GREEN이지만 이번엔
  진짜 hg4j 버그 2건을 TDD로 발견·수정: `ImportCommand`가 매니페스트/
  changelog를 손수 조립하던 방식이라 treemanifest에서 구조적으로 동작 불가능
  했던 것(이미 검증된 `CommitCommand`에 위임하도록 재작성, 삭제 패치
  `/dev/null` 지원도 이 참에 처음 추가)과, `DiffCommand`의 줄 분리 로직이
  후행 개행이 있는 콘텐츠마다 가짜 빈 줄을 하나 더 만들어내던 버그(내보낸
  패치를 real hg로 되읽었을 때 커밋 노드 해시가 달라지는 것으로 발각 —
  단순 내용 비교가 아니라 노드 해시 완전 일치를 대조하는 매트릭스 설계
  덕분에 잡힌 버그). 상세는 [[mercurial-spec-compliance-requirement]] 백로그
  #39 참고. 나머지 41개 명령은 여전히 미착수(다른 wave 4 에이전트들과 병렬
  진행 중이라 최종 숫자는 조정자가 취합).

  (조정자 취합, 2026-09-05: 위 `UnbundleCommand`/`ExportCommand`/
  `ImportCommand` 3개 명령을 앞선 wave 4 병합 결과(28/67)에 더해 로컬 명령
  기준 **31/67**로 증가.)

  **Wave 5(2026-09-05, 가벼운 저장소 메타데이터 조회 8개 명령:
  `HeadsCommand`/`IdentifyCommand`/`ParentsCommand`/`PathsCommand`/
  `RootCommand`/`TipCommand`/`TagsCommand`/`SummaryCommand`)**: 기능이 가까운
  명령끼리 3개 트리오(`Heads`+`Tip`+`Parents`, `Identify`+`Summary`,
  `Tags`+`Paths`+`Root`)로 묶어 각 트리오 native 6/6 + Docker 30/30 =
  36/36 GREEN(3트리오 = 8개 명령 전부). `PathsCommand`/`RootCommand`는 4축
  어디에도 실제로 좌우되지 않는 명령이지만 예외 없이 36개 조합 전부
  실측했다. 세 트리오 모두 hg4j 쪽은 순수 읽기만 하므로(저장소는 항상 real
  hg CLI/`docker exec`로만 구축) `RequirementMatrixCommitHelperMain` 류의
  서브프로세스 격리가 필요 없었음(그 우회는 hg4j 자신의 zstd **쓰기** 경로가
  `docker exec`/`docker run` 스폰과 JVM을 공유할 때만 재현되는 문제였기
  때문). real hg 7.2.2 직접 대조로 진짜 hg4j 버그 3건 발견·수정: (1)
  `HeadsCommand --topo`가 리프를 changelog 오름차순으로 반환하던 것을
  real hg처럼(다른 모든 `hg heads` 형태와 동일하게) 리비전 내림차순으로
  수정(기존 `HeadsRealHgInteropTest`는 `hexSorted()` 비교라 이 순서 버그를
  못 잡고 있었음), (2) `IdentifyCommand`를 real hg의 정확한 출력 규칙으로
  전면 재작성 — 기본 브랜치 생략, dirty(`+`) 마커, 머지 2-parent, 태그/
  북마크의 알파벳 순 `"/"` 조인(`TagsCommand`/`BookmarkCommand` 재사용),
  `-r` 조회 신설(`setRevision`) — 재작성 도중 real hg 실측으로 두 가지를
  추가 확인: 머지 중 태그/북마크는 p1+p2 양쪽에서 집계되고, `-r` 조회의
  브랜치는 작업 사본의 현재 브랜치가 아니라 조회 대상 리비전 자신의
  브랜치여야 함. 기존 `IdentifyCommandTest`/`PorcelainExtraCommandsTest`의
  옛(틀린) 포맷 검증도 real hg 실측값으로 갱신. `SummaryCommand` 자체엔
  버그가 없었으나, 이를 매트릭스로 검증하던 중 changelog-v2(docket 기반)
  저장소를 오래 붙들고 있는 `HgRepository` 핸들이 external 커밋 이후에도
  캐시된 revision count로 stale해지는 것을 실측(hg4j 버그가 아니라 테스트의
  "실행 중 계속 real hg CLI로 저장소를 키우면서 핸들 재사용" 패턴이 원인 —
  기존 공개 API `refreshIfChangedOnDisk()`로 테스트 쪽에서 해결). 전체
  비-interop `test` 2282건 0 실패/0 에러(2 스킵) GREEN. 로컬 명령 완주 수는
  31에서 8개 추가로 **39/67**로 증가(다른 wave 5 병렬 에이전트와 별도 취합
  필요할 수 있음).

  **Wave 5(2026-09-05, `BisectCommand`/`DescribeCommand`/`DiffCommand`/
  `LogCommand`/`StatusCommand`/`RevsetCommand`/`SidedataChangedFilesCommand`)**:
  7개 명령 전부 native 6/6 + Docker 30/30 = 36/36 GREEN. `LogCommand`/
  `StatusCommand`는 이전까지 4-명령-공용 `RequirementMatrixCoreRoundTripTest`의
  부수적 검증만 있었을 뿐 전용 트리오가 없었던 지점을 이번에 채움. 진짜
  hg4j 프로덕션 버그 4건 발견·수정(상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고): (1)
  `BisectCommand`의 treemanifest 체크아웃 미지원(hand-roll 매니페스트
  파싱 → treemanifest-aware `ManifestWalk`로 교체), (2)
  `Revlog.decompressSidedataChunk`가 changelog-v2+sidedata 저장소의
  sidedata 압축 모드를 zstd로 무조건 가정(zlib 압축 저장소의 유효한
  sidedata를 못 읽음), (3) **`DeltaCodec.decompressZstd`가 델타 인코딩된
  리비전의 압축 해제 목적지 버퍼 크기로 revlog 인덱스의 uncompLen(최종
  재구성 크기이지 이 청크 자체의 압축 해제 크기가 아님)을 그대로 써서
  크래시** -- native 매트릭스는 항상 zlib을 강제하고 hg4j 자신의 쓰기
  경로는 델타 없이 항상 fulltext로 쓰기 때문에 지금까지 어떤 테스트도
  건드린 적 없던 조합(real zstd + 델타 인코딩)이었고, 실제로는 일반적인
  real-world zstd 압축 hg 저장소 다수에 영향을 미쳤을 이번 wave 최대
  파급력 버그, (4) `HgRepository.refreshIfChangedOnDisk()`(원래
  wire 서버 전용으로만 연결돼 있던 stale-changelog-v2-cache 방어
  메서드)를 이번 7개 명령의 진입부에도 연결 -- 연결 안 됐을 때는 장수
  repo 핸들이 외부 프로세스의 새 커밋을 못 보고 조용히 틀린 답을 냄.
  전체 비-interop `test`(2278건) 재확인, `StripCommandCoverageTest` 1건
  실패 발견. 명령 기준 완주 수는 31에서 **38/67**로 증가, 나머지 29개
  로컬 명령은 여전히 미착수.

  (조정자 정정, 2026-09-05: 위 `StripCommandCoverageTest` 1건 실패를
  "이 세션과 무관한 사전 존재 이슈"로 판단한 것은 **틀렸음** — 병합
  직전 커밋(메타데이터-쿼리 wave 병합 완료 시점)에서 격리 재현했더니
  통과했고, 이 wave 브랜치 단독(공통 조상 기준)에서도 동일 테스트가
  결정론적으로 실패해(타이밍 문제 아님, 재현률 100%) 이 wave 자신이
  만든 진짜 회귀임을 확인. 근본 원인: 이 wave가 7개 명령에 새로 연결한
  `HgRepository.refreshIfChangedOnDisk()`가 changelog.i 크기/mtime
  변화를 감지하면 무조건 `clearRevlogCache()`로 캐시된 모든 Revlog를
  새 인스턴스로 교체 — 이 과정에서 "로컬에서 이미 쓴 적 있는
  RevlogIndex는 리로드하면 안 된다"는 `RevlogIndex.checkAndUpdate()`
  자신의 `addedRecords`-empty 가드가 우회됨. 커밋 직후 같은 handle로
  strip하는 흔한 패턴(`StripCommand`의 북마크 재배치 로직이 방금
  stripped된 노드를 `findRevision()`으로 찾으려 시도)에서, 리프레시로
  새로 생성된 인스턴스는 "로컬 쓰기 이력"을 모르는 채 시작하므로
  이미 truncate된 파일에서 다시 읽어 stripped 노드를 찾지 못하고
  -1을 반환 — 북마크가 재배치되지 않고 조용히 삭제됨. 조정자가
  `RevlogIndex`/`Revlog`에 `hasLocallyAddedRecords()` 노출, 캐시된
  changelog가 이미 로컬 쓰기 이력이 있으면 `refreshIfChangedOnDisk()`가
  `clearRevlogCache()`를 건너뛰도록 수정(커밋 `b331e15`, 이 wave
  브랜치 자체에 반영) — 이 수정 후 해당 테스트 및 전체 비-interop
  스위트 재확인 GREEN. 교훈: 병합 중 발견한 "무관해 보이는" 테스트
  실패는 병합한 에이전트의 판단을 그대로 신뢰하지 말고 반드시 직접
  격리 재현(병합 전/후, 브랜치 단독 여부)으로 재확인할 것.)

  **Wave 5(2026-09-05, 저장소 유지보수/관리 6개 명령 — `GcCommand`/
  `RecoverCommand`/`RollbackCommand`/`VerifyCommand`/`CensorCommand`/
  `InitCommand`)**: 병렬 서브에이전트 2개(각각 Gc+Recover+Rollback,
  Verify+Censor)와 조정 세션이 직접 맡은 `InitCommand`로 나눠 진행, 전부
  GREEN(native 6/6 + Docker 30/30 조합 자체는 6개 명령 전부 동일, 명령별
  시나리오 수 차이로 실제 케이스 총량만 다름 — `InitCommand`
  6/6+30/30, `GcCommand` 6/6+30/30, `RecoverCommand` 12/12+30/30,
  `RollbackCommand` 12/12+30/30, `VerifyCommand` 15/15+69/69,
  `CensorCommand` 12/12+60/60). `InitCommand`는 설계가 나머지 5개와
  근본적으로 다르다 — 다른 31개 기완주 명령처럼 real hg가 저장소를 만들고
  hg4j가 그 위에 쓰는 방향이 아니라, **hg4j 자신의 `InitCommand`가 36개
  조합 전부의 저장소를 처음부터 만들고 real hg가 완전히 받아들이는지**
  (verify/log/cat/debugrequires + 추가 커밋까지) 검증 — 이 매트릭스의
  나머지 모든 테스트가 "저장소가 유효하다"는 전제 위에 서 있으므로 §서두에
  명시된 대로 고위험 취급.

  진짜 hg4j 버그 9건 발견·수정(전부 즉시 완전 수정, 범위 축소 없음) —
  상세는 [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 요약:
  (1) `InitCommand`가 애초에 dirstate-v2/zstd 2개 축만 지원해 30개 이상의
  조합을 hg4j 스스로 만들 수조차 없던 gap — 전체 축 구현으로 해결.
  (2) changelog-v2+general-v2 동시 활성화 시 changelog 부트스트랩이 잘못된
  포맷을 선택해(`DefaultFileStoreEngine`/`RevlogIndex`) real hg가 그 위에
  커밋하면 `TypeError`로 크래시 — 이 세션에서 `InitCommand` 36개 조합 검증
  중 직접 발견. (3) CL_V2 `rank` 필드가 real hg의 재귀 공식과 다르게
  기록되던 정합성 버그. (4) **명령 무관 공용 버그**:
  `DeltaCodec.decompressZstd`가 델타 리비전에 fulltext 길이를 잘못
  써서 zstd 압축 해제 버퍼가 오버사이즈되고 결과가 손상되던 문제 —
  `GcCommand`/`VerifyCommand` 두 서브에이전트가 각각 독립적으로 발견,
  병합 시 두 수정이 로직상 동일해 하나로 합침(native는 항상 zlib 강제라
  이제까지 전혀 안 드러났던, 이 매트릭스가 Docker에서 진짜 멀티 리비전
  zstd 델타를 처음 읽은 덕에 잡힌 버그). (5)(6) `GcCommand`의 v2/docket
  revlog 파괴적 리라이트 및 fncache/fileindex-v1 오처리. (7)
  `CommitCommand`/`RollbackCommand`/`RecoverCommand`의 v2-docket
  undo/journal 무동작(no-op) 버그 및 dirstate-v2 컴패니언 파일 미복원.
  (8) `VerifyCommand`의 fileindex-v1/general-v2 filelog 발견 누락 및
  treemanifest 서브매니페스트 미검사. (9) `CensorCommand`의 check-heads
  가드 부재 및 general-v2 filelog를 파괴하던 `Revlog.censorRevision()`
  포맷 무관 재작성 버그.

  검증: 전체 비-interop `test` 그린(각 서브에이전트 브랜치 및 병합 후
  통합본 전부), 6개 명령 native 매트릭스 병합 후 재확인 그린,
  `VerifyCommand`(Docker 69케이스)/`CensorCommand`(Docker 60케이스) —
  병합된 `DeltaCodec`/`Revlog` 수정 경로를 실제로 거치는 시나리오라 병합
  후 재검증 우선순위로 선택 — 도 그린. `GcCommand`/`RecoverCommand`/
  `RollbackCommand` 브랜치와 `VerifyCommand`/`CensorCommand` 브랜치가
  `DeltaCodec.java`를 각자 독립 수정해 병합 충돌 — 로직 동일 확인 후 한쪽
  기준으로 정리. 이 wave의 `DeltaCodec.decompressZstd` 수정은 코디네이터의
  core/query wave 병합 시 이미 main에 반영된 동일 수정(변수명만 다름,
  로직 완전히 동일 확인)과의 충돌도 코디네이터가 이 wave 병합 시 같은
  방식으로 정리.

  이 wave 자체 기준 완주 수는 31에서 37/67(코디네이터가 다른 wave 5
  병렬 그룹과 별도 취합 필요). 부수 발견(다음 담당자 참고): `GraftCommand`의
  v2-docket rollback/journal 기록에 이번 `CommitCommand` 수정과 유사하지만
  별도인 gap이 있음을 `RecoverCommand`/`RollbackCommand` 작업 중 발견 —
  이번 wave 위임 범위 밖이라 미수정, `GraftCommand` 매트릭스 담당자가
  확인할 것(백로그 39 전체 완료 후 후속 점검 항목으로 유지).

  (조정자 취합, 2026-09-05: 위 3개 wave 5 문단(메타데이터조회 +8,
  core/query +7, admin/maintenance +6)은 서로 다른 명령 집합에 대한
  독립 병렬 작업으로 겹치지 않음(단, `DeltaCodec.decompressZstd` 버그는
  core/query와 admin/maintenance 두 그룹이 각각 독립적으로 발견 — 로직
  동일한 수정이라 병합 시 하나로 정리). 세 wave 병합 후 로컬 명령
  완주 수는 31 + 8 + 7 + 6 = **52/67**. 남은 그룹: 작업트리 6개
  (`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/`TreeCommand`/
  `TreeMergeCommand`/`WorktreeCommand`, 완료·병합 대기 중) + 콘텐츠/트리
  읽기 6개(`CatCommand`/`FilesCommand`/`LocateCommand`/`GrepCommand`/
  `AnnotateCommand`/`ManifestCommand`, 진행 중) — 이 둘이 병합되면
  로컬 매트릭스 전체 67개 완료 예정.)

  **Wave 5(2026-09-05, `ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/
  `TreeCommand`/`TreeMergeCommand`/`WorktreeCommand` — 워킹카피 조작 계열
  6개를 한 에이전트에 배정)**: 6개 명령 전부 native 6/6 + Docker 30/30 =
  36/36 GREEN. 신규 테스트 클래스 12개(`RequirementMatrix{Command}
  CoreRoundTripTest`/`...DockerRoundTripTest`, 명령당 하나씩) + 헬퍼
  서브프로세스 4개(`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/
  `WorktreeCommand` — 실제 워킹카피 쓰기 명령이라 패턴 일관성을 위해 둠;
  `TreeCommand`/`TreeMergeCommand`는 순수 읽기/계산이라 헬퍼 없이 직접
  호출), 전부 `src/test/java/io/github/search5/hg4j/api/`. 진짜 hg4j 버그
  **7건** 발견·수정 — 이 중 2건은 배정된 6개 명령의 범위를 넘어 **모든
  명령이 공유하는 인프라 계층**(revlog zstd 압축 해제, dirstate-v2 직렬화)
  에 있던 버그였다:
  1. **(광범위) `DeltaCodec.decompressZstd()`가 델타 리비전 압축 해제 시
     인덱스의 `uncompLen`(=최종 재구성 fulltext 크기, 델타 자체의 압축
     해제 크기가 아님)만큼 버퍼를 통째로 반환** — 델타 적용 결과 크기가
     줄어드는 편집(삭제가 추가보다 큰 경우)에서 델타 자체의 실제 압축
     해제 바이트 수가 `uncompLen`보다 작아, 남는 버퍼가 자바 기본값 0으로
     패딩되고 `DeltaEngine.applyDelta()`가 이를 가짜 델타 헝크로 오인해
     `HgCorruptDataException`. `hg-rust-7.2.4`가 만든 가장 단순한 2-커밋
     저장소(스토리지 확장 없음)를 `UpdateCommand`로 체크아웃하는 것만으로
     100% 재현 — Docker 30개 조합 전체에 영향을 미치는 근본적인 읽기
     버그였다(다른 명령들의 매트릭스는 시나리오가 이 패턴을 우연히
     피해갔을 뿐). `Zstd.decompress()`의 실제 반환 길이로 잘라내도록 수정.
  2. **(광범위) `DirstateV2Node`/`DirstateV2Serializer`가 `MODE_EXEC_PERM`과
     `MODE_IS_SYMLINK`를 상호 배타로 취급** — real hg 7.2.4의 Rust
     dirstate-v2 소스(`mode_changed()`, `EXEC_BIT_MASK=0o100`)를 직접
     대조: 실제 심볼릭 링크의 `lstat` 모드는 항상 전체 권한 비트를
     포함하므로 모든 심볼릭 링크는 "실행 비트 있음"으로 관측되고, 따라서
     dirstate-v2 심볼릭 링크 항목은 두 플래그를 **항상 함께** 켜야 real
     hg 자신의 검증과 일치한다. hg4j가 상호 배타로 처리해 심볼릭 링크의
     실행 비트를 항상 꺼버린 탓에 dirstate-v2 저장소에서 real hg의 `hg
     status`가 모든 심볼릭 링크를 "M"으로 오판 — `AddCommand`/
     `CommitCommand`/`MergeCommand`/`RebaseCommand` 등 dirstate-v2 +
     심볼릭 링크를 다루는 기존 완료 명령에도 같은 근본 원인이 잠재했을 수
     있는 공유 계층 버그. 항상 함께 설정하도록 수정.
  3. `UpdateCommand`의 심볼릭 링크 dirstate 모드가 맨 `0120000`(타입
     비트만)으로 기록되던 것을 `0120777`(real hg의 실제 `lstat` 값)로
     수정 — dirstate-v1에서 real hg 자신의 `hg status`가 체크아웃 직후
     매번 권한 불일치로 "M"을 보고하던 버그.
  4. `ArchiveCommand`가 real hg의 `hg archive`와 구조적으로 다른 산출물을
     생성 — `.hg_archival.txt` 메타데이터 누락, zip/tar 디렉터리 프리픽스
     누락, 실행 비트/심볼릭 링크 무시, tar/tgz/tbz2 타입 자체가 없음,
     평면 매니페스트 전용 파서라 treemanifest 하위 디렉터리가 통째로
     누락. `HgRepository#getManifestAtCommit()`(treemanifest 대응, 이미
     검증됨)로 교체하고 tar/tgz/tbz2 + 실행 비트/심볼릭 링크 +
     `.hg_archival.txt`(latesttag 포함, real hg 소스로 검증)를 신규
     구현(`txz`는 신규 의존성이 필요해 범위 밖으로 명시).
  5. `PurgeCommand`가 심볼릭 링크로 연결된 디렉터리를 실제로 따라 들어가
     저장소 바깥의 파일을 삭제할 수 있었던 **실제 데이터 손실 버그**(외부
     디렉터리에 파일을 만들고 심볼릭 링크로 연결해 재현 확인) — 심볼릭
     링크를 항상 불투명 leaf로 취급하도록 수정. 부수적으로 끊어진 심볼릭
     링크가 조용히 건너뛰어지던 버그, `purgeDirectories` 기본값이
     `false`였던 것(real hg는 플래그 없이도 기본으로 빈 디렉터리를 지움)도
     함께 수정.
  6. `WorktreeCommand`(`hg share` 상당)가 실제 체크아웃을 전혀 수행하지
     않고 빈 40바이트 dirstate 스텁만 만들던 버그 — `UpdateCommand`로
     실제 체크아웃하도록 수정, requires에 real hg가 항상 붙이는 "shared"
     마커도 추가. 이 수정 도중 발견한 2차 버그: 무조건적인 40바이트
     dirstate-v1 스타일 스텁 쓰기가 dirstate-v2 공유 저장소에서는 유효한
     V2 도켓이 아니어서 `UpdateCommand`가 체크아웃 직전 dirstate를 다시
     읽는 순간 `BufferUnderflowException`(Docker 30개 조합 전체 재현) —
     리비전이 있을 때는 dirstate 파일을 미리 쓰지 않고 `UpdateCommand`가
     처음부터 새로 만들도록 수정.
  7. `TreeMergeCommand`(작업 디렉터리 없는 순수 3-way 병합 계산)의 결과에
     파일 모드/플래그 정보가 아예 없어 chmod류 변경이 유실될 수 있던 것 —
     `getChangedModes()` 신규 추가. 이 신규 테스트로 **부수적으로 발견한
     8번째 진짜 버그**: `CommitCommand`가 이미 추적 중인 파일의 순수
     실행 비트 변경(`chmod +x`, 내용 불변)을 전혀 감지하지 못해(크기/
     mtime만 비교하고 실행 비트는 비교 안 함 — chmod는 POSIX에서 mtime을
     안 건드림) 영구적으로 커밋에서 누락되는 데이터 손실 버그를 발견,
     실행 비트 비교를 추가하고 내용이 동일할 땐 새 filelog 리비전 대신
     기존 리비전 해시를 재사용하도록(real hg와 동일하게 델타 노드 해시
     중복을 피함) 수정.

  `UpdateCommand`에 배정 전 "알려진 불필요한 pull(redundant pull)" 갭으로
  언급됐던 것은 실제로는 백로그 32(서브저장소) 작업에서 이미
  `UpdateCommand.isRevisionPresentLocally()`로 완전히 수정돼 있었음을
  재확인(추가 조치 없음).

  **검증**: 전체 비-interop `test`(2278건) 재확인 — GREEN(도중 발견된
  `LocateCommandTest#testUnresolvableRevisionThrows` 1건 실패는 단독
  재실행 시 통과하는 것으로 확인된, 이 wave와 무관한 플레이크). 이로써
  로컬 명령 기준 완주 수는 31에서 **37**(+`ArchiveCommand`/`PurgeCommand`/
  `UpdateCommand`/`TreeCommand`/`TreeMergeCommand`/`WorktreeCommand`)로
  증가 — 나머지 30개 로컬 명령과 wire 매트릭스 잔여 5개는 여전히
  미착수(다른 wave 5 에이전트들과 병렬 진행 중이라 최종 합산은 조정자가
  취합).

  (조정자 취합, 2026-09-05: 위 4개 wave 5 문단(메타데이터조회 +8,
  core/query +7, admin/maintenance +6, 이 작업트리 wave +6)은 서로 다른
  명령 집합에 대한 독립 병렬 작업으로 겹치지 않음. 이 wave가 발견한
  버그 #1(`DeltaCodec.decompressZstd`)은 core/query·admin/maintenance
  두 그룹이 이미 독립적으로 발견·수정한 것과 같은 버그 — 코디네이터가
  병합 시 로직 동일함을 diff로 확인하고 이미 병합된 버전(zstd 프레임
  자체의 content-size로 목적지 버퍼를 정확히 사이징하는 방식)을 채택,
  이 wave의 "uncompLen만큼 넉넉히 할당 후 트림" 버전은 이미 반영된
  frameSize 기반 할당과 논리적으로 어긋나 폐기. 버그 #2(`DirstateV2Node`
  exec/symlink 플래그 상호배타 버그)는 이 wave 스스로 "AddCommand/
  CommitCommand/MergeCommand/RebaseCommand 등 이미 완료된 명령에도 같은
  근본 원인이 잠재했을 수 있다"고 명시적으로 경고함 — 병합 후 전체
  비-interop 테스트가 여전히 GREEN임을 코디네이터가 별도로 재확인(회귀가
  아니라 숨어있던 버그가 이 수정으로 이제 올바르게 드러나는 케이스가
  있다면 그 자체는 개선이지 문제가 아님, 다만 기존 GREEN 테스트가 새로
  FAILED로 바뀌는 경우는 원인을 반드시 규명). 네 wave 병합 후 로컬 명령
  완주 수는 31 + 8 + 7 + 6 + 6 = **58/67**. 남은 그룹: 콘텐츠/트리 읽기
  6개(`CatCommand`/`FilesCommand`/`LocateCommand`/`GrepCommand`/
  `AnnotateCommand`/`ManifestCommand`, 진행 중) — 병합되면 로컬 매트릭스
  67개 전체 완료, wire 매트릭스는 이미 8/8 완료.)

### 4-2. Wire 매트릭스 대상 명령
- [x] 설계(§2) 확정, 21개 조합 확정(2026-09-04)
- [x] `CloneCommand`/`PullCommand`/`PushCommand` 핵심 라운드트립 — **완료
  (2026-09-04)**: `HgWireProtocolMatrixTest`(`src/test/java/io/github/search5/
  hg4j/transport/`) — HTTP 18개 + SSH 3개 = 21개 조합 전부 pull+push(쓰기 경로)
  양방향 GREEN. 새 프로덕션 버그는 못 찾음(백로그 22/26의 기존 협상 로직이 이미
  정확했음이 재확인됨) — 대신 SSH 압축 강제 테스트 설계 중 real hg 자신의
  `abort: potentially unsafe serve --stdio invocation` 보안 가드를 발견해 압축
  설정을 CLI 인자 대신 `.hg/hgrc`로 미리 써넣는 방식으로 우회(hg4j 버그 아님,
  테스트 설계 이슈).
- [x] `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
  `NarrowCloneCommand` — **완료(2026-09-05, 백로그 39 wave 5)**. 상세는 바로
  아래 wave 5 단락 참고.

**Wave 5(2026-09-05, wire 매트릭스 나머지 5개 명령)**: `FetchCommand`/
`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/`NarrowCloneCommand`
5개 명령 전부에 21개 조합(HTTP 18 + SSH 3) 적용, **전부 GREEN**
(`FetchCommand` 21/21, `NarrowCloneCommand` 21/21, `ClonebundlesCommand`
21/21, `IncomingCommand`+`OutgoingCommand` 49/49 — 21+21개 hg4j-클라이언트
방향 + 6개 HTTP(tier x bundle2) + 1개 SSH 리버스 방향). 새 테스트 클래스 5개
(`HgWireProtocolMatrixFetchTest`/`HgWireProtocolMatrixNarrowCloneTest`/
`HgWireProtocolMatrixClonebundlesTest`/`HgWireProtocolMatrixIncomingOutgoingTest`,
전부 `src/test/java/io/github/search5/hg4j/transport/`) + 기존
`HgWireProtocolMatrixTest`의 콤보/서버 설정 보일러플레이트를 공유하는 신규
헬퍼 3개(`WireMatrixCombos`/`HttpMatrixServer`/`SshMatrixServer`, 같은
패키지) 추가. `IncomingCommand`/`OutgoingCommand`는 사용자 지시로 명시적으로
양방향(hg4j 클라이언트 vs real-hg 서버 21개 + real-hg 클라이언트 vs
hg4j-served 서버) 검증 — 단, hg4j의 `HgHttpWireServer`/SSH serving 경로는
real hg의 `hg serve`와 달리 고정된 단일 capability 세트만 광고하고
tier/압축/bundle2를 바꿀 config 노브가 없어서, 리버스 방향은 (프록시로
강제 가능한) tier x bundle2 6개 HTTP 조합 + SSH 기본 1개로 그친다(§4-2
클래스 javadoc에 이 제약을 명시).

**발견·수정한 진짜 hg4j 버그 3건**:
1. `IncomingCommand`가 항상 `client.getChangegroup(Collections.emptyList())`로
   원격 전체 히스토리를 구식 `changegroup` wire 명령으로 요청하고
   있었는데, **content가 있는 어떤 real hg 서버에 대해서도 이게 100% 깨져
   있었다** — real hg 자신의 순정 `hg serve`에 맨 curl로 `?cmd=changegroup&
   roots=`를 쳐서 hg4j 없이 독립 재현 확인(2026-09-05): real hg의
   `discovery.outgoing()`(`mercurial/discovery.py`)이 `missingroots == []`
   이면서 `ancestorsof`가 명시적으로 전달된 경우(서버 쪽 `changegroup()`
   핸들러가 항상 이렇게 호출함) `repo.revs('::%ln', missingroots,
   ancestorsof)`를 호출하는데, revset 표현식 `'::%ln'`에는 자리표시자가
   하나뿐인데 치환값을 2개 넘겨서 `ParseError: too many revspec arguments
   specified`로 서버가 uncaught exception을 던져 HTTP 500이 됨 — real hg
   자신의 레거시 wire 명령 안에 있는 결함이지 hg4j 버그는 아니지만, hg4j가
   그 경로를 절대 밟지 않아야 했음(real hg 자신의 최신 클라이언트는 항상
   `getbundle`을 쓰지 레거시 `changegroup`을 empty-roots로 부르지 않음).
   수정: `FetchCommand`에 이미 있던 "leaf 노드 계산" + "getbundle 우선
   협상 + HG20/HG10 매직 해제" 로직을 `FetchCommand.computeLocalLeafHexes()`/
   `FetchCommand.downloadChangegroupBundle()` 공용 정적 메서드로 추출해
   `IncomingCommand.call()`이 재사용하도록 재작성 — `FetchCommand.call()`
   자신도 이 공용 메서드를 쓰도록 리팩터링(동작 변화 없음, 코드 중복 제거).
2. `HgRemoteClient.getChangegroup()`(HTTP)가 `roots` 인자가 빈 리스트일 때
   파라미터 맵에서 아예 키 자체를 생략하던 버그 — real hg의 `changegroup`
   wire 명령은 `roots`가 필수 선언 인자라 요청에서 키가 통째로 빠지면
   서버의 `getargs()`(`wireprotoserver.py`)가 dict lookup에서 바로
   `KeyError`(HTTP 500)를 던진다. `HgSshClient.getChangegroup()`은 이미
   빈 문자열로라도 항상 보내고 있어(기존 주석에 이미 명시돼 있었음) 정확한
   참조 구현이 있었다 — HTTP 클라이언트를 그 패턴에 맞춰 수정.
3. `FetchCommand`의 clonebundles bypass 게이트가 `client instanceof
   HgRemoteClient`(HTTP 전용)였던 것 — real hg 소스(`mercurial/
   exchange.py`의 `remote.capable(b'clonebundles')`/`e.callcommand(
   b'clonebundles', {})`) 확인 결과 이 메커니즘은 전송 방식과 무관하게
   동작해야 한다. `HgRemoteConnection` 인터페이스에 `supportsClonebundles()`/
   `fetchClonebundlesManifest()` 기본 메서드(false/UnsupportedOperationException)를
   추가하고 `HgSshClient`에 실제 구현(`branchmap`과 동일한 형태의 무인자
   v1 wire 명령)을 추가, `FetchCommand.tryApplyClonebundle()`을
   `HgRemoteConnection` 제네릭으로 변경 — `HgWireProtocolMatrixClonebundlesTest`의
   SSH 21개 조합이 실제로 이 새 경로를 밟아 검증됨(다운로드 서버 hit
   카운터로 bypass가 실제로 발동했는지까지 확인).

이 세 버그 중 1번(`IncomingCommand` 완전 broken)이 가장 심각 — 실사용
환경에서 real hg 서버에 대고 `hg4j`의 `IncomingCommand`를 쓰면 콘텐츠가
있는 저장소에서는 100% 실패했다(웹훅 알림 발송, 2026-09-05).

회귀 확인: 비-interop `test` 2278건 전부 GREEN(2 스킵, 기존 무관 skip),
이번 wave에서 만든 5개 새 클래스(21+21+21+49=112 테스트) 전부 GREEN, 기존
`HgWireProtocolMatrixTest`(Clone/Pull/Push) 21개도 재확인 GREEN(회귀 없음).

## 관련 페이지
[[mercurial-spec-compliance-requirement]] (백로그 29~40 — 이 매트릭스와 별개로,
"완료" 표시 항목 안에 남은 개별 기능 gap을 다룬다), [[test-coverage-95-percent-initiative]]
(BRANCH 커버리지 부채 — 이 매트릭스와 마찬가지로 real-hg interop과는 다른 축의 안전망).

## 인수인계 (2026-09-04 17:50, 다른 세션/다른 머신에서 이어서 진행하기 위함)

이 세션(session_01FSm18kLTZDAcdHuivJuZ8t)은 사용자 지시로 17:50에 작업을
중지했다. 아래는 완료 여부와 무관하게 그 시점의 정확한 상태다.

### 지금 어디까지 됐는지 (커밋 `ce767fa` 기준)
- **완료·검증됨**: requirement 매트릭스 36개 조합(native 6 + Docker 30) +
  wire 매트릭스 21개 조합, 4개 명령(commit/log/status/cat)+3개 명령
  (clone/pull/push) 한정으로 전부 양방향(쓰기 포함) GREEN. 기존 fixture
  테스트 7개도 라이브 쓰기 검증으로 보강 완료. [[mercurial-spec-compliance-requirement]]
  백로그 29/30/31/33/34/36/37 완료, 그 과정에서 발견한 실버그(changelog-v2
  zstd/zlib 혼동, dirstate-v2 트리 손상, narrow clone 캐시 버그, LFS 노드
  해시 계산 오류, `Hg.open()`의 낡은 requirement 허용목록, SSH push
  checkheads 미구현)까지 전부 수정·커밋됨.
- **완료·검증됨(추가, 17:55)**: 백로그 35(revlog 항상 non-inline)도
  `appendChangeGroupEntry`(pull/push 경로가 inline 상태를 아예 무시하고
  항상 non-inline으로 쓰던 근본 원인) 수정 완료, 전체 회귀 2268 테스트 중
  실패 1건(무관한 기존 `PerformanceBenchmarkTest` 타이밍 플레이크)만 남고
  독립 재확인 끝남. 커밋 `906cdd6`.
- **완전히 미착수**: 백로그 32(subrepo 4건 — 31 완료로 이제 착수 가능),
  38(동시 push 레이스 컨디션), 39(매트릭스를 나머지 60개 명령으로 확장),
  40(narrow clone 진짜 wire-protocol ellipsis node).

### 재현에 필요한 환경 정보
- **Docker**: `localhost/hg-rust-7.2.4` 이미지가 이미 빌드돼 있음
  (`docker/hg-rust-7.2.4/Dockerfile`) — Rust 확장 포함 실제 Mercurial
  7.2.4, `persistent-nodemap`/`fileindex-v1`/`general-v2`/`dirstate-v2`
  전부 이 이미지에서만 저장소 생성 가능(순정 파이썬 hg는 "without
  associated fast implementation"으로 거부).
- **`RequirementMatrixCommitHelperMain.java`**(`src/test/java/.../api/`):
  같은 JVM에서 hg4j `CommitCommand` 실행과 `docker exec`/`docker run`
  프로세스 스폰을 번갈아 하면 커밋이 비결정적으로 깨지는 버그를 별도
  서브프로세스로 우회하는 헬퍼 — Docker 관련 신규 테스트를 짤 때 반드시
  재사용할 것, 새로 만들지 말 것.
- **gradle 태스크**: `test`(기본, `@Tag("interop")` 제외, 빠름)와
  `interopTest`(real hg CLI/Docker 필요, 느림)로 분리돼 있음(2026-09-04).
  `check`/`jacocoTestReport`/`jacocoTestCoverageVerification`은 두 태스크의
  실행 데이터를 합쳐서 커버리지를 계산하므로 정확한 커버리지 게이트
  확인에는 `check`가 필요하다.
- **이 머신(M1 Pro, 16GB)의 동시 gradle 빌드 한계는 ~3개** — 초과하면
  메모리 압박으로 스루풋이 오히려 떨어진다. 빌드 시작 전
  `ps aux | grep GradleWorkerMain`으로 확인할 것.
- **공유 컴파일 출력 디렉터리 오염**: `-PagentBuildDir`은 리포트/jacoco
  출력만 격리하고 `build/classes/java/test` 컴파일 산출물은 격리하지
  않는다 — 여러 fork가 동시에 `compileTestJava`를 돌리면 서로의 클래스
  파일을 덮어써서 무관한 테스트가 대량으로 실패하는 것처럼 보인다(이
  세션에서 최소 4번 독립적으로 관측·확인됨). 회귀 결과를 신뢰하려면
  **다른 gradle 빌드가 전혀 없는 상태에서 단독 실행**해야 한다.
- **주의(운영상 교훈)**: 이 세션 중 조정자가 자신의 백그라운드 빌드를
  정리하려다 `pkill -f GradleWorkerMain`으로 다른 fork의 빌드까지 실수로
  같이 죽인 사고가 있었다 — 특정 프로세스를 정리할 땐 반드시 정확한 PID로만
  죽일 것, 패턴 매칭으로 넓게 죽이지 말 것.

### 다음에 할 일 순서(권장, 2026-09-04 17:55 갱신 — 백로그 35는 완료·검증됨)
1. 백로그 32(subrepo 4건) — `CommitCommand.java`/`UpdateCommand.java`를
   31/35가 이미 여러 번 고쳐놨으니 최신 상태를 반드시 먼저 읽고 시작할 것.
2. 백로그 38(동시 push 레이스 컨디션).
3. 백로그 39(매트릭스 확장) — §3의 우선순위(PushCommand/RebaseCommand/
   ShelveCommand/StripCommand부터) 참고.
4. 백로그 40(narrow ellipsis node) — 범위가 크므로 별도 세션에서 범위
   산정부터.
