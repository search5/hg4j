---
updated: 2026-11-03
status: 완료 (2026-11-04, Phase 1~7 전부 구현. 결과와 의도적 차이는 modules/lfs.md, known-bugs-registry.md, log.md를 볼 것. 이 문서는 실행 전 계획의 스냅샷이다)
audience: cross-agent handoff (이 대화의 맥락이 없는 외부 에이전트)
---

# hg4j 구현 계획서: LFS를 JGit 인터페이스에 준해 완성

[[implementation-plan-gpg-ssh-wire]]와 같은 형식의 별도 계획서입니다. 작성 규칙은 [[AGENTS|AGENTS.md]]의
규칙 8, 9, 10을 그대로 따릅니다.

- 추측을 사실처럼 쓰지 않습니다. 이 문서의 **"미확인"** 표시는 착수 전에 직접 확인하라는 뜻입니다.
- 완료 선언은 diff, 명령 출력, 실제 프로세스 응답 중 하나 이상의 증거로만 합니다.
- 실제 hg 7.2.4가 오라클입니다(`LocalHg`, `scripts/build-local-hg.sh`). LFS는 `--config extensions.lfs=`로 켭니다.

## 0. 배경, 현황, 결정 사항

### 0.1 현황 (2026-11-03 직접 확인)

[[narrow-clone-and-lfs]]의 28, 31, 42번 항목과 `modules/lfs.md`가 기록한 범위는 **저장 형식, 커밋, 체크아웃, 원격
blob 내려받기**까지입니다. 이번 점검에서 다음 공백을 확인했습니다.

| 공백 | 증거 |
|---|---|
| 읽기 명령이 포인터 텍스트를 돌려줌 | 실제 hg로 2000바이트 LFS 파일을 커밋한 저장소를 hg4j `CatCommand`로 읽으니 **143바이트** 포인터 텍스트가 반환됨. `Revlog#getRevisionContent`가 포인터를 풀지 않고, `Cat/Diff/Archive/Export`에 LFS 참조가 0건 |
| push가 blob을 올리지 않음 | `PushCommand`, `BundleCommand`, `HgHttpWireServer`에 LFS 참조 0건. `HgLfsManager`에는 내려받기(`fetchObject`)만 있고 업로드가 없음 |
| `lfs.track`, `.hglfs` 미지원 | 커밋 쪽은 `lfs.threshold`만 읽음. 원본은 `lfs.threshold`를 deprecated로 취급하고 `lfs.track`/`.hglfs`가 현재 방식(`hgext/lfs/__init__.py`의 `_trackedmatcher`) |
| LFS 서버 없음 | 원본 `hgext/lfs/wireprotolfsserver.py`에 해당하는 코드가 없음 |
| 부가 기능 없음 | `lfs()` 파일셋, `lfs_files` 템플릿, `debuglfsupload`, `verify` 연동 |

LFS 참조가 있는 파일은 `UpdateCommand`, `CommitCommand`, `AnnotateCommand`, `ImportCommand`, `Revlog`,
`HgLocalClient`, `lfs/*` 뿐입니다. 반대로 `getRevisionContent`/`getRawRevisionContent`를 호출하는 명령은 27개입니다
(Archive, Annotate, Amend, Backout, Clone, Bisect, Diff, Grep, Log, Cat, Bundle, Commit, Status, Export, Graft,
Outgoing, Gc, Merge, Rebase, Remove, SignatureStore, Summary, Push, Histedit, Update, Verify, Shelve).

### 0.2 JGit과 hg의 구조 차이 (그대로 옮기면 안 되는 부분)

JGit의 LFS 인터페이스는 **API 모양**(어디에 이음새를 두는가)의 모범으로만 따릅니다. 동작은 원본 hg를 따릅니다.

| 항목 | JGit | 원본 hg (이 계획서가 따르는 쪽) |
|---|---|---|
| LFS 대상 판단 | `.gitattributes`의 `filter=lfs` (`LfsFactory.getAttributesForPath`) | `lfs.track` 파일셋 또는 `.hglfs`의 `[track]`, 크기 임계값 |
| 변환 위치 | 작업 트리와 객체 DB 사이의 smudge/clean 필터 | filelog의 revlog 플래그 `REVIDX_EXTSTORED`와 flag processor |
| 노드 해시 | 포인터 blob의 해시 | **원본 내용** 기준 (`bypasscheckhash`가 False). 이미 [[narrow-clone-and-lfs]] 31번에서 확인 |
| 로컬 저장 경로 | `<repo>/.git/lfs/objects/<2자>/<다음2자>/<전체oid>` (`Lfs#getMediaFile`) | `.hg/store/lfs/objects/<2자>/<나머지>` 한 단계 (`lfsvfs.join`). **JGit 배치를 복사하지 말 것**: 과거 hg4j가 Git 방식으로 구현했다가 실제 hg와 blob을 공유하지 못한 버그가 있었음 |
| 메타데이터 | 포인터에 oid, size만 | 포인터에 `x-is-binary`, rename 시 `x-hg-copy`/`x-hg-copyrev` |
| 원격 URL | `<url>/info/lfs` + `/objects/batch` (`Protocol.INFO_LFS_ENDPOINT`, `OBJECTS_LFS_ENDPOINT`) | `<url>/.git/info/lfs/objects/batch`, blob 전송은 `.hg/lfs/objects/<oid>` (`wireprotolfsserver.py`) |
| 서버 능력 광고 | 없음 (HTTP 서블릿만) | wire `capabilities`에 `lfs`, LFS가 requirement면 `lfs-serve` 추가 (`hgext/lfs/wrapper.py`의 `_capabilities`) |
| push 시 업로드 | `LfsPrePushHook` | `prepush`가 나가는 리비전의 포인터를 모아 `writebatch`, 번들 작성 때도 같은 업로드 |

### 0.3 결정 사항

1. **이음새를 JGit의 `LfsFactory`처럼 한 곳에 둡니다** (`HgLfsFactory`). 명령이 LFS를 개별로 알지 않고 이 팩터리를 통해
   내용을 얻게 해서, 27개 호출 지점에 흩어진 조건문을 만들지 않습니다.
2. 서버(`org.eclipse.jgit.lfs.server`에 해당)는 **같은 jar 안의 별도 패키지**로 둡니다 (`io.github.search5.hg4j.lfs.server`).
   모듈은 쪼개지 않습니다([[core-package-split-plan]]의 방침).
3. 서버 능력(`lfs`, `lfs-serve`)은 **원본과 같은 조건에서만** 광고합니다. hg4j에는 확장 시스템이 없으므로 `hgrc`의
   `[extensions] lfs =`가 켜져 있거나 저장소 requirement에 `lfs`가 있을 때로 정합니다. hg4j 전용 토큰은 만들지 않습니다
   (capability를 7.2.4와 같게 맞춘 2026-11-03 결정과 같은 원칙).
4. `lfs.threshold`는 계속 지원하되 원본처럼 `(track) | size('>N')`로 합성합니다. `0`은 "비활성"입니다(이미 수정됨).
5. JSON은 새 의존성을 추가하지 않고 기존 구현(`HgLfsManager`의 `MapJsonParser`)을 확장합니다. 대안(Gson/Jackson)은
   배포 크기와 취약점 면적이 늘어나므로 보류하고, **미확인**: 요청/응답 스키마(`objects`, `actions`, `header`, `error`)만
   다루면 충분한지 Phase 1 첫 단계에서 확인합니다.

## 1. 작업 전 필수 절차

- `git status`, `llm-wiki/index.md`, `known-bugs-registry.md`를 먼저 읽습니다(규칙 10).
- **Phase마다 별도 커밋**입니다. 한 커밋에 여러 Phase를 섞지 않습니다.
- 모든 Phase는 **실제 hg와의 대조 테스트를 먼저 작성**(빨강)하고 구현(초록)합니다. 대조 도구는
  `WireServerParityInteropTest`, `HttpSessionParityInteropTest`, `LfsRealHgInteropTest`의 방식을 따릅니다.
- 각 Phase 종료 때 `./gradlew test`(일반 전체)와 해당 Phase의 interop 범위를 실행합니다. 실행하지 않은 범위는 보고에
  "실행하지 않음"으로 적습니다.
- 새로 찾은 버그는 `known-bugs-registry.md`에 등록하고, 완료 후 `modules/lfs.md`, `backlog/narrow-clone-and-lfs.md`,
  `log.md`, `index.md`를 갱신합니다.

## 2. JGit 인터페이스 대응표

| JGit | 역할 | hg4j 대응 (신규 또는 확장) | 현재 |
|---|---|---|---|
| `lfs.Lfs` | 저장소별 LFS 경로 (`getLfsRoot`, `getLfsTmpDir`, `getLfsObjDir`, `getMediaFile`, `createTmpFile`) | `HgLfs`: `getObjectsDir()`, `getUserCacheDir()`, `getMediaFile(oid)`, `createTmpFile()` | `HgLfsManager`가 일부 보유. 시그니처만 JGit에 맞춰 정리 |
| `lfs.LfsPointer` | 포인터 값 객체 (`getOid`, `getSize`, `encode(OutputStream)`, `parseLfsPointer(InputStream)`, `compareTo`) | `HgLfsPointer`에 `encode(OutputStream)`, `parse(InputStream)`, `equals/hashCode/compareTo` 추가 | `parse(String)`만 있음 |
| `lfs.LfsBlobLoader` | blob을 `ObjectLoader`로 노출 (`getSize`, `getCachedBytes`, `openStream`) | `HgLfsBlobLoader`: `getSize()`, `getBytes()`, `openStream()` (큰 파일은 스트림) | 없음 (`getCachedObject`가 전체를 메모리로 읽음) |
| `util.LfsFactory` | 이음새: `isAvailable`, `isEnabled(repo)`, `applyCleanFilter`, `applySmudgeFilter`, `getPrePushHook`, `getInstallCommand`, 정적 `getInstance/setInstance` | `HgLfsFactory`: `isEnabled(repo)`, `applyCleanFilter(repo, path, content)`, `applySmudgeFilter(repo, path, stored, flags)`, `getPrePushHook(repo, out)`, `getInstance/setInstance` | 없음 |
| `lfs.BuiltinLFS` | 기본 구현 등록 (`register()`) | `BuiltinHgLfs.register()`: 기본으로 등록 | 없음 |
| `lfs.CleanFilter` / `lfs.SmudgeFilter` / `LfsBlobFilter` | 내용 ↔ 포인터 변환, 다운로드 | `HgLfsCleanFilter`(내용 → blob 저장 + 포인터), `HgLfsSmudgeFilter`(포인터 → 내용, 없으면 내려받기, `prefetch(pointers)` 일괄) | `CommitCommand`/`UpdateCommand`에 인라인 |
| `lfs.LfsPrePushHook` | push 전 나가는 객체의 blob 업로드 | `HgLfsPrePush`: `extractPointers(repo, revs)`, `uploadBlobs(...)`. `PushCommand`, `BundleCommand`가 호출 | 없음 |
| `lfs.internal.LfsConnectionFactory`, `LfsConfig` | 서버 URL 결정, 인증 헤더, 연결 | `HgLfsConnections`: `lfs.url` > `paths` 유추(`.git/info/lfs`), 자격 증명, 사용자 에이전트, 재시도 | `HgLfsManager.resolveServerUrl`이 일부 |
| `lfs.Protocol` | JSON 모델 (`Request`, `Response`, `ObjectInfo`, `Action`, `Error`), 연산명, 미디어 타입 | `HgLfsProtocol`: 같은 모델, 미디어 타입 `application/vnd.git-lfs+json` | `MapJsonParser`로 부분 구현 |
| `lfs.errors.*` (`LfsException` 계열, 401/404/422/429/507 등) | 오류 분류 | `HgLfsException` 계열: 원본 `LfsRemoteError`, `LfsCorruptionError`와 HTTP 상태 대응 | `IOException`으로 뭉뚱그림 |
| `lfs.server.LargeFileRepository` | 서버 저장소 SPI (`getDownloadAction`, `getUploadAction`, `getVerifyAction`, `getSize`) | `HgLargeFileRepository` 인터페이스 | 없음 |
| `lfs.server.fs.FileLfsRepository`, `FileLfsServlet` | 파일 저장소와 GET/PUT 서블릿 | `HgFileLfsRepository`(`.hg/store/lfs/objects`), `HgLfsObjectServlet` | 없음 |
| `lfs.server.LfsProtocolServlet`, `TransferHandler` | `/objects/batch` POST 처리 | `HgLfsBatchServlet`. `HgHttpWireServer`가 경로를 위임 | 없음 |
| `lfs.InstallBuiltinLfsCommand` | git 설정에 필터 등록 | **대응 없음**. hg는 `[extensions] lfs =`와 requirement로 켜므로 설치 명령이 필요 없음. 대신 `HgLfsConfig`로 요구 사항 감지만 제공 | 해당 없음 |
| `lfs.server.s3.*` | S3 저장소 | **범위 밖** (필요가 생기면 `HgLargeFileRepository` 구현으로 추가) | 해당 없음 |

JGit 본체의 연결 지점(`DirCacheCheckout`, `DiffFormatter`, `ResolveMerger`, `PatchApplier`, `blame.Candidate`,
`TreeWalk`)은 모두 `LfsFactory`를 부릅니다. hg4j의 대응 지점은 3절 Phase 2의 표입니다.

## 3. Phase별 계획

### Phase 1: 이음새와 기본 값 객체 (기능 변화 없음, 토대)

목표: JGit식 이음새를 도입하고 기존 동작을 그대로 그 뒤로 옮깁니다. 이 Phase 끝에는 외부 동작이 바뀌지 않아야 합니다.

1. `HgLfsPointer`에 `encode`, `parse(InputStream)`, `equals/hashCode/compareTo`를 추가합니다. 포인터 필드는 이미
   알파벳순 부가 필드(`x-*`)를 보존합니다. 직렬화 순서(`version`을 먼저)는 `pointer.py`의 `serialize`를 따릅니다.
2. `HgLfs` 파사드와 `HgLfsBlobLoader`를 만듭니다. `HgLfsManager`는 유지하고 내부 구현을 위임합니다(호출처가 많음).
3. `HgLfsFactory`(추상 클래스, 정적 `getInstance/setInstance`)와 `BuiltinHgLfs`를 만들고, `CommitCommand`의 포인터 생성부와
   `UpdateCommand`의 `resolveContent` 호출을 팩터리 뒤로 옮깁니다.
4. `HgLfsException` 계열을 만들고 `HgLfsManager`의 `IOException` 던지기를 오류별로 분류합니다.
5. 미확인 해소: 요청/응답 JSON의 실제 스키마를 `hgext/lfs/blobstore.py`(`_batchrequest`, `_extractobjects`)로 확인하고
   `MapJsonParser`로 충분한지 판단합니다.

검증: `./gradlew test` 전체 통과, `LfsRealHgInteropTest` 10개 통과(변경 전과 같은 결과). 새 단위 테스트는 포인터
왕복(`encode` ↔ `parse`)과 `x-hg-*` 보존입니다.

### Phase 2: 읽기 경로 (가장 눈에 띄는 결함)

목표: 모든 읽기 명령이 LFS 파일의 **실제 내용**을 돌려줍니다. 지금은 포인터 텍스트가 나옵니다.

방식: `Revlog`에 LFS를 아는 읽기 메서드를 추가합니다 (예: `getFileContent(rev)`: `REVIDX_EXTSTORED`면
`HgLfsFactory.applySmudgeFilter`로 해석). 기존 `getRevisionContent`는 "저장된 바이트"로 남겨 두고, 27개 호출 지점을 하나씩
검토해 **파일 내용을 읽는 곳**만 새 메서드로 바꿉니다. 매니페스트와 changelog를 읽는 곳은 건드리지 않습니다.

| 명령 | 필요한 동작 | 원본 근거 |
|---|---|---|
| `Cat`, `Archive`, `Export`, `Grep` | 실제 내용 | filelog flag processor의 `readfromstore` |
| `Diff`, `Annotate` | 실제 내용 비교. 바이너리 판정은 포인터의 `x-is-binary` | `wrapper.filectxisbinary` |
| `Status`, `Update`의 변경 감지 | 작업 파일과 비교할 때 **sha256 oid로 비교**(전체 내용을 내려받지 않음) | `wrapper.filectxcmp` |
| `Merge`, `Graft`, `Rebase`, `Backout`, `Amend`, `Histedit`, `Shelve` | 3-way 병합 입력은 실제 내용, 결과는 다시 `lfs.track` 판단 | 커밋 경로와 같음 |
| `Verify` | blob 존재와 해시 확인, 없으면 보고 | `wrapper._verify_revision` (`revlog._verify_revision` 래핑) |
| `Clone`, `Pull` | blob은 **전송하지 않고** 체크아웃 때 지연 내려받기. 일괄 `prefetch(pointers)`로 요청 수를 줄임 | `scmutil.fileprefetchhooks`의 `_prefetchfiles` |

미확인: `Status`가 원본에서 크기와 oid로 판정하는 정확한 순서(`filectxcmp`, `filelogsize` 포함). 착수 전에
`hgext/lfs/wrapper.py` 252~276줄과 실제 hg 동작으로 확인합니다.

검증(빨강 먼저): 실제 hg가 만든 LFS 저장소를 읽는 대조 테스트를 명령마다 추가합니다. `cat`, `diff`, `annotate`, `archive`,
`export`, `grep`의 출력이 실제 hg와 **바이트 단위로 같아야** 합니다. 여기에 "blob이 로컬에 없고 원격에서 받아야 하는" 변형을
포함합니다(실제 `hg serve` + lfs 사용).

### Phase 3: 쓰기 경로의 추적 대상 지정 (`lfs.track`, `.hglfs`)

목표: 현재 방식의 설정으로 LFS 대상을 지정할 수 있게 합니다.

1. `MiniFileset`: 원본 `minifileset.py`의 부분집합을 구현합니다. 지원 대상은 `all()`, `none()`, `size("<식>")`, 패턴
   (`**.확장자`, `path:접두`), `and`/`or`/`not`과 `&`, `|`, `!`, 괄호입니다. 미지원 패턴은 원본처럼
   `unsupported file pattern`/`paths must be prefixed with "path:"` 오류로 거절합니다.
2. `size()` 인자: 범위(`a-b`), `<=`, `<`, `>=`, `>`, 정확한 값(단위 접미사는 자릿수에 따라 상한이 달라짐, `_sizetomax`).
   단위 해석은 `util.sizetoint`와 같게 합니다.
3. `.hglfs` 파서: 작업 디렉터리에 `.hglfs`가 있으면 `[track]` 항목을 `(패턴, 규칙)` 쌍으로 컴파일하고 **첫 매칭
   패턴의 규칙**을 씁니다. 매칭이 없으면 False, 파일이 비어 있으면 항상 False, 파싱 오류는
   `parse error in .hglfs: ...`로 중단합니다. `.hglfs`가 있으면 `lfs.track` 설정은 무시됩니다.
4. `HgLfsTracker`(경로, 크기 → boolean)를 만들고 `CommitCommand`의 `lfsThreshold` 분기를 대체합니다. `lfs.threshold`가 있으면
   `(track) | size('>N')`으로 합성합니다.
5. 커밋을 만드는 다른 경로가 같은 추적자를 쓰는지 확인합니다. `ImportCommand`는 커밋을 `CommitCommand`에 위임하므로(클래스
   주석 참고) 추적자를 자동으로 물려받습니다. `AmendCommand`, `GraftCommand`, `RebaseCommand`, `HisteditCommand`가 `CommitCommand`를
   거치는지, 직접 filelog에 쓰는지는 **미확인**입니다.

검증: 실제 hg로 같은 설정(`lfs.track = size(">100")`, `**.bin`, `path:assets`, `.hglfs`의 여러 규칙)에서 커밋하고
`hg debugdata`로 포인터 여부를 비교합니다. hg4j가 만든 커밋을 실제 hg의 `hg verify`와 `hg cat`이 받아들이는지도 봅니다.
`.hglfs` 우선순위, 첫 매칭 규칙, 오류 문구는 케이스별로 실제 hg 출력과 대조합니다.

### Phase 4: push와 번들의 blob 업로드

목표: `LfsPrePushHook`에 해당하는 동작으로 push 때 blob을 원격 LFS 서버에 올립니다.

1. `HgLfsPrePush.extractPointers(repo, revs)`: 나가는 리비전의 변경 파일 중 `REVIDX_EXTSTORED` filelog 항목에서 포인터를
   모읍니다 (원본 `extractpointers`, `pointerfromctx`, `pointersfromctx`).
2. `uploadBlobs`: 배치 API의 `upload`로 올립니다. 업로드 직전에 로컬 blob의 sha256을 검증하고, 손상되었으면
   `detected corrupt lfs object` 오류로 중단합니다 (원본 `_basictransfer`의 `localstore.verify`). 이미 서버에 있는 객체는
   건너뜁니다(`_deduplicate`).
3. `PushCommand`: changegroup을 보내기 **전에** 업로드합니다. 로컬 저장소에 `lfs` requirement가 있고 원격이 `lfs` 능력을
   광고하지 않으면 `required features are not supported in the destination: lfs`로 중단합니다 (`wrapper.push`). 원격 URL은 push 대상에서 유추합니다.
4. `BundleCommand`: `hg bundle`을 쓸 때도 같은 업로드를 합니다 (`writenewbundle` 래핑). 번들의 changegroup 버전은 LFS가
   있으면 `03` 이상을 쓰는 원본 규칙(`supportedoutgoingversions`에 `03` 추가)을 확인합니다. **미확인**: 이 규칙이 hg4j의 번들 생성기에도
   필요한지.
5. 로컬 저장소 간 push(원격이 `local()`)는 업로드를 건너뜁니다 (`_canskipupload`와 requirement 검사에 맡김).

검증: 실제 `hg serve`(lfs 확장, `experimental.lfs.serve` 기본값)를 원격으로 두고 ① hg4j가 LFS 커밋을 push, 실제 hg가 clone해서
`hg cat`으로 실제 내용 확인, ② 서버 쪽 `.hg/store/lfs/objects`에 blob이 존재하는지 확인, ③ 손상된 로컬 blob의 push가 같은
문구로 중단되는지. 반대 방향(실제 hg가 push한 것을 hg4j 서버가 받는 경우)은 Phase 5에서 합니다.

### Phase 5: LFS 서버 (`org.eclipse.jgit.lfs.server`에 해당)

목표: `HgHttpWireServer`가 원본 `hg serve`처럼 LFS Batch API와 basic transfer를 제공합니다.

원본 `wireprotolfsserver.py`의 규칙을 그대로 따릅니다 (hg 위키 `ext-lfs.md`의 서버 절 참고).

1. `HgLargeFileRepository` 인터페이스(JGit `LargeFileRepository`의 `getDownloadAction`, `getUploadAction`, `getVerifyAction`,
   `getSize`)와 `HgFileLfsRepository`(저장소의 `lfslocalblobstore`)를 만듭니다.
2. 배치: `.git/info/lfs/objects/batch`에 POST만 허용(405), 요청/응답 미디어 타입이
   `application/vnd.git-lfs+json`이 아니면 415/406, `transfers`에 `basic`이 있어야 하고 `operation`은 `upload`/`download`만
   허용합니다. 권한은 `pull`/`upload`를 구분합니다.
3. 전송: `.hg/lfs/objects/<oid>`에서 PUT은 `upload` 권한 후 길이와 해시를 검증해 저장(성공 200, 신규 201, 손상 422
   "corrupt blob"), GET은 내려줍니다. 경로 분할이 4가 아니면 404.
4. `experimental.lfs.serve`(기본 True)가 꺼져 있으면 처리하지 않습니다.
5. 능력 광고: `lfs`, LFS가 requirement면 `lfs-serve`를 추가합니다 (0.3절 결정 3의 조건). 기존 `capabilityTokens`의 정렬과
   `WireServerParityInteropTest#sshCapabilitiesMatchTheOriginal` 같은 토큰 대조 테스트를 확장해, 확장 설정을 켠 서버의 토큰도
   실제 hg(`--config extensions.lfs=`)와 **정확히 같게** 합니다.
6. HTTP 외 전송(SSH)에는 LFS 엔드포인트가 없습니다(원본도 HTTP만). 요구 사항 검사만 공유합니다.

검증: 실제 hg 클라이언트(lfs 확장)로 hg4j 서버에 push/clone/pull을 하고, `HttpSessionParityInteropTest` 방식으로 배치 요청과
응답(상태, 미디어 타입, 본문)을 실제 `hg serve`와 재생 비교합니다. 오류 경로(405, 406, 415, 404, 422)도 재생 비교에 포함합니다.

### Phase 6: 전송 견고성

원본 `blobstore.py`의 동작을 옮깁니다.

| 항목 | 원본 | 설정 |
|---|---|---|
| 재시도 | 소켓 오류 시 횟수만큼 재시도 | `lfs.retry` (기본 5) |
| 병렬 전송 | 워커로 병렬화 | `experimental.lfs.worker-enable` (기본 True) |
| 인증 | 401 때 자격 증명 처리 (`lfsauthhandler`) | `[auth]`/URL의 사용자 정보 |
| 사용자 에이전트 | 설정 가능 | `experimental.lfs.user-agent` |
| 진행률과 요약 | 바이트 단위 진행, 끝에 `lfs: uploaded/downloaded N files (SIZE)` | |
| 원격 미설정 | `lfs.url` 안내 문구 (`_promptremote`) | |

검증: 느리거나 끊기는 서버(테스트용 소켓 서버, 실제 대조가 어려운 항목은 목 대신 **실제 지연·중단을 만드는 소켓**)로
재시도 횟수와 최종 오류 문구를 확인합니다. 오류 문구는 실제 hg의 출력과 같게 합니다.

### Phase 7: 부가 기능

`lfs()` 파일셋(`FilesCommand`/`LocateCommand`), `lfs_files` 템플릿, `debuglfsupload` 해당 명령, `verify` 연동의 마무리,
`convert` 싱크는 **범위 밖**입니다(hg4j에 convert가 있으면 별도 판단). 요구 사항 자동 추가(`pretxnchangegroup`
훅의 `checkrequireslfs`)는 서버가 받는 push에서 `lfs` requirement를 갖추는 동작으로 Phase 5에서 함께 다룹니다.

## 4. 범위 밖과 미확인 목록

범위 밖:
- JGit의 `InstallBuiltinLfsCommand`(git 필터 설치), S3 저장소, git-lfs의 `lock` API, `rhg`(Rust 클라이언트)의 lfs 처리
  (원본 `requirements.rs`의 `SUPPORTED`에 `lfs`가 없어 rhg는 lfs 저장소를 거절함).
- `.gitattributes` 기반 판단. hg에는 해당 개념이 없습니다.

착수 전에 직접 확인할 것(미확인):
1. 배치 JSON 스키마가 `MapJsonParser` 확장으로 충분한지 (Phase 1).
2. `Status`의 LFS 비교 순서 (Phase 2).
3. LFS가 있는 번들의 changegroup 버전 규칙이 hg4j에도 필요한지 (Phase 4).
4. 사용자 캐시 기본 경로의 OS별 차이는 [[narrow-clone-and-lfs]] 42번에서 구현했으나, 서버 측 업로드가 사용자 캐시를 채우는지는
   원본 동작을 확인해야 함 (Phase 4).
5. `size()` 단위 해석(`util.sizetoint`)의 지원 단위 목록과 대소문자 규칙 (Phase 3).
6. `AmendCommand`, `GraftCommand`, `RebaseCommand`, `HisteditCommand`가 LFS 추적자를 거치는지 (Phase 3).

## 5. 완료 기준

- 각 Phase의 대조 테스트가 **빨강에서 초록으로** 바뀐 증거(수정 전 코드로 되돌려 실패를 확인하는 방식)를 남깁니다.
- Phase 2 이후: 실제 hg가 만든 LFS 저장소에서 `cat/diff/annotate/archive/export/grep/status`가 실제 hg와 같은 출력을 냅니다.
- Phase 4 이후: hg4j가 push한 LFS 저장소를 실제 hg가 clone해 내용을 읽습니다.
- Phase 5 이후: 실제 hg 클라이언트가 hg4j 서버를 LFS 포함으로 push/clone합니다.
- 일반 `./gradlew test` 전체 통과. 실행하지 않은 interop 범위는 보고에 명시합니다.
- 알려진 차이는 숨기지 않고 `backlog/narrow-clone-and-lfs.md`에 "의도적 차이"로 기록합니다.

## 6. 실행 결과 요약 (2026-11-04)
| Phase | 커밋 | 결과 |
|---|---|---|
| 1 이음새와 값 객체 | eb70fd3 | `HgLfsFactory`/`BuiltinHgLfs`, 예외 계열, 포인터 메서드 |
| 2 읽기 경로 | 0d89129, 4b82710 | 읽기 명령이 실제 내용을 읽음, `verify`, 일괄 내려받기, LFS 저장소 clone/pull 가능 |
| 3 `lfs.track`, `.hglfs` | f92d0a5 | `MiniFileset`, `HgLfsTracker`, Histedit 재기록 |
| 4 push·번들 업로드 | 1124f38 | `HgLfsPrePush`, 번들2 push 요청과 응답 해석 |
| 5 LFS 서버 | 8944284 | `HgLfsProtocolHandler`, `lfs` 능력, 수신 시 `lfs` requirement |
| 6 전송 견고성 | e2dd400 | `HgLfsClient`(문구·힌트, UA, 인증, 병렬, 재시도 실측) |
| 7 부가 기능 | 이 문서와 같은 커밋 | `HgLfsFiles`, `LfsUploadCommand`, `verify` 마무리 |
계획과 달리 바뀐 점: Phase 7의 `lfs()` 파일셋과 `lfs_files` 템플릿은 구문이 아니라 API(`HgLfsFiles`)로 제공했다(엔진 부재). 계획서가 "미확인"으로 둔 항목은 모두 실측으로 해소했다.
