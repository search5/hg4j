---
updated: 2026-09-01
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
| store 레이아웃 / fncache 인코딩 | wiki `fncacheRepoFormat`, `mercurial/store.py`(`_pathencode`/`_hashencode` 실측) | `util.NodeIdUtil.encodeFname` | ✅ **구현 완료(2026-09-01)** — `store.py`의 실제 인코딩 알고리즘대로 전면 재작성. 이전에는 Windows `COM#`/`LPT#` 예약어의 세 번째 글자가 아니라 끝자리 숫자를 이스케이프하는 버그와, 긴 경로(120바이트 초과) 해싱 방식이 실제 hg에 없는 방식(255바이트 초과 시 디렉터리 없는 형태로 전환)으로 되어 있던 버그를 발견·수정. 대문자/앞자리 점/Windows 예약어/150바이트 초과 경로 등 7개 까다로운 파일명으로 실제 hg 온디스크 레이아웃과 바이트 단위 일치 검증(`FncacheEncodingInteropTest`). fncache 파일 목록 자체(원본 논리 경로 그대로 저장)는 기존에도 정확했음 |
| Revlog v1 (인덱스/데이터, generaldelta, inline) | `hg help internals.revlogs`, wiki `FileFormats` | `Revlog`, `RevlogIndex`, `DeltaEngine`, `DeltaCodec` | ✅ v1 구현, **2026-09-01 실제 hg CLI 재검증 중 zstd requirement 문자열 버그 발견·수정**(`revlog-compression=zstd`→`revlog-compression-zstd`) — 상세는 [[revlog]] |
| Revlog v2 — changelog-v2(`exp-changelog-v2`) | `mercurial/revlogutils/docket.py`+실제 hg 데이터 대조 | `storage.RevlogIndex`, `storage.Revlog` | ✅ **구현 완료(2026-09-01)** — 실제 hg CLI로 생성한 저장소로 읽기/쓰기 검증, `hg verify`로 상호운용성까지 확인. 상세: [[revlog-v2-support-plan]] |
| Revlog v2 — 일반(`exp-revlogv2.2`, 매니페스트/파일로그) 및 persistent-nodemap | 동일 소스, 바이트 레이아웃은 확인했으나 fixture 미검증 | 없음 | ❌ **미구현 · 의도적 보류** — 이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 두 기능의 저장소를 아예 생성 못 함(`abort: accessing ... without associated fast implementation`), 검증 안 된 구현을 반복하지 않기 위해 보류. Rust 포함 hg 확보 시 재개. 상세: [[revlog-v2-support-plan]] |
| Changelog 포맷 (커밋 메타데이터 인코딩) | wiki `FileFormats`, `mercurial/changelog.py`(`encodeextra`/`add` 실측) | `Revlog` + `api.CommitCommand`/`LogCommand` | ✅ **구현 완료(2026-09-01)** — 다중 부모(p1/p2 정렬 포함 노드 해시 계산) 인코딩은 기존에도 정확했음. **발견·수정한 실제 버그**: default 브랜치 커밋에 항상 "branch:default" extra 필드를 썼는데, 실제 hg는 default 브랜치일 때 이 필드를 아예 안 써서 hg4j가 만든 default 브랜치 커밋의 노드 해시가 동일 내용이라도 실제 hg와 달라지고 있었음. 콜론을 이스케이프하는(실제 hg엔 없는) 가짜 extra-key 인코딩도 제거. 동일 입력에 대해 노드 해시가 실제 hg와 일치함을 확인(`ChangelogExtraFieldInteropTest`) |
| Manifest 포맷 | wiki `Manifest` | `HgRepository.getManifestRevlog()`, `treewalk.ManifestWalk` | ✅ 존재 |
| Dirstate v1 | wiki `DirState` | `Dirstate` | ✅ |
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ 존재, 정확한 바이트 레이아웃 대조는 미착수 ([[index]]의 "아직 없는 페이지" 참고) |
| Bundle1 (레거시 `HG10UN/GZ/BZ`) | wiki `BundleFormat` | `api.UnbundleCommand`, `api.FetchCommand` | ⚠️ **부분(2026-09-01 확인)** — 읽기(HG10UN/HG10GZ/HG10BZ 세 압축 형식 전부)는 실제 `hg bundle --type=none-v1/gzip-v1/bzip2-v1`로 만든 파일로 검증됨(`TrackCMissingCommandsInteropTest`). 다만 독립된 "Bundle1 writer" 클래스는 없음 — `HgLocalClient.getBundle()`이 내부적으로 HG10UN 스트림을 만들긴 하지만 이는 wire protocol 응답용이지 파일로 저장하는 `hg bundle --type=v1` 대응 명령은 없음. 백로그 참고 |
| Bundle2 컨테이너 | `hg help internals.bundle2` | `Bundle2Parser` | ✅ **버그 2건 발견·수정(2026-09-01)** — 스트림 파라미터 크기 필드가 2바이트가 아니라 실제로는 4바이트(`_fstreamparamsize='>i'`)였던 버그, 파트 헤더의 파라미터를 키/값 교차로 읽던 게 아니라 실제로는 모든 (keylen,vallen) 쌍을 먼저 읽고 그다음 키/값 바이트를 순서대로 읽는 2단계 구조였던 버그. 둘 다 실제 `hg bundle`(기본 bzip2 압축) 결과물로 발견 |
| Changegroup (cg1/cg2/cg3) | `hg help internals.changegroups`, `mercurial/changegroup.py` 실측 | `ChangegroupParser`, `storage.Revlog`, `transport.HgLocalClient`, `transport.HgRemoteClientV2` | ✅ **cg1/cg2/cg3 헤더 구조 버그 발견·수정 완료(2026-09-01)** — (1) cg1 델타 베이스 규칙: 실제 cg1은 `forcedeltaparentprev=True`로 각 엔트리를 "실제 DAG 부모(p1)"가 아니라 "스트림상 바로 직전 엔트리"를 기준으로 델타 인코딩한다(위치 기반, DAG와 무관). `HgLocalClient.getBundle()`이 p1 기준으로 델타를 만들고 있어서 다중 head(branch) 저장소를 pull하면 콘텐츠가 깨지는 실제 버그였음. (2) cg2/cg3 델타 헤더 필드 순서: 실제 구조체는 `node,p1,p2,deltabase,cs`인데 hg4j는 `node,p1,p2,cs,deltabase`로 읽어서 changelog 그룹에서 deltabase가 항상 자기 자신의 node와 같아지는 버그였음(`node`/`cs`가 changelog에서는 같은 값이라 증상이 그렇게 나타남) — 실제 `hg bundle` 결과물의 unbundle 실패로 발견. cg3의 트리매니페스트/censor 관련 필드는 여전히 미확인 — 백로그 참고 |
| Wire protocol v1 (HTTP/SSH, capability 협상) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `HgWireServer`, `TransportProtocol` | ✅ 기본 구현 존재, ⚠️ **실제 "살아있는" 최신 Mercurial 서버/클라이언트와의 라이브 통신 검증은 미착수** — 이번 세션에 검증한 것은 실제 hg가 만든 번들/체인지그룹 "파일"과의 정적 라운드트립뿐이고, `hg serve`로 띄운 실제 서버에 hg4j 클라이언트가 HTTP로 접속해 실시간 clone/pull/push를 하는 시나리오, 또는 실제 hg 클라이언트가 hg4j의 `HgWireServer`(HTTP)에 접속하는 시나리오는 한 번도 실행해보지 않았다(wireprotocol v2에서는 Mercurial 6.0으로 이걸 했지만 v1으로는 안 함). 백로그 참고 |
| Wire protocol v2 (실험적, cbor+프레임 기반) | `hg help internals.wireprotocolv2`, `mercurial/wireprotoframing.py`/`wireprotov2server.py`/`wireprotov2peer.py` 실측(Mercurial 6.0) | `transport.HgRemoteClientV2`, `transport.HgWireServer`, `transport.wireprotov2.*`(`Wire2Frame`/`Wire2Transport`/`Wire2Commands`/`Cbor`) | ✅ **전면 재구현 완료(2026-09-01)** — 이전 구현은 사실상 전부 가짜였다(존재하지도 않는 `/api/<command>` 평면 HTTP+CBOR 스킴, `changegroup`/`getbundle`/`unbundle`이라는 v2에 없는 명령, 실제로는 모든 문자열이 CBOR byte-string인데 text-string으로 인코딩). Mercurial 6.0(v2 서버 코드가 남아있는 마지막 릴리스 — 6.1에서 완전히 제거됨)을 Docker로 직접 띄워 **양방향** 검증: (1) hg4j 클라이언트 → 실제 hg 6.0 서버로 capabilities/heads/known/listkeys/lookup/pushkey/branchmap 및 changesetdata+manifestdata+filesdata로부터 재구성한 전체 clone까지 노드 해시 일치 확인. (2) 실제 hg 6.0 클라이언트(`hg --config experimental.httppeer.advertise-v2=true clone`) → hg4j의 `HgWireServer`로 완전한 clone 성공 + `hg verify` 통과. 진짜 프레임 프로토콜(8바이트 헤더, capabilities 발견 핸드셰이크, 실제 명령 집합)을 처음부터 구현. **구조적 한계**: 이 프로토콜 자체가 실제 Mercurial에서 6.1부터 완전히 폐기됐다 — 즉 아무리 정확히 구현해도 현재 배포되는 실제 hg 서버 중 이 프로토콜을 쓰는 것은 사실상 없다(README의 "완전 준수" 요건 충족 목적으로는 의미 있으나 실사용 가치는 제한적). **연결 안 된 부분**: `HgRemoteClient`(v1)의 v1→v2 자동 업그레이드 감지 로직이 실제 v1 capabilities에는 존재하지도 않는 가짜 `"http-v2"` 플래그를 찾도록 되어 있어 절대 트리거되지 않음 — v2를 쓰려면 `HgRemoteClientV2`를 직접 생성해야 함. 백로그 참고. 상세: [[wireprotocol-v2-support-plan]] |
| Phases (draft/public/secret) | `hg help phases` | `PhaseRoots`, `api.PhaseCommand` | ✅ |
| Bookmarks (이동 가능한 포인터, named branch와 구별) | `hg help bookmarks`, `mercurial/bookmarks.py`(comparebookmarks/validdest 실측) | `api.BookmarkCommand`, `api.CommitCommand`, `api.UpdateCommand`, `api.FetchCommand` | ✅ **구현 완료(2026-09-01)** — commit 자동 전진/update 활성화·비활성화/pull·push 동기화 전부 구현, 실제 hg CLI로 fast-forward·진짜 divergence·원격 push/pull까지 검증(`BookmarkRealHgInteropTest`). 검증 중 데이터 손실 버그 2건 발견·수정: (1) pull 시 ancestor 관계를 안 따져서 로컬의 독자적 bookmark 이동이 조용히 덮어써지던 버그, (2) 새 changeset 없이 bookmark만 이동한 원격을 pull하면 동기화 자체가 생략되던 버그. 상세: [[bookmark-full-support-plan]] |
| Obsolescence markers | `mercurial/obsolete.py`(FM1 포맷 실측, 실제 obsstore 픽스처로 검증) | `HgObsolescenceParser`, `HgObsMarker`, `api.AmendCommand`/`RebaseCommand`/`GraftCommand`/`HisteditCommand`/`StripCommand` | ✅ **구현 완료(2026-09-01)** — 5개 명령 전부 마커 생성 확인. **완료 과정에서 obsstore 바이너리 포맷 자체가 완전히 틀렸던 것을 발견** — 파일 버전 바이트 부재, 필드 순서·크기 전부 불일치. 실제 FM1(version=1) 스펙대로 전면 재작성, 실제 hg가 만든 obsstore를 hg4j로 파싱 + hg4j가 쓴 obsstore를 실제 `hg debugobsolete`로 읽기 — 양방향 검증 통과(`HgObsolescenceRealHgInteropTest`). 상세: [[obsolescence-marker-completeness-plan]] |
| Censor (민감정보 삭제) | `hg help internals.censor` | 없음 | ❌ **미구현으로 추정** |
| Narrow clone / narrowspec | wiki 관련 문서 | `NarrowCloneCommand`, `HgTreeFilter` | ✅ (README에 명시) |
| Sparse checkout | `mercurial/sparse.py`(`parseconfig`/`patternsforrev` 실측) | `treewalk.SparseConfig`, `treewalk.SparsePathFilter` | ✅ **구현 완료(2026-09-01)** — `.hg/sparse` 파일 파싱(`[include]`/`[exclude]`/`%include` 프로파일 참조, 앞자리 `/` 거부, 섹션 밖 항목 에러 등 실제 hg의 검증 규칙까지 재현) 및 `%include`로 참조된 프로파일을 해당 리비전의 매니페스트에서 읽어 재귀적으로 병합하는 `patternsforrev` 로직 신규 구현. `.hg*` 자동 include 규칙 포함. 실제 hg CLI로 만든 `.hg/sparse` 픽스처와 대조 검증(`SparseConfigInteropTest`) |
| LFS (largefiles) | 관련 확장 문서 | `HgLfsManager`, `HgLfsPointer` | ✅ |
| Subrepositories (`.hgsub`/`.hgsubstate`) | wiki 관련 문서 | `HgSubrepoParser`, `HgSubrepoEntry`, `api.SubrepoCommand` | ✅ |
| Config 파일 포맷 (`hgrc`, include/`%include`, 섹션) | `hg help internals.config`, `mercurial/config.py`(`parse` 실측) | `HgRcConfig` | ✅ **구현 완료(2026-09-01)** — `%include <path>`(포함 파일의 디렉터리 기준 상대 경로 해석, 없는 파일은 조용히 무시), `%unset <key>`(현재 시점까지 설정된 값 완전 제거), 들여쓰기 연속 줄 지원을 실제 `mercurial/config.py` 소스대로 구현. 실제 `hg config` 명령 출력과 대조 검증(`HgRcConfigTest#testIncludeAndUnsetMatchRealHg`) |
| Merge state 영속화 (재개 가능한 머지) | `hg help internals.mergestate`, `mercurial/mergestate.py`(`_readrecordsv2`/`_writerecordsv2` 실측) | `merge.MergeState`(신규, `.hg/merge/state2`), `api.MergeCommand`, `api.ResolveCommand`(레거시 `.hg/merge/state`만) | ⚠️ **부분 완료(2026-09-01)** — 실제 hg의 `state2` 바이너리 포맷(타입 1바이트+길이 4바이트 프레임, `L`/`O`/`F` 레코드, 비허용 타입은 `t` 오버라이드로 래핑)을 그대로 구현한 `MergeState` 클래스 신규 작성. `MergeCommand`가 충돌 시 실제로 `state2`를 쓰도록 연결 완료 — 양방향 검증(hg4j가 실제 hg의 충돌 상태를 읽고, 실제 hg의 `resolve --list`가 hg4j가 쓴 상태를 읽음, `MergeStateInteropTest`). **연결 안 된 부분(백로그)**: `ResolveCommand`는 여전히 레거시 v1 `.hg/merge/state`만 읽고 쓴다 — 새로 만든 `MergeState`(state2)를 실제로 소비해서 파일별 resolve 마킹을 하도록 연결하는 작업이 남아 있음 |
| 트랜잭션 저널링 / 크래시 복구 (`recover`, `rollback`) | `hg help internals.transaction`(트랜잭션/저널 파일 포맷) | `api.CommitCommand`, `api.FetchCommand`, `api.RebaseCommand`, `api.RollbackCommand`, `lib.HgRepository.checkAndPerformAutoRollback()` | ✅ **구현 완료(2026-09-01)** — 크래시 자동복구(commit/fetch/pull/rebase/amend/graft/remove/rename/merge/strip 경로 커버)와 `RollbackCommand` 둘 다 실제 hg CLI로 검증(`RollbackRealHgInteropTest`). **완료 과정에서 발견한 실제 갭**: `FetchCommand`가 undo 정보를 안 남겨서 pull 직후에는 rollback이 전혀 동작하지 않았음(가장 흔한 실사용 시나리오) — 수정 완료. `histedit`는 아직 journal 미적용(Track C, 빈도 낮은 경로). 상세: [[journaling-crash-recovery-plan]] |
| 누락된 코어 포셀린 명령 (`forget`, `backout`, `addremove`, `verify`, `paths`, `summary`, `tip`, `root`, `parents`, `unbundle`) | `hg help <command>` 각각 | `api.ForgetCommand`, `api.BackoutCommand`, `api.AddremoveCommand`, `api.VerifyCommand`, `api.SummaryCommand`, `api.TipCommand`, `api.RootCommand`, `api.ParentsCommand`, `api.UnbundleCommand` (모두 신규) | ✅ **구현 완료(2026-09-01)** — 9개 명령 전부 신규 클래스+`Hg` 파사드 메서드로 추가, 각각 실제 hg CLI와 대조 검증(`TrackCMissingCommandsInteropTest`, `SummaryCommandInteropTest`). `VerifyCommand`는 기존에 Javadoc만 있고 filelog 검사가 실제로 빠져 있던 것도 이 참에 채움. `paths` 자체는 별도 명령 클래스 없이 `HgRcConfig.getPath()` 방식 유지하되, `PullCommand`/`PushCommand`가 인자 없을 때 `paths.default`/`paths.default-push`로 폴백하고 URL이 아닌 문자열은 `[paths]` 별칭으로 해석하도록 연결 완료(`hg pull upstream` 같은 이름 지정 pull도 동작) |
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

## 남은 백로그 (2026-09-01 확정, 우선순위는 사용자 확인 후 진행)

Track B(B-1~B-5)와 Track C의 나머지 항목이 이번 세션에 전부 실제 hg CLI/서버로 검증
완료됐다. 아래는 그 과정에서 새로 발견했거나, 환경 제약으로 계속 보류 중인 진짜 gap이다.

1. **`ResolveCommand`가 새 `MergeState`(state2)를 안 씀** — `MergeCommand`는 충돌 시
   실제 `.hg/merge/state2` 포맷으로 쓰도록 연결됐는데, 정작 그 상태를 읽어서 파일별로
   resolve 처리를 하는 `ResolveCommand`는 여전히 레거시 v1(`.hg/merge/state`)만
   다룬다. 배관의 한쪽 끝(쓰기)은 놨는데 반대쪽(읽기/조작)이 안 이어진 상태.
2. **`HgRemoteClient`의 v1→v2 자동 업그레이드 로직이 절대 트리거되지 않음** — 실제 v1
   capabilities 응답에는 존재하지도 않는 가짜 `"http-v2"`/`"api-v2"` 플래그를 찾도록
   되어 있다. v2를 쓰려면 사용자가 `HgRemoteClientV2`를 직접 생성해야 한다.
3. **최신(실제 배포되는) Mercurial 서버와의 라이브 통신 검증 미착수** — 이번 세션의
   wireprotocol v1 관련 수정(cg1/cg2/cg3, Bundle2Parser)은 전부 실제 hg가 만든
   "번들 파일"과의 정적 라운드트립으로 검증했다. `hg serve`로 띄운 실제 최신 Mercurial
   서버에 hg4j 클라이언트가 HTTP로 붙어 실시간 clone/pull/push를 하는 시나리오,
   그리고 실제 hg 클라이언트가 hg4j의 `HgWireServer`에 접속하는 시나리오는 v1으로는
   한 번도 실행해보지 않았다(wireprotocol v2는 Mercurial 6.0으로 이 검증을 완료했지만,
   실제 최신 서버는 v2를 지원하지 않으므로 이 항목은 v1 기준으로 별도로 필요).
4. **Revlog v2 일반(`exp-revlogv2.2`, 매니페스트/파일로그) + persistent-nodemap** —
   의도적으로 보류 중. 이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 포맷의
   저장소 자체를 생성하지 못해(`abort: accessing ... without associated fast
   implementation`) 검증 불가. Rust 포함 hg 확보 시 재개.
5. **Dirstate v2(44바이트 노드) 정확한 바이트 레이아웃 검증** — `DirstateV2Parser`/
   `DirstateV2Serializer`/`DirstateV2Node` 클래스는 존재하지만, 실제 hg가 만든
   dirstate-v2 픽스처와의 바이트 단위 대조 검증은 아직 착수하지 않았다.
6. **Censor(민감정보 삭제)** — 아예 미구현. 구현 범위/우선순위 사용자 확인 필요.
7. **cg3의 트리매니페스트/censor 지원 깊은 부분** — cg2/cg3 델타 헤더 구조 버그는
   고쳤지만, 트리매니페스트(`treemanifest`)나 censor 플래그가 실제로 요구하는 세부
   동작까지는 검증하지 않았다.
8. **`histedit`의 크래시 복구 journal 미적용** — commit/fetch/pull/rebase/amend/graft/
   remove/rename/merge/strip은 전부 journal이 적용됐지만 `histedit`만 빠져 있다
   (빈도 낮은 destructive 경로).

## 관련 페이지
- [[jgit-parity-requirement]] — 구조/네이밍 요건 (이 문서와는 독립적인 축)
- [[revlog]], [[dirstate]], [[bundle2-changegroup]], [[revset]] — 이미 조사된 스펙 영역 상세
- [[core]], [[transport]] — 관련 구현 클래스 위치
- [[sources]] — 향후 원문 스냅샷을 추가할 위치
