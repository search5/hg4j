---
updated: 2026-09-06
status: items 28/30/31/40/42 전부 완료
---

# 백로그 28, 30, 31, 40, 42: Narrow clone과 LFS

관련 항목: 28(narrow clone/LFS 실제 hg CLI interop 검증 누락 — narrowspec 파일
위치/포맷/`.hg/requires` 키가 전부 실제 hg와 달랐던 버그 발견), 30(narrow clone의
wire-protocol 수준 재통합 — 로컬 pull/update 시 narrowspec 필터 재로드, 사전 존재
캐시 버그 발견), 31(LFS 커밋/체크아웃 파이프라인 — LFS 노드 해시가 포인터 텍스트가
아니라 실제 파일 콘텐츠 기준이어야 한다는 버그 발견), 40(narrow clone의 진짜
wire-protocol 수준 협상 — ellipsis node가 아니라 real hg의 실제 기본 메커니즘인
getbundle narrow 인자 협상을 구현, 클라이언트+서버 양방향, ✅ 완료), 42(LFS
세부 옵션 3가지 — rename+LFS copy-tracing, `[lfs] url` override, `lfs.disableusercache`,
✅ 완료).

## 원문
28. ~~**Narrow clone / LFS — 실제 hg CLI interop 검증 누락(근거 없는 bare `✅`)**~~
    — ✅ **완료(2026-09-04)**. gap table의 `Narrow clone / narrowspec`과
    `LFS (largefiles)` 두 행이 근거 서술 없는 bare `✅`였던 것을 — 23번 항목이
    `Subrepositories` 행에 대해 했던 것과 정확히 같은 방식으로 — 실제 hg 7.2
    CLI와 대조 검증했다. **양쪽 다 실제 버그를 발견·수정했다.**

    **Narrow clone (`NarrowCloneCommand`, `HgTreeFilter`)**: 이 호스트의 hg 7.2는
    `narrow` 확장이 기본 비활성(`--config extensions.narrow=`로 켜야 함)이지만
    설치는 돼 있음을 먼저 확인. 실제 hg CLI로 `hg clone --narrow --include/--exclude`
    를 여러 조합(단순 include, include+exclude, 형제 디렉터리, `rootfilesin:`,
    include 없음)으로 실행해 산출물을 직접 조사(`mercurial/narrowspec.py` 소스도
    함께 읽음), hg4j `NarrowCloneCommand`가 만든 산출물과 대조한 결과 **완전히
    다른 포맷/위치**였음을 확인:
    - narrowspec 파일 위치: hg4j는 `.hg/narrowspec`에 썼지만 실제 hg는
      `.hg/store/narrowspec`(+ 작업카피 미러 `.hg/narrowspec.dirstate`)에 쓴다.
    - `.hg/requires` 키: hg4j는 `"narrowspec"`을 썼지만 실제 hg는
      `"narrowhg-experimental"`을 쓴다.
    - narrowspec 파일 포맷: hg4j는 `[includes]`/`[excludes]`(복수형, 원본 문자열
      그대로)를 썼지만 실제 hg는 `[include]`/`[exclude]`(단수형), 각 패턴은
      `path:`/`rootfilesin:` kind가 붙고 끝 슬래시가 제거된 정규화된 형태만
      허용한다(`glob:`/`re:` 같은 다른 kind는 실제 hg가 `abort: invalid prefix on
      narrow pattern`으로 거부함을 실측 확인).
    - 매칭 규칙: hg4j `HgTreeFilter.createPathPrefixFilter`는 단순
      `String#startsWith`라서 `include=["srcdir"]`가 이름이 비슷한 형제 디렉터리
      `srcdirextra/`까지 잘못 매치하는 **실제 버그**였다(실제 hg로
      `srcdir`/`srcdirextra` 둘 다 있는 저장소를 narrow clone해서 실측 확인 —
      real hg는 `srcdirextra/` 쪽을 절대 포함시키지 않음). 또한 include가 하나도
      없으면 실제 hg는 `matchmod.never()`(아무 것도 매치 안 함)를 쓰는데, hg4j는
      "전부 매치"로 반대로 동작했다.

    이 네 가지를 전부 TDD로 수정: `HgTreeFilter`에 `NarrowPattern`/
    `normalizeNarrowPattern()`(kind 정규화·검증, 실제 hg의
    `narrowspec._validatepattern()` 규칙 — `.`/`..`/빈 컴포넌트 거부, 지원 안
    하는 kind prefix 거부 — 그대로 재현)와 `createNarrowSpecFilter()`(경로
    컴포넌트 경계를 지키는 `path:` 매칭, `rootfilesin:`의 직계 자식만 매치하는
    규칙, exclude 우선, include 없으면 전부 거부)를 신설 — 기존
    `createPathPrefixFilter`는 narrow 외 다른 8곳 호출부(status/log/diff/files
    등)가 의존하는 "include 없으면 전부 허용" 기본값을 그대로 유지해야 해서
    건드리지 않고 별도로 분리. `NarrowCloneCommand`가 새 정규화/필터/파일
    위치·포맷을 전부 쓰도록 재작성. **narrowspec 밖 리비전/파일이 이후 pull에서
    필터링되는지**는 `FetchCommand`가 이미 파일그룹 단위로 `treeFilter`를 적용하고
    있어(줄 490 부근) 새 필터로 그대로 이어지는 것을 확인했지만, hg4j는 narrowspec을
    저장소 상태로 다시 읽어들이는 통합 코드가 전혀 없어서(narrow clone 시점에만
    그 자리에서 필터를 만들어 쓰고 끝 — pull/update 등 다른 명령이 나중에
    narrowspec 파일을 다시 파싱해 자동으로 재적용하는 경로가 없음) "narrow
    저장소에 대한 진짜 wire-protocol 수준의 후속 narrow pull"(ellipsis node 등)
    양방향 검증은 이번 범위에서 시도하지 않았다 — 이는 구현 자체가 없는 기능이라
    버그가 아니라 완성도 격차이며, 별도 백로그로 다룰 만한 규모(정직하게 명시).
    대신 (1) hg4j가 만든 narrow clone을 실제 hg CLI(`hg tracked`/`hg files`/
    `hg status`)로 열어서 완전히 인식하는지, (2) 실제 hg가 만든 narrow clone의
    narrowspec 텍스트를 hg4j의 새 `normalizeNarrowPattern`/`createNarrowSpecFilter`
    로 파싱·매칭했을 때 실제 hg가 실제로 체크아웃한 파일 목록과 판정이 정확히
    일치하는지를 `NarrowCloneRealHgInteropTest`(3개 시나리오)로 검증 — 전부 GREEN.

    **LFS (`HgLfsManager`, `HgLfsPointer`)**: 이 호스트에 `lfs` 확장이 실제로
    동작함을 먼저 확인(`hg --config extensions.lfs= version` 성공,
    `hgext/lfs/blobstore.py` 소스 확인 가능) — 백로그 문서가 우려했던 "환경에
    없을 수 있음"은 기우였고, 전체 read-side 왕복을 실제로 검증할 수 있었다.
    실제 hg로 `[lfs] threshold`를 낮게 잡고 큰 파일을 커밋한 뒤:
    - 포인터 파일 텍스트 포맷(`version`/`oid sha256:`/`size` 줄 + 알파벳순 부가
      필드 `x-is-binary`)은 hg4j `HgLfsPointer.parse()`가 그대로 정확히 파싱함을
      확인 — **버그 없음**.
    - 로컬 blob 저장 경로는 **실제 버그**였다: `HgLfsManager.getLocalPath()`가
      Git-LFS 스타일 2단계 샤딩(`objects/XX/YY/ZZZZ...`)을 쓰고 있었는데, 실제
      hg의 `hgext/lfs`(`blobstore.py`의 `lfsvfs.join()`: "split the path at
      first two characters, like: XX/XXXXX...")는 1단계 샤딩만
      쓴다(`objects/XX/YYYYY...`) — hg4j와 실제 hg가 같은
      `.hg/store/lfs/objects/`를 공유해도 서로 blob을 절대 못 찾는 버그였다.
      TDD로 수정(단일 레벨 샤딩으로 변경).

    수정 후 `LfsRealHgInteropTest`(2개 시나리오)로 양방향 확인: ① 실제 hg가
    커밋한 LFS 파일의 filelog 포인터 텍스트를 hg4j `Revlog`로 직접 읽어
    `HgLfsPointer.parse()`로 파싱하고, 실제 hg가 로컬에 캐시해둔 blob을 수정된
    `HgLfsManager.getLocalPath()`로 정확히 같은 경로에서 찾아 원본과 동일한
    바이트를 읽어냄. ② hg4j `HgLfsManager.cacheObject()`로 로컬 store에 쓴 blob을
    real hg 자신의 `hg cat`이 그대로 읽어냄(경로/포맷이 hg4j가 만든 것도
    아니고 real hg 자신이 실제로 소비할 수 있는 형식임을 확인).

    **정직하게 기록해야 할 미검증/미구현 부분**: hg4j는 LFS를 커밋/체크아웃
    파이프라인에 전혀 연결하지 않았다(`CommitCommand`/`UpdateCommand`/`AddCommand`
    어디에도 `HgLfsManager`/`HgLfsPointer` 참조가 0건, revlog의
    `REVIDX_EXTSTORED` 플래그도 다루지 않음, `.hgrc`의 `[lfs] threshold` 자동
    감지도 없음) — `HgLfsPointer`/`HgLfsManager`는 완전히 독립된 유틸리티
    라이브러리다. 그래서 "hg4j가 LFS 커밋을 만들고 실제 hg가 읽는다"는 리버스
    방향은 그 자체가 존재하지 않는 기능이라 테스트할 방법이 없었다 — 이번
    항목의 범위(검증 + 발견한 버그 수정)를 넘어서는 별도의 기능 구현(커밋/체크아웃
    파이프라인 전체 연결)이 필요한 항목이라 착수하지 않고 이 사실만 기록한다.
    원격 HTTP blob store 업로드/다운로드(배치 API)는 `HgLfsManager.fetchObject()`
    에 이미 구현돼 있고 기존 `HgLfsTest`가 목(mock) HTTP 서버로 커버하고 있어
    이번 항목에서 다시 다루지 않음(로컬 저장소 포맷 정확성이 우선순위가 더
    높다는 원래 방침대로).

    **신규 테스트**: `HgTreeFilterTest`(narrow 매칭 프리미티브 단위 테스트 8건
    추가), `HgNarrowCloneTest`(포맷/위치 수정 반영 + component-boundary 회귀
    테스트 추가), `NarrowCloneRealHgInteropTest`(신규, 3건),
    `LfsRealHgInteropTest`(신규, 2건). 전체 회귀 2415건 전부 GREEN(신규 실패 없음).

30. ~~**Narrow clone의 wire-protocol 수준 재통합 (narrow pull/update)**~~ —
    ✅ **완료(2026-09-04)**. `PullCommand`/`UpdateCommand`/`FetchCommand` 어디에도
    narrowspec 참조가 없어(narrow clone 시점에만 그 자리에서 필터를 만들어 쓰고
    이후 `pull`/`update`가 narrow 상태를 다시 읽지 않던 문제) narrow clone한
    저장소에 plain `pull`/`update`를 하면 범위 밖 파일까지 받아버릴 위험이 있었다.

    **구현**: `HgTreeFilter.loadFromRepository(HgRepository)` 신설 — `.hg/store/
    narrowspec`을 읽어(`NarrowCloneCommand.formatNarrowSpec`이 쓰는 `[include]`/
    `[exclude]` 포맷을 그대로 파싱) 클론 시점과 동일한 `createNarrowSpecFilter`
    매처를 재구성한다(narrowspec이 없으면 `HgTreeFilter.ALL`). `FetchCommand.call()`/
    `applyBundle()`과 `UpdateCommand.call()`이 각각 시작 시점에 "자신의 treeFilter가
    여전히 기본값 `ALL`인 경우에만" 이걸로 자동 대체하도록 배선 — 명시적으로
    `setTreeFilter()`를 호출한 기존 호출자(`NarrowCloneCommand` 자신 포함)는 전혀
    영향받지 않는다. `PullCommand`는 자신의 필터가 기본값일 때 `FetchCommand`에
    아예 전달하지 않아 `FetchCommand` 자신의 자동 로딩이 작동하도록 함(오버라이드
    사고 방지).

    **부수적으로 발견·수정한 진짜 버그(NarrowCloneCommand 자체, backlog 30과
    무관하게 이미 존재하던 결함)**: 검증 도중 기존 `NarrowCloneRealHgInteropTest`의
    선행 시나리오까지 `HgCorruptDataException`("Failed to read complete hunk ...
    at offset 64")으로 깨지는 걸 발견 — `git stash`로 순정 코드에 대조해도 100%
    동일하게 재현되어 이번 변경과 무관한 사전 존재 버그임을 확인했다. 근본 원인은
    `NarrowCloneCommand.call()`이 `hg.pull()` 직후 캐시 무효화 없이 바로
    `hg.update()`를 호출해서, pull이 방금 쓴 매니페스트 revlog를 update가 그
    이전(또는 `Hg.init()` 중 우연히 캐시된) stale `Revlog` 인스턴스로 읽어버리던
    것 — `FetchCommand`의 clonebundle 경로가 이미 쓰고 있던
    `repository.clearRevlogCache()` 패턴이 이 한 호출부에서만 빠져 있었다. 같은
    한 줄 추가로 수정, narrow clone 전체(narrow clone을 쓰는 모든 사용자)에
    영향을 미치던 결함이라 이번 검증이 아니었으면 계속 잠복해 있었을 것.

    **범위 밖으로 남긴 것(정직하게 기록)**: 실제 hg의 wire-protocol ellipsis node
    메커니즘(서버가 narrow 범위 밖 리비전 자체를 아예 전송하지 않는 것)은 여전히
    미구현 — 이번 항목은 "이미 로컬에 받은 changegroup을 적용/체크아웃할 때
    narrow 필터를 존중하는 것"까지만 다룬다(이미 백로그 28번에서도 같은 경계로
    문서화됨).

    **검증**: `NarrowCloneRealHgInteropTest`에 신규 시나리오
    `hg4jNarrowCloneScopeIsRespectedOnSubsequentPlainPull` 추가 — narrow clone
    이후 real hg로 범위 안/밖 파일을 각각 하나씩 추가 커밋하고, hg4j로 **명시적
    treeFilter 없이** plain `pull`+`update`를 실행했을 때 범위 밖 파일이 워킹
    카피/추적 목록에 전혀 안 나타나는지, real hg CLI 자신이 그 결과를 열었을 때도
    일관되게 보는지 확인. 기존 시나리오 1~3 포함 전체 4개 테스트 GREEN, 전체
    회귀(`test`+`interopTest`) `BUILD SUCCESSFUL`(22분48초, 새 실패 없음).

31. ~~**LFS 커밋/체크아웃 파이프라인 연동**~~ — ✅ **핵심 경로 완료(2026-09-04)**.
    신규 발견(백로그 28번에서 "정직하게 기록"만 하고 범위 밖으로 남긴 것을 별도
    항목으로 승격). `CommitCommand`/`UpdateCommand`에 `[lfs] threshold`를 넘는
    파일을 LFS 포인터로 치환해 커밋하고(`REVIDX_EXTSTORED` 플래그 세팅, 실제
    바이트는 로컬 blob store에 캐시), 체크아웃 시 그 플래그를 보고 포인터를 실제
    바이트로 되돌리는 핵심 경로를 구현했다.

    **근본적으로 잘못 짚었던 가정 하나 발견·수정**: 처음엔 filelog 리비전의 노드
    해시를 (다른 모든 리비전과 마찬가지로) 저장되는 바이트 그 자체(포인터 텍스트)
    로 계산하면 될 거라 가정했는데, 실제 hg CLI로 만든 LFS 커밋을 직접 재현해
    `SHA1(p1,p2,포인터텍스트)`와 `SHA1(p1,p2,실제파일바이트)` 둘 다 계산해 대조해본
    결과 **후자만 실제 filelog 노드와 일치**했다 — 즉 real hg는 LFS 리비전의 노드
    해시를 저장된 포인터가 아니라 real hg의 `hgext/lfs` flag processor가
    돌려주는 실제 파일 콘텐츠 기준으로 계산한다(read-side flag processor의
    `validatehash=True`가 실제 콘텐츠에 대해 매번 진짜로 검증됨). 이걸 놓치고
    포인터 텍스트 기준으로 해시를 계산해 커밋했더니, real hg CLI가 그 커밋을 열 때
    `abort: integrity check failed`로 거부하는 실제 상호운용성 버그가 났었다 —
    `Revlog.appendRevision`에 `hashBasisOverride` 파라미터를 신설해 "저장되는
    바이트"와 "해시 계산 기준 바이트"를 분리함으로써 수정. 부수적으로 real hg의
    `hgext/lfs`가 커밋 훅으로 `.hg/requires`에 `lfs`를 지연 추가한다는 것도 확인해
    `CommitCommand`에 동일하게 구현(없으면 real hg가 checkhash 우회 자체를
    활성화하지 않아 같은 에러가 남).

    **검증**: `LfsRealHgInteropTest`에 신규 파이프라인 테스트 2건 추가 — real hg가
    커밋한 LFS 파일을 hg4j `UpdateCommand`가 체크아웃해 실제 바이트를 복원하는지,
    hg4j `CommitCommand`로 만든 LFS 커밋을 real hg의 `hg cat`(lfs 확장 활성화)이
    정확히 읽고 `hg verify`가 에러 없이 통과하는지 양방향 확인 — 전부 GREEN.
    타깃 테스트 클래스 회귀 재확인 완료, 전체 유닛 테스트(interop 제외)
    2262/2263 GREEN(유일한 실패는 이 변경과 무관한 기존 `PerformanceBenchmarkTest`
    타이밍 플레이크).

    **범위 밖으로 남긴 것(정직하게 기록)**: rename/copy 메타데이터와 LFS 임계값을
    동시에 넘는 파일(real hg는 포인터에 `x-hg-*` 키로 copy-tracing을 접어 넣는데
    미구현 — 그런 파일은 그냥 일반 경로로 커밋됨), 원격 LFS 서버 URL을
    `[paths] default`에서 그대로 유추(실제 hg의 `[lfs] url` override 미지원),
    `.hgrc`의 `lfs.disableusercache` 등 세부 옵션. 전체(모든 fork 동시 실행 중)
    회귀는 리소스 경합으로 완주 못함 — 별도 확인 권장.

40. ~~**Narrow clone의 진짜 wire-protocol 수준 협상(genuine narrow clone)**~~
    — ✅ **완료(2026-09-06)**. 등록 당시 "narrow clone의 진짜 메커니즘은
    ellipsis node"라고 적었던 전제 자체가 실제 hg 소스 실측 결과 틀렸음을
    확인했다: `hgext/narrow/__init__.py`의 ellipsis 노드 생성은
    `experimental.narrowservebrokenellipses`(기본값 `False`, 설정명 자체에
    "broken"이 박혀 있고 옆 주석이 "fragile... unlikely this work will get
    done"이라고 명시)로 게이트된 비활성 실험 기능이다. 실제 hg의 진짜 기본
    narrow clone(`hg clone --narrow`)은 `getbundle`의
    `narrow=1`/`includepats`/`excludepats` wire 인자를 협상해 서버가
    범위 밖 파일의 **filelog만** 패킹에서 제외하는 방식(changelog/manifest는
    flat manifest인 한 그대로 전송)이다. 실측: 같은 저장소의 getbundle
    payload가 전체 clone 5,462,104바이트 → narrow clone 29,412바이트로
    약 99.5% 절감.

    **구현(클라이언트+서버 양방향)**: `HgRemoteConnection.supportsNarrow()`
    (`exp-narrow-1` capability 감지) + `getBundle(..., NarrowScope)`;
    `HgRemoteClient`(HTTP)/`HgSshClient`(SSH) 둘 다 narrow wire 인자 전송.
    `FetchCommand`가 narrowspec 기반 `treeFilter`를 감지하면 자동으로
    `NarrowScope`를 구성 — `NarrowCloneCommand`/일반 `PullCommand` 모두
    API 변경 없이 자동 적용. 서버 쪽: `Wire1Commands`가 `exp-narrow-1`을
    상시 광고하고 narrow 인자를 파싱, `HgLocalClient.getBundle(...,
    NarrowScope)`가 범위 밖 filelog 패킹을 건너뜀(`HgHttpWireServer`/
    `HgSshWireServer`는 이미 인자를 그대로 전달하는 구조라 무변경).

    **검증(real hg 7.2.2 CLI, 양방향, HTTP+SSH 둘 다)**:
    `NarrowCloneWireReductionRealHgInteropTest`(hg4j 클라이언트 → real hg
    서버, 실제 응답 바이트 비교 + 클라이언트 로컬 store에 제외 파일
    filelog 자체가 없음을 확인), `HgHttpWireServerNarrowInteropTest`(real
    hg 클라이언트 → hg4j 서버, 실제 HTTP 응답 바이트 캡처),
    `HgSshWireServerRealHgInteropTest#realHgNarrowClonesFromHg4jServedOverSsh`
    (동일 시나리오 SSH). 기존 21콤보 wire matrix + 4개 real-hg-interop
    시나리오 회귀 없음. 전체 non-interop 2287건 GREEN.

    **의도적으로 미구현(정직하게 기록)**: ellipsis 노드/`depth=` shallow
    narrow clone — real hg 자신이 기본 비활성+"fragile/unlikely-to-be-fixed"로
    선언한 기능이고, 이미 구현한 협상만으로 narrow clone의 실제 목적(대역폭
    절감)을 99%+ 달성했으므로 구현 목표에서 제외(스코프 축소가 아니라 실측
    근거 기반 판단).

    **발견했으나 무관해 신고만 함**: `HgSshWireServerRealHgInteropTest
    #realHgSeesExternalRepoChangesAcrossConnectionsOnALongLivedSshServer`가
    수정 전 코드에서도 동일하게 실패(백로그 24번 영역, 별도 사전 존재 이슈).

42. ~~**LFS 세부 옵션 3가지 — 백로그 31번 완료 시 범위 밖으로 남긴 것**~~ —
    ✅ **완료(2026-09-06)**, 3개 항목 전부 real hg 7.2 CLI로 실측 후 구현.

    1. **rename+LFS copy-tracing**: real hg는 이걸 "그냥 되는" 게 아니라
       포인터 파일 자체의 `x-hg-copy`/`x-hg-copyrev` 필드에 copy 메타데이터를
       접어 넣고, filelog 노드 해시를 그 메타데이터-포함 바이트 기준으로
       계산한다(`hg debugdata`로 실측, 두 SHA1 후보 중 metadata-wrapped
       쪽만 실제 filenode와 일치 확인). `HgLfsPointer`가 임의 부가 필드를
       보존하도록 수정, `Revlog.wrapMetadata()`를 공개해 해시 기준 바이트를
       계산, `Revlog.getRevisionMetadata()`를 LFS 인지하도록 만들어
       `AnnotateCommand`/`LogCommand --follow`가 그대로 동작. 양방향 검증:
       hg4j의 annotate 출력이 real `hg annotate -c`와 바이트 단위 일치,
       real hg의 `hg verify`/`hg log --follow`가 hg4j 산출물을 그대로 수용.
    2. **`[lfs] url` override**: **실제 버그 발견** — hg4j는 기본 원격 LFS
       URL을 `<remote>/info/lfs`로 유추했는데, 실제 hg는
       `<remote>/.git/info/lfs`를 쓴다(`hg clone -v` 로그로 실측 확인).
       `HgLfsManager.resolveServerUrl()`/`resolveContent()`로 통일된 읽기
       경로 신설, 실제 `hg serve` 대상으로 종단 간 검증 + override 우선순위
       확인.
    3. **`lfs.disableusercache`/`lfs.usercache`**: hg4j에 사용자 레벨 캐시가
       전혀 없었음(로컬 스토어만) — real hg의 2계층(로컬+usercache)
       블롭스토어를 OS별 정확한 기본 경로(`$XDG_CACHE_HOME/lfs`,
       `~/.cache/lfs`, macOS/Windows)와 `[lfs] usercache`/
       `experimental.lfs.disableusercache` 설정과 함께 구현, real hg가
       채운 커스텀 usercache를 hg4j가 그대로 읽는지 검증.

    **부수 발견 버그(웹훅 신고)**: `[lfs] threshold = 0`을 "모든 파일이
    LFS"로 잘못 처리(실제 hg는 falsy-zero를 "비활성"으로 취급) — 수정.

    **정직하게 미구현으로 남김**: `lfs.track`(fileset 표현식 기반 LFS
    추적)은 이번 3개 세부 항목 범위 밖이라 미구현, 문서에 명시.

    **검증**: 전체 non-interop `test -x jacocoTestReport` 2286건 GREEN,
    scoped `LfsRealHgInteropTest` 10/10 GREEN.

