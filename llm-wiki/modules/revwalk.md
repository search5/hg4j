---
updated: 2026-08-31
status: current
---

# 모듈: revwalk (`io.github.search5.hg4j.revwalk`)

리비전 그래프 순회/필터. JGit의 `org.eclipse.jgit.revwalk`에 대응.

- `ChangesetGraph`: 리비전 DAG 표현. `setSortOrder`로 위상 정렬(topological sort) 지원
  (git log: "ChangesetGraph에 Topological Sort 기능" 추가 이력 있음).
  JGit의 핵심 클래스는 `RevWalk`인데, hg4j는 Mercurial 고유 용어인 "changeset"을 살려
  `ChangesetGraph`라는 이름을 쓴다.
- `RevFilter` (인터페이스), `AndRevFilter` / `OrRevFilter` / `NotRevFilter` /
  `MaxCountRevFilter`: 조합 가능한 필터 체인. **이 5개는 JGit과 이름이 완전히 동일**
  (`org.eclipse.jgit.revwalk.filter.RevFilter`, `AndRevFilter`, `OrRevFilter`, `NotRevFilter`,
  `MaxCountRevFilter`).
- `SortOrder`: 정렬 방식 열거. JGit은 `RevSort`라는 이름을 쓴다.

## 네이밍 비교 (참고용, 격차 아님)
| hg4j | JGit | 판단 |
|---|---|---|
| `ChangesetGraph` | `RevWalk` | 의도적 도메인 용어 사용 — 리네이밍 안 함 ([[jgit-parity-requirement]] 결정 사항 참고) |
| `SortOrder` | `RevSort` | 동상 — 리네이밍 안 함 |
| `RevFilter`, `AndRevFilter`, `OrRevFilter`, `NotRevFilter`, `MaxCountRevFilter` | 동일 | 이미 일치 |

> 2026-08-31 사용자 결정: "머큐리얼만의 고유 특징을 jgit과 1:1로 매칭할 필요는 없음".
> `ChangesetGraph`/`SortOrder`는 격차가 아니라 의도된 도메인 네이밍으로 확정.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/revwalk/`
