---
updated: 2026-09-04
status: completed
---

# 백로그 11, 16, 26: Changegroup 포맷 확장 (cg4/cg5, Bundle1 writer, bundle2/sidedata)

관련 항목: 11(cg4/cg5 changegroup 버전 파싱·패킹·협상, real hg 7.2.2 소스 실측 기반),
16(`BundleCommand`의 gzip/bzip2 압축 타입 지원), 26(hg4j 자체 changegroup 생성/적용
경로가 cg1/무sidedata로 고정돼 있던 문제 — `bundle2=` capability 미광고가 근본 원인).

## 원문
11. ~~**Changegroup cg4/cg5 미지원**~~ (2026-09-02, 사용자 제보 후 호스트 native hg 7.2.2
    소스로 직접 대조 확인 — 2016년 논의만 됐다가 폐기된 옛 아이디어가 아니라, 실제로
    Mercurial 7.1(2025-08-04)에서 정식 채택된 최신 포맷) — ✅ **완료(2026-09-03)**.

    **실제 스펙 (`/opt/homebrew/lib/python3.14/site-packages/mercurial/changegroup.py`,
    Mercurial 7.2.2 실측)**:
    - `_packermap`에 `b'01'`~`b'05'` 다섯 버전이 등록돼 있다. 주석 원문: `# cg4 adds
      support for exchanging more advances flags`, `# ch5 adds support for
      exchanging sidedata`.
    - **cg4**(`ChangeGroupPacker04`/`cg4unpacker`, `version = b'04'`) 델타 헤더는
      `_CHANGEGROUPV4_DELTA_HEADER = struct.Struct(b">20s20s20s20s20sHbIBB20sb")`
      (node/p1/p2/deltabase/cs 각 20바이트 + flags 2바이트 + snapshot_level 1바이트
      signed + raw_size 4바이트 + encoded_comp 1바이트 + protocol_flags 1바이트 +
      storage_delta_base 20바이트 + storage_snapshot_level 1바이트signed = 총
      130바이트) — cg3까지 없던 **snapshot level**(sparse-revlog 델타 체인의 스냅샷
      깊이)과 **압축 방식(encoded_comp)**, **저장소측 델타베이스/스냅샷레벨**까지
      델타 단위로 실어 나른다. hg4j가 이미 검증한 `REVIDX_ISCENSORED`처럼, cg4는
      `revlog_constants.REVIDX_DELTA_INFO_FLAGS`(그 안에 `REVIDX_HASMETA` 포함)
      비트를 헤더 `flags` 필드에서 분리해 별도 필드로 명시적으로 전송한다.
    - **cg5**(`ChangeGroupPacker05`/`cg5unpacker`, `version = b'05'`) — sidedata(부가
      메타데이터) 교환 지원 추가. 헤더는
      `_CHANGEGROUPV5_DELTA_HEADER = struct.Struct(b">B20s20s20s20s20sH")`
      (protocol_flags 1바이트 + node/p1/p2/deltabase/cs 각 20바이트 + flags
      2바이트 = 103바이트).
    - **협상 로직**(`exchange.py`의 `_pushb2ctxaddchangesetspart`/
      `_getbundlechangegrouppart`, 둘 다 동일 패턴 실측): 원격이 bundle2 capability
      `changegroup=<v1>,<v2>,...`로 자신이 받을 수 있는 버전 목록을 광고하면, 로컬은
      그중 `changegroup.supportedoutgoingversions(repo)`(자신이 만들 수 있는 버전)와
      겹치는 것만 추려 **`version = max(cgversions)`로 가장 높은 공통 버전을 그대로
      선택**한다(사용자가 언급한 "highest changegroup format supported by both
      side"와 정확히 일치, 별도 우선순위 설정 없이 단순 숫자 최댓값).
    - **실사용 위험도는 조건부**: `changegroup.supportedincomingversions()`가 cg4를
      기본적으로 걸러낸다 — `scmutil.use_delta_info(repo)`(저장소에
      `delta-info-revlog` requirement가 있을 때만 true) 또는
      `experimental.changegroup4` 설정이 명시적으로 켜져 있을 때만 수신측이 cg4를
      광고한다. **실측 확인**: 호스트 native hg 7.2.2로 `hg init`한 기본 저장소의
      `.hg/store/requires`는 `dotencode/fncache/generaldelta/
      revlog-compression-zstd/revlogv1/sparserevlog/store`뿐 —
      **`delta-info-revlog`가 기본 포맷에 없다**. 즉 지금 당장 기본 설정의 real hg
      서버/클라이언트와 주고받을 때는 cg3로 자동 폴백되어 깨지지 않는다(사용자
      분석대로). cg5도 `experimental.changegroup5` 또는 revlogv2/changelogv2
      requirement가 있어야 광고된다 — 둘 다 이 문서의 항목 4(Revlog v2 일반)처럼
      아직 이 개발 환경에서 만들 수조차 없는 포맷과 연결돼 있어 당장은 cg4보다도
      더 먼 얘기.
    - **hg4j 쪽 실제 상태**: `HgSshClient`/`HgRemoteClient`/`FetchCommand` 세 곳
      모두 `bundlecaps`에 `"changegroup=01,02,03"`을 하드코딩 광고 중 — cg4/cg5를
      지원하는 상대와 통신해도 협상 자체에서 hg4j가 최댓값 후보에서 스스로 배제된다
      (수신 능력이 없으니 당연하지만, "최신 hg와 최적 포맷으로 못 주고받는다"는
      사용자 지적이 정확함). `ChangegroupParser`/`Revlog.appendChangeGroupEntry` 등
      실제 파싱/적용 로직에도 cg4/cg5 헤더 포맷에 대응하는 코드가 전혀 없다.
    - **결론**: README의 "SCM v7.2.2 기준 완전 준수" 주장 범위 안에 드는 진짜 gap.
      다만 위 실측대로 기본 포맷 저장소끼리는 즉시 깨지는 문제가 아니므로, cg3까지의
      상호운용성(이미 검증 완료)을 훼손하지 않는 별도 opt-in 확장으로 다뤄야 한다.

    **완료 내용(2026-09-03)**: 위 소스 기반 요약을 로컬 시스템 hg 7.2(Rust/Docker
    불필요)가 만든 실제 cg4/cg5 페이로드로 바이트 단위 재검증 — 100% 일치, 차이
    없음. CLI엔 `hg bundle -t v4/v5`가 없어서("v4 is not a recognized bundle
    specification") `changegroup.makechangegroup()` 내부 API를 직접 호출해 픽스처
    확보(`src/test/resources/fixtures/changegroup-v4-v5/README.md`에 생성 스크립트/
    sha1/바이트 레이아웃 기록). `ChangegroupParser.ChangeGroupEntry`에 cg4 전용
    필드(fullText/snapshotLevel/rawTextSize/encodedCompression/storageDeltaBase/
    storageSnapshotLevel)와 cg5 전용 필드(protocolFlags/sidedata)를 추가해 파싱+
    패킹 양쪽 구현, cg4의 `flags` 필드에서 `REVIDX_DELTA_INFO_FLAGS`(0x7C0, sparse-
    revlog 델타 힌트 비트)를 분리해 걷어내는 처리까지 포함. `HgSshClient`/
    `HgRemoteClient`/`FetchCommand`의 하드코딩된 `"changegroup=01,02,03"`을
    `01..05`로 확장하고, 원격 광고 버전과의 교집합에서 최댓값을 고르는 협상 로직을
    배선. **부수 발견(진짜 버그)**: `HgRemoteClient.getBundle()`이 `bundlecaps`
    파라미터를 스페이스로 join하고 있었는데, 실제 스펙(`wireprototypes.
    GETBUNDLE_ARGUMENTS`)상 `bundlecaps`는 콤마 구분 "scsv" 타입이라 — 스페이스
    join은 토큰이 하나로 뭉쳐 `exchange.bundle2requested()`가 항상 false가 되고,
    hg4j가 어떤 changegroup 버전을 광고하든 조용히 구식 bundle1(cg1)로만 통신하고
    있었던 별개의 실제 버그. 콤마 join + `bundle2=<중첩 blob>` 형태로 수정. 기존
    cg1/cg2/cg3 상호운용성 회귀 없음(전체 회귀 확인). 상세: `ChangegroupV4V5Test`.

    **부수 발견 2 — cg3에도 있던 별개의 실제 버그**: `ChangegroupParser.parseBundle()`
    의 cg3(트리매니페스트 봉투) 처리가 "루프 첫 반복부터 무조건 경로 청크를 읽는다"는
    잘못된 가정으로 짜여 있었다. 실제 스펙(`changegroup.py`의 `generatemanifests()`:
    `if tree: yield _fileheader(tree)`)은 **루트("") 매니페스트 그룹은 경로 청크 없이
    바로 온다** — 실제 hg가 만든 cg3 바이트를 직접 hexdump/재파싱해 확인. 기존 코드는
    루트 그룹의 첫 델타 엔트리를 통째로 (엉뚱한) 서브디렉터리 경로 이름으로 먹어버리고
    나머지 엔트리만 그 밑에 잘못 붙이는 상태였다 — 즉 **cg3로 받은 매니페스트 리비전이
    항상 최소 1개 누락된 채로 misfiled됐다**. cg3/cg4/cg5 공통 구조라 세 버전 모두
    같이 고침(먼저 bare 루트 그룹을 파싱한 뒤 선택적 서브디렉터리 그룹 루프). 기존
    회귀에서 이 경로가 걸리지 않았던 이유: 지금까지 real-hg-interop 테스트들은 전부
    필로그 콘텐츠만 직접 검증했지(`HgRemoteAndSyncTest#testNativeHgCopyRenamePull`
    등) cg3 매니페스트 그룹 자체를 pull 후 검증한 테스트가 없었고, 게다가 위 bundlecaps
    버그로 그 테스트들조차 실제로는 대부분 bundle1(cg1, 매니페스트 그룹 구조 자체가
    다름)로 통신하고 있었다.

    **부수 발견 3 — 이 항목의 원래 "실사용 위험도" 서술 정정**: 위 배경 요약의
    "`supportedincomingversions()`가 cg4를 기본 필터링하니 기본 설정 저장소끼리는
    cg3로 자동 폴백"이라는 판단은 **push(unbundle 받는 방향)에만 해당**한다.
    pull/getbundle(서버가 클라이언트에게 "보내는" 방향)은 `changegroup.
    supportedoutgoingversions()`가 쓰이는데, 이건 treemanifest/narrow/lfs 저장소가
    아닌 한 **cg4를 설정 없이 무조건 포함**한다 — `experimental.changegroup4`도
    `delta-info-revlog` requirement도 필요 없다. 위 두 버그(bundlecaps 인코딩 +
    루트 매니페스트)를 고친 뒤 로컬 hg 7.2로 실측 재확인(`GET ?cmd=getbundle`을
    직접 만들어 응답을 hexdump/zlib로 대조): 기본 설정(아무 config도 안 준) real hg
    저장소를 그냥 pull만 해도 서버가 **cg3가 아니라 cg4를 고른다**(`version04`
    응답 확인). 즉 cg4 지원은 "당장 안 깨지는 opt-in 확장"이 아니라 **이 세션의
    수정 이후 기본 pull 경로에서 바로 쓰이는 실사용 코드**다 — cg5는 여전히
    `experimental.changegroup5`/revlogv2/changelogv2가 있어야 광고되므로 원래
    서술대로 opt-in에 가깝다.

    **테스트**: `ChangegroupV4V5Test`(신설, 6건, 모두 협소하게 설계) — 실제 hg가
    만든 cg4/cg5 페이로드 파싱 검증 2건, hg4j가 패킹한 cg4/cg5를 실제 hg
    `unbundle`이 정확히 받아들이는지 라운드트립 검증 2건(호스트 native hg 7.2로
    확인, Docker/Rust 불필요), 협상이 최댓값을 고르는지 real hg로 검증 2건
    (`experimental.changegroup5=yes` 켠 서버 → `05`, 기본 설정 서버 → `04`). 이
    작업으로 기존 cg1/cg2/cg3 관련 테스트 3개(`FetchCommandTest`,
    `HgRemoteClientCoverageTest`, `HgRemoteClientTest`)가 구식(버그 기준) bundlecaps
    문자열/cg3 루트-매니페스트 구조를 그대로 하드코딩해서 검증하고 있던 것도 같이
    발견해 실측대로 갱신. 전체 회귀 클린(217개 테스트 클래스, 2230건, failures=0
    errors=0, skipped=8 — Docker/Rust 필요 등 기존에도 스킵되던 것들).

    **남은 gap**: (1) ~~SSH 경로(`HgSshClient`)도 같은 방식으로 bundlecaps 문자열을
    고쳤지만... 실제로 SSH 경로에서 bundle2/cg4/cg5 협상을 켜는지는 검증 못 했다~~
    — ✅ **2026-09-04 확인, 이미 검증돼 있었다**. `HgSshClientRealHgInteropTest#
    getBundleActuallyNegotiatesAgainstARealHgSshServer`가 실제 hg SSH 서버를 상대로
    `assertNotEquals("01", ext.cgVersion, ...)`로 정확히 이걸 검증하고 있고(다른
    세션이 이후 SSH 클라이언트를 실제 hg 바이너리 프로토콜대로 전면 재작성하며 같이
    닫힘), 실행해서 통과 확인함(3/3 GREEN). (2)/(3)은 여전히 미해결 — hg4j가
    SERVER 역할일 때(`HgHttpWireServer`/`HgSshWireServer` → `Wire1Commands.getbundle`
    → `HgLocalClient.getBundle()`)는 여전히 cg1만 생성하고(`getBundle()`의
    `bundleCaps` 파라미터가 시그니처에만 있고 본문에서 전혀 안 쓰임, 2026-09-04
    재확인), cg5를 통해 받은 sidedata도 파싱만 하고(`entry.sidedata`) 로컬 revlog에
    실제로 반영하는 코드가 `api` 패키지 어디에도 없음(2026-09-04 재확인) — 상세는
    백로그 26번.
16. ~~**Bundle1 writer 독립 클래스 없음**~~ — ✅ **완료(2026-09-03)**. 진단해보니
    `api.BundleCommand`(직전 세션에 이미 신설)는 `none-v1`(`"HG10UN"` + 무압축 cg1)만
    만들 수 있었고, 실제 gap은 서술 그대로 `--type` 파라미터/gzip·bzip2 압축
    부재였다. `BundleCommand.BundleType`(`NONE_V1`/`GZIP_V1`/`BZIP2_V1`,
    `setType(BundleType)`/`setType(String)`) 추가로 해결 — `gzip-v1`은 순수
    zlib/DEFLATE(`java.util.zip.Deflater` 기본 wrapped 모드, `GZIPOutputStream`이
    아님), `bzip2-v1`은 4바이트 리터럴 `"HG10"` + 표준 bzip2 스트림
    (`mercurial/bundle2.py`의 `bundletypes["HG10BZ"] = ("HG10", "BZ")` 그대로).
    실제 hg 7.2.2 CLI로 세 방식 모두 양방향 round-trip 검증(`BundleCommandTest`
    신규 3건). 상세는 위 gap table "Bundle1" 행 참고. 로컬 시스템 hg만으로 검증,
    Rust/Docker 불필요했음.
26. ~~**hg4j 자체 changegroup 생성/적용 경로가 cg1/무sidedata로 제한됨**~~ —
    ✅ **완료(2026-09-04)**. 백로그 11번 "남은 gap"에 번호 없이 있던 것을 메인
    에이전트가 직접 재확인 후 승격, 사용자 확인 후 두 부분(생성 쪽 버전 협상 +
    적용 쪽 sidedata 반영) 모두 완전히 구현.

    **1부(생성 쪽 버전 협상)**: `HgLocalClient.getBundle()`이 실제로
    `bundleCaps`를 읽어 협상하도록 배선. 실측(mercurial/exchange.py,
    2026-09-04): 클라이언트가 `bundlecaps`에 `"HG2"`로 시작하는 토큰을 하나도
    안 보내면(legacy) 버전은 무조건 `"01"`이고 응답도 봉투 없이 맨 cg1
    바이트 그대로 — 이게 바로 이 백로그의 근본 원인이었다: `Wire1Commands.
    capabilitiesString()`이 `bundle2=` 토큰을 전혀 광고하지 않았기 때문에, 실제
    hg 클라이언트의 `remote.capable('bundle2')`가 항상 false가 되어 legacy
    경로(`_pullchangeset`)로만 빠졌고, 그 경로는 `bundlecaps` 인자 자체를 아예
    안 보낸다(hg4j `getbundle` 핸들러가 뭘 하든 무관하게 cg1 확정) — 실제로
    `Wire1Commands.getbundle`에 임시 로깅을 심어 real `hg clone` 요청을 직접
    캡처해 확인. 그래서 `capabilitiesString()`에 `Bundle2Parser.
    buildBundle2CapsToken("01,02,03,04,05")`를 추가해 bundle2 자체를 광고하게
    고쳤고, 그 결과 실제 hg 7.2 클라이언트가 `_pullbundle2` 경로로 넘어가
    자신의 기본 `changegroup=01,02,03` 목록(cg4/cg5는 클라이언트가 설정 없이는
    절대 광고 안 함)을 `bundle2=<blob>` 안에 실어 보내는 것도 그대로 캡처
    확인. `HgLocalClient.getBundle()`은 이제 `Bundle2Parser.
    requestsBundle2()`/`decodeChangegroupVersions()`(신설, `urlutil.
    b2_caps_from_bundle_caps`/`decode_b2_caps` 실측 이식)로 이 값을 읽어
    `max(요청 목록 ∩ {01..05})`를 고르고(교집합이 비었거나 bundle2 미요청이면
    실제 hg와 동일하게 `"01"`), `ChangegroupParser.writeBundle`을 그 버전으로
    직접 호출해 패킹한다 — `writeEntry`/`writeBundle`는 기존 cg4/cg5 전용이던
    걸 cg1/cg2/cg3 헤더 레이아웃(각각 80/100/102바이트, `parseGroup`의 읽기
    쪽과 대칭)까지 지원하도록 확장하고, `writeBundle`의 `manifestsend` 종료
    청크도 "버전이 tree-capable(03+)인가"로 올바르게 분기하도록 고쳤다(예전엔
    무조건 붙여서 cg1/cg2에 쓰면 스트림이 깨졌을 버그). 응답은 bundle2
    요청이었으면 항상 `Bundle2Parser.wrapChangegroupInBundle2`로 HG20 봉투에
    감싸고, 아니면 기존 `"HG10UN"` 관례를 그대로 유지.

    **부수적으로 드러난 필수 작업(같은 캡ability 플래그 하나가 양방향을 다
    좌우하는 real hg 자신의 설계 때문에 회피 불가능했음, `exchange.
    _forcebundle1`)**: `bundle2=`를 광고하자 실제 hg 클라이언트의 **push**도
    자동으로 bundle2 프로토콜로 전환돼(`_pushbundle2`) body가 맨 cg 바이트가
    아니라 HG20 봉투가 되고, 응답도 HG20 봉투([reply:changegroup]/[error:abort]
    파트)여야만 했다 — 이 배선 없이 광고만 켰더니 기존
    `HgHttpWireServerRealHgInteropTest`의 push 테스트들이 즉시 "abort: not a
    Mercurial bundle"로 재현됐다(2026-09-04 직접 확인). `HgLocalClient.
    pushWithHooks()`가 HG20 요청을 `Bundle2Parser.extractChangegroupDetailed`
    로 언랩하도록, `Wire1Commands.unbundle()`이 요청이 bundle2였으면 응답도
    `Bundle2Parser.buildChangegroupReplyBundle2`/`buildEmptyBundle2Reply`/
    `buildErrorAbortBundle2`(전부 신설, `bundle2_part_handlers.
    handlechangegroup`/`wireprotov1server.unbundle`의 예외 처리 실측 이식)로
    만들도록 고쳤다. HTTP는 real hg의 `streamreslegacy`(비압축)와 `streamres`
    (압축)가 다른 처리라는 것도 실측으로 확인해 `Wire1Response`에 `STREAM_
    UNCOMPRESSED` 종류를 신설(`HgHttpWireServer`는 비압축으로, `HgSshWireServer`
    는 기존 `STREAM`과 동일하게 무프레이밍으로 처리 — SSH엔 애초에 압축
    구분이 없음).

    **2부(적용 쪽 sidedata 반영)**: `Revlog.appendChangeGroupEntry()`가
    `index.isV2()`를 전혀 확인하지 않고 항상 v1 전용 수동 바이트 라이팅
    경로(64바이트 레코드)로 썼던 것을 고쳐, v2 revlog(가장 흔하게는
    `exp-copies-sidedata-changeset`가 켜진 changelog)에는 기존
    `appendRevisionV2`(로컬 커밋용으로 이미 있던 메서드, 96바이트 레코드 +
    `.sda` 반영)를 그대로 재사용하도록 분기 추가 — `entry.sidedata`(cg5의
    `CG_FLAG_SIDEDATA`로 온, 이미 `SidedataCodec`이 쓰는 것과 같은 포맷의
    원시 컨테이너 바이트)를 그대로 넘기면 로컬 커밋이 만드는 것과 동일한
    온디스크 상태가 된다. 이건 sidedata 문제만이 아니라 더 넓은 사전 존재
    버그였다: 이 분기가 없었던 예전 코드는 v2 revlog에 pull/push로 들어오는
    아무 리비전이나 다 v1 레이아웃으로 깨뜨렸을 것이다(사이드 이펙트로 sidedata도
    통째로 버려짐). 대칭으로, `HgLocalClient.getBundle()`도 이제 cg5로 패킹할
    때 소스 저장소가 `isSidedataCopies()`면 changelog 엔트리에 `SD_FILES`
    sidedata를 실어 보내도록(있으면) 배선해 생성 쪽도 손실 없이 왕복되게 했다.

    **검증(전부 real hg CLI 기반, hg4j-내부 왕복만으로는 불충분하다는 지시에
    따름)**:
    - `HgHttpWireServerRealHgInteropTest`/`HgSshWireServerRealHgInteropTest`의
      기존 clone/pull/push 테스트 전부 그대로 통과(위 부수 작업 포함 회귀
      없음).
    - 신규 `realHgCloneWithDefaultCapabilitiesNegotiatesAboveCg1OnTheWire`
      (`HgHttpWireServerRealHgInteropTest`): 실제 `hg clone`이 기본 설정
      그대로 hg4j 서버에 요청할 때, `HttpExchange`를 얇게 감싸(프로덕션 코드
      변경 없이) 실제 `?cmd=getbundle` 응답 바이트를 그대로 캡처 → inflate →
      `Bundle2Parser.extractChangegroupDetailed`로 봉투 안 `CHANGEGROUP` 파트의
      `version` 파라미터를 직접 읽어 `"01"`이 아님을 확인(실측: 실제 hg 7.2
      클라이언트의 기본 `changegroup=01,02,03` 목록과 hg4j의 협상 로직이 만나
      `"03"` 선택).
    - 신규 `PullSidedataRealHgInteropTest`(part 2 전용): 소스/대상 저장소 둘 다
      `hg --config format.exp-use-copies-side-data-changeset=yes init`으로
      부트스트랩(hg4j가 아직 이 포맷을 처음부터 만들진 못하는, 백로그 19에서도
      이미 문서화된 별개 gap이라 real hg로 부트스트랩만 하고 커밋은 전부
      hg4j가 함 — `SidedataFilesWriteTest`와 동일 패턴), hg4j `CommitCommand`로
      rename(`a.txt`→`b.txt`)+신규 파일(`c.txt`) 커밋 → `FetchCommand`가 실제
      pull에 쓰는 것과 동일한 `bundleCaps`로 `HgLocalClient.getBundle()` 직접
      호출(hg4j↔hg4j HTTP는 `HgRemoteClient`가 wireprotocol v2로 자동 승급해
      버려 이 백로그가 다루는 v1/cg5 경로 자체를 안 타므로 의도적으로 우회) →
      `05` 협상 확인 → `entry.sidedata` 존재 확인 → `FetchCommand.applyBundle`
      로 적용 → 적용된 저장소에서 `SidedataChangedFilesCommand`로 소스와 동일한
      added/removed/copiedFromP1(`b.txt`←`a.txt`)을 읽어냄을 확인 → 마지막으로
      `hg debugchangedfiles 1`/`hg verify`를 적용된 저장소에 대해 직접 돌려
      real hg 자신도 동의함을 확인(hg4j 자체 리더와의 자기정합성이 아니라
      스펙 정합성 검증).
    - 전체 회귀 스위트 2402 테스트, 실패/에러 0(스킵 10 — 도커 기반 인터롭
      테스트 등 환경 의존, 기존과 동일).

    **발견했지만 이 백로그 범위 밖으로 남겨둔 것**: real hg 자신의 wireprotocol
    v1 `getbundle` 서버 구현은 `remote_sidedata`를 아예 안 넘겨서(2026-09-04
    `wireprotov1server.py`/`exchange.py` 실측) **실제 hg를 서버로 한 일반
    wire `getbundle` 요청에서는 cg5여도 SD_FILES가 전송되지 않는 것으로
    보인다** — 이번 검증에서 hg4j↔hg4j 대신 real-hg-bootstrap-only 시나리오를
    쓴 이유이기도 함. hg4j 쪽 생성/적용 배선은 모두 완료됐으므로, 실제 hg
    자신의 이 제약이 풀리거나 다른 소스(예: hg4j가 만든 cg5)가 쓰이면 그대로
    작동한다.

~~27. **`hg log --follow`/annotate가 sidedata 기반 copy-tracing과 연동되지 않음 —
    신규, 2026-09-04 발견(백로그 17번 "남은 gap"에 번호 없이 있던 것을 메인
    에이전트가 직접 재확인 후 승격), 미착수. 백로그 23번 완료 후 즉시 진행.**~~
    ✅ **완료(2026-09-04)**.

    **조사 경과 — 사용자가 지정한 "sidedata로 보강"이라는 전제가 실제로는
    틀렸음을 확인**: 실제 `hg`(7.2) 소스(`mercurial/copies.py`
    `usechangesetcentricalgo()`, `mercurial/filelog.py` `renamed()`,
    `mercurial/context.py` `filectx._copied`)를 직접 읽고, 로컬 시스템 `hg`로
    평범한 `hg init` 저장소를 만들어 `hg debugformat`을 찍어 확인한 결과
    (`copies-sdc: no`, `changelog-v2: no`) — **real hg의 `--follow`/`annotate`는
    기본적으로 changelog sidedata(`SD_FILES`, 백로그 17/19번)를 전혀 읽지 않는다.**
    `usechangesetcentricalgo()`는 저장소가 `format.use-changelog-v2`+
    `exp-copies-sidedata-changeset` requirement로 명시적으로 만들어진 경우에만
    참이 되고, 평범한 `hg init` 저장소(이번 검증에 쓴 것 포함, 사실상 실사용
    중인 거의 모든 저장소)는 항상 거짓이다. 실제로 쓰이는 기본 메커니즘은 **파일로그
    수준의 `copy`/`copyrev` 메타데이터**(`filelog.renamed()`) — 각 filelog
    리비전 데이터 앞에 붙는 `\x01\n...\x01\n` 메타데이터 헤더에 저장되며,
    rename/copy 대상 파일은 항상 새 filelog를 리비전 0부터 시작하므로(부모
    파일리비전과 내용이 무관하다고 보고 델타를 안 만듦) 이 메타데이터는 항상
    그 filelog의 리비전 0에만 존재한다. 다행히 **이 계층은 hg4j에 이미 절반
    구현돼 있었다** — `CommitCommand`(551-556행 근처)가 `dirstate.getCopyMap()`을
    보고 커밋 시 이미 `copy`/`copyrev` 메타데이터를 filelog에 쓰고 있었고(백로그
    17/19번보다 먼저 존재), `storage.Revlog.getRevisionMetadata(rev)`도 이미
    그 헤더를 파싱해 되돌려주고 있었다 — 다만 `api` 패키지의 그 무엇도 그
    reader를 소비하지 않고 있었다(이번에 직접 확인). 즉 실제 gap은 "sidedata
    연동 부재"가 아니라 "이미 존재하는 filelog 메타데이터 reader를
    `LogCommand`/`AnnotateCommand`가 안 쓰고 있었다"였다 — 사용자에게 이
    발견을 있는 그대로 보고하고, 사용자 지시대로 "실제 hg가 안 쓰는 계층을
    억지로 sidedata 기반으로 구현"하지 않고 real hg와 실제로 일치하는 filelog
    메커니즘으로 구현했다.

    **구현**: (1) `LogCommand.setFollowPath(String path)` 신규(빌더 패턴,
    `setFollowAncestors(true)`를 암묵적으로 켬) — 지정한 경로의 filelog를
    조회해 그 리비전들의 linkRev가 시작 리비전(옵션 미지정 시 tip)의 조상
    집합(`ChangesetGraph.getAllAncestors`) 안에 있는 것만 모으고, 그 filelog의
    리비전 0의 linkRev까지 조상 범위 안에 들어오면(= 실제로 그 파일의 origin까지
    거슬러 올라갔으면) 리비전 0의 `copy` 메타데이터를 읽어 이전 경로로 갈아타
    반복 — 이렇게 모은 리비전 집합을 새 `computeFollowPathRevs()` 헬퍼로 계산해
    `call()`의 기존 `allowedRevs` 필터링 경로에 그대로 꽂았다(기존
    `followAncestors`용 필터링 로직과 나란히 배치, 서로 배타적). (2)
    `AnnotateCommand`는 내부적으로 `(path, targetRev)`별 순수 재귀 헬퍼
    `traceLines()`/`tryCrossRenameBoundary()`로 리팩터링 — 기존엔 "origin
    리비전"을 **현재 filelog 안의 리비전 인덱스**로만 추적하다 맨 마지막에
    한 번 linkRev로 변환했는데, 그 방식은 베이스라인이 다른 filelog(rename
    source)에서 온 경우 표현이 불가능했다. 새 방식은 "origin changelog linkRev"를
    처음부터 끝까지 직접 들고 다니고, 파일 리비전 0의 `copy` 메타데이터가
    있으면 그 `copyrev`가 가리키는 정확한 소스 filelog 리비전으로 재귀
    호출해 그 결과를 베이스라인으로 삼은 뒤 나머지는 기존 LCS 기반 forward
    diff 알고리즘을 그대로 재사용한다 — 별도 `--follow` 플래그 없이 항상 이렇게
    동작(실제 `hg annotate`에 그런 플래그가 없는 것과 동일).

    **검증**: 실제 `hg` 7.2 CLI로 `add old.txt` → 커밋 → `hg mv old.txt new.txt`
    → 커밋 → `new.txt` 내용 수정 → 커밋 시나리오를 만들어 오라클로 사용.
    `hg log --follow new.txt --template "{rev} {desc}\n"`은 `2 modify new.txt`/
    `1 rename to new.txt`/`0 add old.txt` 세 리비전 전부(rename 이전 리비전
    포함)를 반환했고, `hg annotate -u new.txt`는 rename에서 살아남은 두 줄을
    rename 전 커미터(Alice)에게 정확히 귀속시켰다(`Alice: line1`/`Alice: line2`/
    `Carol: line3`) — hg4j의 동일 시나리오(`LogCommandTest.followPathCrossesRenameBoundaryToOldPath`,
    `AnnotateCommandCoverageTest.annotateFollowsRenameBoundaryToOriginalAuthor`)가
    바이트 단위로 이 출력과 일치함을 확인. `hg copy`(rename 아닌 순수 copy)
    경계도 별도 케이스로 검증(`annotateFollowsCopyBoundaryButLeavesOriginalUntouched`
    — 복사본은 원본 커미터로 귀속되고, 원본 파일 자신의 annotate는 영향받지
    않음). rename 없는 평범한 `--follow <path>`(`followPathWithoutRenameBehavesLikePlainFollow`)와
    존재한 적 없는 경로(`followPathOnNeverExistingPathReturnsEmpty`)도 커버.
    전체 회귀 2405 테스트, 실패 0.

    **알려진 스코프 한계(문서화, 정확성 결함 아님)**: 이 구현은 rename 목적지의
    filelog 리비전 0(=그 filelog의 유일한 origin, 실제 hg의 `filelog.renamed()`가
    지원하는 정확히 그 범위)에서만 copy 경계를 확인한다 — 이는 "출발 경로가
    같은 조상 라인 안에서 한 번 이상 재사용된" 병적인 케이스(예: 나중에 무관한
    커밋이 같은 옛 경로명을 다시 만드는 경우)까지 완벽하게 `copyrev` 노드
    단위로 구분하지는 않는다(대신 조상 집합 필터링에 의존). 실제 hg의
    `_tracefile`/`_fullcopytracing`이 다루는 병합 커밋의 양쪽 부모 서로 다른
    copy 등 더 복잡한 케이스도 이번 스코프 밖.

