---
updated: 2026-08-31
status: current
---

# 계획: Wire Protocol v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원"으로 확정된 항목의 실행 계획.
> **아직 실행되지 않음** — 조사 결과를 바탕으로 한 계획 문서 단계.

## 목표
현재 `transport` 패키지는 wire protocol v1(HTTP/SSH, 텍스트/바이너리 혼합 프레이밍)만
구현되어 있다. v2(실험적, `/api/*` URL 공간, CBOR 기반)를 **v1과 병행**으로 추가해,
서버가 v2를 노출하면 v2를, 아니면 v1로 자동 폴백하도록 만든다.

## 공식 근거
- `hg help internals.wireprotocolv2` — 1차 소스.
- `hg help internals.wireprotocolrpc` — v2의 RPC 프레이밍 세부 규칙.
- mercurial-scm.org 위키 `HttpCommandProtocol` — v1/v2 공존 방식 배경.

## 알려진 핵심 차이 (조사로 확인된 범위)
- **URL 공간 분리**: v2는 `/api/*` 하위에 노출되며 v1(`?cmd=...` 쿼리스트링 방식)과
  공존한다. 서버가 v2를 지원하는지는 v1 capability 문자열 안에 v2 관련 토큰이 있는지로
  탐지 가능한 것으로 확인.
- **미디어 타입**: `application/mercurial-cbor` — CBOR(RFC 8949) 페이로드. 응답
  바이트스트림이 CBOR map으로 시작해 이후 데이터를 설명.
- **Capability 협상 방식 자체는 v1과 유사한 철학**(공백 구분 토큰 문자열)이지만, v2
  전용 capability 토큰 체계가 별도로 존재.

> ⚠️ 이 섹션도 1차 조사 수준이다. 실제 커맨드 목록(v2에서 어떤 명령이 v1과 이름/파라미터가
> 다른지), 프레이밍 세부(청크 경계, 에러 응답 포맷)는 `hg help internals.wireprotocolv2`
> 원문 전체와 Mercurial 소스(`mercurial/wireprotov2*.py`)를 직접 대조해야 확정된다.

## 선행 과제: 의존성
- **CBOR 파싱/인코딩 라이브러리가 `build.gradle`에 없다.** 현재 의존성 목록
  (`commons-compress`, `zstd-jni`, `jsch`, `bouncycastle` 3종)에는 CBOR 관련 라이브러리가
  전혀 없음 — v2 지원의 최우선 선행 작업.
- 후보: Jackson `jackson-dataformat-cbor`(가장 널리 쓰임) 또는 경량 대안. 라이선스/이미
  프로젝트에 들어와 있는 의존성과의 충돌 여부는 실제 도입 시점에 검토.

## 단계별 계획
| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | 조사: `hg help internals.wireprotocolv2`/`wireprotocolrpc` 원문 전체 확보, v2 커맨드 목록과 v1과의 매핑표 작성 | [[sources]]에 원문 요약 페이지 추가 |
| 2 | CBOR 의존성 도입 및 최소 인코딩/디코딩 유닛 테스트 | `build.gradle` 갱신 |
| 3 | `transport` 패키지에 v2 전용 클라이언트 클래스 추가 (v1 클래스와 병존, 예: `HgRemoteClient`가 v1/v2 분기하거나 별도 `HgRemoteClientV2` 추가 — 세부 설계는 착수 시 결정) | v2 클라이언트 골격 |
| 4 | Capability 협상에서 v2 토큰 탐지 → 가능하면 v2, 아니면 v1 폴백 | `TransportProtocol` 갱신 |
| 5 | 픽스처: 실제 hg CLI(v7.2.2)로 v2를 노출하는 서버를 띄워 라운드트립 테스트 (`HgWireServer`에 v2 서버 측 지원 추가도 필요한지 검토) | `HgHttpTransportRoundtripTest` 계열 확장 |
| 6 | push/pull/fetch 등 기존 porcelain 명령이 v2 경로로도 동일하게 동작하는지 검증 | 기존 `api` 테스트 재사용 + v2 전용 케이스 추가 |

## 코드 영향 범위 (현재 구조 기준)
- `transport` 패키지: `HgRemoteClient`, `HgRemoteConnection`, `HgRemoteConnectionFactory`,
  `TransportProtocol`, `HgWireServer` — v2 분기 지점.
- `build.gradle` — CBOR 의존성 추가.
- [[jgit-parity-requirement]] 관점에서, JGit에는 wire protocol v2 개념 자체가 없으므로
  (JGit은 Git 프로토콜 v2를 갖지만 이름만 같을 뿐 다른 스펙) 이 작업은 순수하게
  [[mercurial-spec-compliance-requirement]] 축의 작업이지 JGit 대응 클래스를 찾는 작업이
  아니다.

## 미해결 (다음 조사에서 채울 것)
- v2가 실험적 상태(`hg help`에도 "under active development"로 명시)라, README의 기준
  버전(v7.2.2) 시점에 v2 스펙이 얼마나 안정화되어 있는지 확인 필요 — 스펙이 계속
  바뀌는 중이라면 "완전 준수"의 기준 시점을 못박는 게 중요해짐.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거 (필수 구현 확정)
- [[transport]] — v1 구현 현황
- [[core-package-split-plan]] — 이 작업과 시점이 겹치지 않도록 순서 조율 필요
