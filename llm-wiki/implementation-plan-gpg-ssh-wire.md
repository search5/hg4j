---
updated: 2026-11-03
status: current (미착수. 1회성 실행 스냅샷이므로 실행이 끝나면 이 문서를 갱신하지 말고 decisions/modules를 갱신할 것)
audience: cross-agent handoff (이 대화의 맥락이 없는 외부 에이전트)
---

# hg4j 구현 계획서: GPG(.hgsigs) 호환 + SSH(Apache MINA SSHD) 교체 + 서버 와이어 명령 점검

[[implementation-plan]]의 Track A/B/C가 끝난 뒤에 추가된 별도 계획서입니다. 작성 규칙은
[[AGENTS|AGENTS.md]]의 규칙 8, 9, 10을 그대로 따릅니다. 특히 아래 두 가지를 먼저 상기하십시오.

- 추측을 사실처럼 쓰지 않습니다. 이 문서의 "미확인" 표시는 착수 전에 직접 확인하라는 뜻입니다.
- 완료 선언은 diff, 명령 출력, 실제 프로세스 응답 중 하나 이상의 증거로만 합니다.

## 0. 배경과 결정 사항

JGit의 `http.*`, `ssh.apache.*`, upload-pack/receive-pack, `gpg.bc`에 대응하는 기능을 hg4j에
갖추는 것이 목표입니다. 다만 **코드를 그대로 옮기지 않고 역할만 대응**시킵니다. 근거는 다음과 같습니다.

| JGit 구성 요소 | hg4j의 현재 상태 (2026-11-03 직접 확인) | 이 계획서의 처리 |
|---|---|---|
| `http.server` | `HgHttpWireServer`가 `jakarta.servlet.http.HttpServlet` 구현. v1과 v2 모두 처리 | 변경 없음. 서버 측 명령 완성도만 Phase 3에서 점검 |
| `ssh.apache` | `JschSessionFactory`/`JschSshSession`(jsch 0.2.17). `known_hosts`와 개인키 인증만 확인됨 | **Phase 1: Apache MINA SSHD로 교체 (사용자 결정)** |
| upload-pack/receive-pack | git 프로토콜 개념이라 대응물이 없음. `Wire1Commands`(HTTP, SSH 서버 공유)가 역할을 대신함 | Phase 3: hg 원본과 명령 목록 대조 |
| `gpg.bc` | `GpgSignature` 하나. 서명 방식이 git식(`extra`의 `gpgsig`)이라 hg와 호환되지 않음 | **Phase 2: `.hgsigs` 호환 구현** |

결정 사항은 다음과 같습니다.

1. SSH 구현은 jsch에서 Apache MINA SSHD로 **교체**합니다(병행 유지하지 않음). 사용자 지시입니다.
2. JGit처럼 모듈을 쪼개지 않습니다. `transport`, `gpg` 하위 패키지로 유지합니다(분리는 배포 요구가 생길 때).
3. GPG는 원본 `hgext/gpg.py`의 저장 형식과 의미를 따릅니다. 참고 문서는
   `hg/llm-wiki/wiki/python/ext-gpg.md`(hg 저장소 쪽 위키)입니다.

## 1. 작업 전 필수 절차

- `git status`로 현재 상태를 확인합니다. 이 문서 작성 시점의 HEAD는 `f75eb85`입니다.
- `llm-wiki/index.md`, `known-bugs-registry.md`를 먼저 읽습니다(규칙 10).
- **Phase마다 별도 커밋**으로 나눕니다. 한 커밋에 여러 Phase를 섞지 않습니다.
- 각 Phase 종료 시 검증 명령은 다음과 같고, `BUILD SUCCESSFUL` 문구를 반드시 확인합니다.
  ```bash
  ./gradlew clean test jacocoTestCoverageVerification
  ```
- 병렬 에이전트가 같은 체크아웃에서 빌드할 때는 `-PagentBuildDir=/tmp/<이름>-build`를 씁니다
  ([[test-coverage-95-percent-initiative]]에 기록된 데몬 충돌 사고를 반복하지 않기 위함).
- 실제 `hg` 바이너리와 실제 `gpg` 바이너리로 하는 interop 테스트를 우선합니다. 가짜 서버나
  더미 응답으로 대체하지 않습니다(규칙 8).

## Phase 1. SSH를 Apache MINA SSHD로 교체

### 1-1. 착수 전 확인 (모두 증거를 남길 것)

- [ ] `HgSshClient.java` 3행이 `com.jcraft.jsch.*`를 직접 import하고, 166행이 `JSchException`을
      `instanceof`로 검사합니다. 즉 `SshSession` 추상화가 새어 있습니다. `grep -rn "jcraft" src`로
      main과 test의 모든 사용처를 목록으로 만듭니다(현재 확인된 파일: main 3개 `HgSshClient`,
      `JschSshSession`, `JschSessionFactory`, test 4개 `ThrowingSession`, `JschSessionFactoryCoverageTest`,
      `JschSshSessionCoverageTest`, `HgSshClientTest`, `HgSshClientTransportTest`).
- [ ] `build.gradle`의 test 의존성에 이미 `org.apache.sshd:sshd-core:2.12.0`, `sshd-common:2.12.0`이
      있습니다(테스트용 SSH 서버). main 의존성으로 승격할 때 버전을 하나로 통일합니다. JGit 저장소의
      `jgit/pom.xml`은 `2.20.1`을 씁니다. 어느 버전을 고를지는 Java 21과의 호환성과 BouncyCastle 1.78과의
      충돌 여부를 직접 확인하고 결정한 근거를 `decisions/`에 남깁니다. (미확인)
- [ ] Apache SSHD에서 ssh-agent, `~/.ssh/config`, `known_hosts` 검증을 각각 어떤 API로 지원하는지
      해당 버전의 소스 또는 javadoc에서 확인합니다. JGit은 agent를 별도 모듈(`ssh.apache.agent`)로
      분리했으므로, SSHD 코어에 포함되는지 추가 의존성이 필요한지를 추측하지 말고 확인합니다. (미확인)
- [ ] 현재 `JschSessionFactory`의 동작을 기준선으로 기록합니다: `~/.ssh/known_hosts`가 있고 로컬호스트가
      아니면 `StrictHostKeyChecking=ask`, 그 외에는 `no`. 즉 **로컬호스트나 known_hosts 부재 시 호스트 키를
      검증하지 않습니다.** 교체 후에도 이 동작을 유지할지, 더 엄격하게 바꿀지는 보안 판단이므로 임의로
      바꾸지 말고 `decisions/`에 선택 근거를 적어 사용자에게 확인받습니다.
- [ ] 주석에 적힌 KEX 제한(`ecdh-sha2-nistp256,...`)은 jsch와 BouncyCastle의 충돌 우회책입니다.
      SSHD에는 해당 우회가 필요하지 않을 수 있으나 **실측으로 확인**한 뒤 제거 여부를 정합니다. (미확인)

### 1-2. 구현

1. `ApacheSshdSession`, `ApacheSshdSessionFactory`를 `transport` 패키지에 추가합니다. `SshSession`과
   `SshSessionFactory` 인터페이스는 유지합니다(위키의 "SSH 라이브러리 독립적 완전 추상화" 결정 유지).
2. 인터페이스가 호스트 키 검증 정책, agent 사용 여부, 설정 파일 해석을 표현하지 못하면 확장합니다.
   확장 시 기존 호출부(`HgSshClient`)와 `CredentialsProvider` 쪽 영향을 전수 조사합니다(규칙 8의
   1:N 의존성 점검).
3. `HgSshClient`의 `JSchException` 의존을 제거합니다. 인증 실패 판별을 라이브러리 독립적인 예외
   타입(예: 신규 `SshAuthenticationException`)으로 바꾸고, `errors` 패키지의 기존 예외 계층과 어떻게
   맞출지는 `modules/errors.md`를 읽고 결정합니다.
4. 기본 팩토리를 `ApacheSshdSessionFactory`로 바꿉니다(`HgSshClient`의 `sshSessionFactory` 초기값).
5. 지원 범위를 단계로 나눕니다. 각 단계는 별도 테스트를 가집니다.
   - (a) 개인키 + 암호문, 비밀번호 인증 (jsch 기능과 동일 수준 확보)
   - (b) `known_hosts` 검증
   - (c) ssh-agent
   - (d) `~/.ssh/config` 해석 (`Host`, `HostName`, `User`, `Port`, `IdentityFile`)
   1-1에서 확인한 결과 (c), (d)가 추가 의존성을 요구하면 그 의존성의 라이선스와 크기를 `decisions/`에
   적고 사용자에게 확인받은 뒤 진행합니다.
6. jsch를 제거합니다: `build.gradle`의 `com.github.mwiede:jsch` 의존성, `Jsch*` 클래스 2개, 해당
   테스트와 `ThrowingSession`. 단, 삭제하기 전에 삭제될 테스트가 검증하던 동작(예외 경로 포함)이 새
   테스트로 모두 이전되었는지 대조표를 남깁니다. 커버리지 수치를 맞추려고 테스트를 지우지 않습니다.

### 1-3. 검증

- [ ] 기존 SSH interop 테스트가 그대로 통과합니다: `HgSshClientRealHgInteropTest`,
      `HgSshWireServerRealHgInteropTest`, `HgSshUnbundleHashTest`, `HgSshUnbundleHashOffInteropTest`,
      `HgSshClientTransportTest`.
- [ ] 실제 `hg serve --stdio`를 대상으로 clone, pull, push가 SSHD 클라이언트로 성공함을 출력으로 증명합니다.
- [ ] 인증 실패(잘못된 비밀번호, 잘못된 키, 호스트 키 불일치) 각각이 **예외로 전파**됨을 테스트합니다.
      `catch`로 삼키고 성공처럼 처리하지 않습니다(규칙 8).
- [ ] `grep -rn "jcraft" src build.gradle` 결과가 비어 있습니다(출력 첨부).
- [ ] `./gradlew clean test jacocoTestCoverageVerification`이 `BUILD SUCCESSFUL`입니다.
- [ ] 산출 위키: `modules/transport.md` 갱신, `decisions/ssh-apache-sshd-migration.md` 신규.

## Phase 2. GPG: Mercurial `.hgsigs` 호환 구현

### 2-1. 원본 스펙 (hg 저장소 위키에서 확인된 사실, 구현 전에 원본 `hgext/gpg.py`로 재대조할 것)

- `.hgsigs`는 저장소 안에서 버전 관리되는 텍스트 파일이고, 한 줄이 서명 하나입니다.
  형식은 `<40자 hex 노드> <버전> <base64 서명>`이며 파싱은 공백 기준 최대 3분할입니다.
- `.hg/localsigs`는 같은 형식이고 버전 관리되지 않습니다(`hg sign --local`).
- 서명 대상 텍스트는 버전 `0`에서 `hex(node) + "\n"`입니다. 다른 버전은 중단됩니다.
- 서명 바이트는 `gpg --sign --detach-sign`의 **바이너리** detached 서명을 base64로 인코딩하고
  개행을 제거한 값입니다.
- 검증은 `.hgsigs`의 **모든 head**(필로그 head 전부)와 `localsigs`를 읽습니다.
- 검증 결과 분류: `GOODSIG`(유효), `EXPSIG`(만료 서명, 유효로 포함하고 경고), `EXPKEYSIG`(만료 키,
  유효로 포함하고 경고), `BADSIG`(제외), `ERRSIG`(알 수 없는 키, 제외).
- 원본의 알려진 결함: `hg sign --force`가 `UnboundLocalError`로 실패(`msigs` 미정의), 동일 노드의
  중복 서명이 쌓임, gpg 종료 코드를 확인하지 않음, 신뢰도(`TRUST_*`)를 보지 않음.
  **이 결함들을 그대로 복제하지 않습니다.** 어떤 동작을 선택했는지 `decisions/`에 기록합니다.

### 2-2. 설계 결정 (착수 전에 사용자 확인이 필요한 항목)

1. **기존 `gpgsig` extra 방식의 처리 (사용자 동의 완료, 2026-11-03)**: `CommitCommand.setGpgSigner()`가
   만드는 `extra["gpgsig"]`는 Mercurial 표준이 아닌 hg4j 고유 확장입니다. **삭제하지 않고** javadoc에
   비표준 확장임을 명시합니다. 삭제하면 이미 이 방식으로 서명한 저장소를 검증할 수 없게 되기 때문입니다.
2. **키링 읽기 (사용자 결정, 2026-11-03: 시스템 키링 자동 탐색을 범위에 포함)**:
   - 기본 경로는 호출자가 `PGPPublicKeyRingCollection` 또는 `InputStream`으로 공개키를 넘기는 방식입니다.
   - 추가로 **시스템 GnuPG 키링 자동 탐색**을 구현합니다. 탐색 위치는 `GNUPGHOME` 환경변수, 없으면
     `~/.gnupg`입니다.
   - 키링 형식별 지원 가능 여부는 **추측하지 말고 실측**합니다. 아래 세 가지를 각각 시험하고 결과를
     `decisions/gpg-hgsigs-compatibility.md`에 표로 남깁니다. (모두 미확인)
     - 레거시 `pubring.gpg`: BC 1.78로 읽히는지 (hg 저장소의 `tests/gpg/pubring.gpg`로 시험)
     - GnuPG 2.1 이상의 `pubring.kbx`: BC가 직접 읽는지. 읽지 못하면 아래 대안 중 하나를 선택합니다.
     - 대안 A: `pubring.kbx`를 직접 파싱하는 코드를 작성 (형식 스펙은 GnuPG 문서에서 바이트 단위로 검증, 규칙 8)
     - 대안 B: 외부 `gpg --export` 실행 결과를 BC로 읽는 위임 모드 (외부 실행 파일이 있을 때만 동작)
   - 자동 탐색은 실패 시 **조용히 빈 키링으로 대체하지 않습니다.** 못 읽은 이유(파일 없음, 형식 미지원, 외부
     `gpg` 부재)를 예외 또는 결과 객체로 호출자에게 전달합니다(규칙 8, 삼키기 금지).
   - 개인키(서명용)도 같은 방식으로 찾을 수 있는지 함께 시험합니다. `private-keys-v1.d`는 `gpg-agent`가
     관리하는 형식이라 BC로 직접 읽지 못할 가능성이 있으므로, 안 되면 서명은 호출자가 넘긴 개인키만 지원하고
     그 한계를 위키에 명시합니다. (미확인)
3. **API 위치**: 기존 `GpgSignature`(프리미티브)는 유지하고, 그 위에 hg 형식을 다루는 클래스를
   `gpg` 패키지에 추가합니다.

### 2-3. 구현

1. `gpg` 패키지에 추가:
   - `HgSigEntry`: `(nodeHex, version, signatureBytes, source)` 값 객체 (`source`는 `.hgsigs`의 head 노드
     또는 `localsigs`).
   - `HgSigsFile`: 파싱과 직렬화. 필드가 3개 미만인 줄은 원본처럼 포착되지 않은 `ValueError`를 내지 말고
     명확한 예외로 전파합니다(삼키지 않음).
   - `HgSigner`: 노드에서 서명 대상 텍스트(`hex + "\n"`) 생성, detached 바이너리 서명 생성, base64 직렬화.
   - `HgSigVerifier`: 서명 검증 결과를 `GOOD`, `EXPIRED_SIG`, `EXPIRED_KEY`, `BAD`, `UNKNOWN_KEY`로 분류.
2. `api` 패키지에 `SignCommand`, `SigsCommand`, `SigCheckCommand`를 추가하고 `Hg` 파사드에 노출합니다.
   - `SignCommand`: 대상 노드 결정(인자 없으면 워킹 디렉터리 부모, 병합 중이면 중단), `.hgsigs`에 줄
     추가 후 `.hgsigs`만 커밋(`--no-commit` 지원), `--local` 지원. 커밋 메시지는 원본 형식
     `Added signature for changeset <short>`를 따릅니다.
   - 기존 `CommitCommand`와 락, 저널 규약을 지킵니다(규칙 8의 복구와 정리 최우선 원칙).
3. 모든 head 읽기는 `.hgsigs` 필로그의 head 전체를 순회해야 합니다. 기존 `Revlog` API로 가능한지
   확인하고, 불가능하면 필요한 최소 API만 추가합니다.

### 2-4. 검증

- [ ] **상호운용(필수)**: 실제 `hg`와 실제 `gpg`로 만든 `.hgsigs`를 hg4j가 검증하고, hg4j가 만든
      `.hgsigs`를 실제 `hg sigcheck`가 유효하다고 판정합니다. 두 방향의 명령 출력을 첨부합니다.
- [ ] 테스트 키링으로 hg 저장소의 `tests/gpg/{pubring.gpg,secring.gpg,trustdb.gpg}`를 재사용합니다
      (원본 테스트 `tests/test-gpg.t`가 쓰는 픽스처, 키 사용자명 `hgtest`). 이 파일들이 BC 1.78로
      읽히는지 먼저 확인합니다. (미확인)
- [ ] 시스템 키링 자동 탐색: `GNUPGHOME`을 임시 디렉터리로 지정한 실제 gpg로 키를 생성한 뒤, hg4j가 그 키링에서
      공개키를 찾아 서명을 검증함을 출력으로 증명합니다. 키링 파일이 없을 때와 형식을 읽지 못할 때 예외가
      전파됨도 각각 테스트합니다.
- [ ] 만료 키, 만료 서명, 잘못된 서명, 알 수 없는 키 각각의 분류를 실제 gpg가 만든 데이터로 테스트합니다.
      가짜 서명 바이트로 분류를 꾸미지 않습니다.
- [ ] 산출 위키: `modules/gpg.md` 갱신, `decisions/gpg-hgsigs-compatibility.md` 신규. 위 2-1의 원본 결함 각각에
      대해 hg4j가 취한 동작을 표로 남깁니다.

## Phase 3. 서버 와이어 명령 점검 (upload-pack/receive-pack 대응 계층)

### 3-1. 현재 확인된 사실

- `Wire1Commands.dispatch`가 처리하는 명령: `capabilities`, `hello`, `heads`, `between`, `known`, `lookup`,
  `listkeys`, `pushkey`, `branchmap`, `changegroup`, `changegroupsubset`, `getbundle`, `clonebundles`.
  `unbundle`은 `Wire1Commands.unbundle(...)`로 별도 처리되고, `HgSshWireServer`의 `ARG_SPECS`에
  `unbundle`, `batch`가 따로 등록되어 있습니다.
- `HgSshWireServer`는 순수 `InputStream`/`OutputStream` 위에서 동작하므로 SSH 라이브러리 교체
  (Phase 1)의 영향을 받지 않습니다. 서버 코드에서 `jcraft`를 쓰지 않음도 확인했습니다.

### 3-2. 작업

1. 원본 `mercurial/wireprotov1server.py`의 명령 등록부와 `hg4j`의 처리 목록을 대조해서 **누락 목록**을
   만듭니다. 이 문서에서 누락 명령을 단정하지 않습니다. 대조 결과를 근거(원본 줄 번호)와 함께
   `backlog/wire-server-command-gaps.md`에 표로 남깁니다. `stream_out`(streaming clone), `branches`,
   `debugwireargs`, `protocaps`가 후보이지만 **확인 전에는 후보일 뿐**입니다.
2. 같은 방식으로 v2(`Wire2Commands`)와 `wireprotov2server.py`를 대조합니다. 기존
   [[wireprotocol-v2-support-plan]]과 중복되는 항목은 그 문서를 갱신하고 이 문서에서 참조만 합니다.
3. 누락 목록에서 구현 대상을 고른 뒤, 사용자에게 우선순위를 확인받고 구현합니다. 구현 시 HTTP와 SSH
   양쪽에서 같은 `Wire1Commands`를 거치는지 확인합니다.
4. 인증과 접근 제어: 서버 측 push 허용 여부, 훅(`preChangegroupHooks`/`postChangegroupHooks`)이 SSH와 HTTP에서
   동일하게 동작하는지 확인합니다. 두 경로의 차이를 발견하면 `known-bugs-registry.md`에 기록합니다.

### 3-3. 검증

- [ ] 구현한 명령마다 실제 `hg` 클라이언트가 hg4j 서버에 붙어서 해당 명령을 실행하고 성공하는 interop 테스트
      (기존 `HgHttpWireServerRealHgInteropTest`, `HgSshWireServerRealHgInteropTest` 패턴).
- [ ] 반대로 hg4j 클라이언트가 실제 `hg serve`에 붙는 경우도 유지됩니다.
- [ ] `matrix-status.md`와 `known-bugs-registry.md`를 갱신합니다.

## Phase 4. 위키 정리 (마지막)

- [ ] `index.md`에 새 페이지 항목을 추가했는지 확인합니다.
- [ ] 이 문서의 모든 체크박스가 증거와 함께 닫혔는지 확인하고, 실행이 끝났으면 이 문서는 더 이상 갱신하지
      않습니다(`decisions/`와 `modules/`가 1차 저장소).
- [ ] `log.md`에 Phase별 요약, 커밋 해시, 최종 테스트 수치(실행 출력)를 기록합니다.

## 의도적으로 범위에서 제외한 것

- 모듈 분리(JGit식 `http.server`, `ssh.apache`, `gpg.bc` 별도 모듈): 배포 요구가 생길 때 [[core-package-split-plan]]
  방식으로 진행.
- `gpg-agent` 연동(소켓 프로토콜로 개인키 연산을 위임): 이 계획서에서는 다루지 않음. 시스템 키링 자동 탐색은 범위에 포함됨(2-2 결정 2).
- 기존 `gpgsig` extra 방식의 제거: 처음에는 하지 않기로 했으나(2-2 결정 1) 2026-11-03 사용자가 필요 없다고 확정해 **제거함**([[gpg-hgsigs-compatibility]]).
