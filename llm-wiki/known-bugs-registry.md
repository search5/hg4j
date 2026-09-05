---
updated: 2026-09-06
status: current
---

# 알려진 버그 레지스트리

**목적**: 이 세션 내내 반복된 문제 — 여러 웨이브/에이전트가 같은 하위 클래스의 같은
버그를 서로 모르고 중복 발견하는 것(가장 극적인 사례: `DeltaCodec.decompressZstd`가
4개의 독립된 병렬 웨이브에서 각각 새로 발견됨)을 막기 위한 색인. **새 명령/기능의
매트릭스 작업을 시작하기 전, 그 명령이 호출하는 하위 클래스들을 여기서 먼저 검색할
것** — 이 규칙은 `llm-wiki/AGENTS.md`와 `llm-wiki/implementation-plan.md`에도 명시돼
있다.

클래스.메서드 단위로 정렬. 각 항목: 증상 → 근본 원인 → 수정 → 발견 이력(몇 번
독립 발견됐는지, 어느 백로그/웨이브에서).

## 공유 인프라 계층 (여러 명령에 영향 — 반드시 먼저 확인)

### `DeltaCodec.decompressZstd()`
**증상**: 델타(비-리터럴) 리비전의 zstd 압축 해제 결과가 손상되거나
`HgCorruptDataException("Invalid delta hunk offsets")`로 실패.
**근본 원인**: 목적지 버퍼 크기로 revlog 인덱스의 `uncompLen`(델타 체인을 전부 적용한
뒤의 **최종 재구성 텍스트 크기**)을 그대로 썼는데, 델타 자체의 압축 해제 크기는 보통
이보다 작다 — 남는 버퍼가 0으로 패딩되고 `DeltaEngine.applyDelta()`가 이를 가짜 델타
헝크로 오인.
**수정**: zstd 프레임 자체에 내장된 content-size 헤더(`Zstd.getFrameContentSize()`)로
목적지 버퍼를 정확히 사이징, 방어적으로 실제 반환 길이로 재trim.
**발견 이력**: **4번 독립 발견** — core/query 웨이브(백로그 39 wave 5), admin/maintenance
웨이브의 Gc/Recover/Rollback 서브에이전트, 같은 웨이브의 Verify/Censor 서브에이전트,
작업트리 웨이브. 병합 시마다 매번 `DeltaCodec.java` 충돌이 나서 로직 동일함을 확인 후
정리해야 했다. 상세: [[backlog/39-exhaustive-interop-matrix]].

### `RevlogIndex.checkAndUpdate()` / `addedRecords`
**증상**: (a) 읽기 전용 핸들이 스로틀 때문에 stale 리비전 카운트를 반환(백로그 13),
(b) `HgRepository.refreshIfChangedOnDisk()`가 로컬 쓰기 이력 있는 revlog까지 통째로
캐시 무효화해 `StripCommand`의 북마크 재배치가 stripped 노드를 못 찾고 조용히 삭제
(백로그 39 wave 5 core/query 회귀).
**근본 원인**: "한 번이라도 자체적으로 쓴 적 있는 RevlogIndex는 자기 북키핑을
신뢰해야 한다"는 불변조건을 `checkAndUpdate()`는 `addedRecords.isEmpty()` 가드로
지키고 있었는데, `refreshIfChangedOnDisk()`는 이 가드를 우회해 캐시 전체를 새
인스턴스로 교체해버렸다.
**수정**: 스로틀 완전 제거(단, `addedRecords.isEmpty()` 게이트는 유지) + `RevlogIndex`/
`Revlog`에 `hasLocallyAddedRecords()` 노출, `refreshIfChangedOnDisk()`가 캐시된
changelog가 이미 로컬 쓰기 이력 있으면 무효화를 건너뛰도록 수정.
**발견 이력**: 백로그 13번(2026-09-02, 스로틀), 백로그 39 wave 5(2026-09-05,
refreshIfChangedOnDisk 과다 연결) — 같은 클래스, 반대 방향의 실수가 두 번.
상세: [[backlog/push-and-concurrency]], [[backlog/39-exhaustive-interop-matrix]].

### `DirstateV2Node` / `DirstateV2Serializer` — exec/symlink 플래그
**증상**: dirstate-v2 저장소에서 real hg의 `hg status`가 hg4j가 쓴 심볼릭 링크를
항상 "M"(수정됨)으로 오판.
**근본 원인**: `MODE_EXEC_PERM`과 `MODE_IS_SYMLINK`를 상호 배타로 처리 — 실제
심볼릭 링크의 `lstat` 모드는 항상 실행 비트를 포함하므로 두 플래그를 **항상 함께**
켜야 한다.
**수정**: 항상 함께 설정. 다른 완료 명령(`AddCommand`/`CommitCommand`/`MergeCommand`/
`RebaseCommand`)에도 잠재했을 수 있는 공유 계층 버그로 명시 — 병합 후 전체 회귀
재확인 결과 회귀 없음.
**발견 이력**: 1회(백로그 39 wave 5 작업트리 그룹). 상세: [[dirstate-v2]].

### 심볼릭 링크 lstat 불일치 (여러 클래스, 12곳)
**증상**: 심볼릭 링크 자신은 안 건드렸는데 타겟 파일 크기/mtime/실행비트가 바뀌면
"수정됨"으로 오판, 또는 반대로 실제 변경을 놓침.
**근본 원인**: Java `File` API(`length()`/`lastModified()`/`canExecute()`/`isFile()`/
`exists()`)가 심볼릭 링크를 항상 따라가는데, Mercurial은 `lstat`(링크 자신의 경로
문자열 기준)으로 다룬다.
**수정**: `SafeFileIO.lastModifiedSeconds()`(NIO `NOFOLLOW_LINKS`) 등 lstat 인식
헬퍼로 통일. `CommitCommand`/`StatusCommand`/`AddCommand`/`UpdateCommand`/
`RebaseCommand`/`ShelveCommand`/`RevertCommand`/`CopyCommand`/`GraftCommand`/
`CloneCommand`(mtime, 10파일 12곳) + `RenameCommand`/`RemoveCommand`(size까지,
lstat 인식 자체가 없었음).
**발견 이력**: 백로그 10번(누락·거부), 14번(CommitCommand 크기), 2026-09-01
추가 완료 항목(StatusCommand/CommitCommand), 2026-09-03(mtime 전수 조사, 12곳).
상세: [[symlink-handling]].

## 명령별 버그

### `StatusCommand` / `ShelveCommand` — racy-write 검증 대상 리비전 오류
**증상**: `hg update`로 과거 리비전 전환 후 손대지 않은 파일도 modified로 오탐,
`ShelveCommand`는 같은 초·같은 크기 편집을 놓쳐 shelve가 no-op이 됨.
**근본 원인**: 비교 대상을 "filelog의 가장 최근 리비전"으로 고정 — 워킹카피가 tip에
있을 때만 맞는 가정.
**수정**: 워킹카피의 실제 dirstate parent 커밋 기준으로 비교(`getParentCommitFileContent()`/
`getBaselineContent()`).
**발견 이력**: 커버리지 95% 이니셔티브 라운드(2026-09-01).

### `ResolveCommand`
레거시 v1 → `.hg/merge/state2`(`MergeState`) 기반 전면 재작성. 상세: [[backlog/01-resolve-mergestate]].

### `BackoutCommand`
**증상(데이터 손실 가능)**: 작업 디렉터리의 직계 부모가 아닌 오래된 조상을
백아웃할 때 3-way merge/충돌 감지 경로가 아예 없어, 이후 독립 변경을 조용히
덮어쓰거나 무시할 수 있었음.
**수정**: `RebaseCommand.attemptThreeWayMerge()` 재사용, `.hg/merge/state2` 기록,
조상 검증/root 커밋 백아웃 거부 추가. 발견 이력: 백로그 39 wave 4.

### `RevertCommand`
**증상(데이터 손실)**: add-uncommitted 파일 되돌릴 때 콘텐츠를 통째로 삭제(real hg는
untrack만 함). 반대 경우(대상 리비전에 없는 파일)는 삭제+`R` 마킹이 빠짐. `.orig`
백업 미구현.
**수정**: 두 경로 모두 real hg 동작에 맞춤, `.orig` 백업 추가. 발견 이력: 백로그
39 wave 4.

### `PurgeCommand`
**증상(실제 데이터 손실)**: 심볼릭 링크로 연결된 디렉터리를 실제로 따라 들어가
저장소 바깥의 파일을 삭제할 수 있었음. 끊어진 심볼릭 링크는 건너뜀.
`purgeDirectories` 기본값이 `false`(real hg는 기본으로 빈 디렉터리 삭제).
**수정**: 심볼릭 링크를 항상 불투명 leaf로 취급, `NOFOLLOW_LINKS`, 기본값 `true`로
변경. 발견 이력: 백로그 39 wave 5 작업트리 그룹.

### `WorktreeCommand`
**증상**: 실제 체크아웃을 전혀 수행하지 않고 빈 40바이트 dirstate 스텁만 생성.
2차 버그: 이 스텁이 dirstate-v2 공유 저장소에서는 유효한 도켓이 아니라
`BufferUnderflowException`.
**수정**: `UpdateCommand`로 실제 체크아웃, requires에 "shared" 마커 추가, 리비전
있을 때는 스텁을 아예 안 쓰고 새로 생성. 발견 이력: 백로그 39 wave 5 작업트리 그룹.

### `ArchiveCommand`
**증상**: `.hg_archival.txt` 누락, zip/tar 디렉터리 프리픽스 누락, 실행비트/심볼릭
링크 무시, tar/tgz/tbz2 미지원, 평면 매니페스트 전용이라 treemanifest 하위 디렉터리
누락.
**수정**: `HgRepository#getManifestAtCommit()`로 교체, tar/tgz/tbz2 + 실행비트/
심볼릭 링크 + `.hg_archival.txt` 신규 구현(`txz`는 신규 의존성 필요해 범위 밖).
발견 이력: 백로그 39 wave 5 작업트리 그룹.

### `CommitCommand` — 순수 chmod 변경 누락
**증상(데이터 손실)**: 이미 추적 중인 파일의 순수 실행 비트 변경(`chmod +x`, 내용
불변)을 전혀 감지 못해 영구적으로 커밋에서 누락(크기/mtime만 비교, chmod는 POSIX에서
mtime을 안 건드림).
**수정**: 실행 비트 비교 추가, 내용 동일 시 기존 filelog 리비전 재사용.
발견 이력: 백로그 39 wave 5 작업트리 그룹(TreeMergeCommand 테스트 작성 중 부수 발견).

### `InitCommand`
**증상**: 원래 dirstate-v2/zstd 2개 축만 지원해 36개 조합 중 30개 이상을 스스로
만들 수조차 없었음.
**수정**: real hg의 상호 함의/배타 규칙까지 반영해 4개 축 전부 구현.
발견 이력: 백로그 39 wave 5 admin/maintenance 그룹.

### `DefaultFileStoreEngine` / `RevlogIndex` — changelog-v2+general-v2 부트스트랩 순서
**증상(크래시)**: changelog-v2 + general-v2 동시 활성화 시 changelog가 잘못된
포맷으로 생성돼 real hg가 그 위에 커밋하면 `fast_rank()`에서 `TypeError`.
**근본 원인**: `createAsGeneralV2`가 `createAsChangelogV2`보다 먼저 체크됨(real hg의
실제 우선순위와 반대).
**수정**: 우선순위 수정. 발견 이력: 백로그 39 wave 5 admin/maintenance 그룹(InitCommand
36콤보 검증 중 발견).

### `GcCommand`
**증상(데이터 손상)**: v2/docket revlog를 만나면 구식 v1 헤더로 통째로 덮어씀.
fncache 재구축이 real hg가 안 쓰는 루트 revlog를 끼워 넣거나 fileindex-v1 저장소에도
무조건 fncache를 씀. 분할된(non-inline) revlog를 재압축 중 실수로 다시 inline화.
**수정**: v2/docket revlog는 건너뛰기, real hg 실측대로 fncache 조건 수정, inline화
버그 수정. 발견 이력: 백로그 39 wave 5 admin/maintenance 그룹.

### `CommitCommand`/`RollbackCommand`/`RecoverCommand`/`GraftCommand` — v2-docket undo/journal
**증상**: undo/journal 기록이 v2 docket 파일의 (append해도 안 변하는) 바이트
길이만 기록해서 changelog-v2/general-v2 커밋에 대한 rollback/recover가 완전히
무동작(성공 커밋 직후 크래시 시 "phantom commit" 위험). dirstate-v2 컴패니언
파일도 rollback 후 미복원.
**수정**: docket 전체 내용 백업/복원 방식으로 재구현.
**발견 이력**: 이 버그 클래스가 **3개 명령에서 독립적으로 발견됨** —
`CommitCommand`/`RollbackCommand`/`RecoverCommand`(백로그 39 wave 5
admin/maintenance 그룹, 최초 발견·수정), `GraftCommand`(같은 웨이브 작업 중
"유사하지만 별도인 gap"으로 플래그만 남겨졌다가 ✅ 2026-09-06 후속 처리로
완료 — `GraftCommand`는 `CommitCommand`에 위임하면서도 자기 자신의 크래시-
안전 저널을 별도로 관리하고 있어 `CommitCommand` 자체의 수정이 전파되지
않았던 것이 재발 원인). **교훈**: "다른 명령에 위임하니 안전하다"고 가정하지
말고, 위임 호출부 자신이 별도의 저널/롤백 상태를 갖고 있는지 항상 확인할 것.
상세: [[backlog/39-exhaustive-interop-matrix]].

### `VerifyCommand`
**증상**: filelog 발견을 fncache에만 의존해 fileindex-v1/general-v2 저장소에서는
검사가 통째로 스킵(거짓 음성). treemanifest 서브매니페스트도 검사 대상에서 빠짐.
**수정**: 둘 다 추가. 발견 이력: 백로그 39 wave 5 admin/maintenance 그룹.

### `CensorCommand` / `Revlog.censorRevision()`
"head/작업 디렉터리 parent에 살아있는 리비전은 censor 거부" 가드 부재,
general-v2 filelog를 파괴하던 포맷-무관 재작성 버그. 상세: [[censor]].

### `IncomingCommand`
**증상**: 콘텐츠 있는 real hg 서버 어디에도 100% 깨져 있었음(HTTP 500).
**근본 원인**: 항상 구식 `changegroup` wire 명령을 빈 `roots`로 요청 — real hg
자신의 `discovery.outgoing()` 레거시 코드 결함과 맞물려 서버가 크래시.
**수정**: `FetchCommand`의 getbundle-우선 협상 로직을 공용 정적 메서드로 추출해
재사용. 발견 이력: 백로그 39 wave 5 wire-matrix 그룹.

### `HgRemoteClient.getChangegroup()` (HTTP)
**증상**: `roots`가 빈 리스트일 때 요청 파라미터에서 키 자체를 생략 — 서버가
`KeyError`(HTTP 500). **수정**: 빈 문자열이라도 항상 전송(`HgSshClient`는 이미
정확했음). 발견 이력: 백로그 39 wave 5 wire-matrix 그룹.

### `GrepCommand`
fileindex-v1/general-v2 저장소(fncache 없음)에서 조용히 빈 결과 반환.
`store/data/` 재귀 스캔 폴백 + `NodeIdUtil.decodeStoreDataPath` 신규.
발견 이력: 백로그 39 wave 5 콘텐츠/트리읽기 그룹.

### `AnnotateCommand`
rename+편집이 같은 커밋에 있을 때 그 커밋 자신의 diff를 건너뛰어 줄 유실/오귀속.
`DiffCommand`와 같은 계열의 "가짜 후행 개행" 버그도 있었음. 발견 이력: 백로그 39
wave 5 콘텐츠/트리읽기 그룹.

### `BisectCommand` — treemanifest 체크아웃
루트 매니페스트를 hand-roll 파싱해 서브디렉터리 항목을 실제 파일로 오인.
treemanifest-aware `ManifestWalk`로 교체. 발견 이력: 백로그 39 wave 5 core/query 그룹.

### `Revlog.decompressSidedataChunk`
changelog-v2+sidedata 저장소의 sidedata 압축 모드를 zstd로 무조건 가정 — zlib
압축 저장소를 못 읽음. 발견 이력: 백로그 39 wave 5 core/query 그룹.

### `HgHttpWireServer`/`HgSshWireServer` — 외부 프로세스 stale 캐시
장수 서버 핸들이 hg CLI로 직접 수정된 저장소를 못 봄 —
`HgRepository.refreshIfChangedOnDisk()` 신설로 해결. 발견 이력: 백로그 24번.

### `HgRemoteClient.getBundle()` — bundlecaps 인코딩
스페이스 join(틀림) → 콤마 join(실제 스펙). 이로 인해 cg 버전 협상이 항상
무력화돼 hg4j가 어떤 changegroup 버전을 광고하든 조용히 cg1으로만 통신.
발견 이력: 백로그 11번.

### `ChangegroupParser.parseBundle()` — cg3 루트 매니페스트
"루프 첫 반복부터 무조건 경로 청크를 읽는다"는 잘못된 가정 — 루트("") 매니페스트
그룹은 경로 청크 없이 바로 옴. cg3/4/5 공통 구조라 3버전 모두 영향. 발견 이력:
백로그 11번.

### `HisteditCommand` — 브랜치 유실 (데이터 손상급)
`commitNewRev()`가 `CommitCommand`를 안 거치고 changelog를 직접 조립하며 브랜치
extra 필드를 무조건 생략 — 재작성된 커밋이 조용히 전부 default 브랜치로 뒤바뀜.
발견 이력: 2026-09-01, [[backlog/branch-restore-bugs]].

### `BisectCommand`/`MergeCommand`(fast-forward만)/`StripCommand` — 브랜치 미복원
체크아웃/전진/strip 후 워킹 브랜치명(`.hg/branch`)을 복원 안 함. 발견 이력:
2026-09-01, [[backlog/branch-restore-bugs]].

### `NarrowCloneCommand.call()` — pull 직후 캐시 무효화 없음
**증상(모든 narrow clone 사용자에게 영향)**: pull 직후 update 호출 전 캐시
무효화가 없어 manifest `Revlog`가 stale 상태로 읽혀 `HgCorruptDataException`.
**수정**: `repository.clearRevlogCache()` 한 줄. 발견 이력: 백로그 30번.

### `HgTreeFilter` — narrowspec 컴포넌트 경계
include 매칭이 단순 `String#startsWith`라 `srcdir`가 형제 디렉터리 `srcdirextra/`도
잘못 포함. 발견 이력: 백로그 28번.

### `HgLfsPointer`/`HgLfsManager` — 노드 해시 기준
LFS 노드 해시가 포인터 텍스트가 아니라 실제 파일 콘텐츠 기준이어야 함. 발견 이력:
백로그 31번.

### `HgLfsManager` — 원격 LFS URL 유추 경로
**증상**: hg4j가 유추한 원격 LFS 서버 URL이 real hg가 실제로 쓰는 것과 다름.
**근본 원인**: `<remote>/info/lfs`로 유추했는데 real hg는 실제로
`<remote>/.git/info/lfs`를 쓴다(`hg clone -v` 로그로 실측 확인).
**수정**: `resolveServerUrl()`/`resolveContent()`로 통일, `[lfs] url` override
지원 추가. 부수 발견: `[lfs] threshold = 0`을 "모든 파일이 LFS"로 잘못 처리(real
hg는 falsy-zero를 "비활성"으로 취급) — 함께 수정. 발견 이력: 백로그 42번
(2026-09-06).

### `CommitCommand`/`FetchCommand` — fncache `.d` 파일 미등록
**증상**: revlog가 inline→non-inline으로 전환되면(백로그 43번) real `hg verify`가
"not in fncache" 경고.
**근본 원인**: 두 커맨드 모두 필드 등록된 `.i` 경로만 fncache에 넣고 `.d`는 넣지
않음 — inline-by-default 이전에는 항상 처음부터 non-inline이라 가려져 있었음.
**수정**: `filelog.isInline() == false`일 때 `.d` 엔트리도 추가(`GcCommand`의
fncache 재구축 관례와 통일).

**관련 후속(백로그 45번, ✅ 2026-09-06 완료)**: `CommitCommand`가 treemanifest
하위 디렉터리 manifest(`meta/<dir>/00manifest.*`)는 애초에 `data/`/`meta/` 어느
쪽으로도 fncache에 전혀 등록하지 않는 별개의 gap이었음. **실측으로 정정된 사실**:
43번 당시 문서에 "real `hg verify`가 경고할 것"이라 적었던 추정은 실제로는
틀렸다 — real hg 7.2로 직접 확인한 결과 `hg verify`는 `meta/` fncache 누락을
전혀 잡지 못하고, 유일하게 이 gap을 검출하는 real-hg-CLI 도구는
`hg debugrebuildfncache`(dry-run)였다("adding meta/<dir>/00manifest.i" 출력).
`writeTreeManifestDir`가 `fncachePaths`를 파라미터로 받아 디렉터리 revlog마다
`.i`(+ 비인라인일 때만 `.d`)를 등록하도록 수정, fncache 실제 쓰기 시점도
treemanifest 매니페스트 작성 이후로 이동(순서 자체가 근본 원인 중 하나였음).
**교차 점검 중 발견한 별개의 진짜 버그**: `FetchCommand`의 treemanifest
매니페스트 그룹 적용 경로는 이미 `meta/` 등록 코드가 있었지만 `.d`를
`isInline()` 체크 없이 **항상 무조건** 추가하고 있어서, 43번이 확립한
"인라인이면 `.d` 없음" 규칙과 정반대였다 — 같은 패턴(filelog isInline() 가드)으로
통일. 상세: [[backlog/revlog-storage-formats]].
발견 이력: 백로그 43번(2026-09-06), 45번 후속(2026-09-06).

## 관련 문서
- [[dirstate-v2]], [[censor]], [[symlink-handling]], [[push-and-concurrency]],
  [[backlog/branch-restore-bugs]], [[backlog/39-exhaustive-interop-matrix]] — 위
  각 항목의 전체 서술.
- `decisions/test-coverage-95-percent-initiative.md` — JaCoCo 커버리지 라운드별
  상세 수치(이 레지스트리는 그 라운드들에서 발견된 버그 중 `StatusCommand`/
  `ShelveCommand`/`HgRevsetEngine`/`SubrepoCommand` 항목만 색인하고 전체 라운드
  기록은 그 문서가 원본).
