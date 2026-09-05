---
updated: 2026-09-06
status: completed
---

# 백로그 6, 7: Censor(민감정보 삭제)

관련 항목: 6(`Revlog.censorRevision()` 신설 — real hg의 `mercurial/revlogutils/rewrite.py`
`v1_censor` 방식대로 tombstone 재작성, `CensorCommand` 포셀린 신설), 7(cg3 changegroup
경유 전송 시 censor된 리비전이 크래시하거나 censored 플래그가 조용히 사라지던 버그 2건).

**후속 발견(2026-09-05, 백로그 39 wave 5 admin/maintenance 그룹)**: 위 항목들 완료 후
한참 뒤, requirement 매트릭스 작업 중 `CensorCommand`에 **2건의 추가 실제 버그**를
발견·수정:
1. real hg의 `hgext.censor`가 갖고 있는 "head/작업 디렉터리 parent에 살아있는 리비전은
   censor 거부" 가드가 hg4j에는 아예 없었음 — `setCheckHeads(false)`로 우회 가능하게
   real hg의 `--no-check-heads`와 동일하게 구현.
2. **데이터 손상**: `Revlog.censorRevision()`이 포맷 무관하게 항상 구식 revlogv1
   레이아웃으로 재작성해 `general-v2` filelog의 docket을 깨뜨리고 진짜 컴패니언
   파일들을 고아로 만들던 버그(`truncate`+`appendRevisionV2` 재사용하는
   `censorRevisionV2` 분기 신설로 수정).

원래 이 문서(item 6/7)의 "완료(2026-09-01)" 표시는 general-v2 저장소 조합까지는
검증하지 못한 상태였다는 점에서 사실상 부분 완료였음 — 두 wave의 발견을 합쳐야
`CensorCommand`의 실제 완전한 상태가 된다. 상세는 [[39-exhaustive-interop-matrix]] 및
`known-bugs-registry.md`의 `Revlog.censorRevision` 항목 참고.

## 원문
6. ~~**Censor(민감정보 삭제)** — 아예 미구현.~~ — ✅ **완료(2026-09-01)**.
   `Revlog.censorRevision(int rev, byte[] tombstone)` 신설: 대상 리비전을 실제 hg의
   `mercurial/revlogutils/rewrite.py`(`v1_censor`) 방식대로 통째로 재작성한다 — 대상
   payload를 tombstone(`storageutil.packmeta({censored: msg}, '')`과 동일한
   `"\x01\ncensored: <msg>\n\x01\n"` 포맷)으로 교체하고 `REVIDX_ISCENSORED`(실측
   `0x8000`) 플래그를 설정, 노드ID·부모·linkrev는 원본 그대로 보존해 히스토리/DAG
   구조는 전혀 건드리지 않는다(다른 리비전도 전부 재작성하지만 전부 풀텍스트로만
   쓴다는 점만 실제 hg와 다름 — 저장 효율 차이일 뿐 상호운용성엔 무관). 포셀린
   `CensorCommand`(api) 신설, 읽기 시 실제 hg처럼 `HgCensoredContentException`을 던지도록
   `Revlog.getRevisionContent()`에 검사 추가. Docker Mercurial 6.0의 실제 `censor`
   확장으로 만든 진짜 바이트(인덱스 레코드 64바이트 전체, tombstone payload)를 직접
   hexdump로 대조해 포맷을 확정했고, 실제 hg 양방향 interop 테스트로 검증
   (`CensorRealHgInteropTest`): hg4j가 censor한 리비전을 real hg `cat`이 거부
   (`abort: censored node`)하고 `hg verify`가 `censored file data`로 인식, 반대로 real
   hg가 censor한 리비전을 hg4j가 올바르게 인식·차단.
7. **cg3의 censor 지원** — ✅ **완료(2026-09-01)**. censor 구현(항목 6) 직후 실제로
   검증해보니 changegroup 경유 전송 경로 두 곳에서 진짜 버그가 나왔다:
   1. **패킹 시 크래시**: `HgLocalClient.getBundle()`/`PushCommand`의 push용 번들 조립이
      파일로그 콘텐츠를 `getRevisionContent()`(항목 6에서 censored 리비전에 대해
      예외를 던지도록 바꾼 그 메서드)로 읽고 있어서, censored 리비전이 하나라도
      포함된 저장소를 pull/clone/push하면 무조건 크래시했다. `getRawRevisionContent()`
      (원본 저장 바이트 그대로, 실제 hg의 `rawdata()`/`_chunk()`와 동일한 접근)로 수정.
   2. **수신 측에서 censored 플래그가 조용히 사라짐**: hg4j의 changegroup은 cg1/cg2
      형태만 실제로 만들어 보내는데(cg3의 명시적 flags 필드가 없음), 수신측
      `Revlog.appendChangeGroupEntry()`는 flags를 항상 0으로 하드코딩하고 있었다 —
      즉 censor된 파일을 pull하면 수신 측에는 **censored 표시가 없는 평범한 리비전으로
      들어와, 원래 지워졌어야 할 내용이 그대로 복원**되는 심각한 버그였다. 실제 hg
      자신도 정확히 같은 문제를 안고 있어 `revlog.py`의 `_peek_iscensored()`로 전송된
      콘텐츠 안에서 censor tombstone 마커(`\x01\ncensored: ...`)를 스니핑해 플래그를
      복원하는 방식을 쓴다 — 이 메커니즘을 `Revlog.isCensoredText()`로 그대로 재현해
      수신측에 적용. 덤으로 censored 리비전에 대해서는 원격 노드 해시 무결성 검증도
      건너뛰도록 수정해야 했다(censor는 정의상 parents+content로 원래 해시를 재현할
      수 없으므로 — 원본 해시는 censor 시점 이전에 이미 검증된 값을 그대로 보존).
   실제 hg4j↔hg4j pull round-trip으로 TDD 검증(`CensorChangegroupTransferTest`):
   censored 리비전이 크래시 없이 전송되고, 수신측에서도 여전히 censored로 인식되며
   내용 접근 시 예외가 발생함을 확인.
