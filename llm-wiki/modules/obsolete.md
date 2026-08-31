---
updated: 2026-08-31
status: current
---

# 모듈: obsolete (`com.github.search5.hg4j.obsolete`)

Obsolescence marker(evolve 메커니즘) 지원 패키지. [[core-package-split-plan]] Phase 6에서
분리됨.

## 클래스
- **`HgObsMarker`**: obsolescence marker 하나(predecessor 리비전 → 0개 이상의 successor
  리비전) 값 객체.
- **`HgObsolescenceParser`**: `.hg/store/obsstore` 바이너리 포맷 파서.

## 알려진 미비점 (2026-08-31 전수 감사, Track B-5 승격)
`api.AmendCommand`는 obsstore에 실제로 마커를 쓰지만, `RebaseCommand`/`GraftCommand`/
`HisteditCommand`/`StripCommand`는 전혀 쓰지 않음(문자열 검색 0건) — history-rewriting
명령 전반으로 확장 필요. 상세 계획: [[obsolescence-marker-completeness-plan]].

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/obsolete/`
