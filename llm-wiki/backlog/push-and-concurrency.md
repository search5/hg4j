---
updated: 2026-09-05
status: completed
---

# 백로그 13, 33, 38: Push 정확성과 동시성

관련 항목: 13(`PushCommand` 증분 push 누락 — 실제 원인은 `RevlogIndex.checkAndUpdate()`의
디스크 재확인 스로틀), 33(`PushCommand`의 checkheads 안전장치가 SSH에서 미작동),
38(동시 push 레이스 컨디션 — real hg와 동일한 lock timeout + 재검증 동작 구현).

## 원문
13. ~~**`PushCommand`의 증분(2회차) push가 최신 커밋을 누락함**~~ — ✅ **완료(2026-09-02)**.
    TDD로 원인 추적 결과, `PushCommand` 자체에는 버그가 없었다 — 진짜 원인은
    `RevlogIndex.checkAndUpdate()`의 **디스크 재확인 200ms 스로틀**이었다.
    `PushCommandTest`의 원격 저장소 핸들은 읽기 전용(자기 자신은 changelog에 한 번도
    안 씀)인데, 첫 push 직후의 첫 읽기가 스로틀 창을 열어버려서, 두 번째 push 직후의
    두 번째 읽기(빠른 테스트라 밀리초 단위로 붙어있음)가 스로틀에 걸려 조용히
    스킵되고 스테일(push2 이전) 리비전 카운트를 그대로 반환했다 — 디스크 자체는
    항상 정확했고 인프로세스 캐시만 뒤쳐진 것. 스로틀을 완전히 제거(`idxFile.length()`
    는 단순 stat() 한 번, `PerformanceBenchmarkTest`의 2초 SLA에 여유 충분).
    단, `addedRecords.isEmpty()` 게이트는 그대로 남겨둬야 했다 — 처음엔 이것도
    제거했더니 `StripCommand`/`RebaseCommand`/`HisteditCommand`가 깨졌다(이들은
    `RandomAccessFile#setLength`로 revlog `.i`/`.d`를 직접 truncate한 뒤 **같은**
    Revlog 참조를 계속 써서, 방금 strip한 리비전이 북마크가 가리키던 대상인지
    `findRevision()`으로 확인하는데, 여기서 자동 리로드가 걸리면 방금 truncate된
    파일 기준으로 nodeMap이 재구성돼 막 지워진 리비전에 대한 지식을 잃고 북마크를
    이동 대신 삭제해버림). 결론: **한 번이라도 자체적으로 쓴 적 있는 RevlogIndex는
    자기 북키핑을 신뢰(기존 동작 유지)하고, 한 번도 안 쓴(순수 읽기 전용) 핸들만
    스로틀 없이 매번 디스크를 재확인**하도록 정리. `HgRepositoryTest`에 읽기 전용
    핸들 케이스 회귀 테스트 추가. 커밋 `f0b1eff`.
33. ~~**`PushCommand`의 checkheads 안전장치가 SSH에서 미작동**~~ — ✅
    **완료(2026-09-04)**. 근본 원인: `HgRemoteConnection.getBranchHeads()`를
    `HgRemoteClient`(HTTP)만 구현하고 `HgSshClient`는 오버라이드하지 않아
    인터페이스 기본값(`return null`, "branch-unaware 폴백")으로 떨어졌던 것 — SSH로
    push할 때는 브랜치 인식 checkheads 안전장치(다중 head/새 브랜치 push 거부)가
    실질적으로 무력화된 채 topological-only 체크로만 동작하고 있었다. 실제 hg의
    v1 wire 프로토콜 `branchmap` 커맨드(무인자, HTTP `HgRemoteClient`가 이미 쓰던
    것과 동일한 커맨드 — hg4j 서버측 `Wire1Commands.branchmap`/`HgSshWireServer`는
    이미 이 커맨드를 지원하고 있었고, 빠진 건 오직 클라이언트측 호출뿐이었다)를
    `HgSshClient`에 `getHeads()`/`listKeys()`와 동일한 패턴(`sendCommand`+
    `readFramedResponse`, SSH 프로토콜 v2 폴백 포함)으로 신규 구현. 검증: (1)
    `getBranchHeadsReturnsRealHgsBranchMapOverSsh` — 2개 named branch가 있는 real
    hg SSH 서버에서 브랜치별 head hex가 정확히 일치하는지 확인, (2)
    `pushCreatingNewHeadIsRejectedOverSshThenForceSucceeds` — HTTP 쪽 기존
    `testPushRejectedWhenCreatingNewHeadThenForceSucceeds`와 대칭으로, 새 head를
    만드는 SSH push가 `--force` 없이 거부되고(원격 head 개수 불변 확인) `--force`
    로는 성공하는지(원격 head 2개로 증가 확인) real hg SSH 서버(embedded MINA
    SSHD 뒤에서 진짜 `hg serve --stdio` 서브프로세스) 상대로 확인. 둘 다
    `HgSshClientRealHgInteropTest`에 신규 추가, 그 파일 전체(5 테스트) GREEN.
    (참고: 같은 시각 병행 중이던 다른 fork들의 미완료 파일 때문에 전체 회귀에서는
    무관한 `HgCorruptDataException` 계열 실패가 다수 나왔음 — `HgSshClient.java`
    로 스코프를 좁힌 격리 실행에서는 실패 없음, 원인은 공유 컴파일 출력 디렉터리
    오염으로 판단됨.)

38. ~~**동시 push 레이스 컨디션 — real hg와 완전히 동일한 동작 검증 필요**~~ — ✅
    **완료(2026-09-04)**. `PushCommand`(로컬 push 경로)는 이미
    `repository.lockStore()`(`HgLock`, POSIX 원자적 symlink 생성 기반)를 쓰고
    있었지만, **서버 방향**(`Wire1Commands.unbundle` → `HgLocalClient
    .pushWithHooks` → `PullCommand.applyBundle` → `FetchCommand.applyBundle`)이
    실제 동시 요청에서 어떻게 동작하는지는 미검증이었다.

    **real hg 자신의 기준선 확인** (`mercurial/lock.py`/`localrepo.py`/
    `scmutil.py`, 호스트에 설치된 real hg 7.2 소스 직접 확인 + 실측): `repo.lock()`/
    `wlock()`은 기본 `wait=True`로 `ui.timeout`(기본 `"600"`초)만큼 1초 간격으로
    재시도하다 실패하면 `error.LockHeld`를 던지고, `scmutil.callcatch()`가
    `"abort: %s: timed out waiting for lock held by %r\n"`(예:
    `"abort: repository /path: timed out waiting for lock held by 'host:pid'"`)
    형태로 출력한다 — real `hg serve`의 store lock을 인위적으로(가짜
    `host:pid` symlink) 잡아두고 `ui.timeout=2`로 설정한 뒤 real hg CLI로 push해
    실측: 정확히 ~2초 대기 후 실패했고(`wireprotov1server.unbundle()`은
    `error.LockHeld`/`LockUnavailable`을 따로 잡지 않아 클라이언트에는 그냥
    `"abort: HTTP Error 500: Internal Server Error"`로만 보임), 이후 `hg verify`로
    저장소가 전혀 손상되지 않았음을 확인.

    **hg4j 서버 방향의 실제(수정 전) 동작**: `HgRepository.lockStore()`가 항상
    `new HgLock(file, 0, true)`(즉시 실패, 대기 없음)로 고정돼 있어, push 적용
    경로가 lock을 못 따면 real hg처럼 대기하지 않고 그 자리에서 즉시
    `HgLockException`을 던졌다 — real hg의 "대기 후 타임아웃" 형태와 불일치.

    **구현**: (1) `HgRepository`에 `lockStore(int timeoutMs)`/
    `lockWorkingCopy(int timeoutMs)` 오버로드 신설(기존 무인자 버전은 `timeoutMs=0`
    위임으로 완전히 하위 호환 유지 — commit/update/rebase 등 다른 모든 호출부는
    그대로 즉시-실패); `resolvePushLockTimeoutMs()` 신설, real hg의 `ui.timeout`과
    동일한 개념으로 저장소 자체 hgrc의 `[ui] timeout`(기본 `"600"`초, hg4j 쪽
    기본 600000ms)을 읽어 push/unbundle 경로에서만 사용. (2) `FetchCommand
    .applyBundle`/`PullCommand.applyBundle`에 `lockTimeoutMs` 오버로드 추가.
    (3) `HgLocalClient.pushWithHooks`(HTTP·SSH·file:// 세 방향 모두가 공유하는
    서버측 unbundle 적용 지점)가 `remoteRepo.resolvePushLockTimeoutMs()`를 이
    오버로드에 전달 — real hg의 대기·타임아웃 SHAPE을 그대로 재현. (4)
    `PushCommand`(클라이언트측 자신의 로컬 저장소 lock)도 동일하게 대기하도록
    변경 — real hg `exchange.py push()`가 소스 저장소도 `repo.lock()`(wait=True
    기본)으로 잡는 것과 동일(규칙 2: read/write, client/server 양쪽 다 처리).
    (5) `HgLock`의 타임아웃 메시지에 `"-- timed out waiting for lock after
    <ms>ms"` 접미사 추가(대기 후 실패임을 real hg 메시지 SHAPE에 맞춰 구분;
    기존 "Could not acquire..."/"Currently held by..." 부분 문자열은 보존해
    기존 테스트 전부 그대로 통과).

    **구현 중 발견한 별도 버그(수정 완료)**: `HgRepository.lockStore(int)`/
    `lockWorkingCopy(int)`가 (기존 무인자 버전과 마찬가지로) `synchronized`
    인스턴스 메서드였던 것이, 대기 로직이 없던 시절엔 무해했지만 실제 대기가
    생기자 **자기 자신을 교착시키는 버그**로 즉시 드러남: 한 스레드가 lock을
    기다리며 `synchronized` 메서드 안에서 최대 timeoutMs만큼 머무르면, 그동안
    같은 `HgRepository` 인스턴스의 다른 `synchronized` 메서드는 (그 lock과
    무관하더라도) 전부 블록된다 — 실측(2026-09-04): 진짜 동시에 겹치는 두
    real-hg push를 hg4j HTTP 서버에 쏘자, 이긴 쪽 스레드가 진 쪽의 전체 대기
    시간(30초) 동안 자신과 무관한 `synchronized` 호출에 멈춰 30초 넘게
    걸림 — 두 요청 모두 정상 완료되긴 했지만 실질적으로 "동시성"이 전혀
    없었던 셈. `lockStore`/`lockStore(int)`/`lockWorkingCopy`/
    `lockWorkingCopy(int)` 전부에서 `synchronized`를 제거해 해결(상호배제는
    이미 `HgLock` 자신의 static, path-keyed `JVM_ACTIVE_LOCKS` + 원자적 파일
    시스템 symlink 생성만으로 충분히 보장되고 있었음 — `HgRepository`
    인스턴스 모니터는 애초에 불필요했던 것으로 확인). 수정 후 같은 동시 push
    테스트가 30초대에서 1.8초로 단축.

    **검증**: 신규 `PushLockRaceRealHgInteropTest`(`@Tag("interop")`) 5건, 전부
    real hg CLI를 push 클라이언트로 사용, HTTP·SSH 양쪽 전송 모두 커버 —
    (1)/(3) `httpServerWaitsOutContendedStoreLockThenAcceptsRealHgPush`/
    `sshServer...`: store lock을 배경 스레드로 1.5초 잡아둔 뒤 real hg push,
    push가 실제로 대기했다가(elapsed ≥ hold 시간) 성공하고 커밋이 정확히
    반영됨을 확인. (2)/(4) `httpServerAbortsRealHgPushAfterConfiguredTimeout
    WhenStoreLockHeldTooLong`/`sshServer...`: `ui.timeout=1`초로 설정한 채 lock을
    5초간 잡아둠 — real hg push가 정확히 설정된 타임아웃 근처(대기 전체
    5초가 아니라)에서 실패하고, 실패한 push는 아무것도 반영되지 않았으며
    (`hg verify` 클린, revision count 불변), 이어지는 정상 push는 여전히
    성공함(저장소가 막히지 않음)을 확인. (5)
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository`: 두 개의
    독립된 real hg CLI 프로세스를 `CountDownLatch`로 동시에 풀어 진짜 겹치는
    push 레이스를 유발 — 결과와 무관하게(둘 다 성공하거나 하나만 성공 — 과제
    자체가 명시한 두 결과 모두 허용) `hg verify`로 저장소가 절대 손상되지
    않았음과 revision count가 실제 성공 건수와 정확히 일치함(부분/중복 적용
    없음)을 확인.

    **후속 확장(2026-09-04, 같은 날 사용자 지시로 범위 내 편입)**: 위
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository` 테스트가
    실측한 대로, 최초 구현은 lock 대기/타임아웃만 real hg와 맞췄을 뿐 real hg의
    `PushRaced`(`mercurial/error.py`)에 해당하는 **독립적인 서버측 재검증**이
    없어서 두 real-hg 클라이언트가 같은 오래된 head를 보고 각자 다른 자식
    커밋을 동시에 push하면 hg4j 서버가 **양쪽 다 적용**해(2개 head 생성) real
    hg 서버라면 거부했을 시나리오를 놓치고 있었다 — 사용자가 이를 "범위 밖"이
    아니라 이번 항목에 포함해 즉시 구현하도록 지시. real hg 소스 확인
    (`mercurial/bundle2_part_handlers.py`의 `handlecheckheads()`,
    `mercurial/exchange.py`의 `_pushb2ctxcheckheads()`/`check_heads()`) 결과:
    real hg 클라이언트는 `--force`가 아니고 실제로 push할 게 있으면 bundle2
    봉투에 `check:heads` 파트(클라이언트가 push를 계산할 때 본 원격 head 목록)를
    changegroup 파트보다 먼저 넣어 보내고, 서버는 이 파트를 **lock을 실제로
    획득한 뒤**(트랜잭션 내부) 처리하면서 `sorted(heads) != sorted(op.repo.
    heads())`면 `error.PushRaced("remote repository changed while pushing -
    please try again")`를 던진다 — legacy(non-bundle2) 경로는 대신 `unbundle`
    명령 자체의 `heads=` 인자를 (lock 이전에) 검사하며, `--force`는
    `heads=[b'force']`(단, `wireprototypes.encodelist()`가 이것도 그냥
    `hex()`로 인코딩하므로 실제 wire 값은 `"666f726365"`— `hex(b'force')` —
    이지 리터럴 단어 "force"가 아님, 실측: 리터럴 "force"를 real hg 서버에
    보내면 서버의 `decodelist()`가 hex 디코드에 실패해 깨짐)로 체크를 완전히
    생략한다.

    구현: (1) `Bundle2Parser.ExtractedBundle2`에 `checkHeadsRaw` 필드 추가,
    `check:heads` 파트의 원시 20바이트 head 목록을 파싱. (2)
    `HgPushRacedException`(`HgValidationException` 상속) 신설. (3)
    `FetchCommand.applyBundle`/`PullCommand.applyBundle`에 `PostLockValidator`
    콜백 오버로드 추가 — store/wlock을 실제로 잡은 직후, 아무것도 쓰기 전에
    실행되어(따라서 실패해도 journal 등 부분 상태가 전혀 남지 않음) real hg의
    "lock 획득 후 재검증" 타이밍을 그대로 재현. (4)
    `HgLocalClient.buildPushRaceValidator()`가 (a) bundle2 `check:heads` 파트가
    있으면 그걸, (b) 없으면 legacy `heads=` wire 인자(단, 비어있거나 force
    센티널이면 스킵)를 써서 검증기를 만들고, `pushWithHooks`가 lock-timeout과
    함께 이를 `applyBundle`에 전달 — HTTP·SSH·file:// 세 방향 모두가 공유하는
    지점이라 한 번의 구현으로 전부 커버됨. (5) `NodeIdUtil
    .computeUnbundleHeadsWireValue()`의 force 분기를 real hg와 동일하게
    `hex(b'force')`로 wire 인코딩하도록 수정(기존엔 리터럴 "force"를 그대로
    보내 real hg 서버와의 실제 --force 왕복이 깨져 있었음 — 이전에는
    `PushCommand`가 force 시에도 항상 진짜 head 목록을 보내 이 분기 자체가
    한 번도 실행된 적이 없었던 잠재 버그, 이번에 처음 실사용되며 발견·수정);
    서버측(`HgLocalClient`)은 리터럴 "force"와 hex 인코딩 두 형태를 모두
    force로 인식(전자는 wire 인코딩을 안 거치는 `file://` 로컬 피어 경로용).
    (6) `PushCommand.call()`이 `--force`일 때 실제 head 목록 대신
    `["force"]` 센티널을 보내도록 수정(real hg의 `_pushchangeset`:
    `if pushop.force: remoteheads = [b'force']`) — 이게 없으면 강제 push
    자체가 새 레이스 체크에 걸려 스스로 거부당할 위험이 있었음.

    검증: `PushLockRaceRealHgInteropTest`에 5건 추가(총 10건) —
    `twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository`를
    "성공 개수 ≥1"에서 "정확히 1개만 성공, 진 쪽은 real hg와 동일한
    'changed while pushing'/'try again' 계열 메시지로 거부, 최종 head
    1개"로 강화, SSH 버전(`twoRealHgClientsRacingOverSsh...`) 신규 추가,
    "레이스가 아닌 경우 과잉 거부 안 함" 네거티브 컨트롤 2건
    (`sequentialNonRacingRealHgPushesBothSucceedOverHttp` — 순차 push는 둘 다
    성공, `concurrentNoOpPushDoesNotTripRaceCheckAlongsideARealPushOverHttp` —
    보낼 게 없는 push는 애초에 `check:heads` 파트 자체가 없어 레이스 체크
    대상이 아님을 확인). 구현 중 기존 테스트 6건이 새로 깨졌다가 전부
    원인 규명 후 수정: `PushCommandTest` 2건(`QuirkyLocalConnection`이 일부러
    null/전부-0 sentinel을 head 목록에 섞어 보내는 방어 코드 테스트 — 레이스
    체크가 그 쓰레기 값에 NPE, `HgLocalClient`에 null/all-zero 필터링 추가로
    해결), `Wire1CommandsTest`/`HgSshWireServerTest` 4건(빈 저장소로의 push를
    검증하면서 `heads=` wire 인자에 "들어오는 커밋 자신의 hex"라는(이 인자가
    당시 죽은 코드였을 때는 무해했던) 의미 없는 값을 넣어뒀던 테스트 픽스처
    — 실제로 이 값이 쓰이게 되자 "헤드 없음"이어야 할 빈 저장소 대상 push가
    스스로 레이스로 오탐되어 실패 — 픽스처를 "heads 인자 생략/빈 값"으로
    수정해 원래 테스트 의도 보존), `HgSshClientRealHgInteropTest`/
    `PushRealHgInteropTest`의 force 관련 2건(위 (5)번 wire 인코딩 버그 — real
    hg 서버가 리터럴 "force"를 받고 깨짐). 전체 회귀 최종: 비-interop
    `test` 2269건 0 실패/0 에러(2 스킵), `interopTest` 239건 중 이 항목과
    무관한 기존 `StripRealHgInteropTest` 사전 존재 실패 2건(수정 전
    베이스라인에서도 동일 재현 확인)을 제외하고 전부 GREEN.

