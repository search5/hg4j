---
updated: 2026-09-01
status: completed
---

# 계획: Obsolescence Marker 생성 경로 완성 (rebase/graft/histedit/strip)

> [[mercurial-spec-compliance-requirement]]에서 Track B-5로 승격된 항목의 실행 계획.
>
> ✅ **2026-09-01 구현 완료 — 단, obsstore 바이너리 포맷 자체가 완전히 틀렸던 것을
> 발견해 전면 재작성했다.** Rebase/Graft/Histedit/Strip 전부 마커 생성 경로 연결까지는
> 이미 돼 있었지만(코드 확인), 그 마커를 실제로 디스크에 쓰는 `HgObsMarker.writeMarker()`/
> `HgObsolescenceParser`가 구현한 바이너리 레이아웃이 실제 Mercurial(FM1, version=1)과
> 전혀 달랐다 — 파일 버전 바이트 부재, 필드 순서·크기 전부 불일치. 실제 hg CLI로
> `commit --amend`를 수행해 얻은 진짜 obsstore 바이트를 `mercurial.obsolete._readmarkers()`
> (Python 표준 구현)로 직접 디코딩해 정확한 스펙(고정 헤더 19바이트: totalsize+date+tz+
> flags+numsuc+numpar+nummeta, 이어서 predecessor+successors+메타데이터)을 확인하고
> 그대로 재작성. 실제 hg가 만든 obsstore를 hg4j로 파싱 + hg4j가 쓴 obsstore를 실제
> `hg debugobsolete`로 읽기 — 양방향 검증 통과(`HgObsolescenceRealHgInteropTest`).

## 목표
Mercurial의 obsolescence marker는 "이 리비전은 저 리비전(들)으로 대체됐다"는 관계를
기록해 `hg log`가 죽은 리비전을 숨기고 `hg evolve` 계열 도구가 이력을 재구성할 수 있게
한다. 이번 전수 감사에서 `AmendCommand`는 이 기록을 정확히 남기지만, **history-rewriting
계열의 다른 명령(rebase/graft/histedit/strip)은 전혀 남기지 않는다**는 것을 확인했다.
결과적으로 rebase/histedit 후에 원본 리비전이 "죽은 게 아니라 그냥 별개의 head"로
남아버려, 실제 hg CLI로 같은 저장소를 열었을 때와 다른 이력 뷰가 보이는 상호운용성
문제가 생긴다.

## 공식 근거
- `hg help internals.changelogs`/`hg help evolution` — obsolescence marker가 log
  뷰(숨김 처리)에 미치는 영향.
- `mercurial/obsolete.py`의 `createmarkers()` 호출 지점 — rebase/histedit/amend/uncommit
  등에서 공통적으로 호출됨(코어 hg의 실제 소스 기준, 이번 세션에서 원문 대조는 아직 안 함
  — 착수 전 확인 필요).

## 현재 구현 상태 (2026-08-31 코드 조사로 확인)
| 명령 | obsstore 마커 생성 | 근거 |
|---|---|---|
| `AmendCommand` | ✅ 있음 | `.hg/store/obsstore`에 `FileOutputStream(..., true)`로 append하는 코드 확인 (`AmendCommand.java`) |
| `RebaseCommand` | ❌ 없음 | `HgObsMarker`/`obsstore` 문자열 검색 0건 |
| `GraftCommand` | ❌ 없음 | 동일 검색 0건 |
| `HisteditCommand` | ❌ 없음 | 동일 검색 0건 |
| `StripCommand` | ❌ 없음 | 동일 검색 0건(단, strip은 원래 obsolescence 대신 완전 삭제가 스펙상 맞을 수도 있음 — 착수 시 `hg help strip` 재확인 필요) |

**결론**: obsstore 쓰기 자체는 기술적으로 검증됐다(amend에서 동작) — 나머지 명령에
동일 패턴을 적용하기만 하면 되는, 상대적으로 작은 작업이다.

## 단계별 계획
| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | `AmendCommand`의 obsstore 쓰기 로직을 공용 유틸(예: `obsolete.ObsoleteMarkerWriter`)로 추출 | 공용 클래스, `AmendCommand`는 이걸 호출하도록 리팩터링 |
| 2 | `RebaseCommand`: 각 리비전을 새 부모 위로 재적용한 뒤, 원본→새 리비전 마커 생성 | `RebaseCommand` 수정 + 테스트 |
| 3 | `GraftCommand`: 원본→graft 결과 리비전 마커 생성 (graft는 명시적 `--no-obsolete` 유사 옵션이 있는지 `hg help graft` 확인 후 옵션화) | `GraftCommand` 수정 + 테스트 |
| 4 | `HisteditCommand`: fold/pick/drop 등 각 액션 종료 후 대응 마커 생성 (fold는 다중 predecessor → 단일 successor) | `HisteditCommand` 수정 + 테스트 |
| 5 | `StripCommand`: strip이 obsolescence가 아니라 완전 삭제가 맞는 스펙인지 확인 후, 필요 시 스펙에 맞게 조정(현재 동작 유지 또는 마커 추가) | 조사 결과 반영 |
| 6 | 라운드트립 검증: 실제 hg CLI로 rebase/histedit 후 `hg log --hidden`으로 obsolete 마커가 올바르게 걸렸는지 대조 | 통합 테스트 |

## 코드 영향 범위 (현재 구조 기준)
- `api.RebaseCommand`, `api.GraftCommand`, `api.HisteditCommand`, `api.StripCommand`,
  `api.AmendCommand`(리팩터링 대상)
- `obsolete.HgObsMarker`, `obsolete.HgObsolescenceParser`(쓰기 유틸 추가 위치 후보)

## 관련 페이지
- [[mercurial-spec-compliance-requirement]] — 상위 근거 (Track B-5로 승격, 2026-08-31).
  이 문서의 gap table에서 "Obsolescence markers: 파싱만 확인, 쓰기 경로 확인 필요"로
  남아있던 항목을 이번 조사로 "amend만 씀, 나머지 명령엔 없음"으로 확정함.
