---
updated: 2026-09-06
status: 번호 매겨진 백로그 1~45번 중 41번(SVN 서브저장소)과 45번(treemanifest
  manifest fncache 미등록, 43번 작업 중 발견)만 미착수, 나머지 전부 완료(25번은
  오탐으로 종결). 상세는 이 문서가 아니라 각 backlog/*.md 파일과
  known-bugs-registry.md/matrix-status.md를 참고할 것 — 이 파일은 **인덱스**로만
  유지한다(2026-09-06 재구조화, 이전 버전은 4508줄의 단일 누적 문서였음).
---

# 요건: Mercurial 전체 스펙 완전 준수 — 인덱스

> **사용자가 직접 지시한 개발 요건이다.** (2026-08-31, 대화 중 명시)
> "이 라이브러리는 머큐리얼의 모든 스펙을 완전히 준수할 수 있도록 요건을 넣어줘."
> README의 "binary-level interoperability with the official Mercurial CLI (verified
> against SCM v7.2.2)"라는 주장을, **낱낱의 공식 스펙 항목 단위로 검증 가능한
> 체크리스트**로 구체화한 것으로 이해할 것. [[jgit-parity-requirement]]가 "구조" 요건
> 이라면, 이 문서는 "동작/포맷 정확성" 요건이다.

> **2026-09-06 재구조화 안내**: 이 문서는 원래 4508줄짜리 단일 누적 문서였다 —
> 상단 요약표가 하단 상세 내용을 못 따라가고, 여러 웨이브가 같은 버그를 중복 발견하고,
> 총합 숫자가 프로즈에 묻혀 off-by-one 오류(원래 "67개"라고 계속 썼던 로컬+wire
> 명령 총합이 실은 68개였음)가 실제로 발생하는 문제가 반복됐다. 그래서 각 백로그
> 번호(또는 관련 번호 묶음)를 `llm-wiki/backlog/*.md`의 자기 완결적 문서로 분리하고,
> 이 파일은 **gap table + 백로그 목록만 남긴 얇은 인덱스**로 유지한다. 실제 상태가
> 바뀌면 대상 backlog 파일만 고치면 되고, 이 인덱스는 링크만 가리키므로 항상
> 최신을 반영한다. 반복 버그 발견을 막기 위한 `known-bugs-registry.md`, 매트릭스
> GREEN/RED 데이터를 위한 `matrix-status.md`도 이때 신설됐다.

## 공식 스펙 소스 (신뢰 우선순위 순)
1. **`hg help internals.*`** — Mercurial 코어 배포판에 내장된 공식 내부 스펙 문서
   (`mercurial/helptext/internals/*.txt`). 가장 권위 있는 1차 소스.
2. **mercurial-scm.org 위키** — `FileFormats`, `WireProtocol`, `BundleFormat`, `DirState`,
   `fncacheRepoFormat`, `CompatibilityRules` 등 커뮤니티가 정리한 2차 문서.
3. **Mercurial 소스 코드 자체** (`mercurial/*.py`) — 위 문서와 실제 동작이 어긋나면
   코드가 최종 근거. Mercurial은 사양 문서보다 구현이 항상 우선하는 프로젝트임을 유의.
4. **README에 명시된 기준 버전**: SCM v7.2.2. 스펙 준수 검증은 이 버전 기준으로 하고,
   버전이 바뀌면(특히 major bump) 재검증이 필요하다는 점을 명시.

## 스펙 영역별 현재 상태 (gap table — 요약만, 상세는 링크된 backlog 파일 참고)

| 스펙 영역 | 공식 근거 | hg4j 관련 클래스 | 상태 | 상세 |
|---|---|---|---|---|
| `requires` 파일 / requirements | `hg help internals.requirements` | `HgRepository.loadRequires()`, `Hg.open()` | ✅ 완료 | [[backlog/requires-format-strings]] |
| store 레이아웃 / fncache 인코딩 | wiki `fncacheRepoFormat` | `util.NodeIdUtil.encodeFname` | ✅ 완료(2026-09-01) | [[concepts/revlog]] |
| Revlog v1 (인덱스/데이터, generaldelta, inline) | `hg help internals.revlogs` | `Revlog`, `RevlogIndex`, `DeltaEngine`, `DeltaCodec` | ✅ 완료 | [[backlog/revlog-storage-formats]] |
| Revlog v2 — changelog-v2 | `mercurial/revlogutils/docket.py` | `storage.RevlogIndex`, `storage.Revlog` | ✅ 완료(2026-09-01) | [[revlog-v2-support-plan]] |
| Revlog v2 — 일반(`exp-revlogv2.2`) + `fileindex-v1` + persistent-nodemap | Rust 확장 실측 | `RevlogIndex`, `NodeMapFile`, `FileIndex` | ✅ 완료 | [[backlog/revlog-storage-formats]] |
| Revlog inline→non-inline 성장 전환 | `_enforceinlinesize`(real hg) | `Revlog` | ✅ 완료(백로그 43) | [[backlog/revlog-storage-formats]] |
| Manifest 포맷 (flat + treemanifest) | wiki `Manifest` | `ManifestWalk`, `ManifestTreeIterator` | ✅ 완료 | [[backlog/treemanifest]] |
| Dirstate v1 | wiki `DirState` | `Dirstate` | ✅ 완료 | [[concepts/dirstate]] |
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ 완료(5건 버그 발견) | [[dirstate-v2]] |
| Sidedata (revlog v2 부가 메타데이터) | `mercurial/revlogutils/sidedata.py` | `SidedataCodec`, `ChangingFiles`, `SidedataChangedFilesCommand` | ✅ 완료 | [[backlog/sidedata-copy-tracing]] |
| Changegroup (cg1~cg5) | `hg help internals.changegroups` | `ChangegroupParser`, `Revlog`, `HgLocalClient` | ✅ 완료 | [[backlog/changegroup-versions]] |
| Censor(민감정보 삭제) | `mercurial/revlogutils/rewrite.py` | `Revlog.censorRevision`, `CensorCommand` | ✅ 완료(2단계 발견) | [[censor]] |
| Merge state 영속화 (재개 가능한 머지) | `mercurial/mergestate.py` | `MergeState`, `MergeCommand`, `ResolveCommand` | ✅ 완료 | [[backlog/01-resolve-mergestate]] |
| 트랜잭션 저널링 / 크래시 복구 | `hg help internals.transaction` | `CommitCommand`, `FetchCommand`, `RollbackCommand`, `HisteditCommand`, `GraftCommand` | ✅ 완료(GraftCommand v2-docket 후속 gap도 해결) | [[backlog/39-exhaustive-interop-matrix]] |
| 누락된 코어 포셀린 명령 노출 | `hg help <command>` | `Hg` 파사드 전체 | ✅ 완료 | [[backlog/porcelain-command-exposure]] |
| Python 확장(extensions) 시스템 | `hg help internals.extensions` | 해당 없음 | 🚫 범위 밖 확정(2026-08-31) | — |
| Wire protocol v1 (HTTP/SSH) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `HgHttpWireServer`, `HgSshWireServer` | ✅ 완료 | [[backlog/wire-protocol-negotiation]] |
| Wireprotocol v2 | 프레임+cbor | `HgRemoteClientV2`, `Wire2Commands` | ✅ 완료(2026-09-01, 6.1부터 real hg 자체가 폐기) | [[wireprotocol-v2-support-plan]] |
| Obsolescence markers | `mercurial/obsolete.py` | `HgObsolescenceParser`, `HgObsMarker` | ✅ 완료 | [[obsolescence-marker-completeness-plan]] |
| Bookmark 전체 연동 | — | `BookmarkCommand` | ✅ 완료 | [[bookmark-full-support-plan]] |
| Clonebundles | `mercurial/wireprotov1server.py` | `ClonebundlesCommand` | ✅ 완료(서버 매니페스트-없음 응답 포함, 백로그 44) | [[backlog/clonebundles]] |
| Narrow clone / narrowspec | `mercurial/narrowspec.py` | `NarrowCloneCommand`, `HgTreeFilter` | ✅ 완료(로컬 필터링 + wire-protocol genuine narrow 협상, 백로그 40 — ellipsis node 전제는 오판으로 폐기) | [[backlog/narrow-clone-and-lfs]] |
| LFS(Large File Storage) | `mercurial/lfs/` | `HgLfsPointer`, `HgLfsManager` | ✅ 완료(세부 옵션 3가지 포함, 백로그 42) | [[backlog/narrow-clone-and-lfs]] |
| Subrepositories (Git) | `mercurial/subrepo.py` | `GitSubrepoUtil`, `HgSubrepoParser` | ✅ 완료 | [[backlog/subrepo]] |
| Subrepositories (SVN) | `[svn]` prefix | 해당 없음(미구현) | 🔶 진행 중(백로그 41, 최하 우선순위였으나 착수) | [[backlog/subrepo]] |
| 심볼릭 링크 (`lstat` 처리 전반) | Java `File` vs NIO lstat | 10개+ 파일 | ✅ 완료 | [[symlink-handling]] |
| Push 정확성/동시성 | `hg help internals.*` | `PushCommand` | ✅ 완료 | [[backlog/push-and-concurrency]] |
| 워킹 브랜치 복원 (update/histedit/bisect/merge/strip) | — | 5개 명령 | ✅ 완료 | [[backlog/branch-restore-bugs]] |
| Exhaustive interop matrix (68개 명령 × requirement/wire 조합) | 자체 설계 | 전체 포셀린 계층 | ✅ 완료(백로그 39) | [[backlog/39-exhaustive-interop-matrix]], `matrix-status.md` |

## 백로그 번호별 문서 목록

각 항목은 발견 경위·근본 원인·수정 내용·검증 방법·관련 커밋을 담은 자기 완결적 문서다.
완료된 항목도 삭제하지 않고 취소선/✅ 표시로 히스토리를 보존한다.

| 번호 | 제목 | 상태 | 문서 |
|---|---|---|---|
| 1 | ResolveCommand의 MergeState 연동 | ✅ | [[backlog/01-resolve-mergestate]] |
| 2, 3 | HgRemoteClient v1→v2 업그레이드, 라이브 서버 검증 | ✅ | [[backlog/wire-protocol-negotiation]] |
| 4 | Revlog v2 일반 + persistent-nodemap + fileindex-v1 | ✅ | [[backlog/revlog-storage-formats]] |
| 5 | Dirstate v2 바이트 레이아웃 | ✅ | [[dirstate-v2]] |
| 6, 7 | Censor + cg3 censor 지원 | ✅ | [[censor]] |
| 8 | Treemanifest 읽기 | ✅ | [[treemanifest]] |
| 9 | Clonebundles | ✅ | [[backlog/clonebundles]] |
| 10 | 깨진 symlink 누락·거부 | ✅ | [[symlink-handling]] |
| 11 | Changegroup cg4/cg5 | ✅ | [[backlog/changegroup-versions]] |
| 12 | 포셀린 명령 노출 완전성 | ✅ | [[backlog/porcelain-command-exposure]] |
| 13 | PushCommand 증분 push 누락 | ✅ | [[backlog/push-and-concurrency]] |
| 14 | CommitCommand symlink 크기 참조 버그 | ✅ | [[symlink-handling]] |
| 15 | persistent-nodemap 가속 읽기 | ✅ | [[backlog/revlog-storage-formats]] |
| 16 | Bundle1 writer 압축 타입 | ✅ | [[backlog/changegroup-versions]] |
| 17 | Sidedata decode/조회 | ✅ | [[backlog/sidedata-copy-tracing]] |
| 18 | Treemanifest 쓰기 | ✅ | [[treemanifest]] |
| 19 | Sidedata SD_FILES writer | ✅ | [[backlog/sidedata-copy-tracing]] |
| 20 | Wireprotocol v2 재귀 tree fetch | ✅ | [[treemanifest]] |
| 21 | persistent-nodemap 쓰기 | ✅ | [[backlog/revlog-storage-formats]] |
| 22 | HTTP/SSH 협상 테스트 확충 | ✅ | [[backlog/wire-protocol-negotiation]] |
| 23 | commit/push/branch/merge/tag(+rebase/shelve/bisect/strip/subrepo) 실전 종합 검증 | ✅ | [[backlog/23-core-command-interop-verification]] |
| 24, 25 | 장수 서버 stale 캐시, 브랜치 clone 오탐(오탐 종결) | ✅ | [[backlog/wire-protocol-negotiation]] |
| 26 | bundle2/sidedata 자체 changegroup 경로 | ✅ | [[backlog/changegroup-versions]] |
| 27 | log --follow/annotate copy-tracing | ✅ | [[backlog/sidedata-copy-tracing]] |
| 28 | Narrow clone/LFS interop 검증 | ✅ | [[backlog/narrow-clone-and-lfs]] |
| 29 | requires 문자열 재검증 | ✅ | [[backlog/requires-format-strings]] |
| 30 | Narrow clone wire-level 재통합(로컬) | ✅ | [[backlog/narrow-clone-and-lfs]] |
| 31 | LFS 커밋/체크아웃 파이프라인 | ✅ | [[backlog/narrow-clone-and-lfs]] |
| 32 | Subrepo 잔여 gap 4건(Git) | ✅ | [[backlog/subrepo]] |
| 33 | PushCommand SSH checkheads | ✅ | [[backlog/push-and-concurrency]] |
| 34, 36 | Bisect DAG 검증, Tag 재태깅 가드 | ✅ | [[backlog/misc-command-fixes]] |
| 35 | Revlog inline 레이아웃 | ✅ | [[backlog/revlog-storage-formats]] |
| 37 | dirstate-v2 트리 구조 유실 | ✅ | [[dirstate-v2]] |
| 38 | 동시 push 레이스 컨디션 | ✅ | [[backlog/push-and-concurrency]] |
| 39 | Exhaustive interop matrix (68개 명령) | ✅ | [[backlog/39-exhaustive-interop-matrix]] |
| 40 | Narrow clone wire-protocol 진짜 협상(genuine, ellipsis 아님) | ✅ | [[backlog/narrow-clone-and-lfs]] |
| 41 | SVN 서브저장소 지원 | 🔶 진행 중 | [[backlog/subrepo]] |
| 42 | LFS 세부 옵션 3가지 | ✅ | [[backlog/narrow-clone-and-lfs]] |
| 43 | Revlog inline→non-inline 성장 전환 | ✅ | [[backlog/revlog-storage-formats]] |
| 44 | Clonebundles 서버 매니페스트-없음 응답 | ✅ | [[backlog/clonebundles]] |
| 45 | CommitCommand가 treemanifest manifest를 fncache 미등록(43번 작업 중 발견) | 🔶 미착수 | [[backlog/revlog-storage-formats]] |
| — | 워킹 브랜치 복원 버그 4건 + histedit 저널링 | ✅ | [[backlog/branch-restore-bugs]] |

## 반복 발견 방지 규칙

**새 명령/기능의 매트릭스 작업이나 버그 조사를 시작하기 전, 그 명령이 호출하는
하위 클래스들을 `known-bugs-registry.md`에서 먼저 검색할 것.** 이 세션에서
`DeltaCodec.decompressZstd`가 4개의 서로 다른 병렬 웨이브에서 각각 독립적으로
재발견된 사례가 이 규칙의 직접적 근거다 — 상세는 `known-bugs-registry.md` 참고.

## 관련 페이지
- [[jgit-parity-requirement]] — 구조/네이밍 요건 (이 문서와는 독립적인 축)
- [[test-coverage-95-percent-initiative]] — 커버리지 작업 라운드별 상세 결과/버그 목록
- `known-bugs-registry.md` — 클래스/메서드 단위 버그 색인(반복 발견 방지)
- `matrix-status.md` — 68개 명령의 requirement/wire 매트릭스 현재 상태(구조화 데이터)
- [[revlog]], [[dirstate]], [[bundle2-changegroup]], [[revset]] — 이미 조사된 스펙 영역 상세
- [[lib]], [[transport]] — 관련 구현 클래스 위치(`core` 패키지는 Track A에서 `lib`로 병합됨)
- `sources/2026-08-31-initial-codebase-survey.md` — 원문 스냅샷 위치
