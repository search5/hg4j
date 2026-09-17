---
updated: 2026-09-17
status: current
---

# 모듈: lfs (`io.github.search5.hg4j.lfs`)

LFS(Large File Storage) 지원 패키지. [[core-package-split-plan]] Phase 9에서 분리됨 —
JGit이 정확히 `org.eclipse.jgit.lfs` 패키지를 갖고 있어 이름까지 일치.

## 클래스
- **`HgLfsManager`**: LFS 오브젝트의 로컬 캐싱/해석과 원격 LFS 서버 연동을 담당.
  `.hg/store/lfs/objects/` 아래를 **실제 hg 방식의 단일 2글자 샤딩**(`objects/XX/YYYY...`)
  으로 해석한다 — Git-LFS의 2단계 중첩 샤딩(`XX/YY/ZZZZ...`)과는 다르며, 과거 hg4j가
  Git 방식으로 구현했던 것을 실제 hg와 스토어를 공유할 수 있도록 수정한 이력이 있다
  (`getLocalPath()` 주석 참고). `fetchObject()`로 LFS 배치 API(`/objects/batch`)를 통한
  원격 다운로드도 지원. `locate()`/`exists()`/`verify()`는 서버 사이드(`lfs.server`
  패키지)가 oid 하나만 갖고 로컬 블롭을 찾아 무결성(SHA-256)을 재확인할 때 쓴다.
- **`HgLfsPointer`**: LFS 포인터 파일 파싱 값 객체 (`version`/`oid`/`size` 텍스트 포맷,
  revlog 안에는 이 포인터 텍스트만 저장되고 실제 대용량 파일은 별도 저장).

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/lfs/`

## 서버 사이드 (`io.github.search5.hg4j.lfs.server`)

다른 클라이언트에게 LFS blob을 서빙하는 HTTP 엔드포인트. JGit이 `org.eclipse.jgit.lfs.server`를
core와 분리한 것과 동일한 발상으로 서브패키지 분리. 상세 배경/검증 과정은
[[lfs-server-side-batch-api-plan]] 참고.

- **`HgLfsServer`**: `jakarta.servlet.http.HttpServlet`(`HgHttpWireServer`와 동일 컨벤션).
  Git LFS Batch API(`POST .../.git/info/lfs/objects/batch`)와 Basic Transfer Adapter
  (`GET`/`PUT .../.hg/lfs/objects/{oid}`)를 구현. `HgHttpWireServer`와 같은 저장소 URL
  공간에 나란히 마운트한다 — 서블릿 경로 매핑 우선순위(가장 긴 프리픽스 우선)로 두 접두사가
  `HgHttpWireServer`의 `/*`보다 먼저 매칭된다. `registerPullPermissionHook`/
  `registerUploadPermissionHook`으로 `HgHook` 기반 인가 콜백을 등록(real hg의
  `checkperm('pull'|'upload')`에 대응).
- **`HgHttpWireServer#enableLfsCapability()`** / **`HgSshWireServer#enableLfsCapability()`**:
  `HgLfsServer`를 같이 마운트했을 때만 호출. wire capability 문자열에 `lfs`(+ `.hg/requires`에
  `lfs`가 있으면 `lfs-serve`)를 추가한다 — 없으면 real hg 클라이언트가 LFS 파일이 포함된
  push를 "required features are not supported in the destination: lfs"로 아예 거부한다(real
  hg의 `exchange.push()`가 `remote.capable('lfs')`를 확인하는 지점, `hgext/lfs/wrapper.py`).
  SSH 쪽은 이 capability를 `capabilities` 명령이 아니라 `hello` 핸드셰이크 응답으로 전달하므로
  (`mercurial/sshpeer.py`의 `_performhandshake`), `HgSshWireServer`는 두 명령 모두 패치한다.

### SSH 원격에서의 LFS: 실측 확인된 실제 지원 범위
`HgLfsServerSshRealHgInteropTest`(임베디드 Apache MINA SSHD + real hg CLI, 2개 시나리오,
GREEN)로 확인한 real hg의 실제 동작:
- **SSH만으로는 LFS blob이 절대 못 간다.** `hgext/lfs/blobstore.py`의 `remote()`는 원격
  URL 스킴이 `http`/`https`일 때만 `.git/info/lfs`를 자동 유추한다 — `ssh://`는 이 분기를
  건너뛰고 `_storemap[None]`(`_promptremote`)로 떨어져, 실제 업로드 시도 시점에
  `abort: lfs.url needs to be configured`로 실패한다(SSH→HTTPS 변환 자체가 real hg에
  미구현 — `# TODO: consider the ssh -> https transformation that git applies`).
- **명시적 `[lfs] url`을 HTTP(S) 엔드포인트로 지정하면 정상 동작한다** — changegroup은
  SSH(`HgSshWireServer`)로, blob은 그 URL이 가리키는 `HgLfsServer`(HTTP)로 각각 오가며,
  push/clone/verify 모두 성공한다. 이게 real hg가 지원하는 유일한 SSH+LFS 토폴로지이고,
  `HgSshWireServer` 자체에는 capability 광고 외에 LFS 전용 코드를 추가할 필요가 없다.

### 이 작업으로 드러난 changegroup 수신 경로의 기존 버그 2건
서버가 아니라 `Revlog.appendChangeGroupEntry`(changegroup 수신 공통 경로 — push 수신과 pull
양쪽에서 재사용) 자체에 있던, 이번 LFS 서버 작업 전까지는 드러나지 않았던 결함:
1. **노드해시 검증**: EXTSTORED(LFS) 리비전에 대해 저장된 포인터 텍스트로 해시를 재검증하고
   있었다. real hg는 LFS 리비전의 노드해시를 애초에 "포인터 텍스트"가 아니라 "실제 파일
   바이트"로 계산하므로(`CommitCommand`의 `lfsHashBasis`가 이미 이렇게 하고 있음), 이 제네릭
   경로는 검증 자체를 건너뛰어야 한다(censored 콘텐츠와 동일한 이유).
2. **플래그 소실**: v1/v2 인덱스에 리비전을 쓸 때 `entry.flags`(wire로 전달된 REVIDX_EXTSTORED
   등)를 무시하고 콘텐츠 자체를 검사해 얻은 값만 저장하고 있었다 — 받은 LFS 리비전이 로컬
   인덱스에는 "플래그 없음"으로 남아, 이후 이 저장소가 그 리비전을 다시 내보낼 때 포인터
   텍스트를 일반 파일 내용처럼 취급하게 되는 결함.

### 이 작업으로 드러난 읽기 계열 커맨드의 LFS 미인지 버그
`UpdateCommand`(체크아웃)/`AnnotateCommand`만 `HgLfsManager.resolveContent()`로 포인터를
디레퍼런스하고 있었고, **`CatCommand`/`ArchiveCommand`는 전혀 LFS를 모른 채 포인터 텍스트를
그대로 반환**하고 있었다 -- `hg4jCatsRealHgLfsCommitWithFullContent`/
`hg4jArchivesRealHgLfsCommitWithFullContent`(`LfsRealHgInteropTest`)로 RED 확인 후, 두
커맨드 모두 동일한 `HgLfsManager.resolveContent(repository, content, filelog.isExtStored(rev),
path)` 패턴을 추가해 GREEN으로 고쳤다.

**정정(이전 기록이 잘못됐었음)**: 당초 `DiffCommand`/`GrepCommand`는 real hg의
`filectxcmp`/`filectxisbinary` fast path(포인터의 oid/`x-is-binary` 메타데이터만으로 비교·
바이너리 판정을 끝냄)와 동일한 이유로 손대지 않아도 된다고 기록했었는데, real hg 소스
(`hgext/lfs/wrapper.py`)를 다시 확인해보니 틀린 판단이었다 -- `filectxcmp`/`filectxisbinary`는
"다른지"/"바이너리인지"만 빠르게 답하는 최적화용 fast path일 뿐, **실제 `hg diff`/`hg grep`이
보여주는 콘텐츠 자체는 `fctx.data()`를 거쳐(revlog 플래그 프로세서의 `readfromstore`) 항상
실제 파일 바이트로 디레퍼런스된다.** 즉 real hg는 LFS-tracked 텍스트 파일에 대해 진짜
줄 단위 diff/grep 결과를 보여주며, 포인터 텍스트(`oid sha256:...`, `size ...`)를 노출하지
않는다. hg4j의 `DiffCommand`/`GrepCommand`가 포인터 텍스트를 그대로 diff/grep하던 것은
진짜 버그였다 -- `hg4jDiffsRealHgLfsCommitsWithFullContent`/`hg4jGrepsRealHgLfsCommitWithFullContent`
(`LfsRealHgInteropTest`)로 RED 확인 후 두 커맨드 모두 `CatCommand`/`ArchiveCommand`와 동일한
`HgLfsManager.resolveContent(...)` 패턴을 추가해 GREEN으로 고쳤다. `ExportCommand`는
내부적으로 `DiffCommand`(`new DiffCommand(repository)`, `ExportCommand.java` 107행)를 그대로
재사용하므로 `DiffCommand` 수정만으로 함께 고쳐졌다(별도 수정 불필요, 코드 리딩으로 확인).
real hg의 `filectxcmp`/`filectxisbinary` fast-path 최적화(전체 blob을 안 당겨오고 메타데이터만
보는 것) 자체는 hg4j에 재현하지 않았다 -- 정확성에는 영향이 없는 순수 성능 최적화이고, 과설계를
피하기 위해 `resolveContent`로 항상 실제 바이트를 디레퍼런스하는 단순한 구현을 택했다.
