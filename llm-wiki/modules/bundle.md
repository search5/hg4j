---
updated: 2026-09-04
status: current
---

# 모듈: bundle (`io.github.search5.hg4j.bundle`)

Mercurial bundle2/changegroup 컨테이너 포맷 파서 패키지. [[core-package-split-plan]]
Phase 8에서 분리됨.

## 클래스
- **`Bundle2Parser`**: bundle2(HG20) 컨테이너 포맷 디코딩/인코딩. 스트림 레벨 압축(zlib
  deflate)을 동적으로 해석하고 내부 CHANGEGROUP 페이로드를 추출하며, `bundleCaps`/
  `bundle2` capability 토큰 빌드·파싱(`buildChangegroupBundleCaps`/
  `decodeChangegroupVersions`/`requestsBundle2`), 응답용 bundle2 프레이밍(reply/에러
  abort/빈 응답 빌더)까지 함께 담당한다.
- **`ChangegroupParser`**: changegroup(cg1~cg5) 페이로드를 파싱/작성. 버전별 델타 헤더
  형태 자동 판별(`autoDetectVersion`), cg3+ 트리 매니페스트, cg4 `CG_FLAG_FULL_TEXT`,
  cg5 `CG_FLAG_SIDEDATA` 플래그를 처리한다.
- **`ClonebundlesManifest`**: `clonebundles.manifest` 텍스트 파싱 및 지원 bundlespec
  필터링(`filterSupported`).

## 테스트 위치
`bundle` 패키지 전용 단위 테스트: `Bundle2ParserTest`, `ChangegroupV4V5Test`,
`ClonebundlesManifestTest`. 추가로 `api` 패키지의 `BundleCommandTest`,
`UnbundleCommandCoverageTest`, `ClonebundlesCommandTest`,
`ClonebundlesAutoWireInteropTest`, `ClonebundlesRealHgInteropTest`,
`CensorChangegroupTransferTest` 등 통합/실제 hg 상호운용 테스트에서도 폭넓게 커버된다.

## 도메인 개념 문서
컨테이너 포맷 상세, 과거 이슈 이력은 [[bundle2-changegroup]](concepts/bundle2-changegroup.md) 참고.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 백로그 22~28에서 bundle2 프레이밍/
  changegroup 버전 협상 관련 실제 hg CLI 상호운용 버그를 다수 발견·수정
- [[wireprotocol-v2-support-plan]] — wireprotocol v2 완료 후 changegroup 교환 경로가
  바뀔 수 있음(참고용, 아직 미실행)

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/bundle/`
