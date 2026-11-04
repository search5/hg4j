---
updated: 2026-11-04
status: 진행 중. 5단계 중 1단계 완료(2026-11-04)
audience: cross-agent handoff (이 대화의 맥락이 없는 외부 에이전트)
---

# hg4j 구현 계획서: 일반 파일셋 엔진과 패턴 매처

[[implementation-plan-lfs]]와 같은 형식입니다. 작성 규칙은 [[AGENTS|AGENTS.md]]의 규칙을 그대로 따릅니다.

- 추측을 사실처럼 쓰지 않습니다. **"미확인"** 은 착수 전에 직접 확인하라는 뜻입니다.
- 완료 선언은 diff, 명령 출력, 실제 프로세스 응답 중 하나 이상의 증거로만 합니다.
- 오라클은 실제 hg 7.2.4입니다(`LocalHg`, `scripts/build-local-hg.sh`). 원본 소스는
  `~/.cache/hg4j/hg-rust-7.2.4/mercurial-7.2.4/mercurial/`(`fileset.py` 617줄, `filesetlang.py` 351줄, `match.py`)입니다.
- 이 단계 수와 진행 상황은 매 보고에 "5단계 중 N단계"로 밝힙니다.

## 0. 배경, 현황, 결정

### 0.1 현황 (2026-11-04 직접 확인)

| 항목 | 현황 | 증거 |
|---|---|---|
| 파일셋 | `MiniFileset`(401줄) 하나뿐: `lfs.track` 판정용 부분집합(`**.ext`, `path:`, `all()`, `none()`, `size()`, `lfs()`와 `and`/`or`/`not`/`-`/`,`) | `lfs/MiniFileset.java` |
| 패턴 매처 | **없음.** 명령들은 각자 글롭만 처리(`SparsePathFilter` 93줄). `glob:`, `re:`, `path:`, `rootfilesin:`, `relpath:`, `relglob:`, `relre:`, `listfile:`, `listfile0:`, `-I`/`-X` 종류 구분이 없다 | `treewalk/SparsePathFilter.java`, `api/FilesCommand#setPattern` |
| `set:` 패턴을 받는 명령 | `FilesCommand`만(이번 LFS 작업에서 `lfs()`용으로 추가). `status`, `cat`, `diff`, `commit`, `add`, `remove`, `forget`, `revert`, `locate`, `grep`, `archive`, `annotate`, `log`는 없음 | `api/*Command` 중 패턴 인자는 `AnnotateCommand#setPath`, `LocateCommand#setPattern`, `FilesCommand#setPattern`뿐 |
| ignore | `.hgignore`를 `HgRepository`가 읽는다(`hgignore()` 술어의 재료). 구문 종류(`syntax: glob/re`) 지원 범위는 **미확인** | `lib/HgRepository.java:458` |

원본의 파일셋 술어(`fileset.py`의 `@predicate`): `modified`, `added`, `removed`, `deleted`, `missing`, `unknown`,
`ignored`, `clean`, `tracked`, `binary`, `exec`, `symlink`, `resolved`, `unresolved`, `hgignore`, `portable`, `grep`, `size`,
`encoding`, `eol`, `copied`, `revs`, `status`, `subrepo`, 그리고 확장이 더하는 `lfs`. 연산자는 `and`, `or`(`|`, `+`), `not`(`!`),
`-`, `,`, 함수 호출, `kind:pattern`, 괄호입니다(`filesetlang.py`).

### 0.2 결정

사용자 지시(2026-11-04)에 따라 **구현한다.** 이유:

1. [[mercurial-spec-compliance-requirement]]의 원칙은 오라클 7.2.4와 같아야 한다는 것이고, 파일 선택은 거의 모든 명령의 입력이다.
2. 파일셋 자체는 작다(원본 약 970줄). 그러나 **패턴 매처가 먼저 필요하다**(파일셋은 `match.py` 위에서 동작한다). 따라서 실제 규모는
   "파일셋 + 매처 + 명령 연결"이다.
3. `lfs.track`/`.hglfs`도 같은 엔진을 쓰므로 `MiniFileset`이 일반 엔진으로 흡수되어 중복이 사라진다.

범위 밖(이 계획에서 하지 않음): 템플릿 엔진(`log -T`), 확장 술어 등록(`registrar`, Python 확장 시스템), `rhg`, `convert`.
`subrepo()` 술어는 [[subrepo]] 지원 범위 안에서만 한다.

## 1. 단계

### Phase 1: 패턴 매처 (`match.py`의 핵심)
- 패키지 `io.github.search5.hg4j.match`(신규). `Matcher` 인터페이스: `matches(path)`, `visitDir(dir)`(디렉터리 가지치기), `exact()`.
- 패턴 종류: `glob:`, `re:`, `path:`, `rootfilesin:`, `relpath:`, `relglob:`, `relre:`, `listfile:`, `listfile0:`, `set:`(2단계 연결),
  기본 종류(CLI 인자는 `relpath`, `-I/-X`는 `glob`). 합성: include/exclude, 합집합, 교집합, 차집합, 부정.
- 원본의 글롭 → 정규식 변환(`_globre`)과 `.hgignore`의 구문 줄(`syntax: glob`, `syntax: re`, `syntax: rootglob`)을 같은 구현으로 쓴다.
- 대조: 실제 hg로 `hg files -I`/`-X`/종류별 패턴을 같은 저장소에 돌려 목록이 같은지 비교(패턴 약 40종 x 리비전/작업 사본).
- 이 단계 완료 기준: 위 대조 통과.
- **완료 (2026-11-04).** 패키지 `io.github.search5.hg4j.match`: `Matchers`(패턴 종류 14종, `-I`/`-X` 합성, `listfile:`/`include:`/`subinclude:`/`set:`), `Globs`
  (`_globre`와 `_regex` 이식, 파이썬 정규식 번역), `PatternFiles`(`.hgignore` 문법), `Matcher`, `KindPattern`. 대조 테스트
  `MatcherParityInteropTest` 7개(약 70개 패턴 x 인자/`-I`/`-X` x 루트/하위 디렉터리 + 조합 + 오류 문구), 단위 테스트 `MatchersTest` 10개.
- **계획 변경:** `SparsePathFilter`는 이관하지 않는다. sparse checkout 전용 글롭 규칙(디렉터리면 내용 전체 포함, `hg debugsparse`로 확인)이라 일반 매처와
  의미가 다르다. 일반 매처로 바꾸면 sparse 동작이 변한다. `FilesCommand#setPattern`(글롭 의미)도 4단계에서 `setPatterns`(원본 의미)를 새로 만들고 옛 것은
  그대로 둔다.
- **원본 7.2의 결함 확인:** `hg files subinclude:...`는 내부 오류(AssertionError)로 죽는다. `hg status -A -n subinclude:...`는 정상이라 후자로 대조했다.

### Phase 2: 언어 (파서·분석·평가 틀)
- `MiniFileset`의 토큰화·파서(결합 강도 20/15/10/5/4/2)를 `filesetlang.py` 전체로 확장: `analyze`, `optimize`, `andsets`/`orsets`/`notset`/
  `minusset`/`listset`, 오류 문구(`unsupported file pattern`, `invalid pattern`, `not a symbol`, `unknown identifier` 등)를 원본과 같게.
- 평가 문맥(`matchctx`): 대상 컨텍스트(리비전 또는 작업 사본), 상태(`status`), 부분집합(`subset`), 상태가 필요한 술어를 위한 지연 계산.
- 이 단계 완료 기준: 구문 오류 문구와 `hg debugfileset --show-stage`에 해당하는 파싱 결과가 원본과 같다(문구 비교 테스트 30종 이상).

### Phase 3: 술어 24종 + 확장 술어
- 내용·플래그: `binary`, `exec`, `symlink`, `size`, `grep`, `encoding`, `eol`, `portable`, `tracked`, `copied`.
- 상태: `modified`, `added`, `removed`, `deleted`, `missing`, `unknown`, `ignored`, `clean`, `resolved`, `unresolved`, `hgignore`,
  `status(base, rev, pattern)`, `revs(revs, pattern)`, `subrepo([pattern])`.
- `lfs()`는 이미 구현되어 있으므로 새 엔진 위로 옮긴다.
- 대조: 술어마다 리비전 4개 + 작업 사본에서 실제 hg `hg files "set:..."`와 결과 집합 비교(술어 24종 x 문맥 5개 이상, 인자 변형 포함).
- 이 단계 완료 기준: 위 대조 전부 통과, `lfs.track`/`.hglfs` 기존 테스트(`LfsTrackRealHgInteropTest` 등)가 새 엔진으로도 통과.

### Phase 4: 명령 연결
- `set:EXPR`과 패턴 종류를 받도록 만든다: `status`, `cat`, `diff`, `commit`, `add`, `remove`, `forget`, `revert`, `locate`, `grep`, `archive`,
  `annotate`, `log`(파일 인자), `files`(`-I`/`-X` 포함). 각 명령에 `setPatterns(String...)`, `setInclude(String...)`, `setExclude(String...)`.
- 규칙: 명령마다 원본이 패턴을 해석하는 기준(작업 디렉터리 상대인지 저장소 루트 상대인지, `hg add`의 하위 저장소 처리 등)을 원본 코드로 확인한 뒤 적용한다.
- 이 단계 완료 기준: 명령별 대조 테스트(명령 14개 x 시나리오 4개 이상).

### Phase 5: 마무리
- `MiniFileset`을 제거하고 호출부를 새 엔진으로 일원화. 위키 갱신(`modules/`, `index.md`, 이 계획서의 상태, 백로그 문서, `log.md`).
- 일반 `test` 전체와 변경 영역의 interop 통과, 알려진 의도적 차이 기록.

## 2. 검증 규율 (사용자 지시 반영)
- 일반 `./gradlew test -x interopTest`는 단계마다 전체를 돌린다.
- interop는 **직접 수정한 클래스와 그 영향을 받는 관련 클래스만** 지정해서 돌린다(넓은 와일드카드 묶음 금지). 핵심 경로를 바꿀 때만 넓히고 이유를 보고에 쓴다.
- 구현 전 빨강(테스트가 실패함)을 먼저 확인한다.

## 3. 위험과 미확인
- **미확인:** `.hgignore` 구문 지원 범위, 명령별 패턴 기준 디렉터리 규칙, `hg status`의 `-I/-X`와 `set:` 결합 순서.
- 매처가 성능에 영향을 줄 수 있다(대형 저장소의 `status`/`files`). `visitDir` 가지치기를 구현하고 측정한다.
- 기존 명령의 동작이 바뀔 수 있다(패턴 해석이 원본에 맞춰지므로). 바뀌는 점은 단계마다 `log.md`에 적고, 의존하던 단위 테스트는 이유와 함께 고친다.
