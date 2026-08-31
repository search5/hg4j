---
updated: 2026-08-31
status: current
---

# 모듈: transport (`com.github.search5.hg4j.transport`)

HTTP/SSH 와이어 프로토콜 구현. JGit의 `org.eclipse.jgit.transport`에 대응.

- `HgRemoteClient` / `HgRemoteConnection` / `HgRemoteConnectionFactory`: 원격 저장소와의
  프로토콜 협상 및 통신.
- `HgSshClient` / `SshSession` / `SshSessionFactory` / `JschSshSession` / `JschSessionFactory`:
  jsch 기반 SSH 구현. `SshSessionFactory`로 SSH 라이브러리를 추상화해 jsch 외 다른 구현체로
  교체 가능하게 설계됨 (git log: "SSH 라이브러리 독립적 완전 추상화 아키텍처").
- `HgLocalClient`: 로컬 파일시스템 저장소 간 클론/동기화.
- `HgWireServer`: 테스트용/임베디드 서버 측 와이어 프로토콜 구현.
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
