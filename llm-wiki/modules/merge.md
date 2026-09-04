---
updated: 2026-09-04
status: current
---

# 모듈: merge (`io.github.search5.hg4j.merge`)

3-way 머지 알고리즘 전용 패키지. [[core-package-split-plan]] Phase 2에서 분리됨 —
순수 알고리즘이라 외부 의존이 거의 없어 초반에 안전하게 뗄 수 있었다. JGit의
`org.eclipse.jgit.merge`에 대응.

## 클래스
- **`Merge3`**: LCS(최장 공통 부분 수열) 기반 sync point 매칭 + Hirschberg 알고리즘으로
  O(N) 공간 복잡도를 달성한 라인 단위 3-way 머지 엔진. 대용량 파일 머지 시 OOM 방지가
  설계 목표. 내부 `MergeResult`가 충돌 여부와 병합 결과를 담는다.
- **`MergeState`**: `.hg/merge/state2`(진행 중인, 충돌 가능성 있는 머지의 온디스크
  기록) 읽기/쓰기. 실제 hg의 `mergestate._readrecordsv2`/`_writerecordsv2`와 동일한
  `[1바이트 타입][4바이트 빅엔디안 길이][내용]` 레코드 프레이밍을 그대로 구현해, hg4j가
  시작한 충돌 머지를 실제 `hg resolve`로 이어서 처리하거나 그 반대도 가능하다. `L`(로컬
  노드)/`O`(상대 노드)/`F`(병합된 파일 엔트리) 외 타입은 `t`(RECORD_OVERRIDE)로 감싸
  구버전 클라이언트가 안전하게 무시하도록 한다.

## 관련 페이지
- [[jgit-parity-requirement]] — 패키지명 정합성 근거
- `api.MergeCommand`(포셀린 계층, `api`에 그대로 위치)가 이 클래스를 호출하는 소비자

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/merge/`
