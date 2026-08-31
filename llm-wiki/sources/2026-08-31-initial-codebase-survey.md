---
updated: 2026-08-31
status: current
---

# 원본 조사: 최초 코드베이스 서베이 (2026-08-31)

llm-wiki 최초 생성 시점의 조사 기록. 이후 코드가 바뀌면 이 페이지는 갱신하지 않고
(스냅샷이므로) `status: superseded-by:[[...]]`로 표시하고 새 sources 페이지를 추가한다.

## 조사 방법
- Serena MCP(`get_symbols_overview`, `list_dir`)로 심볼/디렉터리 구조 파악.
- `git log --oneline -30`으로 최근 개발 흐름 파악.
- `README.md`, `build.gradle`, `settings.gradle` 직접 읽음.

## 당시 HEAD
```
64b1521 fix: resolve Javadoc warnings and Gradle Plugin Portal publishing namespace restrictions
56b1988 fix: resolve remaining architecture gaps and performance bottlenecks including
        fncache layer mismatch, dirstate cache restoration, O(N) parent lookup
        short-circuit, and cross-thread lock cleanup
27299fc fix: resolve Gradle 9 implicit task dependency collision between sign and publish
9879ab0 config: integrate signing (GPG) configuration into build.gradle for Maven Central
f96db1f refactor: revert package namespace back to com.github.search5.hg4j &
        configure java-gradle-plugin integration
```

## 관찰된 개발 패턴
- 커밋 메시지가 한국어/영어 혼용. 최근으로 올수록 영어 conventional-commit 스타일
  (`fix:`, `refactor:`, `config:`)로 수렴하는 추세 — `20a042b`("한국어 주석을 영어로 번역")
  커밋 이후 뚜렷해짐.
- `BUG-01`~`BUG-11`, `C-1` 같은 자체 버그 ID 체계로 결함을 추적한 흔적이 커밋 메시지에 남아
  있음 (별도 이슈 트래커 없이 커밋 메시지가 사실상 버그 트래커 역할).
- 최근 커밋은 "N대 테스트케이스", "ALL GREEN 달성" 같은 표현이 반복 — 대규모 회귀 테스트
  스위트를 기준으로 완결 여부를 판단하는 개발 문화로 보임.

## 커버리지 게이트 (build.gradle 기준, 조사 시점)
- BUNDLE 전체: 최소 70%
- 지정 핵심 클래스 21개: 최소 90% ([[api]], [[core]] 모듈 페이지의 목록 참고)

## 다음에 조사가 필요할 만한 지점 (미착수)
- Dirstate v2 바이너리 44바이트 노드 포맷의 정확한 필드 레이아웃 (현재는 존재만 확인, 상세
  스펙 미조사).
- Revlog 델타 알고리즘의 generaldelta 베이스 선택 휴리스틱 상세.
- transport 계층의 실제 wire protocol capability 협상 시퀀스.
