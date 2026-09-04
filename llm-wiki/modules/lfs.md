---
updated: 2026-09-04
status: current
---

# 모듈: lfs (`io.github.search5.hg4j.lfs`)

LFS(Large File Storage) 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 —
JGit이 정확히 `org.eclipse.jgit.lfs` 패키지를 갖고 있어 이름까지 일치.

## 클래스
- **`HgLfsManager`**: LFS 오브젝트의 로컬 캐싱/해석과 원격 LFS 서버 연동을 담당.
  `.hg/store/lfs/objects/` 아래를 **실제 hg 방식의 단일 2글자 샤딩**(`objects/XX/YYYY...`)
  으로 해석한다 — Git-LFS의 2단계 중첩 샤딩(`XX/YY/ZZZZ...`)과는 다르며, 과거 hg4j가
  Git 방식으로 구현했던 것을 실제 hg와 스토어를 공유할 수 있도록 수정한 이력이 있다
  (`getLocalPath()` 주석 참고). `fetchObject()`로 LFS 배치 API(`/objects/batch`)를 통한
  원격 다운로드도 지원.
- **`HgLfsPointer`**: LFS 포인터 파일 파싱 값 객체 (`version`/`oid`/`size` 텍스트 포맷,
  revlog 안에는 이 포인터 텍스트만 저장되고 실제 대용량 파일은 별도 저장).

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/lfs/`
