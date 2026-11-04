---
title: rebase 원본(hgext/rebase.py) 소스 분석과 hg4j 재작성 계획
status: 분석 2차 완료 (2026-11-04). 코드는 아직 바꾸지 않았다. dryrun, obsolete, pull --rebase, mq는 범위 밖.
---

# rebase: 원본 분석

선생님 결정(2026-11-04): hg4j의 `rebase`를 원본과 같게 맞춘다([[lock-parity-matrix]]). 코드를 옮기기 전에 소스 분석을 먼저 쓴다. **포팅 중 이 문서와 다른 점을 발견하면 코드보다 문서를 먼저 고친다.** 원본: `/home/jiho/yona-convert/hg/hgext/rebase.py` (2281줄).

## 1. hg4j 현황과 원본의 차이

| 항목 | 원본 | hg4j (`RebaseCommand`, 1064줄) |
|---|---|---|
| 입력 | `-s`/`-b`/`-r` 여러 개, `-d`, `--collapse`, `--keep`, `--keepbranches`, `-m`, `-t`, `--auto-orphans`, `--dry-run`/`--confirm` | 소스 노드 1개 + 대상 노드 1개 |
| 커밋 단위 | **노드마다 별도 트랜잭션**(`singletransaction` 꺼짐이 기본), 중간에 멈춰도 앞부분은 남음("partial success") | 전체를 한 번에 하고 실패하면 스토어 전체를 물리적으로 되돌림(`rebase-backup`) |
| 상태 파일 | `.hg/rebasestate` (아래 2절), 노드마다 갱신 | `.hg/rebasestate-hg4j` (자체 형식, 충돌로 멈출 때만) |
| 병합 | 작업 사본에서 `mergemod`로 병합, 충돌이면 `InterventionRequired` | 자체 3-way |
| 잠금 | 전체를 `with repo.wlock(), repo.lock()` 안에서 | `wlock` -> `lock` (맞음) |
| abort | 리베이스된 노드를 `repair.strip`(백업 번들 포함), 작업 사본을 `originalwd`로 | 백업 디렉터리에서 스토어 복원 |

## 2. 상태 파일 `.hg/rebasestate` (원본 형식)

한 줄씩, 해시는 전부 40자리 hex:

```
0: originalwd (리베이스 시작 때의 작업 사본 부모)
1: (비어 있음. 옛 버전은 dest)
2: external (collapse의 외부 부모, 없으면 -1)
3: collapse (0/1)
4: keep (0/1)
5: keepbranches (0/1)
6: activebookmark (없으면 빈 줄)
7~: <oldrev>:<newrev>:<destnode>   # 변경집합마다 한 줄
```

- `newrev`는 새 노드의 hex, 아직 할 일이면 `-1`(`revtodo`), 다른 음수는 특수값(`nullmerge -2`, `revignored -3`, `revprecursor -4`, `revpruned -5`; 읽을 때 `legacystates`는 건너뜀).
- 6번 줄은 "나중에 추가됨": 81자이고 `:`가 있으면 활성 북마크가 아니라 상태 줄로 본다.
- 읽을 때 `data[b'keepbranches'] is None`이면 `.hg/rebasestate is incomplete`.
- `skipped`는 저장하지 않고 다시 계산한다: `collapse`가 아니면 `destmap` 값들을 `seen`으로 두고, 상태를 정렬해 걸으며 `new != revtodo and new in seen`인 `old`를 skipped에 넣고 `seen.add(new)`.
- 쓰기: `storestatus()`는 `vfs("rebasestate","w")`에 `_writestatus`. 단일 트랜잭션이면 `tr.addfilegenerator('rebasestate', ...)`.
- 지울 때 `clearstatus`(아직 안 읽음, 12절).
- collapse 메시지는 별도 파일에 저장/복원: `storecollapsemsg`, `restorecollapsemsg(repo, isabort)`, `clearcollapsemsg`(아직 안 읽음).

## 3. 잠금과 트랜잭션 (핵심)

```
rebase(ui, repo, **opts):
    action = continue | abort | stop | None
    dry_run/confirm  -> _dryrunrebase (별도)
    stop:   with repo.wlock(), repo.lock():
               restorestatus; collapse면 StateError("cannot stop in --collapse session")
               keep도 아니고 allowunstable도 아니면 StateError("cannot remove original changesets with unrebased descendants", hint ...)
               mergemod.clean_update(repo[originalwd]); _finishrebase(); return 0
    inmemory(기본 꺼짐): 충돌이면 "hit merge conflicts; re-running rebase without in-memory merge"로 디스크 방식 재실행
    else: _dorebase -> _origrebase

_origrebase:
    with repo.wlock(), repo.lock():            # 처음부터 끝까지 두 잠금을 쥔다
        action이면: continue는 mergestate.read + checkunresolved, _prepareabortorcontinue(isabort)
                    abort면 _abort까지 여기서 하고 반환
        아니면:     destmap = _definedestmap(...); _preparenewrebase(destmap); storecollapsemsg
        tr = repo.transaction("rebase") if rebase.singletransaction else None
        with util.acceptintervention(tr):
            _performrebase(tr)
            if not dryrun: _finishrebase()
```

- `rebase.singletransaction`가 꺼져 있으면(기본) 트랜잭션은 **각 노드를 커밋할 때마다** `repo.commit`이 따로 연다. 그래서 `wlock`, `lock`은 계속 쥐고 있지만 트랜잭션은 노드마다 열고 닫힌다. 중단된 트랜잭션이 남으면 다음 명령이 `abandoned transaction found`로 거부한다([[concepts/locking-and-concurrency]]).
- `_performrebase`는 시작 전에 `storestatus()`로 상태를 쓰고(트랜잭션이 있으면 그 커밋 때도 쓰도록 등록), 활성 북마크가 있으면 `bookmarks.deactivate`.
- 각 노드: `_rebasenode`의 마지막에서 `if not tr: self.storestatus()`. 충돌(`InterventionRequired`)이면 이 줄에 닿지 못해 상태가 저장되지 않고, 다음 `--continue`는 "작업 사본 부모가 2개"인 경우로 진행 중인 커밋을 알아낸다(`resume = self.resume and self.wctx.p1().rev() == p1`).

## 4. 노드 하나의 처리 (`_rebasenode`)

상태 `self.state[rev]`에 따라:
1. `== rev`: `already rebased %s`.
2. `obsolete_with_successor_in_rebase_set`: divergence 경고(`note: not rebasing %s and its descendants as this would cause divergence`), skipped.
3. `obsolete_with_successor_in_destination`: 이미 목적지에 있음(`note: not rebasing %s, already in destination as %s` / `it has no successor`), `state[rev] = max(adjustdest(...))`.
4. `revtodo`: `rebasing %s`(`_ctxdesc`) -> `defineparents(rev, destmap, state, skipped, obsskipped)`로 (p1, p2, base) -> 재개이면 병합 생략, 아니면 `rebasenode(...)`(병합) -> collapse가 아니면 `wctx.setparents(p1, p2)` 후 `_concludenode`(원본 메시지·사용자·날짜를 재사용, `rebase_source` extra는 `rebase.store-source` 설정일 때, `destphase = max(ctx.phase(), draft)`, `allowemptycommit`은 `skipemptysuccessorf`의 반대) -> 새 노드면 `state[rev] = newrev`(비어 있으면 `note: created empty successor for %s, its destination already has all its changes`), 새 노드가 없으면(collapse 아님) `note: not rebasing %s, its destination already has all its changes`, skipped, `state[rev] = p1`.
5. 그 밖: `already rebased %s as %s`.

`_ctxdesc(ctx)`는 `REV:NODE "첫 줄"` 형식(아직 안 읽음).

## 5. 끝내기 (`_finishrebase`)와 되돌리기 (`_abort`)

`_finishrebase`: collapse면 마지막에 합친 커밋(메시지 `Collapsed revision` + 각 설명 앞에 `* `); mq 처리(`qtip`); **작업 사본을 `state.get(originalwd, originalwd)`로 복원**(`mergemod.update`); `clearrebased`(옛 노드 정리: 북마크 이동, strip 또는 obsolete 마커, 아직 안 읽음); `clearstatus`; `clearcollapsemsg`; `util.unlinkpath(repo.sjoin("undo"))`; 활성 북마크 복원.

`_abort`: `rebased = [s for r,s in state if s >= 0 and s != r and s != destmap[r]]`. 공개(immutable) 변경집합이 있으면 `warning: can't clean up public changesets ...`(cleanup 안 함). 리베이스 결과의 자손 중 리베이스된 것이 아닌 것이 있으면 `warning: new changesets detected on destination branch, can't strip`(cleanup 안 함). cleanup이면: strip 지점 = `roots(rebased)`; 현재 작업 사본이 rebased/dest/originalwd 중이면 `clean_update(originalwd)`; `repair.strip(strippoints, backup=backup)`. 마지막에 활성 북마크 재활성, `finally`로 `clearstatus`, `clearcollapsemsg`, `rebase aborted` 경고. `RepoLookupError`면(`상태가 깨짐`) `rebase aborted (no revision is removed, only broken state is cleared)`.

## 6. 나머지 함수 (2차에서 읽음)

### 6.1 목적지 정하기 `_definedestmap(destf, srcf, basef, revf)`
- 먼저 `checkunfinished(repo)`, 인메모리가 아니면 `bail_if_changed(repo)`. `commands.rebase.requiredest`이고 `-d`가 없으면 `you must specify a destination` + hint `use: hg rebase -d REV`.
- 우선순위 `-r` > `-s` > `-b`(기본 `.`). `-r`이 비면 `empty "rev" revision set - nothing to rebase`, `-s`가 비면 `empty "source" revision set - nothing to rebase`, `-b`가 비면 `empty "base" revision set - can't compute rebase set`.
- `-s`: `rebaseset = (src):: + (src)` (소스와 자손). `-b`: 목적지 `dest`(없으면 `_destrebase`)와 각 base의 `ancestor`를 분기점으로 삼아 `children(bp) & ancestors(bases)`를 루트로 `roots::`.
- `-b`에서 집합이 비면 상황별 메시지: `nothing to rebase - %s is both "base" and destination`, `... working directory parent is also destination`, `... "base" %s is already an ancestor of destination %s`, `... working directory parent is already an ancestor of destination %s`, `nothing to rebase from %s to %s`.
- 작업 사본(wdir)이 집합에 있으면 `cannot rebase the working copy`. 목적지가 없으면 `_destrebase`. `-d`는 `SRC`, `ALLSRC` 별칭을 쓰는 리비전 집합 식일 수 있음(없는 소스는 `skipping %s - empty destination`, 2개 이상이면 `rebase destination for %s is not unique`). 결과 `destmap = {src: dest}`가 비면 `nothing to rebase - empty destination`.

### 6.2 상태 만들기 `buildstate`, `sortsource`, `externalparent`
- `sortsource(destmap)`: 목적지가 아직 소스 집합에 있는 항목은 미루는 위상 정렬. 더 못 나아가면 `source and destination form a cycle`.
- `buildstate`: 첫 묶음의 루트를 구하고(없으면 `no matching revisions`), 각 루트에서 `root.ancestor(dest)`가 루트 자신이면 `source is ancestor of destination`. 목적지가 조상이고 같은 브랜치이며 목적지가 루트의 부모이고 collapse가 아니면 그 루트는 이미 끝난 것으로 `state[root] = root`. 전부 그러면 `None`(할 일 없음). 이후 모든 부모가 끝난 리비전도 끝난 것으로 표시. 반환 `(originalwd, destmap, state)`; `state` 초기값은 모두 `revtodo(-1)`.
- `externalparent`(collapse): 집합 밖·목적지 조상 밖의 부모가 둘 이상이면 `unable to collapse on top of %d, there is more than one external parent: %s`.

### 6.3 부모 정하기 `adjustdest`, `defineparents`
- `adjustdest(rev, destmap, state, skipped)`: 같은 목적지로 이미 리베이스된 소스 중 `prev`의 조상인 가장 큰 것의 새 리비전(`state[candidate]`)을 부모별 목적지로 쓴다. `adjusted == dest`이고 `dest in state`면 `state[dest]`(`revtodo`면 ProgrammingError).
- `defineparents`: 병합이 아닌 변경집합은 `newps[0] = dests[0]`. 병합이면 부모마다 (1) `dests[i]`가 `p` 또는 후계의 자손이면 `dests[i]`, (2) `state[p] > 0`이면 리베이스된 `state[p]`, (3) 한 부모가 다른 부모의 조상이 되면 조상 쪽을 버림(CASE-1, CASE-2는 `bases` 교환). `newps[1] != null`이고 p1이 그대로면 순서를 뒤집음. 부모가 하나도 안 바뀌고 `dest`가 새 부모에 없으면 `cannot rebase %d:%s without moving at least one of its parents`. 새 부모 중 `rev`의 자손이 있으면 `source is ancestor of destination`. 병합 base 후보가 둘 이상이면 "원치 않는 변경이 섞이는지" 계산해 `rebasing %d:%s will include unwanted changes from %s`. 반환 `(newps[0], newps[1], bases[0])`.

### 6.4 병합과 커밋 `rebasenode`, `commitnode`
- `rebasenode(rev, p1, p2, base, collapse, wctx)`: 디스크 방식은 작업 사본이 p1이 아니면 `mergemod.clean_update(p1)`, `dirstate.write`, `changing_parents` 안에서 `mergemod._update(rev, branchmerge=True, force=True, ancestor=base, mergeancestor=(p1이 rev의 조상), labels=[dest, source, parent of source])`, 그 뒤 `wctx.setparents(p1, p2)`와 `copies.graftcopies`(collapse가 아니면 `ctx.p1()`과 비교). 해결 안 된 파일이 있으면 `ConflictResolutionRequired('rebase')`, 문구는 `unresolved conflicts (see 'hg resolve', then 'hg rebase --continue')`.
- `commitnode`: `rebase.singletransaction`이 꺼져 있으면 `repo.transaction("rebase")` 안에서 `repo.commit(text, user, date, extra, editor)` 후 `dirstate.setbranch(새 노드의 브랜치)`.
- `commitmemorynode`(인메모리 전용)는 이번 재작성 범위에서 제외 후보: 기본이 꺼져 있다(`rebase.experimental.inmemory`).

### 6.5 정리 `clearrebased`, 상태·메시지 파일
- 상태 파일 지우기 `clearstatus`: 열린 트랜잭션의 파일 생성기를 빼고 `rebasestate`를 지운다. collapse 메시지는 `.hg/last-message.txt`(`storecollapsemsg`는 한 줄 + 개행, `restorecollapsemsg`는 첫 줄 strip, 없으면 abort일 때만 빈 문자열, 아니면 `missing .hg/last-message.txt for rebase`).
- `clearrebased`: 상태에서 `newrev >= 0 and newrev != rev`인 것마다 `moves[old] = new`(collapse면 새 노드는 collapsedas), 건너뛴 것은 후계 `()`(옛 노드만 제거), 일반은 `(newnode,)`, collapse는 하나로 묶어 `(collapsedas,)`. `--keep`이면 `replacements`를 비우되 북마크 이동(`moves`)은 한다. 끝으로 `cmdutil.cleanup_nodes(repo, replacements, "rebase", moves, backup=backup)`(hg4j `ChangesetRewrite.cleanupNodes`).

### 6.6 여전히 안 읽음
`_dryrunrebase`(`--dry-run`/`--confirm`), `_compute_obsolete_sets`/`_checkobsrebase`(obsolete 기능이 꺼진 저장소에서는 불필요), `pullrebase`(`pull --rebase`), `updatemq`/mq(확장 범위 밖), `summaryhook`.

## 7. 재작성 설계 초안

재사용: `StripCommand`(자손만 제거 + 백업 번들), `ChangesetRewrite`(precheck, revrange, cleanupNodes, phases, 북마크 이동), `UpdateCommand`/`RebaseCommand.restoreWorkingCopyCleanTo`, `MergeCommand`의 병합 계산, `StoreRollback`(노드 하나의 트랜잭션).

단계(각 단계 끝에서 테스트로 고정):
1. `RebaseState`: 원본 형식의 읽기/쓰기/지우기와 skipped 재계산, collapse 메시지 파일. 실제 hg가 쓴 `rebasestate`와 왕복 일치 검증.
2. 목적지 계산(`_definedestmap`, `buildstate`, `sortsource`)과 부모 계산(`defineparents`, `adjustdest`)을 순수 함수로 옮기고, 실제 hg의 Python 구현으로 만든 벡터로 검증.
3. 노드마다 별도 트랜잭션으로 커밋하는 루프(`_rebasenode`), 노드 뒤마다 `storestatus`. 충돌이면 상태를 남기고 `InterventionRequired`.
4. `--continue`, `--abort`, `--stop`과 `_finishrebase`/`clearrebased`.
5. 잠금: `wlock` -> `lock`을 처음부터 끝까지 쥐고(원본과 같게), 트랜잭션만 노드마다.
6. 기존 `RebaseCommand` API(`setSource`/`setTarget`/`call`/`continueRebase`/`abort`)는 새 구현 위에 얇게 남긴다. 기존 test는 원본 동작에 맞게 고친다.

## 8. 재작성 진행

- **1단계 완료**: `RebaseState`(`.hg/rebasestate` 읽기·쓰기·skipped 재계산, `last-message.txt`). 실제 hg 7.2가 충돌로 멈춘 rebase에서 쓴 파일(`activebookmark`가 `mark`, 두 변경집합이 `-1`)과 바이트 단위로 왕복 일치(`RebaseStateTest` 5개). 같은 실행에서 확인한 사실: 멈춘 상태에서 `last-message.txt`는 빈 메시지 한 줄(`\n`), `--abort` 후 `rebasestate`와 `last-message.txt`가 지워지고 리베이스된 노드도 사라지며 `rebase aborted`가 출력된다.
- **2단계 완료**: `RebasePlanner`(`sortSource`, `adjustDest`, `defineParents`)를 순수 함수로 이식. `hg debugbuilddag`로 만든 무작위 DAG 30개(분기·병합 포함)에서 실제 hg 7.2의 `hgext.rebase` 함수가 낸 답 4,360건(오류 1,456건 포함: `source is ancestor of destination`, `cannot rebase ... without moving at least one of its parents`, `will include unwanted changes`, `source and destination form a cycle`)을 전부 재현(`RebasePlannerVectorsTest`, 픽스처 `fixtures/shape/rebase-parents-vectors.tsv`). 얻은 교훈: 원본 `successorrevs`는 변경집합 자신도 후계로 내놓으므로, 병합 규칙 1번은 obsolete 표지가 없어도 `p`가 `dests[i]`의 조상이면 `dests[i]`로 옮긴다. 벡터 생성 스크립트는 `revset_predicates`를 import해야 revset 함수가 등록된다.
- 다음: 3단계(노드별 트랜잭션 루프와 `RebaseState` 저장, wlock→lock 구조).
- **3단계 완료 (2026-11-04)**: `RebaseCommand`의 `call/continueRebase/abort`를 원본 구조로 교체.
  - **잠금**: `wlock`, 이어서 `lock`을 명령 전체에 한 번 잡고 끝날 때 풀며(충돌로 멈출 때도 풀림), `--continue`와 `--abort`도 같은 순서로 다시 잡는다. 상태 파일은 잠금 뒤에 읽는다. 버려진 트랜잭션이 있으면 복구하지 않고 거부한다. (`RebaseLockTest` 5개: 다른 스레드가 wlock 또는 store lock을 쥐면 실패하고 저장소는 그대로, 충돌 정지 뒤 두 잠금이 풀림, continue/abort도 잠금을 잡음.)
  - **상태**: 시작 전과 노드 하나가 끝날 때마다 `.hg/rebasestate`(원본 형식)를 쓴다. 충돌로 멈춘 파일은 실제 hg가 쓴 파일과 바이트 단위로 같다.
  - **노드 처리**: 원본 `_rebasenode`처럼 작업 사본을 새 부모로 clean update, `defineparents`가 정한 base로 병합, 커밋은 노드마다 별도 트랜잭션(`CommitCommand`)에서 한다. 메시지, 사용자, 날짜는 그대로이고 `rebase_source` extra(`rebase.store-source` 기본값)를 기록한다. 브랜치는 `--keepbranches`가 아니면 목적지 브랜치다.
  - **마무리**: 작업 사본을 원래 부모(또는 그 결과)로 되돌리고, 옛 노드를 strip(`--keep`이면 유지), 상태 파일 삭제, 활성 북마크 복원. `--abort`는 리베이스된 노드를 strip(공개 단계이거나 새 자손이 있으면 경고 후 유지), 작업 사본 복원, `rebase aborted`.
  - **실제 hg와 비교** (`RebaseOriginalParityTest`): 선형 스택 리베이스는 changelog/manifest 원문, 로그 전체, 작업 사본 부모, `hg verify`까지 일치. 충돌 정지, 해결 후 `--continue`, `--abort`도 일치.
  - **이 과정에서 고친 것**: ① 번들 생성이 changeset이 바꾼 파일 리비전을 linkrev로만 골라, 내용이 같아 기존 리비전을 재사용한 파일이 strip 뒤 사라지던 문제(`cgpacker.generatefiles`처럼 changeset이 바꾼 파일 노드를 포함, 연결은 처음 바꾼 outgoing changeset). ② 충돌 병합에서 `<file>.orig`(로컬 버전) 백업을 남기지 않던 문제(merge/graft/rebase 공용 경로).
  - **기존 테스트 정리**: 원본을 숨긴 채 유지하던 hg4j 전용 기대("evolution-only")와 `[rebase] ` 접두사를 실제 hg 동작(strip, 메시지 그대로)으로 수정. 백업/저널 구현에만 해당하던 테스트(손상 fncache 허용, obsstore 기록 실패, 저널 삭제 실패)는 삭제.
- **남은 차이**: `-r/-b/-d` 식 해석과 다중 목적지, `--collapse`, `--stop`, `--dry-run`/`--confirm`, `rebase.singletransaction`, 진행 메시지의 북마크/태그 표시, 같은 묶음 안 노드 순서(`sort(-topo)` 대신 리비전 오름차순), `pull --rebase`, 빈 후계 노트(`note: not rebasing ..., its destination already has all its changes`)의 정확한 조건, 번들 `strip` 백업 파일 이름.
