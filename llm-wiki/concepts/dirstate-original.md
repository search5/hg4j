---
title: dirstate 원본 분석 (dirstate.py, dirstatemap.py, Rust dirstate_map/on_disk)
status: 분석 1차 (2026-11-04), 재작성 전
---

# dirstate: 원본의 구조와 hg4j와의 차이

## 1. 변경 컨텍스트 (`mercurial/dirstate.py`)

dirstate를 바꾸는 코드는 컨텍스트 안에서만 바꿀 수 있다. 이 규칙이 "언제 쓰고 언제 버리는가"를 한곳에 모은다.

| 컨텍스트 | 쓰임 | 규칙 |
|---|---|---|
| `changing_parents(repo)` | 작업 사본 부모가 바뀌는 일(`update`, `merge`, `commit` 등) | `wlock` 필수. `@requires_changing_parents` 메서드(`setparents`, `update_file`의 일부)만 허용 |
| `changing_files(repo)` | 추적 파일 집합이 바뀌는 일(`add`, `remove`, `forget`, `copy`, `rename`) | `wlock` 필수. 부모는 그대로 |
| `running_status(repo)` | `status` 계산. `changing_*`와 배타적이지 않고 `wlock`을 요구하지 않음 | 잠금이 있으면 나갈 때 쓰기 예약, 없으면 쓰지 않고 버림 |

- **상호 배타**: `changing_parents`와 `changing_files`는 동시에 열 수 없다(`trying to open "%s" dirstate-changing context while a "%s" is already open`).
- **중첩**: `_changing_level`을 센다. **가장 바깥 컨텍스트가 정상 종료할 때만** `write`가 호출된다.
- **예외**: 어느 깊이에서든 예외가 나면 `invalidate()`가 불려 **변경을 전부 버린다**(`_invalidated_context`). 바깥 컨텍스트가 정상 종료해도 쓰지 않는다.
- **`wlock` 없이 열면** `ProgrammingError("trying to change the dirstate without holding the wlock")`.
- **`_dirty`**: 메모리에 쓰이지 않은 변경이 있다는 표시. 트랜잭션도 컨텍스트도 없이 dirty인 채로 컨텍스트에 들어가면 develwarn.
- **`refresh()`**: 컨텍스트 중간에 다시 읽으면 ProgrammingError. 컨텍스트 밖에서는 파일이 바뀌었으면(`may_need_refresh`) 무효화한다.

## 2. 쓰기 (`dirstate.write(tr)`)

- `_dirty`가 아니면 아무것도 안 한다.
- **트랜잭션이 있을 때**: 즉시 쓰지 않는다. `tr.addfilegenerator("dirstate-1-main", (dirstate,), on_success, location=plain, post_finalize=True)`로 등록한다. 트랜잭션이 **성공적으로 닫힐 때** 쓰이고(`post_finalize`: 저널 정리 뒤), **중단되면** `_setup_tr_abort`가 dirstate를 무효화한다. 그래서 별도의 `dirstate.backup` 복사/복원이 필요 없다.
- **트랜잭션이 없을 때**: `opener(f, "w", atomictemp=True, checkambig=True)`로 임시 파일에 쓰고 이름을 바꾼다(원자적, mtime 모호성 방지).
- `tracked-hint`(`dirstate-tracked-key-v1`)가 켜져 있으면 추적 집합이 바뀐 경우에만 키 파일을 **본 파일을 쓴 뒤** 갱신한다.

## 3. dirstate-v2의 저장 (`dirstatemap`, Rust `dirstate_map.rs`, `on_disk.rs`)

- **읽기**: docket(`.hg/dirstate`: 마법 문자열, 부모 둘, 트리 메타데이터, 데이터 길이, uid)을 읽고, 데이터 파일(`.hg/dirstate.<uid>`)을 **`mmap`** 한다. 노드는 필요할 때 해석한다(전체를 맵으로 펼치지 않는다). 메타데이터가 데이터 파일의 **유효 길이**를 정하므로, 쓰는 도중의 파일은 뒤의 바이트를 읽지 않아 안전하다.
- **쓰기**: `write_should_append()`: `unreachable_bytes / on_disk.len() < ACCEPTABLE_UNREACHABLE_BYTES_RATIO(0.5)`이면 **같은 데이터 파일 끝에 덧붙인다**(바뀐 노드와 경로만). 아니면 새 uid로 새 데이터 파일을 처음부터 쓴다(`ForceNewDataFile`/`ForceAppend`로 강제 가능). 덧붙일 때는 쓸모없어진 바이트 수를 `unreachable_bytes`에 더한다.
- **docket 갱신이 커밋 지점**: 데이터를 쓴 뒤에 docket을 원자적으로 바꾼다. docket이 가리키는 길이까지만 유효하므로, 데이터 쓰기 도중 크래시가 나도 옛 docket과 옛 데이터가 그대로 일관된다. 새 데이터 파일을 만든 경우에만 옛 파일을 지운다.
- v1(옛 형식)은 전체를 읽고 전체를 다시 쓴다. 원본도 v1에서는 메모리에 전체를 올린다.

## 4. hg4j `Dirstate`와의 차이

| 항목 | 원본 | hg4j |
|---|---|---|
| 변경 규칙 | `changing_files`/`changing_parents`/`running_status` 컨텍스트, `wlock` 강제, 예외 시 전부 버림 | 컨텍스트 없음. 호출자가 `getDirstate()`로 객체를 받아 고치고 `writeDirstate`를 직접 호출 |
| 트랜잭션 | 파일 생성기(`post_finalize`)로 커밋과 함께 쓰고, 중단되면 무효화 | 트랜잭션과 무관. 명령이 `.hg/dirstate.backup`을 복사해 두었다가 실패하면 덮어쓴다 |
| v2 읽기 | `mmap`, 지연 해석 | 전체를 `LinkedHashMap<String, Entry>`에 펼침 |
| v2 쓰기 | 대부분 덧붙이기, 비율을 넘으면 새 파일 | 항상 전체를 새 `dirstate.<uid>`로 직렬화 후 docket 교체, 옛 파일 삭제(예외 삼킴) |
| 크래시 | docket이 커밋 지점이라 일관 | docket과 데이터가 두 번의 원자 쓰기라 사이에 크래시가 나면 고아 `dirstate.<uid>`가 남을 수 있음 |
| `status` | 끝에서 `wlock(wait=False)`로 선택적으로 보정을 씀 | 이번 루프에서 맞춤(`StatusCommand`) |

## 5. 재작성 계획 (단계마다 테스트로 고정)

1. **저장 계층**(공개 API 유지): v2에서 데이터 파일을 `mmap`하고 노드를 지연 해석하는 읽기, 변경분만 메모리에 두는 쓰기(덧붙이기, `unreachable_bytes`, 비율 0.5), docket이 커밋 지점인 쓰기 순서. 검증: 실제 hg가 쓴 v2 파일 읽기, hg4j가 덧붙인 파일을 실제 hg가 읽고 `hg status`/`hg verify`가 일치, 크래시 모사(데이터만 쓰고 docket 전 중단)에서 옛 상태 유지.
2. **컨텍스트**: `DirstateChange`(`changingParents`, `changingFiles`, `runningStatus`)를 만들고, 규칙(wlock 강제, 상호 배타, 중첩, 예외 시 무효화, 가장 바깥 종료에서만 쓰기)을 단위 테스트로 고정.
3. **트랜잭션 연결**: `StoreRollback`/`CommitCommand`의 트랜잭션이 닫힐 때 dirstate를 쓰고(`post_finalize`) 중단되면 무효화하는 생성기. 이로써 `dirstate.backup` 복사/복원을 걷어낸다.
4. **명령 이전**: `add`/`remove`/`forget`/`copy`/`rename`은 `changing_files`, `update`/`merge`/`commit`/`rebase`/`graft`/`strip` 등은 `changing_parents`로, 한 명령씩 옮기며 해당 명령 테스트와 실제 hg 비교를 돌린다.
5. 전체 단위 테스트, 매트릭스 갱신.

호출 지점 수(`getDirstate()` + `writeDirstate`)를 세어 단계 4의 규모를 먼저 잡는다.
