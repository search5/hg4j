---
updated: 2026-09-04
status: current
---

# 모듈: transport (`io.github.search5.hg4j.transport`)

HTTP/SSH 와이어 프로토콜 구현. JGit의 `org.eclipse.jgit.transport`에 대응.

- `HgRemoteClient` / `HgRemoteConnection` / `HgRemoteConnectionFactory`: 원격 저장소와의
  wire protocol v1 프로토콜 협상 및 통신. v1→v2 자동 업그레이드는 백로그 항목으로 수정
  완료됨 — 예전에는 실제 v1 capabilities에 없는 가짜 `"http-v2"` 플래그를 찾도록 되어 있어
  트리거되지 않았으나, 지금은 real hg의 실제 업그레이드 핸드셰이크(`?cmd=capabilities`
  요청에 `X-HgUpgrade-1`/`X-HgProto-1` 헤더를 실어 보내고, 서버가 v2를 지원하면 plain-text
  대신 CBOR `{apibase, apis: {...}, v1capabilities}` 디스크립터로 응답)를 그대로 구현한다
  (`HgRemoteClient#tryEstablishV2FromDiscoveryResponse`). 업그레이드에 성공하면 내부적으로
  `HgRemoteClientV2` 델리게이트를 생성해 이후 호출을 위임한다 — v1-only 서버는 미인식 헤더를
  무시하고 평문 응답을 그대로 돌려주므로 CBOR 디코딩이 실패하고 정상적으로 v1 경로로 폴백한다.
  `HgRemoteClientV2`를 직접 생성할 필요는 더 이상 없다(단, 여전히 가능).
- `HgRemoteClientV2`: wire protocol v2 클라이언트 — 실제 Mercurial 6.0 서버로 양방향
  검증 완료. `transport.wireprotov2` 서브패키지(`Wire2Frame`/`Cbor`/`Wire2Transport`/
  `Wire2Commands`) 위에서 동작. 상세: [[wireprotocol-v2-support-plan]].
- `HgSshClient` / `SshSession` / `SshSessionFactory` / `JschSshSession` / `JschSessionFactory`:
  jsch 기반 SSH 구현. `SshSessionFactory`로 SSH 라이브러리를 추상화해 jsch 외 다른 구현체로
  교체 가능하게 설계됨 (git log: "SSH 라이브러리 독립적 완전 추상화 아키텍처").
- `HgLocalClient`: 로컬 파일시스템 저장소 간 클론/동기화.
- `HgHttpWireServer`: 서버 측 HTTP 와이어 프로토콜(v1 `?cmd=` GET/POST + v2 `/api/<namespace>/
  <ro|rw>/<command>` 프레이밍) 구현체. JDK 내장 `com.sun.net.httpserver.HttpHandler`를 직접
  구현하므로 새 의존성 없이 `com.sun.net.httpserver.HttpServer`에 바로 꽂을 수 있는 실제
  프로덕션 서버 클래스다(테스트 전용 하네스가 아님). 요청마다 `repository.refreshIfChangedOnDisk()`
  를 먼저 호출해 장기 실행 서버 프로세스가 디스크상의 변경(다른 프로세스의 커밋/스트립 등)을
  놓치고 오래된 캐시를 서빙하는 문제를 막는다. v1 응답 종류별 압축 규칙(real hg 기준: `BYTES`/
  `OOB_ERROR`/`STREAM_UNCOMPRESSED`는 비압축, `STREAM`만 zlib deflate)과 pushkey 등의
  `httppostargs` POST 처리, `unbundle` 시 pre/post-changegroup 훅 실행까지 포함한다.
  v2 부분은 `Wire2Commands`/`Wire2Transport`에 위임.
- `HgSshWireServer`: 서버 측 SSH 와이어 프로토콜(v1 line-based, `mercurial/wireprotoserver.py`의
  `sshv1protocolhandler` 프레이밍 검증 완료) 구현체. `HgHttpWireServer`와 동일한
  `Wire1Commands` 코어를 공유하지만, 특정 SSH 채널 구현에 묶이지 않도록 순수 `InputStream`/
  `OutputStream` 쌍 위에서만 동작한다(JGit도 자체 SSH 서버를 내장하지 않는 것과 같은 이유).
- `CredentialsProvider` / `UsernamePasswordCredentialsProvider` / `SshKeyCredentialsProvider` /
  `CredentialItem`: 인증 정보 추상화.
- `TransportProtocol`: 프로토콜 종류(HTTP/SSH/local) 열거.

## JGit 대응 관계
| hg4j | JGit 대응 개념 |
|---|---|
| `HgRemoteClient`/`HgRemoteConnection` | `Transport`/`TransportHttp` |
| `HgSshClient`/`SshSessionFactory` | `SshTransport`/`SshSessionFactory` |
| `CredentialsProvider` | `org.eclipse.jgit.transport.CredentialsProvider` (동일 이름) |
| `CredentialItem` | `org.eclipse.jgit.transport.CredentialItem` (동일 이름) |

→ 패키지 이름 자체는 이미 JGit과 일치. 클래스명은 `Hg` 접두어가 붙어 구분되나, 역할별
1:1 대응은 유지되고 있음. 자세한 명명 규칙 요건은 [[jgit-parity-requirement]] 참고.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/transport/`
