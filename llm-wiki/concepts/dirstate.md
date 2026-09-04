---
updated: 2026-09-04
status: current
---

# 개념: Dirstate

워킹 디렉터리의 각 파일이 "커밋된 상태 대비 어떤지"를 추적하는 Mercurial의 핵심 캐시 구조.
Git의 index(staging area)와 유사하지만, Mercurial은 스테이징 개념이 없어서 dirstate는
"현재 상태 + 다음 커밋의 부모(들)"만 표현한다.

## v1 vs v2
- **v1**: 텍스트/단순 바이너리 포맷, 파일 수가 많아지면 파싱 비용이 큼.
- **v2**: 44바이트 고정 크기 바이너리 노드(`DirstateV2Node`) 구조. Java NIO
  `MappedByteBuffer`로 메모리 매핑해 대규모 워킹카피에서도 고성능 유지.
  → `DirstateV2Parser` / `DirstateV2Serializer`가 파싱/직렬화 담당.
- `HgRepository.defaultDirstateV2` 플래그로 신규 저장소의 기본 포맷 선택.
- `Dirstate.isV2()`로 런타임에 어떤 포맷을 읽었는지 판별.

## 핵심 필드
- `parent1` / `parent2`: 다음 커밋의 부모 리비전(머지 중이면 둘 다 존재).
- `entries`: 경로 → 상태(Entry) 매핑. 각 Entry는 mtime/size/flag 등을 담아 "재스캔 없이
  변경 여부 판단"에 사용 (mtime 기반 캐시 무효화).
- `copyMap`: rename/copy 추적 (`addCopy`).

## 2026-09-01: v2 44바이트 노드 바이트 레이아웃 검증 — 실제 hg와 전혀 호환 안 되던 3개 버그 발견·수정
실제 hg CLI로 만든 dirstate-v2 저장소와 대조 검증하기 전까지, hg4j의 v2 구현은 **자기 자신과의
라운드트립만 우연히 통과할 뿐** 실제 hg와는 완전히 단절돼 있었다:
1. `DirstateV2Node`의 44바이트 NODE 필드 오프셋이 전부 지어낸 값이었다. 실제 스펙
   (`mercurial/dirstateutils/v2.py`의 `NODE = struct.Struct('>LHHLHLLLLHlll')`)은
   `path_start@0, path_len@4, basename_start@6, copy_source_start@8, copy_source_len@12,
   children_start@14, children_count@18, descendants_with_entry@22, tracked_descendants@26,
   flags@30, size@32, mtime_s@36, mtime_ns@40`.
2. flags 비트 값도 틀렸다 — 실제(`mercurial/pure/parsers.py`)는 `HAS_MODE_AND_SIZE=1<<10`,
   `HAS_MTIME=1<<11`, `MODE_EXEC_PERM=1<<3`, `MODE_IS_SYMLINK=1<<4`인데 hg4j는 각각
   `1<<3`/`1<<4`/`1<<5`/`1<<6`을 쓰고 있었다.
3. 데이터 파일명 패턴이 `dirstate.d.<uid>`였는데 실제는 **`dirstate.<uid>`**(".d"가 끼어들
   자리가 없음, `docket.py`의 `data_filename_pattern = b'dirstate.%s'`) — 이것만으로도
   hg4j가 쓴 파일을 실제 hg가 못 찾고, 실제 hg가 쓴 파일을 hg4j가 못 찾는 완전 단절이었다.

실제 hg가 만든 dirstate-v2 저장소의 바이트를 캡처해 그대로 박아넣은 회귀 테스트
(`DirstateV2RealFixtureTest`)로 검증. 상세는 [[mercurial-spec-compliance-requirement]]의
백로그 5번 참고.

## 2026-09: racy-write 검증이 잘못된 리비전과 비교하던 버그 (발견·수정됨)
`StatusCommand`/`ShelveCommand`가 "workdir 파일이 dirstate 기록 이후 같은 초(mtime granularity)
안에 다시 쓰였을 수 있는가"를 판단하는 racy-write 검증에서, 비교 대상 리비전 자체가 잘못돼
있던 버그를 TDD로 발견·수정했다(자연스럽게 dirstate state `'n'` 케이스로 수정됨). 상세는
[[mercurial-spec-compliance-requirement]] 참고.

## 최근 수정 이력 (git log 기반)
- **BUG-05**: `Dirstate.Entry`의 mtime 범위 검증 부재 → 타임스탬프 truncation 문제
  (`babdb2a` 커밋에서 mtime range validation 추가).
- **dirstate 캐시 복원 문제**(`56b1988`): `HgRepository.cachedDirstate`가 특정 상황에서
  갱신되지 않고 stale 상태로 남는 버그 수정.
- rebuild 관련: `HgRepository.rebuildDirstateFromManifest()` — dirstate가 손상되었을 때
  매니페스트로부터 재구성하는 복구 경로. "Dirstate rebuild loss" 버그가 `e68c34f`에서 수정됨.

## 관련 페이지
- [[dirstate]] 모듈(`modules/dirstate.md`) — `Dirstate`, `DirstateV2*` 클래스 위치(Track A로
  `core`에서 이관됨)
- [[revlog]] — 커밋되면 dirstate 정보가 매니페스트/체인지로그 revlog로 흡수됨
