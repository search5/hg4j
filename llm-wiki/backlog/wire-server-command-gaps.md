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
