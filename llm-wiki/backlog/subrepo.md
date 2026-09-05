---
updated: 2026-09-06
status: item 32 completed; item 41 completed
---

# 백로그 32, 41: Subrepositories (Git/SVN)

관련 항목: 32(subrepo 잔여 gap 4건 — `CommitCommand`/`UpdateCommand` 공유, git
서브저장소 merge/commit 상태 갱신 포함, 사용자가 "범위 밖이라고 하는 건 없다"고 직접
정정하며 전부 완전 구현 지시), 41(SVN 서브저장소 `[svn]` prefix 지원 — 코드베이스에
관련 처리가 전혀 없어 맨땅에서 시작, 백로그 39 완료 후 신규 등록, 2026-09-06 완료 — git
서브저장소 구현(`GitSubrepoUtil`/`HgSubrepoParser`/`HgSubrepoEntry`)을 대칭 참고 모델로
사용).

## 원문
32. ~~**Subrepositories 잔여 gap 4건**~~ — ✅ **완료(2026-09-04)**. 4가지 모두
    real hg 7.2 CLI(+git)와 나란히 재현하는 TDD로 확인/수정했다.

    **(1) `CloneCommand`가 서브저장소를 재귀적으로 clone하지 않음.** `UpdateCommand`가
    이미 갖고 있던 재귀 서브저장소 체크아웃 블록(그때까지는 `UpdateCommand.call()`
    안에 인라인으로만 존재해 재사용 불가능한 상태였음)을 패키지 프라이빗 정적 메서드
    `UpdateCommand.recursiveSubrepoCheckout(HgRepository)`로 추출하고, `CloneCommand`가
    `checkoutLatest()` 직후 이를 호출하도록 배선했다. 이 추출 과정에서 (3)/(4) 수정도
    같은 메서드에 함께 반영되므로 clone도 자동으로 git 서브저장소 재귀 clone과 로컬
    존재 시 pull 생략 혜택을 받는다.

    **(2) `.hgsub`이 완전히 사라진 채 커밋.** 실제 hg 7.2로 직접 재현해보니 **사라지는
    방식에 따라 동작이 다르다**(`mercurial/subrepoutil.py`의 `precommit()` 직접 확인):
    - `hg remove .hgsub`로 명시적으로 지우면(dirstate `'r'`) 실제 hg는 사용자가
      `hg remove .hgsubstate`를 따로 하지 않아도 `.hgsubstate`까지 같은 커밋에서 완전히
      추적 해제한다(`hg cat -r tip .hgsubstate`가 "no such file in rev"로 실패) —
      `subrepoutil.precommit()`의 `elif '.hgsub' in status.removed:` 분기.
    - `rm .hgsub`로 그냥 디스크에서만 지우면(`hg remove` 없이, dirstate는 여전히
      `'n'`) `.hgsub` 자체의 추적은 전혀 건드리지 않고(tracked-but-missing 상태로
      남음) `.hgsubstate`만 **빈 내용으로 커밋**된다 — 신기하게도 이 경우는 다른 모든
      "추적됐지만 디스크에서 사라진 파일"과 달리 real hg가 missing-file abort
      ("nothing changed (N missing files)")를 내지 않는 특별 취급이다.
    둘 다 hg4j에 없었다. `CommitCommand.applySubrepoStateBeforeCommit()`의
    `if (!hgsubFile.exists()) return;` 조기 반환과, 그보다 더 안쪽의
    `if (subUrls.isEmpty()) return;` 조기 반환(둘 다 "빈 `.hgsubstate` 커밋" 경로를
    막고 있었음)을 제거하고 dirstate의 `.hgsub` 엔트리 상태로 두 경우를 분기하도록
    재작성했다. 명시적 제거 시에는 `.hgsubstate`도 함께 `'r'`로 마킹, raw 삭제 시에는
    빈 `.hgsubstate` 내용을 그대로 쓴다. 추가로, 메인 커밋 루프(파일별 dirstate 순회)
    자체가 "추적됐는데 디스크에 없는 파일"을 전부 `HgValidationException`으로
    거부하고 있어서 raw 삭제 케이스는 `applySubrepoStateBeforeCommit()`을 고치는 것만
    으로는 부족했다 — `.hgsub`이면서 `workingState == 'n'`이고 디스크에 없는 경우를
    특별 취급해(P1/P2 manifest 엔트리를 그대로 캐리) 예외를 던지지 않도록 별도 수정.

    **(3) git 서브저장소 커밋측 상태 갱신 부재.** real git(`git rev-parse HEAD`)이
    설치돼 있어 `mercurial/subrepo.py`의 `gitsubrepo` 클래스(`basestate()`/`dirty()`/
    `commit()`)를 직접 읽고, **실제 git 서브저장소를 만들어 real hg 7.2(+
    `[subrepos] git:allowed = true`)로 라이브 왕복 검증**까지 했다. 확인된 사실: (a)
    `.hgsubstate`에는 `git rev-parse HEAD`가 그대로(hg 노드 해시가 아니라 git commit
    sha) `"<sha> <path>"` 포맷으로 기록됨 — hg 서브저장소와 완전히 동일한 라인 포맷.
    (b) dirty 판정은 git 자신의 `git diff-index --quiet HEAD`(추적 파일의 스테이지/
    미스테이지 변경만, untracked는 무시)이고, dirty하면 hg 서브저장소와 **글자
    하나까지 동일한** `uncommitted changes in subrepository "<path>" (use --subrepos
    for recursive commit)` 메시지로 부모 커밋을 거부한다. (c) `-S`를 켜면
    `git commit -a -m <message> [--author <author>]`가 대신 실행되고 새 HEAD sha가
    기록된다. (d) 로컬에 전혀 체크아웃되지 않은 git 서브저장소는 hg 서브저장소와 달리
    null 리비전 폴백이 **없다** — real hg가 `No such file or directory: '<abspath>'`로
    부모 커밋 자체를 abort한다(직접 재현 확인). 이 4가지를 그대로 구현: 신규
    `io.github.search5.hg4j.submodule.GitSubrepoUtil`(git CLI를 쉘아웃하는 얇은
    헬퍼 — `revParseHead`/`isDirty`/`commit`/`clone`/`fetch`/`checkout`)를 만들고
    `CommitCommand`의 상태 갱신 루프에서 `if (gitPaths.contains(path)) continue;`를
    제거해 `computeGitSubrepoState()`로 대체했다. `UpdateCommand`의 재귀 체크아웃
    쪽도(1)에서 추출한 공용 메서드 안에서 git 서브저장소를 더 이상 건너뛰지 않고
    실제 `git clone`/`fetch`/`checkout`으로 체크아웃하도록 확장(단, real hg의
    `gitsubrepo.get()`이 pin된 커밋을 가리키는 named branch가 있으면 그걸 우선
    checkout하는 것과 달리, hg4j는 항상 detached HEAD로 checkout — 내용/커밋은
    동일하게 도달하므로 기능적 차이는 없고 문서화된 단순화).

    **(4) `UpdateCommand` 재귀 서브저장소 체크아웃의 무조건 pull.** real hg 소스
    (`hgsubrepo._fetch()`/`gitsubrepo._fetch()`)를 직접 확인 — 둘 다 `hasunlinkedrev`/
    `_githavelocally`로 대상 리비전이 로컬에 이미 있는지 먼저 확인하고, 있으면 pull/
    fetch 자체를 생략한다. hg4j의 (1)에서 추출한 공용 메서드에 동일한 사전 확인을
    추가했다 — hg 서브저장소는 로컬 changelog에서 `NodeIdUtil.findRevisionByNodeId`로,
    git 서브저장소는 `git cat-file -e <sha>`로 로컬 존재 여부를 먼저 확인 후에만
    pull/fetch. 검증은 real hg CLI 출력으로 직접 관측 가능한 성질이 아니라서(네트워크
    호출을 "안 했다"는 hg CLI stdout으로 증명할 수 없음), 대신 로그 캡처로 간접
    검증했다: 서브저장소 소스 경로를 존재하지 않는 곳으로 바꿔치기한 뒤, 이미 로컬에
    있는 두 pin 리비전 사이를 hg4j `UpdateCommand`로 오가면서 "Failed to pull
    subrepo" 실패 로그가 전혀 남지 않는지 확인(수정 전이었다면 무조건 pull을 시도해
    무효한 소스에 대한 실패 로그가 남았을 것).

    **검증**: `SubrepoRealHgInteropTest`에 신규 시나리오 8개 추가(총 13개 GREEN) —
    `hg4jCloneRecursivelyClonesSubrepoMatchingRealHg`,
    `hg4jCloneRecursivelyClonesGitSubrepoMatchingRealHg`,
    `hg4jCommitEmptiesHgsubstateWhenHgsubRawlyDeletedMatchingRealHg`,
    `hg4jCommitRemovesHgsubstateWhenHgsubExplicitlyRemovedMatchingRealHg`,
    `hg4jCommitRecordsGitSubrepoStateMatchingRealHg`,
    `hg4jCommitBlocksThenRecursivelyCommitsDirtyGitSubrepoMatchingRealHg`,
    `hg4jCommitAbortsForNotCheckedOutGitSubrepoMatchingRealHg`,
    `hg4jUpdateSkipsPullWhenSubrepoRevisionAlreadyLocalMatchingRealHg`. 각각 real hg(
    +git) 오라클로 동일 시나리오를 나란히 재현해 hg4j 결과와 대조하고, 여러 테스트는
    hg4j 결과물을 다시 real hg CLI로 읽어 양방향 확인까지 한다. 기존
    `UpdateCommandCoverageTest`의 `subrepoCheckoutSkipsGitEntriesAndHandlesUnrecordedRevisionAndSource`
    는 "git 서브저장소는 무조건 건너뛴다"는 낡은 전제로 작성돼 있어 새 동작(로컬에
    없는 git 서브저장소는 커밋 자체가 abort)과 충돌 — `subrepoCheckoutHandlesUnrecordedRevisionAndSource`
    (git 없는 부분만 유지)와 `subrepoCommitAbortsForNotCheckedOutGitSubrepo`(새 abort
    동작 검증)로 분리했다. 전체 회귀: `test` 태스크 2268/2268 GREEN,
    `interopTest` 태스크 228개 중 이 변경과 무관한 기존 `StripRealHgInteropTest`
    2건(`stripMiddleRevisionKeepsAncestorsVerifiable`/
    `stripWithUnevenRevisionSizesLeavesVerifiableRepo`)만 실패 — 이 변경분을 전부
    `git stash`로 되돌린 상태에서도 동일하게 실패함을 직접 확인해 무관함을 검증(백로그
    32와 무관한 사전 존재 이슈).

    **정직한 한계**: git 서브저장소 검증은 이 머신에 real git 7.2 계열 바이너리와 real
    hg 7.2가 둘 다 설치돼 있어(실제로 `[subrepos] git:allowed = true`를 켠 real hg가
    실제 git 저장소를 서브저장소로 받아들이는 전 과정을 라이브로) 상당히 두텁게 검증할
    수 있었다 — record/dirty-차단/`-S` 재귀 커밋/미체크아웃 abort/재귀 clone까지 5개
    시나리오 모두 실제 git 커밋 sha 단위로 대조. 남아 있던 항목 중 (b)
    `gitsubrepo.get()`의 named-branch 우선 checkout(hg4j는 항상 detached checkout)은
    기능적 차이가 없는 문서화된 단순화라 그대로 남겨뒀지만, (a)/(c)는 2026-09-04
    사용자 지시로 후속 작업해 아래와 같이 완료했다.

    **(a) `GIT_AUTHOR_DATE` byte-exactness — ✅ 라이브 검증 완료.** 실제 hg 소스
    직접 확인(`mercurial/subrepo.py` `gitsubrepo.commit()`): `env[b'GIT_AUTHOR_DATE']
    = dateutil.datestr(date, b'%Y-%m-%dT%H:%M:%S %1%2')` — "T" 구분자 + 공백 +
    콜론 없는 `+HHMM`/`-HHMM` 오프셋(예: `"2023-11-15T07:13:20 +0900"`). hg4j의
    `GitSubrepoUtil.commit()`은 `DateTimeFormatter.ISO_OFFSET_DATE_TIME`을 써서
    실제로는 다른 문자열(`"2023-11-15T07:13:20+09:00"`, 공백 없음 + 콜론 있는
    오프셋, 0 오프셋일 땐 `"...Z"`)을 넘긴다 — **그러나 git 자신의 날짜 파서가 두
    포맷을 완전히 동일하게 파싱함을 git 2.53 CLI로 직접 확인**(둘 다
    `"1700000000 +0900"`으로 커밋 오브젝트에 기록됨, 0 오프셋/1e9 이전 epoch
    경계값 포함 여러 케이스로 재확인) — 즉 git에 넘기는 원본 env var 문자열
    자체는 다르지만, 실제로 바이트 비교 대상이 되는 **git 커밋 오브젝트 자체는
    byte-exact로 일치**해 기존 구현을 고칠 필요가 없었다(코드 변경 없음, 검증만
    추가). 부모 hg 커밋에 `-d`를 안 준 "지금 시각" 커밋의 경우도 확인: 실제
    hg 소스(`mercurial/commands.py`/`localrepo.py` `commit()`)를 추적해보면 그
    경우 `date` 로컬 변수 자체가 falsy인 채로 `sub.commit(text, user, date)`에
    그대로 전달되므로(부모 자신의 `cctx._date`는 나중에 `propertycache`로 별도
    시점에 `dateutil.makedate()`가 채움) real hg조차 부모 커밋 시각과 git
    서브저장소 커밋 시각이 서로 다른 `now()` 호출로 어긋날 수 있는 것을 확인 —
    hg4j가 "명시적 `-d` 없으면 `GIT_AUTHOR_DATE`를 아예 안 세팅"하는 기존 동작이
    바로 이 real hg 동작과 이미 일치한다. **신규 라이브 검증**:
    `hg4jGitSubrepoCommitDateMatchesRealHgByteExact` — real hg `hg commit -S -d
    "1700000000 -32400"`로 만든 git 서브저장소 커밋의 author 날짜(`git log
    --format=%ad --date=raw`)와, hg4j `CommitCommand.setSubrepos(true)
    .setDate(1700000000L, -32400)`로 만든 동일 시나리오의 결과를 byte-for-byte
    비교(`"1700000000 +0900"` 고정값까지 확인).

    **(c) git 서브저장소 병합/충돌(`gitsubrepo.merge()`) — ✅ 구현 완료.** 실제
    hg 소스(`mercurial/subrepo.py` `gitsubrepo.merge()` + `subrepoutil.submerge()`)
    를 직접 읽고 real hg CLI + 실제 git 서브저장소로 라이브 재현(2026-09-04):
    같은 git 서브저장소를 공통 조상에서 서로 다르게 갈라놓은 두 hg 커밋을 만들고
    `hg merge`(비대화형)를 실행하자, `subrepoutil.submerge()`가 `.hgsubstate` 3-way
    비교로 "양쪽 다 변경됨(diverged)"을 판정해 `ui.promptchoice(msg, 0)`(비대화형
    기본값 = "Merge")로 `gitsubrepo.merge()`를 호출함을 실측: `git merge-base(remote,
    local)` 계산 후 `base==remote`면 `get(remote)`("fast forward", real hg 표현
    그대로 — local이 remote의 후손이어도 문자 그대로 remote를 checkout), `base !=
    local`이면 `git merge --no-commit <remote>`(종료 코드는 `_gitcommand`가 무조건
    버려서 **충돌이 나도 감지·보고하지 않음** — 뒤이은 `hg commit`이 git 서브저장소를
    dirty로 보고 처리하도록 방치), 그 외(진짜 순방향 fast-forward)엔 아무 것도 안 함.
    **결정적으로, `.hgsubstate`에 최종 기록되는 pin 값은 이 세 경우 모두 LOCAL(병합
    전 값) 그대로 유지된다**(`submerge()`의 `sm[s] = l`, "merge"/"local" 선택
    양쪽 다) — git 서브저장소 실제 병합 결과(2-parent 커밋)는 이어지는 `hg commit
    -S`가 (이미 구현돼 있던 백로그 32 gap #3의 dirty()/commit() 로직으로) 새로
    기록한다. 이 전체 시퀀스를 그대로 이식: `GitSubrepoUtil.mergeDiverged(gitDir,
    remoteRev, localRev)`(+ `mergeBase`/`mergeNoCommit` — 후자는 real hg와 동일하게
    종료 코드를 의도적으로 무시) 신규 추가, `MergeCommand`에 `.hgsubstate`를 더
    이상 일반 텍스트 3-way 병합(diff3 충돌 마커가 그대로 파일에 박히는 버그였음)에
    맡기지 않고 전용 `mergeSubrepoState()`로 서브저장소별 3-way 판정(unchanged/
    remote-only-changed → `UpdateCommand.checkoutSubrepoEntry`로 실제 체크아웃/
    diverged → git이면 `mergeDiverged`, pin 값은 LOCAL 유지)을 수행하도록 배선.
    **신규 라이브 검증(git)**: `hg4jMergeHandlesDivergedGitSubrepoMatchingRealHg` —
    real hg 오라클로 `left.txt`/`right.txt`를 각각 추가하는 두 갈래 git 서브저장소
    커밋을 만들고 `hg merge`+`hg commit -S`까지 실행해 (i) merge 직후
    `.hgsubstate`가 LOCAL pin을 유지하는지, (ii) 최종 병합 커밋이 진짜 2-parent
    git 커밋인지(`git log --format=%P`)를 오라클 자체 검증 + hg4j 쪽도 동일하게
    자기 자신의 right/left sha와 대조(두 구현이 각자 별도 시각에 만든 git 커밋이라
    sha 자체는 오라클과 hg4j 사이에서 다를 수 있어 서로 직접 비교하지 않음), 병합
    후 두 파일이 모두 존재하는지까지 확인.

    **hg-타입(중첩 hg) 서브저장소의 대칭 케이스도 ✅ 구현 완료.** `subrepoutil.
    submerge()`는 git이든 hg든 구분 없이 diverged 케이스에서 똑같이
    `sub.merge(r)`을 호출하므로, git 쪽만 고치고 hg-타입은 그대로 두면 gap B가
    반쪽만 닫히는 것이었다 — real hg의 hg-타입 대응 메서드
    `mercurial/subrepo.py` `hgsubrepo.merge()`도 함께 직접 읽고 실제 hg CLI(중첩
    hg 서브저장소, git 불필요)로 2026-09-04 라이브 재현했다: `self._get(state)`로
    원격 pin을 로컬에 먼저 확보한 뒤 `anc = dst.ancestor(cur)`로 **서브저장소
    자신의** 조상 관계를 계산해 — `anc==cur`이고 같은 브랜치면 단순
    `up_impl.update(state[1])`(순방향 fast-forward), `anc==dst`면 아무 것도 안
    함(cur이 이미 dst를 포함), 그 외(진짜 divergence, 또는 조상은 같지만 브랜치가
    다른 경우)엔 서브저장소 안에서 **진짜 재귀 `hg merge`**(`up_impl.merge(dst,
    remind=False)`)를 실행함을 실측 — git 쪽과 마찬가지로 `.hgsubstate`에
    기록되는 pin은 이 모든 경우에 LOCAL 값 그대로 유지된다. 이를 hg4j 자신의
    기존 명령을 재귀 호출해 그대로 이식: `MergeCommand.mergeDivergedHgSubrepo()`
    신규 추가 — 서브저장소를 별도 `HgRepository`로 열어 `ChangesetGraph.
    isAncestor()`로 조상 관계를 판정하고, fast-forward면 서브저장소에
    `UpdateCommand`를, 진짜 divergence면 서브저장소에 **재귀적으로
    `MergeCommand` 자신**을 호출한다(hg4j가 이미 갖고 있던 `MergeCommand`를
    중첩 호출하는 것만으로 real hg의 재귀 `hg merge`와 동일한 결과를 얻는다 —
    별도의 충돌 UI/머지 엔진을 새로 만들 필요가 전혀 없었다).

    이 재귀 병합을 라이브로 재현하는 과정에서 **진짜 버그를 하나 더 발견**:
    `CommitCommand.applySubrepoStateBeforeCommit()`의 hg-타입 서브저장소
    dirty 판정이 `StatusCommand`의 added/modified/removed 목록만 봤는데,
    `StatusCommand`는 dirstate 엔트리를 디스크와만 비교하지 parent1의 매니페스트와
    비교하지 않는다 — 그래서 재귀 병합이 만든 "parent2에서 새로 들어온 파일"이
    디스크와 일치하는 `'n'`(정상) 엔트리로 기록되면 완전히 "clean"으로 보여서
    dirty 판정이 거짓이 되고, pending 2-parent 병합인 서브저장소가 커밋 없이
    그냥 넘어가 버렸다(`.hgsubstate`가 갱신되지 않음). 실제 hg 소스로 근본원인
    확인: `hgsubrepo.dirty()`는 `workingctx.dirty()`로 위임하는데, 그 함수의 첫
    조건이 바로 `merge and self.p2()` — **parent2가 있으면 파일 내용과 무관하게
    무조건 dirty**다. `CommitCommand`의 dirty 판정에 이 조건(서브저장소 dirstate의
    parent2가 non-null이면 무조건 dirty)을 추가해 수정 — 이 수정이 없었다면 hg4j
    쪽 재귀 병합 커밋이 1-parent 커밋으로 잘못 기록됐을 것이다(실측: 수정 전에는
    정확히 이 증상으로 테스트가 실패했음).

    **신규 라이브 검증(hg-타입)**: `hg4jMergeHandlesDivergedHgSubrepoMatchingRealHg`
    — 순수 hg 서브저장소(git 불필요)로 위와 동일한 left/right divergence 시나리오를
    real hg 오라클과 hg4j(부모/서브저장소 양쪽 다 hg4j 자체 API로 조작) 양쪽에서
    재현해 (i) 오라클: merge 직후 `.hgsubstate`가 LOCAL(right) pin을 유지하는지,
    서브저장소 병합 커밋의 부모 순서(`{p1node} {p2node}`)가 (right, left)인지, (ii)
    hg4j: merge 직후 서브저장소 dirstate가 실제로 pending 2-parent 상태인지, 병합 후
    두 파일이 모두 존재하는지, 최종 재귀 커밋 후 서브저장소 changelog에 진짜
    2-parent 커밋이 새로 생겼는지, 그 부모 쌍이 hg4j 자신의 right/left sha와
    정확히 일치하는지까지 확인. 회귀 확인: `test`+`interopTest` 전체
    231개(이번 hg-타입 테스트 1건 추가) 중 이 변경과 무관한 기존
    `StripRealHgInteropTest` 2건(위와 동일)만 실패, 나머지 전부 GREEN.

41. ~~**SVN 서브저장소(`[svn]` prefix) 지원**~~ — ✅ **완료(2026-09-06)**. 코드베이스에
    SVN 관련 처리가 전혀 없던 맨땅 상태에서, 이 머신에 실제 `svn`/`svnadmin` 1.14 CLI를
    설치(`sudo apt-get install -y subversion` — 권한 있어 즉시 성공, Docker 불필요)한 뒤
    real hg 7.2의 **설치된 실제 소스**(`/usr/lib/python3/dist-packages/mercurial/
    subrepo.py`의 `svnsubrepo` 클래스, `subrepoutil.py`의 `precommit()`/`submerge()`)를
    직접 읽고, 매 동작을 `svnadmin create` + `file://` 로컬 저장소로 라이브 재현해
    오라클로 확보한 뒤 포팅했다 — git 서브저장소 구현(`GitSubrepoUtil`/
    `HgSubrepoParser`/`HgSubrepoEntry`)을 대칭 참고 모델로 사용.

    **아키텍처**: `HgSubrepoEntry`를 `isGit` 단일 boolean에서 `Type{HG,GIT,SVN}` enum
    기반으로 확장(기존 4-arg/boolean 생성자와 `isGit()`은 완전히 하위 호환 유지, 신규
    5-arg 생성자 `(path,url,rev,isGit,isSvn)`와 `Type` 생성자, `isSvn()`/`getType()`
    추가). `HgSubrepoParser`가 `.hgsub`의 `[svn]` prefix를 `[git]`과 동일한 자리에서
    인식해 벗겨내도록 확장. 신규 `io.github.search5.hg4j.submodule.SvnSubrepoUtil`(svn
    CLI 쉘아웃 헬퍼)을 `GitSubrepoUtil`과 나란히 추가하고, `CommitCommand`/
    `UpdateCommand`/`MergeCommand`/`SubrepoCommand`의 기존 `isGit()` 분기 옆에
    `isSvn()` 분기를 대칭적으로 배선했다(`CloneCommand`는 `UpdateCommand.
    recursiveSubrepoCheckout()`에 완전히 위임하는 구조라 무수정으로 자동 지원됨).

    **real hg의 `svnsubrepo` 동작(라이브 확인, 2026-09-06) 및 이식**:
    - **상태 포맷**: `.hgsubstate` 리비전은 git/hg처럼 40-hex sha가 아니라 **평범한
      가변 길이 정수 문자열**(예: `"1 sub"`) — real hg CLI로 직접 재현해 byte-exact
      확인.
    - **로케일 함정(실측)**: real hg의 `_svncommand()`는 `LC_MESSAGES=C`를 강제하면서
      나머지는 `LC_ALL`을 보존한다 — 이 샌드박스 기본 로케일이 한국어라 `svn commit`
      출력이 그대로 나오면 `"Committed revision N."`이 `"커밋된 리비전 N."`으로
      번역되어 real hg 자신의(그리고 hg4j `SvnSubrepoUtil.commit()`의) 정규식이 매칭에
      실패함을 직접 재현해 확인 — `SvnSubrepoUtil`도 동일하게 `LC_MESSAGES=C`를
      강제(`LC_ALL`은 보존)하도록 구현.
    - **`get()`(체크아웃)**: real hg의 `svnsubrepo.get()`은 git과 달리 "이미 있으면
      스킵"하는 fast path가 **전혀 없이** 매번 무조건 `svn checkout --force <url>@<rev>`
      를 실행함을 라이브로 확인(기존 워킹카피 위에서도 안전하게 in-place로 리비전을
      전환함을 직접 관측) — hg4j `SvnSubrepoUtil.get()`도 스킵 로직 없이 동일하게 매번
      `checkout --force` 실행.
    - **`dirty()`/`basestate()`/`commit()`**: `svn status --xml`/`svn info --xml`을
      real hg와 동일한 필드(`wc-status item`/`props`, `entry`/`commit` revision)로
      파싱하도록 포팅(`_wcchanged()`/`_wcrevs()` 알고리즘 그대로). `commit()`은 real
      hg처럼 external/missing 변경 시 각각 `"cannot commit svn externals"`/
      `"cannot commit missing svn entries"`로 abort, 정상 커밋 후 `svn commit` 출력에서
      `"Committed revision N."`을 파싱해 새 리비전을 얻고 **명시적으로 `svn update -r
      N`을 실행**(real hg와 동일 — 순수 `svn commit`은 로컬 워킹카피 리비전을 스스로
      전진시키지 않음을 라이브로 확인)한 뒤 그 리비전을 반환.
    - **미체크아웃 서브저장소의 커밋 시 처리**: real hg의 svn 서브저장소는 hg 타입과
      달리 null-리비전 auto-vivify 폴백이 **없다** — `.svn`이 없고 이전에 기록된
      리비전도 없는(처음 선언된) 경로는 `dirty()`가 `False`를 반환해 `precommit()`이
      그대로 `basestate()`를 호출하는데, 이게 `svn info`를 존재하지 않는 워킹카피에
      실행해 `"'<path>' is not a working copy"`로 실패 → **부모 커밋 전체가 abort**됨을
      real hg CLI로 직접 재현 확인(git 서브저장소의 "No such file or directory" abort와
      동일한 패턴). hg4j `CommitCommand.computeSvnSubrepoState()`도 동일하게 부모 커밋
      전체를 `HgValidationException`으로 abort하도록 구현 — null-리비전 폴백을
      추가하지 않았다(대칭성 있는 의도적 비-구현, git과 동일한 이유).
    - **dirty 차단/`-S` 재귀 커밋**: git과 완전히 동일한 문자 그대로의 abort 메시지
      `uncommitted changes in subrepository "<path>" (use --subrepos for recursive
      commit)`를 real hg CLI로 재현해 확인, hg4j도 동일 메시지로 구현.
    - **병합(diverged) — real hg 소스로 확인한 놀라운 사실**: `mercurial/subrepoutil.py`
      `submerge()`의 "양쪽 다 변경됨" 3지선다 프롬프트(`Merge`/`Local`/`Remote`, 비대화형
      기본값 index 0 = "Merge")는 git과 hg 타입 서브저장소에서는 실제로 무언가를
      수행하지만, **`svnsubrepo.merge()` 자신의 내부 프롬프트**(`_updateprompt`,
      "(l)ocal or (r)emote", 비대화형 기본값 index **0 = Local**)는 Python에서 `0`이
      falsy이기 때문에 `if _updateprompt(...): self.get(...)` 자체가 실행되지 않아 —
      **real hg의 실제 기본 동작은 diverged svn 서브저장소에 대해 완전한 no-op**이다
      (워킹카피를 전혀 건드리지 않고 부모의 `.hgsubstate`는 이미 LOCAL 값으로 유지됨).
      이 사실을 CPython 소스 레벨에서 직접 확인한 뒤, `SvnSubrepoUtil.mergeDiverged()`를
      (git의 `mergeDiverged`와 호출부 대칭을 맞추기 위해) 의도적인 빈 no-op 메서드로
      구현하고 `MergeCommand`의 diverged 분기에 배선 — 실제로 아무 것도 하지 않는 것이
      정답이다.

    **버그 발견 (웹훅 전송 완료)**: 이 작업 도중 기존 `SubrepoCommand`(4개 대상 파일
    중 하나, "이미 완성된 git 대칭 모델"이라는 전제로 참고하려 했던 파일)에서 두 가지
    실제 결함을 발견:
    1. `SubrepoCommand`의 `init`/`update` 액션은 **`[git]` prefix를 전혀 인식하지
       않았다** — `CommitCommand`/`UpdateCommand`/`MergeCommand`/`CloneCommand`는 이미
       git 서브저장소를 완전히 지원하는데, 이 한 파일만 리터럴 `"[git]<url>"` 문자열을
       그대로 `hg clone` 소스로 넘겨 사실상 git 서브저장소를 전혀 체크아웃하지 못하는
       상태였다.
    2. `.hgsubstate` 리비전 파싱이 `stateLine.substring(0, 40)` 고정폭이었다 — 모든
       리비전이 40-hex sha(hg/git)라고 가정한 코드라, svn의 가변 길이 평문 리비전
       번호(예: `"1 sub"`, 6글자)에 대해서는 `StringIndexOutOfBoundsException`을 던지거나
       잘못된 부분 문자열을 읽었을 것이다.
    두 결함 모두 이번 작업으로 함께 수정(첫 whitespace 기준 분리로 가변 길이 리비전
    지원 + `[git]`/`[svn]` prefix 디스패치 추가)했고, 발견 즉시 웹훅으로 보고했다
    (`SubrepoCommand`의 [git] 무지원 + 고정폭 파싱 버그, HTTP 302 성공 응답 확인).

    **신규 검증(TDD, 전부 real svn 1.14 + real hg 7.2 CLI 오라클과 나란히 대조)**:
    `SubrepoSvnRealHgInteropTest`(신규, `@Tag("interop")`) 8개 —
    `hg4jParsesRealHgGeneratedSvnHgsubAndHgsubstate`,
    `hg4jCommitAutoGeneratesSvnHgsubstateRealHgUnderstands`,
    `hg4jCommitBlocksThenRecursivelyCommitsDirtySvnSubrepoMatchingRealHg`(오라클과
    hg4j가 각각 독립된 `svnadmin create` 저장소를 써야 함 — svn 리비전 번호는 저장소
    전역 카운터라 하나를 공유하면 두 쪽의 기대 리비전 번호가 서로 어긋나고 이미
    버저닝된 경로에 `svn add`가 충돌한다는 것을 실제로 겪고 나서 분리),
    `hg4jUpdateCheckoutMatchesRealHgAcrossSvnRevisionPins`,
    `hg4jCommitAbortsWhenSvnSubrepoDeclaredButNotCheckedOutMatchingRealHg`,
    `svnSubrepoUtilReadAndWritePrimitivesMatchRealSvnCli`(status/info/checkout/commit
    직접 왕복), `subrepoCommandInitChecksOutPinnedSvnRevision`/
    `subrepoCommandInitChecksOutPinnedGitRevision`(위 `SubrepoCommand` 버그 수정
    회귀 테스트 — git 쪽도 함께 커버). 기존 `HgSubrepoTest`/`HgSubrepoEntryTest`에
    `[svn]` prefix 파싱 및 `Type`/5-arg 생성자 단위 테스트 추가.

    **회귀 확인**: `test` 태스크(interop 제외) 2299/2299 GREEN(신규 실패 없음),
    `interopTest`를 `SubrepoSvnRealHgInteropTest`+`SubrepoRealHgInteropTest`로 스코프
    지정해 실행 시 24/24 GREEN.

    **읽기/쓰기 완전성 체크** (git 서브저장소가 지원하는 모든 연산과 대조):
    | 연산 | git | svn | 비고 |
    |---|---|---|---|
    | 상태 조회 (read) | `git status --porcelain` | `svn status --xml` | 완료 |
    | 리비전 읽기 (read) | `git rev-parse HEAD` | `svn info --xml` | 완료 |
    | 체크아웃/pin (write) | `git checkout <sha>` | `svn checkout --force <url>@<rev>` | 완료 |
    | 커밋 전파 (write) | `git commit -a` | `svn commit -m` + `svn update -r N` | 완료 |
    | 미체크아웃 커밋 처리 | 전체 abort | 전체 abort | real hg와 동일하게 대칭 |
    | diverged 병합 | 실제 `git merge --no-commit` 수행 | **의도적 no-op**(real hg 기본 동작 자체가 no-op) | 대칭 — svn 쪽만 "아무 것도 안 함"이 정답 |
    | push 전파 | 지원(`PushCommand`가 재귀 처리하는지는 별도 백로그 영역) | real hg 자체가 "svn은 push no-op"이라 명시 — hg4j도 별도 구현 불필요 | `hg help subrepos` 명시 사항, 범위 밖 아님 — real hg 자체 사양 |

