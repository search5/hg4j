# 작업 로그

## [2026-08-31] 위키 생성 | llm-wiki 최초 구축
- Karpathy의 LLM Wiki 패턴(index.md/log.md + concepts/modules/decisions/sources)을
  hg4j(티켓 없는 라이브러리 저장소)에 맞게 채택.
- Serena MCP 심볼 조사 + `git log` 30개 커밋 분석을 바탕으로 modules/core.md,
  modules/api.md, modules/transport-treewalk-revwalk.md,
  concepts/{revlog,dirstate,bundle2-changegroup,revset}.md,
  decisions/{package-namespace-and-dual-publishing,module-info-disabled,
  checked-exception-conversion}.md, sources/2026-08-31-initial-codebase-survey.md 작성.
- index.md에 "아직 없는 페이지" 섹션으로 미착수 조사 항목(Dirstate v2 바이너리 레이아웃,
  Merge3 상세, 저널링/크래시 복구, SSH 라이브러리 추상화 배경) 남김 — 다음 세션에서 필요할
  때 채울 것.

## [2026-08-31] 요건 추가 | JGit 완전 정합성(네이밍/구조/패키지) 요건 문서화
- 사용자 지시: "추가 기능 개발 시 JGit과 완전히 같은 코드 네이밍/구조/패키지 구조를
  가져야 한다." → `decisions/jgit-parity-requirement.md` 신설.
- JGit(`org.eclipse.jgit`) 실제 최상위 패키지 목록(GitHub 조회)을 hg4j 현재 구조와 대조해
  격차표 작성: `core` 패키지 하나가 JGit 기준 `lib`+`storage`+`dircache`+`merge`+`diff`+
  `util`+`submodule` 7개 패키지 역할을 겸하고 있음을 확인.
- 사용자가 지적한 "일부 합쳐져서 기록된 부분" 해소: `modules/transport-treewalk-revwalk.md`
  1개 파일을 `modules/{transport,treewalk,revwalk,lib,errors}.md` 5개로 분리하여 JGit
  패키지 경계와 1:1 대응하도록 재구성. `core.md`는 실제 소스가 아직 합쳐진 상태를 그대로
  반영해 의도적으로 분리하지 않음 — 위키가 실제 코드보다 앞서 나가지 않도록 함.
- 미해결 쟁점 3가지(Hg 접두어 유지 여부, core 분리 순서, ChangesetGraph/SortOrder 리네이밍
  여부)는 jgit-parity-requirement.md에 남겨두고 실제 리팩토링은 아직 착수하지 않음.

## [2026-08-31] 요건 추가 | Mercurial 전체 스펙 완전 준수 요건 문서화
- 사용자 지시: "이 라이브러리는 머큐리얼의 모든 스펙을 완전히 준수할 수 있도록 요건을
  넣어줘." → `decisions/mercurial-spec-compliance-requirement.md` 신설.
- 근거 소스 우선순위 정리(`hg help internals.*` > mercurial-scm.org 위키 > 소스 코드
  자체 > README 기준 버전 v7.2.2) 후, 조사 가능한 범위에서 스펙 영역별(requires,
  fncache, revlog v1/v2, changelog, manifest, dirstate v1/v2, bundle1/2, changegroup,
  wireprotocol v1/v2, phases, obsolescence, censor, narrow/sparse, LFS, subrepo, config,
  mergestate, extensions) hg4j 현재 클래스 존재 여부를 대조한 gap table 작성.
- Revlog v2, wireprotocol v2, censor, bundle1(레거시)은 관련 클래스/의존성이 코드에
  전혀 없어 "미구현"으로 판단. 다수 항목은 클래스는 존재하나 세부 규칙 대조가
  안 되어 "확인 필요"로 표시 — 이 표는 원문 전수 대조가 아니라 1차 스크리닝임을
  문서에 명시.
- Python 확장(extensions) 시스템은 언어 특성상 이식 불가로 범위 밖(🚫) 표시.

## [2026-08-31] 미해결 쟁점 해결 | JGit 정합성 요건 확정 + core 분리 계획 작성
- 사용자 답변: "Hg 접두어는 유지하고, 머큐리얼만의 고유 특징을 jgit과 1:1로 매칭할
  필요는 없음. core 분리 순서는 니가 알아서. 확인은 내가."
- `decisions/jgit-parity-requirement.md`의 미해결 쟁점 3개를 "결정된 사항"으로 전환:
  Hg 접두어 유지 확정, ChangesetGraph/SortOrder 등 Mercurial 고유 네이밍 리네이밍 안 함
  확정, core 분리 순서는 위임받아 별도 문서로 작성.
- `modules/revwalk.md`의 "리네이밍 후보" 표기 철회, `modules/errors.md`의 Hg 접두어
  쟁점을 "유지 확정"으로 갱신. 이 과정에서 `errors.HgLockException`과
  `core.HgLockException`이 동시에 존재함을 발견 — 실제 코드 대조 결과 우연한 충돌이
  아니라 `errors` 쪽이 `core` 쪽을 감싸는 의도된 어댑터였음을 확인(Javadoc에 명시).
  다만 예외 타입 이원화 자체는 기술 부채로 판단해 core 분리 계획의 Phase 0로 등록.
- `decisions/core-package-split-plan.md` 신설: Phase 0(HgLockException 정리)부터
  Phase 10(storage/diff 분리)까지 10단계 순서 제안. 저의존 → 고의존 순으로 배치,
  Mercurial 고유 개념(phase/obsolete/revset/bundle)은 JGit 이름을 억지로 붙이지 않고
  자체 용어로 새 패키지명 지정, JGit과 정확히 대응되는 `lfs`/`gpg`만 JGit 이름 채택.
  `build.gradle`의 jacocoTestCoverageVerification FQCN 목록을 각 단계마다 함께
  갱신해야 한다는 점을 가장 흔한 실수 위험으로 명시. **아직 실행 전, 계획 단계.**

## [2026-08-31] 미해결 쟁점 해결 | Mercurial 스펙 준수 범위 확정
- 사용자 답변: "1. Python 확장 시스템 → 수용 안함. 2번(wireprotocol v2) 3번(Revlog v2)은
  무조건 지원해야 함. 완전 준수임."
- `decisions/mercurial-spec-compliance-requirement.md` 갱신: gap table의 Revlog v2,
  Wire protocol v2 행에 "필수 구현 대상" 태그 추가(우선순위 하향 불가로 확정). Python
  확장 행은 "범위 밖 확정"으로 문구 강화. "미해결 쟁점" 섹션을 "결정된 사항"으로 전환.
- 후속 실행 시사점 기록: wireprotocol v2는 cbor 인코딩 의존성 추가 필요(현재
  build.gradle에 없음), Revlog v2는 기존 v1을 대체가 아니라 **병행 지원**해야 함
  (레포별 `requires` 파일 기준으로 v1/v2 모두 읽고 쓸 수 있어야 함). 아직 실행 계획
  문서(`revlog-v2-support-plan.md`, `wireprotocol-v2-support-plan.md`)는 미작성 —
  index.md의 "아직 없는 페이지"에 등록만 해둠.

## [2026-08-31] 남은 쟁점 3건 해결 | v2 실행계획 2건 작성 + HgTreeFilter/core→lib 확정
- 사용자 답변: "revlog-v2/wireprotocol-v2 계획 지금 작성. HgTreeFilter는 JGit에 맞춰서.
  분리 완료 후 남는 core는 JGit 관례상 lib로 개명. 단 이름 겹치는 문제는 추천해줘."
- 웹 조사(`RevlogV2Plan` 위키, `internals.wireprotocolv2`/`wireprotocolrpc`)를 바탕으로
  `decisions/revlog-v2-support-plan.md`, `decisions/wireprotocol-v2-support-plan.md`
  신설. 둘 다 1차 조사 수준임을 명시하고(바이트 레이아웃·커맨드 매핑 등은 Mercurial
  소스 직접 대조 필요), 단계별 실행 순서/코드 영향 범위/선행 의존성(CBOR 라이브러리
  등)을 기록. **계획만 작성, 실행 안 함.**
- `HgTreeFilter` 실제 소스 확인: Javadoc에 "Inspired by JGit's TreeFilter api" 명시 +
  이미 `treewalk.PathFilter` 구현 중 → JGit 대응 패키지인 `treewalk`로 이동 확정.
  `core-package-split-plan.md`에 Phase 11로 등록, `modules/core.md`·`modules/treewalk.md`에
  상호 참조 추가.
- `core`→`lib` 개명(Phase 12) 확정: 병합 후보 클래스 이름을 직접 대조한 결과 **실제
  충돌 없음**(lib: NodeId/ProgressMonitor 계열, core 잔여: HgRepository/Repository/
  HgRcConfig/HgLock/HgLockException — 겹치는 이름 없음)을 확인. 별도 충돌 회피 전략
  없이 그대로 병합하는 것을 추천 — 오히려 JGit 원본의 `org.eclipse.jgit.lib`(Repository+
  ObjectId+ProgressMonitor+Config를 한 패키지에 모은 구조)에 더 가까워짐. 체크리스트를
  `core-package-split-plan.md`에 추가하고 `modules/lib.md`에도 반영.
- index.md 갱신: 신규 결정 문서 2건 테이블 등록, core.md/lib.md 요약 문구를 최신 계획
  반영해 수정. **전부 계획 단계 — 실제 코드 이동/신규 의존성 추가는 아직 없음.**

## [2026-08-31] 핸드오프 문서 신설 | Gemini용 통합 구현 계획서 작성
- 사용자 지시: "현재 코드베이스 기준으로 수정을 가해야 할 항목들 기준으로 구현
  계획서를 제미나이가 알아먹을 수 있도록 친절히 써줘."
- `llm-wiki/implementation-plan.md` 신설 — 이 대화 맥락이 없는 외부 에이전트도 그대로
  실행 가능하도록 자기완결적으로 작성. jgit-parity-requirement +
  core-package-split-plan + mercurial-spec-compliance-requirement +
  revlog-v2-support-plan + wireprotocol-v2-support-plan 5개 decisions 문서를
  Track A(패키지 구조 재정렬, Phase 0~12)/Track B(Revlog v2·Wireprotocol v2 필수
  구현)/Track C(검증 백로그, 우선순위 미정)로 통합.
- 작성 중 실제 소스(`build.gradle`, `src/test/java/.../core/` 디렉터리 목록)를 다시
  대조해 build.gradle의 jacocoTestCoverageVerification FQCN 6개(Dirstate,
  NodeIdUtil, SafeFileIO, Merge3, Bundle2Parser, ChangegroupParser) 전후 매핑표를
  정확히 작성. 테스트 파일 분류 중 새 발견 2건 추가로 확인:
  (1) `core` 테스트 디렉터리에 있는 `HgRemoteClientTest`/`HgSshClientTest`/
  `HgRemoteMockAndServeExtensionTest`가 실제로는 `transport` 패키지 클래스를
  테스트하는 것으로 보임(이미 `transport` 테스트 디렉터리에 별도 테스트가 존재해
  중복 가능성), (2) `GpgSignatureTest`가 `core`가 아니라 `api` 테스트 디렉터리에
  있음 — 둘 다 Phase 9/12 체크리스트에 "이동 전 확인 필요" 항목으로 반영.
- `AGENTS.md`에 `implementation-plan.md`와 `decisions/*.md`의 역할 차이(전자는
  실행용 일회성 스냅샷, 후자는 계속 유지되는 진실 소스) 명시. `index.md`에 "실제
  작업 시작 시 이 문서부터 보라"는 안내 추가. **문서만 작성, 코드 변경 없음.**

## [2026-08-31] 리팩토링 진행 | Track A 패키지 구조 재정렬 (Phase 0 ~ 12) 완료 및 검증
- Phase 0 ~ Phase 12의 순차적 리팩토링을 완수하여 JGit 패키지 구조 정합성 요건을 완전히 달성.
- **Phase 0** (HgLockException 단일화), **Phase 1** (dirstate 패키지 신설), **Phase 2** (merge 패키지 신설), **Phase 3** (util 패키지 신설), **Phase 4** (submodule 패키지 신설), **Phase 5** (phase 패키지 신설), **Phase 6** (obsolete 패키지 신설), **Phase 7** (revset 패키지 신설), **Phase 8** (bundle 패키지 신설), **Phase 9** (lfs 및 gpg 패키지 신설), **Phase 10** (storage 및 diff 패키지 신설), **Phase 11** (HgTreeFilter를 treewalk로 이동), **Phase 12** (core 잔여 클래스를 lib, transport, errors로 완전히 분산 및 core 디렉터리 삭제) 완수.
- 각 단계마다 FQCN 치환, build.gradle Jacoco 대상 갱신, 상호 의존성 임포트 누락 보완을 정밀하게 처리하여 로컬 Git 커밋으로 고착화.
- 전체 클린 빌드 및 JUnit 테스트, Jacoco 커버리지 게이트 검증을 완전히 통과(BUILD SUCCESSFUL)하여 무결성 확인 및 원격 origin/main 브랜치에 최종 푸시 완료.
