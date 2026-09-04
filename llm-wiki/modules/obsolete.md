---
updated: 2026-09-04
status: current
---

# 모듈: obsolete (`io.github.search5.hg4j.obsolete`)

Obsolescence marker(evolve 메커니즘) 지원 패키지. [[core-package-split-plan]] Phase 6에서
분리됨.

## 클래스
- **`HgObsMarker`**: obsolescence marker 하나(predecessor 리비전 → 0개 이상의 successor
  리비전) 값 객체.
- **`HgObsolescenceParser`**: `.hg/store/obsstore` 바이너리 포맷 파서.

## 해결됨: 과거 "일부 명령만 obsstore에 씀" 미비점
2026-08-31 전수 감사 당시 `api.AmendCommand`만 obsstore에 마커를 쓰고
`RebaseCommand`/`GraftCommand`/`HisteditCommand`/`StripCommand`는 전혀 쓰지 않는다는
격차가 있었으나, 이후(백로그 22~28, 실제 hg CLI 상호운용 검증 라운드) 위 네 명령 모두
obsstore/`HgObsMarker`를 참조하도록 구현이 확장됐다 — 문자열 검색으로 재확인 완료
(2026-09-04). `PushCommand`도 obsmarker 교환(원격에 obsolescence 정보 전달)에
관여한다. 상세 이력은 [[obsolescence-marker-completeness-plan]] 참고(단, 그 문서
자체는 2026-09-01 이후 갱신되지 않아 이 변경을 아직 반영하지 못했을 수 있음 — 이
모듈 문서가 더 최신 상태).

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/obsolete/`
