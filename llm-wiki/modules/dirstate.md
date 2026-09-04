---
updated: 2026-08-31
status: current
---

# 모듈: dirstate (`io.github.search5.hg4j.dirstate`)

워킹카피 상태 추적 담당 패키지. [[core-package-split-plan]] Phase 1에서 `core`로부터
분리됨 — 서로만 강하게 결합돼 있고 `HgRepository`가 단방향으로만 참조해 가장 먼저
분리하기 안전한 패키지였다. JGit에는 직접 대응하는 패키지가 없다(Git은 인덱스를
플랫 바이너리 파일 하나로 관리, Mercurial은 v1/v2 두 포맷을 병행 지원).

## 클래스
- **`Dirstate`**: `.hg/dirstate` 바이너리 파일을 읽고 쓰는 메인 클래스. parent1/parent2
  노드, 파일별 상태(added/removed/merged/normal), mtime 캐시를 관리.
- **`DirstateV2Parser`**, **`DirstateV2Serializer`**: dirstate v2(44바이트 노드) 포맷의
  파싱/직렬화.
- **`DirstateV2Node`**: v2 트리 노드 값 객체.

## 도메인 개념 문서
바이너리 포맷 상세, v1/v2 차이, mtime 캐시 동작은 [[dirstate]](concepts/dirstate.md) 참고 —
이 페이지는 "패키지에 뭐가 있는가"만 다루고, "어떻게 동작하는가"는 concepts 쪽 책임.

## 상위 클래스 코드 위치
`src/main/java/io/github/search5/hg4j/dirstate/`
