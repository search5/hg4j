---
updated: 2026-09-04
status: current
---

# 모듈: treewalk (`io.github.search5.hg4j.treewalk`)

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
- **`HgTreeFilter`**: `PathFilter`를 구현하는 추상 클래스, Javadoc에 "Inspired by JGit's
  TreeFilter api"라고 명시 — JGit의 `org.eclipse.jgit.treewalk.filter.TreeFilter`에
  대응. [[core-package-split-plan]] Phase 11 계획대로 이미 이 패키지로 이동 완료(더 이상
  `core`에 없음). `createPathPrefixFilter()`(단순 prefix 기반 narrow/sparse)에 더해,
  실제 hg의 narrowspec 포맷(`.hg/store/narrowspec`, requirement
  `narrowhg-experimental`, `[include]`/`[exclude]` 단수 섹션, `path:`/`rootfilesin:`
  패턴 타입)을 그대로 정규화·매칭하는 `NarrowPattern`(중첩 static 클래스),
  `normalizeNarrowPattern()`, `createNarrowSpecFilter()`가 추가됨(백로그 22~28 상호운용
  검증 과정에서).
- **`SparseConfig`**: `.hg/sparse`(추적 안 되는 워킹카피 파일, `.hg/hgrc`처럼 직접 읽음)와
  `%include`로 참조되는 프로파일 파일(매니페스트에서 추적되는 파일이라 리비전에 따라
  달라질 수 있음)을 실제 hg의 `mercurial/sparse.py`(`parseconfig()`/`patternsforrev()`)와
  동일하게 파싱해 유효 include/exclude 패턴 집합으로 해석.

## 관찰된 네이밍 차이
- JGit은 `TreeIterator`가 아니라 `AbstractTreeIterator`(추상 클래스)를 사용. hg4j는
  `TreeIterator`라는 이름을 그대로 씀 — 완전 동일 네이밍 요건([[jgit-parity-requirement]])
  관점에서는 검토 대상.
- JGit의 `PathFilter`는 `org.eclipse.jgit.treewalk.filter` **하위 패키지**에 위치하지만
  hg4j는 `treewalk` 패키지에 바로 둠 — 서브패키지 분리 여부도 검토 대상.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/treewalk/`
