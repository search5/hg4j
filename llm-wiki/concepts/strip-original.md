---
title: strip 원본 분석 (hgext/strip.py, mercurial/strip.py, mercurial/repair.py)
status: 분석 완료 (2026-11-04), 재작성은 아직
---

# `hg strip`의 원본 구조

## 1. 명령 (`mercurial/strip.py::_strip_command`)

- 리비전 집합을 `logcmdutil.revrange`로 만든 뒤 **`with repo.wlock():`** 안에서 일한다(여기서는 `lock`을 아직 잡지 않는다).
- `-B BOOKMARK`: 지정한 북마크가 저장소 북마크의 부분집합이 아니면 `bookmark '...' not found`. 한 노드에 다른 북마크도 걸려 있으면 그 북마크만 지우고 strip하지 않는다(그 경우 `repo.lock(), repo.transaction("bookmark")`로 북마크만 삭제하고 `bookmark 'x' deleted`).
- 집합이 비면 `empty revision set`(`debugstrip`과 `--permit-empty-revset`은 0 반환).
- 지울 집합 = 지정한 리비전 + 자손. 루트 = 지정한 것 중 다른 지정 리비전의 자손이 아닌 것. 작업 사본 부모가 지워지면 `update`.
- `update` + `--keep`: `repo.dirstate.changing_parents`로 dirstate만 새 부모로 재구성(바뀔 파일만), merge 상태 정리, `update=False`.
- 그다음 `strip(ui, repo, revs, ...)`.

## 2. 본체 (`mercurial/strip.py::strip`)

```
with repo.wlock(), repo.lock():
    if update:
        checklocalchanges(repo, force)          # force가 아니면 checkunfinished + bail_if_changed
        urev = _findupdatetarget(repo, revs)    # 첫 루트의 부모 (브랜치가 다르면 같은 브랜치의 가장 가까운 조상)
        up_impl.clean(repo, urev)               # clean update
        repo.dirstate.write(repo.currenttransaction())
    repair.softstrip(...) if soft else repair.strip(ui, repo, revs, backup)
    if bookmarks:
        with repo.transaction("strip") as tr:   # 활성 북마크면 비활성화 후 지정한 북마크 삭제
        ui.write("bookmark '%s' deleted\n")
```

잠금은 **wlock 다음 lock**, 둘 다 `repair.strip`이 끝날 때까지 쥔다.

## 3. 저장소 수술 (`repair.strip`)

호출 조건: 호출자가 저장소를 잠갔고 **현재 트랜잭션이 없어야** 한다(`cannot strip from inside a transaction`이면 ProgrammingError). 그래서 트랜잭션 안에서 지우고 싶은 쪽(rebase 등)은 `repair.delayedstrip`을 쓴다: 트랜잭션이 있으면 **트랜잭션이 닫힌 뒤(`addpostclose`)** 실행하고, 없으면 바로 `strip`한다.

순서:
1. `striprev = min(지울 리비전)`; `files = striprev 이후 changeset들이 건드린 파일 목록`; `saverevs = _collectbrokencsets(...)`(잘림으로 깨지는 changeset: manifest와 각 filelog의 `getstrippoint`가 알려 주는 linkrev).
2. 지울 집합을 자손으로 넓히고, 지우지 않는 `striprev` 뒤 리비전은 `saverevs`/`saveheads`에 넣는다.
3. `_bookmarkmovements`: 지워지는 노드에 걸린 북마크를 `max(parents(tostrip) - tostrip)`(없으면 `.`)로 옮길 목록을 계산.
4. `backup`이면 `_createstripbackup`: `.hg/strip-backup/<노드>-<해시>-<topic>.hg` 번들을 만들고 `saved backup bundle to ...`를 출력(`_internal()` changeset은 제외, 모두 internal이면 백업 없음).
5. `saveheads`가 있으면 **임시 번들** `...-temp.hg`(비압축)에 보존할 changeset을 저장.
6. `with ui.uninterruptible(): try:` 안에서
   - **트랜잭션 `strip`**: `register_strip`(phase), `cl.strip`, `stripmanifest`, 각 `filelog.strip`(비면 fileindex에서 제거), 비게 된 파일은 `markremoved`, fncache 기록, 새로 생긴 파일 truncate 정리, (devel일 때만) obsmarker 삭제. `revbranchcache().invalidate(striprev)`.
   - 임시 번들이 있으면 **트랜잭션 `strip`(두 번째)**: `bundle2.applybundle`로 보존 changeset을 다시 적용(`adding branch` 노트).
   - **트랜잭션 `repair`**: 북마크 이동 적용.
   - `transaction.cleanup_undo_files`(undo 파일 정리).
7. 실패하면(`except:`) 되돌리지 **않고** 경고만 출력하고 다시 던진다:
   - 백업이 있으면 `strip failed, backup bundle stored in '...'`
   - 임시 번들이 있으면 `strip failed, unrecovered changes stored in '...'` 와 `(fix the problem, then recover the changesets with "hg unbundle '...'")`.
8. 성공이면 임시 번들을 지우고 `repo.destroyed()`. 반환은 백업 파일.

## 4. 폐기 마커

`strip`은 obsolescence marker를 **만들지 않는다**. `devel.strip-obsmarkers`일 때 지워지는 노드에 배타적인 마커를 *삭제*할 뿐이다.

## 5. hg4j `StripCommand`와의 차이 (재작성 대상)

| 항목 | 원본 | hg4j |
|---|---|---|
| 잠금 | 명령은 `wlock`, 본체에서 `wlock`+`lock`, 끝까지 쥠 | 확인 필요(재작성 때 맞춤) |
| 트랜잭션 | 자르기, 보존 번들 적용, 북마크의 세 트랜잭션 | 하나의 자체 저널 + 전체 파일 백업 |
| 실패 | 되돌리지 않음, 번들 위치와 `hg unbundle` 안내, 임시 번들 남김 | 모든 파일을 백업에서 복원 |
| 백업 번들 | `.hg/strip-backup/<노드>-<해시>-<topic>.hg`, `saved backup bundle to` | 같음(이미 구현) |
| 폐기 마커 | 만들지 않음 | 항상 prune 마커를 씀 |
| 트랜잭션 안에서 | 불가(`delayedstrip`이 닫힌 뒤 실행) | 바깥 저널을 지움 |
| `--keep` | dirstate 재구성, 파일 유지 | 비슷(확인 필요) |
| 북마크 이동, `-B`, `--soft` | 있음 | 일부(확인 필요) |

## 6. 이전 설명의 정정 (2026-11-04)

`rebase.singletransaction`을 만들 때 "원본은 옛 노드의 strip까지 같은 트랜잭션 안에서 한다"고 설명하고 그렇게 구현했으나 **틀렸다**. 원본은 `delayedstrip`으로 트랜잭션이 닫힌 뒤에 strip한다(위 3절). 구현을 원본대로 고쳤다: 단일 트랜잭션은 마지막 노드 커밋과 상태 정리까지 감싸고, 닫은 뒤에 옛 노드를 strip한다.
