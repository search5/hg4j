---
updated: 2026-08-31
status: current
---

# 결정: HgException을 Unchecked → Checked Exception으로 전환 (BUG-11)

## 무엇이 바뀌었나
git log 커밋 `7f0c42d`: "HgException Checked Exception으로 전격 전향(BUG-11)".
`errors/HgException`을 루트로 하는 예외 계층 전체가 unchecked(RuntimeException 계열)에서
checked exception으로 전환되었다.

## 왜 (추정 — 커밋 메시지 이상의 상세 근거는 코드/커밋에 명시되어 있지 않음)
JGit 등 유사 라이브러리는 대체로 checked exception(`GitAPIException` 등)을 채택한다.
- 라이브러리 소비자에게 "이 API는 저장소 I/O 실패, 락 충돌, 프로토콜 오류 등이 날 수 있다"는
  것을 컴파일 타임에 강제로 인지시키는 것이 목적으로 보임.
- 트랜잭션/락/네트워크가 얽힌 API(`runTransaction`, `push`/`pull` 등)에서 예외 처리 누락이
  치명적일 수 있어 명시적 처리를 강제하는 쪽을 택한 것으로 판단됨.

## 실무 영향
- `Hg.xxx(repo).call()` 계열 메서드를 호출하는 코드/테스트는 `throws HgException`(또는 하위
  타입)을 선언하거나 try-catch가 필요하다.
- 새 명령(`XxxCommand`)을 추가할 때도 이 계약을 따라야 함 — 임의로 unchecked로 되돌리면
  기존 API와 비일관.

## 관련 페이지
- [[core]] 모듈의 예외 계층 목록
