---
updated: 2026-08-31
status: current
---

# 모듈: submodule (`io.github.search5.hg4j.submodule`)

Mercurial subrepository(`.hgsub`/`.hgsubstate`) 지원 패키지. [[core-package-split-plan]]
Phase 4에서 분리됨 — 독립적 기능 단위라 이동이 단순했다. JGit의 서브모듈 개념과 유사하나
파일 포맷은 Mercurial 고유.

## 클래스
- **`HgSubrepoEntry`**: `.hgsub`/`.hgsubstate`에서 파싱된 서브레포 정의 하나(경로,
  소스 URL, 리비전, 타입) 값 객체. 타입은 `Type{HG,GIT,SVN}` enum(백로그 41,
  2026-09-06 — 이전엔 `isGit` 단일 boolean이었음; 기존 4-arg/boolean 생성자와
  `isGit()`은 하위 호환 유지, 신규 5-arg 생성자 + `isSvn()`/`getType()` 추가).
- **`HgSubrepoParser`**: `.hgsub`(정의)와 `.hgsubstate`(상태 리비전)를 함께 읽어
  `HgSubrepoEntry` 목록으로 조인. `[git]`/`[svn]` URL prefix를 인식해 타입을 분기.
- **`GitSubrepoUtil`**: `[git]` 타입 서브저장소의 git CLI 쉘아웃 헬퍼.
- **`SvnSubrepoUtil`**(백로그 41, 신규): `[svn]` 타입 서브저장소의 svn CLI 쉘아웃
  헬퍼 — `svn status`/`svn info` XML 파싱(상태 조회), `svn checkout --force`(pin
  체크아웃), `svn commit`+`svn update`(커밋 전파). 상세는 [[backlog/subrepo]].

## 관련 페이지
- `api.SubrepoCommand` — 포셀린 계층 소비자
- [[mercurial-spec-compliance-requirement]] gap table의 "Subrepositories" 항목(✅ 존재)

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/submodule/`
