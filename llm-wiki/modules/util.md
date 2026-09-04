---
updated: 2026-08-31
status: current
---

# 모듈: util (`io.github.search5.hg4j.util`)

의존은 낮지만 참조하는 파일 수가 많은 공용 유틸리티 패키지. [[core-package-split-plan]]
Phase 3에서 분리됨 — import 변경 범위가 커서 Track A 초반에 처리. JGit의
`org.eclipse.jgit.util`에 대응.

## 클래스
- **`NodeIdUtil`**: Mercurial node id(20바이트 SHA-1) ↔ 16진수 문자열 변환 등 공통
  헬퍼. `static final` 유틸 클래스(인스턴스화 금지).
- **`SafeFileIO`**: 원자적 파일 쓰기 등 프로덕션 등급 안전 파일 I/O. [[bookmark-full-support-plan]],
  [[journaling-crash-recovery-plan]] 등 여러 쓰기 경로가 이 클래스의 원자적 쓰기에 의존.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/util/`
