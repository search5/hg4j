---
updated: 2026-08-31
status: current
---

# 모듈: merge (`com.github.search5.hg4j.merge`)

3-way 머지 알고리즘 전용 패키지. [[core-package-split-plan]] Phase 2에서 분리됨 —
순수 알고리즘이라 외부 의존이 거의 없어 초반에 안전하게 뗄 수 있었다. JGit의
`org.eclipse.jgit.merge`에 대응.

## 클래스
- **`Merge3`**: LCS(최장 공통 부분 수열) 기반 sync point 매칭 + Hirschberg 알고리즘으로
  O(N) 공간 복잡도를 달성한 라인 단위 3-way 머지 엔진. 대용량 파일 머지 시 OOM 방지가
  설계 목표. 내부 `MergeResult`가 충돌 여부와 병합 결과를 담는다.

## 관련 페이지
- [[jgit-parity-requirement]] — 패키지명 정합성 근거
- `api.MergeCommand`(포셀린 계층, `api`에 그대로 위치)가 이 클래스를 호출하는 소비자

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/merge/`
