---
title: hg 코어 기능 격차 구현 계획
status: 진행 중 (2026-11-04 시작)
---

# hg 코어 기능 격차 구현 계획

잠금 점검([[concepts/locking-and-concurrency]])에서 hg에는 있고 hg4j에는 없는 **확장이 아닌 코어 기능**을 가려냈고, 사용자 결정(2026-11-04)으로 이 묶음만 구현한다. 번들 확장(`mq`, `largefiles`, `histedit --continue` 등)은 "Python 확장 시스템 범위 밖 확정(2026-08-31)"을 유지하며 구현하지 않는다.

각 항목의 원칙: hg 소스를 읽고 대조하며 구현한다. 잠금 획득과 해제 위치, 실패 시 롤백 위치까지 hg와 같게 한다. 단위 test로 검증하고 실제 hg와의 비교(인터롭)는 사용자 지시가 있을 때까지 돌리지 않는다.

| # | 항목 | hg 근거 | 상태 |
|---|---|---|---|
| 1 | `shelve --delete`, `--cleanup` | `shelve.py` deletecmd, cleanupcmd (`wlock`) | ✅ 완료 (`ShelveDeleteCleanupTest` 6개) |
| 2 | `branch --clean`, `--force`, 라벨 검증 | `commands.branch`, `scmutil.checknewlabel` | ✅ 완료 (`BranchCommandRulesTest` 5개, 실제 hg 7.2로 문구 확인) |
| 2b | `branch --rev` (`changebranch`) | `cmdutil.changebranch`: 변경집합을 새 브랜치로 다시 만든 뒤 옛 노드를 `strip`/`cleanup_nodes`로 제거 | ✅ 완료 (`BranchRevTest` 7개, 동시에 세 머리를 바꾸는 경쟁 상태 test 포함. 실제 hg 7.2의 출력, `branch_change` 필드, 리비전 번호 배치와 일치) |
| 3 | `unshare` (+ `StoreCopy` = `copy_store`) | `cmd_impls/clone.py` unshare, copy_store | ✅ 완료 (`UnshareCommandTest` 5개) |
| 4 | `copy`/`rename` 전체 포팅: `--after`, `--forget`, `--dry-run`, `-I/-X` 패턴, 여러 원본과 디렉터리 대상, `--at-rev` | `cmdutil.copy` | ✅ 완료 (`CopyEngine` + `CopyAtRev` + `ChangesetRewrite`, `CopyRenamePatternsTest` 22개, `CopyAtRevTest` 9개. 기존 한 파일 전용 `call()`은 호환을 위해 유지) |
| 5 | 태그/브랜치 캐시 쓰기 (`hgtagsfnodes1`, `tags2-*`, `branch2-*`) | `tags.py` write, `branchmap`, `rev_cache` (모두 `repo.lock(wait=False)`: 잠금을 못 잡으면 쓰지 않고 넘어간다) | ✅ 완료: `RepositoryCaches.update`가 `branch2-served`, `tags2-visible`, `hgtagsfnodes1`, `rbc-names-v2`, `rbc-revs-v2`를 실제 hg 7.2와 **바이트 단위로 같게** 기록 (`RepositoryCachesTest` 6개). **계획서의 잠금 설명 정정**: hg 7.2에서 `tags2-*`/`branch2-*`는 잠금 없이 atomictemp, `hgtagsfnodes1`은 `lock(wait=False)`, `rbc-*`는 `wlock(wait=False)`이며 못 잡으면 쓰지 않고 넘어간다(잠금을 끄면 test 실패 확인). 필터가 걸린 저장소(숨김·secret 변경집합)의 `branch2-visible` 등은 repoview 해시가 필요해 쓰지 않는다. 커밋마다 전체 changelog를 읽게 되므로 자동 호출은 하지 않고 호출자가 부르는 API다 |
| 6 | obsolete pushkey (서버) | `obsolete.py` pushmarker (`lock`) | ✅ 완료: `ObsStore`(fm0/fm1 읽기·쓰기, 실제 hg가 쓴 obsstore 바이트와 왕복 일치), `obsolete` 네임스페이스 listkeys(`dumpN`, 정렬, base85)·pushkey(`pushmarker`, `lock` 아래 병합·중복 제거·실패 시 복구), `Base85`, `experimental.evolution`이 `exchange`/`all`일 때만 제공 (`ObsStorePushkeyTest` 8개, 겹치는 마커를 동시에 밀어 넣는 test는 잠금을 끄면 실패 확인). bundle2 `obsmarkers` 파트는 이 항목 범위 밖 |
| 7 | 스트림 클론 클라이언트 | `streamclone.py` consumev1/2/3 (`lock`) | ✅ 완료: v1 `stream_out`과 v2 bundle2 `stream2` 파트를 모두 적용(`StreamCloneClient`, HTTP·SSH 연결에 `streamOut()`/`getStreamBundle()`, 양쪽이 bundle2 스트림을 하면 v2 우선), `pull/clone --stream`, 저장소 `lock` 아래 전송·요구사항 갱신·실패 시 롤백 (`StreamCloneClientTest` 8개, 잠금을 끄면 test 실패 확인) |
| 8 | 로컬 클론 스토어 복사와 풀 | `cmd_impls/clone.py` copy_store, clone_with_share | ✅ 완료: `CloneCommand`가 로컬 소스를 `StoreCopy`로 복사(소스 `lock(wait=False)`를 못 잡거나 secret 변경집합이 있거나 `--pull`이면 pull), 작업 디렉터리 requirement 제거, 북마크 복사 (`LocalCloneCopyTest` 5개, 복사 중 소스가 계속 커밋하는 경쟁 상태 test 포함). 공유 풀(`clone_with_share`)은 `share` 확장 범위라 제외 |
| 9 | bundle2 sharded stream bundles | `bundle2.py` write_repo_sharded_stream_bundles | 🔶 분석 완료: [[concepts/store-shapes]]. 사용자 결정(2026-11-04)으로 Java 포팅. 순서 A(지문·트리·패턴 블록, 완료) -> B(설정·StoreShards, 완료) -> C(와이어·clone --shape, 완료) -> D(admin 명령, 완료: shapes lock/store lock) -> E(sharded 번들 생성·선택·적용, 완료, 실제 Rust hg 번들·서버와 비교) |
| 10 | `upgrade` (`debugupgraderepo`, auto upgrade) | `upgrade.py`, `upgrade_utils/` | 다음 메이저 버전으로 연기 (사용자 결정 2026-11-04, CLI 지원을 추가할 때 함께) |
| 11 | `graft --to` (메모리 graft, EXPERIMENTAL) | `cmd_impls/graft.py` `_graft_revisions_in_memory` (`lock`) | ✅ 완료: `GraftInMemory`(`graft --to`): 작업 사본·dirstate를 건드리지 않고 `lock`(store)만 잡은 채 `StoreRollback` 트랜잭션 안에서 각 변경집합을 첫 부모를 조상으로 메모리 병합해 커밋, 충돌이면 전부 롤백하고 `cannot graft in memory: merge conflicts`. 결과 노드가 실제 hg 7.2와 같음(`GraftInMemoryTest` 10개, 입력이 같으면 해시도 같다). `TreeMergeCommand.setBase`, `StoreRollback`(저널 기반 롤백, `GraftCommand`의 private 로직을 재사용 가능하게 옮김) 추가. 남은 단순화: 원본 변경집합의 복사·이름 변경 기록은 옮기지 않는다 |
| 12 | remotenames (`experimental.remotenames`) | `logexchange.py` saveremotenames (`wlock`), pull/push/clone 후 호출 | ✅ 완료: `RemoteNames`가 pull/push 후 `afterLock`으로 `wlock` 아래에서 `.hg/logexchange/{bookmarks,branches}`를 저장, 형식은 실제 hg 7.2와 동일 (`RemoteNamesTest` 5개, 8개 스레드 동시 저장 test는 잠금을 끄면 실패 확인) |
| 13 | `admin::` 명령들 (chainsaw-update, 저장소 shape 관련) | `admin/chainsaw.py`, `admin_commands.py`, `localrepo.store_shapes_lock` | ✅ 완료(shape 무관 명령 전부): `admin::narrow-server`/`narrow-client`와 `store-shapes-lock`(`NarrowServerAdmin`), `admin::chainsaw-update`(`ChainsawUpdateCommand`: 잠금 파일을 먼저 지우고 `wlock`->`lock` 아래에서 recover, pull, purge, update. `ChainsawUpdateCommandTest` 7개). 이를 위해 코어 `pull -r REV`(`PullCommand.setRevisions`, `lookup` 와이어, `PullRevisionsTest` 5개)와 `purge --all/--files`를 추가. `admin::verify`(`AdminVerifyCommand`: 체크 등록 틀과 7.2의 유일한 체크 `working-copy.dirstate`, 잠금 없음, 출력은 실제 hg와 같음, `AdminVerifyCommandTest` 7개). 원본의 `error.getsimilar` 힌트는 옮기지 않았고, 원본이 `-o dirstate:x=1`에서 ProgrammingError로 죽는 것은 재현하지 않고 의도된 오류 문구를 낸다 |
| 14 | hg 관리 subrepo의 재귀 연산 (`-S`: add, addremove, forget, remove, revert, push/pull의 저장소 검사) | `subrepo.py` hgsubrepo (`wlock`, `lock`) | ✅ 완료: `SubrepoRecursion`(subdirmatcher, 하위 저장소 자체 `wlock`)으로 add(`-S`, 명시 파일만)/forget(명시 파일만)/remove(`-S`, 삭제 전에 substate 읽기)/revert(2단계 + `.orig` 백업)/addremove(`-S`) 재귀, `RemoveCommand`/`RevertCommand`의 선택 단계 전체를 hg처럼 `wlock` 아래로 이동 (`SubrepoRecursionTest` 10개, 잠금을 끄면 경쟁 test 실패 확인). git/svn subrepo는 hg의 abstractsubrepo처럼 건너뜀 |
| 15 | `strip` 충실도 | `repair.strip`, `strip.py` | ✅ 완료: 자손만 제거하고 나머지는 번들로 보존해 다시 적용(phase 복원), 북마크는 제거되는 노드만 이동, 갱신 대상 선택(`_findupdatetarget`), 작업 사본 갱신, 지역 변경 거절과 `-f`/`-k` (`StripKeepsUnrelatedRevisionsTest` 6개). 백업 번들(`.hg/strip-backup/<12자리>-<sha1 앞 4바이트>-<주제>.hg`)도 구현. 남음: hg는 obsolescence가 꺼져 있으면 마커를 쓰지 않는데 hg4j는 `prune` 마커를 쓴다 |
| 16 | 저장소 파일의 **하드링크 끊기** | `vfs.py`: `nlink > 1`이면 쓰기 전에 복사본으로 교체 | ✅ 완료: `SafeFileIO.breakHardLinks`를 `Revlog`, `fncache`, `NodeMapFile`, obsstore, 인덱스 제자리 수정에 적용. 실제 hg의 로컬 `clone`(기본 하드링크)이 만든 저장소에 hg4j가 쓰면 원본이 망가지던 기존 결함 (`UnshareCommandTest`) |

## 사용자 결정 (2026-11-04)
- 확장(`hgext/`)은 구현하지 않는다. 코어(`mercurial/`)는 빠뜨리지 않는다. 11~14번은 이 기준으로 계획에 추가했다.
- 제외: `debug*` 명령(내부 진단용)과 `hg debug::` 계열, 테스트용 `testing/`. 코어에 있지만 사용자 기능이 아니라고 판단했으며, 포함해야 하면 알려 달라.

## 잠금 원칙 (2026-11-04 사용자 지시)
이 계획의 목적은 잠금, 해제, 재잠금을 hg와 같게 반영해 극심한 경쟁 상태에서도 동작이 보존되게 하는 것이다. 새 기능은 모두 hg 코드의 잠금 위치를 따르고, 경쟁 상태 test를 함께 만들며(잠금을 끄면 실패하는지 확인), 필요하면 hg보다 안전한 쪽으로만 다르게 한다.
- `copy --at-rev`: `commands.copy`의 `wlock`, 그 안에서 `lock`을 먼저 잡고 `precheck`부터 재작성과 정리까지(hg의 `rewriteutil.precheck`는 "잠금을 잡은 뒤 호출"해야 한다고 명시한다).
- `branch --rev`: `commands.branch`의 `wlock` 안에서 `changebranch`의 `lock`.
- `strip`: `wlock`+`lock` 안에서 보존할 리비전을 번들로 만들고, 자르고, 다시 적용한다. 다시 적용하는 `applyBundle`은 같은 스레드의 재진입 잠금을 쓴다.

## 남은 순서 (2026-11-04)
(12, 8, 14, 7, 6, 5 완료)(hg 관리 subrepo `-S`) → 7(스트림 클론 클라이언트: v1 `stream_out`과 v2 번들2 `stream2` 파트, HTTP·SSH·로컬 세 연결 구현에 메서드 추가 필요) → 6(obsolete pushkey) → 9(sharded stream bundles) → 10(`upgrade`) → 13(`admin::`) → 11(`graft --to`, 메모리 병합 엔진이 필요해 가장 큼).
7번의 와이어 정확성은 실제 hg와의 비교(인터롭)로만 확인되므로, 인터롭 재개 전까지는 hg4j 서버와의 왕복으로만 검증한다.
