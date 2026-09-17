---
updated: 2026-09-17
status: planned (미착수)
---

# 계획: LFS 서버 사이드 HTTP Batch API 실행 계획

> [[mercurial-spec-compliance-requirement]]의 LFS(Large File Storage) 행이 "✅ 완료(세부
> 옵션 3가지 포함, 백로그 42)"로 기재돼 있으나, 이는 **로컬 커밋/체크아웃 파이프라인과
> 클라이언트가 원격에서 fetch하는 경로**만을 가리킨다 — hg4j가 **서버 역할로 다른
> 클라이언트에게 LFS blob을 서빙하는 HTTP 엔드포인트 자체가 없다**는 사실이 그 행에
> 반영돼 있지 않았다(아래 "현재 구현 상태" 표의 실측 근거 참고). 이 문서는 그 격차를
> 메우는 계획이다. **아직 착수하지 않았다.**

## 목표

real `hg`(lfs 확장 활성화)로 hg4j가 서빙하는 저장소에 큰 파일을 커밋해 push하고, 별도
클라이언트가 그 저장소를 clone/pull했을 때 큰 파일의 실제 바이트를 정확히 받는 것 —
즉 hg4j를 **LFS 서버**로도 완전히 동작하게 만드는 것. (클라이언트 역할은 이미
[[narrow-clone-and-lfs|backlog 31/42]]에서 완료됨 — 이 문서는 그 나머지 절반이다.)

## 공식 근거

- `.native/hg-rust-7.2.4/build-src/mercurial-7.2.4/hgext/lfs/wireprotolfsserver.py` (369줄,
  이미 이 저장소에 vendored돼 있음 — real hg의 서버 사이드 레퍼런스 구현 원문).
- Git LFS Batch API 스펙(real hg가 그대로 재사용): 위 파일의 `_processbatchrequest()`
  docstring이 직접 링크 — `https://github.com/git-lfs/git-lfs/blob/master/docs/api/batch.md`.
- Git LFS Basic Transfer 스펙: `_processbasictransfer()` docstring 링크 —
  `https://github.com/git-lfs/git-lfs/blob/master/docs/api/basic-transfers.md`.

## 현재 구현 상태 (2026-09-17 코드 조사로 확인)

| 동작 | 상태 | 근거 |
|---|---|---|
| 로컬 커밋 시 threshold 초과 파일을 LFS 포인터로 치환(`REVIDX_EXTSTORED`) | ✅ 구현됨 | `CommitCommand.java`에 `HgLfsManager`/`HgLfsPointer` 참조 존재 |
| 체크아웃 시 포인터→실제 바이트 복원 | ✅ 구현됨 | `UpdateCommand.java`에 동일 참조 존재 |
| annotate 시 LFS 콘텐츠 인지 | ✅ 구현됨 | `AnnotateCommand.java`에 동일 참조 존재 |
| 로컬 blob store(`.hg/store/lfs/objects/`, 1단계 샤딩) + 유저 캐시 | ✅ 구현됨 | `HgLfsManager.getLocalPath()`/`getUserCacheDir()` |
| 클라이언트가 **원격** LFS 서버로부터 blob을 가져오는 경로 | ✅ 구현됨 | `HgLfsManager.fetchObject()`/`resolveServerUrl()` |
| **서버가 다른 클라이언트에게 LFS blob을 서빙하는 HTTP 엔드포인트**(Batch API `POST .../.git/info/lfs/objects/batch` + Basic Transfer `GET/PUT .../.hg/lfs/objects/{oid}`) | ❌ 미구현 | `grep -rl "HgLfsManager\|HgLfsPointer" --include="*.java" src/`로 확인한 참조처 6개 파일 중 `transport/` 패키지는 0개. `javap`으로 확인한 `HgHttpWireServer`/`HgSshWireServer`/`Wire1Commands`/`Wire2Commands`의 바이트코드에도 "lfs" 문자열 참조 0건(대소문자 무관 검색) |
| `verify(oid)`(업로드된 blob의 해시 재검증) | ❌ 미구현 | `HgLfsManager`의 public API(`javap` 확인)에 `verify` 메서드 없음 — real hg의 `blobstore.local.verify()`(위 vendored 파일이 호출)에 대응하는 게 없다 |

**결론**: "LFS 완료"는 클라이언트/로컬 파이프라인 기준으로는 맞지만, **서버로서는 아직
아무 것도 못 한다** — hg4j가 서빙하는 저장소에 real hg가 LFS 파일을 push하면 포인터
텍스트 자체는 정상적으로 changegroup에 실려가지만(일반 파일 내용과 동일하게 취급되므로
기존 wire protocol이 그대로 처리), **큰 파일의 실제 바이트를 주고받는 별도 HTTP 채널이
아예 없어 그 부분만 실패한다.**

## 프로토콜 요약 (vendored `wireprotolfsserver.py` 실측)

1. **Batch API** — `POST {baseurl}{apppath}/.git/info/lfs/objects/batch`
   - 요청/응답 모두 `Content-Type: application/vnd.git-lfs+json`, `Accept` 헤더도 동일 값 검사(불일치 시 415/406).
   - 요청 바디: `{"operation": "upload"|"download", "objects": [{"oid": "...", "size": N}], "transfers": ["basic"]}`(`transfers` 생략 시 `basic` 가정, `basic` 아닌 값 요청 시 400).
   - 각 object마다 로컬 store에서 `store.verify(oid)`(존재+해시 일치)를 확인:
     - download인데 없으면 `error: {code: 404}`, 있는데 해시 불일치면 `error: {code: 422}`.
     - upload인데 이미 존재+검증 통과면 `actions` 없이 그대로 반환(재업로드 스킵 신호).
   - 필요한 경우만 `actions.{upload|download}.href`를 채워 반환:
     `{baseurl}{apppath}/.hg/lfs/objects/{oid}`(같은 저장소의 base URL 기준 상대 경로 —
     **별도 전역 엔드포인트가 아니라 저장소 URL 하위에 중첩**), `expires_at`(+10분),
     `header`(`Accept: application/vnd.git-lfs` + 원 요청의 `Authorization` 헤더를 그대로 반사).
2. **Basic Transfer** — `GET|PUT {baseurl}{apppath}/.hg/lfs/objects/{oid}`
   - PUT: `checkperm('upload')` 먼저 확인 → `localstore.download(oid, body, contentLength)`로
     저장(신규면 201, 기존 덮어쓰기면 200) → 손상 시 422.
   - GET: `checkperm('pull')` 먼저 확인 → `localstore.read(oid)`를 `application/octet-stream`으로 반환, 손상 시 422.

## 단계별 계획

| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | RED부터 시작: `HgLfsServerRealHgInteropTest` 작성 — real hg(`--config extensions.lfs=`, 낮은 `[lfs] threshold`)로 큰 파일 커밋 후 hg4j가 서빙하는 빈 저장소로 push, 별도 real hg 클론이 그 파일을 정확한 바이트로 받는지 + `hg verify` 통과 확인(아직 서버가 없으니 이 시점엔 반드시 RED) | 실패하는 테스트 1개 |
| 2 | `HgLfsManager`에 `verify(String oid): boolean` 추가(로컬에 존재 + `getCachedObject()` 내용의 SHA256이 oid와 일치하는지) | `HgLfsManager` 수정 + 단위 테스트 |
| 3 | `io.github.search5.hg4j.lfs.server.HgLfsServer` 신설(`jakarta.servlet.http.HttpServlet` 상속 — 백로그 46번에서 `HgHttpWireServer`를 이 방식으로 통일한 것과 동일 컨벤션). 생성자는 `HgHttpWireServer(HgRepository)`와 동일하게 `HgRepository` 하나를 받고, 인가는 `HgHttpWireServer`의 `registerXxxHook` 패턴처럼 `pull`/`upload` 권한 콜백을 등록받는 방식으로 위임(구체 인터페이스는 구현 중 `HgHook` 재사용 가능 여부 확인 후 결정 — 억지로 기존 훅 타입에 끼워 맞추지 말 것) | `HgLfsServer` 신설 |
| 4 | Batch API 핸들러 구현: 위 "프로토콜 요약" 그대로 이식(Content-Type/Accept 검사, upload/download 분기, `transfers` 검사, per-object 존재/검증 확인, href 조립) | `HgLfsServer#service()` 또는 별도 dispatch 메서드 |
| 5 | Basic Transfer 핸들러 구현: `GET`/`PUT` 분기, `HgLfsManager.getLocalPath()`/`cacheObject()`/`getCachedObject()` 재사용, 상태 코드(200/201/404/422) 정확히 매칭 | 동일 클래스에 추가 |
| 6 | 1번 테스트가 GREEN이 될 때까지 반복(TDD) + 추가 시나리오: 이미 존재하는 blob 재업로드 스킵, 존재하지 않는 oid download 404, 손상된 로컬 blob 422 | `HgLfsServerRealHgInteropTest` 확장(최소 4개 시나리오) |
| 7 | HTTP뿐 아니라 SSH 경로도 확인 — real hg의 LFS는 HTTP LFS 서버를 **별도로** 쓰는 게 기본이므로(SSH remote라도 `[lfs] url`이 없으면 원격의 HTTP(S) 주소를 유추), SSH로 push/pull한 저장소에서도 같은 `HgLfsServer`가 같은 저장소 URL에서 서빙되면 되는지 확인만 하고, SSH 프로토콜 자체에 LFS를 얹을 필요는 없음(real hg도 안 함) — 이 가정을 반드시 real hg CLI로 실측 확인 후 문서화 | 확인 결과를 이 문서에 반영(가정이 틀리면 계획 수정) |
| 8 | 문서 갱신: 이 파일의 frontmatter `status`를 `completed`로, [[mercurial-spec-compliance-requirement]]의 LFS 행을 정확한 완료 근거로 갱신, `modules/lfs.md`에 서버 섹션 추가, `log.md`에 한 줄 기록 | 문서 3곳 갱신 |

## 코드 영향 범위 (현재 구조 기준)

- 신규: `io.github.search5.hg4j.lfs.server.HgLfsServer`(패키지 신설 — JGit이 LFS 서버를
  `org.eclipse.jgit.lfs.server`로 core와 분리한 것과 동일한 발상, `jgit-parity-requirement`
  원칙에 부합)
- 수정: `io.github.search5.hg4j.lfs.HgLfsManager`(`verify()` 추가)
- 신규 테스트: `src/test/java/io/github/search5/hg4j/lfs/server/HgLfsServerRealHgInteropTest.java`
- 참고만 하고 수정 없음: `HgHttpWireServer`/`HgSshWireServer`/`Wire1Commands`/`Wire2Commands`
  (LFS 포인터 텍스트는 일반 파일 내용과 동일하게 처리되므로 wire protocol 자체는 변경 불필요
  — 이 가정 자체도 1번 테스트의 real hg push 단계에서 changegroup이 정상 적용되는지로
  실측 확인됨)

## 이 계획에서 의도적으로 다루지 않는 것 (정직하게 기록)

- **hg4j 자체의 LFS 서버 통합 지점을 yona 등 소비자가 어떻게 마운트할지**는 이 문서의
  범위 밖 — hg4j는 라이브러리 레벨의 서버 컴포넌트만 제공하고, 실제 프로젝트별 URL
  라우팅/인가 정책 연결은 소비자(yona) 쪽 문서에서 별도로 다룬다.
- `lfs.track`(fileset 표현식 기반 LFS 추적)은 [[narrow-clone-and-lfs|백로그 42]]에서
  이미 범위 밖으로 명시됐고 이 문서도 그대로 따른다 — 서버 사이드 작업과 무관.

## 관련 페이지

- [[mercurial-spec-compliance-requirement]] — 이 계획의 상위 근거(LFS 행 정정 필요)
- [[narrow-clone-and-lfs]] — 로컬 파이프라인(백로그 31/42) 완료 근거, 이 계획의 전제
- [[jgit-parity-requirement]] — `lfs.server` 서브패키지 분리 근거
