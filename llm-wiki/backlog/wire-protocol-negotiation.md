---
updated: 2026-09-09
status: 48번 미해결(SSH PRIVATE 거부 hang) — 나머지는 completed
---

# 백로그 2, 3, 22, 24, 25, 46, 47: HTTP/SSH 와이어 프로토콜 협상과 서버 견고성

관련 항목: 2(v1→v2 자동 업그레이드), 3(실제 hg 서버와의 라이브 통신 검증),
22(HTTP/SSH "실전 통신·협상" 테스트 확충 4개 그룹), 24(장수 서버 핸들의 stale 캐시),
25(외부에서 새 브랜치에 커밋된 파일 내용이 clone 시 전달 안 됨 — 조사 결과 오탐으로
판명, real hg 자체의 정상 동작이었음), 46(HgHttpWireServer를 com.sun.net.httpserver.
HttpHandler에서 jakarta.servlet.http.HttpServlet으로 마이그레이션), 47(46번 검증
중 발견 — 24번이 고쳤던 장수 서버 핸들 stale 캐시 버그가 39번의 StripCommand 회귀
수정으로 인해 재발한 것을 확인·수정). 추가로 HTTP v1 인자 전송(X-HgArg-N/-0.2 프레이밍)
버그, SSH 전송 계층 전면 재구현, unbundlehash 최적화 세션 기록도 이 문서에 통합.

모든 항목 real hg CLI/서버(host native 7.2.2, Docker `hg-rust-7.2.4`, Docker Mercurial
6.0)와 실제 왕복 검증 완료.

## 원문 (백로그 2, 3)
2. ~~**`HgRemoteClient`의 v1→v2 자동 업그레이드 로직이 절대 트리거되지 않음**~~ — ✅
   **완료(2026-09-01)**. `?cmd=capabilities` 요청에 실제 `X-HgUpgrade-1`/`X-HgProto-1`
   핸드셰이크 헤더를 실어 보내고, 응답이 CBOR `{apibase, apis: {<namespace>: {...}}}`로
   디코딩되면(실제 v2 서버) `HgRemoteClientV2`로 자동 위임, 디코딩 실패/미해당이면
   (실제 v1 전용 서버는 알 수 없는 헤더를 그냥 무시하고 평문을 그대로 반환) 같은 응답
   바이트를 기존 평문 v1 파싱 경로로 폴백 — 요청 한 번으로 양쪽 다 처리한다. 가짜
   `"http-v2"` 토큰 매칭 로직은 제거. TDD 테스트 2건(GREEN, 로컬 `HttpServer` +
   `HgWireServer.handleCapabilitiesDiscovery`로 실제 핸드셰이크/폴백 양쪽 재현).
3. ~~최신 Mercurial 서버와의 라이브 통신 검증 미착수~~ — ✅ **완료(2026-09-01)**.
   Docker로 실제 Mercurial 6.0(`hg serve`, HTTP)을 띄우고
   `transport.HgHttpV1LiveServerInteropTest`(`@Tag("interop")`)로 hg4j
   `PullCommand`/`PushCommand`가 실시간으로 pull(2커밋 수신) + push(신규 커밋 전송 →
   별도 fresh pull로 서버에 실제 반영됐는지 재확인)까지 왕복 검증했다. 실제 hg
   클라이언트 → hg4j 서버 방향(v1)은 이 시점엔 미검증이었으나, 이후 JGit식 재구성
   (`HgWireServer`를 `HgHttpWireServer`/`HgSshWireServer`로 교체)과 백로그 22/24번을
   거치며 완료됨 — 상세는 위 gap table "Wire protocol v1" 행 참고.
22. ~~**HTTP/SSH 와이어 프로토콜 "실전 통신·협상" 테스트 확충**~~ — ✅ **완료(2026-09-03,
    4개 그룹 전부)**. 배경: 2026-09-03 세션에서 두 종류의 작업을 병행해봄 —
    (a) 순수 JaCoCo BRANCH 커버리지 갭 메우기(missed 1~4 롱테일 ~30개 클래스), (b) 그
    이전에 했던 SSH/HTTP 라이브 interop 검증(백로그 3번, `unbundlehash`, SSH 핸드셰이크
    전면 재구현 등). **(a)는 실버그를 하나도 못 찾았고**(전부 "이미 맞게 동작하는데
    테스트만 없었다" 아니면 "도달 불가능한 방어 코드"), **(b)는 실제 프로토콜 버그를
    다수 찾아냈다**(HTTP `X-HgArg-N`/`-0.2` 프레이밍, SSH 핸드셰이크 전체가 지어낸
    것이었던 문제 등, 백로그 3번/위 표 참고). 시간 대비 버그 발견율이 압도적으로 높은
    쪽을 우선하는 게 합리적이라 판단해 다음 세션의 정식 작업 항목으로 백로그에 편입.

    **범위(포함)**:
    - **hg4j 클라이언트 → 실제 hg 서버**: HTTP v1의 3단계 인자 전송 방식
      (`httppostargs`/`httpheader=N`/레거시 GET) 각각을 실제로 강제 광고하는 서버
      설정으로 개별 검증(현재는 한 가지 조합만 확인됨). `x-hgproto-1` 압축 협상
      (`zlib`/`zstd`/none) 조합별 실제 왕복. SSH의 `unbundlehash` on/off 양쪽 다
      실제 서버로 push 성공까지 확인(현재는 와이어 바이트만 mock 서버로 검증, 실제
      hg 서버가 그 sentinel을 accept하는지는 미검증).
    - **실제 hg 클라이언트 → hg4j 서버 방향(HTTP v1, SSH v1)** — 위 항목 3번에 이미
      "미검증으로 남음"이라고 명시된 진짜 gap. 실제 `hg clone`/`hg pull`/`hg push`
      CLI가 hg4j의 `HgHttpWireServer`/`HgSshWireServer`를 상대로 정상 동작하는지
      양방향 확인.
    - 서버 구현체: native hg 7.2.2(호스트) + `hg-rust-7.2.4`(Docker) 두 개로 충분 —
      이번 세션에서 이미 둘 다 쓰고 있었고, 둘 다 wireprotocol v1이 대상이라 v2
      전용이었던 Mercurial 6.0 Docker(별도 인스턴스)까지는 불필요(v2는 아래
      "범위(제외)" 참고).
    - 협상 실패/폴백 경로: 서버가 특정 capability를 광고하지 않을 때 클라이언트가
      실제로 하위 호환 경로로 정확히 떨어지는지(예: `httppostargs` 미광고 시
      `httpheader=` 경로로, 그것도 없으면 레거시 GET으로) 각 단계를 개별 강제해
      확인.

    **범위(제외, 무한정 확장 방지)**: wireprotocol v2(백로그 상 이미 "실사용 가치
    제한적"으로 결론난 상태, 6.1부터 폐기됨) 관련 협상 확장은 이번 항목에서 제외.
    clonebundles(백로그 별도 섹션에서 이미 다룸)도 제외. TLS/인증 계층(HTTP Basic
    auth 이상의 보안 프로토콜 자체)은 제외 — 순수 Mercurial 와이어 프로토콜
    협상만 대상. 압축 알고리즘 자체의 정확성(zlib/zstd 코덱 버그)은 `DeltaCodec`
    쪽 BRANCH 커버리지 백로그(별도, `test-coverage-95-percent-initiative.md`)와
    중복되므로 이 항목에서는 "협상 결과로 올바른 코덱이 선택되는지"까지만 본다.

    **다음 세션 시작점**: 위 "범위(포함)" 4개 그룹을 각각 별도 TDD 배치로 나눠
    순서대로 진행 권장(실제 hg 클라이언트→hg4j 서버 방향이 가장 검증 안 된 채
    남아있어 우선순위가 가장 높음).

    **그룹 1/2/4(클라이언트 방향) 진행 결과, 2026-09-03**: 기존 구현(`HgRemoteClient`/
    `HgSshClient`)의 3단계 인자 전송·압축 협상·`unbundlehash` 로직 자체는 이미 정확하게
    구현돼 있었다(코드 재작성 불필요) — 이번 세션이 실제로 한 일은 "각 조합을 real hg
    상대로 개별 강제해서 실버그가 없는지 확인"뿐이었고, **실제로 새로 발견한 프로토콜
    버그는 0건**이었다(이 점에서 이번 배치는 이 문서 상단에 적힌 "(a) 순수 커버리지
    갭 메우기는 실버그를 못 찾는다"는 패턴에 더 가까웠다 — 다만 "이미 구현은 있지만
    한 번도 real hg로 강제 검증된 적 없는 분기"였다는 점에서 여전히 가치 있는 검증).

    - **httppostargs 강제**: 실제 hg는 `experimental.httppostargs`(기본 false) 설정으로
      켤 수 있음 확인(`mercurial/wireprotoserver.py addcapabilities()` 실측). 이 config로
      real `hg serve`를 띄워 capabilities에 `httppostargs`가 실제로 나타나는지, 그리고
      hg4j `PullCommand`/`PushCommand`가 그 상태에서 실제 pull+push 왕복에 성공하는지
      확인 — 통과.
    - **httpheader=N 경로**: 실제 hg는 이 토큰을 **무조건** 광고한다(config로 끌 수
      없음, `addcapabilities()`에 조건 없이 `caps.append(b'httpheader=%d' % ...)`).
      기존 `HgHttpV1LiveServerCgNegotiationInteropTest`가 이미 이 경로를 통해 real
      Rust hg 7.2.4와 실제로 changegroup 버전까지 협상하고 있었으므로 별도 그룹으로
      중복 검증하지 않음.
    - **레거시 GET(3번째 티어) 강제**: real hg에 이를 끄는 config가 없어서(위와 동일한
      이유), real hg 자체는 건드리지 않고 `?cmd=capabilities` 응답 바디에서만
      `httpheader=` 토큰을 제거하는 투명 리버스 프록시(`CapabilityStrippingHttpProxy`,
      다른 모든 요청/응답은 바이트 단위로 그대로 통과)를 hg4j와 real hg 사이에 세워
      강제. 이 상태에서 hg4j가 실제로 쿼리스트링 GET으로 폴백하고(capabilities에
      `httpheader=`/`httppostargs` 둘 다 없음을 sanity로 확인) real hg의 `_args()`가
      —원래 querystring도 항상 파싱하므로— 정상적으로 pull을 완주함을 확인.
    - **압축 협상 `zlib`/`zstd`/`none`**: real hg는 `server.compressionengines=<엔진>`
      config로 광고 목록을 강제로 한 엔진만으로 좁힐 수 있음 확인
      (`wireprototypes.supportedcompengines()` 실측). 처음엔 zstd에 별도 `zstandard`
      PyPI 패키지가 필요한 줄 알았으나, real hg는 자체 번들 C 확장
      (`mercurial.zstd`, `mercurial/zstd.*.so`)을 쓰므로 호스트 native hg 7.2에 이미
      포함돼 있어 Docker/재빌드 불필요였음 — 세 엔진 전부 host `hg serve`만으로 강제
      가능. 세 조합 각각 capabilities의 `compression=` 토큰이 강제한 엔진 하나만
      담고 있음을 확인 후 실제 pull(0.2 프레이밍 + 해당 코덱 압축해제 왕복) 성공 확인.
    - **`unbundlehash` off (HTTP+SSH 둘 다)**: real hg는 이 토큰도 무조건 광고
      (`wireprotov1server.wireprotocaps` 리스트에 조건 없이 포함) — "on" 경로는
      기존 `HgSshClientRealHgInteropTest#pushEndToEndToARealHgSshServer`(SSH)/
      `HgHttpV1LiveServerInteropTest#pushes...`(HTTP)가 real hg가 always-on이므로
      이미 암묵적으로 검증하고 있었음(해시된 sentinel이 실제로 accept됨).
      "off"는 real hg에 끌 방법이 없어 두 가지 MITM 기법으로 강제: HTTP는 위와 같은
      `CapabilityStrippingHttpProxy`로 `unbundlehash` 토큰만 제거; SSH는 real
      `hg serve --stdio` 서브프로세스를 그대로 파이프하되 최초의 framed 응답(`hello`
      커맨드의 `capabilities: ...` 줄)만 디코드·토큰 제거·재인코드하는
      `UnbundlehashStrippingHgServeCommand`(Apache MINA SSHD `Command` 어댑터,
      `HgSshClientRealHgInteropTest`의 `RealHgServeCommand` 패턴 확장)로 구현. 두
      경우 다 capabilities에서 `unbundlehash`가 실제로 사라졌음을 sanity로 확인한
      뒤, hg4j가 (해시 대신) literal heads 목록을 보내고 real hg의
      `exchange.check_heads()`가 이를 정상적으로 accept해 push가 성공함을 확인 —
      "off"가 단순히 "안 깨진다" 수준이 아니라 진짜 원래(최적화 이전) 경로가 real
      서버에 여전히 정상 동작함을 증명.

    **강제 못 시킨 조합**: 없음 — 위 "범위(포함)" 1/2/4번 그룹의 모든 하위 조합을
    real hg(host native 7.2)만으로 전부 강제·검증했다(Docker `hg-rust-7.2.4`는 결국
    쓰지 않았음 — zstd가 host native hg에도 이미 있었기 때문).

    **테스트**: `HgHttpV1NegotiationForcingInteropTest`(httppostargs 강제/레거시 GET
    강제/압축 3종 강제/HTTP unbundlehash-off 강제, 6개 테스트) +
    `HgSshUnbundleHashOffInteropTest`(SSH unbundlehash-off 강제, 1개 테스트) — 신설
    지원 클래스 `RealHgServeSupport`(host `hg serve` 기동+포트 감지)/
    `CapabilityStrippingHttpProxy`(capabilities 응답 토큰 제거 투명 프록시)와 함께
    `src/test/java/io/github/search5/hg4j/transport/`에 추가, 전부 GREEN. 기존
    `HgHttpV1LiveServerInteropTest`/`HgHttpV1LiveServerCgNegotiationInteropTest`/
    `HgSshClientRealHgInteropTest` 등은 건드리지 않음(그대로 GREEN 유지, transport
    패키지 전체 492개 테스트 무손상 확인).

    **미완료**: 없음 — 그룹 3(실제 hg 클라이언트 → hg4j 서버 방향)도 병렬로 진행된
    별도 작업으로 ✅ 완료(2026-09-03, 위 gap table "Wire protocol v1" 행 참고): SSH
    증분 pull/push, 여러 branch/bookmark/tag가 있는 저장소의 clone 정확성(HTTP+SSH),
    존재하지 않는 리비전 요청 시 에러 처리, 같은 서버에서의 클라이언트 간 즉시 일관성
    신규 검증. 이로써 22번 항목의 4개 그룹(1/2/3/4) 전부 완료.

24. ~~**`HgHttpWireServer`/`HgSshWireServer`가 외부 프로세스의 저장소 변경을 못 보고
    stale `Revlog` 캐시를 계속 서빙함**~~ — ✅ **완료(2026-09-03)**. 백로그 22번
    검증 중 발견된 것을 정식 백로그로 승격 후 바로 수정.

    **방향 결정**: 자동 stale 감지 + 갱신(사용자 승인, "운영 제약 문서화만" 대신
    선택).

    **근본 원인(먼저 재현 테스트로 정확히 특정)**: 처음엔 `HgRepository.getRevlog()`가
    캐싱한 `Revlog`/`RevlogIndex`가 아예 재확인을 안 하는 문제인 줄 알았으나,
    실제로는 `RevlogIndex.checkAndUpdate()`가 매 읽기마다 디스크 크기를 재확인하는
    기존 로직 자체는 있었다 — 문제는 그 로직의 `addedRecords.isEmpty()` 가드였다.
    `addedRecords`는 로컬 쓰기가 있을 때마다 채워지고 **한 번도 다시 비워지지
    않는다**(`clearCache()`/`loadIndex()`에서만 비워짐) — 그래서 `serverRepo`가
    자기 자신으로 단 한 번이라도 커밋한 적이 있으면(이 세션 테스트들의 `setUp()`이
    항상 그랬듯), 그 RevlogIndex 인스턴스는 그 시점부터 **영원히** 외부 변경
    재확인을 건너뛴다. 이 가드 자체는 StripCommand/RebaseCommand/HisteditCommand가
    truncate 직후 같은 핸들을 재사용하는 시나리오에 필요한 것이라(주석 참고)
    함부로 손대면 위험 — 그래서 `RevlogIndex.checkAndUpdate()`는 그대로 두고,
    **더 상위 계층**에 새 메커니즘을 추가했다.

    **구현**: `HgRepository.refreshIfChangedOnDisk()` 신설 — changelog 파일
    (`00changelog.i`)의 크기와 mtime을 둘 다 비교(크기만으로는 부족 — changelog-v2
    저장소는 docket 파일 크기가 고정이고 `index_end`/`data_end` 필드만 in-place로
    갱신되므로)해서 바뀌었으면 `clearRevlogCache()`로 캐시 전체를 무효화, 다음
    접근 시 완전히 새로운 `RevlogIndex`가 만들어지므로 `addedRecords`도 다시
    빈 상태로 시작한다(기존 로직을 안 건드리고 우회). `HgHttpWireServer.handle()`
    맨 앞과 `HgSshWireServer.handleConnection()`의 명령 루프 맨 앞에서 호출 —
    매 HTTP 요청/SSH 명령마다 자동으로 확인.

    **검증**: 기존 `HgHttpWireServerRealHgInteropTest`의 멀티 브랜치/북마크/태그
    테스트에서 수동 `clearRevlogCache()` 호출을 제거해도 그대로 통과함을 확인.
    SSH 쪽은 기존 테스트들이 전부 연결마다 `HgRepository`를 새로 여는 테스트
    하네스 패턴이라(그래서 이 버그를 애초에 재현할 수 없었음) 이 문제를 실제로
    검증하는 신규 테스트
    `realHgSeesExternalRepoChangesAcrossConnectionsOnALongLivedSshServer`를
    추가(HTTP 테스트처럼 하나의 `HgRepository`/`HgSshWireServer`를 여러 SSH
    연결에 걸쳐 재사용하는 `SharedRepoHgWireCommand` 신설) — 자동 감지가 SSH
    쪽에서도 동작함을 확인. 전체 회귀 2358 테스트, 실패 0.

    **검증 중 발견한 별개의 새 버그**: 백로그 25번 참고.

25. ~~**외부에서 새 브랜치에 커밋된 파일의 내용이 clone 시 전달되지 않음**~~ —
    🟢 **오탐으로 확인·종결(2026-09-04)**. hg4j 버그가 아니라 **real Mercurial
    자체의 정상 clone 동작**이었다.

    **조사 경과**: `HgLocalClient.getBundle()`이 만든 raw 번들 바이트를 직접
    파싱·델타 재구성해서 대조한 결과, changelog/manifest/filelog(b.txt 포함)
    전부 완벽하게 정확히 패킹돼 있었다(재구성한 매니페스트 텍스트가 서버 원본과
    바이트 단위로 일치). `hg clone --debug`로 실제 와이어 트래픽까지 확인한
    결과 — **b.txt.i가 클라이언트의 `.hg/store/data/`에 정상적으로 도착해
    있었다**(unbundle 자체는 완전히 성공). 문제는 그 다음 단계: real hg
    클라이언트가 clone 직후 자동으로 수행하는 체크아웃이 `updating to branch
    default`를 출력하며 저장소 전체 tip(방금 만든 "feature" 브랜치 커밋)이 아니라
    **"default" 브랜치의 tip만** 작업 디렉터리에 반영하고 있었다 — b.txt는
    "feature" 브랜치에서 추가됐으므로 체크아웃 대상에서 제외된 것.

    **확인**: hg4j를 완전히 배제하고 순정 `hg`끼리만(`hg init` → 커밋 → `hg
    branch feature` → 커밋 → `hg clone --debug`) 똑같은 시나리오를 재현하니
    **바이트 하나 다르지 않게 동일한 결과**(`updating to branch default`,
    작업 디렉터리에 `a.txt`만 존재)가 나왔다 — real hg 자신의 문서화된 clone
    기본 동작(별도 `-u`/`--updaterev` 옵션이 없으면 저장소 전체 tip이 아니라
    **"default" 브랜치의 tip**을 체크아웃)임을 확정. hg4j는 이 시나리오에서
    처음부터 끝까지 완전히 올바르게 동작하고 있었다 — `hg log`/`hg branches`/
    `hg update feature` 등으로 확인하면 b.txt가 정상적으로 보였을 것.

    **교훈**: 백로그 24번 검증 테스트를 짤 때 "clone 후 새로 추가된 파일이 작업
    디렉터리에 있어야 한다"는 가정 자체가 named-branch 시나리오에서는 틀린
    가정이었다 — real hg의 실제 동작을 먼저 확인하지 않고 직관적으로 "당연히
    되어야 할 것"을 단언에 넣었던 것이 원인. 백로그 24번의 테스트들은 이 잘못된
    단언을 이미 제거하고 changelog/branch 메타데이터만 검증하도록 수정된 상태라
    문제 없음.

## HTTP v1 인자 전송(X-HgArg-N) 버그 발견·수정 + mercurial-0.2 프레이밍 버그 (2026-09-03)

사용자 요청("interop 통신과정에서 광고하는 버전으로 협상하는지 면밀하게 확인해줘")에 따라
`getbundle`의 changegroup 버전 협상이 실제 hg 서버와의 라이브 통신에서 진짜로 광고한 버전에
수렴하는지 검증하다가, 그 협상 자체를 무력화시키는 훨씬 근본적인 버그를 발견했다.

### 버그 1 (SEVERE): `HgRemoteClient`가 인자 전달에 POST를 쓰고 있었다 — 실제 hg는 GET+헤더

실제 hg의 `mercurial/httppeer.py`(`makev1commandrequest()`)를 직접 읽고, 로컬 TCP 로깅
프록시로 실제 `hg --debug clone` 세션의 원본 바이트를 캡처해 확인한 실제 스펙: 인자를 가진
v1 명령(`getbundle`/`changegroup`/`pushkey`)은 3단계 폴백으로 전송된다.
1. 서버가 `httppostargs` 광고 시: POST 바디 = urlencode(정렬된 인자), 헤더
   `X-HgArgs-Post: <len>`.
2. 서버가 `httpheader=<N>` 광고 시(실제 hg 서버 기본값): **GET** 요청, 인자 없는 쿼리스트링,
   urlencode된 인자 문자열을 `X-HgArg-1`, `X-HgArg-2`, ... 요청 헤더로 쪼개 전송(각 청크는
   `N - len("X-HgArg-0") - 4`바이트), `Vary: X-HgArg-1,...` 헤더 동반.
3. 둘 다 없으면(구식/최소 서버): 레거시 — 인자를 그대로 쿼리스트링에 붙인 평범한 GET.

hg4j의 `HgRemoteClient`는 이 셋 중 어느 것도 구현하지 않고 **항상 POST 폼바디**로 보내고
있었다 — 실제 hg 서버의 v1 인자 파서는 이 명령들에 대해 POST 바디를 전혀 읽지 않으므로,
서버는 `bundlecaps` 등 인자를 아예 못 받은 것으로 보고 조용히 구식 bundle1(cg1)로
폴백했다. 즉 이전 세션에서 "01~05까지 광고하도록" 고친 것 자체는 틀리지 않았지만, 전송
메커니즘이 잘못돼 있어 실사용 효과가 사실상 전무했다.

**수정**: `HgRemoteClient`에 `executeArgsCommand()`(3단계 분기), `negotiateV2()`가
`httppostargs`/`httpmediatype=`/`compression=` 토큰도 파싱하도록 확장, 서버가 실제로
`httpheader=`를 광고했을 때만 헤더 tier를 쓰고 그렇지 않으면 레거시 쿼리스트링 tier로
폴백(`sawHttpHeaderCap` 플래그) — 이 폴백 분기 자체도 최초 구현에서 누락되어 있었다가
회귀 테스트(`HgRemoteAndSyncTest`, capabilities가 비어 있는 최소 mock 서버)에서 곧바로
발각·수정됨. `x-hgproto-1` 헤더(`buildXHgProto1Header()`)도 처음 구현해 real hg의
`sorted(protoparams)` 방식대로 동일하게 헤더분할·전송. 서버 측(`HgHttpWireServer`)에는
`X-HgArg-N` 재조립 로직을 추가하고 `Wire1Commands.capabilitiesString()`에
`httpheader=1024`를 광고에 추가. 검증: `HgArgProtocolTest`(mock 서버로 GET/POST/헤더분할/
x-hgproto-1 내용을 바이트 단위로 확인), `HgHttpWireServerTest`(hg4j↔hg4j 자기 정합성),
`HgHttpV1LiveServerCgNegotiationInteropTest`(`hg-rust-7.2.4` 실 서버로 라이브 검증 — 응답을
직접 압축 해제해 CHANGEGROUP 파트의 `version=` 파라미터를 읽어 실제로 `04`가 협상됨을
확인; 서버의 `changegroup=01,02,03` capabilities 토큰만으로 예측한 `03`보다 높은 값이었다 —
서버 측 버전 선택이 평평한 리스트의 단순 max가 아니라 요청별 실시간 판단이라는 뜻).

### 버그 2 (별도, 위 수정의 부산물로 발견): `application/mercurial-0.2` 응답 프레이밍이 스펙과 달랐다

위 수정으로 `x-hgproto-1`이 처음 제대로 전송되자, 실제 hg 서버가 사상 처음으로 hg4j
클라이언트에게 `application/mercurial-0.2` + zstd/zlib 압축 응답을 실제로 보내왔고, 이
과정에서 `HgRemoteClient`의 -0.2 파싱이 실제 hg 서버 응답과 맞지 않는다는 것이 드러났다.
`curl`로 원본 응답 바이트를 직접 캡처해 확인: 압축명(`zstd`/`zlib`) 뒤에 hg4j가 가정하던
"4바이트 길이 프리픽스 청크 프레이밍"이 전혀 없이 압축 매직바이트(zstd `28 b5 2f fd`, zlib
`78 9c`)가 곧바로 이어짐 — 실제 포맷은 `[1바이트 이름길이][이름][압축된 페이로드가 스트림
끝까지 그대로]`뿐이었다. hg4j 자체 서버(`HgHttpWireServer`)는 -0.2를 절대 안 보내므로(항상
-0.1만 응답) 이 파싱 경로는 지금까지 실제 hg 서버 상대로 한 번도 검증된 적이 없었다 — 이번
수정 전까지는 x-hgproto-1 자체가 안 나갔으니 실제 서버가 -0.2를 골라줄 일도 없었다.

**수정**: `unwrapResponseStream`에서 `MercurialChunkedInputStream`(스펙에 없는 클래스)
사용을 제거하고 이름 뒤 바이트를 곧바로 압축 해제하도록 변경, zstd 지원도 추가(zstd-jni는
이미 의존성으로 있었음). 해당 클래스 및 그 클래스만을 대상으로 하던 리플렉션 유닛테스트
전부(`HgRemoteClientTest`/`HgRemoteClientStreamTest`/`HgRemoteClientCoverageTest`, 총
13개) 삭제, 나머지 -0.2 통합 테스트들의 mock 바디를 실제 포맷대로 재작성. 이 스코프
확장(클래스 삭제 포함)은 사용자에게 `AskUserQuestion`으로 먼저 확인 후 진행.

### 결과

전체 회귀(2270 테스트) 100% 통과, real Mercurial 7.2.4(Rust 확장 포함, `hg-rust-7.2.4`
이미지) 라이브 서버 대상 changegroup 버전 협상 및 -0.2 압축 응답 둘 다 실제로 검증됨.

## SSH 전송 계층 전면 재구현 — HgSshClient가 실제로는 전혀 다른(발명된) 프로토콜을 쓰고 있었다 (2026-09-03)

위 HTTP 작업 완료 후, 사용자가 코드리뷰에서 지적한 두 항목("SSH bundlecaps 콤마는 고쳐졌지만
실제 SSH 협상에서 작동하는지 미검증", "push()의 heads `+` 구분자가 버그인지 의도인지 불명")을
검증하다가, 애초 예상보다 훨씬 근본적인 문제를 발견했다.

### 사전 확인: `+` 구분자는 버그가 아니었다

`mercurial/wireprotov1peer.py`(`unbundle()`)를 직접 확인: 실제 hg도 heads를 `encodelist()`
(기본 구분자 공백)로 인코딩한 뒤 그 문자열을 HTTP 폼 인코딩(`urlencode`)하면서 공백이
자동으로 `+`로 변환된다 — hg4j가 직접 `+`로 join하는 것은 그 결과와 바이트 단위로 동일해
버그가 아니었다(다만 `unbundlehash` capability가 있을 때 real hg가 heads 목록 대신 SHA1
해시로 대체하는 별도 최적화는 hg4j에 없음 — 별개의 저우선순위 갭으로 기록만 함).

### 발견한 진짜 문제: `HgSshClient`의 SSH v1 인자 전송 프로토콜 자체가 실제 hg와 다르다

`mercurial/sshpeer.py`(`_sendrequest`)를 직접 확인한 결과, 실제 hg SSH v1은 인자별로
`"<key> <바이트길이(4바이트 헤더 자신 포함 아님, 텍스트 줄)>\n"` + 그만큼의 **raw 바이트**(값
뒤에 개행 없음)를 정렬된 키 순서로 보낸다. `*` 와일드카드 인자명은 `"* <count>\n"` +
`count`개의 `"<name> <len>\n<bytes>"` 트리플로 중첩 인코딩된다. 그런데 `HgSshClient`는
클라이언트·서버 양쪽에서 서로 짜맞춘, 스펙에 없는 단순 `"key value\n"` 줄 기반 포맷을 쓰고
있었다 — HTTP의 -0.2 청크 버그와 동일한 패턴(자기 자신끼리는 일관되지만 실제 hg와는 한 번도
검증된 적 없음)이되, 이번엔 심지어 **hg4j 자신의 서버(`HgSshWireServer`)와도 안 맞았다** —
`HgSshWireServer`는 이미 실제 hg 스펙대로(`"<argname> <len>\n<bytes>"`) 올바르게 구현돼
있었고(class javadoc에 "Mercurial 6.0 대조 검증" 명시), `HgSshClient`만 다른 프로토콜을
말하고 있었던 것. 즉 두 클래스를 실제로 연결하면 **핸드셰이크부터 데드락**이었다(사용자
확인: "생각보다 범위가 큽니다" → `AskUserQuestion`으로 전면 재구현 여부 확인 후 진행).

### 재구현 중 실제로 캡처한 데드락 4건 (각각 real hg 소스 확인 후 수정)

1. **핸드셰이크 자체가 틀림**: 기존 코드는 서버가 아무 명령 없이 먼저
   `"capabilities: ...\n"`을 쓴다고 가정. 실제 hg는 `_performhandshake()`에서 클라이언트가
   먼저 `"hello\n" + "between\n" + "pairs 81\n" + <81바이트 null-range 값>`을 한 번에 보내고,
   `hello`의 framed 응답(`capabilities: ...` 포함)과 `between`의 framed 응답(null-range라
   1바이트 `"\n"`)을 순서대로 읽는다 — hg4j 서버는 명령을 받을 때까지 기다리므로, 기존
   클라이언트와 연결하면 양쪽 다 상대가 먼저 말하길 기다리며 영원히 블로킹.
2. **`unbundle`(push)에 서버의 사전 확인 응답이 빠져 있었다**: 실제 hg는 `heads` 인자를 읽은
   직후 **빈 프레임을 먼저 보내** "페이로드 보내도 됨"을 알린 뒤에야 페이로드를 읽는다
   (`wireprotoserver.py`의 `getpayload()`). `HgSshWireServer`는 이 사전 확인 응답 없이 바로
   페이로드를 읽으려 해서, 새로 고친 클라이언트(사전 확인을 기다림)와 맞물려 서버는 페이로드를,
   클라이언트는 응답을 기다리며 데드락. 서버 쪽에 사전 확인 프레임 전송 +
   응답을 "에러-또는-empty 확인" + "성공 시에만 결과값" 2단계로 분리하도록 수정.
3. **cg1 changegroup 자체의 내부 청크 길이가 "헤더 포함(inclusive)" 규칙인데 "헤더 제외"로
   읽고 있었다**: `HgLocalClient`의 실제 writer(`writeEntryChunk`)를 확인하니
   `totalLen = 4(자기 자신) + 80 + delta.length` — 즉 length 필드 자신의 4바이트까지 포함한
   값이다. 기존 리더는 `len`바이트를 그대로 데이터로 읽어 매번 4바이트씩 밀려 다음 청크의
   length 필드를 쓰레기값으로 오독, 그 쓰레기값만큼 읽기를 기다리며 블로킹. changelog
   그룹 → manifest 그룹 → 파일별 그룹(각각 0000 종료) 순서로 구조를 이해하는 리더로 재작성.
4. **`getbundle` 응답이 bundle2(`"HG20"`)일 수 있는데 raw cg1 리더만 있었다**: real hg SSH
   서버(`hg-rust-7.2.4`가 아니라 이번엔 host의 native hg 7.2.2, `hg -R <repo> serve --stdio`를
   서브프로세스로 실행해 진짜 SSH 세션으로 검증)를 상대로 실제 pull/push 테스트를 돌리자마자
   재발견 — bundle2는 cg1과 완전히 다른 자기 서술 구조(매직 + 파라미터 + 파트 시퀀스, 파트
   페이로드 청크는 오히려 "헤더 제외(exclusive)" 길이 규칙)라 별도 워크가 필요했다. 응답 첫
   4바이트를 봐서 `"HG20"`이면 bundle2 워크, 아니면 cg1 워크로 분기하도록 수정(단, 이미 읽은
   4바이트를 cg1 경로에서 그냥 버리면 changelog 그룹 첫 청크의 length 필드를 잃어버리므로,
   그 4바이트를 "이미 읽은 첫 청크 길이"로 재사용하도록 별도 처리).

### 검증

- `HgSshClientTest`/`HgSshClientTransportTest`: hg4j 클라이언트 ↔ hg4j 서버(이미 실제 hg
  대조 검증된 `HgSshWireServer`) 자기정합성 — capabilities/heads/changegroup/getbundle(null
  파라미터 포함)/push/pushkey/listkeys(다중 엔트리 — 기존 줄 기반 읽기의 "첫 줄만 읽혀 다중
  키 응답이 잘리는" 별도 버그도 이번에 같이 해소)/between/known 전부.
- `HgSshWireServerTest`: unbundle 성공/실패(pre-changegroup hook 거부) 양쪽 다 새 3단계 프레임
  응답 형태로 검증.
- **`HgSshClientRealHgInteropTest`(신규)**: host의 native 실제 hg 7.2.2를 `serve --stdio`
  서브프로세스로 띄워 진짜 SSH 세션(Apache MINA SSHD, JSch)으로 hg4j 클라이언트와 연결 —
  capabilities/heads/getbundle(실제로 cg1이 아닌 버전으로 협상됨을 직접 확인)/clone
  전체(pull, 커밋 메시지·이력 일치)/push 전체(hg4j에서 만든 커밋이 실제 hg 서버에 실제로
  반영됨, `hg log`로 확인) — 이 세션에서 애초 사용자가 요청한 "SSH 경로를 실제 hg로
  재검증"이 최종적으로 완료된 지점.
- SSH 관련 테스트 전체(82개) + 전체 회귀 재실행, 0 실패.
- 위 재구현 과정에서 노출된, 실제로는 **이 SSH 재구현과 별개로 이미 있던** 회귀 4건도 같은
  세션에서 발견·수정: `HgConcurrentAndHookTest.testRealSshRoundtrip`, `HgSshTransportRoundtripTest`
  3건, `HgRemoteMockAndServeExtensionTest`의 SSH 관련 3개 테스트 — 전부 옛 hg4j 프로토콜
  가정(단순 줄 기반, `readCapabilities` 즉시 배너, "헤더 제외" 청크 길이)으로 손으로 만든
  가짜 서버/응답 바이트를 쓰고 있었다. 가능한 곳은 `HgSshWireServer`(이미 실제 hg로 검증됨)를
  직접 백엔드로 쓰는 방식으로 교체, 나머지는 실제 프레이밍에 맞게 바이트를 다시 구성.

## unbundlehash 최적화 구현 (2026-09-03, 같은 세션 후속)

코드리뷰에서 지적된 나머지 한 항목 — "`unbundlehash` capability가 있을 때 실제 hg는 heads
목록 대신 SHA1 해시 sentinel을 보내는 별도 메커니즘이 있는데, hg4j는 구현 안 함" — 도 TDD로
마저 처리했다. `mercurial/wireprotov1peer.py`의 `unbundle()`을 확인:
```python
if heads != [b'force'] and self.capable(b'unbundlehash'):
    heads = wireprototypes.encodelist([b'hashed', hashutil.sha1(b''.join(sorted(heads))).digest()])
```
서버(`exchange.py`의 `check_heads()`)는 `their_heads`가 서버의 실제 현재 heads와 문자 그대로
같거나, `[b'hashed', sha1(정렬된 자기 현재 heads 이어붙인 것)]`와 같으면 수락한다 — 즉 클라
이언트가 (잠재적으로 훨씬 긴) 리터럴 heads 목록 대신 20바이트 SHA1 다이제스트만 보내도
되는 순수 와이어 크기 최적화다. `NodeIdUtil.computeUnbundleHeadsWireValue(heads,
serverSupportsUnbundleHash)`로 HTTP(`HgRemoteClient`)·SSH(`HgSshClient`) 양쪽에 공유
구현으로 추가, `capabilities`에 `unbundlehash` 토큰이 있을 때만 발동(없으면 기존과 동일하게
리터럴 heads 전송 — 회귀 없음).

**검증**: `HgSshUnbundleHashTest`/`HgHttpUnbundleHashTest`(신규, mock 서버로 정확한 와이어
바이트 확인) + `HgSshClientRealHgInteropTest`의 기존 push 테스트가 실제 hg SSH 서버로
그대로 재검증(다이제스트가 틀렸다면 실제 서버의 `check_heads()`가 `PushRaced`로 거부했을
것). 추가로 hg4j 코드를 전혀 거치지 않는 독립 Python 스크립트로 SSH(`serve --stdio`
서브프로세스)·HTTP(`hg serve --config web.push_ssl=false`) 양쪽에 hg4j와 동일한 형태의
요청을 직접 보내 재확인 — SSH는 `hashed` sentinel 전송 후 precheck 빈 프레임→error-or-empty
빈 프레임→`result=1`(성공), HTTP는 `"1\nadding changesets..."` 성공 응답을 실제로 받았다.
전체 회귀(2278 테스트) 재실행 결과 실패 1건(`CommitCommandTest`의 심링크 테스트, 트랜스포트와

## 백로그 46: HgHttpWireServer를 jakarta.servlet.http.HttpServlet으로 마이그레이션

✅ **완료(2026-09-06)**. 사용자 지시로 `HgHttpWireServer`가 JDK 내장
`com.sun.net.httpserver.HttpHandler` 대신 `jakarta.servlet.http.HttpServlet`을
구현하도록 변경. 라이브러리 자체는 이제 어떤 서블릿 컨테이너에도 꽂을 수 있는
순수 서블릿만 제공한다(JGit의 `GitServlet`이 자체 리스닝 소켓을 갖지 않는 것과
동일한 설계).

**변경 범위**:
- `build.gradle`: `implementation 'jakarta.servlet:jakarta.servlet-api:6.1.0'`
  (컴파일/런타임에 필요한 API만, 컨테이너는 소비자 책임 — 라이브러리에 무거운
  서블릿 컨테이너를 강제하지 않음). 테스트 전용으로
  `org.eclipse.jetty:jetty-server:12.1.12` + `org.eclipse.jetty.ee11:
  jetty-ee11-servlet:12.1.12`(jakarta.servlet 6.1과 매칭되는 Jetty 12 ee11
  환경) 추가.
- `HgHttpWireServer.java`: `implements HttpHandler` → `extends HttpServlet`,
  `handle(HttpExchange)` → `protected service(HttpServletRequest,
  HttpServletResponse)`. `HttpExchange`의 `getResponseHeaders()/
  sendResponseHeaders()/getResponseBody()` 등을 서블릿 API의
  `setHeader()/setStatus()/setContentLength()/getOutputStream()`으로 1:1 대응
  치환 — 응답 흐름(200/404/OOB_ERROR/BYTES/STREAM_UNCOMPRESSED/STREAM 케이스별
  content-type·압축 규칙)은 완전히 동일하게 보존.
- `HgTestUtils.java`(테스트 전용)에 `startServlet(HttpServlet)`/`port(Server)`/
  `stop(Server)` 헬퍼 3개 신설 — 임베디드 Jetty를 랜덤 포트로 띄워 서블릿을
  `/*`에 마운트.
- **13개 테스트 파일**이 `HgHttpWireServer`를 직접 `com.sun.net.httpserver.
  HttpServer.createContext(...)`로 등록하고 있어서 전부 Jetty 기반으로 함께
  전환 필요(`FetchCommandTest`/`FetchCommandCoverageTest`/
  `HgHttpTransportV2RoundtripTest`/`HgHttpWireServerTest`/
  `HgHttpWireServerCoverageTest`/`HgHttpWireServerNarrowInteropTest`/
  `HgHttpWireServerRealHgInteropTest`/`HgRemoteClientCoverageTest`/
  `HgRemoteClientTest`/`HgRemoteClientV2Test`/
  `HgWireProtocolMatrixIncomingOutgoingTest`/`PushLockRaceRealHgInteropTest`/
  `Wire2TreeManifestFetchTest`). `HgRemoteClientCoverageTest`/`HgRemoteClientTest`
  처럼 같은 파일에 `HgHttpWireServer`와 무관한 순수 mock `HttpHandler`(클라이언트
  측 테스트용)가 섞여 있는 경우는 그 부분만 정확히 남겨두고 `HgHttpWireServer`
  관련 블록만 선별적으로 전환. `HgHttpWireServerNarrowInteropTest`/
  `HgHttpWireServerRealHgInteropTest`처럼 응답 바이트를 가로채는 테스트 전용
  래퍼(`ResponseCapturingHttpExchange`)는 `HttpServletResponseWrapper` 기반
  `ResponseCapturingHttpServletResponse`로 재작성 — 서블릿 API의 래퍼 클래스가
  위임 보일러플레이트를 대신 처리해줘서 원래 버전보다 코드가 훨씬 짧아짐.

**검증**: non-interop 전체 통과, 영향받는 non-interop 테스트 185개(9개 파일)
개별 확인 zero failures. Scoped interop(`HgHttpWireServerRealHgInteropTest`/
`HgHttpWireServerNarrowInteropTest`/`PushLockRaceRealHgInteropTest`/
`HgWireProtocolMatrixIncomingOutgoingTest`)도 47번 수정과 함께 전부 통과 확인.

**검증 중 발견한 실제로 멈춰있던 버그 — ✅ 완료(2026-09-07, 근본 원인 확정·수정)**:
위에서 "간헐적, 재실행 시 재현 안 됨"으로 남겨뒀던 `HgWireProtocolMatrixIncomingOutgoingTest`
의 `sshMatrixOutgoing`/`sshMatrixIncoming` 행업을, post-pull(백로그 1~47 완료) 상태를
`./gradlew check` 단독 전체 재검증하던 중 다시 만나 이번엔 끝까지 추적했다. "간헐적"이라는
평가 자체가 틀렸다 — 실제로는 압축(zlib/zstd/none)이나 incoming/outgoing 방향과 무관하게
**항상 재현 가능한 결정론적 버그**였고, 단지 어느 조합에서 걸리는지가 타이밍에 따라
달라졌을 뿐이다(이번에 zstd-outgoing, none-outgoing, zlib-outgoing, zlib-incoming 전부
각각 독립적으로 재현됨).

**근본 원인** (`jstack` 스레드 덤프로 확정): 4개 테스트 헬퍼 파일
(`SshMatrixServer.java`, `HgWireProtocolMatrixTest.java`, `HgSshClientRealHgInteropTest.java`,
`HgSshUnbundleHashOffInteropTest.java`)에 문자 그대로 동일하게 복제된
`RealHgServeCommand.pump(InputStream src, OutputStream dst)` 메서드가, `src`가
EOF나 예외로 끝나도 `dst`를 닫지 않고 있었다. 이 메서드는 SSH exec 채널의
입력 스트림을 spawn된 real `hg serve --stdio` 서브프로세스의 stdin으로 파이핑하는
용도로도 쓰이는데, `dst`(서브프로세스 stdin)를 안 닫으면 그 서브프로세스는
EOF 신호를 영원히 못 받는다 — 자기 stdin이 기술적으로는 여전히 열려있다고
보고 다음 입력을 계속 기다리며 완전히 멈춘다. `jstack`으로 확인: `real-hg-serve-stdin`
펌프 스레드는 이미 조용히 종료돼 있었고(`dst`를 안 닫은 채로), `real-hg-serve-stdout`/
`real-hg-serve-wait` 스레드는 그 하위 프로세스가 다시는 응답을 안 줘서
영원히 블로킹돼 있었다. hg4j 프로덕션 코드가 아니라 실제 hg CLI 양쪽(client/server)을
잇는 테스트 헬퍼 브리지 로직 자체의 버그.

**수정**: 4개 파일 전부 `pump()`의 `finally` 블록에 `dst.close()` 추가 — 어느 방향이든
EOF를 downstream(서브프로세스 stdin 또는 SSH 채널 출력 스트림)에 정확히 전파하도록
수정. `HgWireProtocolMatrixIncomingOutgoingTest` 전체(incoming+outgoing, zlib/zstd/none
6개 조합)를 10분 OS 레벨 타임아웃으로 감싸 재실행, `BUILD SUCCESSFUL in 2m 55s`로
행업 없이 통과 확인.

## 백로그 47: RevlogIndex 장수 서버 핸들 stale 캐시 재발(24번 회귀)

✅ **완료(2026-09-06)**. 46번 검증 중 `HgHttpWireServerRealHgInteropTest
#realHgClonesMultipleBranchesBookmarksAndTagsFromHg4jServedOverHttp`가 실패해
처음엔 서블릿 마이그레이션 회귀로 의심했으나, `git stash`로 마이그레이션 이전
원본 코드로 되돌려 3번 재실행해도 동일하게 실패 — **서블릿 마이그레이션과
무관한 pre-existing 버그**로 확인.

**증상**: 장수 `HgHttpWireServer`/`HgSshWireServer` 핸들이 시작 시 로컬 커밋을
단 한 번이라도 하면(예: 테스트의 초기 seed 커밋), 그 이후로는 외부 `hg` CLI
프로세스가 같은 저장소에 아무리 커밋을 더 쌓아도 서버가 영원히 그 변경을 못 봄
— 정확히 백로그 24번이 원래 고쳤던 시나리오의 재발.

**근본 원인**: 백로그 39번(2026-09-05)이 `StripCommandCoverageTest` 회귀(커밋
직후 같은 핸들로 strip하면 방금 쓴 리비전을 잃어버리는 문제)를 고치려고
`RevlogIndex.checkAndUpdate()`에 `addedRecords.isEmpty()` 가드를 추가했는데,
이게 "이 인스턴스가 로컬로 뭔가 쓴 적이 있으면" 영구적으로 true가 되는 조건이라
— `addRecord()`가 로컬 쓰기마다 정확히 갱신해두는 `lastKnownSize` 정보를 아예
활용을 안 하고 전체를 건너뛰게 만들었다. 실측(`Repro.java` 임시 스크립트로
직접 재현): 로컬 커밋 후 `hasLocallyAddedRecords()=true`, 외부 hg CLI가 커밋을
추가해 디스크 크기가 커져도(`00changelog.i` 64→128바이트) `refreshIfChangedOnDisk()`
호출 후에도 `revisionCount`가 여전히 1로 고정, 캐시된 `Revlog` 인스턴스도
교체 안 됨(`sameInstance=true`).

**수정**: `checkAndUpdate()`에 `addedRecords`가 비어있지 않은 경우를 위한 새
분기 추가 — `currentSize > lastKnownSize`(순수 성장)일 때만 기존
`loadIndexIncremental()`(꼬리에 새 레코드만 추가, 기존 `addedRecords`/
`nodeMap`은 절대 안 건드림)을 태워 안전하게 따라잡는다. StripCommand
시나리오는 항상 크기 *감소*(truncate)라 이 새 분기의 `currentSize >
lastKnownSize` 조건에 애초에 걸리지 않으므로 기존 보호는 전혀 손상되지 않음
— `StripCommandCoverageTest` 재실행으로 회귀 없음 확인.

**검증**: `HgRepositoryTest
#testGetRevlogDetectsExternalWritesEvenAfterThisHandleMadeItsOwnEarlierLocalCommit`
(신규, real hg CLI 불필요 — 두 번째 독립 `HgRepository`/`CommitCommand` 인스턴스로
"외부 프로세스"를 시뮬레이션). TDD로 수정 전 실제로 실패함을 확인한 뒤 수정 →
통과. `HgHttpWireServerRealHgInteropTest`의 원래 실패 테스트도 통과 확인.
전체 non-interop + `StripCommandCoverageTest` 회귀 없음.

**교훈**: 한 버그(StripCommand 회귀)를 고치려고 추가한 가드가 완전히 다른,
이미 한 번 고쳤던 버그(장수 서버 핸들 stale 캐시, 백로그 24번)를 되살렸다 —
새 가드/조건을 추가할 때는 그게 어떤 기존 동작을 되돌리는지 항상 재검증할
것. 상세: [[known-bugs-registry]]의 `HgHttpWireServer`/`HgSshWireServer` 항목.
무관 — 단독 재실행 시 통과, 이 세션 앞부분에서 이미 조사된 기존 타이밍성 플레이키와 동일)
외 전부 통과.

## 백로그 48: SSH PRIVATE 저장소 거부 시 hang + SSH 전용 회귀 테스트 부재 (✅ 완료, 2026-09-09)

✅ **2026-09-09 재수정 완료**. `HgSshWireServer.rejectConnection(InputStream,
OutputStream, String)` 정적 메서드를 추가(hello 읽기 → hello 응답 → between 읽기
→ between 응답 → `Wire1Response.oobError(reason)` 순서로 쓴다; 이를 위해
`readArgs`/`writeResponse`/`writeLengthPrefixed`를 인스턴스에서 static으로 전환
— 셋 다 원래 `this`/인스턴스 필드를 쓰지 않는 순수 함수였다). `SshRelayServer.kt`의
hg 거부 분기(`dispatch()`, hg 명령 전용 — git 분기는 JGit이 자체적으로 처리하므로
그대로 둠)가 `writeErrorLine(...)` 대신 이 메서드를 호출하도록 배선.

**TDD로 확인한 RED→GREEN**: `HgSshWireServerRealHgInteropTest`에
`realHgFailsFastRatherThanHangingWhenDeniedOverSsh` 테스트를 추가하고,
`rejectConnection`을 일부러 원래 버그 형태(hello/between 생략, 에러 한 줄만
쓰고 종료)로 임시 구현해 RED를 직접 실행 확인한 뒤(2026-09-09), 진짜 구현으로
되돌려 GREEN 확인. **RED 확인 과정에서 발견한 중요한 사실**: 이 클래스가 쓰는
임베디드 Apache MINA SSHD 하네스는 실제 프로덕션의 "유닉스 도메인 소켓 릴레이 +
forced-command 쉘 스크립트" 한 홉이 없어서, hello/between을 생략한 원래 버그
코드도 이 하네스 안에서는 문자 그대로 무한 hang하지는 않는다(MINA가 커맨드
종료 시 채널을 곧바로 정리해 클라이언트 쪽 read가 곧 EOF를 보고 real hg 자신의
일반적인 `"no suitable response from remote hg"`로 빠르게 중단한다) — 실제
프로덕션에서 관찰된 진짜 hang은 그 추가 릴레이 홉에서만 재현되는 것으로 보이며,
이는 hg4j/yona 이 두 파일의 수정 범위 밖이다. 그래서 최종 테스트의 결정적
RED/GREEN 신호는 "타임아웃 여부"가 아니라 **hello/between 핸드셰이크가 실제로
완료됐는지**(real hg `--debug`가 에코하는 `capabilities: ...` 줄의 존재 여부)로
잡았다 — 이게 이 백로그가 실제로 보장해야 하는 계약이자, hg4j 레벨에서
결정론적으로 검증 가능한 유일한 계약이다. `assertTimeoutPreemptively(20s)`는
향후 문자 그대로 hang하는 회귀가 재발하면 여전히 잡아준다.

또 하나 발견한 사실: hello/between 완주 이후 real hg가 실제로 어떤 명령을 다음
으로 보내는지는 hg4j의 통제 밖이고(이 서버가 광고하는 capabilities로는 plain
`hg clone`의 첫 discovery 명령이 `listkeys namespace=bookmarks`로 결정됨,
2026-09-09 `--debug` 실측), `listkeys`는 lookup/unbundle과 달리 "0/1 접두" 같은
에러 관례가 없는 tab-분리 포맷 전용이라 우리의 범용 `Wire1Response.oobError()`
텍스트는 그 자리에서 real hg 쪽 `pushkey.decodekeys`의 `ValueError`로 이어진다
— hang은 아니고 매번 즉시(수 초 내) 종료되는 크래시이므로 원래 버그(무한 대기)
보다는 명백한 개선이지만, 클라이언트가 reason 문자열을 사람이 읽기 좋게 보여
주지는 못한다(향후 개선 여지 — command-aware 에러 포맷은 이번 수정 범위 밖).

**미해결로 남은 부분 → 2026-09-09 후속 세션에서 해소**: 이 절 원문이 지적했던
"SSH 경로 empty-repo 회귀 테스트 부재"는 `HgSshWireServerRealHgInteropTest`에
`realHgClonesEmptyRepoFromHg4jServedOverSsh` 테스트를 새로 추가해 메웠다(별도
클래스가 아니라 기존 SSH interop 테스트 클래스에 10번째 `@Test`로 추가).
`HgHttpWireServerEmptyRepoRealHgInteropTest`의 첫 번째 시나리오(`Hg.init()`으로
만든 커밋 0개 저장소 clone)와 동등하게, `hg clone ssh://...`가
`assertTimeoutPreemptively(20s)` 안에서 hang 없이 성공하고 `hg log`가 빈
문자열, `hg heads`가 exit 1(빈 출력)임을 검증한다. 프로덕션 코드
(`HgLocalClient.getBundle()`)는 이미 고쳐져 있었으므로 GREEN이 정상 결과 —
확신을 위해 `getBundle()`의 count==0 분기를 임시로 옛 `new byte[0]` 버그
형태로 되돌려 이 신규 테스트가 실제로 RED(실패)가 됨을 먼저 확인한 뒤 원상
복구했다(`git diff`로 `HgLocalClient.java` 무변경 확인). 최종
`HgSshWireServerRealHgInteropTest` 전체 재실행: `tests="10" failures="0"
errors="0"`. 이로써 백로그 48은 SSH 거부 hang 수정 + SSH empty-repo 회귀
테스트까지 완전히 마무리됐다.

---

<details>
<summary>원래 미해결 상태 기록(2026-09-09 이전, 참고용으로 보존)</summary>

🔴 **미해결(2026-09-09 기준)**. yona 쪽 실제 컨테이너 SSH 왕복 검증(P3-18 완료 로그,
2026-09-08) 중 발견한 버그로, 한 번 고쳤으나 이후 로컬 변경분을 `git reset --hard`
+ `git pull`로 되돌리는 과정에서 **커밋되지 않은 채 유실**됐다. 병렬 세션이 같은
시점에 고쳤던 "빈 저장소 clone hang" 버그(아래 참고)는 독립적으로 재적용되어 현재
코드베이스에 남아있지만, 이 버그는 그렇지 않다 — 2026-09-09 재확인:
`HgSshWireServer`에 `rejectConnection`(또는 동등한) 메서드 없음(`grep -n "public
static\|public void\|private " HgSshWireServer.java` 결과에 없음), `SshRelayServer.kt`의
hg 거부 분기도 여전히 `writeErrorLine(...)` 한 줄뿐.

**증상**: PRIVATE 프로젝트 비멤버가 `hg clone ssh://...`를 시도하면 서버가 즉시
에러를 반환해도 **실제 hg 클라이언트가 무한 대기**한다(연결 거부가 아니라 hang).

**근본 원인**: 실제 hg의 SSH peer는 연결하자마자 `hello`+`between` 두 명령을
**파이프라인으로(응답을 기다리지 않고) 먼저 전송**하고, `between`의 표준 응답
마커(`"1\n\n"`)를 볼 때까지는 **그 무엇도, 심지어 즉시 온 에러 라인(`ERR ...`)도
파싱하지 않는다.** 그래서 hello/between 핸드셰이크를 생략하고 바로 OOB 에러만
쓴 뒤 연결을 닫으면, 클라이언트는 그 에러 바이트를 전혀 소비하지 않은 채
`between`의 응답을 계속 기다리다가(또는 연결 종료를 다른 방식으로 오인하며)
멈춘다.

**고쳐야 할 곳(2군데, 크로스 레포)**:
1. **hg4j**: `HgSshWireServer`에 "hello+between 핸드셰이크를 정상적으로 완료한
   뒤 OOB 에러로 거절"하는 정적 유틸리티(예: `rejectConnection(InputStream in,
   OutputStream out, String reason)`)를 다시 추가.
2. **yona**: `SshRelayServer.kt`의 `hg` 명령 디스패치 중 인가 거부 분기(현재
   `writeErrorLine(output, authorization.reason ?: "...")`만 호출하는 지점)가
   위 유틸리티를 호출하도록 배선.

**SSH 전용 회귀 테스트도 없음**: "빈 저장소 clone hang" 버그(`HgLocalClient.
getBundle()`이 "보낼 것 없음" 케이스에 `byte[0]`을 그대로 반환해 실제 hg
클라이언트가 0바이트를 "아직 응답 안 옴"과 구분 못 해 영원히 대기하던 문제)는
이미 고쳐져 현재 코드에 있지만(다른 세션이 early-return 제거 방식으로 재수정,
`HgLocalClient.getBundle()` 주석 2026-09-08 참고), 이를 검증하는 real-hg interop
테스트는 `HgHttpWireServerEmptyRepoRealHgInteropTest`(HTTP 경로)만 존재하고
**SSH 경로 동등 테스트(`HgSshWireServerEmptyRepoRealHgInteropTest` 같은 것)는
없다** — `grep -n "@Test" HgSshWireServerRealHgInteropTest.java` 8건 중 empty-repo/
denial 시나리오 없음(2026-09-09 재확인). 위 48번 SSH 거부 버그를 재수정할 때
같은 클래스(또는 신규 클래스)에 아래 두 테스트를 함께 추가할 것:
- 실제 hg가 hg4j로 SSH 서빙되는 **빈** 저장소를 clone해도 hang 없이 성공하는지
  (empty-repo 버그의 SSH 경로 커버리지)
- 실제 hg가 PRIVATE 저장소를 SSH로 clone 시도하면 hang 없이 빠르게 거부되는지
  (48번 수정의 회귀 방지 테스트)

관련: [[known-bugs-registry]], yona `docs/parity/tickets/p3-18.md`
"2026-09-08 컨테이너 end-to-end 검증 완료" 절(원래 발견 경위).

</details>

