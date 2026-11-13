---
title: 잠금 대조표 (hg 원본 대 hg4j)
status: 진행 중 (2026-11-04 시작). 루프로 한 칸씩 채우고 맞춘다.
---

# 잠금, 해제, 재잠금 대조표

원칙: hg 원본의 `wlock`/`lock`/트랜잭션 위치와 순서를 로직 기준으로 맞춘다(래퍼로 뭉뚱그리지 않는다). 의도적 차이는 작업 중에 선생님께 묻는다. 관련 문서: [[concepts/locking-and-concurrency]].

## 결정된 사항 (2026-11-04, 선생님)

| 항목 | 결정 | 상태 |
|---|---|---|
| 중단된 트랜잭션 | 잠금에서 복구하지 않고 `abandoned transaction found`로 거부, 복구는 `recover` | ✅ 완료 (`HgAbandonedTransactionException`) |
| `bookmarksinstore` 요건: bookmarks 파일 위치 | 원본과 동일하게 (`bookmarks.bookmarksvfs`: 요건이면 `.hg/store/bookmarks`, 아니면 `.hg/bookmarks`; `bookmarks.current`는 항상 `.hg`) (사용자 결정 2026-11-13: 지원 쪽으로) | ✅ 코드 완료, **컴파일·테스트 미실행**: `HgRepository#isBookmarksInStore`/`getBookmarksFile()`로 읽기·쓰기 지점 11곳을 모음(`BookmarkCommand`, `RepoSupport` 2곳, `HgLocalClient` 2곳, `Bundle2Push` 스냅샷, `StripCommand`, `GraftInMemory`, `HgRevsetEngine`, `RollbackCommand`, `CommitCommand`). **`Hg.open()`의 허용 목록에 `bookmarksinstore`가 없어 이 요건 저장소를 "unsupported repository requirement"로 거부하던 것**을 추가([[known-bugs-registry]]). `InitCommand#setBookmarksInStore`(실제 hg가 `use-share-safe=no`와 함께 이 요건을 받아들임을 실행으로 확인, 단일 `.hg/requires`에 기록). stream clone, clone bundle, 로컬 복제는 요건을 쓴 뒤 열려 있던 저장소 객체의 플래그가 낡아 이후 받는 bookmarks가 `.hg/bookmarks`로 가는 문제를 `reloadBookmarksInStoreRequirement()`로 해결(`FetchCommand` 2곳, `ClonebundlesCommand`, `CloneCommand`; 실제 hg의 `STREAM_IGNORABLE_REQUIREMENTS`에 이 요건이 없어 stream으로 요건이 전달됨을 소스로 확인). hg4j 자체 롤백 백업(`undo.backup.bookmarks`)은 bookmarks 파일 옆(요건이면 store)에 둠 — 이름은 hg4j 고유(실제 hg는 `undo.backupfiles`/`*.bck`로 달라 hg와 hg4j의 롤백 정보는 서로 읽지 못하며, 이는 이 요건과 무관한 기존 차이). 테스트: 단위 `BookmarksInStoreRequirementTest`(4개), interop `BookmarksInStoreRealHgInteropTest`(7개) 모두 **작성만 함, 실행하지 않음**(사용자 지시) [[decisions/server-push-concurrency-roadmap]] |
| `pushkey` bookmarks의 `wlock` | 원본과 동일하게 (`bookmarks.pushbookmark`: `wlock`은 요건이 없을 때만, `lock`은 항상) | ✅ 코드 완료, 검증 전(`HgLocalClient#pushkey`). 다른 스레드가 `wlock`을 쥔 상태로 요건 저장소는 성공, 일반 저장소는 거부되는지 보는 테스트가 있음(미실행) |
| bundle2 `unbundle`(서버 푸시)의 `wlock` | 원본은 `wlock`을 요건이 없을 때만 잡음(`exchange.unbundle`의 `gettransaction`) | ⚠️ **의도적 이탈, 사용자 확인 필요(2026-11-13)**: hg4j는 `Bundle2Push`와 `HgLocalClient#pushWithHooks`에서 항상 `wlock`을 잡는다(더 엄격해서 데이터는 안전, 동시성만 hg보다 낮음). 그냥 빼면 안 되는 이유: (1) 실패 되돌리기가 dirstate와 `bookmarks.current`를 쓴다(`Bundle2Push.Snapshot`, `HgLocalClient#pushWithHooksLocked`의 dirstate 백업·복원, `RollbackCommand`), 원본의 unbundle은 이를 건드리지 않는다. (2) `RollbackCommand`와 `FetchCommand`(기본 `workingCopyLock=true`)가 `wlock`을 안에서 다시 잡아, 바깥이 store 락만 쥐고 있으면 `wlock`→`lock` 순서가 깨진다. 설계는 [[decisions/server-push-concurrency-roadmap]] 6-1절(S0~S5)에 있고 코드 변경은 아직 없음 (`PullCommand.applyBundle`도 `wlock`을 잡는 계층이라 푸시 한 건에 다섯 계층) |
| dirstate 변경을 `changing_files`/`changing_parents`로 묶기 | 원본과 동일하게 (사용자 결정 2026-11-04) | ✅ 저장 계층(v2 mmap 읽기, 덧붙이기 쓰기, docket 마지막 교체, 비율 0.5), 컨텍스트(`HgRepository.changingFiles/changingParents/DirstateChange`), 명령 이전(add, forget, remove, copy/rename, revert, update, merge, backout, bisect, pull, clone, commit, graft, histedit, rebase, strip, unshelve) 완료. `dirstate.backup` 복사/복원과 저널의 `dirstate` 항목 제거. 근거: [[concepts/dirstate-original]]. 의도적 차이: shelve(보관 단계)는 원본도 자체 컨텍스트가 없고 내부의 commit/revert가 각자 연다(감싸면 보관용 임시 커밋의 부모가 남아 unshelve가 깨져 감싸지 않음), status의 stat 보정은 `running_status`처럼 컨텍스트 없이 `wlock(wait=False)`로 쓴다 |
| (계획 9 E단계) sharded stream bundle 생성(`wlock`→`lock`), clonebundle shape/샤드 선택, 클론번들 안의 stream 번들 적용 | 원본과 동일하게 | ✅ 완료: 생성은 실제 Rust hg와 바이트 일치, 선택은 400건 일치, 클라이언트 적용은 hg4j 서버와 실제 Rust hg 서버 양쪽에서 확인 [[concepts/store-shapes]] |
| (계획 10) `upgrade` (`debugupgraderepo`, 자동 업그레이드) | 다음 메이저 버전으로 연기 (사용자 결정 2026-11-04: CLI 지원을 추가할 때 함께) | 보류, 이번 범위에서 제외 |
| `rebase` 재개 가능한 상태 파일 | 원본과 동일하게 | ✅ 잠금 구조·상태 파일·노드별 커밋·continue/abort를 원본 구조로 교체, 실제 hg와 노드 해시까지 일치 [[concepts/rebase-original]] 8절. 남은 옵션은 아래 `rebase` 하위 항목 |
| `rebase --stop` | 원본과 동일하게 (wlock→lock, restorestatus, clean_update, finish) | ✅ 완료, 실제 hg와 로그·작업 사본·상태 파일 일치 (`RebaseOriginalParityTest`). 남은 차이 복원은 사용자 지시 2026-11-04 |
| `rebase` `-r/-b/-d` 와 다중 목적지(`SRC`/`ALLSRC` 별칭) | 원본과 동일하게 (`_definedestmap`) | ✅ 완료(`addSourceRevset/addBaseRevset/addRevisionRevset/setDestination`, `rewriteutil.precheck` 포함), 8가지 조합이 실제 hg의 로그·작업 사본·메시지·거부와 일치. `-d` 생략 시 기본 목적지(`destutil._destrebase`)는 `RebaseCommand.defaultDestination`으로 구현됨 |
| `rebase --collapse` (+ `last-message.txt`, `Collapsed revision`) | 원본과 동일하게 | ✅ 완료(`setCollapse`, `setMessage`): 노드마다 커밋하지 않고 작업 사본에 누적 병합, 끝에서 한 커밋, 외부 부모, 충돌 후 continue/abort까지 실제 hg와 로그·상태 파일·`last-message.txt` 일치. 메시지를 안 주면 원본은 편집기를 열지만 hg4j는 제안 문구를 그대로 쓴다(편집기 없음) |
| `rebase.singletransaction` | 원본과 동일하게 | ✅ 완료(`setSingleTransaction`, hgrc `[rebase] singletransaction`): 하나의 트랜잭션(저널)으로 모든 커밋과 상태 정리, 옛 노드 strip은 트랜잭션이 닫힌 뒤(`repair.delayedstrip`, 원본 확인 [[concepts/strip-original]] 6절. 처음 "strip까지 한 트랜잭션"으로 구현했던 것은 제 설명 착오였고 바로잡음), 충돌이면 닫고 그때 상태 파일을 씀, 그 밖의 실패는 모든 커밋을 되돌림. 실제 hg와 로그·상태 파일 일치 |
| `rebase --dry-run`/`--confirm` | 원본과 동일하게 | ✅ 완료(`setDryRun`, `setConfirm`): wlock→lock 안에서 인메모리 병합·커밋을 되돌릴 수 있는 트랜잭션에 하고 항상 abort. 깨끗한 스택과 충돌(`merging f.txt`, `hit a merge conflict`)의 출력이 실제 hg와 일치, 더러운 작업 사본도 그대로. `--collapse`의 dry-run은 노드별 커밋으로 대신 계산(충돌 판정이 드물게 다를 수 있음) |
| `strip` (`hgext/strip.py` → `mercurial/strip.py` → `repair.strip`) | 원본과 동일하게 (사용자 결정 2026-11-04: "원본처럼 되돌리지 않음") | ✅ 핵심 완료 [[concepts/strip-original]]: 워킹카피 갱신을 저장소 수술 앞으로(부모가 지워질 때만, 그때만 로컬 변경 검사), 저널에 잘린 크기를 먼저 기록(크래시 시 `hg recover`가 자르기를 마저 끝냄), 자르기 → 보존 번들 재적용 → 북마크 순서, 실패하면 되돌리지 않고 `strip failed, backup bundle stored in`/`unrecovered changes stored in`/`hg unbundle` 안내와 임시 번들 `.hg/strip-backup/*-temp.hg` 보존, 폐기(prune) 마커 안 씀. 실제 hg와 로그·작업 사본·북마크·verify·strip-backup 파일 수 일치(`StripOriginalParityTest`). 남은 것: 여러 리비전과 `-B`/`--soft`, v2(docket) revlog의 저널 크기 기록, 세 트랜잭션을 각각 별도 저널로 나누기 |
| `rebase` 진행 메시지(북마크·태그·tip 표시), `sort(-topo)` 순서, `--keep` 시 북마크 이동, `-d` 생략 시 기본 목적지(`destutil.destmerge`) | 원본과 동일하게 | ✅ 완료. 진행 메시지는 `_ctxdesc`(북마크 → 태그, tip 포함), 순서는 `dagop.toposort` 이식(실제 hg 벡터 120건 일치, 24건은 오름차순과 다름), `--keep`도 북마크는 새 노드로 이동, 기본 목적지는 활성 북마크/브랜치 헤드 규칙과 오류 문구까지(`-s`,`-b`,`-r` 생략 조합 포함 비교). 빈 후계(`rewrite.empty-successor=skip` 기본)도 원본처럼 `note: not rebasing ..., its destination already has all its changes`와 함께 건너뛰고 옛 노드만 제거(북마크는 부모로 이동), 실제 hg와 메시지·로그·북마크 일치 |
| `sign` 복원 방식 | 원본과 동일하게 | ✅ 완료: `wlock`만 잡고(커밋이 store lock을 스스로 잡고 풀음), 실패해도 `.hgsigs`를 복원하지 않으며, 커밋은 `.hgsigs`만(`match=.hgsigs`, 다른 변경은 커밋 안 됨, 이전의 거부 제거), `.hgsigs`가 변경됐거나 추적되지 않으면 원본 문구로 거부, 이미 추적 중이면 add 생략. 의도적 차이로 남은 것: `--force`가 원본에서는 `UnboundLocalError`로 죽는데 hg4j는 동작함(원본 버그). 단위 test는 gpg 키링이 필요해 interop 쪽에만 있어 이번에는 실행하지 않음 |
| push 클라이언트의 잠금 실패 처리 | 원본과 동일하게 | ✅ 완료: 원본 `exchange.push`처럼 소스 저장소 `lock()`을 `ui.timeout`만큼 기다리고, `LockUnavailable`이면 push를 중단하지 않고 로컬 단계 갱신만 건너뛰며 `cannot lock source repo, skipping local public phase update`를 알림(버려진 트랜잭션은 그대로 중단). 이 과정에서 push가 로컬 단계(phase)를 갱신하지 않던 누락도 발견해 구현(`_pushsyncphase`/`_localphasemove`: 출판 원격이면 푸시한 변경집합이 로컬에서도 public, 비출판이면 원격 draft 루트 아래는 제외). 실제 hg와 로컬 단계 일치, 잠금 불가 시 push 성공+단계 유지+메시지(`PushPhaseParityTest`). 남은 것: 원본의 `experimental.bundle2.pushback` 때 wlock 선취득, 비출판 원격에서 `draft`로 되돌리는 이동과 원격 단계 pushkey |
| `merge --abort`, `revert`, `phase`의 읽기 순서 | 원본과 동일하게 | ✅ 완료: `merge --abort`는 dirstate·merge state를 잠금 전에 읽고(되돌아갈 노드도 그때 정함, `aborting the merge, updating back to ...` 메시지) 작업 사본 갱신에서만 wlock, `revert`는 두 번째 부모 검사와 `-r` 해석을 잠금 전에, `phase`는 리비전 해석을 잠금 전에 하고 **조회(변경할 단계 없음)는 잠금을 전혀 잡지 않으며** 변경만 store lock (`ReadBeforeLockTest`). 남은 것: `revert`의 "파일 지정 없음" 힌트(dirty/`hg update N` 문구)와 `phase` 변경 후 `cannot move N changesets to a higher phase` 경고 문구 |
| 읽기 명령의 캐시·dirstate 쓰기 (`status`의 `wlock(wait=False)` 등) | 원본과 동일하게 | ✅ 완료. `status`: 원본 `_poststatusfixup`처럼 mtime만 바뀌고 내용은 같은 파일을 clean으로 보고(이전 hg4j는 modified로 잘못 보고함) 보정된 stat을 `wlock(wait=False)` 아래 dirstate에 기록, 잠금이 없으면 쓰지 않고 답만 함, 실제 hg가 그 dirstate를 읽어 `C`로 봄(`StatusFixupTest`). 병합된(`m`) 항목은 커밋 전까지 modified(실제 hg `hg status`로 확인, 이전 테스트가 반대로 고정하고 있었음). 두 경로(빠른 길, treeFilter) 모두 같은 판정. 다른 읽기 명령: 상태를 계산하는 `summary`/`identify`는 `status`를 거쳐 같은 보정을 받고, `branches`/`tags`/`heads`/`log`의 캐시(branch2, tags2, hgtagsfnodes1 `lock(wait=False)`, rbc)는 `RepositoryCaches`로 이미 일치(이전 틱), `diff`/`log`는 dirstate를 건드리지 않음 |
| (후속) push: 비출판 원격으로 푸시할 때 원격 단계를 `pushkey phases`로 갱신, `experimental.bundle2.pushback`일 때 `wlock` 선취득 | 원본과 동일하게 | ✅ 완료: 비출판 원격이면 푸시한 변경집합 중 로컬에서 이미 public인 것의 머리를 `pushkey phases`(draft→public)로 알림(실패하면 `updating X to public failed!`), 서버 쪽 `phases.pushphase`도 구현(store lock만, 현재 단계가 old와 같으면 낮춤, 이미 new면 성공, 아니면 거부), `pushback` 설정이 켜지면 wlock 다음 lock 순서로 선취득하고 역순 해제, 실패하면 둘 다 없는 것으로 보고 단계 갱신만 건너뜀. 실제 hg와 원격·소스 단계 일치(`PushPhaseParityTest` 5개). 아무것도 밀지 않은 push도 `fallbackoutdatedphases`(원격 draft 루트 중 여기서 public인 것의 범위의 머리)를 원격에 public으로 알림(실제 hg와 일치). 남은 것 없음 |
| (후속) `rebase --collapse`의 dry-run을 노드별 커밋 대신 누적 인메모리 병합으로 | 원본과 동일하게 | ✅ 확인 결과 차이 없음: 누적 병합은 지역 쪽이 "목적지+앞선 노드들의 결과", 기준은 옛 부모라서 노드별 커밋 체인과 병합 입력이 같다. 깨끗한 경우와 충돌 경우 모두 실제 hg의 `rebase -n --collapse` 출력과 일치(`RebaseOriginalParityTest`) |
| (후속) `strip`: 여러 리비전 지정, `-B/--bookmark`, `--soft`, v2(docket) revlog의 저널 크기 기록, 세 트랜잭션을 각각 별도 저널로 | 원본과 동일하게 | ✅ 여러 리비전·revset, `-B`(그 북마크만 닿는 변경집합 제거, 다른 북마크와 노드를 공유하면 북마크만 삭제, `bookmark 'X' deleted`, `bookmark 'X' not found`), 빈 집합 거부와 `--permit-empty-revset`, 명령은 wlock 아래에서 집합을 정하고 본체가 store lock을 잡는 순서까지 완료(실제 hg와 로그·북마크·작업 사본·메시지·verify 일치). `--soft`는 실제 hg도 기본 저장소에서 예외로 죽는(archived 단계에 internal-phase 요구사항 필요) 기능이라 hg4j는 명확한 오류로 거부(의도적 차이). 세 트랜잭션은 자르기(저널), 보존 번들 적용(`applyBundle`의 저널), 북마크·단계 갱신(원자적 파일 쓰기)로 나뉨. v2(docket) revlog도 저널에 "trunc" 줄과 자른 뒤의 도켓을 담아 크래시 뒤 `recover`가 자르기를 마저 끝냄(실제 hg와 v2 changelog strip 일치, 중단된 strip을 `recover`한 뒤 `hg verify` 깨끗). 남은 것 없음 |
| (후속) `sign`: gpg 키링을 쓰는 단위 테스트(잠금 경합, 실패 시 `.hgsigs` 유지) | 검증 | ✅ 완료(사용자 승인 2026-11-04: 실제 gpg 사용): `SignOriginalBehaviorTest` 4개 — 커밋은 `.hgsigs`만, store lock을 남이 쥐면 서명은 쓰이고 커밋만 실패하며 파일은 그대로, wlock을 남이 쥐면 아무것도 쓰기 전에 중단, 변경된/추적 안 된 `.hgsigs` 거부 문구 |
| (후속) `rebase` 편집기: 메시지를 안 준 `--collapse`에서 원본은 편집기를 연다 | 원본과 동일하게 | ✅ 완료(사용자 결정 불필요: hg4j는 편집기를 호출자가 주는 콜백으로 둠): `setMessageEditor`, `setEdit`(`-e`), 메시지 없는 collapse는 항상 편집, 빈 결과는 `empty commit message`. 실제 hg(`HGEDITOR` 스크립트)와 로그 일치 |
| (후속) `revert`: 파일 지정 없음 오류의 힌트 문구(dirty/`hg update N`) | 원본과 동일하게 | ✅ 완료 (`no files or directories specified` + 병합/dirty/다른 리비전별 힌트) |
| (후속) `phase`: 변경 뒤 `cannot move N changesets to a higher phase, use --force` 경고 문구와 종료 코드, `phase changed for N changesets` | 원본과 동일하게 | ✅ 완료(`getMessages`/`getExitCode`/`getNotes`) |
| `wlock` 획득 시 `.hg/wc-journal` 복구 (hg에 대응 없음) | 원본처럼 없앰 | ✅ 완료: `lockWorkingCopy`는 복구하지 않고, `copy`/`rename`/`remove`/`merge`/`CopyEngine`은 `.hg/wc-journal`과 `.hg/dirstate.backup`을 만들지 않는다(메모리의 이전 dirstate 바이트로 실패 시 되돌리는 것만 남음) |

## 명령별 대조

각 행: hg 원본 명령, 원본의 잠금과 트랜잭션, hg4j 현황, 판정(같음 / 다름 / 의도적). 읽기 명령 포함.

쓰기 명령은 이전 점검([[concepts/locking-and-concurrency]])과 이번 루프에서 소스를 읽고 맞춘 결과, 읽기 명령은 아래에 정리한다. "같음"은 원본 소스를 읽고 잠금 종류, 순서, 위치를 대조해 맞춘 것이다.

### 쓰기 명령

| hg4j 명령 | hg 원본의 잠금과 트랜잭션 | 판정 |
|---|---|---|
| `AddCommand`, `AddremoveCommand`, `ForgetCommand`, `RemoveCommand`, `CopyCommand`, `RenameCommand`, `ResolveCommand`, `UpdateCommand`, `RevertCommand` | `wlock` (revert는 `cmdutil.revert`가 `wlock`, 인자 검사는 잠금 전) | 같음 |
| `MergeCommand` (`abort` 포함) | `wlock` (`--abort`는 dirstate·merge state를 잠금 전에 읽고 `clean_update` 안에서 `wlock`) | 같음 |
| `CommitCommand`, `AmendCommand` | `wlock` 다음 `lock`, 트랜잭션 `commit` | 같음 |
| `BackoutCommand`, `CensorCommand`, `HisteditCommand` | `wlock` 다음 `lock` | 같음 |
| `GraftCommand` | `wlock`(+ 커밋 단계 `lock`), `--to`는 `lock`+트랜잭션만 | 같음 |
| `RebaseCommand` | `wlock` 다음 `lock` 한 번으로 명령 전체, 노드별 트랜잭션(`singletransaction`이면 하나), strip은 트랜잭션 뒤 | 같음 (이번 루프) |
| `StripCommand` | 명령 `wlock`, 본체 `wlock`+`lock`, 세 트랜잭션, 실패해도 되돌리지 않음 | 같음 (이번 루프) |
| `SignCommand` | `wlock`만, 커밋이 `lock`을 스스로 | 같음 (이번 루프) |
| `TagCommand`, `ImportCommand`, `RollbackCommand` | `wlock` 다음 `lock` | 같음 |
| `BookmarkCommand` (변경) | `wlock`, `lock`, 트랜잭션 `bookmark` (목록은 잠금 없음) | 같음 |
| `BranchCommand` | 이름 정하기/`--clean`: `wlock`; `--rev`: `wlock`, `lock`, 트랜잭션 `branches` | 같음 |
| `BisectCommand` | `wlock` (상태 저장), 조회는 잠금 없음 | 같음 |
| `PhaseCommand` | 조회는 잠금 없음, 변경은 `lock`+트랜잭션 `phase` | 같음 (이번 루프) |
| `ShelveCommand` | shelve: `wlock` 다음 `lock`+트랜잭션, unshelve: `wlock` 직후 `lock`+트랜잭션 | 같음 |
| `PullCommand`, `FetchCommand`, `UnbundleCommand` | pull: `--update`이면 `wlock`, `exchange.pull` 안 `lock`; unbundle: `lock`만 | 같음 |
| `PushCommand` | `lock` (선택적으로 `wlock` 선취득), 실패해도 push 계속, 로컬 단계 갱신만 건너뜀 | 같음 (이번 루프) |
| `CloneCommand`, `NarrowCloneCommand` | 대상 `wlock`(pull과 갱신 내내), 로컬 복제 `lock` | 같음 |
| `RecoverCommand`, `VerifyCommand`, `GcCommand`, `UnshareCommand`, `ChainsawUpdateCommand`, `ClonebundlesCommand` | `lock`(복구 없이) 또는 `wlock`+`lock` | 같음 (`lockStoreWithoutRecovery`) |
| `PurgeCommand`, `InitCommand`, `ArchiveCommand`, `BundleCommand` | 잠금 없음 | 같음 |
| `StatusCommand` | 읽기, 끝에서 `wlock(wait=False)`로 dirstate 보정 | 같음 (이번 루프) |

### 읽기 명령

| hg4j 명령 | hg 원본 | 판정 |
|---|---|---|
| `LogCommand`, `DiffCommand`, `CatCommand`, `AnnotateCommand`, `ManifestCommand`, `FilesCommand`, `LocateCommand`, `GrepCommand`, `ParentsCommand`, `TipCommand`, `RootCommand`, `PathsCommand`, `ExportCommand`, `IncomingCommand`, `OutgoingCommand`, `HeadsCommand`, `BranchesCommand`, `TagsCommand`, `SigsCommand`, `SigCheckCommand`, `RevsetCommand`, `DescribeCommand`, `TreeCommand` | 잠금 없음. 캐시(branch2, tags2, rbc, hgtagsfnodes1)는 `wlock(wait=False)`/`lock(wait=False)` 또는 잠금 없이 원자적 쓰기 | 같음 (`RepositoryCaches`, 이전 루프) |
| `IdentifyCommand`, `SummaryCommand` | 작업 사본 상태를 계산하며 `status`와 같은 dirstate 보정 | 같음 (`StatusCommand`를 거침) |

### hg 본체에 대응이 없는 hg4j 고유 명령

`WorktreeCommand`, `TreeMergeCommand`, `SidedataChangedFilesCommand`, `AdminVerifyCommand`, `LfsUploadCommand`, `SubrepoCommand`: 대조할 원본 잠금이 없음(확장 또는 hg4j 고유). 각자 자기 저장소의 쓰기에 필요한 잠금을 잡는다.

