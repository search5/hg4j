---
updated: 2026-08-31
status: current
---

# 모듈: errors (`com.github.search5.hg4j.errors`)

`HgException`을 루트로 하는 checked exception 계층. JGit의 `org.eclipse.jgit.errors`에
대응하며, **패키지명과 계층 형태(공통 루트 + 세부 서브클래스)는 이미 JGit 관례와 일치**.

- `HgException` (루트, checked — [[checked-exception-conversion]] 참고)
- `HgLockException`, `HgTransportException`, `HgMergeConflictException`,
  `HgRevisionNotFoundException`, `HgAuthException`, `HgRepositoryNotFoundException`,
  `HgValidationException`, `HgProtocolException`, `HgCorruptDataException`

## JGit과의 이름 비교
| hg4j | JGit 유사 개념 | 비고 |
|---|---|---|
| `HgLockException` | `LockFailedException` | 접두어(`Hg`) 유무 차이 — 아래 참고 |
| `HgTransportException` | `TransportException` (동일 위치: `errors`가 아니라 `api.errors`) | |
| `HgRepositoryNotFoundException` | `RepositoryNotFoundException` | 접두어만 다름 |
| `HgCorruptDataException` | `CorruptObjectException` | 개념 유사, 이름은 도메인 반영(Object→Data) |

> 2026-08-31 사용자 결정: `Hg` 접두어는 유지한다. [[jgit-parity-requirement]]가 요구하는
> "동일한 네이밍/구조"는 패키지 구조 정합성에 대한 것이며, 클래스명 접두어 제거를
> 요구하지 않는 것으로 확정.

## 해결됨: 과거 `HgLockException` 이원화 (2026-08-31 Phase 0에서 정리 완료)
한때 `core.HgLockException`(레거시, `IOException` 상속)과 `errors.HgLockException`
(`HgException` 상속, checked exception 계층 정식 멤버)이 동시에 존재했으나,
[[core-package-split-plan]] Phase 0에서 모든 참조를 `errors.HgLockException`으로
통일하고 `core` 판은 삭제했다. 현재는 `errors.HgLockException` 하나만 존재.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/errors/`
