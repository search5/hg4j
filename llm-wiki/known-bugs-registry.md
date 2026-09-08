---
updated: 2026-09-09
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

### `HgLocalClient.getBundle()` — 보낼 게 없을 때 `new byte[0]`을 그대로 반환
**증상(심각 — 신규 저장소를 아무도 clone 못 함)**: yona 앱으로 만든, 커밋이 0개인 갓
`hg init`된 저장소를 real hg 클라이언트로 최초 clone하면 HTTP 경로는 즉시
`abort: stream ended unexpectedly (got 0 bytes, expected 4)`, SSH 소켓 릴레이(raw
stream) 경로는 아예 hang(4바이트 길이 헤더를 영원히 기다림). 실제 yona 앱으로
end-to-end 재현(2026-09-08)해 hg4j 자체 버그임을 확정.
**근본 원인**: `getBundle(...)`이 (a) `00changelog.i`가 아예 없을 때, (b)
`changelog.getRevisionCount() == 0`일 때, (c) 증분 pull에서 `startRev >= count`
(클라이언트가 이미 최신)일 때, 이 세 지점 모두 `ChangegroupParser.writeBundle`/
`Bundle2Parser.wrapChangegroupInBundle2`(또는 레거시 `"HG10UN"` 접두사)를 전혀
거치지 않고 바로 `return new byte[0]`. real hg 클라이언트 입장에선 "정상적으로
봉투에 감싸인 빈 changegroup"과 "스트림이 중간에 잘렸다"를 구분할 방법이 전혀 없는,
프로토콜상 무효한 응답 — 사실상 (a)/(b)는 같은 케이스(신규 빈 저장소)이고 (c)는
별도로 발견된 동일 버그의 두 번째 발현("이미 최신 상태인 pull"도 동일하게
hang/abort — 이전엔 이 시나리오를 테스트한 적이 자체가 없어 미발견 상태였음).
**수정**: 세 조기 반환을 모두 제거하고 정상 흐름으로 흘려보냄 — 뒤이은
changelog/manifest/filelog 루프는 전부 `for (int r = startRev; r < count; r++)`
형태라 `count==0`이든 `startRev>=count`든 그냥 0번 반복하고 끝나 자연스럽게 빈
`ChangegroupBundle`(3개 필드 모두 빈 리스트)이 만들어진다는 것을 확인. 이렇게 만든
빈 bundle을 기존과 동일하게 `ChangegroupParser.writeBundle`(3개의 빈 종료 청크만
있는 올바른 cg1)로 직렬화한 뒤 `Bundle2Parser.wrapChangegroupInBundle2`(bundle2
요청 시) 또는 `"HG10UN"` 접두사(레거시 요청 시)로 감싼다. `remoteRepo.getRevlog()`는
idxFile이 존재하지 않아도(일반 v1 저장소 기준) 디스크에 아무것도 안 쓰고
`revisionCount=0`인 빈 `Revlog`를 안전하게 만들어준다는 것도 `RevlogIndex` 실측으로
확인(사이드 이펙트 없음 — v2 general/changelog 요구사항이 있는 저장소만 예외적으로
새 docket을 실제로 씀, 이는 이 버그와 무관한 기존 동작).
**실측으로 확인/정정한 것**: 사전 조사 문서는 "real hg의 `exchange.getbundlechunks()`가
보낼 게 없어도 절대 조기 반환하지 않는다"고 추정했는데, 실제 hg 7.2를 `hg serve`로
띄워 빈 저장소를 clone하며 와이어 바이트를 직접 캡처해보니 **real hg 서버 자신은
보낼 changeset이 없으면 bundle2 응답에서 CHANGEGROUP 파트 자체를 통째로 생략**하고
(LISTKEYS/PHASE-HEADS 파트만 보냄) 반환한다 — 그러나 hg4j 서버는애초에 bookmarks/
phases를 getbundle의 bundle2 파트로 인라인하지 않고 별도의 `listkeys` wire 커맨드로
서빙하는 다른 설계라서(기존 동작, 이 버그와 무관), hg4j가 CHANGEGROUP 파트를
"내용은 비었지만 형식은 올바르게" 채워 보내도 real hg 클라이언트가 문제없이 받아들임을
real-hg-CLI clone/pull 라운드트립으로 직접 검증 완료.
**부수 확인(버그 아님)**: 이 조사 중 "빈 원격으로의 첫 push도 비슷하게 깨져 있는가"도
직접 검증 — 이미 정상 동작함(`pushWithHooks`/`applyBundle`은 애초에 `getBundle`을
전혀 거치지 않는 별개 경로). 유일하게 걸리는 것은 real hg 클라이언트 자신의
`abort: push creates new remote branches: default` 안전장치(브랜치가 하나도 없는
원격에 첫 push할 때 `--new-branch` 요구) — hg4j와 무관한 real hg의 표준 동작.
**회귀 테스트**: `HgHttpWireServerEmptyRepoRealHgInteropTest`(real hg CLI로 빈
저장소 clone/무변경 pull/빈 저장소로의 첫 push, 3건 모두 수정 전 RED→수정 후 GREEN
확인) + `HgLocalClientCoverageTest`의 기존 "returns empty bytes" 3건을 "well-formed
빈 bundle" 검증으로 교체. 발견 이력: 2026-09-08, yona 앱 실사용 중 발견(신규 프로젝트
최초 clone 100% 재현).

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

### `HgRemoteClient(String)` 생성자 — URL 내장 인증정보 미파싱
**증상**: 실제 hg 자신의 URL 관례(`mercurial/urlutil.py`의 `url` 클래스)는
`https://user:pass@host/path` 형태로 인증정보를 목적지 URL에 직접 담는 것을 정식으로
지원하고 HTTP Basic 인증에 그대로 쓴다 — `hg push`/`hg pull` 실사용에서 가장 자연스러운
인증 방법 중 하나. `HgRemoteClient`(HTTP 전송 계층)는 `setCredentials(user,pass)`/
`setCredentialsProvider(...)`는 이미 정상 동작했지만, 생성자가 URL 문자열을 그대로
`baseUrl`에 저장할 뿐 userinfo 부분을 전혀 파싱하지 않아 이 방식으로 인증하려는 호출자는
Authorization 헤더가 아예 안 실려 401/403을 받았다. `PushCommand`/`FetchCommand`/
`IncomingCommand`/`OutgoingCommand`가 전부 거치는 단일 접점
`HgRemoteConnectionFactory.createConnection(url)` → `new HgRemoteClient(url)` 경로라
사실상 HTTP(S) 목적지로의 모든 명령에 영향. 참고로 `HgSshClient.parseSshUrl()`은
`ssh://user[:pass]@host[:port]/path`를 이미 처음부터 정확히 파싱하고 있었다 — HTTP
전송 계층만 이 관례를 놓치고 있었다.
**수정**: `HgRemoteClient(String url)` 생성자에서 authority 부분의 `user[:pass]@`를
직접 파싱(엄격한 RFC 3986을 요구하는 `java.net.URI` 대신 손으로 파싱 — 실제 hg
URL 파싱은 이보다 더 관대해서, 예를 들어 percent-encode 안 된 raw 비밀번호도
그대로 왕복시킨다)해 `this.username`/`this.password`를 채우고, userinfo가 제거된
URL을 `baseUrl`로 쓴다. 이후 요청 경로(`executeGet`/`executePost` 등, Authorization
헤더를 세팅하는 3곳)는 기존 코드 그대로 — `username`/`password` non-null 검사만으로
자동으로 동작한다. userinfo 값은 `application/x-www-form-urlencoded`용
`URLDecoder`(`+`를 공백으로 취급 — 틀림) 대신 전용 percent-decoder로 디코딩.
`HgSshClient`는 손대지 않음(이미 정확했고, 이번 변경과 무관).
**발견 이력**: 1회, yona-convert 코디네이터 세션(2026-09) — yona 서버에 인증 필요한
Mercurial 프로젝트로 push 검증 중 발견. 상세: `HgRemoteClientTest.
testHgRemoteClientEmbeddedUrlCredentials()`(실제 `HttpServer`가 Basic 인증을 강제하는
통합 테스트 — 올바른 자격증명/무자격증명/틀린 자격증명 3가지 모두 검증).

### `DiffCommand.newRevision` — "값 미지정" sentinel이 "빈 매니페스트" sentinel과 충돌
**증상**: `oldRevision`은 "값 미지정"(-2)과 "빈 매니페스트를 뜻하는 리비전 없음"(-1)이 서로
다른 값이라 `setOldRevision(-1)`/`setOldRevision(존재하지 않는 NodeId)`가 정확히 "빈
매니페스트"로 처리됐지만(`ManifestTreeIterator.loadEntries()`의 "-1" 조기 반환), `newRevision`은
"값 미지정"(호출자가 `setNewRevision()`을 아예 안 부른 경우, 기본값 tip)도 -1을 썼다 —
`call()`이 `targetNew == -1`이면 무조건 tip으로 치환해버려서, `setNewRevision(-1)`이나
`setNewRevision(존재하지 않는 NodeId)`처럼 "새 쪽을 빈 매니페스트로 명시적으로 요청"한 경우도
조용히 tip과의 diff로 바뀌었다(예외나 로그 없음 — 완전히 조용한 버그).
**수정**: "값 미지정" 전용 sentinel을 `Integer.MIN_VALUE`(상수 `NOT_SET`)로 분리해 -1과 겹치지
않게 함(`oldRevision`의 -2/-1 분리 방식과 대칭). `setNewRevision((NodeId) null)`도 새 sentinel을
쓰도록 함께 수정, `NodeId`가 안 풀리는 `IOException` 폴백은 기존 그대로 -1 유지(빈 매니페스트
의미 — `oldRevision`의 대칭 메서드와 동일 관례).
**발견 이력**: 1회, yona-convert 코디네이터 세션(2026-09-09) — yona `HgRepository.getDiff(revA,
revB)`가 존재하지 않는 revB를 -1로 근사해 호출했다가 엉뚱하게 tip과의 diff가 나오는 걸
포착. 상세: `DiffCommandCoverageTest`의
`test{Explicit,Unresolvable}NewRevisionMinusOneMeansEmptyManifestNotTip`/
`testUnsetNewRevisionStillDefaultsToTip`/`testSetNewRevisionNodeIdNullResetsToDefaultTip`.

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
**1차 발견(백로그 24번)**: 장수 서버 핸들이 hg CLI로 직접 수정된 저장소를 못 봄 —
`HgRepository.refreshIfChangedOnDisk()` 신설로 해결.

**2차 발견(재발, 2026-09-06)**: 백로그 39번(2026-09-05)이 `StripCommandCoverageTest`
회귀(커밋 직후 같은 핸들로 strip/rebase/histedit 시 방금 쓴 리비전을 잃어버리는 문제)를
고치려고 `refreshIfChangedOnDisk()`/`RevlogIndex.checkAndUpdate()`에
`hasLocallyAddedRecords()` 가드를 추가했는데, 이 가드가 "이 인스턴스가 로컬로 뭔가
쓴 적이 있으면" 영구적으로(그 이후 몇 번을 더 써도) true로 고정돼버려서, **백로그
24번이 원래 고쳤던 바로 그 시나리오를 다시 깨뜨렸다** — 장수 서버 핸들이 시작 시
로컬 커밋을 딱 한 번만 해도(예: 초기 seed 커밋), 그 이후로는 외부 `hg` 프로세스가
아무리 커밋을 더 쌓아도 영원히 못 봄. `jakarta.servlet` 마이그레이션과는 무관하게
`HgHttpWireServerRealHgInteropTest
#realHgClonesMultipleBranchesBookmarksAndTagsFromHg4jServedOverHttp`에서 발견,
`git stash`로 마이그레이션 이전 원본 코드에서도 3/3 동일 재현 확인.
**근본 원인**: `RevlogIndex.addRecord()`는 로컬 쓰기마다 `lastKnownSize`를 실제
디스크 크기로 정확히 갱신해두는데, `checkAndUpdate()`가 `addedRecords`가 비어있지
않으면 이 정확한 `lastKnownSize` 정보를 아예 활용을 안 하고 통째로 건너뛰어버림.
**수정**: `checkAndUpdate()`에 `addedRecords`가 비어있지 않아도 `currentSize >
lastKnownSize`(순수 성장)일 때만 기존 `loadIndexIncremental()`(꼬리에 새 항목만
추가, 기존 `addedRecords`/`nodeMap`은 절대 안 건드림)을 타도록 분기 추가 — StripCommand
시나리오는 항상 크기 *감소*라 이 새 분기 조건에 걸리지 않아 기존 보호는 그대로 유지됨
(`StripCommandCoverageTest`로 회귀 없음 재확인). 회귀 테스트:
`HgRepositoryTest#testGetRevlogDetectsExternalWritesEvenAfterThisHandleMadeItsOwnEarlierLocalCommit`
(real hg CLI 불필요, TDD로 수정 전 실패 확인 후 복원). **교훈**: 한 버그를 고치려고
추가한 가드가 다른 버그를 되살릴 수 있다 — 새 가드를 넣을 때는 그 가드가 어떤 기존
동작을 되돌리는지 항상 재검증할 것. 발견 이력: 백로그 24번(1차), 45번 이후 후속
작업 중(2차, 2026-09-06).

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
