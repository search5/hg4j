---
updated: 2026-08-31
status: current
---

# 모듈: lib (`com.github.search5.hg4j.lib`)

JGit의 `org.eclipse.jgit.lib`에 대응하는 최상위 핵심 데이터 구조 및 공통 구성 패키지입니다.
2026-08-31 리팩토링(Track A)을 통해 기존 `com.github.search5.hg4j.core` 패키지가 완전히 정리되고 남은 핵심 진입점 클래스들이 이 패키지로 통합되었습니다.

## 핵심 데이터 및 공통 유틸리티
- **`NodeId`**: 20바이트 SHA-1 기반 리비전 식별자 (Mercurial node id) 값 객체. JGit의 `ObjectId`에 대응.
- **`ProgressMonitor`** (인터페이스), **`NullProgressMonitor`**, **`TextProgressMonitor`**: 장시간 작업 진행률 콜백. JGit과 동일한 명명 및 역할.

## 저장소 진입점
- **`Repository`** (인터페이스) — 저장소 기본 동작 시그니처 정의. JGit의 `Repository`와 유사.
- **`HgRepository`** — 저장소 진입점 유일 구현체. 작업 디렉터리(`directory`) 및 `.hg` 디렉터리 경계를 구분하고, ignore 패턴 파싱과 트랜잭션 자동 롤백 트리거(`checkAndPerformAutoRollback()`) 등을 관리.
- **`HgRcConfig`**: `.hgrc` 로컬/글로벌 구성 파일 인터페이스 및 파서.
- **`HgLock`**: 저장소 락(`.hg/wlock`) 및 스토어 락(`.hg/store/lock`) 획득을 담당하여 다중 스레드/프로세스 환경에서의 안전성 보장.

## 패키지 구조 정합성 달성
이전에는 다수의 역할이 `core` 패키지 하나에 뭉쳐 있었으나, 격차 분석 결과에 따라 각 책임을 세분화된 하위 패키지(`storage`, `dirstate`, `merge`, `diff`, `util`, `submodule`, `phase`, `obsolete`, `revset`, `bundle`, `lfs`, `gpg`)로 이관하고, 최상위 저장소 수명주기 및 형상 수호 계층만을 `lib` 패키지에 유지하도록 재구성하였습니다.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/lib/`
