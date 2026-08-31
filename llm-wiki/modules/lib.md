---
updated: 2026-08-31
status: current
---

# 모듈: lib (`com.github.search5.hg4j.lib`)

JGit의 `org.eclipse.jgit.lib`에 대응하는 이름을 가졌지만, **역할 범위는 훨씬 좁다** —
JGit의 `lib`는 `Repository`, `ObjectId`, `Config`, `PersonIdent` 등 저장소 핵심 데이터
구조 전체를 담는 반면, hg4j는 그 핵심 클래스들(`HgRepository`, `Dirstate`, `Revlog` 등)을
전부 `core` 패키지에 몰아넣고 `lib`에는 다음 두 클래스만 남아 있다:

- `NodeId`: 20바이트 SHA-1 기반 리비전 식별자 (Mercurial node id) 값 객체.
  JGit의 `ObjectId`에 대응.
- `ProgressMonitor` (인터페이스), `NullProgressMonitor`, `TextProgressMonitor`:
  장시간 작업 진행률 콜백. **JGit과 완전히 동일한 이름/역할.**

## 이것이 바로 "일부 합쳐져서 기록된" 구조 불일치
현재 hg4j의 패키지 경계는 JGit과 이름은 같지만 **책임 범위가 다르게 쪼개져** 있다:
- JGit: `lib`(핵심 데이터구조) + `storage.file`(파일 저장 백엔드) + `dircache`(인덱스) +
  `merge` + `util` + `ignore` + `submodule` + `hooks`로 세분화.
- hg4j: 위 전부가 **`core` 패키지 하나**로 합쳐짐 (`HgRepository`, `Dirstate`, `Revlog`,
  `Merge3`, `SafeFileIO`, `HgSubrepoParser`, `HgLfsManager` 등이 전부 `core`에 공존).

자세한 격차 분석은 [[jgit-parity-requirement]] 참고.

## 해소 계획 (결정됨, 2026-08-31 — 아직 미실행)
`core` 패키지를 [[core-package-split-plan]]의 Phase 1~11에 따라 여러 패키지로 분리하고
나면, 남는 `HgRepository`/`Repository`/`HgRcConfig`/`HgLock`/`HgLockException`을
**이 `lib` 패키지로 합친다** (Phase 12). 지금 이 페이지에 나열된 `NodeId`/
`ProgressMonitor` 계열과 이름이 겹치는 클래스는 없어 단순 병합이면 충분하다고 확인됨.
합쳐진 이후의 `lib`가 오히려 JGit 원본의 `org.eclipse.jgit.lib` 구조(Repository +
ObjectId + ProgressMonitor + Config를 한 패키지에)에 더 가까워진다.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/lib/`
