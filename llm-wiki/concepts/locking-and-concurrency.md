---
title: 잠금 범위와 동시 접근
status: 점검 완료, 한계 있음 (2026-11-04)
---

# 잠금 범위와 동시 접근

실제 Mercurial은 저장소를 바꾸는 명령 전체(읽기, 판단, 쓰기)를 `wlock` 다음 `lock` 순서로 쥔 채 수행한다. hg4j가 같은 저장소를 실제 hg와 함께 쓰려면(같은 `.hg/wlock`, `.hg/store/lock` 파일을 쓴다) 범위와 순서가 같아야 한다.

## 2026-11-04 점검에서 고친 것

| 문제 | 영향 | 고침 |
|---|---|---|
| `bookmark`에 잠금이 없었다 | 동시 기록자 중 나중 쪽이 앞선 북마크를 덮어쓴다 | `wlock`, `lock`을 `ui.timeout`만큼 기다리며 잡는다. 읽기 전용 목록은 `BookmarkCommand#list()`로 분리 |
| 로컬 `pushkey`(북마크)에 잠금이 없었다 | 두 push가 같은 옛 값을 보고 한쪽을 잃는다 | 비교와 기록을 두 잠금 안에서 수행 |
| `tag`, `sign`, `import`, `bisect`에 잠금이 없었다 | `.hgtags` 읽고 이어쓰기, 작업 사본 변경이 무방비 | 실제 hg와 같은 범위(`tag` wlock+lock, `sign` wlock, `import` wlock+lock, `bisect`는 `update`와 같이 둘 다) |
| 8개 명령이 `lock` 다음 `wlock`을 잡았다 | 실제 hg(wlock 다음 lock)나 다른 명령과 반대 순서로 서로를 기다리면 교착 | backout, fetch, graft, runTransaction, histedit, merge, rebase, strip을 실제 hg 순서로 |
| 롤백용 백업을 잠금 전에 떴다 (commit, strip, histedit, rebase, fetch.applyBundle, remove, rename, copy) | 실패 시 복원이 그 사이 다른 프로세스의 변경을 덮어쓴다 | 백업과 원래 부모 읽기를 잠금 안으로 |
| `Bundle2Push`(서버 push 수신)가 부분마다 잠금을 잡았다 놓았다 | 한 요청의 실패 복원이 동시 요청의 북마크, phase를 덮어쓰고, 롤백이 다른 요청의 변경집합을 되돌릴 수 있다 | 요청 처리 전체를 `wlock`, `lock` 하나로 |
| 로컬 `push`가 적용과 `pretxnchangegroup` 롤백 사이에 잠금을 놓았다 | 위와 같다 | `pushWithHooks`를 잠금 래퍼와 본문으로 분리 |
| 재진입 `lockStore()`가 자동 복구를 실행했다 | 잠금을 쥔 채 다시 호출하면 진행 중인 자신의 저널을 복구(되돌림) | 재진입이면 복구하지 않는다 |

검증: `LockScopeConcurrencyTest`(4), `BookmarkConcurrencyTest`(4), `BookmarkConcurrencyRealHgInteropTest`(2, 실제 hg 프로세스와 hg4j 스레드를 섞어 동시 실행). 각 수정은 끄면 대응 테스트가 실패함을 확인했다(red-first).

## 획득과 해제 지점 (실제 hg 7.2 소스 대 hg4j)

기준은 **로직 위치**다. hg는 (1) 옵션과 인자 검사, (2) 잠금 획득, (3) 저장소 상태 읽기, 판단, 쓰기, 실패 시 되돌리기, (4) 잠금 해제 순서로 진행한다. hg4j도 같은 순서가 되도록 각 메서드 안에서 잠금 획득 문장을 옮겼다(메서드 전체를 감싸는 래퍼는 쓰지 않는다). 실패 시 롤백(`catch`)도 잠금 안에서 일어난다.

| 명령 | hg의 잠금과 위치 | hg4j |
|---|---|---|
| commit | 옵션 검사 뒤 `wlock`, `lock`. 서브레포 준비와 `precommit` 훅 포함 전부 안 | 같음 (`CommitCommand.call`, 백업은 잠금 안에서 읽음) |
| amend | `wlock`, `lock`, 트랜잭션. 대상 읽기부터 안 | 같음 |
| tag, import, bookmark(변경), rollback | hg와 같은 잠금 종류를 첫 상태 읽기 앞에서 | 같음. `bookmark` 목록, `branch` 조회는 잠그지 않음(hg도 안 잠금) |
| backout, strip, rebase, histedit | `wlock`, `lock`. 리비전 해석부터 안 | 같음 (리비전 해석, changelog 읽기를 잠금 안으로) |
| graft, update, merge, revert, remove, forget, add, rename, copy, resolve, branch(변경), sign, addremove, bisect | `wlock` (graft는 커밋 단계에서 `lock`) | 같음 |
| shelve, unshelve | shelve는 `wlock` 뒤 `lock`+트랜잭션, unshelve는 `wlock` 직후 `lock`+트랜잭션 | 같음 |
| phase | `lock`, 트랜잭션 | `lock` (잠금 실패는 `IOException`으로 감쌈) |
| pull | 갱신하면 `wlock`, `exchange.pull` 안에서 클론번들 확인, discovery, 수신, 적용, phase, 북마크 전부 `wlock`+`lock` | `PullCommand`가 `wlock`, `FetchCommand.call()`이 네트워크 수신 전에 둘 다 잡고 끝에서 해제 |
| push(클라이언트) | 연결과 능력 확인 뒤 discovery 앞에서 `lock`, 모든 단계 뒤 해제 | 같음 (연결 `try`의 자원으로 `lock`) |
| push 수신(서버) | `check_heads`(잠금 밖) 뒤 번들 처리 직전에 `wlock`, `lock` | `Bundle2Push`가 번들 해석과 필수 파트 확인 뒤, 파트 처리 직전에 둘 다 잡고 응답을 만든 뒤 해제. 로컬 `push`도 적용, 훅, 롤백을 한 번의 보유 안에서 |
| purge | 잠금 없음 | 잠금 없음 |

### hg와 같게 맞춘 것 (이전에는 hg4j가 더 잠갔다)
- `update`, `merge`, `revert`, `remove`는 `wlock`만 잡는다. `store/journal`을 공유하던 문제는 저널을 나누어 해결했다. dirstate 한 줄만 기록하던 `remove`, `rename`, `copy`, `merge`는 `.hg/wc-journal`을 쓰며(hg의 `journal.dirstate`에 해당), `store/journal`은 store 잠금을 잡은 명령만 쓴다. 중단된 작업 사본 변경은 다음에 `wlock`을 새로 잡는 명령이 복구한다(`HgRepository#lockWorkingCopy`). `rename`과 `copy`는 예전에도 `wlock`만 잡으면서 `store/journal`을 써서 `commit`과 충돌할 수 있었다.
- `update`가 활성 북마크를 바꿀 때는 `wlock` 안에서 `bookmarks.current`만 쓴다(`BookmarkCommand#setCurrentOnly`). `hg bookmark -i`에 해당하는 일반 호출은 여전히 세 잠금을 잡는다.
- `graft`는 `wlock`만 잡고, 커밋 단계(`commitGraftedRevision`)에서만 store 잠금을 잡는다.
- `bisect`는 `wlock`만 잡는다. `bundle`은 잠그지 않는다.
- `shelve`는 `wlock`으로 작업 사본을 읽은 뒤 hg가 `repo.lock()`을 잡는 지점(변경 없음 판정 앞)에서 store 잠금을 잡는다. `unshelve`는 hg처럼 `wlock` 직후 둘 다 잡는다.

## hg 소스와 한 줄씩 대조한 결과 (2026-11-04)

"구조만 보고 판단한" 항목이 남지 않도록 아래를 모두 hg 소스에서 읽고 대조했다. 읽은 곳은 `commands.py`(add, addremove, forget, remove, rename, copy, update, backout, graft, pull, commit, tag, import_, bookmark, branch, phase, resolve, unshelve), `cmdutil.py`(amend, revert, abortgraft, unbundle_files, copy), `exchange.py`(pull, push, unbundle, gettransaction), `merge.py`(_update), `localrepo.py`(commit, rollback, recover, txnclose 훅), `bookmarks.py`, `phases.py`, `strip.py`/`repair.py`, `shelve.py`, `hgext/rebase.py`, `hgext/histedit.py`, `hgext/gpg.py`, `verify.py`, `dirstate.py`(`_changing`), `cmd_impls/clone.py`, `cmd_impls/update.py`, `changegroup.py`와 `bundle2_part_handlers.py`(`_afterlock`).

### 이번 대조에서 고친 것
| hg | hg4j의 문제 | 고침 |
|---|---|---|
| `exchange.pull`의 `with wlock(), repo.lock(), pullop.trmanager:`: 트랜잭션 중단이 잠금 해제보다 먼저 | `FetchCommand.applyBundle`의 롤백(`catch`)이 잠금을 푼 뒤 실행되었고, 잠금 획득 실패 때도 롤백이 실행되어 백업이 없는 dirstate와 fncache를 **삭제**할 수 있었다 | 롤백을 잠금 안쪽 `try`로 옮김. 재현 테스트 `applyingABundleThatCouldNotGetTheLocksTouchesNothing` |
| `rebase.continuerebase`/`abortrebase`: `wlock`, `lock` 순서로 처음부터 | `RebaseCommand.continueRebase`/`abort`가 `lock` 다음 `wlock` 순서였고 상태 파일 확인이 잠금 앞 | 순서와 위치 교정 |
| `unshelve --continue/--abort`: `wlock` 안에서 상태 읽기, 그 뒤 `lock` | 두 잠금을 한꺼번에, 상태 확인은 잠금 앞 | `wlock`, 상태 읽기, `lock` 순서로 분리 |
| `graft --continue/--abort`: `wlock` 안에서 상태 확인 | 상태 확인이 잠금 앞 | 잠금 안으로 |
| `unbundle_files`: `lock`만 | `UnbundleCommand`가 `wlock`도 잡고 dirstate를 백업/복원했다 | `FetchCommand#setWorkingCopyLock(false)`: `wlock`을 잡지 않고 dirstate와 `.hg/dirstate.backup`을 건드리지 않음 |
| `recover`, `verify`: `lock` | 잠금 없음 | `HgRepository#lockStoreWithoutRecovery`로 잠금(hg의 `lock()`은 스스로 복구하지 않음) |
| `_afterlock`: `commit`, `changegroup`/`incoming`, `pushkey`, `txnclose` 훅은 **가장 바깥 잠금이 풀린 뒤** | 사후 훅이 잠금 안에서 실행되었고, 로컬 `push`는 제가 넣은 잠금 때문에 이전보다 나빠졌으며, 서버 `Bundle2Push`는 바깥 잠금 안에서 `pushWithHooks`를 호출해 해제 뒤 실행이 보장되지 않았다 | `HgRepository#afterLock`(스레드별 보유 횟수 + 대기열). `commit`, 로컬 `push`, `push` 클라이언트 훅이 사용. 훅 예외는 기록만 하고 작업을 실패시키지 않음 |
| `clone.py:369`: 대상에 `destwlock = destrepo.wlock()`을 pull과 갱신 내내 | `CloneCommand`가 `wlock` 없이 dirstate를 씀 | 대상 저장소를 만든 직후부터 `wlock` |
| `unbundle -u`/`pull` 갱신은 `wlock` 아래 | `PullCommand.applyBundle`이 `wlock` 없이 dirstate를 씀 | `wlock` 추가 |

dirstate 쓰기 전수 확인: hg는 `wlock` 없이 dirstate를 바꾸면 `ProgrammingError`로 거절한다(`dirstate._changing`). hg4j에서도 임시 계측(쓰기 시점에 `wlock` 보유 여부를 기록)을 넣어 단위 test 전체를 돌려, 제품 코드에서 `wlock` 없이 쓰는 호출자를 찾았다(위의 `CloneCommand`, `PullCommand.applyBundle`). 고친 뒤 재실행에서 남은 것은 비공개 메서드를 리플렉션으로 직접 부르는 테스트와 `setSkipLockAndJournal(true)`를 명시한 테스트뿐이었다. 단위 test가 지나가는 경로에 한정된 확인이다(인터롭 경로는 계측하지 않았다).

### 잠금 대기 방식 (2026-11-04 추가)
hg는 `repo.lock()`/`repo.wlock()`의 `wait`가 기본 true라서 모든 명령이 `ui.timeout`초(기본 600) 동안 기다리며 잠그고, 0이면 즉시 실패한다. 예전 hg4j의 인자 없는 `lockWorkingCopy()`/`lockStore()`는 0으로 고정되어 `commit`, `update`, `rebase`, `strip`, `graft`, `merge`, `remove` 등이 즉시 실패했다. 이제 둘 다 `ui.timeout`을 따른다(`HgRepository#resolvePushLockTimeoutMs`). 테스트 JVM만 시스템 프로퍼티 `hg4j.ui.timeout=0`을 쓰며(`build.gradle`), 저장소의 `[ui] timeout`이 있으면 항상 우선한다. 대기를 전제로 하는 테스트는 hgrc에 timeout을 명시한다.

### hg 전체 잠금 목록의 분류 (`mercurial/`과 `hgext/` 전수 스캔)
**대조 완료(위 표와 앞 절):** commands.py의 모든 명령, cmdutil(amend, revert, abortgraft, unbundle_files, copy), exchange(pull, push, unbundle, gettransaction, 클론번들), merge, update, localrepo(commit, rollback, recover), bookmarks, phases, strip, shelve, hbisect, tags(`tag`), verify, repair(`rebuildfncache` = `GcCommand`), streamclone 서버 쪽 `generatev1/2/3`, clone(대상 `destwlock`), share(`post_share` + 갱신), hgext의 amend, censor, closehead, fetch, gpg, histedit, rebase, lfs(`checkrequireslfs`), clonebundles 클라이언트 쪽 적용(`trypullbundlefromurl`).

**이번에 새로 고친 것:** `CensorCommand`(wlock+lock, 이전에는 잠금 없이 filelog를 제자리에서 고침), `GcCommand`(wlock+lock, 복구 없는 store 잠금), `ClonebundlesCommand.downloadAndApply`(다운로드부터 적용까지 store 잠금), `WorktreeCommand`(대상 wlock), `NarrowCloneCommand`(대상 wlock + narrowspec 쓰기 시 store 잠금), `HgLfsConfig.addRequirement`(store 잠금).

**hg4j에 대응 기능이 없어 비교 대상이 아닌 것:** `absorb`, `automv`, `convert`, `eol`, `fastannotate`, `fix`, `fsmonitor`, `journal`, `keyword`, `largefiles`, `mq`, `narrow tracked`, `phabricator`, `phantom_commits`, `relink`, `remotefilelog`, `sparse`, `split`, `thin`, `transplant`, `uncommit`/`unamend`, `upgrade`/`auto_upgrade`, `unshare`, 스트림 클론 클라이언트 쪽(`consumev*`), `obsolete.pushmarker`, 태그 캐시 쓰기(`tags.write`), 브랜치/리비전 캐시 쓰기, `bundle2.write_repo_sharded_stream_bundles`, `clone --pool`/`share` 풀, 로컬 클론 스토어 복사(`copy_store`), `hgext/clonebundles`의 서버 쪽 자동 생성 잠금(`clonebundles_lock`), `shelve` 삭제/정리, `branch --clean`/`--rev`.

**전용 잠금 테스트가 없는 것:** `ClonebundlesCommand.downloadAndApply`(HTTP 서버가 필요), `WorktreeCommand`와 `NarrowCloneCommand`(새로 만드는 디렉터리라 경합 상대가 없음), `pull`, `resolve`.

### 일치를 확인한 것 (코드를 읽고 같다고 판단)
`commit`(옵션 검사 뒤 `wlock`, `lock`, `precommit`은 잠금 안, `commit` 훅은 해제 뒤), `update`(옵션 검사 뒤 `wlock`, 갱신 전부 안), `strip`(`wlock`+`lock` 안에서 로컬 변경 확인, 갱신 대상 계산, 백업 번들, 자체 트랜잭션), `rebase`(`_origrebase` 전체가 `wlock`+`lock`), `histedit`(시작, `--continue`, `--abort` 모두 `wlock`+`lock`), `merge`(`mergemod._update` 맨 앞 `wlock`), `shelve`(`wlock` 뒤 `lock`+트랜잭션), `unshelve`, `graft`(`wlock`, 커밋 단계에서 `lock`), `tag`, `import`, `bookmark`, `branch`(조회는 무잠금), `phase`(`lock`+트랜잭션), `resolve`, `amend`(`commit` 안), `sign`(`wlock` 안에서 키 확인부터 커밋까지), `rollback`, `push`(연결 뒤 discovery 앞 `lock`), 서버 push 수신(`check_heads` 뒤 번들 처리 직전).

### hg와 다르게 남은 것 (모두 의도적이거나 안전한 쪽)
- `merge --abort`, `revert`, `phase`, `amend`: hg는 병합 상태, `dirstate.parents()`, 리비전 해석 같은 읽기를 잠금 앞에서 하고 hg4j는 더 일찍 잠근다(읽기가 잠금 안이라 안전한 쪽).
- `Bundle2Push`: hg는 `check_heads`(헤드 사전 확인)를 잠금 밖에서 하고 hg4j는 안에서 한다(같은 이유).
- dirstate 보호의 메커니즘: hg는 `changing_files` 컨텍스트가 예외 시 메모리 변경만 버리고 디스크 dirstate는 마지막에 원자적으로 한 번 쓴다. hg4j는 `.hg/dirstate.backup`과 `.hg/wc-journal`로 되돌린다. 보호 효과는 같으나 같은 구현은 아니다.
- `rebase`: hg는 리비전마다 트랜잭션(부분 성공 허용)이거나 `singletransaction`, hg4j는 예외 시 전체를 되돌린다. 잠금 위치와 롤백이 잠금 안에서 일어난다는 점은 같다.
- `sign`: hg는 커밋이 실패해도 `.hgsigs`를 되돌리지 않는다. hg4j는 되돌린다(`SignCommand#restore`).
- `push` 클라이언트: hg는 `LockUnavailable`(권한 없음 등)이면 잠금 없이 계속한다. hg4j는 잠금 획득 실패 시 중단한다.
- hg4j에 없는 기능: `histedit --continue/--abort`, `shelve` 삭제/정리, `branch --clean`, `copy --at-rev`, `hg branch --rev`.

## 알려진 한계 (점검하지 못했거나 고치지 않은 것)

- hg가 잠그지만 hg4j에 대응 명령이 없는 것은 비교에서 뺐다. `Hg.rollback()`의 시그니처가 `HgLockException`을 던지도록 바뀌었다.
- `commit`의 서브레포 준비(`applySubrepoStateBeforeCommit`)는 잠금 전에 실행된다.
- 로컬 `push`가 훅 실패 후 복원하는 원격 dirstate 백업(`HgLocalClient`)은 잠금 전에 읽는다. 이제 호출 전체가 잠금 안이라 실질 위험은 낮다.
- 같은 `HgRepository` 객체를 여러 스레드가 공유하는 경우(리비전로그 캐시, dirstate)의 스레드 안전성은 보장하지 않는다. 동시 사용은 스레드마다 별도 객체를 쓴다.
- HTTP/SSH 서버의 읽기 요청이 쓰기 중인 파일을 보는 경우는 검증하지 않았다(실제 hg는 읽기에서 잠금을 잡지 않고 원자적 파일 교체에 기대며, hg4j도 `SafeFileIO`의 원자적 쓰기를 쓰지만 모든 쓰기가 그런지는 확인하지 않았다).
- 위 표의 hg 쪽은 소스를 읽어 얻었다. 정적 탐색(쓰기가 있는 명령 중 잠금 참조 없음 + 잠금 전 읽기)은 휴리스틱이었다. `shelve`는 오탐으로 확인했고 `graft`는 잠금 안 호출임을 읽어서 확인했다.
- 잠금 순서 교정(교착 제거)은 코드와 실제 hg 소스 대조로 근거를 확인했을 뿐 교착을 재현해 보인 테스트는 없다.

## 2026-11-04 중단된 트랜잭션: 잠금에서 복구하지 않는다 (사용자 결정)

실제 hg는 `repo.lock()`에서 복구하지 않는다. 트랜잭션을 여는 명령이 `store/journal`을 만나면 `abandoned transaction found` + `run 'hg recover' to clean up transaction`으로 중단하고, 읽기 명령과 `update`(wlock만 잡음)는 그대로 동작한다. 복구는 `hg recover`만 한다. 실제 hg 7.2로 확인했다.

예전 hg4j는 스토어 잠금을 새로 잡을 때마다 `checkAndPerformAutoRollback`으로 자동 롤백했다. 선생님 결정(2026-11-04)으로 원본과 같게 바꿨다.

| 잠금 | 동작 |
|---|---|
| `HgRepository.lockStore(...)` | journal이 있으면 `HgAbandonedTransactionException`(`HgLockException`의 하위)으로 거부. 같은 스레드가 이미 쥔 잠금(재진입)은 자기 트랜잭션의 journal이므로 건너뜀 |
| `HgRepository.lockStoreWithoutRecovery(...)` | 확인 없음. `recover`, `verify`, `gc`, `chainsaw-update`(recover를 하려고), 트랜잭션 없이 `lock()`만 잡는 `unshare`, shape 설정 갱신, `hgtagsfnodes1` 쓰기, LFS requires 추가 |

`wlock`도 원본과 같게 바꿨다(선생님 결정): 새로 잡을 때 `.hg/wc-journal`로 복구하던 hg4j 고유 동작을 없앴고, `copy`/`rename`/`remove`/`merge`는 그 journal과 `dirstate.backup` 파일을 더는 만들지 않는다. 실패했을 때 메모리의 이전 dirstate 바이트로 되돌리는 것만 남는다(원본의 `changing_files`로 바꾸는 것은 차후 재작성).
