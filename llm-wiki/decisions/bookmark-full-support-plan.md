---
updated: 2026-09-01
status: completed
---

# 계획: Bookmark 완전 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 완전 지원"으로 확정된 항목의 실행 계획
> (Track B-3, [[jgit-parity-requirement]]와 무관 — JGit엔 없는 Mercurial 고유 개념이므로
> 클래스명은 그대로 `BookmarkCommand` 유지).
>
> ✅ **2026-09-01 구현 완료** — commit 자동 전진/update 활성화·비활성화/pull·push 동기화
> 전부 구현하고 실제 `hg` CLI로 fast-forward·진짜 divergence·원격 push·pull까지 검증
> (`BookmarkRealHgInteropTest`, 6개 테스트). 검증 과정에서 데이터 손실 버그 2건을 발견해
> 함께 수정: (1) `mercurial/bookmarks.py`의 `comparebookmarks()`/`validdest()`를 참고해
> ancestor 관계를 확인하지 않고 무조건 덮어쓰던 pull 병합 로직을 fast-forward/진짜
> divergence 구분 로직(`BookmarkCommand.mergeFromRemote()`)으로 교체, (2) 새 changeset
> 없이 bookmark만 이동한 원격을 pull하면 동기화 자체가 생략되던 조기 리턴 버그 수정.
> 이 과정에서 changegroup(cg1) 델타 베이스 규칙이 잘못돼 다중 head 저장소 pull 시
> 데이터가 깨지는 별도의 심각한 버그도 발견·수정했다(상세는
> [[mercurial-spec-compliance-requirement]]의 Changegroup 항목).

## 목표
Bookmark는 named branch와 달리 **가변적이고 이동 가능한 포인터**라는 것이 핵심 스펙이다.
현재 `BookmarkCommand`는 `.hg/bookmarks`/`.hg/bookmarks.current` 파일을 직접 CRUD하는
것까지만 구현되어 있고, 이 포인터가 실제로 "따라다니게" 만드는 나머지 명령들과는 전혀
연동되어 있지 않다. 목표는 commit/update/pull/push 전체 경로에서 bookmark가 실제 Mercurial과
동일하게 동작하도록 완성하는 것.

## 공식 근거
- `hg help bookmarks` — 사용자 관점의 동작 스펙 (활성 bookmark, 자동 전진, divergence).
- `hg help internals.wireprotocol` — `listkeys`/`pushkey` 네임스페이스로 bookmark를
  원격과 교환하는 프로토콜 상세.
- mercurial-scm.org 위키 `Bookmarks` — 설계 배경, `name@remote` divergent bookmark 표기 규칙.

## 현재 구현 상태 (2026-08-31 코드 조사로 확인)
| 동작 | 상태 | 근거 |
|---|---|---|
| `.hg/bookmarks`, `.hg/bookmarks.current` CRUD (생성/삭제/목록/활성 지정) | ✅ 구현됨 | `BookmarkCommand.call()`, 테스트 `BookmarkCommandTest.java` 존재 |
| commit 시 활성 bookmark를 새 리비전으로 자동 전진 | ❌ 미구현 | `CommitCommand.java`에 "bookmark" 문자열 0건 |
| update 시 대상 리비전에 따라 bookmark 활성화/비활성화 | ❌ 미구현 | `UpdateCommand.java`에 "bookmark" 문자열 0건 |
| pull 시 원격 bookmark를 로컬에 반영 | ❌ 미구현 | `PullCommand.java`에 "bookmark" 문자열 0건. `HgLocalClient.listKeys("bookmarks")`는 구현되어 있으나 호출하는 곳이 없음 |
| push 시 로컬 bookmark를 원격에 반영 (`pushkey`) | ❌ 미구현 | `PushCommand.java`에 "bookmark" 문자열 0건 |
| divergent bookmark 처리 (`name@remote` 표기) | ❌ 미구현 | 관련 코드 없음 |

**결론**: 지금은 "`.hg/bookmarks` 파일을 수동으로 편집하는 명령 하나"만 있는 수준이고,
Mercurial에서 bookmark를 branch와 구별 짓는 핵심 동작(자동 추종, 원격 동기화)이 전부 빠져
있다.

## 단계별 계획
| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | `CommitCommand`에 활성 bookmark 자동 전진 로직 추가 — 커밋 성공 직후 `BookmarkCommand.getActiveBookmark()`가 non-null이면 해당 bookmark를 새 리비전 노드로 갱신 | `CommitCommand` 수정 + 회귀 테스트 |
| 2 | `UpdateCommand`에 활성화/비활성화 로직 추가 — 대상 리비전을 정확히 하나의 bookmark가 가리키면 그 bookmark를 활성화, 이동하면 기존 활성 bookmark를 비활성화 | `UpdateCommand` 수정 + 테스트 |
| 3 | `PullCommand`에서 `listKeys("bookmarks")`로 원격 bookmark를 가져와 로컬과 병합. 로컬/원격이 같은 이름을 다른 노드로 가리키면 `name@remote` divergent bookmark로 분기 생성 | `PullCommand` 수정, divergence 처리 로직 |
| 4 | `PushCommand`에서 로컬 bookmark 변경분을 `pushkey` 프로토콜로 원격에 반영 (원격이 오래된 상태면 실패 처리) | `PushCommand` 수정, `HgRemoteConnection`에 `pushkey` 인터페이스 필요 시 추가 |
| 5 | 라운드트립 검증: 실제 hg CLI 저장소와 bookmark를 주고받는 clone/pull/push 시나리오 테스트 | 통합 테스트 (`BookmarkRoundtripTest` 등) |

## 코드 영향 범위 (현재 구조 기준)
- `api.CommitCommand`, `api.UpdateCommand`, `api.PullCommand`, `api.PushCommand`, `api.BookmarkCommand`
- `transport.HgLocalClient`(이미 `listKeys("bookmarks")` 구현됨, 호출부만 연결하면 됨),
  `transport.HgRemoteConnection`(원격 HTTP/SSH 경로에도 동일한 `listKeys`/`pushkey` 지원 확인 필요)

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거 (Track B-3로 승격, 2026-08-31)
- [[jgit-parity-requirement]] — 참고용(JGit에는 대응 개념 없음, 이름 유지 근거)
