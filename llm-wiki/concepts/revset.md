---
updated: 2026-08-31
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

## 관련 페이지
- [[core]] 모듈 — `HgRevsetEngine` 위치
- `api/RevsetCommand.java` — porcelain 진입점
