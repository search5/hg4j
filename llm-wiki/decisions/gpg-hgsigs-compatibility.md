---
updated: 2026-11-03
status: current
---

# 결정: GPG 서명을 Mercurial `.hgsigs` 방식과 호환되게 구현

> **방향 정정 (2026-11-03, 같은 날 뒤에)**: 이 문서와 [[implementation-plan-gpg-ssh-wire]] Phase 2는 "일반 Mercurial의
> `hg sigcheck`와 호환"이 목적이라는 **제 전제**로 `.hgsigs`를 구현했다. 사용자가 밝힌 실제 목적은 "로컬에서 커밋할 때 gpg로
> 서명하고, 원격 저장소에서 서명됨을 보여 주는 것"이다. 이 목적에는 커밋 시점 서명(`gpgsig` extra)이 맞는다(커밋 1개, 서버가
> 직접 검증 가능). 그래서 같은 키링/서명/검증 코드를 재사용해 `CommitCommand#setGpgSigner(GpgKeyring, ...)`와
> `VerifyCommitCommand`를 추가했고, `.hgsigs`는 일반 Mercurial 호환이 필요할 때를 위한 부가 기능으로 둔다.
> 서명이 무엇을 위한 것인지 계획 단계에서 묻지 않은 것이 원인이다.

## 결정
[[implementation-plan-gpg-ssh-wire]] Phase 2. Mercurial의 `gpg` 확장(`hgext/gpg.py`)이 정의한 저장 형식과
의미를 그대로 따르는 서명 생성/검증/명령을 순수 Java(Bouncy Castle)로 구현했다. 기존 git식 `gpgsig` extra
방식은 **삭제하지 않고** 비표준임을 javadoc에 명시했다(사용자 동의, 2026-11-03: 이미 그렇게 서명된 저장소를
검증할 수 없게 되는 것을 피하기 위함). 시스템 GnuPG 키링 자동 탐색은 범위에 포함했다(사용자 결정).

## 원본 형식 (원본 소스 `hgext/gpg.py`로 확인)
- `.hgsigs`: 줄마다 `<40자 hex 노드> <버전> <base64 서명>`. 버전 0만 정의됨. 다른 버전은 원본도 중단.
- 서명 대상 텍스트(버전 0): `hex(node) + "\n"`.
- 서명 바이트: `gpg --sign --detach-sign`의 **바이너리** 분리 서명을 base64 한 줄로.
- 읽기: `.hgsigs` 파일로그의 **모든 head**(`reversed(fl.heads())`) 다음에 `.hg/localsigs`. 빈 줄은 건너뛰고 줄 번호에 세지 않음.
- 분류: `GOODSIG` 유효, `EXPSIG`(서명 만료)와 `EXPKEYSIG`(키 만료)는 경고를 붙여 유효로 포함, `BADSIG`와 `ERRSIG`는 제외.

## 키링 실측 결과 (Bouncy Castle 1.78, gpg 2.4.8, 2026-11-03)
| 대상 | 결과 |
|---|---|
| 레거시 `pubring.gpg` (hg 저장소 `tests/gpg/`) | 정상: 키 1개, 사용자 `hgtest` |
| 레거시 `secring.gpg` | 정상: 비밀키 1개(암호화 없음) |
| GnuPG 2.1+ `pubring.kbx` | **오류 없이 키 0개**. 그대로 쓰면 모든 서명이 "알 수 없는 키"로 오판 |
| `private-keys-v1.d/` (gpg-agent 비밀키) | 읽지 않음(기능 한계로 기록) |

### `pubring.kbx` 구조 (실제 gpg 2.4.8 파일의 바이트로 확인)
blob 열: `u32 길이(자신 포함) | u8 유형`. 유형 1(헤더): 오프셋 5 버전, 6 플래그, **8에 `KBXf`**. 유형 2(OpenPGP):
오프셋 8에 키블록 오프셋(u32, blob 시작 기준), 12에 키블록 길이(u32). 키블록은 평범한 OpenPGP 패킷열이고 첫 바이트가
`0x99`/`0x98`(공개키 패킷, tag 6)이다. 유형 0과 3(X.509)은 건너뛴다. 이 레이아웃으로 잘라낸 키블록을 BC로 읽은
지문이 `gpg --list-keys --with-colons`의 지문과 일치함을 테스트가 확인한다(RSA와 Ed25519). **이 레이아웃은 gpg 2.4.8로
만든 파일(블롭 버전 1)에서만 확인했다.** blob별 체크섬과 키 정보 표는 읽지 않는다.

### 키링을 못 읽을 때의 처리
조용히 빈 키링으로 바꾸지 않는다: 존재하지 않는 홈, 키링이 없는 홈, 손상된 키박스, 키가 하나도 없는 비어 있지 않은 입력은
`IOException`(`HgSigFormatException`)이다. 키박스를 OpenPGP 키 파일로 잘못 넘기면 그 사실을 알려 주는 메시지가 나간다.
읽지 못하는 비밀키 형식은 `GpgKeyring#getNotes()`로 알린다.

## 원본과 의도적으로 다른 점
| 항목 | 원본 | hg4j |
|---|---|---|
| `hg sign --force` | `UnboundLocalError`로 크래시 (`msigs` 미정의, 실제로 재현함) | 동작함 |
| 사전 조건 검사 | `.hgsigs`에 먼저 쓴 뒤 중단할 수 있어 수정된 채 남음 | 파일을 건드리기 전에 모두 검사 |
| 형식이 깨진 줄 | `ValueError`로 크래시 또는 쓰레기 수용 | 파일명:줄번호가 있는 예외 |
| gpg 종료 코드 | 확인하지 않음(출력 상태 줄만 봄) | 해당 없음(외부 프로세스를 쓰지 않음) |
| 신뢰도(`TRUST_*`) | 보지 않음 | 보지 않음(원본과 같음) |
| 폐기된 키 | `REVKEYSIG`가 무시되어 결과적으로 무효 | `REVOKED_KEY`로 명시하고 무효 |
| 서명 만료/키 만료 | 경고와 함께 유효로 포함 | 같음 |
| 같은 노드에 여러 번 서명 | 줄이 쌓임 | 같음(서명이 다르면 서로 다른 줄) |

## 알려진 한계 (정직한 기록)
1. **`SignCommand`의 커밋 범위**: 원본은 `match=.hgsigs`로 그 파일만 커밋하지만 hg4j의 `CommitCommand`에는 경로 필터가 없다.
   그래서 다른 추적 파일에 커밋되지 않은 변경이 있으면 커밋을 거부한다(우회: `setCommit(false)`). 경로 필터는 2000줄이 넘는
   `CommitCommand`의 변경이라 이번 범위에서 제외했다.
2. **커밋 실패 시 `.hgsigs` 복원 경로는 미검증**: 구현했지만 실패를 안정적으로 유발할 수 없어 테스트하지 못했다.
3. **업데이트 중 병합 상태 서명 거부**: `uncommitted merge - please provide a specific revision` 경로는 구현했으나 테스트하지 않았다.
4. 비밀키 자동 탐색은 레거시 `secring.gpg`만 가능하다.

## 결정론적 서명이 낳는 함정 (테스트에서 발견)
Ed25519와 RSA(PKCS#1 v1.5) 서명은 결정적이다. 같은 키, 같은 텍스트, 같은 생성 시각(초 단위)이면 서명 바이트가 완전히
같다. 같은 초 안에 갈라진 두 가지에서 서명하면 `.hgsigs` 내용이 같아 파일로그 노드가 같고, hg도 새 리비전을 만들지 않으므로
head는 2개가 아니라 1개가 된다. 제품 결함이 아니며, 두 head를 만드는 테스트는 서명 시각을 다르게 지정한다.

## 검증
- 실제 `gpg`가 만든 서명을 hg4j가 `GOOD`으로, hg4j가 만든 서명을 실제 `gpg --verify`가 `GOODSIG`+`VALIDSIG`로 인정 (RSA와 Ed25519).
- hg4j가 만든 `.hgsigs`를 실제 `hg sigcheck`가 서명자와 함께 인정, 망가뜨린 서명은 `no valid signature`로 거부.
- 실제 `hg sign`이 만든 `.hgsigs`를 hg4j가 읽고 검증.
- `.hgsigs`가 두 head로 갈라진 저장소에서 hg4j와 실제 hg가 둘 다 2개의 서명을 읽음.
- 만료된 서명, 만료된 키, 취소된 키, 잘못된 passphrase, 알 수 없는 키, 변조된 노드, 해독 불가 서명.
