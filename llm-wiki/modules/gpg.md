---
updated: 2026-11-03
status: current
---

# 모듈: gpg (`io.github.search5.hg4j.gpg`)

OpenPGP 서명 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 — JGit의
`org.eclipse.jgit.gpg.bc`에 대응. 2026-11-03에 Mercurial `.hgsigs` 호환 계층이 추가되었다
(상세와 근거는 [[gpg-hgsigs-compatibility]]).

## 서명 모델
Mercurial 표준인 `.hgsigs`/`.hg/localsigs` 방식만 지원한다. 커밋 뒤에 `hg sign`에 해당하는 `SignCommand`로 서명하며, 서명 대상은
`hex(node) + "\n"`이고 실제 `hg sigcheck`가 인식한다(실제 hg로 양방향 검증).
git식(커밋 자신의 `extra["gpgsig"]`)은 hg가 해석하지 않는 비표준이라 **전부 제거했다**(2026-11-03, 사용자 결정):
`GpgSignature`, `CommitCommand#setGpgSigner`, `HgCommit#getGpgSignature` 등. 근거는 [[gpg-hgsigs-compatibility]].

## 클래스
- `HgSigEntry`, `HgSigsFile`: `.hgsigs`/`localsigs`의 한 줄(`<40hex> <버전> <base64>`) 모델과 파서/직렬화.
  형식 오류는 건너뛰지 않고 `HgSigFormatException`(파일명:줄번호 포함).
- `GpgKeyring`: 공개/비밀키 모음. 직접 넘기거나(`fromPublicKeys`, `withSecretKeys`) GnuPG 홈에서 읽는다
  (`fromGnupgHome`, `fromDefaultHome`: `$GNUPGHOME` 또는 `~/.gnupg`). 읽지 못한 이유는 `getNotes()`로 알린다.
- `KeyboxReader`(패키지 비공개): GnuPG 2.1+ `pubring.kbx` 직접 파서. Bouncy Castle은 이 형식을 오류 없이
  **빈 키링**으로 읽어 모든 서명이 "알 수 없는 키"가 되므로 필수다.
- `HgSigner`: `gpg --sign --detach-sign`과 같은 바이너리 분리 서명(v4, 유형 0x00) 생성.
- `HgSigVerifier`, `HgSigResult`: 검증과 분류(`GOOD`, `EXPIRED_SIGNATURE`, `EXPIRED_KEY`, `REVOKED_KEY`,
  `BAD`, `UNKNOWN_KEY`). 신뢰도(trust)는 원본처럼 평가하지 않는다.

**api 패키지의 명령**: `SignCommand`(hg sign), `SigsCommand`(hg sigs), `SigCheckCommand`(hg sigcheck),
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
- `HgSigsGpgInteropTest`, `SignCommandsInteropTest` (`@Tag("interop")`): 실제 `gpg`와 실제 `hg`(gpg 확장)로
  양방향 검증. 임시 `GNUPGHOME`을 쓰며 `GpgHome`(테스트 지원)이 종료 시 gpg-agent를 정리한다.

## 코드 위치
`src/main/java/io/github/search5/hg4j/gpg/`, `src/main/java/io/github/search5/hg4j/api/Sign*.java`·`Sig*.java`
