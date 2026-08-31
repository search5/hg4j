---
updated: 2026-08-31
status: current
---

# 계획: Revlog v2 지원 실행 계획

> [[mercurial-spec-compliance-requirement]]에서 "무조건 지원, v1과 병행"으로 확정된 항목의
> 실행 계획. **아직 실행되지 않음** — 조사 결과를 바탕으로 한 계획 문서 단계.

## 목표
현재 `Revlog`/`RevlogIndex`는 v1 포맷 전용으로 구현되어 있다. 저장소의 `requires` 파일에
v2 관련 requirement(`persistent-nodemap`, `revlogv2`/`exp-revlogv2` 계열, `sidedata` 등)가
있으면 v2 포맷으로 읽고/쓸 수 있어야 하고, 없으면 기존 v1 그대로 동작해야 한다 —
**대체가 아니라 저장소별 분기**.

## 공식 근거
- `hg help internals.revlogs` — 가장 권위 있는 1차 소스 (버전별 차이 포함).
- Mercurial 위키 `RevlogV2Plan` — 설계 배경 문서.
- `mercurial/revlog.py` (Mercurial 소스) — 최종 근거. 스펙 문서보다 실제 파서 코드가
  우선.

## 알려진 핵심 차이 (조사로 확인된 범위, 미확정 세부사항 있음)
- **Persistent nodemap**: node id → revision 번호 매핑을 매번 스캔하지 않고 디스크에
  캐싱하는 인덱스. `persistent-nodemap` requirement로 저장소가 선언.
- **Sidedata**: 리비전 본문과 별도로 부가 메타데이터(예: copy-tracing, LFS 포인터 등)를
  저장하는 슬롯. 별도 `_sidedatafile`이 존재하는 것으로 확인됨.
- **Docket 파일**: v1의 `.i`/`.d` 2파일 구조 대신, v2는 `_docket_file`이 인덱스/데이터/
  사이드데이터 파일들을 가리키는 상위 메타 파일 역할을 한다는 것까지는 확인. **정확한
  바이너리 레이아웃(필드 순서, 크기, 체크섬 여부)은 이번 조사로 확정하지 못함 —
  구현 착수 전 `mercurial/revlog.py`의 `_docket` 관련 클래스를 직접 읽어야 한다.**
- **requirement 문자열**: `persistent-nodemap`, `revlogv2` 계열 requirement 이름이
  Mercurial 버전에 따라 실험적(`exp-` 접두어) → 정식으로 바뀐 이력이 있어, README 기준
  버전(v7.2.2)에서 실제로 어떤 문자열을 쓰는지 코드/실제 저장소로 재확인 필요.

> ⚠️ 이 섹션은 웹 검색 기반 1차 조사이며, 바이트 단위 파싱 구현에 들어가기 전 반드시
> Mercurial 소스(`mercurial/revlog.py`, `mercurial/revlogutils/`)를 직접 읽고 이 문서를
> 갱신해야 한다. 부정확한 정보로 파서를 작성하면 v1 때와 같은 종류의 버그
> (BUG-01/02/10, [[revlog]] 참고)가 재발할 위험이 크다.

## 단계별 계획
| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | 조사: `mercurial/revlog.py`, `mercurial/revlogutils/docket.py`(존재 시) 등 실제 소스를 정밀 대조해 docket/nodemap/sidedata 바이너리 레이아웃을 확정 | 이 문서의 "알려진 핵심 차이" 섹션 갱신, 불확실 표시 제거 |
| 2 | 픽스처 확보: 실제 hg CLI(v7.2.2, `persistent-nodemap`/`revlogv2` 활성화)로 생성한 저장소를 `src/test/resources/fixtures/`에 추가 | `revlogv2-sample.*` 픽스처 |
| 3 | 읽기 전용 지원: `RevlogIndex`에 v2 인덱스 파싱 분기 추가 (기존 v1 파싱 경로는 그대로 유지) | `RevlogIndex` v2 read path, `RevlogV2ParserTest` |
| 4 | nodemap 캐시 활용: persistent nodemap이 있으면 그걸 읽어 조회 가속, 없으면 기존 스캔 방식 fallback | 성능 벤치마크로 개선 검증 ([[core]]의 `PerformanceBenchmarkTest`와 유사한 형태) |
| 5 | 쓰기 지원: 신규 리비전 append 시 v2 포맷으로 쓰는 경로 추가 (`requires`에 v2가 선언된 저장소에서만) | `Revlog.appendRevision` v2 분기 |
| 6 | 라운드트립 검증: 실제 hg CLI로 v2 저장소를 만들고 hg4j로 읽기/쓰기 → 다시 hg CLI로 검증 | 상호운용성 테스트 |

## 코드 영향 범위 (현재 구조 기준)
- `Revlog`, `RevlogIndex`, `DeltaCodec` — v1/v2 분기 추가 지점.
- `HgRepository.loadRequires()` — v2 관련 requirement 문자열 인식 추가.
- [[core-package-split-plan]] Phase 10(`storage` 패키지 분리)과 시점이 겹친다 — **분리
  작업과 v2 지원 작업을 같은 커밋에서 섞지 않는다.** 분리를 먼저 끝내고 나서 v2 지원을
  얹는 순서를 권장 (리뷰 범위를 좁히기 위함).

## 미해결 (다음 조사에서 채울 것)
- v1 저장소를 v2로 업그레이드하는 명령(`hg debugupgraderepo`) 상호운용까지 지원 범위에
  넣을지, 아니면 "이미 v2인 저장소를 읽고 쓰는 것"까지만 범위로 할지 미정.

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거 (필수 구현 확정)
- [[revlog]] — v1 구현 현황 및 과거 버그 이력
- [[core]] — 관련 클래스 현재 위치
