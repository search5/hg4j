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

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/lfs/`
