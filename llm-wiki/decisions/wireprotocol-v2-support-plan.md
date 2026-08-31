---
updated: 2026-09-01
status: completed
---

# 계획: Wire Protocol v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원"으로 확정된 항목의 실행 계획.
> **구현 완료됨.**
>
> ⚠️ **2026-09-01 재검토 — 중요한 근본적 한계 발견**: 이 환경(및 실제로 조사해보니
> 최신 Mercurial 배포판 전반)에는 wireprotocol v2를 실제로 서빙하는 서버 코드가
> **Mercurial 자체에 없다** — `mercurial/wireprotov2server.py`/`wireprotov2peer.py`가
> 존재하지 않고, 실제 v1 디스패처(`wireprotoserver.py`)에도 `application/mercurial-cbor`나
> `/api/` 관련 코드가 0건. `hg help internals.wireprotocolv2`는 "experimental and under
> active development"라고 문서화하지만 실제 구현은 개발이 중단된 것으로 보인다. **즉
> revlog v2(Track B-1)와 달리 이 기능은 실제 hg 서버와 상호운용 검증이 원천적으로
> 불가능하다** — hg4j의 v2 클라이언트-서버 쌍이 서로를 검증하는 것이 유일한 방법이며,
> 이는 진짜 프로토콜 호환성을 증명하지 못한다. 또한 실제 `hg help
> internals.wireprotocolrpc`가 정의하는 8바이트 바이너리 프레임 헤더 기반 스트림
> 다중화 프로토콜(hgrpc)은 구현하지 않았고, 단순 HTTP POST/응답에 CBOR 하나씩 담는
> 방식으로 근사했다 — 검증할 실제 서버가 없는 상태에서 복잡한 다중화 프로토콜을 만드는
> 건 검증 안 된 추측을 쌓는 것이라 판단해 의도적으로 보류. 아래 "3. 단계별 계획"
> 자체는 여전히 유효하지만, "완료"의 의미가 revlog v2와는 다르다는 점을 반드시 인지할
> 것 — 상세는 [[mercurial-spec-compliance-requirement]]의 Wire protocol v2 항목 참고.

## 목표
현재 `transport` 패키지는 주로 wire protocol v1(HTTP `?cmd=` 쿼리 스트링, SSH 텍스트 기반 프레이밍)을 기준으로 작동하도록 설계되어 있습니다.
다만, 기존 `HgRemoteClient.java`와 `HgWireServer.java` 등에는 공식 스펙과 일치하지 않는 미완성 형태의 자체 v2 분기(예: `application/mercurial-x-api-v2` 미디어 타입, `/.hg/api/v2/` 엔드포인트, 내장 `CborDecoder` 클래스 등)가 하드코딩되어 있습니다.

본 계획은 공식 스펙(예: `/api/<command>` 엔드포인트 공간, `application/mercurial-cbor` 미디어 타입, 표준 CBOR 기반 데이터 교환)을 완전히 준수하는 정식 wire protocol v2를 **v1과 병행**할 수 있도록 추가하는 것을 목표로 합니다. 서버와의 capability 협상 결과에 따라 v2를 기본 사용하고, 미지원 시 v1으로 자동 폴백하도록 구현하며, 이 과정에서 기존의 임시 v2 구현 잔재를 정리합니다.

## 공식 근거
- `hg help internals.wireprotocolv2` — 1차 소스 (전송 포맷 및 API 상세).
- `hg help internals.wireprotocolrpc` — v2 RPC 프레이밍 및 구조 상세.
- mercurial-scm.org 위키 `HttpCommandProtocol` — v1/v2 호환성 공존 체계.

## 1. Wire Protocol v2 바이너리 및 통신 명세

### A. URL 엔드포인트 공간 분리
- **HTTP 엔드포인트**: v1의 `?cmd=...` 호출 방식 대신, v2 클라이언트는 `<repo-url>/api/<command>` 구조의 REST-like API 방식으로 원격 서버에 요청을 전송합니다. (기존 하드코딩되었던 `/.hg/api/v2/`는 스펙에 맞지 않으므로 폐기합니다.)
- **요청/응답 미디어 타입**: 공식 규격인 `application/mercurial-cbor` 미디어를 활용하며, 데이터 전송은 규격화된 CBOR(Concise Binary Object Representation, RFC 8949) 페이로드로 직렬화됩니다.

### B. CBOR 프레이밍 규격
- **요청 본문**: 명령 실행 인자들을 포함하는 CBOR Map 구조로 인코딩되어 전송됩니다.
- **응답 본문**: 스트리밍 처리를 위해 최상위가 CBOR Map 형식으로 인코딩된 프레임 구조를 가지며, 응답 상태 코드 및 출력 스트림, 그리고 에러 처리를 위한 표준 메타데이터를 내장합니다.
- **상호 협상**: v1 capability 응답 문자열에서 `httpheader=1024` 등 v2 호환성을 나타내는 프레이밍 토큰 및 헤더 크기 협상을 지원해야 합니다.

---

## 2. 선행 과제: 의존성 추가
현재 `build.gradle`에 CBOR 처리를 위한 라이브러리가 존재하지 않으므로, 다음 라이브러리를 추가해야 합니다.
- **도입 라이브러리**: Jackson CBOR 모듈 (`com.fasterxml.jackson.dataformat:jackson-dataformat-cbor`)
- **버전**: 프로젝트의 Java 21 및 기존 의존성 버전 호환성을 보장하는 범위로 선택 (빌드 테스트 검증 필수).

---

## 3. 단계별 계획

| 단계 | 작업 | 산출물 |
|---|---|---|
| 0 | **기존 레거시 v2 잔재 제거**: `HgRemoteClient.java`(내장된 임시 `CborDecoder` 클래스 및 `isV2` 플래그 제거)와 `HgWireServer.java`(비표준 미디어 타입 `application/mercurial-x-api-v2`를 포함한 v2 전송 분기 제거) 등 코드와 관련 테스트 클래스에 흩어져 있는 비표준 v2 잔재를 전수 검토하고 정리합니다. | 정리된 `HgRemoteClient.java` 및 `HgWireServer.java` |
| 1 | **CBOR 의존성 적용 및 기초 테스트**: `build.gradle`에 CBOR 의존성을 적용하고, 기초적인 직렬화/역직렬화 유닛 테스트를 작성하여 라이브러리 정상 동작을 검증합니다. | `build.gradle` 의존성 반영 및 CBOR 유닛 테스트 코드 |
| 2 | **`TransportProtocol` 확장**: v1 handshake 단계에서 v2 capability 및 `/api/` 관련 헤더 가능 여부를 해석하고 캐싱하는 로직을 구현합니다. | `TransportProtocol.java` 수정 |
| 3 | **v2 전용 클라이언트 프레임워크 구현**: `transport` 패키지 내부에 v2 전용 통신 및 RPC 규격을 구현할 `HgRemoteClientV2`, `HgRemoteConnectionV2` 등의 전용 클래스를 추가합니다. | `HgRemoteClientV2.java` 등 신규 골격 클래스 |
| 4 | **프레임 스트림 파서 작성**: 응답으로 수신되는 `application/mercurial-cbor` 스트림을 안정적으로 토큰 단위로 끊어 파싱하는 역직렬화 엔진을 작성합니다. | `CborFrameParser.java` |
| 5 | **폴백 및 연동 메커니즘**: `HgRemoteConnectionFactory` 등에서 서버 capability 협상 결과에 따라 v2 클라이언트를 생성하고, 연결 실패 또는 미지원 시 v1 클라이언트로 폴백 연동합니다. | 연결 팩토리 분기 구현 |
| 6 | **서버 측 v2 구현**: `HgWireServer`(JGit `UploadPack`/`ReceivePack` 대응, 실제로 `com.sun.net.httpserver.HttpServer` 위에서 HTTP 서버 역할 수행 중)에 정식 스펙 준수 v2 서빙 로직을 추가합니다 — `/api/<command>` 라우팅, `application/mercurial-cbor` 요청/응답 프레이밍(Phase 4의 프레임 파서 재사용). Phase 0에서 제거한 비표준 `handleHttpV2Connection`(`application/mercurial-x-api-v2`)을 대체하는 것이며, 단순 테스트 목업이 아니라 `HgWireServer` 본체에 반영합니다. | `HgWireServer.java` v2 서빙 경로 |
| 7 | **통합 및 라운드트립 검증**: Phase 6에서 v2를 지원하게 된 `HgWireServer`를 실제 로컬 서버로 띄우고, push/pull/fetch 등의 porcelain 명령어 세트가 v1/v2 모드 양쪽에서 모두 안전하게 통과하는지 JUnit으로 검증합니다. | `HgHttpTransportV2RoundtripTest.java` |

---

## 코드 영향 범위 (현재 구조 기준)
- **`transport` 패키지**: `HgRemoteClient`, `HgRemoteConnection`, `HgRemoteConnectionFactory`, `TransportProtocol`, `HgWireServer` (Track A에 의하여 `core`에서 `transport`로 기이동 완료).
- **의존성**: `build.gradle`에 `jackson-dataformat-cbor` 추가.

## 미해결 쟁점
- v7.2.2 기준 wireprotocol v2의 실험적 스펙 수준 확인: 스펙 변화에 따른 내부 파이프라인의 유연한 폴백 설계 우선순위 조율 필요.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거
- [[transport]] — v1 구현 현황

