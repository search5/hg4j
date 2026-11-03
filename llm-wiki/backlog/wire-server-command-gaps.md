---
updated: 2026-11-03
status: current (B8, B3, B1, B2, B4, B9 수정 완료(높음 4개 모두). 나머지 진행 중 - 아래 표의 상태 열 참고)
---

# 서버 와이어 명령 점검: 원본 Mercurial 대비 누락과 차이

[[implementation-plan-gpg-ssh-wire]] Phase 3의 결과. hg4j 서버(`HgSshWireServer`, `HgHttpWireServer`, 공유 코어
`Wire1Commands`)를 원본 Mercurial 7.2의 `mercurial/wireprotov1server.py`와 대조했다.

## 방법과 근거의 종류 (읽기와 실측을 구분한다)
1. **원본 등록부 읽기**: `wireprotov1server.py`의 `@wireprotocommand` 23개와 `_capabilities()`.
2. **와이어 대조(실측)**: 같은 요청을 실제 `hg -R <repo> serve --stdio`와 `HgSshWireServer`에 보내 응답 바이트를 비교
   (`WireServerParityInteropTest`의 `ask`).
3. **실제 클라이언트 대조(실측)**: 실제 `hg` 클라이언트를 `--ssh <스크립트>`로 두 서버(원본, hg4j)에 각각 붙여 같은 작업을
   하고 결과(변경셋의 단계, 오류 메시지)를 비교.
4. 원본 클라이언트 소스에서 각 명령을 **언제 호출하는지** 확인(파일:줄 표기). 이것은 읽기이며 실행 확인이 아니다.
`grep`으로 이름이 없다는 것만으로는 "미구현"이라 하지 않았다(AGENTS 규칙 7): 위 2, 3으로 동작을 확인했다.

범위 밖: wire protocol **v2**는 대조하지 못했다. 이 hg 소스(7.2)에는 `wireprotov2server.py`가 없다(v2 서버가 제거된
버전). hg4j의 `Wire2Commands`는 [[wireprotocol-v2-support-plan]]의 영역이다.

## 결함 (실측으로 확인됨)

| ID | 상태 | 증상 | 실제 클라이언트에서 보이는 결과 | 원인(확인된 범위) | 우선순위 |
|---|---|---|---|---|---|
| **B8** | **수정됨** (4b24d10) | `getbundle`이 `heads`를 반영하지 않음. `heads=rev1`과 `heads=rev3`의 응답이 같은 크기(2157B) | `hg clone -r 1`이 리비전 0~1이 아니라 **전체 5개**를 받음 | `Wire1Commands.getbundle`은 `heads`를 `HgLocalClient.getBundle`에 넘기지만 bundle2 경로에서 효과가 없다. 정확한 지점은 미조사 | **높음** |
| **B3** | **수정됨** | `listkeys phases`가 모든 변경셋을 draft(1)로 나열하고 `publishing True`를 보내지 않음. 원본은 draft **루트**만 + `publishing True` | hg4j에서 `clone`한 변경셋이 **전부 `draft`**. 원본 서버에서는 전부 `public` | `Wire2Commands.readListKeys`의 phases 구현. 서버 저장소의 publishing 여부 판단이 없음 | **높음** |
| **B1** | **수정됨** | `lookup`이 북마크, 태그, 브랜치 이름을 풀지 못함(`1 <hex>` 대신 `0 unknown revision`). 16진수, `tip`은 정상 | `clone -r bm1`, `-r v1`, `-r default`, `-r feature` 모두 `abort: unknown revision` | `NodeIdUtil.resolveRevision`이 이름 공간(bookmark/tag/branch)을 모름 | **높음** |
| **B2** | **수정됨** | `listkeys namespaces`가 빈 응답. 원본은 `bookmarks`, `namespaces`, `phases` | `hg incoming -B`/`outgoing -B`가 `remote doesn't support bookmarks`. 원본 클라이언트는 `commands.py:4015`, `:4670`, `:3555`, `:7034`와 push 시 obsolete 확인(`exchange.py:775`)에서 이 목록을 본다 | `readListKeys`가 `namespaces`를 처리하지 않음 | 중간 |
| B4 | **수정됨** | `branchmap`, `listkeys bookmarks` 응답 끝에 개행이 하나 더 있음(원본은 없음) | 실제 클라이언트는 줄 단위로 파싱해 영향이 보이지 않음(기존 clone/pull interop 통과) | 응답 조립 방식 | 낮음 |
| B5 | **수정됨** | `between`이 서로 다른 두 노드에 대해 중간 노드를 주지 않음(`1\n\n`). 원본은 1, 2, 4, 8 간격의 이분 탐색 목록 | 구형 discovery 클라이언트에서만 의미(현대 클라이언트는 `heads`/`known`). 핸드셰이크용 null-null 쌍은 동일 | 소스에 "실제 이분 탐색은 구현하지 않았다"고 적혀 있음 | 낮음 |
| B6 | **수정됨** | `branches` 명령 없음 | 구형 discovery에서만 사용 | 미구현 | 낮음 |
| B7 | **수정됨(SSH)** | 지원하지 않는 명령에 원본은 **빈 응답**(`0\n`)을 주고 계속하는데, hg4j는 `unsupported command: x` 텍스트를 응답 본문으로 보냄 | 광고하지 않은 명령을 호출하는 클라이언트가 오류 텍스트를 데이터로 읽음. 인자 줄도 별도 명령으로 오해해 오류가 연쇄됨 | `Wire1Response.oobError` 사용 | 중간 |
| B9 | **수정됨** | 헤드가 둘 이상이면 `heads` 순서가 원본과 다름(집합은 같음) | 순서에 의존하는 출력에서만 차이. 헤드가 하나면 동일 | `HgLocalClient#getHeads` 정렬 | 낮음 |

### 수정하면서 추가로 드러난 것
- **B10 (수정됨)**: `branchmap`이 **현재 브랜치 하나**에 저장소의 모든 헤드를 붙여 내보냈다(브랜치가 둘이면 틀림). 이제
  `HgLocalClient#getBranchHeads`로 브랜치별 헤드를 내보내며 원본과 바이트까지 같다(브랜치 2개 저장소로 확인).
- **B11 (수정됨)**: 빈 저장소의 `heads`가 널 노드(`000...0\n`) 대신 `\n`을 줬다. 원본은 널 노드를 준다.
- **hg4j 클라이언트 쪽 한계(미수정)**: `FetchCommand`는 원격이 `publishing`이어도 가져온 변경셋을 public으로 만들지 않고
  전부 draft로 둔다(원본 `exchange._pullphase`는 publishing이면 public). 서버가 이제 `publishing`을 보내므로 클라이언트가 그
  키를 건너뛰도록만 방어했다(`Integer.parseInt("True")` 예외로 단계 동기화 전체가 중단되던 문제를 방지).
- **기존 불안정 테스트**: `PushLockRaceRealHgInteropTest`는 변경 이전의 깨끗한 체크아웃에서도 6회 중 3회 실패했다. 두 실제
  `hg push`를 동시에 출발시키므로 패자의 거부 문구가 "changed while pushing"(서버 재검증)일 수도, "push creates new remote
  head"(클라이언트가 이미 새 head를 봄, 원본과 같은 문구)일 수도 있는데 앞의 것만 허용했기 때문이다. 둘 다 정당한 거부이므로
  둘 다 허용하도록 고쳤다(성공은 정확히 1건, `hg verify` 통과 등 핵심 단언은 그대로).

각 결함에 대응하는 **실패 테스트가 `@Disabled`로 준비**되어 있다(`WireServerParityInteropTest`). 수정할 때 해당 항목의
`@Disabled`를 풀면 빨강 → 초록이다. 현재 통과하는 항목(`heads` 집합, `known`, `lookup`의 16진수/`tip`/없는 이름,
`listkeys` 없는 네임스페이스, `clonebundles`(없을 때), `between` null-null, 기본 clone의 변경셋 수)은 일반 테스트로 고정했다.

## 명령별 현황 (원본 23개)
| 명령 | 원본 위치 | hg4j | 비고 |
|---|---|---|---|
| batch | :207 | 구현 | 이번에 대조하지 않음(기존 테스트가 다룸) |
| between | :253 | 구현(껍데기) | B5 |
| branchmap | :263 | 구현 | B4 |
| branches | :276 | 구현 | B6 수정됨 (병합 이력 포함 원본과 바이트 동일) |
| get_cached_bundle_inline | :286 | 없음 | 클라이언트는 `peer-bundle-cache://` 항목이 있을 때만 호출(`exchange.py:3148`). hg4j 매니페스트에는 없음. 영향 없음 |
| clonebundles | :339 | 구현 | 매니페스트 없을 때 동일 |
| clonebundles_manifest | :361 | 없음 | 클라이언트는 서버가 `clonebundles_manifest` capability를 광고할 때만 호출(`wireprotov1peer.py:361`). hg4j는 광고 안 함. 영향 없음 |
| store_shape | :390 | 없음 | narrow shapes(Rust, experimental) capability 광고 시에만. 영향 없음 |
| capabilities / hello | :503 / :708 | 구현 | 아래 capability 비교 |
| changegroup / changegroupsubset | :509 / :520 | 구현 | 구형 경로. 대조하지 않음 |
| debugwireargs | :530 | 구현 | 원본과 바이트 동일(`three`/`four` 선택 인자 포함). 광고할 capability는 없음 |
| getbundle | :609 | 구현 | **B8** |
| heads | :688 | 구현 | B9 |
| heads_buckets | :694 | 없음 | `experimental.disco.heads-fingerprints` capability를 광고할 때만(`setdiscovery.py:438`). 영향 없음 |
| listkeys | :739 | 구현(부분) | B2, B3, B4 |
| lookup | :745 | 구현(부분) | **B1** |
| known | :758 | 구현 | 동일 |
| protocaps | :767 | 구현(SSH만) | 원본 서버는 SSH에서 광고하지만(`wireprotoserver.py:455`) 이 소스의 `sshpeer.py`/`wireprotov1peer.py`에는 호출이 없다. hg4j도 SSH capabilities에 `protocaps`를 광고하고 `OK`로 응답한다(값은 저장하지 않음) |
| pushkey | :774 | 구현 | 기존 테스트가 다룸 |
| stream_out | :803 | 없음 | 서버가 `stream` capability를 광고할 때만 호출(`streamclone.py:273`). hg4j는 광고 안 함 → 스트리밍 클론이 없을 뿐 clone은 `getbundle`로 동작. 성능 이점만 없음 |
| unbundle | :812 | 구현 | 기존 테스트가 다룸 |

즉 "이름이 없는" 8개(branches, get_cached_bundle_inline, clonebundles_manifest, store_shape, debugwireargs, heads_buckets,
protocaps, stream_out) 중 **실제로 문제가 되는 것은 구형 discovery용 `branches` 하나뿐**이고(B6, 낮음), 나머지는 서버가
광고하지 않으므로 클라이언트가 부르지 않는다. 더 큰 문제는 구현은 되어 있으나 동작이 다른 B1, B2, B3, B8이다.

## capability 비교 (기본 설정의 원본 7.2 대 hg4j)
- 원본: `batch branchmap bundle2=<HG20 bookmarks changegroup=01,02,03 checkheads=related delta-compression=none,zlib,zstd
  digests=md5,sha1,sha512 error=abort,unsupportedcontent,pushraced,pushkey hgtagsfnodes listkeys phases=heads pushkey
  remote-changegroup=http,https stream=v2> changegroupsubset getbundle known lookup protocaps pushkey streamreqs=... 
  unbundle=HG10GZ,HG10BZ,HG10UN unbundlehash`
- hg4j: `lookup changegroupsubset branchmap pushkey known getbundle batch httpheader=1024 unbundle=HG10UN,HG10GZ exp-narrow-1
  bundle2=<HG20 changegroup=01,02,03,04,05> [clonebundles]`
- hg4j에 없는 것: `unbundlehash`, `protocaps`, `stream*`, `unbundle`의 `HG10BZ`, bundle2의 `bookmarks`, `checkheads`,
  `delta-compression`, `digests`, `error`, `hgtagsfnodes`, `listkeys`, `phases`, `pushkey`, `remote-changegroup`, `stream`.
  bundle2의 `listkeys`/`phases`/`bookmarks`를 광고하지 않아 클라이언트는 이 정보를 bundle2 부분이 아닌 별도 `listkeys`
  호출로 받는다. 그래서 B2, B3가 클라이언트에 그대로 드러난다.
- 원본에 없는데 hg4j가 광고하는 것: `exp-narrow-1`(원본은 narrow 확장을 켰을 때만), `changegroup`의 `04`, `05`.

## 제안 (우선순위 확인 필요)
1. **B8, B3, B1, B2**를 먼저 고친다. 모두 실제 `hg` 클라이언트에서 눈에 띄는 오동작(`clone -r`가 전체 복제, 복제본이 전부 draft,
   이름으로 clone 불가, `incoming -B` 불가)이며, 준비된 실패 테스트로 빨강 → 초록을 확인할 수 있다.
2. 다음으로 B7(미지원 명령 빈 응답), B9, B4, B5, B6.
3. capability로 가려지는 선택 명령(`stream_out`, `heads_buckets`, `store_shape`, `clonebundles_manifest`,
   `get_cached_bundle_inline`, `protocaps`, `debugwireargs`)은 필요가 생길 때까지 만들지 않는다.

## 확인하지 못한 것 (정직한 기록)
- B8의 정확한 코드 지점: `Wire1Commands.getbundle`은 `heads`를 넘기지만 bundle2 경로에서 무시됨을 응답 크기와 실제 클라이언트로
  확인했을 뿐, `HgLocalClient.getBundle`의 어느 줄인지는 읽지 않았다.
- B3가 push, 병합, 후속 `hg phase` 동작에 주는 영향은 확인하지 않았다(clone 직후의 단계만 확인).
- HTTP 서버(`HgHttpWireServer`)는 대조하지 않았다. 명령 디스패치가 같은 `Wire1Commands`를 쓰므로 같은 결함이 있을 것으로
  보이지만 실행해서 확인하지는 않았다.
- `batch`, `pushkey`, `unbundle`, `changegroup*`의 응답은 이번 대조에 넣지 않았다.

## [2026-11-03] 후속 대조: 개발판 명령, HTTP 서버, batch/pushkey/changegroup
검증 기준: 7.2.4 릴리스 오라클. `heads_buckets`와 `store_shape`는 릴리스에 없어서 `../hg` 개발 소스로 별도 빌드한 오라클
(`HG_SOURCE_DIR=<체크아웃> scripts/build-local-hg.sh ~/.cache/hg4j/hg-rust-dev`)과만 비교했다. 그 오라클이 없으면 해당 테스트는 건너뛴다.

해결:
- `heads_buckets`, `store_shape` 구현과 `heads_fingerprints`, `exp-shape-1` 광고. 응답 바이트가 개발판과 같다(SSH `hello`의 버킷 줄은 토큰 집합이 같고
  나열 순서만 다르다). `store_shape`는 hg4j에 narrow "모양" 정의가 없어 항상 SHAPE_NOT_FOUND다. 원본은 두 광고를 설정으로 켜야 하지만(기본 꺼짐)
  hg4j는 기본으로 광고하고 `experimental.disco.heads-fingerprints=false`, `experimental.advertise-shapes=false`로 끈다.
- HTTP 서버(`HttpServerParityInteropTest`): 읽기 명령 13종의 상태, Content-Type, 본문이 실제 `hg serve`와 같다. 달랐던 두 가지를 고쳤다: 알 수 없는 명령은
  HTTP 400 "no such method"(기존: 200 + hg-error), 쓰기 명령(`pushkey`, `unbundle`)의 GET은 405 + `0\npush requires POST request\n`.
  실제 hg 클라이언트의 `clone --stream`도 HTTP로 통과.
- `pushkey`(`applyPushkey`): 실제 `bookmarks.pushbookmark` 규칙으로 맞춤. 현재값이 old 또는 new와 같으면 성공(존재하지 않는 북마크 삭제도 성공), 목표 노드가 없으면
  실패, `@`가 든 이름은 거부. 이전에는 old가 다르면 무조건 실패였다.
- 이 작업에서 내가 만든 회귀 하나를 찾아 고쳤다: 빈 저장소 `heads`가 널 노드를 주게 바꾼 뒤, 실제 hg 클라이언트가 `[nullid]`를 `check:heads`로 되돌려 보내면 서버가
  "remote repository changed while pushing"으로 거부했다(`HgHttpWireServerEmptyRepoRealHgInteropTest`). 비교에서 널 노드를 양쪽에서 제외하고 해시 경로도 빈 저장소를 `[nullid]`로 취급하도록 수정.
- `batch`, `changegroup`, `changegroupsubset`: 응답이 같다. changegroup은 바이트가 아니라 **의미**로 비교한다. 같은 내용을 다른 delta(원본은 revlog에 저장된 delta 재사용, hg4j는 계산)로 인코딩하므로,
  두 응답을 실제 `hg unbundle`로 새 저장소에 적용해 `hg verify`와 이력, manifest가 같음을 확인한다.

## [2026-11-03] capability와 bundle2 부품을 7.2.4에 맞춤
기준은 7.2.4 오라클이다. SSH와 HTTP의 `capabilities` 토큰 집합(bundle2 블록은 줄 단위로 펼쳐서)이 원본과 **정확히 같다**
(`WireServerParityInteropTest#sshCapabilitiesMatchTheOriginal`, `HttpServerParityInteropTest#capabilityTokensMatchTheOriginal`).
hg4j 전용 토큰은 광고하지 않는다: `exp-narrow-1`은 `experimental.narrow`, `heads_fingerprints`는 `experimental.disco.heads-fingerprints`,
`exp-shape-1`은 `experimental.advertise-shapes`를 켰을 때만 붙는다(원본과 같은 조건, 기본 꺼짐). 그 결과 narrow 클론을 받으려면 서버 저장소에
`[experimental] narrow = true`가 있어야 한다(기존 narrow 테스트 3개에 반영).

광고한 토큰마다 뒷받침하는 동작을 구현했다.
- `unbundle=HG10GZ,HG10BZ,HG10UN`: 이전에는 `HG10GZ`를 광고하면서 `HG10UN`만 처리했다(번들1 push가 "Unsupported compression"으로 실패). 이제 GZ와 BZ를 푼다.
- `unbundlehash`: 해시 heads 검사는 이미 있었고, 빈 저장소를 `[nullid]`로 해시하도록 고쳤다.
- bundle2 부품 (`Bundle2` 범용 읽기/쓰기, `Bundle2Pull`, `Bundle2Push`):
  - pull(getbundle): `bookmarks`, `listkeys`, `phase-heads`, `hgtagsfnodes`(캐시에 있는 것만, 계산하지 않음, 원본과 같음), `stream2`, `cg=0`.
    부품 순서와 형식은 `exchange.py`를 따른다. `stream2`의 `cache/` 파일은 보내지 않는다(받는 쪽의 최적화일 뿐).
  - push(unbundle): `check:heads`, `check:updated-heads`, `check:bookmarks`, `check:phases`, `changegroup`, `remote-changegroup`
    (URL에서 받아 크기와 digest 검증, 번들1만), `pushkey`, `bookmarks`, `phase-heads`. 응답 부품은 `reply:changegroup`, `reply:pushkey`,
    오류는 `error:abort`, `error:pushraced`, `error:pushkey`, `error:unsupportedcontent`. 알 수 없는 **필수** 부품은 조용히 건너뛰지 않고 거부한다.
- HTTP: `httpheader`, `httpmediatype=0.1rx,0.1tx,0.2tx`, `compression=<엔진>`(`server.compressionengines`, 기본 zstd,zlib), `httppostargs`(설정 시).
  클라이언트가 `0.2`를 보내면 엔진 이름 헤더가 붙은 `application/mercurial-0.2`로 zstd/zlib/none 응답, 아니면 0.1 zlib.
- `capabilities`는 원본처럼 정렬해서 낸다.

검증: 실제 hg 클라이언트로 clone, pull(북마크 이동과 phase 변화), push(북마크 생성, 이동, 삭제, phase 공개), `clone --stream`
(`Bundle2PartsRealClientInteropTest` 5개), getbundle 부수 부품을 실제 서버와 부품 단위로 비교(공개/비공개 저장소, `cg=0`).

hg4j와 원본이 **다른** 점(숨기지 않고 기록):
- `pushkey`/`bookmarks`/`phase-heads` 부품은 changegroup 적용과 한 트랜잭션이 아니다. 뒤 부품이 실패해도 changegroup은 이미 반영된다
  (원본은 전체 롤백). 검사 부품(`check:*`)은 락 안에서 변경 전에 실행되므로 경합 방지는 같다.
- `bundle2` 블록의 `stream=v2`는 hg4j가 실제로 스트리밍할 수 있는 저장소에서만 광고한다(원본은 설정만 보고 광고하고 요청 시 거부).
- `obsmarkers`, `pushback`, `stream3-exp`는 광고하지 않는다. 원본 기본값도 광고하지 않는다.
- `store_shape`에 빈 이름(`names=`)을 주면 원본은 응답 없이 비정상 종료한다. 그 경우는 흉내 내지 않았다.

## [2026-11-03] 스트림 파일 순서와 unbundle 응답 바이트를 원본과 맞춤
- **`stream_out`, `stream2` 파일 순서**: 원본 `store.walk` 규칙으로 맞췄다. `data/`는 revlog 단위로 묶어 복원된(디코딩된) 이름의 바이트순, 한 revlog 안은
  `.n`, `.nd`, `.d`, 그 밖, `.i`(`_ext_key`), 이어서 (비게시 저장소의 `stream2`는 `phaseroots`,) `00manifest`, `00changelog`. 이전에는 fncache에 적힌 순서였다.
  `fncache`의 이름은 `encodedir` 형태로 저장되어 있으므로 복원해서 보낸다. 대조: 파일이 여러 개인 저장소(하위 디렉터리, 대문자, 긴 이름)에서 순서와 바이트가 같다.
  `stream2`에서 원본이 추가로 보내는 `cache/` 파일은 비교에서 제외한다(hg4j는 보내지 않는다).
- **`unbundle` 응답 바이트**: 실제 hg 클라이언트가 push할 때 보낸 바이트를 녹화해 같은 상태의 두 서버(실제 hg, hg4j)에 재생하고 **세션 전체의 응답**
  (hello, 탐색, listkeys, unbundle 응답)을 바이트 단위로 비교한다(`assertSameSession`). 일치하는 시나리오: 일반 push, 북마크 push, 북마크 이동과 삭제, 새 head 강제 push,
  비게시 서버의 phase 공개, 병합(head 감소), 브랜치 닫기, 경합 거절.
  이 대조로 찾아 고친 차이: ① 오류 부품(`error:pushraced`, `error:abort`, `error:pushkey`, `error:unsupportedcontent`)은 **필수 부품**이고 `message` 등이 필수 파라미터
  (이전에는 선택 부품과 선택 파라미터) ② `reply:changegroup`의 `return`은 head 증감에 따라 `1`, `1+n`, `-1-n`(이전에는 1 고정)
  ③ `known`은 null 노드를 아는 것으로 답한다.

## [2026-11-03] HTTP 세션과 서버 쪽 오류 문구를 원본과 대조
**HTTP 세션 대조** (`HttpSessionParityInteropTest`): 실제 hg 클라이언트와 실제 `hg serve` 사이에 녹화 프록시를 두어 요청을 기록하고, 같은 상태의 실제 `hg serve`와 hg4j에
재생해 모든 응답의 상태, `Content-Type`, 본문(zlib 또는 0.2 엔진을 풀고, bundle2는 changegroup을 뺀 부품 단위)을 비교한다. 일치: clone, pull(새 커밋, 이동한 북마크),
일반 push, 북마크 push, 번들1 push, 경합 거절.
- 찾아 고친 차이: ① HTTP는 서버가 처리 중 출력한 줄을 응답에 싣는다. 번들2는 `output` 부품(부품 처리 중 출력은 `in-reply-to`가 붙고, 트랜잭션 종료 출력은 파라미터 없는 부품으로
  **항상** 마지막에 붙음, 오류 응답에서는 그 앞의 `output`들과 빈 `output` 하나), 번들1은 `"<결과>\n<출력>"`. 내용은 `adding changesets`, `adding manifests`, `adding file changes`,
  `added N changesets with M changes to K files (+n heads)`. 클라이언트에는 `remote: ...`로 보인다. SSH에서는 서버 stderr로 가므로 응답에 넣지 않는다.
  ② 요청에 `heads`가 없을 때 `hgtagsfnodes`의 head 순서는 `repo.heads()`(최신 우선).
- 비교에서 **순서를 보지 않는** 것: `hgtagsfnodes` 쌍. 원본은 같은 요청에도 실행마다 쌍 순서가 달라진다(집합 순회로 보임). 흉내 낼 규칙이 없어 쌍의 집합으로 비교한다.

**서버 쪽 오류 문구 대조** (`UnbundleErrorParityInteropTest` 15개, `assertSameSession`의 훅 시나리오): 직접 만든 bundle2를 두 서버에 보내 응답 바이트와 이후 상태를 비교한다.
일치: 알 수 없는 필수/선택 부품, 분기된 북마크, `bookmarks` 부품의 생성/이동/삭제, 필수/선택 `pushkey`(replycaps 유무), `check:bookmarks`(세 문구), `check:phases`, `check:heads`,
`check:updated-heads`, `phase-heads`, 깨진 changegroup(`stream ended unexpectedly (got N bytes, expected M)`), 잘린 번들, bundle2가 아닌 본문, 훅 거절(`prechangegroup hook failed`,
번들2와 번들1, `prepushkey hook failed`).
- 찾아 고친 차이: 번들1 경로는 거절 문구를 응답에 싣지 않고(서버 stderr) 결과 코드 0만 준다. 경합은 `pusherr`로 문구와 함께: 페이로드를 읽기 전에 `repository changed while preparing
  changes - please try again`, 락 안에서 `... while uploading changes ...`(둘 다 `remote`가 없음). cg1의 delta base는 그룹의 직전 청크(첫 청크는 p1)여야 하는데 로컬 revlog의 직전
  리비전으로 가정하고 있어 push된 커밋의 부모가 서버의 마지막 리비전이 아닐 때 번들1 push가 깨졌다(`HG10GZ`/`HG10BZ` 처리를 넣으면서 드러남). 게시형(publishing) 서버가 받은
  변경셋을 public으로 올리지 않던 것, `pushkey`의 `phases` 네임스페이스(`pushphase`)가 없던 것, 알 수 없는 changegroup 버전을 조용히 받던 것도 고쳤다.
  변경 전에 가능한 거절(훅, 필수 `pushkey`의 비교 실패, 분기된 북마크)을 changegroup 적용 **전에** 검사하므로, 이런 거절 뒤에는 원본처럼 아무것도 반영되지 않는다.

hg4j와 원본이 **여전히 다른** 점:
- `bookmarks` 부품이 저장소에 없는 노드를 가리키면 원본은 그대로 저장하지만 hg4j는 거절한다(의도적: 존재하지 않는 리비전을 가리키는 북마크를 만들지 않는다).
- 알 수 없는 changegroup 버전: 원본은 내부 오류로 연결이 끊기고 hg4j는 `error:abort`로 거절한다.
- 훅은 `hgrc`의 `[hooks]`가 아니라 Java 콜백(`HgHook`)이다. 문구는 원본의 `<이름> hook failed`에 맞췄지만 `pretxn*`/`prepushkey`가 트랜잭션 안에서 실패할 때 원본이 덧붙이는
  `transaction abort!`, `rollback completed` 출력은 보내지 않는다(hg4j에는 이 부품들에 걸친 트랜잭션이 없다).
- 변경 뒤에 실패하는 경우(예: changegroup은 적용됐는데 뒤 부품이 파일 쓰기에서 실패)는 여전히 한 트랜잭션이 아니다.
