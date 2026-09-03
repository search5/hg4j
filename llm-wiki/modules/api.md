---
updated: 2026-08-31
status: current
---

# 모듈: api (`io.github.search5.hg4j.api`)

Porcelain(고수준 명령) 계층. 사용자가 실제로 호출하는 공개 API.

## 진입점: `Hg` 파사드
모든 명령은 정적 팩토리 메서드로 `Hg` 클래스에 노출된다.
```java
HgRepository repo = Hg.init().setDirectory(dir).call();
Hg.add(repo).addFile(file).call();
Hg.commit(repo).setMessage("...").setAuthor("...").call();
Status status = Hg.status(repo).call();
List<HgCommit> commits = Hg.log(repo).call();
```
`Hg`는 `repository` 필드와 `hooks`(등록된 `HgHook`)를 들고 있으며, `runTransaction`,
`registerHook`, `wrap`(기존 저장소 감싸기), `open` 같은 저수준 진입점도 제공한다.

## Command 클래스 목록 (Builder 패턴)
각 명령은 `XxxCommand` 클래스로 분리되어 있고, `Hg.xxx(repo)`가 이를 생성해서 반환한다.
Builder 스타일(`setXxx().call()`)로 통일되어 있다.

| 카테고리 | 명령 클래스 |
|---|---|
| 저장소 초기화/기록 | `InitCommand`, `CommitCommand`, `AmendCommand`, `AddCommand`, `RemoveCommand`, `RevertCommand` |
| 조회 | `StatusCommand`, `LogCommand`, `CatCommand`, `DiffCommand`, `TreeCommand`, `AnnotateCommand`, `GrepCommand`, `IdentifyCommand`, `DescribeCommand` |
| 브랜치/태그/북마크/페이즈 | `BranchCommand`, `TagCommand`, `BookmarkCommand`, `PhaseCommand` |
| 머지/재배치 | `MergeCommand`, `RebaseCommand`, `GraftCommand`, `HisteditCommand`, `ResolveCommand` |
| 워킹카피 | `UpdateCommand`, `WorktreeCommand`, `ShelveCommand`, `PurgeCommand`, `RenameCommand` |
| 원격 동기화 | `PushCommand`, `PullCommand`, `FetchCommand`, `CloneCommand`, `IncomingCommand`, `OutgoingCommand` |
| 히스토리 정리 | `StripCommand`, `BisectCommand`, `GcCommand` |
| import/export | `ExportCommand`, `ImportCommand`, `ArchiveCommand` |
| 기타 | `SubrepoCommand`, `NarrowCloneCommand`, `RevsetCommand`, `HeadsCommand` |

## 보조 타입
- **`Status`**: `getClean`/`getModified`/`getAdded`/`getRemoved`/`getUntracked` 등 상태 집합 반환.
- **`HgCommit`**: `getRevision`, `getNodeIdHex`, `getAuthor`, `getMessage` — changelog 엔트리 표현.
- **`HgHook` / `HgHookType` / `ProcessHook`**: pre-commit 등 훅 등록/실행 메커니즘.

## 커버리지 게이트 대상 (build.gradle 기준)
아래 클래스들은 90% 클래스 단위 커버리지 규칙 적용 대상이라 리팩토링 시 테스트 영향에 특히 주의:
`AddCommand`, `BookmarkCommand`, `BranchCommand`, `InitCommand`, `LogCommand`,
`RemoveCommand`, `RevertCommand`, `ShelveCommand`, `StatusCommand`, `TagCommand`,
`PushCommand`, `CatCommand`, `DiffCommand`, `TreeCommand`, `UpdateCommand`.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/api/`
