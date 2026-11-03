---
updated: 2026-11-03
status: current
---

# 결정: interop 테스트의 `hg` 오라클을 Docker에서 호스트 로컬 빌드로 교체

## 결정
`@Tag("interop")` 테스트가 쓰던 `hg-rust-7.2.4` Docker 이미지를 걷어내고, 호스트에 직접 빌드한 Rust 확장
포함 `hg` 7.2.4를 쓴다. 사용자 지시(2026-11-03)다.

- 빌드: `scripts/build-local-hg.sh` (결과 `~/.cache/hg4j/hg-rust-7.2.4/bin/hg`). `docker/hg-rust-7.2.4/Dockerfile`과
  같은 절차(PyPI 소스 배포본 + `setup.py --rust build_ext --inplace` + `pyo3_rustext*.so` 수동 복사 +
  `HGMODULEPOLICY=rust+c` 래퍼)이며, Python은 `uv`의 3.11을 쓴다.
- 탐색: 환경변수 `HG4J_HG`, 없으면 `$HG4J_HG_HOME/bin/hg`, 없으면 위 기본 경로. `hg debuginstall`이
  `Rust extensions (installed)`를 보고하지 않으면 거부한다(순수 파이썬 hg는 `exp-revlogv2.2`와
  `persistent-nodemap`을 못 읽어 엉뚱한 이유로 실패하기 때문).
- 테스트 헬퍼: `src/test/java/.../testsupport/LocalHg.java`. `HGRCPATH` 비움, `TZ=UTC`, `LC_ALL=C.UTF-8`,
  상속된 `HG*` 환경변수 제거로 컨테이너가 암묵적으로 주던 격리를 재현한다.

## 왜
- 사용자 판단: 이미 소스를 받아 컴파일해 쓰는 이미지이므로 호스트에서 같은 일을 하면 Docker 의존이 필요 없다.
- Docker 없이 `./gradlew interopTest`를 실행할 수 있다.

## 측정 결과 (과장하지 않기 위해 기록)
- `docker exec hg --version` 5회 0.69초 대 로컬 0.53초. 호출당 차이는 작다(파이썬 `hg` 기동이 대부분).
- 컨테이너를 테스트마다 만들고 기다리고 멈추던 비용은 사라졌다.
- 그러나 전체 `interopTest`는 **약 63분**(2,558개 테스트)이 걸렸다. 일반 `test`는 약 2분(2,329개). 같은 조건에서
  Docker 방식과 비교한 수치는 없다(Docker 실행은 22분 경과 시점에 중단함). "크게 빨라졌다"고 단정할 근거는 없다.
- 시간은 `hg` 호출 수(매트릭스 케이스 × 호출)에 비례하므로, 더 줄이려면 병렬 실행(`maxParallelForks`)이나
  호출 수 축소가 필요하다. 별도 과제로 남긴다.

## 이식하면서 고친 것
- 컨테이너 마운트 지점 `/repo-root`는 `LocalHg.CONTAINER_ROOT_ALIAS`로 호환 처리한다. `hgIn`의 인자가
  `/repo-root`로 시작하면 실제 작업 디렉터리로 바꾼다.
- `hg -R <repo> cat a.txt`처럼 상대 경로를 쓰는 호출은 컨테이너의 작업 디렉터리가 저장소(`/repo`)였기 때문에
  통과했었다. 로컬 실행에서는 `processBuilder(repoDir, ...)`로 작업 디렉터리를 지정해야 한다(3개 클래스).
- `RequirementMatrixWorktreeDockerRoundTripTest`는 `.hg/sharedpath`를 컨테이너 경로로 덮어써서 컨테이너 안의
  hg가 읽게 하는 변환이 있었다. 컨테이너 뷰가 사라졌으므로 변환을 제거했다(30건 실패의 원인이었고 수정 후 30/30 통과).
- `ClonebundlesAutoWireInteropTest`는 저장소에 정의가 없는 별도 이미지(`hg4j-test-mercurial-6.0`)와 미리 만들어진
  `/repo`에 의존했다. 로컬 `hg`로 2커밋 저장소를 만들고 `hg serve`를 하위 프로세스로 띄우도록 다시 썼다.
  서버 버전은 Mercurial 6.0에서 7.2.4로 바뀌었다.

## 남은 일
- 클래스 이름에 `Docker`가 남아 있다(`RequirementMatrix*DockerRoundTripTest` 등). 위키/백로그 참조가 많아 이번에는
  이름을 바꾸지 않았다.
- `docker/hg-rust-7.2.4/`는 삭제하지 않았다(이미지를 직접 쓰는 사람을 위한 참고용).
- `ClonebundlesRealHgInteropTest`는 수동으로 띄운 외부 서버(`hg4j-hg60-server`)가 필요한 별개 테스트라 건드리지 않았다.
  `HgHttpV1LiveServerCgNegotiationInteropTest` 2건도 같은 이유로 건너뛴다.
