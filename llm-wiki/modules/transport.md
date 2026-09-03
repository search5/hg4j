---
updated: 2026-09-01
status: current
---

# 모듈: transport (`io.github.search5.hg4j.transport`)

HTTP/SSH 와이어 프로토콜 구현. JGit의 `org.eclipse.jgit.transport`에 대응.

- `HgRemoteClient` / `HgRemoteConnection` / `HgRemoteConnectionFactory`: 원격 저장소와의
  wire protocol v1 프로토콜 협상 및 통신. ⚠️ `HgRemoteClient`의 v1→v2 자동 업그레이드
  감지는 실제 v1 capabilities에 없는 가짜 `"http-v2"` 플래그를 찾도록 되어 있어 트리거되지
  않는다(백로그, [[mercurial-spec-compliance-requirement]] 참고) — v2를 쓰려면
  `HgRemoteClientV2`를 직접 생성해야 한다.
- `HgRemoteClientV2`: wire protocol v2 클라이언트 — 실제 Mercurial 6.0 서버로 양방향
  검증 완료. `transport.wireprotov2` 서브패키지(`Wire2Frame`/`Cbor`/`Wire2Transport`/
  `Wire2Commands`) 위에서 동작. 상세: [[wireprotocol-v2-support-plan]].
- `HgSshClient` / `SshSession` / `SshSessionFactory` / `JschSshSession` / `JschSessionFactory`:
  jsch 기반 SSH 구현. `SshSessionFactory`로 SSH 라이브러리를 추상화해 jsch 외 다른 구현체로
  교체 가능하게 설계됨 (git log: "SSH 라이브러리 독립적 완전 추상화 아키텍처").
- `HgLocalClient`: 로컬 파일시스템 저장소 간 클론/동기화.
- `HgWireServer`: 서버 측 와이어 프로토콜 구현(v1 + v2). v2 부분은 `Wire2Commands`에
  위임. 아직 실제 소켓 위에서 실행되는 내장 HTTP 서버(예: `com.sun.net.httpserver.
  HttpServer` 와이어링)는 라이브러리 코드에 없다 — 테스트에서만 직접 구성한다
  (`HgHttpTransportV2RoundtripTest` 참고).
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
`src/main/java/com/github/search5/hg4j/transport/`
