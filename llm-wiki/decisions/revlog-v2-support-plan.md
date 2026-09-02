---
updated: 2026-09-02
status: current
---

# 계획: Revlog v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원, v1과 병행"으로 확정된 항목.
> **2026-09-01: changelog-v2는 구현·실제 hg CLI 상호운용 검증까지 완료.**
> **2026-09-02: 일반 revlog-v2(매니페스트/파일로그, `exp-revlogv2.2`)와 `fileindex-v1`도
> 읽기+쓰기 구현 및 실제 hg CLI 상호운용 검증까지 완료.** 이전에는 이 개발 환경에 Rust
> 확장이 없어 막혀 있었지만, `docker/hg-rust-7.2.4/Dockerfile`로 Rust 확장이 활성화된
> 실제 Mercurial 7.2.4를 직접 빌드해 그 제약을 해소했다 — 아래 "Rust 확장 포함 hg를
> Docker로 확보" 섹션 참고. `persistent-nodemap`(`.n` 트라이 파일)만 여전히 인식-only로
> 남아 있다 — "현재 구현 상태" 섹션 참고.

## 이전 시도의 문제와 이번에 다르게 한 것
2026-08-31 Gemini의 1차 구현은 두 번 반려됐다: 매직 넘버(`0x00020000`)와 헤더 레이아웃이
전부 **웹 검색 기반 추측**이었고, 실제 hg 데이터로 한 번도 검증되지 않았으며, 인덱스
메타데이터를 읽는 핵심 경로(`getIndexRecord()`)가 companion 파일이 아니라 docket
파일 자체를 읽으려 해서 애초에 동작할 수 없는 구조였다.

이번에는 **이 머신에 실제로 설치된 `hg` CLI(Mercurial 7.2, `/usr/bin/hg`)와 그 Python
소스(`/usr/lib/python3/dist-packages/mercurial/`)를 직접 사용**했다:
1. `hg --config format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data init`로
   실제 changelog-v2 저장소를 만들어 커밋 2회 수행.
2. `mercurial/revlogutils/docket.py`, `mercurial/revlogutils/constants.py`,
   `mercurial/requirements.py`를 직접 읽어 정확한 바이트 레이아웃과 requirement 문자열을
   확인.
3. 실제 저장소의 docket/index/data 파일을 `hexdump`+`python3 struct`로 바이트 단위 대조.
4. `zstd` CLI로 실제 압축 데이터를 해제해 평문 changelog 텍스트까지 확인.
5. hg4j로 이 실제 픽스처를 파싱하는 TDD 테스트를 먼저 작성(실패 확인) 후 구현.
6. **최종적으로 hg4j로 새 리비전을 append한 뒤, 그 저장소를 실제 `hg log`/`hg verify`로
   직접 열어 상호운용성까지 확인** (아래 "실제 hg CLI 상호운용 검증" 참고).

## 이전 계획의 "1. 바이너리 명세" 섹션은 전부 틀렸음 (정정)
과거 버전의 이 문서는 S_HEADER를 `>IBBLLLLc`(23바이트), UUID 16바이트 고정 쌍, "1바이트
길이+경로 문자열" 구조로 서술했다. **실측 결과 전부 사실과 다르다.** 정확한 구조는 아래
"검증된 바이너리 명세"로 대체한다.

## 검증된 바이너리 명세 (실제 hg 소스 + 실제 데이터 대조 완료)

### A. Docket 파일 구조 (docket 파일 자체는 여전히 `00changelog.i` 등 기존 이름 유지)
`S_HEADER = INDEX_HEADER_FMT(>I) + b'BBBBBBQQQQQQc'` — **59바이트**
(mercurial/revlogutils/docket.py):

| 오프셋 | 타입 | 필드명 | 실측값 예시(changelog-v2, 2 커밋) |
|---|---|---|---|
| 0~3 | `uint32` (BE) | version_header | `0x0000D34D` (하위 16비트가 매직) |
| 4 | `uint8` | index_uuid_size | 8 |
| 5 | `uint8` | older_index_uuid_count | 0 |
| 6 | `uint8` | data_uuid_size | 8 |
| 7 | `uint8` | older_data_uuid_count | 0 |
| 8 | `uint8` | sidedata_uuid_size | 8 |
| 9 | `uint8` | older_sidedata_uuid_count | 0 |
| 10~17 | `uint64` (BE) | index_end | 96(0x60), 192(0xc0) — 2커밋 후 |
| 18~25 | `uint64` | pending_index_end | index_end와 동일(트랜잭션 미구현) |
| 26~33 | `uint64` | data_end | 실제 압축 데이터 총합 |
| 34~41 | `uint64` | pending_data_end | data_end와 동일 |
| 42~49 | `uint64` | sidedata_end | 0(사용 안 하면) |
| 50~57 | `uint64` | pending_sidedata_end | sidedata_end와 동일 |
| 58 | `char` | default_compression_header | `(` (zstd) |

헤더(59바이트) 다음에 순서대로: `index_uuid`(index_uuid_size 바이트, **ASCII 16진수
문자열 그대로**, 추가 디코딩 없음) → older_index_uuids(있으면 `S_OLD_UID='>BL'` 5바이트
×count + 실제 uuid 바이트들) → `data_uuid` → older_data_uuids → `sidedata_uuid` →
older_sidedata_uuids.

**중요**: 이전 계획서가 말한 "1바이트 길이 + 경로 문자열"은 존재하지 않는다. 실제
companion 파일 경로는 **파일명 규칙으로 계산**한다: `{radix}-{uuid}.idx` /
`{radix}-{uuid}.dat` / `{radix}-{uuid}.sda` (radix = docket 파일명에서 확장자 제거,
예: `00changelog.i` → radix `00changelog`). 즉 하나의 docket이 index/data/sidedata
**3개의 독립된 파일**을 가리킨다 — "docketDataPath 1개"라는 이전 가정 자체가 틀렸다.

### B. 매직 넘버 (mercurial/revlogutils/constants.py 실측)
- `REVLOGV2 = 0xDEAD` (일반 revlog v2 — 매니페스트/파일로그)
- `CHANGELOGV2 = 0xD34D` (changelog 전용 v2)
- version_header의 **하위 16비트**가 이 값과 일치하면 v2. 이전 계획의 `0x00020000`은
  완전히 근거 없는 추측이었다.

### C. 인덱스 레코드 (96바이트, `mercurial/revlogutils/constants.py` 실측)
CL_V2와 일반 V2는 **레이아웃이 다르다** (필드 개수 차이로 node id 오프셋도 다름):

- `INDEX_ENTRY_CL_V2 = >Qiiii20s12xQiBi23x` (changelog 전용)
  offset+flags(Q,8) · complen(i) · uncomplen(i) · **parent1(i)** · **parent2(i)** ·
  node(20s, **오프셋 24**) · pad(12x) · sidedata_offset(Q) · sidedata_complen(i) ·
  compression_mode(B) · rank(i) · pad(23x)
  **baseRev/linkRev는 저장 필드가 없다** — 실측 결과 changelog-v2의 각 리비전은
  델타 체인 없이 **독립적인 zstd 프레임**으로 저장된다(매 hunk가 `28 B5 2F FD` zstd
  매직으로 시작). 따라서 파싱 시 `baseRev = linkRev = rev`로 합성해도 정확하다
  (delta 체인 추적이 즉시 종료되어 항상 fulltext로 처리됨 — 실제로 맞다).

- `INDEX_ENTRY_V2 = >Qiiiiii20s12xQiB19x` (일반 revlog v2 — 매니페스트/파일로그)
  offset+flags(Q,8) · complen · uncomplen · baseRev · linkRev · parent1 · parent2
  (i×6) · node(20s, **오프셋 32**) · pad(12x) · sidedata_offset(Q) · sidedata_complen(i)
  · compression_mode(B) · pad(19x). v1과 필드 순서는 동일하고 뒤에 sidedata 트레일러가
  붙는 구조. **2026-09-02: Rust 확장 포함 실제 hg 7.2.4(Docker)로 만든 fixture로
  바이트 단위 검증 완료** (`src/test/resources/fixtures/revlogv2-general/`,
  `RevlogV2GeneralParserTest`) — changelog-v2와 달리 baseRev/linkRev/parent1/parent2가
  전부 **명시적으로 저장**된다(합성하지 않음). hg4j의 기존 읽기 경로는 이미 이 레이아웃을
  올바르게 처리하고 있었다(과거 "changelog-v2만 지원" 코드 주석은 stale했던 것으로
  판명). 쓰기 경로(`Revlog.appendRevisionV2`)는 새로 구현 — 항상 `baseRev=rev`(풀텍스트,
  델타 없음)로 단순화해서 쓴다.

- 압축 모드(`compression_mode` 1바이트): changelog-v2 실측값 `9`(`0b1001`)는 하위
  2비트가 `COMP_MODE_DEFAULT(1)`을 가리킨다(= 저장소 기본 압축 엔진, 여기서는 zstd
  사용). `COMP_MODE_PLAIN(0)`으로 잘못 쓰면 **실제 hg가 zstd 바이트를 평문으로 취급해
  `hg verify`에서 integrity check failed가 남** — 실제로 재현·확정함. **일반 revlog
  v2(fixture)는 반대로 실측값이 `compMode & 0x3 == 0`(`COMP_MODE_PLAIN`)** —
  `.dat`에 v1처럼 앞에 압축 방식 마커 바이트(`'x'`/`'u'`/zstd 매직)를 붙이지 않고,
  압축 방식은 오직 이 필드로만 결정된다는 뜻. hg4j의 쓰기 경로도 general v2에서는
  `COMP_MODE_PLAIN`을 쓴다(검증되지 않은 v2 zstd 프레임 포맷을 재현할 필요가 없어짐).

### D. Requirement 문자열 (mercurial/requirements.py 실측, `.hg/requires`가 아니라
**`.hg/store/requires`**에 기록됨 — share-safe 저장소 기준)
- `exp-changelog-v2` — changelog가 v2(docket 기반)
- `exp-revlogv2.2` — 매니페스트/파일로그가 일반 revlog v2 (버전 접미사 `.2`까지 정확히 일치).
  **주의**: 이 requirement만 켜면(changelog-v2는 별개) changelog **자신도** 일반 v2
  포맷(`0xDEAD`)으로 저장된다 — `exp-revlogv2.2`와 `exp-changelog-v2`는 서로 독립된
  requirement로, 어느 쪽을 켜느냐에 따라 changelog가 어느 v2 변종을 쓰는지가 갈린다.
- `persistent-nodemap` — `.n` 트라이 파일 사용
- `fileindex-v1` — `exp-revlogv2.2` 저장소가 fncache 대신 쓰는 파일 경로 인덱스
  (`.hg/store/fileindex`, 방사 트라이 — docket + list/meta/tree 3개 컴패니언 파일).
  원래 계획 문서에는 전혀 없던 requirement로, 구현 도중 발견했다. 상세 바이트 명세는
  아래 "fileindex-v1 바이너리 명세" 섹션 참고.

## fileindex-v1 바이너리 명세 (mercurial/store_utils/file_index_util.py 실측 + 실제 fixture 대조)

`exp-revlogv2.2`를 켠 저장소는 `fncache`(평문 경로 목록) 대신 `fileindex`를 쓴다. 이
개발 환경엔 Rust 없는 hg만 있어 실제 저장소로 마주치기 전까지 이 requirement의 존재
자체를 몰랐다 — 발견 즉시 사용자에게 알리고 "읽기+쓰기 전체 구현" 승인을 받아 진행.
스펙은 이 환경에 설치된 순정 hg(Rust 없음)의 pure-Python 참조 구현
(`/usr/lib/python3/dist-packages/mercurial/store_utils/file_index_util.py`,
`file_index.py`)을 직접 읽어 확인 — Rust 없이도 **읽을 수는** 있는 소스 트리라
정확한 스펙 확보가 가능했다(Rust는 그 스펙으로 저장소를 **생성**할 때만 필요).

- **Docket**(`.hg/store/fileindex`, 실측 68바이트): `struct = ">12s 3I 8s8s8s 3I"` =
  marker(`b"fileindex-v1"`, 12바이트) + list_file_size(4) + meta_file_size(4) +
  tree_file_size(4) + list_file_id(8자리 ascii hex) + meta_file_id(8) + tree_file_id(8)
  + tree_root_pointer(4) + tree_unused_bytes(4) + reserved_flags(4) = 60바이트 고정
  헤더, 뒤에 `GarbageList`(헤더 `>II` num_entries+path_buf_size, hg4j 쓰기 경로는
  항상 비워서 씀 — 8바이트).
- **List 파일**(`fileindex-list.<uid>`): NUL로 구분된 경로 문자열을 이어붙인 평문
  버퍼(예: `b"a.txt\x00sub/b.txt\x00"`). 경로는 store-encode된 경로(`data/...`)가
  아니라 **원본 논리 경로**(매니페스트에 쓰이는 그대로) 그대로 들어간다.
- **Meta 파일**(`fileindex-meta.<uid>`): 8바이트 `Metadata` 엔트리(`struct=">IHH"` =
  offset(4)+length(2)+dirname_length(2)) 배열, 토큰 0번은 루트용 예약 엔트리(전부 0).
- **Tree 파일**(`fileindex-tree.<uid>`): 방사 트라이. 물리 `TreeNode` =
  `TreeNodeHeader`(`struct=">IBB"` = token(4)+label_length(1)+num_children(1)) +
  child_chars(자식 개수만큼, 각 자식 라벨의 첫 바이트) + child_ptrs(자식 개수 ×
  4바이트 `PointerOrToken` — 최상위 비트(MSB) 세트면 물리 노드 없는 리프이고 그
  값은 파일 토큰(라벨은 `meta_array[token].length - position`으로 암묵적 계산);
  MSB 클리어면 다른 물리 `TreeNode`로의 실제 바이트 오프셋). 각 노드의 라벨
  **바이트 자체는 저장하지 않고**, `node.token`이 가리키는 파일의 list 파일 상의
  경로에서 현재 깊이(position)만큼 슬라이스해서 복원한다(`_read_label`) — 그래서
  내부(분기) 노드도 반드시 자신의 라벨 바이트를 실제로 포함하는 어떤 토큰을 갖고
  있어야 한다(그 노드를 만든 삽입 대상 경로의 토큰을 그대로 씀).
- **읽기 알고리즘**(`FileIndexView.get_token`/`get_path`): 루트부터 트라이를 내려가며
  경로 바이트를 노드 라벨과 매칭, 첫 글자가 다르면 child_ptrs로 분기.
- **쓰기 알고리즘 — hg4j는 실제 hg와 다르게 "항상 전체 재빌드" 전략을 쓴다.** 실제
  hg의 `MutableTree.insert()`/`serialize()`는 기존 트리 파일 뒤에 새/복사된 노드만
  이어붙이는 증분 copy-on-write 알고리즘이고, 일정량 이상 가비지가 쌓이면 "vacuum"으로
  가끔만 전체 재빌드한다. hg4j(`FileIndex.writeTrackedPaths`)는 커밋마다 매번 그
  vacuum 경로를 타는 것과 동치인 동작을 한다: 현재 추적 중인 전체 경로 집합을 읽고
  새 경로를 더한 뒤, **완전히 새로운 트리를 새 UUID 3개로 통째로 재빌드**한다. 트라이를
  빌드하는 알고리즘 자체(`FileIndex.TrieBuilder`)는 `MutableTree.insert()`/
  `serialize()`를 `base=None`으로 실행하는 경우와 필드 단위로 동일하게 포팅했다 —
  다른 점은 "매번 재빌드하느냐 vs 증분 append 후 가끔 재빌드하느냐"뿐이므로, 생성되는
  바이트는 실제 hg 자신의 vacuum 결과와 완전히 같은 형태(스펙상 100% 유효)이고, 다만
  커밋을 거듭할수록 실제 hg보다 디스크 낭비가 크다(정확성 우선, 최적화는 후속 과제).
- **롤백 안전성**: `writeTrackedPaths`는 새 docket을 쓰자마자 이전 세대의 컴패니언
  파일을 바로 지운다(실제 hg처럼 TTL을 둔 지연 삭제가 아님) — `CommitCommand`처럼
  같은 트랜잭션 안에서 나중에 실패해 롤백해야 할 수 있는 호출자는 반드시
  `FileIndex.snapshot(storeDir)`으로 먼저 스냅샷을 뜬 뒤 쓰고, 실패 시
  `FileIndex.restore(storeDir, snapshot)`으로 복원해야 한다(fncache 백업/복원과
  동일한 패턴).

## 현재 구현 상태 (2026-09-01)
| 항목 | 상태 |
|---|---|
| changelog-v2 docket 파싱(59바이트 헤더, 3-UUID 파일 해석) | ✅ 구현+실제 데이터 검증 |
| changelog-v2 인덱스 레코드 파싱(96바이트 CL_V2) | ✅ 구현+실제 데이터 검증 |
| `RevlogIndex.getIndexRecord()` v2 인식(올바른 companion 파일에서 읽기) | ✅ 수정됨 — 이전 반려의 핵심 버그 |
| `storage.Revlog` v2 콘텐츠 읽기 연동(datFile 자동 해석, inline=false 강제) | ✅ 구현 |
| changelog-v2 리비전 append(쓰기) | ✅ 구현+**실제 hg CLI로 상호운용 검증**(아래 참고) |
| `HgRepository.loadRequires()` v2 requirement 인식 | ✅ 구현(`.hg/store/requires`까지 읽도록 수정) |
| 일반 revlog-v2(`exp-revlogv2.2`, 매니페스트/파일로그) 읽기 | ✅ 기존 코드가 이미 올바르게 지원(재확인 완료) |
| 일반 revlog-v2 리비전 append(쓰기) | ✅ 구현+**실제 hg CLI로 상호운용 검증**(아래 참고), 항상 풀텍스트로 씀(델타 없음) |
| 브랜드 뉴 revlog를 저장소 요구사항에 맞춰 처음부터 v2로 초기화 | ✅ 구현(`RevlogIndex.initializeNewGeneralV2Docket`) |
| `fileindex-v1`(경로 인덱스) 읽기/쓰기 | ✅ 구현(`storage.FileIndex`)+실제 hg CLI 상호운용 검증, "항상 전체 재빌드" 전략 |
| `CommitCommand` ↔ `fileindex-v1` 연동(신규 파일 추적 등록) | ✅ 구현, 스냅샷/롤백까지 지원 |
| persistent-nodemap(`.n` 트라이 파일) 가속 조회 | ❌ **미구현 — 의도적**, 항상 순차 스캔 fallback(스펙상 유효) |
| Sidedata 실제 활용(LFS/copy-tracing 등) | ❌ 미구현 — 파싱 골격만 있고 소비하는 기능 없음 |

## 실제 hg CLI 상호운용 검증 (2026-09-01)
1. 실제 `hg`로 만든 changelog-v2 저장소(2 커밋)를 hg4j `Revlog.appendRevision()`으로
   3번째 리비전 추가.
2. 그 저장소 디렉터리를 그대로 실제 `hg log`로 열람 → **hg4j가 쓴 리비전의 node hash,
   설명, 저자가 정확히 표시됨.**
3. `hg verify` 실행 → **"checking changesets/manifests/files" 전부 통과, integrity
   error 0건.** (파일로그는 여전히 v1이라 "uses revlog format 1; expected 54093"
   경고 1건은 발생 — changelog만 v2로 승격했으므로 예상된 정상 동작.)

## 실제 hg CLI 상호운용 검증 (일반 revlog v2 + fileindex-v1, 2026-09-02)
1. Docker(`hg-rust-7.2.4`)로 만든 3커밋 실제 저장소(`exp-revlogv2.2` +
   `fileindex-v1` + `persistent-nodemap`)의 쓰기 가능한 복사본을 준비.
2. hg4j `CommitCommand`로 **이미 추적 중인 파일 수정** 커밋 실행 →
   changelog/manifest/filelog(a.txt) 전부 general v2 revlog에 새 리비전이 append됨.
3. 같은 저장소를 `hg-rust-7.2.4` 컨테이너의 `hg verify`/`hg log --debug`/`hg cat`으로
   열람 → **전부 경고 없이 통과, hg4j가 쓴 리비전 내용/노드 해시가 정확히 표시됨.**
4. **브랜드 뉴 파일**(한 번도 존재한 적 없는 filelog) 커밋도 별도로 검증 — 처음엔
   `hg verify`가 `"... uses revlog format 1; expected 57005"`(브랜드 뉴 revlog가
   v1로 초기화됨) + `"... not in file index!"`(fileindex 미연동) 두 경고를 냄.
   `RevlogIndex.initializeNewGeneralV2Docket()`(브랜드 뉴 revlog를 저장소 요구사항에
   맞춰 처음부터 v2로 초기화)과 `CommitCommand` ↔ `FileIndex` 연동을 각각 추가한 뒤
   재검증하니 **두 경고 모두 사라짐**, `hg files`/`hg cat`으로 신규 파일 내용까지
   정상 확인됨.

## Rust 확장 포함 hg를 Docker로 확보 (환경 제약 해소, 2026-09-02)
이 개발 환경의 `hg` 바이너리(Mercurial 7.2, 순수 Python 빌드)로 `experimental.revlogv2`
또는 `format.use-persistent-nodemap`을 켜려고 하면:
```
abort: accessing `fileindex` repository without associated fast implementation.
abort: accessing `persistent-nodemap` repository without associated fast implementation.
```
즉 이 두 기능은 Rust 확장(`rustext`/`hg-cpython`)이 컴파일된 hg 바이너리가 있어야만
실제로 생성·검증 가능하다. **해결책**: `docker/hg-rust-7.2.4/Dockerfile`로 Rust
확장이 활성화된 실제 Mercurial 7.2.4를 직접 빌드했다(이미지 태그 `hg-rust-7.2.4`,
`docker run --rm hg-rust-7.2.4 hg debuginstall`로 "checking Rust extensions
(installed)" 확인됨). 핵심 절차:
1. `python3.11-slim-bookworm` + rustup stable + `pip download --no-binary :all:`로
   Mercurial 7.2.4 sdist 확보.
2. `python3 setup.py install`은 최신 setuptools의 `copy_file`/`bdist_egg` 경로와
   호환이 깨져 실패(`TypeError: cannot unpack non-iterable NoneType object`) —
   대신 `python3 setup.py --rust build_ext --inplace`로 소스 트리 안에서 직접
   빌드(설치 단계 자체를 생략).
3. `RustStandaloneExtension`(pyo3 확장)은 `build_ext --inplace`의 자동 복사 대상에서
   의도적으로 제외돼 있어(`hgbuildext.build_extensions()`), `build/lib.*/mercurial/
   pyo3_rustext*.so`를 소스 트리로 수동 복사.
4. `HGMODULEPOLICY`가 기본값 `allow`(Rust 미사용)로 남는 문제 — 정상적으로는
   `setup.py install`이 생성하는 `mercurial/__modulepolicy__.py`가 이 설정을
   담당하는데, install을 건너뛰었으므로 환경변수 `HGMODULEPOLICY=rust+c`를 직접
   지정해서 해결.

이 방법론(실제 저장소 생성 → hexdump/`struct` 대조 → TDD)을 changelog-v2와 동일하게
적용해 일반 revlog v2 + `fileindex-v1`을 구현·검증했다.

## 코드 영향 범위 (실제 반영됨)
- `storage.RevlogIndex` — v2 docket/index 파싱, `getIndexRecord()` v2 분기,
  `updateV2DocketSizes()` 신설(2026-09-01), **브랜드 뉴 revlog를 저장소 요구사항에
  맞춰 처음부터 general v2 docket으로 초기화하는 `initializeNewGeneralV2Docket()` +
  그 경로를 타는 새 생성자 신설(2026-09-02)**.
- `storage.Revlog` — 생성자에서 v2일 때 datFile 자동 재해석, `appendRevisionV2()`가
  `index.isChangelogV2()`로 changelog-v2/일반 v2를 분기하도록 확장(2026-09-02, 이전엔
  changelog-v2만 처리하고 나머지는 `UnsupportedOperationException`).
- `storage.FileIndex` — **신설(2026-09-02)**. `fileindex-v1` docket/list/meta/tree
  읽기, "항상 전체 재빌드" 쓰기, 트랜잭션 롤백용 `snapshot()`/`restore()`.
- `api.CommitCommand` — `fileindex-v1` 저장소일 때 신규/변경 파일 경로를 `FileIndex`에
  등록하도록 연동(fncache와 같은 지점, 같은 패턴으로 스냅샷/롤백까지 포함).
- `storage.DefaultFileStoreEngine` — 브랜드 뉴 revlog에 v2 초기화가 필요한지 여부를
  계산해 `Revlog` 생성자로 전달.
- `lib.HgRepository` — `.hg/store/requires` 추가 로드, `isChangelogV2()`/`isRevlogV2()`/
  `isPersistentNodemap()`(2026-09-01) + `isFileIndexV1()`(2026-09-02) 신설.
- 테스트: `RevlogV2ParserTest`(changelog-v2, 실제 hg 픽스처 6종),
  `RevlogV2GeneralParserTest`(일반 v2, 실제 hg 픽스처 기준),
  `FileIndexTest`(fileindex-v1 읽기/쓰기/롤백),
  `HgRepositoryTest#testLoadRequiresRecognizes*`(실제 requires 내용 검증).
- 픽스처: `src/test/resources/fixtures/revlogv2-changelog/`(changelog-v2, 실제 hg가
  생성한 docket/idx/dat/sda 4개 파일), `src/test/resources/fixtures/revlogv2-general/`
  (일반 v2 + fileindex-v1, Rust 확장 포함 실제 hg 7.2.4가 생성). 둘 다 README.md에
  출처와 검증값 기록.
- `docker/hg-rust-7.2.4/Dockerfile` — 신설. Rust 확장 포함 실제 Mercurial 7.2.4
  빌드 레시피(재현 가능, 각 워크어라운드에 상세 주석 포함).

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거
- [[revlog]] — v1 구현 현황 및 과거 버그 이력
- [[lib]] — `HgRepository` 위치
