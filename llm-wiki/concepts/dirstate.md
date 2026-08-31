---
updated: 2026-08-31
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

## 최근 수정 이력 (git log 기반)
- **BUG-05**: `Dirstate.Entry`의 mtime 범위 검증 부재 → 타임스탬프 truncation 문제
  (`babdb2a` 커밋에서 mtime range validation 추가).
- **dirstate 캐시 복원 문제**(`56b1988`): `HgRepository.cachedDirstate`가 특정 상황에서
  갱신되지 않고 stale 상태로 남는 버그 수정.
- rebuild 관련: `HgRepository.rebuildDirstateFromManifest()` — dirstate가 손상되었을 때
  매니페스트로부터 재구성하는 복구 경로. "Dirstate rebuild loss" 버그가 `e68c34f`에서 수정됨.

## 관련 페이지
- [[core]] 모듈 — `Dirstate`, `DirstateV2*` 클래스 위치
- [[revlog]] — 커밋되면 dirstate 정보가 매니페스트/체인지로그 revlog로 흡수됨
