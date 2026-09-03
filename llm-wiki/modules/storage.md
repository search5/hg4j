---
updated: 2026-08-31
status: current
---

# 모듈: storage (`io.github.search5.hg4j.storage`)

Revlog 저장 엔진 패키지 — Track A에서 가장 광범위하게 옮긴 대상. [[core-package-split-plan]]
Phase 10에서 분리됨(마지막 순서로 진행). JGit의 `org.eclipse.jgit.storage`에 대응.

## 클래스
- **`Revlog`**: 파일별 append-only 히스토리 로그 메인 클래스.
- **`RevlogIndex`**: `.i` 인덱스 파일 파싱/조회.
- **`DeltaCodec`**: revlog 데이터 압축/해제 담당(SRP 분리 — zlib/zstd 등 포맷 지원).
- **`StoreEngine`**(인터페이스): 물리 revlog 파일시스템 접근을 포셀린 명령으로부터
  분리하는 pluggable 저장 엔진 추상화 — SQLite/RocksDB/가상화 호스팅 확장 여지를 열어둠.
- **`DefaultFileStoreEngine`**: `StoreEngine`의 표준 파일시스템 구현, `.hg` 저장소를
  직접 다룸.

## 도메인 개념 문서
Revlog 포맷 상세, generaldelta/inline, 과거 버그 이력(BUG-01/02/04/10)은
[[revlog]](concepts/revlog.md) 참고.

## 진행 중인 확장 계획
- [[revlog-v2-support-plan]] — persistent nodemap/sidedata/docket 기반 v2 지원(Track B-1,
  설계 완료·실행 대기). `Revlog`/`RevlogIndex`/`DeltaCodec`가 v1/v2 분기 추가 지점.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/storage/`
