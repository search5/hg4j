---
updated: 2026-09-02
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
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ **구현 완료(2026-09-01)** — 실제 hg CLI(Docker 6.0 + host native 7.2.2)로 만든 진짜 dirstate-v2 저장소 바이트를 직접 캡처해 대조, **3건의 실제 버그 발견·수정**: (1) NODE 44바이트 구조체 필드 오프셋이 전부 지어낸 값(자기 자신과의 라운드트립만 우연히 통과, 실제 hg가 쓴 파일은 못 읽는 상태), (2) flags 비트 값 오류, (3) 데이터 파일명 패턴이 `dirstate.d.<uid>`가 아니라 실제로는 `dirstate.<uid>`였던 완전 단절 버그. 캡처한 실제 바이트를 그대로 박은 회귀(`DirstateV2RealFixtureTest`) 신설, 기존 `CHgDirstateV2Test`의 설정 순서/requires 누락 버그도 같이 수정 — 상세는 아래 백로그 5번 |
| Bundle1 (레거시 `HG10UN/GZ/BZ`) | wiki `BundleFormat` | `api.UnbundleCommand`, `api.FetchCommand` | ⚠️ **부분(2026-09-01 확인)** — 읽기(HG10UN/HG10GZ/HG10BZ 세 압축 형식 전부)는 실제 `hg bundle --type=none-v1/gzip-v1/bzip2-v1`로 만든 파일로 검증됨(`TrackCMissingCommandsInteropTest`). 다만 독립된 "Bundle1 writer" 클래스는 없음 — `HgLocalClient.getBundle()`이 내부적으로 HG10UN 스트림을 만들긴 하지만 이는 wire protocol 응답용이지 파일로 저장하는 `hg bundle --type=v1` 대응 명령은 없음. 백로그 참고 |
| Bundle2 컨테이너 | `hg help internals.bundle2` | `Bundle2Parser` | ✅ **버그 2건 발견·수정(2026-09-01)** — 스트림 파라미터 크기 필드가 2바이트가 아니라 실제로는 4바이트(`_fstreamparamsize='>i'`)였던 버그, 파트 헤더의 파라미터를 키/값 교차로 읽던 게 아니라 실제로는 모든 (keylen,vallen) 쌍을 먼저 읽고 그다음 키/값 바이트를 순서대로 읽는 2단계 구조였던 버그. 둘 다 실제 `hg bundle`(기본 bzip2 압축) 결과물로 발견 |
| Changegroup (cg1/cg2/cg3) | `hg help internals.changegroups`, `mercurial/changegroup.py` 실측 | `ChangegroupParser`, `storage.Revlog`, `transport.HgLocalClient`, `transport.HgRemoteClientV2` | ✅ **cg1/cg2/cg3 헤더 구조 버그 발견·수정 완료(2026-09-01)** — (1) cg1 델타 베이스 규칙: 실제 cg1은 `forcedeltaparentprev=True`로 각 엔트리를 "실제 DAG 부모(p1)"가 아니라 "스트림상 바로 직전 엔트리"를 기준으로 델타 인코딩한다(위치 기반, DAG와 무관). `HgLocalClient.getBundle()`이 p1 기준으로 델타를 만들고 있어서 다중 head(branch) 저장소를 pull하면 콘텐츠가 깨지는 실제 버그였음. (2) cg2/cg3 델타 헤더 필드 순서: 실제 구조체는 `node,p1,p2,deltabase,cs`인데 hg4j는 `node,p1,p2,cs,deltabase`로 읽어서 changelog 그룹에서 deltabase가 항상 자기 자신의 node와 같아지는 버그였음(`node`/`cs`가 changelog에서는 같은 값이라 증상이 그렇게 나타남) — 실제 `hg bundle` 결과물의 unbundle 실패로 발견. **cg3의 censor 지원은 ✅ 완료(2026-09-01, 백로그 7번)** — 패킹측 크래시(censored 리비전 포함 저장소를 pull/clone/push하면 무조건 죽던 버그)와 수신측 censored 플래그 소실(censor된 내용이 조용히 복원되는 심각한 버그) 둘 다 발견·수정. **트리매니페스트(treemanifest) 파싱은 여전히 ❌ 미구현으로 확정** — 관련 파싱 로직 자체가 없음, 별도 백로그 8번. **cg4/cg5는 ❌ 미구현으로 신규 확인(2026-09-02)** — hg4j는 `changegroup=01,02,03`만 하드코딩 광고해 협상에서 자체 배제됨, 실제 Mercurial 7.1+에 존재하는 포맷. 상세는 백로그 11번 |
| Wire protocol v1 (HTTP/SSH, capability 협상) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `transport.wireprotov1.Wire1Commands`, `HgHttpWireServer`, `HgSshWireServer` | ✅ **양방향 완전 검증 완료(2026-09-01)**. 클라이언트 방향: Docker 실제 Mercurial 6.0 `hg serve` HTTP 서버를 대상으로 hg4j `PullCommand`/`PushCommand`가 실시간 pull+push 왕복 성공(`HgHttpV1LiveServerInteropTest`). **서버 방향(실제 hg 클라이언트 → hg4j 서버)도 완료** — 기존 모놀리식 `HgWireServer`(가짜 SSH stdio 핸들러 포함, 실제 검증된 적 없음)를 JGit의 `UploadPack`/`ReceivePack`+전송별 glue 패턴대로 `Wire1Commands`(전송 무관 v1 코어)+`HgHttpWireServer`(HTTP)+`HgSshWireServer`(SSH, real hg SSH 라인 프로토콜 재구현)로 재구성 후 삭제, 실제 hg CLI를 클라이언트로 clone/pull/push/bookmark(HTTP, `HgHttpWireServerRealHgInteropTest`) 및 clone(SSH, 임베디드 Apache MINA SSHD 경유 진짜 SSH 세션, `HgSshWireServerRealHgInteropTest`)까지 전부 검증 — 이 과정에서 SSH 핸드셰이크의 `between` 커맨드가 빈 응답을 내던 진짜 버그(real hg 클라이언트가 영원히 멈춤)도 발견·수정 |
| Wire protocol v2 (실험적, cbor+프레임 기반) | `hg help internals.wireprotocolv2`, `mercurial/wireprotoframing.py`/`wireprotov2server.py`/`wireprotov2peer.py` 실측(Mercurial 6.0) | `transport.HgRemoteClientV2`, `transport.HgHttpWireServer`, `transport.wireprotov2.*`(`Wire2Frame`/`Wire2Transport`/`Wire2Commands`/`Cbor`) | ✅ **전면 재구현 완료(2026-09-01)** — 이전 구현은 사실상 전부 가짜였다(존재하지도 않는 `/api/<command>` 평면 HTTP+CBOR 스킴, `changegroup`/`getbundle`/`unbundle`이라는 v2에 없는 명령, 실제로는 모든 문자열이 CBOR byte-string인데 text-string으로 인코딩). Mercurial 6.0(v2 서버 코드가 남아있는 마지막 릴리스 — 6.1에서 완전히 제거됨)을 Docker로 직접 띄워 **양방향** 검증: (1) hg4j 클라이언트 → 실제 hg 6.0 서버로 capabilities/heads/known/listkeys/lookup/pushkey/branchmap 및 changesetdata+manifestdata+filesdata로부터 재구성한 전체 clone까지 노드 해시 일치 확인. (2) 실제 hg 6.0 클라이언트(`hg --config experimental.httppeer.advertise-v2=true clone`) → hg4j의 서버(현재 `HgHttpWireServer`, JGit식 재구성으로 옛 `HgWireServer`에서 이관됨)로 완전한 clone 성공 + `hg verify` 통과. 진짜 프레임 프로토콜(8바이트 헤더, capabilities 발견 핸드셰이크, 실제 명령 집합)을 처음부터 구현. **구조적 한계**: 이 프로토콜 자체가 실제 Mercurial에서 6.1부터 완전히 폐기됐다 — 즉 아무리 정확히 구현해도 현재 배포되는 실제 hg 서버 중 이 프로토콜을 쓰는 것은 사실상 없다(README의 "완전 준수" 요건 충족 목적으로는 의미 있으나 실사용 가치는 제한적). **v1→v2 자동 업그레이드는 ✅ 완료(2026-09-01, 백로그 2번)** — 가짜 `"http-v2"` 플래그 매칭을 제거하고 실제 `X-HgUpgrade-1`/`X-HgProto-1` 핸드셰이크로 교체, CBOR discovery 응답이면 `HgRemoteClientV2`로 자동 위임·아니면 평문 v1 폴백. **추가로 발견·수정한 버그(JGit 재구성 세션, 이후)**: 이 자동 업그레이드 시에도 discovery 응답에 실려오는 `v1capabilities` 필드(`clonebundles` 같은 v2에 없는 v1 전용 토큰)를 클라이언트가 실제로는 파싱하지 않고 버리고 있었음 — 파싱하도록 수정. 상세: [[wireprotocol-v2-support-plan]] |
| Phases (draft/public/secret) | `hg help phases` | `PhaseRoots`, `api.PhaseCommand` | ✅ |
| Bookmarks (이동 가능한 포인터, named branch와 구별) | `hg help bookmarks`, `mercurial/bookmarks.py`(comparebookmarks/validdest 실측) | `api.BookmarkCommand`, `api.CommitCommand`, `api.UpdateCommand`, `api.FetchCommand` | ✅ **구현 완료(2026-09-01)** — commit 자동 전진/update 활성화·비활성화/pull·push 동기화 전부 구현, 실제 hg CLI로 fast-forward·진짜 divergence·원격 push/pull까지 검증(`BookmarkRealHgInteropTest`). 검증 중 데이터 손실 버그 2건 발견·수정: (1) pull 시 ancestor 관계를 안 따져서 로컬의 독자적 bookmark 이동이 조용히 덮어써지던 버그, (2) 새 changeset 없이 bookmark만 이동한 원격을 pull하면 동기화 자체가 생략되던 버그. 상세: [[bookmark-full-support-plan]] |
| Obsolescence markers | `mercurial/obsolete.py`(FM1 포맷 실측, 실제 obsstore 픽스처로 검증) | `HgObsolescenceParser`, `HgObsMarker`, `api.AmendCommand`/`RebaseCommand`/`GraftCommand`/`HisteditCommand`/`StripCommand` | ✅ **구현 완료(2026-09-01)** — 5개 명령 전부 마커 생성 확인. **완료 과정에서 obsstore 바이너리 포맷 자체가 완전히 틀렸던 것을 발견** — 파일 버전 바이트 부재, 필드 순서·크기 전부 불일치. 실제 FM1(version=1) 스펙대로 전면 재작성, 실제 hg가 만든 obsstore를 hg4j로 파싱 + hg4j가 쓴 obsstore를 실제 `hg debugobsolete`로 읽기 — 양방향 검증 통과(`HgObsolescenceRealHgInteropTest`). 상세: [[obsolescence-marker-completeness-plan]] |
| Censor (민감정보 삭제) | `hg help internals.censor` | `Revlog.censorRevision`/`isCensoredText`, `api.CensorCommand` | ✅ **구현 완료(2026-09-01)** — 실제 hg의 `v1_censor` 방식대로 대상 리비전을 tombstone 콘텐츠+`REVIDX_ISCENSORED` 플래그로 재작성, 포셀린 `CensorCommand` 신설, 읽기 시 `HgCensoredContentException`. changegroup 전송 경로(cg3)의 censor 지원도 완료 — 패킹측 크래시와 수신측 플래그 소실 버그 2건 발견·수정(위 Changegroup 행 및 아래 백로그 6/7번 참고). Docker Mercurial 6.0의 실제 censor 확장 산출물과 바이트 단위 대조 + 실제 hg 양방향 interop 검증(`CensorRealHgInteropTest`, `CensorChangegroupTransferTest`) |
| Narrow clone / narrowspec | wiki 관련 문서 | `NarrowCloneCommand`, `HgTreeFilter` | ✅ (README에 명시) |
| Sparse checkout | `mercurial/sparse.py`(`parseconfig`/`patternsforrev` 실측) | `treewalk.SparseConfig`, `treewalk.SparsePathFilter` | ✅ **구현 완료(2026-09-01)** — `.hg/sparse` 파일 파싱(`[include]`/`[exclude]`/`%include` 프로파일 참조, 앞자리 `/` 거부, 섹션 밖 항목 에러 등 실제 hg의 검증 규칙까지 재현) 및 `%include`로 참조된 프로파일을 해당 리비전의 매니페스트에서 읽어 재귀적으로 병합하는 `patternsforrev` 로직 신규 구현. `.hg*` 자동 include 규칙 포함. 실제 hg CLI로 만든 `.hg/sparse` 픽스처와 대조 검증(`SparseConfigInteropTest`) |
| LFS (largefiles) | 관련 확장 문서 | `HgLfsManager`, `HgLfsPointer` | ✅ |
| Subrepositories (`.hgsub`/`.hgsubstate`) | wiki 관련 문서 | `HgSubrepoParser`, `HgSubrepoEntry`, `api.SubrepoCommand` | ✅ |
| Config 파일 포맷 (`hgrc`, include/`%include`, 섹션) | `hg help internals.config`, `mercurial/config.py`(`parse` 실측) | `HgRcConfig` | ✅ **구현 완료(2026-09-01)** — `%include <path>`(포함 파일의 디렉터리 기준 상대 경로 해석, 없는 파일은 조용히 무시), `%unset <key>`(현재 시점까지 설정된 값 완전 제거), 들여쓰기 연속 줄 지원을 실제 `mercurial/config.py` 소스대로 구현. 실제 `hg config` 명령 출력과 대조 검증(`HgRcConfigTest#testIncludeAndUnsetMatchRealHg`) |
| Merge state 영속화 (재개 가능한 머지) | `hg help internals.mergestate`, `mercurial/mergestate.py`(`_readrecordsv2`/`_writerecordsv2` 실측) | `merge.MergeState`(`.hg/merge/state2`), `api.MergeCommand`, `api.ResolveCommand` | ✅ **완료(2026-09-01)** — 실제 hg의 `state2` 바이너리 포맷(타입 1바이트+길이 4바이트 프레임, `L`/`O`/`F` 레코드, 비허용 타입은 `t` 오버라이드로 래핑)을 그대로 구현한 `MergeState` 클래스, `MergeCommand`가 충돌 시 실제로 `state2`를 쓰도록 연결 — 양방향 검증(hg4j가 실제 hg의 충돌 상태를 읽고, 실제 hg의 `resolve --list`가 hg4j가 쓴 상태를 읽음, `MergeStateInteropTest`). `ResolveCommand`도 레거시 v1에서 `MergeState`(state2) 기반으로 전면 재작성해 list/markResolved/markUnresolved를 실제 hg와 양방향 interop까지 검증 완료(백로그 1번) |
| 트랜잭션 저널링 / 크래시 복구 (`recover`, `rollback`) | `hg help internals.transaction`(트랜잭션/저널 파일 포맷) | `api.CommitCommand`, `api.FetchCommand`, `api.RebaseCommand`, `api.RollbackCommand`, `api.HisteditCommand`, `lib.HgRepository.checkAndPerformAutoRollback()` | ✅ **구현 완료(2026-09-01)** — 크래시 자동복구(commit/fetch/pull/rebase/amend/graft/remove/rename/merge/strip/**histedit** 경로 전부 커버)와 `RollbackCommand` 둘 다 실제 hg CLI로 검증(`RollbackRealHgInteropTest`). **완료 과정에서 발견한 실제 갭**: `FetchCommand`가 undo 정보를 안 남겨서 pull 직후에는 rollback이 전혀 동작하지 않았음(가장 흔한 실사용 시나리오) — 수정 완료. `histedit`도 journal 미적용이었으나 TDD로 수정 완료. 상세: [[journaling-crash-recovery-plan]] |
| 누락된 코어 포셀린 명령 (`forget`, `backout`, `addremove`, `verify`, `paths`, `summary`, `tip`, `root`, `parents`, `unbundle`) | `hg help <command>` 각각 | `api.ForgetCommand`, `api.BackoutCommand`, `api.AddremoveCommand`, `api.VerifyCommand`, `api.SummaryCommand`, `api.TipCommand`, `api.RootCommand`, `api.ParentsCommand`, `api.UnbundleCommand` (모두 신규) | ✅ **구현 완료(2026-09-01)** — 9개 명령 전부 신규 클래스+`Hg` 파사드 메서드로 추가, 각각 실제 hg CLI와 대조 검증(`TrackCMissingCommandsInteropTest`, `SummaryCommandInteropTest`). `VerifyCommand`는 기존에 Javadoc만 있고 filelog 검사가 실제로 빠져 있던 것도 이 참에 채움. `paths` 자체는 별도 명령 클래스 없이 `HgRcConfig.getPath()` 방식 유지하되, `PullCommand`/`PushCommand`가 인자 없을 때 `paths.default`/`paths.default-push`로 폴백하고 URL이 아닌 문자열은 `[paths]` 별칭으로 해석하도록 연결 완료(`hg pull upstream` 같은 이름 지정 pull도 동작). **주의**: 이 행은 2026-09-01 당시 확인된 9개 한정이다 — 2026-09-02 `Hg` 파사드 전수 재대조에서 별개의 새 갭(파사드 미배선 4건 + 대응 클래스 자체가 없는 명령 다수)이 추가로 발견됐다 — 백로그 12번 참고 |
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

1. ~~**`ResolveCommand`가 새 `MergeState`(state2)를 안 씀**~~ — ✅ **완료(2026-09-01)**.
   `ResolveCommand`를 `.hg/merge/state2`(`MergeState`) 기반으로 전면 재작성해
   `MergeCommand`가 남긴 실제 충돌 상태를 list/markResolved/markUnresolved로 조작할 수
   있도록 배관을 이었다. `MergeState`에 `markUnresolved`/`hasFile`/`isActive` 신설.
   TDD 6건 전부 GREEN(실제 hg CLI 대조 interop 테스트 포함).
2. ~~**`HgRemoteClient`의 v1→v2 자동 업그레이드 로직이 절대 트리거되지 않음**~~ — ✅
   **완료(2026-09-01)**. `?cmd=capabilities` 요청에 실제 `X-HgUpgrade-1`/`X-HgProto-1`
   핸드셰이크 헤더를 실어 보내고, 응답이 CBOR `{apibase, apis: {<namespace>: {...}}}`로
   디코딩되면(실제 v2 서버) `HgRemoteClientV2`로 자동 위임, 디코딩 실패/미해당이면
   (실제 v1 전용 서버는 알 수 없는 헤더를 그냥 무시하고 평문을 그대로 반환) 같은 응답
   바이트를 기존 평문 v1 파싱 경로로 폴백 — 요청 한 번으로 양쪽 다 처리한다. 가짜
   `"http-v2"` 토큰 매칭 로직은 제거. TDD 테스트 2건(GREEN, 로컬 `HttpServer` +
   `HgWireServer.handleCapabilitiesDiscovery`로 실제 핸드셰이크/폴백 양쪽 재현).
3. ~~최신 Mercurial 서버와의 라이브 통신 검증 미착수~~ — ✅ **완료(2026-09-01)**.
   Docker로 실제 Mercurial 6.0(`hg serve`, HTTP)을 띄우고
   `transport.HgHttpV1LiveServerInteropTest`(`@Tag("interop")`)로 hg4j
   `PullCommand`/`PushCommand`가 실시간으로 pull(2커밋 수신) + push(신규 커밋 전송 →
   별도 fresh pull로 서버에 실제 반영됐는지 재확인)까지 왕복 검증했다. 실제 hg
   클라이언트 → hg4j `HgWireServer` 방향(v1)은 여전히 미검증으로 남음.
4. **Revlog v2 일반(`exp-revlogv2.2`, 매니페스트/파일로그) + persistent-nodemap** —
   의도적으로 보류 중. 이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 포맷의
   저장소 자체를 생성하지 못해(`abort: accessing ... without associated fast
   implementation`) 검증 불가. Rust 포함 hg 확보 시 재개.
5. ~~**Dirstate v2(44바이트 노드) 정확한 바이트 레이아웃 검증**~~ — ✅ **완료(2026-09-01)**,
   그리고 실제로 검증해보니 **3가지 진짜 버그**가 나왔다(실제 hg와 전혀 상호운용
   불가능한 수준):
   1. `DirstateV2Node`의 44바이트 NODE 구조체 필드 오프셋이 전부 틀림 — 실제 spec은
      `mercurial/dirstateutils/v2.py`의 `NODE = struct.Struct('>LHHLHLLLLHlll')`
      (path_start@0, path_len@4, basename_start@6, copy_source_start@8,
      copy_source_len@12, children_start@14, children_count@18,
      descendants_with_entry@22, tracked_descendants@26, flags@30, size@32,
      mtime_s@36, mtime_ns@40)인데, hg4j는 완전히 다른(한 번도 검증 안 된, 지어낸)
      오프셋을 쓰고 있었다 — hg4j 자기 자신과의 라운드트립만 우연히 통과할 뿐 실제 hg가
      만든 파일은 전혀 못 읽는 상태였다.
   2. flags 비트 값도 틀림 — 실제(`mercurial/pure/parsers.py`)는
      `HAS_MODE_AND_SIZE=1<<10`, `HAS_MTIME=1<<11`, `MODE_EXEC_PERM=1<<3`,
      `MODE_IS_SYMLINK=1<<4`인데 hg4j는 각각 `1<<3`/`1<<4`/`1<<5`/`1<<6`을 쓰고 있었다.
   3. **데이터 파일명 패턴이 `dirstate.d.<uid>`였는데 실제는 `dirstate.<uid>`**(".d"가
      끼어들 자리가 없음, `docket.py`의 `data_filename_pattern = b'dirstate.%s'`로 확인)
      — 이 하나만으로도 hg4j가 쓴 파일을 실제 hg가 못 찾고, 실제 hg가 쓴 파일을 hg4j가
      못 찾는 완전 단절 상태였다.

   검증 방법: Docker Mercurial 6.0(및 호스트 native hg 7.2.2 둘 다) +
   `--config format.exp-rc-dirstate-v2=1`(6.0) / `format.use-dirstate-v2=true`(7.2.2)
   `--config storage.dirstate-v2.slow-path=allow`(Rust 확장 없이 pure-Python 경로 강제
   허용)로 실제 dirstate-v2 저장소를 만들어 `.hg/dirstate`+`.hg/dirstate.<uid>`를 직접
   캡처, 바이트 단위로 역산해 위 3건을 확정. 캡처한 실제 바이트를 그대로 박아넣은
   회귀 테스트(`DirstateV2RealFixtureTest`) 신설. 기존 interop 테스트
   (`CHgDirstateV2Test`)도 `hg init` **이후에** hgrc로 `use-dirstate-v2`를 설정하던
   순서 버그(설정이 너무 늦게 적용돼 항상 v1로 초기화됨) + `slow-path=allow` 누락으로
   requires 파일에 `dirstate-v2`가 없어 매번 조용히 skip되던 것을 발견·수정 — 실제로
   한 번도 통과된 적 없던 인터롭 테스트가 이제 실제로 실행되고 통과한다.
6. ~~**Censor(민감정보 삭제)** — 아예 미구현.~~ — ✅ **완료(2026-09-01)**.
   `Revlog.censorRevision(int rev, byte[] tombstone)` 신설: 대상 리비전을 실제 hg의
   `mercurial/revlogutils/rewrite.py`(`v1_censor`) 방식대로 통째로 재작성한다 — 대상
   payload를 tombstone(`storageutil.packmeta({censored: msg}, '')`과 동일한
   `"\x01\ncensored: <msg>\n\x01\n"` 포맷)으로 교체하고 `REVIDX_ISCENSORED`(실측
   `0x8000`) 플래그를 설정, 노드ID·부모·linkrev는 원본 그대로 보존해 히스토리/DAG
   구조는 전혀 건드리지 않는다(다른 리비전도 전부 재작성하지만 전부 풀텍스트로만
   쓴다는 점만 실제 hg와 다름 — 저장 효율 차이일 뿐 상호운용성엔 무관). 포셀린
   `CensorCommand`(api) 신설, 읽기 시 실제 hg처럼 `HgCensoredContentException`을 던지도록
   `Revlog.getRevisionContent()`에 검사 추가. Docker Mercurial 6.0의 실제 `censor`
   확장으로 만든 진짜 바이트(인덱스 레코드 64바이트 전체, tombstone payload)를 직접
   hexdump로 대조해 포맷을 확정했고, 실제 hg 양방향 interop 테스트로 검증
   (`CensorRealHgInteropTest`): hg4j가 censor한 리비전을 real hg `cat`이 거부
   (`abort: censored node`)하고 `hg verify`가 `censored file data`로 인식, 반대로 real
   hg가 censor한 리비전을 hg4j가 올바르게 인식·차단.
7. **cg3의 censor 지원** — ✅ **완료(2026-09-01)**. censor 구현(항목 6) 직후 실제로
   검증해보니 changegroup 경유 전송 경로 두 곳에서 진짜 버그가 나왔다:
   1. **패킹 시 크래시**: `HgLocalClient.getBundle()`/`PushCommand`의 push용 번들 조립이
      파일로그 콘텐츠를 `getRevisionContent()`(항목 6에서 censored 리비전에 대해
      예외를 던지도록 바꾼 그 메서드)로 읽고 있어서, censored 리비전이 하나라도
      포함된 저장소를 pull/clone/push하면 무조건 크래시했다. `getRawRevisionContent()`
      (원본 저장 바이트 그대로, 실제 hg의 `rawdata()`/`_chunk()`와 동일한 접근)로 수정.
   2. **수신 측에서 censored 플래그가 조용히 사라짐**: hg4j의 changegroup은 cg1/cg2
      형태만 실제로 만들어 보내는데(cg3의 명시적 flags 필드가 없음), 수신측
      `Revlog.appendChangeGroupEntry()`는 flags를 항상 0으로 하드코딩하고 있었다 —
      즉 censor된 파일을 pull하면 수신 측에는 **censored 표시가 없는 평범한 리비전으로
      들어와, 원래 지워졌어야 할 내용이 그대로 복원**되는 심각한 버그였다. 실제 hg
      자신도 정확히 같은 문제를 안고 있어 `revlog.py`의 `_peek_iscensored()`로 전송된
      콘텐츠 안에서 censor tombstone 마커(`\x01\ncensored: ...`)를 스니핑해 플래그를
      복원하는 방식을 쓴다 — 이 메커니즘을 `Revlog.isCensoredText()`로 그대로 재현해
      수신측에 적용. 덤으로 censored 리비전에 대해서는 원격 노드 해시 무결성 검증도
      건너뛰도록 수정해야 했다(censor는 정의상 parents+content로 원래 해시를 재현할
      수 없으므로 — 원본 해시는 censor 시점 이전에 이미 검증된 값을 그대로 보존).
   실제 hg4j↔hg4j pull round-trip으로 TDD 검증(`CensorChangegroupTransferTest`):
   censored 리비전이 크래시 없이 전송되고, 수신측에서도 여전히 censored로 인식되며
   내용 접근 시 예외가 발생함을 확인.
8. **트리매니페스트(`treemanifest`) 읽기 지원** — 조사 결과 **미구현으로 확정**(단순
   "미검증"이 아니라 관련 파싱 로직 자체가 없음). cg3의 `ManifestGroup`(디렉터리별
   중첩 매니페스트 그룹) 파싱/적용 골격은 `ChangegroupParser`/`FetchCommand`에 이미
   있지만, 매니페스트 리비전 콘텐츠 자체를 파싱하는 어느 코드에도 treemanifest의
   `t`(subdirectory-pointer) 플래그를 인식하는 로직이 없다 — `hg init
   --config experimental.treemanifest=1`로 실제 저장소를 만들어(`.hg/store/meta/<dir>/
   00manifest.i`로 디렉터리별 매니페스트 revlog가 생성됨을 실제로 확인) 검증해보니,
   hg4j의 매니페스트 파싱은 완전히 flat(평면) 구조만 가정한다. 트리매니페스트를 쓰는
   저장소를 열면 디렉터리 항목을 일반 파일처럼 잘못 해석해 `LogCommand`/`StatusCommand`
   /체크아웃 등 매니페스트를 쓰는 모든 명령이 조용히 잘못된 결과를 낼 위험이 크다.
   구현 범위가 크다(재귀적 디렉터리 매니페스트 해석 + 이를 소비하는 모든 명령 배선) —
   이번 세션 범위 밖으로 남기고 별도 백로그 항목으로 분리.
   - **(2026-09-02 추가 확인)** 같은 미구현의 구체적 증상 하나를 커버리지 작업 중
     `HgRemoteClientV2`에서 발견: `getBundle()`이 wireprotocol v2 서버에 항상
     `"tree": ""`(루트 매니페스트)만 요청하고 서브디렉터리별 `tree=<dir>` 재귀 fetch를
     전혀 하지 않는다. `"tree": ""` 자체는 정상 요청이지만(hg4j 자체 저장소는 전부
     flat 매니페스트라 문제 없음), 진짜 treemanifest를 쓰는 제3자 real hg 서버와
     연동한다면 서브디렉터리 매니페스트/파일 데이터가 통째로 누락된다. 수정하려면
     재귀적 `tree=<dir>` fetch로 cg3 `ManifestGroup`을 조립해야 하는데, 이는 위
     항목 자체의 구현 범위와 동일해 별도 항목으로 분리하지 않고 여기 종속시킨다.
     wireprotocol v2는 hg 6.1부터 제거돼 실질 노출면은 좁다.
9. ~~**Clonebundles (대용량 클론 오프로딩) — 아예 미구현.**~~ — ✅ **완료(2026-09-01)**.
   클라이언트(발견·매니페스트 파싱·다운로드·적용·`FetchCommand`/`CloneCommand` 자동
   배선)와 **서버 측(`Wire1Commands`의 조건부 capability 광고 + `?cmd=clonebundles`
   핸들러)까지 전부 구현·검증 완료** — 처음엔 서버 측(8~9번)을 사용자 요청으로 보류
   했었으나, "JGit식 재구성" 작업(`HgHttpWireServer`/`HgSshWireServer` 신설) 중 같은
   세션에서 마저 구현됐다(`HgHttpWireServerTest#serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists`
   로 확인: `.hg/clonebundles.manifest` 파일이 없으면 capability 미광고, 생기면 광고 +
   내용 그대로 서빙). 상세 계획과 경위는 아래 "Clonebundles 실행 계획" 절 참고.
10. **깨진(dangling) symlink가 `AddCommand`/`HgRepository`에서 조용히 누락·거부됨**
    (2026-09-02, 커버리지 95% 이니셔티브 라운드2 중 `UpdateCommand` 담당 에이전트가
    부수적으로 발견, 미착수). `HgRepository.scanDirectory()`와 `AddCommand.call()`의
    명시적 경로 처리 둘 다 `File.isFile()`/`.exists()`로 파일 존재를 판단하는데, 이
    메서드들은 심볼릭 링크를 **따라가서** 판단하므로 타겟이 존재하지 않는 (깨진)
    symlink는 `false`를 반환한다 — 결과적으로 전체 저장소 `hg add`(스캔)는 깨진
    symlink를 조용히 건너뛰고, 특정 경로를 지정한 `hg add <path>`는 아예 거부한다.
    실제 hg 7.2로 확인: `ln -s missing-target.txt link.txt; hg add` → `A link.txt`
    (정상 추가, 이후 커밋/업데이트해도 타겟 경로가 그대로 보존됨). 수정 범위는 작을
    것으로 예상(두 체크 지점에 `Files.isSymbolicLink()` 분기 추가 정도) — 아직
    미착수.
11. **Changegroup cg4/cg5 미지원** (2026-09-02, 사용자 제보 후 호스트 native hg 7.2.2
    소스로 직접 대조 확인 — 2016년 논의만 됐다가 폐기된 옛 아이디어가 아니라, 실제로
    Mercurial 7.1(2025-08-04)에서 정식 채택된 최신 포맷). **미착수.**

    **실제 스펙 (`/opt/homebrew/lib/python3.14/site-packages/mercurial/changegroup.py`,
    Mercurial 7.2.2 실측)**:
    - `_packermap`에 `b'01'`~`b'05'` 다섯 버전이 등록돼 있다. 주석 원문: `# cg4 adds
      support for exchanging more advances flags`, `# ch5 adds support for
      exchanging sidedata`.
    - **cg4**(`ChangeGroupPacker04`/`cg4unpacker`, `version = b'04'`) 델타 헤더는
      `_CHANGEGROUPV4_DELTA_HEADER = struct.Struct(b">20s20s20s20s20sHbIBB20sb")`
      (node/p1/p2/deltabase/cs 각 20바이트 + flags 2바이트 + snapshot_level 1바이트
      signed + raw_size 4바이트 + encoded_comp 1바이트 + protocol_flags 1바이트 +
      storage_delta_base 20바이트 + storage_snapshot_level 1바이트signed = 총
      130바이트) — cg3까지 없던 **snapshot level**(sparse-revlog 델타 체인의 스냅샷
      깊이)과 **압축 방식(encoded_comp)**, **저장소측 델타베이스/스냅샷레벨**까지
      델타 단위로 실어 나른다. hg4j가 이미 검증한 `REVIDX_ISCENSORED`처럼, cg4는
      `revlog_constants.REVIDX_DELTA_INFO_FLAGS`(그 안에 `REVIDX_HASMETA` 포함)
      비트를 헤더 `flags` 필드에서 분리해 별도 필드로 명시적으로 전송한다.
    - **cg5**(`ChangeGroupPacker05`/`cg5unpacker`, `version = b'05'`) — sidedata(부가
      메타데이터) 교환 지원 추가. 헤더는
      `_CHANGEGROUPV5_DELTA_HEADER = struct.Struct(b">B20s20s20s20s20sH")`
      (protocol_flags 1바이트 + node/p1/p2/deltabase/cs 각 20바이트 + flags
      2바이트 = 103바이트).
    - **협상 로직**(`exchange.py`의 `_pushb2ctxaddchangesetspart`/
      `_getbundlechangegrouppart`, 둘 다 동일 패턴 실측): 원격이 bundle2 capability
      `changegroup=<v1>,<v2>,...`로 자신이 받을 수 있는 버전 목록을 광고하면, 로컬은
      그중 `changegroup.supportedoutgoingversions(repo)`(자신이 만들 수 있는 버전)와
      겹치는 것만 추려 **`version = max(cgversions)`로 가장 높은 공통 버전을 그대로
      선택**한다(사용자가 언급한 "highest changegroup format supported by both
      side"와 정확히 일치, 별도 우선순위 설정 없이 단순 숫자 최댓값).
    - **실사용 위험도는 조건부**: `changegroup.supportedincomingversions()`가 cg4를
      기본적으로 걸러낸다 — `scmutil.use_delta_info(repo)`(저장소에
      `delta-info-revlog` requirement가 있을 때만 true) 또는
      `experimental.changegroup4` 설정이 명시적으로 켜져 있을 때만 수신측이 cg4를
      광고한다. **실측 확인**: 호스트 native hg 7.2.2로 `hg init`한 기본 저장소의
      `.hg/store/requires`는 `dotencode/fncache/generaldelta/
      revlog-compression-zstd/revlogv1/sparserevlog/store`뿐 —
      **`delta-info-revlog`가 기본 포맷에 없다**. 즉 지금 당장 기본 설정의 real hg
      서버/클라이언트와 주고받을 때는 cg3로 자동 폴백되어 깨지지 않는다(사용자
      분석대로). cg5도 `experimental.changegroup5` 또는 revlogv2/changelogv2
      requirement가 있어야 광고된다 — 둘 다 이 문서의 항목 4(Revlog v2 일반)처럼
      아직 이 개발 환경에서 만들 수조차 없는 포맷과 연결돼 있어 당장은 cg4보다도
      더 먼 얘기.
    - **hg4j 쪽 실제 상태**: `HgSshClient`/`HgRemoteClient`/`FetchCommand` 세 곳
      모두 `bundlecaps`에 `"changegroup=01,02,03"`을 하드코딩 광고 중 — cg4/cg5를
      지원하는 상대와 통신해도 협상 자체에서 hg4j가 최댓값 후보에서 스스로 배제된다
      (수신 능력이 없으니 당연하지만, "최신 hg와 최적 포맷으로 못 주고받는다"는
      사용자 지적이 정확함). `ChangegroupParser`/`Revlog.appendChangeGroupEntry` 등
      실제 파싱/적용 로직에도 cg4/cg5 헤더 포맷에 대응하는 코드가 전혀 없다.
    - **결론**: README의 "SCM v7.2.2 기준 완전 준수" 주장 범위 안에 드는 진짜 gap.
      다만 위 실측대로 기본 포맷 저장소끼리는 즉시 깨지는 문제가 아니므로, cg3까지의
      상호운용성(이미 검증 완료)을 훼손하지 않는 별도 opt-in 확장으로 다뤄야 한다 —
      우선순위는 사용자 확인 후 진행.
12. **포셀린 명령 노출이 완전하지 않음** (2026-09-02, 사용자 질문 "포셀린 기능은 모두
    노출 끝?"에 답하며 `hg debugcommands`(real hg 7.2.2, debug*/admin* 제외 145개 중
    핵심 포셀린)와 `Hg` 파사드 메서드 목록을 직접 전수 대조해 발견). **미착수.** 두
    갈래 문제가 섞여 있다:

    **(a) 클래스는 있는데 `Hg` 파사드에 안 걸려 있음** — 다른 모든 명령은 예외 없이
    `Hg.xxx()` 형태로 노출되는데 아래는 그 관례에서 벗어나 있다:
    - `BranchesCommand`(이번 세션 신설), `ClonebundlesCommand`, `TreeMergeCommand`,
      `CensorCommand` — `new XxxCommand(repository)`로 개별 생성은 가능하지만
      `Hg` 파사드 메서드가 없다.
    - `RollbackCommand`는 `Hg.java` 내부(271번 줄 부근)에서 다른 명령의 크래시
      복구 로직에 종속적으로만 호출되고, `Hg.rollback()`처럼 사용자가 명시적으로
      부를 수 있는 파사드 메서드가 없다.

    **(b) 대응 클래스 자체가 아예 없음** — real hg 핵심 포셀린 명령 중:
    - `hg tags`(전체 태그 **목록 조회**) — `TagCommand`는 태그 **생성**만 하고
      `.hgtags`를 읽어 목록을 돌려주는 조회 기능이 없다(`getTags`/`listTags`류
      코드 전체 검색 결과 0건).
    - `hg copy` — 기존 `RenameCommand.call()`은 `Files.move`로 원본을 지운다.
      real hg의 `copy`는 원본을 남긴 채 새 추적 사본만 만드는(dirstate copy
      metadata만 새로 등록) 별개 동작인데 대응 코드가 없다.
    - `hg files`(패턴에 매칭되는 추적 파일 목록), `hg locate`(작업사본에서 파일
      검색), `hg manifest`(특정 리비전의 매니페스트 직접 조회 — `ManifestWalk`
      내부 클래스는 있지만 포셀린 진입점이 없음), `hg bundle`(현재 저장소를
      번들 파일로 저장 — `UnbundleCommand`의 정반대 방향, 위 gap table의 "Bundle1"
      행에서 이미 "독립된 Bundle1 writer 클래스는 없음"으로 지적된 것과 같은
      맥락), `hg recover`(중단된 트랜잭션에서 명시적으로 복구를 트리거하는 단독
      명령 — `HgRepository.checkAndPerformAutoRollback()`으로 다음 작업 시작 시
      자동으로는 되지만 사용자가 직접 호출할 방법이 없음), `hg paths`(등록된
      경로 별칭 **목록**을 사용자에게 보여주는 조회 명령 — `HgRcConfig.getPath()`
      로 내부 소비만 될 뿐 조회 결과를 노출하는 API가 없음) — 전부 대응 클래스가
      없다.

    수정 범위는 (a)가 작고(파사드 메서드 5개 추가), (b)는 명령마다 편차가 있다
    (`tags`/`paths`는 이미 있는 데이터를 읽어 반환하기만 하면 되는 조회성이라
    작고, `copy`/`bundle`/`locate`/`files`/`manifest`는 신규 로직이 필요해 상대적으로
    크다). 우선순위는 사용자 확인 후 진행.

## 완료된 항목 (번호 재사용, 위 목록과 별개로 시간순 기록)
- ~~**`histedit`의 크래시 복구 journal 미적용**~~ — ✅ **완료(2026-09-01)**.
   `StripCommand`/`CommitCommand`와 동일한 journal + dirstate.backup 스냅샷/롤백
   패턴을 `HisteditCommand`에 이식. 규칙 처리 도중 실패(예: 존재하지 않는 노드로 된
   뒷 규칙)해도 이미 부분적으로 재작성된 커밋/변경된 changelog·manifest·filelog가
   전부 원래 크기로 truncate되고 dirstate도 복원되도록 TDD로 확인
   (`histeditRollsBackAllProgressWhenALaterRuleFails`).

## Clonebundles 실행 계획 (2026-09-01, 신규 백로그)

> 사용자 지시: "'Clonebundles(클론 번들) 기반의 대용량 오프로딩은 Wire Protocol(v1/v2)의
> 처리 과부하를 피하기 위해 와이어 프로토콜 자체를 우회(Bypass)하는 방식'도 llm-wiki
> 백로그에 추가하고 TDD로 작업 진행."

### 실제 스펙 (Mercurial 6.0 소스 직접 확인, `mercurial/wireprotov1server.py`/
`wireprotov1peer.py`/`bundlecaches.py`/`exchange.py`)

1. **발견(discovery)**: 서버가 v1 `capabilities` 응답에 `"clonebundles"` 토큰을
   포함시켜 지원 여부를 광고한다(항목 3에서 이미 다룬 v1→v2 업그레이드 핸드셰이크와는
   무관한, 별도의 단순 capability 토큰). **중요한 정정(Docker Mercurial 6.0/7.2.2
   실측)**: 이 토큰은 core 캡ability가 아니라 `hgext/clonebundles.py`(`censor`와
   동일하게 서버측 **확장**)가 `wireprotov1server._capabilities`를
   `extensions.wrapfunction`으로 감싸서 `.hg/clonebundles.manifest` 파일이 존재할
   때만 추가한다 — `--config extensions.clonebundles=`로 확장을 명시적으로
   로드하지 않으면 매니페스트 파일이 있어도 캡ability가 절대 광고되지 않는다(반면
   `?cmd=clonebundles` 명령 핸들러 자체는 core에 무조건 등록돼 있어 확장 없이도
   응답은 오지만, 실제 hg 클라이언트도 hg4j도 캡ability 미광고 시 애초에 요청을
   시도하지 않으므로 문제 없음). 6.0/7.2.2 둘 다 확장 활성화 시 정확히 같은 조건으로
   동작함을 확인 — 단, 매니페스트 응답 바이트에 trailing newline 개수가 버전마다
   미세하게 다름(6.0: `...v2\n`, 7.2.2: `...v2\n\n`) — 파서는 빈 줄을 무시하므로
   영향 없음.
2. **매니페스트 조회**: 클라이언트가 `?cmd=clonebundles`로 GET 요청 — 응답 바디는
   서버 저장소의 `.hg/clonebundles.manifest` 파일 내용을 **그대로** 반환한다
   (`wireprotov1server.py:263` `clonebundles()`: `repo.vfs.tryread(CB_MANIFEST_FILE)`).
   이 요청 자체는 여전히 기존 wire protocol v1 경로(`?cmd=`)를 타지만, 응답으로 받은
   URL을 통한 **실제 대용량 데이터 전송은 wire protocol을 완전히 벗어난 일반 HTTP(S)
   GET**이라는 점이 이 기능의 핵심("바이패스").
3. **매니페스트 포맷**: 줄바꿈(`\n`)으로 구분된 엔트리 목록. 각 줄은
   `<URL> [<KEY>=<value>[ <KEY>=<value>]...]` — URL 뒤에 공백으로 구분된
   `key=value` 속성(키/값 모두 URI 인코딩). 예약 키(대문자): `BUNDLESPEC`(예:
   `zstd-v2`, `hg bundle --type`과 동일한 문법, `<compression>-<type>` 형태),
   `REQUIRESNI=true`, `REQUIREDRAM=64MB`. 소문자 키는 사이트 커스텀(필터링 대상
   아님). pullbundles 전용 키 `heads`/`bases`(`;`로 구분된 hex 목록)도 있지만
   이번 백로그는 **clonebundles만** 범위로 한다(pullbundles는 서버가 일반 pull
   요청에 부분 응답을 끼워 넣는 별개 기능 — 필요시 후속 항목으로 분리).
4. **클라이언트 알고리즘**(`exchange.py:_maybeapplyclonebundle` 부근): (a) 로컬
   설정 `ui.clonebundles`가 꺼져 있거나 원격이 `clonebundles` capability를 광고하지
   않으면 스킵하고 평범한 pull로 진행. (b) 매니페스트를 파싱해 `BUNDLESPEC`이
   클라이언트가 지원하지 않는 포맷인 엔트리, `REQUIRESNI=true`인데 SNI 미지원인
   엔트리 등을 필터링. (c) 남은 엔트리를 `ui.clonebundleprefers` 설정 기준으로
   정렬(비어있으면 매니페스트 순서 그대로, 첫 번째 엔트리 사용). (d) 선택된 URL로
   **일반 HTTP(S) GET**(와이어 프로토콜과 무관, 평범한 파일 다운로드)을 실행해
   번들 파일을 받는다. (e) 받은 번들을 로컬에 그대로 `unbundle`(적용) — 이미
   hg4j에 있는 `UnbundleCommand`/`ChangegroupParser` 재사용 가능. (f) 그 후 원래
   서버에 재접속해 **평범한 `pull`**로 번들 생성 시점 이후의 나머지 변경분을 마저
   받는다(즉, clonebundle은 "부분 시드"이고 항상 뒤이어 증분 pull이 따라온다 — 이
   마무리 pull 단계는 hg4j의 기존 `FetchCommand`/`PullCommand` 그대로 재사용 가능,
   신규 구현 불필요).
5. **실패 시 폴백 없음**: 다운로드가 실패하면 전체 클론이 실패해야 한다(서버
   운영자가 의도적으로 무거운 클론을 오프로딩했으므로, 실패 시 자동으로 원 서버에
   폴백하면 애초에 오프로딩한 의미가 없어지고 서버가 과부하될 수 있다는 것이 실제
   hg의 설계 근거) — 조용히 무시하고 평범한 pull로 넘어가면 안 된다.

### hg4j 구현 범위 (제안, TDD 순서)

**클라이언트 측 (우선순위 높음 — 실사용 가치가 큰 쪽)**
1. `HgRemoteClient.getCapabilities()`가 이미 파싱하는 v1 capabilities 목록에서
   `"clonebundles"` 토큰 존재 여부를 확인하는 `supportsClonebundles()` 추가.
2. `HgRemoteClient`에 `fetchClonebundlesManifest()` 신설 — `?cmd=clonebundles`
   GET, 응답 바이트를 그대로 반환(이미 있는 `executeGetBinary()` 재사용 가능).
3. 매니페스트 파서 신설(`ClonebundlesManifest`류) — 위 3번 포맷 그대로 파싱,
   `BUNDLESPEC`/`REQUIRESNI`/`REQUIREDRAM` 등 예약 키 구조화.
4. hg4j가 실제로 읽을 수 있는 `BUNDLESPEC` 값(현재 지원하는 bundle2/changegroup
   버전과 압축 방식 조합)만 남기는 필터링 로직.
5. 선택된 URL로 순수 HTTP(S) GET(와이어 프로토콜 계층을 전혀 거치지 않는 별도
   다운로드 경로 — `HgRemoteClient`의 기존 `?cmd=` 기반 메서드들과는 무관한 신규
   메서드)로 번들 바이트를 받아 기존 `UnbundleCommand`로 적용.
6. 실패 시 예외를 그대로 전파(폴백 금지)하도록 `FetchCommand`/클론 진입점에 연결.
7. clonebundle 적용 후 이어지는 마무리 증분 pull은 기존 `FetchCommand` 그대로
   호출 — 신규 코드 없음, 통합 테스트에서만 확인.

**서버 측 (우선순위 낮음 — hg4j가 서버 역할을 하는 시나리오, `HgWireServer`)**
8. `HgWireServer`의 capabilities 응답에 `.hg/clonebundles.manifest` 파일이
   존재할 때만 `"clonebundles"` 토큰을 추가.
9. `?cmd=clonebundles` 핸들러 신설 — 해당 파일 내용을 그대로 반환(실제 hg와
   동일하게 파일 유무만으로 지원 여부가 결정되므로, 파일이 없으면 빈 응답 또는
   404 — 실제 hg 동작 재확인 필요).

### 검증 방법
- 단위 테스트: 매니페스트 파서(다양한 key=value 조합, 필터링 로직) — 실제 hg
  서버 없이도 가능.
- interop 테스트(`@Tag("interop")`): 로컬 파일시스템에 정적 파일 서버(이미 세션
  전반에 쓰인 `com.sun.net.httpserver.HttpServer` 패턴)를 띄우고, 그 URL을 담은
  가짜 `clonebundles.manifest`를 실제 hg 저장소에 심어 **real hg 클라이언트**가
  hg4j가 만든 번들을 clonebundle로 실제로 받아가는지 확인하거나, 반대로 hg4j
  클라이언트가 real hg 서버(Docker 6.0)의 clonebundles 응답을 올바르게 파싱·적용
  하는지 확인.
- 회귀 없음: 기존 pull/clone 경로는 `ui.clonebundles`에 해당하는 옵션이 꺼져
  있으면(또는 서버가 capability를 광고하지 않으면) 전혀 건드리지 않아야 한다 —
  100% opt-in 경로로 구현.

### 진행 현황 (2026-09-01)

**클라이언트 1~7번, 서버 8~9번 전부 완료.** 서버 측(8~9번)은 최초엔 사용자 요청으로
보류했었으나, 이후 "JGit식 재구성"(`HgHttpWireServer`/`HgSshWireServer` 신설) 작업
중 같은 세션에서 마저 구현됐다 — 아래 목록 참고.

- ✅ `ClonebundlesManifest`(신규, `com.github.search5.hg4j.bundle`) — 매니페스트
  파서(`parse`) + hg4j가 실제로 소화 가능한 BUNDLESPEC만 남기는 필터
  (`filterSupported`: `none-v1`/`gzip-v1`/`bzip2-v1`/`none-v2`/`gzip-v2`/
  `bzip2-v2`/`zstd-v2`). 단위 테스트 7건 GREEN.
- ✅ `HgRemoteClient.supportsClonebundles()`/`fetchClonebundlesManifest()` 신설
  — 기존 `negotiateV2`가 파싱하던 capabilities 목록에 `"clonebundles"` 토큰
  체크를 추가. 로컬 `HttpServer` 기반 단위 테스트 3건 GREEN.
- ✅ `ClonebundlesCommand.downloadAndApply(repository, url)` 신설 — 순수 HTTP(S)
  GET(와이어 프로토콜 미경유)으로 번들을 받아 기존 `UnbundleCommand`로 적용,
  다운로드 실패 시 폴백 없이 예외 전파. 단위 테스트 2건 GREEN.
- ✅ **실제 hg 이중 버전 검증(Docker Mercurial 6.0 + 7.2.2, 사용자 지시로 둘 다
  확인)**: `ClonebundlesRealHgInteropTest`(`@Tag("interop")`) 3건 — (1) 확장
  미활성 시 캡ability 비광고, 활성 시 광고됨을 실제 서버로 확인, (2) 실제 서버의
  `?cmd=clonebundles` 응답이 hg4j 파서로 정확히 파싱됨(6.0/7.2.2 trailing
  newline 차이 포함해도 문제 없음), (3) **실제 hg가 `hg bundle --all --type
  none-v2`로 만든 진짜 HG20(bundle2) 번들 파일**을 hg4j의
  `UnbundleCommand`/`Bundle2Parser`가 clonebundles 다운로드 경로를 통해 정확히
  적용함(2개 커밋 모두 반영 확인) — hg4j 자체 생성 번들이 아닌 **진짜 real-hg
  산출물**로 검증했다는 점이 중요.
- ✅ **6~7번 완료(2026-09-01, TDD)**: `FetchCommand.call()`에 자동 배선 완료 —
  로컬 changelog가 완전히 비어있을 때(= 사실상 clone 시나리오, 실제 hg도
  `pull`이 아니라 `clone`에서만 시도)만, 그리고 원격이 `HgRemoteClient`(HTTP)이고
  `supportsClonebundles()`가 참일 때만 시도한다: 매니페스트 조회 →
  `filterSupported`로 필터 → 첫 엔트리 선택(→ `ui.clonebundleprefers` 미설정 시
  실제 hg와 동일) → `ClonebundlesCommand.downloadAndApply` → 실패 시 예외
  그대로 전파(폴백 없음) → 성공 시 `repository.clearRevlogCache()`로 로컬
  상태 갱신 후 기존 discovery/getbundle 로직에 그대로 이어붙여 "마무리 증분
  pull"이 자동으로 수행됨(신규 코드 없이 기존 로직 재사용, 계획대로).
  **버그 발견·수정**: 최초 구현 시 클론번들로 받은 커밋이 `FetchCommand.call()`의
  반환값(`List<byte[]>`)에 반영되지 않아 — 클론번들만으로 전체 저장소가
  이미 다 채워진 경우 뒤이은 discovery가 "이미 최신"으로 판단해 빈 리스트를
  반환하고, 그 결과 `CloneCommand.checkoutLatest()`가 "아무것도 안 받아왔다"고
  오판해 워킹카피 체크아웃을 건너뛰는 버그가 있었음(TDD로 RED 확인 후 발견).
  `ClonebundlesCommand.downloadAndApply`/`tryApplyClonebundle`이 임포트된 커밋
  목록을 반환하도록 고치고 `mergeClonebundleResults()`로 이후 결과와 합쳐(클론번들
  분량이 먼저, 증분 pull 분량이 나중) 모든 반환 지점에서 정확한 결과가 나가도록
  수정. `ClonebundlesAutoWireInteropTest`(`@Tag("interop")`)로 실제 Mercurial
  6.0 컨테이너를 대상으로 `CloneCommand` 전체 흐름(캡ability 감지 → 매니페스트
  조회 → 실제 hg가 만든 진짜 번들 다운로드 → 적용 → 워킹카피 체크아웃)이 완전히
  자동으로 동작함을 종단간 검증 — 별도로 시작한 disposable 컨테이너 기준.
- ✅ **8~9번 완료(서버 측 — `Wire1Commands`가 clonebundles를 광고·서빙하는 저장소
  역할)**. 처음엔 사용자 요청으로 보류했었으나, "JGit식 재구성" 작업(아래 log.md의
  [2026-09-01] "JGit식 재구성 + 갭 표 백로그 4건" 항목)에서 별도로 제시된
  `HgWireServer` 6개 갭 표의 5번 항목으로 다시 다뤄져 같은 세션에 마저 구현됐다:
  `Wire1Commands.capabilitiesString(repo)`가 `.hg/clonebundles.manifest` 파일 존재
  여부로 `"clonebundles"` capability 토큰을 조건부 광고(파일 없으면 미광고 — 실제
  hg의 확장 활성화 조건과 동일한 효과), `clonebundles(repo)` 커맨드가 `?cmd=clonebundles`
  요청에 파일 내용을 그대로 반환. `HgHttpWireServerTest#serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists`
  로 종단간 검증(매니페스트 파일이 생기기 전/후 실제 `HgRemoteClient`로 capability
  광고 여부와 응답 내용이 정확히 바뀌는지 확인) — 2026-09-01 재확인, GREEN.

## 커버리지 95% 작업 중 추가로 발견·수정한 버그 (2026-09-01)
빌드 게이트(`jacocoTestCoverageVerification`)를 다시 90% 이상으로 통과시키는 작업 중
실제 hg4j 동작 버그 3건을 더 발견·수정했다 (테스트가 아니라 프로덕션 코드 수정):

1. **`StatusCommand`의 racy-write 검증이 잘못된 리비전과 비교하던 버그(핵심)** —
   `dirstate`의 size/mtime이 우연히 일치할 때 실제 내용을 다시 확인하는 안전장치가
   있었는데, 비교 대상을 "해당 파일 filelog의 가장 최근 리비전"으로 고정해뒀었다.
   이는 워킹카피가 tip에 있을 때만 맞는 가정이라, `hg update`로 과거 리비전으로
   전환한 뒤에는 **전혀 손대지 않은 파일도 modified로 오탐**했다. 워킹카피의 실제
   dirstate parent 커밋 기준으로 비교하도록 수정(`getParentCommitFileContent()` 신설,
   fast/slow path 양쪽에 적용).
2. **`ShelveCommand`의 동일 버그 패턴** — 자연스럽게 수정된(dirstate state `'n'`)
   추적 파일 감지가 크기/mtime만 봤는데, 같은 초·같은 바이트 크기로 편집되면
   변경을 놓치고 shelve 자체가 조용히 no-op이 되는 버그. `getBaselineContent()`
   기반 콘텐츠 비교로 보강.
3. **`HgRevsetEngine.evaluateSort`의 `sort(x, "author"/"user")`가 사실상 no-op** —
   changelog 리비전이 아니라 filelog 전용 메타데이터 파서(`Revlog.getRevisionMetadata`)를
   호출해서 항상 빈 문자열끼리 비교하고 있었다. changelog 원문에서 직접 author를
   추출하도록 수정.
4. **`SubrepoCommand`** — `.hgsubstate`에 리비전이 없을 때 fallback 문자열 `"tip"`을
   그대로 hex 파서에 넘겨 항상 예외가 나던 버그(이전 세션에 이미 보고·수정됨, 여기서는
   같은 커밋 배치로 재확인).

## 추가로 완전히 해결된 항목 2건 (2026-09-01, 같은 세션 후속)
5. **심볼릭 링크에 대한 `StatusCommand`/`CommitCommand`의 size/content 불일치** —
   조사 결과 확정: Java의 `File.length()`/`Files.readAllBytes()`는 심볼릭 링크를
   만나면 링크를 따라가 **대상 파일**의 크기/내용을 반환하는데, 실제 hg는 `lstat`
   방식으로 심볼릭 링크 자체를 "링크가 가리키는 경로 문자열"로 취급한다(filelog에도
   이 문자열이 그대로 저장됨). `UpdateCommand`/`RevertCommand`/`ShelveCommand`는 이미
   경로 문자열 길이를 올바르게 썼지만, `CommitCommand`/`StatusCommand`는
   `File.length()`로 대상 파일 크기를 썼다 — 두 관례가 섞여 있어 대상 파일 내용
   길이가 링크 경로 문자열 길이와 다르면 아무것도 안 건드린 심볼릭 링크도 항상
   modified로 오탐했다. `StatusCommand`에 `effectiveSize()`/`effectiveContent()`
   헬퍼를 추가하고 `CommitCommand`의 dirstate 기록도 경로 문자열 길이 기준으로
   통일해 수정. TDD로 확인.
6. **`UpdateCommand`가 리비전 전환 시 워킹 브랜치명을 복원하지 않던 문제** — TDD로
   수정 완료. 실제 hg는 `hg update`시 대상 리비전이 커밋됐던 브랜치로 작업 브랜치를
   전환하는데, hg4j는 dirstate parent만 바꾸고 `.hg/branch`는 그대로 뒀다.
   `CommitCommand.getBranchOfRevision(Revlog, int)`을 신설해(changelog의
   `branch:<name>` extra 필드를 디코딩하는 로직을 `LogCommand`/`UpdateCommand`에
   중복 구현하던 것 중 하나를 이 공유 헬퍼로 흡수) `UpdateCommand.call()` 마지막에
   `repository.setBranch(...)`를 호출하도록 추가. `UpdateCommand.resolveTargetNodeId()`의
   named-branch-head 탐색도 같은 헬퍼로 재사용하도록 정리.

## `UpdateCommand`류 브랜치 미복원 버그, 형제 명령 4개 전수 조사 후 4건 TDD 수정 (2026-09-01)
`UpdateCommand`를 재사용하지 않고 자체적으로 워킹카피를 재배치하는 다른 포셀린 명령들을
전수 조사해 실제로 같은 버그 계열이 있는지 확인했다. 결과: 4개 중 4개 전부 실제 버그로
확인·TDD(RED→GREEN)로 수정, 2개는 조사 결과 문제 없음으로 확정.

**수정한 것:**
1. **`HisteditCommand` — 가장 심각, 단순 표시 문제가 아니라 히스토리 자체 손상.**
   `commitNewRev()`가 `CommitCommand`를 거치지 않고 changelog 텍스트를 직접 조립하면서
   브랜치 extra 필드를 무조건 생략했다(`secs + " 0\n"` 고정) — non-default 브랜치
   커밋을 histedit로 재작성하면 **재작성된 커밋이 조용히 전부 default 브랜치로
   뒤바뀌는** 데이터 손상급 버그였다. `CommitCommand.getBranchOfRevision()`으로 원본
   커밋의 브랜치를 조회해 그대로 보존하도록 수정, `call()` 종료 시 새 tip 기준으로
   `repository.setBranch(...)`도 추가.
2. **`BisectCommand`** — 이분 탐색 중간 리비전을 실제로 체크아웃하는 "Physical File
   Checkout & Workspace Sync" 로직(주석에도 명시)에 브랜치 전환이 없었음. `UpdateCommand`
   와 동일 패턴으로 수정.
3. **`MergeCommand`의 fast-forward 경로만 해당** — P1이 P2의 조상이라 사실상
   `hg update`와 같은 단일-부모 전진을 수행하는 분기(295번 줄 부근)에 브랜치 전환이
   없었음. **진짜 두 부모짜리 병합 경로(431번 줄 부근)는 조사 결과 문제 없음** — 실제
   hg도 병합 커밋은 현재 브랜치를 유지하므로 그대로 둠.
4. **`StripCommand`** — strip 후 워킹카피 parent를 살아남은 리비전으로 되돌리는
   지점에 브랜치 복원이 없었음(예: feature 브랜치 tip을 strip하면 default 브랜치
   조상으로 돌아가야 하는데 워킹 브랜치는 feature로 남음). 전용 테스트 파일이 아예
   없어서 `StripCommandTest.java`를 새로 만듦.

**문제 없음으로 확정한 것:**
- **`GraftCommand`** — 소스 리비전의 브랜치로 전환하지 않고 현재 브랜치를 유지하는 게
  실제 hg의 정확한 동작(graft는 브랜치를 바꾸지 않음) — 정상.
- **`RebaseCommand`** — 이미 별도 메커니즘으로 원본 커밋의 브랜치를 재커밋에 보존하는
  로직(`backup.branch` 기반)이 올바르게 구현돼 있었음 — 오늘 고친 것과는 무관한
  정상 동작.

4건 모두 RED(실패하는 테스트로 버그 재현) 확인 후 수정해 GREEN 전환, 관련 기존
테스트 전체 재실행으로 회귀 없음 확인.

## 커버리지 95% 이니셔티브 라운드 2 — 44개 클래스 TDD, 실제 버그 21건 발견 (2026-09-02)

`agentBuildDir` 빌드 격리(위 "라운드 1" 사고 이후 도입)로 병렬 에이전트를 5개 배치에
걸쳐 안전하게 돌려, 미커버 JaCoCo instruction 수가 큰 순서로 44개 클래스를 전수
TDD 처리했다. 전체 회귀 기준 INSTRUCTION 97.10%/LINE 97.05%/METHOD 97.84%/CLASS
100%까지 끌어올렸고(BRANCH는 86.40%로 아직 미달, 대부분 방어적 죽은 코드), 그 과정에서
실제 hg 7.2 대조 검증을 통해 진짜 버그 21건을 발견해 19건을 즉시 수정했다(데이터
손상/크래시급 다수 포함 — `CommitCommand`의 dirstate-v2 롤백 손상, `Revlog`의
`appendRawRevision` inline 무시, `StripCommand`의 무효 롤백, `HgRemoteClientV2`의
증분 pull 손상, `GraftCommand`/`RebaseCommand`의 신규 파일 누락·exec/symlink 소실
등). 위 항목 8("트리매니페스트")과 10("깨진 symlink")은 이 라운드에서 새로 발견해
백로그에 추가한 것이다. 전체 버그 목록, 클래스별 전후 커버리지 수치, 배치 구성은
[[test-coverage-95-percent-initiative]]에 상세 기록. 커밋 `a8b9d96`.

## 관련 페이지
- [[jgit-parity-requirement]] — 구조/네이밍 요건 (이 문서와는 독립적인 축)
- [[test-coverage-95-percent-initiative]] — 커버리지 작업 라운드별 상세 결과/버그 목록
- [[revlog]], [[dirstate]], [[bundle2-changegroup]], [[revset]] — 이미 조사된 스펙 영역 상세
- [[core]], [[transport]] — 관련 구현 클래스 위치
- [[sources]] — 향후 원문 스냅샷을 추가할 위치
