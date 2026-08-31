---
updated: 2026-08-31
status: current
---

# 모듈: lfs (`com.github.search5.hg4j.lfs`)

LFS(Large File Storage) 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 —
JGit이 정확히 `org.eclipse.jgit.lfs` 패키지를 갖고 있어 이름까지 일치.

## 클래스
- **`HgLfsManager`**: LFS 오브젝트의 로컬 캐싱/해석 관리. 보통 `.hg/store/lfs/objects/`
  아래에서 해석(Git/Mercurial 공통 관례).
- **`HgLfsPointer`**: LFS 포인터 파일 파싱 값 객체 (`version`/`oid`/`size` 텍스트 포맷,
  revlog 안에는 이 포인터 텍스트만 저장되고 실제 대용량 파일은 별도 저장).

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/lfs/`
