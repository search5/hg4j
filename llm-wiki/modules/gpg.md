---
updated: 2026-11-03
status: current
---

# 모듈: gpg (`io.github.search5.hg4j.gpg`)

OpenPGP 서명 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 — JGit의
`org.eclipse.jgit.gpg.bc`에 대응. 2026-11-03에 Mercurial `.hgsigs` 호환 계층이 추가되었다
(상세와 근거는 [[gpg-hgsigs-compatibility]]).

## 어느 쪽을 쓰는가 (목적에 따라)
**목적이 "로컬에서 커밋할 때 gpg로 서명하고, 원격에서 서명됨을 보여 주기"이면 커밋 시점 서명(`gpgsig`)을 쓴다.**
Mercurial에는 커밋하는 순간 서명하는 기능이 없고, `.hgsigs`는 이미 만들어진 변경셋에 별도 커밋으로 서명하는
방식이라 서명마다 커밋이 하나 더 생기며 일반 Mercurial 서버/웹에는 "검증됨"을 보여 주는 곳이 없다.

| | 커밋 시점 서명 (`gpgsig`) | Mercurial `.hgsigs` |
|---|---|---|
| 저장 위치 | 커밋 자신의 `extra["gpgsig"]` | 별도 파일 `.hgsigs` (버전 관리) 또는 `.hg/localsigs` |
| 서명 대상 | 변경셋 원문(`gpgsig` 항목만 제외) | `hex(node) + "\n"` (이미 존재하는 변경셋) |
| 커밋 수 | 1개 | 서명마다 1개 더 |
| 로컬 서명 API | `CommitCommand#setGpgSigner(GpgKeyring, spec, passphrase)` | `SignCommand` |
| 검증 API | `VerifyCommitCommand` | `SigCheckCommand`, `SigsCommand` |
| `hg sigcheck` 인식 | 못 함(일반 hg는 `extra`를 보존만 함) | 함 |
| 서버 표시 | 서버가 `extra`의 `gpgsig`를 읽어 검증하면 가능 | 일반 서버에는 없음 |

둘은 서로 독립이다. 일반 Mercurial 도구와 호환이 필요할 때만 `.hgsigs`를 쓴다.

## 커밋 시점 서명 (주 경로)
- **서명**: `new CommitCommand(repo).setGpgSigner(keyring, keySpec, passphrase)`. 사용자의 GnuPG 키(`GpgKeyring.fromDefaultHome()`
  + `withSecretKeys(...)`)로 `gpg --detach-sign --armor`와 같은 서명을 만들어 `gpgsig` extra에 넣고, 주 키 지문을 `gpgfingerprint`에
  적는다. 키는 커밋 시작 전에 찾으므로 잘못된 지정은 커밋 전에 실패하고, 틀린 passphrase는 커밋을 만들지 않는다.
- **검증**: `VerifyCommitCommand`가 변경셋의 `gpgsig`를 키링으로 검증한다. 서명된 바이트는 `HgCommit#getUnsignedChangelogText()`
  (원문에서 `gpgsig`만 뺀 것)이고 서명은 `HgCommit#getGpgSignature()`이다. 서버는 이 둘과 등록된 공개키만 있으면 직접 검증해
  "서명됨"을 표시할 수 있다(yona의 `GpgSignatureVerifier.kt`가 git 커밋에 대해 하는 일과 같은 모양).
- **옮겨 가도 유지됨**: `extra`는 변경셋의 일부라 `hg clone`/`push`/`pull` 뒤에도 노드 해시와 서명이 그대로다(실제 `hg clone`으로 확인).
- **신뢰 판단은 호출자 몫**: "유효"는 키링의 그 키가 서명했다는 뜻일 뿐이다. 그 키가 커밋터의 것인지(사용자 id의 이메일 대조 등)는
  호출자가 정한다(GitHub의 verified 배지와 같은 구조).

## 클래스
**커밋 시점 서명 (`gpgsig`)**
- `GpgSignature`: Bouncy Castle로 OpenPGP 서명 생성/검증하는 값 객체(자바 `PrivateKey`를 받으므로 사용자의 gpg 키링에서는 쓰기 어려워 `GpgKeyring` 경로가 추가됨).

**Mercurial 호환 (신규)**
- `HgSigEntry`, `HgSigsFile`: `.hgsigs`/`localsigs`의 한 줄(`<40hex> <버전> <base64>`) 모델과 파서/직렬화.
  형식 오류는 건너뛰지 않고 `HgSigFormatException`(파일명:줄번호 포함).
- `GpgKeyring`: 공개/비밀키 모음. 직접 넘기거나(`fromPublicKeys`, `withSecretKeys`) GnuPG 홈에서 읽는다
  (`fromGnupgHome`, `fromDefaultHome`: `$GNUPGHOME` 또는 `~/.gnupg`). 읽지 못한 이유는 `getNotes()`로 알린다.
- `KeyboxReader`(패키지 비공개): GnuPG 2.1+ `pubring.kbx` 직접 파서. Bouncy Castle은 이 형식을 오류 없이
  **빈 키링**으로 읽어 모든 서명이 "알 수 없는 키"가 되므로 필수다.
- `HgSigner`: `gpg --sign --detach-sign`과 같은 바이너리 분리 서명(v4, 유형 0x00) 생성. `signDetached`(임의 데이터), `signDetachedArmored`(ASCII armor, LF), `sign`(`.hgsigs` 줄).
- `HgSigVerifier`, `HgSigResult`: 검증(`verify`: `.hgsigs` 줄, `verifyDetached`: 임의 데이터, 바이너리/armor 모두)과 분류(`GOOD`, `EXPIRED_SIGNATURE`, `EXPIRED_KEY`, `REVOKED_KEY`,
  `BAD`, `UNKNOWN_KEY`). 신뢰도(trust)는 원본처럼 평가하지 않는다.

**api 패키지의 명령**: `VerifyCommitCommand`(커밋의 gpgsig 검증, `Hg#verifyCommit()`), `SignCommand`(hg sign), `SigsCommand`(hg sigs), `SigCheckCommand`(hg sigcheck),
`Hg#sign()`/`sigs()`/`sigcheck()`. 공용 읽기 `SignatureStore`는 `.hgsigs` 파일로그의 **모든 head**를 읽은 뒤
`localsigs`를 읽는다(원본 `sigwalk`와 동일).

## 알려진 한계
- GnuPG 2.1+의 비밀키(`private-keys-v1.d/`, gpg-agent 형식)는 읽지 못한다. 서명하려면
  `gpg --export-secret-keys` 결과를 `GpgKeyring#withSecretKeys`로 넘긴다. 레거시 `secring.gpg`는 읽는다.
- `SignCommand`는 `.hgsigs`만 골라 커밋할 수 없다(`CommitCommand`에 경로 필터가 없음). 다른 파일에
  커밋되지 않은 변경이 있으면 커밋을 거부한다(`setCommit(false)`로 우회).
- 커밋이 중간에 실패했을 때 `.hgsigs`를 되돌리는 경로는 테스트하지 못했다(실패를 안정적으로 유발할 방법이 없음).
- 업무상 신뢰도 정책(어떤 키를 믿을지)은 다루지 않는다.

## 테스트
- `HgSigsFileTest`, `KeyboxReaderTest`: 형식과 손상 가드 (일반 `test`).
- `CommitSigningInteropTest` (`@Tag("interop")`): 커밋 시점 서명. 실제 `gpg --verify`가 저장된 서명과 바이트를 인정하는지, 실제 `hg`가 서명된 저장소를 읽고 `clone` 뒤에도 서명이 유지되는지.
- `HgSigsGpgInteropTest`, `SignCommandsInteropTest` (`@Tag("interop")`): 실제 `gpg`와 실제 `hg`(gpg 확장)로
  양방향 검증. 임시 `GNUPGHOME`을 쓰며 `GpgHome`(테스트 지원)이 종료 시 gpg-agent를 정리한다.
- `GpgSignatureTest`, `GpgSignatureCoverageTest`: 기존 git식 클래스.

## 코드 위치
`src/main/java/io/github/search5/hg4j/gpg/`, `src/main/java/io/github/search5/hg4j/api/Sign*.java`·`Sig*.java`
