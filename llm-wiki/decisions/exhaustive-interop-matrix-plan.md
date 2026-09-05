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
  백로그 #39 참고. 나머지 54개 명령은 여전히 미착수.
  **Wave 3(2026-09-05)**: `MergeCommand`/`SubrepoCommand` 둘 다 native 6/6 +
  Docker 30/30 전부 GREEN — `SubrepoCommand`의 `init`/`update`가 pin된
  리비전으로 실제 체크아웃을 한 적이 없던 버그, `CommitCommand`의 미해결
  머지 충돌 차단 로직(마커 텍스트 스캔 → 실제 머지 상태 기반으로 수정) 및
  머지 커밋 후 `.hg/merge` 미정리 버그까지 진짜 hg4j 버그 3건을 TDD로
  발견·수정. 상세는 [[mercurial-spec-compliance-requirement]] 백로그 #39
  참고.

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
- [ ] `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
  `NarrowCloneCommand` — 미착수

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
