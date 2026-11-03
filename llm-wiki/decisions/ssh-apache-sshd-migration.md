---
updated: 2026-11-03
status: current
---

# 결정: SSH 구현을 jsch에서 Apache MINA SSHD로 교체

## 결정
`transport` 패키지의 SSH 구현을 jsch(`com.github.mwiede:jsch:0.2.17`)에서 Apache MINA SSHD
(`sshd-core`/`sshd-common` 2.20.1)로 **교체**했다(병행 유지 없음). 사용자 지시(2026-11-03)다. 같은 날 계획서
[[implementation-plan-gpg-ssh-wire]] Phase 1로 수행했다.

## 왜
- JGit이 SSH에 Apache MINA SSHD를 쓰므로 [[jgit-parity-requirement]]와 일치한다. 버전 2.20.1은 JGit
  `pom.xml`의 `apache-sshd-version`과 같다.
- 테스트는 이미 SSHD를 임베디드 SSH 서버로 쓰고 있었다(`testImplementation`). 클라이언트와 서버가 같은
  라이브러리가 되어 의존성이 하나로 정리됐다.
- 기존 구조에서 `HgSshClient`가 `JSchException`을 직접 참조하고 있어 `SshSession` 추상화가 새어 있었다.
  교체하면서 이를 라이브러리 독립 예외 `SshAuthenticationException`으로 바로잡았다.

## 대안과 기각 사유
- jsch 유지 + SSHD 병행: 두 구현을 계속 유지보수해야 하고 사용자가 교체를 지시했다.
- Bouncy Castle 충돌 우회: jsch 시절 `kex` 목록을 강제하던 우회 코드는 SSHD에서 필요하지 않았다(기본 KEX 설정으로
  ed25519 키 인증 테스트가 통과함. 다른 키 종류는 검증하지 않았다). 그래서 제거했다.

## 동작 보존과 변경
| 항목 | 결정 |
|---|---|
| 호스트 키 검증 | **기존 동작 유지**: `~/.ssh/known_hosts`가 있고 호스트가 `localhost`/`127.0.0.1`이 아니면 엄격 검증(미등록/키 변경은 거부), 그 외는 검증 없이 수락. 강화 여부는 별도 보안 결정으로 남김 |
| `known_hosts` 파일 권한 | SSHD의 `strict=true`는 그룹 쓰기 권한(mode 664, umask 002 기본값) 파일을 읽지 못하고 모든 서버 키를 거부한다. jsch는 권한을 검사하지 않았으므로 `strict=false`로 보존. 읽을 수 없는 파일은 여전히 연결을 거부한다. 회귀 테스트 있음 |
| 인증 실패 | `SshAuthenticationException`(IOException)으로 전파. `HgSshClient`가 `HgAuthException`으로 변환 |
| `~/.ssh/config` | 신규 지원: `HostName`, `User`, `Port`, `IdentityFile` |
| 포트/사용자 미지정 | `HgSshClient`는 URL에 포트/사용자가 없으면 0 / null로 넘김. 설정 파일이 정하고, 없으면 22 / 로컬 사용자. 명시된 값이 설정 파일보다 우선(ssh와 같은 규칙) |
| 원격 stderr | `ApacheSshdSession.getCapturedStderr()`로 최대 64KB 보관 |
| `close()` | 채널, 세션, 클라이언트를 모두 닫아 보고 첫 실패를 `IOException`으로 던진다. jsch 시절의 `catch (Exception ignored)`는 제거 |

## 삼키는 예외가 남은 곳 (정직한 기록)
`HgSshClient.close()`는 `HgRemoteConnection.close()`가 체크 예외를 선언하지 않아 `sshSession.close()`의
`IOException`을 삼킨다. 이 인터페이스 변경은 이번 범위를 벗어나서 손대지 않았다. `ensureConnected()`의 실패
경로에서 원래 예외를 가리지 않아야 한다는 이유도 있다.

## ssh-agent (구현함, 별도 커밋)
SSHD의 유닉스 소켓 agent 클라이언트(`org.apache.sshd.agent.unix.AgentClient`)는 `tomcat-apr` 네이티브
라이브러리를 요구한다(소스 확인). 추가 의존성 없이 Java 16+의 유닉스 도메인 소켓으로 `UnixSocketSshAgent`
(`AbstractAgentProxy` 상속)와 `UnixSocketSshAgentFactory`를 직접 작성했다. 프레임은 표준 agent 프로토콜(4바이트
길이 + 본문)이며 응답 길이는 256KB로 제한한다. jsch 시절에는 `setAgentForwarding(true)` 한 줄뿐이어서 실제 agent
인증은 없었으므로 **신규 기능**이다.
- 소켓은 `SSH_AUTH_SOCK`에서 읽는다. `new ApacheSshdSessionFactory(Path)`로 명시하거나 null로 끌 수 있다(테스트용).
- 한계: Windows의 OpenSSH named pipe(`\\.\pipe\openssh-ssh-agent`)는 미지원. agent 포워딩 미지원.
  `~/.ssh/config`의 `IdentityAgent`는 해석하지 않는다.
- **의도적 선택(삼키는 예외와 관련)**: 소켓이 있는데 접속하거나 신원 목록을 읽지 못하면 경고 로그를 남기고
  agent 없이 인증을 계속한다(`ssh(1)`과 같은 동작). 그렇지 않으면 오래된 `SSH_AUTH_SOCK` 하나 때문에 비밀번호
  인증까지 막힌다. 성공으로 위장하지는 않는다: 다른 방법도 없으면 `SshAuthenticationException`으로 끝난다(테스트 있음).

## jsch 대비 인증 동작이 늘어난 부분
Apache SSHD 클라이언트는 `start()` 때 기본 신원(`~/.ssh/id_*`)을 스스로 불러 시도한다(`ssh(1)`과 같음). jsch
시절에는 명시한 키만 썼다. 또 ssh-agent의 키도 시도한다. 따라서 명시 자격 증명이 틀려도 기본 키나 agent 키가 서버에
등록되어 있으면 로그인될 수 있다. 의도한 `ssh` 호환 동작이지만 jsch와 다르므로 기록한다.

## 검증
- 신규 `ApacheSshdSessionTest`(실제 임베디드 SSHD에 TCP로 접속, 목 없음): 비밀번호, 키, 암호문 키(맞음/틀림),
  인증 실패 전파, 양방향 스트리밍, stderr 수집, `known_hosts`(미등록/등록/키 변경/664 권한/로컬호스트/파일 없음),
  `~/.ssh/config`(HostName, User, Port, IdentityFile, 명시값 우선, 로컬 사용자 대체).
- 삭제된 jsch 테스트와의 대응: `JschSessionFactoryCoverageTest`(known_hosts 분기, 비밀번호, 키/암호문 분기)는
  위 `known_hosts`/키 테스트로, `JschSshSessionCoverageTest`(미연결 상태 `IllegalStateException`, 중복 connect,
  close 무예외)는 `executeBeforeConnect`/`connectTwice`/`closeWithoutConnect`/`closeTwice` 테스트로 이전.
  jsch 내부 클래스를 리플렉션으로 건드리던 `close()` 예외 삼킴 테스트는 삼킴 자체를 없앴으므로 대응 테스트가 없다.
- agent: 실제 `ssh-agent` 프로세스를 띄워 키를 등록하고(`ssh-add`) 인증 성공/키 없음/허용되지 않은 키/접속 불가 소켓
  (비밀번호로 계속, 다른 방법 없으면 실패)/신원 목록 읽기/비정상 길이 접두 거부를 확인.
- 기존 `HgSshClientTest`, `HgSshClientTransportTest`, `HgSshWireServer*` 통과.
