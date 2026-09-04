---
updated: 2026-09-04
status: current
---

# 개념: Revset

Mercurial 고유의 함수형 리비전 질의 언어(`hg log -r "..."`). hg4j는 이를
`HgRevsetEngine`으로 자체 재구현했다 (외부 파서 라이브러리 없이 직접 파싱/평가).

## 지원 함수 (README 기준 19개, 코드 기준 evaluate* 메서드들)
`draft()`, `heads()`, `merge()`, `author()`, `user()`, `keyword()`, `branch()`, `file()`,
`date()`, `parents()`, `ancestors()`, `descendants()`, `tag()`, `bookmark()`, `public()`,
`secret()`, `sort()`, `children()` 등.

## 구조
- `query(String expr)`: 진입점.
- `evaluateExpression`: 표현식 파싱/분기.
- `findLogicalKeyword`: `and`/`or`/`not` 등 논리 연산자 탐지.
- `resolveRevisionToInt`: 리비전 식별자(hex node id, 숫자, 심볼) → 정수 리비전 번호 변환.

## 2026-09: `sort(x, "author"/"user")`가 사실상 no-op이던 버그 (발견·수정됨)
`evaluateSort`가 `"author"`/`"user"` 정렬 키를 인식하지 못해 실제로는 아무 정렬도
하지 않고 입력 순서를 그대로 반환하고 있었다. TDD로 수정. 상세는
[[mercurial-spec-compliance-requirement]] 참고.

## 관련 페이지
- [[revset]] 모듈(`modules/revset.md`) — `HgRevsetEngine` 위치(Track A Phase 7에서
  `core`로부터 이관됨)
- `api/RevsetCommand.java` — porcelain 진입점
