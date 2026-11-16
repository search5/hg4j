---
updated: 2026-11-16
status: current
---

# 로드맵: Yona가 공개 API를 조합해 풀 리퀘스트 스쿼시를 만드는 방안

> **상태: 결정됨(2026-11-17, 사용자): `setSquash`를 유지한다(방안 C).** 새 공개 API(방안 B의 `TreeCommitCommand`, D3의 `SquashMergeCommand`)는 만들지 않는다. 아래 5절의 게이트는 이 결정으로 닫힌다. 남은 선택은 방안 A의 조합을 문서로만 알릴지(D1)와 `debugsetparents`를 추가할지(D2)인데, 사용자가 요청하기 전에는 착수하지 않는다. 사용자는 "어제 말했다"고 했으나 기록에서 해당 발언은 찾지 못했고, 이 문서가 권장한 "유지"와 같은 내용이라 그대로 따른다.
> 이 문서의 줄 번호는 `main` `4eb74f9`(2026-11-16) 기준이다. 줄 번호는 바뀌므로 인용 전에 다시 확인한다.
> 표기: **[확인]** 코드나 실행 결과를 직접 확인함 / **[추정]** 읽은 것에서 해석함 / **[미확인]** 확인하지 못함.

## 1. 배경과 목표

Yona의 `HgPullRequestBackend.kt`는 스쿼시 병합을 `MergeCommitCommand(...).setSquash(true).call()` 하나로 처리한다. `next2` 세션의 사용자는 "스쿼시 전용 메서드"가 아니라 hg4j가 원래 제공하는 공개 API를 Yona에서 조합하는 구성을 원한다고 전해 왔다(2026-11-16). 이 문서는 그 구성이 현재 가능한지, 불가능하면 무엇이 막는지, hg4j에서 무엇을 열어야 하는지를 적는다.

`setSquash`는 `583f8ef`(2026-11-06)에서 Yona의 요청으로 추가되었고 CHANGELOG 1.1.2와 `maintenance.adoc`에 공개 옵션으로 올라 있다. **[확인]** 문서 어디에도 "Yona가 조합해 쓰라"는 서술은 없다.

## 2. 현황 (확인한 사실)

| 항목 | 내용 | 위치 |
|---|---|---|
| `setSquash`가 하는 일 | `TreeMergeCommand`로 3방향 병합을 계산하고, `plan()`이 복사·이름 변경을 따라간 뒤, `writeSquash()`가 p1 위에 일반 커밋(부모 하나)으로 파일 리비전, manifest, changelog를 직접 쓴다 | `MergeCommitCommand.java:288-296`, `:405` `plan`, `:674` `writeSquash` |
| 쓰기 담당 클래스의 공개 범위 | `FilelogWrite`, `ChangesetRewrite`는 package-private(`final class`)이라 Yona가 부를 수 없다 | `FilelogWrite.java:17`, `ChangesetRewrite.java:27` |
| 공개된 병합 계산 | `TreeMergeCommand`(`Hg.treeMerge()`)는 `changedFiles`, `changedModes`, `removedFiles`, `conflicts`만 값으로 돌려주고 아무것도 쓰지 않는다. **복사·이름 변경 정보는 결과에 없다** | `TreeMergeCommand.java:79-146`, `:155` |
| 복사 추적 | `MergeCopies`가 package-private이고 `plan()`이 `copyMerges`로 쓴다 | `MergeCopies.java:25`, `MergeCommitCommand.java:471` |
| 작업 디렉터리 경로의 부품 | `UpdateCommand`, `MergeCommand`, `CommitCommand`, `HgRepository.getDirstate()`(`setParents`)와 `writeDirstate()`가 모두 공개다 | `Dirstate.java:219,233`, `HgRepository.java:289,398` |
| 리베이스와의 관계 | `GraftInMemory`는 작업 파일 없이 쓰지만 리비전마다 체인지셋 하나를 만든다. 한 체인지셋으로 합치는 스쿼시가 아니다 | `GraftInMemory.java` |

## 3. 실험: 기존 공개 API만으로 작업 디렉터리에서 스쿼시 (2026-11-16)

**[확인]** 임시 interop 테스트(커밋하지 않고 삭제함)를 `interopTest`로 실행했다. 실제 hg는 `hg update P1`, `hg merge -r P2`, `hg debugsetparents P1`, `hg commit`이고, hg4j는 아래 순서다.

```java
new UpdateCommand(repo).setRevision(p1).call();                       // hg update P1
new MergeCommand(repo).setNodeId(NodeIdUtil.fromHex(p2)).call();      // hg merge -r P2
Dirstate ds = repo.getDirstate();
ds.setParents(NodeIdUtil.fromHex(p1), new byte[20]);                  // hg debugsetparents P1
repo.writeDirstate(ds);
byte[] node = new CommitCommand(repo).setMessage("squash")
        .setAuthor("tester").setDate(1700000000L, 0).call();          // hg commit
```

결과(실제 hg가 만든 노드와 hg4j가 만든 노드의 비교):

| 시나리오 | 실제 hg | hg4j 조합 | 일치 |
|---|---|---|---|
| 한 파일을 양쪽이 다른 줄에서 수정, 파일 추가, 파일 삭제 | `2c926c5a9ab7bde5f2613643cc1bbd0fcfacfa0c` | 같음 | 예 |
| 위에 더해 한쪽의 파일 이름 변경 | `e0c307a6c9399d3f0bcbfc7e32bdf531a674835f` | 같음 | 예 |

**추가 실험 (같은 날): 작업 파일 없는 서버 저장소에서 `WorktreeCommand`(hg share)로 만든 작업 사본**
작업 파일이 없는 저장소(`hg clone -U`)에서 `new WorktreeCommand(serverRepo).setNewWorktreeDir(dir).call()`로 사본을 만들고 위와 같은 순서로 실행했다. **[확인]**

| 항목 | 결과 |
|---|---|
| 노드 | 실제 hg `bb2fc18b899888bd7efa1b21c158fb5bdc91c32a`와 같음(파일 이름 변경 포함) |
| 결과의 위치 | 공유 저장소이므로 **push 없이** 서버 저장소의 `hg log -r NODE`가 바로 찾았고 부모는 `p1`과 null |
| 검증 | 서버 저장소에서 `hg verify`가 오류 없이 통과(체인지셋 4, 변경 7) |
| 시간 | 작은 저장소에서 사본 생성부터 커밋까지 78 ms. 큰 저장소의 값은 **[미확인]** |

그래서 방안 A의 "결과를 서버로 가져온다"는 단계는 필요 없다. 작업 사본이 서버 저장소의 store를 그대로 쓰기 때문이다.

한계(숨기지 않고 남긴다):
- 두 시나리오뿐이다. 실행 비트, 심볼릭 링크, LFS, 충돌, 조상 관계, changelog-v2 부가 데이터는 **[미확인]**.
- 이 경로는 **작업 디렉터리가 있어야 한다**. Yona 서버 저장소는 푸시와 풀로 채워진 작업 파일 없는 저장소이므로, 임시 작업 사본(`WorktreeCommand` 또는 복제)을 만들어 거기서 실행해야 한다. 큰 저장소에서의 비용과, 사본 생성이 서버 저장소에 남기는 흔적(공유 정보 파일 등)은 **[미확인]**.
- `getDirstate().setParents`와 `writeDirstate`로 부모를 되돌리는 방식은 `hg debugsetparents`를 손으로 흉내 낸 것이며, 공개 API로서 의도된 사용인지는 **[미확인]**.

## 4. 후보 방안

### 방안 A. 임시 작업 사본에서 기존 명령을 조합 (코드 변경 없음)
- Yona가 서버 저장소에서 `WorktreeCommand`로 임시 작업 사본(hg share)을 만들고 3절의 순서로 커밋한다. 사본이 store를 공유하므로 결과는 서버 저장소에 바로 들어간다(3절 추가 실험).
- 장점: hg4j를 바꾸지 않는다. 3절에서 두 시나리오가 실제 hg와 같은 노드를 만든다.
- 단점: 작업 파일을 쓰고 읽는 비용, 임시 디렉터리 정리, 동시 병합 처리를 Yona가 맡는다. 병합 한 번에 사본 하나가 든다. 작업 파일 없는 서버가 목적이었던 `MergeCommitCommand`의 취지와 어긋난다.

### 방안 B. 공개 "트리 커밋" 명령을 추가해 `TreeMergeCommand`와 조합 (권장 후보)
- **새 공개 명령**(가칭 `TreeCommitCommand`)이 부모 노드 하나, 변경 파일(내용, 모드, 복사 원본), 삭제 파일, 사용자, 메시지, 날짜, 브랜치를 받아 부모 하나짜리 체인지셋을 쓴다. 현재 `writeSquash()`의 쓰기 부분을 그대로 옮긴 것이다.
- Yona는 `TreeMergeCommand`(병합 계산)와 이 명령(커밋)을 조합한다. `setSquash`는 두 호출의 얇은 포장으로 남겨 동작은 그대로 둔다.
- 선행 조건: `TreeMergeResult`가 **복사·이름 변경 정보**를 돌려줘야 한다(2절). 지금은 `MergeCommitCommand.plan()`이 비공개 `MergeCopies`로 따로 계산한다. 이 정보를 결과에 싣는 것은 `TreeMergeResult`에 getter 하나를 더하는 추가 변경이다.
- 장점: 작업 파일 없이 동작하고(스토어 락과 롤백 트랜잭션만), 스쿼시 외에 체리픽 커밋이나 "변경 적용" 같은 용도로도 재사용된다. 실제 hg와 같은 노드라는 interop 기준을 `MergeCommitSquashRealHgInteropTest`로 그대로 유지할 수 있다.
- 단점: 새 공개 API는 유지 책임이 생긴다. 원본 hg에는 같은 이름의 명령이 없다(원본의 대응은 `hg commit` + `debugsetparents`). 따라서 hg 원본 기준으로는 hg4j 고유 확장이며, 5절 결정 1이 필요하다.

### 방안 D. 기존 명령의 조합을 hg4j가 공개 (2026-11-16 추가 검토)
방안 A의 조합을 Yona가 각자 짜지 않고 hg4j가 공개하는 방안이다. 세 수준이 있다.

**D1. 문서화만.** `maintenance.adoc`(영문, 국문)에 3절의 순서를 "스쿼시 병합 조합 예제"로 싣고, 예제를 실제로 실행하는 interop 테스트를 둔다. 새 API가 없고 원본 hg의 명령 흐름(`update`, `merge`, `debugsetparents`, `commit`)과 1대1이다. 단점은 `getDirstate().setParents`와 `writeDirstate`를 Yona가 직접 부르는 것이 공식 사용법이 아니라는 점(3절 한계)이다.

**D2. 원본 hg의 `debugsetparents`에 해당하는 명령 추가.** 원본에 있는 명령이므로 "원본 hg 지원 여부" 기준에 맞는 유일한 신규 API다. **[확인]** `api` 패키지에 `Debug`로 시작하는 클래스가 없고 `Hg.java`에도 debug 계열 진입점이 없다(`debugsetparents`는 hg4j에 없음). 원본의 동작(`hg debugsetparents REV1 [REV2]`: dirstate 부모만 바꾸고 작업 파일, 병합 상태는 건드리지 않음)은 원본 소스로 다시 대조해야 한다 **[미확인]**. 이 명령이 있으면 D1의 순서가 `hg4j.debugSetParents().setP1(p1).call()` 한 줄로 공식화된다.

**D3. 조합 전체를 감싼 공개 편의 명령(가칭 `SquashMergeCommand`).** 내부에서 `WorktreeCommand`, `UpdateCommand`, `MergeCommand`, 부모 재설정, `CommitCommand`를 순서대로 부르고 사본 디렉터리를 정리한다. Yona는 호출 한 번으로 끝난다. 원본 hg에는 같은 이름의 명령이 없고 hg4j 고유 편의다(이미 있는 `setSquash`와 성격이 같다). 새 저수준 쓰기 코드는 없다.

| 수준 | 새 공개 API | 원본 hg 대응 | 작업 파일 필요 | Yona 측 부담 |
|---|---|---|---|---|
| D1 | 없음 | 흐름이 1대1 | 예(임시 사본) | 순서, 정리, 오류 처리를 직접 |
| D2 | `debugsetparents` 명령 | 있음 | 예(임시 사본) | 순서, 정리, 오류 처리를 직접 |
| D3 | `SquashMergeCommand` | 없음(고유) | 예(내부에서 처리) | 호출 한 번 |

주의할 점(코드로 확인한 범위만):
- 사본 생성, 업데이트, 병합, 커밋이 하나의 락 아래 원자적으로 일어나지 않는다. `MergeCommand`와 `CommitCommand`가 각각 `wlock`을 잡고(`MergeCommand.java:297`, `CommitCommand.java:294`), 커밋만 store 락도 잡는다(`CommitCommand.java:295`). 사본마다 `wlock`이 따로이므로 같은 서버 저장소에 대한 병합끼리 서로 막지는 않고, 서버 저장소가 그 사이 바뀌어도 부모를 명시했으므로 커밋 내용은 달라지지 않는다 **[추정]**(동시 실행 시험은 하지 않았음 **[미확인]**).
- 병합이 충돌로 끝나면 사본에 충돌 표시가 남는다. 정리와 "아무것도 쓰지 않는다"는 보장은 D3가 직접 처리해야 한다. `setSquash`는 충돌을 값으로 돌려주고 아무것도 쓰지 않는다는 점(문서 보장)에서 더 강하다.
- 작업 파일 없는 서버라는 `MergeCommitCommand`의 설계 목적(작업 파일 비용 없음)과 달리 파일 입출력이 든다. 비용은 **[미확인]**.

### 방안 C. 현상 유지 (`setSquash` 유지, 문서만 보강)
- `setSquash`를 정식 공개 API로 두고, `maintenance.adoc`에 "작업 파일 없는 서버용 편의 옵션이며 조합 대안은 작업 디렉터리 경로"라고 적는다.
- 장점: 변경이 없다. 단점: 사용자가 원하는 "조합" 방향과 다르다.

## 5. 결정 게이트 (착수 전 사용자 결정 필요)

1. **[의도적 차이 후보]** 방안 B의 `TreeCommitCommand`는 원본 hg에 같은 이름이 없는 hg4j 고유 공개 API다. 새 공개 API를 허용할지, 기존 `setSquash`를 유지할지 정해야 한다. 판단 기준은 "원본 hg가 지원하는지"이며, 원본의 대응은 `hg commit`이 부모를 dirstate에서 읽는다는 점뿐이다.
2. 방안 A의 조합을 어느 수준까지 공개할지: D1(문서만), D2(`debugsetparents` 명령 추가, 원본에 있는 명령), D3(`SquashMergeCommand`, 고유 편의). D2는 원본 지원 기준에 맞고, D3와 B는 고유 확장이다.
3. `setSquash`를 방안 B 이후에도 유지할지(권장: 유지, 호환성), 폐기 예고할지.

## 6. 로드맵 (결정 게이트 통과 후)

| 단계 | 내용 | 검증 |
|---|---|---|
| 0 | 3절 실험(두 가지 작업 사본)을 정식 interop 테스트로 승격(실행 비트, 심볼릭 링크, LFS, 충돌, 조상 관계, changelog-v2 추가) | 실제 hg와 같은 노드 |
| 1 | `TreeMergeResult`에 복사·이름 변경 정보 추가(공개 getter). 기존 호출자 영향은 `plan()` 한 곳 | 기존 `MergeCommitCommandTest`, `MergeCommitSquashRealHgInteropTest` 통과 |
| 2 | `writeSquash()`의 쓰기 부분을 공개 명령으로 분리, `setSquash`가 이 명령을 부르도록 변경 | 같은 노드(회귀 없음), 롤백 시험 |
| 3 | 영문·국문 문서와 CHANGELOG에 조합 예제 추가, Yona 쪽 전환 가이드 | 문서 예제를 실제로 실행 |

의존: 1 → 2 → 3, 0은 어느 단계와도 독립이다. 단계 2의 롤백은 규칙 8(복구와 정리 최우선)에 따라 `StoreRollback` 경로를 그대로 쓴다.

방안 D를 택하면 단계 1~2(트리 커밋 명령) 대신 다음이 들어간다: D2는 `debugsetparents` 원본 소스 대조 → 명령 구현 → 같은 노드 interop 테스트, D3는 위 단계 0 → 정리와 롤백 설계(사본 디렉터리, 충돌 시) → 문서. D1은 단계 3의 문서와 단계 0의 테스트만이다.

## 7. 알려진 한계와 위험

- 방안 B는 `MergeCommitCommand`의 내부(`plan`, `writeSquash`) 일부를 바꾸므로, 1.1.1~1.1.3에서 맞춘 interop 노드 일치가 깨질 위험이 있다. 단계 2 전후로 두 테스트 클래스를 반드시 돌린다.
- 방안 A의 임시 작업 사본 경로는 서버에서 대용량 저장소를 병합할 때 느릴 수 있다. 측정하지 않았다 **[미확인]**.
- 이 문서는 Yona 쪽 호출 위치(`HgPullRequestBackend.kt:154`)와 위키 서술을 읽었을 뿐 Yona 코드를 바꾸지 않았다.

## 관련 문서
- [[decisions/server-push-concurrency-roadmap]]: 서버 백엔드 로드맵(락 구간, 푸시)
- `src/docs/asciidoc/en/reference/maintenance.adoc`의 `MergeCommitCommand` 절
- `CHANGELOG.md` 1.1.1(MergeCommitCommand), 1.1.2(`setSquash`)
