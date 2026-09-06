---
updated: 2026-09-06
status: 결정 완료 — 구현은 아직 안 함(질문에 대한 답변만 문서화, 사용자가 "문서화만 해줘"라고 명시)
---

# 결정: Interop 테스트를 픽스처로 캐싱할 것인가

> 사용자 질문(2026-09-06): "interop 테스트는 참 좋지만 이걸 픽스처로 저장해두면 나중에
> 빠르게 테스트 가능하지 않을까?"

## 결론

**둘로 나눠서 접근할 것.** 포맷/바이트 레이아웃 검증은 픽스처로 캐싱해도 안전하고 이미
일부 그렇게 하고 있다(예: [[treemanifest]] 관련 `src/test/resources/fixtures/treemanifest/`
— real hg가 실제로 만든 저장소를 그대로 저장해두고 hg4j의 읽기 경로를 검증). 반면
"real hg CLI가 실제로 이걸 받아들이는가 / 이렇게 응답하는가"류의 **프로토콜·행동
검증은 픽스처로 굳히면 안 된다** — 지금처럼 매번 실제 hg CLI/Docker를 라이브로 띄워서
검증해야 한다.

## 왜 (트레이드오프)

**픽스처 캐싱의 장점**: 서브프로세스 spawn·Docker 컨테이너 기동이 없어져서 테스트가
훨씬 빠르고, hg/Docker가 설치 안 된 환경(CI 등)에서도 돌아가고, 결정론적(deterministic)
이 된다.

**픽스처 캐싱의 위험**: 픽스처는 "그 시점에 기록해둔 스냅샷"이지 "지금 real hg의 실제
동작"이 아니다. 이번 세션 전체에 걸쳐 발견한 버그 다수가 정확히 "문서/가정과 real hg의
실제 동작이 달랐던" 경우였다(예: [[backlog/wire-protocol-negotiation]]의 bundlecaps
콤마 vs 스페이스 join, [[backlog/39-exhaustive-interop-matrix]]의 다수 항목,
[[backlog/revlog-storage-formats]]의 `_enforceinlinesize` 실측 등) — 이런 버그들은
"real hg가 실제로 이렇게 동작하는지"를 라이브로 재확인했기 때문에 잡을 수 있었다.
만약 애초에 잘못 기록된 픽스처를 만들었거나, real hg 버전이 바뀌어 동작이 달라지면,
픽스처 기반 테스트는 **조용히 낡은 기준으로 계속 "통과"만 하게 된다** — 정확히
[[known-bugs-registry]]가 반복 방지하려는 종류의 실패 모드를 프로토콜 검증 영역에서는
오히려 만들어내는 셈이다.

## 권장 절충안

- **픽스처로 캐싱해도 되는 것**: 정적 파일 포맷 파싱/바이트 레이아웃(revlog index 구조,
  dirstate-v2 노드, treemanifest 저장소 구조 등) — 포맷 자체가 안정적이고 자주 안
  바뀌므로, "이 바이트가 이렇게 파싱돼야 한다"는 것은 한 번 real hg로 만들어두면 계속
  유효하다.
- **라이브로 유지해야 하는 것**: wire protocol 협상(capabilities, v1↔v2 업그레이드),
  서버 응답 포맷, `hg verify`/`hg debugrebuildfncache` 같은 real hg 자체 도구의 판정,
  push/pull/clone의 종단간 행동 — "지금 real hg가 실제로 이렇게 하는가"가 핵심 질문인
  모든 것.
- 만약 나중에 실제로 이 방향으로 진행한다면: 어떤 테스트를 픽스처화할지 후보를 먼저
  추리고, 각 픽스처에 "언제/어떤 real hg 버전으로 기록했는지" 메타데이터를 남겨서,
  최소한 hg 버전이 바뀔 때 픽스처 재검증이 필요하다는 신호는 남기는 것을 권장.

## 관련 문서
- [[test-coverage-95-percent-initiative]] — 테스트 속도/커버리지 트레이드오프의 다른 사례
- [[39-exhaustive-interop-matrix]] — 라이브 real-hg 검증으로 발견한 버그들의 규모
- `known-bugs-registry.md` — "이미 한 번 확인한 걸 왜 또 확인하냐"는 유혹이 실제로
  버그를 놓치게 만든 사례들의 인덱스
