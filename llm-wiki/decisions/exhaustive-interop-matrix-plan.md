---
name: exhaustive-interop-matrix-plan
updated: 2026-09-04
status: 설계 완료(표 확정), 구현은 requirement 매트릭스 핵심 라운드트립(commit/log/status/cat)
  1차 착수 — 나머지는 전부 미착수. 백로그 29~36과는 별개 축(개별 기능 gap이 아니라
  "조합 공간을 체계적으로 훑는 인프라" 자체가 목적)이다.
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

### 1-1. Native-buildable 3축 (이 호스트의 순정 파이썬 `hg` 7.2.2로 즉시 실행 가능, 2026-09-04 실측)

`hg init --config ...`를 여러 조합으로 직접 실행해 확인(아래 §1-3 참고). 다음 3개
불리언/3지선다 토글은 서로 자유롭게 조합 가능하고 Docker 없이 바로 테스트를 돌릴 수
있다:

| 축 | 선택지 | config 키 |
|---|---|---|
| dirstate | v1 (기본) / v2 | `format.use-dirstate-v2=yes` |
| changelog | v1(기본) / changelog-v2 / changelog-v2+sidedata-copies | `format.exp-use-changelog-v2=...` (+`format.exp-use-copies-side-data-changeset=yes`가 changelog-v2를 자동으로 암시함, 실측 확인) |
| manifest | flat(기본) / treemanifest | `experimental.treemanifest=1` |

2 x 3 x 2 = **12개 조합**, 전부 유효(서로 배타적인 조합 없음, 실측 확인):

| # | dirstate | changelog | manifest |
|---|---|---|---|
| 1 | v1 | v1 | flat |
| 2 | v1 | v1 | tree |
| 3 | v1 | changelog-v2 | flat |
| 4 | v1 | changelog-v2 | tree |
| 5 | v1 | changelog-v2+sidedata | flat |
| 6 | v1 | changelog-v2+sidedata | tree |
| 7 | v2 | v1 | flat |
| 8 | v2 | v1 | tree |
| 9 | v2 | changelog-v2 | flat |
| 10 | v2 | changelog-v2 | tree |
| 11 | v2 | changelog-v2+sidedata | flat |
| 12 | v2 | changelog-v2+sidedata | tree |

### 1-2. Docker 전용 3개 (매트릭스에서 제외, 기존 fixture로 별도 커버)

`experimental.revlogv2`(general v2, `exp-revlogv2.2`), `format.use-fileindex-v1`,
`format.use-persistent-nodemap` 셋 다 이 호스트 순정 파이썬 `hg`로는
`abort: accessing '...' repository without associated fast implementation`로
저장소 생성 자체가 실패한다(2026-09-04 실측, §1-3 로그 참고) — Rust 확장이 포함된
`hg-rust-7.2.4` Docker 이미지가 있어야 한다. 이 셋을 파라미터화 매트릭스에 라이브로
넣으면 케이스마다 Docker를 띄워야 해서 느리고 불안정해지므로, 이미 존재하는
사전 캡처 fixture 기반 테스트(`RevlogV2GeneralParserTest`, `FileIndexTest`,
`NodeMapFileFixtureTest`, `NodeMapFileWriterTest`, `src/test/resources/fixtures/
revlogv2-general/`)가 이 조합을 계속 전담한다. 신규 매트릭스 인프라의 책임 범위 밖.

### 1-3. 실측 로그 (재현 근거)

```
OK   v1plain        requires=[...revlogv1,store,...]
OK   changelogv2     requires=[...exp-changelog-v2,...]
FAIL generalv2       (abort: accessing `fileindex` repository without associated fast implementation)
FAIL pnodemap-alone   (abort: accessing `persistent-nodemap` repository without associated fast implementation)
OK   treemanifest    requires=[...treemanifest,...]
OK   sidedata-alone  requires=[...exp-changelog-v2,exp-copies-sidedata-changeset,...]  <- changelog-v2 자동 암시
OK   zstd / sparserevlog / generaldelta-off  (기본값과 동일하게 처리됨, 별도 requirement 안 남음)
```

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

59개를 12개 requirement 조합 전부에 매번 적용하면 708개 케이스라 한 번에 구현하기엔
너무 크다 — §4의 우선순위대로 점진적으로 채운다.

## 4. 구현 우선순위 및 진행 상황

핵심 라운드트립(commit → log/status/cat, clone/pull/push)부터 먼저 매트릭스를 채우고,
이후 나머지 로컬 명령·전송 명령으로 확장한다. 각 명령이 매트릭스에 편입될 때마다 이
표의 상태를 갱신한다.

### 4-1. Requirement 매트릭스 대상 명령
- [x] 설계(§1) 확정, 12개 조합 유효성 실측 완료(2026-09-04)
- [ ] `CommitCommand`/`LogCommand`/`StatusCommand`/`CatCommand` 핵심 라운드트립 —
  **구현 착수(2026-09-04, WIP)**: `RequirementMatrixCoreRoundTripTest`
  (`src/test/java/io/github/search5/hg4j/api/`) — real hg 쓰기 → hg4j 읽기,
  hg4j 쓰기 → real hg 읽기(+`hg verify`) 양방향, 12개 조합 x 2방향 = 24 케이스.
  아직 컴파일/실행 검증 전.
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
