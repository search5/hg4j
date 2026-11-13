---
updated: 2026-11-13
status: current
---

# 로드맵: hg4j 서버 백엔드의 푸시 병목 줄이기 (에이전트 병렬 사용 대비)

> **상태: 제안(미착수)**. 아래 3절의 호환 조건과 5절의 결정 게이트를 사용자가 승인하기 전에는 코드를 바꾸지 않는다.
> 이 문서의 줄 번호는 `main` `5855845`(2026-11-10), hg는 7.2.3 소스 기준이다. 줄 번호는 바뀌므로 인용 전에 다시 확인한다.
> 표기: **[확인]** 코드나 공식 글을 직접 읽음 / **[추정]** 읽은 것에서 해석함 / **[미확인]** 읽지 못함.

## 1. 배경과 목표

AI 에이전트가 저장소에 동시에 많이 푸시·클론하면 Git 서버 쪽이 병목이 된다는 보고가 나왔다(출처는 7절). 같은 부하가 hg4j 서버(`HgHttpWireServer`, `HgSshWireServer`)에 오면 어디가 막히는지 정리하고, **기존 `hg` 동작과 디스크 포맷을 깨지 않으면서 서버 백엔드에서만** 줄일 수 있는 것을 순서대로 적는다.

목표는 "서로 다른 브랜치로의 푸시를 병렬로 쓰기"가 아니다(이유는 2절). 목표는 다음 셋이다.

1. 락을 잡고 있는 시간(임계 구역)을 줄인다.
2. 락 획득과 `fsync` 비용을 여러 푸시가 나눠 낸다.
3. 브랜치가 달라서 생기는 불필요한 경합 실패를 없앤다.

## 2. 왜 브랜치별 병렬 쓰기는 안 되는가

- **[확인]** 푸시는 `wlock`과 store `lock`을 한 번에 잡고 적용, pretxn 훅, 실패 시 롤백까지 한 구간에서 처리한다(`HgLocalClient.java:679-680`, bundle2 경로는 `Bundle2Push.java:85`). 롤백 중에 다른 푸시가 끼어들면 그 푸시까지 되돌려진다는 이유로 락을 풀지 않는다고 코드 주석에 적혀 있다.
- **[추정]** hg는 커밋마다 changelog와 manifest 하나에 이어 붙이는 구조라 브랜치가 달라도 쓰기 순서가 하나로 정해진다. 포맷을 바꾸면 실제 `hg`가 저장소를 읽지 못한다.

따라서 아래 `분리 저장소(샤딩)`와 `브랜치별 changelog`는 **하지 않는다**(8절).

## 3. 호환 조건 (모든 단계가 지켜야 함)

1. 디스크 포맷을 바꾸지 않는다.
2. `.hg/wlock`과 `.hg/store/lock` 파일, 그 순서를 유지한다. 실제 `hg`가 같은 저장소를 건드려도 상호 배제가 유지되어야 한다.
3. wire 응답과 훅 의미를 유지한다. 푸시마다 pre·pretxn·post 훅이 따로 돌고, 한 푸시의 실패가 다른 푸시에 영향을 주지 않는다.
4. 푸시를 승인(응답)하기 전에 데이터가 `fsync`로 디스크에 내려가 있어야 한다.

## 4. 현황 (확인한 사실)

### 4.1 hg 7.2.3 (비교 기준)

| 항목 | 내용 | 위치 |
|---|---|---|
| 락 대기 | 1초 간격 폴링, 기본 `ui.timeout` 600초 | `lock.py:282-296`, `configitems.toml:2919` |
| 오래된 락 | 같은 호스트에서 pid가 죽었을 때만 자동으로 깸 | `lock.py:357-372` |
| 락 획득 시 | 캐시 무효화(`acquirefn=self.invalidate`) | `localrepo.py:2837-2859` |
| 서버 unbundle | `lock`을 잡고, `bookmarks-in-store`가 아니면 `wlock`도 잡음 | `exchange.py:2855-2858` |
| 늦은 락 | `experimental.bundle2lazylocking` 옵션이 있음(기본은 즉시 잡기) | `exchange.py:2818` 부근 |
| 명령마다 프로세스 | 에이전트가 `hg`를 반복 호출하면 Python 기동 비용이 누적됨. 완화책은 `chg`와 `rhg` | `contrib/chg`, `rust/rhg` |
| `rhg` 범위 | `status`, `files`, `cat`, `annotate`, `config`, `root`, `purge` 등 읽기 위주. `commit`·`log`·`diff`·`pull`은 없음 | `rust/rhg/src/commands` |

**[추정]** `hg share`로 store를 공유하면 store 락이 공유되어 쓰기가 직렬화된다(`localrepo.py:1120`의 `sharedpath`). 에이전트마다 별도 클론이면 간섭이 없다.

### 4.2 hg4j (`main` `5855845`)

| 항목 | 내용 | 위치 |
|---|---|---|
| 성격 | 순수 Java 클라이언트 라이브러리. `HgHttpWireServer`(서블릿), `HgSshWireServer`도 있음 | `transport/` |
| 락 대기 | `HgLock`이 50ms 간격으로 폴링. 푸시 대기는 `ui.timeout`(기본 600초)로 hg와 같음 | `HgLock.java`, `HgRepository.java:1004-1025` |
| 오래된 락 | 같은 호스트의 죽은 pid만 자동 해제. hg와 같은 한계 | `HgLock.java:167-196` |
| 서버 푸시 락 | `wlock`과 store `lock`을 항상 둘 다 잡음. hg의 `bookmarks-in-store` 분기는 없음 | `Bundle2Push.java:85` |
| bookmarks 저장 위치 | **2026-11-13 이후:** `bookmarksinstore` 요건을 인식하고(`HgRepository#isBookmarksInStore`), 읽기·쓰기를 `getBookmarksFile()`로 모았다. 이전에는 항상 `.hg/bookmarks`만 읽고 써서 이 요건 저장소를 조용히 잘못 다뤘다. 코드 완료, 검증 전([[lock-parity-matrix]]) | `HgRepository.java`, `RepoSupport.java`, `HgLocalClient.java` |
| 이미 구현됨 | bundle2 `check:heads`, `check:updated-heads`, `check:bookmarks`, `check:phases` | `Bundle2Push.java:41-42, 478-492` |
| 번들 입력 | 요청 전체를 `byte[]`로 받은 뒤 락에 들어감 | `HgHttpWireServer.java:293-303` |
| 캐시 | `Revlog` 내용 LRU 100개 고정 | `Revlog.java:51` |
| 내구성 | 쓰기마다 `fsync`를 호출 | `Revlog.java:795-797, 1213`, `SafeFileIO.java:133, 143` |
| stream clone | v1(`stream_out`)과 v2(`stream2` 번들 파트) 둘 다 구현. **생성이 store 락을 쥔 채 모든 파일 내용을 메모리에 읽는다 [확인]**(`lockStore()` 안에서 `Files.readAllBytes`로 `ByteArrayOutputStream`에 쓴 뒤 반환). 256MB 초과는 거부. **hg 원본은 락을 파일 목록과 크기를 모으는 동안만 쥔다**(`streamclone.py` `generatev2`: `with repo.lock():`에서 `_entries_walk`로 목록을 만들고 락을 놓은 뒤 `_emit2`가 데이터를 낸다. `generatev3` 주석도 "holding the lock for a shorter time"). 그래서 hg4j 서버에서는 stream clone 요청 하나가 **푸시와 다른 stream clone 모두를 그 시간만큼 막는다** | `StreamClone.java:126, 207-250, 54`, `StreamCloneClient.java` |
| clonebundles | **서버까지 구현 완료**(백로그 44 완료): `.hg/clonebundles.manifest`가 있으면 `clonebundles`·`clonebundles_manifest` 명령과 capability를 광고. 번들 파일을 만드는 일은 서버 밖(`BundleCommand`, `ShardedStreamBundlesCommand`) | `Wire1Commands.java:140-143, 240-253`, [[backlog/clonebundles]] |
| peer-bundle-cache | `get_cached_bundle_inline`(서버 설정 `server.peer-bundle-cache-root` 디렉터리의 번들을 그대로 내려줌), `heads_buckets`(heads의 버킷 지문), `store_shape` 명령이 있음. **hg 쪽의 "같은 요청에 번들을 다시 만들지 않기" 장치에 해당** | `Wire1Commands.java:258-282, 316, 1070-1072` |
| narrow | **wire 수준 협상까지 구현 완료**(백로그 28/30/31/40/42 완료): `narrow`·`includepats`·`excludepats` 인자, `exp-narrow-1` capability(`experimental.narrow`일 때), 서버 `getbundle`과 클라이언트(HTTP·SSH)의 `NarrowScope`, shape | `Wire1Commands.java:111-119, 788-`, `HgLocalClient.java:198-212`, [[backlog/narrow-clone-and-lfs]] |
| 동적 getbundle | `HgLocalClient#getBundle`이 요청마다 번들을 만든다. **응답 캐시는 없다 [확인]:** `src/main` 전체에서 번들·응답 캐시 클래스를 검색했고, 있는 캐시는 `Revlog` 내용 LRU, `HgLock` 등록부, `Bundle2Pull`이 읽는 `.hg/cache/hgtagsfnodes1` 파일뿐이다 | `Wire1Commands.java:808-834` |

> **정정(2026-11-13):** 이 문서의 이전 판은 clonebundles 서버와 wire narrow를 "미구현"으로 적었다. 오래된 [[matrix-status]](2026-09-06)의 "백로그 40·44 진행 중" 문구를 그대로 옮긴 것이고, 코드와 최신 백로그 문서는 둘 다 완료로 나와 있다. **`matrix-status.md`가 현재 코드와 어긋나 있으니 그 문서를 고쳐야 한다(이 문서에서는 하지 않음).**

**위키가 이미 하는 일:** 잠금 동작을 hg와 일치시키는 작업이 2026-11-04에 완료·대조되어 있다([[concepts/locking-and-concurrency]], [[lock-parity-matrix]], [[backlog/push-and-concurrency]]). 이 로드맵의 A~C는 그 일치를 **내부적으로 일부 벗어나는** 제안이다(5절).

## 5. 결정 게이트 (착수 전 사용자 결정 필요)

잠금 일치는 지금까지 사용자가 요건으로 확정해 온 방향이다. 아래 단계들은 "바깥에서 보이는 동작은 같게, 안쪽의 잠금 범위는 서버에서만 다르게"를 요구한다.

- **게이트 1:** hg4j 서버가 저장소의 **유일한 쓰기 주체**라고 가정해도 되는가? 실제 `hg`가 같은 디렉터리에 직접 쓸 수 있다면 B와 C는 파일 락을 계속 잡는 방식으로만 가능하다(3절 2번).
- **게이트 2:** 서버 모드에서 잠금 범위가 hg와 달라지는 것을 허용하는가? 허용하면 [[lock-parity-matrix]]에 "서버 전용 이탈" 항목을 만들어 근거와 위험을 남긴다.
- **게이트 3 (`bookmarks-in-store`) — 결정됨(2026-11-13, 사용자): (가) 요건을 지원한다.** 지원 범위와 진행 상황은 위 Phase 2의 항목과 [[lock-parity-matrix]]. 요건 지원과 `pushkey`의 `wlock` 분기는 코드까지 끝났고 **테스트는 작성만 하고 실행하지 않았다**. bundle2 `unbundle`의 `wlock` 생략은 의도적 이탈로 남겨 두었고(이유는 같은 표), 사용자 확인을 기다린다.

## 6. 로드맵

### Phase 0 — 계측 (코드 동작 변경 없음)

- 락 대기 시간, 락 보유 시간, 푸시 단계별 소요(수신, 파싱, 해시 검증, 적용, pretxn 훅, `fsync`, 커밋)를 지표로 남긴다.
- **종료 조건:** 동시 푸시 N개에서 락 보유 시간의 구성비를 수치로 얻는다. 이 수치가 Phase 2와 3의 우선순위를 정한다. **2026-11-13에 한 차례 측정했다(6-4절).** 락 안의 시간은 대기나 `fsync`가 아니라 **CPU(인덱스 전체 재적재와 16진 변환)가 가장 큰 후보**로 나왔다. 다만 깨끗한 프로파일은 얻지 못해 시간 비율은 아직 [미확인]이고, 상시 계측(JFR 이벤트)은 여전히 필요하다.

### Phase 1 — 동시성 회귀 하네스 (동작 변경 전에 먼저)

- `interopTest`에 실제 `hg push`를 서로 다른 브랜치로 동시에 N개 보내는 시나리오를 추가한다.
- 결과 저장소를 실제 `hg verify`, `hg log`로 확인한다(가짜 목 서버 금지, [[AGENTS]] 규칙 8).
- 장애 주입: 푸시 도중 `kill -9`한 뒤 `hg recover`와 `hg verify`로 일관성을 확인한다. [[decisions/journaling-crash-recovery-plan]]과 연결한다.
- 실제 `hg` 클라이언트가 `check:updated-heads`를 보내는 조건은 **hg 소스로 확인했다**(아래 시나리오 A 설명). 하네스에서는 그 소스 분석이 실제 실행과 맞는지(서버가 받은 파트 종류)를 한 번 캡처해 확인한다. 이 실행 확인은 interop 보류로 하지 않았다.
- **종료 조건:** 현재 동작에서 하네스가 GREEN이고, 이후 단계마다 같은 하네스로 회귀를 잡는다.

### Phase 2 — 락 밖 준비와 공정 대기열 (낮은 위험)

- **2-A (락 밖으로):** 번들 파싱과 리비전 해시 검증 같은 CPU 작업을 락 밖에서 한다. 락 안에서는 heads 검사, 적용, pretxn 훅, journal만 한다. hg의 `bundle2lazylocking`과 같은 방향이다.
- **2-B (공정 대기열):** 파일 락 앞에 프로세스 안 FIFO 대기열을 둬서 50ms 폴링과 락 해제 후 재경쟁을 없앤다. 파일 락은 그대로 잡으므로 실제 `hg`와도 상호 배제된다.
- **2-C (소규모 개선):** 오래된 락에 TTL 또는 하트비트를 도입해 호스트가 달라도 풀 수 있게 하고(컨테이너·NFS 대비), `Revlog` 캐시 크기를 설정 가능하게 한다.
- **`bookmarks-in-store`와 `wlock` (2026-11-13 진행 상황):** hg는 이 요건일 때 bookmarks를 store(`svfs`)에 두므로 `wlock` 없이 store 락만으로 보호된다(`bookmarks.py:47-48`, `exchange.py:2857`).
  - **완료(코드까지, 컴파일·테스트 미실행):** 요건 지원(파일 위치 11곳, `Hg.open()` 허용 목록, `InitCommand#setBookmarksInStore`, 클론·stream clone·clone bundle 뒤 요건 새로고침, 롤백 백업 파일 위치)과 `pushkey` bookmarks의 `wlock` 분기(`HgLocalClient#pushkey`). 세부는 [[lock-parity-matrix]].
  - **남음 — bundle2 `unbundle`(서버 푸시)의 `wlock` 생략:** 그냥 빼면 안 된다. 푸시 한 건이 `wlock`을 다섯 계층에서 각각 다시 잡고, 실패 되돌리기가 dirstate와 `bookmarks.current`를 쓴다. **6-1절에 Phase 2와 함께 설계했다**(코드 변경 없음).
- **종료 조건:** Phase 1 하네스 GREEN, Phase 0 지표에서 락 보유 시간 감소를 확인.

### Phase 3 — 그룹 커밋 (측정으로 필요가 확인될 때만)

- 대기 중인 푸시 여러 개를 파일 락 한 번으로 연속 적용하고 `fsync`를 묶는다. 승인은 `fsync` 뒤에 보낸다.
- 푸시별 savepoint(각 revlog 파일 길이 기록)로, 훅이 거부하면 그 푸시만 잘라낸다. 지금 롤백은 트랜잭션 전체를 되돌리므로 새로 설계해야 한다.
- **위험:** 배치 중간 크래시에서의 journal 복구, 푸시별 훅 의미 유지, 배치 창만큼 개별 푸시 지연이 늘 수 있음.
- **종료 조건:** 장애 주입에서 `hg recover`와 `hg verify`가 통과하고, 처리량이 Phase 2 대비 개선됨.

### Phase 4 — 클론·fetch 쪽 (푸시와 독립, 병렬 진행 가능)

clonebundles 서버, peer-bundle-cache, stream v2는 **이미 구현되어 있다**(4.2절). 남은 일은 기능 추가가 아니라 **"번들을 누가 언제 만들고 갱신하는가"와 동적 getbundle 경로의 반복 생성 비용**이다. 상세는 6-2절.

### Phase 5 — 장기

- ~~wire 수준 narrow 협상(백로그 40)~~ — 이미 완료(4.2절). 남은 일은 에이전트 사용 시나리오에서의 **측정**(6-2절).
- 공유 객체 스토리지 백엔드. `StoreEngine`을 읽은 결과 기반이 되지 못한다(6-2절 5-B). 권장은 하지 않는 것.
- 서버 푸시 경합 시 클라이언트 재시도 정책 정리(`PushRaced` 동등 동작, 백로그 38과 연결).

## 6-1. 상세 설계: bundle2 `unbundle`의 락 구조와 Phase 2 (2026-11-13, 설계만, 코드 변경 없음)

`bookmarks-in-store`일 때 `wlock`을 생략하는 일(hg와 같은 동작)과 Phase 2-A(락 밖 준비)는 **같은 뼈대**를 필요로 한다. "푸시 한 건에서 누가 어떤 락을 언제 잡는가"를 한 곳에서 정하고 아래로 내려보내는 구조다. 둘을 따로 하면 같은 곳을 두 번 건드린다.

### 현재: 푸시 한 건이 `wlock`을 다섯 계층에서 각각 다시 잡는다 [확인]

| # | 위치 | 잡는 락 |
|---|---|---|
| 1 | `Bundle2Push.java:85` (요청 전체) | `wlock`, store `lock` |
| 2 | `HgLocalClient.pushWithHooks` (`:679-680`) | `wlock`, store `lock` |
| 3 | `PullCommand.applyBundle` (`:257`) | `wlock` |
| 4 | `FetchCommand.applyBundle` (`:882`, 기본 `workingCopyLock=true`, 끄는 `setWorkingCopyLock(false)`는 이미 있음) | `wlock` |
| 5 | 실패 되돌리기 `Bundle2Push.undo` → `RollbackCommand.call` (`:44`) | `wlock`, store `lock` |

재진입(`allowReentrant`)이 켜져 있어 지금은 1번이 이미 `wlock`을 쥐고 있으므로 2~5번이 조용히 통과한다. 1번에서 `wlock`을 빼면 2~5번이 **store 락을 쥔 채 `wlock`을 새로 잡게 된다.** hg는 "항상 `wlock`을 먼저, `lock`을 나중에"를 교착 방지 규칙으로 둔다(`localrepo.py:2953` docstring). 그래서 1번만 고치면 안 된다.

### 목표: 결정은 요청 입구에서 한 번, 아래 계층은 따라가기만

```
Bundle2Push (입구)
  takeWorkingCopyLock = !repo.isBookmarksInStore()      // hg: gettransaction()
  [wlock if takeWorkingCopyLock] → store lock → 스냅샷 → apply → 부가 파트 → (실패 시) 되돌리기
     └ pushWithHooks(..., takeWorkingCopyLock)
          └ PullCommand.applyBundle → FetchCommand.setWorkingCopyLock(takeWorkingCopyLock)
     └ undo(..., takeWorkingCopyLock) → store 파일과 bookmarks 파일만 되돌림 (dirstate, bookmarks.current는 건드리지 않음)
```

불변식 (요건 저장소의 푸시에서):
1. `.hg/dirstate`, `.hg/bookmarks.current`, `.hg/undo.backup.dirstate*`를 만들거나 바꾸지 않는다. 원본의 unbundle이 하지 않는 일이다.
2. 락 획득 순서는 항상 `wlock` → store `lock`이다. 순서를 어기는 획득이 있으면 테스트가 실패한다(아래 S0).
3. 실패하면 이 푸시가 바꾼 것만 정확히 되돌린다. 다른 푸시의 변경이 끼어들 수 없다(같은 락 구간).

요건이 **없는** 저장소의 동작은 한 줄도 바꾸지 않는다. 새 경로는 요건이 있을 때만 켜지므로, 지금까지 이 요건을 지원하지 않던 저장소 외에는 영향이 없다.

### 변경 순서 (각 단계는 기본값이 현재 동작이라 단독으로 넣어도 회귀가 없다)

| 단계 | 내용 | 비고 |
|---|---|---|
| S0 | **락 순서 감시**: `HgLock`이 스레드별 획득 순서를 기록하고 "store 락을 쥔 채 `wlock` 획득"을 감지하는 테스트용 훅. hg의 `devel.check-locks`와 같은 역할. 락 대기·보유 시간 지표(Phase 0)도 여기서 함께 넣는다 | 나머지 단계의 안전망. 이게 없으면 S4의 순서 위반을 자동으로 못 잡는다 |
| S1 | `PullCommand`에 `setWorkingCopyLock(boolean)` 추가, `false`면 `lockWorkingCopy`를 잡지 않고 `FetchCommand.setWorkingCopyLock(false)`로 전달 | 기본 `true` |
| S2 | `RollbackCommand`에 내부용 "트랜잭션 되돌리기" 모드: `wlock`을 잡지 않고 dirstate와 `bookmarks.current`를 건드리지 않음. hg의 `transaction.abort`가 하는 일(저널에 기록된 파일 복원)에 해당 | 공개 `rollback` 명령의 동작(`wlock`+`lock`, dirstate 복원)은 그대로 |
| S3 | `HgLocalClient.pushWithHooks`에 `takeWorkingCopyLock` 인자 추가. 락을 쥐지 않았으면 `pushWithHooksLocked`의 dirstate 백업·복원을 건너뜀 | 기존 시그니처는 `true`로 위임 |
| S4 | `Bundle2Push`가 입구에서 한 번 결정해 위로 전달. `Snapshot`은 `wlock`이 없으면 dirstate를 담지 않음 | 여기서 처음으로 동작이 바뀜(요건 저장소만) |
| S5 | 락 순서 감시를 켠 채 동시 푸시 테스트(실제 hg 클라이언트 사용분은 **사용자 허락 후**) | 아래 검증 참고 |

### Phase 2와의 관계

- **2-A(락 밖 준비)**는 S1~S4가 만드는 "단계마다 어떤 락을 쥐는가" 구분 위에서만 안전하게 할 수 있다. 번들 파싱과 해시 검증을 락 밖으로 빼려면 그 구간이 어떤 락도 쥐지 않는다는 보장이 먼저 있어야 한다. 그래서 순서는 **S0~S4 → 2-A → 2-B → 2-C**다.
- **2-B(공정 대기열)**는 S4의 락 획득 앞에 놓는다. 락 순서를 바꾸지 않으므로 S0의 감시가 그대로 적용된다.
- Phase 3(그룹 커밋)의 푸시별 savepoint는 S2의 "트랜잭션 되돌리기"를 푸시 단위로 쪼갠 것이라 S2의 설계를 먼저 굳혀야 한다.

### 검증 계획 (이번에는 실행하지 않음)

- **단위(실제 hg 불필요):** S0의 순서 감시를 켠 채 요건이 있는/없는 저장소에서 푸시 한 건을 돌리고 (가) 요건 저장소에서 `.hg/dirstate`와 `bookmarks.current`가 생기지 않음, (나) 순서 위반 없음, (다) 실패 주입 시 store 파일과 bookmarks 파일이 푸시 전 바이트와 같음을 확인한다.
- **상호운용(실제 hg 필요):** 실제 `hg push`를 요건 저장소로 동시에 N개 보내고 `hg verify`와 `hg log`로 확인한다. 푸시 도중 `kill -9` 뒤 `hg recover`로 일관성을 확인한다. **사용자가 interop 테스트 실행을 보류했으므로 실행하지 않았다.**

### 위험과 열린 질문 [미확인 포함]

- **[확인 + 남은 위험]** `FetchCommand.applyBundle`이 `wlock` 없이 도는 경로(`setWorkingCopyLock(false)`, 로컬 `unbundle` 명령)는 이미 있고 위키가 hg와 맞췄다고 기록한다([[lock-parity-matrix]]). 푸시 경로의 훅은 `"repository"`(`HgRepository`)를 컨텍스트로 받는다(`HgHook.java:10`). 즉 **사용자 훅이 그 저장소로 무엇이든 할 수 있다**(작업 사본 읽기, `wlock`을 잡는 명령 호출 등). `wlock` 없이 도는 푸시 안에서 훅이 `wlock`을 잡으면 락 순서(`wlock`→`lock`)가 깨진다. hg의 훅도 같은 규칙을 따라야 하므로(트랜잭션 안에서 `wlock`을 새로 잡으면 안 됨) 이를 **S0의 순서 감시 테스트로 잡고 훅 문서에 규칙으로 적는다.** 푸시 경로가 그 밖의 작업 사본 접근을 하는지는 S1~S4 때 코드로 확인한다.
- **[추정, git 이력으로 확인 불가]** S2의 되돌리기가 `bookmarks.current`를 건드리지 않는 것이 맞다. 푸시는 활성 bookmark를 바꾸지 않기 때문이다. `RollbackCommand`의 `bookmarks.current` 삭제가 왜 있는지 `git log -S`로 찾았지만 패키지 이름 변경 커밋(`f397b37`, 2026-09-03)뿐이라 도입 이유는 확인하지 못했다. S2는 푸시용 되돌리기에서만 이를 빼므로 공개 `rollback` 명령에는 영향이 없다.
- **결정 필요:** 요건 저장소에만 새 경로를 켤지(권장, 영향 범위가 가장 작음), 요건이 없는 서버 저장소에도 `wlock` 생략을 확장할지. 후자는 hg와 달라지는 것이라 [[lock-parity-matrix]] 원칙상 별도 확인이 필요하다.

## 6-2. Phase별 상세 설계 (2026-11-13, 설계만, 코드 변경 없음)

공통 전제: **측정 없이 최적화하지 않는다.** Phase 0의 수치가 나오기 전에는 Phase 2-A의 2단계, Phase 3, Phase 4-C의 착수 여부를 정하지 않는다. 임계값은 수치를 본 뒤 사용자와 정한다(여기서 숫자를 지어내지 않는다).

### Phase 0 — 계측

**목적:** 푸시 한 건과 읽기 요청 한 건의 시간이 어디에 쓰이는지, 락 경합이 얼마나 되는지를 코드 변경 없이(동작 불변) 얻는다.

**방식:** 새 의존성 없이 **JFR(Java Flight Recorder) 사용자 이벤트**(`jdk.jfr.Event`)를 쓴다. 기록하지 않을 때 비용이 거의 없고, 켜려면 JVM 옵션만 주면 된다. 이벤트는 다음과 같다.

| 이벤트 | 위치 [확인된 지점] | 필드 |
|---|---|---|
| `hg4j.LockWait` / `hg4j.LockHold` | `HgLock` 획득 루프(`HgLock.java:128-246`)와 `close()`(`:312`) | 락 파일 이름, 대기 ms, 보유 ms, 폴링 횟수, 재진입 여부 |
| `hg4j.PushPhase` | `HgLocalClient#pushWithHooksLocked`의 단계 | 단계 이름, ms, 바이트 |
| `hg4j.GetBundle` | `Wire1Commands.getbundle`(`:808-834`) | 요청 키(공통 head 수, head 수, narrow 여부), ms, 응답 바이트 |
| `hg4j.StreamGenerate` | `StreamClone#streamOut`(`:126`), `StreamClone#stream2`(`:207`) | 락을 쥔 ms(목록 수집 / 데이터 읽기 구분), 파일 수, 바이트. **6-2절 4-A의 락 점유를 수치로 확인하는 용도** |

`PushPhase`의 단계는 이 코드에서 읽은 순서를 그대로 쓴다: 번들 봉투 파싱 → changegroup 파싱 → `prechangegroup` 훅 → `applyBundle`(락 획득 대기 포함) → heads 경합 검사 → journal 기록 → revlog 덧붙이기(해시 검증 포함) → `fsync` → `pretxnchangegroup` 훅 → undo 정보 → 사후 훅 → `clearRevlogCache`.

- **`synchronized` 경합:** `Revlog.appendRevision`·`appendChangeGroupEntry`가 `synchronized`라 JVM 안에서도 같은 `Revlog` 인스턴스를 쓰는 스레드는 직렬화된다(`Revlog.java:1127, 1812`). 서버가 `HgRepository` 하나를 모든 서블릿 스레드가 공유하므로(`HgHttpWireServer.java:40`) 이 경합이 실제로 있는지는 JFR 기본 이벤트 `jdk.JavaMonitorEnter`로 본다. **[추정]** 새 코드가 필요 없다.
- **푸시 뒤 캐시 비움:** `applyBundle` 끝의 `clearRevlogCache()`(`FetchCommand`)가 같은 시간에 도는 읽기 요청의 인덱스·내용 캐시를 비운다. 읽기 쪽 재적재 비용을 `hg4j.GetBundle`의 ms 변화로 간접 확인한다.

**불변식:** 기본 꺼짐, 켜도 동작과 출력은 같다. **종료 조건:** 동시 푸시 N개와 읽기 M개에서 단계별 시간과 락 대기·보유가 JFR 파일로 나온다.

### Phase 1 — 동시성 회귀 하네스

**목적:** 이후 모든 단계를 같은 시나리오로 검증한다. **이 하네스는 실제 `hg`를 쓰므로 interop 테스트이고, 사용자가 실행을 보류한 상태라 작성도 실행도 하지 않았다.**

**구성:** 기존 패턴(`PushLockRaceRealHgInteropTest`, `HgHttpWireServerRealHgInteropTest`)을 따라 임베디드 서버 + 실제 `hg push/pull` 프로세스를 쓴다.

| 시나리오 | 확인할 것 |
|---|---|
| A. 서로 다른 브랜치 N개 동시 푸시 | 전부 성공하고 결과 저장소가 `hg verify`를 통과, 변경집합 합집합이 정확히 N개, 락 파일과 `journal` 잔재 없음 |
| B. 같은 브랜치 동시 푸시 | 하나만 성공하고 나머지는 원본과 같은 문구로 거절(경합 검사). 어느 쪽이든 저장소는 일관 |
| C. 푸시 + pull 혼합 | pull이 중간 상태를 보지 않음(푸시 전 또는 후의 head 집합만 관찰) |
| D. 서버 프로세스 강제 종료 | 푸시 도중 `kill -9`(별도 JVM), 다음 시작에서 `hg recover` 후 `hg verify` 통과, 재푸시 성공 |
| E. 오래된 락 | 호스트명이 다른 죽은 소유자의 락(2-C와 연결) |

**실제 `hg` 클라이언트가 `check:updated-heads`를 보내는 조건 [확인: hg 7.2.3 소스 `exchange.py:896-920`, 실행으로는 미확인]:**
- 강제(`force`) 푸시이거나 보낼 변경집합이 없으면 검사 파트를 보내지 않는다.
- 서버 capability에 `checkheads=related`가 없거나 원격 저장소가 비어 있으면 `check:heads`(원격의 **모든** head)를 보낸다. 이러면 다른 브랜치에 푸시가 들어와도 head 집합이 달라져 경합 실패가 난다.
- 그 외에는 `check:updated-heads`로 **이 푸시가 대체하거나 버리는 head만** 보낸다(`affected`). 그 집합이 비어 있으면(예: 새 head나 새 브랜치만 만드는 푸시) 검사 파트를 아예 보내지 않는다.
- hg4j 서버는 `checkheads=related`를 광고한다(`Wire1Commands.java:168`)고 확인했다. 서버 검사 `check:updated-heads`는 "목록의 모든 노드가 지금도 head여야 한다"로 원본 핸들러(`bundle2_part_handlers.py:303-325`, `all_nodes_are_heads`)와 같고 `HgPushRacedException`도 같은 문구다(`Bundle2Push.java:484-490`). 원본 docstring이 이 파트의 목적을 "관련 없는 그래프 부분의 활동은 무시해 트래픽이 많은 서버의 푸시 경합을 피한다"로 적고 있다. **그래서 서로 다른 브랜치의 푸시는 이 검사 단계에서는 충돌하지 않는다.** 남는 직렬화는 락과 revlog 덧붙이기 순서다.
- 원본은 이 검사를 락 안에서 하려고 `bundle2lazylocking`이면 검사 핸들러가 먼저 트랜잭션을 연다(`bundle2_part_handlers.py:320-321`). hg4j는 요청 전체가 락 안이라 같다. **2-A에서 검사를 락 밖으로 빼면 안 되는 이유다.**

### Phase 2-A — 락 밖 준비 (단계적)

**현재 [확인]:** `pushWithHooksLocked`가 락 안에서 봉투를 파싱(`Bundle2Parser.extractChangegroupDetailed`)하고 changegroup을 파싱(`ChangegroupParser.parseBundle`)한다. 리비전 해시 검증은 `Revlog.appendChangeGroupEntry` 안(`Revlog.java:1871-1894`, "E3")에서 `synchronized` + store 락 아래에서 한다. 푸시가 `byte[]`로 다 받은 뒤 락에 들어간다(`HgHttpWireServer.java:293-303`).

**이중 파싱이 있다 [확인].** `Bundle2Push`는 락을 잡기 **전에** `Bundle2.read(bundleBytes)`로 봉투를 파싱한다(`Bundle2Push.java:58`, 락은 `:85`). 그런데 변경집합을 적용할 때 파싱한 파트가 아니라 **원본 `bundleBytes` 전체를 `pushWithHooks`에 넘기고**(`:144, :173`), `pushWithHooksLocked`가 그 안에서 락을 쥔 채 봉투를 `Bundle2Parser.extractChangegroupDetailed`로 한 번 더, changegroup을 `ChangegroupParser.parseBundle`로 파싱한다. 즉 봉투 파싱은 한 번은 락 밖, 한 번은 락 안에서 두 번 한다. `remote-changegroup` 경로는 내려받은 바이트(`fetched`)를 같은 식으로 넘긴다(`:173`).

**1단계 — 파싱만 락 밖으로 (위험 낮음, 이중 파싱 제거 포함).** 파싱은 입력 바이트만의 순수 함수라 저장소 상태를 읽지 않는다. `PreparedBundle prepare(byte[])`가 봉투·changegroup 파싱과 `check:heads` 원문 추출을 **한 번만** 하고, `Bundle2Push`가 이미 가진 파싱 결과를 `pushWithHooks(PreparedBundle, ...)` 오버로드로 넘겨 락 안의 재파싱을 없앤다. 기존 `byte[]` 시그니처는 `prepare` 뒤 위임한다. 훅은 락 안에 둔다(원본에서도 `prechangegroup` 훅은 열린 트랜잭션 `tr`을 들고 돈다 — `changegroup.py:714`에서 확인). **heads 경합 검사도 락 안에 둔다**(위 Phase 1의 이유).

**2단계 — 해시 검증도 락 밖으로 (측정이 필요성을 보일 때만).**
- 검증하려면 각 리비전의 전체 텍스트(델타 적용)가 필요하다. 기반이 (가) 이미 저장소에 있는 리비전이면 **append-only라 스냅샷 길이 아래는 바뀌지 않으므로** 락 없이 읽을 수 있다. (나) 같은 번들의 앞선 항목이면 메모리 맵에서 푼다.
- **낙관적 검증:** 준비 시점에 revlog별 `(리비전 수, 마지막 노드)`를 기록하고, 락 안에서 같은지 확인한다. 다르면(그 사이 푸시가 들어왔거나 `strip`/롤백이 잘랐으면) **현재 방식으로 락 안에서 다시 검증한다.** 틀려도 느려질 뿐 틀린 데이터는 들어가지 않는다.
- `Revlog.appendChangeGroupEntry`에 "검증 끝남" 인자가 있는 오버로드를 둔다. 기본은 지금처럼 검증한다.
- **위험:** 준비 중 읽는 revlog 파일에 다른 프로세스가 덧붙이는 중일 수 있다. 시작 때 `.i` 크기를 한 번 읽고 그 아래만 읽는다(`RevlogIndex.checkAndUpdate`가 이미 디스크 재확인 로직을 가짐).
- **동등성 검증:** 같은 번들을 (가) 현재 경로 (나) 준비 경로로 각각 적용해 revlog `.i`/`.d` 바이트가 같은지, `hg verify`가 통과하는지 본다(실제 hg 사용 → 실행은 사용자 허락 후).

### Phase 2-B — 공정 대기열

**현재 [확인]:** `HgLock`은 JVM 안에 경로별 등록부(`JVM_ACTIVE_LOCKS`)가 있어 **같은 JVM의 다른 스레드가 쥔 락은 50ms 간격으로 깨어나 다시 확인한다**(`HgLock.java:128-246`, `Thread.sleep(50)`). 대기자 사이의 순서 보장이 없다.

**설계:** JVM 안의 대기는 등록부 항목에서 **공정(FIFO) 조건 대기**로 바꾼다. 해제하면 맨 앞 대기자를 깨운다. 파일 락(다른 프로세스와의 상호 배제)과 그 폴링은 그대로다. 그러면 (가) 같은 JVM 안의 락 해제 뒤 최대 50ms를 더 기다리는 일이 없어지고 (나) 굶는 스레드가 없어지며 (다) 재경쟁(thundering herd)이 없다.

- **대기 시간 예산:** `ui.timeout`을 JVM 안 대기와 파일 락 대기가 **나눠 쓴다**(남은 시간 계산). 합쳐서 두 배가 되면 안 된다.
- **재진입:** 같은 스레드의 중첩 획득은 지금처럼 즉시 통과한다(`isReentrant`).
- **영향 범위:** `HgLock`은 모든 명령이 쓰므로 서버 전용이 아니다. 다만 **배타성은 그대로이고 깨우는 방식만 바뀐다.** 바깥에서 관찰되는 동작은 대기 시간 분포뿐이라 [[lock-parity-matrix]]의 의미는 같다. 그래도 게이트 2의 승인을 받는다.
- **S0(락 순서 감시)와 같은 곳을 건드리므로 함께 설계한다.**

### Phase 2-C — 소규모 개선

- **락 TTL/하트비트 (기본 꺼짐, opt-in).** 락 파일(심볼릭 링크 대상 `host:pid`)의 형식은 바꿀 수 없다. 실제 hg가 `host:pid`를 파싱하기 때문이다(`lock.py:357-372`). 그래서 hg4j가 쓴 락에 한해 **옆 파일**(예: `lock.hb`)에 소유자 토큰과 갱신 시각을 둔다. 다른 호스트의 대기자는 (가) 옆 파일이 있고 (나) 토큰이 현재 락 대상과 같고 (다) 갱신이 TTL보다 오래됐을 때만 깬다. 옆 파일이 없는 락(실제 hg가 쓴 것)은 지금처럼 `ui.timeout`까지 기다린다.
  - **위험:** GC 정지나 VM 정지로 살아 있는 소유자를 죽었다고 오판하면 쓰기 둘이 겹칠 수 있다. 쓰기를 막는 펜싱이 없어 완전히 막을 수 없으므로 **기본은 꺼 둔다.** 실제 hg에는 없는 기능이라 켜는 것은 사용자 결정이다.
- **`Revlog` 내용 캐시 설정화.** 지금은 항목 100개 고정(`Revlog.java:51`)이다. 항목 수와 **바이트 상한** 둘 다 설정으로 받는다(큰 파일 리비전 몇 개가 힙을 먹을 수 있음). 기본값은 현재와 같다.
- **푸시 뒤 `clearRevlogCache()` 범위 축소 (조사 후 결정).** append-only 커밋은 기존 항목이 그대로 유효하므로 개수·인덱스만 갱신하면 읽기 요청의 캐시가 살아남는다. 반면 `strip`/롤백처럼 자르는 경우는 전체 비움이 필요하다. **2026-09-02 사고 기록([[backlog/push-and-concurrency]] 13번)이 보여 주듯 잘린 뒤 같은 `Revlog` 참조를 계속 쓰는 코드가 있어 섣불리 바꾸면 깨진다.** 그래서 "덧붙이기만 한 경우"를 호출자가 명시하는 방식으로만 하고, Phase 0에서 읽기 지연이 실제로 늘어나는지 보인 뒤 착수한다.

### Phase 3 — 그룹 커밋 (측정이 필요성을 보일 때만)

**핵심 결정: 배치마다 저널 하나, 푸시마다 savepoint.**
- **현재 [확인]:** `FetchCommand.applyBundle`은 파일마다 **덧붙이기 전 길이**를 `journal`에 적는다(`store/00changelog.i\t<길이>` 등). 롤백은 그 길이로 자른다. 이 길이 기록이 이미 savepoint의 재료다.
- **설계:** 선두 스레드가 락을 한 번 잡고, **이미 대기 중인 푸시만**(타이머로 기다리지 않음 → 개별 푸시 지연이 늘지 않음) 순서대로 적용한다. 푸시마다 시작 시점의 (덧붙이기 전용 파일 길이 + 덮어쓰기 파일 내용)을 savepoint로 기록한다. `pretxnchangegroup` 훅이 거부하면 그 푸시의 savepoint까지만 되돌린다.
- **savepoint가 덮어야 할 파일:** revlog `.i`/`.d`(자르기), `fncache`, nodemap docket·데이터, `phaseroots`, bookmarks 파일(요건이면 store), obsstore. 지금 `Bundle2Push.Snapshot`이 `bookmarks`·`phaseroots`·`dirstate` 셋만 담는 것보다 넓어서 **목록을 코드에서 전수 확인하는 것이 선행 작업**이다.
- **내구성:** `fsync`는 배치 끝에서 한 번 하고, **응답은 그 뒤에** 보낸다. 배치 도중 크래시는 저널이 남아 `hg recover`가 **배치 전체**를 되돌린다. 아직 어느 푸시에도 응답하지 않았으므로 약속을 어기지 않고, 클라이언트는 오류를 받아 다시 푸시한다.
- **하지 않는 것:** 푸시마다 트랜잭션을 완결하되 `fsync`만 미루는 방식. 완료된 푸시의 데이터가 디스크에 내려가기 전에 크래시하면 인덱스가 디스크에 없는 데이터를 가리킬 수 있어 위험하다.
- **착수 조건:** Phase 0에서 락 획득·해제·`fsync`·캐시 비움이 푸시당 시간에서 의미 있는 비중이고, Phase 2로도 줄지 않을 때만.

### Phase 4 — 클론·fetch

현재 구현은 4.2절. 남은 일은 **누가 번들을 만들고 갱신하는가**와 **같은 요청의 반복 생성**이다.

- **4-A stream 생성의 락 점유 (가장 먼저 할 일, hg와 같아지는 방향).** 이번 확인에서 찾은 병목이다. hg4j의 `StreamClone#streamOut`(`:126`)과 `stream2`(`:207`)는 **store 락을 쥔 채로 모든 파일 내용을 메모리에 읽는다.** 에이전트 여러 대가 동시에 stream clone을 하면 요청마다 락을 순서대로 오래 잡아 푸시와 서로를 막는다. hg는 락을 **파일 목록과 각 파일의 기대 크기를 기록하는 동안만** 쥐고, 첫 `yield`에서 락을 풀며, 데이터는 그 뒤에 기록한 크기만큼 읽어 내고 **읽은 바이트가 기대 크기와 다르면 오류로 처리한다**(`streamclone.py` `_emit2`의 `bytecount != size`).
  - **설계:** `stream2`/`streamOut`을 둘로 나눈다. (1) 락 안에서 `(이름, 파일, 크기)` 목록만 만든다. 락을 푼다. (2) 목록 순서대로 각 파일을 **기록한 크기까지만** 읽어 청크로 내보낸다. 읽기 전후로 파일 길이가 기대 크기 미만이면(그 사이 `strip`/롤백이 잘랐으면) 오류로 중단한다.
  - **안전한 이유 [추정, hg 원본이 같은 가정을 쓴다는 것은 확인]:** revlog는 append-only라 기록한 길이 이전 바이트는 바뀌지 않는다. 덮어쓰는 작은 파일(`phaseroots`, 캐시 파일)은 목록 단계에서 내용을 락 안에서 읽어 둔다(원본의 `preserve_volatiles`). 이 파일들의 목록은 코드에서 전수 확인한 뒤 정한다.
  - **이 변경은 hg와의 이탈이 아니라 일치 쪽이다.** 지금의 "락 안에서 전부 읽기"가 원본과 다르다. 따라서 게이트 2와 무관하게 진행할 수 있다고 본다(사용자 확인은 받는다).
  - **4-D와 같은 코드라 함께 한다.** 청크로 내보내면 256MB 메모리 상한도 사라진다(아래 4-D).
- **4-A′ 번들 파일 생성 정책 (clonebundles/peer-bundle-cache).** 락 범위를 코드로 확인했다 [확인]: `ShardedStreamBundlesCommand.call()`은 `wlock`과 store 락을 **생성하는 내내** 잡는다(`:49-50`). 반면 **`BundleCommand`는 락을 잡지 않는다**("Like real hg, `bundle` only reads: no lock", `BundleCommand.java:430`; 메서드 javadoc의 "store lock needed" 문구는 낡았고 `HgLock`을 쓰는 코드가 없다). 따라서 `.hg` 전체를 받는 **일반 번들(`HG20`, changegroup)은 푸시를 막지 않고 만들 수 있다.** stream 번들은 클라이언트 적용이 빠르지만 생성에 `ShardedStreamBundlesCommand`를 쓰면 쓰기를 막으므로, 4-A와 같은 방식(락은 목록·크기 기록 동안만)으로 고친 뒤에 주기 생성한다. 생성 주기는 푸시 수나 경과 시간이며 수치는 Phase 0 뒤에 정한다.
- **4-B peer-bundle-cache.** `get_cached_bundle_inline`은 번들 파일을 **통째로 메모리에 올려**(`Files.readAllBytes`, `Wire1Commands.java:316-345`) 내려준다. 큰 번들을 에이전트 여러 대가 동시에 받으면 힙이 문제가 된다. 설계: 파일을 청크 단위로 흘려보낸다(길이 varint는 파일 크기로 먼저 알 수 있음).
- **4-C 동적 getbundle 응답 캐시.** `HgLocalClient#getBundle`은 요청마다 만든다(`Wire1Commands.java:808-834`). 캐시 키는 `(정렬한 공통 head 집합, 정렬한 head 집합, 정규화한 bundlecaps, narrow 범위 지문)`으로 하면 **새 푸시가 들어와도 키가 달라질 뿐 오래된 결과가 틀리지는 않는다**(내용 주소 방식). 다만 응답의 `bookmarks`·`phase-heads` 같은 부가 파트는 푸시 뒤 값이 달라지므로(`Bundle2Pull.wantsExtraParts`) **changegroup 파트만 캐시하고 부가 파트는 매번 붙인다.** 바이트 상한 LRU, secret 단계가 섞이는 요청은 캐시하지 않는다. `getBundle`을 "changegroup 바이트"와 "부가 파트"로 나누는 리팩터링이 선행 작업이다. Phase 0의 `hg4j.GetBundle`로 같은 키의 반복 비율을 먼저 본다.
- **4-D stream clone의 메모리 상한.** 응답을 메모리에서 만들고 256MB를 넘으면 거부한다(`StreamClone.java:54`, `ByteArrayOutputStream`). 이 한도는 코드로 확인된 **구조적 상한**이다. 한도를 넘으면 서버는 `1\n`(허용 안 함)을 보내고(`StreamClone.java:134-136`), 스토어 락을 못 얻으면 `2\n`을 보낸다(`:148-150`). **이 응답을 받으면 클라이언트는 일반 pull로 돌아가지 않고 중단한다** — hg4j 클라이언트(`StreamCloneClient#consumeV1`: "operation forbidden by server" / "locking the remote repository failed")도, hg 원본(`streamclone.py:241-243`)도 같다. 그래서 큰 저장소를 stream으로 받으려는 에이전트는 clone 자체가 실패한다. 서버는 stream 광고(`capabilityTokens`) 여부를 `allowServerGeneration`으로 정하는데 이것은 **레이아웃, `server.uncompressed`, secret 변경집합만 보고 저장소 크기를 보지 않는다 [확인, `StreamClone.java:81-113`].** 그래서 한도를 넘는 저장소도 stream을 광고하고, 클라이언트는 요청한 뒤에야 `1\n`으로 거절당한다. 운영 쪽 임시 대응은 `server.uncompressed=false`로 광고를 끄는 것이다(코드로 확인). 개선은 광고 시점에 대략의 총 크기를 보고 한도를 넘으면 광고하지 않거나, 4-A의 청크 전송으로 한도 자체를 없애는 것이다. 4-A의 청크 전송으로 메모리 상한이 없어지고 힙 사용이 청크 크기로 줄어든다. 현재 운영 저장소가 한도를 넘는지는 알 수 없다(저장소 크기는 확인하지 않았다).

### Phase 5 — 장기

- **5-A narrow/shape는 측정만.** 구현은 끝났다(4.2절). 에이전트 시나리오(하위 디렉터리 일부만 필요)에서 전체 클론 대비 전송 바이트와 시간을 재서 효과를 수치로 남긴다. 새 코드는 필요 없다.
- **5-B 공유 객체 스토리지는 현재 구조로는 어렵다 [확인].** `StoreEngine`은 `Revlog` 조회, manifest, dirstate 읽기·쓰기 5개 메서드뿐이고(`StoreEngine.java`) **인자가 `File`이며 `Revlog`와 `RevlogIndex`가 파일 API를 직접 쓴다**(각각 36곳, 8곳 확인). journal, 락, `fncache`, `phaseroots`, bookmarks도 엔진 밖에서 파일로 다룬다. 공유 스토리지를 쓰려면 이 모두 밑에 "바이트 범위 읽기, 덧붙이기, 자르기, 원자적 교체, 분산 락"을 담은 새 계층이 필요하다. 이는 hg 디스크 포맷 호환(3절 1번)과 부딪히고 락 의미(`hg`와의 상호 배제)도 다시 정의해야 한다. **권장: 하지 않는다.** 읽기 부하 분산은 Phase 4의 번들 캐시(객체 스토리지에 번들 두기)로 대신한다.
- **5-C 클라이언트 재시도 (서버가 아니라 `PushCommand`).** 락 대기 만료나 경합 거절이 났을 때 지터 있는 지수 백오프로 재시도하는 **옵트인** 옵션(`setRetry(최대 횟수, 기본 대기)`). 매 시도마다 원격 heads를 다시 받아 보낼 변경집합을 다시 계산해야 한다(그 사이 원격이 바뀌었으므로). 원본은 재시도하지 않고 `abort`하므로 기본은 꺼 둔다.

## 6-3. 단계 간 의존과 권장 순서

```
S0/Phase 0(계측, 락 순서 감시) ──┬─> Phase 1(하네스, 허락 후)
                                 ├─> S1~S4(unbundle wlock, 6-1절) ──> 2-A 1단계 ──> 2-B
                                 └─> Phase 4-A/4-B(독립, 4-A가 가장 먼저)
측정 결과 ──> 2-A 2단계 / 2-C의 캐시 범위 축소 / Phase 3 / 4-C 착수 여부 결정
```

- **측정(6-4절) 이후의 권장 순서:** ① **2-D(락 안 CPU — 인덱스 재적재와 16진 변환)** → ② **4-A(stream 생성의 락 점유)** → ③ 2-B(공정 대기열) → ④ 2-A → ⑤ Phase 3. 2-D는 락 의미를 바꾸지 않아 게이트 2와 무관하다. ①을 끝낸 뒤 같은 하네스로 **다시 측정해서** ③~⑤의 착수 여부를 정한다.
- 코드로 먼저 할 수 있고 위험이 낮은 것: 2-D의 (가), 4-A, 4-B(청크 전송), Phase 0·S0(계측과 락 순서 감시).
- 실제 `hg` 실행이 필요해 **허락 전에는 검증하지 못하는 것:** Phase 1 전부, 2-A 2단계의 동등성 검증, Phase 3의 장애 주입, 2-D 변경 전후 저장소를 `hg verify`로 확인하는 일.

## 6-4. 측정 결과와 우선순위 재조정 (2026-11-13)

질문: "푸시가 락을 쥐고 있는 동안 시간이 어디에 쓰이는가?" 답: **락 대기나 `fsync`가 아니라 락 안의 CPU가 가장 큰 후보다.** 다만 깨끗한 프로파일을 얻지 못해 비율은 확정하지 못했다.

### 방법과 신뢰도 (먼저 읽을 것)

- **대상:** `main` `5855845`를 코드 수정 없이 별도 worktree에서 측정했다. 측정용 JUnit 하네스(`LockHoldMeasure`)를 얹었고 **커밋하지 않았다.** hg4j만 쓰고 실제 `hg`는 쓰지 않았다(interop 금지).
- **푸시 방식:** `HgLocalClient#pushWithHooks`를 같은 JVM에서 직접 호출했다. 네트워크, HTTP 서블릿, 실제 hg 클라이언트의 `HG20` 봉투는 거치지 않았다(번들은 `HG10UN`). 실제 푸시는 봉투 파싱이 조금 더 든다 [추정]. 훅은 쓰지 않았다.
- **환경:** Apple M1 Pro 8코어, Java 21, macOS APFS, 한 대. `fsync` 비용은 Linux 서버 디스크와 다를 수 있다. 반복 횟수가 적다(n=2~30).
- **두 번 실행했다.** 1차는 JFR이 파일 읽기 이벤트를 185만 건 기록해 **절대 시간이 부풀려졌다**(여기서는 CPU 샘플 비율과 `fsync` 값만 참고). 2차는 가벼운 설정이라 **시간 값은 2차를 신뢰한다.** 2차의 JFR 파일은 같은 이름을 나중에 뜬 JVM이 덮어써 사라졌다. 그래서 CPU 프로파일은 1차에서만 있다.
- 2차 실행 중 gradle이 `interopTest`를 시작했다가 즉시 중단했다(완료된 테스트 0개). 상세는 [[log]].

### 결과 (2차 실행)

| 상황 | 결과 |
|---|---|
| 변경집합 101개 초기 푸시 (번들 약 320KB) | 4.1초 |
| 변경집합 1개(파일 3개, 번들 약 1.1KB), n=30 | 중앙값 148ms (최소 97, 최대 397) |
| 변경집합 10개(파일 5개씩, 약 16KB), n=10 | 중앙값 1.29초, 변경집합당 약 129ms |
| 변경집합 100개(약 155KB), n=2 | 10.9초, 14.7초 → 변경집합당 약 110~147ms |
| stream clone 생성, 저장소 2MB / 105MB | 중앙값 25ms / 334ms (생성 내내 store 락을 쥠) |
| 작은 푸시 단독 vs stream 생성 중 푸시, n=3 | 중앙값 105ms vs 270ms (149, 270, 1,037ms). 푸시가 stream 뒤에서 기다림 |
| 동시 푸시 K개(각 변경집합 1개) | K=1: 389ms, K=2: 1.4초, K=4: 2.6초, K=8: 5.6초(전체 소요). 지연이 K에 비례해 늘어 직렬 처리임을 보임 |

### CPU 프로파일 (1차 실행, 푸시 스레드 샘플 668개, 참고용)

| 항목 | 포함(inclusive) 비율 |
|---|---|
| `HgLocalClient#pushWithHooksLocked` | 99.1% |
| `FetchCommand#applyBundle` | 83.5% |
| `Revlog#appendChangeGroupEntry` | 73.8% |
| `RevlogIndex#loadIndex` | 68.1% |
| `NodeIdUtil#toHex` | 63.6% |
| `Revlog#clearCache` / `RevlogIndex#clearCache` | 60.8% |

- 최상위 프레임은 `java.util.regex`와 `Formatter`(`String.format`)가 대부분이었다.
- 포함 비율 상위 28개 메서드 안에 **파싱**(`ChangegroupParser`, `Bundle2Parser`)은 없었다.
- **`fsync`(JFR `FileForce`):** 3,066회, 합 14.4초, 평균 4.7ms. 푸시 58건이므로 푸시당 평균 약 53회. 변경집합 761개를 밀어 넣는 동안의 값이고, 이 실행의 총 시간이 부풀려져 있어 전체에서 차지한 비율은 계산하지 않았다.

### 코드로 확인한 원인 [확인]

1. `NodeIdUtil.toHex`가 **바이트마다 `String.format("%02x")`를 부른다**(`NodeIdUtil.java:27-34`). 노드 하나에 20번이다.
2. `RevlogIndex.loadIndex`가 인덱스를 읽을 때 **모든 레코드마다** 이 `toHex`로 문자열 키를 만들어 `hexNodeMap`에 넣는다(`RevlogIndex.java:561` 등).
3. `Revlog#appendChangeGroupEntry`가 `clearCache()`를 부른다(`Revlog.java:1923`). 이는 `index.clearCache()`로 이어진다(`Revlog.java` `clearCache`). **[미확인]** 이 줄이 어떤 조건에서 실행되는지, 푸시당 한 번인지 항목마다인지는 읽지 않았다.

### 해석 [추정, 직접 검증하지 않음]

- 동시 푸시 한 건이 약 0.65~0.7초인 반면 단건 푸시(저장소 약 120 리비전)는 148ms였고, 동시 푸시 실험의 저장소는 약 560 리비전이었다. **인덱스 재적재 비용이 리비전 수에 비례한다는 가설과 맞지만, 크기만 바꾼 비교 실험은 하지 않았다.**
- 변경집합당 시간이 작은 푸시(148ms)와 큰 푸시(약 130ms)에서 비슷하다. 푸시당 고정 비용만이 아니라 **변경집합(항목)마다 드는 비용**이 크다는 뜻일 수 있다.

### 우선순위 재조정

| 항목 | 이전 판단 | 측정 후 |
|---|---|---|
| **2-D (신규) 락 안 CPU 줄이기** | 없음 | **최우선 후보** |
| 4-A stream 생성의 락 점유 | 코드 비교에서 찾은 병목 | 측정으로 확인: 105MB 저장소 생성 중앙 334ms, 그동안 푸시가 대기 |
| 2-B 공정 대기열 | 낮은 위험 | 동시 푸시가 직렬로 K배. 50ms 폴링이 차지하는 몫은 분리하지 못함 |
| 2-A 락 밖 준비 | 1단계부터 | 파싱이 프로파일 상위에 없어 **1단계의 이득이 작을 수 있음.** 2단계(해시 검증)는 `appendChangeGroupEntry` 안에서 해시와 인덱스 재적재 중 무엇이 지배적인지 분리하지 못했으므로 2-D 뒤 재측정 |
| Phase 3 그룹 커밋 | 측정 후 결정 | `fsync` 비중을 확정하지 못함. 2-D 뒤 재측정 |

### 2-D 설계: 락 안 CPU 줄이기 (코드 변경 없음)

락 의미를 바꾸지 않는 순수 CPU 개선이라 게이트 2와 무관하다. 단계는 위험이 낮은 순서다.

1. **(가) `NodeIdUtil.toHex`를 테이블 기반 변환으로.** 입출력이 같고 동작이 바뀌지 않는다. 호출자를 전수 확인한다([[AGENTS]] 규칙 8의 1:N 검사). 기존 단위 테스트가 이 함수를 커버하는지 먼저 본다.
2. **(나) `hexNodeMap`의 문자열 키를 노드 바이트 키로.** (가)만으로 충분한지 재측정한 뒤 결정한다. 구조 변경이라 위험이 더 크다.
3. **(다) 덧붙일 때 인덱스 전체를 비우지 않고 새 레코드만 반영.** `RevlogIndex#addRecord`와 `loadIndexIncremental`이 이미 있고 둘 다 프로파일에 나왔다. **위험:** 2026-09-02 사고([[backlog/push-and-concurrency]] 13번)처럼 자른 뒤 같은 `Revlog` 참조를 계속 쓰는 코드가 깨진다. 그래서 **"덧붙이기만 한 경우"를 호출자가 명시하는 방식**으로만 하고, `strip`/롤백 경로는 전체 비움을 유지한다.

**검증 계획(이번에는 실행하지 않음):** 같은 하네스로 수정 전후 A/B를 재측정한다. 수정 전후 revlog 바이트가 같은지 비교한다. 실제 `hg verify`는 interop 보류가 풀린 뒤에 한다.

### 여전히 미확인

- 깨끗한 CPU 프로파일에서의 비율(위 프로파일은 1차 실행이다)
- 푸시당·변경집합당 `fsync` 횟수와 전체 시간에서의 비중
- 락 대기의 50ms 폴링이 차지하는 시간
- `Revlog.java:1923`의 `clearCache()`가 실행되는 조건
- 실제 HTTP 서블릿과 실제 hg 클라이언트 경로, Linux 운영 디스크에서의 `fsync`
- 반복 횟수가 적어 분산이 크다는 점

### 재현

측정용 하네스는 스크래치 worktree에만 있고 커밋하지 않았다. 필요하면 같은 방식(JUnit 하나, 푸시 스레드를 이름으로 구분, JFR `ExecutionSample`/`FileForce`)으로 다시 만든다. **`./gradlew test`는 `jacocoTestReport`를 거쳐 `interopTest`까지 돌린다**(`build.gradle:146, 188`). 실행하려면 `-x interopTest -x jacocoTestReport -x jacocoTestCoverageVerification`을 붙이고 `--dry-run`으로 태스크 목록을 먼저 확인한다.

## 7. 근거 자료

| 출처 | 내용 | 신뢰도 |
|---|---|---|
| GitLab 공식 글 "Scaling Git for the agentic software factory" | 클라이언트가 큰 저장소 데이터를 내려받음(에이전트 1회에 5~10GB, 30초 이상), 파이프라인 40%·푸시 50% 증가. 대응은 필요한 것만 요청하는 인터페이스, 상태 없는 컴퓨팅, 격리 세션 | 1차 자료 확인 |
| Anthropic 공식 글 "Redesigning source control at Anthropic" | 병합 하루 약 2,500건(5월)에서 3만 건 이상(9월). 일반 Git 대체재가 아님 | 1차 자료 확인 |
| OpenAI "Scaling Git at OpenAI"(Pando) | 서로 다른 브랜치 푸시의 직렬화, packfile 반복 생성, 병합 신뢰성 90% 미만. 대응은 동시 푸시 처리와 packfile 캐시 | **[미확인]** 공식 글은 접근이 막혀(403) 못 읽음. 보도 요약만 확인 |
| GitHub, 기타 업체 | 푸시 처리량 수치 등 | **[미확인]** 2차 블로그 요약만 확인 |

GitLab·OpenAI의 수치는 모두 업체 자체 보고다. 위 병목 목록을 hg4j에 대응시키면 다음과 같다.

| 병목 | hg4j 대응 |
|---|---|
| 필요한 것보다 많이 가져감 | narrow·shape는 구현됨(4.2절). 남은 일은 측정(6-2절 Phase 5) |
| 상태 없는 컴퓨팅 + 공유 스토리지 | Phase 5 조사 |
| 서로 다른 브랜치 푸시 직렬화 | Phase 2~3 (락 구간 축소, 배치) |
| packfile 반복 생성 | peer-bundle-cache는 구현됨. 남은 일은 번들 생성·갱신 정책과 동적 getbundle 반복 비용(6-2절 Phase 4) |
| 병렬 부하에서 병합 신뢰성 저하 | Phase 2-B, Phase 5 재시도 정책 |
| 에이전트별 격리 | 별도 클론이 가장 단순함. share는 store 락 공유 |

## 8. 하지 않을 것과 이유

- **브랜치별 저장소 분리(샤딩):** 단일 저장소 의미가 깨져 `hg`와 호환되지 않는다.
- **브랜치별 changelog:** 디스크 포맷이 바뀌어 `hg`가 읽지 못한다.
- **락 파일 제거:** 실제 `hg`와의 상호 배제가 사라진다.

## 9. 알려진 한계와 위험 (숨기지 않고 남김)

- 번들 전체를 메모리(`byte[]`)에 올리는 구조라 대용량 푸시가 동시에 오면 힙 압박이 생긴다. 대기열에 크기 제한과 역압이 필요하다.
- Phase 3의 배치 창은 개별 푸시의 지연을 늘릴 수 있다.
- 이 문서의 hg 쪽 분석은 정적으로 읽은 것이고, 실행이나 벤치마크로 확인하지 않았다.
- hg의 `phases`, 북마크가 대형 저장소 성능에 주는 영향은 조사하지 못했다.

## 관련 문서

[[concepts/locking-and-concurrency]], [[lock-parity-matrix]], [[backlog/push-and-concurrency]], [[backlog/clonebundles]], [[backlog/narrow-clone-and-lfs]], [[decisions/journaling-crash-recovery-plan]], [[decisions/mercurial-spec-compliance-requirement]]
