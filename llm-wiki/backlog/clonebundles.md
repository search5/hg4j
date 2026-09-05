---
updated: 2026-09-06
status: item 9 completed; item 44 in progress (다른 에이전트가 이 문서 작성 시점에 병렬로
  TDD 진행 중 — 완료되면 이 파일의 44번 절을 그 결과로 갱신할 것)
---

# 백로그 9, 44: Clonebundles (대용량 클론 오프로딩)

관련 항목: 9(clonebundles 클라이언트+서버 전체 구현 — 발견/매니페스트 파싱/다운로드/
적용 자동 배선), 44(clonebundles 서버의 매니페스트 파일이 없을 때 응답 포맷 미확인,
우선순위 낮음, 백로그 39 완료 후 신규 등록, 진행 중). 실제 스펙 조사·실행 계획은 아래
"Clonebundles 실행 계획" 절 참고.

## 원문 (백로그 9)
9. ~~**Clonebundles (대용량 클론 오프로딩) — 아예 미구현.**~~ — ✅ **완료(2026-09-01)**.
   클라이언트(발견·매니페스트 파싱·다운로드·적용·`FetchCommand`/`CloneCommand` 자동
   배선)와 **서버 측(`Wire1Commands`의 조건부 capability 광고 + `?cmd=clonebundles`
   핸들러)까지 전부 구현·검증 완료** — 처음엔 서버 측(8~9번)을 사용자 요청으로 보류
   했었으나, "JGit식 재구성" 작업(`HgHttpWireServer`/`HgSshWireServer` 신설) 중 같은
   세션에서 마저 구현됐다(`HgHttpWireServerTest#serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists`
   로 확인: `.hg/clonebundles.manifest` 파일이 없으면 capability 미광고, 생기면 광고 +
   내용 그대로 서빙). 상세 계획과 경위는 아래 "Clonebundles 실행 계획" 절 참고.
44. **Clonebundles 서버: 매니페스트 파일 없을 때 응답 포맷 미확인**. 신규,
    2026-09-04 문서 재감사로 발견(clonebundles 실행 계획의 서버측 항목
    9번, 원래도 "우선순위 낮음"으로 표시돼 있던 것을 정식 번호로 승격)
    — 미착수, 우선순위 낮음. `?cmd=clonebundles` 핸들러가 매니페스트
    파일이 아예 없는 상태에서 요청을 받았을 때 real hg가 빈 응답을 주는지
    404를 주는지 실측 확인이 안 된 상태 — real hg CLI로 직접 재현해서
    확인 후 hg4j 서버 동작을 맞출 것.

## Clonebundles 실행 계획 (2026-09-01, 신규 백로그)

> 사용자 지시: "'Clonebundles(클론 번들) 기반의 대용량 오프로딩은 Wire Protocol(v1/v2)의
> 처리 과부하를 피하기 위해 와이어 프로토콜 자체를 우회(Bypass)하는 방식'도 llm-wiki
> 백로그에 추가하고 TDD로 작업 진행."

### 실제 스펙 (Mercurial 6.0 소스 직접 확인, `mercurial/wireprotov1server.py`/
`wireprotov1peer.py`/`bundlecaches.py`/`exchange.py`)

1. **발견(discovery)**: 서버가 v1 `capabilities` 응답에 `"clonebundles"` 토큰을
   포함시켜 지원 여부를 광고한다(항목 3에서 이미 다룬 v1→v2 업그레이드 핸드셰이크와는
   무관한, 별도의 단순 capability 토큰). **중요한 정정(Docker Mercurial 6.0/7.2.2
   실측)**: 이 토큰은 core 캡ability가 아니라 `hgext/clonebundles.py`(`censor`와
   동일하게 서버측 **확장**)가 `wireprotov1server._capabilities`를
   `extensions.wrapfunction`으로 감싸서 `.hg/clonebundles.manifest` 파일이 존재할
   때만 추가한다 — `--config extensions.clonebundles=`로 확장을 명시적으로
   로드하지 않으면 매니페스트 파일이 있어도 캡ability가 절대 광고되지 않는다(반면
   `?cmd=clonebundles` 명령 핸들러 자체는 core에 무조건 등록돼 있어 확장 없이도
   응답은 오지만, 실제 hg 클라이언트도 hg4j도 캡ability 미광고 시 애초에 요청을
   시도하지 않으므로 문제 없음). 6.0/7.2.2 둘 다 확장 활성화 시 정확히 같은 조건으로
   동작함을 확인 — 단, 매니페스트 응답 바이트에 trailing newline 개수가 버전마다
   미세하게 다름(6.0: `...v2\n`, 7.2.2: `...v2\n\n`) — 파서는 빈 줄을 무시하므로
   영향 없음.
2. **매니페스트 조회**: 클라이언트가 `?cmd=clonebundles`로 GET 요청 — 응답 바디는
   서버 저장소의 `.hg/clonebundles.manifest` 파일 내용을 **그대로** 반환한다
   (`wireprotov1server.py:263` `clonebundles()`: `repo.vfs.tryread(CB_MANIFEST_FILE)`).
   이 요청 자체는 여전히 기존 wire protocol v1 경로(`?cmd=`)를 타지만, 응답으로 받은
   URL을 통한 **실제 대용량 데이터 전송은 wire protocol을 완전히 벗어난 일반 HTTP(S)
   GET**이라는 점이 이 기능의 핵심("바이패스").
3. **매니페스트 포맷**: 줄바꿈(`\n`)으로 구분된 엔트리 목록. 각 줄은
   `<URL> [<KEY>=<value>[ <KEY>=<value>]...]` — URL 뒤에 공백으로 구분된
   `key=value` 속성(키/값 모두 URI 인코딩). 예약 키(대문자): `BUNDLESPEC`(예:
   `zstd-v2`, `hg bundle --type`과 동일한 문법, `<compression>-<type>` 형태),
   `REQUIRESNI=true`, `REQUIREDRAM=64MB`. 소문자 키는 사이트 커스텀(필터링 대상
   아님). pullbundles 전용 키 `heads`/`bases`(`;`로 구분된 hex 목록)도 있지만
   이번 백로그는 **clonebundles만** 범위로 한다(pullbundles는 서버가 일반 pull
   요청에 부분 응답을 끼워 넣는 별개 기능 — 필요시 후속 항목으로 분리).
4. **클라이언트 알고리즘**(`exchange.py:_maybeapplyclonebundle` 부근): (a) 로컬
   설정 `ui.clonebundles`가 꺼져 있거나 원격이 `clonebundles` capability를 광고하지
   않으면 스킵하고 평범한 pull로 진행. (b) 매니페스트를 파싱해 `BUNDLESPEC`이
   클라이언트가 지원하지 않는 포맷인 엔트리, `REQUIRESNI=true`인데 SNI 미지원인
   엔트리 등을 필터링. (c) 남은 엔트리를 `ui.clonebundleprefers` 설정 기준으로
   정렬(비어있으면 매니페스트 순서 그대로, 첫 번째 엔트리 사용). (d) 선택된 URL로
   **일반 HTTP(S) GET**(와이어 프로토콜과 무관, 평범한 파일 다운로드)을 실행해
   번들 파일을 받는다. (e) 받은 번들을 로컬에 그대로 `unbundle`(적용) — 이미
   hg4j에 있는 `UnbundleCommand`/`ChangegroupParser` 재사용 가능. (f) 그 후 원래
   서버에 재접속해 **평범한 `pull`**로 번들 생성 시점 이후의 나머지 변경분을 마저
   받는다(즉, clonebundle은 "부분 시드"이고 항상 뒤이어 증분 pull이 따라온다 — 이
   마무리 pull 단계는 hg4j의 기존 `FetchCommand`/`PullCommand` 그대로 재사용 가능,
   신규 구현 불필요).
5. **실패 시 폴백 없음**: 다운로드가 실패하면 전체 클론이 실패해야 한다(서버
   운영자가 의도적으로 무거운 클론을 오프로딩했으므로, 실패 시 자동으로 원 서버에
   폴백하면 애초에 오프로딩한 의미가 없어지고 서버가 과부하될 수 있다는 것이 실제
   hg의 설계 근거) — 조용히 무시하고 평범한 pull로 넘어가면 안 된다.

### hg4j 구현 범위 (제안, TDD 순서)

**클라이언트 측 (우선순위 높음 — 실사용 가치가 큰 쪽)**
1. `HgRemoteClient.getCapabilities()`가 이미 파싱하는 v1 capabilities 목록에서
   `"clonebundles"` 토큰 존재 여부를 확인하는 `supportsClonebundles()` 추가.
2. `HgRemoteClient`에 `fetchClonebundlesManifest()` 신설 — `?cmd=clonebundles`
   GET, 응답 바이트를 그대로 반환(이미 있는 `executeGetBinary()` 재사용 가능).
3. 매니페스트 파서 신설(`ClonebundlesManifest`류) — 위 3번 포맷 그대로 파싱,
   `BUNDLESPEC`/`REQUIRESNI`/`REQUIREDRAM` 등 예약 키 구조화.
4. hg4j가 실제로 읽을 수 있는 `BUNDLESPEC` 값(현재 지원하는 bundle2/changegroup
   버전과 압축 방식 조합)만 남기는 필터링 로직.
5. 선택된 URL로 순수 HTTP(S) GET(와이어 프로토콜 계층을 전혀 거치지 않는 별도
   다운로드 경로 — `HgRemoteClient`의 기존 `?cmd=` 기반 메서드들과는 무관한 신규
   메서드)로 번들 바이트를 받아 기존 `UnbundleCommand`로 적용.
6. 실패 시 예외를 그대로 전파(폴백 금지)하도록 `FetchCommand`/클론 진입점에 연결.
7. clonebundle 적용 후 이어지는 마무리 증분 pull은 기존 `FetchCommand` 그대로
   호출 — 신규 코드 없음, 통합 테스트에서만 확인.

**서버 측 (우선순위 낮음 — hg4j가 서버 역할을 하는 시나리오, `HgWireServer`)**
8. `HgWireServer`의 capabilities 응답에 `.hg/clonebundles.manifest` 파일이
   존재할 때만 `"clonebundles"` 토큰을 추가.
9. `?cmd=clonebundles` 핸들러 신설 — 해당 파일 내용을 그대로 반환(실제 hg와
   동일하게 파일 유무만으로 지원 여부가 결정되므로, 파일이 없으면 빈 응답 또는
   404 — 실제 hg 동작 재확인 필요).

### 검증 방법
- 단위 테스트: 매니페스트 파서(다양한 key=value 조합, 필터링 로직) — 실제 hg
  서버 없이도 가능.
- interop 테스트(`@Tag("interop")`): 로컬 파일시스템에 정적 파일 서버(이미 세션
  전반에 쓰인 `com.sun.net.httpserver.HttpServer` 패턴)를 띄우고, 그 URL을 담은
  가짜 `clonebundles.manifest`를 실제 hg 저장소에 심어 **real hg 클라이언트**가
  hg4j가 만든 번들을 clonebundle로 실제로 받아가는지 확인하거나, 반대로 hg4j
  클라이언트가 real hg 서버(Docker 6.0)의 clonebundles 응답을 올바르게 파싱·적용
  하는지 확인.
- 회귀 없음: 기존 pull/clone 경로는 `ui.clonebundles`에 해당하는 옵션이 꺼져
  있으면(또는 서버가 capability를 광고하지 않으면) 전혀 건드리지 않아야 한다 —
  100% opt-in 경로로 구현.

### 진행 현황 (2026-09-01)

**클라이언트 1~7번, 서버 8~9번 전부 완료.** 서버 측(8~9번)은 최초엔 사용자 요청으로
보류했었으나, 이후 "JGit식 재구성"(`HgHttpWireServer`/`HgSshWireServer` 신설) 작업
중 같은 세션에서 마저 구현됐다 — 아래 목록 참고.

- ✅ `ClonebundlesManifest`(신규, `io.github.search5.hg4j.bundle`) — 매니페스트
  파서(`parse`) + hg4j가 실제로 소화 가능한 BUNDLESPEC만 남기는 필터
  (`filterSupported`: `none-v1`/`gzip-v1`/`bzip2-v1`/`none-v2`/`gzip-v2`/
  `bzip2-v2`/`zstd-v2`). 단위 테스트 7건 GREEN.
- ✅ `HgRemoteClient.supportsClonebundles()`/`fetchClonebundlesManifest()` 신설
  — 기존 `negotiateV2`가 파싱하던 capabilities 목록에 `"clonebundles"` 토큰
  체크를 추가. 로컬 `HttpServer` 기반 단위 테스트 3건 GREEN.
- ✅ `ClonebundlesCommand.downloadAndApply(repository, url)` 신설 — 순수 HTTP(S)
  GET(와이어 프로토콜 미경유)으로 번들을 받아 기존 `UnbundleCommand`로 적용,
  다운로드 실패 시 폴백 없이 예외 전파. 단위 테스트 2건 GREEN.
- ✅ **실제 hg 이중 버전 검증(Docker Mercurial 6.0 + 7.2.2, 사용자 지시로 둘 다
  확인)**: `ClonebundlesRealHgInteropTest`(`@Tag("interop")`) 3건 — (1) 확장
  미활성 시 캡ability 비광고, 활성 시 광고됨을 실제 서버로 확인, (2) 실제 서버의
  `?cmd=clonebundles` 응답이 hg4j 파서로 정확히 파싱됨(6.0/7.2.2 trailing
  newline 차이 포함해도 문제 없음), (3) **실제 hg가 `hg bundle --all --type
  none-v2`로 만든 진짜 HG20(bundle2) 번들 파일**을 hg4j의
  `UnbundleCommand`/`Bundle2Parser`가 clonebundles 다운로드 경로를 통해 정확히
  적용함(2개 커밋 모두 반영 확인) — hg4j 자체 생성 번들이 아닌 **진짜 real-hg
  산출물**로 검증했다는 점이 중요.
- ✅ **6~7번 완료(2026-09-01, TDD)**: `FetchCommand.call()`에 자동 배선 완료 —
  로컬 changelog가 완전히 비어있을 때(= 사실상 clone 시나리오, 실제 hg도
  `pull`이 아니라 `clone`에서만 시도)만, 그리고 원격이 `HgRemoteClient`(HTTP)이고
  `supportsClonebundles()`가 참일 때만 시도한다: 매니페스트 조회 →
  `filterSupported`로 필터 → 첫 엔트리 선택(→ `ui.clonebundleprefers` 미설정 시
  실제 hg와 동일) → `ClonebundlesCommand.downloadAndApply` → 실패 시 예외
  그대로 전파(폴백 없음) → 성공 시 `repository.clearRevlogCache()`로 로컬
  상태 갱신 후 기존 discovery/getbundle 로직에 그대로 이어붙여 "마무리 증분
  pull"이 자동으로 수행됨(신규 코드 없이 기존 로직 재사용, 계획대로).
  **버그 발견·수정**: 최초 구현 시 클론번들로 받은 커밋이 `FetchCommand.call()`의
  반환값(`List<byte[]>`)에 반영되지 않아 — 클론번들만으로 전체 저장소가
  이미 다 채워진 경우 뒤이은 discovery가 "이미 최신"으로 판단해 빈 리스트를
  반환하고, 그 결과 `CloneCommand.checkoutLatest()`가 "아무것도 안 받아왔다"고
  오판해 워킹카피 체크아웃을 건너뛰는 버그가 있었음(TDD로 RED 확인 후 발견).
  `ClonebundlesCommand.downloadAndApply`/`tryApplyClonebundle`이 임포트된 커밋
  목록을 반환하도록 고치고 `mergeClonebundleResults()`로 이후 결과와 합쳐(클론번들
  분량이 먼저, 증분 pull 분량이 나중) 모든 반환 지점에서 정확한 결과가 나가도록
  수정. `ClonebundlesAutoWireInteropTest`(`@Tag("interop")`)로 실제 Mercurial
  6.0 컨테이너를 대상으로 `CloneCommand` 전체 흐름(캡ability 감지 → 매니페스트
  조회 → 실제 hg가 만든 진짜 번들 다운로드 → 적용 → 워킹카피 체크아웃)이 완전히
  자동으로 동작함을 종단간 검증 — 별도로 시작한 disposable 컨테이너 기준.
- ✅ **8~9번 완료(서버 측 — `Wire1Commands`가 clonebundles를 광고·서빙하는 저장소
  역할)**. 처음엔 사용자 요청으로 보류했었으나, "JGit식 재구성" 작업(아래 log.md의
  [2026-09-01] "JGit식 재구성 + 갭 표 백로그 4건" 항목)에서 별도로 제시된
  `HgWireServer` 6개 갭 표의 5번 항목으로 다시 다뤄져 같은 세션에 마저 구현됐다:
  `Wire1Commands.capabilitiesString(repo)`가 `.hg/clonebundles.manifest` 파일 존재
  여부로 `"clonebundles"` capability 토큰을 조건부 광고(파일 없으면 미광고 — 실제
  hg의 확장 활성화 조건과 동일한 효과), `clonebundles(repo)` 커맨드가 `?cmd=clonebundles`
  요청에 파일 내용을 그대로 반환. `HgHttpWireServerTest#serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists`
  로 종단간 검증(매니페스트 파일이 생기기 전/후 실제 `HgRemoteClient`로 capability
  광고 여부와 응답 내용이 정확히 바뀌는지 확인) — 2026-09-01 재확인, GREEN.

