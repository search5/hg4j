---
updated: 2026-09-06
status: completed
---

# 심볼릭 링크 처리 버그 모음

Mercurial은 심볼릭 링크를 `lstat` 방식(링크 자신을 "링크가 가리키는 경로 문자열"로
취급, 절대 타겟을 따라가지 않음)으로 다루는데, Java의 `File` API(`length()`/
`lastModified()`/`isFile()`/`exists()`/`canExecute()`)는 기본적으로 심볼릭 링크를
따라가(follow) 타겟의 정보를 반환한다 — 이 불일치가 이 세션 내내 반복해서 여러 명령에
개별적으로 나타난 버그 계열이다. 관련 백로그: 10(깨진 symlink가 조용히 누락·거부됨),
14(`CommitCommand`가 symlink 미변경 판단 시 `File#length()`로 타겟 크기 참조).

**후속 발견(2026-09-05, 백로그 39 wave 5 작업트리 그룹)**: `DirstateV2Node`의
exec/symlink 플래그 상호배타 버그도 근본적으로 같은 계열의 문제(심볼릭 링크의 실행
비트 처리)다 — 상세는 [[dirstate-v2]] 참고. 같은 wave에서 `UpdateCommand`의 심볼릭
링크 dirstate 모드가 `0120000`(타입 비트만)으로 기록되던 것을 `0120777`(실제 hg의
`lstat` 값)로 수정한 것도 이 문서의 아래 "심볼릭 링크 dirstate mtime 버그" 절과 같은
계열.

## 원문 (백로그 10, 14)
10. ~~**깨진(dangling) symlink가 `AddCommand`/`HgRepository`에서 조용히 누락·거부됨**~~
    — ✅ **완료(2026-09-02)**. `HgRepository.scanDirectory()`, `AddCommand.call()`의
    명시적 경로 처리, `CommitCommand.call()`의 tracked-file 존재 검증까지 **3곳
    전부** `File.isFile()`/`.exists()`로만 판단하고 있어서(심볼릭 링크를 따라가
    타겟이 없거나 디렉터리면 `false`) 깨진 symlink는 전체 스캔에서 조용히
    누락되고, 명시적 `hg add <path>`/커밋 시엔 아예 거부됐다(세 번째는 커버리지
    작업 때는 발견 못 하고 이번 수정 중 TDD로 재현하다 추가로 찾음). 실제 hg 7.2로
    확인(`ln -s missing-target.txt link.txt; hg add` → `A link.txt`, 커밋까지 정상
    round-trip, 심지어 타겟이 **디렉터리**를 가리키는 symlink도 재귀 안 하고
    그냥 파일로 추적함)한 뒤 세 지점 전부 `Files.isSymbolicLink()` 체크를 추가.
    TDD 3건(명시 경로 add, 전체 스캔, add→commit 라운드트립에서 매니페스트 `l`
    플래그·filelog 콘텐츠 검증) + 기존 `HgRepositoryCoverageTest`의 "symlink-to-
    directory는 완전히 스킵돼야 한다"는 낡은(버그 기준) 단언을 실측 정정. 전체
    회귀 클린(사전에 존재하던 무관한 `PushCommandTest` 실패 1건 제외 — 아래 참고).
    커밋 `f0244f2`.
14. ~~**`CommitCommand`가 symlink의 미변경 여부를 판단할 때 `File#length()`로
    타겟 파일 크기를 참조함**~~ — ✅ **완료(2026-09-02)**. symlink 자신의 "크기"는
    타겟 경로 문자열의 바이트 길이여야 하는데(다른 곳, 예컨대 `AddCommand`/
    `CopyCommand`/`GraftCommand`/`RebaseCommand`는 이미
    `Files.readSymbolicLink(...).toString().getBytes(...).length`로 올바르게
    처리), `CommitCommand`의 미변경(재커밋 스킵) 판정 경로(size 비교 + M-2
    racy-write 콘텐츠 비교 두 곳 모두)가 `File#length()`/`Files.readAllBytes()`를
    그대로 써서 symlink가 가리키는 대상 파일을 읽어버리고 있었다 — 그 결과 symlink
    자신의 타겟 문자열은 전혀 안 바뀌었는데도 타겟 파일 크기/내용이 바뀌면 symlink가
    "변경됨"으로 오판되어 불필요한 새 filelog 리비전(부모 체인이 다른 새 노드 ID)이
    생성됐다. TDD로 재현(타겟 파일만 키우고 symlink 자체는 안 건드린 뒤 재커밋 →
    filelog 리비전 수가 그대로 1이어야 함을 실패하는 테스트로 확인) 후 두 지점 모두
    `Files.readSymbolicLink()` 기반으로 수정. 전체 회귀 클린. 커밋 `1162715`.
## 심볼릭 링크 dirstate mtime 버그 (2026-09-03, 별개 발견 — 프로토콜과 무관)

위 SSH/unbundlehash 작업 완료 후, 회귀에서 우연히 나온 `CommitCommandTest`의 심링크 관련
테스트 1건을 "플레이키"로 넘기려다 사용자가 제동을 걸어("재실행시 통과라는 말이 웃기잖아")
격리 재실행(8회 중 1회 실패)으로 재현성을 직접 확인, 근본 원인을 찾았다: `java.io.File`의
`lastModified()`/`length()`/`canExecute()`/`isFile()`/`exists()`는 심볼릭 링크를 항상
**따라가서**(follow) 링크 자신이 아니라 타겟의 정보를 반환한다(NIO의 `NOFOLLOW_LINKS`
같은 옵션이 legacy `File` API엔 없음). dirstate에 mtime을 기록/비교하는 코드가 이걸
모르고 썼다가, 심링크 자신은 안 건드렸는데 타겟 파일만 커지면 조용히 재커밋되거나
(반대로) 실제 변경을 놓치는 타이밍 의존적 버그였다.

**영향 범위**: `size`는 이미 lstat 인식(NIO `readSymbolicLink`)으로 처리하던 파일이
대부분이었지만 `mtime`만 buggy `File#lastModified()`를 그대로 쓰고 있었다 —
`CommitCommand`/`StatusCommand`/`AddCommand`/`UpdateCommand`/`RebaseCommand`(2곳)/
`ShelveCommand`(3곳)/`RevertCommand`/`CopyCommand`/`GraftCommand`/`CloneCommand` 총
10개 파일 12곳. `RenameCommand`/`RemoveCommand` 2개는 아예 lstat 인식 자체가 없어서
(size도 target을 따라감) 별도로 더 깊게 고쳐야 했다 — `RenameCommand`는 `exists()`가
dangling 심링크를 거짓으로 판단해 존재하는 파일도 rename 거부, mode/size/mtime 전부
타겟 기준으로 오염; `RemoveCommand`는 (1) 타겟 크기와 심링크 자신의 기록 크기를 비교해
손 안 댄 심링크를 "수정됨"으로 오판하고, (2) dangling 심링크는 `exists()`가 false라
물리 삭제 자체를 건너뛰어 dirstate엔 제거로 기록되지만 디스크엔 파일이 남는 버그가
있었다.

**수정**: `SafeFileIO.lastModifiedSeconds(File)` 공유 헬퍼(NIO
`Files.getLastModifiedTime(path, NOFOLLOW_LINKS)`) 신설, 12개 호출부 전부 교체.
`RenameCommand`/`RemoveCommand`는 `isSymbolicLink` 분기를 추가해 mode/size/mtime/삭제
로직을 다른 9개 파일과 같은 lstat 인식 패턴으로 맞춤. 각 버그를 TDD로(먼저 실패하는
테스트로 재현 확인 후 수정) 처리, `CommitCommandTest`의 원래 실패 테스트는 8/8 반복
실행으로 결정성 확정. 전체 회귀 2282 테스트, 0 실패.

5. **심볼릭 링크에 대한 `StatusCommand`/`CommitCommand`의 size/content 불일치** —
   조사 결과 확정: Java의 `File.length()`/`Files.readAllBytes()`는 심볼릭 링크를
   만나면 링크를 따라가 **대상 파일**의 크기/내용을 반환하는데, 실제 hg는 `lstat`
   방식으로 심볼릭 링크 자체를 "링크가 가리키는 경로 문자열"로 취급한다(filelog에도
   이 문자열이 그대로 저장됨). `UpdateCommand`/`RevertCommand`/`ShelveCommand`는 이미
   경로 문자열 길이를 올바르게 썼지만, `CommitCommand`/`StatusCommand`는
   `File.length()`로 대상 파일 크기를 썼다 — 두 관례가 섞여 있어 대상 파일 내용
   길이가 링크 경로 문자열 길이와 다르면 아무것도 안 건드린 심볼릭 링크도 항상
   modified로 오탐했다. `StatusCommand`에 `effectiveSize()`/`effectiveContent()`
   헬퍼를 추가하고 `CommitCommand`의 dirstate 기록도 경로 문자열 길이 기준으로
   통일해 수정. TDD로 확인.
