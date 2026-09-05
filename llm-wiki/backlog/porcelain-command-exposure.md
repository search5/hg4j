---
updated: 2026-09-02
status: completed
---

# 백로그 12: 포셀린 명령 노출 완전성

`hg debugcommands`(real hg 7.2.2)와 `Hg` 파사드 메서드 목록을 전수 대조해 발견한
두 갈래 문제: (a) 클래스는 있는데 `Hg` 파사드에 안 걸려 있던 4개 명령, (b) 대응 클래스
자체가 아예 없던 8개 명령(`hg tags`/`hg copy`/`hg files`/`hg locate`/`hg manifest`/
`hg bundle`/`hg recover`/`hg paths`).

## 원문
12. ~~**포셀린 명령 노출이 완전하지 않음**~~ — ✅ **완료(2026-09-02)** (사용자 질문
    "포셀린 기능은 모두 노출 끝?"에 답하며 `hg debugcommands`(real hg 7.2.2,
    debug*/admin* 제외 145개 중 핵심 포셀린)와 `Hg` 파사드 메서드 목록을 직접 전수
    대조해 발견했던 두 갈래 문제 전부 해결:

    **(a) 클래스는 있는데 `Hg` 파사드에 안 걸려 있음** — 다른 모든 명령은 예외 없이
    `Hg.xxx()` 형태로 노출되는데 아래는 그 관례에서 벗어나 있다:
    - `BranchesCommand`(이번 세션 신설), `ClonebundlesCommand`, `TreeMergeCommand`,
      `CensorCommand` — `new XxxCommand(repository)`로 개별 생성은 가능하지만
      `Hg` 파사드 메서드가 없다.
    - `RollbackCommand`는 `Hg.java` 내부(271번 줄 부근)에서 다른 명령의 크래시
      복구 로직에 종속적으로만 호출되고, `Hg.rollback()`처럼 사용자가 명시적으로
      부를 수 있는 파사드 메서드가 없다.

    **(b) 대응 클래스 자체가 아예 없음** — real hg 핵심 포셀린 명령 중:
    - `hg tags`(전체 태그 **목록 조회**) — `TagCommand`는 태그 **생성**만 하고
      `.hgtags`를 읽어 목록을 돌려주는 조회 기능이 없다(`getTags`/`listTags`류
      코드 전체 검색 결과 0건).
    - `hg copy` — 기존 `RenameCommand.call()`은 `Files.move`로 원본을 지운다.
      real hg의 `copy`는 원본을 남긴 채 새 추적 사본만 만드는(dirstate copy
      metadata만 새로 등록) 별개 동작인데 대응 코드가 없다.
    - `hg files`(패턴에 매칭되는 추적 파일 목록), `hg locate`(작업사본에서 파일
      검색), `hg manifest`(특정 리비전의 매니페스트 직접 조회 — `ManifestWalk`
      내부 클래스는 있지만 포셀린 진입점이 없음), `hg bundle`(현재 저장소를
      번들 파일로 저장 — `UnbundleCommand`의 정반대 방향, 위 gap table의 "Bundle1"
      행에서 이미 "독립된 Bundle1 writer 클래스는 없음"으로 지적된 것과 같은
      맥락), `hg recover`(중단된 트랜잭션에서 명시적으로 복구를 트리거하는 단독
      명령 — `HgRepository.checkAndPerformAutoRollback()`으로 다음 작업 시작 시
      자동으로는 되지만 사용자가 직접 호출할 방법이 없음), `hg paths`(등록된
      경로 별칭 **목록**을 사용자에게 보여주는 조회 명령 — `HgRcConfig.getPath()`
      로 내부 소비만 될 뿐 조회 결과를 노출하는 API가 없음) — 전부 대응 클래스가
      없다.

    (a)는 `BranchesCommand`/`TreeMergeCommand`/`CensorCommand`/`ClonebundlesCommand`에
    `Hg.branches()`/`Hg.treeMerge()`/`Hg.censor()`/`Hg.clonebundle(url)`을 추가해
    해결(`RollbackCommand`는 이미 `Hg.rollback()`으로 노출돼 있었음이 드러나 —
    원래 이 항목의 "미노출" 서술 자체가 틀렸던 것으로 확인). 커밋 `f04edb9`.

    (b)는 `TagsCommand`/`PathsCommand`/`FilesCommand`/`LocateCommand`/
    `ManifestCommand`/`CopyCommand`/`BundleCommand`/`RecoverCommand` 8개를 병렬
    격리 빌드 에이전트로 신설, 전부 real hg 7.2 CLI/소스로 검증(예: `hg locate`가
    `hg files`와 달리 미커밋 삭제 파일도 포함하고 기본 패턴이 relglob이라는 것,
    `hg copy`의 copy-chain이 커밋 시점에 끊긴다는 것, `hg bundle`의 정확한 cg1
    델타베이스 규칙과 real hg 양방향 라운드트립 등 — 상세는 커밋 메시지 참고),
    `Hg` 파사드에 전부 배선. 전체 회귀 2223 테스트, 실패 0. 커밋 `d055084`.

    부수 발견(범위 밖, 새 백로그 — 아래 항목 14): `ManifestCommand` 작업 중
    `CommitCommand`의 미변경 파일 감지가 symlink에서도 `File#length()`를 호출해
    타겟 파일 크기를 잘못 참조하는 버그 발견.
