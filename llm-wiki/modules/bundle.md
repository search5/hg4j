---
updated: 2026-08-31
status: current
---

# 모듈: bundle (`com.github.search5.hg4j.bundle`)

Mercurial bundle2/changegroup 컨테이너 포맷 파서 패키지. [[core-package-split-plan]]
Phase 8에서 분리됨.

## 클래스
- **`Bundle2Parser`**: bundle2(HG20) 컨테이너 포맷 디코딩. 스트림 레벨 압축(zlib deflate)을
  동적으로 해석하고 내부 CHANGEGROUP 페이로드를 추출.
- **`ChangegroupParser`**: changegroup(번들) 페이로드를 로컬 저장소에 적용(unpackage).

## 테스트 위치 참고
전용 단위 테스트 파일이 없고, `CHgPushRoundtripTest`/`CHgPullRoundtripTest` 등 `api`
패키지의 통합 테스트에서 간접적으로만 커버됨(Track A Phase 8 계획 당시부터 알려진 사항).

## 도메인 개념 문서
컨테이너 포맷 상세, 과거 이슈 이력은 [[bundle2-changegroup]](concepts/bundle2-changegroup.md) 참고.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] gap table — Bundle1(레거시)은 미구현, cg1/cg2/cg3
  버전별 차이는 확인 필요로 남아있음
- [[wireprotocol-v2-support-plan]] — wireprotocol v2 완료 후 changegroup 교환 경로가
  바뀔 수 있음(참고용, 아직 미실행)

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/bundle/`
