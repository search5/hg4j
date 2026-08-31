---
updated: 2026-08-31
status: current
---

# 개념: Bundle2 / Changegroup

push/pull/fetch 시 리비전 델타들을 네트워크로 주고받기 위한 Mercurial의 컨테이너 포맷.

## 계층 구조
1. **Bundle2**: 여러 "part"(스트림 조각)를 담는 최상위 컨테이너. capability 협상, phase
   정보, obsolescence marker 등 다양한 종류의 part가 들어갈 수 있음.
   → `Bundle2Parser.extractChangegroup()` / `extractChangegroupDetailed()`로 changegroup
   part만 추출.
2. **Changegroup**: 실제 리비전 델타 스트림 (changelog → manifest → filelog 순서로 델타 묶음).
   → `ChangegroupParser`가 파싱.

## 테스트 픽스처
`src/test/resources/fixtures/`에 실제 bundle 파일이 있음:
- `simple-3commits.bundle` — 기본 케이스
- `branch-and-merge.bundle` — 브랜치/머지 포함 케이스
- `large-path-dh.bundle` — 긴 경로명(dh/ 하이브리드 인코딩) 케이스, [[revlog]]의 fncache
  인코딩 이슈와 연관.

## 관련 페이지
- [[revlog]] — changegroup 안에 담기는 델타의 원본 저장 포맷
- [[transport-treewalk-revwalk|transport 모듈]] — push/pull 시 이 포맷을 주고받는 전송 계층
