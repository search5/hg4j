---
updated: 2026-11-03
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
  원격 다운로드도 지원.
- **`HgLfsPointer`**: LFS 포인터 파일 파싱 값 객체 (`version`/`oid`/`size` 텍스트 포맷,
  revlog 안에는 이 포인터 텍스트만 저장되고 실제 대용량 파일은 별도 저장).

- **`HgLfsFactory` / `BuiltinHgLfs`** (2026-11-03): 명령이 LFS를 아는 유일한 이음새(JGit `LfsFactory`/`BuiltinLFS` 대응).
  `applyCleanFilter`(내용 → 저장 형태), `applySmudgeFilter`/`readContent`(저장 형태 → 내용), `prefetch`(일괄 내려받기).
  파일 내용을 읽는 명령은 `Revlog#getRevisionContent`(저장 바이트) 대신 `readContent`를 쓴다. changegroup 델타 기준처럼
  저장 바이트가 맞는 곳(Push, Bundle, Gc)은 일부러 그대로 둔다.
- **`MiniFileset`** : `lfs.track`과 `.hglfs`의 파일셋 부분집합(`**.ext`, `path:`, `all()`, `none()`, `size()`, 연산자).
  원본 `minifileset.py`와 `filesetlang.elements`의 결합 강도를 따른다.
- **`HgLfsTracker`**: `.hglfs`(첫 일치 규칙) > `lfs.track` + 폐기 예정 `lfs.threshold`. `BuiltinHgLfs#applyCleanFilter`와
  `HisteditCommand`가 사용한다.
- **`errors/HgLfsException`** 계열: 401/403, 404, 손상 객체.

- **`HgLfsClient`** (패키지 비공개, 2026-11-04): Git LFS 서버와의 통신(배치 요청, 기본 전송, 재시도, 병렬 전송, `User-Agent`, URL의 Basic 자격 증명).
  오류 문구와 힌트는 원본 `_gitlfsremote`와 글자까지 같다(실제 hg 대조 테스트로 확인).
- **`HgLfsPrePush`**: push와 bundle이 나가는 리비전의 blob을 올리는 곳(JGit `LfsPrePushHook` 대응). 저장소 선택은 `blobstore.remote()` 규칙(`http(s)`,
  `file`, `null`, 주소 없음, 알 수 없는 스킴).
- **`HgLfsFiles`**: 변경셋의 LFS 파일 목록. 원본 `lfs()` 파일셋(`inManifest`)과 `lfs_files` 템플릿(`changedBy`)이 계산하는 값을 API로 제공한다
  (hg4j에는 파일셋·템플릿 언어가 없다).
- **`HgLfsConfig`**: 확장 활성(`[extensions] lfs`), `experimental.lfs.serve`, `lfs` requirement 판정과 추가.
- **`server/`** (JGit `org.eclipse.jgit.lfs.server` 대응): `HgLargeFileRepository`/`HgFileLfsRepository`(저장소 SPI와 `.hg/store/lfs/objects` 구현),
  `HgLfsProtocolHandler`(Batch API와 Basic 전송, `HgHttpWireServer`가 호출).
- **`api/LfsUploadCommand`**: `hg debuglfsupload -r REV`.

## JGit 인터페이스와의 대응
계획서([[implementation-plan-lfs]])의 2절 표를 구현했다. 대응이 없는 것: `InstallBuiltinLfsCommand`(hg는 `[extensions] lfs`와 requirement로 켠다), S3 저장소.

## 범위 밖 (의도적)
- `lfs()` 파일셋과 `lfs_files` 템플릿 구문 자체: hg4j에 파일셋·템플릿 엔진이 없다(값은 `HgLfsFiles`로 제공).
- `convert` 싱크, `rhg`(Rust 클라이언트, 원본도 lfs 저장소를 거절), git-lfs `lock` API.
- 진행률 표시줄과 `lfs: uploading ...` 콘솔 출력(라이브러리라 UI가 없다), `[auth]` 설정(URL의 자격 증명만).
- HTTP 상태 줄의 사유 문구(`java.net.http`가 노출하지 않음, `HttpServlet`으로 지정 불가).
- hg4j 서버에는 `web.allow-push` 같은 권한 구분이 없어 LFS 엔드포인트도 구분하지 않는다.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/lfs/`
