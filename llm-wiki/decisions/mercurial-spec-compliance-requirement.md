---
updated: 2026-08-31
status: current
---

# 요건: Mercurial 전체 스펙 완전 준수

> **사용자가 직접 지시한 개발 요건이다.** (2026-08-31, 대화 중 명시)
> "이 라이브러리는 머큐리얼의 모든 스펙을 완전히 준수할 수 있도록 요건을 넣어줘."
> README의 "binary-level interoperability with the official Mercurial CLI (verified against
> SCM v7.2.2)"라는 주장을, **낱낱의 공식 스펙 항목 단위로 검증 가능한 체크리스트**로
> 구체화한 것으로 이해할 것. [[jgit-parity-requirement]]가 "구조" 요건이라면, 이 문서는
> "동작/포맷 정확성" 요건이다.

## 공식 스펙 소스 (신뢰 우선순위 순)
1. **`hg help internals.*`** — Mercurial 코어 배포판에 내장된 공식 내부 스펙 문서
   (`mercurial/helptext/internals/*.txt`). 가장 권위 있는 1차 소스.
2. **mercurial-scm.org 위키** — `FileFormats`, `WireProtocol`, `BundleFormat`, `DirState`,
   `fncacheRepoFormat`, `CompatibilityRules` 등 커뮤니티가 정리한 2차 문서.
3. **Mercurial 소스 코드 자체** (`mercurial/*.py`) — 위 문서와 실제 동작이 어긋나면
   코드가 최종 근거. Mercurial은 사양 문서보다 구현이 항상 우선하는 프로젝트임을 유의.
4. **README에 명시된 기준 버전**: SCM v7.2.2. 스펙 준수 검증은 이 버전 기준으로 하고,
   버전이 바뀌면(특히 major bump) 재검증이 필요하다는 점을 명시.

## 스펙 영역별 현재 상태 (gap table)
최초 버전은 `get_symbols_overview`로 확인 가능한 **클래스 존재 여부만으로** 판단했다.
**각 항목의 "구현됨" 표시는 클래스가 존재한다는 뜻이지 스펙의 모든 세부 규칙까지
검증됐다는 뜻이 아니다** — 실제로 이 방식은 `BookmarkCommand`처럼 "클래스는 있지만
다른 명령과 전혀 연동 안 된" 반쪽짜리 구현을 놓쳤다(2026-08-31 재감사에서 발견,
Bookmarks/Obsolescence/Merge state/트랜잭션 저널링 행 갱신 및 신규 추가는 이 재감사
결과). **"확인 필요"로 남은 항목은 여전히 실제 hg 소스/공식 문서와 라인 단위로
대조해야 한다** — 이번 재감사도 grep 기반 교차 검증이지 원문 전수 대조는 아니다.

| 스펙 영역 | 공식 근거 | hg4j 관련 클래스 | 현재 판단 |
|---|---|---|---|
| `requires` 파일 / requirements | `hg help internals.requirements` | `HgRepository.loadRequires()` | ✅ 존재 (세부 requirement 문자열 커버리지는 확인 필요) |
| store 레이아웃 / fncache 인코딩 | wiki `fncacheRepoFormat` | `StoreEngine`, `DefaultFileStoreEngine` | ⚠️ 부분 — 최근 커밋(`56b1988`)에서 "fncache 레이어 불일치" 버그를 수정한 이력 자체가 이 영역이 취약했음을 시사 |
| Revlog v1 (인덱스/데이터, generaldelta, inline) | `hg help internals.revlogs`, wiki `FileFormats` | `Revlog`, `RevlogIndex`, `DeltaEngine`, `DeltaCodec` | ✅ v1 구현, **2026-09-01 실제 hg CLI 재검증 중 zstd requirement 문자열 버그 발견·수정**(`revlog-compression=zstd`→`revlog-compression-zstd`) — 상세는 [[revlog]] |
| Revlog v2 — changelog-v2(`exp-changelog-v2`) | `mercurial/revlogutils/docket.py`+실제 hg 데이터 대조 | `storage.RevlogIndex`, `storage.Revlog` | ✅ **구현 완료(2026-09-01)** — 실제 hg CLI로 생성한 저장소로 읽기/쓰기 검증, `hg verify`로 상호운용성까지 확인. 상세: [[revlog-v2-support-plan]] |
| Revlog v2 — 일반(`exp-revlogv2.2`, 매니페스트/파일로그) 및 persistent-nodemap | 동일 소스, 바이트 레이아웃은 확인했으나 fixture 미검증 | 없음 | ❌ **미구현 · 의도적 보류** — 이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 두 기능의 저장소를 아예 생성 못 함(`abort: accessing ... without associated fast implementation`), 검증 안 된 구현을 반복하지 않기 위해 보류. Rust 포함 hg 확보 시 재개. 상세: [[revlog-v2-support-plan]] |
| Changelog 포맷 (커밋 메타데이터 인코딩) | wiki `FileFormats` | `Revlog` + `api.CommitCommand`/`LogCommand` | ⚠️ 확인 필요 — extra 필드, 다중 부모, 인코딩 예외 케이스 |
| Manifest 포맷 | wiki `Manifest` | `HgRepository.getManifestRevlog()`, `treewalk.ManifestWalk` | ✅ 존재 |
| Dirstate v1 | wiki `DirState` | `Dirstate` | ✅ |
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ 존재, 정확한 바이트 레이아웃 대조는 미착수 ([[index]]의 "아직 없는 페이지" 참고) |
| Bundle1 (레거시 `HG10UN/GZ/BZ`) | wiki `BundleFormat` | 없음 | ❌ **미구현으로 추정** — `Bundle2Parser`만 존재, Bundle1 클래스 없음 |
| Bundle2 컨테이너 | `hg help internals.bundle2` | `Bundle2Parser` | ✅ |
| Changegroup (cg1/cg2/cg3) | `hg help internals.changegroups`, `mercurial/changegroup.py` 실측 | `ChangegroupParser`, `storage.Revlog`, `transport.HgLocalClient` | ⚠️ **cg1 델타 베이스 규칙 버그 발견·수정(2026-09-01)** — 실제 cg1은 `forcedeltaparentprev=True`로 각 엔트리를 "실제 DAG 부모(p1)"가 아니라 "스트림상 바로 직전 엔트리"를 기준으로 델타 인코딩한다(위치 기반, DAG와 무관). `HgLocalClient.getBundle()`이 p1 기준으로 델타를 만들고 있어서 다중 head(branch) 저장소를 pull하면 콘텐츠가 깨지는 실제 버그였음(재현·수정 완료, incremental pull의 공통 베이스 처리 포함). cg2/cg3(트리매니페스트, censor 지원 등)은 여전히 확인 필요 — Track C |
| Wire protocol v1 (HTTP/SSH, capability 협상) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `HgWireServer`, `TransportProtocol` | ✅ (README에 "capability negotiations" 명시) |
| Wire protocol v2 (실험적, cbor 기반) | `hg help internals.wireprotocolv2`/`wireprotocolrpc` | `transport.HgRemoteClientV2`, `transport.HgWireServer`, `transport.CborFrameParser` | ✅ **구현 완료(2026-09-01)** — Jackson CBOR로 capabilities/heads/changegroup/getbundle/listkeys/pushkey/unbundle 구현, capabilities 응답 포맷을 실제 스펙(중첩 commands 맵)에 맞게 정정. ⚠️ **근본적 한계**: 이 환경(및 사실상 최신 Mercurial 전반)에 wireprotocol v2를 실제로 서빙하는 서버 코드 자체가 없음(`wireprotov2server.py` 부재 확인) — 진짜 hgrpc 바이너리 프레임 프로토콜(8바이트 헤더 기반 스트림 다중화)은 미구현이고 단순 HTTP+CBOR로 근사함. 실제 서버가 없어 상호운용 검증 불가, hg4j 자체 client-server 쌍의 자기 검증만 가능. 상세: [[wireprotocol-v2-support-plan]] |
| Phases (draft/public/secret) | `hg help phases` | `PhaseRoots`, `api.PhaseCommand` | ✅ |
| Bookmarks (이동 가능한 포인터, named branch와 구별) | `hg help bookmarks`, `mercurial/bookmarks.py`(comparebookmarks/validdest 실측) | `api.BookmarkCommand`, `api.CommitCommand`, `api.UpdateCommand`, `api.FetchCommand` | ✅ **구현 완료(2026-09-01)** — commit 자동 전진/update 활성화·비활성화/pull·push 동기화 전부 구현, 실제 hg CLI로 fast-forward·진짜 divergence·원격 push/pull까지 검증(`BookmarkRealHgInteropTest`). 검증 중 데이터 손실 버그 2건 발견·수정: (1) pull 시 ancestor 관계를 안 따져서 로컬의 독자적 bookmark 이동이 조용히 덮어써지던 버그, (2) 새 changeset 없이 bookmark만 이동한 원격을 pull하면 동기화 자체가 생략되던 버그. 상세: [[bookmark-full-support-plan]] |
| Obsolescence markers | `mercurial/obsolete.py`(FM1 포맷 실측, 실제 obsstore 픽스처로 검증) | `HgObsolescenceParser`, `HgObsMarker`, `api.AmendCommand`/`RebaseCommand`/`GraftCommand`/`HisteditCommand`/`StripCommand` | ✅ **구현 완료(2026-09-01)** — 5개 명령 전부 마커 생성 확인. **완료 과정에서 obsstore 바이너리 포맷 자체가 완전히 틀렸던 것을 발견** — 파일 버전 바이트 부재, 필드 순서·크기 전부 불일치. 실제 FM1(version=1) 스펙대로 전면 재작성, 실제 hg가 만든 obsstore를 hg4j로 파싱 + hg4j가 쓴 obsstore를 실제 `hg debugobsolete`로 읽기 — 양방향 검증 통과(`HgObsolescenceRealHgInteropTest`). 상세: [[obsolescence-marker-completeness-plan]] |
| Censor (민감정보 삭제) | `hg help internals.censor` | 없음 | ❌ **미구현으로 추정** |
| Narrow clone / narrowspec | wiki 관련 문서 | `NarrowCloneCommand`, `HgTreeFilter` | ✅ (README에 명시) |
| Sparse checkout | 관련 확장 문서 | `SparsePathFilter` | ⚠️ 부분 — sparse 설정 파일(`.hg/sparse`) 자체 파싱 로직 존재 여부 확인 필요 |
| LFS (largefiles) | 관련 확장 문서 | `HgLfsManager`, `HgLfsPointer` | ✅ |
| Subrepositories (`.hgsub`/`.hgsubstate`) | wiki 관련 문서 | `HgSubrepoParser`, `HgSubrepoEntry`, `api.SubrepoCommand` | ✅ |
| Config 파일 포맷 (`hgrc`, include/`%include`, 섹션) | `hg help internals.config`, man `hgrc(5)` | `HgRcConfig` | ⚠️ 확인 필요 — `%include`/`%unset` 등 세부 지시자 커버리지 |
| Merge state 영속화 (재개 가능한 머지) | `hg help internals.mergestate` | `api.ResolveCommand`(`.hg/merge/state` 기록) | ⚠️ **확인됨(2026-08-31)** — 영속화 자체는 됨, 단 **레거시 v1 `.hg/merge/state` 포맷만 쓰고 최신 `state2` 포맷은 안 씀**(`ResolveCommand.java`에 `merge/state` 경로만 존재, `state2` 검색 0건). 실 hg CLI가 최신 필드(드라이버 처리 등)를 기대하는 상황이면 정보 손실 가능 — 우선순위는 Track C (기본 재개 자체는 동작하므로) |
| 트랜잭션 저널링 / 크래시 복구 (`recover`, `rollback`) | `hg help internals.transaction`(트랜잭션/저널 파일 포맷) | `api.CommitCommand`, `api.FetchCommand`, `api.RebaseCommand`, `api.RollbackCommand`, `lib.HgRepository.checkAndPerformAutoRollback()` | ✅ **구현 완료(2026-09-01)** — 크래시 자동복구(commit/fetch/pull/rebase/amend/graft/remove/rename/merge/strip 경로 커버)와 `RollbackCommand` 둘 다 실제 hg CLI로 검증(`RollbackRealHgInteropTest`). **완료 과정에서 발견한 실제 갭**: `FetchCommand`가 undo 정보를 안 남겨서 pull 직후에는 rollback이 전혀 동작하지 않았음(가장 흔한 실사용 시나리오) — 수정 완료. `histedit`는 아직 journal 미적용(Track C, 빈도 낮은 경로). 상세: [[journaling-crash-recovery-plan]] |
| 누락된 코어 포셀린 명령 (`forget`, `backout`, `addremove`, `verify`, `paths`, `summary`, `tip`, `root`, `parents`, `unbundle`) | `hg help <command>` 각각 | 없음 (해당 `XxxCommand` 클래스/`Hg` 파사드 메서드 전무) | ⚠️ **확인됨(2026-08-31), Track C** — `add`+`remove`로 `addremove` 대체 가능, `log`/`revset`으로 `tip`/`parents` 대체 가능 등 일부는 우회 가능하지만 명령 자체는 없음. `paths`는 조금 다름: `HgRcConfig.getPath()`로 `[paths]` 섹션 **읽기는 가능**하지만 `PullCommand`/`PushCommand`가 이를 호출하지 않아 "default" 등 별칭이 실제로 연결되진 않음 |
| Python 확장(extensions) 시스템 | `hg help internals.extensions` | 해당 없음 | 🚫 **범위 밖 확정** (2026-08-31) — "완전 준수" 요건에서 명시적으로 제외. Java 라이브러리이므로 Python 플러그인 API 자체를 이식하지 않으며, 대신 `HgHook`/`ProcessHook`으로 외부 프로세스 훅만 지원 |

## 검증 방법론 제안 (미착수, 향후 작업)
1. **실제 hg CLI 라운드트립 픽스처 확대**: `src/test/resources/fixtures/*.bundle`은 현재
   3종(`simple-3commits`, `branch-and-merge`, `large-path-dh`) 뿐 — censor, narrow, LFS,
   obsolescence marker가 포함된 실제 hg 생성 데이터를 추가로 확보해 라운드트립 테스트를
   짜야 실질적 검증이 된다.
2. **버전 고정 재검증 루틴**: README가 못박은 "SCM v7.2.2" 기준으로, 이 버전의 `hg help
   internals.*` 전문을 한 번 스냅샷해서 [[sources]]에 원문 요약을 남기는 것을 권장
   (현재는 미착수 — 이 페이지의 표는 검색 결과 기반 정리이지 원문 전수 대조가 아님).
3. **`?` 표시 항목부터 우선순위화**: 이 표에서 "확인 필요"로 남은 항목이 "미구현" 확정 항목보다
   실제 상호운용성 버그를 낼 위험이 크다 (존재는 하되 세부 규칙이 틀린 경우가 완전 부재보다
   발견하기 어렵기 때문).

## 결정된 사항 (2026-08-31, 사용자 확정 답변)
1. **Python 확장(extensions) 시스템은 "완전 준수" 범위에서 수용하지 않는다.** 언어가
   다른 이상 API 자체를 이식하는 것이 불가능하다는 판단을 그대로 확정. 대체 수단인
   `HgHook`/`ProcessHook` 외부 프로세스 훅 이상으로 확장하지 않는다.
2. **Wire protocol v2는 무조건 지원해야 한다.** 실제 서버 노출 비율과 무관하게 "완전 준수"
   요건에 포함 — 우선순위를 낮추지 않는다.
3. **Revlog v2는 무조건 지원해야 한다.** README의 "Revlog (v1)" 표기는 현재 상태일 뿐,
   목표 상태가 아니다. v1을 유지한 채 v2를 **추가로** 지원해야 한다(레포별 `requires`
   파일에 따라 v1/v2 저장소를 모두 읽고 쓸 수 있어야 함 — 하나로 대체하는 것이 아님).
4. **Bookmark는 완전히 지원해야 한다 (Track B-3).** Python 확장이 아니라 Mercurial
   코어 내장 기능이므로 "완전 준수" 범위에서 제외 대상이 아니다. 신규 기능 개발이 아니라
   **이미 부분 구현된 기능(`.hg/bookmarks` CRUD)을 완성**하는 작업 — commit 자동 전진,
   update 활성화/비활성화, push/pull 동기화까지 포함. 상세: [[bookmark-full-support-plan]].
5. **트랜잭션 저널링/크래시 복구를 지원해야 한다 (Track B-4).** 데이터 무결성 문제이며
   "완전 준수"의 전제 조건에 가깝다 — 저널/undo 파일이 없으면 다른 스펙 항목을 아무리
   정확히 구현해도 중간에 죽었을 때 저장소가 깨진다. 상세: [[journaling-crash-recovery-plan]].
6. **Obsolescence marker 생성을 history-rewriting 명령 전체로 확장해야 한다 (Track B-5).**
   `AmendCommand`에서 이미 obsstore 쓰기가 검증됐으므로 신규 기능이 아니라 기존 패턴을
   `RebaseCommand`/`GraftCommand`/`HisteditCommand`로 확장하는 작업. 상세:
   [[obsolescence-marker-completeness-plan]].

## 위 결정이 실행 계획에 미치는 영향 (후속 작업, 아직 미착수)
- **의존성 추가 필요**: wireprotocol v2는 cbor 인코딩을 요구한다 — `build.gradle`에 cbor
  파싱 라이브러리 추가가 선행 과제. 현재 의존성 목록에는 없음.
- **`Revlog` 클래스 확장 필요**: 현재 `Revlog`는 v1 전용으로 설계돼 있음(`inline` 필드만
  존재, v2 관련 필드 없음). v2 지원은 [[core-package-split-plan]]의 Phase 10(`storage`
  패키지 분리) 이후, 혹은 그 이전에라도 `Revlog`/`RevlogIndex` 내부에 버전 분기를 추가하는
  별도 작업으로 다뤄야 함 — 이 문서에는 "결정"만 반영하고 실행 계획은 아직 작성하지 않았다.
- **wireprotocol v2용 신규 클래스 필요**: `transport` 패키지에 v1 클래스들과 병행하는
  v2 전용 클래스(예: capability 협상, cbor 프레이밍)가 추가로 필요. [[transport]] 문서에
  아직 반영 안 됨 — 실제 설계 시작 시 갱신할 것.
- **다음에 만들 문서**: `decisions/revlog-v2-support-plan.md`, `decisions/wireprotocol-v2-support-plan.md`
  (둘 다 아직 미생성 — [[index]]의 "아직 없는 페이지"에 등록 필요).

## 관련 페이지
- [[jgit-parity-requirement]] — 구조/네이밍 요건 (이 문서와는 독립적인 축)
- [[revlog]], [[dirstate]], [[bundle2-changegroup]], [[revset]] — 이미 조사된 스펙 영역 상세
- [[core]], [[transport]] — 관련 구현 클래스 위치
- [[sources]] — 향후 원문 스냅샷을 추가할 위치
