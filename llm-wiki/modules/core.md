---
updated: 2026-08-31
status: current
---

# 모듈: core (`com.github.search5.hg4j.core`)

Plumbing(저수준) 계층. `.hg/` 저장소를 직접 읽고 쓰는 모든 로직이 여기 모인다.
API 계층(`api/`)의 모든 `XxxCommand`는 결국 이 패키지의 클래스들을 조합해서 동작한다.

## 저장소 진입점
- **`Repository`** (인터페이스) — `getDirectory`, `getHgDir`, `getStoreDir`, `getDirstate`,
  `writeDirstate`, `getBranch`/`setBranch`, `lockWorkingCopy`/`lockStore`, `open`/`close`.
- **`HgRepository`** — 유일한 구현체. 필드로 `directory`(작업 디렉터리), `hgDir`(`.hg/`),
  `storeDir`(`.hg/store/`), `cachedDirstate`, `revlogCache`, `ignorePatterns`를 들고 있다.
  - dirstate v1/v2 여부는 `defaultDirstateV2` 플래그로 제어.
  - `useZstdCompression`: revlog 압축 포맷 선택 (자세한 내용은 [[revlog]] 참고).
  - `checkAndPerformAutoRollback()`: 저널(`journal`) 파일이 남아있으면 이전 작업이 비정상
    종료된 것으로 보고 자동 롤백을 수행한다 → [[journaling-and-crash-recovery]] (필요시 작성).
  - ignore 패턴은 glob → regex 변환(`globToRegex`, `expandBraces`)으로 처리.

## Dirstate (작업 디렉터리 상태 추적)
- **`Dirstate`**: parent1/parent2(부모 리비전), entries(파일별 상태), copyMap(복사/이름변경 추적).
  v1/v2 포맷을 `isV2` 플래그로 구분해 같은 클래스에서 처리.
- **`DirstateV2Parser` / `DirstateV2Serializer` / `DirstateV2Node`**: v2는 44바이트 고정 크기
  바이너리 노드 구조를 Java NIO 메모리 매핑으로 처리하는 고성능 포맷.
  → 자세한 내용은 [[dirstate]] concept 페이지.

## Revlog (히스토리 저장 엔진)
- **`Revlog`**: `.i`(인덱스)/`.d`(데이터) 파일 파서. inline 여부, zstd 사용 여부에 따라
  분기. 델타 생성(`createDelta`, `createSimpleDelta`)과 압축 해제(`decompressHunk`)를 담당.
- **`RevlogIndex`**: 인덱스 레코드(`IndexRecord`) 관리, generaldelta 지원.
- **`DeltaEngine` / `DeltaCodec`**: Myers-diff 기반 멀티훈크 델타 생성/적용.
  → 과거 "Myers Diff 백트래킹 대각선 불일치(BUG-04)" 버그가 여기서 발생했었다
  (git log 참고, [[revlog]] 페이지의 알려진 이슈 섹션 참고).

## 번들/체인지그룹 (네트워크 동기화 데이터 포맷)
- **`Bundle2Parser`**: bundle2 컨테이너 포맷에서 changegroup part를 추출
  (`extractChangegroup`, `extractChangegroupDetailed`).
- **`ChangegroupParser`**: changegroup 내부의 리비전 델타 스트림을 파싱.
  → [[bundle2-changegroup]] 참고.

## Revset 엔진
- **`HgRevsetEngine`**: Mercurial revset 미니 언어의 자체 구현. `evaluateDraft`,
  `evaluateHeads`, `evaluateAncestors`, `evaluateDescendants`, `evaluateBranch`,
  `evaluateBookmark` 등 19개 함수 지원(README 기준). → [[revset]] 참고.

## 그 외 핵심 클래스
| 클래스 | 역할 |
|---|---|
| `HgLock` / `HgLockException` | `.hg/wlock`, `.hg/store/lock` OS 레벨 파일 락 |
| `SafeFileIO` | 원자적 파일 쓰기, JVM `OverlappingFileLock` 대응 하이브리드 락킹 |
| `Merge3` | 3-way 라인 단위 머지 엔진 (LCA 기반) |
| `PhaseRoots` | draft/public/secret phase 경계 관리 |
| `HgRcConfig` | `.hgrc` 설정 파일 읽기/쓰기 |
| `GpgSignature` | 커밋 서명 검증 (bouncycastle 사용) |
| `HgLfsManager` / `HgLfsPointer` | Git LFS와 유사한 대용량 파일 포인터 지원 |
| `HgObsolescenceParser` / `HgObsMarker` | obsolescence marker(deprecated changeset 추적) |
| `HgSubrepoParser` / `HgSubrepoEntry` | `.hgsub`/`.hgsubstate` 서브저장소 |
| `StoreEngine` / `DefaultFileStoreEngine` | 저장소 파일 I/O 추상화 계층 |
| `NodeIdUtil` | 40자 hex node id ↔ 바이트 변환 유틸 |
| `HgTreeFilter` | 경로 필터 인터페이스, `treewalk.PathFilter` 구현. Javadoc에 "Inspired by JGit's TreeFilter" 명시 — ⚠️ `core → treewalk` 역방향 의존. [[core-package-split-plan]] Phase 11에서 `treewalk`로 이동 예정(결정됨, 미실행) |

## 향후 변경 예정 (계획 문서 참고, 아직 미실행)
- 이 패키지는 [[jgit-parity-requirement]]에 따라 JGit 기준 7개 패키지(`lib`/`storage`/
  `dirstate`/`merge`/`diff`/`util`/`submodule`/`phase`/`obsolete`/`revset`/`bundle`/`lfs`/
  `gpg`)로 분리될 예정이다. 구체적 순서는 [[core-package-split-plan]] 참고.
- Revlog v2 지원 작업([[revlog-v2-support-plan]])이 이 패키지의 `Revlog`/`RevlogIndex`에
  영향을 준다.

## 상위 클래스 코드 위치
`src/main/java/com/github/search5/hg4j/core/`
