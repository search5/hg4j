---
updated: 2026-11-04
status: current
---

# 결정: wire protocol v2 제거, 오라클은 Mercurial 7.2.4

## 결정
사용자 지시(2026-11-04)로 hg4j의 wire protocol v2 구현을 모두 제거했다: 클라이언트 `HgRemoteClientV2`와 `transport.wireprotov2` 패키지(`Cbor`, `Wire2Frame`, `Wire2Transport`, `Wire2Commands`), `HgRemoteClient`의 자동 업그레이드(`X-HgUpgrade-1`)와 위임, `HgHttpWireServer`의 발견 응답과 `/api/...` 처리, 그리고 이들의 테스트. 이후 hg4j의 wire 동작은 오라클 Mercurial 7.2.4와 같아야 한다.

## 왜
- 오라클이 v2를 지원하지 않는다. 7.2.4는 v2 서버 코드를 이미 제거했다(소스에 `filesdata` 정의 없음, 도움말에만 남음). 마지막으로 실제 v2가 동작한 릴리스는 6.0이다.
- v2는 hg4j끼리만 통신하는 경로였고 실제 hg와 대조할 수 없었다. 통신 E2E 점검에서 이 경로가 결함의 근원이었다: 복사·이름 변경 메타데이터가 빠져 해시 검증이 실패하고, 리비전 플래그가 없어 LFS를 전달하지 못했다.
- 7.2.4의 동작: `X-HgUpgrade-1` 헤더는 무시되고 평범한 v1 capabilities가 돌아온다. hg4j 서버도 같게 동작한다(테스트 있음).

## 변경 요약
- `RepoSupport`(`wireprotov1`): v1 서버가 공유하던 도우미(`changelog`, `readListKeys`, pushkey 적용, `isPublishing`)를 `Wire2Commands`에서 옮겼다.
- `HgRemoteClient#negotiateV2`는 `negotiateCapabilities`로 개명(v1 capabilities 토큰만 읽는다).
- 이 결정은 [[wireprotocol-v2-support-plan]]을 대체한다. 그 문서는 역사 기록이다.
