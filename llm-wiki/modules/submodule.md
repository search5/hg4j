---
updated: 2026-08-31
status: current
---

# 모듈: submodule (`com.github.search5.hg4j.submodule`)

Mercurial subrepository(`.hgsub`/`.hgsubstate`) 지원 패키지. [[core-package-split-plan]]
Phase 4에서 분리됨 — 독립적 기능 단위라 이동이 단순했다. JGit의 서브모듈 개념과 유사하나
파일 포맷은 Mercurial 고유.

## 클래스
- **`HgSubrepoEntry`**: `.hgsub`/`.hgsubstate`에서 파싱된 서브레포 정의 하나(경로,
  소스 URL, 리비전) 값 객체.
- **`HgSubrepoParser`**: `.hgsub`(정의)와 `.hgsubstate`(상태 리비전)를 함께 읽어
  `HgSubrepoEntry` 목록으로 조인.

## 관련 페이지
- `api.SubrepoCommand` — 포셀린 계층 소비자
- [[mercurial-spec-compliance-requirement]] gap table의 "Subrepositories" 항목(✅ 존재)

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/submodule/`
