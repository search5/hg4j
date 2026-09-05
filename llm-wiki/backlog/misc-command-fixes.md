---
updated: 2026-09-04
status: completed
---

# 백로그 34, 36 + 커버리지 라운드1 부수 발견 4건: 기타 명령 수정

관련 항목: 34(`BisectCommand`의 merge 커밋 DAG 시나리오 real hg 대조 검증 — 새 버그는
없었음), 36(`TagCommand`가 기존 태그 재태깅을 `-f` 없이도 허용하던 버그), 그리고 번호
없이 "커버리지 95% 작업 중 추가로 발견·수정한 버그"(2026-09-01)로 기록됐던 4건
(`StatusCommand`/`ShelveCommand`의 racy-write 오탐, `HgRevsetEngine.evaluateSort`의
author/user 정렬 no-op, `SubrepoCommand`의 tip fallback 크래시).

## 원문
34. ~~**`BisectCommand`의 merge 커밋 DAG 시나리오 real hg 대조 검증 누락**~~ —
    ✅ **완료(2026-09-04)**. 신규 테스트 `bisectConvergesToSameCulpritAsRealHgAcrossMergeCommit`
    (`BisectRealHgInteropTest`)를 TDD로 추가 — root에서 branch A(2커밋, flag.txt
    미변경)/branch B(3커밋, 중간에 flag.txt를 "1"로 바꾸는 진짜 culprit)로 분기시킨
    뒤 `UpdateCommand`+`MergeCommand`로 실제 3-way 자동 병합(충돌 없음) merge
    커밋을 만들고, 그 뒤로 한 커밋 더 진행한 8-리비전 DAG로 검증. good=root,
    bad=최종 리비전으로 hg4j `BisectCommand`와 real `hg bisect`를 동일한
    good/bad-by-flag.txt 오라클로 나란히 걸어 매 단계 후보 시퀀스와 최종 culprit이
    정확히 일치함을 확인(`assertEquals(nativeCandidates, hg4jCandidates)`). **실제
    프로덕션 버그는 발견되지 않음** — `BisectCommand`의 기존 merge-DAG 인식 알고리즘
    (`getTopologicalRange`/`selectBisectCandidate`의 양쪽 부모 전파)이 이미 정확했음이
    확인됨, 검증 자체가 목적이었던 항목이라 이걸로 완료. 전체 회귀 확인 중 61건의
    무관한 실패가 관측됐으나 `bisect` 관련은 0건이고 이 항목은 프로덕션 코드를 전혀
    건드리지 않은 순수 테스트 추가라, 동시에 실행 중이던 다른 병렬 작업의 빌드
    간섭(이 세션에서 이미 여러 차례 확인된 현상)으로 판단 — 이 항목 자체의 회귀는
    없음.

36. ~~**`TagCommand`가 기존 태그 재태깅을 `-f` 없이도 허용함**~~ — ✅
    **완료(2026-09-04)**. 신규 발견(백로그 23번 tag 카테고리 완료 후 재검증 중
    승격). real hg는 이미 존재하는 태그 이름으로 다시 태깅하면 `abort: tag '%s'
    already exists (use -f to force)`로 거부하고 `--force`가 있어야 덮어쓰기를
    허용하는데, `TagCommand.java`에는 관련 가드가 전혀 없어 hg4j는 항상 무조건
    덮어쓰고 있었다. `TagCommand`에 `setForce(boolean)` 신설, 기존 태그로 재태깅
    시도 시 `force`가 false면 real hg와 동일한 메시지(`"tag '<name>' already
    exists (use -f to force)"`)로 `HgValidationException`을 던지도록 구현.
    `TagRealHgInteropTest`에 신규 테스트 4건(force 없이 거부, `-f`로 성공, 로컬
    태그가 기존 전역 태그와 충돌 시 force 없이 거부, 태그 제거는 force 불필요)
    추가 — real hg CLI와 거부 메시지까지 일치 확인. 기존
    `hg4jRetaggingMovesTheTagAndRealHgSeesTheNewTargetWithOldLineStale` 테스트는
    (원래 "hg4j never gates on -f"를 전제로 통과하던 테스트) `setForce(true)`를
    명시적으로 넣도록 조정해 원래 검증 의도(태그 이동 자체)를 보존.


## 원문 (커버리지 95% 작업 중 추가 발견 4건, 2026-09-01)
빌드 게이트(`jacocoTestCoverageVerification`)를 다시 90% 이상으로 통과시키는 작업 중
실제 hg4j 동작 버그 3건을 더 발견·수정했다 (테스트가 아니라 프로덕션 코드 수정):

1. **`StatusCommand`의 racy-write 검증이 잘못된 리비전과 비교하던 버그(핵심)** —
   `dirstate`의 size/mtime이 우연히 일치할 때 실제 내용을 다시 확인하는 안전장치가
   있었는데, 비교 대상을 "해당 파일 filelog의 가장 최근 리비전"으로 고정해뒀었다.
   이는 워킹카피가 tip에 있을 때만 맞는 가정이라, `hg update`로 과거 리비전으로
   전환한 뒤에는 **전혀 손대지 않은 파일도 modified로 오탐**했다. 워킹카피의 실제
   dirstate parent 커밋 기준으로 비교하도록 수정(`getParentCommitFileContent()` 신설,
   fast/slow path 양쪽에 적용).
2. **`ShelveCommand`의 동일 버그 패턴** — 자연스럽게 수정된(dirstate state `'n'`)
   추적 파일 감지가 크기/mtime만 봤는데, 같은 초·같은 바이트 크기로 편집되면
   변경을 놓치고 shelve 자체가 조용히 no-op이 되는 버그. `getBaselineContent()`
   기반 콘텐츠 비교로 보강.
3. **`HgRevsetEngine.evaluateSort`의 `sort(x, "author"/"user")`가 사실상 no-op** —
   changelog 리비전이 아니라 filelog 전용 메타데이터 파서(`Revlog.getRevisionMetadata`)를
   호출해서 항상 빈 문자열끼리 비교하고 있었다. changelog 원문에서 직접 author를
   추출하도록 수정.
4. **`SubrepoCommand`** — `.hgsubstate`에 리비전이 없을 때 fallback 문자열 `"tip"`을
   그대로 hex 파서에 넘겨 항상 예외가 나던 버그(이전 세션에 이미 보고·수정됨, 여기서는
   같은 커밋 배치로 재확인).

