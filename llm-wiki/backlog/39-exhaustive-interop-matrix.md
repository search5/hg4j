---
updated: 2026-09-06
status: completed (2026-09-06) — 로컬 매트릭스 대상 60개 명령 전부 + wire 매트릭스
  대상 8개 명령 전부, 합계 68개 명령이 36개(로컬) 또는 21개(wire) requirement 조합
  전체에서 검증 완료. 최종 커밋 `0d6fe2a`.
---

# 백로그 39: Exhaustive Interop Matrix — 포셀린 명령 x wire protocol x requirement 조합

이 문서는 hg4j 최대 규모 검증 캠페인의 전체 기록이다. 5개 웨이브에 걸쳐 15개 이상의
병렬 worktree-isolated 에이전트를 투입해, 60개 로컬/저장소 전용 명령을 각각 36개
requirement 조합(native 6 + Docker 30, `hg-rust-7.2.4` 컨테이너 기반)에, 8개
전송 관여 명령을 각각 21개 wire 조합(HTTP 18 + SSH 3)에 전수 검증했다.

## 핵심 요약 (전체를 읽지 않아도 되는 사람을 위한 TL;DR)

- **최종 결과**: 로컬 60/60 + wire 8/8 = **68/68 명령 전체 매트릭스 커버리지 확보**.
  원래 목표 문서에 "67개"라고 계속 쓰여 있던 것은 [[exhaustive-interop-matrix-plan]]
  §3-2 소제목 자체의 표기 오류(실제로는 60개가 나열돼 있었음)로 밝혀졌다 — 최종 병합
  시점에 실제 명령 파일 목록·설계 문서 나열·실제 테스트 클래스를 3자 프로그램 대조해
  확정.
- **진짜 hg4j 프로덕션 버그 40건 이상 발견·수정**(모두 즉시 완전 수정, 범위 축소
  없음) — 상세 목록은 `known-bugs-registry.md` 참고. 가장 파급력 컸던 것:
  - `DeltaCodec.decompressZstd`의 델타-리비전 버퍼 크기 버그 — **4개의 서로 다른 병렬
    웨이브가 서로 모르고 각각 독립적으로 발견**(core/query, admin/maintenance,
    작업트리, 콘텐츠/트리읽기). 이 세션이 반복적으로 겪은 "문서가 너무 커서 이전
    발견을 못 찾음" 문제의 가장 극적인 사례이자, 이 문서 재구조화 자체의 직접적
    동기.
  - `BackoutCommand`의 오래된 조상 백아웃 시 3-way merge/충돌 감지 경로가 아예 없어
    데이터 손실 가능성이 있던 버그.
  - `PurgeCommand`가 심볼릭 링크로 연결된 디렉터리를 실제로 따라 들어가 저장소
    바깥의 파일을 삭제할 수 있었던 실제 데이터 손실 버그.
  - `IncomingCommand`가 콘텐츠 있는 real hg 서버 어디에도 100% 깨져 있던 버그(real hg
    자신의 레거시 코드 결함과 맞물린 것).
  - `DirstateV2Node`의 exec/symlink 플래그 상호배타 처리 버그 — 다른 여러 완료 명령
    (AddCommand/CommitCommand/MergeCommand/RebaseCommand)에도 공유되는 인프라
    버그였을 가능성. 상세는 [[dirstate-v2]].
  - `InitCommand`가 36개 조합 중 30개 이상을 아예 생성조차 못 하던 gap의 전면 구현.
- **병합 중 발견한 중요한 교훈**: 병합한 에이전트가 "이 테스트 실패는 이 세션과
  무관한 사전 존재 이슈"라고 주장한 것을 그대로 믿지 않고 직접 병합 전/후·브랜치
  단독 여부로 격리 재현해서 확인해야 했던 사례(`StripCommandCoverageTest`) — 실제로는
  그 wave 자신이 만든 진짜 회귀였고, 근본 원인은 `refreshIfChangedOnDisk()`가
  로컬 쓰기 이력이 있는 revlog까지 통째로 캐시 무효화해버린 것이었다.
- **후속으로 남았던 것 — `GraftCommand`의 v2-docket rollback/journal gap**:
  ✅ **완료(2026-09-06, 커밋 `c9acb90`/병합 `5d031e8`)**.
  `GraftCommand#commitGraftedRevision`은 `CommitCommand`에 위임하면서도
  자기 자신의 크래시-안전 저널을 별도로 관리하는데, 그 구현이
  `CommitCommand`/`RollbackCommand`/`RecoverCommand`가 이미 고쳤던 것과
  똑같은 "v2-docket 파일은 append해도 바이트 길이가 안 변한다" 버그를
  그대로 갖고 있었다. **실측 재현**(changelog-v2 저장소에서
  `RevlogIndex.updateV2DocketSizes()`의 in-place 쓰기 시점에 `chmod`로
  강제 실패 주입): 수정 전 코드는 4개 콤보(`cl2`/`cl2+sidedata` ×
  flat/tree) 전부에서 실패한 그래프트 후에도 changelog의 resolved
  companion 파일이 288→384바이트로 커진 채 영구히 남았고, 크래시 복구
  저널 경로로는 "환상 커밋(phantom commit)" 위험(성공한 그래프트 직후
  크래시 시 재시작해도 그 커밋이 그대로 살아남음)까지 확인됐다. **수정**:
  `CommitCommand#recordRevlogRollbackState`의 패턴을 그대로 이식(docket
  전체 백업 + companion 파일 truncate-only 저널링). **검증**:
  `RequirementMatrixGraftCoreRoundTripTest`에 신규 테스트 2건(6콤보×2=12개,
  수정 전 코드로 먼저 실패 확인 후 수정으로 그린 전환하는 TDD 순서 실제로
  밟음) — in-process 복구와 재오픈 시 자동 크래시 복구 양쪽 다 real hg
  `log`/`verify`/`parents`로 확인. 기존 성공-그래프트 경로 회귀 없음(20개
  `GraftCommandCoverageTest` + 기존 6/6 콤보 그대로 그린).
- **후속으로 남은 것**: `refreshIfChangedOnDisk()` 연결 여부의 전수
  재점검(과다 연결도 부족 연결도 둘 다 회귀를 만들 수 있음이 이번에
  확인됨) — 아직 미착수.

## 매트릭스 현재 상태(GREEN/RED 데이터)

68개 명령 × 조합별 실제 상태는 프로즈가 아니라 `matrix-status.md`에 구조화된
표로 유지한다 — 총합 숫자를 서술 중간에서 세다가 실수하는 일(이번에 실제로
"67개"로 겪었던 off-by-one)을 구조적으로 막기 위함이다.

## 상세 원문 A — `mercurial-spec-compliance-requirement.md` 백로그 39번 (가장 상세, 웨이브 1~5 전체)
39. **[[exhaustive-interop-matrix-plan]] 매트릭스 범위 확장 — 명령 커버리지가
    극히 일부에 머물러 있음**. 신규, 2026-09-04 사용자 지시로 등록 —
    **완료(2026-09-05, wave 1~5). 로컬 매트릭스 대상 60개 명령 전부 +
    wire 매트릭스 대상 8개 명령 전부, 합계 68개 명령이 예외 없이
    36개(로컬) 또는 21개(wire) requirement 조합 전체에서 검증됨. 상세
    진행 이력은 아래 각 wave 문단 및 [[exhaustive-interop-matrix-plan]]
    §4 참고.**
    requirement 매트릭스(36개 조합, native 6 + Docker 30, 전부 GREEN 확정)는
    원래 `CommitCommand`/`LogCommand`/`StatusCommand`/`CatCommand` **4개 명령**
    에만 적용돼 있었고, 나머지 로컬/저장소 전용 명령 55개(`AddCommand`,
    `AmendCommand`, `BookmarkCommand`, `MergeCommand`, `RebaseCommand`,
    `ShelveCommand`, `StripCommand`, `SubrepoCommand` 등, 전체 목록은
    [[exhaustive-interop-matrix-plan]] §3-2)는 이 36개 조합 전체를 통과한 적이
    없었다. wire 매트릭스(21개 조합, HTTP 18 + SSH 3, 전부 GREEN 확정)도
    `CloneCommand`/`PullCommand`/`PushCommand` **3개 명령**에만 적용돼 있고,
    `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
    `NarrowCloneCommand` 5개는 여전히 미착수([[exhaustive-interop-matrix-plan]]
    §4, 이 wave에서 다루지 않음).

    **Wave 1 진행 현황(2026-09-05)**: 사용자 지시로 우선순위 4개 명령
    (`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`)에 requirement
    매트릭스(native 6 + Docker 30 = 36개 조합)를 확장 적용. 새 테스트 클래스
    8개 추가(명령당 native/Docker 각 1개 — `RequirementMatrixPushCoreRoundTripTest`/
    `RequirementMatrixPushDockerRoundTripTest`/`RequirementMatrixRebaseCoreRoundTripTest`/
    `RequirementMatrixRebaseDockerRoundTripTest`/`RequirementMatrixShelveCoreRoundTripTest`/
    `RequirementMatrixShelveDockerRoundTripTest`/`RequirementMatrixStripCoreRoundTripTest`/
    `RequirementMatrixStripDockerRoundTripTest`, 전부 `src/test/java/io/github/search5/hg4j/api/`)
    + Docker 쓰기 경로 corruption 회피용 subprocess 헬퍼 4개(`RequirementMatrixStripHelperMain`/
    `RequirementMatrixRebaseHelperMain`/`RequirementMatrixShelveHelperMain`/
    `RequirementMatrixPushHelperMain`, 기존 `RequirementMatrixCommitHelperMain`과
    동일한 패턴 재사용). 명령별 결과:

    - **`RebaseCommand`**: native 6/6 + Docker 30/30 전부 GREEN. 새 실버그
      없음(diverging 2-branch 히스토리를 rebase하는 시나리오가 모든 조합에서
      깨끗하게 통과).
    - **`StripCommand`**: native 6/6 + Docker 30/30 전부 GREEN — 단, 실제
      운영 코드 버그 2건을 TDD로 발견·수정한 뒤에야 도달함(아래 "발견·수정한
      실버그" 참고). 이 수정으로 기존에 원인 불명이던 `StripRealHgInteropTest`의
      사전 존재 실패 2건(백로그 23 완료 보고에 "무관한 사전 존재 실패"로
      기록돼 있던 것)도 근본 원인이 규명되어 함께 해소됨.
    - **`ShelveCommand`**: native 6/6 + Docker 30/30 전부 GREEN — `StripCommand`와
      동일한 근본 원인의 버그를 공유하고 있어 같은 수정으로 함께 해소.
      추가로 "changelog-v2인데 sidedata-copies 기능은 안 쓰는" 조합(`cl2`,
      `cl2+sidedata` 아님)에서 **real hg 자신의 결함**을 발견(아래 참고) —
      hg4j 문제가 아니므로 테스트에서 명시적으로 tolerate 처리.
    - **`PushCommand`**: **native 6/6 + Docker 30/30 전부 GREEN(2026-09-05,
      사용자 지시로 같은 세션 내에서 즉시 후속 수정 — 아래 "발견·수정한
      실버그" #3/#4 참고)**. 처음엔 native 2/6, Docker 14/30만 GREEN이었고
      나머지는 전부 2가지 진짜 hg4j 프로덕션 버그(treemanifest dirlog 미전송,
      changelog-v2+sidedata 미전송)로 실패했었다 — 실패 패턴이 매우
      일관적이었음(native/Docker 양쪽에서 실패한 조합은 전부 `treemanifest`
      또는 `cl2+sidedata`를 포함하는 조합뿐이고, `persistent-nodemap`/
      `fileindex-v1`/`general-v2`/`dirstate-v2` 자체는 새로운 실패를 유발한
      적이 없어 Docker 16개 실패 = treemanifest 9개 + cl2+sidedata·flat 7개로
      정확히 예측과 일치했음). 사용자가 "백로그 39 범위 안에서 지금 바로
      고치라"고 지시해 같은 세션에서 두 버그 모두 수정, 재검증까지 완료.

    **발견·수정한 실버그 2건(`StripCommand`/`ShelveCommand` 공유 근본 원인)**:
    1. `StripCommand`/`ShelveCommand`가 각자 따로 구현하고 있던 revlog 물리
       truncate 로직이 **inline revlog**(backlog #35로 신규 non-changelog v1
       revlog의 기본값이 된 레이아웃)와 **v2/docket 기반 revlog**(changelog-v2/
       general-v2)를 전혀 다루지 못했다 — 항상 "non-inline, 64바이트 고정
       레코드"만 가정. inline revlog를 그렇게 자르면 real hg가 "index
       00manifest is corrupted"로 완전히 거부(이게 바로 위에서 언급한
       `StripRealHgInteropTest`의 원인불명 사전 존재 실패 2건의 정체였음).
       v2/docket을 그렇게 자르면 실제 데이터 파일이 아니라 docket 헤더
       파일 자체를 잘라버리고, 그마저도 실제 companion 파일(UUID 기반
       `<radix>-<uuid>.idx`/`.dat`) 경로를 전혀 모른 채 엉뚱한 경로를
       건드려 다음 open에서 `BufferUnderflowException`. 두 명령의 중복
       로직을 없애고 **`Revlog.truncate(int keepCount)`** 신규 공용
       메서드(`storage/Revlog.java`)로 통합 — inline/non-inline v1/v2 세
       가지 레이아웃 전부 올바르게 처리(v2는 `RevlogIndex.updateV2DocketSizes()`
       로 docket의 index_end/data_end까지 갱신). `StripCommand.truncateRevlog()`/
       `ShelveCommand.stripRevisionsFrom()`의 중복 구현은 삭제하고 이
       메서드를 호출하도록 변경.
    2. 위 통합 도중 발견한 2차 버그: non-inline 분기에서 `.i` 파일을 먼저
       물리적으로 truncate한 뒤에 `.d` 파일 truncate 크기를 계산하려고
       `getRevisionCount()`를 다시 호출하면, `RevlogIndex.checkAndUpdate()`가
       "방금 줄어든 `.i` 파일 크기"를 보고 revisionCount를 이미 줄어든 값으로
       재계산해버려 `.d` truncate 자체가 조용히 no-op이 되는 버그(정확한
       크기 계산은 반드시 두 파일 다 건드리기 **전**에 끝내야 함). 이 버그가
       `ShelveRealHgInteropTest#unshelveAbortRestoresPreUnshelveStateAndKeepsShelfUsable`
       회귀를 유발했다가(원인 규명 도구: `git stash`로 수정 전/후 A/B 테스트),
       계산 순서를 "먼저 계산, 나중에 두 파일 다 truncate"로 고쳐 해결.
       전체 회귀(비-interop `test` + `interopTest`)로 재확인, 새 회귀 없음.

    **real hg 자신의 결함 1건(hg4j 버그 아님, 발견만 함)**: changelog-v2를
    쓰면서 sidedata-copies 기능은 켜지 않은 저장소(`cl2` 조합)에서
    `hg shelve`(real hg 자신의 것) 자신이 v2 docket이 참조하는(항상
    비어있고 안 쓰이는) `<radix>-<uuid>.sda` companion 파일을 삭제해버리면서도
    docket 헤더의 sidedata_uuid 필드는 그대로 남겨둬서, 그 이후 아무 때나
    `hg verify`가 "No such file or directory: ...sda"로 abort. **hg4j를
    전혀 거치지 않는 순정 real hg 단독 재현으로 확인**(`hg init` →
    `hg shelve` → `hg unshelve` → `hg verify`만으로 100% 재현). 테스트에서
    이 특정 시그니처만 명시적으로 tolerate.

    **발견·수정한 진짜 hg4j 버그 2건 더(`PushCommand`, 2026-09-05 같은 세션
    내 즉시 후속 수정 — 사용자가 "백로그 39 범위 안에서 지금 바로 고치라"고
    명시적으로 지시)**:
    3. **treemanifest 조합에서 디렉터리 manifest(dirlog)를 전혀 전송하지
       않던 버그**: `PushCommand`의 changegroup 빌드 로직(`repository.getManifestRevlog()`
       만 사용)은 루트 manifest revlog 하나만 다루고, `meta/<dir>/00manifest.i`
       형태의 디렉터리별 manifest revlog는 전혀 열거하지 않았다 — 반면
       읽는/받는 쪽인 `FetchCommand.applyBundle()`은 이미
       `bundle.manifestGroups`(디렉터리별 그룹)를 완전히 지원하고 있었다.
       근본 원인은 더 근본적이었다: `PushCommand`가 항상 cg1(`HG10UN`)만
       하드코딩해서 만들고 있었는데, cg1 자체가 treemanifest 봉투(`bundle.
       manifestGroups`)도 sidedata 청크도 구조적으로 실어 나를 수 없는
       포맷이었다(`ChangegroupParser#isTreeCapableVersion`은 cg3/cg4/cg5만
       true). `HgLocalClient#getBundle()`(pull/getbundle 응답 방향)이 이미
       `bundleCaps`에서 버전을 협상해 `ChangegroupParser.writeBundle()`로
       패킹하는 것과 같은 패턴을, push 쪽은 목적지 능력 협상 대신 **"이
       push가 보내는 데이터 자체가 무엇을 요구하는지"로 직접 결정**하도록
       수정(로컬 file:// 목적지는 애초에 capabilities에 bundle2= 정보가
       없어 협상이 불가능함을 확인 후 내린 판단) — sidedata가 필요하면
       cg5, treemanifest만 필요하면 cg3(둘 다 아니면 기존 그대로 cg1, 압도적
       다수인 평범한 저장소는 와이어 바이트 변화 없음). 디렉터리 dirlog
       열거는 fncache에 등록되지 않으므로(파일로그와 달리) `store/meta/`
       트리를 직접 재귀 스캔해서 찾는 방식으로 구현(`CommitCommand
       #writeTreeManifestDir`가 쓰는 것과 동일한 무-인코딩 경로 규칙 재사용).
       hand-롤 cg1 전용 writer(`writeEntryChunk`/`writePathChunk`/
       `writeTerminalChunk`)를 완전히 제거하고 `ChangegroupParser.writeBundle()`
       (이미 cg1-5 전부 지원, `HgLocalClient#getBundle()`이 실전 검증한 것과
       동일한 코드)을 재사용하도록 교체. 필요하면 `Bundle2Parser.
       wrapChangegroupInBundle2()`로 HG20 봉투도 씌운다(로컬/서버 양쪽 다
       `HgLocalClient#pushWithHooks()`가 이미 HG20 파싱을 지원 — 백로그 26).
    4. **changelog-v2 + sidedata-copies 조합에서 sidedata를 전혀 전송하지
       않던 버그**: `PushCommand`에는 sidedata 관련 코드가 아예 없었다.
       `HgLocalClient#getBundle()`의 `packChangelogSidedata`(백로그 26)와
       완전히 대칭인 로직을 push 쪽에도 추가 — 위에서 결정한 버전이 cg5이고
       저장소가 `isSidedataCopies()`이면 각 changelog 엔트리에 `changelog.
       getSidedata(r)`로 읽은 sidedata를 `SidedataCodec.serialize()`로
       실어 보낸다. 받는 쪽(`Revlog.appendChangeGroupEntry`)은 이미
       `entry.sidedata`를 처리하는 코드가 있었다(pull 방향에서 이미
       검증됨) — push 쪽에 대칭 코드를 추가하는 것만으로 충분했다.
       부수적으로 cg2+ 포맷이 요구하는 `deltabase`/`flags` 필드도 changelog/
       manifest(root)/filelog 세 그룹 전부에 채워 넣었다(cg1은 스트림 순서로
       암묵적으로 나타내던 것을 cg2+는 명시적 필드로 요구함) — 파일로그와
       신규 dirlog 패킹은 완전히 동일한 규칙이라 `packRevlogRange()`라는
       공용 헬퍼로 통합.

       **검증**: 두 수정 후 `RequirementMatrixPushCoreRoundTripTest`(native
       6/6) + `RequirementMatrixPushDockerRoundTripTest`(Docker 30/30) 전부
       GREEN 재확인. 기존 push 관련 테스트(`PushCommandTest`,
       `CatUpdateClonePushCoverageTest`, `CHgPushRoundtripTest`,
       `PushRealHgInteropTest`, `PushLockRaceRealHgInteropTest`)와 비-interop
       `test` 전체(2270+건)도 회귀 없음 재확인.

    **환경 메모**: 이 세션에서 Docker의 `localhost/hg-rust-7.2.4` 태그가
    사라져 있어(이미지 자체는 `hg-rust-7.2.4:latest`로 존재) 처음엔 Docker
    매트릭스 전체가 조용히 스킵됐다 — `docker tag hg-rust-7.2.4:latest
    localhost/hg-rust-7.2.4`로 로컬 재태깅해 해결(코드 변경 아님, 이 머신의
    Docker 상태일 뿐 — 다음 세션이 같은 머신이 아니면 다시 필요할 수 있음).

    **Wave 2(2026-09-05, PushCommand 수정 완료 직후 사용자 지시로 계속
    진행)**: 다음 우선순위 명령으로 `AmendCommand`를 선택 — `CommitCommand`
    (이미 원본 36개 조합 전체에서 검증됨)에 실제 쓰기를 위임하고 obsstore에
    평문 마커만 추가하는 얇은 명령이라, "이미 검증된 저수준 원시 동작에
    위임하는 명령이 모든 조합에서 정말 안전하게 동작하는지"를 확인하는
    좋은 다음 표본으로 판단(사용자가 권장한 "방금 고친 것과 코드 경로를
    공유하는 명령" 기준과는 별개로, "이미 검증된 CommitCommand에 위임하는
    명령들이 조합 전체에서 안전한지"라는 다른 유용한 신호). `RequirementMatrixAmendCoreRoundTripTest`
    (native)/`RequirementMatrixAmendDockerRoundTripTest`(Docker) +
    `RequirementMatrixAmendHelperMain`(subprocess 헬퍼) 신규 추가.
    **Native 6/6 + Docker 30/30 전부 GREEN** — 새 실버그 없음(`CommitCommand`로의
    위임이 모든 36개 조합에서 안전함을 재확인).

    **Wave 3(2026-09-05, 사용자 지시로 계속 진행)**: `MergeCommand`/`SubrepoCommand`
    두 명령을 함께 배정(코드 공유는 없음, 스케줄링 편의상 묶음). 신규 테스트
    클래스 6개 추가(`RequirementMatrixMergeCoreRoundTripTest`/
    `RequirementMatrixMergeDockerRoundTripTest`/`RequirementMatrixMergeHelperMain`,
    `RequirementMatrixSubrepoCoreRoundTripTest`/
    `RequirementMatrixSubrepoDockerRoundTripTest`/`RequirementMatrixSubrepoHelperMain`).
    **두 명령 다 native 6/6 + Docker 30/30 전부 GREEN.**
    - **`MergeCommand`**: 두 갈래로 갈라진 히스토리의 충돌 없는 3-way 병합과,
      양쪽이 같은 줄을 고쳐 진짜로 충돌하는 병합(마커 확인 + 실제 hg
      `hg resolve --list`로 상태 대조 + hg4j `ResolveCommand`로 resolve 후
      커밋까지) 둘 다 36개 조합 전체에서 검증. 이 과정에서 `CommitCommand`에
      진짜 hg4j 버그 2건을 TDD로 발견·수정(아래).
    - **`SubrepoCommand`**: 기존 `SubrepoRealHgInteropTest`(이번 세션 앞서
      작성, git/hg 서브저장소 커밋·머지·체크아웃을 이미 상세히 검증)와
      중복을 피하기 위해, PARENT 저장소의 포맷/버전 조합 차원만 타겟팅
      — `add`로 서브저장소를 v1에 pin, `init`으로 clone+체크아웃, hg4j
      `CommitCommand`로 커밋(자동 `.hgsubstate` 스냅샷), pin을 v2로
      올린 뒤 `update`로 재체크아웃, 재커밋까지 36개 조합 전체에서 검증.
      진짜 hg4j 버그 1건을 TDD로 발견·수정(아래).

    **발견·수정한 진짜 hg4j 버그 3건**:
    1. **`SubrepoCommand`의 `init`/`update`가 pin된 리비전으로 실제
       워킹카피 체크아웃을 한 적이 없었음**: 기존 코드는 서브저장소를
       clone한 뒤(또는 이미 체크아웃된 경우) `.hgsubstate`에 적힌 리비전을
       서브저장소 dirstate의 parent 포인터에 그대로 써넣기만 했지, 그
       리비전에 맞는 파일 내용으로 워킹카피를 갱신하는 실제 체크아웃은
       한 번도 수행하지 않았다 — pin된 리비전이 clone 기본 체크아웃(tip)과
       다르거나(멀티 리비전 소스), 이미 체크아웃된 서브저장소의 pin이
       나중에 다른 값으로 bump된 경우(`update`의 원래 목적 그 자체) 워킹카피
       내용이 조용히 어긋난 채로 남았다. 기존 단위테스트가 이 케이스를
       놓친 이유는 전부 소스가 커밋 1개짜리라 clone 기본 tip == pin이라
       버그가 우연히 가려져 있었기 때문. `UpdateCommand`(강제 업데이트,
       필요시 pull까지)로 위임하도록 수정 — 이미 검증된
       `UpdateCommand.checkoutSubrepoEntry`와 동일한 real-hg `hg update -S`
       동작을 재사용.
    2. **`CommitCommand`의 미해결 병합 충돌 차단 로직이 실제 hg와 다른
       기준으로 동작**: 기존 코드는 dirstate가 `m` 상태인 파일의 "현재
       디스크 내용에 `<<<<<<<`/`=======`/`>>>>>>>` 리터럴 텍스트가 있는지"만
       스캔해서 커밋을 막았다 — 실제 hg(`mercurial/commands.py`의 `commit`,
       `mergestatemod.mergestate.read(repo)` + `ms.unresolvedcount()`)는
       머지 상태(`.hg/merge/state2`) 자체의 resolved/unresolved 플래그만
       본다. 텍스트 스캔 방식은 두 방향 모두 실제 hg와 어긋난다: (a) 이미
       resolve된 파일이 우연히 저 마커 문자열을 정당하게 담고 있으면(예:
       diff/patch 파일 자체) 영원히 커밋이 막히고, (b) `hg resolve --tool
       internal:local`/`:other`처럼 마커를 아예 안 쓰는 방식으로 resolve한
       뒤에도 실제 hg는 여전히 막아야 하는데 이 스캔은 통과시켜버린다.
       머지 상태 파일(`MergeState.read` + `unresolvedFiles()`)을 직접
       확인하도록 수정, 예외 메시지도 실제 hg 문구(`"unresolved merge
       conflicts (see 'hg help resolve')"`)로 맞춤. 기존 `CommitCommandTest`/
       `MergeCommandCoverageTest`의 관련 테스트 2개는 실제 머지 상태 없이
       텍스트만 흉내 내던 비현실적 시나리오였어서, 진짜 2-parent dirstate +
       `.hg/merge/state2`를 갖춘 시나리오로 함께 갱신.
    3. **`CommitCommand`가 성공적인 머지 커밋 후 `.hg/merge`를 전혀 정리하지
       않음**: 실제 hg 7.2 CLI로 직접 재현해 확인(`hg merge` → 충돌 →
       `hg resolve -m` → `hg commit` → `ls .hg/merge`가 "No such file or
       directory") — 실제 hg는 머지가 커밋으로 확정되면 `.hg/merge` 디렉터리
       자체를 통째로 지운다(`mergestatemod.mergestate.reset()`). hg4j는
       이걸 전혀 하지 않아서, 커밋 후에도 실제 hg `hg resolve --list`가
       이미 끝난 머지의 파일을 계속 unresolved로 잘못 보고하고, 충돌 시
       남긴 `.hg/merge/<localkey>` 백업 파일들도 영원히 남아있었다.
       2-parent 커밋이 실제로 진행된 경우(`dirstate.getParent2Node()` 존재)
       커밋 성공 직후 `.hg/merge`를 재귀 삭제하도록 수정.

    **검증**: 위 3건 수정 후 `RequirementMatrixMergeCoreRoundTripTest`(native
    6/6, clean+conflict 각 6 = 12개) + `RequirementMatrixMergeDockerRoundTripTest`
    (Docker 30/30 x 2 = 60개) + `RequirementMatrixSubrepoCoreRoundTripTest`
    (native 6/6) + `RequirementMatrixSubrepoDockerRoundTripTest`(Docker 30/30)
    전부 GREEN. 비-interop `test`(2270+건) + `interopTest`(587건, 무관한
    사전 조건부 스킵 8건 제외 전부 통과) 전체 재확인 — 새 회귀 없음.
    **Wave 3(2026-09-05, `AmendCommand` 완료 이후 다른 wave 3 에이전트와 병렬
    진행)**: `HisteditCommand`/`GraftCommand`에 requirement 매트릭스(native 6 +
    Docker 30 = 36개 조합) 확장 적용 — 사용자 지시로 두 명령을 같은 에이전트에
    묶어 순서대로("`RebaseCommand`와 cherry-pick/rewrite 내부 로직을 공유하는지"
    확인 목적) 진행. 신규 테스트 클래스/헬퍼 6개(`RequirementMatrixHisteditCoreRoundTripTest`/
    `RequirementMatrixHisteditDockerRoundTripTest`/`RequirementMatrixHisteditHelperMain`/
    `RequirementMatrixGraftCoreRoundTripTest`/`RequirementMatrixGraftDockerRoundTripTest`/
    `RequirementMatrixGraftHelperMain`, 전부 `src/test/java/io/github/search5/hg4j/api/`).

    - **`HisteditCommand`**: **native 6/6 + Docker 30/30 전부 GREEN** — TDD로
      실제 프로덕션 버그 2건 발견·수정:
      1. `commitNewRev()`가 새 매니페스트 리비전을 직렬화할 때 `LinkedHashMap`
         (부모의 이미 정렬된 매니페스트로 seed된 뒤 fold/pick 그룹이 새 경로를
         처리 순서대로 추가)의 `entrySet()` 삽입 순서를 그대로 썼다 — fold/pick이
         새로 도입한 경로가 정렬 위치가 아니라 처리 순서에 삽입되어 real hg
         `verify`가 "Manifest lines not in sorted order"로 실패(요구사항 매트릭스
         테스트로 최초 재현, 6/6 native 조합 전부에서 재현됨). `NodeIdUtil.
         UTF8_STRING_COMPARATOR`로 직렬화 직전 명시적 재정렬하도록 수정.
      2. 히스테디트 종료 시 워킹 디렉터리에서 dropped/제거된 경로의 물리 파일은
         지웠지만 dirstate 엔트리는 그대로 남겨둬(`Files.deleteIfExists`만 하고
         `dirstate.removeEntry`는 호출한 적이 없었음) real hg `verify`가 "<path>
         marked as tracked in p1 (...) but not in manifest1" + "dirstate
         inconsistent with current parent's manifest"로 실패(1번 버그를 고친
         뒤에야 verify가 여기까지 도달해 드러남). 히스테디트 이전/이후 매니페스트를
         비교해 dirstate를 완전히 재동기화(제거된 경로는 `removeEntry`, 새로
         추가되었거나 내용이 바뀐 경로는 'n'으로 재등록)하도록 수정.
      3. (진짜 버그라기보다 real hg 자신의 evolution 동작과의 gap) DROP된
         리비전에 대해 prune obsmarker(빈 successor 집합)를 전혀 남기지 않아
         DROP된 리비전이 real hg의 plain `hg log`에 영원히 계속 보임 — real
         `hg histedit`를 `experimental.evolution=all`로 직접 재현해 대조
         검증(DROP된 리비전에 대해 정확히 "precursor + 빈 successor 집합" 형태의
         prune obsmarker를 남기고, `hg log`(evolution 없이도)에서 사라짐을 확인).
         `StripCommand.call()`이 이미 쓰는 것과 동일한 `HgObsMarker.writeMarker(
         ..., List.of(), "prune")` 패턴을 DROP 처리 분기에 추가.

      **`RebaseCommand` 중복 여부 점검 결과 — 중복 아님**: `HisteditCommand`는
      항상 "현재 체크아웃된 같은 브랜치의 연속 구간"만 재작성하는 구조라(별도의
      diverging destination이 없음) rebase/graft가 필요로 하는 3-way merge
      개념 자체가 이 명령의 설계상 구조적으로 불필요하다 — 각 리비전이 자기
      자신의 "최종 절대 매니페스트 내용"을 그대로 재생(진짜 diff 적용이 아니라
      스냅샷 복사)하는 방식이라 소스/목적지 분기(divergence)라는 개념 자체가
      없다. 이 자체 설계는 이미 이전 세션에서 fold/roll의 파일 병합·저자/브랜치
      유지·빈 설명 처리 등 다수의 실버그를 수정하며 상당히 하드닝되어 있었고,
      이번 세션에서 발견한 2건(매니페스트 정렬, dirstate 재동기화)은 rebase의
      cherry-pick 로직과는 무관한 이 명령 고유의 새 버그였다.

    - **`GraftCommand`**: **native 6/6 + Docker 30/30 전부 GREEN** —
      **`RebaseCommand`의 2026-09-04 하드닝 이전(구식) cherry-pick 로직을
      독자적으로 재구현하고 있던 실제 사례를 발견, 공용 로직 재사용으로 전환**
      (이번 wave의 핵심 발견 — 사용자가 미리 지시한 가설이 실제로 맞아떨어짐):
      1. **3-way merge/conflict 감지가 전혀 없었음(진짜 data-loss 버그)**:
         destination이 graft source와 공통 조상(source 리비전 자신의 parent)
         이후 같은 경로를 다르게 바꿨어도 hg4j `GraftCommand`는 항상 source
         내용으로 무조건 덮어써 destination의 독립적인 변경사항을 자동으로
         유실시켰다. Real `hg graft` 7.2로 직접 재현해 대조 검증(동일 시나리오
         에서 real hg는 "no tool found to merge", "file ... needs to be
         resolved"로 멈추고 사용자에게 keep/take/leave를 묻는다 -- 절대
         조용히 하나를 택하지 않음). `RebaseCommand`의 2026-09-04 하드닝에서
         만든 `Merge3` 기반 3-way merge + `.hg/merge/state2` 충돌 마커 로직의
         핵심 부분(`attemptThreeWayMerge`)을 `RebaseCommand`의 package-private
         static 메서드로 추출해 `GraftCommand`가 **그대로 재사용**하도록
         리팩터링(독립 재구현 아님 — 백로그 39 작업 지시의 "중복이면 공유
         로직으로 교체" 원칙을 그대로 적용). `GraftCommand`에 `continueGraft()`/
         `abort()`를 신규 추가해 `RebaseCommand`와 동일한 hg4j 자체
         pause/resume 프로토콜(mid-flight 상태는 real hg와 바이트 단위로
         맞출 필요 없음 — 최종 상태만 real hg로 왕복 검증되면 됨) 지원.
      2. **크래시 안전 저널이 전혀 없었음**: `GraftCommand`는 `CommitCommand`를
         `setSkipLockAndJournal(true)`로 호출하면서도 자기 자신의 저널/백업을
         전혀 만들지 않았다 — 같은 패턴을 쓰는 `RebaseCommand`/`HisteditCommand`
         는 둘 다 자체 저널을 갖고 있는 것과 비대칭. 논리적 실패(예외)는
         `CommitCommand` 자신의 인메모리 롤백으로 이미 커버되지만, 실제
         프로세스 크래시가 커밋 도중 발생하면 복구할 방법이 전혀 없는 실제
         gap이었다. `HisteditCommand`와 동일한 패턴(사전 파일 크기 스냅샷 +
         물리 저널 기록 + 예외 시 truncate 기반 롤백)을 커밋 위임 부분에
         신규 추가.
      3. **모든 graft에 무조건 obsolescence marker를 기록하던 버그**: real
         `hg graft` 7.2로 직접 검증 — 평범한 `hg graft REV`(`--log` 없이)는
         obsmarker를 전혀 남기지 않고 source 리비전이 grafted 결과와 나란히
         plain `hg log`에 그대로 남는다(graft는 rewrite가 아니라 copy 연산).
         hg4j `GraftCommand`는 무조건 predecessor(source)→successor(grafted)
         obsmarker를 기록하고 있어서, real hg로 그 저장소를 읽으면 원본
         source 리비전이 hidden 처리되어 사라지는 실제 버그(직접 재현: 임의의
         두 리비전 사이에 real hg 자신의 `hg debugobsolete`로 동일한 형태의
         마커를 수동으로 심어보니 "obsoleted 1 changesets"가 뜨며 원본이
         plain log에서 사라짐을 확인 — hg4j를 전혀 거치지 않은 순정 real hg
         재현). obsmarker 기록 코드 전체를 제거.

      **검증**: 세 버그 모두 real hg 7.2 CLI로 직접 재현/대조 검증 후 수정,
      `RequirementMatrixGraftCoreRoundTripTest`(native 6/6 — 각 조합마다
      충돌 없는 diverging-branch graft + 충돌 발생·`hg resolve --list`/
      `continueGraft()` 재개까지 두 시나리오 모두 확인)/
      `RequirementMatrixGraftDockerRoundTripTest`(Docker 30/30, 동일 두
      시나리오, conflict/continue 왕복은 `RequirementMatrixGraftHelperMain`의
      `call`/`continue` 2-phase 서브프로세스로 처리)로 재확인. 기존
      `GraftCommandCoverageTest`도 새로 옳아진 동작에 맞춰 갱신 -- 구식
      "항상 source로 무조건 덮어쓰기" 동작을 정답으로 assert하던 테스트 1개를
      real-hg-검증된 conflict-pause 동작(+ `abort()` 왕복)으로 교체, obsmarker
      관련 테스트 2개는 제거/개명, 3-way merge 클린 케이스 및
      `continueGraft()` 성공 케이스 테스트 2개 신규 추가.

    - **공용 리팩터링(`RebaseCommand` 자신은 무변경 동작 재확인)**:
      `RebaseCommand`의 cherry-pick 3-way-merge 핵심 로직(신규
      `attemptThreeWayMerge` + `ThreeWayMergeOutcome`)과 워킹카피/머지상태
      헬퍼(`checkoutNode`/`applyManifestToWorkingCopy`/`writeFileToWorkingCopy`/
      `deleteFileFromWorkingCopy`/`applyResolvedContent`/`restoreWorkingCopyCleanTo`/
      `cleanMergeDir`/`mergeStateFile`)를 인스턴스 메서드에서 `HgRepository`를
      인자로 받는 (package-private 또는 그대로 private) static 메서드로
      승격 — `GraftCommand`가 필요한 것만(`attemptThreeWayMerge`/
      `restoreWorkingCopyCleanTo`/`cleanMergeDir`/`mergeStateFile`) 재사용.
      순수 추출 리팩터링(동작 변경 없음)이라 `RebaseCommand`의 기존 테스트
      전부(`RebaseCommandTest`/`RebaseCommandCoverageTest`/
      `RebaseRealHgInteropTest`, 충돌 시나리오 3건 포함) +
      `RequirementMatrixRebaseCoreRoundTripTest`(native 6/6) 재확인 —
      새 회귀 없음.

    **Wave 3(2026-09-05, `BundleCommand`)**: Wave 2 완료 시점에 이미
    "`PushCommand`의 수정 전과 동일한 cg1-only 하드코딩"으로 지목돼 있던
    `BundleCommand`(`hg bundle` — 독립 번들 FILE 산출, push/pull의
    over-the-wire changegroup 전송과는 별개지만 changegroup 패킹 로직 대부분을
    공유)에 requirement 매트릭스를 확장. 조사 결과 예상대로 `PushCommand`와
    똑같은 근본 버그를 갖고 있었다 — 자체 `writeEntryChunk`/`writePathChunk`/
    `writeTerminalChunk` 헬퍼로 bare cg1 바이트만 손으로 조립해서, treemanifest
    서브디렉터리 그룹을 구조적으로 절대 실어보낼 수 없었다(어떤 `--type`을
    줘도 항상 깨진 번들). `ChangegroupParser.writeBundle`(협상된 버전)로 교체하고,
    `PushCommand`의 `packRevlogRange`와 동일한 규칙의 `packRevlogForSelectedRevs`
    헬퍼로 treemanifest dirlog까지 패킹하도록 수정 — **BundleType에 `NONE_V3`/
    `GZIP_V3`/`BZIP2_V3`(cg3-in-bundle2/HG20) 3종 신규 추가**, 기존 `NONE_V1`/
    `GZIP_V1`/`BZIP2_V1`(cg1-only)은 완전히 하위호환 유지. real hg 자신이
    treemanifest 저장소에 `--type none-v1`(및 명시적 `--type` 없는 기본값
    `bzip2`조차)를 주면 "repository does not support bundle version 01/02"로
    **abort**하는 것을 직접 재현 확인했으므로(`changegroup.supportedoutgoingversions()`가
    treemanifest 저장소에선 `{03, 04}`뿐), `BundleCommand.call()`도 이를 그대로
    미러링 — treemanifest 저장소에 v1 계열 타입을 요청하면 real hg와 동일한
    문구로 `IllegalStateException`을 던지고, v3 계열 타입을 쓰면 정상 동작한다
    (real hg `--type none-v3`와 byte-for-byte 대조 검증: cg3 payload는
    `ChangegroupParser`가 이미 담당, bundle2 envelope 압축은 `Bundle2Parser`에
    `wrapChangegroupInBundle2(bytes, version, compression)` 오버로드를 신규
    추가해 GZ/BZ 스트림 압축까지 실제 hg 바이트와 대조 확인).

    **sidedata(cg5)는 의도적으로 미대응** — `PushCommand`와 달리 이번엔 실제
    hg 자신의 근본적 한계이지 hg4j 버그가 아님을 3중으로 직접 재현 확인(전부
    2026-09-05, Mercurial 7.2.2 기준): (1) `hg bundle` CLI 자체
    (`mercurial/cmd_impls/bundle.py`)가 cg 버전을 01/02/03/04 넷으로만
    하드코딩 분기하고 그 외(05 포함)는 무조건
    `error.ProgrammingError`을 던진다 — `--type "none-v2;cg.version=05"`로
    직접 재현, repo가 실제로 `changegroup.supportedoutgoingversions()`에서
    05를 지원하는지 여부와 무관하게 `hg bundle`에는 애초에 cg5를 만들
    방법 자체가 없다(대응하는 `v4`/`v5` bundlespec도 없음). (2) CLI 가드를
    우회해 Python `mercurial.changegroup`/`bundle2` API로 직접 cg5 bundle2
    FILE을 만들어 real `hg unbundle`로 적용해도 `hg verify`가
    "in manifest but not in changeset"/"rev 0 points to unexpected
    changeset" 무결성 오류를 낸다. (3) 결정적으로, 이건 cg5나 수제 번들
    한정 문제가 아니다 — **순정 real hg가 만든 평범한 cg1 `none-v1` 번들
    FILE**을 real `hg unbundle`로 `exp-use-copies-side-data-changeset=yes`
    저장소에 적용해도 **완전히 동일한** 무결성 오류가 난다(hg4j가 전혀
    개입하지 않는 순수 real-hg-to-real-hg 컨트롤 재현). 같은 포맷에서
    sidedata 플래그만 뺀 순수 `exp-changelog-v2`는 파일 기반
    번들/unbundle 왕복이 깨끗하다 — 그리고 **라이브 peer-to-peer 교환**
    (번들 FILE 없이 순수 `hg push`/`hg pull`)은 멀쩡하다(`PushCommand`가
    cg5를 안전하게 지원할 수 있었던 이유). 즉 real hg 7.2의
    `exp-copies-sidedata-changeset`(이름 그대로
    `enable-unstable-format-and-corrupt-my-data`) 구현 자체가 파일 기반
    bundle/unbundle 경로에서만 깨지는 것이지, hg4j가 "고칠" 수 있는 문제가
    아니다(real hg 자신의 깨진 바이트에 맞추는 게 오히려 스펙 이탈).

    **매트릭스 결과**: native 6/6 + Docker 30/30 전부 "GREEN"(테스트
    성공)이지만, 이 중 `cl2+sidedata` 조합(native 2개, Docker 10개)은
    "`hg verify` 무결성 오류 = 확인된 real hg 자체 한계"로 명시적으로
    tolerate 처리(위 (3) 컨트롤 재현이 근거) — hg4j 회귀가 아님을 테스트
    코드 자체에 근거와 함께 문서화. 나머지 24/36(비-sidedata 조합, 특히
    treemanifest 포함)은 완전히 깨끗한 `hg verify` 통과. 새 테스트 클래스
    3개 추가(`RequirementMatrixBundleCoreRoundTripTest`/
    `RequirementMatrixBundleDockerRoundTripTest`/
    `RequirementMatrixBundleHelperMain`, 전부
    `src/test/java/io/github/search5/hg4j/api/`) — 기존
    `BundleCommandTest`(8케이스, 전부 cg1/v1 타입 대상) 회귀 없음 재확인.

    **남은 것(대부분 미착수, 이 문단 시점 기준)**: 전체 목록
    [[exhaustive-interop-matrix-plan]] §3-2에서 이번 문단까지 다룬 9개
    (`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`/
    `AmendCommand`/`MergeCommand`/`SubrepoCommand`/`HisteditCommand`/
    `GraftCommand`)와 이 문단 바로 아래 이어지는 `BundleCommand`를 제외한
    나머지 로컬 명령, wire 매트릭스의 나머지 5개 명령
    (`FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
    `NarrowCloneCommand`). "67개 명령 × 매트릭스" 전체 목표 중 실제로
    완주된 것은 이 문단 시점 기준 명령 기준 17/67(4+3+5+4+1 — `PushCommand`도
    이제 실버그 없이 완주로 카운트, `AmendCommand`/`BundleCommand`도 포함,
    `BundleCommand`는 sidedata 조합의 real-hg-자체-한계 tolerate까지 포함해
    완주로 카운트).

    **Wave 3(2026-09-05, `AddCommand`/`BookmarkCommand`/`TagCommand`, 사용자
    지시로 독립 진행)**: 세 명령 각각에 native 6개 + Docker 30개 = 36개 조합
    테스트 클래스 세트(`RequirementMatrix{Add,Bookmark,Tag}CoreRoundTripTest`/
    `...DockerRoundTripTest`/`...HelperMain`, 기존 8개와 동일 패턴 재사용)를
    신설. **세 명령 모두 native 6/6 + Docker 30/30 전부 GREEN.**

    - **`AddCommand`**: 새 실버그 없음. 시나리오는 이미 커밋된 루트 파일 1개 +
      신규 루트 파일 1개 + 신규 하위 디렉터리 파일 1개(루트 레벨 경로 세그먼트
      3개)를 추가해, 백로그 #37(dirstate-v2 자식 노드 정렬)이 재현하려면
      필요했던 "형제가 2개 이상인 루트" 모양을 정확히 만들어 그 수정이 커밋
      경로뿐 아니라 `AddCommand`가 직접 쓰는 dirstate 경로에서도 여전히
      유효함을 재확인.
    - **`BookmarkCommand`**: 진짜 hg4j 프로덕션 버그 3건 발견·수정(1건은
      매트릭스 통과 *이후* 전체 회귀에서 드러남 — 아래 참고).
      (1) `BookmarkCommand`에 `TagCommand`의 백로그 #36 force 게이트에
      대응하는 장치가 전혀 없었다 — 이미 존재하는 bookmark를 그 현재 위치의
      자손이 아닌 리비전으로 옮길 때 real hg 7.2는 `abort: bookmark '<name>'
      already exists (use -f to force)`로 거부하는데(CLI로 직접 실측,
      2026-09-05), hg4j는 아무 검사 없이 조용히 덮어썼다 — 순방향(자손,
      fast-forward) 이동과 동일 대상으로의 이동은 여전히 force 없이 허용,
      새 이름 생성도 그대로 허용, 되돌리기(backward)/divergent 이동만
      `HgValidationException` + 동일 메시지로 거부하도록 게이트 신설
      (`setForce(boolean)` 추가). 이 게이트의 "순방향" 판정은 처음엔 단순
      changelog DAG 조상 관계(`ChangesetGraph#isAncestor`)만 봤는데, 전체
      회귀(`test`+`interopTest`)를 돌리자 기존 `PushRealHgInteropTest#
      testPushOfBookmarkAdvancedAcrossAmendSucceedsWithoutForce`가
      즉시 깨졌다 — `hg amend`로 만든 후속 커밋은 원본 커밋의 **형제**(같은
      parent를 공유)일 뿐 자손이 아니므로, "amend 직후 활성 bookmark를
      후속 커밋으로 전진"이라는 실제 hg 자신의 표준 동작이 새 게이트에 막혀버렸다.
      `PushCommand`가 이미 `discovery._postprocessobsolete`/`bookmarks.
      validdest` 대응으로 구현해둔 것과 똑같은 "obsolescence-successor 체인도
      순방향으로 인정"하는 `obsutil.foreground` 로직(`isInForeground` +
      `.hg/store/obsstore` 파싱)을 `BookmarkCommand`에도 자체 구현(코드
      결합을 피하려 이미 검증된 `PushCommand`의 사본을 건드리지 않고 독립
      복사본으로 추가)해 최종 해결. 이 과정에서 진짜 2번째 버그도 드러났다:
      **`CommitCommand`가 매 커밋마다 활성 bookmark를 새 커밋으로 자동
      전진시키는 내부 호출**(active bookmark 자동 추적)이 새로 추가된 이
      게이트를 그대로 통과해야 했는데, `AmendCommand`는 obsmarker를 성공한
      커밋의 노드 해시가 있어야만 쓸 수 있어 항상 커밋 **다음**에 기록한다
      — 즉 `CommitCommand`의 내부 자동전진 호출 시점엔 obsstore에 predecessor→
      successor 링크가 아직 존재하지 않아 이 경로만은 foreground 판정이
      실패했다. real hg 자신도 이런 내부 재작성(`scmutil.cleanupnodes`)에
      의한 bookmark 이동은 사용자 대화형 `hg bookmark -r`의 validdest 게이트를
      거치지 않고 무조건 이동시키므로, `CommitCommand`의 그 한 곳만
      `.setForce(true)`로 게이트를 우회하도록 수정(일반 커밋의 경우 새 커밋의
      parent1이 정확히 옛 활성 위치라 어차피 항상 순수 fast-forward였으므로
      동작 변화 없음 — 변화는 amend류 내부 위임 경로에서만 발생). 별도로,
      `mergeFromRemote`가 이미 스스로 올바른 판단(로컬이 사라진 리비전을
      가리킬 때 원격을 그대로 채택)을 내린 뒤 호출하는 한 곳도 새 게이트를
      우회하도록 `setForce(true)`를 추가해 기존 pull/fetch 병합 동작은 그대로
      유지. (2) 마지막 남은 bookmark를 삭제하면 real hg는 `.hg/bookmarks`를
      0바이트 빈 파일로 남겨두는데(CLI로 직접 실측: 한 번이라도 생성된 적
      있는 파일은 지우지 않음), hg4j는 파일 자체를 삭제해버렸다 — 실질적 동작
      차이는 없지만(양쪽 다 "bookmark 없음"으로 읽힘) real hg와 바이트 단위로
      맞추도록 빈 문자열을 씀으로 수정. (3) 위 게이트 수정과 별개로, 기존
      `PushRealHgInteropTest#testPushRejectedWhenBookmarkMovedToDivergentSiblingWithoutObsolescenceLink`
      는 진짜 divergent(조상도 후속자도 아닌) 이동을 로컬 `BookmarkCommand`
      호출에서 force 없이 하고 있었다 — 그 테스트 자신의 기존 javadoc이 이미
      "실제 hg CLI 검증 시 force-moved"라고 적어뒀던 대로, 새 게이트가 생긴
      지금은 그 로컬 호출에 `.setForce(true)`를 붙이는 게 맞는 수정(테스트의
      실제 검증 대상은 그 다음 PUSH 시점의 거부이지, 로컬 이동 자체가 아님)
      — 테스트 쪽만 수정, `BookmarkCommand`/`PushCommand` 프로덕션 로직은
      그대로. 부수적으로, 매트릭스 시나리오를 작성하며 **하나의
      `HgRepository` 핸들을 real hg CLI의 외부 커밋 이후에도 계속 재사용하면
      안 된다**는 사실을 재확인(캐시된 `Revlog`가 외부에서 새로 쓰인 리비전을
      보지 못해 `HgRevisionNotFoundException`으로 실패) — 이건 hg4j
      프로덕션 코드의 버그가 아니라 `HgRepository#refreshIfChangedOnDisk()`
      의 javadoc이 이미 명시적으로 문서화한, 장기 보관 핸들(wire 서버)에만
      해당하는 의도된 설계이므로 테스트 쪽을 "명령마다 새 `HgRepository`"라는
      기존 매트릭스 전체의 관례에 맞게 고쳤을 뿐, 프로덕션 코드는 건드리지
      않음. **검증**: native 6/6+Docker 30/30 재확인, 위에서 깨졌던
      `PushRealHgInteropTest`(전체) 재확인, `RebaseRealHgInteropTest`/
      `ShelveRealHgInteropTest`/`StripRealHgInteropTest`/`TagRealHgInteropTest`
      /`BookmarkRealHgInteropTest`/`CommitCommandTest`/`CommitCommandCoverageTest`
      전부 GREEN, 전체 비-interop `test`(587건) GREEN. 전체 `interopTest`
      1회 완주 시도에서 무관한 기존 타이밍 플레이크(`PushLockRaceRealHgInteropTest`
      의 SSH 동시 레이스 테스트, 단독 재실행 시 통과 확인 — 동시 실행 중이던
      다른 wave-3 에이전트들의 부하로 인한 것으로 추정, hg4j 로직과 무관)
      1건 외 전부 통과.
    - **`TagCommand`**: 새 실버그 없음. 기존 `TagRealHgInteropTest`(백로그
      23)가 이미 동작 자체(생성/이동/삭제/로컬/force 게이트/병합 커밋 태깅)를
      기본 포맷 저장소 하나로 철저히 검증해뒀으므로, 이번 매트릭스는 그
      테스트가 전혀 다루지 않은 축(changelog v1/v2(+sidedata) x flat/tree
      manifest, Docker에서는 추가로 dirstate-v2/persistent-nodemap/
      fileindex-v1/general-v2)에서 태그 생성 → 로컬 태그 → 강제 이동 → 삭제
      전체 시퀀스(매번 `CommitCommand`로 실제 커밋)가 살아남는지만 확인 —
      전부 GREEN. `AmendCommand`(wave 2)와 마찬가지로 이미 검증된
      `CommitCommand`에 위임하는 명령이 모든 조합에서 안전함을 다시 한 번
      재확인한 표본.

    이로써 "67개 명령 × 매트릭스" 목표의 명령 기준 완주 수는 11에서
    14(+`AddCommand`/`BookmarkCommand`/`TagCommand`)로 증가.

    **Wave 4(2026-09-05, `BranchCommand`/`BranchesCommand`/`PhaseCommand`)**:
    이 3개 명령(스케줄링 편의상 묶음 — `BranchCommand`/`BranchesCommand`는
    백로그 23에서 이미 real-hg-CLI 행위 검증을 받았지만 매트릭스 커버리지는
    전무했고, `PhaseCommand`는 독립 로직)에 requirement 매트릭스(native 6 +
    Docker 30 = 36개 조합)를 신규 적용. 새 테스트 클래스 6개 추가
    (`RequirementMatrixBranchCoreRoundTripTest`/`RequirementMatrixBranchDockerRoundTripTest`/
    `RequirementMatrixPhaseCoreRoundTripTest`/`RequirementMatrixPhaseDockerRoundTripTest`
    + Docker 쓰기 경로 subprocess 헬퍼 2개 `RequirementMatrixBranchHelperMain`/
    `RequirementMatrixPhaseHelperMain`, 전부 `src/test/java/io/github/search5/hg4j/api/`).

    - **`BranchCommand`/`BranchesCommand`**: native 6/6 + Docker 30/30 전부
      GREEN, 새 실버그 없음 — 이름 있는 브랜치 생성 → 커밋 → close →
      `BranchesCommand` 목록 조회 시퀀스가 changelog v1/v2(+sidedata) x
      flat/tree-manifest, Docker에서는 추가로 dirstate-v2/persistent-nodemap/
      fileindex-v1/general-v2 전체 조합에서 실제 hg `branches`/`branches
      --closed` 출력(순서 + closed 마커)과 정확히 일치.
    - **`PhaseCommand`**: native 6/6 + Docker 30/30 전부 GREEN — 단, 이
      명령 자체가 사실상 미완성 수준이어서 real hg와의 byte-for-byte
      `.hg/store/phaseroots` 비교 검증을 설계하는 과정에서 **진짜 hg4j
      버그 3건**을 발견·수정한 뒤에야 도달함:
      (1) `PhaseCommand.call()`이 `phase.PhaseRoots`(다른 모든 호출자가
      쓰는 공용 저장 계층)를 전혀 쓰지 않고 자체적으로 phaseroots를 "터치한
      노드마다 한 줄씩 직접 추가/치환"하는 방식으로 재구현하고 있었다 —
      real hg는 phaseroots를 각 phase의 **최소 boundary root 집합**으로
      유지(조상 전체 이동/자손 전체 상속을 반영해 매번 전체 재계산)하는데
      반해, 이 방식은 real hg가 절대 만들지 않는 중복/중첩 라인을 계속
      쌓는다 — 실제 hg 7.2.4 CLI(`hg phase -r ... --public/--secret
      [--force]`)로 다양한 DAG 시나리오를 직접 실측하고 real hg 소스
      (`mercurial/phases.py`의 `advanceboundary`/`_retractboundary`)까지
      대조해 알고리즘을 다시 구현: public으로 향하는 이동(advance)은
      대상+모든 조상에 적용되고 무조건 허용, secret으로 향하는 이동
      (retract)은 대상+모든 자손에 적용되고 **`--force` 없이는 거부**
      (`setForce(boolean)` 신설, real hg와 동일한 "cannot move N changesets
      to a higher phase, use --force" 게이트), 매번 전체 리비전에서 최소
      root 집합을 재계산해 저장(줄 순서도 real hg의 `_write()`와 동일하게
      phase 오름차순 → 리비전 번호 오름차순). (2) `CommitCommand`가 매
      커밋마다 무조건 phaseroots에 draft root를 명시적으로 추가하고
      있었다 — 부모가 이미 draft/secret이면 상속으로 충분해 real hg는
      아무것도 쓰지 않는데, hg4j는 선형 커밋 체인마다 중복 줄을 계속
      쌓고 있었다(N개 커밋 저장소가 real hg의 "루트 하나"짜리 phaseroots
      대비 N-1줄 초과). 부모 phase가 draft 미만일 때만 기록하도록 수정.
      (3) `FetchCommand`의 pull 시 phase 동기화도 동일한 무조건 기록
      버그를 갖고 있어(당겨온 커밋마다, 원격 phase 키마다 무조건 기록)
      같은 패턴으로 이미 원하는 phase면 건너뛰도록 방어적으로 수정(이
      명령 자체의 완전한 매트릭스 검증은 이번 범위 밖). changelog-v2
      저장소에서도 phaseroots 자체는 순수 텍스트 포맷이라 형식이 달라지지
      않음을 real hg 7.2 CLI로 직접 확인(우려했던 "changelog-v2 phaseroots
      포맷 차이"는 실재하지 않음). 검증: draft→secret(`--force`)→
      draft(무조건 허용, 역방향)→draft→public 역방향(`--force`) 전이 +
      `--force` 없는 상향 이동 거부 시나리오를, hg4j 구동 저장소와 동일한
      고정-날짜 커밋의 real-hg 구동 대조 저장소 사이에서 `.hg/store/
      phaseroots` 바이트 단위 비교로 매 단계 확인. 이 알고리즘 교체로
      기존 `PhaseCommandCoverageTest`의 낡은(실제 hg와 다른) 기대치 2건도
      함께 정정(미해결 노드 라인 보존 기대 → real hg처럼 드롭되는 것으로
      정정), 신규 게이트 테스트 2건 추가. 부수적으로 이 게이트가 생기며
      기존에 force 없이 `PhaseCommand`를 secret으로 강제 설정하던 테스트
      픽스처 4곳(`Wire2CommandsTest` 2건, `PushCommandTest`,
      `HgRevsetTest`)에 `.setForce(true)`를 추가(이 명령들 자체의 프로덕션
      로직은 무관, 순수 픽스처 설정 코드). 전체 비-interop `test`(2273건)
      0 실패로 재확인, 새 회귀 없음.

    이 wave로 `BranchCommand`/`BranchesCommand`/`PhaseCommand` 3개 명령이
    추가로 매트릭스 완주 — 백로그 39 전체(67개 명령) 관점에서는 여전히
    다수가 미착수인 부분 진행 상태(이 항목 자체를 "완료"로 표시하지 않음).

    **Wave 4(2026-09-05, `CopyCommand`/`RenameCommand`/`ForgetCommand`/
    `RemoveCommand`/`AddremoveCommand`, `AddCommand`와 묶인 소규모 dirstate
    상태-전이 명령군)**: 5개 명령 모두 native 6/6 + Docker 30/30 = 36/36
    **전부 GREEN**(새 테스트 클래스 15개 — 명령당
    `RequirementMatrix{X}CoreRoundTripTest`/`...DockerRoundTripTest`/
    `...HelperMain` 3종, 기존 8개 명령과 동일 패턴, 전부
    `src/test/java/io/github/search5/hg4j/api/`). 이 과정에서 진짜 hg4j
    프로덕션 버그 **4건**을 real hg 7.2 CLI 직접 대조로 발견·TDD로 수정:

    1. **`AddCommand`의 "forget 후 재-add" 처리 누락(신규 발견, 백로그
       #39 wave 3에서 "이미 매트릭스 커버리지 완료"로 표시됐던 명령의
       엣지 케이스)**: real hg는 이미 dirstate 항목이 있는 경로(가장 흔하게는
       `ForgetCommand`/`RemoveCommand`가 남긴 `r` 상태)에 대한 명시적
       `hg add`를 `dirstate.normallookup()`으로 처리 — 신규 `a`(added)
       항목이 아니라 `n` 상태 + 모호(ambiguous) stat(mode=0/size=-1/
       mtime=all-1) 항목으로 되돌린다(CLI로 직접 검증: `hg forget f; hg add f`
       직후 `hg debugstate` raw dirstate 바이트가 `n 0 -1 -1`). hg4j
       `AddCommand`는 기존 dirstate 항목 존재 여부와 무관하게 항상 새
       `a` 항목을 만들어 덮어썼는데, 이는 `CommitCommand`가 `a` 상태를
       "부모 매니페스트에 없는 완전히 새 파일"로 처리하는 경로로 흘러가
       **파일 히스토리가 통째로 끊기는(filelog p1이 null이 되어 forget
       이전 리비전과 연결이 끊김) 실제 data-loss급 스펙 이탈**이었다(real
       hg는 `hg debugindex`에서 재-add 후 리비전의 p1이 forget 이전
       리비전을 정확히 가리킴을 확인). `AddCommand.call()`에 기존 항목
       존재 시(상태가 이미 `a`가 아니면) `n` + 모호 stat sentinel로
       복원하는 분기 추가.
    2. **dirstate-v2 모호(ambiguous) stat 라운드트립 손상(신규 발견,
       위 1번을 테스트하다 real hg 자신의 dirstate-v2 원시 바이트 검증
       중 발견 — `AddCommand`/`RemoveCommand`/`StatusCommand`/
       `ShelveCommand`/`CommitCommand` 5곳 모두에 존재하던 공용 버그)**:
       real hg는 "이 파일의 캐시된 size/mtime을 신뢰할 수 없음(대부분
       같은 초에 커밋된 racy-write)"을 dirstate-v1에서는 size=-1/
       mtime=0xFFFFFFFF sentinel로, dirstate-v2에서는 `HAS_MODE_AND_SIZE`/
       `HAS_MTIME` 플래그 비트를 아예 끔으로써 표현한다(`mercurial/pure/
       parsers.py`의 `DirstateItem` 소스를 Docker 이미지 안에서 직접 읽어
       대조 확인). hg4j의 `DirstateV2Node.getSize()`/`getMtime()`은 그
       비트가 꺼져 있으면 **구체적이지만 틀린 값인 0을 반환**했고(정답은
       "모름", 0이 아님), `RemoveCommand`/`StatusCommand`(2곳)/
       `ShelveCommand`/`CommitCommand`의 dirty-check는 그 0을 실제
       크기로 신뢰해 diskSize와 항상 불일치시켜 **건드리지도 않은 파일을
       "수정됨"으로 오판**했다(실측: dirstate-v2 저장소에서 `hg copy` 한 번
       만 해도 같은 초에 커밋된 `orig.txt`가 즉시 "M orig.txt"로 나타남).
       `Dirstate.Entry`에 공용 `isStatAmbiguous()`/`AMBIGUOUS_TIME` 상수
       신설, `DirstateV2Node.getSize()/getMtime()`이 플래그 없을 때
       size=-1/AMBIGUOUS_TIME sentinel을 반환하도록 수정,
       `DirstateV2Serializer`가 그 sentinel을 다시 쓸 때 real hg와 동일하게
       `HAS_MODE_AND_SIZE`/`HAS_MTIME`(및 그에 종속된 exec/symlink 비트)을
       생략하도록 수정, 그리고 `RemoveCommand`/`StatusCommand`(2곳)/
       `ShelveCommand`/`CommitCommand`의 dirty-check 5곳 전부가 모호
       항목을 무조건 "변경됨"으로 단정하지 않고 실제 콘텐츠 비교로
       폴백하도록 수정. 기존 `DirstateV2LayoutTest`/`DirstateV2RealFixtureTest`
       2건이 구버전(0 반환) 동작을 정답으로 assert하고 있어 함께 갱신(진짜
       Mercurial 6.0 서버에서 캡처한 raw fixture 기준 재검증 — 그 fixture 자체가
       "committed 안 된 add" 케이스라 애초에 sentinel이 정답이었음을 재확인).
    3. **dirstate-v2 `Dirstate.read(File)`가 copyMap을 통째로 버림(신규
       발견, `CopyCommand`의 "같은 원본에서 2개 목적지로 복사" 시나리오를
       Docker 매트릭스로 테스트하다 발견)**: `Dirstate.read(File)`의
       dirstate-v2 분기가 `DirstateV2Parser.parse()`가 정확히 복원한
       `parsed.getEntries()`만 `this.entries`로 복사하고 `parsed.getCopyMap()`은
       아예 옮기지 않는 한 줄 누락 버그 — dirstate-v2 저장소에서 커밋 전
       `hg copy`를 두 번(또는 다른 hg4j 쓰기 명령을 사이에 끼워) 연달아
       실행하면, 두 번째 호출이 dirstate를 다시 읽어들이는 순간 **첫 번째
       복사의 copy-source 기록이 통째로 사라지고**, 이후 커밋된 changeset은
       그 파일에 대해 아무 copy 메타데이터도 남기지 않았다(`hg log
       --template {file_copies}`에서 조용히 누락, `hg log --follow`가 원본
       히스토리를 못 찾음) — `CopyCommand`/`RenameCommand`가 여러 파일을
       순차 처리하는 흔한 사용 패턴에서 실제로 발생하는 data-loss급
       버그였다. `Dirstate.read(File)`에 `this.copyMap.clear(); this.copyMap.
       putAll(parsed.getCopyMap());` 추가로 해결 — hg4j 자신의 저수준
       파서(`DirstateV2Parser`)와 raw 바이트 자체는 처음부터 정확했고,
       그 위 한 계층(`Dirstate.read`)만 결과를 버리고 있었다는 점에서
       "발견하기 까다로운" 부류의 버그(scratch harness로 저수준 파서를
       직접 호출하면 통과, `Dirstate.read(File)` 경유로만 재현).
    4. **`CommitCommand`가 커밋된 파일의 dirstate copyMap 잔여 기록을
       정리하지 않음(신규 발견, real hg `hg debugstate` 커밋 전/후 대조로
       발견)**: real hg는 `hg copy a b; hg commit`처럼 copy가 커밋되면
       그 순간 dirstate의 pending-copy 기록(`b -> a`)을 지운다(커밋 후
       `hg debugstate`에 더 이상 `copy:` 줄이 없음) — 그 정보는 이제
       changeset/filelog 메타데이터에 영구히 남기 때문이다. hg4j
       `CommitCommand`는 파일을 `a`/`m` → `n`으로 전이시키면서도 copyMap
       항목은 그대로 남겨둬, 커밋 후에도 물리 dirstate 파일에 이미 완료된
       copy가 "아직 대기 중"인 것처럼 남는 스펙 이탈이 있었다 — 커밋 루프의
       상태 전이 지점에 `dirstate.getCopyMap().remove(path)` 추가로 해결.

    네 버그 모두 TDD로 수정 후 native 6/6 + Docker 30/30(5개 명령 전부) +
    전체 비-interop `test`(회귀 0건, 2번 재확인 — 최초 회귀에서
    `DirstateV2LayoutTest`/`DirstateV2RealFixtureTest` 2건 실패 발견 후
    위 2번 항목에 맞춰 갱신하고 재확인) 재검증 완료. "67개 명령 ×
    매트릭스" 목표의 명령 기준 완주 수는 14에서 19(+`CopyCommand`/
    `RenameCommand`/`ForgetCommand`/`RemoveCommand`/`AddremoveCommand`)로
    증가 — 단, 백로그 항목 자체는 나머지 48개 로컬 명령(및 wire 매트릭스
    잔여 5개)이 남아 있으므로 여전히 미완료.

    **Wave 4(2026-09-05, `ResolveCommand`/`BackoutCommand`/`RevertCommand`)**: 세
    명령 모두 native 6/6 + Docker 30/30 전부 GREEN. `MergeCommand`/
    `RebaseCommand`/`GraftCommand`가 이미 하드닝해 둔 3-way merge/충돌 처리
    인프라(`Merge3`, `MergeState`, `RebaseCommand.attemptThreeWayMerge`)를 그대로
    재사용. 진짜 hg4j 버그 6건을 TDD로 발견·수정:
    1. `BackoutCommand`가 `REV`가 작업 디렉터리의 부모가 아닌 "오래된 조상"을
       백아웃하는 경우(real hg의 `mergemod.back_out`에 해당하는 실제 3-way
       merge 경로)를 전혀 구현하지 않고 있었다 — 항상 대상 리비전과 그 부모의
       매니페스트 diff만 맹목적으로 적용해, 대상 리비전 이후의 독립적인 변경을
       조용히 덮어쓰거나 무시하는 데이터 손실 가능성이 있었고 충돌 감지가 전혀
       없었다. `RebaseCommand.attemptThreeWayMerge`를 재사용해 실제 3-way
       merge(ancestor=백아웃 대상, local=현재 작업 디렉터리, other=대상의 부모)를
       구현, 충돌 시 `HgMergeConflictException` + `.hg/merge/state2` 기록으로
       전환(real hg도 `backout --continue`가 없어 수동 resolve+commit 흐름이라는
       것까지 라이브 검증). 대상이 작업 디렉터리 조상이 아니면 거부하는 검증
       (`cannot backout change that is not an ancestor`)과 root 커밋 백아웃
       거부(`cannot backout a change with no parents`)도 추가 — 후자는 기존
       테스트가 반대로("root 커밋도 백아웃된다") 잘못 단정하고 있던 실제
       회귀였다(수정, 라이브 검증으로 확인).
    2. `RevertCommand`가 `hg add`만 되고 커밋된 적 없는 파일을 되돌릴 때 디스크
       콘텐츠를 통째로 **삭제**하고 있었다 — real hg는 그 내용을 그대로 두고
       dirstate에서만 untrack한다(라이브 검증, 데이터 손실 버그). 반대로 대상
       리비전에 존재하지 않는(과거에 커밋된 적 있는) 파일을 되돌릴 때는 real
       hg가 삭제 + `R`(removed)로 마킹하는데 hg4j는 완전히 untrack만 하고
       있어서 대칭이 깨져 있었다 — 두 경로 모두 수정.
    3. `RevertCommand`가 real hg의 `<file>.orig` 백업(수정된 파일을 되돌리기
       직전 원본을 보존)을 전혀 구현하지 않고 있었다 — "현재 파일이 실제로
       modified 상태였는지"를 `StatusCommand`로 판단해 그 경우에만 백업하도록
       추가(clean한 파일을 `-r`로 다른 리비전에 되돌릴 때는 백업이 생기지
       않는 것까지 라이브로 검증).
    4. `StatusCommand`가 real hg의 dirstate "possibly dirty" 센티널(mtime이
       0xFFFFFFFF일 뿐 아니라 size도 -1로 동시에 기록되는 경우 —
       `dirstatemap.py`의 `set_possibly_dirty()`)에서 size 필드를 전혀
       처리하지 못해, 같은 초 안에 커밋된 파일을 곧바로 hg4j `StatusCommand`로
       재확인하면 무조건 "modified"로 오판하는 버그를 발견 — `BackoutCommand`의
       새 "작업 디렉터리 clean 확인" 전제조건이 이 버그에 걸려 native 6개
       조합 전부 실패하면서 드러남. size<0을 ambiguousTime과 동일하게 content
       비교로 폴백하도록 수정.
    5. `RevertCommand`/`BackoutCommand`가 되돌린/백아웃된 파일에 항상 실제
       mtime을 기록하고 있어, 같은 바이트 길이의 다른 내용으로 되돌리는 경우
       (예: "v1\n" -> "v0\n") 같은 초 안에 실행되면 real hg 자신의 `hg status`
       조차 이를 clean으로 오판하는 레이스가 있었다 — `ShelveCommand`가 이미
       쓰고 있던 동일한 방어(항상 ambiguous-mtime 센티널 기록)를 두 명령의
       'n' 상태 쓰기에도 적용.
    6. `CommitCommand`가 `.hg/merge` 정리를 `parent2Rev != -1`(진짜 2-parent
       병합) 조건에만 걸어 둬서, `BackoutCommand`의 새 단일-parent 충돌-해결
       커밋처럼 `.hg/merge/state2`는 쓰지만 병합 커밋 자체는 single-parent인
       경우 `.hg/merge`가 영원히 안 지워지는 버그도 발견·수정(real hg는
       `ms.active()`만으로 무조건 정리 — 라이브 검증).

    부가로, Docker 매트릭스 검증 중 dirstate-v2 포맷 자체의 별도 실버그 2건도
    발견·수정: `DirstateV2Parser`가 real hg의 "HAS_MODE_AND_SIZE`/`HAS_MTIME`
    플래그 없음"(v1의 size=-1/time=0xFFFFFFFF 센티널에 대응하는 v2식
    "possibly dirty" 표현)을 그대로 리터럴 0/0으로 변환해 버려 위 버그 4와
    동일한 오판을 dirstate-v2 조합에서 별도로 재현시켰고(수정: flag 부재를
    동일한 v1식 센티널로 번역), `DirstateV2Serializer`도 반대 방향으로
    센티널 값(size=-1/time=0xFFFFFFFF)을 실제 유효한 캐시값인 것처럼
    `HAS_MODE_AND_SIZE`/`HAS_MTIME` 플래그를 무조건 세팅한 채 기록하고
    있어 대칭으로 수정.

    검증: `RequirementMatrixResolveCoreRoundTripTest`/`...DockerRoundTripTest`,
    `RequirementMatrixBackoutCoreRoundTripTest`/`...DockerRoundTripTest`,
    `RequirementMatrixRevertCoreRoundTripTest`/`...DockerRoundTripTest`(+각
    Docker 서브프로세스 헬퍼 3개) 신규 추가, 전부 GREEN. 위 버그 (2)/root
    커밋 백아웃 관련해 잘못된 기대값을 갖고 있던 기존 `RevertCommandTest`/
    `WorkingCopySafetyTest`/`TrackCMissingCommandsInteropTest`/
    `BackoutCommandCoverageTest`/`DirstateV2RealFixtureTest`의 해당 테스트를
    real-hg-검증된 올바른 기대값으로 수정. 전체 비-interop `test`(2273건)
    GREEN, 관련 `interopTest` 서브셋(Resolve/Backout/Revert Core+Docker,
    `TrackCMissingCommandsInteropTest`) 전부 GREEN.

    이로써 "67개 명령 × 매트릭스" 목표의 명령 기준 완주 수는 14에서
    17(+`ResolveCommand`/`BackoutCommand`/`RevertCommand`)로 증가.

    (두 wave 4 브랜치 병합 후 최종 집계, 2026-09-05: 위 두 하위-wave가
    독립적으로 완주시킨 명령을 합치면 `BranchCommand`/`BranchesCommand`/
    `PhaseCommand`(3) + `CopyCommand`/`RenameCommand`/`ForgetCommand`/
    `RemoveCommand`/`AddremoveCommand`(5) + `ResolveCommand`/
    `BackoutCommand`/`RevertCommand`(3) = 이번 wave에서만 11개 명령 신규
    완주, 이전 wave까지의 17개(`PushCommand`/`RebaseCommand`/
    `ShelveCommand`/`StripCommand`/`MergeCommand`/`GraftCommand`/
    `BundleCommand` 등 포함)에 더해 로컬 명령 기준 총 28/67로 증가. 병합
    과정에서 두 하위-wave가 각각 독립적으로 발견한 "dirstate-v2 ambiguous
    stat 라운드트립" 버그가 사실상 동일한 근본 원인이었음을 확인 —
    `Dirstate.Entry#isStatAmbiguous()` 공용 헬퍼(먼저 병합된 Copy/Rename
    wave가 신설)로 통일하고, `StatusCommand`/`DirstateV2Serializer`의
    중복 인라인 재구현은 제거.)

    **Wave 4(2026-09-05)**: `UnbundleCommand`/`ImportCommand`/`ExportCommand`
    3개 명령에 36개 조합(native 6 + Docker 30) 적용 — **전부 GREEN**
    (`RequirementMatrixUnbundleCoreRoundTripTest` native 6/6 + Docker 30/30,
    `RequirementMatrixExportImportCoreRoundTripTest`가 `ExportCommand`/
    `ImportCommand`를 함께 native 6/6 + Docker 30/30, 총 72케이스).
    - **`UnbundleCommand`**: 새 실버그 없음 — real hg가 만든 번들 파일(같은
      백로그 항목에서 `BundleCommand`가 새로 갖추게 된 `none-v3`/treemanifest
      cg3 타입 포함)을 `FetchCommand#applyBundle`에 위임해 적용하는 기존
      경로가 이미 모든 조합에서 정확했음이 재확인됨(해당 위임 경로의
      `bundle.manifestGroups` 처리는 `BundleCommand`의 송신측 fix 때 "수신측은
      이미 완전 지원"이라고 문서화돼 있던 것과 일치). 테스트는 real hg가
      `hg bundle`로 만든 파일을 hg4j가 적용한 뒤, 그 결과 저장소를 다시 real
      hg가 읽어 검증하는 방향(`BundleCommand` 매트릭스의 송수신 반대 방향)으로
      작성 — `cl2+sidedata` 조합은 `BundleCommand`와 동일한 근거로 confirmed
      real-hg-only 파일 기반 bundle 한계를 tolerate 처리.
    - **`ExportCommand`/`ImportCommand`**: **진짜 hg4j 프로덕션 버그 2건을
      TDD로 발견·수정**. (1) `ImportCommand`가 patch/manifest/changelog를
      전부 손수 조립하고 있어서 treemanifest 저장소에서는 구조적으로 아예
      동작할 수 없었다(평평한 `path\0hex\n` 매니페스트 텍스트를 무조건
      `00manifest.i`에 직접 쓰는 방식 — treemanifest는 `store/meta/` 하위
      디렉터리별 revlog 트리라 이 방식 자체가 성립하지 않음). 이미 모든
      조합에서 검증된 `CommitCommand`에 실제 커밋 작성을 위임하도록 재작성해
      해결 — `ImportCommand`는 이제 패치를 파싱해 작업 디렉터리/dirstate에
      반영(`AddCommand`/`RemoveCommand` 재사용)한 뒤 `CommitCommand`를
      호출하는 얇은 계층이 됨(treemanifest/changelog-v2/sidedata 전부
      `CommitCommand`가 이미 처리하던 로직을 그대로 상속). 부수적으로
      real hg의 `--- /dev/null`(신규 파일)/`+++ /dev/null`(삭제 파일) 패치
      규약도 처음으로 지원(이전엔 삭제 패치를 통째로 무시 — 파일이 디스크/
      매니페스트에서 전혀 지워지지 않았음). (2) `DiffCommand#generateUnifiedDiff`
      가 `text.split("\n", -1)`로 줄을 세면서, 줄바꿈으로 끝나는(거의 모든
      텍스트 파일의) 콘텐츠마다 가짜 빈 줄을 하나 더 있는 것처럼 취급하는
      버그를 발견 — `RequirementMatrixExportImportCoreRoundTripTest`가 hg4j가
      내보낸 패치를 real hg `hg import`로 되읽어 커밋 노드 해시가 완전히
      동일한지(단순 내용 비교가 아니라) 대조하는 과정에서 발각됨: 이 가짜 줄이
      실제 hg import/hg4j import 양쪽 모두에 의해 파일 끝에 진짜 빈 줄로
      기록돼버려 파일/매니페스트/체인지셋 노드 해시가 전부 달라졌다. 줄 분리
      로직을 실제 diff 관례(후행 개행은 추가 줄이 아니라 마지막 줄의
      종결자)에 맞게 수정하고, 파일이 정말로 후행 개행이 없는 경우를 위한
      `\ No newline at end of file` 마커의 생성(`ExportCommand`)/파싱
      (`ImportCommand`) 양쪽을 새로 구현. 이 수정으로 기존
      `TreeAndDiffCommandTest`의 대용량 diff 테스트 하나가 가정하고 있던
      (버그가 있던 시절의) 줄 수 상수도 올바른 값으로 함께 수정.
    - **검증**: native 6/6 + Docker 30/30(두 클래스 합산 72케이스) 전부
      GREEN, 전체 비-interop `test`(그 사이 늘어난 신규/수정 케이스 포함)
      전부 GREEN. `interopTest`는 전체가 아니라 이 4개 신규 클래스(Unbundle
      native/Docker, ExportImport native/Docker)로 스코프를 좁혀 재검증—
      이전 wave들이 이미 기록한 "전체 interopTest 1회 완주는 너무 느림"
      교훈을 그대로 따름.

    이로써 "67개 명령 × 매트릭스" 목표의 명령 기준 완주 수는 (다른 wave 4
    에이전트들과 병렬로 진행 중이라 이 문단 기준으로는) 14에서
    17(+`UnbundleCommand`/`ImportCommand`/`ExportCommand`)로 증가 — 최종
    합산은 조정자가 병렬 에이전트들의 기여를 모아 정리.

    (조정자 최종 취합, 2026-09-05: 이번 wave 4에서 병합된 3개 하위-브랜치
    (`BranchCommand`/`BranchesCommand`/`PhaseCommand`(3) +
    `CopyCommand`/`RenameCommand`/`ForgetCommand`/`RemoveCommand`/
    `AddremoveCommand`(5) + `ResolveCommand`/`BackoutCommand`/
    `RevertCommand`(3) + `UnbundleCommand`/`ExportCommand`/
    `ImportCommand`(3))를 합치면 wave 4에서만 14개 명령 신규 완주,
    이전 wave까지의 17개에 더해 로컬 명령 기준 총 **31/67**로 증가.
    나머지 36개 로컬 명령 및 wire 매트릭스 잔여 5개는 여전히 미착수.)

    **Wave 5(2026-09-05, "가벼운 저장소 메타데이터 조회" 8개 명령:
    `HeadsCommand`/`IdentifyCommand`/`ParentsCommand`/`PathsCommand`/
    `RootCommand`/`TipCommand`/`TagsCommand`/`SummaryCommand`)**: 기능적으로
    가까운 명령끼리 3개 트리오로 묶어(다른 wave의 Branch/Branches/Phase
    선례를 따름) 각 트리오당 native 6/6 + Docker 30/30 = 36/36 전부 GREEN —
    `HeadsCommand`/`TipCommand`/`ParentsCommand` 트리오, `IdentifyCommand`/
    `SummaryCommand` 트리오, `TagsCommand`/`PathsCommand`/`RootCommand`
    트리오 3개 전부. `PathsCommand`/`RootCommand`는 4축(dirstate/changelog/
    manifest/storage-확장) 어디에도 실제로 의존하지 않는 명령이지만, 과제
    범위대로 예외 없이 36개 조합 전부에 통과시켰다(가정이 아니라 실측
    커버리지를 남긴다는 취지). 세 트리오 모두 hg4j 자신은 순수 읽기만
    수행하므로(저장소 자체는 항상 real hg CLI/`docker exec`로만 구축)
    `RequirementMatrixCommitHelperMain` 류의 별도 서브프로세스 격리가
    필요 없었다(그 우회는 hg4j의 zstd 압축 **쓰기** 경로가 `docker exec`/
    `docker run` 프로세스 스폰과 같은 JVM에서 겹칠 때만 재현되는 문제였음).

    real hg 7.2.2 직접 실측 대조로 진짜 hg4j 버그 3건을 발견·수정:
    (1) `HeadsCommand`의 `--topo`가 리프를 changelog 오름차순으로 반환하고
    있었는데, real hg의 `hg heads --topo`는 다른 모든 `hg heads` 형태와
    마찬가지로 리비전 내림차순으로 나열함 — 리프가 2개 이상인 시나리오에서만
    드러나는 순서 버그(기존 `HeadsRealHgInteropTest`는 우연히 항상
    `hexSorted()`로 비교해 이 버그를 놓치고 있었음), 내림차순 순회로 수정.
    (2)~(4) `IdentifyCommand`(기존엔 real hg와 전혀 다른 자체 포맷:
    `hexShort [tag] [branch]`처럼 항상 브랜치를 리터럴로 붙이고 dirty
    마커/북마크/머지 2-parent를 전혀 다루지 않았음)를 real hg의
    `hg identify` 정확한 출력 규칙으로 전면 재작성 — 기본 브랜치는 아예
    생략(비-기본일 때만 괄호로 표시), dirty 마커(`+`)와 머지 중 2-parent
    처리, 태그/북마크는 `TagsCommand`/`BookmarkCommand`를 재사용해 알파벳
    순으로 `"/"` 조인(가짜 `tip` 의사-태그도 이름 순서 그대로 정렬),
    `-r` 조회(`setRevision` 신설) 지원까지 추가. 그 과정에서 재작성 도중
    두 가지를 실측으로 추가 확인: (a) 머지 중에는 태그/북마크가 p1뿐
    아니라 p1+p2 **양쪽 모두**에서 집계됨(p2에만 있는 로컬 태그도 표시됨),
    (b) `-r` 조회의 브랜치는 작업 사본의 현재 `.hg/branch`가 아니라 **조회
    대상 리비전 자신의** 브랜치(체인지로그 extra 필드)여야 함 — 둘 다 처음
    구현에서 놓쳐 매트릭스 테스트 작성 중 즉시 잡힘. 기존
    `IdentifyCommandTest`의 여러 단위 테스트가 옛(틀린) 포맷을 그대로
    검증하고 있어 real hg 실측값으로 전부 갱신, `PorcelainExtraCommandsTest`
    1건도 함께 수정. 부수적으로 `SummaryCommand`(별도 버그는 없었음, 이미
    정확했음 확인)를 테스트하다가 changelog-v2(docket 기반) 저장소를 오래
    붙들고 있는 `HgRepository` 핸들이 external(real hg CLI) 커밋 이후에도
    같은 revision count를 캐시해 stale 상태를 보이는 것을 실측(docket 파일
    자체 크기는 append로 변하지 않는다는 기존 `refreshIfChangedOnDisk`
    javadoc의 근거와 일치) — 이는 hg4j 버그가 아니라 이 테스트 설계 자체의
    "실행 중 real hg CLI로 저장소를 계속 키우면서 같은 `HgRepository`
    핸들을 재사용" 패턴이 원인이라 테스트 쪽에서 `refreshIfChangedOnDisk()`
    호출/핸들 재생성으로 해결(문서화된 기존 공개 API를 그대로 사용).
    전체 비-interop `test` 2282건 0 실패/0 에러(2 스킵) GREEN 재확인.
    이로써 로컬 명령 완주 수는 31에서 8개 추가로 **39/67**로 증가(다른
    wave 5 병렬 에이전트들과 별도 취합 필요할 수 있음). 나머지 28개 로컬
    명령 및 wire 매트릭스 잔여 5개는 여전히 미착수.

    **Wave 5(2026-09-05, `BisectCommand`/`DescribeCommand`/`DiffCommand`/
    `LogCommand`/`StatusCommand`/`RevsetCommand`/`SidedataChangedFilesCommand`)**:
    7개 명령 모두 native 6/6 + Docker 30/30 = 36/36 **전부 GREEN**(새 테스트
    클래스 15개 -- 6개 명령은 명령당 `RequirementMatrix{X}CoreRoundTripTest`/
    `...DockerRoundTripTest` 2종[전부 순수 read라 `HelperMain` 서브프로세스
    불필요, 판단 근거는 `RequirementMatrixDescribeDockerRoundTripTest`의
    javadoc], `BisectCommand`만 `next()`가 실제로 워킹 카피/dirstate를
    쓰므로 `...HelperMain`까지 3종, 전부
    `src/test/java/io/github/search5/hg4j/api/`). `LogCommand`/`StatusCommand`는
    이번 wave 이전엔 `RequirementMatrixCoreRoundTripTest`(commit/log/status/cat
    4개 명령 공용)의 부수적 검증(tip hex 개수 확인, 커밋 직후 clean 상태 확인)만
    있었을 뿐 각 명령 자체의 전용 매트릭스 트리오는 없었다는 점을 이번에
    확인·보강(백로그 39 작업 지시가 명시한 지점). 이 과정에서 진짜 hg4j
    프로덕션 버그 **4건**을 TDD로 발견·수정:

    1. **`BisectCommand`의 treemanifest 체크아웃 미지원(신규 발견)**:
       `next()`가 매 bisect 후보 체크아웃 시 루트 매니페스트 revlog를
       직접 hand-roll 파싱(`getManifestForCommit`)해 모든 라인을 실제 파일로
       취급하고 있었다 -- `experimental.treemanifest=1` 저장소에서는 루트
       매니페스트의 서브디렉터리 항목이 `t`-플래그가 붙은 `meta/<dir>/
       00manifest.i` 서브매니페스트 포인터일 뿐 파일 콘텐츠가 아니므로,
       하위 디렉터리에 있는 모든 파일이 체크아웃되지 않거나 잘못된 콘텐츠로
       쓰였다. `ManifestCommand`/`StatusCommand`/`DiffCommand`가 이미 쓰는
       treemanifest-aware `ManifestWalk`(→`ManifestTreeIterator`)로 교체해
       해결 -- `RequirementMatrixBisectCoreRoundTripTest`/`...DockerRoundTripTest`
       양쪽 모두 nested-directory 파일의 체크아웃 콘텐츠를 매 bisect 스텝마다
       `hg cat`과 직접 대조해 이 수정을 검증.
    2. **`Revlog.decompressSidedataChunk`가 `COMP_MODE_DEFAULT`를 zstd로
       무조건 가정(신규 발견)**: `format.exp-use-changelog-v2` +
       `format.exp-use-copies-side-data-changeset`는 켰지만
       `revlog-compression-zstd` requirement는 없는(real hg의
       `format.usezstd=false`/`format.revlog-compression=zlib`) 저장소에서
       real hg 자신이 실제로 zlib로 압축한 사이드데이터 청크를 읽으면
       "Invalid zstd sidedata frame: could not determine content size"로
       실패 -- 완전히 유효한 real-hg 저장소의 데이터를 못 읽는 버그.
       `RequirementMatrixSidedataChangedFilesCoreRoundTripTest`의
       `cl2+sidedata` 콤보(이 스위트는 바이트 재현성을 위해 항상 zlib을
       강제하므로 100% 재현) 개발 중 발견. 이미 `COMP_MODE_INLINE` 분기가
       쓰던 것과 동일한 패턴(첫 바이트가 zstd 매직 `0x28`인지 스니핑,
       아니면 `DeltaCodec.decompress`에 위임)으로 `COMP_MODE_DEFAULT` 분기도
       수정.
    3. **`DeltaCodec.decompressZstd`가 델타 인코딩된 리비전의 압축 해제
       목적지 버퍼 크기로 revlog 인덱스의 `uncompLen`(최종 재구성된 풀텍스트
       크기)을 그대로 써서 "Destination buffer is too small"로 크래시(신규
       발견, 이번 wave에서 가장 파급력 큰 버그)**: real hg의 revlog v1
       인덱스 `uncompressed_len` 필드는 항상 "이 리비전을 완전히 재구성했을
       때의 최종 크기"를 기록하는 것이지 "이 리비전 자체가 저장하고 있는
       (그리고 그래서 압축 해제되는) 바이트 수"가 아니다 -- 베이스 리비전이
       아닌 델타 리비전(generaldelta가 기본인 v1 매니페스트/파일로그는
       거의 항상 이 경우)의 경우 실제로 zstd 압축된 것은 bdiff 패치 자체
       (헌크당 12바이트 헤더 + 교체 텍스트)이고 그 바이트 길이는 최종
       풀텍스트 크기와 무관하다. `RequirementMatrixDiffDockerRoundTripTest`가
       real hg 7.2.4(Docker, 강제 zlib 없이 진짜 zstd 사용)가 쓴 매니페스트의
       두 번째 리비전(델타 인코딩됨)을 `ManifestTreeIterator`로 읽다가
       30/30 전부 크래시로 발각 -- native 쪽(항상 zlib 강제)과 hg4j
       자체 write 경로(델타 없이 항상 fulltext로 씀)는 이 조합을 지금껏
       한 번도 건드린 적이 없어 숨어 있던, **일반적인 real-world zstd
       압축 hg 저장소 다수에 실질적으로 영향을 미쳤을 버그**. 이미
       사이드데이터 경로(`Revlog#getSidedata`)가 쓰던 것과 동일한 해법 --
       zstd 프레임 자체에 내장된 콘텐츠 크기(`Zstd.getFrameContentSize`)를
       읽어 목적지 버퍼를 정확히 사이징 -- 를 메인 콘텐츠 경로에도 적용해
       해결(대상 메서드가 `DeltaCodec` 공용 유틸이라 델타/풀텍스트, 매니페스트/
       파일로그/체인지로그 구분 없이 전부 수정됨).
    4. **장수(long-lived) `HgRepository` 핸들이 changelog-v2 docket을 외부
       프로세스가 갱신한 뒤에도 캐시된 stale 데이터를 조용히 반환(신규 발견)**:
       `HgRepository.refreshIfChangedOnDisk()`(원래 `HgHttpWireServer`/
       `HgSshWireServer` 전용으로 이미 존재하던, "바깥에서 hg CLI가 커밋했을 때"
       대비 메서드)가 이번에 다루는 7개 read 명령 어디에도 연결돼 있지 않아,
       같은 `HgRepository` 객체를 재사용한 채 외부(real hg CLI)가 새 커밋을
       추가하면 changelog-v2(docket 기반, 파일 크기 불변 -- `index_end`/
       `data_end`만 내부적으로 갱신) 저장소에서는 캐시된 옛 리비전 개수로
       계속 답해 진짜처럼 보이지만 틀린 답(예: `DescribeCommand`가 있지도
       않은 태그 매치를 반환)을 조용히 냈다(plain v1 revlog는 파일 크기
       자체가 커져서 우연히 감지됨 -- `RequirementMatrixDescribeCoreRoundTripTest`의
       `cl1` 콤보만 통과하고 `cl2`/`cl2+sidedata` 콤보만 실패하는 패턴으로
       발각). 7개 명령의 `call()`/`next()` 진입부에
       `repository.refreshIfChangedOnDisk()` 호출을 추가해 해결(정상적인
       매번-새로-여는 사용 패턴에서는 `stat()` 2회의 공짜 방어일 뿐).
       이 문제 자체는 이번에 다룬 7개 명령보다 훨씬 일반적(다른 wave가
       이미 완료한 read-heavy 명령들도 잠재적으로 영향권)이지만, 이번
       세션은 백로그 39 범위(이 7개 명령)에 한해 수정 -- 나머지 명령으로의
       확장은 별도 후속 필요.

    검증: native 6/6 + Docker 30/30(7개 명령 전부) 완료, 전체 비-interop
    `test`(2278건) 재확인 -- `StripCommandCoverageTest#stripMovesBookmarkPointingAtStrippedRevisionToNewTip`
    1건 실패 발견(원인 분석은 아래 조정자 정정 참고).
    "67개 명령 × 매트릭스" 목표의 명령 기준 완주 수는 31에서 38로 증가.
    나머지 29개 로컬 명령 및 wire 매트릭스 잔여 5개는 여전히 미착수.

    **조정자 정정(2026-09-05)**: 위 `StripCommandCoverageTest` 실패를
    "`git stash` 격리 재실행에서도 동일 실패, 이 세션과 무관한 사전
    존재 버그/타이밍 플레이크"로 판단한 것은 **틀렸음** -- 직접
    재검증한 결과 (1) 병합 직전 커밋(메타데이터-쿼리 wave까지 병합된
    상태)에서 이 테스트만 격리 재현하면 **통과**하고, (2) 이 wave
    브랜치 단독(공통 조상 커밋 기준, 다른 wave와 무관)에서도 이 테스트
    메서드 하나만 돌려도 **결정론적으로 매번 실패**(11초 만에 재현,
    타이밍 요소 없음) — 즉 이 wave 자신이 만든 진짜 회귀. 원래
    "git stash 재현" 검증이 잘못된 결론에 도달한 이유는 재현 시
    `-x jacocoTestReport` 플래그를 빠뜨려 전체 interop 스위트까지
    딸려 실행되면서 30분+ 걸렸고, 그 상태를 "여전히 실패"로 잘못
    해석했을 가능성이 있음(조정자도 동일한 실수로 한 번 헷갈렸다가
    재시도로 바로잡음).

    **근본 원인**: 이 wave가 위 버그 4번 항목(`refreshIfChangedOnDisk()`
    연결)에서 7개 명령에 새로 연결한 이 메서드가, changelog.i 파일
    크기/mtime 변화를 감지하면 무조건 `clearRevlogCache()`로 캐시된
    모든 `Revlog`를 새 인스턴스로 통째로 교체한다 -- 새 인스턴스는
    `addedRecords`(이 프로세스 안에서 로컬로 추가한 레코드 이력)가
    빈 채로 시작하는데, 이는 `RevlogIndex.checkAndUpdate()` 자신의
    "`addedRecords`가 비어 있을 때만 파일에서 리로드" 불변조건이
    보호하려던 바로 그 상태(StripCommand/RebaseCommand/HisteditCommand가
    로컬 truncate 직후 같은 핸들을 재사용하는 패턴)를 우회시켜버린다.
    `StripCommandCoverageTest`의 시나리오: rev0 커밋 → `LogCommand`
    호출(이제 `refreshIfChangedOnDisk()` 포함 → changelog 캐시 교체) →
    rev1 커밋 → `LogCommand` 재호출(다시 캐시 교체, 이번 인스턴스는
    rev1을 로컬로 추가한 이력이 없음) → 북마크 설정 → `StripCommand`가
    rev1을 strip하며 이 "이력 없는" changelog 핸들로 북마크 재배치
    루프에서 `findRevision(rev1의 노드)`를 호출 → 이미 truncate된
    파일에서 다시 읽어 -1 반환 → "resolve 불가능한 노드"로 오인해
    북마크를 재배치 대신 삭제.

    **수정**(조정자가 이 wave 브랜치 자체에 직접 반영, 커밋 `b331e15`):
    `RevlogIndex`/`Revlog`에 `hasLocallyAddedRecords()` public getter
    신설, `HgRepository.refreshIfChangedOnDisk()`가 캐시된 changelog
    `Revlog`가 이미 로컬 쓰기 이력을 갖고 있으면 `clearRevlogCache()`를
    건너뛰도록 수정 -- 진짜 외부 변경(이 프로세스가 한 번도 쓴 적 없는
    경우)은 영향 없이 그대로 감지되고, 로컬에서 커밋 직후 같은 핸들을
    재사용하는 패턴만 보호됨. 수정 후 `StripCommandCoverageTest` 및
    전체 비-interop 스위트 재검증 GREEN.

    **후속 항목**: 이 브랜치의 버그 4번 문단이 스스로 인정했듯
    `refreshIfChangedOnDisk()` 미연결(이번엔 반대로 "과도한 연결"까지)
    문제는 이번 7개 명령보다 범위가 넓다 -- 다른 wave가 이미 완료한
    read-heavy 명령들도 이 메서드를 연결하게 될 경우 동일한 클래스의
    회귀에 잠재적으로 노출된다는 점을 백로그 39 전체 완료 후 전수
    재점검 항목으로 유지한다. 또한 이 사건은 **"병합 중 발견한 무관해
    보이는 테스트 실패는 병합한 에이전트의 판단을 그대로 신뢰하지 말고
    반드시 직접 병합 전/후·브랜치 단독 여부로 격리 재현해 확인할 것"**
    이라는 39번 표준 규칙 6을 재확인시켜준 사례로 기록한다.

    **Wire 매트릭스 wave 5(2026-09-05)**: [[exhaustive-interop-matrix-plan]]
    §4-2가 "미착수"로 남겨뒀던 wire 매트릭스 잔여 5개 명령(`FetchCommand`/
    `IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
    `NarrowCloneCommand`)에 21개 조합(HTTP 18 + SSH 3)을 전부 적용,
    **5개 명령 전부 GREEN**: `FetchCommand` 21/21, `NarrowCloneCommand`
    21/21, `ClonebundlesCommand` 21/21, `IncomingCommand`+`OutgoingCommand`
    49/49(hg4j-클라이언트 vs real-hg-서버 방향 21+21개 + real-hg-클라이언트
    vs hg4j-served-서버 리버스 방향 6+1개 — 사용자가 이 두 명령만 명시적으로
    양방향 검증을 요구했음). 새 테스트 클래스 4개(`HgWireProtocolMatrixFetchTest`/
    `HgWireProtocolMatrixNarrowCloneTest`/`HgWireProtocolMatrixClonebundlesTest`/
    `HgWireProtocolMatrixIncomingOutgoingTest`, 전부
    `src/test/java/io/github/search5/hg4j/transport/`) + 공유 헬퍼 3개
    (`WireMatrixCombos`/`HttpMatrixServer`/`SshMatrixServer`, 기존
    `HgWireProtocolMatrixTest`의 콤보/서버 보일러플레이트를 추출).

    **발견·수정한 진짜 hg4j 프로덕션 버그 3건**:
    1. **`IncomingCommand`가 content 있는 real hg 서버 어디에 대해서도
       100% 깨져 있었다**(가장 심각, 웹훅 알림 발송): 항상
       `client.getChangegroup(Collections.emptyList())`로 원격 전체
       히스토리를 구식 `changegroup` wire 명령에 빈 `roots`로 요청하고
       있었는데, real hg 자신의 순정 `hg serve`에 맨 curl로
       `?cmd=changegroup&roots=`를 쳐서 **hg4j 없이 독립 재현 확인**
       (2026-09-05): real hg의 `discovery.outgoing()`(`mercurial/
       discovery.py`)이 `missingroots == []`이면서 서버 쪽 핸들러가 항상
       명시적으로 `ancestorsof=repo.heads()`를 넘기는 경우
       `repo.revs('::%ln', missingroots, ancestorsof)`를 호출하는데,
       revset `'::%ln'`에는 자리표시자가 하나뿐인데 치환값을 2개 넘겨
       `ParseError: too many revspec arguments specified`로 서버가
       uncaught exception(HTTP 500)을 던진다 — real hg 자신의 레거시
       코드 결함이지 hg4j 버그는 아니지만, real hg 자신의 최신 클라이언트는
       이 경로를 절대 밟지 않는다(항상 `getbundle` 우선). 수정:
       `FetchCommand`에 이미 있던 "로컬 leaf 노드 계산" + "getbundle 우선
       협상 + HG20/HG10 매직 해제" 로직을 `FetchCommand.
       computeLocalLeafHexes()`/`FetchCommand.downloadChangegroupBundle()`
       공용 정적 메서드로 추출해 `IncomingCommand.call()`이 재사용하도록
       재작성(`FetchCommand.call()` 자신도 이 공용 메서드를 쓰도록
       리팩터링, 동작 변화 없음).
    2. `HgRemoteClient.getChangegroup()`(HTTP)가 `roots`가 빈 리스트일 때
       요청 파라미터 맵에서 그 키 자체를 아예 생략하던 버그 — real hg의
       `changegroup` wire 명령은 `roots`가 필수 선언 인자라 키가 통째로
       빠지면 서버의 `getargs()`가 dict lookup에서 바로 `KeyError`(HTTP
       500)를 던진다. `HgSshClient.getChangegroup()`은 이미 빈 문자열로
       라도 항상 보내고 있어(기존 주석에 이미 명시) 정확한 참조 구현이
       있었다 — HTTP 클라이언트를 그 패턴에 맞춰 수정.
    3. `FetchCommand`의 clonebundles bypass 게이트가 `client instanceof
       HgRemoteClient`(HTTP 전용)였던 것 — real hg 소스(`mercurial/
       exchange.py`의 `remote.capable(b'clonebundles')`/`e.callcommand(
       b'clonebundles', {})`)는 전송 방식과 무관하게 동작한다. `HgRemoteConnection`
       인터페이스에 `supportsClonebundles()`/`fetchClonebundlesManifest()`
       기본 메서드를 추가하고 `HgSshClient`에 실제 구현(`branchmap`과
       동일한 형태의 무인자 v1 wire 명령)을 추가, `FetchCommand
       .tryApplyClonebundle()`을 `HgRemoteConnection` 제네릭으로 변경 —
       `HgWireProtocolMatrixClonebundlesTest`의 SSH 21개 조합이 실제로
       이 새 경로(다운로드 서버 hit 카운터로 bypass 발동 자체를 확인)를
       밟아 검증됨.

    **회귀 확인**: 비-interop `test` 2278건 전부 GREEN(2 스킵, 기존 무관
    skip), 이번 wave 신규 4개 클래스(21+21+21+49=112 테스트) 전부 GREEN,
    기존 `HgWireProtocolMatrixTest`(Clone/Pull/Push) 21개도 재확인 GREEN
    (회귀 없음). 이로써 [[exhaustive-interop-matrix-plan]]의 wire 매트릭스
    (§4-2) 8개 명령 전부 완료.

    **Wave 5(2026-09-05, `GcCommand`/`RecoverCommand`/`RollbackCommand`/
    `VerifyCommand`/`CensorCommand`/`InitCommand`)**: 저장소 유지보수/관리
    계열 6개 명령에 requirement 매트릭스(native 6 + Docker 30 = 36개 조합)
    확장 적용, 전부 GREEN(명령별 시나리오 수가 달라 native/Docker 케이스
    총량은 명령마다 다름 — `InitCommand` native 6/6+Docker 30/30,
    `GcCommand` native 6/6+Docker 30/30, `RecoverCommand` native
    12/12+Docker 30/30, `RollbackCommand` native 12/12+Docker 30/30,
    `VerifyCommand` native 15/15+Docker 69/69, `CensorCommand` native
    12/12+Docker 60/60). `InitCommand`는 다른 5개와 설계가 다르다 — 나머지
    31개 기완주 명령이 전부 그래왔듯 real hg가 저장소를 만들고 hg4j가 그
    위에 쓰는 방향이 아니라, **hg4j 자신의 `InitCommand`가 36개 조합 전부의
    저장소를 처음부터 만들고 real hg가 그 결과를 완전히 받아들이는지**(검증
    +추가 커밋까지) 검증 — 이 캠페인의 다른 모든 테스트가 "저장소가
    유효하다"는 전제에 의존하므로 사용자가 특별히 고위험으로 지정한 항목.

    **발견·수정된 진짜 hg4j 버그 9건** (전부 표준 원칙에 따라 발견 즉시 완전
    수정, 범위 축소 없음):
    1. **(`InitCommand`, 최초엔 지원 자체가 없던 gap)** `InitCommand`가
       원래 `dirstate-v2`/zstd 압축 2개 축만 지원해 36개 조합 중 30개 이상을
       hg4j 스스로는 아예 만들 수 없었다 — changelog-v2/sidedata/
       treemanifest/persistent-nodemap/fileindex-v1/general-v2 축을 real
       hg의 상호 함의 규칙(fileindex-v1→persistent-nodemap 함의,
       general-v2→둘 다 함의, sidedata→changelog-v2 함의, treemanifest는
       fileindex-v1/general-v2와 상호배타로 real hg와 동일하게 abort)까지
       그대로 반영해 완전히 구현.
    2. **(크래시, `DefaultFileStoreEngine`/`RevlogIndex`)** changelog-v2 +
       general-v2가 동시에 활성화된 저장소에서 changelog를 부트스트랩할 때
       `createAsGeneralV2`가 `createAsChangelogV2`보다 먼저 체크되어
       changelog가 `rank` 필드 없는 일반 general-v2 포맷(`INDEX_ENTRY_V2`)
       으로 잘못 생성됨 — 이 메서드 자신의 기존 문서화된 의도("changelog-v2가
       우선")와 real hg의 실제 우선순위(`mercurial/revlog.py`의
       `_init_opts`가 changelog kind에서 `'changelogv2'`를
       `'revlogv2'`보다 먼저 체크) 둘 다에 반하는 순서였다. real hg가 그
       위에 커밋하면 `revlog.py`의 `fast_rank()`가 `CHANGELOGV2`가 아닌
       모든 포맷에 대해 무조건 `None`을 반환해 `rank = 1 + None`에서
       `TypeError`로 크래시. 두 곳(근본 원인/방어 코드) 모두 우선순위 수정.
    3. **(정합성, `Revlog.appendRevisionV2`)** CL_V2 리비전의 `rank` 필드를
       real hg의 실제 재귀 공식(루트 리비전은 1, 이후는
       `1 + rank(parent)`) 대신 그냥 `rev`(첫 커밋이면 0)로 써서 real hg
       자신이 그 위에 이어서 계산하는 모든 후속 rank가 1씩 어긋나던 문제 —
       `IndexRecord`에 `rank` 필드 추가하고 CL_V2 읽기/쓰기 양쪽에 실제
       재귀 계산을 관통시켜 수정.
    4. **(데이터 손상, `DeltaCodec.decompressZstd` — 명령 무관 공용 버그,
       2개 병렬 에이전트가 `GcCommand`/`VerifyCommand` 작업 중 각각 독립
       발견)** zstd 압축 해제 목적지 버퍼 크기를 리비전의 최종 fulltext
       길이(`uncompLen`)로 잡았는데, 이는 델타(비-fulltext) 리비전에는 틀린
       값이다 — 델타 페이로드는 보통 fulltext보다 훨씬 작아서 버퍼가
       남으면 뒤쪽에 0바이트 패딩이 남고, `DeltaEngine.applyDelta`가 그걸
       가짜 `(start=0,end=0,length=0)` 헝크로 오인해 "Invalid delta hunk
       offsets"로 거부했다. native 테스트는 항상 zlib을 강제해서 이제까지
       한 번도 안 드러났고, hg-rust의 기본 압축이 zstd라 Docker 조합에서
       진짜 멀티 리비전 zstd 델타를 처음 만든 이번 매트릭스에서 처음
       드러남. zstd 프레임 자체에 내장된 content-size 헤더(이미
       `Revlog#decompressSidedataChunk`가 같은 이유로 쓰던 것과 동일한
       근거)로 버퍼 크기를 잡도록 수정 — 두 에이전트가 병합 시 거의 동일한
       수정을 독립적으로 제출해 하나로 합침.
    5. **(데이터 손상, `GcCommand`)** 순수 revlogv1 전제의 리라이트
       로직이 changelog-v2/general-v2(docket 기반) revlog를 만나면 그
       docket을 구식 v1 헤더로 통째로 덮어써 저장소를 망가뜨림 — v2/docket
       revlog는 건드리지 않고 건너뛰도록 수정.
    6. **(`GcCommand`)** fncache 재구축이 real hg가 절대 포함시키지 않는
       루트 `00changelog.i`/`00manifest.i`를 끼워 넣고, fileindex-v1
       저장소(real hg는 fncache 자체를 안 씀, 실측 확인)에서도 무조건
       fncache를 다시 씀 — 둘 다 real hg 실측대로 수정, 분할된(non-inline)
       revlog를 재압축 중 실수로 다시 inline화해 `.d` 파일을 고아로 만들던
       버그도 함께 수정.
    7. **(`CommitCommand`/`RollbackCommand`/`RecoverCommand`)** undo/journal
       기록이 v2 docket 파일의 (커밋해도 안 변하는) 바이트 길이만 기록해서
       changelog-v2/general-v2 커밋에 대한 rollback/recover가 완전히
       무동작(no-op)이었던 문제, 그리고 dirstate-v2의 컴패니언 데이터
       파일이 rollback 후 복원되지 않아 real hg가 "dirstate read race
       happened 5 times in a row"로 abort하던 문제 — docket 전체 내용
       백업/복원 방식으로 재구현. treemanifest 디렉터리 매니페스트도 이
       참에 rollback 추적 대상에 추가.
    8. **(`VerifyCommand`)** filelog 발견을 fncache에만 의존해 fileindex-v1/
       general-v2 저장소에서는 filelog 무결성 검사가 통째로 스킵되던(거짓
       음성) 문제, treemanifest의 `meta/<dir>/00manifest.i` 서브매니페스트를
       아예 검사 대상에서 빠뜨리던 문제 — 둘 다 수정.
    9. **(`CensorCommand`)** real hg의 `hgext.censor`가 갖고 있는
       "head/작업 디렉터리 parent에 살아있는 리비전은 censor 거부" 가드가
       hg4j에는 아예 없던 문제(`setCheckHeads(false)`로 우회 가능하게 real
       hg의 `--no-check-heads`도 동일하게 구현), 그리고
       `Revlog.censorRevision()`이 포맷 무관하게 항상 구식 revlogv1
       레이아웃으로 재작성해 general-v2 filelog의 docket을 깨뜨리고 진짜
       컴패니언 파일들을 고아로 만들던 데이터 손상 버그(`truncate`+
       `appendRevisionV2` 재사용하는 `censorRevisionV2` 분기로 수정) — 둘
       다 수정.

    **검증**: 전체 비-interop `test` 그린(merge 전후 각 브랜치 및 최종
    통합본 전부 확인), 6개 명령의 native 매트릭스 전부 스코프 재확인
    그린, `VerifyCommand`/`CensorCommand`의 Docker 매트릭스(각각 병합된
    `DeltaCodec`/`Revlog` 수정 경로를 실제로 타는 시나리오라 병합 후
    재검증 우선순위로 선택)도 그린. `GcCommand`/`RecoverCommand`/
    `RollbackCommand` 브랜치와 `VerifyCommand`/`CensorCommand` 브랜치가
    `DeltaCodec.java`(#4 버그)를 각자 독립 수정해 병합 시 충돌 — 두 수정이
    로직상 동일해 한쪽으로 합치고 문서만 통합.

    이번 wave 자체 기준 로컬 명령 완주 수는 31에서 37/67(코디네이터가
    다른 wave 5 병렬 그룹과 별도 취합 필요). 부수 발견: `GraftCommand`의
    v2-docket rollback/journal 기록에도 이번 `CommitCommand` 수정과
    유사한(그러나 별도인) gap이 있다는 것을 `RecoverCommand`/
    `RollbackCommand` 작업 중 발견했으나 이번 wave의 위임 범위 밖이라
    손대지 않음 — `GraftCommand` 매트릭스를 맡을 다음 에이전트가 반드시
    확인할 것(백로그 39 전체 완료 후 후속 점검 항목으로 유지).

    **조정자 취합(2026-09-05)**: 위 3개 wave 5 문단(메타데이터조회 wave
    +8, core/query wave +7, 이 admin/maintenance wave +6)은 서로 다른
    명령 집합에 대한 독립 병렬 작업으로 겹치지 않음(단, `DeltaCodec
    .decompressZstd` 델타-버퍼-크기 버그는 core/query wave와 이
    admin/maintenance wave가 각각 독립적으로 발견 — 두 수정이 변수명만
    다르고 로직이 완전히 동일함을 diff로 직접 확인한 뒤 병합 시 하나로
    정리). 세 wave를 모두 병합한 결과 로컬 명령 완주 수는
    31 + 8 + 7 + 6 = **52/67**. 남은 두 그룹: 작업트리 6개
    (`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/`TreeCommand`/
    `TreeMergeCommand`/`WorktreeCommand`, 완료됨·병합 대기) + 콘텐츠/트리
    읽기 6개(`CatCommand`/`FilesCommand`/`LocateCommand`/`GrepCommand`/
    `AnnotateCommand`/`ManifestCommand`, 진행 중) — 이 둘까지 병합되면
    로컬 매트릭스 67개 전체 완료, wire 매트릭스는 이미 8/8 완료 상태.

    **Wave 5(2026-09-05, `ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/
    `TreeCommand`/`TreeMergeCommand`/`WorktreeCommand`, 워킹카피 조작 계열
    6개를 한 에이전트에 배정)**: 6개 명령 전부 native 6/6 + Docker 30/30
    = 36/36 GREEN 도달. 이 중 2건은 이 6개 명령의 범위를 넘어 **requirement
    매트릭스 인프라 자체(모든 명령이 공유하는 revlog 압축 해제/dirstate-v2
    직렬화 계층)에 있던 진짜 hg4j 버그**였다 — 다른 wave가 이미 "완료"로
    표시한 명령들도 잠재적으로 영향받았을 수 있어 별도로 굵게 표시한다.

    **진짜 hg4j 버그 7건 발견·수정**:
    1. **(광범위 영향) `DeltaCodec.decompressZstd()`가 델타 리비전의 실제
       압축 해제 길이를 무시하고 인덱스의 `uncompLen`만큼 통째로 반환**:
       real hg의 revlog v1 포맷에서 `uncompLen`은 "이 델타 자체의 압축
       해제 크기"가 아니라 "이 리비전까지 델타 체인을 다 적용했을 때
       재구성되는 최종 fulltext 크기"다(델타를 적용한 결과가 줄어드는
       경우, 즉 삭제/축소가 추가보다 큰 편집에서는 델타 자체의 압축 해제
       바이트 수가 `uncompLen`보다 작다) — hg4j는 `dest = new
       byte[uncompLen]`로 미리 할당해두고 `Zstd.decompress()`의 실제
       반환 길이(`result`)를 무시한 채 버퍼 전체를 반환해, 델타 페이로드
       뒤에 자바 배열 기본값인 0바이트가 패딩으로 남아 `DeltaEngine.
       applyDelta()`가 이를 `start=0, end=0`의 가짜 델타 헝크로 오인,
       `HgCorruptDataException`으로 실패했다. `hg-rust-7.2.4` 컨테이너가
       만든 순정 2-커밋 저장소(스토리지 확장 없음, `dirstate1/cl1/
       flatmanifest/none`처럼 가장 단순한 조합)를 hg4j `UpdateCommand`로
       체크아웃하는 것만으로 100% 재현되는, **Docker 30개 조합 전체에
       영향을 미치는 근본적인 읽기 버그**였다(다른 명령들의 매트릭스가
       이를 피해간 것은 그 명령들의 시나리오가 마침 이 "델타가 줄어드는"
       패턴을 건드리지 않았을 뿐). `Zstd.decompress()`의 실제 반환
       길이로 잘라내도록(`Arrays.copyOf(dest, (int) result)`) 수정.
    2. **(광범위 영향) `DirstateV2Node`/`DirstateV2Serializer`가
       `MODE_EXEC_PERM`과 `MODE_IS_SYMLINK`를 상호 배타로 취급**: real hg
       7.2.4의 Rust dirstate-v2 소스(`rust/hg-core/src/dirstate/entry.rs`
       `mode_changed()`)를 직접 대조 확인 — `EXEC_BIT_MASK`는 소유자
       실행 비트(`0o100`) 하나뿐이고, 실제 OS 심볼릭 링크의 `lstat` 모드는
       항상 전체 `rwxrwxrwx`(0120777)를 보고하므로 **모든 심볼릭 링크는
       항상 "실행 비트 있음"으로 관측된다** — 즉 심볼릭 링크 dirstate
       항목은 `MODE_IS_SYMLINK`와 `MODE_EXEC_PERM`을 **둘 다** 켜야
       real hg의 자체 검증과 일치한다. hg4j가 둘을 상호 배타로 처리해
       심볼릭 링크에서 `MODE_EXEC_PERM`을 항상 꺼버렸던 탓에, dirstate-v2
       저장소에서 hg4j가 만든(또는 다시 쓴) 심볼릭 링크 항목은 real hg
       자신의 `hg status`가 항상 "M"(수정됨)으로 오판했다 — **`AddCommand`/
       `CommitCommand`/`MergeCommand`/`RebaseCommand` 등 dirstate-v2 +
       심볼릭 링크 조합을 다루는 모든 기존 완료 명령에도 동일하게 영향을
       미쳤을 수 있는 공유 계층 버그**(각 명령의 기존 매트릭스가 심볼릭
       링크를 시나리오에 포함하지 않았다면 가려져 있었을 뿐). 둘을 항상
       함께 설정하도록 수정, 기존 `DirstateV2LayoutTest`의 "심볼릭 링크는
       실행 비트가 꺼져야 한다"는 낡은(틀린) 기대치도 함께 정정.
    3. **`UpdateCommand`가 심볼릭 링크의 dirstate 모드를 맨 `0120000`
       (S_IFLNK 타입 비트만)으로 기록**: real hg 7.2 CLI로 직접 확인 —
       실제 심볼릭 링크의 `lstat` 모드는 항상 `0120777`(타입 비트 +
       전체 권한 비트)이고, dirstate-v1은 이 raw 정수 모드를 그대로
       저장/비교하므로 맨 `0120000`으로 저장하면 real hg 자신의 `hg
       status`가 매번 권한 불일치로 "M"을 보고했다(체크아웃 직후에도).
       `0120777`로 수정 — 이 값이 위 dirstate-v2 버그(#2)의 실행 비트
       충돌을 실제로 노출시킨 계기이기도 했다(맨 `0120000`이던 시절엔
       `mode & 0111`이 항상 0이라 #2가 가려져 있었음).
    4. **`ArchiveCommand`가 real hg의 `hg archive`와 구조적으로 전혀
       다른 산출물을 만들고 있었음**: real hg 7.2 CLI로 직접 대조 —
       (a) `.hg_archival.txt` 메타데이터 멤버(repo root hex/아카이브된
       노드 hex/브랜치/최신 태그 정보)가 모든 아카이브 타입에 항상
       포함되는데 hg4j는 전혀 만들지 않았음, (b) zip/tar 계열은 대상
       파일명에서 확장자를 뺀 이름을 디렉터리 프리픽스로 항상 붙이는데
       hg4j는 맨 경로만 썼음, (c) 실행 비트/심볼릭 링크 플래그를 완전히
       무시하고 항상 평범한 파일로 씀, (d) tar/tgz/tbz2 타입 자체가
       구현돼 있지 않아 zip과 디렉터리 두 가지뿐이었음, (e) 자체 구현한
       평면 매니페스트 전용 파서를 써서 treemanifest 저장소의 하위
       디렉터리가 통째로 누락됨. `HgRepository#getManifestAtCommit()`
       (이미 treemanifest 대응됨, `TreeCommand`/`ManifestCommand`와
       공유)로 교체하고, commons-compress의 `TarArchiveOutputStream`/
       `ZipArchiveOutputStream`으로 tar/tgz/tbz2 + 실행 비트/심볼릭 링크
       + `.hg_archival.txt`(단일 계보 히스토리 기준 latesttag/distance
       알고리즘까지, real hg 소스 `archival.py`/`templatekw.py`로 검증)를
       전부 새로 구현. `txz`(lzma)는 신규 의존성(`org.tukaani:xz`)이
       필요해 의도적으로 범위 밖으로 남김(정직하게 기록).
    5. **`PurgeCommand`가 심볼릭 링크로 연결된 디렉터리를 실제로 따라
       들어가 그 안의 파일을 삭제할 수 있었음(실제 데이터 손실 버그)**:
       `Files.isDirectory(path)`가 `LinkOption.NOFOLLOW_LINKS` 없이
       심볼릭 링크를 그대로 따라가, 저장소 바깥의 임의 디렉터리를
       가리키는 심볼릭 링크가 있으면 그 바깥 디렉터리의 파일까지
       "추적 안 됨"으로 오판해 삭제할 수 있었다(실제로 외부 디렉터리에
       파일을 만들고 심볼릭 링크로 연결해 재현: hg4j는 외부 파일을
       지웠지만 real hg의 `hg purge`는 심볼릭 링크 자신만 지우고 외부
       디렉터리는 전혀 건드리지 않음을 확인). 심볼릭 링크는 항상 불투명한
       leaf로 취급(절대 내부 순회 안 함)하도록 수정. 부수적으로 두 건
       더 발견: (a) 끊어진(대상이 없는) 심볼릭 링크가 `Files.exists()`가
       false를 반환하는 바람에 조용히 건너뛰어지던 버그(real hg는 끊어진
       심볼릭 링크도 삭제함, `NOFOLLOW_LINKS`로 수정), (b)
       `purgeDirectories` 기본값이 `false`였는데 real hg 자신의 `hg
       purge`는 플래그 없이도 기본으로 추적 안 된 빈 디렉터리를 지운다
       (`--dirs`/`--files`는 범위를 좁히기만 할 뿐, 켜는 옵션이 아님) —
       기본값을 `true`로 수정. `.hgsub` 선언 경로를 불투명 경계로 처리하는
       로직도 추가(`HgRepository#loadSubrepoPaths()`를 public으로 승격해
       재사용, `scanWorkingCopy()`와 동일 규칙).
    6. **`WorktreeCommand`(`hg share` 상당)가 실제 체크아웃을 전혀
       수행하지 않았음**: real hg 7.2의 `share` 확장(`--config
       extensions.share=`)으로 직접 대조 — real `hg share`는 항상 공유
       저장소의 tip으로 실제 체크아웃까지 수행하고("updating working
       directory" 출력) 새 워크트리의 `.hg/requires`에 소스가 뭐였든
       "shared" 마커 줄을 추가로 붙이는데, hg4j는 빈 40바이트 dirstate
       스텁만 만들고 파일을 전혀 체크아웃하지 않았으며 requires도 그대로
       복사만 했다. `UpdateCommand`로 실제 체크아웃하도록 수정하고
       requires에 "shared" 줄을 추가. 이 수정 도중 2차 버그 발견:
       기존의 무조건적인 40바이트(p1+p2 노드) dirstate 스텁 쓰기가
       dirstate-v1 레이아웃을 가정한 것이라, dirstate-v2 공유 저장소에서는
       유효한 V2 도켓이 아닌 쓰레기 바이트가 되어 `UpdateCommand`가 체크아웃
       직전 현재 부모를 읽으려고 dirstate를 다시 읽는 순간
       `BufferUnderflowException`으로 깨졌다(Docker 30개 조합 전체 재현).
       공유 스토어에 리비전이 있을 때는 dirstate 파일을 아예 미리 쓰지 않고
       `UpdateCommand`가 처음부터 새로 만들도록(파일이 없으면 빈 Dirstate로
       시작하는 기존 경로 재사용) 수정 — 리비전이 0개인 예외 케이스에서만
       기존 스텁 방식 유지(기존 `WorktreeCommandTest`의 "main이 비어있을 때"
       기대치 보존).
    7. **`TreeMergeCommand`(작업 디렉터리 없는 순수 3-way 병합 계산)의
       결과 API에 파일 모드/플래그 정보가 아예 없었음**: `getChangedFiles()`
       는 바이트만 반환해, 내용은 그대로인데 실행 비트나 심볼릭 링크
       플래그만 바뀐 변경(예: `chmod +x`)이 호출자에게 조용히 유실될 수
       있었다. `RebaseCommand.attemptThreeWayMerge()`가 이미 쓰는 것과
       동일한 관례(로컬 우선, 없으면 상대편)로 모드를 계산하는
       `getChangedModes()`를 신규 추가.

    **확인만 하고 새 버그 없음으로 결론**: 이 wave에 배정된 "`UpdateCommand`의
    알려진 불필요한 pull(redundant pull) 갭"은 실제로는 백로그 32(서브저장소)
    작업에서 이미 `UpdateCommand.isRevisionPresentLocally()`로 완전히 수정돼
    있었다(리비전이 로컬에 이미 있으면 pull/fetch를 건너뜀, `hg4jUpdateSkips
    PullWhenSubrepoRevisionAlreadyLocalMatchingRealHg` 테스트로 이미 검증됨)
    — 이 wave에서는 재확인만 하고 추가 조치 없음.

    **검증**: 6개 명령 각각 `RequirementMatrix{Command}CoreRoundTripTest`
    (native 6/6)/`...DockerRoundTripTest`(Docker 30/30) 신규 추가(총 12개
    클래스 + 4개 헬퍼 서브프로세스 — `ArchiveCommand`/`PurgeCommand`/
    `UpdateCommand`/`WorktreeCommand`는 실제 쓰기 커맨드라 패턴 일관성을 위해
    헬퍼를 뒀고, `TreeCommand`/`TreeMergeCommand`는 순수 읽기/계산이라 헬퍼
    없이 직접 호출), 전부 `src/test/java/io/github/search5/hg4j/api/`. 기존
    `ArchiveCommandCoverageTest`(getManifestForCommit 관련 낡은 테스트 6개를
    새 아키텍처에 맞게 제거·2개는 교체), `PurgeCommandTest`(기본값/심볼릭 링크
    기대치 4개 갱신), `DirstateV2LayoutTest`(1개 갱신), `TreeMergeCommandTest`
    (신규 2개 추가), `WorktreeCommandTest`(무변경, 7개 그대로 GREEN) 전부
    회귀 확인. 전체 비-interop `test` 전부 GREEN(신규 실패 없음, 상세 수치는
    이 문단 갱신 시점의 커밋 메시지 참고). 이로써 명령 기준 완주 수는 31에서
    37(+`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/`TreeCommand`/
    `TreeMergeCommand`/`WorktreeCommand`)로 증가 — 나머지 30개 로컬 명령과
    wire 매트릭스 잔여 5개는 여전히 미착수(다른 wave 5 에이전트들과 병렬
    진행 중이라 최종 합산은 조정자가 취합).

    **조정자 취합(2026-09-05)**: 위 4개 wave 5 문단(메타데이터조회 +8,
    core/query +7, admin/maintenance +6, 이 작업트리 wave +6)은 서로
    다른 명령 집합에 대한 독립 병렬 작업으로 겹치지 않음. 이 wave의
    버그 #1(`DeltaCodec.decompressZstd`)은 core/query·admin/maintenance
    두 그룹이 이미 독립 발견·수정한 것과 같은 버그 — 병합 시 diff로
    로직 동일함을 확인, 이미 반영된 버전(zstd 프레임 자체의
    content-size로 목적지 버퍼를 정확히 사이징)을 채택하고 이 wave의
    "uncompLen만큼 넉넉히 할당 후 트림" 버전은 이미 반영된 frameSize
    기반 할당과 논리적으로 어긋나 폐기. 버그 #2(`DirstateV2Node`
    exec/symlink 플래그 상호배타 버그)는 이 wave 스스로 "AddCommand/
    CommitCommand/MergeCommand/RebaseCommand 등 이미 완료된 명령에도
    같은 근본 원인이 잠재했을 수 있다"고 명시 — 병합 후 전체 비-interop
    테스트가 여전히 GREEN임을 코디네이터가 별도로 재확인. 네 wave 병합
    후 로컬 명령 완주 수는 31 + 8 + 7 + 6 + 6 = **58/67**. 남은 그룹:
    콘텐츠/트리 읽기 6개(`CatCommand`/`FilesCommand`/`LocateCommand`/
    `GrepCommand`/`AnnotateCommand`/`ManifestCommand`, 진행 중) —
    병합되면 로컬 매트릭스 67개 전체 완료, wire 매트릭스는 이미 8/8
    완료 상태.

    **Wave 5(2026-09-05, `CatCommand`/`FilesCommand`/`LocateCommand`/
    `GrepCommand`/`AnnotateCommand`/`ManifestCommand`)**: 이 6개 명령
    (모두 읽기 전용, 트리/경로/콘텐츠 조회 계열)에 36개 조합(native 6 +
    Docker 30) 적용, **전부 GREEN**. `CatCommand`는 이미 원래 4개 명령
    (commit/log/status/cat)의 일부로 최소 커버리지가 있었지만 rename/
    executable-bit/removal/nested-treemanifest 시나리오는 다루지 않았으므로
    별도 전용 테스트로 재검증. 읽기 전용 명령들이라 hg4j 자신은 어떤 조합에서도
    쓰기를 하지 않아 `HelperMain` 서브프로세스가 필요 없음(그 우회 대상이었던
    docker-exec 인터리빙 손상은 hg4j 자신의 revlog *쓰기* 특유의 문제).
    신규 테스트 클래스 4개(`RequirementMatrixCatFilesLocateManifestCoreRoundTripTest`/
    `...DockerRoundTripTest`가 Cat+Files+Locate+Manifest 4개를, `RequirementMatrixGrepAnnotateCoreRoundTripTest`/
    `...DockerRoundTripTest`가 Grep+Annotate 2개를 함께 검증 — 앞서 Copy/Rename/
    Forget/Remove/Addremove를 묶은 전례와 같은 근거: 공유 manifest-읽기 경로를
    한 번에 검증하면서 컨테이너 생성 오버헤드도 줄임), `src/test/java/io/github/search5/hg4j/api/`.
    **결과**: `RequirementMatrixCatFilesLocateManifestCoreRoundTripTest` native 6/6,
    `...DockerRoundTripTest` Docker 30/30; `RequirementMatrixGrepAnnotateCoreRoundTripTest`
    native 18/18(6 조합 x 3 테스트 메서드), `...DockerRoundTripTest` Docker
    90/90(30 조합 x 3 테스트 메서드). 비-interop `test` 전체 재확인: 2278건
    0 실패/0 에러(2 스킵, 기존과 동일).

    **발견·수정한 진짜 hg4j 버그 3건**:
    1. **`DeltaCodec.decompressZstd`가 델타(비-리터럴) 리비전의 압축 해제
       목표 버퍼 크기로 인덱스의 `uncompLen` 필드를 그대로 신뢰하던 버그** —
       실제로는 이 필드가 "이 청크 자체의 압축 해제 후 크기"가 아니라
       "델타를 전부 적용한 뒤의 최종 재구성 텍스트 크기"(`hg --debug
       debugindex`의 `full-size` 컬럼과 정확히 일치)를 기록한다(real hg
       소스/실측 양쪽으로 확인: `hg-rust-7.2.4`가 쓴 `format.use-fileindex-v1=yes`
       매니페스트 리비전 1의 `uncompLen`이 210이었는데 실제 zstd 프레임을
       디코드하면 140바이트 델타 스트림이 나왔고, 그 델타를 153바이트
       베이스에 적용하면 정확히 210바이트 재구성 텍스트가 나옴 — 델타
       청크 자체의 크기(140)와 재구성 텍스트 크기(210)가 다르다는 것을
       바이트 단위로 확정). 기존 코드는 `new byte[uncompLen]`(210바이트)를
       zstd 목적지 버퍼로 할당해 실제 140바이트만 채워지고 나머지 70바이트가
       0으로 남는데, 이 배열을 잘라내지 않고 그대로 반환해 `DeltaEngine
       .applyDelta`가 후행 0바이트를 "start=0,end=0"인 새 델타 헝크 헤더로
       오인식 — `lastCopied`가 이미 전진해 있어 `start < lastCopied`
       위반으로 `HgCorruptDataException("Invalid delta hunk offsets")`.
       네이티브 테스트는 `HgTestUtils#hg`가 항상 zlib를 강제해서(zlib
       경로는 `Inflater`가 크기와 무관하게 동작해 이 버그를 겪지 않음)
       이 버그를 절대 드러낼 수 없었고, Docker 매트릭스도 지금까지는
       리비전이 2개 이상인 매니페스트/파일로그를 zstd 압축 저장소에서
       실제로 읽어본 적이 없어 처음 발각됨(이 캠페인의 다른 wave들이 만든
       Docker 시나리오 다수가 재현 조건에 가까웠을 가능성이 있으나, 이번
       조사 범위 밖이라 재검증하지 않음 — 별도 확인 필요 시 후속 항목으로).
       수정: `Zstd.getFrameContentSize()`로 zstd 프레임 자신이 담고 있는
       실제 압축 해제 크기를 얻어 목적지 버퍼 크기로 쓰고(이미 sidedata
       청크 압축 해제 경로가 쓰던 것과 동일 패턴), 방어적으로
       `Zstd.decompress()`가 실제로 반환한 바이트 수로 한 번 더 자름.
       쓰기 경로(`Revlog.appendRevision`류)는 애초에 `uncompLen`에
       "재구성된 전체 텍스트 길이"를 올바르게 기록하고 있어(`processedContent
       .length`) 수정 불필요 — 읽기 경로만의 버그였음.
    2. **`GrepCommand`가 `fileindex-v1`/`general-v2` 저장소에서 항상 빈
       결과만 반환하던 버그** — 기존 구현은 `store/fncache` 파일 하나만
       읽어 추적 대상 파일 목록을 얻었는데, `fileindex-v1`(및 이를
       암시하는 `general-v2`)로 만든 저장소는 `fncache` 자체가 존재하지
       않는다(실측: `hg-rust-7.2.4` 컨테이너에서 `format.use-fileindex-v1=yes`
       저장소의 `store/requires`는 `store`만 있고 `fncache`/`dotencode`가
       없음 — 자체 `fileindex`/`fileindex-list`/`fileindex-tree` 사이드카
       파일이 같은 역할을 대신함). `hg grep` 자신은 매니페스트를 훑지
       `fncache`에 의존하지 않으므로, 이는 유효한 저장소 형식 전체에서
       조용히 결과가 0개가 되는 진짜 완결성 누락이었다(성능 최적화 누락이
       아니라 스펙 위반). 수정: `fncache`가 없으면 `store/data/`를 직접
       재귀 스캔해 `.i` 파일을 찾는 폴백 추가, 물리 경로를 논리 경로로
       되돌리는 `NodeIdUtil.decodeStoreDataPath`(기존 `encodeFname`의
       역함수, `~xx`/`_x`/`__` 이스케이프를 한 패스로 복원 — `auxEncode`가
       추가하는 이스케이프도 같은 `~xx` 표기라 순서 무관하게 정확) 신규
       추가. 겸사겸사 `fncache` 경로도 같은 디코더로 통일(기존 코드는
       `fncache`에 적힌 "인코딩된" 경로를 디코딩 없이 그대로 `GrepResult
       .path`에 넣던 잠재 버그였음 — 대문자/예약어가 포함된 파일명에서만
       드러나는 것이라 이번 테스트의 순수 소문자 시나리오로는 검증되지
       않았지만 안전하게 함께 수정).
    3. **`AnnotateCommand.traceLines`의 rename-crossing 로직이 두 가지로
       실제 hg와 다르게 동작**: (a) 파일 리비전 0에 copy/copyrev 메타데이터가
       있어 이전 파일의 상태로 크로스했을 때, 크로스된 콘텐츠를 "베이스"로만
       쓰고 그 파일 자신의 리비전 0 콘텐츠와의 실제 diff를 건너뛰던 버그 —
       `hg mv old new; <new 내용 편집>; hg commit`처럼 rename과 편집이 같은
       커밋에 함께 일어나는(실무에서 매우 흔한) 경우, 그 커밋에서 바뀌거나
       추가된 줄이 통째로 유실되거나 잘못된 리비전으로 귀속됐다(재현: rename과
       동시에 새 줄 하나를 추가하면 그 줄의 content가 빈 문자열로 나옴).
       기존 테스트들은 전부 "순수 rename"(같은 커밋에 편집 없음) 시나리오만
       썼기 때문에 크로스된 베이스와 리비전 0의 실제 콘텐츠가 우연히 같아
       이 버그가 가려져 있었다. 리비전 0도 다른 리비전과 동일하게 루프에서
       diff하도록 수정(크로스 시 루프 시작을 r=1이 아니라 r=0으로).
       (b) 개행으로 끝나는(사실상 대부분의) 파일마다 가짜 빈 줄을 하나 더
       만들어내던 버그(`split("\n", -1)`가 후행 개행 뒤 빈 문자열을 보존) —
       `DiffCommand`에서 이미 한 번 발견·수정됐던 것과 정확히 같은 종류의
       버그가 `AnnotateCommand`에도 남아 있었다. 이 버그는 기존 유닛
       테스트(`HgAnnotateTest`/`AnnotateCommandCoverageTest`)의 기대값에도
       그대로 박제돼 있었어서(주석에 "+ trailing empty" 명시) 함께 갱신.
       두 수정 모두 real hg 7.2 CLI(`hg annotate -n`)와 직접 대조해 검증.
    상세 근거·재현 절차는 각 신규 테스트 클래스의 javadoc 및
    `DeltaCodec`/`GrepCommand`/`AnnotateCommand`의 갱신된 코드 주석 참고.
    이로써 로컬 명령 기준 완주 수는 31에서 37로 증가(다른 wave 5 에이전트들과
    병렬 진행 중이라 최종 합산은 조정자가 취합), 나머지 30개 로컬 명령 및
    wire 매트릭스 잔여 5개는 여전히 미착수.

    **조정자 최종 취합 및 백로그 39 완료 선언(2026-09-05)**: 위 5개
    wave 5 문단(메타데이터조회 +8, core/query +7, admin/maintenance +6,
    작업트리 +6, 이 콘텐츠/트리읽기 wave +6)은 서로 다른 명령 집합에
    대한 독립 병렬 작업으로 겹치지 않음. 이 wave의 버그 #1
    (`DeltaCodec.decompressZstd`)은 core/query·admin/maintenance·
    작업트리 세 그룹이 이미 각각 독립적으로 발견·수정한 것과 정확히
    같은 버그(이번 세션에서 4번째 독립 발견 — 4개 병렬 그룹이 서로
    소통 없이 같은 근본 원인에 도달했다는 것 자체가 이 버그의 실제
    파급력을 보여줌) — 병합 시 diff로 로직 동일함을 재확인, 이미 반영된
    버전(zstd 프레임 자체의 content-size 기반 사이징)을 그대로 유지.

    **다섯 wave 병합 후 정확한 최종 집계**: 이 문서 및
    [[exhaustive-interop-matrix-plan]] 양쪽에서 계속 써온 "67개"라는
    분모 자체가 처음부터 부정확했음이 드러남 —
    [[exhaustive-interop-matrix-plan]] §3-2의 "로컬/저장소 전용,
    59개"라는 소제목 아래 실제로 나열된 명령은 60개였고(소제목 자체의
    표기 오류), 이후 모든 wave의 "X/67" 진행률은 이 잘못된 분모를
    그대로 물려받은 근사치에 불과했다. 병합 완료 시점에 실제 명령 파일
    전체 목록(`src/main/java/.../api/*Command.java`, 68개)과 두
    설계 문서의 §3-1(wire 대상 8개)/§3-2(로컬 대상, 실제로는 60개)
    나열, 그리고 실제 존재하는 모든 `RequirementMatrix*
    CoreRoundTripTest`/`HgWireProtocolMatrix*Test` 클래스가 커버하는
    명령을 3자 프로그램 대조한 결과: **로컬 매트릭스 대상 60개 전부와
    wire 매트릭스 대상 8개 전부가 예외 없이 실제 매트릭스 테스트로
    커버되어 있음을 확인**(로컬 60/60, wire 8/8, 합계 68/68 — 빠진
    명령 없음, 초과 카운트도 없음). **이로써 백로그 항목 39("포셀린
    명령 x wire protocol 조합 x requirement 조합 exhaustive interop
    매트릭스")는 완료로 전환한다.**


## 상세 원문 B — `exhaustive-interop-matrix-plan.md` §4 (매트릭스 설계 문서 자체의 진행상황 절 — 초기 부트스트랩 체크리스트 포함, 원문 A와 상당 부분 중복되나 초기 native/Docker 조합 설계·부트스트랩 세부는 원문 A에 없는 고유 내용)

## 4. 구현 우선순위 및 진행 상황

핵심 라운드트립(commit → log/status/cat, clone/pull/push)부터 먼저 매트릭스를 채우고,
이후 나머지 로컬 명령·전송 명령으로 확장한다. 각 명령이 매트릭스에 편입될 때마다 이
표의 상태를 갱신한다.

### 4-1. Requirement 매트릭스 대상 명령
- [x] 설계(§1) 확정, native 6개 + Docker 30개 = 36개 조합 유효성/상호배타 관계
  실측 완료(2026-09-04, `hg-rust-7.2.4` 컨테이너 포함, dirstate-v2도 Docker
  필요임을 TDD 도중 재확인해 분할 정정)
- [x] native 6개 조합 — **완료(2026-09-04)**: `RequirementMatrixCoreRoundTripTest`
  (`src/test/java/io/github/search5/hg4j/api/`), 6 x 2방향(real hg 쓰기→hg4j
  읽기, hg4j 쓰기→real hg 읽기+`hg verify`) = 12케이스 전부 GREEN. TDD 과정에서
  `Revlog.appendRevisionV2`/`RevlogIndex.initializeNewV2Docket`의 실제 버그 2건
  발견·수정(changelog-v2 저장소가 zlib 설정인데도 zstd 프레임을 쓰던 문제, docket
  압축 헤더 바이트가 항상 zstd로 하드코딩돼 있던 문제) — 둘 다 real hg가 만든
  저장소를 hg4j가 쓸 때만 드러나는 종류의 버그였다.
- [x] Docker 30개 조합 — **완료(2026-09-04)**: `RequirementMatrixDockerRoundTripTest`
  (60케이스 = 30읽기+30쓰기), **60개 전부 GREEN**. 쓰기 방향에서 처음엔 dirstate=v2
  18개가 신규 실버그(백로그 #37)로 SKIP됐으나, 근본 원인 규명·수정으로 전부 해소—
  real hg의 dirstate-v2 리더(`hg-rust-7.2.4` 컨테이너의 실제 Rust 소스
  `dirstate_map.rs` 직접 대조)가 자식 노드 배열을 basename 오름차순 이진 탐색으로
  찾는데, hg4j `DirstateV2Serializer`가 `LinkedHashMap` 삽입 순서 그대로(정렬 없이)
  썼던 것이 원인 — UTF-8 바이트 기준 정렬 로직을 추가해 수정, 18개 전부 GREEN 전환.
  부수적으로 JVM 안에서 hg4j 커밋 실행과 `docker exec`/`docker run` 프로세스
  스폰을 번갈아 하면 커밋이 비결정적으로 깨지는 테스트 인프라 문제도 발견해
  `RequirementMatrixCommitHelperMain`(별도 서브프로세스)으로 우회.
- [x] 기존 fixture 기반 커버리지 테스트(`TreemanifestRealFixtureTest`,
  `ChangelogV2BootstrapTest`, `DirstateV2RealFixtureTest`, `FileIndexTest`,
  `NodeMapFileFixtureTest`, `NodeMapFileWriterTest`, `RevlogV2GeneralParserTest`)를
  라이브 쓰기 검증으로 보강 — **완료(2026-09-04)**(사용자 지시, "이미 만들어진
  커버리지 테스트는 interop에 맞춰서 변경"). 조사 결과 `ChangelogV2BootstrapTest`/
  `NodeMapFileWriterTest` 2개는 이미 라이브 쓰기 검증이 있어 중복 작업 없이 유지.
  `TreemanifestRealFixtureTest`에 중첩 디렉터리 treemanifest 쓰기 검증(기존 native
  매트릭스 테스트가 루트 레벨 파일만 다뤘던 진짜 누락분) 추가, `RevlogV2GeneralParserTest`
  에 general-v2 라이브 쓰기 검증 추가, `FileIndexTest`/`NodeMapFileFixtureTest`/
  `DirstateV2RealFixtureTest`는 동일 조합의 라이브 커버리지가 이미 있는 곳(Docker
  매트릭스 파일)으로의 javadoc 상호 참조만 추가(같은 저장소 상태를 검증하는 3~4번째
  중복 하니스를 만들지 않기 위한 판단).
- [x] 나머지 56개 로컬 명령 — **완료(2026-09-05, wave 1~5, 백로그 #39)**.
  아래는 그 과정의 웨이브별 이력. **Wave 1(2026-09-05, 백로그 #39)**:
  우선순위 4개(`PushCommand`/`RebaseCommand`/`ShelveCommand`/`StripCommand`)에
  36개 조합(native 6 + Docker 30) 적용 완료 — `RebaseCommand`/`StripCommand`/
  `ShelveCommand` 3개는 전부 GREEN(그 과정에서 `Revlog.truncate()` 통합으로
  실버그 2건 수정), `PushCommand`는 treemanifest dirlog 미전송 + changelog-v2
  sidedata 미전송 2개의 실버그가 남아 native 2/6·Docker 14/30만 GREEN(원인
  규명 완료, 수정은 다음 wave). 상세는 [[mercurial-spec-compliance-requirement]]
  백로그 #39 참고.
  **Wave 3(2026-09-05)**: 4개 병렬 에이전트로 총 11개 명령 진행.
  `MergeCommand`/`SubrepoCommand` 둘 다 native 6/6 + Docker 30/30 전부
  GREEN — `SubrepoCommand`의 `init`/`update`가 pin된 리비전으로 실제
  체크아웃을 한 적이 없던 버그, `CommitCommand`의 미해결 머지 충돌 차단
  로직(마커 텍스트 스캔 → 실제 머지 상태 기반으로 수정) 및 머지 커밋 후
  `.hg/merge` 미정리 버그까지 진짜 hg4j 버그 3건을 TDD로 발견·수정.
  `HisteditCommand`/`GraftCommand`도 36개 조합 전부 GREEN —
  `HisteditCommand`는 매니페스트 정렬/dirstate 재동기화 실버그 2건 수정.
  `GraftCommand`는 `RebaseCommand`의 하드닝 이전 cherry-pick 로직을 독자
  재구현하고 있던 실제 사례(3-way merge/conflict 감지 부재로 인한 data-loss,
  크래시 안전 저널 부재, 잘못된 obsolescence marker 기록) 3건을 발견해
  `RebaseCommand`의 공용 로직(`attemptThreeWayMerge` 등, package-private
  static으로 추출)을 재사용하도록 수정. `AddCommand`/`BookmarkCommand`/
  `TagCommand` 세 명령도 36개 조합 전부 GREEN(`AddCommand` native 6/6+Docker
  30/30, `BookmarkCommand` native 6/6+Docker 30/30, `TagCommand` native
  6/6+Docker 30/30) — 이 과정에서 `BookmarkCommand`의 진짜 hg4j 버그 3건을
  발견·수정: (1) bookmark 이동에 `TagCommand`의 백로그 #36과 동일한 force
  게이트가 아예 없던 것 — 추가한 게이트가 DAG 조상 관계뿐 아니라
  `PushCommand`가 이미 쓰던 `obsutil.foreground`(obsolescence-successor
  체인)까지 인정하도록 구현하지 않으면 `hg amend` 직후 활성 bookmark
  자동전진이 깨진다는 것을 전체 회귀에서 발견해 함께 해결, (2) 그 과정에서
  `CommitCommand`의 커밋마다 활성 bookmark를 전진시키는 내부 호출도 별도로
  force 우회가 필요함을 발견·수정, (3) 마지막 bookmark 삭제 시 real hg는
  `.hg/bookmarks`를 빈 파일로 남기는데 hg4j는 파일 자체를 지워버리던 것.
  `BundleCommand`(`hg bundle` — 독립 번들 FILE, push/pull과 changegroup
  패킹 로직 공유)도 36개 조합 전부 GREEN — 예상대로 `PushCommand`의 수정
  전과 동일한 cg1-only 하드코딩 버그를 갖고 있어 동일 패턴으로 수정
  (`ChangegroupParser.writeBundle` + treemanifest dirlog 패킹 + 신규
  `BundleType.NONE_V3`/`GZIP_V3`/`BZIP2_V3` cg3-in-bundle2 타입 추가).
  `cl2+sidedata` 조합(native 2, Docker 10)은 순정 real-hg-to-real-hg
  컨트롤로 재현 확인한 **real hg 자신의 파일 기반 bundle/unbundle 한계**
  (hg4j가 고칠 수 없는 문제)로 명시적으로 tolerate 처리. 상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 나머지 44개
  명령은 여전히 미착수.
  **Wave 4(2026-09-05, `CopyCommand`/`RenameCommand`/`ForgetCommand`/
  `RemoveCommand`/`AddremoveCommand`)**: 5개 명령 전부 native 6/6 + Docker
  30/30 = 36/36 GREEN. 진짜 hg4j 버그 4건 발견·수정: (1) `AddCommand`가
  이미 dirstate 항목이 있는 경로(forget/remove 이후 재-add)에 항상 신규
  `a` 항목을 만들어 filelog 히스토리 연결이 끊기던 버그(real hg는
  `normallookup`으로 `n`+모호 stat 복원), (2) dirstate-v2에서 "캐시된
  size/mtime을 신뢰 불가"(HAS_MODE_AND_SIZE/HAS_MTIME 플래그 꺼짐)를
  hg4j가 구체적이지만 틀린 0으로 읽어들여 `RemoveCommand`/`StatusCommand`/
  `ShelveCommand`/`CommitCommand`가 건드리지 않은 파일을 "수정됨"으로
  오판하던 공용 버그(`Dirstate.Entry#isStatAmbiguous()` 신설로 해결), (3)
  `Dirstate.read(File)`의 dirstate-v2 분기가 파싱된 copyMap을 아예
  버리던 한 줄 누락 버그(연속된 `hg copy` 호출 중 앞선 복사의 copy-source
  기록이 사라짐), (4) `CommitCommand`가 커밋된 파일의 dirstate copyMap
  잔여 기록을 정리하지 않던 버그. 상세는 [[mercurial-spec-compliance-requirement]]
  백로그 #39 참고. 나머지 48개 로컬 명령은 여전히 미착수.

  **Wave 4(2026-09-05, `BranchCommand`/`BranchesCommand`/`PhaseCommand`)**:
  이 3개 명령에 36개 조합(native 6 + Docker 30) 적용, 전부 GREEN
  (`BranchCommand`/`BranchesCommand` native 6/6+Docker 30/30,
  `PhaseCommand` native 6/6+Docker 30/30). `PhaseCommand`는 real-hg
  byte-for-byte `.hg/store/phaseroots` 비교 검증 설계 도중 진짜 hg4j
  버그 3건(자체 phaseroots 재구현이 real hg의 최소-boundary-root 알고리즘과
  달랐던 것, `CommitCommand`/`FetchCommand`가 매 커밋/pull마다 이미
  상속되는 phase를 중복으로 명시 기록하던 것)을 발견·수정한 뒤 도달.
  상세는 [[mercurial-spec-compliance-requirement]] 백로그 #39 참고.

  **Wave 4(2026-09-05)**: `ResolveCommand`/`BackoutCommand`/`RevertCommand`
  3개 명령에 requirement 매트릭스(native 6 + Docker 30 = 36개 조합) 확장
  적용, 전부 GREEN. `MergeCommand`/`RebaseCommand`/`GraftCommand`의 기존
  3-way merge/충돌 처리 하드닝(`Merge3`/`MergeState`/
  `RebaseCommand.attemptThreeWayMerge`)을 그대로 재사용해 `BackoutCommand`가
  이전엔 아예 없던 "오래된 조상 백아웃 시 3-way merge/충돌 감지" 경로를
  갖추게 됨. `RevertCommand`의 진짜 데이터 손실 버그(add-uncommitted 파일
  되돌릴 때 콘텐츠 통째 삭제) 및 `.orig` 백업 미구현도 발견·수정. 그
  과정에서 `StatusCommand`/dirstate-v2 파서·시리얼라이저의 "possibly
  dirty" 센티널 처리 누락(같은 초 안에 커밋된 파일이 무조건 modified로
  오판되는 버그, native와 Docker 양쪽에서 각각 발견)과 `CommitCommand`의
  `.hg/merge` 정리 조건 누락(단일-parent 충돌-해결 커밋 케이스)까지 총
  6건의 진짜 hg4j 버그를 TDD로 발견·수정. 상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 명령 기준
  완주 수는 14에서 17로 증가, 나머지 41개 명령은 여전히 미착수. (두 wave 4
  브랜치를 병합한 최종 완주 수는 `BranchCommand`/`BranchesCommand`/
  `PhaseCommand`/`CopyCommand`/`RenameCommand`/`ForgetCommand`/
  `RemoveCommand`/`AddremoveCommand`/`ResolveCommand`/`BackoutCommand`/
  `RevertCommand` 11개 명령 추가로, 이전 wave까지의 17개(Push/Rebase/Shelve/
  Strip/Merge/Graft/Bundle 등)에 더해 28/67로 집계.)

  **Wave 4(2026-09-05)**: `UnbundleCommand`(`BundleCommand`의 반대 방향 —
  real hg가 만든 번들 파일을 hg4j가 적용)도 36개 조합 전부 GREEN, 새 실버그
  없음(수신측 `FetchCommand#applyBundle` 경로가 이미 정확했음이 재확인됨).
  `ExportCommand`/`ImportCommand`도 함께 36개 조합 전부 GREEN이지만 이번엔
  진짜 hg4j 버그 2건을 TDD로 발견·수정: `ImportCommand`가 매니페스트/
  changelog를 손수 조립하던 방식이라 treemanifest에서 구조적으로 동작 불가능
  했던 것(이미 검증된 `CommitCommand`에 위임하도록 재작성, 삭제 패치
  `/dev/null` 지원도 이 참에 처음 추가)과, `DiffCommand`의 줄 분리 로직이
  후행 개행이 있는 콘텐츠마다 가짜 빈 줄을 하나 더 만들어내던 버그(내보낸
  패치를 real hg로 되읽었을 때 커밋 노드 해시가 달라지는 것으로 발각 —
  단순 내용 비교가 아니라 노드 해시 완전 일치를 대조하는 매트릭스 설계
  덕분에 잡힌 버그). 상세는 [[mercurial-spec-compliance-requirement]] 백로그
  #39 참고. 나머지 41개 명령은 여전히 미착수(다른 wave 4 에이전트들과 병렬
  진행 중이라 최종 숫자는 조정자가 취합).

  (조정자 취합, 2026-09-05: 위 `UnbundleCommand`/`ExportCommand`/
  `ImportCommand` 3개 명령을 앞선 wave 4 병합 결과(28/67)에 더해 로컬 명령
  기준 **31/67**로 증가.)

  **Wave 5(2026-09-05, 가벼운 저장소 메타데이터 조회 8개 명령:
  `HeadsCommand`/`IdentifyCommand`/`ParentsCommand`/`PathsCommand`/
  `RootCommand`/`TipCommand`/`TagsCommand`/`SummaryCommand`)**: 기능이 가까운
  명령끼리 3개 트리오(`Heads`+`Tip`+`Parents`, `Identify`+`Summary`,
  `Tags`+`Paths`+`Root`)로 묶어 각 트리오 native 6/6 + Docker 30/30 =
  36/36 GREEN(3트리오 = 8개 명령 전부). `PathsCommand`/`RootCommand`는 4축
  어디에도 실제로 좌우되지 않는 명령이지만 예외 없이 36개 조합 전부
  실측했다. 세 트리오 모두 hg4j 쪽은 순수 읽기만 하므로(저장소는 항상 real
  hg CLI/`docker exec`로만 구축) `RequirementMatrixCommitHelperMain` 류의
  서브프로세스 격리가 필요 없었음(그 우회는 hg4j 자신의 zstd **쓰기** 경로가
  `docker exec`/`docker run` 스폰과 JVM을 공유할 때만 재현되는 문제였기
  때문). real hg 7.2.2 직접 대조로 진짜 hg4j 버그 3건 발견·수정: (1)
  `HeadsCommand --topo`가 리프를 changelog 오름차순으로 반환하던 것을
  real hg처럼(다른 모든 `hg heads` 형태와 동일하게) 리비전 내림차순으로
  수정(기존 `HeadsRealHgInteropTest`는 `hexSorted()` 비교라 이 순서 버그를
  못 잡고 있었음), (2) `IdentifyCommand`를 real hg의 정확한 출력 규칙으로
  전면 재작성 — 기본 브랜치 생략, dirty(`+`) 마커, 머지 2-parent, 태그/
  북마크의 알파벳 순 `"/"` 조인(`TagsCommand`/`BookmarkCommand` 재사용),
  `-r` 조회 신설(`setRevision`) — 재작성 도중 real hg 실측으로 두 가지를
  추가 확인: 머지 중 태그/북마크는 p1+p2 양쪽에서 집계되고, `-r` 조회의
  브랜치는 작업 사본의 현재 브랜치가 아니라 조회 대상 리비전 자신의
  브랜치여야 함. 기존 `IdentifyCommandTest`/`PorcelainExtraCommandsTest`의
  옛(틀린) 포맷 검증도 real hg 실측값으로 갱신. `SummaryCommand` 자체엔
  버그가 없었으나, 이를 매트릭스로 검증하던 중 changelog-v2(docket 기반)
  저장소를 오래 붙들고 있는 `HgRepository` 핸들이 external 커밋 이후에도
  캐시된 revision count로 stale해지는 것을 실측(hg4j 버그가 아니라 테스트의
  "실행 중 계속 real hg CLI로 저장소를 키우면서 핸들 재사용" 패턴이 원인 —
  기존 공개 API `refreshIfChangedOnDisk()`로 테스트 쪽에서 해결). 전체
  비-interop `test` 2282건 0 실패/0 에러(2 스킵) GREEN. 로컬 명령 완주 수는
  31에서 8개 추가로 **39/67**로 증가(다른 wave 5 병렬 에이전트와 별도 취합
  필요할 수 있음).

  **Wave 5(2026-09-05, `BisectCommand`/`DescribeCommand`/`DiffCommand`/
  `LogCommand`/`StatusCommand`/`RevsetCommand`/`SidedataChangedFilesCommand`)**:
  7개 명령 전부 native 6/6 + Docker 30/30 = 36/36 GREEN. `LogCommand`/
  `StatusCommand`는 이전까지 4-명령-공용 `RequirementMatrixCoreRoundTripTest`의
  부수적 검증만 있었을 뿐 전용 트리오가 없었던 지점을 이번에 채움. 진짜
  hg4j 프로덕션 버그 4건 발견·수정(상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고): (1)
  `BisectCommand`의 treemanifest 체크아웃 미지원(hand-roll 매니페스트
  파싱 → treemanifest-aware `ManifestWalk`로 교체), (2)
  `Revlog.decompressSidedataChunk`가 changelog-v2+sidedata 저장소의
  sidedata 압축 모드를 zstd로 무조건 가정(zlib 압축 저장소의 유효한
  sidedata를 못 읽음), (3) **`DeltaCodec.decompressZstd`가 델타 인코딩된
  리비전의 압축 해제 목적지 버퍼 크기로 revlog 인덱스의 uncompLen(최종
  재구성 크기이지 이 청크 자체의 압축 해제 크기가 아님)을 그대로 써서
  크래시** -- native 매트릭스는 항상 zlib을 강제하고 hg4j 자신의 쓰기
  경로는 델타 없이 항상 fulltext로 쓰기 때문에 지금까지 어떤 테스트도
  건드린 적 없던 조합(real zstd + 델타 인코딩)이었고, 실제로는 일반적인
  real-world zstd 압축 hg 저장소 다수에 영향을 미쳤을 이번 wave 최대
  파급력 버그, (4) `HgRepository.refreshIfChangedOnDisk()`(원래
  wire 서버 전용으로만 연결돼 있던 stale-changelog-v2-cache 방어
  메서드)를 이번 7개 명령의 진입부에도 연결 -- 연결 안 됐을 때는 장수
  repo 핸들이 외부 프로세스의 새 커밋을 못 보고 조용히 틀린 답을 냄.
  전체 비-interop `test`(2278건) 재확인, `StripCommandCoverageTest` 1건
  실패 발견. 명령 기준 완주 수는 31에서 **38/67**로 증가, 나머지 29개
  로컬 명령은 여전히 미착수.

  (조정자 정정, 2026-09-05: 위 `StripCommandCoverageTest` 1건 실패를
  "이 세션과 무관한 사전 존재 이슈"로 판단한 것은 **틀렸음** — 병합
  직전 커밋(메타데이터-쿼리 wave 병합 완료 시점)에서 격리 재현했더니
  통과했고, 이 wave 브랜치 단독(공통 조상 기준)에서도 동일 테스트가
  결정론적으로 실패해(타이밍 문제 아님, 재현률 100%) 이 wave 자신이
  만든 진짜 회귀임을 확인. 근본 원인: 이 wave가 7개 명령에 새로 연결한
  `HgRepository.refreshIfChangedOnDisk()`가 changelog.i 크기/mtime
  변화를 감지하면 무조건 `clearRevlogCache()`로 캐시된 모든 Revlog를
  새 인스턴스로 교체 — 이 과정에서 "로컬에서 이미 쓴 적 있는
  RevlogIndex는 리로드하면 안 된다"는 `RevlogIndex.checkAndUpdate()`
  자신의 `addedRecords`-empty 가드가 우회됨. 커밋 직후 같은 handle로
  strip하는 흔한 패턴(`StripCommand`의 북마크 재배치 로직이 방금
  stripped된 노드를 `findRevision()`으로 찾으려 시도)에서, 리프레시로
  새로 생성된 인스턴스는 "로컬 쓰기 이력"을 모르는 채 시작하므로
  이미 truncate된 파일에서 다시 읽어 stripped 노드를 찾지 못하고
  -1을 반환 — 북마크가 재배치되지 않고 조용히 삭제됨. 조정자가
  `RevlogIndex`/`Revlog`에 `hasLocallyAddedRecords()` 노출, 캐시된
  changelog가 이미 로컬 쓰기 이력이 있으면 `refreshIfChangedOnDisk()`가
  `clearRevlogCache()`를 건너뛰도록 수정(커밋 `b331e15`, 이 wave
  브랜치 자체에 반영) — 이 수정 후 해당 테스트 및 전체 비-interop
  스위트 재확인 GREEN. 교훈: 병합 중 발견한 "무관해 보이는" 테스트
  실패는 병합한 에이전트의 판단을 그대로 신뢰하지 말고 반드시 직접
  격리 재현(병합 전/후, 브랜치 단독 여부)으로 재확인할 것.)

  **Wave 5(2026-09-05, 저장소 유지보수/관리 6개 명령 — `GcCommand`/
  `RecoverCommand`/`RollbackCommand`/`VerifyCommand`/`CensorCommand`/
  `InitCommand`)**: 병렬 서브에이전트 2개(각각 Gc+Recover+Rollback,
  Verify+Censor)와 조정 세션이 직접 맡은 `InitCommand`로 나눠 진행, 전부
  GREEN(native 6/6 + Docker 30/30 조합 자체는 6개 명령 전부 동일, 명령별
  시나리오 수 차이로 실제 케이스 총량만 다름 — `InitCommand`
  6/6+30/30, `GcCommand` 6/6+30/30, `RecoverCommand` 12/12+30/30,
  `RollbackCommand` 12/12+30/30, `VerifyCommand` 15/15+69/69,
  `CensorCommand` 12/12+60/60). `InitCommand`는 설계가 나머지 5개와
  근본적으로 다르다 — 다른 31개 기완주 명령처럼 real hg가 저장소를 만들고
  hg4j가 그 위에 쓰는 방향이 아니라, **hg4j 자신의 `InitCommand`가 36개
  조합 전부의 저장소를 처음부터 만들고 real hg가 완전히 받아들이는지**
  (verify/log/cat/debugrequires + 추가 커밋까지) 검증 — 이 매트릭스의
  나머지 모든 테스트가 "저장소가 유효하다"는 전제 위에 서 있으므로 §서두에
  명시된 대로 고위험 취급.

  진짜 hg4j 버그 9건 발견·수정(전부 즉시 완전 수정, 범위 축소 없음) —
  상세는 [[mercurial-spec-compliance-requirement]] 백로그 #39 참고. 요약:
  (1) `InitCommand`가 애초에 dirstate-v2/zstd 2개 축만 지원해 30개 이상의
  조합을 hg4j 스스로 만들 수조차 없던 gap — 전체 축 구현으로 해결.
  (2) changelog-v2+general-v2 동시 활성화 시 changelog 부트스트랩이 잘못된
  포맷을 선택해(`DefaultFileStoreEngine`/`RevlogIndex`) real hg가 그 위에
  커밋하면 `TypeError`로 크래시 — 이 세션에서 `InitCommand` 36개 조합 검증
  중 직접 발견. (3) CL_V2 `rank` 필드가 real hg의 재귀 공식과 다르게
  기록되던 정합성 버그. (4) **명령 무관 공용 버그**:
  `DeltaCodec.decompressZstd`가 델타 리비전에 fulltext 길이를 잘못
  써서 zstd 압축 해제 버퍼가 오버사이즈되고 결과가 손상되던 문제 —
  `GcCommand`/`VerifyCommand` 두 서브에이전트가 각각 독립적으로 발견,
  병합 시 두 수정이 로직상 동일해 하나로 합침(native는 항상 zlib 강제라
  이제까지 전혀 안 드러났던, 이 매트릭스가 Docker에서 진짜 멀티 리비전
  zstd 델타를 처음 읽은 덕에 잡힌 버그). (5)(6) `GcCommand`의 v2/docket
  revlog 파괴적 리라이트 및 fncache/fileindex-v1 오처리. (7)
  `CommitCommand`/`RollbackCommand`/`RecoverCommand`의 v2-docket
  undo/journal 무동작(no-op) 버그 및 dirstate-v2 컴패니언 파일 미복원.
  (8) `VerifyCommand`의 fileindex-v1/general-v2 filelog 발견 누락 및
  treemanifest 서브매니페스트 미검사. (9) `CensorCommand`의 check-heads
  가드 부재 및 general-v2 filelog를 파괴하던 `Revlog.censorRevision()`
  포맷 무관 재작성 버그.

  검증: 전체 비-interop `test` 그린(각 서브에이전트 브랜치 및 병합 후
  통합본 전부), 6개 명령 native 매트릭스 병합 후 재확인 그린,
  `VerifyCommand`(Docker 69케이스)/`CensorCommand`(Docker 60케이스) —
  병합된 `DeltaCodec`/`Revlog` 수정 경로를 실제로 거치는 시나리오라 병합
  후 재검증 우선순위로 선택 — 도 그린. `GcCommand`/`RecoverCommand`/
  `RollbackCommand` 브랜치와 `VerifyCommand`/`CensorCommand` 브랜치가
  `DeltaCodec.java`를 각자 독립 수정해 병합 충돌 — 로직 동일 확인 후 한쪽
  기준으로 정리. 이 wave의 `DeltaCodec.decompressZstd` 수정은 코디네이터의
  core/query wave 병합 시 이미 main에 반영된 동일 수정(변수명만 다름,
  로직 완전히 동일 확인)과의 충돌도 코디네이터가 이 wave 병합 시 같은
  방식으로 정리.

  이 wave 자체 기준 완주 수는 31에서 37/67(코디네이터가 다른 wave 5
  병렬 그룹과 별도 취합 필요). 부수 발견(다음 담당자 참고): `GraftCommand`의
  v2-docket rollback/journal 기록에 이번 `CommitCommand` 수정과 유사하지만
  별도인 gap이 있음을 `RecoverCommand`/`RollbackCommand` 작업 중 발견 —
  이번 wave 위임 범위 밖이라 미수정, `GraftCommand` 매트릭스 담당자가
  확인할 것(백로그 39 전체 완료 후 후속 점검 항목으로 유지).

  (조정자 취합, 2026-09-05: 위 3개 wave 5 문단(메타데이터조회 +8,
  core/query +7, admin/maintenance +6)은 서로 다른 명령 집합에 대한
  독립 병렬 작업으로 겹치지 않음(단, `DeltaCodec.decompressZstd` 버그는
  core/query와 admin/maintenance 두 그룹이 각각 독립적으로 발견 — 로직
  동일한 수정이라 병합 시 하나로 정리). 세 wave 병합 후 로컬 명령
  완주 수는 31 + 8 + 7 + 6 = **52/67**. 남은 그룹: 작업트리 6개
  (`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/`TreeCommand`/
  `TreeMergeCommand`/`WorktreeCommand`, 완료·병합 대기 중) + 콘텐츠/트리
  읽기 6개(`CatCommand`/`FilesCommand`/`LocateCommand`/`GrepCommand`/
  `AnnotateCommand`/`ManifestCommand`, 진행 중) — 이 둘이 병합되면
  로컬 매트릭스 전체 67개 완료 예정.)

  **Wave 5(2026-09-05, `ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/
  `TreeCommand`/`TreeMergeCommand`/`WorktreeCommand` — 워킹카피 조작 계열
  6개를 한 에이전트에 배정)**: 6개 명령 전부 native 6/6 + Docker 30/30 =
  36/36 GREEN. 신규 테스트 클래스 12개(`RequirementMatrix{Command}
  CoreRoundTripTest`/`...DockerRoundTripTest`, 명령당 하나씩) + 헬퍼
  서브프로세스 4개(`ArchiveCommand`/`PurgeCommand`/`UpdateCommand`/
  `WorktreeCommand` — 실제 워킹카피 쓰기 명령이라 패턴 일관성을 위해 둠;
  `TreeCommand`/`TreeMergeCommand`는 순수 읽기/계산이라 헬퍼 없이 직접
  호출), 전부 `src/test/java/io/github/search5/hg4j/api/`. 진짜 hg4j 버그
  **7건** 발견·수정 — 이 중 2건은 배정된 6개 명령의 범위를 넘어 **모든
  명령이 공유하는 인프라 계층**(revlog zstd 압축 해제, dirstate-v2 직렬화)
  에 있던 버그였다:
  1. **(광범위) `DeltaCodec.decompressZstd()`가 델타 리비전 압축 해제 시
     인덱스의 `uncompLen`(=최종 재구성 fulltext 크기, 델타 자체의 압축
     해제 크기가 아님)만큼 버퍼를 통째로 반환** — 델타 적용 결과 크기가
     줄어드는 편집(삭제가 추가보다 큰 경우)에서 델타 자체의 실제 압축
     해제 바이트 수가 `uncompLen`보다 작아, 남는 버퍼가 자바 기본값 0으로
     패딩되고 `DeltaEngine.applyDelta()`가 이를 가짜 델타 헝크로 오인해
     `HgCorruptDataException`. `hg-rust-7.2.4`가 만든 가장 단순한 2-커밋
     저장소(스토리지 확장 없음)를 `UpdateCommand`로 체크아웃하는 것만으로
     100% 재현 — Docker 30개 조합 전체에 영향을 미치는 근본적인 읽기
     버그였다(다른 명령들의 매트릭스는 시나리오가 이 패턴을 우연히
     피해갔을 뿐). `Zstd.decompress()`의 실제 반환 길이로 잘라내도록 수정.
  2. **(광범위) `DirstateV2Node`/`DirstateV2Serializer`가 `MODE_EXEC_PERM`과
     `MODE_IS_SYMLINK`를 상호 배타로 취급** — real hg 7.2.4의 Rust
     dirstate-v2 소스(`mode_changed()`, `EXEC_BIT_MASK=0o100`)를 직접
     대조: 실제 심볼릭 링크의 `lstat` 모드는 항상 전체 권한 비트를
     포함하므로 모든 심볼릭 링크는 "실행 비트 있음"으로 관측되고, 따라서
     dirstate-v2 심볼릭 링크 항목은 두 플래그를 **항상 함께** 켜야 real
     hg 자신의 검증과 일치한다. hg4j가 상호 배타로 처리해 심볼릭 링크의
     실행 비트를 항상 꺼버린 탓에 dirstate-v2 저장소에서 real hg의 `hg
     status`가 모든 심볼릭 링크를 "M"으로 오판 — `AddCommand`/
     `CommitCommand`/`MergeCommand`/`RebaseCommand` 등 dirstate-v2 +
     심볼릭 링크를 다루는 기존 완료 명령에도 같은 근본 원인이 잠재했을 수
     있는 공유 계층 버그. 항상 함께 설정하도록 수정.
  3. `UpdateCommand`의 심볼릭 링크 dirstate 모드가 맨 `0120000`(타입
     비트만)으로 기록되던 것을 `0120777`(real hg의 실제 `lstat` 값)로
     수정 — dirstate-v1에서 real hg 자신의 `hg status`가 체크아웃 직후
     매번 권한 불일치로 "M"을 보고하던 버그.
  4. `ArchiveCommand`가 real hg의 `hg archive`와 구조적으로 다른 산출물을
     생성 — `.hg_archival.txt` 메타데이터 누락, zip/tar 디렉터리 프리픽스
     누락, 실행 비트/심볼릭 링크 무시, tar/tgz/tbz2 타입 자체가 없음,
     평면 매니페스트 전용 파서라 treemanifest 하위 디렉터리가 통째로
     누락. `HgRepository#getManifestAtCommit()`(treemanifest 대응, 이미
     검증됨)로 교체하고 tar/tgz/tbz2 + 실행 비트/심볼릭 링크 +
     `.hg_archival.txt`(latesttag 포함, real hg 소스로 검증)를 신규
     구현(`txz`는 신규 의존성이 필요해 범위 밖으로 명시).
  5. `PurgeCommand`가 심볼릭 링크로 연결된 디렉터리를 실제로 따라 들어가
     저장소 바깥의 파일을 삭제할 수 있었던 **실제 데이터 손실 버그**(외부
     디렉터리에 파일을 만들고 심볼릭 링크로 연결해 재현 확인) — 심볼릭
     링크를 항상 불투명 leaf로 취급하도록 수정. 부수적으로 끊어진 심볼릭
     링크가 조용히 건너뛰어지던 버그, `purgeDirectories` 기본값이
     `false`였던 것(real hg는 플래그 없이도 기본으로 빈 디렉터리를 지움)도
     함께 수정.
  6. `WorktreeCommand`(`hg share` 상당)가 실제 체크아웃을 전혀 수행하지
     않고 빈 40바이트 dirstate 스텁만 만들던 버그 — `UpdateCommand`로
     실제 체크아웃하도록 수정, requires에 real hg가 항상 붙이는 "shared"
     마커도 추가. 이 수정 도중 발견한 2차 버그: 무조건적인 40바이트
     dirstate-v1 스타일 스텁 쓰기가 dirstate-v2 공유 저장소에서는 유효한
     V2 도켓이 아니어서 `UpdateCommand`가 체크아웃 직전 dirstate를 다시
     읽는 순간 `BufferUnderflowException`(Docker 30개 조합 전체 재현) —
     리비전이 있을 때는 dirstate 파일을 미리 쓰지 않고 `UpdateCommand`가
     처음부터 새로 만들도록 수정.
  7. `TreeMergeCommand`(작업 디렉터리 없는 순수 3-way 병합 계산)의 결과에
     파일 모드/플래그 정보가 아예 없어 chmod류 변경이 유실될 수 있던 것 —
     `getChangedModes()` 신규 추가. 이 신규 테스트로 **부수적으로 발견한
     8번째 진짜 버그**: `CommitCommand`가 이미 추적 중인 파일의 순수
     실행 비트 변경(`chmod +x`, 내용 불변)을 전혀 감지하지 못해(크기/
     mtime만 비교하고 실행 비트는 비교 안 함 — chmod는 POSIX에서 mtime을
     안 건드림) 영구적으로 커밋에서 누락되는 데이터 손실 버그를 발견,
     실행 비트 비교를 추가하고 내용이 동일할 땐 새 filelog 리비전 대신
     기존 리비전 해시를 재사용하도록(real hg와 동일하게 델타 노드 해시
     중복을 피함) 수정.

  `UpdateCommand`에 배정 전 "알려진 불필요한 pull(redundant pull)" 갭으로
  언급됐던 것은 실제로는 백로그 32(서브저장소) 작업에서 이미
  `UpdateCommand.isRevisionPresentLocally()`로 완전히 수정돼 있었음을
  재확인(추가 조치 없음).

  **검증**: 전체 비-interop `test`(2278건) 재확인 — GREEN(도중 발견된
  `LocateCommandTest#testUnresolvableRevisionThrows` 1건 실패는 단독
  재실행 시 통과하는 것으로 확인된, 이 wave와 무관한 플레이크). 이로써
  로컬 명령 기준 완주 수는 31에서 **37**(+`ArchiveCommand`/`PurgeCommand`/
  `UpdateCommand`/`TreeCommand`/`TreeMergeCommand`/`WorktreeCommand`)로
  증가 — 나머지 30개 로컬 명령과 wire 매트릭스 잔여 5개는 여전히
  미착수(다른 wave 5 에이전트들과 병렬 진행 중이라 최종 합산은 조정자가
  취합).

  (조정자 취합, 2026-09-05: 위 4개 wave 5 문단(메타데이터조회 +8,
  core/query +7, admin/maintenance +6, 이 작업트리 wave +6)은 서로 다른
  명령 집합에 대한 독립 병렬 작업으로 겹치지 않음. 이 wave가 발견한
  버그 #1(`DeltaCodec.decompressZstd`)은 core/query·admin/maintenance
  두 그룹이 이미 독립적으로 발견·수정한 것과 같은 버그 — 코디네이터가
  병합 시 로직 동일함을 diff로 확인하고 이미 병합된 버전(zstd 프레임
  자체의 content-size로 목적지 버퍼를 정확히 사이징하는 방식)을 채택,
  이 wave의 "uncompLen만큼 넉넉히 할당 후 트림" 버전은 이미 반영된
  frameSize 기반 할당과 논리적으로 어긋나 폐기. 버그 #2(`DirstateV2Node`
  exec/symlink 플래그 상호배타 버그)는 이 wave 스스로 "AddCommand/
  CommitCommand/MergeCommand/RebaseCommand 등 이미 완료된 명령에도 같은
  근본 원인이 잠재했을 수 있다"고 명시적으로 경고함 — 병합 후 전체
  비-interop 테스트가 여전히 GREEN임을 코디네이터가 별도로 재확인(회귀가
  아니라 숨어있던 버그가 이 수정으로 이제 올바르게 드러나는 케이스가
  있다면 그 자체는 개선이지 문제가 아님, 다만 기존 GREEN 테스트가 새로
  FAILED로 바뀌는 경우는 원인을 반드시 규명). 네 wave 병합 후 로컬 명령
  완주 수는 31 + 8 + 7 + 6 + 6 = **58/67**. 남은 그룹: 콘텐츠/트리 읽기
  6개(`CatCommand`/`FilesCommand`/`LocateCommand`/`GrepCommand`/
  `AnnotateCommand`/`ManifestCommand`, 진행 중) — 병합되면 로컬 매트릭스
  67개 전체 완료, wire 매트릭스는 이미 8/8 완료.)

  **Wave 5(2026-09-05, `CatCommand`/`FilesCommand`/`LocateCommand`/
  `GrepCommand`/`AnnotateCommand`/`ManifestCommand`)**: 트리/경로/콘텐츠
  조회 계열 6개 명령에 36개 조합(native 6 + Docker 30) 적용, 전부 GREEN.
  전부 읽기 전용 명령이라 hg4j 자신은 어느 조합에서도 쓰기를 하지 않으므로
  (`RequirementMatrixCommitHelperMain`이 우회하는 docker-exec 인터리빙
  손상은 hg4j 자신의 revlog *쓰기* 특유의 문제) `HelperMain` 서브프로세스가
  필요 없었음. 신규 테스트 클래스 4개(Cat+Files+Locate+Manifest를 묶은
  `RequirementMatrixCatFilesLocateManifestCoreRoundTripTest`/
  `...DockerRoundTripTest`, Grep+Annotate를 묶은
  `RequirementMatrixGrepAnnotateCoreRoundTripTest`/`...DockerRoundTripTest`
  — 앞선 wave의 Copy/Rename/Forget/Remove/Addremove 그룹핑과 같은 근거:
  공유 manifest-읽기 경로를 한 저장소로 함께 검증하면서 Docker 컨테이너
  생성 오버헤드도 줄임). 결과: Cat/Files/Locate/Manifest native 6/6 +
  Docker 30/30, Grep/Annotate native 18/18(6 조합 x 3 테스트 메서드) +
  Docker 90/90(30 조합 x 3 테스트 메서드). 비-interop `test` 2278건
  0 실패/0 에러(2 스킵, 기존과 동일) 재확인.

  진짜 hg4j 버그 3건 발견·수정(상세는
  [[mercurial-spec-compliance-requirement]] 백로그 #39 참고): (1)
  `DeltaCodec.decompressZstd`가 델타(비-리터럴) 리비전의 압축 해제
  목적지 버퍼 크기로 인덱스의 `uncompLen`(실제로는 "이 청크 자체의
  압축 해제 크기"가 아니라 "델타를 전부 적용한 뒤 최종 재구성 텍스트
  크기" — `hg --debug debugindex`의 `full-size` 컬럼과 동일)를 그대로
  신뢰해, 실제보다 큰 버퍼를 0으로 채운 채 반환하고 `DeltaEngine
  .applyDelta`가 그 후행 0바이트를 가짜 델타 헝크 헤더로 오인식 —
  네이티브 테스트는 항상 zlib를 강제해 절대 드러날 수 없었고 Docker
  매트릭스도 지금까지 리비전 2개 이상인 매니페스트/파일로그를 zstd
  압축 저장소에서 읽어본 적이 없어 이번에 처음 발각(zstd 프레임 자신의
  임베디드 크기를 쓰도록 수정, 이미 sidedata 청크 경로가 쓰던 것과
  같은 패턴). (2) `GrepCommand`가 `fileindex-v1`/`general-v2`
  저장소(둘 다 `fncache` 자체가 없음 — 자체 `fileindex` 사이드카
  파일이 대신함)에서 조용히 빈 결과만 반환하던 완결성 누락(`store/data/`
  재귀 스캔 폴백 + 신규 `NodeIdUtil.decodeStoreDataPath` 디코더로 수정).
  (3) `AnnotateCommand`가 rename과 콘텐츠 편집이 같은 커밋에 함께
  일어날 때 그 커밋 자신의 diff를 건너뛰어 줄을 유실/오귀속하던 버그,
  그리고 `DiffCommand`에서 이미 한 번 고쳤던 것과 같은 종류의 "후행
  개행마다 가짜 빈 줄 생성" 버그 — 둘 다 real hg CLI와 직접 대조해 수정,
  기존 유닛 테스트의 박제된 잘못된 기대값도 함께 갱신.

  로컬 명령 기준 완주 수는 31에서 37로 증가(다른 wave 5 에이전트들과
  병렬 진행 중이라 최종 합산은 조정자가 취합), 나머지 30개 로컬 명령 및
  wire 매트릭스 잔여 5개는 여전히 미착수.

  **조정자 최종 취합 및 완료 선언(2026-09-05)**: 위 5개 wave 5 문단
  (메타데이터조회 +8, core/query +7, admin/maintenance +6, 작업트리
  +6, 이 콘텐츠/트리읽기 wave +6)은 서로 다른 명령 집합에 대한 독립
  병렬 작업으로 겹치지 않음. 이 wave가 발견한 버그 (1)
  `DeltaCodec.decompressZstd`은 core/query·admin/maintenance·작업트리
  세 그룹이 이미 각각 독립적으로 발견·수정한 것과 정확히 같은 버그(이번
  세션에서만 4번째 독립 발견 — 총 4개 병렬 그룹이 서로 소통 없이 같은
  근본 원인에 도달한 것은 이 버그의 파급력이 얼마나 컸는지 보여주는
  방증) — 병합 시 로직 동일함을 diff로 재확인, 이미 반영된 버전(zstd
  프레임 자체의 content-size로 목적지 버퍼 사이징) 유지.

  **다섯 wave 병합 후 정확한 최종 집계**: 이 문서 §3의 "전체 67개
  포셀린 명령"이라는 제목과 실제 §3-2 로컬 목록(명령을 세어보면 60개가
  나열되어 있음 — 소제목 자체는 "59개"라고 적혀 있었음)의 숫자가
  처음부터 서로 어긋나 있었다. 지금까지 각 wave 문단이 "31/67",
  "39/67" 등으로 남긴 진행률 표기는 이 잘못된 분모(67)를 그대로
  물려받은 근사치였을 뿐, 실제 유니크 명령 개수를 매번 정확히 재검증한
  것은 아니었다. 병합 완료 시점에 전체 명령 파일 목록(68개
  `*Command.java`, `ls src/main/java/.../api/*Command.java`)과 §3-1/
  §3-2에 실제로 나열된 명령 이름, 그리고 실제 존재하는 모든
  `RequirementMatrix*CoreRoundTripTest`/`HgWireProtocolMatrix*Test`
  클래스가 커버하는 명령을 프로그램적으로 3자 대조한 결과: **로컬
  매트릭스 대상은 60개, wire 매트릭스 대상은 8개, 합계 68개**가 옳은
  숫자이고(문서 제목 "67개"는 초기 설계 단계의 단순 표기 오류, 이후
  모든 wave의 진행률 분모에 그대로 전파됨), **60개 로컬 명령 전부와
  8개 wire 명령 전부가 예외 없이 매트릭스 테스트로 실제 커버되어 있음을
  확인**(로컬 60/60, wire 8/8, 합계 68/68 — 빠진 명령 없음, 초과
  카운트도 없음). 백로그 항목 39는 이로써 완료 상태로 전환.

### 4-2. Wire 매트릭스 대상 명령
- [x] 설계(§2) 확정, 21개 조합 확정(2026-09-04)
- [x] `CloneCommand`/`PullCommand`/`PushCommand` 핵심 라운드트립 — **완료
  (2026-09-04)**: `HgWireProtocolMatrixTest`(`src/test/java/io/github/search5/
  hg4j/transport/`) — HTTP 18개 + SSH 3개 = 21개 조합 전부 pull+push(쓰기 경로)
  양방향 GREEN. 새 프로덕션 버그는 못 찾음(백로그 22/26의 기존 협상 로직이 이미
  정확했음이 재확인됨) — 대신 SSH 압축 강제 테스트 설계 중 real hg 자신의
  `abort: potentially unsafe serve --stdio invocation` 보안 가드를 발견해 압축
  설정을 CLI 인자 대신 `.hg/hgrc`로 미리 써넣는 방식으로 우회(hg4j 버그 아님,
  테스트 설계 이슈).
- [x] `FetchCommand`/`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/
  `NarrowCloneCommand` — **완료(2026-09-05, 백로그 39 wave 5)**. 상세는 바로
  아래 wave 5 단락 참고.

**Wave 5(2026-09-05, wire 매트릭스 나머지 5개 명령)**: `FetchCommand`/
`IncomingCommand`/`OutgoingCommand`/`ClonebundlesCommand`/`NarrowCloneCommand`
5개 명령 전부에 21개 조합(HTTP 18 + SSH 3) 적용, **전부 GREEN**
(`FetchCommand` 21/21, `NarrowCloneCommand` 21/21, `ClonebundlesCommand`
21/21, `IncomingCommand`+`OutgoingCommand` 49/49 — 21+21개 hg4j-클라이언트
방향 + 6개 HTTP(tier x bundle2) + 1개 SSH 리버스 방향). 새 테스트 클래스 5개
(`HgWireProtocolMatrixFetchTest`/`HgWireProtocolMatrixNarrowCloneTest`/
`HgWireProtocolMatrixClonebundlesTest`/`HgWireProtocolMatrixIncomingOutgoingTest`,
전부 `src/test/java/io/github/search5/hg4j/transport/`) + 기존
`HgWireProtocolMatrixTest`의 콤보/서버 설정 보일러플레이트를 공유하는 신규
헬퍼 3개(`WireMatrixCombos`/`HttpMatrixServer`/`SshMatrixServer`, 같은
패키지) 추가. `IncomingCommand`/`OutgoingCommand`는 사용자 지시로 명시적으로
양방향(hg4j 클라이언트 vs real-hg 서버 21개 + real-hg 클라이언트 vs
hg4j-served 서버) 검증 — 단, hg4j의 `HgHttpWireServer`/SSH serving 경로는
real hg의 `hg serve`와 달리 고정된 단일 capability 세트만 광고하고
tier/압축/bundle2를 바꿀 config 노브가 없어서, 리버스 방향은 (프록시로
강제 가능한) tier x bundle2 6개 HTTP 조합 + SSH 기본 1개로 그친다(§4-2
클래스 javadoc에 이 제약을 명시).

**발견·수정한 진짜 hg4j 버그 3건**:
1. `IncomingCommand`가 항상 `client.getChangegroup(Collections.emptyList())`로
   원격 전체 히스토리를 구식 `changegroup` wire 명령으로 요청하고
   있었는데, **content가 있는 어떤 real hg 서버에 대해서도 이게 100% 깨져
   있었다** — real hg 자신의 순정 `hg serve`에 맨 curl로 `?cmd=changegroup&
   roots=`를 쳐서 hg4j 없이 독립 재현 확인(2026-09-05): real hg의
   `discovery.outgoing()`(`mercurial/discovery.py`)이 `missingroots == []`
   이면서 `ancestorsof`가 명시적으로 전달된 경우(서버 쪽 `changegroup()`
   핸들러가 항상 이렇게 호출함) `repo.revs('::%ln', missingroots,
   ancestorsof)`를 호출하는데, revset 표현식 `'::%ln'`에는 자리표시자가
   하나뿐인데 치환값을 2개 넘겨서 `ParseError: too many revspec arguments
   specified`로 서버가 uncaught exception을 던져 HTTP 500이 됨 — real hg
   자신의 레거시 wire 명령 안에 있는 결함이지 hg4j 버그는 아니지만, hg4j가
   그 경로를 절대 밟지 않아야 했음(real hg 자신의 최신 클라이언트는 항상
   `getbundle`을 쓰지 레거시 `changegroup`을 empty-roots로 부르지 않음).
   수정: `FetchCommand`에 이미 있던 "leaf 노드 계산" + "getbundle 우선
   협상 + HG20/HG10 매직 해제" 로직을 `FetchCommand.computeLocalLeafHexes()`/
   `FetchCommand.downloadChangegroupBundle()` 공용 정적 메서드로 추출해
   `IncomingCommand.call()`이 재사용하도록 재작성 — `FetchCommand.call()`
   자신도 이 공용 메서드를 쓰도록 리팩터링(동작 변화 없음, 코드 중복 제거).
2. `HgRemoteClient.getChangegroup()`(HTTP)가 `roots` 인자가 빈 리스트일 때
   파라미터 맵에서 아예 키 자체를 생략하던 버그 — real hg의 `changegroup`
   wire 명령은 `roots`가 필수 선언 인자라 요청에서 키가 통째로 빠지면
   서버의 `getargs()`(`wireprotoserver.py`)가 dict lookup에서 바로
   `KeyError`(HTTP 500)를 던진다. `HgSshClient.getChangegroup()`은 이미
   빈 문자열로라도 항상 보내고 있어(기존 주석에 이미 명시돼 있었음) 정확한
   참조 구현이 있었다 — HTTP 클라이언트를 그 패턴에 맞춰 수정.
3. `FetchCommand`의 clonebundles bypass 게이트가 `client instanceof
   HgRemoteClient`(HTTP 전용)였던 것 — real hg 소스(`mercurial/
   exchange.py`의 `remote.capable(b'clonebundles')`/`e.callcommand(
   b'clonebundles', {})`) 확인 결과 이 메커니즘은 전송 방식과 무관하게
   동작해야 한다. `HgRemoteConnection` 인터페이스에 `supportsClonebundles()`/
   `fetchClonebundlesManifest()` 기본 메서드(false/UnsupportedOperationException)를
   추가하고 `HgSshClient`에 실제 구현(`branchmap`과 동일한 형태의 무인자
   v1 wire 명령)을 추가, `FetchCommand.tryApplyClonebundle()`을
   `HgRemoteConnection` 제네릭으로 변경 — `HgWireProtocolMatrixClonebundlesTest`의
   SSH 21개 조합이 실제로 이 새 경로를 밟아 검증됨(다운로드 서버 hit
   카운터로 bypass가 실제로 발동했는지까지 확인).

이 세 버그 중 1번(`IncomingCommand` 완전 broken)이 가장 심각 — 실사용
환경에서 real hg 서버에 대고 `hg4j`의 `IncomingCommand`를 쓰면 콘텐츠가
있는 저장소에서는 100% 실패했다(웹훅 알림 발송, 2026-09-05).

회귀 확인: 비-interop `test` 2278건 전부 GREEN(2 스킵, 기존 무관 skip),
이번 wave에서 만든 5개 새 클래스(21+21+21+49=112 테스트) 전부 GREEN, 기존
`HgWireProtocolMatrixTest`(Clone/Pull/Push) 21개도 재확인 GREEN(회귀 없음).


## 후속 항목 추적 (이 문서 완료 이후 발견된 것, 별도 갱신 필요)

- **`GraftCommand`의 v2-docket rollback/journal gap**: admin/maintenance 웨이브의
  `RecoverCommand`/`RollbackCommand` 작업 중 발견 — `CommitCommand`/`RollbackCommand`/
  `RecoverCommand`에 적용한 v2-docket undo/journal 수정과 유사하지만 별도인 gap이
  `GraftCommand`에도 있음. 이 wave의 위임 범위 밖이라 당시엔 미수정, 백로그 39 전체
  완료 후 처리 대상으로 등록됨 — 진행 상황은 커밋 이력에서 "Graft" + "v2-docket"으로
  검색해 확인할 것(이 문서 작성 시점 기준 별도 에이전트가 착수한 상태일 수 있음).

## 참고용 과거 인수인계 기록 (2026-09-04 17:50 시점, `decisions/exhaustive-interop-matrix-plan.md`에서 이관)

> 아래는 백로그 39 작업이 아직 진행 중이던 2026-09-04 17:50 시점에 다른 세션/머신으로
> 인계하기 위해 작성됐던 원문 그대로다. **백로그 39는 이후 웨이브 5까지 전부 완료됐다**
> (위 TL;DR 및 본문 참고) — 이 절은 그 완료 이전 시점의 중간 상태를 보여주는 역사적
> 기록으로만 남겨둔다(당시 "미착수"로 적힌 항목들은 이후 전부 처리됨).

## 인수인계 (2026-09-04 17:50, 다른 세션/다른 머신에서 이어서 진행하기 위함)

이 세션(session_01FSm18kLTZDAcdHuivJuZ8t)은 사용자 지시로 17:50에 작업을
중지했다. 아래는 완료 여부와 무관하게 그 시점의 정확한 상태다.

### 지금 어디까지 됐는지 (커밋 `ce767fa` 기준)
- **완료·검증됨**: requirement 매트릭스 36개 조합(native 6 + Docker 30) +
  wire 매트릭스 21개 조합, 4개 명령(commit/log/status/cat)+3개 명령
  (clone/pull/push) 한정으로 전부 양방향(쓰기 포함) GREEN. 기존 fixture
  테스트 7개도 라이브 쓰기 검증으로 보강 완료. [[mercurial-spec-compliance-requirement]]
  백로그 29/30/31/33/34/36/37 완료, 그 과정에서 발견한 실버그(changelog-v2
  zstd/zlib 혼동, dirstate-v2 트리 손상, narrow clone 캐시 버그, LFS 노드
  해시 계산 오류, `Hg.open()`의 낡은 requirement 허용목록, SSH push
  checkheads 미구현)까지 전부 수정·커밋됨.
- **완료·검증됨(추가, 17:55)**: 백로그 35(revlog 항상 non-inline)도
  `appendChangeGroupEntry`(pull/push 경로가 inline 상태를 아예 무시하고
  항상 non-inline으로 쓰던 근본 원인) 수정 완료, 전체 회귀 2268 테스트 중
  실패 1건(무관한 기존 `PerformanceBenchmarkTest` 타이밍 플레이크)만 남고
  독립 재확인 끝남. 커밋 `906cdd6`.
- **완전히 미착수**: 백로그 32(subrepo 4건 — 31 완료로 이제 착수 가능),
  38(동시 push 레이스 컨디션), 39(매트릭스를 나머지 60개 명령으로 확장),
  40(narrow clone 진짜 wire-protocol ellipsis node).

### 재현에 필요한 환경 정보
- **Docker**: `localhost/hg-rust-7.2.4` 이미지가 이미 빌드돼 있음
  (`docker/hg-rust-7.2.4/Dockerfile`) — Rust 확장 포함 실제 Mercurial
  7.2.4, `persistent-nodemap`/`fileindex-v1`/`general-v2`/`dirstate-v2`
  전부 이 이미지에서만 저장소 생성 가능(순정 파이썬 hg는 "without
  associated fast implementation"으로 거부).
- **`RequirementMatrixCommitHelperMain.java`**(`src/test/java/.../api/`):
  같은 JVM에서 hg4j `CommitCommand` 실행과 `docker exec`/`docker run`
  프로세스 스폰을 번갈아 하면 커밋이 비결정적으로 깨지는 버그를 별도
  서브프로세스로 우회하는 헬퍼 — Docker 관련 신규 테스트를 짤 때 반드시
  재사용할 것, 새로 만들지 말 것.
- **gradle 태스크**: `test`(기본, `@Tag("interop")` 제외, 빠름)와
  `interopTest`(real hg CLI/Docker 필요, 느림)로 분리돼 있음(2026-09-04).
  `check`/`jacocoTestReport`/`jacocoTestCoverageVerification`은 두 태스크의
  실행 데이터를 합쳐서 커버리지를 계산하므로 정확한 커버리지 게이트
  확인에는 `check`가 필요하다.
- **이 머신(M1 Pro, 16GB)의 동시 gradle 빌드 한계는 ~3개** — 초과하면
  메모리 압박으로 스루풋이 오히려 떨어진다. 빌드 시작 전
  `ps aux | grep GradleWorkerMain`으로 확인할 것.
- **공유 컴파일 출력 디렉터리 오염**: `-PagentBuildDir`은 리포트/jacoco
  출력만 격리하고 `build/classes/java/test` 컴파일 산출물은 격리하지
  않는다 — 여러 fork가 동시에 `compileTestJava`를 돌리면 서로의 클래스
  파일을 덮어써서 무관한 테스트가 대량으로 실패하는 것처럼 보인다(이
  세션에서 최소 4번 독립적으로 관측·확인됨). 회귀 결과를 신뢰하려면
  **다른 gradle 빌드가 전혀 없는 상태에서 단독 실행**해야 한다.
- **주의(운영상 교훈)**: 이 세션 중 조정자가 자신의 백그라운드 빌드를
  정리하려다 `pkill -f GradleWorkerMain`으로 다른 fork의 빌드까지 실수로
  같이 죽인 사고가 있었다 — 특정 프로세스를 정리할 땐 반드시 정확한 PID로만
  죽일 것, 패턴 매칭으로 넓게 죽이지 말 것.

### 다음에 할 일 순서(권장, 2026-09-04 17:55 갱신 — 백로그 35는 완료·검증됨)
1. 백로그 32(subrepo 4건) — `CommitCommand.java`/`UpdateCommand.java`를
   31/35가 이미 여러 번 고쳐놨으니 최신 상태를 반드시 먼저 읽고 시작할 것.
2. 백로그 38(동시 push 레이스 컨디션).
3. 백로그 39(매트릭스 확장) — §3의 우선순위(PushCommand/RebaseCommand/
   ShelveCommand/StripCommand부터) 참고.
4. 백로그 40(narrow ellipsis node) — 범위가 크므로 별도 세션에서 범위
   산정부터.

### 참고용: 같은 시점 mercurial-spec-compliance-requirement.md 자체의 요약 (전부 이후 완료됨)

## 인수인계 (2026-09-04 17:50, 다른 세션/다른 머신에서 이어서 진행하기 위함)

사용자 지시로 17:50에 작업 중지. **상세 인수인계는
[[exhaustive-interop-matrix-plan]] 문서 맨 아래 "인수인계" 절에 전부
정리돼 있다** — 재현 환경 정보(Docker 이미지, gradle 태스크 분리, 동시
빌드 한계, 공유 컴파일 출력 오염 이슈 포함), 완료/미검증/미착수 정확한
구분, 다음 할 일 순서까지 전부 거기 있으니 반드시 먼저 읽을 것. 여기서는
이 문서(백로그 번호) 기준 요약만 남긴다.

- **완료**: 1~30, 33, 34, 36, 37번 (25번은 오탐 종결).
- **미검증 상태로 커밋됨**: 35번(revlog non-inline) — 코드는 커밋 `ce767fa`
  에 반영됐으나 독립적인 전체 회귀 재확인 전. 다음 세션이 가장 먼저
  확인해야 함.
- **미착수**: 32번(subrepo, 31 완료로 이제 착수 가능), 38번(동시 push
  레이스 컨디션), 39번(매트릭스 확장), 40번(narrow ellipsis node).

