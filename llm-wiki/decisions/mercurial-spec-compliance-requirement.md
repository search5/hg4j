---
updated: 2026-09-04
status: 번호 매겨진 백로그 1~37번 전부 완료(25번은 오탐으로 종결). 요약:
  1~21 완료 → 재검증에서 22~25 발견·완료(25는 오탐) → 재검증에서 26~28
  발견·완료 → "완료 항목 안에 남은 캐비어트" 재확인으로 29~36 발견·전부 완료
  → [[exhaustive-interop-matrix-plan]] TDD 중 37 발견·완료, 사용자 지시로
  38~41 등록. **완료(29~37 상세)**: 29(requires 허용목록이 낡아 평범한
  저장소도 거부되던 버그), 30(narrow wire-level 재통합 + 사전 존재 캐시
  버그), 31(LFS 커밋/체크아웃 파이프라인 — LFS 노드 해시가 포인터 텍스트가
  아니라 실제 파일 콘텐츠 기준이어야 한다는 버그 발견·수정), 33(SSH push
  checkheads 미구현), 34(bisect merge DAG 검증, 새 버그는 없었음), 35(revlog
  항상 non-inline — `appendChangeGroupEntry`가 inline 상태를 무시하던 근본
  원인 수정), 36(tag 재태깅 `-f` 가드), 37(dirstate-v2 트리 손상 — real hg의
  이진 탐색 리더가 정렬 안 된 자식 배열을 못 찾던 것, `hg-rust-7.2.4`
  컨테이너의 Rust 소스 대조로 근본 원인 확정). **미착수**: 32(subrepo
  잔여 gap 4건, 31 완료로 이제 착수 가능 — `CommitCommand`/`UpdateCommand`
  공유), 38(동시 push 레이스 컨디션), 39([[exhaustive-interop-matrix-plan]]
  매트릭스 범위 확장 — 현재 67개 명령 중 7개만 검증됨), 40(narrow clone의
  진짜 wire-protocol ellipsis node 왕복 — 지금은 로컬 필터링만 있고 전송량
  절감 효과가 없음), 41(SVN 서브저장소 지원, **우선순위 최하** — 코드베이스에
  관련 처리가 전혀 없어 맨땅에서 시작해야 함, 32/38/39/40 완료 후 착수).
  **42~44번은 신규 — 2026-09-04 gap table/완료 항목 재감사로 발견된 미등록
  캐비어트를 승격, 전부 미착수**: LFS 세부 옵션 3가지(rename+LFS copy-tracing,
  `[lfs] url` override, `lfs.disableusercache`, 42), revlog가 자라도
  inline→non-inline 전환을 안 함(`_enforceinlinesize` 상당 로직 없음, 43),
  clonebundles 서버의 매니페스트-없음 응답 포맷 미확인(우선순위 낮음, 44).
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
| `requires` 파일 / requirements | `hg help internals.requirements` | `HgRepository.loadRequires()`, `Hg.open()` | ✅ **재검증 완료(2026-09-04, 백로그 29번)** — `HgOpenRequirementValidationTest` 신설. `HgRepository.loadRequires()` 자체는 8개 문자열(dirstate-v2/revlog-compression-zstd/exp-changelog-v2/exp-revlogv2.2/persistent-nodemap/fileindex-v1/treemanifest/exp-copies-sidedata-changeset)만 인식하고 문제없었으나, **`Hg.open()`의 별도 `SUPPORTED` 허용목록이 심각하게 낡아 있던 실제 버그를 발견·수정**했다 — `sparserevlog`(모든 real hg 7.2 기본 저장소에 있음)가 아예 빠져 있었고 `revlog-compression-zstd`를 접미사 없는 `revlog-compression`으로 잘못 갖고 있어서, **설정 없이 그냥 `hg init`한 완전히 평범한 저장소조차 `Hg.open()`으로 열면 `HgValidationException: unsupported repository requirement: sparserevlog`로 거부되고 있었다**(`new HgRepository(dir)`로 이 게이트를 우회하는 기존 테스트들만 이 사실을 가려왔음). `SUPPORTED` 목록을 실측된 정확한 문자열로 동기화 |
| store 레이아웃 / fncache 인코딩 | wiki `fncacheRepoFormat`, `mercurial/store.py`(`_pathencode`/`_hashencode` 실측) | `util.NodeIdUtil.encodeFname` | ✅ **구현 완료(2026-09-01)** — `store.py`의 실제 인코딩 알고리즘대로 전면 재작성. 이전에는 Windows `COM#`/`LPT#` 예약어의 세 번째 글자가 아니라 끝자리 숫자를 이스케이프하는 버그와, 긴 경로(120바이트 초과) 해싱 방식이 실제 hg에 없는 방식(255바이트 초과 시 디렉터리 없는 형태로 전환)으로 되어 있던 버그를 발견·수정. 대문자/앞자리 점/Windows 예약어/150바이트 초과 경로 등 7개 까다로운 파일명으로 실제 hg 온디스크 레이아웃과 바이트 단위 일치 검증(`FncacheEncodingInteropTest`). fncache 파일 목록 자체(원본 논리 경로 그대로 저장)는 기존에도 정확했음 |
| Revlog v1 (인덱스/데이터, generaldelta, inline) | `hg help internals.revlogs`, wiki `FileFormats` | `Revlog`, `RevlogIndex`, `DeltaEngine`, `DeltaCodec` | ✅ v1 구현, **2026-09-01 실제 hg CLI 재검증 중 zstd requirement 문자열 버그 발견·수정**(`revlog-compression=zstd`→`revlog-compression-zstd`) — 상세는 [[revlog]]. **inline 레이아웃도 완료(2026-09-04, 백로그 35번)** — hg4j는 새 revlog를 항상 non-inline(`.i`/`.d` 분리)으로만 써서 real `hg verify`가 "not in fncache" 경고를 내던 문제를 발견·수정. real hg는 revlogv1이 기본 inline이고 131072바이트(`_maxinline`)를 넘을 때만 분리하는데(changelog만 예외), hg4j `Revlog` 생성자가 신규 v1 filelog/manifest를 inline으로 시작하도록 수정 — 근본 원인은 `appendChangeGroupEntry`(pull/push changegroup 적용 경로)가 inline 상태를 아예 무시하고 항상 non-inline으로 쓰던 별도의 hand-rolled write path였음, `appendRevision`과 동일한 inline/non-inline 분기로 재작성 |
| Revlog v2 — changelog-v2(`exp-changelog-v2`) | `mercurial/revlogutils/docket.py`+실제 hg 데이터 대조 | `storage.RevlogIndex`, `storage.Revlog` | ✅ **구현 완료(2026-09-01)** — 실제 hg CLI로 생성한 저장소로 읽기/쓰기 검증, `hg verify`로 상호운용성까지 확인. 상세: [[revlog-v2-support-plan]] |
| Revlog v2 — 일반(`exp-revlogv2.2`, 매니페스트/파일로그) 및 `fileindex-v1` | Rust 확장 포함 실제 Mercurial 7.2.4(Docker) fixture로 바이트 단위 검증 완료 | `RevlogV2GeneralParserTest`, `FileIndexTest`(+ 수동 Docker `hg verify`/`log`/`cat` 왕복) | ✅ **완료(2026-09-02)** — 읽기+쓰기 모두 구현. 상세: [[revlog-v2-support-plan]] |
| persistent-nodemap(`.n` 트라이 파일 가속 조회) | `mercurial/revlogutils/nodemap.py`(docket + trie 인코딩 실측) | `storage.NodeMapFile`, `storage.RevlogIndex`, `storage.Revlog`, `storage.DefaultFileStoreEngine` | ✅ **읽기(가속 조회) 구현 완료(2026-09-03)** — Docker `hg-rust-7.2.4`(Rust 확장 포함, 이 환경의 시스템 hg는 이 requirement의 저장소 자체를 못 만듦)로 40커밋 실제 저장소를 만들어 `.n` docket(62바이트: version/uid_size/tip_rev/data_length/data_unused/tip_node_size 헤더 + uid + tip_node) + `-<uid>.nd` 트라이(64바이트 블록, 16×4바이트 빅엔디안 signed int, 루트는 항상 마지막 블록)를 바이트 단위로 대조(`NodeMapFileFixtureTest`, 40/40 실제 노드 해시 일치). `RevlogIndex`에 `.n`이 신선하고(tip_rev/tip_node가 현재 인덱스와 일치) 비-inline인 경우 전체 레코드 스캔을 건너뛰고 오프셋을 산술적으로 계산하는 fast path를 추가, `findRevision()`이 트라이를 우선 조회(항상 실제 레코드로 재검증 후 반환)하고 stale/부재 시 기존 순차 스캔 fallback으로 안전하게 전환(`RevlogIndexPersistentNodeMapTest`). `findByHexPrefix()`는 트라이가 전체 노드 해시를 저장하지 않아 가속 대상에서 제외 — 최초 호출 시 지연된 맵을 1회 materialize. **쓰기(커밋 시 `.n` 갱신)도 ✅ 완료(2026-09-03)** — `mercurial/revlogutils/nodemap.py`의 전체 재빌드(`_build_trie`)와 증분 갱신(`_update_trie`) 양쪽을 실측해 `NodeMapFile`에 모두 구현(실제 hg의 "새 길이가 unused*10 이하면 포기하고 전체 재빌드로 폴백" 10% 임계값 로직 포함), `Revlog`의 리비전 append 진입점 전부에 배선. 상세는 백로그 15번(읽기)/21번(쓰기) 참고 |
| Sidedata (revlog v2 부가 메타데이터) | `mercurial/revlogutils/sidedata.py`/`metadata.py` 실측 | `storage.SidedataCodec`, `api.ChangingFiles`, `api.SidedataChangedFilesCommand` | ✅ **읽기+쓰기 모두 완료(2026-09-03)** — 실제 쓰이는 건 `exp-copies-sidedata-changeset` requirement 하의 `SD_FILES` 키 하나뿐(`SD_P1COPIES`류 레거시 상수는 실제 hg 소스에 죽은 코드로만 존재, LFS는 sidedata와 무관함을 확인). index 레코드의 sidedata offset/complen/compression-mode 파싱 + 컨테이너/payload 디코드 + 과거 리비전 copy-tracing 조회까지 구현·검증됨. **쓰기(커밋 시 hg4j 스스로 `SD_FILES` 생성)도 ✅ 완료(2026-09-03)** — `SidedataCodec.serialize`/`ChangingFiles.encode` 신설, `CommitCommand`가 `exp-copies-sidedata-changeset` 저장소에서 added/removed/touched/copy 정보를 수집해 커밋 시 인코딩·`.sda`에 append. 부수 발견: v2 docket의 `sidedata_end` 필드 미갱신 버그, changelog-v2 압축모드가 항상 DEFAULT로 하드코딩돼 있던 버그(작은 콘텐츠엔 PLAIN이어야 하는데 zstd로 오인되게 만들던 실제 상호운용성 버그) 둘 다 발견·수정. real hg `hg debugchangedfiles`/`hg verify` 양방향 검증(`SidedataFilesWriteTest`). 상세는 백로그 19번 |
| Changelog 포맷 (커밋 메타데이터 인코딩) | wiki `FileFormats`, `mercurial/changelog.py`(`encodeextra`/`add` 실측) | `Revlog` + `api.CommitCommand`/`LogCommand` | ✅ **구현 완료(2026-09-01)** — 다중 부모(p1/p2 정렬 포함 노드 해시 계산) 인코딩은 기존에도 정확했음. **발견·수정한 실제 버그**: default 브랜치 커밋에 항상 "branch:default" extra 필드를 썼는데, 실제 hg는 default 브랜치일 때 이 필드를 아예 안 써서 hg4j가 만든 default 브랜치 커밋의 노드 해시가 동일 내용이라도 실제 hg와 달라지고 있었음. 콜론을 이스케이프하는(실제 hg엔 없는) 가짜 extra-key 인코딩도 제거. 동일 입력에 대해 노드 해시가 실제 hg와 일치함을 확인(`ChangelogExtraFieldInteropTest`) |
| Manifest 포맷 | wiki `Manifest` | `HgRepository.getManifestRevlog()`, `treewalk.ManifestWalk`, `treewalk.ManifestTreeIterator` | ✅ 평면(flat) 매니페스트 존재. **Treemanifest(`experimental.treemanifest=1`, 디렉터리별 중첩 revlog) 읽기는 ✅ 완료(2026-09-03)** — `ManifestTreeIterator.loadEntries()`에서 `t` 플래그 항목을 재귀적으로 펼쳐 flat map으로 만들어주므로 `LogCommand`/`StatusCommand`/`UpdateCommand` 등 기존 소비자는 수정 없이 그대로 동작. 상세는 백로그 8번. **쓰기(커밋)도 ✅ 완료(2026-09-03)** — `mercurial/manifest.py`의 `treemanifest.writesubtrees`/`dirtext()`를 실측해(자식 디렉터리부터 bottom-up 재귀 기록, `sorted(dirs+files)` 순서 직렬화) `CommitCommand.writeTreeManifestDir`로 구현. `treemanifest` requirement가 `.hg/store/requires`가 아니라 최상위 `.hg/requires`에 있다는 것도 이때 확인. hg4j로 쓴 3단 중첩 저장소를 real hg(Docker `hg-rust-7.2.4`)의 `hg verify`/`hg log`/`hg cat`으로 검증(`TreeManifestWriteTest`). 상세는 백로그 18번 |
| Dirstate v1 | wiki `DirState` | `Dirstate` | ✅ |
| Dirstate v2 (44바이트 노드) | `hg help internals.dirstate-v2` | `DirstateV2Parser`, `DirstateV2Serializer`, `DirstateV2Node` | ✅ **구현 완료(2026-09-01)** — 실제 hg CLI(Docker 6.0 + host native 7.2.2)로 만든 진짜 dirstate-v2 저장소 바이트를 직접 캡처해 대조, **3건의 실제 버그 발견·수정**: (1) NODE 44바이트 구조체 필드 오프셋이 전부 지어낸 값(자기 자신과의 라운드트립만 우연히 통과, 실제 hg가 쓴 파일은 못 읽는 상태), (2) flags 비트 값 오류, (3) 데이터 파일명 패턴이 `dirstate.d.<uid>`가 아니라 실제로는 `dirstate.<uid>`였던 완전 단절 버그. 캡처한 실제 바이트를 그대로 박은 회귀(`DirstateV2RealFixtureTest`) 신설, 기존 `CHgDirstateV2Test`의 설정 순서/requires 누락 버그도 같이 수정 — 상세는 아래 백로그 5번. **4번째 실제 버그를 2026-09-04에 추가로 발견·수정(백로그 37번)** — 위 3건과 별개로, real hg가 이미 커밋해둔 파일이 있는 dirstate-v2 저장소에 hg4j가 새 파일을 추가 커밋하면 기존 파일이 트리 구조에서 유실되는 버그였다(`hg verify`가 "not marked as tracked in p1"로 검출). 근본 원인은 real hg의 Rust dirstate-v2 리더(`dirstate_map.rs`)가 부모 노드의 자식 배열을 basename 오름차순 **이진 탐색**으로 찾는데, `DirstateV2Serializer`가 `LinkedHashMap` 삽입 순서 그대로(정렬 없이) 써서 내림차순이 되면 못 찾던 것 — `hg-rust-7.2.4` 컨테이너 내부의 실제 Rust 소스 직접 대조로 확정, UTF-8 바이트 기준 정렬 로직 추가로 수정. `RequirementMatrixDockerRoundTripTest` 60케이스(30읽기+30쓰기) 전부 GREEN |
| Bundle1 (레거시 `HG10UN/GZ/BZ`) | wiki `BundleFormat` | `api.UnbundleCommand`, `api.FetchCommand`, `api.BundleCommand` | ✅ **완료(2026-09-03)** — 읽기(HG10UN/HG10GZ/HG10BZ 세 압축 형식 전부)는 이미 실제 `hg bundle --type=none-v1/gzip-v1/bzip2-v1`로 만든 파일로 검증돼 있었음(`TrackCMissingCommandsInteropTest`). 진단해보니 `api.BundleCommand`(직전 세션에 이미 신설)는 `none-v1`(`"HG10UN"` + 무압축 cg1)만 만들 수 있었고, 실제 gap은 `--type` 파라미터/gzip·bzip2 압축 부재였다 — `BundleCommand.BundleType`(`NONE_V1`/`GZIP_V1`/`BZIP2_V1`, `setType(BundleType)`/`setType(String)`, `hg`의 `--type` 철자 그대로) 추가로 해결. 바이트 레이아웃은 real hg 7.2.2 CLI 출력을 `xxd`로 직접 대조해 확정: `gzip-v1`은 순수 zlib/DEFLATE(`java.util.zip.Deflater`의 기본 wrapped 모드 — `GZIPOutputStream`이 만드는 gzip 컨테이너가 아님, `mercurial/utils/compression.py`의 `zlib.compressobj()` 및 기존 `UnbundleCommand`의 `InflaterInputStream` 읽기 경로와 대칭), `bzip2-v1`은 4바이트 리터럴 `"HG10"` 뒤에 표준 bzip2 스트림(`BZip2CompressorOutputStream`, 자체 `"BZh9..."` 매직으로 시작)을 그대로 이어붙여 `"HG10BZh9..."`가 되도록 함(6바이트 `"HG10BZ"` 헤더는 리터럴로 쓰지 않음 — `mercurial/bundle2.py`의 `bundletypes["HG10BZ"] = ("HG10", "BZ")`가 정확히 이 레이아웃임을 소스로 확인). 세 압축 방식 모두 real hg round-trip으로 검증(`BundleCommandTest`: hg4j가 쓴 gzip-v1/bzip2-v1 파일을 real `hg unbundle`이 정확히 읽음, real hg가 만든 gzip-v1 파일을 hg4j `UnbundleCommand`가 정확히 읽음) |
| Bundle2 컨테이너 | `hg help internals.bundle2` | `Bundle2Parser` | ✅ **버그 2건 발견·수정(2026-09-01)** — 스트림 파라미터 크기 필드가 2바이트가 아니라 실제로는 4바이트(`_fstreamparamsize='>i'`)였던 버그, 파트 헤더의 파라미터를 키/값 교차로 읽던 게 아니라 실제로는 모든 (keylen,vallen) 쌍을 먼저 읽고 그다음 키/값 바이트를 순서대로 읽는 2단계 구조였던 버그. 둘 다 실제 `hg bundle`(기본 bzip2 압축) 결과물로 발견 |
| Changegroup (cg1~cg5) | `hg help internals.changegroups`, `mercurial/changegroup.py` 실측 | `ChangegroupParser`, `storage.Revlog`, `transport.HgLocalClient`, `transport.HgRemoteClientV2` | ✅ **cg1/cg2/cg3 헤더 구조 버그 발견·수정 완료(2026-09-01)** — (1) cg1 델타 베이스 규칙: 실제 cg1은 `forcedeltaparentprev=True`로 각 엔트리를 "실제 DAG 부모(p1)"가 아니라 "스트림상 바로 직전 엔트리"를 기준으로 델타 인코딩한다(위치 기반, DAG와 무관). `HgLocalClient.getBundle()`이 p1 기준으로 델타를 만들고 있어서 다중 head(branch) 저장소를 pull하면 콘텐츠가 깨지는 실제 버그였음. (2) cg2/cg3 델타 헤더 필드 순서: 실제 구조체는 `node,p1,p2,deltabase,cs`인데 hg4j는 `node,p1,p2,cs,deltabase`로 읽어서 changelog 그룹에서 deltabase가 항상 자기 자신의 node와 같아지는 버그였음(`node`/`cs`가 changelog에서는 같은 값이라 증상이 그렇게 나타남) — 실제 `hg bundle` 결과물의 unbundle 실패로 발견. **cg3의 censor 지원은 ✅ 완료(2026-09-01, 백로그 7번)** — 패킹측 크래시(censored 리비전 포함 저장소를 pull/clone/push하면 무조건 죽던 버그)와 수신측 censored 플래그 소실(censor된 내용이 조용히 복원되는 심각한 버그) 둘 다 발견·수정. **트리매니페스트(treemanifest) 파싱은 읽기 경로만 ✅ 완료(2026-09-03, 백로그 8번)** — cg3의 `ManifestGroup` 파싱 골격 자체는 이미 있었고, 실제 병목은 매니페스트 콘텐츠를 소비하는 `ManifestTreeIterator` 쪽이었다(위 "Manifest 포맷" 행 참고). 쓰기는 백로그 18번. **cg4/cg5는 ✅ 완료(2026-09-03)** — 상세는 백로그 11번. 같은 작업 중 cg3에도 있던 별도의 실제 버그(루트 매니페스트 그룹을 서브디렉터리 경로 청크로 오인하는 버그)와, cg2~cg5 모두에 해당하는 협상 자체가 사실상 항상 무력화돼 있던 버그(bundlecaps 인코딩)도 같이 발견·수정. **hg4j 자체 changegroup 생성/적용 경로(로컬 `HgLocalClient.getBundle()`/`Revlog.appendChangeGroupEntry()`)가 cg1/무sidedata로 고정돼 있던 문제도 ✅ 완료(2026-09-04, 백로그 26번)** — 근본 원인은 `Wire1Commands.capabilitiesString()`이 `bundle2=`를 아예 광고하지 않아 실제 hg 클라이언트의 `remote.capable('bundle2')`가 항상 false가 되고, 그래서 legacy 경로(`bundlecaps` 자체를 안 보냄, 응답도 무조건 cg1 맨 바이트)로만 빠지던 것 — `bundle2=` 광고를 추가하고 `HgLocalClient.getBundle()`이 `bundleCaps`를 읽어 `max(요청 목록 ∩ {01..05})`로 버전을 협상하도록, `Revlog.appendChangeGroupEntry()`가 v2 revlog에는 `appendRevisionV2`(sidedata 포함)로 분기하도록 수정. 부수적으로 `bundle2=` 광고 자체가 실제 hg 클라이언트의 **push** 프로토콜도 자동으로 bundle2로 전환시켜(`exchange._forcebundle1`) HG20 봉투 요청/응답 처리를 새로 배선해야 했음(`Bundle2Parser.buildChangegroupReplyBundle2`류 신설). 전부 real hg CLI 기반으로 검증(`HgHttpWireServerRealHgInteropTest`/`PullSidedataRealHgInteropTest` 신규 케이스), 회귀 2402건 전부 GREEN |
| Wire protocol v1 (HTTP/SSH, capability 협상) | `hg help internals.wireprotocol` | `HgRemoteClient`, `HgSshClient`, `transport.wireprotov1.Wire1Commands`, `HgHttpWireServer`, `HgSshWireServer` | ✅ **양방향 완전 검증 완료(2026-09-01, 서버 방향 및 클라이언트 협상 매트릭스 2026-09-03에 확장)**. 클라이언트 방향: Docker 실제 Mercurial 6.0 `hg serve` HTTP 서버를 대상으로 hg4j `PullCommand`/`PushCommand`가 실시간 pull+push 왕복 성공(`HgHttpV1LiveServerInteropTest`). **서버 방향(실제 hg 클라이언트 → hg4j 서버)도 완료** — 기존 모놀리식 `HgWireServer`(가짜 SSH stdio 핸들러 포함, 실제 검증된 적 없음)를 JGit의 `UploadPack`/`ReceivePack`+전송별 glue 패턴대로 `Wire1Commands`(전송 무관 v1 코어)+`HgHttpWireServer`(HTTP)+`HgSshWireServer`(SSH, real hg SSH 라인 프로토콜 재구현)로 재구성 후 삭제, 실제 hg CLI를 클라이언트로 clone/pull/push/bookmark(HTTP, `HgHttpWireServerRealHgInteropTest`) 및 clone(SSH, 임베디드 Apache MINA SSHD 경유 진짜 SSH 세션, `HgSshWireServerRealHgInteropTest`)까지 전부 검증 — 이 과정에서 SSH 핸드셰이크의 `between` 커맨드가 빈 응답을 내던 진짜 버그(real hg 클라이언트가 영원히 멈춤)도 발견·수정. **백로그 22번 "실제 hg 클라이언트 → hg4j 서버" 그룹(2026-09-03)**: 그때까지 SSH 쪽은 clone 한 가지만 검증돼 있었던 실제 gap을 메움 — real hg **SSH 증분 pull**(`realHgPullsIncrementalChangesFromHg4jServedOverSsh`)과 real hg **SSH push**(`realHgPushesToHg4jServedOverSsh`) 신규 검증 통과(기존에 알려졌던 대로 SSH push 경로 자체는 이미 정상 동작했음 — 새로운 실버그는 못 찾음), 여러 named branch/bookmark/tag가 있는 저장소의 clone 정확성(HTTP+SSH 둘 다), 존재하지 않는 리비전을 `clone -r`/`pull -r`로 요청했을 때 real hg 클라이언트가 "unknown revision"/"abort" 메시지로 정상 종료(크래시·행 없음, HTTP+SSH 둘 다)까지 신규 검증. 추가로 "첫 번째 real hg 클라이언트가 push한 직후 두 번째 real hg 클라이언트가 같은 살아있는 서버에서 즉시 그 커밋을 보는지"(서버 재시작 없이 자체 일관성 유지되는지)도 신규 검증(HTTP+SSH 둘 다 통과). **테스트 작성 중 발견한 함정(버그 아님)**: `HgHttpWireServer`/`HgSshWireServer`에 넘긴 `HgRepository` 객체가 내부적으로 `Revlog` 인스턴스를 캐싱하므로, 서버가 살아있는 동안 그 저장소 디렉터리를 **hg4j API를 거치지 않고** 별도 `hg` CLI 프로세스로 직접 수정하면(이번 세션의 브랜치/북마크/태그 테스트 셋업이 처음에 그렇게 했다가 걸림) 서버가 그 변경을 못 보고 stale 데이터를 서빙한다 — `serverRepo.clearRevlogCache()`를 명시적으로 호출해야 함(기존 push 테스트도 이미 이 패턴을 쓰고 있었음). 이건 wire protocol 자체의 버그가 아니라 "server가 물고 있는 저장소를 외부 프로세스가 몰래 건드리는" 별개의 아키텍처 이슈라 이번 항목 범위 밖으로 두고 그대로 문서화만 함. 이번 확장분 테스트: `HgHttpWireServerRealHgInteropTest`(4→8건), `HgSshWireServerRealHgInteropTest`(1→6건), 전부 GREEN. **백로그 22번 그룹 1/2/4(클라이언트 방향 "실전 통신·협상" 세부 조합) ✅ 완료(2026-09-03)** — HTTP 3단계 인자 전송(`httppostargs`/`httpheader=N`/레거시 GET) 각각을 실제 hg 서버로 개별 강제(레거시 GET은 real hg가 `httpheader=`를 끌 방법이 없어 그 토큰만 투명하게 벗겨내는 신규 `CapabilityStrippingHttpProxy`로 강제), `zlib`/`zstd`/`none` 압축 조합 전부, SSH `unbundlehash` off(신규 `HgSshUnbundleHashOffInteropTest`)까지 전부 real hg 7.2(Docker 불필요, 시스템 hg가 zstd 확장 내장)로 검증 — **새 프로토콜 버그는 못 찾음**(기존 구현이 이미 정확했고 강제 검증만 안 돼 있던 상태였음이 확인됨). 상세는 백로그 22번 항목 참고. **장기 실행 서버의 stale 캐시 문제도 ✅ 완료(2026-09-03, 백로그 24번)** — `HgRepository.refreshIfChangedOnDisk()` 신설(changelog 파일 크기+mtime 비교 후 변경 시 `clearRevlogCache()`), `HgHttpWireServer.handle()`/`HgSshWireServer.handleConnection()` 양쪽에 배선. **이 검증 중 발견했던 "파일 내용이 안 전달되는 것처럼 보이는 현상"은 조사 결과 hg4j 버그가 아니라 real hg clone 자체의 정상 동작(named branch가 있으면 저장소 tip이 아니라 "default" 브랜치 tip만 체크아웃)으로 확인·종결됨 — 상세는 백로그 25번. **SSH push의 checkheads 안전장치도 완료(2026-09-04, 백로그 33번)** — `HgRemoteConnection.getBranchHeads()`가 `HgRemoteClient`(HTTP)만 구현돼 있고 `HgSshClient`는 인터페이스 기본값(`return null`, branch-unaware 폴백)에 의존해서 SSH push 시 다중 head/새 브랜치 거부 안전장치가 무력화돼 있던 버그 — `HgSshClient`에 `getHeads()`/`listKeys()`와 동일한 패턴(`branchmap` 커맨드)으로 신규 구현, HTTP와 대칭되는 신규 interop 테스트로 강제 거부/`--force` 성공 양쪽 검증 |
| Wire protocol v2 (실험적, cbor+프레임 기반) | `hg help internals.wireprotocolv2`, `mercurial/wireprotoframing.py`/`wireprotov2server.py`/`wireprotov2peer.py` 실측(Mercurial 6.0) | `transport.HgRemoteClientV2`, `transport.HgHttpWireServer`, `transport.wireprotov2.*`(`Wire2Frame`/`Wire2Transport`/`Wire2Commands`/`Cbor`) | ✅ **전면 재구현 완료(2026-09-01)** — 이전 구현은 사실상 전부 가짜였다(존재하지도 않는 `/api/<command>` 평면 HTTP+CBOR 스킴, `changegroup`/`getbundle`/`unbundle`이라는 v2에 없는 명령, 실제로는 모든 문자열이 CBOR byte-string인데 text-string으로 인코딩). Mercurial 6.0(v2 서버 코드가 남아있는 마지막 릴리스 — 6.1에서 완전히 제거됨)을 Docker로 직접 띄워 **양방향** 검증: (1) hg4j 클라이언트 → 실제 hg 6.0 서버로 capabilities/heads/known/listkeys/lookup/pushkey/branchmap 및 changesetdata+manifestdata+filesdata로부터 재구성한 전체 clone까지 노드 해시 일치 확인. (2) 실제 hg 6.0 클라이언트(`hg --config experimental.httppeer.advertise-v2=true clone`) → hg4j의 서버(현재 `HgHttpWireServer`, JGit식 재구성으로 옛 `HgWireServer`에서 이관됨)로 완전한 clone 성공 + `hg verify` 통과. 진짜 프레임 프로토콜(8바이트 헤더, capabilities 발견 핸드셰이크, 실제 명령 집합)을 처음부터 구현. **구조적 한계**: 이 프로토콜 자체가 실제 Mercurial에서 6.1부터 완전히 폐기됐다 — 즉 아무리 정확히 구현해도 현재 배포되는 실제 hg 서버 중 이 프로토콜을 쓰는 것은 사실상 없다(README의 "완전 준수" 요건 충족 목적으로는 의미 있으나 실사용 가치는 제한적). **v1→v2 자동 업그레이드는 ✅ 완료(2026-09-01, 백로그 2번)** — 가짜 `"http-v2"` 플래그 매칭을 제거하고 실제 `X-HgUpgrade-1`/`X-HgProto-1` 핸드셰이크로 교체, CBOR discovery 응답이면 `HgRemoteClientV2`로 자동 위임·아니면 평문 v1 폴백. **추가로 발견·수정한 버그(JGit 재구성 세션, 이후)**: 이 자동 업그레이드 시에도 discovery 응답에 실려오는 `v1capabilities` 필드(`clonebundles` 같은 v2에 없는 v1 전용 토큰)를 클라이언트가 실제로는 파싱하지 않고 버리고 있었음 — 파싱하도록 수정. **`getBundle()`의 재귀적 tree fetch도 ✅ 완료(2026-09-03, 백로그 20번)** — 루트 매니페스트에서 `t` 플래그 서브디렉터리 포인터를 발견하면 BFS로 `manifestdata`를 `tree=<dir>`로 재귀 호출해 깊이 중첩된 포인터까지 전부 수집. 이 과정에서 hg4j 자체 wire2 **서버** 측(`Wire2Commands.manifestdata`)도 비어있지 않은 `tree` 인자를 무조건 거부하던 실제 갭을 발견해 대칭적으로 수정. Mercurial 6.1부터 폐기된 프로토콜이라 real hg 서버로는 검증 못 하고 hg4j↔hg4j 자기 일관성 왕복(`Wire2TreeManifestFetchTest`)으로 검증. 상세: [[wireprotocol-v2-support-plan]] |
| Phases (draft/public/secret) | `hg help phases` | `PhaseRoots`, `api.PhaseCommand` | ✅ |
| Bookmarks (이동 가능한 포인터, named branch와 구별) | `hg help bookmarks`, `mercurial/bookmarks.py`(comparebookmarks/validdest 실측) | `api.BookmarkCommand`, `api.CommitCommand`, `api.UpdateCommand`, `api.FetchCommand` | ✅ **구현 완료(2026-09-01)** — commit 자동 전진/update 활성화·비활성화/pull·push 동기화 전부 구현, 실제 hg CLI로 fast-forward·진짜 divergence·원격 push/pull까지 검증(`BookmarkRealHgInteropTest`). 검증 중 데이터 손실 버그 2건 발견·수정: (1) pull 시 ancestor 관계를 안 따져서 로컬의 독자적 bookmark 이동이 조용히 덮어써지던 버그, (2) 새 changeset 없이 bookmark만 이동한 원격을 pull하면 동기화 자체가 생략되던 버그. 상세: [[bookmark-full-support-plan]] |
| Obsolescence markers | `mercurial/obsolete.py`(FM1 포맷 실측, 실제 obsstore 픽스처로 검증) | `HgObsolescenceParser`, `HgObsMarker`, `api.AmendCommand`/`RebaseCommand`/`GraftCommand`/`HisteditCommand`/`StripCommand` | ✅ **구현 완료(2026-09-01)** — 5개 명령 전부 마커 생성 확인. **완료 과정에서 obsstore 바이너리 포맷 자체가 완전히 틀렸던 것을 발견** — 파일 버전 바이트 부재, 필드 순서·크기 전부 불일치. 실제 FM1(version=1) 스펙대로 전면 재작성, 실제 hg가 만든 obsstore를 hg4j로 파싱 + hg4j가 쓴 obsstore를 실제 `hg debugobsolete`로 읽기 — 양방향 검증 통과(`HgObsolescenceRealHgInteropTest`). 상세: [[obsolescence-marker-completeness-plan]] |
| Censor (민감정보 삭제) | `hg help internals.censor` | `Revlog.censorRevision`/`isCensoredText`, `api.CensorCommand` | ✅ **구현 완료(2026-09-01)** — 실제 hg의 `v1_censor` 방식대로 대상 리비전을 tombstone 콘텐츠+`REVIDX_ISCENSORED` 플래그로 재작성, 포셀린 `CensorCommand` 신설, 읽기 시 `HgCensoredContentException`. changegroup 전송 경로(cg3)의 censor 지원도 완료 — 패킹측 크래시와 수신측 플래그 소실 버그 2건 발견·수정(위 Changegroup 행 및 아래 백로그 6/7번 참고). Docker Mercurial 6.0의 실제 censor 확장 산출물과 바이트 단위 대조 + 실제 hg 양방향 interop 검증(`CensorRealHgInteropTest`, `CensorChangegroupTransferTest`) |
| Narrow clone / narrowspec | wiki 관련 문서, `mercurial/narrowspec.py`(hg 7.2 실측) | `NarrowCloneCommand`, `HgTreeFilter` | ✅ **재검증 및 실제 버그 발견·수정(2026-09-04, 백로그 28번)** — 실제 hg 7.2 CLI(`narrow` 확장, `--config extensions.narrow=`로 활성화 필요함을 실측 확인)와 대조해보니 narrowspec 파일 위치(`.hg/narrowspec`이 아니라 `.hg/store/narrowspec`+`.hg/narrowspec.dirstate`), `.hg/requires` 키(`"narrowspec"`이 아니라 `"narrowhg-experimental"`), 파일 포맷(`[includes]`/`[excludes]` 복수형이 아니라 `[include]`/`[exclude]` 단수형 + `path:`/`rootfilesin:` 정규화)이 전부 실제 hg와 달랐고, `HgTreeFilter`의 include 매칭이 단순 `String#startsWith`라 `srcdir`가 형제 디렉터리 `srcdirextra/`까지 잘못 포함시키는 실제 버그였음(경로 컴포넌트 경계 미고려)도 발견. 전부 TDD로 수정 — `HgTreeFilter`에 `NarrowPattern`/`normalizeNarrowPattern()`/`createNarrowSpecFilter()` 신설(실제 hg의 `_validatepattern`/컴포넌트 경계 매칭/`rootfilesin:` 직계-자식-only 규칙 재현, include 없으면 전부 거부), `NarrowCloneCommand`가 이를 쓰도록 재작성. hg4j가 만든 narrow clone을 실제 hg CLI(`hg tracked`/`hg files`/`hg status`)로 열어 완전히 인식함을, 그리고 실제 hg가 만든 narrowspec을 hg4j 매처로 파싱했을 때 실제 hg의 체크아웃 결과와 판정이 정확히 일치함을 양방향 검증(`NarrowCloneRealHgInteropTest`, 3건). **로컬 pull/update 재통합도 완료(2026-09-04, 백로그 30번)** — `HgTreeFilter.loadFromRepository()` 신설로 `.hg/store/narrowspec`을 pull/update 시점에 다시 읽어 필터를 재구성, `FetchCommand`/`UpdateCommand`가 명시적 필터가 없으면 자동 로드하도록 배선. narrow clone 이후 plain pull을 해도 범위 밖 파일이 워킹 디렉터리에 안 나타남을 real hg CLI 대조로 검증(`NarrowCloneRealHgInteropTest`). 이 작업 중 **사전 존재하던 별개의 실제 버그도 발견·수정** — `NarrowCloneCommand.call()`이 pull 직후 캐시 무효화 없이 바로 update를 호출해서 manifest `Revlog`가 stale 상태로 읽혀 `HgCorruptDataException`을 던지던 버그(pristine `origin/main`에서도 100% 재현 확인, **모든 narrow clone 사용자에게 영향**), `repository.clearRevlogCache()` 한 줄로 수정. **여전히 범위 밖(정직하게 기록, 백로그 40번으로 승격)**: 이건 로컬에서 전체 changegroup을 받은 뒤 걸러내는 방식일 뿐, 진짜 wire-protocol 수준의 narrow pull(서버가 애초에 범위를 좁혀 전송하는 ellipsis node 협상)은 여전히 없어 narrow clone의 원래 목적(대역폭/저장 절감)은 달성 못함 |
| Sparse checkout | `mercurial/sparse.py`(`parseconfig`/`patternsforrev` 실측) | `treewalk.SparseConfig`, `treewalk.SparsePathFilter` | ✅ **구현 완료(2026-09-01)** — `.hg/sparse` 파일 파싱(`[include]`/`[exclude]`/`%include` 프로파일 참조, 앞자리 `/` 거부, 섹션 밖 항목 에러 등 실제 hg의 검증 규칙까지 재현) 및 `%include`로 참조된 프로파일을 해당 리비전의 매니페스트에서 읽어 재귀적으로 병합하는 `patternsforrev` 로직 신규 구현. `.hg*` 자동 include 규칙 포함. 실제 hg CLI로 만든 `.hg/sparse` 픽스처와 대조 검증(`SparseConfigInteropTest`) |
| LFS (largefiles) | 관련 확장 문서, `hgext/lfs/blobstore.py`(hg 7.2 실측) | `HgLfsManager`, `HgLfsPointer`, `CommitCommand`, `UpdateCommand` | ✅ **커밋/체크아웃 파이프라인 연동 완료(2026-09-04, 백로그 31번)** — 로컬 저장소 포맷 버그(Git-LFS 스타일 2단계 샤딩 → real hg 1단계 샤딩, 2026-09-04 백로그 28번에서 발견·수정)에 이어, `CommitCommand`가 `.hgrc`의 `[lfs] threshold`를 넘는 파일을 LFS 포인터로 치환해 커밋(`.hg/requires`에 `lfs` 지연 추가)하고 `UpdateCommand`가 `REVIDX_EXTSTORED` 리비전 체크아웃 시 포인터→실제 바이트로 복원(로컬 캐시 우선, 없으면 원격 fetch)하도록 신규 구현. **핵심 실버그 발견·수정**: LFS 리비전의 filelog 노드 해시는 저장된 포인터 텍스트가 아니라 **real hg의 flag processor가 돌려주는 실제 파일 콘텐츠**로 계산돼야 한다는 것을 real hg CLI로 직접 재현해 확정 — 이를 놓치면 hg4j가 만든 LFS 커밋을 real hg가 `abort: integrity check failed`로 거부함(발견 못 했으면 사실상 실사용 불가능). `Revlog.appendRevision`에 `hashBasisOverride` 파라미터를 신설해 "저장 바이트"와 "해시 기준 바이트"를 분리해 수정. `LfsRealHgInteropTest` 확장 검증. **범위 밖(정직하게 기록)**: rename+LFS 동시 파일의 copy-tracing 메타데이터, `[lfs] url` override, `lfs.disableusercache` 등 세부 옵션 |
| Subrepositories (`.hgsub`/`.hgsubstate`) | `mercurial/subrepo.py`/`subrepoutil.py`(`hgsubrepo.dirty`/`basestate`/`get`, `precommitstate`, `writestate` 실측) | `HgSubrepoParser`, `HgSubrepoEntry`, `api.SubrepoCommand`, `api.CommitCommand`, `api.UpdateCommand`, `lib.HgRepository.scanWorkingCopy()` | ✅ **재검증 및 실제 버그 2건 발견·수정(2026-09-04)** — 백로그 23번(subrepo 카테고리). 기존 "✅"는 근거 없이 클래스 존재만으로 체크된 것이었음이 확인됨: 실제 hg CLI와 대조해보니 (1) `CommitCommand`에 subrepo 인식 로직이 전혀 없어서, 서브저장소가 낀 상태로 커밋해도 `.hgsubstate`가 자동 갱신되지 않고(실제 hg는 `.hgsub`만 tracked면 `.hgsubstate`를 hg add 없이 자동 생성·추적하고 서브저장소의 현재 체크아웃 리비전으로 매 커밋마다 갱신함) dirty한 서브저장소가 있어도 부모 커밋이 그냥 성공해버렸음(실제 hg는 `uncommitted changes in subrepository "..."` 로 거부하고 `-S`/`--subrepos`가 있어야 재귀 커밋을 허용). (2) `HgRepository.scanWorkingCopy()`(`AddCommand`/`StatusCommand`/`CommitCommand`의 워킹카피 스캔이 전부 공유)가 서브저장소 경계를 전혀 인식하지 못해, 체크아웃된 서브저장소 디렉터리 내부 파일들이 **부모 저장소 자신의 추적 파일**로 잘못 add/commit되는 심각한 버그였음(real hg는 `.hgsub`가 선언한 경로를 walk 자체를 안 함). 둘 다 TDD로 수정: `CommitCommand.applySubrepoStateBeforeCommit()`(dirty-check→abort/재귀커밋, `.hgsubstate` 자동 생성·정렬·추적, `setSubrepos(true)`로 `-S` 상당 기능 추가) 신설, `scanWorkingCopy()`에 `.hgsub` 선언 경로를 opaque 경계로 처리하는 로직 추가. **4개 시나리오 전부 실제 hg 7.2 CLI 양방향 대조로 검증**(`SubrepoRealHgInteropTest`): ① 실제 hg가 만든 `.hgsub`/`.hgsubstate`를 `HgSubrepoParser`가 정확히 파싱, ② hg4j `CommitCommand`가 만든 `.hgsubstate`를 실제 hg CLI(`hg status`/`hg files`/`hg cat`/`hg log`)가 정확히 인식, ③ 서브저장소 리비전이 바뀐 뒤 dirty-check 거부→재귀 커밋으로 `.hgsubstate` 갱신까지 실제 hg와 동일한 abort 메시지/동작으로 일치, ④ 실제 hg로 두 개의 서로 다른 서브 리비전을 pin한 부모 커밋 두 개를 만들고 hg4j `UpdateCommand`로 그 사이를 오가며 서브저장소 워킹카피 내용이 일치. **서브저장소가 로컬에 체크아웃돼 있지 않은 채 부모를 커밋할 때의 처리 (2026-09-04 사용자 결정 및 구현 완료)**: 서브저장소가 로컬에 체크아웃돼 있지 않은 채 부모를 커밋하면 실제 hg는 그 경로에 빈 저장소를 자동 생성하고 `.hgsubstate`를 null 리비전(`0000000000000000000000000000000000000000`)으로 덮어쓴다(실측 확인). 최초 구현 당시에는 이를 그대로 재현하면 기존 hg4j 워크플로우(`.hgsub`+`.hgsubstate`를 먼저 손으로 써서 커밋한 뒤 `UpdateCommand`가 나중에 clone하는 패턴 — 기존 `HgSubrepoTest`/`UpdateCommandTest`가 이 패턴에 의존)가 깨진다는 이유로 담당 에이전트가 임의로 기존 기록을 그대로 보존하는 쪽으로 발산시켰었음. → **결정(2026-09-04, AskUserQuestion으로 사용자 확인 완료): real hg와 동일하게 null 리비전으로 리셋하는 쪽으로 확정, 구현 및 회귀 그린 완료.** `CommitCommand.applySubrepoStateBeforeCommit()`에서 서브저장소 경로가 로컬에 체크아웃돼 있지 않을 때 기존 `.hgsubstate` 레코드를 보존하던 분기(`priorState` 조회)를 제거하고 `NodeId.NULL.toHex()`로 덮어쓰도록 변경했다. 이로 인해 깨지는 기존 테스트들은 모두 "서브저장소를 체크아웃하기 전에 손으로 `.hgsub`+`.hgsubstate`를 써서 커밋"하는, 실제 hg라면 애초에 non-null 리비전을 기록해 주지 않는 비현실적 전제에 기대고 있었던 것으로 확인됨 — 각각 원래 검증 의도를 유지하며 다음과 같이 수정: `HgSubrepoTest#testSubrepoRecursiveCheckoutDuringUpdate`와 `UpdateCommandTest#updateRecursivelyChecksOutSubrepositoriesDeclaredInHgsub`는 커밋 전에 실제로 서브저장소를 clone/체크아웃해 두도록 순서를 바꿔 "재귀 체크아웃 검증"이라는 원래 목적을 그대로 유지(체크아웃 후 삭제 → update로 재생성되는지 확인하는 흐름은 그대로); `UpdateCommandCoverageTest#updateSkipsSubrepoCheckoutWhenOnlyHgsubIsPresentWithoutHgsubstate`는 `.hgsub`를 커밋하지 않고 워킹 디렉터리에만 둔 채(untracked) `UpdateCommand`의 "`.hgsub`/`.hgsubstate` 둘 다 있어야 함" 가드를 독립적으로 검증하도록 조정(커밋을 거치면 `.hgsubstate`가 항상 자동 합성되어 "파일 자체가 없는" 시나리오를 더 이상 재현할 수 없어짐); `subrepoCheckoutSkipsGitEntriesAndHandlesUnrecordedRevisionAndSource`/`subrepoPullFailureIsLoggedAndDoesNotAbortTheOverallUpdate`는 어서션이 그대로 통과함을 확인하고 주석만 새 동작(리비전이 이제 빈 문자열이 아니라 null 리비전 문자열이 됨)에 맞게 정정. `SubrepoRealHgInteropTest`에 신규 시나리오(`hg4jCommitResetsNotCheckedOutSubrepoToNullRevisionMatchingRealHg`)를 추가해, 동일 시나리오를 실제 hg CLI로 나란히 재현한 오라클과 hg4j 결과의 `.hgsubstate` 바이트가 정확히 일치함(byte-for-byte)과 실제 hg CLI로 hg4j 결과 저장소를 읽어도(`hg status`/`hg cat`) 동일한 null 리비전 엔트리로 인식됨을 양방향 검증. 전체 회귀 그린 확인(신규 실패 없음). **범위 밖으로 남은 것(시간 부족, 정직하게 명시)**: `CloneCommand`는 서브저장소를 재귀적으로 clone하지 않음(실제 hg `hg clone`은 자동으로 재귀 clone함) — 이번 항목이 명시한 범위는 "commit/update"뿐이라 미착수. `.hgsub`가 제거됐을 때 `.hgsubstate`를 자동 정리하는 로직, git 서브저장소(`[git]` prefix)의 커밋측 상태 갱신도 미구현(기존과 동일하게 스킵). `UpdateCommand`의 재귀 서브저장소 체크아웃은 매번 무조건 `pull`을 시도(실제 hg는 리비전이 로컬에 이미 있으면 네트워크 요청을 안 함) — 실패해도 로그만 남기고 넘어가 기능적으로는 무해하지만 실제 hg와 동작이 다름, 미수정. 전체 회귀 2362건 전부 GREEN(신규 실패 없음) |
| Config 파일 포맷 (`hgrc`, include/`%include`, 섹션) | `hg help internals.config`, `mercurial/config.py`(`parse` 실측) | `HgRcConfig` | ✅ **구현 완료(2026-09-01)** — `%include <path>`(포함 파일의 디렉터리 기준 상대 경로 해석, 없는 파일은 조용히 무시), `%unset <key>`(현재 시점까지 설정된 값 완전 제거), 들여쓰기 연속 줄 지원을 실제 `mercurial/config.py` 소스대로 구현. 실제 `hg config` 명령 출력과 대조 검증(`HgRcConfigTest#testIncludeAndUnsetMatchRealHg`) |
| Merge state 영속화 (재개 가능한 머지) | `hg help internals.mergestate`, `mercurial/mergestate.py`(`_readrecordsv2`/`_writerecordsv2` 실측) | `merge.MergeState`(`.hg/merge/state2`), `api.MergeCommand`, `api.ResolveCommand` | ✅ **완료(2026-09-01)** — 실제 hg의 `state2` 바이너리 포맷(타입 1바이트+길이 4바이트 프레임, `L`/`O`/`F` 레코드, 비허용 타입은 `t` 오버라이드로 래핑)을 그대로 구현한 `MergeState` 클래스, `MergeCommand`가 충돌 시 실제로 `state2`를 쓰도록 연결 — 양방향 검증(hg4j가 실제 hg의 충돌 상태를 읽고, 실제 hg의 `resolve --list`가 hg4j가 쓴 상태를 읽음, `MergeStateInteropTest`). `ResolveCommand`도 레거시 v1에서 `MergeState`(state2) 기반으로 전면 재작성해 list/markResolved/markUnresolved를 실제 hg와 양방향 interop까지 검증 완료(백로그 1번) |
| 트랜잭션 저널링 / 크래시 복구 (`recover`, `rollback`) | `hg help internals.transaction`(트랜잭션/저널 파일 포맷) | `api.CommitCommand`, `api.FetchCommand`, `api.RebaseCommand`, `api.RollbackCommand`, `api.HisteditCommand`, `lib.HgRepository.checkAndPerformAutoRollback()` | ✅ **구현 완료(2026-09-01)** — 크래시 자동복구(commit/fetch/pull/rebase/amend/graft/remove/rename/merge/strip/**histedit** 경로 전부 커버)와 `RollbackCommand` 둘 다 실제 hg CLI로 검증(`RollbackRealHgInteropTest`). **완료 과정에서 발견한 실제 갭**: `FetchCommand`가 undo 정보를 안 남겨서 pull 직후에는 rollback이 전혀 동작하지 않았음(가장 흔한 실사용 시나리오) — 수정 완료. `histedit`도 journal 미적용이었으나 TDD로 수정 완료. 상세: [[journaling-crash-recovery-plan]] |
| 누락된 코어 포셀린 명령 (`forget`, `backout`, `addremove`, `verify`, `paths`, `summary`, `tip`, `root`, `parents`, `unbundle`) | `hg help <command>` 각각 | `api.ForgetCommand`, `api.BackoutCommand`, `api.AddremoveCommand`, `api.VerifyCommand`, `api.SummaryCommand`, `api.TipCommand`, `api.RootCommand`, `api.ParentsCommand`, `api.UnbundleCommand` (모두 신규) | ✅ **구현 완료(2026-09-01)** — 9개 명령 전부 신규 클래스+`Hg` 파사드 메서드로 추가, 각각 실제 hg CLI와 대조 검증(`TrackCMissingCommandsInteropTest`, `SummaryCommandInteropTest`). `VerifyCommand`는 기존에 Javadoc만 있고 filelog 검사가 실제로 빠져 있던 것도 이 참에 채움. `paths` 자체는 별도 명령 클래스 없이 `HgRcConfig.getPath()` 방식 유지하되, `PullCommand`/`PushCommand`가 인자 없을 때 `paths.default`/`paths.default-push`로 폴백하고 URL이 아닌 문자열은 `[paths]` 별칭으로 해석하도록 연결 완료(`hg pull upstream` 같은 이름 지정 pull도 동작). **주의**: 이 행은 2026-09-01 당시 확인된 9개 한정이다 — 2026-09-02 `Hg` 파사드 전수 재대조에서 별개의 새 갭(파사드 미배선 4건 + 대응 클래스 자체가 없는 명령 다수)이 추가로 발견됐다 — 백로그 12번 참고 |
| Python 확장(extensions) 시스템 | `hg help internals.extensions` | 해당 없음 | 🚫 **범위 밖 확정** (2026-08-31) — "완전 준수" 요건에서 명시적으로 제외. Java 라이브러리이므로 Python 플러그인 API 자체를 이식하지 않으며, 대신 `HgHook`/`ProcessHook`으로 외부 프로세스 훅만 지원 |

## 검증 방법론 제안 (2026-08-31 초안 — 아래 각 항목 현재 상태 주석 참고)
> 이 섹션은 최초 계획 단계(2026-08-31)에 쓰인 것이라 원문을 그대로 남기되, 각 항목에
> 2026-09-04 기준 실제 진행 상태를 덧붙였다.
1. **실제 hg CLI 라운드트립 픽스처 확대**: `src/test/resources/fixtures/*.bundle`은 현재
   3종(`simple-3commits`, `branch-and-merge`, `large-path-dh`) 뿐 — censor, narrow, LFS,
   obsolescence marker가 포함된 실제 hg 생성 데이터를 추가로 확보해 라운드트립 테스트를
   짜야 실질적 검증이 된다.
   **2026-09-04 상태**: censor(`CensorRealHgInteropTest`)와 obsolescence marker
   (`HgObsolescenceRealHgInteropTest`)는 ✅ 완료(2026-09-01, 위 gap table 참고).
   **narrow와 LFS도 ✅ 완료(2026-09-04, 백로그 28번)** — `NarrowCloneRealHgInteropTest`/
   `LfsRealHgInteropTest` 신규로 real hg CLI 대조 검증 추가, 검증 과정에서 narrowspec
   파일 위치/포맷 불일치와 LFS 블롭 샤딩 방식 불일치(Git-LFS 2단계 vs 실제 hg 1단계)
   실제 버그 2건도 발견·수정. 상세는 위 gap table `Narrow clone / narrowspec`/`LFS` 행.
2. **버전 고정 재검증 루틴**: README가 못박은 "SCM v7.2.2" 기준으로, 이 버전의 `hg help
   internals.*` 전문을 한 번 스냅샷해서 [[sources]]에 원문 요약을 남기는 것을 권장.
   **2026-09-04 상태**: 여전히 미착수(문서화 방법론 제안일 뿐 구체적 버그 추적 대상은
   아님 — 백로그 번호 없이 남겨둠).
3. **`?` 표시 항목부터 우선순위화**: 이 표에서 "확인 필요"로 남은 항목이 "미구현" 확정 항목보다
   실제 상호운용성 버그를 낼 위험이 크다. **2026-09-04 상태**: 이 문서의 gap table 자체는
   이제 "확인 필요(`?`)" 표기가 남은 행이 없다(전부 ✅/⚠️/🚫로 정리됨) — 이 제안은
   사실상 달성됐다.

## 결정된 사항 (2026-08-31, 사용자 확정 답변 — 전부 이후 실행 완료)
1. **Python 확장(extensions) 시스템은 "완전 준수" 범위에서 수용하지 않는다.** ✅ 그대로
   유지 중(범위 밖 확정, 변경 없음).
2. **Wire protocol v2는 무조건 지원해야 한다.** ✅ 완료(2026-09-01) — 상세는 위 gap table
   "Wire protocol v2" 행.
3. **Revlog v2는 무조건 지원해야 한다.** ✅ 완료(changelog-v2는 2026-09-01, 일반 v2+
   fileindex-v1은 2026-09-02) — 상세는 위 gap table의 두 "Revlog v2" 행.
4. **Bookmark는 완전히 지원해야 한다 (Track B-3).** ✅ 완료(2026-09-01) — 상세는 위 gap
   table "Bookmarks" 행, [[bookmark-full-support-plan]].
5. **트랜잭션 저널링/크래시 복구를 지원해야 한다 (Track B-4).** ✅ 완료(2026-09-01) —
   상세는 위 gap table "트랜잭션 저널링" 행, [[journaling-crash-recovery-plan]].
6. **Obsolescence marker 생성을 history-rewriting 명령 전체로 확장해야 한다 (Track B-5).**
   ✅ 완료(2026-09-01) — 상세는 위 gap table "Obsolescence markers" 행,
   [[obsolescence-marker-completeness-plan]].

## 위 결정이 실행 계획에 미치는 영향 (2026-08-31 작성 — 전부 이후 처리됨)
- **의존성 추가**: wireprotocol v2의 cbor 인코딩 — ✅ 처리됨(`transport.wireprotov2.Cbor`
  신설, 2026-09-01 전면 재구현에 포함).
- **`Revlog` 클래스 확장**: v1 전용 설계를 v2까지 확장 — ✅ 처리됨(`Revlog`/`RevlogIndex`가
  이제 v1/changelog-v2/일반 v2 전부 처리, 상세는 [[revlog-v2-support-plan]]).
- **wireprotocol v2용 신규 클래스**: `transport.wireprotov2.*`(`Wire2Frame`/
  `Wire2Transport`/`Wire2Commands`/`Cbor`) — ✅ 신설됨(2026-09-01).
- **문서**: `decisions/revlog-v2-support-plan.md`, `decisions/wireprotocol-v2-support-plan.md`
  — ✅ 둘 다 생성됨.

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
   클라이언트 → hg4j 서버 방향(v1)은 이 시점엔 미검증이었으나, 이후 JGit식 재구성
   (`HgWireServer`를 `HgHttpWireServer`/`HgSshWireServer`로 교체)과 백로그 22/24번을
   거치며 완료됨 — 상세는 위 gap table "Wire protocol v1" 행 참고.
4. ~~**Revlog v2 일반(`exp-revlogv2.2`, 매니페스트/파일로그) + persistent-nodemap**~~
   — ✅ **완료(2026-09-02, persistent-nodemap 읽기 가속은 2026-09-03에 추가 완료 —
   백로그 15번 참고)**.
   이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 포맷의 저장소 자체를 생성하지
   못해(`abort: accessing ... without associated fast implementation`) 막혀
   있었는데, `docker/hg-rust-7.2.4/Dockerfile`로 Rust 확장이 활성화된 실제
   Mercurial 7.2.4를 직접 빌드해(pip sdist + `setup.py --rust build_ext --inplace`
   + `HGMODULEPOLICY=rust+c`) 이 문제를 해결하고 진행했다. 구현하며 알게 된 것:
   - hg4j의 기존 `RevlogIndex`/`Revlog` **읽기** 경로는 이미 general v2를 올바르게
     지원하고 있었다(코드 주석은 "changelog-v2만 지원"이라고 돼 있었지만 stale) —
     changelog-v2와 general v2는 docket 헤더(59바이트 `S_HEADER`)와
     `{radix}-{uuid}` 컴패니언 파일 규약은 동일하고, 96바이트 index 레코드
     레이아웃만 다르다(general v2는 baseRev/linkRev/parent1/parent2를 전부
     명시적으로 저장, node@32, rank 없음; changelog-v2는 baseRev/linkRev를
     저장 안 하고 rev 값으로 합성, node@24, rank 있음).
   - **쓰기** 경로는 실제로 미구현이었다 — `Revlog.appendRevisionV2`가
     `index.isChangelogV2()`로 분기해 general v2도 처리하도록 확장(항상
     `COMP_MODE_PLAIN` 풀텍스트로 쓰고 델타는 안 함 — 스펙상 유효하나 실제 hg보다
     덜 효율적), `RevlogIndex`에 브랜드 뉴(한 번도 존재한 적 없는) revlog가
     저장소 요구사항상 v2여야 할 때 처음부터 v2 docket으로 초기화하는 생성자
     추가.
   - **`fileindex-v1`**은 원래 백로그 문서에 전혀 없던, `exp-revlogv2.2` 저장소가
     fncache 대신 쓰는 완전히 새로운 바이너리 포맷(방사 트라이, docket +
     list/meta/tree 3개 컴패니언 파일)이었다 — 발견 후 사용자에게 범위 확장을
     알리고 "읽기+쓰기 전체 구현" 승인을 받아 진행. 이 개발 환경에 Rust 없는
     Mercurial의 소스 트리(`/usr/lib/python3/dist-packages/mercurial/store_utils/
     file_index_util.py`)가 pure-Python 참조 구현으로 존재해 그것을 직접 포트했다
     (`FileIndex.java`). 다만 쓰기 전략은 실제 hg의 `MutableTree`가 쓰는 증분
     copy-on-write append(기존 트리 파일 뒤에 이어붙이고 주기적으로만 "vacuum"으로
     전체 재빌드) 대신, **매번 전체를 새 UUID로 재빌드하는 단순화된 전략**을 쓴다
     — 이는 실제 hg 자신의 vacuum 결과와 바이트 단위로 동일한 형태이므로 스펙상
     완전히 유효하지만, 커밋을 거듭할수록 실제 hg보다 디스크 낭비가 크다(첫 구현
     범위에서는 정확성을 우선, 최적화는 후속 과제).
   - 검증: `RevlogV2GeneralParserTest`(6 tests, 실제 fixture 바이트 기준
     docket/index 파싱) + `FileIndexTest`(8 tests, 실제 fixture 읽기 +
     hg4j 자체 왕복 + snapshot/restore 롤백) + 수동 Docker round-trip(hg4j
     `CommitCommand`로 기존 파일 수정 커밋과 **브랜드 뉴 파일** 커밋 둘 다 실행 후
     같은 저장소를 `hg-rust-7.2.4` 컨테이너의 `hg verify`/`hg log --debug`/
     `hg cat`/`hg files`로 전부 경고 없이 통과 확인 — fileindex 연동 전에는
     `hg verify`가 "uses revlog format 1; expected 57005" +
     "not in file index!" 두 경고를 냈으나 연동 후 둘 다 사라짐).
   - 픽스처: `src/test/resources/fixtures/revlogv2-general/`
     (`README.md`에 노드 해시/UUID/정확한 바이트 상세 기록).
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
8. ~~**트리매니페스트(`treemanifest`) 읽기 지원** — 조사 결과 미구현으로 확정(단순
   "미검증"이 아니라 관련 파싱 로직 자체가 없었음).~~ — ✅ **읽기 경로 완료(2026-09-03)**.
   Docker Mercurial 6.0(Rust 확장 없음 — treemanifest에 불필요, `hg --version`/
   `hg debuginstall`로 정상 동작 확인)으로 `experimental.treemanifest=1` 저장소를
   만들어(`a.txt`, `sub/b.txt`, `sub/deep/c.txt`, 이후 `sub2/d.txt` 3커밋, 2단계 중첩)
   `.hg/store/00manifest.i`(루트)와 각 `.hg/store/meta/<dir>/00manifest.i`(서브디렉터리)의
   실제 바이트를 `hg debugdata -m`/`hg debugindex --debug -m`(`--dir <path>`)로 직접
   대조. **실측 결과**: 매니페스트 콘텐츠 포맷은 파일/디렉터리 항목이 완전히 동일하다 —
   `<path>\0<40자 hex 노드ID><flag>\n` 한 줄, `flag`는 `''`/`x`/`l`/`t` 중 하나(구분자 없이
   노드ID 바로 뒤에 붙음, `mercurial/manifest.py`의 `_manifestflags`/`treemanifest.parse()`와
   일치). `t` 플래그가 붙은 항목의 노드ID는 파일 콘텐츠가 아니라
   `meta/<누적경로>/00manifest.i`(`manifestrevlog.dirlog()`의 `radix = "meta/" + tree +
   "00manifest"`와 동일 규칙)에 있는 서브매니페스트 revlog 리비전을 가리키고, 그 리비전의
   콘텐츠 안 경로는 **그 서브디렉터리 기준 상대경로**다(`treemanifest._subpath()`가
   호출부에서 접두사를 붙이는 구조) — 그래서 재귀 펼침 시 누적된 디렉터리 접두사를
   직접 복원해야 한다. `ManifestTreeIterator.loadEntries()`가 이 로직의 유일한 병목점임을
   확인(`ManifestWalk`/`getManifestAtCommit()`을 포함해 `StatusCommand`/`UpdateCommand`가
   직접 쓰는 것도 결국 다 이 클래스를 거침) — `parseManifestContent()`(순수 라인 파싱
   함수, 기존 동작 그대로 유지 + `Entry.isTreeDir()` 추가)와 새로 추가한
   `expandTree()`/`readSubManifestContent()`(재귀적으로 `meta/<dir>/00manifest.i`를 열어
   펼침)로 구현. 이 지점 하나만 고치는 것으로 `LogCommand`/`StatusCommand`/`UpdateCommand`
   등 매니페스트를 소비하는 기존 명령 전부가 수정 없이 flat 매니페스트와 동일하게
   동작한다. 픽스처는 `src/test/resources/fixtures/treemanifest/`(README.md에 생성 명령·
   전체 노드 해시·raw 매니페스트 바이트 기록), TDD는
   `ManifestTreeIteratorCoverageTest`(순수 파싱 레벨 `t` 플래그 인식 2건) +
   `TreemanifestRealFixtureTest`(`getManifestAtCommit()`/`ManifestWalk`/`LogCommand`
   레벨에서 3커밋 전부 재귀 펼침이 실제 hg `hg manifest --debug` 기준값과 정확히
   일치함을 확인, 디렉터리 포인터 항목이 flat 결과에 전혀 남지 않음도 검증). 전체
   회귀 클린(217개 테스트 클래스 전부 failures=0 errors=0).

   **범위 밖으로 명시적으로 남긴 것**: treemanifest **쓰기**(생성/커밋)는 이번에 다루지
   않았다 — 백로그 제목대로 읽기만 구현. `HgRemoteClientV2.getBundle()`이 wireprotocol v2
   서버에 항상 `"tree": ""`(루트 매니페스트)만 요청하고 서브디렉터리별 `tree=<dir>` 재귀
   fetch를 하지 않는 문제(2026-09-02 확인)도 사용자 지시로 그대로 남겨뒀다 — 이건 원격
   wireprotocol v2 경로 전용 문제이고(hg4j 자체 저장소는 전부 flat이라 무관, 로컬 저장소를
   직접 여는 이번 구현과는 별개), wireprotocol v2는 실제 hg 6.1부터 제거된 프로토콜이라
   실사용 노출면이 좁다.
9. ~~**Clonebundles (대용량 클론 오프로딩) — 아예 미구현.**~~ — ✅ **완료(2026-09-01)**.
   클라이언트(발견·매니페스트 파싱·다운로드·적용·`FetchCommand`/`CloneCommand` 자동
   배선)와 **서버 측(`Wire1Commands`의 조건부 capability 광고 + `?cmd=clonebundles`
   핸들러)까지 전부 구현·검증 완료** — 처음엔 서버 측(8~9번)을 사용자 요청으로 보류
   했었으나, "JGit식 재구성" 작업(`HgHttpWireServer`/`HgSshWireServer` 신설) 중 같은
   세션에서 마저 구현됐다(`HgHttpWireServerTest#serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists`
   로 확인: `.hg/clonebundles.manifest` 파일이 없으면 capability 미광고, 생기면 광고 +
   내용 그대로 서빙). 상세 계획과 경위는 아래 "Clonebundles 실행 계획" 절 참고.
10. ~~**깨진(dangling) symlink가 `AddCommand`/`HgRepository`에서 조용히 누락·거부됨**~~
    — ✅ **완료(2026-09-02)**. `HgRepository.scanDirectory()`, `AddCommand.call()`의
    명시적 경로 처리, `CommitCommand.call()`의 tracked-file 존재 검증까지 **3곳
    전부** `File.isFile()`/`.exists()`로만 판단하고 있어서(심볼릭 링크를 따라가
    타겟이 없거나 디렉터리면 `false`) 깨진 symlink는 전체 스캔에서 조용히
    누락되고, 명시적 `hg add <path>`/커밋 시엔 아예 거부됐다(세 번째는 커버리지
    작업 때는 발견 못 하고 이번 수정 중 TDD로 재현하다 추가로 찾음). 실제 hg 7.2로
    확인(`ln -s missing-target.txt link.txt; hg add` → `A link.txt`, 커밋까지 정상
    round-trip, 심지어 타겟이 **디렉터리**를 가리키는 symlink도 재귀 안 하고
    그냥 파일로 추적함)한 뒤 세 지점 전부 `Files.isSymbolicLink()` 체크를 추가.
    TDD 3건(명시 경로 add, 전체 스캔, add→commit 라운드트립에서 매니페스트 `l`
    플래그·filelog 콘텐츠 검증) + 기존 `HgRepositoryCoverageTest`의 "symlink-to-
    directory는 완전히 스킵돼야 한다"는 낡은(버그 기준) 단언을 실측 정정. 전체
    회귀 클린(사전에 존재하던 무관한 `PushCommandTest` 실패 1건 제외 — 아래 참고).
    커밋 `f0244f2`.
11. ~~**Changegroup cg4/cg5 미지원**~~ (2026-09-02, 사용자 제보 후 호스트 native hg 7.2.2
    소스로 직접 대조 확인 — 2016년 논의만 됐다가 폐기된 옛 아이디어가 아니라, 실제로
    Mercurial 7.1(2025-08-04)에서 정식 채택된 최신 포맷) — ✅ **완료(2026-09-03)**.

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
      상호운용성(이미 검증 완료)을 훼손하지 않는 별도 opt-in 확장으로 다뤄야 한다.

    **완료 내용(2026-09-03)**: 위 소스 기반 요약을 로컬 시스템 hg 7.2(Rust/Docker
    불필요)가 만든 실제 cg4/cg5 페이로드로 바이트 단위 재검증 — 100% 일치, 차이
    없음. CLI엔 `hg bundle -t v4/v5`가 없어서("v4 is not a recognized bundle
    specification") `changegroup.makechangegroup()` 내부 API를 직접 호출해 픽스처
    확보(`src/test/resources/fixtures/changegroup-v4-v5/README.md`에 생성 스크립트/
    sha1/바이트 레이아웃 기록). `ChangegroupParser.ChangeGroupEntry`에 cg4 전용
    필드(fullText/snapshotLevel/rawTextSize/encodedCompression/storageDeltaBase/
    storageSnapshotLevel)와 cg5 전용 필드(protocolFlags/sidedata)를 추가해 파싱+
    패킹 양쪽 구현, cg4의 `flags` 필드에서 `REVIDX_DELTA_INFO_FLAGS`(0x7C0, sparse-
    revlog 델타 힌트 비트)를 분리해 걷어내는 처리까지 포함. `HgSshClient`/
    `HgRemoteClient`/`FetchCommand`의 하드코딩된 `"changegroup=01,02,03"`을
    `01..05`로 확장하고, 원격 광고 버전과의 교집합에서 최댓값을 고르는 협상 로직을
    배선. **부수 발견(진짜 버그)**: `HgRemoteClient.getBundle()`이 `bundlecaps`
    파라미터를 스페이스로 join하고 있었는데, 실제 스펙(`wireprototypes.
    GETBUNDLE_ARGUMENTS`)상 `bundlecaps`는 콤마 구분 "scsv" 타입이라 — 스페이스
    join은 토큰이 하나로 뭉쳐 `exchange.bundle2requested()`가 항상 false가 되고,
    hg4j가 어떤 changegroup 버전을 광고하든 조용히 구식 bundle1(cg1)로만 통신하고
    있었던 별개의 실제 버그. 콤마 join + `bundle2=<중첩 blob>` 형태로 수정. 기존
    cg1/cg2/cg3 상호운용성 회귀 없음(전체 회귀 확인). 상세: `ChangegroupV4V5Test`.

    **부수 발견 2 — cg3에도 있던 별개의 실제 버그**: `ChangegroupParser.parseBundle()`
    의 cg3(트리매니페스트 봉투) 처리가 "루프 첫 반복부터 무조건 경로 청크를 읽는다"는
    잘못된 가정으로 짜여 있었다. 실제 스펙(`changegroup.py`의 `generatemanifests()`:
    `if tree: yield _fileheader(tree)`)은 **루트("") 매니페스트 그룹은 경로 청크 없이
    바로 온다** — 실제 hg가 만든 cg3 바이트를 직접 hexdump/재파싱해 확인. 기존 코드는
    루트 그룹의 첫 델타 엔트리를 통째로 (엉뚱한) 서브디렉터리 경로 이름으로 먹어버리고
    나머지 엔트리만 그 밑에 잘못 붙이는 상태였다 — 즉 **cg3로 받은 매니페스트 리비전이
    항상 최소 1개 누락된 채로 misfiled됐다**. cg3/cg4/cg5 공통 구조라 세 버전 모두
    같이 고침(먼저 bare 루트 그룹을 파싱한 뒤 선택적 서브디렉터리 그룹 루프). 기존
    회귀에서 이 경로가 걸리지 않았던 이유: 지금까지 real-hg-interop 테스트들은 전부
    필로그 콘텐츠만 직접 검증했지(`HgRemoteAndSyncTest#testNativeHgCopyRenamePull`
    등) cg3 매니페스트 그룹 자체를 pull 후 검증한 테스트가 없었고, 게다가 위 bundlecaps
    버그로 그 테스트들조차 실제로는 대부분 bundle1(cg1, 매니페스트 그룹 구조 자체가
    다름)로 통신하고 있었다.

    **부수 발견 3 — 이 항목의 원래 "실사용 위험도" 서술 정정**: 위 배경 요약의
    "`supportedincomingversions()`가 cg4를 기본 필터링하니 기본 설정 저장소끼리는
    cg3로 자동 폴백"이라는 판단은 **push(unbundle 받는 방향)에만 해당**한다.
    pull/getbundle(서버가 클라이언트에게 "보내는" 방향)은 `changegroup.
    supportedoutgoingversions()`가 쓰이는데, 이건 treemanifest/narrow/lfs 저장소가
    아닌 한 **cg4를 설정 없이 무조건 포함**한다 — `experimental.changegroup4`도
    `delta-info-revlog` requirement도 필요 없다. 위 두 버그(bundlecaps 인코딩 +
    루트 매니페스트)를 고친 뒤 로컬 hg 7.2로 실측 재확인(`GET ?cmd=getbundle`을
    직접 만들어 응답을 hexdump/zlib로 대조): 기본 설정(아무 config도 안 준) real hg
    저장소를 그냥 pull만 해도 서버가 **cg3가 아니라 cg4를 고른다**(`version04`
    응답 확인). 즉 cg4 지원은 "당장 안 깨지는 opt-in 확장"이 아니라 **이 세션의
    수정 이후 기본 pull 경로에서 바로 쓰이는 실사용 코드**다 — cg5는 여전히
    `experimental.changegroup5`/revlogv2/changelogv2가 있어야 광고되므로 원래
    서술대로 opt-in에 가깝다.

    **테스트**: `ChangegroupV4V5Test`(신설, 6건, 모두 협소하게 설계) — 실제 hg가
    만든 cg4/cg5 페이로드 파싱 검증 2건, hg4j가 패킹한 cg4/cg5를 실제 hg
    `unbundle`이 정확히 받아들이는지 라운드트립 검증 2건(호스트 native hg 7.2로
    확인, Docker/Rust 불필요), 협상이 최댓값을 고르는지 real hg로 검증 2건
    (`experimental.changegroup5=yes` 켠 서버 → `05`, 기본 설정 서버 → `04`). 이
    작업으로 기존 cg1/cg2/cg3 관련 테스트 3개(`FetchCommandTest`,
    `HgRemoteClientCoverageTest`, `HgRemoteClientTest`)가 구식(버그 기준) bundlecaps
    문자열/cg3 루트-매니페스트 구조를 그대로 하드코딩해서 검증하고 있던 것도 같이
    발견해 실측대로 갱신. 전체 회귀 클린(217개 테스트 클래스, 2230건, failures=0
    errors=0, skipped=8 — Docker/Rust 필요 등 기존에도 스킵되던 것들).

    **남은 gap**: (1) ~~SSH 경로(`HgSshClient`)도 같은 방식으로 bundlecaps 문자열을
    고쳤지만... 실제로 SSH 경로에서 bundle2/cg4/cg5 협상을 켜는지는 검증 못 했다~~
    — ✅ **2026-09-04 확인, 이미 검증돼 있었다**. `HgSshClientRealHgInteropTest#
    getBundleActuallyNegotiatesAgainstARealHgSshServer`가 실제 hg SSH 서버를 상대로
    `assertNotEquals("01", ext.cgVersion, ...)`로 정확히 이걸 검증하고 있고(다른
    세션이 이후 SSH 클라이언트를 실제 hg 바이너리 프로토콜대로 전면 재작성하며 같이
    닫힘), 실행해서 통과 확인함(3/3 GREEN). (2)/(3)은 여전히 미해결 — hg4j가
    SERVER 역할일 때(`HgHttpWireServer`/`HgSshWireServer` → `Wire1Commands.getbundle`
    → `HgLocalClient.getBundle()`)는 여전히 cg1만 생성하고(`getBundle()`의
    `bundleCaps` 파라미터가 시그니처에만 있고 본문에서 전혀 안 쓰임, 2026-09-04
    재확인), cg5를 통해 받은 sidedata도 파싱만 하고(`entry.sidedata`) 로컬 revlog에
    실제로 반영하는 코드가 `api` 패키지 어디에도 없음(2026-09-04 재확인) — 상세는
    백로그 26번.
12. ~~**포셀린 명령 노출이 완전하지 않음**~~ — ✅ **완료(2026-09-02)** (사용자 질문
    "포셀린 기능은 모두 노출 끝?"에 답하며 `hg debugcommands`(real hg 7.2.2,
    debug*/admin* 제외 145개 중 핵심 포셀린)와 `Hg` 파사드 메서드 목록을 직접 전수
    대조해 발견했던 두 갈래 문제 전부 해결:

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

    (a)는 `BranchesCommand`/`TreeMergeCommand`/`CensorCommand`/`ClonebundlesCommand`에
    `Hg.branches()`/`Hg.treeMerge()`/`Hg.censor()`/`Hg.clonebundle(url)`을 추가해
    해결(`RollbackCommand`는 이미 `Hg.rollback()`으로 노출돼 있었음이 드러나 —
    원래 이 항목의 "미노출" 서술 자체가 틀렸던 것으로 확인). 커밋 `f04edb9`.

    (b)는 `TagsCommand`/`PathsCommand`/`FilesCommand`/`LocateCommand`/
    `ManifestCommand`/`CopyCommand`/`BundleCommand`/`RecoverCommand` 8개를 병렬
    격리 빌드 에이전트로 신설, 전부 real hg 7.2 CLI/소스로 검증(예: `hg locate`가
    `hg files`와 달리 미커밋 삭제 파일도 포함하고 기본 패턴이 relglob이라는 것,
    `hg copy`의 copy-chain이 커밋 시점에 끊긴다는 것, `hg bundle`의 정확한 cg1
    델타베이스 규칙과 real hg 양방향 라운드트립 등 — 상세는 커밋 메시지 참고),
    `Hg` 파사드에 전부 배선. 전체 회귀 2223 테스트, 실패 0. 커밋 `d055084`.

    부수 발견(범위 밖, 새 백로그 — 아래 항목 14): `ManifestCommand` 작업 중
    `CommitCommand`의 미변경 파일 감지가 symlink에서도 `File#length()`를 호출해
    타겟 파일 크기를 잘못 참조하는 버그 발견.
14. ~~**`CommitCommand`가 symlink의 미변경 여부를 판단할 때 `File#length()`로
    타겟 파일 크기를 참조함**~~ — ✅ **완료(2026-09-02)**. symlink 자신의 "크기"는
    타겟 경로 문자열의 바이트 길이여야 하는데(다른 곳, 예컨대 `AddCommand`/
    `CopyCommand`/`GraftCommand`/`RebaseCommand`는 이미
    `Files.readSymbolicLink(...).toString().getBytes(...).length`로 올바르게
    처리), `CommitCommand`의 미변경(재커밋 스킵) 판정 경로(size 비교 + M-2
    racy-write 콘텐츠 비교 두 곳 모두)가 `File#length()`/`Files.readAllBytes()`를
    그대로 써서 symlink가 가리키는 대상 파일을 읽어버리고 있었다 — 그 결과 symlink
    자신의 타겟 문자열은 전혀 안 바뀌었는데도 타겟 파일 크기/내용이 바뀌면 symlink가
    "변경됨"으로 오판되어 불필요한 새 filelog 리비전(부모 체인이 다른 새 노드 ID)이
    생성됐다. TDD로 재현(타겟 파일만 키우고 symlink 자체는 안 건드린 뒤 재커밋 →
    filelog 리비전 수가 그대로 1이어야 함을 실패하는 테스트로 확인) 후 두 지점 모두
    `Files.readSymbolicLink()` 기반으로 수정. 전체 회귀 클린. 커밋 `1162715`.
15. ~~**persistent-nodemap(`.n` 트라이 파일) 가속 조회 — 인식만 하고 실제 조회는 항상
    순차 스캔 fallback**~~ — ✅ **읽기(가속 조회) 완료(2026-09-03)**. Docker
    `hg-rust-7.2.4`(이 환경의 Rust 미포함 시스템 hg는 이 requirement의 저장소
    자체를 못 만듦)로 40커밋 실제 저장소를 만들어 `.n` docket(62바이트)과
    `-<uid>.nd` 트라이(64바이트 블록, 16×4바이트 빅엔디안 signed int, 루트는
    항상 마지막 블록)를 `mercurial/revlogutils/nodemap.py` 실측 레이아웃대로
    파싱하는 `storage.NodeMapFile` 신설 — 실제 hg가 만든 바이트로 40/40 노드
    해시 일치 검증(`NodeMapFileFixtureTest`, 시스템 python의 pure-python
    `mercurial.revlogutils.nodemap` 모듈로 독립 재검증까지 포함). `RevlogIndex`에
    신선한(tip_rev/tip_node가 현재 인덱스와 일치) 비-inline 트라이가 있으면 전체
    레코드 스캔을 건너뛰고 오프셋을 산술 계산(`rev*64`)하는 fast path를 추가,
    `findRevision()`이 트라이를 먼저 조회하되 항상 실제 레코드로 재검증한 뒤
    반환(트라이 자체는 부재 노드를 실재하는 다른 노드로 오인할 수 있다는
    `mercurial/revlogutils/nodemap.py`의 알려진 특성 때문 — 이 재검증이 그 허점을
    막는다), stale/부재 시 기존 순차 스캔으로 안전하게 fallback
    (`RevlogIndexPersistentNodeMapTest`, stale-트라이 케이스 포함). `findByHexPrefix`는
    트라이가 전체 노드 해시를 저장하지 않아(접두사 disambiguation에 필요한 만큼만
    저장) 가속 대상에서 제외 — 최초 호출 시 지연된 맵을 1회 materialize해 이후부턴
    기존 방식과 동일하게 동작. **쓰기(`.n` 갱신)는 이 시점엔 미구현이었으나 이후
    백로그 21번에서 완료됨** — 상세는 이 문서 위쪽의 persistent-nodemap gap table
    행과 백로그 21번 참고. 전체 회귀 2231 테스트, 실패 0(이 완료 시점 기준).
13. ~~**`PushCommand`의 증분(2회차) push가 최신 커밋을 누락함**~~ — ✅ **완료(2026-09-02)**.
    TDD로 원인 추적 결과, `PushCommand` 자체에는 버그가 없었다 — 진짜 원인은
    `RevlogIndex.checkAndUpdate()`의 **디스크 재확인 200ms 스로틀**이었다.
    `PushCommandTest`의 원격 저장소 핸들은 읽기 전용(자기 자신은 changelog에 한 번도
    안 씀)인데, 첫 push 직후의 첫 읽기가 스로틀 창을 열어버려서, 두 번째 push 직후의
    두 번째 읽기(빠른 테스트라 밀리초 단위로 붙어있음)가 스로틀에 걸려 조용히
    스킵되고 스테일(push2 이전) 리비전 카운트를 그대로 반환했다 — 디스크 자체는
    항상 정확했고 인프로세스 캐시만 뒤쳐진 것. 스로틀을 완전히 제거(`idxFile.length()`
    는 단순 stat() 한 번, `PerformanceBenchmarkTest`의 2초 SLA에 여유 충분).
    단, `addedRecords.isEmpty()` 게이트는 그대로 남겨둬야 했다 — 처음엔 이것도
    제거했더니 `StripCommand`/`RebaseCommand`/`HisteditCommand`가 깨졌다(이들은
    `RandomAccessFile#setLength`로 revlog `.i`/`.d`를 직접 truncate한 뒤 **같은**
    Revlog 참조를 계속 써서, 방금 strip한 리비전이 북마크가 가리키던 대상인지
    `findRevision()`으로 확인하는데, 여기서 자동 리로드가 걸리면 방금 truncate된
    파일 기준으로 nodeMap이 재구성돼 막 지워진 리비전에 대한 지식을 잃고 북마크를
    이동 대신 삭제해버림). 결론: **한 번이라도 자체적으로 쓴 적 있는 RevlogIndex는
    자기 북키핑을 신뢰(기존 동작 유지)하고, 한 번도 안 쓴(순수 읽기 전용) 핸들만
    스로틀 없이 매번 디스크를 재확인**하도록 정리. `HgRepositoryTest`에 읽기 전용
    핸들 케이스 회귀 테스트 추가. 커밋 `f0b1eff`.
16. ~~**Bundle1 writer 독립 클래스 없음**~~ — ✅ **완료(2026-09-03)**. 진단해보니
    `api.BundleCommand`(직전 세션에 이미 신설)는 `none-v1`(`"HG10UN"` + 무압축 cg1)만
    만들 수 있었고, 실제 gap은 서술 그대로 `--type` 파라미터/gzip·bzip2 압축
    부재였다. `BundleCommand.BundleType`(`NONE_V1`/`GZIP_V1`/`BZIP2_V1`,
    `setType(BundleType)`/`setType(String)`) 추가로 해결 — `gzip-v1`은 순수
    zlib/DEFLATE(`java.util.zip.Deflater` 기본 wrapped 모드, `GZIPOutputStream`이
    아님), `bzip2-v1`은 4바이트 리터럴 `"HG10"` + 표준 bzip2 스트림
    (`mercurial/bundle2.py`의 `bundletypes["HG10BZ"] = ("HG10", "BZ")` 그대로).
    실제 hg 7.2.2 CLI로 세 방식 모두 양방향 round-trip 검증(`BundleCommandTest`
    신규 3건). 상세는 위 gap table "Bundle1" 행 참고. 로컬 시스템 hg만으로 검증,
    Rust/Docker 불필요했음.
17. ~~**Sidedata 실제 활용(copy-tracing 등)**~~ — ✅ **decode/조회 완료(2026-09-03)**.
    `mercurial/revlogutils/sidedata.py`/`metadata.py` 실측 결과, 백로그 노트가
    언급한 `SD_P1COPIES`/`SD_P2COPIES`/`SD_FILESADDED`/`SD_FILESREMOVED`는 실제
    hg 7.2 소스에 정의만 있고 아무 데서도 생성/소비되지 않는 죽은 코드임을 확인 —
    실제로 쓰이는 건 `exp-copies-sidedata-changeset` requirement 하의 단일
    `SD_FILES` 키(추가/삭제/병합/salvage/touch 플래그 + p1/p2 복사 출처, `hg
    debugchangedfiles`로 노출)뿐이었다. LFS는 `mercurial/lfs/` 소스 확인 결과
    포인터 파일 확장이라 sidedata와 무관해 범위에서 제외. 바이트 레이아웃: index
    레코드의 sidedata offset/complen/compression-mode(3비트, PLAIN/DEFAULT-zstd/
    INLINE) 필드(hg4j가 필드는 갖고 있었지만 값을 파싱 안 하고 있었음) → outer
    컨테이너(`count:u16` + entry별 `key:u16,length:u32,sha1:20B`) → `SD_FILES`
    payload(`totalFiles:u32` + entry별 `flag:byte,fileEnd:u32,copyIdx:u32` +
    파일명들). `RevlogIndex`/`Revlog`가 세 필드를 실제로 파싱하도록 수정, 신규
    `storage.SidedataCodec`(컨테이너 디코드) + `api.ChangingFiles`(`SD_FILES`
    디코드 + `getCopySource(path)`) + 포셀린 `api.SidedataChangedFilesCommand`
    (dirstate 기반 `CopyCommand`의 과거-리비전 조회 보완). 로컬 hg만으로 검증
    (`exp-copies-sidedata-changeset`은 Rust 불필요), 실제 3커밋 저장소를
    `src/test/resources/fixtures/sidedata-copytracing/`에 fixture로 확보해
    `hg debugchangedfiles <rev>` 출력과 대조 검증(`SidedataCopyTracingTest`,
    `SidedataChangedFilesCommandTest`, 8건). **남은 gap**: ~~커밋 시점에 hg4j가
    `SD_FILES`를 직접 쓰는 writer는 미구현~~ — ✅ 백로그 19번에서 완료. ~~`hg log
    --follow`/annotate 연동은 여전히 미배선~~ — ✅ 백로그 27번에서 완료(2026-09-04)
    — 단, 백로그 27번 조사 결과 real hg의 `--follow`/`annotate` 자체가 기본
    설정에서는 이 `SD_FILES` sidedata를 전혀 읽지 않는다는 것이 밝혀져(별도의
    filelog 수준 `copy`/`copyrev` 메타데이터가 진짜 메커니즘), 실제 연동은 그
    계층으로 이뤄졌다 — 상세는 백로그 27번.
18. ~~**Treemanifest 쓰기(생성/커밋)**~~ — ✅ **완료(2026-09-03)**. 실제
    `mercurial/manifest.py`의 `manifestlog._addtree`/`treemanifest.writesubtrees`/
    `dirtext()`를 실측(자식 디렉터리부터 bottom-up 재귀 기록, 각 레벨의 파일+
    서브디렉터리 포인터를 파일명 하나의 정렬 리스트로 합쳐 `sorted(dirs+files)`
    순서로 직렬화 — 파일 먼저, 디렉터리 나중이 아님)해서 `CommitCommand`에
    `writeTreeManifestDir` 재귀 메서드로 구현. `repository.isTreemanifest()`일
    때만 활성화(새 `HgRepository.isTreemanifest()` — 실제 hg 픽스처로 확인한 대로
    `treemanifest` requirement는 `.hg/store/requires`가 아니라 **최상위
    `.hg/requires`**에 있음, persistent-nodemap 등 store 포맷 플래그와는 다른
    분류), 기존 평면 매니페스트 경로는 완전히 무수정·무영향. 부모 커밋의 트리에서
    각 디렉터리의 기존 노드를 찾기 위한 `collectDirNodes`(`ManifestTreeIterator`의
    재귀 펼치기와 동일한 순회를 쓰되 디렉터리 노드 자체를 보존 — 이를 위해
    `ManifestTreeIterator.Entry`에 `getPath()`/`getNodeId()` public getter 추가)도
    신설. **의도적 단순화(문서화, 정확성엔 무관)**: 실제 hg는 서브트리 전체가
    부모와 바이트 단위로 동일하면 그 디렉터리의 새 리비전 작성을 건너뛰고
    부모 노드를 재사용하는 최적화(`m.unmodifiedsince(m1)`)가 있는데, hg4j는
    이 최적화 없이 커밋마다 변경된 파일 경로상의 모든 디렉터리에 항상 새
    리비전을 쓴다(루트 레벨 평면 매니페스트가 원래도 매번 새로 쓰던 것과 동일한
    수준) — 결과 바이트는 여전히 완전히 유효한 부모/콘텐츠 해시이고 실제 hg가
    문제없이 읽지만, 저장 중복 제거가 덜 됨.
    검증(`TreeManifestWriteTest`, 2건 GREEN): (1) 중첩 디렉터리(`sub/`,
    `sub/deep/`, `sub2/`) 커밋 후 자체 `ManifestTreeIterator`/`getManifestAtCommit`
    왕복 정확 + 두 번째(증분) 커밋에서 안 건드린 서브디렉터리의 부모-링크 정확성
    확인, (2) **hg4j로 커밋한 저장소를 실제 hg-rust-7.2.4(Docker)에 넘겨 `hg
    verify`/`hg log`/`hg cat -r 0 sub/deep/c.txt`(3단 중첩 경로)/`hg cat -r 1
    sub2/d.txt`(안 건드린 서브트리) 전부 성공** — real hg가 hg4j가 쓴
    `meta/sub/00manifest.i`+`meta/sub/deep/00manifest.i`를 실제로 타고 내려가며
    파일을 정확히 찾아냄을 증명. CommitCommand/treewalk 패키지 전체 회귀로 기존
    평면 매니페스트 동작 무손상 확인.
19. ~~**Sidedata `SD_FILES` writer(커밋 시 쓰기)**~~ — ✅ **완료(2026-09-03)**.
    `SidedataCodec.serialize`(바깥 컨테이너 인코딩, 기존 `deserialize`의 역방향)와
    `ChangingFiles.encode`(added/removed/touched + copiedFromP1/P2 → `SD_FILES`
    페이로드, 파일명 알파벳 정렬 + flag 비트 조합, 기존 `decode`의 역방향) 신설,
    `CommitCommand`에서 `repository.isSidedataCopies()`(신규 accessor,
    `exp-copies-sidedata-changeset` requirement)일 때 기존 파일 추적 루프에서
    added/removed/touched/copy 정보를 수집해 인코딩 후 `Revlog.appendRevision`의
    새 `sidedataContainer` 파라미터로 전달. `Revlog.appendRevisionV2`가
    `.sda` 파일에 append하고 인덱스 레코드의 sidedata offset/length 필드를 채운다.
    **의도적 단순화(문서화)**: `merged`/`salvaged` 두 액션 분류(병합 상태 전용
    세부 구분)는 `CommitCommand`가 현재 별도로 추적하지 않아 항상 빈 집합으로
    전달 — added/removed/touched 3분류와 copy-tracing(이 백로그의 핵심 목적)은
    완전히 지원됨.
    **버그 발견·수정**: 구현 중 real hg로 검증하다가 실제 결함을 하나 발견 —
    v2 docket 헤더의 `sidedata_end`/`pending_sidedata_end` 필드(offset 42~58,
    기존 `updateV2DocketSizes`가 index_end/data_end만 갱신하고 이 두 필드는
    손도 안 대고 있었음)를 갱신하지 않으면, `.sda` 파일에 바이트를 실제로 다
    append했어도 real hg가 **파일 자체 크기가 아니라 이 docket 필드를 신뢰**해서
    `"cannot read from revlog ...sda; expected N bytes from offset M, data size
    is <stale값>"`로 거부한다(hg4j 자체 read 경로는 이 필드를 안 쓰고 파일을
    직접 읽어서 자기 자신에게는 정상으로 보였음 — 자기일관성만으로는 못 잡는
    전형적 사례). `updateV2DocketSizes`에 3-인자 오버로드를 추가해 수정.
    **발견 당시 인접 갭이었으나 같은 날 별도로 완료됨**: hg4j는 `exp-changelog-v2`
    저장소를 처음부터(`Hg.init()`) 생성하는 경로가 당시엔 전혀 없어서, 이 항목
    검증은 실제 local hg CLI로 rev0을 만들고 그 위에 hg4j가 rev1을 이어붙이는
    방식으로 진행했다(백로그 19가 실제로 다루는 시나리오와는 일치하지만, hg4j
    스스로 저장소를 처음부터 만드는 건 별도 갭으로 문서화해뒀었다) — ✅ 바로 이어서
    **완료(2026-09-03)**. `RevlogIndex.initializeNewV2Docket(boolean
    asChangelogV2)`로 기존 `exp-revlogv2.2` 부트스트랩 로직을 일반화(도켓/컴패니언
    파일 바이트 구조는 완전히 동일, magic값과 `isChangelogV2()`만 다름),
    `DefaultFileStoreEngine.getRevlog()`가 파일명이 정확히 `00changelog.i`이고
    `repository.isChangelogV2()`이며 파일이 아직 없을 때만 이 경로를 요청하도록
    배선(`exp-changelog-v2`는 매니페스트/파일로그가 아니라 changelog에만 적용되는
    좁은 requirement이므로).
    **이 작업 중 실제 심각한 버그를 하나 더 발견·수정**: `appendRevisionV2`의
    CL_V2(changelog-v2) 분기가 압축 모드를 항상 `COMP_MODE_DEFAULT`(zstd)로
    하드코딩하고 있었는데, 실제 hg는 리비전마다 **동적으로** zstd 압축이 실제로
    콘텐츠를 줄였을 때만 DEFAULT를, 안 줄었으면(작은 커밋 메시지 등) 원본 그대로
    `COMP_MODE_PLAIN`(마커 바이트 없이)을 쓴다 — 기존 hg4j 코드는 이때
    `DeltaCodec.compress`의 **v1 revlog 전용 관례**(`'u'`+원본바이트 폴백 마커)를
    그대로 재사용하면서 레코드의 압축모드 바이트는 여전히 DEFAULT로 잘못 표시해,
    real hg가 `'u'`로 시작하는 바이트열을 zstd 프레임으로 오인해 `"zstd
    decompressor error: Unknown frame descriptor"`로 거부하는 상태였다(실제 hg
    픽스처 `sidedata-copytracing/data.idx`의 3개 리비전 중 2개가 compbyte=0x00
    PLAIN임을 직접 바이트 단위로 대조해 확정). 이전 세션들의 changelog-v2 관련
    테스트가 전부 우연히 "충분히 긴" 콘텐츠만 다뤄서 이 버그를 피해갔던 것 — 이번
    작업의 짧은 첫 커밋(rev0)에서 처음 노출됨. `Zstd.compress` 결과가 원본보다
    작을 때만 그 결과+COMP_MODE_DEFAULT(1)를, 아니면 원본 그대로+COMP_MODE_PLAIN(0)를
    쓰도록 동적 선택으로 수정.
    검증(`ChangelogV2BootstrapTest`, GREEN): (1) hg4j가 처음부터 만든
    `exp-changelog-v2` 저장소에 2회 커밋(자체 read 경로로 왕복 확인), (2) **real
    local hg가 hg4j로 A부터 Z까지 만든 저장소에서 `hg verify`/`hg log` 둘 다 성공**
    (수정 전엔 정확히 이 시나리오에서 압축 버그가 터졌었음). storage/api/transport
    패키지 전체 회귀(963개 중 3개 실패 — 전부 기존에도 있던 무관한 동시성/타이밍
    플레이키로 단독 재실행 시 통과 확인) GREEN.
    검증(`SidedataFilesWriteTest`, GREEN): local hg(Rust 불필요, 백로그 17에서
    이미 확인된 대로)로 `format.exp-use-copies-side-data-changeset=yes`
    저장소를 만들고 rev0 커밋 → hg4j가 rename(copy+remove)+신규파일 추가로 rev1
    커밋 → hg4j 자체 `SidedataChangedFilesCommand`로 정확히 디코드 확인 +
    **real hg `hg debugchangedfiles 1`/`hg verify`가 hg4j가 쓴 바이트를 그대로
    정확히 읽음**(`removed: a.txt; added p1: b.txt, a.txt; added: c.txt` 실측
    출력 일치). storage/CommitCommand/sidedata 관련 패키지 전체 회귀+api 패키지
    전체 회귀(963개 중 962개 GREEN, 유일한 실패는 기존에도 있던 무관한 타이밍
    플레이키 `ProcessHookTest`) 확인.
20. ~~**Wireprotocol v2 `getBundle()`의 재귀적 `tree=<dir>` fetch 미구현**~~ — ✅
    **완료(2026-09-03)**. `HgRemoteClientV2.getBundle()`이 루트 매니페스트에서
    `t`플래그 서브디렉터리 포인터를 발견하면 BFS로 `manifestdata`를
    `tree=<dir>`(트레일링 슬래시 없는 bare 경로, 예: `"sub"`/`"sub/deep"`)를 재귀
    호출해 더 깊이 중첩된 포인터까지 전부 수집, `ChangegroupParser.ChangegroupBundle`
    의 기존 `manifestGroups`(cg3/4/5용 봉투, 이미 구현돼 있었음) 필드로 조립한 뒤
    `writeBundle(..., "04")`+`Bundle2Parser.wrapChangegroupInBundle2`로 HG20/cg4
    번들을 생성한다(cg1/HG10UN은 트리 봉투 자체가 없어 flat 매니페스트일 때만
    그대로 사용, 서브디렉터리 발견 시에만 전환 — 기존 flat 경로 완전 무영향).
    **발견한 실제 갭 하나 더**: 백로그 문서가 "manifestdata 명령이 이미 있어서
    재귀 배선만 추가하면 됨"이라고 적어놨던 전제가 틀렸음을 확인 —
    `Wire2Commands.manifestdata`(hg4j 자체 wire2 **서버** 측)가 실제로는 비어있지
    않은 `tree` 인자를 무조건 거부하고 있었음(`"tree manifests are not
    supported"`). 클라이언트 수정을 실제로 검증할 방법이 없어서 서버 측도 대칭적으로
    `meta/<tree>/00manifest.i`를 찾아 서빙하도록 같이 고쳤다(존재하지 않는 tree는
    여전히 명확한 에러로 거부). 실제 Mercurial 6.0 wireprotocol v2 서버(6.1부터
    폐기돼 이 환경의 Docker 이미지들엔 남아있지 않음)로는 검증 못 했고, 대신
    hg4j↔hg4j 자기 일관성 왕복으로 검증: 서버 측에 treemanifest 저장소(`sub/`,
    `sub/deep/`, `sub2/` 3단 중첩, 백로그 18번 쓰기 지원으로 실제 커밋)를
    `HgHttpWireServer`로 띄우고, 클라이언트가 v1→v2 자동 업그레이드를 거쳐
    `FetchCommand`로 pull한 뒤 로컬에 `meta/sub/00manifest.i`+
    `meta/sub/deep/00manifest.i`+`meta/sub2/00manifest.i`가 실제로 생성되고
    4개 파일 콘텐츠가 정확히 재구성됨을 확인(`Wire2TreeManifestFetchTest`, GREEN).
    **의도적 단순화(문서화)**: 서브디렉터리의 linknode는 wire2 응답이 별도로
    안 실어주므로 그 서브디렉터리 포인터를 처음 발견한 상위 엔트리의 linknode로
    근사(cg1 포지셔널 디코딩엔 유효한 changelog rev이기만 하면 되므로 정확성엔
    영향 없음), 증분(common-root seeding) 최적화는 루트 경로만 적용하고
    서브디렉터리 델타체인은 매번 처음부터 구성(정확하지만 최적은 아님).
    transport/bundle/FetchCommand 패키지 전체 회귀(540개+ 테스트) GREEN.
21. ~~**persistent-nodemap `.n` 파일 쓰기(커밋 시 갱신)**~~ — ✅ **완료(2026-09-03)**.
    `mercurial/revlogutils/nodemap.py`를 실측(전체 재빌드 `_build_trie`/`_persist_trie`/
    `_walk_trie`, 증분 갱신 `_update_trie`/`_insert_into_block`, docket 직렬화, 실제
    hg의 "새 길이가 unused*10 이하면 포기하고 전체 재빌드로 폴백" 10% 임계값까지)해서
    `NodeMapFile`에 두 경로 모두 구현. `Revlog`의 리비전 append 진입점 5곳(v1/v2
    changelog/일반 revlog, changegroup 적용, raw/optimized 경로) 전부가 공통으로
    거치는 유일한 지점 `RevlogIndex.addRecord()` 직후에 단일 훅
    (`updatePersistentNodeMapAfterAppend()`)을 걸어 changelog/manifest/filelog 전체에
    자동 적용(개별 명령 코드는 전혀 건드리지 않음 — `DefaultFileStoreEngine`이 이미
    모든 Revlog를 `repository.isPersistentNodemap()` 플래그로 균일하게 생성하고
    있었던 기존 아키텍처 덕분). requirement 미설정이거나 inline revlog면 즉시
    no-op(기존 동작 100% 보존). 검증 3단계: (1) 백로그 15번의 실제 hg-rust-7.2.4
    픽스처(40리비전)로 전체 재빌드 후 40개 노드 해시 전부 정확히 조회됨, (2) 10→25→40
    3단계 증분 확장에서 매 단계 정확 + uid 보존(진짜 증분 경로임을 증명), (3) **hg4j로
    브랜드뉴 저장소를 만들어 persistent-nodemap requirement를 켜고 실제
    `CommitCommand`로 8회 커밋 → 실제 Rust 확장 hg-rust-7.2.4(Docker)에 그대로 넘겨
    `hg verify`/`hg log` 둘 다 성공 확인**(`NodeMapFileWriterTest`, 4건 GREEN). storage
    패키지 전체 회귀 및 CommitCommand 관련 회귀(212개 테스트, 무관한 기존 심볼릭링크
    타이밍 플레이키 1건 제외 전부 GREEN, 단독 재실행 시 그것도 통과)로 기존 동작
    무손상 확인.

22. ~~**HTTP/SSH 와이어 프로토콜 "실전 통신·협상" 테스트 확충**~~ — ✅ **완료(2026-09-03,
    4개 그룹 전부)**. 배경: 2026-09-03 세션에서 두 종류의 작업을 병행해봄 —
    (a) 순수 JaCoCo BRANCH 커버리지 갭 메우기(missed 1~4 롱테일 ~30개 클래스), (b) 그
    이전에 했던 SSH/HTTP 라이브 interop 검증(백로그 3번, `unbundlehash`, SSH 핸드셰이크
    전면 재구현 등). **(a)는 실버그를 하나도 못 찾았고**(전부 "이미 맞게 동작하는데
    테스트만 없었다" 아니면 "도달 불가능한 방어 코드"), **(b)는 실제 프로토콜 버그를
    다수 찾아냈다**(HTTP `X-HgArg-N`/`-0.2` 프레이밍, SSH 핸드셰이크 전체가 지어낸
    것이었던 문제 등, 백로그 3번/위 표 참고). 시간 대비 버그 발견율이 압도적으로 높은
    쪽을 우선하는 게 합리적이라 판단해 다음 세션의 정식 작업 항목으로 백로그에 편입.

    **범위(포함)**:
    - **hg4j 클라이언트 → 실제 hg 서버**: HTTP v1의 3단계 인자 전송 방식
      (`httppostargs`/`httpheader=N`/레거시 GET) 각각을 실제로 강제 광고하는 서버
      설정으로 개별 검증(현재는 한 가지 조합만 확인됨). `x-hgproto-1` 압축 협상
      (`zlib`/`zstd`/none) 조합별 실제 왕복. SSH의 `unbundlehash` on/off 양쪽 다
      실제 서버로 push 성공까지 확인(현재는 와이어 바이트만 mock 서버로 검증, 실제
      hg 서버가 그 sentinel을 accept하는지는 미검증).
    - **실제 hg 클라이언트 → hg4j 서버 방향(HTTP v1, SSH v1)** — 위 항목 3번에 이미
      "미검증으로 남음"이라고 명시된 진짜 gap. 실제 `hg clone`/`hg pull`/`hg push`
      CLI가 hg4j의 `HgHttpWireServer`/`HgSshWireServer`를 상대로 정상 동작하는지
      양방향 확인.
    - 서버 구현체: native hg 7.2.2(호스트) + `hg-rust-7.2.4`(Docker) 두 개로 충분 —
      이번 세션에서 이미 둘 다 쓰고 있었고, 둘 다 wireprotocol v1이 대상이라 v2
      전용이었던 Mercurial 6.0 Docker(별도 인스턴스)까지는 불필요(v2는 아래
      "범위(제외)" 참고).
    - 협상 실패/폴백 경로: 서버가 특정 capability를 광고하지 않을 때 클라이언트가
      실제로 하위 호환 경로로 정확히 떨어지는지(예: `httppostargs` 미광고 시
      `httpheader=` 경로로, 그것도 없으면 레거시 GET으로) 각 단계를 개별 강제해
      확인.

    **범위(제외, 무한정 확장 방지)**: wireprotocol v2(백로그 상 이미 "실사용 가치
    제한적"으로 결론난 상태, 6.1부터 폐기됨) 관련 협상 확장은 이번 항목에서 제외.
    clonebundles(백로그 별도 섹션에서 이미 다룸)도 제외. TLS/인증 계층(HTTP Basic
    auth 이상의 보안 프로토콜 자체)은 제외 — 순수 Mercurial 와이어 프로토콜
    협상만 대상. 압축 알고리즘 자체의 정확성(zlib/zstd 코덱 버그)은 `DeltaCodec`
    쪽 BRANCH 커버리지 백로그(별도, `test-coverage-95-percent-initiative.md`)와
    중복되므로 이 항목에서는 "협상 결과로 올바른 코덱이 선택되는지"까지만 본다.

    **다음 세션 시작점**: 위 "범위(포함)" 4개 그룹을 각각 별도 TDD 배치로 나눠
    순서대로 진행 권장(실제 hg 클라이언트→hg4j 서버 방향이 가장 검증 안 된 채
    남아있어 우선순위가 가장 높음).

    **그룹 1/2/4(클라이언트 방향) 진행 결과, 2026-09-03**: 기존 구현(`HgRemoteClient`/
    `HgSshClient`)의 3단계 인자 전송·압축 협상·`unbundlehash` 로직 자체는 이미 정확하게
    구현돼 있었다(코드 재작성 불필요) — 이번 세션이 실제로 한 일은 "각 조합을 real hg
    상대로 개별 강제해서 실버그가 없는지 확인"뿐이었고, **실제로 새로 발견한 프로토콜
    버그는 0건**이었다(이 점에서 이번 배치는 이 문서 상단에 적힌 "(a) 순수 커버리지
    갭 메우기는 실버그를 못 찾는다"는 패턴에 더 가까웠다 — 다만 "이미 구현은 있지만
    한 번도 real hg로 강제 검증된 적 없는 분기"였다는 점에서 여전히 가치 있는 검증).

    - **httppostargs 강제**: 실제 hg는 `experimental.httppostargs`(기본 false) 설정으로
      켤 수 있음 확인(`mercurial/wireprotoserver.py addcapabilities()` 실측). 이 config로
      real `hg serve`를 띄워 capabilities에 `httppostargs`가 실제로 나타나는지, 그리고
      hg4j `PullCommand`/`PushCommand`가 그 상태에서 실제 pull+push 왕복에 성공하는지
      확인 — 통과.
    - **httpheader=N 경로**: 실제 hg는 이 토큰을 **무조건** 광고한다(config로 끌 수
      없음, `addcapabilities()`에 조건 없이 `caps.append(b'httpheader=%d' % ...)`).
      기존 `HgHttpV1LiveServerCgNegotiationInteropTest`가 이미 이 경로를 통해 real
      Rust hg 7.2.4와 실제로 changegroup 버전까지 협상하고 있었으므로 별도 그룹으로
      중복 검증하지 않음.
    - **레거시 GET(3번째 티어) 강제**: real hg에 이를 끄는 config가 없어서(위와 동일한
      이유), real hg 자체는 건드리지 않고 `?cmd=capabilities` 응답 바디에서만
      `httpheader=` 토큰을 제거하는 투명 리버스 프록시(`CapabilityStrippingHttpProxy`,
      다른 모든 요청/응답은 바이트 단위로 그대로 통과)를 hg4j와 real hg 사이에 세워
      강제. 이 상태에서 hg4j가 실제로 쿼리스트링 GET으로 폴백하고(capabilities에
      `httpheader=`/`httppostargs` 둘 다 없음을 sanity로 확인) real hg의 `_args()`가
      —원래 querystring도 항상 파싱하므로— 정상적으로 pull을 완주함을 확인.
    - **압축 협상 `zlib`/`zstd`/`none`**: real hg는 `server.compressionengines=<엔진>`
      config로 광고 목록을 강제로 한 엔진만으로 좁힐 수 있음 확인
      (`wireprototypes.supportedcompengines()` 실측). 처음엔 zstd에 별도 `zstandard`
      PyPI 패키지가 필요한 줄 알았으나, real hg는 자체 번들 C 확장
      (`mercurial.zstd`, `mercurial/zstd.*.so`)을 쓰므로 호스트 native hg 7.2에 이미
      포함돼 있어 Docker/재빌드 불필요였음 — 세 엔진 전부 host `hg serve`만으로 강제
      가능. 세 조합 각각 capabilities의 `compression=` 토큰이 강제한 엔진 하나만
      담고 있음을 확인 후 실제 pull(0.2 프레이밍 + 해당 코덱 압축해제 왕복) 성공 확인.
    - **`unbundlehash` off (HTTP+SSH 둘 다)**: real hg는 이 토큰도 무조건 광고
      (`wireprotov1server.wireprotocaps` 리스트에 조건 없이 포함) — "on" 경로는
      기존 `HgSshClientRealHgInteropTest#pushEndToEndToARealHgSshServer`(SSH)/
      `HgHttpV1LiveServerInteropTest#pushes...`(HTTP)가 real hg가 always-on이므로
      이미 암묵적으로 검증하고 있었음(해시된 sentinel이 실제로 accept됨).
      "off"는 real hg에 끌 방법이 없어 두 가지 MITM 기법으로 강제: HTTP는 위와 같은
      `CapabilityStrippingHttpProxy`로 `unbundlehash` 토큰만 제거; SSH는 real
      `hg serve --stdio` 서브프로세스를 그대로 파이프하되 최초의 framed 응답(`hello`
      커맨드의 `capabilities: ...` 줄)만 디코드·토큰 제거·재인코드하는
      `UnbundlehashStrippingHgServeCommand`(Apache MINA SSHD `Command` 어댑터,
      `HgSshClientRealHgInteropTest`의 `RealHgServeCommand` 패턴 확장)로 구현. 두
      경우 다 capabilities에서 `unbundlehash`가 실제로 사라졌음을 sanity로 확인한
      뒤, hg4j가 (해시 대신) literal heads 목록을 보내고 real hg의
      `exchange.check_heads()`가 이를 정상적으로 accept해 push가 성공함을 확인 —
      "off"가 단순히 "안 깨진다" 수준이 아니라 진짜 원래(최적화 이전) 경로가 real
      서버에 여전히 정상 동작함을 증명.

    **강제 못 시킨 조합**: 없음 — 위 "범위(포함)" 1/2/4번 그룹의 모든 하위 조합을
    real hg(host native 7.2)만으로 전부 강제·검증했다(Docker `hg-rust-7.2.4`는 결국
    쓰지 않았음 — zstd가 host native hg에도 이미 있었기 때문).

    **테스트**: `HgHttpV1NegotiationForcingInteropTest`(httppostargs 강제/레거시 GET
    강제/압축 3종 강제/HTTP unbundlehash-off 강제, 6개 테스트) +
    `HgSshUnbundleHashOffInteropTest`(SSH unbundlehash-off 강제, 1개 테스트) — 신설
    지원 클래스 `RealHgServeSupport`(host `hg serve` 기동+포트 감지)/
    `CapabilityStrippingHttpProxy`(capabilities 응답 토큰 제거 투명 프록시)와 함께
    `src/test/java/io/github/search5/hg4j/transport/`에 추가, 전부 GREEN. 기존
    `HgHttpV1LiveServerInteropTest`/`HgHttpV1LiveServerCgNegotiationInteropTest`/
    `HgSshClientRealHgInteropTest` 등은 건드리지 않음(그대로 GREEN 유지, transport
    패키지 전체 492개 테스트 무손상 확인).

    **미완료**: 없음 — 그룹 3(실제 hg 클라이언트 → hg4j 서버 방향)도 병렬로 진행된
    별도 작업으로 ✅ 완료(2026-09-03, 위 gap table "Wire protocol v1" 행 참고): SSH
    증분 pull/push, 여러 branch/bookmark/tag가 있는 저장소의 clone 정확성(HTTP+SSH),
    존재하지 않는 리비전 요청 시 에러 처리, 같은 서버에서의 클라이언트 간 즉시 일관성
    신규 검증. 이로써 22번 항목의 4개 그룹(1/2/3/4) 전부 완료.

23. ~~**commit/push/branch/merge/tag(+ rebase/shelve/bisect/strip/subrepo) — 실전
    시나리오 종합 interop 검증**~~ — ✅ **완료(2026-09-04, 10개 카테고리 전부)**.
    배경: 백로그 22번(와이어 프로토콜 협상)과 같은 이유 — 이 명령들은 hg의 가장
    기본적이고 가장 자주 쓰이는 포셀린 명령인데, 지금까지의 검증은 "한 가지 대표
    시나리오"(예: 선형 커밋 2개, fast-forward merge 1건) 위주였고 각 명령이
    실전에서 마주치는 조합의 상당수는 아직 실제 hg CLI와 대조된 적이 없다. 아래
    카테고리 각각을 별도 TDD
    배치로 진행 권장.

    **진행 현황(2026-09-04)**: 10개 카테고리 전부 ✅로 완료(commit/push/branch/
    merge/tag/rebase/shelve/bisect/strip/subrepo — 병렬 진행된 5개 배치가 각각
    실제 hg CLI 왕복 검증 + 실제 버그 다수 발견·수정: commit/push 4건,
    branch/tag 2건, merge/rebase 3건(그중 1건은 `CommitCommand` 공통 로직
    버그), shelve/bisect/strip 4건, subrepo 2건 — 총 15건). **여러 항목에서
    코드 변경 없이 사용자 확인만 필요한 아키텍처 수준 결정이 발견돼 아래 각
    카테고리 문단에 표시해뒀다** — 코디네이터가 정리해 별도로 보고할 예정.

    **아키텍처 결정 6건 — 전부 사용자 확정(2026-09-04)**: 아래 각 카테고리
    문단에 흩어져 있던 "사용자 확인 필요" 항목 6개를 모아 `AskUserQuestion`으로
    확인, 전부 "정석대로 완전히 구현" 방향으로 확정됨(코드는 아직 미착수 —
    이 결정 기록 다음에 순서대로 구현 예정):
    1. `HeadsCommand.call()`(인자 없는 기본 호출) → **real hg의 실제 `hg heads`
       시맨틱(브랜치별 head 전부)으로 고침**(브레이킹 체인지 감수, 기존
       `--topo` 동작은 명시적 옵션으로만 유지).
    2. subrepo 미체크아웃 상태에서 부모 커밋 시 `.hgsubstate` 처리 →
       **real hg와 동일하게 null 리비전으로 리셋**(기존 "보존" 방식 폐기 —
       `HgSubrepoTest`/`UpdateCommandTest`의 의존 케이스 갱신 필요).
    3. rebase의 물리적 strip+marker 동시 수행 → **strip을 그만두고 marker만
       남기는 evolution 방식으로 전환**(원본 리비전은 hidden으로 보존,
       `RebaseRealHgInteropTest`의 "unknown revision" 기대값을 "hidden"으로
       갱신 필요).
    4. rebase 충돌 감지 → **`MergeCommand`의 3-way merge 인프라를 cherry-pick
       경로에 이식**(conflict marker+exit 1+`resolve`/`--continue`/`--abort`까지
       real hg와 동등하게).
    5. `unshelve`의 rebase 필요 시나리오(충돌 포함) → **real hg의
       임시커밋+rebase+merge+strip 알고리즘을 지금 이식**(항목 4의 merge
       인프라를 재사용할 것).
    6. `PushCommand`의 `checkheads()` 포트 → **obsolescence-marker 예외,
       bookmark-head 예외까지 지금 마저 포팅**.

    구현 순서: 4(rebase 3-way merge 인프라)를 먼저 완성한 뒤 그 인프라를
    재사용해 3(rebase evolution 전환)과 5(unshelve rebase화)를 이어서 진행,
    1/2/6은 서로 독립적이라 병행 가능.

    **commit**: ✅ **완료(2026-09-04)**. 머지 커밋(부모 2개, `p2` 필드 정확성) —
    특히 심볼릭 링크/실행권한/바이너리 파일이 섞인 머지, `hg commit
    --close-branch`, 빈 커밋(파일 변경 없이 메시지만), extra 필드가 여러 개
    겹칠 때(`branch`+`close` 동시) 정렬/인코딩 순서, `hg commit --amend`(hg4j에
    `AmendCommand`가 이미 있었음) — 전부 실제 hg CLI(host native 7.2.2)와
    왕복 대조로 재검증했다. **실제 버그 발견 및 수정**: `AmendCommand`가
    (1) amend 시 dirstate의 현재 부모(=amend 대상 커밋 자신)를 그대로 새
    커밋의 부모로 써서, 실제 hg처럼 "같은 부모를 공유하는 형제 리비전으로
    교체"가 아니라 amend 대상 커밋의 **자식**이 되는 DAG 형태 오류가 있었다
    (실제 hg로 `hg commit --amend` 후 `hg log -G`/`{p1node}` 대조해 발견,
    2026-09-04) — `CommitCommand`에 `setAmendDeclaredParents(p1, p2)`를
    신설해 "무엇이 바뀌었는지 판단하는 기준(dirstate의 실제 부모)"과
    "changelog/manifest에 기록되는 선언된 부모"를 분리함으로써 해결(파일
    콘텐츠 계산 로직 자체는 전혀 건드리지 않음 — 그대로도 정확했음). (2)
    `-m`/`-u` 없이 amend하면 무조건 예외를 던졌다(실제 hg는 amend 대상
    커밋의 메시지/작성자를 그대로 재사용) — 기존 테스트
    `HgAmendTest#testAmendWithoutExplicitMessagePropagatesCommitMessageRequirement`가
    이 버그를 "정상 동작"으로 문서화하고 있었던 것도 함께 바로잡음
    (`testAmendWithoutExplicitMessageOrAuthorReusesOriginalCommitValues`로
    교체). **테스트**: `CommitRealHgInteropTest`(신설,
    `src/test/java/io/github/search5/hg4j/api/`) 6개 — 혼합 파일타입 머지
    커밋, close-branch, 빈 커밋, branch+close 동시 extra, amend(단순/머지
    커밋 amend) — 전부 GREEN.

    **push**: ✅ **완료(2026-09-04)**. 여러 head가 있는 저장소로 push(reject
    vs 성공 조건), `--force`로 비-fast-forward push, 새 named branch를
    원격에 처음 push할 때의 경고/허용 조건(`hg push --new-branch`), bookmark가
    있는 저장소의 push(북마크 이동 반영), 이미 알려진 커밋만 있어 "no changes
    found"로 끝나는 push — 전부 실제 hg CLI와의 **양방향** interop으로
    검증했다: hg4j가 pusher, 실제 `hg serve`(HTTP)가 accept/reject를 판정하는
    authority(`RealHgServeSupport` 재사용). **범위 확장(설계 판단)**: 기존
    `PushCommand`에는 head-count/새 브랜치 거부 로직이 전혀 없었다
    (`--force`/`--new-branch` 옵션 자체가 없었음 — push는 항상 무조건 성공) —
    담당 에이전트는 이를 architecture-level 변경까지는 아니라고 판단해 직접
    구현했다: `PushCommand.setForce()`/`setAllowNewBranch()` 신설 + 실제 hg
    `mercurial/discovery.py checkheads()`를 간소화 포팅한 client-side
    체크(그룹 3까지: 존재하지 않던 원격 브랜치 정보 조회를 위해
    `HgRemoteConnection.getBranchHeads()` 신설 및 `HgLocalClient`/
    `HgRemoteClient`(HTTP)에 구현 — SSH는 기본 폴백만, 미구현).

    **→ 결정(2026-09-04): 나머지 규칙까지 마저 포팅하는 쪽으로 확정 → ✅
    완료(2026-09-04)**. 이 머신에 설치된 실제 hg 7.2의
    `mercurial/discovery.py`(`checkheads`/`_postprocessobsolete`/
    `_nowarnheads`)와 `mercurial/bookmarks.py`(`validdest`)를 직접 읽어 남은
    두 예외를 마저 포팅했다.
    1. **obsolescence-marker 예외**(`discovery._postprocessobsolete`/
       `pushingmarkerfor`): 후보 새 head가 로컬 저장소 자신의 obsstore
       (`HgObsMarker`/`HgObsolescenceParser`로 읽음)에 predecessor로 기록돼
       있고 그 successor 체인이 이 체크의 후보 리비전 집합(이번에 push되는
       리비전 + 이미 아는 원격 head) 안의 다른 리비전에 닿으면, public
       phase가 아닌 한 새 head로 세지 않는다. 실제 hg 7.2로 직접 검증
       (2026-09-04): 이미 push된 head를 amend한 뒤 그 successor를
       `--force` 없이 push하면 성공하며, 심지어
       `experimental.evolution.exchange=no`로 obsolescence marker 자체를
       원격과 전혀 교환하지 않아도 성공한다 — 실제 hg의 client-side
       accept/reject 판단은 오직 push하는 쪽 저장소 자신의 obsstore에만
       의존하기 때문이다. hg4j의 push도 obsmarker를 전혀 교환하지
       않으므로(bundle1 한정) 이는 근사가 아니라 정확히 일치하는 동작이다.
    2. **bookmark-head 예외**(`discovery._nowarnheads`/
       `bookmarks.validdest`): 로컬 bookmark의 원격 쪽 이전 위치가 로컬에
       알려져 있고, 그 위치에서 현재 로컬 위치까지 "changelog 자식 관계"와
       "로컬 obsstore successor 관계"를 자유롭게 섞어 도달 가능하면
       (`obsutil.foreground`에 대응), 그 위치는 새 head 카운트를 늘리더라도
       거부 사유(blame)에서 제외한다. 단 이 예외는 새 named branch의
       multi-head 서브케이스(`remoteheads is None`)에는 적용되지 않는다 —
       실제 hg 소스가 그 분기에서는 `nowarnheads`를 빼지 않고 그대로
       씀(`checkHeadsPerBranch`가 이 구분을 그대로 반영). 실제 hg 7.2로
       직접 검증(2026-09-04): bookmark를 amend된 successor로 전진시키는
       push는 `--force` 없이 성공하지만, bookmark를 obsolescence 연결이
       전혀 없는 무관한 divergent head로 강제 이동하는 push는 여전히
       거부된다("push creates new remote head ... with bookmark") — 즉
       "bookmark가 달린 head는 무조건 봐준다"가 아니라 실제로 유효한
       forward move일 때만 예외가 적용됨을 확인했다.

    `PushCommand`의 head-count 로직을 개수(int) 비교에서 리비전 집합(Set)
    비교로 재작성해 두 예외를 반영했다(`applyObsolescenceDiscard`,
    `computeNowarnRevs`, `isInForeground`, `hasLiveSuccessorAmongCandidates`,
    `loadObsSuccessorMap`, `buildChildrenMap` 신설). **테스트**:
    `PushRealHgInteropTest`에 3개 추가 —
    `testPushSucceedsWhenObsoleteHeadReplacedBySuccessorWithoutForce`(obsolescence
    예외로 무강제 성공, 원격에 신구 head 2개가 그대로 남는 것까지 실제 hg로
    확인), `testPushOfBookmarkAdvancedAcrossAmendSucceedsWithoutForce`
    (obsolescence 기반 rewrite를 가로지르는 bookmark 전진 성공),
    `testPushRejectedWhenBookmarkMovedToDivergentSiblingWithoutObsolescenceLink`
    (obsolescence 연결이 없는 무관한 head로의 bookmark 이동은 여전히 거부) —
    전부 실제 hg 서버(`hg serve`)를 상대로 GREEN, 기존 5개 포함 전체
    GREEN. 전체 회귀 스위트(gradle test 전체)도 GREEN.
    **실제 버그 발견 및 수정(architecture-level 아님, PushCommand 내부
    로직 수정)**: (1) cg1 changegroup 패킹 시 각 그룹(changelog/manifest/
    파일별 filelog)의 **첫 엔트리**의 델타 베이스를 "로컬 rev 인덱스상
    바로 이전 리비전"으로 잘못 계산하고 있었다 — 실제 hg
    (`cg1unpacker._deltaheader`: `if prevnode is None: deltabase = p1`)는
    그룹의 첫 엔트리만 그 리비전 **자신의 실제 p1**을 베이스로 삼는다.
    선형 히스토리에서는 두 값이 우연히 같아 안 드러났지만, 여러 head가
    있는 저장소로의 push(특히 `--force`로 새 head를 만드는 push)처럼 첫
    신규 리비전의 진짜 부모가 "그 직전 로컬 rev"보다 앞선 경우 수신측
    해시 검증이 깨져 실제 hg 서버가 unbundle에 HTTP 500으로 응답했다(실제
    hg 서버로 재현, 2026-09-04). (2) HTTP `pushkey`가 `httppostargs` capability
    협상 결과에 따라 GET으로 나갈 수 있었는데, 실제 hg HTTP 서버는
    push 권한이 필요한 모든 명령을 **무조건 POST로만** 허용한다
    (`hgweb/common.py checkauthz`: `push requires POST request`; 실제 hg
    클라이언트도 `httppeer.py`에서 pushkey만 따로
    `args['data']=b''`로 강제 POST) — httppostargs 미협상 환경(host
    native hg 7.2의 기본 `hg serve`)에서 실제 hg 서버로 push하면 bookmark
    이동이 405로 조용히 실패했다(`PushCommand`가 bookmark 동기화 실패를
    비차단 경고로만 처리하는 기존 설계 때문에 예외가 삼켜져 있었음).
    `HgRemoteClient.pushkey()`를 항상 POST하도록 수정(인자 전달 위치는
    기존 GET-tier 로직 그대로 유지, HTTP 메서드만 강제 POST). 이 두
    버그를 고치기 전엔 기존 `PushCommandTest`/`HgArgProtocolTest` 등도
    이 경로를 실제 hg로 왕복 검증한 적이 없어 놓치고 있었다(하나는
    `resp.isEmpty()`를 성공으로 처리하는 관대한 폴백 때문에, 다른 하나는
    선형 히스토리만 다뤄서). **테스트**: `PushRealHgInteropTest`(신설,
    `src/test/java/io/github/search5/hg4j/transport/`) 5개 — 다중 head
    저장소로의 성공/거부/force, 새 branch 거부/허용, HTTP를 통한 실제 hg
    서버로의 bookmark 이동, no-changes push — 전부 GREEN. 기존
    `HgArgProtocolTest`/`CatUpdateClonePushCoverageTest`의 관련 테스트
    1개씩을 새로 확인된 정확한 스펙에 맞게 갱신(전자는 pushkey가 POST를
    써야 함을 반영, 후자는 `--force` 없이는 새 head 생성 push가 거부되는
    게 맞는 동작임을 반영).

    **branch**: ✅ **완료(2026-09-04)**. named branch 생성(`hg branch <name>`), 그
    브랜치로 커밋 후 `hg branches`/`hg branches --closed` 목록 정확성,
    `hg commit --close-branch` 후 해당 브랜치가 목록에서 빠지는지, 한 브랜치에
    head가 여러 개 생기는 시나리오(브랜치 내부 분기)와 `hg heads <branch>` 전부
    real hg 7.2.2(host native)와 왕복 검증했다.

    **발견한 실제 버그(1건, 수정 완료)**: `BranchesCommand`의 `hg branches` 목록
    정렬이 리비전 내림차순만 보고 있었는데, 실제 hg의 `branchmap.branches_info()`/
    `commands.branches()`는 `(active, rev, name, isOpen)` 튜플을 내림차순 정렬한다
    — 여기서 "active"는 "현재 워킹카피 브랜치"가 아니라 "그 브랜치의 최신 열린
    head가 저장소 전체 기준 위상 head(어디서도 자식이 없는 리비전)인가"라는,
    브랜치별이 아니라 저장소 전역 기준 개념이다. 리비전 번호만으로 정렬하면, 한
    브랜치(A)가 다른 브랜치(Z)의 자식 커밋으로 인해 inactive가 되었는데 그 A의
    리비전 번호가 다른 active 브랜치(Y, 더 낮은 리비전)보다 높은 경우 순서가
    어긋난다 — 실제 real hg 스크래치 저장소로 재현: `A`(rev2)가 `Z`(rev3)로 인해
    inactive가 되고 `Y`(rev1)는 그대로 active인 상황에서, real hg는
    `Z, Y, A, default`로 정렬하는데(active 우선) 옛 hg4j 로직은
    `Z, A, Y, default`로 정렬했다(리비전 내림차순만). `BranchesCommand.call()`의
    정렬 비교자를 real hg와 동일한 `(active, rev, name, isOpen)` 기준으로 고치고,
    `BranchHead`에 `isActive()` 필드를 추가해 이 정보를 노출했다
    (`src/main/java/io/github/search5/hg4j/api/BranchesCommand.java`).

    **기능 추가**: `HeadsCommand`에 `hg heads <branch>`에 해당하는 `setBranch(String)`/
    `setIncludeClosed(boolean)`를 새로 추가했다 — 기존에는 branch 필터 기능 자체가
    없었다(`src/main/java/io/github/search5/hg4j/api/HeadsCommand.java`).

    **`HeadsCommand` 기본 동작 브레이킹 체인지 ✅ 완료(2026-09-04)**: 위에서 확인된
    갭(`HeadsCommand.call()`의 필터 없는 기본 호출이 사실 real hg의 `hg heads --topo`
    시맨틱만 구현하고 있었음)에 대해 사용자가 "real hg의 실제 `hg heads` 시맨틱으로
    고친다(브레이킹 체인지 수용)"로 확정 결정. `mercurial/commands.py heads()` 소스를
    직접 실측(`/usr/lib/python3/dist-packages/mercurial`, hg 7.2 패키지)해 정확한
    알고리즘을 확인 후 그대로 이식: 인자 없는 기본 `call()`은 이제
    `repo.branchmap()`의 모든 브랜치를 순회하며 각 브랜치의 열린 head(들)를
    (`bm.branchheads(branch, closed=...)`처럼) 모아 리비전 내림차순으로 정렬해
    반환한다 — 브랜치 자체 head라면 저장소 전역 위상 리프가 아니어도 포함된다.
    옛 동작(저장소 전역 위상 리프만)은 `setTopo(boolean)`(기본값 `false`)로 명시적
    opt-in 가능하게 남겨뒀다(real hg의 `hg heads --topo`와 동일). `setIncludeClosed`도
    이제 필터 없는 기본 호출에 적용되도록 의미를 넓혔다(real hg의 `--closed`가
    `branchmap()` 순회 전체에 적용되는 것과 동일). 기존 콜러 재확인: `Hg.heads()`
    포셀린 진입점 외에는 아무도 `HeadsCommand`를 쓰지 않고, 내부 push/pull 로직
    (`PushCommand`/`PullCommand`/`HgLocalClient`)은 별도 head 계산을 쓴다는 문서의
    주장을 grep으로 재확인(import조차 없음) — 블라스트 반경은 `Hg.heads()` 콜러로
    한정됨. 기존 테스트(`HeadsCommandCoverageTest`, `PorcelainExtraCommandsTest`,
    `BranchRealHgInteropTest`)는 전부 단일 브랜치·단일 head 시나리오라 새 기본
    동작과도 결과가 동일해 변경 불필요였음(재확인만 함, 코드 수정 없음). **신규
    테스트**: `HeadsRealHgInteropTest`(신설, `src/test/java/io/github/search5/hg4j/api/`)
    3건 — (1) 브랜치 A의 head가 다른 브랜치로 머지되어 저장소 전역 위상 리프는
    아니지만 자기 브랜치 안에서는 여전히 head인 정확한 백로그 23 재현 시나리오에서
    hg4j의 새 기본 `call()`과 real hg의 맨 `hg heads`가 정확히 일치하고,
    `setTopo(true)`와 real hg `hg heads --topo`도 정확히 일치함을 확인, (2) 여러
    브랜치에 걸친 head 목록의 리비전 내림차순 정렬 순서까지 real hg와 정확히 일치,
    (3) `setTopo(true)`에서 closed head가 (real hg처럼) 여전히 위상 리프로 포함됨을
    확인 — 전부 real hg 7.2 host-native CLI 왕복 검증, GREEN. 전체 회귀 스위트
    2403건 중 신규 실패 없음(`ShelveRealHgInteropTest`의 무관한 1건 실패는 단독
    실행 시 GREEN으로 재확인된 병렬 실행 환경 문제로, 이 변경과 무관).

    **merge**: ✅ **완료(2026-09-04)**. fast-forward는 이미 부분 검증돼 있었고,
    진짜 3-way merge(공통 조상에서 양쪽이 다른 파일을 수정 — 충돌 없음)와 서로
    다른 브랜치 간 merge는 기존 `CHgMergeInteropTest`가 이미 real hg CLI와
    양방향 대조 중이었다(dev/default 두 브랜치, real hg `verify`/`cat`으로
    확인). 충돌이 실제로 나서 `resolve`가 필요한 case도 기존
    `MergeStateInteropTest`가 이미 양방향(실제 hg가 만든 `.hg/merge/state2`를
    hg4j가 읽기, hg4j가 만든 걸 real hg `resolve --list`가 읽기) 검증 중이었다
    — 이 셋은 "미검증"이 아니라 이미 완료돼 있던 것으로 재확인했다.

    남은 두 시나리오를 이번 세션에 신설 `MergeRealHgInteropTest`(3개 테스트)로
    검증: (1) rename/copy가 한쪽 브랜치에만 있는 상태의 merge — real hg로
    `a.txt`를 `b.txt`로 rename한 브랜치를 hg4j `MergeCommand`+`CommitCommand`로
    병합한 뒤, real hg `hg log --follow b.txt`가 rename 이전 이력까지 정상
    추적함을 확인(copy 추적 생존 확인 — 버그 아님, `CommitCommand`가 내용
    불변 파일은 원래 filelog 노드를 그대로 재사용하는 기존 로직 덕분에 이미
    성립하고 있었다). (2) merge 중단 — **`hg merge --abort`에 대응하는 명령이
    hg4j에 전혀 없었다**(신규 확인). `UpdateCommand.setForce(true)`로 우회하려
    해도 안 된다: `UpdateCommand`는 "기록된 이전 parent1 manifest"와 "target
    manifest"를 diff하는데, merge 직후 dirstate의 parent1은 이미 p1 그대로라
    diff가 텅 비어 아무것도 되돌리지 못한다.

    **기능 추가**: `MergeCommand.abort()` 신설 — real hg `hg merge --abort`와
    동일하게(real hg 7.2로 직접 재현해 확인: 다른 parent에서만 추가된 파일은
    삭제되고, 수정된 파일은 p1 내용으로 복원되며, 단일 parent로 복귀하고
    `.hg/merge/state2`가 삭제됨) 동작하도록 p1 manifest 기준으로 모든 경로를
    무조건 다시 쓴다(`UpdateCommand`처럼 diff에 의존하지 않는다 — 위 이유로
    diff가 항상 비어 있기 때문). 병합 중이 아닐 때 호출하면 real hg의
    `"abort: no merge in progress"`와 같은 취지로 거부한다
    (`src/main/java/io/github/search5/hg4j/api/MergeCommand.java`).

    **테스트**: `MergeRealHgInteropTest`(3개: copy 추적 생존, merge abort real
    hg 왕복 대조, 병합 중 아닐 때 abort 거부) 신설, 전부 GREEN.

    **tag**: ✅ **완료(2026-09-04)**. 전역 태그(`.hgtags`, 커밋되는 파일) 생성 후
    `hg tags`로 조회, 로컬 태그(`.hg/localtags`, 미커밋), 기존 태그를 재태깅(move,
    `.hgtags`에 새 줄 추가되고 이전 줄은 사문화), 태그 삭제(`hg tag --remove`),
    머지 커밋에 태그를 다는 경우 — 전부 real hg 7.2.2와 양방향(hg4j로 쓰고 real
    hg로 읽기, real hg로 쓰고 hg4j로 읽기) 왕복 검증했다.

    **기능 추가**: `TagCommand`에 로컬 태그 생성(`setLocal(boolean)`)과 태그 삭제
    (`setRemove(boolean)`)가 아예 없었다 — `TagsCommand`는 이미 `.hg/localtags`를
    읽고 nullid를 "삭제됨"으로 처리하는 로직이 있었지만, 그걸 만들어내는 쓰기 쪽
    (`TagCommand`)에는 그런 옵션 자체가 없어서 로컬 태그/태그 삭제 시나리오를
    hg4j만으로는 재현할 방법이 없었다. 두 옵션을 추가해 real hg와의 왕복 검증이
    가능해졌다(`src/main/java/io/github/search5/hg4j/api/TagCommand.java`).

    **발견한 실제 버그(2건, 수정 완료)**:
    1. `TagCommand`가 태그 커밋 메시지에 40자리 전체 hex를 썼는데, real hg의
       `commands.tag()`는 `mercurial.node.short()`(12자리 축약 hex)를 쓴다
       (`"Added tag %s for changeset %s" % (names, short(node))`). real hg
       interop 테스트(`hg4jGlobalTagIsRecognizedByRealHgTags`)가 커밋 메시지를
       실제 `hg log`로 대조하다가 이 불일치를 바로 잡아냈다 — hg4j↔hg4j 자체
       왕복이었다면 두 쪽 다 같은(틀린) 40자리를 썼을 것이므로 못 잡았을 시나리오.
    (같은 세션에서 위 **branch** 항목의 `BranchesCommand` 정렬 버그도 별도로
    발견·수정했다 — 태그 자체와는 무관한 문제.)

    **범위 내 확인**: `TagCommand`의 태그 나열(인자 없이 호출)은 여전히 `.hgtags`만
    읽는 단순화된 모델이고(`TagsCommand`처럼 `.hg/localtags`/`tip` 의사 태그를
    합치지 않음) — 이는 기존에 이미 클래스 문서에 명시된 의도된 단순화이며 이번
    범위에서 고치지 않았다(쓰기 경로에만 local/remove를 추가했고, 읽기 경로 검증은
    `TagsCommand`를 사용). 기존 태그가 있을 때 `-f` 없이 재태깅을 거부하는 real
    hg의 게이트(`tag '%s' already exists`)도 hg4j에는 없다(항상 허용) — 이 역시
    기존부터 있던 설계이고 백로그 23번 범위 텍스트가 명시적으로 요구한 부분이
    아니라 이번 세션에서는 변경하지 않았다.

    **branch/tag 테스트**: 신설 `BranchRealHgInteropTest`(6개 테스트: hg4j 생성
    브랜치/커밋 인식, 정렬+active 플래그, `--closed` 정렬(별도 시나리오), close-branch
    후 목록 제외, 브랜치 내부 분기 2-head, closed head 기본 제외)와
    `TagRealHgInteropTest`(8개 테스트: 전역 태그 양방향, 로컬 태그 양방향+같은
    이름 전역 태그보다 우선, 재태깅/move, 태그 삭제 양방향, 머지 커밋 태그)를
    `src/test/java/io/github/search5/hg4j/api/`에 추가, 전부 GREEN. 격리된 빌드
    디렉터리로 전체 회귀(2371개 테스트, 0 실패/에러, 10 skip은 기존부터 있던
    무관한 skip) 및 `io.github.search5.hg4j.api.*` 패키지 단독 재확인(1013개 테스트,
    0 실패/에러)도 통과.

    **rebase/shelve/bisect/strip/subrepo — 애초 "이미 다뤄졌으니 제외"로 초안에
    적었다가, 실제로는 아니라는 걸 위키 재확인으로 발견해 정정**: 이 5개는 지금까지
    받은 검증이 전부 "코드 리뷰로 버그 패턴 발견 → TDD RED/GREEN으로 hg4j 내부
    재현·수정 → hg4j 자체 회귀 테스트 재실행"이었다(예:
    `StripCommand`/`BisectCommand`의 워킹 브랜치 미복원 버그, `ShelveCommand`의
    racy-write 감지 버그 — 전부 실제 버그를 잡아낸 유의미한 작업이지만, 결과물을
    **실제 hg CLI와 대조한 적은 없다**). `Subrepositories` 행은 근거 서술 없이
    표에 "✅"만 달려 있어 5개 중 가장 불확실 — 최우선 재확인 대상. 그래서 이
    5개도 아래와 동일한 "실제 hg CLI 왕복" 기준으로 이 항목 범위에 포함한다:
    `rebase`(충돌 있는/없는 리베이스, `--continue`/`--abort`, obsolescence
    마커가 실제 hg `hg log --hidden`에서 인식되는지), `shelve`(shelve →
    다른 작업 → unshelve 왕복이 실제 hg가 만든 shelve와 서로 호환되는지),
    `bisect`(`hg bisect good/bad`로 실제 hg와 나란히 이분 탐색해 같은 culprit에
    도달하는지), `strip`(strip 후 저장소를 실제 hg `hg verify`로 확인),
    `subrepo`(`.hgsub`/`.hgsubstate`를 낀 커밋/업데이트가 실제 hg의 subrepo
    처리와 일치하는지 — 표의 "✅"부터 재검증).

    **`subrepo` 카테고리 ✅ 완료(2026-09-04)** — 표의 "✅"는 실제로 근거 없는
    상태였음이 확인됨(진짜 버그 2건 발견·수정: `CommitCommand`에 subrepo 인식
    로직 자체가 전혀 없었고, `HgRepository.scanWorkingCopy()`가 서브저장소
    경계를 인식 못 해 체크아웃된 서브저장소 내부 파일이 부모 저장소 자신의
    추적 파일로 잘못 add/commit되는 심각한 버그였음). 4개 시나리오(파싱/
    커밋 시 자동 `.hgsubstate` 생성·real hg 인식/서브 리비전 변경 후 dirty-check
    거부 및 재귀 커밋/실제 hg가 만든 두 pin 리비전 사이 hg4j update) 전부 실제
    hg 7.2 CLI 양방향 대조 통과(`SubrepoRealHgInteropTest`, 신규 4건). 의도적
    발산과 범위 밖으로 남긴 것(CloneCommand의 재귀 서브저장소 clone 미구현 등)은
    코드 주석 및 위 gap table `Subrepositories` 행에 상세 기록. 전체 회귀
    2362건 GREEN. 상세 근거는 위 gap table의 `Subrepositories` 행 참고.

    **`rebase` 카테고리 ✅ 완료(2026-09-04)** — 이 5개 중 유일하게 hg4j
    자체 왕복조차 "커밋 메시지/changelog 부모 연결만 확인, 뒤바뀐 커밋의
    manifest/파일 내용은 한 번도 assert하지 않음"이었다는 게 실제 hg 대조
    과정에서 드러났다. 신설 `RebaseRealHgInteropTest`(4개 테스트)로 검증하다가
    **실제 버그 3건**을 발견·수정했다(전부 real hg가 만든 저장소를 hg4j
    `RebaseCommand`로 rebase한 뒤 real hg `verify`/`cat`/`debugobsolete`/
    `log --hidden`으로 대조하다가 나왔다 — hg4j 내부 왕복이었다면 절대 못
    잡았을 종류):

    1. **`stripRevisionsFrom`이 inline revlog를 전혀 고려하지 않고 있었다**
       (가장 심각). real hg는 작은 revlog(막 만들어졌거나 커밋이 몇 개 안
       되는 저장소의 manifest/filelog 대부분)를 별도 `.d` 데이터 파일 없이
       `.i` 파일 안에 헤더+데이터를 인터리빙해서 저장하는데(real hg 7.2로
       직접 확인: 2커밋짜리 저장소의 `00manifest.i`엔 `00manifest.d`가
       아예 없음), `stripRevisionsFrom`은 항상 "리비전 수 × 64바이트"로만
       `.i`를 자르고 있었다 — inline 저장소에서 이건 앞쪽 리비전들의 데이터
       바이트를 통째로 잘라버려 revlog를 깨뜨린다. hg4j 자체 테스트는 전부
       hg4j `CommitCommand`로 만든(항상 non-inline인) 저장소만 써서 이
       경로를 한 번도 밟지 않았다. `Revlog`에 `isInline()`/`getFileOffset(int)`
       공개 접근자를 추가하고, `RebaseCommand.stripRevisionsFrom`을 두
       레이아웃 모두를 올바르게 절단하는 공용 헬퍼로 재작성
       (`src/main/java/io/github/search5/hg4j/storage/Revlog.java`,
       `src/main/java/io/github/search5/hg4j/api/RebaseCommand.java`).
    2. **rebase로 새로 추가된 파일이 결과 커밋의 manifest에서 통째로 사라짐**
       (데이터 손실, 버그 1을 고친 뒤에야 드러남). `RebaseCommand.cherryPickBackup`은
       cherry-pick하는 모든 파일을 dirstate 상태 `'n'`(변경 없음)으로 기록하는데,
       `CommitCommand`의 "변경 없음" 분기는 그 경로가 두 parent 중 어느 쪽
       manifest에도 없으면(= target에 없던 완전히 새 파일) 그냥 아무것도 안
       하고 넘어가는 else-분기가 없었다 — 결과 manifest에 그 파일 항목 자체가
       빠졌다. `CommitCommand`에 "`'n'`으로 기록됐지만 두 parent 어디에도 없는
       경로는 unchanged일 수 없으니 강제로 해시해서 새 filelog 리비전을
       만든다"는 가드를 추가해 고쳤다(`RebaseCommand` 전용이 아니라
       `CommitCommand` 공통 로직 버그였다 —
       `src/main/java/io/github/search5/hg4j/api/CommitCommand.java`).
    3. **obsolescence marker를 물리적 strip과 동시에, 항상 무조건 남긴다** —
       real hg는 evolution이 꺼져 있으면(기본값) rebase 때 marker를 아예 안
       남기고 strip만 하며, evolution이 켜져 있으면 strip 없이 marker만
       남긴다(원본이 "hidden revision"으로 남아 `hg log --hidden`에서 보임).
       hg4j는 이 둘을 동시에 해서, marker가 가리키는 predecessor가 changelog에서
       완전히 사라진 상태가 된다 — `hg log --hidden`으로 찾으면 "hidden"이
       아니라 "unknown revision" 에러가 난다(real hg 7.2로 직접 재현해
       확인). 게다가 evolution을 쓸 생각이 전혀 없는 사용자가 평범한 rebase를
       기대하고 hg4j를 썼더라도, 이후 그 저장소에 대한 모든 real hg 명령이
       `"obsolete" feature not enabled but 1 markers found!` 경고를 stdout에
       찍는 부작용이 생긴다(real hg의 `experimental.evolution` 클라이언트
       설정에 의해 결정되는 것이라 저장소 쪽 `.hg/requires`로 끌 수 있는
       종류가 아님도 확인 — `obsstore`를 requires에 넣으면 real hg가 아예
       "unknown requirement"로 저장소를 못 엶). **이 부분은 고치지 않고 현재
       동작을 `RebaseRealHgInteropTest`에 그대로 문서화만 해뒀다** — strip과
       marker 동시 존재는 real hg가 절대 하지 않는 조합이라 (a) marker를
       아예 안 남기고 순수 strip만 하거나(원래 설계 의도인 "완전한 물리적
       strip 기반 rebase"에 가장 가까움) (b) strip을 그만두고 marker만
       남기는 evolution 방식으로 전환하는 두 방향 중 하나를 선택해야 하는데,
       **이건 이 세션 판단으로 정할 architecture 결정이 아니라 사용자
       확인이 필요하다**(아래 "아키텍처 수준 확인 필요" 참고).

    **→ 결정(2026-09-04): 둘 다 "정석대로 완전 구현" 확정** — 위 두 아키텍처
    질문(obsolescence marker의 strip-and-mark 동시 수행 여부, rebase conflict
    감지+3-way merge+`--continue`/`--abort` 구현 여부) 모두 사용자가 "지름길 없이
    정석대로 완전히 구현하라"고 명시적으로 확정했다. 같은 날 늦게 별도 세션에서
    두 항목 다 구현 완료.

    **4. evolution-only로 전환(물리적 strip 제거) ✅ 완료** — `RebaseCommand`는
    이제 원본 리비전을 절대 물리적으로 strip하지 않는다. cherry-pick된 원본은
    changelog/manifest/filelog에 영원히 완전한 형태로 남고, `HgObsMarker.writeMarker`
    (predecessor → successor)만 기록된다 — real hg 자신의 두 상호배타적 전략(marker
    없이 순수 strip, 또는 strip 없이 marker만) 중 후자와 정확히 일치, 이전처럼 둘을
    동시에 하지 않는다. `stripRevisionsFrom`/`computeTruncateSizes`/`restoreBackup`/
    `BackupCommit.fileContents` 등 "전체 [minOrigRev,tip] 구간을 통째로 strip한 뒤
    재구성"하던 옛 설계 전체를 삭제 — 원본이 사라지지 않으므로 "독립 브랜치를
    물리적으로 복원"할 필요 자체가 없어져 코드가 크게 단순해졌다. 신설
    `originalRevisionIsHiddenNotGoneAfterRebase` 테스트로 real hg 7.2 CLI 직접
    검증: `hg log --hidden -r <원본>`이 이제 "unknown revision"이 아니라 원본
    노드를 그대로 찾고(`{desc}`/`cat` 내용도 원본 그대로), 반대로 `--hidden` 없는
    평범한 `hg log`/`hg log -G`에는 나타나지 않는다(살아있는 non-obsolete
    successor가 있어 기본적으로 숨김) — real hg의 evolution 기반 rebase와 동일한
    결과.
    - **이 전환 과정에서 드러난 별도의 심각한 버그(예상 밖)**: `Revlog.appendRevision`이
      새 리비전의 nodeId를 `SHA1(p1,p2,content)`로 계산해두고도, 그 nodeId가 **이미
      해당 revlog에 존재하는지 전혀 확인하지 않고 무조건 append**하고 있었다. strip
      기반 설계에서는 rebase 시작 전에 filelog를 통째로 잘라내 버려서 이 경로를 밟은
      적이 없었지만, evolution-only로 바뀌어 원본이 그대로 남으면서 "target에 없던
      완전히 새 파일을 cherry-pick"하는 흔한 경우(parent 없음 + 원본과 동일한 내용
      → 원본과 SHA1 입력이 완전히 같음)마다 **동일한 nodeId를 가진 filelog 리비전
      2개**가 생겨 real `hg verify`가 즉시 "`duplicate revision 1 (0)`"/"`not in
      manifests`" integrity error로 잡아냈다(`RebaseRealHgInteropTest`의
      `conflictFreeRebaseVerifiedByRealHg`로 실제 hg 7.2 검증 중 발견). Real hg
      자신의 `revlog.addrevision`/`filelog.add`가 항상 하는 "동일 (parents,content)
      조합이 이미 있으면 기존 리비전을 재사용"을 `Revlog.appendRevision`에 추가해
      수정 — `RebaseCommand`뿐 아니라 같은 메서드를 쓰는 `ImportCommand`/
      `HisteditCommand`/`CommitCommand` 전부가 이 잠재 버그의 수혜자다(전체 회귀
      그대로 GREEN 확인됨). 상세: `src/main/java/io/github/search5/hg4j/storage/Revlog.java`
      `appendRevision`.

    **5. 진짜 3-way merge 충돌 감지 + `continueRebase()`/`abort()` ✅ 완료** —
    `RebaseCommand`의 cherry-pick 경로가 이제 `MergeCommand`와 같은 `Merge3` 엔진으로
    실제 3-way merge를 시도한다: ancestor = 원본 리비전 자신의 parent가 갖고 있던
    파일 내용, local = 현재 목적지(dest, 체인의 이전 cherry-pick 결과 포함)의 내용,
    other = 원본 리비전이 새로 만든 내용. 정말 겹치면(자동 병합 불가) 충돌 마커를
    작업 파일에 쓰고(`<<<<<<< dest` / `=======` / `>>>>>>> source`, real hg 7.2의
    기본 `internal:merge` 마커와 byte-for-byte 일치 — base 섹션 없음, 직접 재현해
    검증) `io.github.search5.hg4j.errors.HgMergeConflictException`(충돌 경로 목록
    포함, 새 `getConflictPaths()` 접근자 추가)을 던지며 rebase를 일시정지한다.
    충돌 파일 상태는 `MergeCommand`가 이미 쓰는 것과 완전히 같은 real-hg 호환
    포맷(`io.github.search5.hg4j.merge.MergeState`, `.hg/merge/state2`)에 기록되므로
    real hg CLI `hg resolve --list`가 그 결과를 그대로 읽어 "U f.txt"를 보여준다
    (직접 검증). `RebaseCommand`에 새 공개 메서드 2개 추가:
    `continueRebase()`(사용자가 파일을 수동으로 고치고 저장한 뒤 호출 — 일시정지된
    리비전의 커밋을 완료하고 남은 큐를 이어서 처리, 다음 리비전도 충돌하면 다시
    `HgMergeConflictException`으로 정지) / `abort()`(이번 rebase 시도로 이미 커밋된
    것까지 전부 포함해 changelog/manifest/filelog를 rebase 시작 전 바이트 그대로
    복원하고 작업 사본·dirstate도 원래 체크아웃 상태로 되돌림, real hg의 `hg rebase
    --abort`와 동일). 두 메서드 모두 **디스크에 영속화된 상태**(`.hg/rebasestate-hg4j`,
    hg4j 전용 텍스트 포맷 — real hg 자신의 바이너리 `.hg/rebasestate`와는 무관, 중간
    재개 상태 자체의 real-hg interop은 목표가 아니었고 최종 상태만 real hg와
    맞으면 됨)로 동작하므로, 처음 충돌을 만난 것과 **다른 새 `RebaseCommand`
    인스턴스**로도 이어서 호출 가능(직접 검증). 미해결 충돌이 남았는데
    `continueRebase()`를 부르거나, 진행 중인 rebase가 없는데 `abort()`/
    `continueRebase()`를 부르면 real hg의 "abort: no rebase in progress"와 같은
    취지로 `HgValidationException`을 던진다.
    - **부수 발견**: `.hg/merge/state2`만 지우고 완료 처리하면, 사용자가 중간에 실제
      `hg resolve --mark`를 돌려서 real hg 자신이 함께 써둔 레거시 v1
      `.hg/merge/state` 파일이 남아 있어 `hg resolve --list`가 완료 후에도 "R f.txt"를
      계속 보여주는 문제가 있었다(real hg의 `mergestate.read()`가 state2 없으면 v1로
      폴백) — `.hg/merge` 디렉터리 전체를 지우는 것으로 수정.

    상세 구현 위치: `src/main/java/io/github/search5/hg4j/api/RebaseCommand.java`
    (cherry-pick당 실제 diff 계산 + 3-way merge는 `cherryPickRevision`, 병합
    commit들은 `processQueue`/`finalizeRebase`, 일시정지 상태 직렬화는
    `writeRebaseState`/`readRebaseState`), `src/main/java/io/github/search5/hg4j/merge/Merge3.java`
    (충돌 마커 라벨을 커스텀할 수 있는 새 오버로드 `merge(base,yours,theirs,yoursLabel,theirsLabel)`
    추가, 기존 `merge(base,yours,theirs)`는 `"Yours"/"Theirs"` 기본값으로 위임 —
    `MergeCommand`는 그대로 옛 동작 유지), `src/main/java/io/github/search5/hg4j/errors/HgMergeConflictException.java`
    (기존에 정의만 되고 아무도 안 쓰던 클래스를 이제 실제로 사용 — 복수 충돌 경로를
    담는 `List<String>` 생성자/`getConflictPaths()` 추가).

    **테스트**: `RebaseRealHgInteropTest`를 6개로 재작성(`conflictFreeRebaseVerifiedByRealHg`,
    `plainRealHgCommandsDoNotWarnAfterHg4jRebase`는 유지, `originalRevisionIsHiddenNotGoneAfterRebase`
    가 옛 `obsoleteMarkerAfterRebaseStripPointsAtNodeGoneFromChangelog`를 대체(정반대
    결과를 검증하도록), `conflictingEditWritesConflictMarkersAndPausesRebase`가 옛
    `conflictingEditIsSilentlyOverwrittenInsteadOfDetectedAsConflict`를 대체, 신규
    `abortAfterConflictRestoresPreRebaseState`/`continueRebaseAfterManualResolutionCompletesTheRebase`
    추가) — 전부 real hg 7.2 CLI 왕복 기준, 전부 GREEN. 기존 `RebaseCommandTest`/
    `RebaseCommandCoverageTest`/`HgAdvancedHistoryTest`도 옛 물리적 strip 전제(정확한
    리비전 개수 등)에 맞춰 갱신, 격리된 빌드 디렉터리(`/tmp/backlog-rebase-overhaul`)로
    전체 회귀 재실행해 GREEN 확인.

    **범위(제외)**: 위 10개 카테고리 전부 **hg4j↔hg4j 자체 왕복이 아니라 실제 hg
    CLI와의 양방향 대조**(hg4j로 만든 결과를 실제 `hg log`/`hg verify`/`hg tags`/
    `hg branches`로 확인, 또는 그 반대)를 검증 기준으로 삼는다 — 이미 이런 형태로
    실제 hg와 대조하지 않고 hg4j 내부끼리만 왕복 검증된 기존 테스트는 이 항목에서
    "미검증"으로 간주하고 다시 본다.

    **진행 상황(2026-09-04)**: `commit`/`push` ✅ 완료(각 섹션 참고, 병렬 세션).
    나머지 `branch`/`merge`/`tag`/`rebase`/`shelve`/`bisect`/`strip`/`subrepo`
    8개는 다른 병렬 작업에서 처리 중이거나 아래 "다음 세션 시작점" 그대로
    미착수.

    **다음 세션 시작점**: 착수 비용 기준으로는 `tag`(코드 경로는 오늘 커버리지
    작업으로 이미 확인 끝, interop 껍데기만 씌우면 됨)가 가장 낮지만, **위험도
    기준으로 최우선이었던 `subrepo`는 2026-09-04 완료**(위 완료 노트 참고) —
    남은 9개 카테고리(commit/push/branch/merge/tag/rebase/shelve/bisect/strip)
    는 여전히 미착수 상태로 남아 있음.

    **`shelve`/`bisect`/`strip` — ✅ 완료(2026-09-04, 별도 병렬 에이전트)**. 실제 hg CLI
    양방향 대조 기준으로 재검증, 실제 버그 4개 발견·수정. `rebase`/`subrepo`는 다른
    병렬 작업에서 별도로 처리 중(이 세션에서는 건드리지 않음).

    - **`strip`**: `StripCommand.truncateRevlog()`의 `.d`(데이터) 파일 truncate가 정확한
      오프셋이 아니라 "`datFile.length() * keepCount / (keepCount+1)`"라는 근사치
      추정("Safe estimation fallback")을 쓰고 있었다 — 리비전 크기가 들쭉날쭉하면 살아남을
      리비전의 델타 바이트를 잘라버리는 **실데이터 파괴 버그**. `StripRealHgInteropTest`로
      크기가 크게 다른 리비전들을 strip한 뒤 real `hg verify`를 돌려서 실제로 재현시켰다
      ("data length off by N bytes"/"partial read of revlog" 등). `changelog.getIndexRecord
      (keepCount).getOffset()`(이미 `ShelveCommand.stripRevisionsFrom`이 쓰던 정확한 방식)로
      교체해 수정. 같은 검증 과정에서 발견된 부수 버그 2개도 함께 수정: (1) `StripCommand`가
      obsolescence marker를 무조건 쓰는데, real hg는 `experimental.evolution.createmarkers`
      config가 꺼진 채로 markers를 쓰면 `hg debugobsolete` 자체를 거부하고, real hg 7.2.2로
      직접 확인한 결과 markers가 있는데 그 config가 꺼져 있으면 이후 `hg verify`가 "obsolete
      feature not enabled but N markers found!"로 플래그한다 — `HgObsMarker.writeMarker()`가
      최초로 marker를 쓸 때 repo `.hg/hgrc`에 그 config를 심어두도록 고쳤다(amend/graft/
      rebase/histedit도 같은 헬퍼를 공유하므로 함께 수정됨 — 공유 코드 변경이니 병합 시
      확인 필요). (2) (미수정, 별도 기록) hg4j는 파일 크기와 무관하게 항상 non-inline
      filelog(`.i`/`.d` 분리)를 쓰는데 real hg는 작은 revlog를 inline으로 유지한다 — 그
      차이 때문에 real `hg verify`가 hg4j가 만든 저장소에 대해 "`warning: revlog 'X.d' not
      in fncache!`" 경고를 내지만(strip과 무관하게 **모든** hg4j 커밋에 존재하는 사전
      버그), 이건 exit code에 영향 없는 경고(`self._warn`, `self.errors`엔 안 들어감)라
      strip의 "real hg verify 통과" 기준 자체는 막지 않는다 — fncache에 `.d` 항목을
      등록하는 CommitCommand 쪽 수정은 이번 범위 밖으로 남겨둠. 테스트:
      `StripRealHgInteropTest`(2개, real `hg verify`/`hg cat`으로 strip 후 내용 무결성
      확인) 신설.
    - **`bisect`**: `BisectCommand`의 이분 탐색 알고리즘(이미 real hg `hbisect.py`와 맞춰
      구현돼 있었음)을 진짜로 real hg와 나란히(동일 good/bad 오라클로 각자 독립 진행) 15개
      리비전 선형 히스토리에서 실행 — 후보 리비전 시퀀스(`[7,10,8,9]`)와 최종 culprit이
      완전히 일치함을 확인. 버그 없음(기존 코드 리뷰 기반 구현이 처음부터 맞았음). 테스트:
      `BisectRealHgInteropTest`(1개) 신설. **미검증으로 남은 부분(정직히 기록)**: merge
      커밋이 있는 DAG(브랜치 2개가 합쳐지는 히스토리)에서의 bisect는 real hg와 대조하지
      않음 — 시간 제약으로 선형 히스토리 1개 시나리오만 검증.
    - **`shelve`**: 가장 규모가 컸다. 실제 hg가 만든 `.hg`/`.patch`/`.shelve` 파일을
      hexdump·real hg 소스(`mercurial/shelve.py`/`bundle2.py`/`exchange.py`/
      `changegroup.py`)와 직접 대조해 **hg4j의 기존 shelve 포맷이 real hg와 완전히
      호환 불가능**함을 확인했다(자세한 내용은 최종 보고 참고). 다음 실버그 3개를 고쳐
      "shelve → 다른 작업 → unshelve" 왕복(양방향)이 real hg와 실제로 맞물리게 만들었다:
      (1) `.hg` 번들이 아예 매직 헤더 없는 hg4j 전용 원시 포맷이라 real hg가 번들로
      인식조차 못함 → `Bundle2Parser.wrapChangegroupInBundle2()`(기존 push 경로 인프라
      재사용)로 HG20/bundle2 봉투를 씌우도록 수정. (2) 델타가 항상 "빈 문자열 기준"으로
      인코딩돼 있어(수정된 파일도 매번 전체 내용을 "복사 없이 삽입"하는 델타), 그 파일이
      hg4j 자기 자신의 (비표준) 재생 로직과만 맞물렸고 real hg의 표준 cg1 델타 적용
      의미론(선언된 delta base 기준으로 복원)과는 애초에 안 맞음 → 실제 parent/base
      콘텐츠 기준으로 정식 델타를 인코딩하도록 `performShelve()` 전면 수정, `performUnshelve
      ()`도 대응 수정. (3) real hg의 cg02+ changegroup 엔트리는 `p1`(진짜 changelog
      부모)과 `deltabase`(델타가 실제로 기준하는 리비전, 둘이 다를 수 있음 — real hg
      shelve가 실제로 `deltabase=null`(full-text 델타)인데 `p1`은 진짜 이전 리비전을
      가리키는 엔트리를 만드는 것을 real hg CLI로 직접 확인)이 별개 필드인데 hg4j는 이
      구분을 몰랐음 → `deltaBaseNode()` 헬퍼로 `deltabase` 우선, 없으면 `p1` 폴백하도록
      수정. real hg의 shelve bundle이 "shelve commit 하나만"이 아니라 draft 상태인 부모
      커밋까지 함께 묶는다는 것도 발견(`mutableancestors()`가 자기 자신+아직 draft인
      조상까지 포함) → `.shelve` info 파일(real hg 포맷 `node=<hex>` 그대로, `performShelve
      ()`가 이제 항상 씀)로 진짜 shelve 커밋 엔트리를 골라내도록 수정. hg4j 자신의
      `.state` 파일 기반 왕복은 100% 하위 호환 유지(기존 `ShelveCommandTest`/
      `ShelveCommandCoverageTest` 전부 그대로 GREEN). 테스트: `ShelveRealHgInteropTest`
      (4개 — hg4j→real hg 수정+추가 파일, real hg→hg4j 수정+추가 파일, 제거 파일 시나리오,
      모두 "shelve → 중간에 무관한 다른 작업 → unshelve" 형태) 신설, 전부 GREEN.
      **→ 결정(2026-09-04): 지금 구현하는 쪽으로 확정**(rebase 3-way merge 인프라
      이식과 함께 진행, 위 "아키텍처 결정 6건" 참고) — **✅ 완료(2026-09-04, 같은 날
      후속 세션)**.

      **진짜 rebase 기반 unshelve ✅ 완료** — `ShelveCommand.performUnshelve()`가
      real hg의 실제 `_dounshelve()` 알고리즘(`mercurial/shelve.py`)으로 전면 재작성됐다:
      (1) 셸브 번들을 원래 shelve 당시의 parent(`p1Hex`) 위에 **진짜(그러나 일회용)
      커밋**으로 복원(기존 per-file 번들 디코드 로직은 그대로 재사용 — 이번엔 그 결과를
      working copy에 바로 노출하는 대신 `CommitCommand`로 실제 커밋한다), (2) 그 사이
      작업 디렉터리 parent가 이동했으면(다른 커밋이 생겼으면) `RebaseCommand.setSource
      (tempCommit).setTarget(currentWdParent).call()`로 **진짜 rebase**(3-way merge
      충돌 감지 포함, 새로 구현하지 않고 항목 5의 `RebaseCommand`를 그대로 구동) —
      아무 일도 없었으면(parent가 그대로면) no-op. (3) 결과를 real hg의 `cmdutil.revert
      (shelvectx)`와 동일하게 **"uncommit"**: 최종(rebase 성공 시 rebase 결과, 아니면
      복원 커밋 자체)의 매니페스트를 진짜 현재 작업 디렉터리 parent의 매니페스트와
      diff해서 그 차이만 working copy에 pending 변경(added/modified/removed)으로
      다시 얹는다 — 이 diff는 shelve 당시 캡처해둔 파일 상태(`.state` 파일의
      add/modify/remove 분류)가 아니라 **매번 새로 계산**하므로, 그 사이 커밋이 같은
      파일을 건드린 경우도 올바르게 처리된다. (4) 일회용 복원/rebase 커밋은 **완전히
      지운다** — real hg CLI로 직접 확인(2026-09-04): real hg 자신의 unshelve는 이
      전체 과정을 트랜잭션 안에서 수행하고 마지막에 그 트랜잭션을 abort하므로, 임시
      커밋이 hidden/obsolete 리비전으로도 전혀 남지 않는다(`RebaseCommand`가 평소
      쓰는 evolution-only 방식과 달리, 이번엔 marker 없이 `stripRevisionsFrom`으로
      물리적 truncate). 새 hg4j 전용 상태 파일 `.hg/shelvedstate-hg4j`(name/
      tempCommitNode/originalWdParent 3줄)에 진행 상황을 영속화해, **`RebaseCommand`와
      동일한 패턴**(새 `ShelveCommand` 인스턴스로도 재개 가능)으로 두 공개 메서드를
      추가했다: `unshelveContinue()`(충돌 해결 후 재개 — 내부적으로
      `RebaseCommand.continueRebase()`를 그대로 위임 호출한 뒤 같은 uncommit+strip
      마무리를 수행) / `unshelveAbort()`(real hg의 `hg unshelve --abort`와 동일 —
      `RebaseCommand.abort()`로 진행 중이던 rebase를 걷어낸 뒤 임시 커밋도 strip하고
      작업 디렉터리를 진짜 unshelve 시작 전 상태로 완전히 되돌리되, **완료된 unshelve와
      달리 shelve 자체는 지우지 않아** 나중에 다시 시도할 수 있게 남겨둔다 — 직접
      검증). 두 메서드 모두 `.hg/rebasestate-hg4j`/`.hg/shelvedstate-hg4j`만으로
      동작하므로 이름/상태를 다시 지정할 필요가 없다.
      - **불변식 하나 새로 추가**: unshelve 시작 시 현재 작업 디렉터리에 pending
        변경(added/modified/removed)이 있으면 `HgValidationException`으로 거부한다
        (real hg는 `_commitworkingcopychanges()`로 그런 변경까지 임시 커밋해서 흡수하는
        일반화된 경로가 있지만, hg4j는 아직 그 일반화를 구현하지 않음 — 명시적 범위
        축소, 새 아키텍처 갈림길은 아님). 현재 parent2가 0이 아니면(미해결 merge 진행
        중) 마찬가지로 거부.
      - **공유 코드에서 발견·수정한 실제 버그 3개(이번 재작성이 처음 밟은 코드
        경로라 여태 안 걸렸던 것들)**:
        1. `ShelveCommand.stripRevisionsFrom()`(옛날부터 있던, `performShelve()`
           자신의 일회용 임시 커밋을 지우는 헬퍼)가 `.i` truncate 크기를 항상
           `rev * 64`로 계산 — real hg의 **inline revlog**(작은 revlog는 리비전
           데이터를 `.d` 파일 없이 `.i` 파일 안에 헤더 바로 뒤에 직접 붙여 쓰는 기본
           포맷)에서는 완전히 틀린 오프셋이라 레코드 중간을 잘라 저장소를 깨뜨린다.
           이 버그는 지금까지 **hg4j 자신이 만든(항상 non-inline) 저장소에서만
           `stripRevisionsFrom`이 호출됐기 때문에** 한 번도 걸리지 않았다 — unshelve가
           **real hg가 만든(따라서 inline인) 저장소** 위에서 처음으로 실제 임시
           커밋+strip을 수행하면서 `ShelveRealHgInteropTest.realHgShelveCanBeUnshelvedByHg4j`
           가 `HgCorruptDataException: Truncated inline revlog data`로 바로 재현시켰다.
           `Revlog.isInline()`/`Revlog.getFileOffset(rev)`(이미 읽기 경로에 존재하던
           API)로 changelog/manifest/파일별 filelog 전부 inline 여부에 따라 분기하도록
           수정.
        2. `CommitCommand.call()`의 "M-2 racy-hg 체크"(같은 초 안에 재기록된 파일을
           dirstate 캐시만으로 오탐지하지 않기 위한 보정)가 "현재 filelog의 **위치상
           마지막** 리비전"과 디스크 내용을 비교해 다르면 "변경됨"으로 판정하고
           있었다 — 이 리비전이 지금 커밋 중인 리비전의 **진짜 parent가 아닌** 경우
           (예: 이번 unshelve의 rebase 단계에서, 목적지가 안 건드린 파일을 shelve
           쪽 내용으로 fast-forward할 때, 그 filelog가 그 사이 생겼다 지워질 임시
           복원 커밋 때문에 이미 최신 리비전을 하나 더 갖고 있는 경우) "마지막
           리비전과 같으면 무변경"이라는 잘못된 결론을 내려 fast-forward된 내용이
           통째로 드롭됐다(`ShelveRealHgInteropTest.unshelveRebasesOntoAnUnrelatedInterveningCommit`
           로 재현). "위치상 마지막"이 아니라 **실제 parent(들)이 그 경로에 대해
           기록한 매니페스트 해시**(`manifestP1`/`manifestP2`)의 콘텐츠와 비교하도록
           수정 — 병합 커밋에서는 P1/P2 중 **어느 한쪽이라도** 일치하면 무변경으로
           남겨 기존 바이트 단위 disambiguation 로직에 그대로 맡기고, 어느 쪽도
           읽을 수 없으면(filelog가 지워지는 등, 기존 커버리지 테스트가 의도적으로
           재현하는 상황) 마찬가지로 무변경으로 남겨 disambiguation의 기존 관용적
           fallback에 위임 — 전체 회귀(`CommitCommandCoverageTest` 포함) 그대로
           GREEN 확인.
        3. `StatusCommand`가 dirstate 엔트리의 mtime을 real hg의 표준 "ambiguous
           time" 센티널(32비트 "-1", 즉 `0xFFFFFFFF` — real hg가 내부적으로 워킹
           카피를 재작성한 직후 등, 캐시된 타임스탬프를 신뢰할 수 없을 때 쓰는 값)을
           전혀 특별 취급하지 않고 디스크 mtime과 그냥 숫자 비교해, real hg가 만든
           그런 엔트리를 hg4j가 읽을 때마다 무조건 "modified"로 오판했다
           (`ShelveRealHgInteropTest.realHgShelveCanBeUnshelvedByHg4j`가 이번에
           `new StatusCommand().call()`을 unshelve 시작 시 실제로 호출하면서 처음
           노출됨). 해당 센티널이면 무조건 실제 부모 커밋 내용과 바이트 비교하도록
           고쳤고, 거꾸로 `ShelveCommand` 자신도 unshelve가 새로 만들어내는 pending
           변경 엔트리(`finishUnshelve`, 상태 `'n'`인 것)에 이제 이 센티널을 쓴다 —
           실제 mtime을 즉시 기록해 "운 좋게 시간 창 안에 들어오면 통과"하던 기존
           방식(real hg의 `hg status`가 오탐하는 별도 타이밍 경쟁까지 새로 만들어냄,
           일회성 diff-replay보다 이번 rebase 기반 알고리즘이 I/O를 훨씬 많이 하므로
           그 경쟁을 더 자주 놓침)을 real hg 자신의 관례로 완전히 대체해 경쟁 자체를
           없앴다.

      **테스트**: `ShelveRealHgInteropTest`에 3개 신설 —
      `unshelveRebasesOntoAnUnrelatedInterveningCommit`(shelve → 무관한 커밋 →
      unshelve 성공, 진짜 rebase가 실행됨을 real hg `status`/`verify`/`log`로 확인,
      작업 디렉터리 parent가 그 무관한 커밋 그대로 유지됨도 확인), `unshelveWithConflictingInterveningCommitPausesResolvesAndContinues`
      (shelve → 같은 줄을 다르게 고치는 충돌 커밋 → unshelve가 `HgMergeConflictException`
      으로 일시정지, 마커가 real hg 7.2 `internal:merge`와 byte-for-byte 일치, real
      `hg resolve --list`로 확인 → 수동 해결 → **새 `ShelveCommand` 인스턴스**의
      `unshelveContinue()`로 완료, real `hg verify`/`status`로 확인), `unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable`
      (같은 충돌 시나리오에서 `unshelveAbort()`로 작업 디렉터리/dirstate가 unshelve
      시작 전 상태로 완전히 복원되고 shelve 자체는 그대로 남아 재시도 가능함을 확인).
      기존 `ShelveCommandTest`/`ShelveCommandCoverageTest`/`HgAdvancedHistoryTest`/
      `ShelveRealHgInteropTest` 기존 3개도 전부 그대로 GREEN(단, `ShelveCommandTest`의
      "손상된 parent1/parent2로 unshelve 시도" 서브케이스 2개는 옛 "shelve 당시
      parent와 정확히 일치해야 함" 검증이 이번에 rebase 기반으로 대체되며 의미가
      바뀌어 기대 예외 메시지만 갱신 — "does not match shelved parent" → 존재하지
      않는 리비전에 대한 "not found", "does not match shelved parent2" → "unresolved
      merge"; `ShelveCommandCoverageTest`의 `unshelveDefaultsToModifiedStateWhenFileMissingFromStateMetadata`
      도 최종 dirstate 상태 기대값만 `'m'`(hg4j 자체 관례, real hg에 없는 상태 문자)
      → `'n'`(real hg의 표준 "추적+수정됨" 상태, 위 diff 재계산 방식이 자연스럽게
      만들어냄)으로 갱신). 격리된 빌드 디렉터리(`/tmp/backlog-shelve-unshelve`)로
      `Shelve`/`Commit`/`Status`/`Rebase`/`Graft`/`Merge` 전체 및 전체 테스트
      스위트(249개 테스트 클래스) 재실행해 GREEN 확인.

      상세 구현 위치: `src/main/java/io/github/search5/hg4j/api/ShelveCommand.java`
      (`performUnshelve`/`finishUnshelve`/`checkoutFullClean`/`unshelveContinue`/
      `unshelveAbort`/`stripRevisionsFrom`), `src/main/java/io/github/search5/hg4j/api/CommitCommand.java`
      (M-2 racy 체크), `src/main/java/io/github/search5/hg4j/api/StatusCommand.java`
      (`AMBIGUOUS_TIME`).

24. ~~**`HgHttpWireServer`/`HgSshWireServer`가 외부 프로세스의 저장소 변경을 못 보고
    stale `Revlog` 캐시를 계속 서빙함**~~ — ✅ **완료(2026-09-03)**. 백로그 22번
    검증 중 발견된 것을 정식 백로그로 승격 후 바로 수정.

    **방향 결정**: 자동 stale 감지 + 갱신(사용자 승인, "운영 제약 문서화만" 대신
    선택).

    **근본 원인(먼저 재현 테스트로 정확히 특정)**: 처음엔 `HgRepository.getRevlog()`가
    캐싱한 `Revlog`/`RevlogIndex`가 아예 재확인을 안 하는 문제인 줄 알았으나,
    실제로는 `RevlogIndex.checkAndUpdate()`가 매 읽기마다 디스크 크기를 재확인하는
    기존 로직 자체는 있었다 — 문제는 그 로직의 `addedRecords.isEmpty()` 가드였다.
    `addedRecords`는 로컬 쓰기가 있을 때마다 채워지고 **한 번도 다시 비워지지
    않는다**(`clearCache()`/`loadIndex()`에서만 비워짐) — 그래서 `serverRepo`가
    자기 자신으로 단 한 번이라도 커밋한 적이 있으면(이 세션 테스트들의 `setUp()`이
    항상 그랬듯), 그 RevlogIndex 인스턴스는 그 시점부터 **영원히** 외부 변경
    재확인을 건너뛴다. 이 가드 자체는 StripCommand/RebaseCommand/HisteditCommand가
    truncate 직후 같은 핸들을 재사용하는 시나리오에 필요한 것이라(주석 참고)
    함부로 손대면 위험 — 그래서 `RevlogIndex.checkAndUpdate()`는 그대로 두고,
    **더 상위 계층**에 새 메커니즘을 추가했다.

    **구현**: `HgRepository.refreshIfChangedOnDisk()` 신설 — changelog 파일
    (`00changelog.i`)의 크기와 mtime을 둘 다 비교(크기만으로는 부족 — changelog-v2
    저장소는 docket 파일 크기가 고정이고 `index_end`/`data_end` 필드만 in-place로
    갱신되므로)해서 바뀌었으면 `clearRevlogCache()`로 캐시 전체를 무효화, 다음
    접근 시 완전히 새로운 `RevlogIndex`가 만들어지므로 `addedRecords`도 다시
    빈 상태로 시작한다(기존 로직을 안 건드리고 우회). `HgHttpWireServer.handle()`
    맨 앞과 `HgSshWireServer.handleConnection()`의 명령 루프 맨 앞에서 호출 —
    매 HTTP 요청/SSH 명령마다 자동으로 확인.

    **검증**: 기존 `HgHttpWireServerRealHgInteropTest`의 멀티 브랜치/북마크/태그
    테스트에서 수동 `clearRevlogCache()` 호출을 제거해도 그대로 통과함을 확인.
    SSH 쪽은 기존 테스트들이 전부 연결마다 `HgRepository`를 새로 여는 테스트
    하네스 패턴이라(그래서 이 버그를 애초에 재현할 수 없었음) 이 문제를 실제로
    검증하는 신규 테스트
    `realHgSeesExternalRepoChangesAcrossConnectionsOnALongLivedSshServer`를
    추가(HTTP 테스트처럼 하나의 `HgRepository`/`HgSshWireServer`를 여러 SSH
    연결에 걸쳐 재사용하는 `SharedRepoHgWireCommand` 신설) — 자동 감지가 SSH
    쪽에서도 동작함을 확인. 전체 회귀 2358 테스트, 실패 0.

    **검증 중 발견한 별개의 새 버그**: 백로그 25번 참고.

25. ~~**외부에서 새 브랜치에 커밋된 파일의 내용이 clone 시 전달되지 않음**~~ —
    🟢 **오탐으로 확인·종결(2026-09-04)**. hg4j 버그가 아니라 **real Mercurial
    자체의 정상 clone 동작**이었다.

    **조사 경과**: `HgLocalClient.getBundle()`이 만든 raw 번들 바이트를 직접
    파싱·델타 재구성해서 대조한 결과, changelog/manifest/filelog(b.txt 포함)
    전부 완벽하게 정확히 패킹돼 있었다(재구성한 매니페스트 텍스트가 서버 원본과
    바이트 단위로 일치). `hg clone --debug`로 실제 와이어 트래픽까지 확인한
    결과 — **b.txt.i가 클라이언트의 `.hg/store/data/`에 정상적으로 도착해
    있었다**(unbundle 자체는 완전히 성공). 문제는 그 다음 단계: real hg
    클라이언트가 clone 직후 자동으로 수행하는 체크아웃이 `updating to branch
    default`를 출력하며 저장소 전체 tip(방금 만든 "feature" 브랜치 커밋)이 아니라
    **"default" 브랜치의 tip만** 작업 디렉터리에 반영하고 있었다 — b.txt는
    "feature" 브랜치에서 추가됐으므로 체크아웃 대상에서 제외된 것.

    **확인**: hg4j를 완전히 배제하고 순정 `hg`끼리만(`hg init` → 커밋 → `hg
    branch feature` → 커밋 → `hg clone --debug`) 똑같은 시나리오를 재현하니
    **바이트 하나 다르지 않게 동일한 결과**(`updating to branch default`,
    작업 디렉터리에 `a.txt`만 존재)가 나왔다 — real hg 자신의 문서화된 clone
    기본 동작(별도 `-u`/`--updaterev` 옵션이 없으면 저장소 전체 tip이 아니라
    **"default" 브랜치의 tip**을 체크아웃)임을 확정. hg4j는 이 시나리오에서
    처음부터 끝까지 완전히 올바르게 동작하고 있었다 — `hg log`/`hg branches`/
    `hg update feature` 등으로 확인하면 b.txt가 정상적으로 보였을 것.

    **교훈**: 백로그 24번 검증 테스트를 짤 때 "clone 후 새로 추가된 파일이 작업
    디렉터리에 있어야 한다"는 가정 자체가 named-branch 시나리오에서는 틀린
    가정이었다 — real hg의 실제 동작을 먼저 확인하지 않고 직관적으로 "당연히
    되어야 할 것"을 단언에 넣었던 것이 원인. 백로그 24번의 테스트들은 이 잘못된
    단언을 이미 제거하고 changelog/branch 메타데이터만 검증하도록 수정된 상태라
    문제 없음.

26. ~~**hg4j 자체 changegroup 생성/적용 경로가 cg1/무sidedata로 제한됨**~~ —
    ✅ **완료(2026-09-04)**. 백로그 11번 "남은 gap"에 번호 없이 있던 것을 메인
    에이전트가 직접 재확인 후 승격, 사용자 확인 후 두 부분(생성 쪽 버전 협상 +
    적용 쪽 sidedata 반영) 모두 완전히 구현.

    **1부(생성 쪽 버전 협상)**: `HgLocalClient.getBundle()`이 실제로
    `bundleCaps`를 읽어 협상하도록 배선. 실측(mercurial/exchange.py,
    2026-09-04): 클라이언트가 `bundlecaps`에 `"HG2"`로 시작하는 토큰을 하나도
    안 보내면(legacy) 버전은 무조건 `"01"`이고 응답도 봉투 없이 맨 cg1
    바이트 그대로 — 이게 바로 이 백로그의 근본 원인이었다: `Wire1Commands.
    capabilitiesString()`이 `bundle2=` 토큰을 전혀 광고하지 않았기 때문에, 실제
    hg 클라이언트의 `remote.capable('bundle2')`가 항상 false가 되어 legacy
    경로(`_pullchangeset`)로만 빠졌고, 그 경로는 `bundlecaps` 인자 자체를 아예
    안 보낸다(hg4j `getbundle` 핸들러가 뭘 하든 무관하게 cg1 확정) — 실제로
    `Wire1Commands.getbundle`에 임시 로깅을 심어 real `hg clone` 요청을 직접
    캡처해 확인. 그래서 `capabilitiesString()`에 `Bundle2Parser.
    buildBundle2CapsToken("01,02,03,04,05")`를 추가해 bundle2 자체를 광고하게
    고쳤고, 그 결과 실제 hg 7.2 클라이언트가 `_pullbundle2` 경로로 넘어가
    자신의 기본 `changegroup=01,02,03` 목록(cg4/cg5는 클라이언트가 설정 없이는
    절대 광고 안 함)을 `bundle2=<blob>` 안에 실어 보내는 것도 그대로 캡처
    확인. `HgLocalClient.getBundle()`은 이제 `Bundle2Parser.
    requestsBundle2()`/`decodeChangegroupVersions()`(신설, `urlutil.
    b2_caps_from_bundle_caps`/`decode_b2_caps` 실측 이식)로 이 값을 읽어
    `max(요청 목록 ∩ {01..05})`를 고르고(교집합이 비었거나 bundle2 미요청이면
    실제 hg와 동일하게 `"01"`), `ChangegroupParser.writeBundle`을 그 버전으로
    직접 호출해 패킹한다 — `writeEntry`/`writeBundle`는 기존 cg4/cg5 전용이던
    걸 cg1/cg2/cg3 헤더 레이아웃(각각 80/100/102바이트, `parseGroup`의 읽기
    쪽과 대칭)까지 지원하도록 확장하고, `writeBundle`의 `manifestsend` 종료
    청크도 "버전이 tree-capable(03+)인가"로 올바르게 분기하도록 고쳤다(예전엔
    무조건 붙여서 cg1/cg2에 쓰면 스트림이 깨졌을 버그). 응답은 bundle2
    요청이었으면 항상 `Bundle2Parser.wrapChangegroupInBundle2`로 HG20 봉투에
    감싸고, 아니면 기존 `"HG10UN"` 관례를 그대로 유지.

    **부수적으로 드러난 필수 작업(같은 캡ability 플래그 하나가 양방향을 다
    좌우하는 real hg 자신의 설계 때문에 회피 불가능했음, `exchange.
    _forcebundle1`)**: `bundle2=`를 광고하자 실제 hg 클라이언트의 **push**도
    자동으로 bundle2 프로토콜로 전환돼(`_pushbundle2`) body가 맨 cg 바이트가
    아니라 HG20 봉투가 되고, 응답도 HG20 봉투([reply:changegroup]/[error:abort]
    파트)여야만 했다 — 이 배선 없이 광고만 켰더니 기존
    `HgHttpWireServerRealHgInteropTest`의 push 테스트들이 즉시 "abort: not a
    Mercurial bundle"로 재현됐다(2026-09-04 직접 확인). `HgLocalClient.
    pushWithHooks()`가 HG20 요청을 `Bundle2Parser.extractChangegroupDetailed`
    로 언랩하도록, `Wire1Commands.unbundle()`이 요청이 bundle2였으면 응답도
    `Bundle2Parser.buildChangegroupReplyBundle2`/`buildEmptyBundle2Reply`/
    `buildErrorAbortBundle2`(전부 신설, `bundle2_part_handlers.
    handlechangegroup`/`wireprotov1server.unbundle`의 예외 처리 실측 이식)로
    만들도록 고쳤다. HTTP는 real hg의 `streamreslegacy`(비압축)와 `streamres`
    (압축)가 다른 처리라는 것도 실측으로 확인해 `Wire1Response`에 `STREAM_
    UNCOMPRESSED` 종류를 신설(`HgHttpWireServer`는 비압축으로, `HgSshWireServer`
    는 기존 `STREAM`과 동일하게 무프레이밍으로 처리 — SSH엔 애초에 압축
    구분이 없음).

    **2부(적용 쪽 sidedata 반영)**: `Revlog.appendChangeGroupEntry()`가
    `index.isV2()`를 전혀 확인하지 않고 항상 v1 전용 수동 바이트 라이팅
    경로(64바이트 레코드)로 썼던 것을 고쳐, v2 revlog(가장 흔하게는
    `exp-copies-sidedata-changeset`가 켜진 changelog)에는 기존
    `appendRevisionV2`(로컬 커밋용으로 이미 있던 메서드, 96바이트 레코드 +
    `.sda` 반영)를 그대로 재사용하도록 분기 추가 — `entry.sidedata`(cg5의
    `CG_FLAG_SIDEDATA`로 온, 이미 `SidedataCodec`이 쓰는 것과 같은 포맷의
    원시 컨테이너 바이트)를 그대로 넘기면 로컬 커밋이 만드는 것과 동일한
    온디스크 상태가 된다. 이건 sidedata 문제만이 아니라 더 넓은 사전 존재
    버그였다: 이 분기가 없었던 예전 코드는 v2 revlog에 pull/push로 들어오는
    아무 리비전이나 다 v1 레이아웃으로 깨뜨렸을 것이다(사이드 이펙트로 sidedata도
    통째로 버려짐). 대칭으로, `HgLocalClient.getBundle()`도 이제 cg5로 패킹할
    때 소스 저장소가 `isSidedataCopies()`면 changelog 엔트리에 `SD_FILES`
    sidedata를 실어 보내도록(있으면) 배선해 생성 쪽도 손실 없이 왕복되게 했다.

    **검증(전부 real hg CLI 기반, hg4j-내부 왕복만으로는 불충분하다는 지시에
    따름)**:
    - `HgHttpWireServerRealHgInteropTest`/`HgSshWireServerRealHgInteropTest`의
      기존 clone/pull/push 테스트 전부 그대로 통과(위 부수 작업 포함 회귀
      없음).
    - 신규 `realHgCloneWithDefaultCapabilitiesNegotiatesAboveCg1OnTheWire`
      (`HgHttpWireServerRealHgInteropTest`): 실제 `hg clone`이 기본 설정
      그대로 hg4j 서버에 요청할 때, `HttpExchange`를 얇게 감싸(프로덕션 코드
      변경 없이) 실제 `?cmd=getbundle` 응답 바이트를 그대로 캡처 → inflate →
      `Bundle2Parser.extractChangegroupDetailed`로 봉투 안 `CHANGEGROUP` 파트의
      `version` 파라미터를 직접 읽어 `"01"`이 아님을 확인(실측: 실제 hg 7.2
      클라이언트의 기본 `changegroup=01,02,03` 목록과 hg4j의 협상 로직이 만나
      `"03"` 선택).
    - 신규 `PullSidedataRealHgInteropTest`(part 2 전용): 소스/대상 저장소 둘 다
      `hg --config format.exp-use-copies-side-data-changeset=yes init`으로
      부트스트랩(hg4j가 아직 이 포맷을 처음부터 만들진 못하는, 백로그 19에서도
      이미 문서화된 별개 gap이라 real hg로 부트스트랩만 하고 커밋은 전부
      hg4j가 함 — `SidedataFilesWriteTest`와 동일 패턴), hg4j `CommitCommand`로
      rename(`a.txt`→`b.txt`)+신규 파일(`c.txt`) 커밋 → `FetchCommand`가 실제
      pull에 쓰는 것과 동일한 `bundleCaps`로 `HgLocalClient.getBundle()` 직접
      호출(hg4j↔hg4j HTTP는 `HgRemoteClient`가 wireprotocol v2로 자동 승급해
      버려 이 백로그가 다루는 v1/cg5 경로 자체를 안 타므로 의도적으로 우회) →
      `05` 협상 확인 → `entry.sidedata` 존재 확인 → `FetchCommand.applyBundle`
      로 적용 → 적용된 저장소에서 `SidedataChangedFilesCommand`로 소스와 동일한
      added/removed/copiedFromP1(`b.txt`←`a.txt`)을 읽어냄을 확인 → 마지막으로
      `hg debugchangedfiles 1`/`hg verify`를 적용된 저장소에 대해 직접 돌려
      real hg 자신도 동의함을 확인(hg4j 자체 리더와의 자기정합성이 아니라
      스펙 정합성 검증).
    - 전체 회귀 스위트 2402 테스트, 실패/에러 0(스킵 10 — 도커 기반 인터롭
      테스트 등 환경 의존, 기존과 동일).

    **발견했지만 이 백로그 범위 밖으로 남겨둔 것**: real hg 자신의 wireprotocol
    v1 `getbundle` 서버 구현은 `remote_sidedata`를 아예 안 넘겨서(2026-09-04
    `wireprotov1server.py`/`exchange.py` 실측) **실제 hg를 서버로 한 일반
    wire `getbundle` 요청에서는 cg5여도 SD_FILES가 전송되지 않는 것으로
    보인다** — 이번 검증에서 hg4j↔hg4j 대신 real-hg-bootstrap-only 시나리오를
    쓴 이유이기도 함. hg4j 쪽 생성/적용 배선은 모두 완료됐으므로, 실제 hg
    자신의 이 제약이 풀리거나 다른 소스(예: hg4j가 만든 cg5)가 쓰이면 그대로
    작동한다.

~~27. **`hg log --follow`/annotate가 sidedata 기반 copy-tracing과 연동되지 않음 —
    신규, 2026-09-04 발견(백로그 17번 "남은 gap"에 번호 없이 있던 것을 메인
    에이전트가 직접 재확인 후 승격), 미착수. 백로그 23번 완료 후 즉시 진행.**~~
    ✅ **완료(2026-09-04)**.

    **조사 경과 — 사용자가 지정한 "sidedata로 보강"이라는 전제가 실제로는
    틀렸음을 확인**: 실제 `hg`(7.2) 소스(`mercurial/copies.py`
    `usechangesetcentricalgo()`, `mercurial/filelog.py` `renamed()`,
    `mercurial/context.py` `filectx._copied`)를 직접 읽고, 로컬 시스템 `hg`로
    평범한 `hg init` 저장소를 만들어 `hg debugformat`을 찍어 확인한 결과
    (`copies-sdc: no`, `changelog-v2: no`) — **real hg의 `--follow`/`annotate`는
    기본적으로 changelog sidedata(`SD_FILES`, 백로그 17/19번)를 전혀 읽지 않는다.**
    `usechangesetcentricalgo()`는 저장소가 `format.use-changelog-v2`+
    `exp-copies-sidedata-changeset` requirement로 명시적으로 만들어진 경우에만
    참이 되고, 평범한 `hg init` 저장소(이번 검증에 쓴 것 포함, 사실상 실사용
    중인 거의 모든 저장소)는 항상 거짓이다. 실제로 쓰이는 기본 메커니즘은 **파일로그
    수준의 `copy`/`copyrev` 메타데이터**(`filelog.renamed()`) — 각 filelog
    리비전 데이터 앞에 붙는 `\x01\n...\x01\n` 메타데이터 헤더에 저장되며,
    rename/copy 대상 파일은 항상 새 filelog를 리비전 0부터 시작하므로(부모
    파일리비전과 내용이 무관하다고 보고 델타를 안 만듦) 이 메타데이터는 항상
    그 filelog의 리비전 0에만 존재한다. 다행히 **이 계층은 hg4j에 이미 절반
    구현돼 있었다** — `CommitCommand`(551-556행 근처)가 `dirstate.getCopyMap()`을
    보고 커밋 시 이미 `copy`/`copyrev` 메타데이터를 filelog에 쓰고 있었고(백로그
    17/19번보다 먼저 존재), `storage.Revlog.getRevisionMetadata(rev)`도 이미
    그 헤더를 파싱해 되돌려주고 있었다 — 다만 `api` 패키지의 그 무엇도 그
    reader를 소비하지 않고 있었다(이번에 직접 확인). 즉 실제 gap은 "sidedata
    연동 부재"가 아니라 "이미 존재하는 filelog 메타데이터 reader를
    `LogCommand`/`AnnotateCommand`가 안 쓰고 있었다"였다 — 사용자에게 이
    발견을 있는 그대로 보고하고, 사용자 지시대로 "실제 hg가 안 쓰는 계층을
    억지로 sidedata 기반으로 구현"하지 않고 real hg와 실제로 일치하는 filelog
    메커니즘으로 구현했다.

    **구현**: (1) `LogCommand.setFollowPath(String path)` 신규(빌더 패턴,
    `setFollowAncestors(true)`를 암묵적으로 켬) — 지정한 경로의 filelog를
    조회해 그 리비전들의 linkRev가 시작 리비전(옵션 미지정 시 tip)의 조상
    집합(`ChangesetGraph.getAllAncestors`) 안에 있는 것만 모으고, 그 filelog의
    리비전 0의 linkRev까지 조상 범위 안에 들어오면(= 실제로 그 파일의 origin까지
    거슬러 올라갔으면) 리비전 0의 `copy` 메타데이터를 읽어 이전 경로로 갈아타
    반복 — 이렇게 모은 리비전 집합을 새 `computeFollowPathRevs()` 헬퍼로 계산해
    `call()`의 기존 `allowedRevs` 필터링 경로에 그대로 꽂았다(기존
    `followAncestors`용 필터링 로직과 나란히 배치, 서로 배타적). (2)
    `AnnotateCommand`는 내부적으로 `(path, targetRev)`별 순수 재귀 헬퍼
    `traceLines()`/`tryCrossRenameBoundary()`로 리팩터링 — 기존엔 "origin
    리비전"을 **현재 filelog 안의 리비전 인덱스**로만 추적하다 맨 마지막에
    한 번 linkRev로 변환했는데, 그 방식은 베이스라인이 다른 filelog(rename
    source)에서 온 경우 표현이 불가능했다. 새 방식은 "origin changelog linkRev"를
    처음부터 끝까지 직접 들고 다니고, 파일 리비전 0의 `copy` 메타데이터가
    있으면 그 `copyrev`가 가리키는 정확한 소스 filelog 리비전으로 재귀
    호출해 그 결과를 베이스라인으로 삼은 뒤 나머지는 기존 LCS 기반 forward
    diff 알고리즘을 그대로 재사용한다 — 별도 `--follow` 플래그 없이 항상 이렇게
    동작(실제 `hg annotate`에 그런 플래그가 없는 것과 동일).

    **검증**: 실제 `hg` 7.2 CLI로 `add old.txt` → 커밋 → `hg mv old.txt new.txt`
    → 커밋 → `new.txt` 내용 수정 → 커밋 시나리오를 만들어 오라클로 사용.
    `hg log --follow new.txt --template "{rev} {desc}\n"`은 `2 modify new.txt`/
    `1 rename to new.txt`/`0 add old.txt` 세 리비전 전부(rename 이전 리비전
    포함)를 반환했고, `hg annotate -u new.txt`는 rename에서 살아남은 두 줄을
    rename 전 커미터(Alice)에게 정확히 귀속시켰다(`Alice: line1`/`Alice: line2`/
    `Carol: line3`) — hg4j의 동일 시나리오(`LogCommandTest.followPathCrossesRenameBoundaryToOldPath`,
    `AnnotateCommandCoverageTest.annotateFollowsRenameBoundaryToOriginalAuthor`)가
    바이트 단위로 이 출력과 일치함을 확인. `hg copy`(rename 아닌 순수 copy)
    경계도 별도 케이스로 검증(`annotateFollowsCopyBoundaryButLeavesOriginalUntouched`
    — 복사본은 원본 커미터로 귀속되고, 원본 파일 자신의 annotate는 영향받지
    않음). rename 없는 평범한 `--follow <path>`(`followPathWithoutRenameBehavesLikePlainFollow`)와
    존재한 적 없는 경로(`followPathOnNeverExistingPathReturnsEmpty`)도 커버.
    전체 회귀 2405 테스트, 실패 0.

    **알려진 스코프 한계(문서화, 정확성 결함 아님)**: 이 구현은 rename 목적지의
    filelog 리비전 0(=그 filelog의 유일한 origin, 실제 hg의 `filelog.renamed()`가
    지원하는 정확히 그 범위)에서만 copy 경계를 확인한다 — 이는 "출발 경로가
    같은 조상 라인 안에서 한 번 이상 재사용된" 병적인 케이스(예: 나중에 무관한
    커밋이 같은 옛 경로명을 다시 만드는 경우)까지 완벽하게 `copyrev` 노드
    단위로 구분하지는 않는다(대신 조상 집합 필터링에 의존). 실제 hg의
    `_tracefile`/`_fullcopytracing`이 다루는 병합 커밋의 양쪽 부모 서로 다른
    copy 등 더 복잡한 케이스도 이번 스코프 밖.

28. ~~**Narrow clone / LFS — 실제 hg CLI interop 검증 누락(근거 없는 bare `✅`)**~~
    — ✅ **완료(2026-09-04)**. gap table의 `Narrow clone / narrowspec`과
    `LFS (largefiles)` 두 행이 근거 서술 없는 bare `✅`였던 것을 — 23번 항목이
    `Subrepositories` 행에 대해 했던 것과 정확히 같은 방식으로 — 실제 hg 7.2
    CLI와 대조 검증했다. **양쪽 다 실제 버그를 발견·수정했다.**

    **Narrow clone (`NarrowCloneCommand`, `HgTreeFilter`)**: 이 호스트의 hg 7.2는
    `narrow` 확장이 기본 비활성(`--config extensions.narrow=`로 켜야 함)이지만
    설치는 돼 있음을 먼저 확인. 실제 hg CLI로 `hg clone --narrow --include/--exclude`
    를 여러 조합(단순 include, include+exclude, 형제 디렉터리, `rootfilesin:`,
    include 없음)으로 실행해 산출물을 직접 조사(`mercurial/narrowspec.py` 소스도
    함께 읽음), hg4j `NarrowCloneCommand`가 만든 산출물과 대조한 결과 **완전히
    다른 포맷/위치**였음을 확인:
    - narrowspec 파일 위치: hg4j는 `.hg/narrowspec`에 썼지만 실제 hg는
      `.hg/store/narrowspec`(+ 작업카피 미러 `.hg/narrowspec.dirstate`)에 쓴다.
    - `.hg/requires` 키: hg4j는 `"narrowspec"`을 썼지만 실제 hg는
      `"narrowhg-experimental"`을 쓴다.
    - narrowspec 파일 포맷: hg4j는 `[includes]`/`[excludes]`(복수형, 원본 문자열
      그대로)를 썼지만 실제 hg는 `[include]`/`[exclude]`(단수형), 각 패턴은
      `path:`/`rootfilesin:` kind가 붙고 끝 슬래시가 제거된 정규화된 형태만
      허용한다(`glob:`/`re:` 같은 다른 kind는 실제 hg가 `abort: invalid prefix on
      narrow pattern`으로 거부함을 실측 확인).
    - 매칭 규칙: hg4j `HgTreeFilter.createPathPrefixFilter`는 단순
      `String#startsWith`라서 `include=["srcdir"]`가 이름이 비슷한 형제 디렉터리
      `srcdirextra/`까지 잘못 매치하는 **실제 버그**였다(실제 hg로
      `srcdir`/`srcdirextra` 둘 다 있는 저장소를 narrow clone해서 실측 확인 —
      real hg는 `srcdirextra/` 쪽을 절대 포함시키지 않음). 또한 include가 하나도
      없으면 실제 hg는 `matchmod.never()`(아무 것도 매치 안 함)를 쓰는데, hg4j는
      "전부 매치"로 반대로 동작했다.

    이 네 가지를 전부 TDD로 수정: `HgTreeFilter`에 `NarrowPattern`/
    `normalizeNarrowPattern()`(kind 정규화·검증, 실제 hg의
    `narrowspec._validatepattern()` 규칙 — `.`/`..`/빈 컴포넌트 거부, 지원 안
    하는 kind prefix 거부 — 그대로 재현)와 `createNarrowSpecFilter()`(경로
    컴포넌트 경계를 지키는 `path:` 매칭, `rootfilesin:`의 직계 자식만 매치하는
    규칙, exclude 우선, include 없으면 전부 거부)를 신설 — 기존
    `createPathPrefixFilter`는 narrow 외 다른 8곳 호출부(status/log/diff/files
    등)가 의존하는 "include 없으면 전부 허용" 기본값을 그대로 유지해야 해서
    건드리지 않고 별도로 분리. `NarrowCloneCommand`가 새 정규화/필터/파일
    위치·포맷을 전부 쓰도록 재작성. **narrowspec 밖 리비전/파일이 이후 pull에서
    필터링되는지**는 `FetchCommand`가 이미 파일그룹 단위로 `treeFilter`를 적용하고
    있어(줄 490 부근) 새 필터로 그대로 이어지는 것을 확인했지만, hg4j는 narrowspec을
    저장소 상태로 다시 읽어들이는 통합 코드가 전혀 없어서(narrow clone 시점에만
    그 자리에서 필터를 만들어 쓰고 끝 — pull/update 등 다른 명령이 나중에
    narrowspec 파일을 다시 파싱해 자동으로 재적용하는 경로가 없음) "narrow
    저장소에 대한 진짜 wire-protocol 수준의 후속 narrow pull"(ellipsis node 등)
    양방향 검증은 이번 범위에서 시도하지 않았다 — 이는 구현 자체가 없는 기능이라
    버그가 아니라 완성도 격차이며, 별도 백로그로 다룰 만한 규모(정직하게 명시).
    대신 (1) hg4j가 만든 narrow clone을 실제 hg CLI(`hg tracked`/`hg files`/
    `hg status`)로 열어서 완전히 인식하는지, (2) 실제 hg가 만든 narrow clone의
    narrowspec 텍스트를 hg4j의 새 `normalizeNarrowPattern`/`createNarrowSpecFilter`
    로 파싱·매칭했을 때 실제 hg가 실제로 체크아웃한 파일 목록과 판정이 정확히
    일치하는지를 `NarrowCloneRealHgInteropTest`(3개 시나리오)로 검증 — 전부 GREEN.

    **LFS (`HgLfsManager`, `HgLfsPointer`)**: 이 호스트에 `lfs` 확장이 실제로
    동작함을 먼저 확인(`hg --config extensions.lfs= version` 성공,
    `hgext/lfs/blobstore.py` 소스 확인 가능) — 백로그 문서가 우려했던 "환경에
    없을 수 있음"은 기우였고, 전체 read-side 왕복을 실제로 검증할 수 있었다.
    실제 hg로 `[lfs] threshold`를 낮게 잡고 큰 파일을 커밋한 뒤:
    - 포인터 파일 텍스트 포맷(`version`/`oid sha256:`/`size` 줄 + 알파벳순 부가
      필드 `x-is-binary`)은 hg4j `HgLfsPointer.parse()`가 그대로 정확히 파싱함을
      확인 — **버그 없음**.
    - 로컬 blob 저장 경로는 **실제 버그**였다: `HgLfsManager.getLocalPath()`가
      Git-LFS 스타일 2단계 샤딩(`objects/XX/YY/ZZZZ...`)을 쓰고 있었는데, 실제
      hg의 `hgext/lfs`(`blobstore.py`의 `lfsvfs.join()`: "split the path at
      first two characters, like: XX/XXXXX...")는 1단계 샤딩만
      쓴다(`objects/XX/YYYYY...`) — hg4j와 실제 hg가 같은
      `.hg/store/lfs/objects/`를 공유해도 서로 blob을 절대 못 찾는 버그였다.
      TDD로 수정(단일 레벨 샤딩으로 변경).

    수정 후 `LfsRealHgInteropTest`(2개 시나리오)로 양방향 확인: ① 실제 hg가
    커밋한 LFS 파일의 filelog 포인터 텍스트를 hg4j `Revlog`로 직접 읽어
    `HgLfsPointer.parse()`로 파싱하고, 실제 hg가 로컬에 캐시해둔 blob을 수정된
    `HgLfsManager.getLocalPath()`로 정확히 같은 경로에서 찾아 원본과 동일한
    바이트를 읽어냄. ② hg4j `HgLfsManager.cacheObject()`로 로컬 store에 쓴 blob을
    real hg 자신의 `hg cat`이 그대로 읽어냄(경로/포맷이 hg4j가 만든 것도
    아니고 real hg 자신이 실제로 소비할 수 있는 형식임을 확인).

    **정직하게 기록해야 할 미검증/미구현 부분**: hg4j는 LFS를 커밋/체크아웃
    파이프라인에 전혀 연결하지 않았다(`CommitCommand`/`UpdateCommand`/`AddCommand`
    어디에도 `HgLfsManager`/`HgLfsPointer` 참조가 0건, revlog의
    `REVIDX_EXTSTORED` 플래그도 다루지 않음, `.hgrc`의 `[lfs] threshold` 자동
    감지도 없음) — `HgLfsPointer`/`HgLfsManager`는 완전히 독립된 유틸리티
    라이브러리다. 그래서 "hg4j가 LFS 커밋을 만들고 실제 hg가 읽는다"는 리버스
    방향은 그 자체가 존재하지 않는 기능이라 테스트할 방법이 없었다 — 이번
    항목의 범위(검증 + 발견한 버그 수정)를 넘어서는 별도의 기능 구현(커밋/체크아웃
    파이프라인 전체 연결)이 필요한 항목이라 착수하지 않고 이 사실만 기록한다.
    원격 HTTP blob store 업로드/다운로드(배치 API)는 `HgLfsManager.fetchObject()`
    에 이미 구현돼 있고 기존 `HgLfsTest`가 목(mock) HTTP 서버로 커버하고 있어
    이번 항목에서 다시 다루지 않음(로컬 저장소 포맷 정확성이 우선순위가 더
    높다는 원래 방침대로).

    **신규 테스트**: `HgTreeFilterTest`(narrow 매칭 프리미티브 단위 테스트 8건
    추가), `HgNarrowCloneTest`(포맷/위치 수정 반영 + component-boundary 회귀
    테스트 추가), `NarrowCloneRealHgInteropTest`(신규, 3건),
    `LfsRealHgInteropTest`(신규, 2건). 전체 회귀 2415건 전부 GREEN(신규 실패 없음).

29. ~~**`requires` 파일 세부 문자열 커버리지 재검증**~~ — ✅ **완료(2026-09-04)**.
    조사 결과 `HgRepository.loadRequires()` 자체는 8개 특수 문자열을 정확히
    인식/무시하고 있어 문제가 없었지만, **완전히 별도의, 서로 동기화되지 않은
    두 번째 검증 게이트**가 `Hg.open()`(`api/Hg.java`)에 이미 존재했고 그게
    심각하게 낡아 있었다 — 이 게이트의 `SUPPORTED` 허용목록이 (1)
    `revlog-compression-zstd`(real hg 7.2 기본 압축 엔진이 남기는 실제 문자열)를
    `revlog-compression`(접미사 없는 잘못된 값)으로만 갖고 있었고, (2)
    `sparserevlog`(모든 real hg 7.2 저장소에 기본으로 존재)가 아예 빠져 있었고,
    (3) `narrowspec`(narrowspec 데이터 파일의 온디스크 **파일명**을 requirement
    문자열로 착각한 값)을 갖고 있으면서 실제 문자열인 `narrowhg-experimental`은
    없었고, (4) `HgRepository`가 이미 완전히 지원하는 6개 고급 포맷 문자열
    (`exp-changelog-v2`/`exp-revlogv2.2`/`persistent-nodemap`/`fileindex-v1`/
    `treemanifest`/`exp-copies-sidedata-changeset`)이 전부 빠져 있었다. **실제
    영향(real hg CLI로 직접 재현 확인)**: 설정 하나 없이 그냥 `hg init`한
    완전히 평범한 real hg 7.2 저장소조차 `Hg.open()`으로 열면
    `HgValidationException: unsupported repository requirement: sparserevlog`로
    거부됐다 — `new HgRepository(dir)`로 그 게이트를 우회하는 기존 테스트들만
    이 사실을 가려온 것. `Hg.java`의 `SUPPORTED` 목록을 실측된 정확한 문자열로
    동기화해 수정, 신규 `HgOpenRequirementValidationTest`(5개 테스트: 평범한
    저장소/압축+sparserevlog 개별 확인/6개 고급 포맷 각각/narrow 문자열/여전히
    진짜 미지의 requirement는 거부)로 검증, 기존
    `HgPorcelainAndExceptionsTest`의 관련 테스트도 회귀 없이 통과.

30. ~~**Narrow clone의 wire-protocol 수준 재통합 (narrow pull/update)**~~ —
    ✅ **완료(2026-09-04)**. `PullCommand`/`UpdateCommand`/`FetchCommand` 어디에도
    narrowspec 참조가 없어(narrow clone 시점에만 그 자리에서 필터를 만들어 쓰고
    이후 `pull`/`update`가 narrow 상태를 다시 읽지 않던 문제) narrow clone한
    저장소에 plain `pull`/`update`를 하면 범위 밖 파일까지 받아버릴 위험이 있었다.

    **구현**: `HgTreeFilter.loadFromRepository(HgRepository)` 신설 — `.hg/store/
    narrowspec`을 읽어(`NarrowCloneCommand.formatNarrowSpec`이 쓰는 `[include]`/
    `[exclude]` 포맷을 그대로 파싱) 클론 시점과 동일한 `createNarrowSpecFilter`
    매처를 재구성한다(narrowspec이 없으면 `HgTreeFilter.ALL`). `FetchCommand.call()`/
    `applyBundle()`과 `UpdateCommand.call()`이 각각 시작 시점에 "자신의 treeFilter가
    여전히 기본값 `ALL`인 경우에만" 이걸로 자동 대체하도록 배선 — 명시적으로
    `setTreeFilter()`를 호출한 기존 호출자(`NarrowCloneCommand` 자신 포함)는 전혀
    영향받지 않는다. `PullCommand`는 자신의 필터가 기본값일 때 `FetchCommand`에
    아예 전달하지 않아 `FetchCommand` 자신의 자동 로딩이 작동하도록 함(오버라이드
    사고 방지).

    **부수적으로 발견·수정한 진짜 버그(NarrowCloneCommand 자체, backlog 30과
    무관하게 이미 존재하던 결함)**: 검증 도중 기존 `NarrowCloneRealHgInteropTest`의
    선행 시나리오까지 `HgCorruptDataException`("Failed to read complete hunk ...
    at offset 64")으로 깨지는 걸 발견 — `git stash`로 순정 코드에 대조해도 100%
    동일하게 재현되어 이번 변경과 무관한 사전 존재 버그임을 확인했다. 근본 원인은
    `NarrowCloneCommand.call()`이 `hg.pull()` 직후 캐시 무효화 없이 바로
    `hg.update()`를 호출해서, pull이 방금 쓴 매니페스트 revlog를 update가 그
    이전(또는 `Hg.init()` 중 우연히 캐시된) stale `Revlog` 인스턴스로 읽어버리던
    것 — `FetchCommand`의 clonebundle 경로가 이미 쓰고 있던
    `repository.clearRevlogCache()` 패턴이 이 한 호출부에서만 빠져 있었다. 같은
    한 줄 추가로 수정, narrow clone 전체(narrow clone을 쓰는 모든 사용자)에
    영향을 미치던 결함이라 이번 검증이 아니었으면 계속 잠복해 있었을 것.

    **범위 밖으로 남긴 것(정직하게 기록)**: 실제 hg의 wire-protocol ellipsis node
    메커니즘(서버가 narrow 범위 밖 리비전 자체를 아예 전송하지 않는 것)은 여전히
    미구현 — 이번 항목은 "이미 로컬에 받은 changegroup을 적용/체크아웃할 때
    narrow 필터를 존중하는 것"까지만 다룬다(이미 백로그 28번에서도 같은 경계로
    문서화됨).

    **검증**: `NarrowCloneRealHgInteropTest`에 신규 시나리오
    `hg4jNarrowCloneScopeIsRespectedOnSubsequentPlainPull` 추가 — narrow clone
    이후 real hg로 범위 안/밖 파일을 각각 하나씩 추가 커밋하고, hg4j로 **명시적
    treeFilter 없이** plain `pull`+`update`를 실행했을 때 범위 밖 파일이 워킹
    카피/추적 목록에 전혀 안 나타나는지, real hg CLI 자신이 그 결과를 열었을 때도
    일관되게 보는지 확인. 기존 시나리오 1~3 포함 전체 4개 테스트 GREEN, 전체
    회귀(`test`+`interopTest`) `BUILD SUCCESSFUL`(22분48초, 새 실패 없음).

31. ~~**LFS 커밋/체크아웃 파이프라인 연동**~~ — ✅ **핵심 경로 완료(2026-09-04)**.
    신규 발견(백로그 28번에서 "정직하게 기록"만 하고 범위 밖으로 남긴 것을 별도
    항목으로 승격). `CommitCommand`/`UpdateCommand`에 `[lfs] threshold`를 넘는
    파일을 LFS 포인터로 치환해 커밋하고(`REVIDX_EXTSTORED` 플래그 세팅, 실제
    바이트는 로컬 blob store에 캐시), 체크아웃 시 그 플래그를 보고 포인터를 실제
    바이트로 되돌리는 핵심 경로를 구현했다.

    **근본적으로 잘못 짚었던 가정 하나 발견·수정**: 처음엔 filelog 리비전의 노드
    해시를 (다른 모든 리비전과 마찬가지로) 저장되는 바이트 그 자체(포인터 텍스트)
    로 계산하면 될 거라 가정했는데, 실제 hg CLI로 만든 LFS 커밋을 직접 재현해
    `SHA1(p1,p2,포인터텍스트)`와 `SHA1(p1,p2,실제파일바이트)` 둘 다 계산해 대조해본
    결과 **후자만 실제 filelog 노드와 일치**했다 — 즉 real hg는 LFS 리비전의 노드
    해시를 저장된 포인터가 아니라 real hg의 `hgext/lfs` flag processor가
    돌려주는 실제 파일 콘텐츠 기준으로 계산한다(read-side flag processor의
    `validatehash=True`가 실제 콘텐츠에 대해 매번 진짜로 검증됨). 이걸 놓치고
    포인터 텍스트 기준으로 해시를 계산해 커밋했더니, real hg CLI가 그 커밋을 열 때
    `abort: integrity check failed`로 거부하는 실제 상호운용성 버그가 났었다 —
    `Revlog.appendRevision`에 `hashBasisOverride` 파라미터를 신설해 "저장되는
    바이트"와 "해시 계산 기준 바이트"를 분리함으로써 수정. 부수적으로 real hg의
    `hgext/lfs`가 커밋 훅으로 `.hg/requires`에 `lfs`를 지연 추가한다는 것도 확인해
    `CommitCommand`에 동일하게 구현(없으면 real hg가 checkhash 우회 자체를
    활성화하지 않아 같은 에러가 남).

    **검증**: `LfsRealHgInteropTest`에 신규 파이프라인 테스트 2건 추가 — real hg가
    커밋한 LFS 파일을 hg4j `UpdateCommand`가 체크아웃해 실제 바이트를 복원하는지,
    hg4j `CommitCommand`로 만든 LFS 커밋을 real hg의 `hg cat`(lfs 확장 활성화)이
    정확히 읽고 `hg verify`가 에러 없이 통과하는지 양방향 확인 — 전부 GREEN.
    타깃 테스트 클래스 회귀 재확인 완료, 전체 유닛 테스트(interop 제외)
    2262/2263 GREEN(유일한 실패는 이 변경과 무관한 기존 `PerformanceBenchmarkTest`
    타이밍 플레이크).

    **범위 밖으로 남긴 것(정직하게 기록)**: rename/copy 메타데이터와 LFS 임계값을
    동시에 넘는 파일(real hg는 포인터에 `x-hg-*` 키로 copy-tracing을 접어 넣는데
    미구현 — 그런 파일은 그냥 일반 경로로 커밋됨), 원격 LFS 서버 URL을
    `[paths] default`에서 그대로 유추(실제 hg의 `[lfs] url` override 미지원),
    `.hgrc`의 `lfs.disableusercache` 등 세부 옵션. 전체(모든 fork 동시 실행 중)
    회귀는 리소스 경합으로 완주 못함 — 별도 확인 권장.

32. ~~**Subrepositories 잔여 gap 4건**~~ — ✅ **완료(2026-09-04)**. 4가지 모두
    real hg 7.2 CLI(+git)와 나란히 재현하는 TDD로 확인/수정했다.

    **(1) `CloneCommand`가 서브저장소를 재귀적으로 clone하지 않음.** `UpdateCommand`가
    이미 갖고 있던 재귀 서브저장소 체크아웃 블록(그때까지는 `UpdateCommand.call()`
    안에 인라인으로만 존재해 재사용 불가능한 상태였음)을 패키지 프라이빗 정적 메서드
    `UpdateCommand.recursiveSubrepoCheckout(HgRepository)`로 추출하고, `CloneCommand`가
    `checkoutLatest()` 직후 이를 호출하도록 배선했다. 이 추출 과정에서 (3)/(4) 수정도
    같은 메서드에 함께 반영되므로 clone도 자동으로 git 서브저장소 재귀 clone과 로컬
    존재 시 pull 생략 혜택을 받는다.

    **(2) `.hgsub`이 완전히 사라진 채 커밋.** 실제 hg 7.2로 직접 재현해보니 **사라지는
    방식에 따라 동작이 다르다**(`mercurial/subrepoutil.py`의 `precommit()` 직접 확인):
    - `hg remove .hgsub`로 명시적으로 지우면(dirstate `'r'`) 실제 hg는 사용자가
      `hg remove .hgsubstate`를 따로 하지 않아도 `.hgsubstate`까지 같은 커밋에서 완전히
      추적 해제한다(`hg cat -r tip .hgsubstate`가 "no such file in rev"로 실패) —
      `subrepoutil.precommit()`의 `elif '.hgsub' in status.removed:` 분기.
    - `rm .hgsub`로 그냥 디스크에서만 지우면(`hg remove` 없이, dirstate는 여전히
      `'n'`) `.hgsub` 자체의 추적은 전혀 건드리지 않고(tracked-but-missing 상태로
      남음) `.hgsubstate`만 **빈 내용으로 커밋**된다 — 신기하게도 이 경우는 다른 모든
      "추적됐지만 디스크에서 사라진 파일"과 달리 real hg가 missing-file abort
      ("nothing changed (N missing files)")를 내지 않는 특별 취급이다.
    둘 다 hg4j에 없었다. `CommitCommand.applySubrepoStateBeforeCommit()`의
    `if (!hgsubFile.exists()) return;` 조기 반환과, 그보다 더 안쪽의
    `if (subUrls.isEmpty()) return;` 조기 반환(둘 다 "빈 `.hgsubstate` 커밋" 경로를
    막고 있었음)을 제거하고 dirstate의 `.hgsub` 엔트리 상태로 두 경우를 분기하도록
    재작성했다. 명시적 제거 시에는 `.hgsubstate`도 함께 `'r'`로 마킹, raw 삭제 시에는
    빈 `.hgsubstate` 내용을 그대로 쓴다. 추가로, 메인 커밋 루프(파일별 dirstate 순회)
    자체가 "추적됐는데 디스크에 없는 파일"을 전부 `HgValidationException`으로
    거부하고 있어서 raw 삭제 케이스는 `applySubrepoStateBeforeCommit()`을 고치는 것만
    으로는 부족했다 — `.hgsub`이면서 `workingState == 'n'`이고 디스크에 없는 경우를
    특별 취급해(P1/P2 manifest 엔트리를 그대로 캐리) 예외를 던지지 않도록 별도 수정.

    **(3) git 서브저장소 커밋측 상태 갱신 부재.** real git(`git rev-parse HEAD`)이
    설치돼 있어 `mercurial/subrepo.py`의 `gitsubrepo` 클래스(`basestate()`/`dirty()`/
    `commit()`)를 직접 읽고, **실제 git 서브저장소를 만들어 real hg 7.2(+
    `[subrepos] git:allowed = true`)로 라이브 왕복 검증**까지 했다. 확인된 사실: (a)
    `.hgsubstate`에는 `git rev-parse HEAD`가 그대로(hg 노드 해시가 아니라 git commit
    sha) `"<sha> <path>"` 포맷으로 기록됨 — hg 서브저장소와 완전히 동일한 라인 포맷.
    (b) dirty 판정은 git 자신의 `git diff-index --quiet HEAD`(추적 파일의 스테이지/
    미스테이지 변경만, untracked는 무시)이고, dirty하면 hg 서브저장소와 **글자
    하나까지 동일한** `uncommitted changes in subrepository "<path>" (use --subrepos
    for recursive commit)` 메시지로 부모 커밋을 거부한다. (c) `-S`를 켜면
    `git commit -a -m <message> [--author <author>]`가 대신 실행되고 새 HEAD sha가
    기록된다. (d) 로컬에 전혀 체크아웃되지 않은 git 서브저장소는 hg 서브저장소와 달리
    null 리비전 폴백이 **없다** — real hg가 `No such file or directory: '<abspath>'`로
    부모 커밋 자체를 abort한다(직접 재현 확인). 이 4가지를 그대로 구현: 신규
    `io.github.search5.hg4j.submodule.GitSubrepoUtil`(git CLI를 쉘아웃하는 얇은
    헬퍼 — `revParseHead`/`isDirty`/`commit`/`clone`/`fetch`/`checkout`)를 만들고
    `CommitCommand`의 상태 갱신 루프에서 `if (gitPaths.contains(path)) continue;`를
    제거해 `computeGitSubrepoState()`로 대체했다. `UpdateCommand`의 재귀 체크아웃
    쪽도(1)에서 추출한 공용 메서드 안에서 git 서브저장소를 더 이상 건너뛰지 않고
    실제 `git clone`/`fetch`/`checkout`으로 체크아웃하도록 확장(단, real hg의
    `gitsubrepo.get()`이 pin된 커밋을 가리키는 named branch가 있으면 그걸 우선
    checkout하는 것과 달리, hg4j는 항상 detached HEAD로 checkout — 내용/커밋은
    동일하게 도달하므로 기능적 차이는 없고 문서화된 단순화).

    **(4) `UpdateCommand` 재귀 서브저장소 체크아웃의 무조건 pull.** real hg 소스
    (`hgsubrepo._fetch()`/`gitsubrepo._fetch()`)를 직접 확인 — 둘 다 `hasunlinkedrev`/
    `_githavelocally`로 대상 리비전이 로컬에 이미 있는지 먼저 확인하고, 있으면 pull/
    fetch 자체를 생략한다. hg4j의 (1)에서 추출한 공용 메서드에 동일한 사전 확인을
    추가했다 — hg 서브저장소는 로컬 changelog에서 `NodeIdUtil.findRevisionByNodeId`로,
    git 서브저장소는 `git cat-file -e <sha>`로 로컬 존재 여부를 먼저 확인 후에만
    pull/fetch. 검증은 real hg CLI 출력으로 직접 관측 가능한 성질이 아니라서(네트워크
    호출을 "안 했다"는 hg CLI stdout으로 증명할 수 없음), 대신 로그 캡처로 간접
    검증했다: 서브저장소 소스 경로를 존재하지 않는 곳으로 바꿔치기한 뒤, 이미 로컬에
    있는 두 pin 리비전 사이를 hg4j `UpdateCommand`로 오가면서 "Failed to pull
    subrepo" 실패 로그가 전혀 남지 않는지 확인(수정 전이었다면 무조건 pull을 시도해
    무효한 소스에 대한 실패 로그가 남았을 것).

    **검증**: `SubrepoRealHgInteropTest`에 신규 시나리오 8개 추가(총 13개 GREEN) —
    `hg4jCloneRecursivelyClonesSubrepoMatchingRealHg`,
    `hg4jCloneRecursivelyClonesGitSubrepoMatchingRealHg`,
    `hg4jCommitEmptiesHgsubstateWhenHgsubRawlyDeletedMatchingRealHg`,
    `hg4jCommitRemovesHgsubstateWhenHgsubExplicitlyRemovedMatchingRealHg`,
    `hg4jCommitRecordsGitSubrepoStateMatchingRealHg`,
    `hg4jCommitBlocksThenRecursivelyCommitsDirtyGitSubrepoMatchingRealHg`,
    `hg4jCommitAbortsForNotCheckedOutGitSubrepoMatchingRealHg`,
    `hg4jUpdateSkipsPullWhenSubrepoRevisionAlreadyLocalMatchingRealHg`. 각각 real hg(
    +git) 오라클로 동일 시나리오를 나란히 재현해 hg4j 결과와 대조하고, 여러 테스트는
    hg4j 결과물을 다시 real hg CLI로 읽어 양방향 확인까지 한다. 기존
    `UpdateCommandCoverageTest`의 `subrepoCheckoutSkipsGitEntriesAndHandlesUnrecordedRevisionAndSource`
    는 "git 서브저장소는 무조건 건너뛴다"는 낡은 전제로 작성돼 있어 새 동작(로컬에
    없는 git 서브저장소는 커밋 자체가 abort)과 충돌 — `subrepoCheckoutHandlesUnrecordedRevisionAndSource`
    (git 없는 부분만 유지)와 `subrepoCommitAbortsForNotCheckedOutGitSubrepo`(새 abort
    동작 검증)로 분리했다. 전체 회귀: `test` 태스크 2268/2268 GREEN,
    `interopTest` 태스크 228개 중 이 변경과 무관한 기존 `StripRealHgInteropTest`
    2건(`stripMiddleRevisionKeepsAncestorsVerifiable`/
    `stripWithUnevenRevisionSizesLeavesVerifiableRepo`)만 실패 — 이 변경분을 전부
    `git stash`로 되돌린 상태에서도 동일하게 실패함을 직접 확인해 무관함을 검증(백로그
    32와 무관한 사전 존재 이슈).

    **정직한 한계**: git 서브저장소 검증은 이 머신에 real git 7.2 계열 바이너리와 real
    hg 7.2가 둘 다 설치돼 있어(실제로 `[subrepos] git:allowed = true`를 켠 real hg가
    실제 git 저장소를 서브저장소로 받아들이는 전 과정을 라이브로) 상당히 두텁게 검증할
    수 있었다 — record/dirty-차단/`-S` 재귀 커밋/미체크아웃 abort/재귀 clone까지 5개
    시나리오 모두 실제 git 커밋 sha 단위로 대조. 남아 있던 항목 중 (b)
    `gitsubrepo.get()`의 named-branch 우선 checkout(hg4j는 항상 detached checkout)은
    기능적 차이가 없는 문서화된 단순화라 그대로 남겨뒀지만, (a)/(c)는 2026-09-04
    사용자 지시로 후속 작업해 아래와 같이 완료했다.

    **(a) `GIT_AUTHOR_DATE` byte-exactness — ✅ 라이브 검증 완료.** 실제 hg 소스
    직접 확인(`mercurial/subrepo.py` `gitsubrepo.commit()`): `env[b'GIT_AUTHOR_DATE']
    = dateutil.datestr(date, b'%Y-%m-%dT%H:%M:%S %1%2')` — "T" 구분자 + 공백 +
    콜론 없는 `+HHMM`/`-HHMM` 오프셋(예: `"2023-11-15T07:13:20 +0900"`). hg4j의
    `GitSubrepoUtil.commit()`은 `DateTimeFormatter.ISO_OFFSET_DATE_TIME`을 써서
    실제로는 다른 문자열(`"2023-11-15T07:13:20+09:00"`, 공백 없음 + 콜론 있는
    오프셋, 0 오프셋일 땐 `"...Z"`)을 넘긴다 — **그러나 git 자신의 날짜 파서가 두
    포맷을 완전히 동일하게 파싱함을 git 2.53 CLI로 직접 확인**(둘 다
    `"1700000000 +0900"`으로 커밋 오브젝트에 기록됨, 0 오프셋/1e9 이전 epoch
    경계값 포함 여러 케이스로 재확인) — 즉 git에 넘기는 원본 env var 문자열
    자체는 다르지만, 실제로 바이트 비교 대상이 되는 **git 커밋 오브젝트 자체는
    byte-exact로 일치**해 기존 구현을 고칠 필요가 없었다(코드 변경 없음, 검증만
    추가). 부모 hg 커밋에 `-d`를 안 준 "지금 시각" 커밋의 경우도 확인: 실제
    hg 소스(`mercurial/commands.py`/`localrepo.py` `commit()`)를 추적해보면 그
    경우 `date` 로컬 변수 자체가 falsy인 채로 `sub.commit(text, user, date)`에
    그대로 전달되므로(부모 자신의 `cctx._date`는 나중에 `propertycache`로 별도
    시점에 `dateutil.makedate()`가 채움) real hg조차 부모 커밋 시각과 git
    서브저장소 커밋 시각이 서로 다른 `now()` 호출로 어긋날 수 있는 것을 확인 —
    hg4j가 "명시적 `-d` 없으면 `GIT_AUTHOR_DATE`를 아예 안 세팅"하는 기존 동작이
    바로 이 real hg 동작과 이미 일치한다. **신규 라이브 검증**:
    `hg4jGitSubrepoCommitDateMatchesRealHgByteExact` — real hg `hg commit -S -d
    "1700000000 -32400"`로 만든 git 서브저장소 커밋의 author 날짜(`git log
    --format=%ad --date=raw`)와, hg4j `CommitCommand.setSubrepos(true)
    .setDate(1700000000L, -32400)`로 만든 동일 시나리오의 결과를 byte-for-byte
    비교(`"1700000000 +0900"` 고정값까지 확인).

    **(c) git 서브저장소 병합/충돌(`gitsubrepo.merge()`) — ✅ 구현 완료.** 실제
    hg 소스(`mercurial/subrepo.py` `gitsubrepo.merge()` + `subrepoutil.submerge()`)
    를 직접 읽고 real hg CLI + 실제 git 서브저장소로 라이브 재현(2026-09-04):
    같은 git 서브저장소를 공통 조상에서 서로 다르게 갈라놓은 두 hg 커밋을 만들고
    `hg merge`(비대화형)를 실행하자, `subrepoutil.submerge()`가 `.hgsubstate` 3-way
    비교로 "양쪽 다 변경됨(diverged)"을 판정해 `ui.promptchoice(msg, 0)`(비대화형
    기본값 = "Merge")로 `gitsubrepo.merge()`를 호출함을 실측: `git merge-base(remote,
    local)` 계산 후 `base==remote`면 `get(remote)`("fast forward", real hg 표현
    그대로 — local이 remote의 후손이어도 문자 그대로 remote를 checkout), `base !=
    local`이면 `git merge --no-commit <remote>`(종료 코드는 `_gitcommand`가 무조건
    버려서 **충돌이 나도 감지·보고하지 않음** — 뒤이은 `hg commit`이 git 서브저장소를
    dirty로 보고 처리하도록 방치), 그 외(진짜 순방향 fast-forward)엔 아무 것도 안 함.
    **결정적으로, `.hgsubstate`에 최종 기록되는 pin 값은 이 세 경우 모두 LOCAL(병합
    전 값) 그대로 유지된다**(`submerge()`의 `sm[s] = l`, "merge"/"local" 선택
    양쪽 다) — git 서브저장소 실제 병합 결과(2-parent 커밋)는 이어지는 `hg commit
    -S`가 (이미 구현돼 있던 백로그 32 gap #3의 dirty()/commit() 로직으로) 새로
    기록한다. 이 전체 시퀀스를 그대로 이식: `GitSubrepoUtil.mergeDiverged(gitDir,
    remoteRev, localRev)`(+ `mergeBase`/`mergeNoCommit` — 후자는 real hg와 동일하게
    종료 코드를 의도적으로 무시) 신규 추가, `MergeCommand`에 `.hgsubstate`를 더
    이상 일반 텍스트 3-way 병합(diff3 충돌 마커가 그대로 파일에 박히는 버그였음)에
    맡기지 않고 전용 `mergeSubrepoState()`로 서브저장소별 3-way 판정(unchanged/
    remote-only-changed → `UpdateCommand.checkoutSubrepoEntry`로 실제 체크아웃/
    diverged → git이면 `mergeDiverged`, pin 값은 LOCAL 유지)을 수행하도록 배선.
    **신규 라이브 검증(git)**: `hg4jMergeHandlesDivergedGitSubrepoMatchingRealHg` —
    real hg 오라클로 `left.txt`/`right.txt`를 각각 추가하는 두 갈래 git 서브저장소
    커밋을 만들고 `hg merge`+`hg commit -S`까지 실행해 (i) merge 직후
    `.hgsubstate`가 LOCAL pin을 유지하는지, (ii) 최종 병합 커밋이 진짜 2-parent
    git 커밋인지(`git log --format=%P`)를 오라클 자체 검증 + hg4j 쪽도 동일하게
    자기 자신의 right/left sha와 대조(두 구현이 각자 별도 시각에 만든 git 커밋이라
    sha 자체는 오라클과 hg4j 사이에서 다를 수 있어 서로 직접 비교하지 않음), 병합
    후 두 파일이 모두 존재하는지까지 확인.

    **hg-타입(중첩 hg) 서브저장소의 대칭 케이스도 ✅ 구현 완료.** `subrepoutil.
    submerge()`는 git이든 hg든 구분 없이 diverged 케이스에서 똑같이
    `sub.merge(r)`을 호출하므로, git 쪽만 고치고 hg-타입은 그대로 두면 gap B가
    반쪽만 닫히는 것이었다 — real hg의 hg-타입 대응 메서드
    `mercurial/subrepo.py` `hgsubrepo.merge()`도 함께 직접 읽고 실제 hg CLI(중첩
    hg 서브저장소, git 불필요)로 2026-09-04 라이브 재현했다: `self._get(state)`로
    원격 pin을 로컬에 먼저 확보한 뒤 `anc = dst.ancestor(cur)`로 **서브저장소
    자신의** 조상 관계를 계산해 — `anc==cur`이고 같은 브랜치면 단순
    `up_impl.update(state[1])`(순방향 fast-forward), `anc==dst`면 아무 것도 안
    함(cur이 이미 dst를 포함), 그 외(진짜 divergence, 또는 조상은 같지만 브랜치가
    다른 경우)엔 서브저장소 안에서 **진짜 재귀 `hg merge`**(`up_impl.merge(dst,
    remind=False)`)를 실행함을 실측 — git 쪽과 마찬가지로 `.hgsubstate`에
    기록되는 pin은 이 모든 경우에 LOCAL 값 그대로 유지된다. 이를 hg4j 자신의
    기존 명령을 재귀 호출해 그대로 이식: `MergeCommand.mergeDivergedHgSubrepo()`
    신규 추가 — 서브저장소를 별도 `HgRepository`로 열어 `ChangesetGraph.
    isAncestor()`로 조상 관계를 판정하고, fast-forward면 서브저장소에
    `UpdateCommand`를, 진짜 divergence면 서브저장소에 **재귀적으로
    `MergeCommand` 자신**을 호출한다(hg4j가 이미 갖고 있던 `MergeCommand`를
    중첩 호출하는 것만으로 real hg의 재귀 `hg merge`와 동일한 결과를 얻는다 —
    별도의 충돌 UI/머지 엔진을 새로 만들 필요가 전혀 없었다).

    이 재귀 병합을 라이브로 재현하는 과정에서 **진짜 버그를 하나 더 발견**:
    `CommitCommand.applySubrepoStateBeforeCommit()`의 hg-타입 서브저장소
    dirty 판정이 `StatusCommand`의 added/modified/removed 목록만 봤는데,
    `StatusCommand`는 dirstate 엔트리를 디스크와만 비교하지 parent1의 매니페스트와
    비교하지 않는다 — 그래서 재귀 병합이 만든 "parent2에서 새로 들어온 파일"이
    디스크와 일치하는 `'n'`(정상) 엔트리로 기록되면 완전히 "clean"으로 보여서
    dirty 판정이 거짓이 되고, pending 2-parent 병합인 서브저장소가 커밋 없이
    그냥 넘어가 버렸다(`.hgsubstate`가 갱신되지 않음). 실제 hg 소스로 근본원인
    확인: `hgsubrepo.dirty()`는 `workingctx.dirty()`로 위임하는데, 그 함수의 첫
    조건이 바로 `merge and self.p2()` — **parent2가 있으면 파일 내용과 무관하게
    무조건 dirty**다. `CommitCommand`의 dirty 판정에 이 조건(서브저장소 dirstate의
    parent2가 non-null이면 무조건 dirty)을 추가해 수정 — 이 수정이 없었다면 hg4j
    쪽 재귀 병합 커밋이 1-parent 커밋으로 잘못 기록됐을 것이다(실측: 수정 전에는
    정확히 이 증상으로 테스트가 실패했음).

    **신규 라이브 검증(hg-타입)**: `hg4jMergeHandlesDivergedHgSubrepoMatchingRealHg`
    — 순수 hg 서브저장소(git 불필요)로 위와 동일한 left/right divergence 시나리오를
    real hg 오라클과 hg4j(부모/서브저장소 양쪽 다 hg4j 자체 API로 조작) 양쪽에서
    재현해 (i) 오라클: merge 직후 `.hgsubstate`가 LOCAL(right) pin을 유지하는지,
    서브저장소 병합 커밋의 부모 순서(`{p1node} {p2node}`)가 (right, left)인지, (ii)
    hg4j: merge 직후 서브저장소 dirstate가 실제로 pending 2-parent 상태인지, 병합 후
    두 파일이 모두 존재하는지, 최종 재귀 커밋 후 서브저장소 changelog에 진짜
    2-parent 커밋이 새로 생겼는지, 그 부모 쌍이 hg4j 자신의 right/left sha와
    정확히 일치하는지까지 확인. 회귀 확인: `test`+`interopTest` 전체
    231개(이번 hg-타입 테스트 1건 추가) 중 이 변경과 무관한 기존
    `StripRealHgInteropTest` 2건(위와 동일)만 실패, 나머지 전부 GREEN.

33. ~~**`PushCommand`의 checkheads 안전장치가 SSH에서 미작동**~~ — ✅
    **완료(2026-09-04)**. 근본 원인: `HgRemoteConnection.getBranchHeads()`를
    `HgRemoteClient`(HTTP)만 구현하고 `HgSshClient`는 오버라이드하지 않아
    인터페이스 기본값(`return null`, "branch-unaware 폴백")으로 떨어졌던 것 — SSH로
    push할 때는 브랜치 인식 checkheads 안전장치(다중 head/새 브랜치 push 거부)가
    실질적으로 무력화된 채 topological-only 체크로만 동작하고 있었다. 실제 hg의
    v1 wire 프로토콜 `branchmap` 커맨드(무인자, HTTP `HgRemoteClient`가 이미 쓰던
    것과 동일한 커맨드 — hg4j 서버측 `Wire1Commands.branchmap`/`HgSshWireServer`는
    이미 이 커맨드를 지원하고 있었고, 빠진 건 오직 클라이언트측 호출뿐이었다)를
    `HgSshClient`에 `getHeads()`/`listKeys()`와 동일한 패턴(`sendCommand`+
    `readFramedResponse`, SSH 프로토콜 v2 폴백 포함)으로 신규 구현. 검증: (1)
    `getBranchHeadsReturnsRealHgsBranchMapOverSsh` — 2개 named branch가 있는 real
    hg SSH 서버에서 브랜치별 head hex가 정확히 일치하는지 확인, (2)
    `pushCreatingNewHeadIsRejectedOverSshThenForceSucceeds` — HTTP 쪽 기존
    `testPushRejectedWhenCreatingNewHeadThenForceSucceeds`와 대칭으로, 새 head를
    만드는 SSH push가 `--force` 없이 거부되고(원격 head 개수 불변 확인) `--force`
    로는 성공하는지(원격 head 2개로 증가 확인) real hg SSH 서버(embedded MINA
    SSHD 뒤에서 진짜 `hg serve --stdio` 서브프로세스) 상대로 확인. 둘 다
    `HgSshClientRealHgInteropTest`에 신규 추가, 그 파일 전체(5 테스트) GREEN.
    (참고: 같은 시각 병행 중이던 다른 fork들의 미완료 파일 때문에 전체 회귀에서는
    무관한 `HgCorruptDataException` 계열 실패가 다수 나왔음 — `HgSshClient.java`
    로 스코프를 좁힌 격리 실행에서는 실패 없음, 원인은 공유 컴파일 출력 디렉터리
    오염으로 판단됨.)

34. ~~**`BisectCommand`의 merge 커밋 DAG 시나리오 real hg 대조 검증 누락**~~ —
    ✅ **완료(2026-09-04)**. 신규 테스트 `bisectConvergesToSameCulpritAsRealHgAcrossMergeCommit`
    (`BisectRealHgInteropTest`)를 TDD로 추가 — root에서 branch A(2커밋, flag.txt
    미변경)/branch B(3커밋, 중간에 flag.txt를 "1"로 바꾸는 진짜 culprit)로 분기시킨
    뒤 `UpdateCommand`+`MergeCommand`로 실제 3-way 자동 병합(충돌 없음) merge
    커밋을 만들고, 그 뒤로 한 커밋 더 진행한 8-리비전 DAG로 검증. good=root,
    bad=최종 리비전으로 hg4j `BisectCommand`와 real `hg bisect`를 동일한
    good/bad-by-flag.txt 오라클로 나란히 걸어 매 단계 후보 시퀀스와 최종 culprit이
    정확히 일치함을 확인(`assertEquals(nativeCandidates, hg4jCandidates)`). **실제
    프로덕션 버그는 발견되지 않음** — `BisectCommand`의 기존 merge-DAG 인식 알고리즘
    (`getTopologicalRange`/`selectBisectCandidate`의 양쪽 부모 전파)이 이미 정확했음이
    확인됨, 검증 자체가 목적이었던 항목이라 이걸로 완료. 전체 회귀 확인 중 61건의
    무관한 실패가 관측됐으나 `bisect` 관련은 0건이고 이 항목은 프로덕션 코드를 전혀
    건드리지 않은 순수 테스트 추가라, 동시에 실행 중이던 다른 병렬 작업의 빌드
    간섭(이 세션에서 이미 여러 차례 확인된 현상)으로 판단 — 이 항목 자체의 회귀는
    없음.

35. ~~**Revlog 쓰기 경로가 항상 non-inline이라 `hg verify`가 fncache 경고를 냄**~~
    — ✅ **완료(2026-09-04)**. 신규 발견(백로그 23번 strip 카테고리 검증 중 발견,
    strip과 무관한 사전 존재 이슈라 별도 항목으로 승격). 실제 hg 스펙 확정
    (`mercurial/revlog.py` 소스 직접 대조, 이 호스트 Homebrew 설치본
    `/opt/homebrew/lib/python3.14/site-packages/mercurial/revlog.py`): revlogv1은
    `REVLOG_DEFAULT_FLAGS = FLAG_INLINE_DATA`로 **기본이 inline**이고
    `_enforceinlinesize()`가 총 크기가 `_maxinline = 131072`바이트(128KiB)를
    넘어야만 별도 `.d` 파일로 분리한다. changelog만 유일하게
    `mercurial/changelog.py`에서 `may_inline=False`로 생성돼 항상 non-inline —
    hg4j의 changelog 처리는 이미 이 부분과 일치했다(안 건드림).

    **1차 시도(같은 세션 초반)와 되돌린 이유**: `Revlog` 생성자에서 신규 v1
    filelog/manifest를 `inline=true`로 시작하게만 바꿨더니 전체 회귀에서 60개
    테스트가 깨짐 — `appendChangeGroupEntry()`(pull/push로 원격 changegroup을
    적용하는 경로)가 `appendRevision`과는 별개의 자체 `if (inline)` 분기를 갖고
    있었는데 그게 실제로 `this.inline`을 전혀 확인하지 않고 **항상 non-inline
    레이아웃으로 하드코딩**돼 있어(offset을 `datFile.length()`로 계산, format
    flags도 non-inline 값 고정) inline 저장소에 pull/push로 리비전이 들어오면
    `"Failed to read complete hunk of size 20 at offset 64"`로 데이터가 실제로
    손상됐다. 1차 시도 때는 이 사실만 확인하고 안전하게 원상 복구·문서화만 하고
    끝냈었다.

    **2차 시도(사용자가 명시적으로 "계속하라"고 지시, 2026-09-04, 같은 세션
    후속)**: `appendChangeGroupEntry()`의 v1 수동 바이트 라이팅 경로를
    `appendRevision()`이 이미 쓰던 것과 동일한 inline/non-inline 분기 패턴으로
    재작성(offset을 `prevRec.getOffset()+getCompLen()`으로 계산, inline이면
    64바이트 레코드+dataHunk를 `idxFile`에 이어쓰기, non-inline이면 기존처럼
    `datFile`에 씀). 조사해보니 `appendRawRevision()`/`appendOptimizedRevision()`
    은 이미 세션 초반(RebaseCommand 백업·복원 버그 수정 때) 올바른 inline 분기가
    붙어 있었던 것으로 확인돼 손댈 필요 없었음 — 실제 미해결 지점은
    `appendChangeGroupEntry` 하나뿐이었다. `Revlog` 생성자도 신규 v1 filelog/
    manifest(파일명에 `00changelog` 미포함, 그리고 idxFile이 아직 존재하지 않는
    "진짜 새 리비전 로그"인 경우만)를 `inline=true`로 시작하도록 재적용.

    **검증**: 신규 `RevlogInlineWriteRealHgInteropTest`(pull로 적용된 filelog가
    실제로 inline인지, real `hg verify`가 fncache 경고 없이 깨끗한지) GREEN.
    전체 회귀에서 처음엔 17건 실패 — 전부 "이 store는 항상 `.i`/`.d`로 분리된다"는
    낡은 전제를 하드코딩한 기존 테스트들(`BackoutCommandCoverageTest`,
    `CommitCommandCoverageTest`, `HisteditCommandCoverageTest`,
    `ShelveCommandCoverageTest`, `StatusCommandTest`, `StripCommandCoverageTest`,
    `RevlogTest`)이었지 실제 데이터 정합성 문제는 아니었음 — 각 테스트를 "필요한
    파일의 `.i`를 미리 빈 파일로 touch해서 non-inline을 강제"하거나(대부분),
    "테스트 이름이 원래 의도한 대로 애초에 없어도 되는 시나리오"로 조정(Strip 2건)
    하거나, `Files.delete`를 `deleteIfExists`로(파일이 아예 없어도 되는 경우), 또는
    잘못된 가드 조건(`clDat.exists()`가 아니라 `mfDat.exists()`를 봐야 했던 버그
    1건)을 고쳐서 해결. 128KiB `_enforceinlinesize` 상당 로직(큰 파일은 새로
    커밋해도 non-inline으로 시작)은 이번에도 구현하지 않음(범위 밖, 별도 후속) —
    현재는 "새 revlog는 파일 크기와 무관하게 inline으로 시작"만 구현됨(실사용
    파일 대부분이 128KiB 미만이라 실질적 영향은 낮음). 전체 회귀 4회 재확인,
    최종 2268개 중 `PerformanceBenchmarkTest`(이 세션 내내 존재해온 무관한 2초
    타이밍 SLA 플레이키) 1건만 실패, 그 외 전부 GREEN.

36. ~~**`TagCommand`가 기존 태그 재태깅을 `-f` 없이도 허용함**~~ — ✅
    **완료(2026-09-04)**. 신규 발견(백로그 23번 tag 카테고리 완료 후 재검증 중
    승격). real hg는 이미 존재하는 태그 이름으로 다시 태깅하면 `abort: tag '%s'
    already exists (use -f to force)`로 거부하고 `--force`가 있어야 덮어쓰기를
    허용하는데, `TagCommand.java`에는 관련 가드가 전혀 없어 hg4j는 항상 무조건
    덮어쓰고 있었다. `TagCommand`에 `setForce(boolean)` 신설, 기존 태그로 재태깅
    시도 시 `force`가 false면 real hg와 동일한 메시지(`"tag '<name>' already
    exists (use -f to force)"`)로 `HgValidationException`을 던지도록 구현.
    `TagRealHgInteropTest`에 신규 테스트 4건(force 없이 거부, `-f`로 성공, 로컬
    태그가 기존 전역 태그와 충돌 시 force 없이 거부, 태그 제거는 force 불필요)
    추가 — real hg CLI와 거부 메시지까지 일치 확인. 기존
    `hg4jRetaggingMovesTheTagAndRealHgSeesTheNewTargetWithOldLineStale` 테스트는
    (원래 "hg4j never gates on -f"를 전제로 통과하던 테스트) `setForce(true)`를
    명시적으로 넣도록 조정해 원래 검증 의도(태그 이동 자체)를 보존.

37. ~~**dirstate-v2 저장소에서 hg4j 커밋이 기존 파일을 트리 구조에서 유실시킴**~~ —
    ✅ **완료(2026-09-04)**. 신규 발견([[exhaustive-interop-matrix-plan]]의
    requirement 매트릭스 Docker 30조합 TDD 중, real hg가 이미 커밋해둔 파일이 있는
    dirstate-v2 저장소에 hg4j `CommitCommand`로 새 파일을 추가 커밋하는 시나리오에서
    100% 결정적으로 재현). `hg debugstate`(플랫 덤프)는 기존 파일을 정상 표시하지만
    `hg status`/`hg files`/`hg verify`(트리 순회 기반, `children_start`/`count`
    사용)는 못 찾고, `hg verify`가 `"<file> in manifest1, but not marked as
    tracked in p1"` + `"dirstate inconsistent with current parent's manifest"`로
    실패했다.

    **근본 원인 확정**(`hg-rust-7.2.4` 컨테이너 내부의 실제 hg 소스
    `/build/mercurial-7.2.4/rust/hg-core/src/dirstate/dirstate_map.rs` 직접 대조로
    확인): real hg의 Rust dirstate-v2 리더는 부모 노드의 자식 배열에서 특정 파일을
    찾을 때 `binary_search_by(|node| node.base_name(on_disk).cmp(base_name))`
    (`dirstate_map.rs:278`)로 **이진 탐색**을 쓰고, 쓰기 경로(`on_disk.rs:327
    sorted()`)는 자식 노드를 반드시 basename 오름차순으로 정렬해서 쓴다. hg4j의
    `DirstateV2Serializer`는 `LinkedHashMap` 삽입 순서 그대로(정렬 없이) 썼다 —
    삽입 순서가 우연히 내림차순이 되면 오름차순을 가정하는 real hg의 이진 탐색이
    해당 파일을 못 찾는다. hg4j 자신의 리더는 스택 기반 DFS라 순서 무관이었기
    때문에 자기 자신과의 라운드트립만 우연히 통과해온 것 — 처음에 조사했던
    `children_start` 오프셋 가설은 틀렸고, 진짜 원인은 정렬 여부였다.

    **수정**: `DirstateV2Serializer.java`에서 root list와 각 디렉터리의 children
    list 양쪽 모두 basename의 UTF-8 바이트 기준 오름차순으로 정렬(Java `String`의
    UTF-16 비교가 아니라 실제 인코딩 바이트로 비교해 real hg의 `&[u8]`/`HgPath`
    순서와 정확히 일치시킴). `DirstateV2SerializerCoverageTest`에 회귀 유닛
    테스트 2개(루트 레벨/중첩 레벨 정렬) 추가. `RequirementMatrixDockerRoundTripTest`
    의 18개 스킵 처리를 제거하고 60개 케이스(30읽기+30쓰기) 전부 GREEN 확인,
    전체 회귀도 새 실패 없음(`BUILD SUCCESSFUL`).

38. ~~**동시 push 레이스 컨디션 — real hg와 완전히 동일한 동작 검증 필요**~~ — ✅
    **완료(2026-09-04)**. `PushCommand`(로컬 push 경로)는 이미
    `repository.lockStore()`(`HgLock`, POSIX 원자적 symlink 생성 기반)를 쓰고
    있었지만, **서버 방향**(`Wire1Commands.unbundle` → `HgLocalClient
    .pushWithHooks` → `PullCommand.applyBundle` → `FetchCommand.applyBundle`)이
    실제 동시 요청에서 어떻게 동작하는지는 미검증이었다.

    **real hg 자신의 기준선 확인** (`mercurial/lock.py`/`localrepo.py`/
    `scmutil.py`, 호스트에 설치된 real hg 7.2 소스 직접 확인 + 실측): `repo.lock()`/
    `wlock()`은 기본 `wait=True`로 `ui.timeout`(기본 `"600"`초)만큼 1초 간격으로
    재시도하다 실패하면 `error.LockHeld`를 던지고, `scmutil.callcatch()`가
    `"abort: %s: timed out waiting for lock held by %r\n"`(예:
    `"abort: repository /path: timed out waiting for lock held by 'host:pid'"`)
    형태로 출력한다 — real `hg serve`의 store lock을 인위적으로(가짜
    `host:pid` symlink) 잡아두고 `ui.timeout=2`로 설정한 뒤 real hg CLI로 push해
    실측: 정확히 ~2초 대기 후 실패했고(`wireprotov1server.unbundle()`은
    `error.LockHeld`/`LockUnavailable`을 따로 잡지 않아 클라이언트에는 그냥
    `"abort: HTTP Error 500: Internal Server Error"`로만 보임), 이후 `hg verify`로
    저장소가 전혀 손상되지 않았음을 확인.

    **hg4j 서버 방향의 실제(수정 전) 동작**: `HgRepository.lockStore()`가 항상
    `new HgLock(file, 0, true)`(즉시 실패, 대기 없음)로 고정돼 있어, push 적용
    경로가 lock을 못 따면 real hg처럼 대기하지 않고 그 자리에서 즉시
    `HgLockException`을 던졌다 — real hg의 "대기 후 타임아웃" 형태와 불일치.

    **구현**: (1) `HgRepository`에 `lockStore(int timeoutMs)`/
    `lockWorkingCopy(int timeoutMs)` 오버로드 신설(기존 무인자 버전은 `timeoutMs=0`
    위임으로 완전히 하위 호환 유지 — commit/update/rebase 등 다른 모든 호출부는
    그대로 즉시-실패); `resolvePushLockTimeoutMs()` 신설, real hg의 `ui.timeout`과
    동일한 개념으로 저장소 자체 hgrc의 `[ui] timeout`(기본 `"600"`초, hg4j 쪽
    기본 600000ms)을 읽어 push/unbundle 경로에서만 사용. (2) `FetchCommand
    .applyBundle`/`PullCommand.applyBundle`에 `lockTimeoutMs` 오버로드 추가.
    (3) `HgLocalClient.pushWithHooks`(HTTP·SSH·file:// 세 방향 모두가 공유하는
    서버측 unbundle 적용 지점)가 `remoteRepo.resolvePushLockTimeoutMs()`를 이
    오버로드에 전달 — real hg의 대기·타임아웃 SHAPE을 그대로 재현. (4)
    `PushCommand`(클라이언트측 자신의 로컬 저장소 lock)도 동일하게 대기하도록
    변경 — real hg `exchange.py push()`가 소스 저장소도 `repo.lock()`(wait=True
    기본)으로 잡는 것과 동일(규칙 2: read/write, client/server 양쪽 다 처리).
    (5) `HgLock`의 타임아웃 메시지에 `"-- timed out waiting for lock after
    <ms>ms"` 접미사 추가(대기 후 실패임을 real hg 메시지 SHAPE에 맞춰 구분;
    기존 "Could not acquire..."/"Currently held by..." 부분 문자열은 보존해
    기존 테스트 전부 그대로 통과).

    **구현 중 발견한 별도 버그(수정 완료)**: `HgRepository.lockStore(int)`/
    `lockWorkingCopy(int)`가 (기존 무인자 버전과 마찬가지로) `synchronized`
    인스턴스 메서드였던 것이, 대기 로직이 없던 시절엔 무해했지만 실제 대기가
    생기자 **자기 자신을 교착시키는 버그**로 즉시 드러남: 한 스레드가 lock을
    기다리며 `synchronized` 메서드 안에서 최대 timeoutMs만큼 머무르면, 그동안
    같은 `HgRepository` 인스턴스의 다른 `synchronized` 메서드는 (그 lock과
    무관하더라도) 전부 블록된다 — 실측(2026-09-04): 진짜 동시에 겹치는 두
    real-hg push를 hg4j HTTP 서버에 쏘자, 이긴 쪽 스레드가 진 쪽의 전체 대기
    시간(30초) 동안 자신과 무관한 `synchronized` 호출에 멈춰 30초 넘게
    걸림 — 두 요청 모두 정상 완료되긴 했지만 실질적으로 "동시성"이 전혀
    없었던 셈. `lockStore`/`lockStore(int)`/`lockWorkingCopy`/
    `lockWorkingCopy(int)` 전부에서 `synchronized`를 제거해 해결(상호배제는
    이미 `HgLock` 자신의 static, path-keyed `JVM_ACTIVE_LOCKS` + 원자적 파일
    시스템 symlink 생성만으로 충분히 보장되고 있었음 — `HgRepository`
    인스턴스 모니터는 애초에 불필요했던 것으로 확인). 수정 후 같은 동시 push
    테스트가 30초대에서 1.8초로 단축.

    **검증**: 신규 `PushLockRaceRealHgInteropTest`(`@Tag("interop")`) 5건, 전부
    real hg CLI를 push 클라이언트로 사용, HTTP·SSH 양쪽 전송 모두 커버 —
    (1)/(3) `httpServerWaitsOutContendedStoreLockThenAcceptsRealHgPush`/
    `sshServer...`: store lock을 배경 스레드로 1.5초 잡아둔 뒤 real hg push,
    push가 실제로 대기했다가(elapsed ≥ hold 시간) 성공하고 커밋이 정확히
    반영됨을 확인. (2)/(4) `httpServerAbortsRealHgPushAfterConfiguredTimeout
    WhenStoreLockHeldTooLong`/`sshServer...`: `ui.timeout=1`초로 설정한 채 lock을
    5초간 잡아둠 — real hg push가 정확히 설정된 타임아웃 근처(대기 전체
    5초가 아니라)에서 실패하고, 실패한 push는 아무것도 반영되지 않았으며
    (`hg verify` 클린, revision count 불변), 이어지는 정상 push는 여전히
    성공함(저장소가 막히지 않음)을 확인. (5)
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository`: 두 개의
    독립된 real hg CLI 프로세스를 `CountDownLatch`로 동시에 풀어 진짜 겹치는
    push 레이스를 유발 — 결과와 무관하게(둘 다 성공하거나 하나만 성공 — 과제
    자체가 명시한 두 결과 모두 허용) `hg verify`로 저장소가 절대 손상되지
    않았음과 revision count가 실제 성공 건수와 정확히 일치함(부분/중복 적용
    없음)을 확인.

    **후속 확장(2026-09-04, 같은 날 사용자 지시로 범위 내 편입)**: 위
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository` 테스트가
    실측한 대로, 최초 구현은 lock 대기/타임아웃만 real hg와 맞췄을 뿐 real hg의
    `PushRaced`(`mercurial/error.py`)에 해당하는 **독립적인 서버측 재검증**이
    없어서 두 real-hg 클라이언트가 같은 오래된 head를 보고 각자 다른 자식
    커밋을 동시에 push하면 hg4j 서버가 **양쪽 다 적용**해(2개 head 생성) real
    hg 서버라면 거부했을 시나리오를 놓치고 있었다 — 사용자가 이를 "범위 밖"이
    아니라 이번 항목에 포함해 즉시 구현하도록 지시. real hg 소스 확인
    (`mercurial/bundle2_part_handlers.py`의 `handlecheckheads()`,
    `mercurial/exchange.py`의 `_pushb2ctxcheckheads()`/`check_heads()`) 결과:
    real hg 클라이언트는 `--force`가 아니고 실제로 push할 게 있으면 bundle2
    봉투에 `check:heads` 파트(클라이언트가 push를 계산할 때 본 원격 head 목록)를
    changegroup 파트보다 먼저 넣어 보내고, 서버는 이 파트를 **lock을 실제로
    획득한 뒤**(트랜잭션 내부) 처리하면서 `sorted(heads) != sorted(op.repo.
    heads())`면 `error.PushRaced("remote repository changed while pushing -
    please try again")`를 던진다 — legacy(non-bundle2) 경로는 대신 `unbundle`
    명령 자체의 `heads=` 인자를 (lock 이전에) 검사하며, `--force`는
    `heads=[b'force']`(단, `wireprototypes.encodelist()`가 이것도 그냥
    `hex()`로 인코딩하므로 실제 wire 값은 `"666f726365"`— `hex(b'force')` —
    이지 리터럴 단어 "force"가 아님, 실측: 리터럴 "force"를 real hg 서버에
    보내면 서버의 `decodelist()`가 hex 디코드에 실패해 깨짐)로 체크를 완전히
    생략한다.

    구현: (1) `Bundle2Parser.ExtractedBundle2`에 `checkHeadsRaw` 필드 추가,
    `check:heads` 파트의 원시 20바이트 head 목록을 파싱. (2)
    `HgPushRacedException`(`HgValidationException` 상속) 신설. (3)
    `FetchCommand.applyBundle`/`PullCommand.applyBundle`에 `PostLockValidator`
    콜백 오버로드 추가 — store/wlock을 실제로 잡은 직후, 아무것도 쓰기 전에
    실행되어(따라서 실패해도 journal 등 부분 상태가 전혀 남지 않음) real hg의
    "lock 획득 후 재검증" 타이밍을 그대로 재현. (4)
    `HgLocalClient.buildPushRaceValidator()`가 (a) bundle2 `check:heads` 파트가
    있으면 그걸, (b) 없으면 legacy `heads=` wire 인자(단, 비어있거나 force
    센티널이면 스킵)를 써서 검증기를 만들고, `pushWithHooks`가 lock-timeout과
    함께 이를 `applyBundle`에 전달 — HTTP·SSH·file:// 세 방향 모두가 공유하는
    지점이라 한 번의 구현으로 전부 커버됨. (5) `NodeIdUtil
    .computeUnbundleHeadsWireValue()`의 force 분기를 real hg와 동일하게
    `hex(b'force')`로 wire 인코딩하도록 수정(기존엔 리터럴 "force"를 그대로
    보내 real hg 서버와의 실제 --force 왕복이 깨져 있었음 — 이전에는
    `PushCommand`가 force 시에도 항상 진짜 head 목록을 보내 이 분기 자체가
    한 번도 실행된 적이 없었던 잠재 버그, 이번에 처음 실사용되며 발견·수정);
    서버측(`HgLocalClient`)은 리터럴 "force"와 hex 인코딩 두 형태를 모두
    force로 인식(전자는 wire 인코딩을 안 거치는 `file://` 로컬 피어 경로용).
    (6) `PushCommand.call()`이 `--force`일 때 실제 head 목록 대신
    `["force"]` 센티널을 보내도록 수정(real hg의 `_pushchangeset`:
    `if pushop.force: remoteheads = [b'force']`) — 이게 없으면 강제 push
    자체가 새 레이스 체크에 걸려 스스로 거부당할 위험이 있었음.

    검증: `PushLockRaceRealHgInteropTest`에 5건 추가(총 10건) —
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository`를
    "성공 개수 ≥1"에서 "정확히 1개만 성공, 진 쪽은 real hg와 동일한
    'changed while pushing'/'try again' 계열 메시지로 거부, 최종 head
    1개"로 강화, SSH 버전(`twoRealHgClientsRacingOverSsh...`) 신규 추가,
    "레이스가 아닌 경우 과잉 거부 안 함" 네거티브 컨트롤 2건
    (`sequentialNonRacingRealHgPushesBothSucceedOverHttp` — 순차 push는 둘 다
    성공, `concurrentNoOpPushDoesNotTripRaceCheckAlongsideARealPushOverHttp` —
    보낼 게 없는 push는 애초에 `check:heads` 파트 자체가 없어 레이스 체크
    대상이 아님을 확인). 구현 중 기존 테스트 6건이 새로 깨졌다가 전부
    원인 규명 후 수정: `PushCommandTest` 2건(`QuirkyLocalConnection`이 일부러
    null/전부-0 sentinel을 head 목록에 섞어 보내는 방어 코드 테스트 — 레이스
    체크가 그 쓰레기 값에 NPE, `HgLocalClient`에 null/all-zero 필터링 추가로
    해결), `Wire1CommandsTest`/`HgSshWireServerTest` 4건(빈 저장소로의 push를
    검증하면서 `heads=` wire 인자에 "들어오는 커밋 자신의 hex"라는(이 인자가
    당시 죽은 코드였을 때는 무해했던) 의미 없는 값을 넣어뒀던 테스트 픽스처
    — 실제로 이 값이 쓰이게 되자 "헤드 없음"이어야 할 빈 저장소 대상 push가
    스스로 레이스로 오탐되어 실패 — 픽스처를 "heads 인자 생략/빈 값"으로
    수정해 원래 테스트 의도 보존), `HgSshClientRealHgInteropTest`/
    `PushRealHgInteropTest`의 force 관련 2건(위 (5)번 wire 인코딩 버그 — real
    hg 서버가 리터럴 "force"를 받고 깨짐). 전체 회귀 최종: 비-interop
    `test` 2269건 0 실패/0 에러(2 스킵), `interopTest` 239건 중 이 항목과
    무관한 기존 `StripRealHgInteropTest` 사전 존재 실패 2건(수정 전
    베이스라인에서도 동일 재현 확인)을 제외하고 전부 GREEN.

39. **[[exhaustive-interop-matrix-plan]] 매트릭스 범위 확장 — 명령 커버리지가
    극히 일부에 머물러 있음**. 신규, 2026-09-04 사용자 지시로 등록 —
    **Wave 1 부분 진행(2026-09-05), 나머지는 여전히 미착수 (아래 참고)**.
    requirement 매트릭스(36개 조합, native 6 + Docker 30, 전부 GREEN 확정)는
    원래 `CommitCommand`/`LogCommand`/`StatusCommand`/`CatCommand` **4개 명령**
    에만 적용돼 있었고, 나머지 로컬/저장소 전용 명령 55개(`AddCommand`,
    `AmendCommand`, `BookmarkCommand`, `MergeCommand`, `RebaseCommand`,
    `ShelveCommand`, `StripCommand`, `SubrepoCommand` 등, 전체 목록은
    [[exhaustive-interop-matrix-plan]] §3-2)는 이 36개 조합 전체를 통과한 적이
    없었다. wire 매트릭스(21개 조합, HTTP 18 + SSH 3, 전부 GREEN 확정)도
    `CloneCommand`/`PullCommand`/`PushCommand` **3개 명령**에만 적용돼 있고,
    `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
    `NarrowCloneCommand` 5개는 여전히 미착수([[exhaustive-interop-matrix-plan]]
    §4, 이 wave에서 다루지 않음).

    **Wave 1 진행 현황(2026-09-05)**: 사용자 지시로 우선순위 4개 명령
    (`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`)에 requirement
    매트릭스(native 6 + Docker 30 = 36개 조합)를 확장 적용. 새 테스트 클래스
    8개 추가(명령당 native/Docker 각 1개 — `RequirementMatrixPushCoreRoundTripTest`/
    `RequirementMatrixPushDockerRoundTripTest`/`RequirementMatrixRebaseCoreRoundTripTest`/
    `RequirementMatrixRebaseDockerRoundTripTest`/`RequirementMatrixShelveCoreRoundTripTest`/
    `RequirementMatrixShelveDockerRoundTripTest`/`RequirementMatrixStripCoreRoundTripTest`/
    `RequirementMatrixStripDockerRoundTripTest`, 전부 `src/test/java/io/github/search5/hg4j/api/`)
    + Docker 쓰기 경로 corruption 회피용 subprocess 헬퍼 4개(`RequirementMatrixStripHelperMain`/
    `RequirementMatrixRebaseHelperMain`/`RequirementMatrixShelveHelperMain`/
    `RequirementMatrixPushHelperMain`, 기존 `RequirementMatrixCommitHelperMain`과
    동일한 패턴 재사용). 명령별 결과:

    - **`RebaseCommand`**: native 6/6 + Docker 30/30 전부 GREEN. 새 실버그
      없음(diverging 2-branch 히스토리를 rebase하는 시나리오가 모든 조합에서
      깨끗하게 통과).
    - **`StripCommand`**: native 6/6 + Docker 30/30 전부 GREEN — 단, 실제
      운영 코드 버그 2건을 TDD로 발견·수정한 뒤에야 도달함(아래 "발견·수정한
      실버그" 참고). 이 수정으로 기존에 원인 불명이던 `StripRealHgInteropTest`의
      사전 존재 실패 2건(백로그 23 완료 보고에 "무관한 사전 존재 실패"로
      기록돼 있던 것)도 근본 원인이 규명되어 함께 해소됨.
    - **`ShelveCommand`**: native 6/6 + Docker 30/30 전부 GREEN — `StripCommand`와
      동일한 근본 원인의 버그를 공유하고 있어 같은 수정으로 함께 해소.
      추가로 "changelog-v2인데 sidedata-copies 기능은 안 쓰는" 조합(`cl2`,
      `cl2+sidedata` 아님)에서 **real hg 자신의 결함**을 발견(아래 참고) —
      hg4j 문제가 아니므로 테스트에서 명시적으로 tolerate 처리.
    - **`PushCommand`**: **native 6/6 + Docker 30/30 전부 GREEN(2026-09-05,
      사용자 지시로 같은 세션 내에서 즉시 후속 수정 — 아래 "발견·수정한
      실버그" #3/#4 참고)**. 처음엔 native 2/6, Docker 14/30만 GREEN이었고
      나머지는 전부 2가지 진짜 hg4j 프로덕션 버그(treemanifest dirlog 미전송,
      changelog-v2+sidedata 미전송)로 실패했었다 — 실패 패턴이 매우
      일관적이었음(native/Docker 양쪽에서 실패한 조합은 전부 `treemanifest`
      또는 `cl2+sidedata`를 포함하는 조합뿐이고, `persistent-nodemap`/
      `fileindex-v1`/`general-v2`/`dirstate-v2` 자체는 새로운 실패를 유발한
      적이 없어 Docker 16개 실패 = treemanifest 9개 + cl2+sidedata·flat 7개로
      정확히 예측과 일치했음). 사용자가 "백로그 39 범위 안에서 지금 바로
      고치라"고 지시해 같은 세션에서 두 버그 모두 수정, 재검증까지 완료.

    **발견·수정한 실버그 2건(`StripCommand`/`ShelveCommand` 공유 근본 원인)**:
    1. `StripCommand`/`ShelveCommand`가 각자 따로 구현하고 있던 revlog 물리
       truncate 로직이 **inline revlog**(backlog #35로 신규 non-changelog v1
       revlog의 기본값이 된 레이아웃)와 **v2/docket 기반 revlog**(changelog-v2/
       general-v2)를 전혀 다루지 못했다 — 항상 "non-inline, 64바이트 고정
       레코드"만 가정. inline revlog를 그렇게 자르면 real hg가 "index
       00manifest is corrupted"로 완전히 거부(이게 바로 위에서 언급한
       `StripRealHgInteropTest`의 원인불명 사전 존재 실패 2건의 정체였음).
       v2/docket을 그렇게 자르면 실제 데이터 파일이 아니라 docket 헤더
       파일 자체를 잘라버리고, 그마저도 실제 companion 파일(UUID 기반
       `<radix>-<uuid>.idx`/`.dat`) 경로를 전혀 모른 채 엉뚱한 경로를
       건드려 다음 open에서 `BufferUnderflowException`. 두 명령의 중복
       로직을 없애고 **`Revlog.truncate(int keepCount)`** 신규 공용
       메서드(`storage/Revlog.java`)로 통합 — inline/non-inline v1/v2 세
       가지 레이아웃 전부 올바르게 처리(v2는 `RevlogIndex.updateV2DocketSizes()`
       로 docket의 index_end/data_end까지 갱신). `StripCommand.truncateRevlog()`/
       `ShelveCommand.stripRevisionsFrom()`의 중복 구현은 삭제하고 이
       메서드를 호출하도록 변경.
    2. 위 통합 도중 발견한 2차 버그: non-inline 분기에서 `.i` 파일을 먼저
       물리적으로 truncate한 뒤에 `.d` 파일 truncate 크기를 계산하려고
       `getRevisionCount()`를 다시 호출하면, `RevlogIndex.checkAndUpdate()`가
       "방금 줄어든 `.i` 파일 크기"를 보고 revisionCount를 이미 줄어든 값으로
       재계산해버려 `.d` truncate 자체가 조용히 no-op이 되는 버그(정확한
       크기 계산은 반드시 두 파일 다 건드리기 **전**에 끝내야 함). 이 버그가
       `ShelveRealHgInteropTest#unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable`
       회귀를 유발했다가(원인 규명 도구: `git stash`로 수정 전/후 A/B 테스트),
       계산 순서를 "먼저 계산, 나중에 두 파일 다 truncate"로 고쳐 해결.
       전체 회귀(비-interop `test` + `interopTest`)로 재확인, 새 회귀 없음.

    **real hg 자신의 결함 1건(hg4j 버그 아님, 발견만 함)**: changelog-v2를
    쓰면서 sidedata-copies 기능은 켜지 않은 저장소(`cl2` 조합)에서
    `hg shelve`(real hg 자신의 것) 자신이 v2 docket이 참조하는(항상
    비어있고 안 쓰이는) `<radix>-<uuid>.sda` companion 파일을 삭제해버리면서도
    docket 헤더의 sidedata_uuid 필드는 그대로 남겨둬서, 그 이후 아무 때나
    `hg verify`가 "No such file or directory: ...sda"로 abort. **hg4j를
    전혀 거치지 않는 순정 real hg 단독 재현으로 확인**(`hg init` →
    `hg shelve` → `hg unshelve` → `hg verify`만으로 100% 재현). 테스트에서
    이 특정 시그니처만 명시적으로 tolerate.

    **발견·수정한 진짜 hg4j 버그 2건 더(`PushCommand`, 2026-09-05 같은 세션
    내 즉시 후속 수정 — 사용자가 "백로그 39 범위 안에서 지금 바로 고치라"고
    명시적으로 지시)**:
    3. **treemanifest 조합에서 디렉터리 manifest(dirlog)를 전혀 전송하지
       않던 버그**: `PushCommand`의 changegroup 빌드 로직(`repository.getManifestRevlog()`
       만 사용)은 루트 manifest revlog 하나만 다루고, `meta/<dir>/00manifest.i`
       형태의 디렉터리별 manifest revlog는 전혀 열거하지 않았다 — 반면
       읽는/받는 쪽인 `FetchCommand.applyBundle()`은 이미
       `bundle.manifestGroups`(디렉터리별 그룹)를 완전히 지원하고 있었다.
       근본 원인은 더 근본적이었다: `PushCommand`가 항상 cg1(`HG10UN`)만
       하드코딩해서 만들고 있었는데, cg1 자체가 treemanifest 봉투(`bundle.
       manifestGroups`)도 sidedata 청크도 구조적으로 실어 나를 수 없는
       포맷이었다(`ChangegroupParser#isTreeCapableVersion`은 cg3/cg4/cg5만
       true). `HgLocalClient#getBundle()`(pull/getbundle 응답 방향)이 이미
       `bundleCaps`에서 버전을 협상해 `ChangegroupParser.writeBundle()`로
       패킹하는 것과 같은 패턴을, push 쪽은 목적지 능력 협상 대신 **"이
       push가 보내는 데이터 자체가 무엇을 요구하는지"로 직접 결정**하도록
       수정(로컬 file:// 목적지는 애초에 capabilities에 bundle2= 정보가
       없어 협상이 불가능함을 확인 후 내린 판단) — sidedata가 필요하면
       cg5, treemanifest만 필요하면 cg3(둘 다 아니면 기존 그대로 cg1, 압도적
       다수인 평범한 저장소는 와이어 바이트 변화 없음). 디렉터리 dirlog
       열거는 fncache에 등록되지 않으므로(파일로그와 달리) `store/meta/`
       트리를 직접 재귀 스캔해서 찾는 방식으로 구현(`CommitCommand
       #writeTreeManifestDir`가 쓰는 것과 동일한 무-인코딩 경로 규칙 재사용).
       hand-롤 cg1 전용 writer(`writeEntryChunk`/`writePathChunk`/
       `writeTerminalChunk`)를 완전히 제거하고 `ChangegroupParser.writeBundle()`
       (이미 cg1-5 전부 지원, `HgLocalClient#getBundle()`이 실전 검증한 것과
       동일한 코드)을 재사용하도록 교체. 필요하면 `Bundle2Parser.
       wrapChangegroupInBundle2()`로 HG20 봉투도 씌운다(로컬/서버 양쪽 다
       `HgLocalClient#pushWithHooks()`가 이미 HG20 파싱을 지원 — 백로그 26).
    4. **changelog-v2 + sidedata-copies 조합에서 sidedata를 전혀 전송하지
       않던 버그**: `PushCommand`에는 sidedata 관련 코드가 아예 없었다.
       `HgLocalClient#getBundle()`의 `packChangelogSidedata`(백로그 26)와
       완전히 대칭인 로직을 push 쪽에도 추가 — 위에서 결정한 버전이 cg5이고
       저장소가 `isSidedataCopies()`이면 각 changelog 엔트리에 `changelog.
       getSidedata(r)`로 읽은 sidedata를 `SidedataCodec.serialize()`로
       실어 보낸다. 받는 쪽(`Revlog.appendChangeGroupEntry`)은 이미
       `entry.sidedata`를 처리하는 코드가 있었다(pull 방향에서 이미
       검증됨) — push 쪽에 대칭 코드를 추가하는 것만으로 충분했다.
       부수적으로 cg2+ 포맷이 요구하는 `deltabase`/`flags` 필드도 changelog/
       manifest(root)/filelog 세 그룹 전부에 채워 넣었다(cg1은 스트림 순서로
       암묵적으로 나타내던 것을 cg2+는 명시적 필드로 요구함) — 파일로그와
       신규 dirlog 패킹은 완전히 동일한 규칙이라 `packRevlogRange()`라는
       공용 헬퍼로 통합.

       **검증**: 두 수정 후 `RequirementMatrixPushCoreRoundTripTest`(native
       6/6) + `RequirementMatrixPushDockerRoundTripTest`(Docker 30/30) 전부
       GREEN 재확인. 기존 push 관련 테스트(`PushCommandTest`,
       `CatUpdateClonePushCoverageTest`, `CHgPushRoundtripTest`,
       `PushRealHgInteropTest`, `PushLockRaceRealHgInteropTest`)와 비-interop
       `test` 전체(2270+건)도 회귀 없음 재확인.

    **환경 메모**: 이 세션에서 Docker의 `localhost/hg-rust-7.2.4` 태그가
    사라져 있어(이미지 자체는 `hg-rust-7.2.4:latest`로 존재) 처음엔 Docker
    매트릭스 전체가 조용히 스킵됐다 — `docker tag hg-rust-7.2.4:latest
    localhost/hg-rust-7.2.4`로 로컬 재태깅해 해결(코드 변경 아님, 이 머신의
    Docker 상태일 뿐 — 다음 세션이 같은 머신이 아니면 다시 필요할 수 있음).

    **Wave 2(2026-09-05, PushCommand 수정 완료 직후 사용자 지시로 계속
    진행)**: 다음 우선순위 명령으로 `AmendCommand`를 선택 — `CommitCommand`
    (이미 원본 36개 조합 전체에서 검증됨)에 실제 쓰기를 위임하고 obsstore에
    평문 마커만 추가하는 얇은 명령이라, "이미 검증된 저수준 원시 동작에
    위임하는 명령이 모든 조합에서 정말 안전하게 동작하는지"를 확인하는
    좋은 다음 표본으로 판단(사용자가 권장한 "방금 고친 것과 코드 경로를
    공유하는 명령" 기준과는 별개로, "이미 검증된 CommitCommand에 위임하는
    명령들이 조합 전체에서 안전한지"라는 다른 유용한 신호). `RequirementMatrixAmendCoreRoundTripTest`
    (native)/`RequirementMatrixAmendDockerRoundTripTest`(Docker) +
    `RequirementMatrixAmendHelperMain`(subprocess 헬퍼) 신규 추가.
    **Native 6/6 + Docker 30/30 전부 GREEN** — 새 실버그 없음(`CommitCommand`로의
    위임이 모든 36개 조합에서 안전함을 재확인).

    **남은 것(대부분 미착수)**: 나머지 로컬 명령 50개(`AddCommand`,
    `BookmarkCommand`, `MergeCommand`, `SubrepoCommand`, `BundleCommand`
    (조사 중 `PushCommand`의 수정 전과 동일한 cg1-only 하드코딩을 그대로
    갖고 있는 것을 발견 — 다음 wave 후보로 유력) 등 — 전체 목록
    [[exhaustive-interop-matrix-plan]] §3-2에서 이번에 다룬 5개
    (`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`/
    `AmendCommand`) 제외), wire 매트릭스의 나머지 5개 명령
    (`FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
    `NarrowCloneCommand`). "67개 명령 × 매트릭스" 전체 목표 중 실제로
    완주된 것은 이제 명령 기준 11/67(4+3+4 — `PushCommand`도 이제 실버그
    없이 완주로 카운트, `AmendCommand`도 native 확인 완료 기준으로 포함).

    **Wave 3(2026-09-05, `AddCommand`/`BookmarkCommand`/`TagCommand`, 사용자
    지시로 독립 진행)**: 세 명령 각각에 native 6개 + Docker 30개 = 36개 조합
    테스트 클래스 세트(`RequirementMatrix{Add,Bookmark,Tag}CoreRoundTripTest`/
    `...DockerRoundTripTest`/`...HelperMain`, 기존 8개와 동일 패턴 재사용)를
    신설. **세 명령 모두 native 6/6 + Docker 30/30 전부 GREEN.**

    - **`AddCommand`**: 새 실버그 없음. 시나리오는 이미 커밋된 루트 파일 1개 +
      신규 루트 파일 1개 + 신규 하위 디렉터리 파일 1개(루트 레벨 경로 세그먼트
      3개)를 추가해, 백로그 #37(dirstate-v2 자식 노드 정렬)이 재현하려면
      필요했던 "형제가 2개 이상인 루트" 모양을 정확히 만들어 그 수정이 커밋
      경로뿐 아니라 `AddCommand`가 직접 쓰는 dirstate 경로에서도 여전히
      유효함을 재확인.
    - **`BookmarkCommand`**: 진짜 hg4j 프로덕션 버그 3건 발견·수정(1건은
      매트릭스 통과 *이후* 전체 회귀에서 드러남 — 아래 참고).
      (1) `BookmarkCommand`에 `TagCommand`의 백로그 #36 force 게이트에
      대응하는 장치가 전혀 없었다 — 이미 존재하는 bookmark를 그 현재 위치의
      자손이 아닌 리비전으로 옮길 때 real hg 7.2는 `abort: bookmark '<name>'
      already exists (use -f to force)`로 거부하는데(CLI로 직접 실측,
      2026-09-05), hg4j는 아무 검사 없이 조용히 덮어썼다 — 순방향(자손,
      fast-forward) 이동과 동일 대상으로의 이동은 여전히 force 없이 허용,
      새 이름 생성도 그대로 허용, 되돌리기(backward)/divergent 이동만
      `HgValidationException` + 동일 메시지로 거부하도록 게이트 신설
      (`setForce(boolean)` 추가). 이 게이트의 "순방향" 판정은 처음엔 단순
      changelog DAG 조상 관계(`ChangesetGraph#isAncestor`)만 봤는데, 전체
      회귀(`test`+`interopTest`)를 돌리자 기존 `PushRealHgInteropTest#
      testPushOfBookmarkAdvancedAcrossAmendSucceedsWithoutForce`가
      즉시 깨졌다 — `hg amend`로 만든 후속 커밋은 원본 커밋의 **형제**(같은
      parent를 공유)일 뿐 자손이 아니므로, "amend 직후 활성 bookmark를
      후속 커밋으로 전진"이라는 실제 hg 자신의 표준 동작이 새 게이트에 막혀버렸다.
      `PushCommand`가 이미 `discovery._postprocessobsolete`/`bookmarks.
      validdest` 대응으로 구현해둔 것과 똑같은 "obsolescence-successor 체인도
      순방향으로 인정"하는 `obsutil.foreground` 로직(`isInForeground` +
      `.hg/store/obsstore` 파싱)을 `BookmarkCommand`에도 자체 구현(코드
      결합을 피하려 이미 검증된 `PushCommand`의 사본을 건드리지 않고 독립
      복사본으로 추가)해 최종 해결. 이 과정에서 진짜 2번째 버그도 드러났다:
      **`CommitCommand`가 매 커밋마다 활성 bookmark를 새 커밋으로 자동
      전진시키는 내부 호출**(active bookmark 자동 추적)이 새로 추가된 이
      게이트를 그대로 통과해야 했는데, `AmendCommand`는 obsmarker를 성공한
      커밋의 노드 해시가 있어야만 쓸 수 있어 항상 커밋 **다음**에 기록한다
      — 즉 `CommitCommand`의 내부 자동전진 호출 시점엔 obsstore에 predecessor→
      successor 링크가 아직 존재하지 않아 이 경로만은 foreground 판정이
      실패했다. real hg 자신도 이런 내부 재작성(`scmutil.cleanupnodes`)에
      의한 bookmark 이동은 사용자 대화형 `hg bookmark -r`의 validdest 게이트를
      거치지 않고 무조건 이동시키므로, `CommitCommand`의 그 한 곳만
      `.setForce(true)`로 게이트를 우회하도록 수정(일반 커밋의 경우 새 커밋의
      parent1이 정확히 옛 활성 위치라 어차피 항상 순수 fast-forward였으므로
      동작 변화 없음 — 변화는 amend류 내부 위임 경로에서만 발생). 별도로,
      `mergeFromRemote`가 이미 스스로 올바른 판단(로컬이 사라진 리비전을
      가리킬 때 원격을 그대로 채택)을 내린 뒤 호출하는 한 곳도 새 게이트를
      우회하도록 `setForce(true)`를 추가해 기존 pull/fetch 병합 동작은 그대로
      유지. (2) 마지막 남은 bookmark를 삭제하면 real hg는 `.hg/bookmarks`를
      0바이트 빈 파일로 남겨두는데(CLI로 직접 실측: 한 번이라도 생성된 적
      있는 파일은 지우지 않음), hg4j는 파일 자체를 삭제해버렸다 — 실질적 동작
      차이는 없지만(양쪽 다 "bookmark 없음"으로 읽힘) real hg와 바이트 단위로
      맞추도록 빈 문자열을 씀으로 수정. (3) 위 게이트 수정과 별개로, 기존
      `PushRealHgInteropTest#testPushRejectedWhenBookmarkMovedToDivergentSiblingWithoutObsolescenceLink`
      는 진짜 divergent(조상도 후속자도 아닌) 이동을 로컬 `BookmarkCommand`
      호출에서 force 없이 하고 있었다 — 그 테스트 자신의 기존 javadoc이 이미
      "실제 hg CLI 검증 시 force-moved"라고 적어뒀던 대로, 새 게이트가 생긴
      지금은 그 로컬 호출에 `.setForce(true)`를 붙이는 게 맞는 수정(테스트의
      실제 검증 대상은 그 다음 PUSH 시점의 거부이지, 로컬 이동 자체가 아님)
      — 테스트 쪽만 수정, `BookmarkCommand`/`PushCommand` 프로덕션 로직은
      그대로. 부수적으로, 매트릭스 시나리오를 작성하며 **하나의
      `HgRepository` 핸들을 real hg CLI의 외부 커밋 이후에도 계속 재사용하면
      안 된다**는 사실을 재확인(캐시된 `Revlog`가 외부에서 새로 쓰인 리비전을
      보지 못해 `HgRevisionNotFoundException`으로 실패) — 이건 hg4j
      프로덕션 코드의 버그가 아니라 `HgRepository#refreshIfChangedOnDisk()`
      의 javadoc이 이미 명시적으로 문서화한, 장기 보관 핸들(wire 서버)에만
      해당하는 의도된 설계이므로 테스트 쪽을 "명령마다 새 `HgRepository`"라는
      기존 매트릭스 전체의 관례에 맞게 고쳤을 뿐, 프로덕션 코드는 건드리지
      않음. **검증**: native 6/6+Docker 30/30 재확인, 위에서 깨졌던
      `PushRealHgInteropTest`(전체) 재확인, `RebaseRealHgInteropTest`/
      `ShelveRealHgInteropTest`/`StripRealHgInteropTest`/`TagRealHgInteropTest`
      /`BookmarkRealHgInteropTest`/`CommitCommandTest`/`CommitCommandCoverageTest`
      전부 GREEN, 전체 비-interop `test`(587건) GREEN. 전체 `interopTest`
      1회 완주 시도에서 무관한 기존 타이밍 플레이크(`PushLockRaceRealHgInteropTest`
      의 SSH 동시 레이스 테스트, 단독 재실행 시 통과 확인 — 동시 실행 중이던
      다른 wave-3 에이전트들의 부하로 인한 것으로 추정, hg4j 로직과 무관)
      1건 외 전부 통과.
    - **`TagCommand`**: 새 실버그 없음. 기존 `TagRealHgInteropTest`(백로그
      23)가 이미 동작 자체(생성/이동/삭제/로컬/force 게이트/병합 커밋 태깅)를
      기본 포맷 저장소 하나로 철저히 검증해뒀으므로, 이번 매트릭스는 그
      테스트가 전혀 다루지 않은 축(changelog v1/v2(+sidedata) x flat/tree
      manifest, Docker에서는 추가로 dirstate-v2/persistent-nodemap/
      fileindex-v1/general-v2)에서 태그 생성 → 로컬 태그 → 강제 이동 → 삭제
      전체 시퀀스(매번 `CommitCommand`로 실제 커밋)가 살아남는지만 확인 —
      전부 GREEN. `AmendCommand`(wave 2)와 마찬가지로 이미 검증된
      `CommitCommand`에 위임하는 명령이 모든 조합에서 안전함을 다시 한 번
      재확인한 표본.

    이로써 "67개 명령 × 매트릭스" 목표의 명령 기준 완주 수는 11에서
    14(+`AddCommand`/`BookmarkCommand`/`TagCommand`)로 증가.

40. **Narrow clone의 진짜 wire-protocol 수준 ellipsis node 왕복 — 여전히
    구현 자체가 없음**. 신규, 2026-09-04 사용자 지시로 등록(백로그 28/30에서
    각각 "범위 밖으로 명시적으로 남긴 것"으로 이미 문서화됐던 것을 별도
    번호로 승격) — 미착수. 백로그 30번 완료로 narrow clone 이후 로컬에서
    pull/update 시 narrowspec 필터를 다시 읽어 범위 밖 파일을 걸러내는
    것까지는 구현됐지만(로컬 changegroup 적용 단계에서 필터링), 이건 진짜
    wire-protocol 수준 narrow pull(실제 hg의 `narrow` 확장이 서버·클라이언트
    간에 협상하는 `ellipsis node` — 범위 밖 리비전을 실제 조상 정보를 보존한
    "가짜" 압축 노드로 대체해서 전송량 자체를 줄이는 메커니즘)과는 다르다.
    hg4j는 narrowspec을 clone/pull 요청 시점에 서버로 전달해 서버가 실제로
    범위를 좁혀 전송하게 만드는 프로토콜 수준 협상이 없고, 대신 항상 전체
    changegroup을 받은 뒤 로컬에서 걸러내는 방식이다 — 기능적으로는 워킹
    디렉터리/추적 목록 결과가 real hg와 일치하지만(백로그 30번에서 검증됨),
    "narrow clone으로 전송량을 줄인다"는 narrow clone의 원래 목적 자체는
    달성하지 못한다(대역폭/저장 절감 효과 없음). 실제 hg의 wire protocol v1
    `narrow` capability 협상(`narrow=`, `depth=` 파라미터, ellipsis 노드
    생성 로직 — `mercurial/narrowbundle2.py` 실측 필요)을 구현하는 것이
    이 항목의 목표. 상당히 큰 작업이므로 별도 세션에서 범위 산정부터 착수
    권장.

41. **SVN 서브저장소(`[svn]` prefix) 지원 — 전혀 없음**. 신규, 2026-09-04
    사용자 지시로 등록, **우선순위 최하** — 미착수. real hg의 `.hgsub`
    스펙은 서브저장소로 Mercurial(기본)/Git(`[git]` prefix)/SVN(`[svn]`
    prefix) 세 종류를 지원한다. Git 쪽은 `HgSubrepoParser`가 이미 URL
    prefix 파싱은 해두고 있어(백로그 32번이 마무리하는 커밋측 상태 갱신만
    남음) 그나마 절반은 진행된 상태지만, **SVN은 코드베이스 어디에도
    관련 처리가 전혀 없다**(2026-09-04 `grep` 확인 — `HgSubrepoParser`의
    prefix 인식조차 없음). 범위: `[svn]` URL 파싱, SVN 워킹카피 상태 조회
    (`svn info`/`svn status` 상당의 로컬 SVN 클라이언트 연동 필요 —
    hg4j에 SVN 관련 인프라가 전무해 맨땅에서 시작해야 함), `.hgsub`/
    `.hgsubstate` 연동은 git 서브저장소 패턴을 참고해 대칭적으로 구현.
    실사용 빈도가 낮고(Mercurial 자체에서도 SVN 서브저장소는 git보다
    훨씬 드물게 쓰임) 작업량 대비 가치가 낮아 최하 우선순위로 등록 —
    32/38/39/40번을 전부 마친 뒤에 착수할 것.

42. **LFS 세부 옵션 3가지 — 백로그 31번 완료 시 범위 밖으로 남긴 것**.
    신규, 2026-09-04 문서 재감사로 발견(gap table "LFS" 행/백로그 31번
    완료 본문에 번호 없이 있던 캐비어트를 승격) — 미착수. 백로그 31번이
    커밋/체크아웃 파이프라인 연동은 끝냈지만, 다음 3가지는 명시적으로
    범위 밖으로 남겼다: (1) rename과 LFS가 동시에 걸린 파일의 copy-tracing
    메타데이터 처리, (2) `[lfs] url` config로 원격 LFS 서버 주소를
    override하는 기능, (3) `lfs.disableusercache` 등 캐시 동작 세부 옵션.
    셋 다 real hg CLI로 실측해서 hg4j 동작과 대조 검증 후 필요한 만큼
    구현할 것.

43. **Revlog가 성장해도 inline→non-inline 전환을 안 함 (`_enforceinlinesize`
    상당 로직 없음)**. 신규, 2026-09-04 문서 재감사로 발견(백로그 35번
    완료 본문에 번호 없이 있던 캐비어트를 승격) — 미착수. 백로그 35번에서
    "새 revlog는 항상 inline으로 시작"하도록 고쳤지만, real hg는 그 이후
    revlog가 자라서 131072바이트(`_maxinline`)를 넘으면 inline에서
    non-inline으로 **전환**하는 로직(`_enforceinlinesize`)이 있다 — hg4j는
    이 "성장 중 전환"이 구현돼 있지 않아서, 계속 커지는 파일을 반복 커밋하는
    실사용 시나리오에서 revlog가 무한정 inline 상태로 남아있을 가능성이 있다
    (정확한 영향 범위 미확인 — 단순히 비효율인지, 어느 시점부터 실제 파싱
    오류로 이어지는지 real hg 소스/CLI로 확인 필요).

44. **Clonebundles 서버: 매니페스트 파일 없을 때 응답 포맷 미확인**. 신규,
    2026-09-04 문서 재감사로 발견(clonebundles 실행 계획의 서버측 항목
    9번, 원래도 "우선순위 낮음"으로 표시돼 있던 것을 정식 번호로 승격)
    — 미착수, 우선순위 낮음. `?cmd=clonebundles` 핸들러가 매니페스트
    파일이 아예 없는 상태에서 요청을 받았을 때 real hg가 빈 응답을 주는지
    404를 주는지 실측 확인이 안 된 상태 — real hg CLI로 직접 재현해서
    확인 후 hg4j 서버 동작을 맞출 것.

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

- ✅ `ClonebundlesManifest`(신규, `io.github.search5.hg4j.bundle`) — 매니페스트
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

## 커버리지 95% 이니셔티브 라운드 3 — 배치1~17, missed≥5 순수 신규 클래스 체크리스트 완주 (2026-09-02)

라운드 2가 "이미 다뤘다"고 판단한 클래스 다수를 재검증해보니 실제로는 추가 개선
여지가 상당히 남아있었음을 실측으로 확인(예: `Revlog` 97.2%→99.1%, `Hg`파사드
95.4%→98.8%, `PhaseRoots` 죽은 `Function` 파라미터 제거 등). 이어서 round1/2가
손대지 않은 "순수 신규" 클래스를 missed instruction 큰 순서로 4개씩(17차만 3개)
17개 배치에 걸쳐 전수 TDD 처리했다. 전체 회귀(14+15+16+17차 통합 병합검증) 기준
INSTRUCTION 99.14%/LINE 99.03%/METHOD 99.70%/CLASS 100%까지 끌어올렸다(BRANCH는
92.20%로 아직 미달이나 잔여 갭 다수가 파일/줄 단위로 "도달 불가능한 방어적 코드"임을
확인·문서화). 이번 구간(배치10~17, 27개 클래스)에서는 실제 버그가 발견되지 않았다
— 라운드2 44개 클래스 때(버그 21건)와 대비되는 지점으로, 그만큼 코드베이스가
안정화됐다는 신호로 해석. 부수적으로 `HgRemoteConnectionFactory$4`(로컬 디렉터리
존재 확인 프로토콜)가 심하게 미커버 상태임을 발견해 향후 백로그 후보로 남겼다.
missed≥5 기준 순수 신규 클래스 체크리스트는 17차로 완전히 소진됐다. 전체 배치별
전후 수치, 새로 확인된 도달불가 잔여 갭 목록은 [[test-coverage-95-percent-initiative]]
"라운드 3 계속(배치 8~17)" 절에 상세 기록.

## HTTP v1 인자 전송(X-HgArg-N) 버그 발견·수정 + mercurial-0.2 프레이밍 버그 (2026-09-03)

사용자 요청("interop 통신과정에서 광고하는 버전으로 협상하는지 면밀하게 확인해줘")에 따라
`getbundle`의 changegroup 버전 협상이 실제 hg 서버와의 라이브 통신에서 진짜로 광고한 버전에
수렴하는지 검증하다가, 그 협상 자체를 무력화시키는 훨씬 근본적인 버그를 발견했다.

### 버그 1 (SEVERE): `HgRemoteClient`가 인자 전달에 POST를 쓰고 있었다 — 실제 hg는 GET+헤더

실제 hg의 `mercurial/httppeer.py`(`makev1commandrequest()`)를 직접 읽고, 로컬 TCP 로깅
프록시로 실제 `hg --debug clone` 세션의 원본 바이트를 캡처해 확인한 실제 스펙: 인자를 가진
v1 명령(`getbundle`/`changegroup`/`pushkey`)은 3단계 폴백으로 전송된다.
1. 서버가 `httppostargs` 광고 시: POST 바디 = urlencode(정렬된 인자), 헤더
   `X-HgArgs-Post: <len>`.
2. 서버가 `httpheader=<N>` 광고 시(실제 hg 서버 기본값): **GET** 요청, 인자 없는 쿼리스트링,
   urlencode된 인자 문자열을 `X-HgArg-1`, `X-HgArg-2`, ... 요청 헤더로 쪼개 전송(각 청크는
   `N - len("X-HgArg-0") - 4`바이트), `Vary: X-HgArg-1,...` 헤더 동반.
3. 둘 다 없으면(구식/최소 서버): 레거시 — 인자를 그대로 쿼리스트링에 붙인 평범한 GET.

hg4j의 `HgRemoteClient`는 이 셋 중 어느 것도 구현하지 않고 **항상 POST 폼바디**로 보내고
있었다 — 실제 hg 서버의 v1 인자 파서는 이 명령들에 대해 POST 바디를 전혀 읽지 않으므로,
서버는 `bundlecaps` 등 인자를 아예 못 받은 것으로 보고 조용히 구식 bundle1(cg1)로
폴백했다. 즉 이전 세션에서 "01~05까지 광고하도록" 고친 것 자체는 틀리지 않았지만, 전송
메커니즘이 잘못돼 있어 실사용 효과가 사실상 전무했다.

**수정**: `HgRemoteClient`에 `executeArgsCommand()`(3단계 분기), `negotiateV2()`가
`httppostargs`/`httpmediatype=`/`compression=` 토큰도 파싱하도록 확장, 서버가 실제로
`httpheader=`를 광고했을 때만 헤더 tier를 쓰고 그렇지 않으면 레거시 쿼리스트링 tier로
폴백(`sawHttpHeaderCap` 플래그) — 이 폴백 분기 자체도 최초 구현에서 누락되어 있었다가
회귀 테스트(`HgRemoteAndSyncTest`, capabilities가 비어 있는 최소 mock 서버)에서 곧바로
발각·수정됨. `x-hgproto-1` 헤더(`buildXHgProto1Header()`)도 처음 구현해 real hg의
`sorted(protoparams)` 방식대로 동일하게 헤더분할·전송. 서버 측(`HgHttpWireServer`)에는
`X-HgArg-N` 재조립 로직을 추가하고 `Wire1Commands.capabilitiesString()`에
`httpheader=1024`를 광고에 추가. 검증: `HgArgProtocolTest`(mock 서버로 GET/POST/헤더분할/
x-hgproto-1 내용을 바이트 단위로 확인), `HgHttpWireServerTest`(hg4j↔hg4j 자기 정합성),
`HgHttpV1LiveServerCgNegotiationInteropTest`(`hg-rust-7.2.4` 실 서버로 라이브 검증 — 응답을
직접 압축 해제해 CHANGEGROUP 파트의 `version=` 파라미터를 읽어 실제로 `04`가 협상됨을
확인; 서버의 `changegroup=01,02,03` capabilities 토큰만으로 예측한 `03`보다 높은 값이었다 —
서버 측 버전 선택이 평평한 리스트의 단순 max가 아니라 요청별 실시간 판단이라는 뜻).

### 버그 2 (별도, 위 수정의 부산물로 발견): `application/mercurial-0.2` 응답 프레이밍이 스펙과 달랐다

위 수정으로 `x-hgproto-1`이 처음 제대로 전송되자, 실제 hg 서버가 사상 처음으로 hg4j
클라이언트에게 `application/mercurial-0.2` + zstd/zlib 압축 응답을 실제로 보내왔고, 이
과정에서 `HgRemoteClient`의 -0.2 파싱이 실제 hg 서버 응답과 맞지 않는다는 것이 드러났다.
`curl`로 원본 응답 바이트를 직접 캡처해 확인: 압축명(`zstd`/`zlib`) 뒤에 hg4j가 가정하던
"4바이트 길이 프리픽스 청크 프레이밍"이 전혀 없이 압축 매직바이트(zstd `28 b5 2f fd`, zlib
`78 9c`)가 곧바로 이어짐 — 실제 포맷은 `[1바이트 이름길이][이름][압축된 페이로드가 스트림
끝까지 그대로]`뿐이었다. hg4j 자체 서버(`HgHttpWireServer`)는 -0.2를 절대 안 보내므로(항상
-0.1만 응답) 이 파싱 경로는 지금까지 실제 hg 서버 상대로 한 번도 검증된 적이 없었다 — 이번
수정 전까지는 x-hgproto-1 자체가 안 나갔으니 실제 서버가 -0.2를 골라줄 일도 없었다.

**수정**: `unwrapResponseStream`에서 `MercurialChunkedInputStream`(스펙에 없는 클래스)
사용을 제거하고 이름 뒤 바이트를 곧바로 압축 해제하도록 변경, zstd 지원도 추가(zstd-jni는
이미 의존성으로 있었음). 해당 클래스 및 그 클래스만을 대상으로 하던 리플렉션 유닛테스트
전부(`HgRemoteClientTest`/`HgRemoteClientStreamTest`/`HgRemoteClientCoverageTest`, 총
13개) 삭제, 나머지 -0.2 통합 테스트들의 mock 바디를 실제 포맷대로 재작성. 이 스코프
확장(클래스 삭제 포함)은 사용자에게 `AskUserQuestion`으로 먼저 확인 후 진행.

### 결과

전체 회귀(2270 테스트) 100% 통과, real Mercurial 7.2.4(Rust 확장 포함, `hg-rust-7.2.4`
이미지) 라이브 서버 대상 changegroup 버전 협상 및 -0.2 압축 응답 둘 다 실제로 검증됨.

## SSH 전송 계층 전면 재구현 — HgSshClient가 실제로는 전혀 다른(발명된) 프로토콜을 쓰고 있었다 (2026-09-03)

위 HTTP 작업 완료 후, 사용자가 코드리뷰에서 지적한 두 항목("SSH bundlecaps 콤마는 고쳐졌지만
실제 SSH 협상에서 작동하는지 미검증", "push()의 heads `+` 구분자가 버그인지 의도인지 불명")을
검증하다가, 애초 예상보다 훨씬 근본적인 문제를 발견했다.

### 사전 확인: `+` 구분자는 버그가 아니었다

`mercurial/wireprotov1peer.py`(`unbundle()`)를 직접 확인: 실제 hg도 heads를 `encodelist()`
(기본 구분자 공백)로 인코딩한 뒤 그 문자열을 HTTP 폼 인코딩(`urlencode`)하면서 공백이
자동으로 `+`로 변환된다 — hg4j가 직접 `+`로 join하는 것은 그 결과와 바이트 단위로 동일해
버그가 아니었다(다만 `unbundlehash` capability가 있을 때 real hg가 heads 목록 대신 SHA1
해시로 대체하는 별도 최적화는 hg4j에 없음 — 별개의 저우선순위 갭으로 기록만 함).

### 발견한 진짜 문제: `HgSshClient`의 SSH v1 인자 전송 프로토콜 자체가 실제 hg와 다르다

`mercurial/sshpeer.py`(`_sendrequest`)를 직접 확인한 결과, 실제 hg SSH v1은 인자별로
`"<key> <바이트길이(4바이트 헤더 자신 포함 아님, 텍스트 줄)>\n"` + 그만큼의 **raw 바이트**(값
뒤에 개행 없음)를 정렬된 키 순서로 보낸다. `*` 와일드카드 인자명은 `"* <count>\n"` +
`count`개의 `"<name> <len>\n<bytes>"` 트리플로 중첩 인코딩된다. 그런데 `HgSshClient`는
클라이언트·서버 양쪽에서 서로 짜맞춘, 스펙에 없는 단순 `"key value\n"` 줄 기반 포맷을 쓰고
있었다 — HTTP의 -0.2 청크 버그와 동일한 패턴(자기 자신끼리는 일관되지만 실제 hg와는 한 번도
검증된 적 없음)이되, 이번엔 심지어 **hg4j 자신의 서버(`HgSshWireServer`)와도 안 맞았다** —
`HgSshWireServer`는 이미 실제 hg 스펙대로(`"<argname> <len>\n<bytes>"`) 올바르게 구현돼
있었고(class javadoc에 "Mercurial 6.0 대조 검증" 명시), `HgSshClient`만 다른 프로토콜을
말하고 있었던 것. 즉 두 클래스를 실제로 연결하면 **핸드셰이크부터 데드락**이었다(사용자
확인: "생각보다 범위가 큽니다" → `AskUserQuestion`으로 전면 재구현 여부 확인 후 진행).

### 재구현 중 실제로 캡처한 데드락 4건 (각각 real hg 소스 확인 후 수정)

1. **핸드셰이크 자체가 틀림**: 기존 코드는 서버가 아무 명령 없이 먼저
   `"capabilities: ...\n"`을 쓴다고 가정. 실제 hg는 `_performhandshake()`에서 클라이언트가
   먼저 `"hello\n" + "between\n" + "pairs 81\n" + <81바이트 null-range 값>`을 한 번에 보내고,
   `hello`의 framed 응답(`capabilities: ...` 포함)과 `between`의 framed 응답(null-range라
   1바이트 `"\n"`)을 순서대로 읽는다 — hg4j 서버는 명령을 받을 때까지 기다리므로, 기존
   클라이언트와 연결하면 양쪽 다 상대가 먼저 말하길 기다리며 영원히 블로킹.
2. **`unbundle`(push)에 서버의 사전 확인 응답이 빠져 있었다**: 실제 hg는 `heads` 인자를 읽은
   직후 **빈 프레임을 먼저 보내** "페이로드 보내도 됨"을 알린 뒤에야 페이로드를 읽는다
   (`wireprotoserver.py`의 `getpayload()`). `HgSshWireServer`는 이 사전 확인 응답 없이 바로
   페이로드를 읽으려 해서, 새로 고친 클라이언트(사전 확인을 기다림)와 맞물려 서버는 페이로드를,
   클라이언트는 응답을 기다리며 데드락. 서버 쪽에 사전 확인 프레임 전송 +
   응답을 "에러-또는-empty 확인" + "성공 시에만 결과값" 2단계로 분리하도록 수정.
3. **cg1 changegroup 자체의 내부 청크 길이가 "헤더 포함(inclusive)" 규칙인데 "헤더 제외"로
   읽고 있었다**: `HgLocalClient`의 실제 writer(`writeEntryChunk`)를 확인하니
   `totalLen = 4(자기 자신) + 80 + delta.length` — 즉 length 필드 자신의 4바이트까지 포함한
   값이다. 기존 리더는 `len`바이트를 그대로 데이터로 읽어 매번 4바이트씩 밀려 다음 청크의
   length 필드를 쓰레기값으로 오독, 그 쓰레기값만큼 읽기를 기다리며 블로킹. changelog
   그룹 → manifest 그룹 → 파일별 그룹(각각 0000 종료) 순서로 구조를 이해하는 리더로 재작성.
4. **`getbundle` 응답이 bundle2(`"HG20"`)일 수 있는데 raw cg1 리더만 있었다**: real hg SSH
   서버(`hg-rust-7.2.4`가 아니라 이번엔 host의 native hg 7.2.2, `hg -R <repo> serve --stdio`를
   서브프로세스로 실행해 진짜 SSH 세션으로 검증)를 상대로 실제 pull/push 테스트를 돌리자마자
   재발견 — bundle2는 cg1과 완전히 다른 자기 서술 구조(매직 + 파라미터 + 파트 시퀀스, 파트
   페이로드 청크는 오히려 "헤더 제외(exclusive)" 길이 규칙)라 별도 워크가 필요했다. 응답 첫
   4바이트를 봐서 `"HG20"`이면 bundle2 워크, 아니면 cg1 워크로 분기하도록 수정(단, 이미 읽은
   4바이트를 cg1 경로에서 그냥 버리면 changelog 그룹 첫 청크의 length 필드를 잃어버리므로,
   그 4바이트를 "이미 읽은 첫 청크 길이"로 재사용하도록 별도 처리).

### 검증

- `HgSshClientTest`/`HgSshClientTransportTest`: hg4j 클라이언트 ↔ hg4j 서버(이미 실제 hg
  대조 검증된 `HgSshWireServer`) 자기정합성 — capabilities/heads/changegroup/getbundle(null
  파라미터 포함)/push/pushkey/listkeys(다중 엔트리 — 기존 줄 기반 읽기의 "첫 줄만 읽혀 다중
  키 응답이 잘리는" 별도 버그도 이번에 같이 해소)/between/known 전부.
- `HgSshWireServerTest`: unbundle 성공/실패(pre-changegroup hook 거부) 양쪽 다 새 3단계 프레임
  응답 형태로 검증.
- **`HgSshClientRealHgInteropTest`(신규)**: host의 native 실제 hg 7.2.2를 `serve --stdio`
  서브프로세스로 띄워 진짜 SSH 세션(Apache MINA SSHD, JSch)으로 hg4j 클라이언트와 연결 —
  capabilities/heads/getbundle(실제로 cg1이 아닌 버전으로 협상됨을 직접 확인)/clone
  전체(pull, 커밋 메시지·이력 일치)/push 전체(hg4j에서 만든 커밋이 실제 hg 서버에 실제로
  반영됨, `hg log`로 확인) — 이 세션에서 애초 사용자가 요청한 "SSH 경로를 실제 hg로
  재검증"이 최종적으로 완료된 지점.
- SSH 관련 테스트 전체(82개) + 전체 회귀 재실행, 0 실패.
- 위 재구현 과정에서 노출된, 실제로는 **이 SSH 재구현과 별개로 이미 있던** 회귀 4건도 같은
  세션에서 발견·수정: `HgConcurrentAndHookTest.testRealSshRoundtrip`, `HgSshTransportRoundtripTest`
  3건, `HgRemoteMockAndServeExtensionTest`의 SSH 관련 3개 테스트 — 전부 옛 hg4j 프로토콜
  가정(단순 줄 기반, `readCapabilities` 즉시 배너, "헤더 제외" 청크 길이)으로 손으로 만든
  가짜 서버/응답 바이트를 쓰고 있었다. 가능한 곳은 `HgSshWireServer`(이미 실제 hg로 검증됨)를
  직접 백엔드로 쓰는 방식으로 교체, 나머지는 실제 프레이밍에 맞게 바이트를 다시 구성.

## unbundlehash 최적화 구현 (2026-09-03, 같은 세션 후속)

코드리뷰에서 지적된 나머지 한 항목 — "`unbundlehash` capability가 있을 때 실제 hg는 heads
목록 대신 SHA1 해시 sentinel을 보내는 별도 메커니즘이 있는데, hg4j는 구현 안 함" — 도 TDD로
마저 처리했다. `mercurial/wireprotov1peer.py`의 `unbundle()`을 확인:
```python
if heads != [b'force'] and self.capable(b'unbundlehash'):
    heads = wireprototypes.encodelist([b'hashed', hashutil.sha1(b''.join(sorted(heads))).digest()])
```
서버(`exchange.py`의 `check_heads()`)는 `their_heads`가 서버의 실제 현재 heads와 문자 그대로
같거나, `[b'hashed', sha1(정렬된 자기 현재 heads 이어붙인 것)]`와 같으면 수락한다 — 즉 클라
이언트가 (잠재적으로 훨씬 긴) 리터럴 heads 목록 대신 20바이트 SHA1 다이제스트만 보내도
되는 순수 와이어 크기 최적화다. `NodeIdUtil.computeUnbundleHeadsWireValue(heads,
serverSupportsUnbundleHash)`로 HTTP(`HgRemoteClient`)·SSH(`HgSshClient`) 양쪽에 공유
구현으로 추가, `capabilities`에 `unbundlehash` 토큰이 있을 때만 발동(없으면 기존과 동일하게
리터럴 heads 전송 — 회귀 없음).

**검증**: `HgSshUnbundleHashTest`/`HgHttpUnbundleHashTest`(신규, mock 서버로 정확한 와이어
바이트 확인) + `HgSshClientRealHgInteropTest`의 기존 push 테스트가 실제 hg SSH 서버로
그대로 재검증(다이제스트가 틀렸다면 실제 서버의 `check_heads()`가 `PushRaced`로 거부했을
것). 추가로 hg4j 코드를 전혀 거치지 않는 독립 Python 스크립트로 SSH(`serve --stdio`
서브프로세스)·HTTP(`hg serve --config web.push_ssl=false`) 양쪽에 hg4j와 동일한 형태의
요청을 직접 보내 재확인 — SSH는 `hashed` sentinel 전송 후 precheck 빈 프레임→error-or-empty
빈 프레임→`result=1`(성공), HTTP는 `"1\nadding changesets..."` 성공 응답을 실제로 받았다.
전체 회귀(2278 테스트) 재실행 결과 실패 1건(`CommitCommandTest`의 심링크 테스트, 트랜스포트와
무관 — 단독 재실행 시 통과, 이 세션 앞부분에서 이미 조사된 기존 타이밍성 플레이키와 동일)
외 전부 통과.

## 심볼릭 링크 dirstate mtime 버그 (2026-09-03, 별개 발견 — 프로토콜과 무관)

위 SSH/unbundlehash 작업 완료 후, 회귀에서 우연히 나온 `CommitCommandTest`의 심링크 관련
테스트 1건을 "플레이키"로 넘기려다 사용자가 제동을 걸어("재실행시 통과라는 말이 웃기잖아")
격리 재실행(8회 중 1회 실패)으로 재현성을 직접 확인, 근본 원인을 찾았다: `java.io.File`의
`lastModified()`/`length()`/`canExecute()`/`isFile()`/`exists()`는 심볼릭 링크를 항상
**따라가서**(follow) 링크 자신이 아니라 타겟의 정보를 반환한다(NIO의 `NOFOLLOW_LINKS`
같은 옵션이 legacy `File` API엔 없음). dirstate에 mtime을 기록/비교하는 코드가 이걸
모르고 썼다가, 심링크 자신은 안 건드렸는데 타겟 파일만 커지면 조용히 재커밋되거나
(반대로) 실제 변경을 놓치는 타이밍 의존적 버그였다.

**영향 범위**: `size`는 이미 lstat 인식(NIO `readSymbolicLink`)으로 처리하던 파일이
대부분이었지만 `mtime`만 buggy `File#lastModified()`를 그대로 쓰고 있었다 —
`CommitCommand`/`StatusCommand`/`AddCommand`/`UpdateCommand`/`RebaseCommand`(2곳)/
`ShelveCommand`(3곳)/`RevertCommand`/`CopyCommand`/`GraftCommand`/`CloneCommand` 총
10개 파일 12곳. `RenameCommand`/`RemoveCommand` 2개는 아예 lstat 인식 자체가 없어서
(size도 target을 따라감) 별도로 더 깊게 고쳐야 했다 — `RenameCommand`는 `exists()`가
dangling 심링크를 거짓으로 판단해 존재하는 파일도 rename 거부, mode/size/mtime 전부
타겟 기준으로 오염; `RemoveCommand`는 (1) 타겟 크기와 심링크 자신의 기록 크기를 비교해
손 안 댄 심링크를 "수정됨"으로 오판하고, (2) dangling 심링크는 `exists()`가 false라
물리 삭제 자체를 건너뛰어 dirstate엔 제거로 기록되지만 디스크엔 파일이 남는 버그가
있었다.

**수정**: `SafeFileIO.lastModifiedSeconds(File)` 공유 헬퍼(NIO
`Files.getLastModifiedTime(path, NOFOLLOW_LINKS)`) 신설, 12개 호출부 전부 교체.
`RenameCommand`/`RemoveCommand`는 `isSymbolicLink` 분기를 추가해 mode/size/mtime/삭제
로직을 다른 9개 파일과 같은 lstat 인식 패턴으로 맞춤. 각 버그를 TDD로(먼저 실패하는
테스트로 재현 확인 후 수정) 처리, `CommitCommandTest`의 원래 실패 테스트는 8/8 반복
실행으로 결정성 확정. 전체 회귀 2282 테스트, 0 실패.

## 인수인계 (2026-09-04 17:50, 다른 세션/다른 머신에서 이어서 진행하기 위함)

사용자 지시로 17:50에 작업 중지. **상세 인수인계는
[[exhaustive-interop-matrix-plan]] 문서 맨 아래 "인수인계" 절에 전부
정리돼 있다** — 재현 환경 정보(Docker 이미지, gradle 태스크 분리, 동시
빌드 한계, 공유 컴파일 출력 오염 이슈 포함), 완료/미검증/미착수 정확한
구분, 다음 할 일 순서까지 전부 거기 있으니 반드시 먼저 읽을 것. 여기서는
이 문서(백로그 번호) 기준 요약만 남긴다.

- **완료**: 1~30, 33, 34, 36, 37번 (25번은 오탐 종결).
- **미검증 상태로 커밋됨**: 35번(revlog non-inline) — 코드는 커밋 `ce767fa`
  에 반영됐으나 독립적인 전체 회귀 재확인 전. 다음 세션이 가장 먼저
  확인해야 함.
- **미착수**: 32번(subrepo, 31 완료로 이제 착수 가능), 38번(동시 push
  레이스 컨디션), 39번(매트릭스 확장), 40번(narrow ellipsis node).

## 관련 페이지
- [[jgit-parity-requirement]] — 구조/네이밍 요건 (이 문서와는 독립적인 축)
- [[test-coverage-95-percent-initiative]] — 커버리지 작업 라운드별 상세 결과/버그 목록
- [[revlog]], [[dirstate]], [[bundle2-changegroup]], [[revset]] — 이미 조사된 스펙 영역 상세
- [[core]], [[transport]] — 관련 구현 클래스 위치
- [[sources]] — 향후 원문 스냅샷을 추가할 위치
