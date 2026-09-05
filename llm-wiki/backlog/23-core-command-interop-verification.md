---
updated: 2026-09-04
status: current — 완료(10개 카테고리 전부)
---

# 백로그 23: commit/push/branch/merge/tag(+rebase/shelve/bisect/strip/subrepo) 실전 종합 interop 검증

`mercurial-spec-compliance-requirement.md`에서 이관됨(2026-09-06 재구조화). 백로그
22번(와이어 프로토콜 협상)과 같은 문제의식 — hg의 가장 기본적이고 자주 쓰이는
포셀린 명령들도 지금까지는 "대표 시나리오 1개" 위주로만 검증돼 있었다는 점을
확인하고, 10개 명령 카테고리 각각을 실제 hg CLI와 전면 대조 검증했다.

23. ~~**commit/push/branch/merge/tag(+ rebase/shelve/bisect/strip/subrepo) — 실전
    시나리오 종합 interop 검증**~~ — ✅ **완료(2026-09-04, 10개 카테고리 전부)**.
    배경: 백로그 22번(와이어 프로토콜 협상)과 같은 이유 — 이 명령들은 hg의 가장
    기본적이고 가장 자주 쓰이는 포셀린 명령인데, 지금까지의 검증은 "한 가지 대표
    시나리오"(예: 선형 커밋 2개, fast-forward merge 1건) 위주였고 각 명령이
    실전에서 마주치는 조합의 상당수는 아직 실제 hg CLI와 대조된 적이 없다. 아래
    카테고리 각각을 별도 TDD
    배치로 진행 권장.

    **진행 현황(2026-09-04)**: 10개 카테고리 전부 ✅로 완료(commit/push/branch/
    merge/tag/rebase/shelve/bisect/strip/subrepo — 병렬 진행된 5개 배치가 각각
    실제 hg CLI 왕복 검증 + 실제 버그 다수 발견·수정: commit/push 4건,
    branch/tag 2건, merge/rebase 3건(그중 1건은 `CommitCommand` 공통 로직
    버그), shelve/bisect/strip 4건, subrepo 2건 — 총 15건). **여러 항목에서
    코드 변경 없이 사용자 확인만 필요한 아키텍처 수준 결정이 발견돼 아래 각
    카테고리 문단에 표시해뒀다** — 코디네이터가 정리해 별도로 보고할 예정.

    **아키텍처 결정 6건 — 전부 사용자 확정(2026-09-04)**: 아래 각 카테고리
    문단에 흩어져 있던 "사용자 확인 필요" 항목 6개를 모아 `AskUserQuestion`으로
    확인, 전부 "정석대로 완전히 구현" 방향으로 확정됨(코드는 아직 미착수 —
    이 결정 기록 다음에 순서대로 구현 예정):
    1. `HeadsCommand.call()`(인자 없는 기본 호출) → **real hg의 실제 `hg heads`
       시맨틱(브랜치별 head 전부)으로 고침**(브레이킹 체인지 감수, 기존
       `--topo` 동작은 명시적 옵션으로만 유지).
    2. subrepo 미체크아웃 상태에서 부모 커밋 시 `.hgsubstate` 처리 →
       **real hg와 동일하게 null 리비전으로 리셋**(기존 "보존" 방식 폐기 —
       `HgSubrepoTest`/`UpdateCommandTest`의 의존 케이스 갱신 필요).
    3. rebase의 물리적 strip+marker 동시 수행 → **strip을 그만두고 marker만
       남기는 evolution 방식으로 전환**(원본 리비전은 hidden으로 보존,
       `RebaseRealHgInteropTest`의 "unknown revision" 기대값을 "hidden"으로
       갱신 필요).
    4. rebase 충돌 감지 → **`MergeCommand`의 3-way merge 인프라를 cherry-pick
       경로에 이식**(conflict marker+exit 1+`resolve`/`--continue`/`--abort`까지
       real hg와 동등하게).
    5. `unshelve`의 rebase 필요 시나리오(충돌 포함) → **real hg의
       임시커밋+rebase+merge+strip 알고리즘을 지금 이식**(항목 4의 merge
       인프라를 재사용할 것).
    6. `PushCommand`의 `checkheads()` 포트 → **obsolescence-marker 예외,
       bookmark-head 예외까지 지금 마저 포팅**.

    구현 순서: 4(rebase 3-way merge 인프라)를 먼저 완성한 뒤 그 인프라를
    재사용해 3(rebase evolution 전환)과 5(unshelve rebase화)를 이어서 진행,
    1/2/6은 서로 독립적이라 병행 가능.

    **commit**: ✅ **완료(2026-09-04)**. 머지 커밋(부모 2개, `p2` 필드 정확성) —
    특히 심볼릭 링크/실행권한/바이너리 파일이 섞인 머지, `hg commit
    --close-branch`, 빈 커밋(파일 변경 없이 메시지만), extra 필드가 여러 개
    겹칠 때(`branch`+`close` 동시) 정렬/인코딩 순서, `hg commit --amend`(hg4j에
    `AmendCommand`가 이미 있었음) — 전부 실제 hg CLI(host native 7.2.2)와
    왕복 대조로 재검증했다. **실제 버그 발견 및 수정**: `AmendCommand`가
    (1) amend 시 dirstate의 현재 부모(=amend 대상 커밋 자신)를 그대로 새
    커밋의 부모로 써서, 실제 hg처럼 "같은 부모를 공유하는 형제 리비전으로
    교체"가 아니라 amend 대상 커밋의 **자식**이 되는 DAG 형태 오류가 있었다
    (실제 hg로 `hg commit --amend` 후 `hg log -G`/`{p1node}` 대조해 발견,
    2026-09-04) — `CommitCommand`에 `setAmendDeclaredParents(p1, p2)`를
    신설해 "무엇이 바뀌었는지 판단하는 기준(dirstate의 실제 부모)"과
    "changelog/manifest에 기록되는 선언된 부모"를 분리함으로써 해결(파일
    콘텐츠 계산 로직 자체는 전혀 건드리지 않음 — 그대로도 정확했음). (2)
    `-m`/`-u` 없이 amend하면 무조건 예외를 던졌다(실제 hg는 amend 대상
    커밋의 메시지/작성자를 그대로 재사용) — 기존 테스트
    `HgAmendTest#testAmendWithoutExplicitMessagePropagatesCommitMessageRequirement`가
    이 버그를 "정상 동작"으로 문서화하고 있었던 것도 함께 바로잡음
    (`testAmendWithoutExplicitMessageOrAuthorReusesOriginalCommitValues`로
    교체). **테스트**: `CommitRealHgInteropTest`(신설,
    `src/test/java/io/github/search5/hg4j/api/`) 6개 — 혼합 파일타입 머지
    커밋, close-branch, 빈 커밋, branch+close 동시 extra, amend(단순/머지
    커밋 amend) — 전부 GREEN.

    **push**: ✅ **완료(2026-09-04)**. 여러 head가 있는 저장소로 push(reject
    vs 성공 조건), `--force`로 비-fast-forward push, 새 named branch를
    원격에 처음 push할 때의 경고/허용 조건(`hg push --new-branch`), bookmark가
    있는 저장소의 push(북마크 이동 반영), 이미 알려진 커밋만 있어 "no changes
    found"로 끝나는 push — 전부 실제 hg CLI와의 **양방향** interop으로
    검증했다: hg4j가 pusher, 실제 `hg serve`(HTTP)가 accept/reject를 판정하는
    authority(`RealHgServeSupport` 재사용). **범위 확장(설계 판단)**: 기존
    `PushCommand`에는 head-count/새 브랜치 거부 로직이 전혀 없었다
    (`--force`/`--new-branch` 옵션 자체가 없었음 — push는 항상 무조건 성공) —
    담당 에이전트는 이를 architecture-level 변경까지는 아니라고 판단해 직접
    구현했다: `PushCommand.setForce()`/`setAllowNewBranch()` 신설 + 실제 hg
    `mercurial/discovery.py checkheads()`를 간소화 포팅한 client-side
    체크(그룹 3까지: 존재하지 않던 원격 브랜치 정보 조회를 위해
    `HgRemoteConnection.getBranchHeads()` 신설 및 `HgLocalClient`/
    `HgRemoteClient`(HTTP)에 구현 — SSH는 기본 폴백만, 미구현).

    **→ 결정(2026-09-04): 나머지 규칙까지 마저 포팅하는 쪽으로 확정 → ✅
    완료(2026-09-04)**. 이 머신에 설치된 실제 hg 7.2의
    `mercurial/discovery.py`(`checkheads`/`_postprocessobsolete`/
    `_nowarnheads`)와 `mercurial/bookmarks.py`(`validdest`)를 직접 읽어 남은
    두 예외를 마저 포팅했다.
    1. **obsolescence-marker 예외**(`discovery._postprocessobsolete`/
       `pushingmarkerfor`): 후보 새 head가 로컬 저장소 자신의 obsstore
       (`HgObsMarker`/`HgObsolescenceParser`로 읽음)에 predecessor로 기록돼
       있고 그 successor 체인이 이 체크의 후보 리비전 집합(이번에 push되는
       리비전 + 이미 아는 원격 head) 안의 다른 리비전에 닿으면, public
       phase가 아닌 한 새 head로 세지 않는다. 실제 hg 7.2로 직접 검증
       (2026-09-04): 이미 push된 head를 amend한 뒤 그 successor를
       `--force` 없이 push하면 성공하며, 심지어
       `experimental.evolution.exchange=no`로 obsolescence marker 자체를
       원격과 전혀 교환하지 않아도 성공한다 — 실제 hg의 client-side
       accept/reject 판단은 오직 push하는 쪽 저장소 자신의 obsstore에만
       의존하기 때문이다. hg4j의 push도 obsmarker를 전혀 교환하지
       않으므로(bundle1 한정) 이는 근사가 아니라 정확히 일치하는 동작이다.
    2. **bookmark-head 예외**(`discovery._nowarnheads`/
       `bookmarks.validdest`): 로컬 bookmark의 원격 쪽 이전 위치가 로컬에
       알려져 있고, 그 위치에서 현재 로컬 위치까지 "changelog 자식 관계"와
       "로컬 obsstore successor 관계"를 자유롭게 섞어 도달 가능하면
       (`obsutil.foreground`에 대응), 그 위치는 새 head 카운트를 늘리더라도
       거부 사유(blame)에서 제외한다. 단 이 예외는 새 named branch의
       multi-head 서브케이스(`remoteheads is None`)에는 적용되지 않는다 —
       실제 hg 소스가 그 분기에서는 `nowarnheads`를 빼지 않고 그대로
       씀(`checkHeadsPerBranch`가 이 구분을 그대로 반영). 실제 hg 7.2로
       직접 검증(2026-09-04): bookmark를 amend된 successor로 전진시키는
       push는 `--force` 없이 성공하지만, bookmark를 obsolescence 연결이
       전혀 없는 무관한 divergent head로 강제 이동하는 push는 여전히
       거부된다("push creates new remote head ... with bookmark") — 즉
       "bookmark가 달린 head는 무조건 봐준다"가 아니라 실제로 유효한
       forward move일 때만 예외가 적용됨을 확인했다.

    `PushCommand`의 head-count 로직을 개수(int) 비교에서 리비전 집합(Set)
    비교로 재작성해 두 예외를 반영했다(`applyObsolescenceDiscard`,
    `computeNowarnRevs`, `isInForeground`, `hasLiveSuccessorAmongCandidates`,
    `loadObsSuccessorMap`, `buildChildrenMap` 신설). **테스트**:
    `PushRealHgInteropTest`에 3개 추가 —
    `testPushSucceedsWhenObsoleteHeadReplacedBySuccessorWithoutForce`(obsolescence
    예외로 무강제 성공, 원격에 신구 head 2개가 그대로 남는 것까지 실제 hg로
    확인), `testPushOfBookmarkAdvancedAcrossAmendSucceedsWithoutForce`
    (obsolescence 기반 rewrite를 가로지르는 bookmark 전진 성공),
    `testPushRejectedWhenBookmarkMovedToDivergentSiblingWithoutObsolescenceLink`
    (obsolescence 연결이 없는 무관한 head로의 bookmark 이동은 여전히 거부) —
    전부 실제 hg 서버(`hg serve`)를 상대로 GREEN, 기존 5개 포함 전체
    GREEN. 전체 회귀 스위트(gradle test 전체)도 GREEN.
    **실제 버그 발견 및 수정(architecture-level 아님, PushCommand 내부
    로직 수정)**: (1) cg1 changegroup 패킹 시 각 그룹(changelog/manifest/
    파일별 filelog)의 **첫 엔트리**의 델타 베이스를 "로컬 rev 인덱스상
    바로 이전 리비전"으로 잘못 계산하고 있었다 — 실제 hg
    (`cg1unpacker._deltaheader`: `if prevnode is None: deltabase = p1`)는
    그룹의 첫 엔트리만 그 리비전 **자신의 실제 p1**을 베이스로 삼는다.
    선형 히스토리에서는 두 값이 우연히 같아 안 드러났지만, 여러 head가
    있는 저장소로의 push(특히 `--force`로 새 head를 만드는 push)처럼 첫
    신규 리비전의 진짜 부모가 "그 직전 로컬 rev"보다 앞선 경우 수신측
    해시 검증이 깨져 실제 hg 서버가 unbundle에 HTTP 500으로 응답했다(실제
    hg 서버로 재현, 2026-09-04). (2) HTTP `pushkey`가 `httppostargs` capability
    협상 결과에 따라 GET으로 나갈 수 있었는데, 실제 hg HTTP 서버는
    push 권한이 필요한 모든 명령을 **무조건 POST로만** 허용한다
    (`hgweb/common.py checkauthz`: `push requires POST request`; 실제 hg
    클라이언트도 `httppeer.py`에서 pushkey만 따로
    `args['data']=b''`로 강제 POST) — httppostargs 미협상 환경(host
    native hg 7.2의 기본 `hg serve`)에서 실제 hg 서버로 push하면 bookmark
    이동이 405로 조용히 실패했다(`PushCommand`가 bookmark 동기화 실패를
    비차단 경고로만 처리하는 기존 설계 때문에 예외가 삼켜져 있었음).
    `HgRemoteClient.pushkey()`를 항상 POST하도록 수정(인자 전달 위치는
    기존 GET-tier 로직 그대로 유지, HTTP 메서드만 강제 POST). 이 두
    버그를 고치기 전엔 기존 `PushCommandTest`/`HgArgProtocolTest` 등도
    이 경로를 실제 hg로 왕복 검증한 적이 없어 놓치고 있었다(하나는
    `resp.isEmpty()`를 성공으로 처리하는 관대한 폴백 때문에, 다른 하나는
    선형 히스토리만 다뤄서). **테스트**: `PushRealHgInteropTest`(신설,
    `src/test/java/io/github/search5/hg4j/transport/`) 5개 — 다중 head
    저장소로의 성공/거부/force, 새 branch 거부/허용, HTTP를 통한 실제 hg
    서버로의 bookmark 이동, no-changes push — 전부 GREEN. 기존
    `HgArgProtocolTest`/`CatUpdateClonePushCoverageTest`의 관련 테스트
    1개씩을 새로 확인된 정확한 스펙에 맞게 갱신(전자는 pushkey가 POST를
    써야 함을 반영, 후자는 `--force` 없이는 새 head 생성 push가 거부되는
    게 맞는 동작임을 반영).

    **branch**: ✅ **완료(2026-09-04)**. named branch 생성(`hg branch <name>`), 그
    브랜치로 커밋 후 `hg branches`/`hg branches --closed` 목록 정확성,
    `hg commit --close-branch` 후 해당 브랜치가 목록에서 빠지는지, 한 브랜치에
    head가 여러 개 생기는 시나리오(브랜치 내부 분기)와 `hg heads <branch>` 전부
    real hg 7.2.2(host native)와 왕복 검증했다.

    **발견한 실제 버그(1건, 수정 완료)**: `BranchesCommand`의 `hg branches` 목록
    정렬이 리비전 내림차순만 보고 있었는데, 실제 hg의 `branchmap.branches_info()`/
    `commands.branches()`는 `(active, rev, name, isOpen)` 튜플을 내림차순 정렬한다
    — 여기서 "active"는 "현재 워킹카피 브랜치"가 아니라 "그 브랜치의 최신 열린
    head가 저장소 전체 기준 위상 head(어디서도 자식이 없는 리비전)인가"라는,
    브랜치별이 아니라 저장소 전역 기준 개념이다. 리비전 번호만으로 정렬하면, 한
    브랜치(A)가 다른 브랜치(Z)의 자식 커밋으로 인해 inactive가 되었는데 그 A의
    리비전 번호가 다른 active 브랜치(Y, 더 낮은 리비전)보다 높은 경우 순서가
    어긋난다 — 실제 real hg 스크래치 저장소로 재현: `A`(rev2)가 `Z`(rev3)로 인해
    inactive가 되고 `Y`(rev1)는 그대로 active인 상황에서, real hg는
    `Z, Y, A, default`로 정렬하는데(active 우선) 옛 hg4j 로직은
    `Z, A, Y, default`로 정렬했다(리비전 내림차순만). `BranchesCommand.call()`의
    정렬 비교자를 real hg와 동일한 `(active, rev, name, isOpen)` 기준으로 고치고,
    `BranchHead`에 `isActive()` 필드를 추가해 이 정보를 노출했다
    (`src/main/java/io/github/search5/hg4j/api/BranchesCommand.java`).

    **기능 추가**: `HeadsCommand`에 `hg heads <branch>`에 해당하는 `setBranch(String)`/
    `setIncludeClosed(boolean)`를 새로 추가했다 — 기존에는 branch 필터 기능 자체가
    없었다(`src/main/java/io/github/search5/hg4j/api/HeadsCommand.java`).

    **`HeadsCommand` 기본 동작 브레이킹 체인지 ✅ 완료(2026-09-04)**: 위에서 확인된
    갭(`HeadsCommand.call()`의 필터 없는 기본 호출이 사실 real hg의 `hg heads --topo`
    시맨틱만 구현하고 있었음)에 대해 사용자가 "real hg의 실제 `hg heads` 시맨틱으로
    고친다(브레이킹 체인지 수용)"로 확정 결정. `mercurial/commands.py heads()` 소스를
    직접 실측(`/usr/lib/python3/dist-packages/mercurial`, hg 7.2 패키지)해 정확한
    알고리즘을 확인 후 그대로 이식: 인자 없는 기본 `call()`은 이제
    `repo.branchmap()`의 모든 브랜치를 순회하며 각 브랜치의 열린 head(들)를
    (`bm.branchheads(branch, closed=...)`처럼) 모아 리비전 내림차순으로 정렬해
    반환한다 — 브랜치 자체 head라면 저장소 전역 위상 리프가 아니어도 포함된다.
    옛 동작(저장소 전역 위상 리프만)은 `setTopo(boolean)`(기본값 `false`)로 명시적
    opt-in 가능하게 남겨뒀다(real hg의 `hg heads --topo`와 동일). `setIncludeClosed`도
    이제 필터 없는 기본 호출에 적용되도록 의미를 넓혔다(real hg의 `--closed`가
    `branchmap()` 순회 전체에 적용되는 것과 동일). 기존 콜러 재확인: `Hg.heads()`
    포셀린 진입점 외에는 아무도 `HeadsCommand`를 쓰지 않고, 내부 push/pull 로직
    (`PushCommand`/`PullCommand`/`HgLocalClient`)은 별도 head 계산을 쓴다는 문서의
    주장을 grep으로 재확인(import조차 없음) — 블라스트 반경은 `Hg.heads()` 콜러로
    한정됨. 기존 테스트(`HeadsCommandCoverageTest`, `PorcelainExtraCommandsTest`,
    `BranchRealHgInteropTest`)는 전부 단일 브랜치·단일 head 시나리오라 새 기본
    동작과도 결과가 동일해 변경 불필요였음(재확인만 함, 코드 수정 없음). **신규
    테스트**: `HeadsRealHgInteropTest`(신설, `src/test/java/io/github/search5/hg4j/api/`)
    3건 — (1) 브랜치 A의 head가 다른 브랜치로 머지되어 저장소 전역 위상 리프는
    아니지만 자기 브랜치 안에서는 여전히 head인 정확한 백로그 23 재현 시나리오에서
    hg4j의 새 기본 `call()`과 real hg의 맨 `hg heads`가 정확히 일치하고,
    `setTopo(true)`와 real hg `hg heads --topo`도 정확히 일치함을 확인, (2) 여러
    브랜치에 걸친 head 목록의 리비전 내림차순 정렬 순서까지 real hg와 정확히 일치,
    (3) `setTopo(true)`에서 closed head가 (real hg처럼) 여전히 위상 리프로 포함됨을
    확인 — 전부 real hg 7.2 host-native CLI 왕복 검증, GREEN. 전체 회귀 스위트
    2403건 중 신규 실패 없음(`ShelveRealHgInteropTest`의 무관한 1건 실패는 단독
    실행 시 GREEN으로 재확인된 병렬 실행 환경 문제로, 이 변경과 무관).

    **merge**: ✅ **완료(2026-09-04)**. fast-forward는 이미 부분 검증돼 있었고,
    진짜 3-way merge(공통 조상에서 양쪽이 다른 파일을 수정 — 충돌 없음)와 서로
    다른 브랜치 간 merge는 기존 `CHgMergeInteropTest`가 이미 real hg CLI와
    양방향 대조 중이었다(dev/default 두 브랜치, real hg `verify`/`cat`으로
    확인). 충돌이 실제로 나서 `resolve`가 필요한 case도 기존
    `MergeStateInteropTest`가 이미 양방향(실제 hg가 만든 `.hg/merge/state2`를
    hg4j가 읽기, hg4j가 만든 걸 real hg `resolve --list`가 읽기) 검증 중이었다
    — 이 셋은 "미검증"이 아니라 이미 완료돼 있던 것으로 재확인했다.

    남은 두 시나리오를 이번 세션에 신설 `MergeRealHgInteropTest`(3개 테스트)로
    검증: (1) rename/copy가 한쪽 브랜치에만 있는 상태의 merge — real hg로
    `a.txt`를 `b.txt`로 rename한 브랜치를 hg4j `MergeCommand`+`CommitCommand`로
    병합한 뒤, real hg `hg log --follow b.txt`가 rename 이전 이력까지 정상
    추적함을 확인(copy 추적 생존 확인 — 버그 아님, `CommitCommand`가 내용
    불변 파일은 원래 filelog 노드를 그대로 재사용하는 기존 로직 덕분에 이미
    성립하고 있었다). (2) merge 중단 — **`hg merge --abort`에 대응하는 명령이
    hg4j에 전혀 없었다**(신규 확인). `UpdateCommand.setForce(true)`로 우회하려
    해도 안 된다: `UpdateCommand`는 "기록된 이전 parent1 manifest"와 "target
    manifest"를 diff하는데, merge 직후 dirstate의 parent1은 이미 p1 그대로라
    diff가 텅 비어 아무것도 되돌리지 못한다.

    **기능 추가**: `MergeCommand.abort()` 신설 — real hg `hg merge --abort`와
    동일하게(real hg 7.2로 직접 재현해 확인: 다른 parent에서만 추가된 파일은
    삭제되고, 수정된 파일은 p1 내용으로 복원되며, 단일 parent로 복귀하고
    `.hg/merge/state2`가 삭제됨) 동작하도록 p1 manifest 기준으로 모든 경로를
    무조건 다시 쓴다(`UpdateCommand`처럼 diff에 의존하지 않는다 — 위 이유로
    diff가 항상 비어 있기 때문). 병합 중이 아닐 때 호출하면 real hg의
    `"abort: no merge in progress"`와 같은 취지로 거부한다
    (`src/main/java/io/github/search5/hg4j/api/MergeCommand.java`).

    **테스트**: `MergeRealHgInteropTest`(3개: copy 추적 생존, merge abort real
    hg 왕복 대조, 병합 중 아닐 때 abort 거부) 신설, 전부 GREEN.

    **tag**: ✅ **완료(2026-09-04)**. 전역 태그(`.hgtags`, 커밋되는 파일) 생성 후
    `hg tags`로 조회, 로컬 태그(`.hg/localtags`, 미커밋), 기존 태그를 재태깅(move,
    `.hgtags`에 새 줄 추가되고 이전 줄은 사문화), 태그 삭제(`hg tag --remove`),
    머지 커밋에 태그를 다는 경우 — 전부 real hg 7.2.2와 양방향(hg4j로 쓰고 real
    hg로 읽기, real hg로 쓰고 hg4j로 읽기) 왕복 검증했다.

    **기능 추가**: `TagCommand`에 로컬 태그 생성(`setLocal(boolean)`)과 태그 삭제
    (`setRemove(boolean)`)가 아예 없었다 — `TagsCommand`는 이미 `.hg/localtags`를
    읽고 nullid를 "삭제됨"으로 처리하는 로직이 있었지만, 그걸 만들어내는 쓰기 쪽
    (`TagCommand`)에는 그런 옵션 자체가 없어서 로컬 태그/태그 삭제 시나리오를
    hg4j만으로는 재현할 방법이 없었다. 두 옵션을 추가해 real hg와의 왕복 검증이
    가능해졌다(`src/main/java/io/github/search5/hg4j/api/TagCommand.java`).

    **발견한 실제 버그(2건, 수정 완료)**:
    1. `TagCommand`가 태그 커밋 메시지에 40자리 전체 hex를 썼는데, real hg의
       `commands.tag()`는 `mercurial.node.short()`(12자리 축약 hex)를 쓴다
       (`"Added tag %s for changeset %s" % (names, short(node))`). real hg
       interop 테스트(`hg4jGlobalTagIsRecognizedByRealHgTags`)가 커밋 메시지를
       실제 `hg log`로 대조하다가 이 불일치를 바로 잡아냈다 — hg4j↔hg4j 자체
       왕복이었다면 두 쪽 다 같은(틀린) 40자리를 썼을 것이므로 못 잡았을 시나리오.
    (같은 세션에서 위 **branch** 항목의 `BranchesCommand` 정렬 버그도 별도로
    발견·수정했다 — 태그 자체와는 무관한 문제.)

    **범위 내 확인**: `TagCommand`의 태그 나열(인자 없이 호출)은 여전히 `.hgtags`만
    읽는 단순화된 모델이고(`TagsCommand`처럼 `.hg/localtags`/`tip` 의사 태그를
    합치지 않음) — 이는 기존에 이미 클래스 문서에 명시된 의도된 단순화이며 이번
    범위에서 고치지 않았다(쓰기 경로에만 local/remove를 추가했고, 읽기 경로 검증은
    `TagsCommand`를 사용). 기존 태그가 있을 때 `-f` 없이 재태깅을 거부하는 real
    hg의 게이트(`tag '%s' already exists`)도 hg4j에는 없다(항상 허용) — 이 역시
    기존부터 있던 설계이고 백로그 23번 범위 텍스트가 명시적으로 요구한 부분이
    아니라 이번 세션에서는 변경하지 않았다.

    **branch/tag 테스트**: 신설 `BranchRealHgInteropTest`(6개 테스트: hg4j 생성
    브랜치/커밋 인식, 정렬+active 플래그, `--closed` 정렬(별도 시나리오), close-branch
    후 목록 제외, 브랜치 내부 분기 2-head, closed head 기본 제외)와
    `TagRealHgInteropTest`(8개 테스트: 전역 태그 양방향, 로컬 태그 양방향+같은
    이름 전역 태그보다 우선, 재태깅/move, 태그 삭제 양방향, 머지 커밋 태그)를
    `src/test/java/io/github/search5/hg4j/api/`에 추가, 전부 GREEN. 격리된 빌드
    디렉터리로 전체 회귀(2371개 테스트, 0 실패/에러, 10 skip은 기존부터 있던
    무관한 skip) 및 `io.github.search5.hg4j.api.*` 패키지 단독 재확인(1013개 테스트,
    0 실패/에러)도 통과.

    **rebase/shelve/bisect/strip/subrepo — 애초 "이미 다뤄졌으니 제외"로 초안에
    적었다가, 실제로는 아니라는 걸 위키 재확인으로 발견해 정정**: 이 5개는 지금까지
    받은 검증이 전부 "코드 리뷰로 버그 패턴 발견 → TDD RED/GREEN으로 hg4j 내부
    재현·수정 → hg4j 자체 회귀 테스트 재실행"이었다(예:
    `StripCommand`/`BisectCommand`의 워킹 브랜치 미복원 버그, `ShelveCommand`의
    racy-write 감지 버그 — 전부 실제 버그를 잡아낸 유의미한 작업이지만, 결과물을
    **실제 hg CLI와 대조한 적은 없다**). `Subrepositories` 행은 근거 서술 없이
    표에 "✅"만 달려 있어 5개 중 가장 불확실 — 최우선 재확인 대상. 그래서 이
    5개도 아래와 동일한 "실제 hg CLI 왕복" 기준으로 이 항목 범위에 포함한다:
    `rebase`(충돌 있는/없는 리베이스, `--continue`/`--abort`, obsolescence
    마커가 실제 hg `hg log --hidden`에서 인식되는지), `shelve`(shelve →
    다른 작업 → unshelve 왕복이 실제 hg가 만든 shelve와 서로 호환되는지),
    `bisect`(`hg bisect good/bad`로 실제 hg와 나란히 이분 탐색해 같은 culprit에
    도달하는지), `strip`(strip 후 저장소를 실제 hg `hg verify`로 확인),
    `subrepo`(`.hgsub`/`.hgsubstate`를 낀 커밋/업데이트가 실제 hg의 subrepo
    처리와 일치하는지 — 표의 "✅"부터 재검증).

    **`subrepo` 카테고리 ✅ 완료(2026-09-04)** — 표의 "✅"는 실제로 근거 없는
    상태였음이 확인됨(진짜 버그 2건 발견·수정: `CommitCommand`에 subrepo 인식
    로직 자체가 전혀 없었고, `HgRepository.scanWorkingCopy()`가 서브저장소
    경계를 인식 못 해 체크아웃된 서브저장소 내부 파일이 부모 저장소 자신의
    추적 파일로 잘못 add/commit되는 심각한 버그였음). 4개 시나리오(파싱/
    커밋 시 자동 `.hgsubstate` 생성·real hg 인식/서브 리비전 변경 후 dirty-check
    거부 및 재귀 커밋/실제 hg가 만든 두 pin 리비전 사이 hg4j update) 전부 실제
    hg 7.2 CLI 양방향 대조 통과(`SubrepoRealHgInteropTest`, 신규 4건). 의도적
    발산과 범위 밖으로 남긴 것(CloneCommand의 재귀 서브저장소 clone 미구현 등)은
    코드 주석 및 위 gap table `Subrepositories` 행에 상세 기록. 전체 회귀
    2362건 GREEN. 상세 근거는 위 gap table의 `Subrepositories` 행 참고.

    **`rebase` 카테고리 ✅ 완료(2026-09-04)** — 이 5개 중 유일하게 hg4j
    자체 왕복조차 "커밋 메시지/changelog 부모 연결만 확인, 뒤바뀐 커밋의
    manifest/파일 내용은 한 번도 assert하지 않음"이었다는 게 실제 hg 대조
    과정에서 드러났다. 신설 `RebaseRealHgInteropTest`(4개 테스트)로 검증하다가
    **실제 버그 3건**을 발견·수정했다(전부 real hg가 만든 저장소를 hg4j
    `RebaseCommand`로 rebase한 뒤 real hg `verify`/`cat`/`debugobsolete`/
    `log --hidden`으로 대조하다가 나왔다 — hg4j 내부 왕복이었다면 절대 못
    잡았을 종류):

    1. **`stripRevisionsFrom`이 inline revlog를 전혀 고려하지 않고 있었다**
       (가장 심각). real hg는 작은 revlog(막 만들어졌거나 커밋이 몇 개 안
       되는 저장소의 manifest/filelog 대부분)를 별도 `.d` 데이터 파일 없이
       `.i` 파일 안에 헤더+데이터를 인터리빙해서 저장하는데(real hg 7.2로
       직접 확인: 2커밋짜리 저장소의 `00manifest.i`엔 `00manifest.d`가
       아예 없음), `stripRevisionsFrom`은 항상 "리비전 수 × 64바이트"로만
       `.i`를 자르고 있었다 — inline 저장소에서 이건 앞쪽 리비전들의 데이터
       바이트를 통째로 잘라버려 revlog를 깨뜨린다. hg4j 자체 테스트는 전부
       hg4j `CommitCommand`로 만든(항상 non-inline인) 저장소만 써서 이
       경로를 한 번도 밟지 않았다. `Revlog`에 `isInline()`/`getFileOffset(int)`
       공개 접근자를 추가하고, `RebaseCommand.stripRevisionsFrom`을 두
       레이아웃 모두를 올바르게 절단하는 공용 헬퍼로 재작성
       (`src/main/java/io/github/search5/hg4j/storage/Revlog.java`,
       `src/main/java/io/github/search5/hg4j/api/RebaseCommand.java`).
    2. **rebase로 새로 추가된 파일이 결과 커밋의 manifest에서 통째로 사라짐**
       (데이터 손실, 버그 1을 고친 뒤에야 드러남). `RebaseCommand.cherryPickBackup`은
       cherry-pick하는 모든 파일을 dirstate 상태 `'n'`(변경 없음)으로 기록하는데,
       `CommitCommand`의 "변경 없음" 분기는 그 경로가 두 parent 중 어느 쪽
       manifest에도 없으면(= target에 없던 완전히 새 파일) 그냥 아무것도 안
       하고 넘어가는 else-분기가 없었다 — 결과 manifest에 그 파일 항목 자체가
       빠졌다. `CommitCommand`에 "`'n'`으로 기록됐지만 두 parent 어디에도 없는
       경로는 unchanged일 수 없으니 강제로 해시해서 새 filelog 리비전을
       만든다"는 가드를 추가해 고쳤다(`RebaseCommand` 전용이 아니라
       `CommitCommand` 공통 로직 버그였다 —
       `src/main/java/io/github/search5/hg4j/api/CommitCommand.java`).
    3. **obsolescence marker를 물리적 strip과 동시에, 항상 무조건 남긴다** —
       real hg는 evolution이 꺼져 있으면(기본값) rebase 때 marker를 아예 안
       남기고 strip만 하며, evolution이 켜져 있으면 strip 없이 marker만
       남긴다(원본이 "hidden revision"으로 남아 `hg log --hidden`에서 보임).
       hg4j는 이 둘을 동시에 해서, marker가 가리키는 predecessor가 changelog에서
       완전히 사라진 상태가 된다 — `hg log --hidden`으로 찾으면 "hidden"이
       아니라 "unknown revision" 에러가 난다(real hg 7.2로 직접 재현해
       확인). 게다가 evolution을 쓸 생각이 전혀 없는 사용자가 평범한 rebase를
       기대하고 hg4j를 썼더라도, 이후 그 저장소에 대한 모든 real hg 명령이
       `"obsolete" feature not enabled but 1 markers found!` 경고를 stdout에
       찍는 부작용이 생긴다(real hg의 `experimental.evolution` 클라이언트
       설정에 의해 결정되는 것이라 저장소 쪽 `.hg/requires`로 끌 수 있는
       종류가 아님도 확인 — `obsstore`를 requires에 넣으면 real hg가 아예
       "unknown requirement"로 저장소를 못 엶). **이 부분은 고치지 않고 현재
       동작을 `RebaseRealHgInteropTest`에 그대로 문서화만 해뒀다** — strip과
       marker 동시 존재는 real hg가 절대 하지 않는 조합이라 (a) marker를
       아예 안 남기고 순수 strip만 하거나(원래 설계 의도인 "완전한 물리적
       strip 기반 rebase"에 가장 가까움) (b) strip을 그만두고 marker만
       남기는 evolution 방식으로 전환하는 두 방향 중 하나를 선택해야 하는데,
       **이건 이 세션 판단으로 정할 architecture 결정이 아니라 사용자
       확인이 필요하다**(아래 "아키텍처 수준 확인 필요" 참고).

    **→ 결정(2026-09-04): 둘 다 "정석대로 완전 구현" 확정** — 위 두 아키텍처
    질문(obsolescence marker의 strip-and-mark 동시 수행 여부, rebase conflict
    감지+3-way merge+`--continue`/`--abort` 구현 여부) 모두 사용자가 "지름길 없이
    정석대로 완전히 구현하라"고 명시적으로 확정했다. 같은 날 늦게 별도 세션에서
    두 항목 다 구현 완료.

    **4. evolution-only로 전환(물리적 strip 제거) ✅ 완료** — `RebaseCommand`는
    이제 원본 리비전을 절대 물리적으로 strip하지 않는다. cherry-pick된 원본은
    changelog/manifest/filelog에 영원히 완전한 형태로 남고, `HgObsMarker.writeMarker`
    (predecessor → successor)만 기록된다 — real hg 자신의 두 상호배타적 전략(marker
    없이 순수 strip, 또는 strip 없이 marker만) 중 후자와 정확히 일치, 이전처럼 둘을
    동시에 하지 않는다. `stripRevisionsFrom`/`computeTruncateSizes`/`restoreBackup`/
    `BackupCommit.fileContents` 등 "전체 [minOrigRev,tip] 구간을 통째로 strip한 뒤
    재구성"하던 옛 설계 전체를 삭제 — 원본이 사라지지 않으므로 "독립 브랜치를
    물리적으로 복원"할 필요 자체가 없어져 코드가 크게 단순해졌다. 신설
    `originalRevisionIsHiddenNotGoneAfterRebase` 테스트로 real hg 7.2 CLI 직접
    검증: `hg log --hidden -r <원본>`이 이제 "unknown revision"이 아니라 원본
    노드를 그대로 찾고(`{desc}`/`cat` 내용도 원본 그대로), 반대로 `--hidden` 없는
    평범한 `hg log`/`hg log -G`에는 나타나지 않는다(살아있는 non-obsolete
    successor가 있어 기본적으로 숨김) — real hg의 evolution 기반 rebase와 동일한
    결과.
    - **이 전환 과정에서 드러난 별도의 심각한 버그(예상 밖)**: `Revlog.appendRevision`이
      새 리비전의 nodeId를 `SHA1(p1,p2,content)`로 계산해두고도, 그 nodeId가 **이미
      해당 revlog에 존재하는지 전혀 확인하지 않고 무조건 append**하고 있었다. strip
      기반 설계에서는 rebase 시작 전에 filelog를 통째로 잘라내 버려서 이 경로를 밟은
      적이 없었지만, evolution-only로 바뀌어 원본이 그대로 남으면서 "target에 없던
      완전히 새 파일을 cherry-pick"하는 흔한 경우(parent 없음 + 원본과 동일한 내용
      → 원본과 SHA1 입력이 완전히 같음)마다 **동일한 nodeId를 가진 filelog 리비전
      2개**가 생겨 real `hg verify`가 즉시 "`duplicate revision 1 (0)`"/"`not in
      manifests`" integrity error로 잡아냈다(`RebaseRealHgInteropTest`의
      `conflictFreeRebaseVerifiedByRealHg`로 실제 hg 7.2 검증 중 발견). Real hg
      자신의 `revlog.addrevision`/`filelog.add`가 항상 하는 "동일 (parents,content)
      조합이 이미 있으면 기존 리비전을 재사용"을 `Revlog.appendRevision`에 추가해
      수정 — `RebaseCommand`뿐 아니라 같은 메서드를 쓰는 `ImportCommand`/
      `HisteditCommand`/`CommitCommand` 전부가 이 잠재 버그의 수혜자다(전체 회귀
      그대로 GREEN 확인됨). 상세: `src/main/java/io/github/search5/hg4j/storage/Revlog.java`
      `appendRevision`.

    **5. 진짜 3-way merge 충돌 감지 + `continueRebase()`/`abort()` ✅ 완료** —
    `RebaseCommand`의 cherry-pick 경로가 이제 `MergeCommand`와 같은 `Merge3` 엔진으로
    실제 3-way merge를 시도한다: ancestor = 원본 리비전 자신의 parent가 갖고 있던
    파일 내용, local = 현재 목적지(dest, 체인의 이전 cherry-pick 결과 포함)의 내용,
    other = 원본 리비전이 새로 만든 내용. 정말 겹치면(자동 병합 불가) 충돌 마커를
    작업 파일에 쓰고(`<<<<<<< dest` / `=======` / `>>>>>>> source`, real hg 7.2의
    기본 `internal:merge` 마커와 byte-for-byte 일치 — base 섹션 없음, 직접 재현해
    검증) `io.github.search5.hg4j.errors.HgMergeConflictException`(충돌 경로 목록
    포함, 새 `getConflictPaths()` 접근자 추가)을 던지며 rebase를 일시정지한다.
    충돌 파일 상태는 `MergeCommand`가 이미 쓰는 것과 완전히 같은 real-hg 호환
    포맷(`io.github.search5.hg4j.merge.MergeState`, `.hg/merge/state2`)에 기록되므로
    real hg CLI `hg resolve --list`가 그 결과를 그대로 읽어 "U f.txt"를 보여준다
    (직접 검증). `RebaseCommand`에 새 공개 메서드 2개 추가:
    `continueRebase()`(사용자가 파일을 수동으로 고치고 저장한 뒤 호출 — 일시정지된
    리비전의 커밋을 완료하고 남은 큐를 이어서 처리, 다음 리비전도 충돌하면 다시
    `HgMergeConflictException`으로 정지) / `abort()`(이번 rebase 시도로 이미 커밋된
    것까지 전부 포함해 changelog/manifest/filelog를 rebase 시작 전 바이트 그대로
    복원하고 작업 사본·dirstate도 원래 체크아웃 상태로 되돌림, real hg의 `hg rebase
    --abort`와 동일). 두 메서드 모두 **디스크에 영속화된 상태**(`.hg/rebasestate-hg4j`,
    hg4j 전용 텍스트 포맷 — real hg 자신의 바이너리 `.hg/rebasestate`와는 무관, 중간
    재개 상태 자체의 real-hg interop은 목표가 아니었고 최종 상태만 real hg와
    맞으면 됨)로 동작하므로, 처음 충돌을 만난 것과 **다른 새 `RebaseCommand`
    인스턴스**로도 이어서 호출 가능(직접 검증). 미해결 충돌이 남았는데
    `continueRebase()`를 부르거나, 진행 중인 rebase가 없는데 `abort()`/
    `continueRebase()`를 부르면 real hg의 "abort: no rebase in progress"와 같은
    취지로 `HgValidationException`을 던진다.
    - **부수 발견**: `.hg/merge/state2`만 지우고 완료 처리하면, 사용자가 중간에 실제
      `hg resolve --mark`를 돌려서 real hg 자신이 함께 써둔 레거시 v1
      `.hg/merge/state` 파일이 남아 있어 `hg resolve --list`가 완료 후에도 "R f.txt"를
      계속 보여주는 문제가 있었다(real hg의 `mergestate.read()`가 state2 없으면 v1로
      폴백) — `.hg/merge` 디렉터리 전체를 지우는 것으로 수정.

    상세 구현 위치: `src/main/java/io/github/search5/hg4j/api/RebaseCommand.java`
    (cherry-pick당 실제 diff 계산 + 3-way merge는 `cherryPickRevision`, 병합
    commit들은 `processQueue`/`finalizeRebase`, 일시정지 상태 직렬화는
    `writeRebaseState`/`readRebaseState`), `src/main/java/io/github/search5/hg4j/merge/Merge3.java`
    (충돌 마커 라벨을 커스텀할 수 있는 새 오버로드 `merge(base,yours,theirs,yoursLabel,theirsLabel)`
    추가, 기존 `merge(base,yours,theirs)`는 `"Yours"/"Theirs"` 기본값으로 위임 —
    `MergeCommand`는 그대로 옛 동작 유지), `src/main/java/io/github/search5/hg4j/errors/HgMergeConflictException.java`
    (기존에 정의만 되고 아무도 안 쓰던 클래스를 이제 실제로 사용 — 복수 충돌 경로를
    담는 `List<String>` 생성자/`getConflictPaths()` 추가).

    **테스트**: `RebaseRealHgInteropTest`를 6개로 재작성(`conflictFreeRebaseVerifiedByRealHg`,
    `plainRealHgCommandsDoNotWarnAfterHg4jRebase`는 유지, `originalRevisionIsHiddenNotGoneAfterRebase`
    가 옛 `obsoleteMarkerAfterRebaseStripPointsAtNodeGoneFromChangelog`를 대체(정반대
    결과를 검증하도록), `conflictingEditWritesConflictMarkersAndPausesRebase`가 옛
    `conflictingEditIsSilentlyOverwrittenInsteadOfDetectedAsConflict`를 대체, 신규
    `abortAfterConflictRestoresPreRebaseState`/`continueRebaseAfterManualResolutionCompletesTheRebase`
    추가) — 전부 real hg 7.2 CLI 왕복 기준, 전부 GREEN. 기존 `RebaseCommandTest`/
    `RebaseCommandCoverageTest`/`HgAdvancedHistoryTest`도 옛 물리적 strip 전제(정확한
    리비전 개수 등)에 맞춰 갱신, 격리된 빌드 디렉터리(`/tmp/backlog-rebase-overhaul`)로
    전체 회귀 재실행해 GREEN 확인.

    **범위(제외)**: 위 10개 카테고리 전부 **hg4j↔hg4j 자체 왕복이 아니라 실제 hg
    CLI와의 양방향 대조**(hg4j로 만든 결과를 실제 `hg log`/`hg verify`/`hg tags`/
    `hg branches`로 확인, 또는 그 반대)를 검증 기준으로 삼는다 — 이미 이런 형태로
    실제 hg와 대조하지 않고 hg4j 내부끼리만 왕복 검증된 기존 테스트는 이 항목에서
    "미검증"으로 간주하고 다시 본다.

    **진행 상황(2026-09-04)**: `commit`/`push` ✅ 완료(각 섹션 참고, 병렬 세션).
    나머지 `branch`/`merge`/`tag`/`rebase`/`shelve`/`bisect`/`strip`/`subrepo`
    8개는 다른 병렬 작업에서 처리 중이거나 아래 "다음 세션 시작점" 그대로
    미착수.

    **다음 세션 시작점**: 착수 비용 기준으로는 `tag`(코드 경로는 오늘 커버리지
    작업으로 이미 확인 끝, interop 껍데기만 씌우면 됨)가 가장 낮지만, **위험도
    기준으로 최우선이었던 `subrepo`는 2026-09-04 완료**(위 완료 노트 참고) —
    남은 9개 카테고리(commit/push/branch/merge/tag/rebase/shelve/bisect/strip)
    는 여전히 미착수 상태로 남아 있음.

    **`shelve`/`bisect`/`strip` — ✅ 완료(2026-09-04, 별도 병렬 에이전트)**. 실제 hg CLI
    양방향 대조 기준으로 재검증, 실제 버그 4개 발견·수정. `rebase`/`subrepo`는 다른
    병렬 작업에서 별도로 처리 중(이 세션에서는 건드리지 않음).

    - **`strip`**: `StripCommand.truncateRevlog()`의 `.d`(데이터) 파일 truncate가 정확한
      오프셋이 아니라 "`datFile.length() * keepCount / (keepCount+1)`"라는 근사치
      추정("Safe estimation fallback")을 쓰고 있었다 — 리비전 크기가 들쭉날쭉하면 살아남을
      리비전의 델타 바이트를 잘라버리는 **실데이터 파괴 버그**. `StripRealHgInteropTest`로
      크기가 크게 다른 리비전들을 strip한 뒤 real `hg verify`를 돌려서 실제로 재현시켰다
      ("data length off by N bytes"/"partial read of revlog" 등). `changelog.getIndexRecord
      (keepCount).getOffset()`(이미 `ShelveCommand.stripRevisionsFrom`이 쓰던 정확한 방식)로
      교체해 수정. 같은 검증 과정에서 발견된 부수 버그 2개도 함께 수정: (1) `StripCommand`가
      obsolescence marker를 무조건 쓰는데, real hg는 `experimental.evolution.createmarkers`
      config가 꺼진 채로 markers를 쓰면 `hg debugobsolete` 자체를 거부하고, real hg 7.2.2로
      직접 확인한 결과 markers가 있는데 그 config가 꺼져 있으면 이후 `hg verify`가 "obsolete
      feature not enabled but N markers found!"로 플래그한다 — `HgObsMarker.writeMarker()`가
      최초로 marker를 쓸 때 repo `.hg/hgrc`에 그 config를 심어두도록 고쳤다(amend/graft/
      rebase/histedit도 같은 헬퍼를 공유하므로 함께 수정됨 — 공유 코드 변경이니 병합 시
      확인 필요). (2) (미수정, 별도 기록) hg4j는 파일 크기와 무관하게 항상 non-inline
      filelog(`.i`/`.d` 분리)를 쓰는데 real hg는 작은 revlog를 inline으로 유지한다 — 그
      차이 때문에 real `hg verify`가 hg4j가 만든 저장소에 대해 "`warning: revlog 'X.d' not
      in fncache!`" 경고를 내지만(strip과 무관하게 **모든** hg4j 커밋에 존재하는 사전
      버그), 이건 exit code에 영향 없는 경고(`self._warn`, `self.errors`엔 안 들어감)라
      strip의 "real hg verify 통과" 기준 자체는 막지 않는다 — fncache에 `.d` 항목을
      등록하는 CommitCommand 쪽 수정은 이번 범위 밖으로 남겨둠. 테스트:
      `StripRealHgInteropTest`(2개, real `hg verify`/`hg cat`으로 strip 후 내용 무결성
      확인) 신설.
    - **`bisect`**: `BisectCommand`의 이분 탐색 알고리즘(이미 real hg `hbisect.py`와 맞춰
      구현돼 있었음)을 진짜로 real hg와 나란히(동일 good/bad 오라클로 각자 독립 진행) 15개
      리비전 선형 히스토리에서 실행 — 후보 리비전 시퀀스(`[7,10,8,9]`)와 최종 culprit이
      완전히 일치함을 확인. 버그 없음(기존 코드 리뷰 기반 구현이 처음부터 맞았음). 테스트:
      `BisectRealHgInteropTest`(1개) 신설. **미검증으로 남은 부분(정직히 기록)**: merge
      커밋이 있는 DAG(브랜치 2개가 합쳐지는 히스토리)에서의 bisect는 real hg와 대조하지
      않음 — 시간 제약으로 선형 히스토리 1개 시나리오만 검증.
    - **`shelve`**: 가장 규모가 컸다. 실제 hg가 만든 `.hg`/`.patch`/`.shelve` 파일을
      hexdump·real hg 소스(`mercurial/shelve.py`/`bundle2.py`/`exchange.py`/
      `changegroup.py`)와 직접 대조해 **hg4j의 기존 shelve 포맷이 real hg와 완전히
      호환 불가능**함을 확인했다(자세한 내용은 최종 보고 참고). 다음 실버그 3개를 고쳐
      "shelve → 다른 작업 → unshelve" 왕복(양방향)이 real hg와 실제로 맞물리게 만들었다:
      (1) `.hg` 번들이 아예 매직 헤더 없는 hg4j 전용 원시 포맷이라 real hg가 번들로
      인식조차 못함 → `Bundle2Parser.wrapChangegroupInBundle2()`(기존 push 경로 인프라
      재사용)로 HG20/bundle2 봉투를 씌우도록 수정. (2) 델타가 항상 "빈 문자열 기준"으로
      인코딩돼 있어(수정된 파일도 매번 전체 내용을 "복사 없이 삽입"하는 델타), 그 파일이
      hg4j 자기 자신의 (비표준) 재생 로직과만 맞물렸고 real hg의 표준 cg1 델타 적용
      의미론(선언된 delta base 기준으로 복원)과는 애초에 안 맞음 → 실제 parent/base
      콘텐츠 기준으로 정식 델타를 인코딩하도록 `performShelve()` 전면 수정, `performUnshelve
      ()`도 대응 수정. (3) real hg의 cg02+ changegroup 엔트리는 `p1`(진짜 changelog
      부모)과 `deltabase`(델타가 실제로 기준하는 리비전, 둘이 다를 수 있음 — real hg
      shelve가 실제로 `deltabase=null`(full-text 델타)인데 `p1`은 진짜 이전 리비전을
      가리키는 엔트리를 만드는 것을 real hg CLI로 직접 확인)이 별개 필드인데 hg4j는 이
      구분을 몰랐음 → `deltaBaseNode()` 헬퍼로 `deltabase` 우선, 없으면 `p1` 폴백하도록
      수정. real hg의 shelve bundle이 "shelve commit 하나만"이 아니라 draft 상태인 부모
      커밋까지 함께 묶는다는 것도 발견(`mutableancestors()`가 자기 자신+아직 draft인
      조상까지 포함) → `.shelve` info 파일(real hg 포맷 `node=<hex>` 그대로, `performShelve
      ()`가 이제 항상 씀)로 진짜 shelve 커밋 엔트리를 골라내도록 수정. hg4j 자신의
      `.state` 파일 기반 왕복은 100% 하위 호환 유지(기존 `ShelveCommandTest`/
      `ShelveCommandCoverageTest` 전부 그대로 GREEN). 테스트: `ShelveRealHgInteropTest`
      (4개 — hg4j→real hg 수정+추가 파일, real hg→hg4j 수정+추가 파일, 제거 파일 시나리오,
      모두 "shelve → 중간에 무관한 다른 작업 → unshelve" 형태) 신설, 전부 GREEN.
      **→ 결정(2026-09-04): 지금 구현하는 쪽으로 확정**(rebase 3-way merge 인프라
      이식과 함께 진행, 위 "아키텍처 결정 6건" 참고) — **✅ 완료(2026-09-04, 같은 날
      후속 세션)**.

      **진짜 rebase 기반 unshelve ✅ 완료** — `ShelveCommand.performUnshelve()`가
      real hg의 실제 `_dounshelve()` 알고리즘(`mercurial/shelve.py`)으로 전면 재작성됐다:
      (1) 셸브 번들을 원래 shelve 당시의 parent(`p1Hex`) 위에 **진짜(그러나 일회용)
      커밋**으로 복원(기존 per-file 번들 디코드 로직은 그대로 재사용 — 이번엔 그 결과를
      working copy에 바로 노출하는 대신 `CommitCommand`로 실제 커밋한다), (2) 그 사이
      작업 디렉터리 parent가 이동했으면(다른 커밋이 생겼으면) `RebaseCommand.setSource
      (tempCommit).setTarget(currentWdParent).call()`로 **진짜 rebase**(3-way merge
      충돌 감지 포함, 새로 구현하지 않고 항목 5의 `RebaseCommand`를 그대로 구동) —
      아무 일도 없었으면(parent가 그대로면) no-op. (3) 결과를 real hg의 `cmdutil.revert
      (shelvectx)`와 동일하게 **"uncommit"**: 최종(rebase 성공 시 rebase 결과, 아니면
      복원 커밋 자체)의 매니페스트를 진짜 현재 작업 디렉터리 parent의 매니페스트와
      diff해서 그 차이만 working copy에 pending 변경(added/modified/removed)으로
      다시 얹는다 — 이 diff는 shelve 당시 캡처해둔 파일 상태(`.state` 파일의
      add/modify/remove 분류)가 아니라 **매번 새로 계산**하므로, 그 사이 커밋이 같은
      파일을 건드린 경우도 올바르게 처리된다. (4) 일회용 복원/rebase 커밋은 **완전히
      지운다** — real hg CLI로 직접 확인(2026-09-04): real hg 자신의 unshelve는 이
      전체 과정을 트랜잭션 안에서 수행하고 마지막에 그 트랜잭션을 abort하므로, 임시
      커밋이 hidden/obsolete 리비전으로도 전혀 남지 않는다(`RebaseCommand`가 평소
      쓰는 evolution-only 방식과 달리, 이번엔 marker 없이 `stripRevisionsFrom`으로
      물리적 truncate). 새 hg4j 전용 상태 파일 `.hg/shelvedstate-hg4j`(name/
      tempCommitNode/originalWdParent 3줄)에 진행 상황을 영속화해, **`RebaseCommand`와
      동일한 패턴**(새 `ShelveCommand` 인스턴스로도 재개 가능)으로 두 공개 메서드를
      추가했다: `unshelveContinue()`(충돌 해결 후 재개 — 내부적으로
      `RebaseCommand.continueRebase()`를 그대로 위임 호출한 뒤 같은 uncommit+strip
      마무리를 수행) / `unshelveAbort()`(real hg의 `hg unshelve --abort`와 동일 —
      `RebaseCommand.abort()`로 진행 중이던 rebase를 걷어낸 뒤 임시 커밋도 strip하고
      작업 디렉터리를 진짜 unshelve 시작 전 상태로 완전히 되돌리되, **완료된 unshelve와
      달리 shelve 자체는 지우지 않아** 나중에 다시 시도할 수 있게 남겨둔다 — 직접
      검증). 두 메서드 모두 `.hg/rebasestate-hg4j`/`.hg/shelvedstate-hg4j`만으로
      동작하므로 이름/상태를 다시 지정할 필요가 없다.
      - **불변식 하나 새로 추가**: unshelve 시작 시 현재 작업 디렉터리에 pending
        변경(added/modified/removed)이 있으면 `HgValidationException`으로 거부한다
        (real hg는 `_commitworkingcopychanges()`로 그런 변경까지 임시 커밋해서 흡수하는
        일반화된 경로가 있지만, hg4j는 아직 그 일반화를 구현하지 않음 — 명시적 범위
        축소, 새 아키텍처 갈림길은 아님). 현재 parent2가 0이 아니면(미해결 merge 진행
        중) 마찬가지로 거부.
      - **공유 코드에서 발견·수정한 실제 버그 3개(이번 재작성이 처음 밟은 코드
        경로라 여태 안 걸렸던 것들)**:
        1. `ShelveCommand.stripRevisionsFrom()`(옛날부터 있던, `performShelve()`
           자신의 일회용 임시 커밋을 지우는 헬퍼)가 `.i` truncate 크기를 항상
           `rev * 64`로 계산 — real hg의 **inline revlog**(작은 revlog는 리비전
           데이터를 `.d` 파일 없이 `.i` 파일 안에 헤더 바로 뒤에 직접 붙여 쓰는 기본
           포맷)에서는 완전히 틀린 오프셋이라 레코드 중간을 잘라 저장소를 깨뜨린다.
           이 버그는 지금까지 **hg4j 자신이 만든(항상 non-inline) 저장소에서만
           `stripRevisionsFrom`이 호출됐기 때문에** 한 번도 걸리지 않았다 — unshelve가
           **real hg가 만든(따라서 inline인) 저장소** 위에서 처음으로 실제 임시
           커밋+strip을 수행하면서 `ShelveRealHgInteropTest.realHgShelveCanBeUnshelvedByHg4j`
           가 `HgCorruptDataException: Truncated inline revlog data`로 바로 재현시켰다.
           `Revlog.isInline()`/`Revlog.getFileOffset(rev)`(이미 읽기 경로에 존재하던
           API)로 changelog/manifest/파일별 filelog 전부 inline 여부에 따라 분기하도록
           수정.
        2. `CommitCommand.call()`의 "M-2 racy-hg 체크"(같은 초 안에 재기록된 파일을
           dirstate 캐시만으로 오탐지하지 않기 위한 보정)가 "현재 filelog의 **위치상
           마지막** 리비전"과 디스크 내용을 비교해 다르면 "변경됨"으로 판정하고
           있었다 — 이 리비전이 지금 커밋 중인 리비전의 **진짜 parent가 아닌** 경우
           (예: 이번 unshelve의 rebase 단계에서, 목적지가 안 건드린 파일을 shelve
           쪽 내용으로 fast-forward할 때, 그 filelog가 그 사이 생겼다 지워질 임시
           복원 커밋 때문에 이미 최신 리비전을 하나 더 갖고 있는 경우) "마지막
           리비전과 같으면 무변경"이라는 잘못된 결론을 내려 fast-forward된 내용이
           통째로 드롭됐다(`ShelveRealHgInteropTest.unshelveRebasesOntoAnUnrelatedInterveningCommit`
           로 재현). "위치상 마지막"이 아니라 **실제 parent(들)이 그 경로에 대해
           기록한 매니페스트 해시**(`manifestP1`/`manifestP2`)의 콘텐츠와 비교하도록
           수정 — 병합 커밋에서는 P1/P2 중 **어느 한쪽이라도** 일치하면 무변경으로
           남겨 기존 바이트 단위 disambiguation 로직에 그대로 맡기고, 어느 쪽도
           읽을 수 없으면(filelog가 지워지는 등, 기존 커버리지 테스트가 의도적으로
           재현하는 상황) 마찬가지로 무변경으로 남겨 disambiguation의 기존 관용적
           fallback에 위임 — 전체 회귀(`CommitCommandCoverageTest` 포함) 그대로
           GREEN 확인.
        3. `StatusCommand`가 dirstate 엔트리의 mtime을 real hg의 표준 "ambiguous
           time" 센티널(32비트 "-1", 즉 `0xFFFFFFFF` — real hg가 내부적으로 워킹
           카피를 재작성한 직후 등, 캐시된 타임스탬프를 신뢰할 수 없을 때 쓰는 값)을
           전혀 특별 취급하지 않고 디스크 mtime과 그냥 숫자 비교해, real hg가 만든
           그런 엔트리를 hg4j가 읽을 때마다 무조건 "modified"로 오판했다
           (`ShelveRealHgInteropTest.realHgShelveCanBeUnshelvedByHg4j`가 이번에
           `new StatusCommand().call()`을 unshelve 시작 시 실제로 호출하면서 처음
           노출됨). 해당 센티널이면 무조건 실제 부모 커밋 내용과 바이트 비교하도록
           고쳤고, 거꾸로 `ShelveCommand` 자신도 unshelve가 새로 만들어내는 pending
           변경 엔트리(`finishUnshelve`, 상태 `'n'`인 것)에 이제 이 센티널을 쓴다 —
           실제 mtime을 즉시 기록해 "운 좋게 시간 창 안에 들어오면 통과"하던 기존
           방식(real hg의 `hg status`가 오탐하는 별도 타이밍 경쟁까지 새로 만들어냄,
           일회성 diff-replay보다 이번 rebase 기반 알고리즘이 I/O를 훨씬 많이 하므로
           그 경쟁을 더 자주 놓침)을 real hg 자신의 관례로 완전히 대체해 경쟁 자체를
           없앴다.

      **테스트**: `ShelveRealHgInteropTest`에 3개 신설 —
      `unshelveRebasesOntoAnUnrelatedInterveningCommit`(shelve → 무관한 커밋 →
      unshelve 성공, 진짜 rebase가 실행됨을 real hg `status`/`verify`/`log`로 확인,
      작업 디렉터리 parent가 그 무관한 커밋 그대로 유지됨도 확인), `unshelveWithConflictingInterveningCommitPausesResolvesAndContinues`
      (shelve → 같은 줄을 다르게 고치는 충돌 커밋 → unshelve가 `HgMergeConflictException`
      으로 일시정지, 마커가 real hg 7.2 `internal:merge`와 byte-for-byte 일치, real
      `hg resolve --list`로 확인 → 수동 해결 → **새 `ShelveCommand` 인스턴스**의
      `unshelveContinue()`로 완료, real `hg verify`/`status`로 확인), `unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable`
      (같은 충돌 시나리오에서 `unshelveAbort()`로 작업 디렉터리/dirstate가 unshelve
      시작 전 상태로 완전히 복원되고 shelve 자체는 그대로 남아 재시도 가능함을 확인).
      기존 `ShelveCommandTest`/`ShelveCommandCoverageTest`/`HgAdvancedHistoryTest`/
      `ShelveRealHgInteropTest` 기존 3개도 전부 그대로 GREEN(단, `ShelveCommandTest`의
      "손상된 parent1/parent2로 unshelve 시도" 서브케이스 2개는 옛 "shelve 당시
      parent와 정확히 일치해야 함" 검증이 이번에 rebase 기반으로 대체되며 의미가
      바뀌어 기대 예외 메시지만 갱신 — "does not match shelved parent" → 존재하지
      않는 리비전에 대한 "not found", "does not match shelved parent2" → "unresolved
      merge"; `ShelveCommandCoverageTest`의 `unshelveDefaultsToModifiedStateWhenFileMissingFromStateMetadata`
      도 최종 dirstate 상태 기대값만 `'m'`(hg4j 자체 관례, real hg에 없는 상태 문자)
      → `'n'`(real hg의 표준 "추적+수정됨" 상태, 위 diff 재계산 방식이 자연스럽게
      만들어냄)으로 갱신). 격리된 빌드 디렉터리(`/tmp/backlog-shelve-unshelve`)로
      `Shelve`/`Commit`/`Status`/`Rebase`/`Graft`/`Merge` 전체 및 전체 테스트
      스위트(249개 테스트 클래스) 재실행해 GREEN 확인.

      상세 구현 위치: `src/main/java/io/github/search5/hg4j/api/ShelveCommand.java`
      (`performUnshelve`/`finishUnshelve`/`checkoutFullClean`/`unshelveContinue`/
      `unshelveAbort`/`stripRevisionsFrom`), `src/main/java/io/github/search5/hg4j/api/CommitCommand.java`
      (M-2 racy 체크), `src/main/java/io/github/search5/hg4j/api/StatusCommand.java`
      (`AMBIGUOUS_TIME`).

