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
`get_symbols_overview`로 확인 가능한 클래스 존재 여부만으로 판단했으며, **각 항목의 "구현됨"
표시는 클래스가 존재한다는 뜻이지 스펙의 모든 세부 규칙까지 검증됐다는 뜻이 아니다** —
"확인 필요"로 표시된 항목은 반드시 실제 hg 소스/공식 문서와 라인 단위로 대조해야 한다.

| 스펙 영역 | 공식 근거 | hg4j 관련 클래스 | 현재 판단 |
|---|---|---|---|
| `requires` 파일 / requirements | `hg help internals.requirements` | `HgRepository.loadRequires()` | ✅ 존재 (세부 requirement 문자열 커버리지는 확인 필요) |
| store 레이아웃 / fncache 인코딩 | wiki `fncacheRepoFormat` | `StoreEngine`, `DefaultFileStoreEngine` | ⚠️ 부분 — 최근 커밋(`56b1988`)에서 "fncache 레이어 불일치" 버그를 수정한 이력 자체가 이 영역이 취약했음을 시사 |
| Revlog v1 (인덱스/데이터, generaldelta, inline) | `hg help internals.revlogs`, wiki `FileFormats` | `Revlog`, `RevlogIndex`, `DeltaEngine`, `DeltaCodec` | ✅ v1 구현 (README에 명시) |
| Revlog v2 (persistent nodemap 등, 최신 Mercurial) | `hg help internals.revlogs` | 없음 | ❌ **미구현 · 필수 구현 대상** (2026-08-31 확정 — v1과 병행 지원 필수, README도 "Revlog (v1)"이라고만 명시된 상태라 v1/v2 겸용으로 확장 필요) |
| Changelog 포맷 (커밋 메타데이터 인코딩) | wiki `FileFormats` | `Revlog` + `api.CommitCommand`/`LogCommand` | ⚠️ 확인 필요 — extra 필드, 다중 부모, 인코딩 예외 케이스 |
| Manifest 포맷 | wiki `Manifest` | `HgRepository.getManifestRevlog()`, `treewalk.ManifestWalk` | ✅ 존재 |
| Dirstate v1 | wiki `DirState` | `Dirstate` | ✅ |
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ 존재, 정확한 바이트 레이아웃 대조는 미착수 ([[index]]의 "아직 없는 페이지" 참고) |
| Bundle1 (레거시 `HG10UN/GZ/BZ`) | wiki `BundleFormat` | 없음 | ❌ **미구현으로 추정** — `Bundle2Parser`만 존재, Bundle1 클래스 없음 |
| Bundle2 컨테이너 | `hg help internals.bundle2` | `Bundle2Parser` | ✅ |
| Changegroup (cg1/cg2/cg3) | `hg help internals.changegroups` | `ChangegroupParser` | ⚠️ 확인 필요 — cg 버전별 차이(트리매니페스트, censor 지원 등)까지 커버하는지 미확인 |
| Wire protocol v1 (HTTP/SSH, capability 협상) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `HgWireServer`, `TransportProtocol` | ✅ (README에 "capability negotiations" 명시) |
| Wire protocol v2 (실험적, cbor 기반) | `hg help internals.wireprotocolv2` | 없음, `build.gradle`에 cbor 관련 의존성 없음 | ❌ **미구현 · 필수 구현 대상** (2026-08-31 확정 — cbor 파싱/인코딩 의존성 추가 필요, v1과 병행 지원) |
| Phases (draft/public/secret) | `hg help phases` | `PhaseRoots`, `api.PhaseCommand` | ✅ |
| Obsolescence markers | wiki 관련 문서 | `HgObsolescenceParser`, `HgObsMarker` | ⚠️ 파싱만 확인, 쓰기/마커 생성 경로까지 커버하는지 확인 필요 |
| Censor (민감정보 삭제) | `hg help internals.censor` | 없음 | ❌ **미구현으로 추정** |
| Narrow clone / narrowspec | wiki 관련 문서 | `NarrowCloneCommand`, `HgTreeFilter` | ✅ (README에 명시) |
| Sparse checkout | 관련 확장 문서 | `SparsePathFilter` | ⚠️ 부분 — sparse 설정 파일(`.hg/sparse`) 자체 파싱 로직 존재 여부 확인 필요 |
| LFS (largefiles) | 관련 확장 문서 | `HgLfsManager`, `HgLfsPointer` | ✅ |
| Subrepositories (`.hgsub`/`.hgsubstate`) | wiki 관련 문서 | `HgSubrepoParser`, `HgSubrepoEntry`, `api.SubrepoCommand` | ✅ |
| Config 파일 포맷 (`hgrc`, include/`%include`, 섹션) | `hg help internals.config`, man `hgrc(5)` | `HgRcConfig` | ⚠️ 확인 필요 — `%include`/`%unset` 등 세부 지시자 커버리지 |
| Merge state 영속화 (재개 가능한 머지) | `hg help internals.mergestate` | 명시적 클래스 미확인 (`ResolveCommand`, `MergeCommand`는 존재) | ⚠️ **확인 필요** — 별도 MergeState 클래스가 없다면 `.hg/merge/state2` 파일 포맷 자체를 안 쓰고 있을 가능성 |
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
