---
updated: 2026-09-01
status: current
---

# 계획: Revlog v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원, v1과 병행"으로 확정된 항목.
> **2026-09-01: changelog-v2는 구현·실제 hg CLI 상호운용 검증까지 완료.** 일반
> revlog-v2(매니페스트/파일로그, `exp-revlogv2.2`)와 persistent-nodemap은 이 개발 환경에
> Rust 확장이 없어 실제 데이터로 검증할 방법이 없었으므로 **의도적으로 미착수** — 아래
> "현재 구현 상태"와 "환경 제약" 섹션 참고.

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
  붙는 구조. **이 레이아웃은 소스 코드에서 직접 확인했으나, 이 환경에서 실제 fixture로
  검증하지는 못했다** (아래 "환경 제약" 참고) — hg4j에 파싱 코드는 넣지 않았다.

- 압축 모드(`compression_mode` 1바이트): 실측값 `9`(`0b1001`)는 하위 2비트가
  `COMP_MODE_DEFAULT(1)`을 가리킨다(= 저장소 기본 압축 엔진, 여기서는 zstd 사용).
  `COMP_MODE_PLAIN(0)`으로 잘못 쓰면 **실제 hg가 zstd 바이트를 평문으로 취급해
  `hg verify`에서 integrity check failed가 남** — 실제로 재현·확정함.

### D. Requirement 문자열 (mercurial/requirements.py 실측, `.hg/requires`가 아니라
**`.hg/store/requires`**에 기록됨 — share-safe 저장소 기준)
- `exp-changelog-v2` — changelog가 v2(docket 기반)
- `exp-revlogv2.2` — 매니페스트/파일로그가 일반 revlog v2 (버전 접미사 `.2`까지 정확히 일치)
- `persistent-nodemap` — `.n` 트라이 파일 사용

## 현재 구현 상태 (2026-09-01)
| 항목 | 상태 |
|---|---|
| changelog-v2 docket 파싱(59바이트 헤더, 3-UUID 파일 해석) | ✅ 구현+실제 데이터 검증 |
| changelog-v2 인덱스 레코드 파싱(96바이트 CL_V2) | ✅ 구현+실제 데이터 검증 |
| `RevlogIndex.getIndexRecord()` v2 인식(올바른 companion 파일에서 읽기) | ✅ 수정됨 — 이전 반려의 핵심 버그 |
| `storage.Revlog` v2 콘텐츠 읽기 연동(datFile 자동 해석, inline=false 강제) | ✅ 구현 |
| changelog-v2 리비전 append(쓰기) | ✅ 구현+**실제 hg CLI로 상호운용 검증**(아래 참고) |
| `HgRepository.loadRequires()` v2 requirement 인식 | ✅ 구현(`.hg/store/requires`까지 읽도록 수정) |
| 일반 revlog-v2(`exp-revlogv2.2`, 매니페스트/파일로그) 읽기/쓰기 | ❌ **미구현 — 의도적** (아래 환경 제약) |
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

## 환경 제약 (일반 revlog-v2 / persistent-nodemap을 미착수로 남긴 이유)
이 개발 환경의 `hg` 바이너리(Mercurial 7.2, 순수 Python 빌드)로 `experimental.revlogv2`
또는 `format.use-persistent-nodemap`을 켜려고 하면:
```
abort: accessing `fileindex` repository without associated fast implementation.
abort: accessing `persistent-nodemap` repository without associated fast implementation.
```
즉 **이 두 기능은 Rust 확장(`rustext`/`hg-cpython`)이 컴파일된 hg 바이너리가 있어야만
실제로 생성·검증 가능**하다. 이 환경에는 순수 Python hg만 설치돼 있어 실제 데이터를
만들 수 없었다. changelog-v2는 예외적으로 Rust 없이도 순수 Python 경로로 동작해서
검증 가능했다.

**결론**: 위 표의 `INDEX_ENTRY_V2`(일반 v2) 레이아웃은 Mercurial 소스 코드에서 직접
확인한 것이라 신뢰도는 높지만, hg4j 코드베이스에는 아직 반영하지 않았다 — 검증 안 된
채로 "구현했다"고 하면 changelog-v2와 똑같은 반려 사유(추측 기반 구현)가 재발하기
때문이다. Rust 확장이 포함된 `hg` 바이너리(또는 원격 CI 환경)를 구할 수 있게 되면
동일한 방법론(실제 저장소 생성 → hexdump 대조 → TDD)으로 이어서 구현할 것.

## 코드 영향 범위 (실제 반영됨)
- `storage.RevlogIndex` — v2 docket/index 파싱, `getIndexRecord()` v2 분기,
  `updateV2DocketSizes()` 신설.
- `storage.Revlog` — 생성자에서 v2일 때 datFile 자동 재해석, `appendRevisionV2()` 신설.
- `lib.HgRepository` — `.hg/store/requires` 추가 로드, `isChangelogV2()`/`isRevlogV2()`/
  `isPersistentNodemap()` 신설.
- 테스트: `RevlogV2ParserTest`(실제 hg 픽스처 6종 검증),
  `HgRepositoryTest#testLoadRequiresRecognizes*`(실제 requires 내용 검증).
- 픽스처: `src/test/resources/fixtures/revlogv2-changelog/`(실제 hg가 생성한 docket/idx/dat/sda
  4개 파일, README.md에 출처와 검증값 기록).

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거
- [[revlog]] — v1 구현 현황 및 과거 버그 이력
- [[lib]] — `HgRepository` 위치
