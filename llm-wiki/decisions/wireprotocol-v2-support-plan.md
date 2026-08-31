---
updated: 2026-09-01
status: completed
---

# 계획: Wire Protocol v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원"으로 확정된 항목의 실행 계획.
> **전면 재구현 완료(2026-09-01), 실제 Mercurial 6.0으로 양방향 검증됨.**
>
> 이 문서의 이전 버전은 Jackson CBOR + `/api/<command>` 평면 HTTP 스킴을 "정식 스펙
> 준수"라고 서술했지만, 실제로는 **사실상 전부 가짜(fictional)** 구현이었다. 아래
> "왜 처음부터 다시 만들었는가"에 그 경위를 남긴다.

## 왜 처음부터 다시 만들었는가

이전 구현을 실제 Mercurial 서버에 직접 붙여본 적이 한 번도 없었다. 실제로 붙여보니
(아래 "검증 방법" 참고) 다음이 전부 사실이 아니었다:

1. **엔드포인트**: 실제 v2는 루트 URL에서 `X-HgUpgrade-1`/`X-HgProto-1` 헤더로
   capabilities 발견 핸드셰이크를 먼저 하고, 그 응답에서 받은 `apibase`+네임스페이스
   (`exp-http-v2-0003`)로 `POST <apibase><namespace>/<ro|rw>/<command>` 형태의 URL에
   요청한다. hg4j는 이 핸드셰이크 자체가 없이 하드코딩된 `/api/<command>`에 그냥
   POST했다.
2. **프레이밍**: 실제 v2는 모든 요청/응답이 8바이트 바이너리 프레임 헤더(24비트
   payload length + 16비트 request id + 8비트 stream id + 8비트 stream flags +
   4비트 type + 4비트 flags, `mercurial/wireprotoframing.py` 실측)로 감싸인다. hg4j는
   프레이밍이 전혀 없이 HTTP 본문에 CBOR 하나를 그냥 실었다.
3. **명령 집합**: 실제 v2 명령은 `branchmap`/`capabilities`/`changesetdata`/
   `filedata`/`filesdata`/`heads`/`known`/`listkeys`/`lookup`/`manifestdata`/
   `pushkey`/`rawstorefiledata` 12개뿐이다(`mercurial/wireprotov2server.py`의
   `COMMANDS` 실측) — 번들/체인지그룹 기반 전송 개념 자체가 없다. hg4j가 만든
   `changegroup`/`getbundle`/`unbundle`은 v2에 **존재하지 않는** 명령이었다.
4. **CBOR 인코딩**: 결정적으로, 실제 hg는 맵 키를 포함해 거의 모든 문자열을 CBOR
   **byte-string**(major type 2)으로 인코딩한다 — Mercurial 내부 문자열이 전부
   Python `bytes`이기 때문이다. 실제 캡ability 응답을 Python `cbor2`로 직접 디코딩해
   `b'apibase'`, `b'commands'`처럼 모든 키가 `bytes`임을 확인했다. Jackson의 CBOR
   모듈(`CBORGenerator.writeFieldName`)은 필드명을 항상 text-string(major type 3)으로만
   쓰기 때문에 이 구조를 낼 방법이 없다 — 그래서 Jackson을 버리고 이 프로토콜 전용의
   최소 CBOR 인코더/디코더(`Cbor`)를 새로 짰다.

## 검증 방법 — 실제 Mercurial 6.0을 Docker로 직접 실행

이 환경(및 현재 배포되는 Mercurial 7.2 전반)에는 wireprotocol v2를 실제로 서빙하는
서버 코드가 없다 — `mercurial/wireprotov2server.py`/`wireprotov2peer.py`가 Mercurial
**6.1부터 완전히 제거**됐다(PyPI에서 각 버전의 소스 배포판을 받아 직접 확인:
6.0에는 존재, 6.1부터 부재). 이 프로토콜이 실제로 동작했던 **마지막 버전인 6.0**을
Docker 컨테이너에 빌드해 띄우고 두 방향 모두 검증했다:

1. **hg4j 클라이언트 → 실제 hg 6.0 서버**: `HgRemoteClientV2`로 capabilities 발견,
   heads/known/listkeys/lookup/pushkey/branchmap을 실행해 실제 서버 응답과 일치
   확인. 이어서 `changesetdata`+`manifestdata`+`filesdata`를 조합해 전체 clone에
   해당하는 changegroup(`HG10UN` 포맷)을 재구성한 뒤 hg4j 자신의 `UnbundleCommand`로
   적용 — 임포트된 노드 해시가 실제 서버의 head와 정확히 일치.
2. **실제 hg 6.0 클라이언트 → hg4j 서버**: `hg --config
   experimental.httppeer.advertise-v2=true clone http://<hg4j서버>/ clientrepo`로
   완전한 clone을 실행 — 파일 내용까지 정확히 복원됐고 `hg verify`가 무결성 이상
   없음을 확인.

이 과정에서 실제 spec의 세부 사항도 여러 개 확정했다(모두 Mercurial 6.0 소스 코드
직접 열람 + 실제 트래픽 캡처로 검증):
- 기본적으로 클라이언트는 sender-protocol-settings 프레임으로 `zstd-8mb`/`zlib`/
  `identity` 압축 후보를 전부 선언하지만(`STREAM_ENCODERS_ORDER`), 아무것도 안 보내면
  서버는 `DEFAULT_PROTOCOL_SETTINGS = {contentencodings: [identity]}`로 항상
  identity(무압축)를 쓴다 — 그래서 hg4j 클라이언트는 이 프레임을 아예 안 보내
  압축 계층 구현을 생략할 수 있었다. 반대로 hg4j 서버는 들어오는
  sender-protocol-settings 프레임을 읽고 버린 뒤 항상 identity로만 응답한다(스펙상
  identity는 항상 유효한 선택).
- 응답 스트림은 항상 `stream-settings` 프레임 한 번(스트림 전체에 한 번, 명령마다
  X) → `{status: "ok"}` → 실제 응답 객체들(각각 독립 CBOR 값으로 이어붙임) 순서다.
- `manifestdata`/`filesdata`는 서버가 각 리비전을 전체 텍스트(`revision` 필드) 또는
  다른 노드 대비 델타(`delta`+`deltabasenode` 필드)로 자유롭게 선택해 보낼 수 있다
  (`revlog.emitrevisions` 휴리스틱) — `changesetdata`는 이 분기가 없이 항상 전체
  텍스트다. hg4j 클라이언트는 두 경우 다 처리하도록 구현(델타는 같은 배치 내에서
  이미 받은 다른 리비전의 전체 텍스트를 베이스로 재구성).
- 클라이언트가 여러 명령을 `multirequest` URL로 배치 요청할 수 있다(실제 clone 시
  `heads`+`known`이 이렇게 함께 감) — hg4j 서버도 이를 지원.

## 새로 만든 것

`transport.wireprotov2` 패키지:
- **`Wire2Frame`**: 8바이트 프레임 헤더 인코드/디코드, 프레임 타입/플래그 상수.
- **`Cbor`**: byte-string 전용 최소 CBOR 인코더/디코더(Jackson 대체). 맵 키는
  디코드 시 편의를 위해 `String`으로 변환하고, 값은 byte-string이면 `byte[]`로 남긴다
  (호출부가 필드별로 의미를 알고 있으므로 여기서 문자열/바이너리를 구분).
- **`Wire2Transport`**: capabilities 발견 응답 조립, 명령 요청/응답 프레임 조립·분해,
  `{status: ok|error}` 봉투 처리, 여러 명령의 "record + 뒤따르는 원본 바이트" 패턴
  (changesetdata/manifestdata/filesdata 공통) 디코딩.
- **`Wire2Commands`**: 서버 측 명령 구현체 — `capabilities`/`heads`/`known`/
  `listkeys`/`lookup`/`pushkey`/`branchmap`/`changesetdata`/`manifestdata`/
  `filesdata`. `changesetdata`/`filesdata`의 리비전 지정은 `changesetexplicit`(명시
  노드 목록)과 `changesetdagrange`(roots/heads 사이 조상) 둘 다 지원.

`HgRemoteClientV2`(재작성)와 `HgWireServer`(v2 부분 재작성)가 이 패키지를 사용한다.

## 구현하지 않은 것 (실사용에 지장 없다고 판단)
- `filedata`(단일 파일 변형 — `filesdata`로 대체 가능)와 `rawstorefiledata`(원시
  revlog 바이트 스트리밍, 고급/최적화 기능).
- push/unbundle에 해당하는 명령 — **실제 v2 자체에 그런 명령이 없다**(읽기 전용
  프로토콜로 남은 채 폐기됨).
- 실제 서버가 선택할 수 있는 zstd/zlib 스트림 압축 — 항상 identity로만 응답(위
  "검증 방법" 참고, 스펙상 유효한 선택).

## 알려진 구조적 한계
이 프로토콜 자체가 Mercurial 6.1부터 완전히 폐기됐다 — 아무리 정확히 구현해도 현재
실제로 배포되는 hg 서버 중 이 프로토콜을 쓰는 것은 사실상 없다. "완전 준수" 요건
충족 목적으로는 의미가 있지만 실사용 가치는 제한적이다.

## 연결 안 된 부분 (백로그)
`HgRemoteClient`(v1)의 자동 v2 업그레이드 감지 로직이 실제 v1 capabilities 응답에는
존재하지도 않는 가짜 `"http-v2"`/`"api-v2"` 플래그를 찾도록 되어 있어 절대 트리거
되지 않는다. v2를 쓰려면 `HgRemoteClientV2`를 직접 생성해야 한다. 상세는
[[mercurial-spec-compliance-requirement]]의 "남은 백로그" 참고.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거 및 최신 백로그
- [[transport]] — v1 구현 현황
