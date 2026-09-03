---
updated: 2026-08-31
status: current
---

# 모듈: gpg (`io.github.search5.hg4j.gpg`)

커밋 서명(GPG/OpenPGP) 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 —
JGit의 `org.eclipse.jgit.gpg.bc`에 대응.

## 클래스
- **`GpgSignature`**: Bouncy Castle PGP API로 표준 OpenPGP 호환 서명을 생성/검증하는
  순수 Java 구현. 표준 GPG 키링, `gpg --verify` 명령과 상호운용 가능하도록 설계.

## 테스트 위치 참고
`GpgSignatureTest.java`는 원래 `api` 테스트 디렉터리에 있었으나 Track A Phase 9에서
`gpg` 테스트 디렉터리로 함께 이동(대응 클래스와 위치 일치).

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/gpg/`
