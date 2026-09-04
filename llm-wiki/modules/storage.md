---
updated: 2026-09-04
status: current
---

# 모듈: storage (`io.github.search5.hg4j.storage`)

Revlog 저장 엔진 패키지 — Track A에서 가장 광범위하게 옮긴 대상. [[core-package-split-plan]]
Phase 10에서 분리됨(마지막 순서로 진행). JGit의 `org.eclipse.jgit.storage`에 대응.

## 클래스
- **`Revlog`**: 파일별 append-only 히스토리 로그 메인 클래스. v1(inline/non-inline,
  델타 체인)과 v2(changelog-v2/일반 revlog-v2, docket 기반, 항상 풀텍스트로 append) 양쪽
  포맷의 읽기/쓰기를 모두 담당하며, `getSidedata()`/`appendRevisionV2()`로 v2 sidedata
  (`.sda`) 영속화도 처리한다.
- **`RevlogIndex`**: `.i` 인덱스 파일 파싱/조회. v1 64바이트 레코드와 v2 96바이트
  레코드(`INDEX_ENTRY_CL_V2`/`INDEX_ENTRY_V2`) 양쪽을 인식하고, 브랜드 뉴 revlog를
  저장소 요구사항에 맞춰 처음부터 v2 docket으로 초기화하는 `initializeNewGeneralV2Docket()`도
  포함.
- **`NodeMapFile`**: `persistent-nodemap` requirement의 `.n` 트라이 파일 로드/영속화
  (`tryLoad`/`persist`) — 존재하면 가속 조회에 쓰이고, 없거나 손상돼도 항상 순차 스캔
  fallback으로 안전하게 무시됨.
- **`FileIndex`**: `fileindex-v1`(`exp-revlogv2.2` 저장소가 fncache 대신 쓰는 경로
  인덱스 — docket + list/meta/tree 방사 트라이) 읽기/쓰기. hg4j 쓰기 경로는 실제 hg의
  증분(copy-on-write) 알고리즘 대신 매번 전체 재빌드("vacuum") 전략을 쓴다(스펙상
  유효, 디스크 낭비만 더 큼). `snapshot()`/`restore()`로 트랜잭션 롤백 지원.
- **`SidedataCodec`**: v2 리비전의 sidedata 컨테이너(정수 키 → 바이트 페이로드 맵)
  직렬화/역직렬화.
- **`DeltaCodec`**: revlog 데이터 압축/해제 담당(SRP 분리 — zlib/zstd 등 포맷 지원).
- **`StoreEngine`**(인터페이스): 물리 revlog 파일시스템 접근을 포셀린 명령으로부터
  분리하는 pluggable 저장 엔진 추상화 — SQLite/RocksDB/가상화 호스팅 확장 여지를 열어둠.
- **`DefaultFileStoreEngine`**: `StoreEngine`의 표준 파일시스템 구현, `.hg` 저장소를
  직접 다루며 브랜드 뉴 revlog에 v2 초기화가 필요한지 계산해 `Revlog` 생성자로 전달한다.

## 도메인 개념 문서
Revlog 포맷 상세, generaldelta/inline, 과거 버그 이력(BUG-01/02/04/10)은
[[revlog]](concepts/revlog.md) 참고.

## v2(docket 기반) 지원 현황
- [[revlog-v2-support-plan]] — changelog-v2/일반 revlog-v2(`exp-revlogv2.2`)/
  `fileindex-v1` 모두 읽기+쓰기 구현 완료, 실제 hg CLI(Rust 확장 포함 7.2.4) 상호운용
  검증까지 끝남. `persistent-nodemap` 가속 조회와 sidedata의 실제 소비(LFS 등)는
  일부만 구현 — 문서의 "현재 구현 상태" 표 참고.
- cg5 changegroup으로 들어오는 sidedata(`exp-copies-sidedata-changeset`의 SD_FILES
  copy-tracing 등)는 `Revlog.appendChangeGroupEntry()`가 v2 revlog일 때
  `appendRevisionV2()`로 그대로 반영한다(백로그 26 — 이전엔 v2 revlog에도 v1 레이아웃을
  강제로 써서 저장소를 깨뜨렸음).

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/storage/`
