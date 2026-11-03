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
| AddCommand | ✅ | RequirementMatrixAddCoreRoundTripTest / RealHgRoundTripTest | |
| AddremoveCommand | ✅ | RequirementMatrixAddremoveCoreRoundTripTest / RealHgRoundTripTest | |
| AmendCommand | ✅ | RequirementMatrixAmendCoreRoundTripTest / RealHgRoundTripTest | |
| AnnotateCommand | ✅ | RequirementMatrixGrepAnnotateCoreRoundTripTest / RealHgRoundTripTest | Grep과 트리오 |
| ArchiveCommand | ✅ | RequirementMatrixArchiveCoreRoundTripTest / RealHgRoundTripTest / HelperMain | |
| BackoutCommand | ✅ | RequirementMatrixBackoutCoreRoundTripTest / RealHgRoundTripTest | 3-way merge 신규 구현 |
| BisectCommand | ✅ | RequirementMatrixBisectCoreRoundTripTest / RealHgRoundTripTest / HelperMain | |
| BookmarkCommand | ✅ | RequirementMatrixBookmarkCoreRoundTripTest / RealHgRoundTripTest | |
| BranchCommand | ✅ | RequirementMatrixBranchCoreRoundTripTest / RealHgRoundTripTest | Branches와 트리오 |
| BranchesCommand | ✅ | RequirementMatrixBranchCoreRoundTripTest / RealHgRoundTripTest | Branch와 트리오 |
| BundleCommand | ✅ | RequirementMatrixBundleCoreRoundTripTest / RealHgRoundTripTest | cl2+sidedata 콤보는 real-hg 자체 한계로 tolerate |
| CatCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / RealHgRoundTripTest | 4개 명령 트리오 |
| CensorCommand | ✅ | RequirementMatrixCensorCoreRoundTripTest / RealHgRoundTripTest / HelperMain | general-v2 filelog 손상 버그 발견·수정 |
| CommitCommand | ✅ | RequirementMatrixCoreRoundTripTest / RealHgRoundTripTest (baseline 4개 명령 공용) | |
| CopyCommand | ✅ | RequirementMatrixCopyCoreRoundTripTest / RealHgRoundTripTest | |
| DescribeCommand | ✅ | RequirementMatrixDescribeCoreRoundTripTest / RealHgRoundTripTest | |
| DiffCommand | ✅ | RequirementMatrixDiffCoreRoundTripTest / RealHgRoundTripTest | 가짜 후행 개행 버그 발견·수정 |
| ExportCommand | ✅ | RequirementMatrixExportImportCoreRoundTripTest / RealHgRoundTripTest | Import와 트리오 |
| FilesCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / RealHgRoundTripTest | 4개 명령 트리오 |
| ForgetCommand | ✅ | RequirementMatrixForgetCoreRoundTripTest / RealHgRoundTripTest | |
| GcCommand | ✅ | RequirementMatrixGcCoreRoundTripTest / RealHgRoundTripTest / HelperMain | |
| GraftCommand | ✅ | RequirementMatrixGraftCoreRoundTripTest / RealHgRoundTripTest | v2-docket journal 후속 gap 있음, [[backlog/39-exhaustive-interop-matrix]] 참고 |
| GrepCommand | ✅ | RequirementMatrixGrepAnnotateCoreRoundTripTest / RealHgRoundTripTest | Annotate와 트리오, fileindex-v1/general-v2 fallback 버그 발견·수정 |
| HeadsCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / RealHgRoundTripTest | Tip+Parents와 트리오 |
| HisteditCommand | ✅ | RequirementMatrixHisteditCoreRoundTripTest / RealHgRoundTripTest | |
| IdentifyCommand | ✅ | RequirementMatrixIdentifyCoreRoundTripTest / RealHgRoundTripTest | Summary와 트리오, 전면 재작성 |
| ImportCommand | ✅ | RequirementMatrixExportImportCoreRoundTripTest / RealHgRoundTripTest | Export와 트리오, treemanifest 미지원 버그 발견·수정 |
| InitCommand | ✅ | RequirementMatrixInitCoreRoundTripTest / RealHgRoundTripTest / HelperMain | 36콤보 중 30+개 생성 불가하던 gap 전면 구현 |
| LocateCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / RealHgRoundTripTest | 4개 명령 트리오 |
| LogCommand | ✅ | RequirementMatrixLogCoreRoundTripTest / RealHgRoundTripTest | |
| ManifestCommand | ✅ | RequirementMatrixCatFilesLocateManifestCoreRoundTripTest / RealHgRoundTripTest | 4개 명령 트리오 |
| MergeCommand | ✅ | RequirementMatrixMergeCoreRoundTripTest / RealHgRoundTripTest | |
| ParentsCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / RealHgRoundTripTest | Heads+Tip과 트리오 |
| PathsCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / RealHgRoundTripTest | Tags+Root와 트리오, 4축 무관 명령이지만 예외 없이 검증 |
| PhaseCommand | ✅ | RequirementMatrixPhaseCoreRoundTripTest / RealHgRoundTripTest | phaseroots 알고리즘 전면 재작성 |
| PurgeCommand | ✅ | RequirementMatrixPurgeCoreRoundTripTest / RealHgRoundTripTest / HelperMain | 심볼릭 링크 데이터 손실 버그 발견·수정 |
| RebaseCommand | ✅ | RequirementMatrixRebaseCoreRoundTripTest / RealHgRoundTripTest | |
| RecoverCommand | ✅ | RequirementMatrixRecoverCoreRoundTripTest / RealHgRoundTripTest / HelperMain | |
| RemoveCommand | ✅ | RequirementMatrixRemoveCoreRoundTripTest / RealHgRoundTripTest | |
| RenameCommand | ✅ | RequirementMatrixRenameCoreRoundTripTest / RealHgRoundTripTest | |
| ResolveCommand | ✅ | RequirementMatrixResolveCoreRoundTripTest / RealHgRoundTripTest | |
| RevertCommand | ✅ | RequirementMatrixRevertCoreRoundTripTest / RealHgRoundTripTest | 데이터 손실 버그 발견·수정 |
| RevsetCommand | ✅ | RequirementMatrixRevsetCoreRoundTripTest / RealHgRoundTripTest | |
| RollbackCommand | ✅ | RequirementMatrixRollbackCoreRoundTripTest / RealHgRoundTripTest / HelperMain | |
| RootCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / RealHgRoundTripTest | Tags+Paths와 트리오, 4축 무관 명령이지만 예외 없이 검증 |
| ShelveCommand | ✅ | RequirementMatrixShelveCoreRoundTripTest / RealHgRoundTripTest | |
| SidedataChangedFilesCommand | ✅ | RequirementMatrixSidedataChangedFilesCoreRoundTripTest / RealHgRoundTripTest | |
| StatusCommand | ✅ | RequirementMatrixStatusCoreRoundTripTest / RealHgRoundTripTest | |
| StripCommand | ✅ | RequirementMatrixStripCoreRoundTripTest / RealHgRoundTripTest | |
| SubrepoCommand | ✅ | RequirementMatrixSubrepoCoreRoundTripTest / RealHgRoundTripTest | Git+SVN 서브저장소 모두 지원(백로그 41 완료, [[backlog/subrepo]]) |
| SummaryCommand | ✅ | RequirementMatrixIdentifyCoreRoundTripTest / RealHgRoundTripTest | Identify와 트리오 |
| TagCommand | ✅ | RequirementMatrixTagCoreRoundTripTest / RealHgRoundTripTest | |
| TagsCommand | ✅ | RequirementMatrixTagsCoreRoundTripTest / RealHgRoundTripTest | Paths+Root와 트리오 |
| TipCommand | ✅ | RequirementMatrixHeadsCoreRoundTripTest / RealHgRoundTripTest | Heads+Parents와 트리오 |
| TreeCommand | ✅ | RequirementMatrixTreeCoreRoundTripTest / RealHgRoundTripTest | |
| TreeMergeCommand | ✅ | RequirementMatrixTreeMergeCoreRoundTripTest / RealHgRoundTripTest | |
| UnbundleCommand | ✅ | RequirementMatrixUnbundleCoreRoundTripTest / RealHgRoundTripTest | |
| UpdateCommand | ✅ | RequirementMatrixUpdateCoreRoundTripTest / RealHgRoundTripTest / HelperMain | 심볼릭 링크 모드 버그 발견·수정 |
| VerifyCommand | ✅ | RequirementMatrixVerifyCoreRoundTripTest / RealHgRoundTripTest / HelperMain | fileindex-v1/general-v2/treemanifest 검사 누락 발견·수정 |
| WorktreeCommand | ✅ | RequirementMatrixWorktreeCoreRoundTripTest / RealHgRoundTripTest / HelperMain | 실제 체크아웃 미수행 버그 발견·수정. **2026-09-07 재검증에서 회귀 발견·수정**: sharedpath가 getAbsolutePath()(심볼릭 링크 미해석)를 써서 real hg의 realpath 기준과 macOS 심볼릭 링크 경로에서 어긋나던 버그, getCanonicalPath()로 수정 — [[39-exhaustive-interop-matrix]] "후속 회귀 발견" 절 참고 |

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
