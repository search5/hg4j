---
updated: 2026-08-31
status: current
---

# 개념: Revlog

Mercurial의 파일별 히스토리 저장 포맷. Git의 pack-file과 달리 **파일마다 독립된 append-only
로그**(`.i` 인덱스 + `.d` 데이터, 또는 작은 파일은 inline으로 `.i`에 데이터 포함)를 사용한다.
→ [[core]] 모듈의 `Revlog`/`RevlogIndex`/`DeltaEngine`/`DeltaCodec`이 구현체.

## 왜 Git과 다른가
- Git: 모든 객체를 pack-file로 묶고 주기적으로 GC(압축) 필요.
- Mercurial: revlog 자체가 자기완결적 로그라서 GC가 불필요 — README의
  "No Garbage Collection" 설계 원칙 참고 (`GcCommand`가 있긴 하지만 Git의 GC와 성격이 다름).

## 델타 생성
- Myers-diff / LCS 알고리즘으로 이전 리비전과의 델타를 계산 (`DeltaEngine.createDelta`).
- generaldelta 지원: 델타 베이스를 반드시 직전 리비전이 아니라 임의의 이전 리비전으로 선택
  가능 → 저장 공간 최적화.
- 압축은 zstd(`useZstdCompression`) 또는 기본 압축 중 선택.

## 알려졌던 이슈 (git log 기반, 이미 수정됨)
- **BUG-04**: Myers Diff 백트래킹 시 대각선(diagonal) 계산이 어긋나는 버그. 여러 차례 커밋에서
  반복 등장(`7f0c42d`, `7f08749`, `babdb2a`) — 다중 훈크(hunk) 델타에서 엣지 케이스가 많았던
  영역이라는 뜻이므로, 델타 관련 코드를 건드릴 때는 `DeltaEngineTest`/`DeltaCodecTest`를
  반드시 함께 확인할 것.
- **BUG-01/02/10**: 인라인 revlog 오프셋 계산 및 zstd 프레임 매직 바이트 오감지 버그.
- **BUG-10 계열**: Zstd 압축 바이트(`0x00` prefix) 표준화 문제 — raw 데이터와 zstd 프레임을
  구분하는 매직 바이트 판별 로직이 원인이었음 (`e865b1e` 커밋).
- 최근(`56b1988`) fncache 레이어 불일치 수정 — 파일명 인코딩(`encodeFname`, dh/ 하이브리드
  인코딩)과 revlog 물리 파일 경로 매핑 관련.

## 관련 페이지
- [[dirstate]] — revlog에 커밋되기 전 워킹카피 상태
- [[bundle2-changegroup]] — 네트워크로 revlog 델타를 주고받는 포맷
- [[core]] 모듈 페이지
