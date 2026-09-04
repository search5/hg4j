---
name: exhaustive-interop-matrix-plan
updated: 2026-09-04
status: 설계 완료 — requirement 매트릭스는 native 12개 + Docker(hg-rust-7.2.4 컨테이너
  실측) 24개 = 총 36개 조합, wire 매트릭스는 21개 조합으로 확정(2026-09-04). 기존
  fixture 기반 테스트가 이미 커버하는 셀도 표에 명시적으로 매핑함(requirement
  36개 중 6개, wire 18개 중 5개 — 나머지는 미커버). 구현은 requirement 매트릭스
  핵심 라운드트립(commit/log/status/cat)을 native 12개 조합에 한해 1차 착수 —
  Docker 24개 조합 반영과 나머지 명령은 전부 미착수. 백로그 29~36과는 별개 축
  (개별 기능 gap이 아니라 "조합 공간을 체계적으로 훑는 인프라" 자체가 목적)이다.
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

### 1-2. 실행 환경 구분 (같은 매트릭스 안의 속성일 뿐, 별도 매트릭스가 아님)

| storage-확장 | 실행 환경 |
|---|---|
| none | 이 호스트 순정 파이썬 `hg` 7.2.2로 즉시 실행(Docker 불필요) |
| persistent-nodemap / fileindex-v1 / general-v2 | `hg-rust-7.2.4` 컨테이너 내부에서만 저장소 생성 가능(순정 파이썬 `hg`는 `abort: accessing '...' repository without associated fast implementation`로 실패, 2026-09-04 재확인) |

none 12개(§1-1 표 그대로) + Docker 필요 24개(persistent-nodemap 2x3x2=12개 +
fileindex-v1 2x3x1=6개[flat만] + general-v2 2x3x1=6개[flat만]) = **36개**.

### 1-3. 전체 36개 조합표 (기존 테스트 커버리지 매핑 포함)

| # | dirstate | changelog | manifest | storage-확장 | 실행환경 | 기존 커버리지 |
|---|---|---|---|---|---|---|
| 1 | v1 | v1 | flat | none | native | — |
| 2 | v1 | v1 | tree | none | native | `TreemanifestRealFixtureTest`(부분 — real hg 픽스처 읽기 방향) |
| 3 | v1 | changelog-v2 | flat | none | native | `ChangelogV2BootstrapTest`(`src/test/resources/fixtures/revlogv2-changelog/`, real hg 7.2 생성 픽스처) — 읽기 방향만 |
| 4 | v1 | changelog-v2 | tree | none | native | — |
| 5 | v1 | changelog-v2+sidedata | flat | none | native | `SidedataFilesWriteTest`/`PullSidedataRealHgInteropTest` — 쓰기+양방향 |
| 6 | v1 | changelog-v2+sidedata | tree | none | native | — |
| 7 | v2(dirstate) | v1 | flat | none | native | `DirstateV2RealFixtureTest` — real hg가 만든 실제 바이트 캡처 기반, 읽기 방향 |
| 8 | v2(dirstate) | v1 | tree | none | native | — |
| 9 | v2(dirstate) | changelog-v2 | flat | none | native | — |
| 10 | v2(dirstate) | changelog-v2 | tree | none | native | — |
| 11 | v2(dirstate) | changelog-v2+sidedata | flat | none | native | — |
| 12 | v2(dirstate) | changelog-v2+sidedata | tree | none | native | — |
| 13~24 | v1/v2 x 3changelog x flat/tree | — | persistent-nodemap | Docker | 미커버(신규) |
| 25 | v1 | v1 | flat | fileindex-v1 | Docker | `FileIndexTest`, `NodeMapFileFixtureTest`, `NodeMapFileWriterTest`, `RevlogV2GeneralParserTest`(전부 `revlogv2-general` 픽스처 공유) |
| 26~30 | 나머지 5개(dirstate/changelog 조합 x fileindex-v1, flat 고정) | — | — | Docker | 미커버(신규) |
| 31 | v1 | v1 | flat | general-v2 | Docker | `RevlogV2GeneralParserTest`(#25와 동일 픽스처 — general-v2가 fileindex-v1/persistent-nodemap을 동반하므로 사실상 같은 셀) |
| 32~36 | 나머지 5개(dirstate/changelog 조합 x general-v2, flat 고정) | — | — | Docker | 미커버(신규) |

**요약**: 36개 중 실제로 이미 뭔가 커버된 셀은 6개(#2, #3, #5, #7, #25, #31)뿐이고 —
그나마도 대부분 "real hg가 쓴 걸 hg4j가 읽는" 한쪽 방향만(#5만 양방향) — 나머지 30개는
전부 미커버다. 특히 dirstate-v2/tree-manifest/general-v2/fileindex-v1/persistent-
nodemap을 서로 조합한 셀(예: dirstate-v2 + general-v2, treemanifest + persistent-
nodemap)은 **단 하나도 시도된 적이 없다**.

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
- [x] 설계(§1) 확정, native 12개 + Docker 24개 = 36개 조합 유효성/상호배타 관계
  실측 완료(2026-09-04, `hg-rust-7.2.4` 컨테이너 포함)
- [ ] `CommitCommand`/`LogCommand`/`StatusCommand`/`CatCommand` 핵심 라운드트립 —
  **구현 착수(2026-09-04, WIP)**: `RequirementMatrixCoreRoundTripTest`
  (`src/test/java/io/github/search5/hg4j/api/`) — 현재는 native 12개 조합만
  구현(real hg 쓰기 → hg4j 읽기, hg4j 쓰기 → real hg 읽기(+`hg verify`) 양방향,
  12 x 2 = 24 케이스, 아직 컴파일/실행 검증 전). **Docker 24개 조합은 아직 이
  테스트에 반영 안 됨** — persistent-nodemap/fileindex-v1/general-v2 축을 추가로
  넣어 36개 조합으로 확장하는 게 다음 작업.
- [ ] 나머지 58개 로컬 명령 — 미착수

### 4-2. Wire 매트릭스 대상 명령
- [x] 설계(§2) 확정, 21개 조합 확정(2026-09-04)
- [ ] `CloneCommand`/`PullCommand`/`PushCommand` 핵심 라운드트립 — 미착수(기존
  `HgHttpV1NegotiationForcingInteropTest`가 개별 축 강제는 이미 하고 있으나 교차
  조합은 아직 없음)
- [ ] `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
  `NarrowCloneCommand` — 미착수

## 관련 페이지
[[mercurial-spec-compliance-requirement]] (백로그 29~36 — 이 매트릭스와 별개로,
"완료" 표시 항목 안에 남은 개별 기능 gap을 다룬다), [[test-coverage-95-percent-initiative]]
(BRANCH 커버리지 부채 — 이 매트릭스와 마찬가지로 real-hg interop과는 다른 축의 안전망).
