---
updated: 2026-08-31
status: current
---

# 모듈: treewalk (`com.github.search5.hg4j.treewalk`)

매니페스트(커밋 시점 트리)와 워킹 디렉터리 순회. JGit의 `org.eclipse.jgit.treewalk`에 대응.

- `TreeWalk` / `TreeIterator`: 트리 순회 공통 프레임워크. (JGit도 동일하게
  `TreeWalk`/`AbstractTreeIterator`)
- `ManifestWalk` / `ManifestTreeIterator`: 커밋된 매니페스트 순회.
  JGit에는 `CanonicalTreeParser`(git tree 객체 파서)가 대응 — hg4j는 Mercurial의 "매니페스트"
  개념을 그대로 이름에 반영.
- `WorkingDirWalk` / `WorkingDirTreeIterator`: 실제 파일시스템 워킹 디렉터리 순회.
  JGit의 `FileTreeIterator`에 대응.
- `PathFilter` / `SparsePathFilter`: 경로 필터링. JGit도 `org.eclipse.jgit.treewalk.filter`
  하위에 `PathFilter`가 동일 이름으로 존재.

## 관찰된 네이밍 차이
- JGit은 `TreeIterator`가 아니라 `AbstractTreeIterator`(추상 클래스)를 사용. hg4j는
  `TreeIterator`라는 이름을 그대로 씀 — 완전 동일 네이밍 요건([[jgit-parity-requirement]])
  관점에서는 검토 대상.
- JGit의 `PathFilter`는 `org.eclipse.jgit.treewalk.filter` **하위 패키지**에 위치하지만
  hg4j는 `treewalk` 패키지에 바로 둠 — 서브패키지 분리 여부도 검토 대상.

## 향후 편입 예정 클래스
- **`HgTreeFilter`** (현재 `core` 패키지에 위치): `treewalk.PathFilter`를 구현하며
  Javadoc에 "Inspired by JGit's TreeFilter api"라고 명시되어 있어, JGit의
  `org.eclipse.jgit.treewalk.filter.TreeFilter`에 대응. [[core-package-split-plan]]
  Phase 11에서 이 패키지로 이동하기로 결정됨(2026-08-31) — **아직 미실행**.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/treewalk/`
