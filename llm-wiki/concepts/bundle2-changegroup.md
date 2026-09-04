---
updated: 2026-09-04
status: current
---

# 개념: Bundle2 / Changegroup

push/pull/fetch 시 리비전 델타들을 네트워크로 주고받기 위한 Mercurial의 컨테이너 포맷.

## 계층 구조
1. **Bundle2**: 여러 "part"(스트림 조각)를 담는 최상위 컨테이너. capability 협상, phase
   정보, obsolescence marker 등 다양한 종류의 part가 들어갈 수 있음.
   → `Bundle2Parser.extractChangegroup()` / `extractChangegroupDetailed()`로 changegroup
   part만 추출. `bundle2=` capability가 협상되면 반드시 bundle2 프레이밍(part 헤더 포함)을
   써야 한다 — raw changegroup 바이트를 그대로 보내면 실제 hg가 거부한다.
2. **Changegroup**: 실제 리비전 델타 스트림 (changelog → manifest → filelog 순서로 델타 묶음).
   → `ChangegroupParser`가 **cg1~cg5 전부** 파싱/패킹 지원(`io.github.search5.hg4j.bundle`
   패키지). 버전별 델타 헤더 레이아웃이 다르다:
   - **cg1**: `node,p1,p2,cs`(4필드, deltabase 없음 — 항상 `forcedeltaparentprev=True`라
     이전 엔트리에 대해 델타를 뜨는 것으로 암묵 결정됨. 단, 그룹의 **첫 엔트리는
     예외**로, 자신의 진짜 p1에 대해 델타를 뜬다 — [[revlog]]의 "cg1 델타 베이스 규칙
     오류" 참고).
   - **cg2/cg3**: `node,p1,p2,deltabase,cs`(explicit deltabase 필드 추가). cg3부터
     treemanifest 봉투(루트 매니페스트 그룹 뒤에 서브디렉터리 그룹들 + `closechunk()`
     종료 마커, cg1/cg2는 이 종료 마커가 없음)와 flags 필드(censor 등)가 생김.
   - **cg4**: 130바이트 델타 헤더(node/p1/p2/deltabase/cs/flags/protocol_flags/
     storage_snapshot_level 등). protocol_flags의 `CG_FLAG_SIDEDATA`/`CG_FLAG_FULL_TEXT`
     비트로 델타 vs 풀텍스트 여부 판별.
   - **cg5**: 103바이트 헤더(cg2/cg3와 필드 순서는 같지만 protocol_flags가 sidedata
     전용 — `CG_FLAG_SIDEDATA`만 씀). sidedata를 `.sda`에 영속화(`appendRevisionV2`).

   전부 실제 Mercurial 7.2.2 소스(`mercurial/changegroup.py`)와 로컬 `hg` CLI로 만든
   진짜 바이트를 hexdump로 대조해 확정한 레이아웃이다.

## 2026-09: censor 플래그가 changegroup 전송 경로에서 조용히 사라지던 버그 (발견·수정됨)
Censor(민감정보 삭제) 기능 구현 직후 실제로 검증해보니 changegroup 경유 전송 경로
두 곳에서 진짜 버그가 나왔다:
1. **패킹 시 크래시**: push용 번들 조립이 censored 리비전에 대해 예외를 던지는
   `getRevisionContent()`로 파일로그를 읽고 있어, censored 리비전이 하나라도 포함된
   저장소를 pull/clone/push하면 무조건 크래시했다 — 원본 저장 바이트를 그대로 읽는
   `getRawRevisionContent()`로 수정.
2. **수신 측에서 censored 플래그 소실**: hg4j의 changegroup은 cg1/cg2 형태로 보내는데
   (flags 필드가 없음), 수신측 `Revlog.appendChangeGroupEntry()`가 flags를 항상 0으로
   하드코딩하고 있어 censored 파일을 pull하면 **원래 지워졌어야 할 내용이 그대로
   복원**되는 심각한 버그였다. 실제 hg도 같은 구조적 문제가 있어 `_peek_iscensored()`로
   전송된 콘텐츠 안에서 censor tombstone 마커를 스니핑해 플래그를 복원하는 방식을 쓴다 —
   이 메커니즘을 `Revlog.isCensoredText()`로 재현해 적용.

## 테스트 픽스처
`src/test/resources/fixtures/`에 실제 bundle 파일이 있음:
- `simple-3commits.bundle` — 기본 케이스
- `branch-and-merge.bundle` — 브랜치/머지 포함 케이스
- `large-path-dh.bundle` — 긴 경로명(dh/ 하이브리드 인코딩) 케이스, [[revlog]]의 fncache
  인코딩 이슈와 연관.

## 관련 페이지
- [[revlog]] — changegroup 안에 담기는 델타의 원본 저장 포맷, cg1 델타 베이스 버그 상세
- [[transport]] 모듈 — push/pull 시 이 포맷을 주고받는 전송 계층
- [[mercurial-spec-compliance-requirement]] — censor/cg3/cg4/cg5 백로그 항목 상세
