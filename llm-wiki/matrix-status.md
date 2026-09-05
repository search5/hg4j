---
updated: 2026-09-06
status: current
---

# Requirement/Wire 매트릭스 현재 상태 (구조화 데이터)

이 문서는 [[backlog/39-exhaustive-interop-matrix]]가 프로즈로 서술하는 캠페인의
**결과 데이터**만 표로 유지한다. 목적: "68/68 완주" 같은 총합 숫자를 프로즈 서술에서
세다가 실수하는 일(이번 세션에서 실제로 "67개"라는 off-by-one 오류로 발생)을 구조적으로
막는 것. 매트릭스 자체의 설계(4축, 36개 조합 유효성, wire 21개 조합)는
[[exhaustive-interop-matrix-plan]] 참고.

## 총계

| 구분 | 대상 | 완료 | 비율 |
|---|---|---|---|
| Requirement 매트릭스 (로컬/저장소 전용, 36개 조합: native 6 + Docker 30) | 60 | 60 | 100% |
| Wire 매트릭스 (전송 관여, 21개 조합: HTTP 18 + SSH 3) | 8 | 8 | 100% |
| **합계** | **68** | **68** | **100%** |

## Requirement 매트릭스 (60개 명령)

| 명령 | 상태 | 테스트 클래스(들) | 비고 |
|---|---|---|---|
| AddCommand | ✅ | RequirementMatrixAddCoreRoundTripTest / DockerRoundTripTest | |
| AddremoveCommand | ✅ | RequirementMatrixAddremoveCoreRoundTripTest / DockerRoundTripTest | |
| AmendCommand | ✅ | RequirementMatrixAmendCoreRoundTripTest / DockerRoundTripTest | |
| AnnotateCommand | ✅ | RequirementMatrixGrepAnnotateCoreRoundTripTest / DockerRoundTripTest | Grep과 트리오 |
| ArchiveCommand | ✅ | RequirementMatrixArchiveCoreRoundTripTest / DockerRoundTripTest / HelperMain | |
| BackoutCommand | ✅ | RequirementMatrixBackoutCoreRoundTripTest / DockerRoundTripTest | 3-way merge 신규 구현 |
| BisectCommand | ✅ | RequirementMatrixBisectCoreRoundTripTest / DockerRoundTripTest / HelperMain | |
| BookmarkCommand | ✅ | RequirementMatrixBookmarkCoreRoundTripTest / DockerRoundTripTest | |
| BranchCommand | ✅ | RequirementMatrixBranchCoreRoundTripTest / DockerRoundTripTest | Branches와 트리오 |
| BranchesCommand | ✅ | RequirementMatrixBranchCoreRoundTripTest / DockerRoundTripTest | Branch와 트리오 |
| BundleCommand | ✅ | RequirementMatrixBundleCoreRoundTripTest / DockerRoundTripTest | cl2+sidedata 콤보는 real-hg 자체 한계로 tolerate |
| CatCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / DockerRoundTripTest | 4개 명령 트리오 |
| CensorCommand | ✅ | RequirementMatrixCensorCoreRoundTripTest / DockerRoundTripTest / HelperMain | general-v2 filelog 손상 버그 발견·수정 |
| CommitCommand | ✅ | RequirementMatrixCoreRoundTripTest / DockerRoundTripTest (baseline 4개 명령 공용) | |
| CopyCommand | ✅ | RequirementMatrixCopyCoreRoundTripTest / DockerRoundTripTest | |
| DescribeCommand | ✅ | RequirementMatrixDescribeCoreRoundTripTest / DockerRoundTripTest | |
| DiffCommand | ✅ | RequirementMatrixDiffCoreRoundTripTest / DockerRoundTripTest | 가짜 후행 개행 버그 발견·수정 |
| ExportCommand | ✅ | RequirementMatrixExportImportCoreRoundTripTest / DockerRoundTripTest | Import와 트리오 |
| FilesCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / DockerRoundTripTest | 4개 명령 트리오 |
| ForgetCommand | ✅ | RequirementMatrixForgetCoreRoundTripTest / DockerRoundTripTest | |
| GcCommand | ✅ | RequirementMatrixGcCoreRoundTripTest / DockerRoundTripTest / HelperMain | |
| GraftCommand | ✅ | RequirementMatrixGraftCoreRoundTripTest / DockerRoundTripTest | v2-docket journal 후속 gap 있음, [[backlog/39-exhaustive-interop-matrix]] 참고 |
| GrepCommand | ✅ | RequirementMatrixGrepAnnotateCoreRoundTripTest / DockerRoundTripTest | Annotate와 트리오, fileindex-v1/general-v2 fallback 버그 발견·수정 |
| HeadsCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / DockerRoundTripTest | Tip+Parents와 트리오 |
| HisteditCommand | ✅ | RequirementMatrixHisteditCoreRoundTripTest / DockerRoundTripTest | |
| IdentifyCommand | ✅ | RequirementMatrixIdentifyCoreRoundTripTest / DockerRoundTripTest | Summary와 트리오, 전면 재작성 |
| ImportCommand | ✅ | RequirementMatrixExportImportCoreRoundTripTest / DockerRoundTripTest | Export와 트리오, treemanifest 미지원 버그 발견·수정 |
| InitCommand | ✅ | RequirementMatrixInitCoreRoundTripTest / DockerRoundTripTest / HelperMain | 36콤보 중 30+개 생성 불가하던 gap 전면 구현 |
| LocateCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / DockerRoundTripTest | 4개 명령 트리오 |
| LogCommand | ✅ | RequirementMatrixLogCoreRoundTripTest / DockerRoundTripTest | |
| ManifestCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / DockerRoundTripTest | 4개 명령 트리오 |
| MergeCommand | ✅ | RequirementMatrixMergeCoreRoundTripTest / DockerRoundTripTest | |
| ParentsCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / DockerRoundTripTest | Heads+Tip과 트리오 |
| PathsCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / DockerRoundTripTest | Tags+Root와 트리오, 4축 무관 명령이지만 예외 없이 검증 |
| PhaseCommand | ✅ | RequirementMatrixPhaseCoreRoundTripTest / DockerRoundTripTest | phaseroots 알고리즘 전면 재작성 |
| PurgeCommand | ✅ | RequirementMatrixPurgeCoreRoundTripTest / DockerRoundTripTest / HelperMain | 심볼릭 링크 데이터 손실 버그 발견·수정 |
| RebaseCommand | ✅ | RequirementMatrixRebaseCoreRoundTripTest / DockerRoundTripTest | |
| RecoverCommand | ✅ | RequirementMatrixRecoverCoreRoundTripTest / DockerRoundTripTest / HelperMain | |
| RemoveCommand | ✅ | RequirementMatrixRemoveCoreRoundTripTest / DockerRoundTripTest | |
| RenameCommand | ✅ | RequirementMatrixRenameCoreRoundTripTest / DockerRoundTripTest | |
| ResolveCommand | ✅ | RequirementMatrixResolveCoreRoundTripTest / DockerRoundTripTest | |
| RevertCommand | ✅ | RequirementMatrixRevertCoreRoundTripTest / DockerRoundTripTest | 데이터 손실 버그 발견·수정 |
| RevsetCommand | ✅ | RequirementMatrixRevsetCoreRoundTripTest / DockerRoundTripTest | |
| RollbackCommand | ✅ | RequirementMatrixRollbackCoreRoundTripTest / DockerRoundTripTest / HelperMain | |
| RootCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / DockerRoundTripTest | Tags+Paths와 트리오, 4축 무관 명령이지만 예외 없이 검증 |
| ShelveCommand | ✅ | RequirementMatrixShelveCoreRoundTripTest / DockerRoundTripTest | |
| SidedataChangedFilesCommand | ✅ | RequirementMatrixSidedataChangedFilesCoreRoundTripTest / DockerRoundTripTest | |
| StatusCommand | ✅ | RequirementMatrixStatusCoreRoundTripTest / DockerRoundTripTest | |
| StripCommand | ✅ | RequirementMatrixStripCoreRoundTripTest / DockerRoundTripTest | |
| SubrepoCommand | ✅ | RequirementMatrixSubrepoCoreRoundTripTest / DockerRoundTripTest | Git+SVN 서브저장소 모두 지원(백로그 41 완료, [[backlog/subrepo]]) |
| SummaryCommand | ✅ | RequirementMatrixIdentifyCoreRoundTripTest / DockerRoundTripTest | Identify와 트리오 |
| TagCommand | ✅ | RequirementMatrixTagCoreRoundTripTest / DockerRoundTripTest | |
| TagsCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / DockerRoundTripTest | Paths+Root와 트리오 |
| TipCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / DockerRoundTripTest | Heads+Parents와 트리오 |
| TreeCommand | ✅ | RequirementMatrixTreeCoreRoundTripTest / DockerRoundTripTest | |
| TreeMergeCommand | ✅ | RequirementMatrixTreeMergeCoreRoundTripTest / DockerRoundTripTest | |
| UnbundleCommand | ✅ | RequirementMatrixUnbundleCoreRoundTripTest / DockerRoundTripTest | |
| UpdateCommand | ✅ | RequirementMatrixUpdateCoreRoundTripTest / DockerRoundTripTest / HelperMain | 심볼릭 링크 모드 버그 발견·수정 |
| VerifyCommand | ✅ | RequirementMatrixVerifyCoreRoundTripTest / DockerRoundTripTest / HelperMain | fileindex-v1/general-v2/treemanifest 검사 누락 발견·수정 |
| WorktreeCommand | ✅ | RequirementMatrixWorktreeCoreRoundTripTest / DockerRoundTripTest / HelperMain | 실제 체크아웃 미수행 버그 발견·수정 |

(전부 `src/test/java/io/github/search5/hg4j/api/`)

## Wire 매트릭스 (8개 명령)

| 명령 | 상태 | 테스트 클래스 | 비고 |
|---|---|---|---|
| CloneCommand | ✅ | HgWireProtocolMatrixTest | |
| PullCommand | ✅ | HgWireProtocolMatrixTest | |
| PushCommand | ✅ | HgWireProtocolMatrixTest | |
| FetchCommand | ✅ | HgWireProtocolMatrixFetchTest | |
| IncomingCommand | ✅ | HgWireProtocolMatrixIncomingOutgoingTest | real hg 서버 상대 100% 파손 버그 발견·수정 |
| OutgoingCommand | ✅ | HgWireProtocolMatrixIncomingOutgoingTest | |
| ClonebundlesCommand | ✅ | HgWireProtocolMatrixClonebundlesTest | 서버 매니페스트-없음 응답 포맷은 [[backlog/clonebundles]] 백로그 44번 별도 진행 중 |
| NarrowCloneCommand | ✅ | HgWireProtocolMatrixNarrowCloneTest | 진짜 wire-protocol 수준 ellipsis node는 [[backlog/narrow-clone-and-lfs]] 백로그 40번 별도 진행 중 |

(전부 `src/test/java/io/github/search5/hg4j/transport/`)

## 알려진 후속 gap (매트릭스 GREEN이지만 별도 백로그로 남은 것)

- `NarrowCloneCommand`: 로컬 필터링만 있고 wire-protocol 수준 ellipsis node 협상은
  없음 — 백로그 40번, [[backlog/narrow-clone-and-lfs]].
- `ClonebundlesCommand`: 서버가 매니페스트 파일 없을 때의 응답 포맷 미확인 — 백로그
  44번, [[backlog/clonebundles]].
- `GraftCommand`: v2-docket rollback/journal gap — [[backlog/39-exhaustive-interop-matrix]]
  후속 항목 절.
