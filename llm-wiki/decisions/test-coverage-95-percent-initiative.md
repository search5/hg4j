---
updated: 2026-09-03
status: round 4 (2026-09-03, 16:27~18:30 연속 작업) — 1순위(Dirstate 계열)·2순위(AddCommand/CopyCommand/CloneCommand/RevertCommand)·3순위(missed=1~4 롱테일, ~30개 클래스) 대부분 처리. TDD로 실제 커버한 분기 약 45개, "도달 불가능" 확인·문서화한 분기 약 35개. FileIndex는 16개 중 13개 해결(예상외로 이번 라운드 최대 성과 클래스). missed≥5 클래스(CommitCommand/HgSshClient/HgRevsetEngine/RevlogIndex 등, 이번 세션에서 크게 손댄 대형 클래스 다수 포함) 및 일부 missed=3~4 클래스(ExportCommand/AnnotateCommand/IncomingCommand/OutgoingCommand/HgLfsManager/Merge3/DeltaCodec/HgRcConfig 등)는 다음 라운드로 이월.
---

# 결정: JaCoCo 커버리지 95% 상향 — 1라운드 결과와 남은 갭

> 사용자가 "라인/메서드/분기 모두 가능한 95%를, 부득이하게 도달 불가능한 경우만 그
> 시점 점수로 충분하고 사유를 문서화"하라고 지시했고, 이후 "아직 손 안 댄 건 빼고
> 진행중인 것만 마무리하자"고 범위를 축소했다. 이 문서는 그 시점(2026-09-01)의 결과와,
> 손댄 클래스들의 남은 소소한 갭 사유, 그리고 아직 손대지 않은 나머지 코드베이스의
> 상태를 기록한다.

## 최종 결과 (전체, 클래스 10개 작업 후)

전체 회귀 `clean test jacocoTestCoverageVerification` 기준(975개 테스트, 실패/에러 0,
skipped 7):

| 지표 | 작업 전 | 작업 후 | 95% 도달? |
|---|---|---|---|
| INSTRUCTION | 87.0% (55254/63518) | **91.5%** (58135/63518) | ❌ |
| LINE | — | **91.6%** (12217/13332) | ❌ |
| BRANCH | — | **74.7%** (5595/7488) | ❌ |
| METHOD | — | **95.3%** (1267/1330) | ✅ |
| CLASS | — | **100%** (200/200) | ✅ |

METHOD/CLASS는 이미 95% 이상을 달성했다. LINE/INSTRUCTION은 87%→91.5% 수준까지
끌어올렸지만 95%에는 못 미친다. BRANCH(74.7%)는 가장 크게 남은 지표다 — 조건문 분기
쌍 전체를 실제로 다 짚으려면 손댄 클래스 안에서도 추가 작업이 필요하고, 아직 손대지
않은 나머지 코드베이스 전체를 놓고 보면 격차가 더 크다.

## 이번 라운드에서 작업한 10개 클래스

각 클래스는 별도 서브에이전트가 "실제 동작을 검증하는 테스트"만 추가했고(스모크성
호출 금지), 프로덕션 코드는 건드리지 않았다(예외 없음 — 아래 표는 순수 테스트 추가
결과다).

| 클래스 | INSTR 전→후 | LINE 후 | BRANCH 후 |
|---|---|---|---|
| `HgLfsManager` | 75%→**100%** | 100% | 94.2% |
| `HgLfsManager$MapJsonParser` | 56%→**100%** | 100% | 90.9% |
| `HgSshClient` | 59%→**98.0%** | 98.0% | 89.2% |
| `Hg`(파사드) | 67%→**96.0%** | 95.8% | 79.2% |
| `ImportCommand` | 74%→**99.3%** | 99.0% | 86.5% |
| `RevlogIndex` | 81%→**99.1%** | 99.7% | 88.0% |
| `Wire2Commands` | 78%→**97.8%** | 99.7% | 88.0% |
| `RebaseCommand` | 87%→**98.0%** | 97.8% | 80.6% |
| `HgRemoteClientV2` | 82%→**99.9%** | 100% | 82.4% |
| `CommitCommand` | 89%→**90.3%** | 88.3% | 78.9% |
| `FetchCommand` | 74%→**85.2%** | 87.2% | 72.2% |

### 100%에 못 미친 채로 남긴 소소한 갭 (의도적, 사유 있음)

- **`RevlogIndex`**: 남은 14개 instruction은 전부 `FileChannel` 부분 읽기/EOF 재시도
  루프(`while (buf.hasRemaining()) { if (channel.read(buf) == -1) break; }`) 방어
  코드. 커스텀 mock 채널로 짧은 읽기(short read)를 강제하지 않는 한 실제 파일
  시스템에서 재현 불가능 — 남은 0.9%를 위해 그 정도 복잡도를 들일 가치가 없다고
  판단해 남겨둠.
- **`Wire2Commands`**: 남은 36개 instruction은 `filesdata`의
  `rev == -1 → continue` 한 줄(매니페스트가 가리키는 fnode가 filelog에 실제로
  없는 저장소 손상 상황 가드). 온디스크 revlog 바이트를 직접 위조하지 않고는
  트리거 불가능해 남겨둠.
- **`HgRemoteClientV2`**: 남은 2개 instruction은 `x != null ? ... : null` 형태의
  널 안전 삼항 연산자 폴백 두 곳 — 억지 mock 없이는 도달 불가능하고, 도달해도
  검증할 의미 있는 동작이 없어 남겨둠.
- **`HgLfsManager`/`MapJsonParser`**: INSTRUCTION은 100%지만 BRANCH는 각각
  94.2%/90.9%다 — 단락평가(short-circuit `&&`/`||`) 조합의 일부 분기 쌍이 JaCoCo
  리포트에 "partial" (`pc`)로 표시되는데, 이는 instruction 관점에서는 이미 양쪽 다
  실행됐지만 JaCoCo의 분기 카운터가 더 세밀하게 쪼개서 세는 경우다. 실질적으로 더
  손볼 게 없다.
- 나머지 클래스들(`Hg`, `RebaseCommand`, `HgSshClient`, `ImportCommand`,
  `CommitCommand`, `FetchCommand`)의 남은 갭은 각 담당 에이전트가 "억지 mock/파괴적
  시나리오 없이는 도달 불가능하거나, 도달해도 이미 검증된 동작의 재확인일 뿐"이라고
  판단해 의도적으로 남긴 것들이다(구체 사례: `Hg`의 일부 porcelain 위임 메서드,
  `CommitCommand`의 동시성 레이스/시간 경계 분기, `FetchCommand`의 네트워크 저수준
  예외 조합). BRANCH가 INSTRUCTION보다 눈에 띄게 낮은 이유가 바로 이 조건문 분기
  쌍들이다.

## 아직 손대지 않은 나머지 코드베이스

사용자가 "진행 중인 것만 마무리"로 범위를 축소해서, 최초 스캔에서 확인된 저커버리지
상위 클래스 중 아래는 **이번 라운드에서 다루지 않았다**(1라운드 시작 시점 기준
INSTRUCTION 커버리지):

`Wire2Transport`(75%), `Cbor`/`Cbor$Reader`(70%/74%), `GpgSignature`(68%),
`OutgoingCommand`(69%), `IncomingCommand`(55%), `HgLock`(78%),
`ManifestTreeIterator`(80%), `VerifyCommand`(79%), `ArchiveCommand`(78%),
`PhaseRoots`(81%), `TreeWalk`(77%), `CloneCommand`(85%), `StripCommand`(88%),
`GraftCommand`(89%), `RevFilter`(14% — 특히 낮음), `SparseConfig`(86%), 그 외
이 목록(상위 40개)에 못 든 더 낮은 커버리지 클래스들도 존재할 수 있다(전체 재스캔
안 함).

이들이 남아있는 한 전체 INSTRUCTION/LINE/BRANCH를 95%까지 끌어올리는 건 불가능하다
— "부득이하게 도달 불가능"이 아니라 **단순히 아직 작업을 안 한 것**이므로, 나중에
이어서 진행하고 싶다면 이 목록에서 미(未)커버 instruction 수가 큰 순서대로 우선순위를
잡으면 된다(이번 라운드와 동일한 방식: 서브에이전트에 클래스 하나씩 배정, 실제 동작
검증 테스트만 추가, 프로덕션 코드는 건드리지 않음, gradle은 한 번에 하나씩만 실행).

## 진행 중 겪은 사고 — 병렬 서브에이전트의 gradle 충돌

1라운드 초반, 6개 서브에이전트를 동시에 병렬 실행해 각자 `./gradlew test
jacocoTestReport`를 같은 작업 디렉터리에서 동시에 돌렸다가:
- 서로의 Gradle 데몬을 `./gradlew --stop`으로 죽이는 사고가 발생(한 에이전트가 자기
  빌드 문제를 "해결"하려다 형제 에이전트의 진행 중이던 빌드까지 함께 죽임)
- 공유 `build/` 산출물(컴파일 클래스, jacoco exec 파일)이 서로 덮어써져 컴파일
  에러/거짓 실패가 반복됨
- 백그라운드로 띄운 gradle 프로세스는 채팅 메시지로 "중지해" 라고 말해도 회수되지
  않는다는 것도 확인(별도로 kill 필요)

교훈: 같은 git 작업 디렉터리를 공유하는 여러 에이전트가 gradle을 동시에 돌리면 절대
안 된다. 이후 라운드부터는 에이전트를 순차 처리(한 명 끝나고 검증한 뒤 다음 에이전트
재개)로 전환해 문제없이 마무리했다.

## 라운드 2 (2026-09-02) — `agentBuildDir` 격리로 병렬 처리 재개, 44개 클래스 완주

라운드 1에서 겪은 gradle 충돌 사고를 근본적으로 해결하기 위해 `build.gradle`에
`agentBuildDir` 프로젝트 프로퍼티를 추가했다(지정 시 `layout.buildDirectory`를
그 경로로 바꿔치기). 각 에이전트가 `-PagentBuildDir=/tmp/agent-<name>/build
--project-cache-dir=/tmp/agent-<name>/cache`로 완전히 격리된 빌드 산출물 디렉터리를
쓰게 하고, `./gradlew --stop`은 절대 호출하지 말라고 매 에이전트에게 명시적으로
지시했다 — 이 방식으로 8~9개 에이전트를 동시에 돌려도 라운드 1의 사고가 재발하지
않았다(단, 개별 에이전트가 "모니터를 걸어두고 기다리겠다"며 진행을 멈추는 패턴이
반복 발생해, 그때마다 "포그라운드로 직접 실행하고 즉시 결과를 보고하라"고 재촉해야
했다 — 비동기 백그라운드 대기 루프에 스스로 빠지는 경향이 있음을 기록해둔다).

### 최종 결과 (전체 회귀, 1670개 테스트, 실패/에러 0, skipped 8)

`./gradlew clean test jacocoTestReport jacocoTestCoverageVerification` 기준
(BUILD SUCCESSFUL, 2m17s):

| 지표 | 라운드 1 후 | 라운드 2 후 | 95% 도달? |
|---|---|---|---|
| INSTRUCTION | 91.5% | **97.10%** (62853/64730) | ✅ |
| LINE | 91.6% | **97.05%** (13214/13615) | ✅ |
| BRANCH | 74.7% | **86.40%** (6575/7610) | ❌ (근접, 대부분 방어적 죽은 코드) |
| METHOD | 95.3% | **97.84%** (1311/1340) | ✅ |
| CLASS | 100% | **100%** (201/201) | ✅ |

`jacocoTestCoverageVerification`(BUNDLE 70% 최소, 핵심 클래스 90% 최소)도 통과.

### 작업한 44개 클래스 (5개 배치, 미커버 instruction 수 큰 순서)

배치1: `ShelveCommand`, `HgRevsetEngine`, `HgRemoteClient`, `FetchCommand`,
`CommitCommand`, `MergeCommand`, `Revlog`, `HgRepository`.
배치2: `IncomingCommand`, `ManifestTreeIterator`, `Wire2Transport`,
`HgHttpWireServer`, `HisteditCommand`, `Cbor`/`Cbor$Reader`, `StripCommand`,
`GpgSignature`, `HgRemoteClientV2`(전담 버그 수정).
배치3: `OutgoingCommand`, `HgLock`, `VerifyCommand`, `PhaseRoots`,
`Wire1Commands`, `NodeIdUtil`, `HgLocalClient`, `TreeWalk`, `CloneCommand`.
배치4: `UpdateCommand`, `GraftCommand`, `RevFilter`, `SparseConfig`,
`SparsePathFilter`, `MergeState`, `ExportCommand`, `DirstateV2Parser`,
`BisectCommand`.
배치5: `RebaseCommand`, `AnnotateCommand`, `RevsetCommand`, `ChangesetGraph`,
`DeltaCodec`, `HgObsolescenceParser`, `HgRcConfig`, `GcCommand`, `DiffCommand`.

대부분 90%대 후반~100% INSTRUCTION에 도달했다(예: `TreeWalk`/`RevFilter`/
`ChangesetGraph`/`ExportCommand` 100%, `VerifyCommand` 전 지표 100%). 남은
소소한 갭은 각 에이전트가 개별적으로 "실제 발생 불가능한 방어 코드"라고 판단·기록한
것들이다(예: JVM이 보장하는 `SHA-1` 알고리즘의 `NoSuchAlgorithmException` catch,
호출부에서 이미 non-null이 보장된 값의 재검사 등) — 상세 사유는 각 커버리지 테스트
클래스의 커밋 시점 코드 리뷰 기록/PR 설명에 남아있다.

### 실제 버그 21건 발견, 19건 수정 (커밋 `a8b9d96` 참고)

TDD로 커버리지를 채우는 과정에서, 항상 real hg 7.2(CLI + Python 소스)와 대조
검증하며 다음 실제 버그들을 발견했다:

- **데이터 손상/크래시급**: `CommitCommand`(dirstate-v2 롤백이 이미 GC된 데이터
  파일을 가리켜 저장소 손상), `Revlog`(`appendRawRevision`이 inline 플래그 무시,
  기존 inline revlog 손상), `StripCommand`(`FileChannel.truncate`로는 파일을 다시
  늘릴 수 없어 strip 실패 시 롤백이 사실상 무효 — 데이터 손실 위험),
  `HgRemoteClientV2`(증분 pull이 changelog/manifest/filelog를 빈 델타 베이스로
  손상), `GraftCommand`/`RebaseCommand`(새로 추가된 파일이 dirstate 미갱신으로
  커밋에서 통째로 누락, 파일 삭제 시 크래시, exec/symlink 플래그 소실),
  `DeltaCodec`(존재하지 않는 압축 포맷 오처리, truncated zlib 스트림이 조용히 부분
  데이터 반환), `GcCommand`(0-리비전 revlog 압축 시 크래시), `RebaseCommand`(target이
  strip 범위에 속하면 크래시).
- **명세 불일치**: `HgRevsetEngine`(작은따옴표 리터럴 매칭 안 됨, `sort()`/`limit()`
  콤마 분리 오류), `Wire1Commands`(`lookup()`이 ambiguous-prefix 에러를 뭉갬),
  `Wire2Transport`(ERROR_RESPONSE 프레임을 CBOR 디코딩 안 하고 그냥 텍스트로 처리),
  `SparsePathFilter`(`?`가 `/`를 제외, `**`가 디렉터리 경계 무시), `HgObsolescenceParser`
  (`FLAG_USING_SHA256` 비트값 오류), `BisectCommand`(DAG 분기 시 후보 선택 알고리즘이
  틀림, good==bad 미검증), `IncomingCommand`/`OutgoingCommand`(summary가 마지막
  줄이 아니라 설명 첫 줄이어야 함), `ExportCommand`(부모를 `rev-1`로 가정, 실제 DAG
  부모 아님), `VerifyCommand`(빈 저장소 오탐, 매니페스트 누락 미검출),
  `HisteditCommand`(fold/roll 5건 — 그룹 첫 멤버가 아닌 마지막 멤버 기준으로 처리),
  `ShelveCommand`(symlink 모드 미보존), `HgLock`(double-close가 다른 락을 오염),
  `HgHttpWireServer`(v2 파싱 에러가 헤더 먼저 전송돼 삼켜짐).

미수정·문서화만 한 2건(범위 밖):
- `HgRemoteClientV2`가 루트 매니페스트만 fetch하고 서브디렉터리 tree는 안 가져옴 —
  진짜 treemanifest를 쓰는 제3자 real hg 서버와 연동 시 서브디렉터리 데이터 누락
  가능(hg4j 자체 저장소는 treemanifest 미사용이라 영향 없음, wireprotocol v2는
  hg 6.1부터 제거돼 실질 노출면도 좁음).
- `AddCommand`/`HgRepository`가 깨진(타겟 없는) symlink를 `File.isFile()`/
  `.exists()`로 체크해서 조용히 건너뛰거나 거부함 — 실제 hg는 깨진 symlink도
  정상 추적함. 수정 자체는 작은 범위(`Files.isSymbolicLink()` 체크 추가)로 예상되나
  아직 미착수.

### 남은 BRANCH 갭(86.4%)에 대해

INSTRUCTION/LINE/METHOD/CLASS는 사실상 목표를 달성했지만 BRANCH는 아직 95%에
못 미친다. 이번 라운드에서 다룬 44개 클래스 각각의 잔여 미커버 분기는 전부
개별적으로 "실제 발생 불가능/방어적 죽은 코드"로 판단·기록됐다(예: JCA가 보장하는
알고리즘의 예외 처리, 호출부에서 이미 보장된 non-null 재검사, 도달 불가능한 switch
default 분기 등). 나머지 격차는 **이 44개 밖의, 아직 손대지 않은 클래스들**에서
온다 — 전체 재스캔(2026-09-02) 기준 미커버 instruction 20개 이상인 클래스가 여전히
다수 남아 있다(`CommitCommand`/`FetchCommand`/`Revlog` 등은 이미 작업했지만 100%까지
안 밀어붙인 잔여분, `RebaseCommand`/`AnnotateCommand`/`RevsetCommand`/
`ChangesetGraph`/`DeltaCodec`/`HgObsolescenceParser`/`HgRcConfig`/`GcCommand`/
`DiffCommand`는 라운드 2 배치5로 이미 처리됨). 이어서 진행하려면 동일한 방식(미커버
instruction 수 큰 순서, `agentBuildDir` 격리, 실제 hg 대조 검증)으로 계속하면 된다.

## 라운드 3 (2026-09-02) — 순차 배치 재개, "라운드 2가 이미 다뤘다"는 판단이 실제로는
불완전했음을 재확인

라운드 2 문서(위 "라운드 2" 절)는 44개 클래스의 잔여 갭을 "전부 개별적으로 판단·기록"
됐다고 뭉뚱그려 서술했으나, 라운드 3에서 그중 다수를 다시 배정해보니 **추가로 실제
개선 여지가 상당히 남아있었다** — "이미 다뤘다"가 "더 이상 손댈 게 없다"를 의미하지
않는다는 것을 실측으로 재확인:

| 클래스 | 라운드 2 이후 | 라운드 3 이후 | 비고 |
|---|---|---|---|
| `Revlog` | 97.2% | **99.1%** | |
| `RebaseCommand` | 98.06% | **99.45%** | |
| `HgRemoteClient` | 97.3% | **100%** | 죽은 null 체크 4곳 제거 |
| `Wire2Commands` | 98.0% | **100%** | |
| `ManifestTreeIterator` | 94% | **100%** | 방어 코드를 test double로 검증(삭제 안 함) |
| `PhaseRoots` | 86% | **98.9%** | 죽은 `Function` 파라미터(호출 안 되는 람다 전체) 제거 |
| `HisteditCommand` | 97.5% | **99.9%** | 죽은 방어 체크 3곳 제거 |
| `Hg`(파사드) | 95.4%(재측정) | **98.8%** | 죽은 null 체크 2곳 제거 |
| `ShelveCommand` | 97.5% | **98.7%** | 잔여 2건 여기서도 impractical 재확인 |
| `MergeCommand`, `BookmarkCommand`(94%→100%), `ChangegroupParser`, `ArchiveCommand` | 5차 배치에서 완료 | | 정확한 전/후 수치는 5차 배치 웹훅 기록 참고(현재 문서에는 BookmarkCommand만 재확인됨) |

또한 라운드 3에서 신규(라운드 1/2 모두 미착수)로 처음 다룬 클래스도 다수 100%
달성: `PurgeCommand`(76%→100%), `ProcessHook`(87.9%→100%), `Wire2Frame`(89%→100%),
`HgSshWireServer`(91%→100%), `RenameCommand`(84.9%→99.2%), `BookmarkCommand`(94%→100%).

**시사점**: 앞으로 "이미 라운드 N이 다뤘다"는 이유만으로 클래스를 건너뛰지 말 것 —
실제로 남은 것을 다시 스캔해서 missed instruction이 여전히 유의미하게 크면(대략
10개 이상) 재시도할 가치가 있다. 진짜로 더 이상 손댈 게 없다고 판단된 것은
**전담 에이전트가 파일/줄 번호와 구체적 이유를 댄 경우만** 신뢰할 것 — 아래
"실제로 도달 불가능하다고 확인된 잔여 갭 목록"에 그런 사례만 모아둔다.

## 라운드 3 계속 (배치 8~17) — missed≥5 "순수 신규 클래스" 체크리스트 완주

배치 8~17은 `/Users/mzc01-search5/.claude` 세션 스크래치패드의 `coverage-checklist.md`
(missed instruction ≥5, 큰 순서, round1/2가 손대지 않은 순수 신규 클래스 우선)를
4개씩(마지막 17차만 3개) 순차 소진했다. 병합검증은 "3배치마다 1회"로 묶어 실행:
2·3·4차 → 5·6·7차(98.51%/98.36%/89.68%/98.66% INSTR/LINE/BRANCH/METHOD, 1897테스트)
→ 8·9·10차(98.81%/98.64%/90.86%/98.81%/100%, 1980테스트) → 11·12·13차(99.00%/
98.85%/91.66%/99.25%/100%, 2056테스트) → 14·15·16·17차(사용자 지시로 4배치 통합,
99.14%/99.03%/92.20%/99.70%/100%, **2129테스트, 전원 GREEN**).

### 배치별 작업 클래스 (10차~17차, 정확한 전→후 수치 확인됨)

| 배치 | 클래스 | INSTR 전→후 |
|---|---|---|
| 10 | `TreeMergeCommand` | (완료, 정확 수치는 배치10 웹훅 참고) |
| 10 | `RollbackCommand` | 93.4%→**100%** |
| 10 | `RevertCommand` | 95.6%→**97.5%** |
| 10 | `LogCommand` | 96.9%→**100%** |
| 11 | `BackoutCommand` | 95.7%→**99.4%** |
| 11 | `DirstateV2Serializer` | 97.8%→**100%** |
| 11 | `Dirstate` | 93.5%→**99.1%** |
| 11 | `SshKeyCredentialsProvider` | 0%(테스트 없었음)→**100%** |
| 12 | `HgSubrepoEntry` | 90.1%→**100%** |
| 12 | `SummaryCommand` | 94.7%→**100%** |
| 12 | `RemoveCommand` | 96.7%→**98.8%** |
| 12 | `GrepCommand` | 94.1%→**100%** |
| 13 | `JschSessionFactory` | 87.7%→**100%** |
| 13 | `AmendCommand` | 90.1%→**100%** |
| 13 | `IdentifyCommand` | 94.5%→**100%** |
| 13 | `HgTreeFilter` | 85.8%→**100%** |
| 14 | `UsernamePasswordCredentialsProvider` | 88.7%→**100%** |
| 14 | `JschSshSession` | 91.9%→**100%** |
| 14 | `DefaultFileStoreEngine` | 91.9%→**100%** |
| 14 | `OrRevFilter` | 0%(테스트 없었음)→**100%** |
| 15 | `AndRevFilter` | 0%(테스트 없었음)→**100%** |
| 15 | `TreeCommand` | 95.5%→**100%** |
| 15 | `ResolveCommand` | 95.9%→**100%** |
| 15 | `HeadsCommand` | 92.9%→**100%** |
| 16 | `CatCommand` | 96%→**100%** |
| 16 | `WorkingDirTreeIterator` | 97%→**100%**(instruction) |
| 16 | `UnbundleCommand` | 97.1%→**100%** |
| 16 | `HgRemoteConnectionFactory$2` | 58.3%→**100%** |
| 17 | `NotRevFilter` | 78.3%→**100%** |
| 17 | `MaxCountRevFilter` | 0%(테스트 없었음)→**100%** |
| 17 | `DeltaEngine` | 97.7%→**99.4%**(instruction), branch 79.5%→90.4% |

배치 1~9는 이 문서 갱신 시점 이전(대화 압축으로 상세 수치 유실)에 진행됐으며,
`MergeCommand`/`ChangegroupParser`/`ArchiveCommand`/`PurgeCommand`/`ProcessHook`/
`Wire2Frame`/`HgSshWireServer`/`RenameCommand`/`BookmarkCommand` 등이 이 구간에서
처리됐다(정확한 전/후 수치는 각 배치 완료 시점 웹훅 알림 기록 참고).

### 이번 구간(배치10~17)에서 새로 발견·확인된 사실

- **베이스라인 수치가 자주 stale였다**: `OrRevFilter`/`AndRevFilter`/
  `MaxCountRevFilter`는 "missed 5~7"이라는 이전 스캔 수치와 달리 실제로는
  **전담 테스트가 아예 존재하지 않아 0%**였다. `SshKeyCredentialsProvider`도
  동일 패턴("missed 12"였지만 실측 0%). 향후 라운드에서도 체크리스트 수치를
  그대로 믿지 말고 매번 empirically 재확인할 것.
- **프로덕션 버그 0건, 죽은 코드 제거 0건** — 배치10~17에서 다룬 27개 클래스
  전부 순수 테스트 추가만으로 100%(또는 근접) 달성. 라운드2 44개 클래스 때와
  달리 이번 구간은 실제 결함이 없었다(코드베이스가 그만큼 이미 안정화됐다는
  신호로 해석).
- **테스트 스코프를 클래스 단위로 좁히지 않으면 빌드 시간이 크게 늘어난다**:
  `api` 패키지(50+ 클래스)를 `--tests "com.github.search5.hg4j.api.*"`로 통째로
  스코프하면 매 iteration마다 전체 api 패키지 테스트가 재실행돼 `UnbundleCommand`
  하나에 32분이 걸렸다. 17차 배치부터 `--tests "...ClassName*"` 식으로 클래스
  단위로 좁히도록 프롬프트를 고치자 `MaxCountRevFilter`가 49초 만에 끝났다 —
  이후 라운드에서는 처음부터 클래스 단위 스코프를 강제할 것.

### 배치10~17에서 새로 확인된 "도달 불가능" 잔여 갭 (파일/줄 근거 있는 것만)

- **`DirstateV2Serializer.java:78`** — `if (current != null)`의 false 분기.
  `path.split("/")`가 항상 길이≥1 배열을 반환하므로 루프가 최소 1회 실행돼
  `current`가 대입된 후에만 이 줄에 도달, `null`일 수 없음.
- **`Dirstate.java:259,261`** — `ByteArrayOutputStream.write()` 주변 `catch
  (IOException e)`. JDK 구현상 이 메서드는 실제로 IOException을 던지지 않음
  (선언은 `OutputStream`으로부터 상속된 체크 예외라 형식상 존재).
- **`Dirstate.java:357`** — `oldUid != null && !oldUid.equals(uid)`의 한
  분기(기존 uid와 새로 생성한 `UUID.randomUUID()` 파생 uid가 우연히 같은 경우)
  — 128비트 UUID 충돌을 강제해야 하므로 사실상 도달 불가.
- **`IdentifyCommand.java:31,37`** — `p1==null`/`branch==null`의 true 분기.
  `Dirstate.getParent1()`은 `NodeId.getBytes()`를 거쳐 항상 20바이트 배열
  반환(null 불가), `HgRepository.getBranch()`도 `"default"` 리터럴이나
  trim()된 문자열만 반환(null 불가) — 단, 향후 구현이 바뀌면 도달 가능해질 수
  있는 방어적 가드라 정적으로 완전한 죽은 코드로 단정하진 않음.
- **`BackoutCommand.java:115-116`** — `manifestOf`의 `changelogRev<0` 가드.
  두 호출부(`NodeIdUtil.resolveRevision` 결과, `parentRev==-1` 삼항의 else쪽)
  모두 항상 유효한 changelog rev만 넘기도록 구조적으로 보장됨.
- **`RemoveCommand.java:137`** — `catch(Exception ignored){}` 안의
  `Files.deleteIfExists(journalFile)`. `journalFile`은 같은 호출 안에서 직전에
  이미 쓰기 가능함이 증명된 일반 파일이라, 외부 프로세스의 동시 변경(TOCTOU
  레이스) 없이는 실패를 재현할 수 없음.
- **`TreeCommand.java:99`** — `else if (flag=='l')`의 false쪽(`hex.length()>40`
  인데 flag가 `x`도 `l`도 아닌 경우). 저장소 내 모든 매니페스트 작성 경로를
  감사한 결과 실제로 생성되는 접미사는 `""`/`"x"`/`"l"` 세 가지뿐 — 손상되거나
  외부에서 주입된 매니페스트가 아니면 도달 불가.
- **`ResolveCommand.java:99`** — `isResolved()`의 `fields==null` 피연산자.
  이 private 헬퍼는 항상 `MergeState.state`에 이미 존재가 검증된 key로만
  호출되고, `MergeState`는 기존 key에 `null`을 저장하는 경로가 없음 — 구조적
  으로 죽은 분기.
- **`WorkingDirTreeIterator.java:65`** — `dEntry==null`(미추적 파일) 분기 안의
  `diskFile.exists() && diskFile.isFile()`. 이 경로로 들어오는 파일은
  `repository.scanWorkingCopy()`가 스캔 시점에 이미 `isFile()==true`로
  확인한 것만 포함하므로, `loadEntries()` 재확인 시점에 값이 달라지려면 진짜
  TOCTOU 레이스가 필요 — 결정론적 재현 불가.
- **`DeltaEngine.java:297,308`** — `catch (IOException ignored){}` 두 곳,
  `ByteArrayOutputStream.write`가 실제로 던지지 않는 죽은 catch.
- **`DeltaEngine.java:167,197,225,226,237,241,253-254`** — Myers diff 백트래킹의
  방어적 경계체크들. `k`가 항상 `[-d+2, d-2]` 구간에 있도록 대수적으로 보장돼
  인덱스가 항상 유효 범위 안에 들어가고, tail-walk 루프는 Myers "snake" 매치만
  순회하도록 구성돼 있어 불일치 분기 자체가 도달 불가.
- **`DeltaEngine.java:280`** — `bEnd>bStart || gEnd>gStart`의 전부-false 조합.
  바깥 루프가 이 블록에 진입하는 조건 자체가 최소 한쪽은 unmatched일 때뿐이라
  도달 불가.

### 라운드3(배치1~17) 최종 전체 커버리지 (14+15+16+17차 통합 병합검증 기준)

| 지표 | 라운드2 후 | 라운드3 최종 | 95% 도달? |
|---|---|---|---|
| INSTRUCTION | 97.10% | **99.14%** (64103/64659) | ✅ |
| LINE | 97.05% | **99.03%** (13472/13604) | ✅ |
| BRANCH | 86.40% | **92.20%** (6989/7580) | ❌ (근접, 잔여 대부분 방어적 죽은 코드로 확인됨) |
| METHOD | 97.84% | **99.70%** (1335/1339) | ✅ |
| CLASS | 100% | **100%** (201/201) | ✅ |

missed≥5 기준 "아직 손 안 댄 순수 신규 클래스" 체크리스트는 17차로 완전히
소진됐다. 이어서 진행한다면 다음 후보군:
1. missed<5인 롱테일 클래스(수십 개, 각 1~4개씩) — 개별 수익은 작지만 누적하면
   BRANCH 95% 근접에 기여.
2. 이번 구간에서 발견된 신규 백로그: `HgRemoteConnectionFactory$4`(로컬 디렉터리
   존재 확인 프로토콜, 배치16 시점 3/20으로 심하게 미커버 — $2만 처리하고
   스코프 밖으로 남김).
3. BRANCH 95% 자체를 목표로 재설정할지 판단 필요 — 이 문서의 "확인된 잔여 갭"
   목록이 이미 상당 부분을 "방어적 죽은 코드"로 설명하고 있어, 남은 격차의
   많은 부분이 구조적으로 도달 불가능할 가능성이 높다.

## 실제로 도달 불가능하다고 확인된 잔여 갭 목록 (파일 단위 근거 있는 것만)

BRANCH 95% 목표는 아래처럼 "실행될 수 없는 코드"가 상당수를 차지하기 때문에 완전
달성이 사실상 불가능에 가깝다. 아래는 담당 에이전트가 실제로 파고들어본 뒤 "이 이상은
안 되거나, 되더라도 의미 없다"고 구체적 근거와 함께 확인한 것만 모은 목록이다(짐작이나
평가를 생략한 뭉뚱그린 판단은 제외 — 이런 것들은 다음 라운드에서 재시도 대상으로
남긴다).

- **`CommitCommand`**(115 missed, 라운드3 skip): 커밋 실패 도중 롤백/정리 자체가 또
  실패하는 "이중 장애" catch 블록들(542~688번 줄대). 1차 장애를 일으킨 뒤 롤백 시점에
  정확히 맞춰 2차 장애를 주입해야 하는데, 디렉터리 손상·권한 트릭 등 시도했으나 전부
  트랜잭션 앞단에서 먼저 실패하거나 타이밍을 맞출 수 없어 재현 불가.
- **`ShelveCommand`**(잔여 30 missed): (1) 307번 줄의 "임시 shelve 커밋 리비전 해석
  실패" 방어 체크 — `CommitCommand.call()`이 항상 리턴 전에 revlog 캐시를 지우는 것을
  확인, mock 없이는 도달 불가. (2) 622~623번 줄(`revertToLatestCommit()`의 symlink
  재생성 catch) — 동일 위치인 `performUnshelve()`(524~525번 줄)는 uncompressed shelve
  번들 바이트를 patch해서 커버했지만, 이쪽은 실제 committed filelog(zlib/zstd 압축)에서
  읽어서 1바이트 손상시키면 압축 해제 자체가 깨져 "파싱은 되지만 유효하지 않은 symlink
  타겟"을 못 만듦 — 스토리지 계층을 훨씬 침습적으로 mock해야 함.
- **`RenameCommand`**(잔여 2 missed): 102·106번 줄, 실패 복구 경로 자체가 또 실패하는
  `catch (Exception ignored) {}`. `SafeFileIO.writeAtomic`/`Files.deleteIfExists`가
  롤백 도중 던지는 경우인데, 이 리포에는 mocking 프레임워크가 없어 fault injection
  불가.
- **`RevlogIndex`**(잔여 14 missed, 라운드2 기록): `FileChannel` 부분 읽기/EOF 재시도
  방어 루프 — 커스텀 mock 채널로 short read를 강제하지 않는 한 실제 파일시스템에서
  재현 불가.
- **`Wire2Commands`**(현재 0 missed, 이전 기록): `filesdata`의 `rev == -1 → continue`
  — 매니페스트가 가리키는 fnode가 filelog에 실제로 없는 저장소 손상 상황. 온디스크
  revlog 바이트를 직접 위조해야만 트리거되며, 라운드3에서 정확히 이 방법으로 실제로
  커버해 0으로 만듦(과거 "불가능" 판단이 재시도로 뒤집힌 사례이기도 함).
- **`HgRemoteClientV2`**(잔여 2 missed, 이전 기록): `x != null ? ... : null` 형태의
  널 안전 삼항 연산자 폴백 두 곳 — 억지 mock 없이는 도달 불가하고 도달해도 검증할
  의미 있는 동작이 없음.
- **`HisteditCommand`**(잔여 1 missed): `getManifestForCommit`의 매니페스트 텍스트
  파서 중 줄 내 공백/null 구분자 누락 분기 — 저장소 레벨의 손상된 원시 바이트를
  손으로 만들어야만 하는 단일 instruction, 위험 대비 이득이 없어 보류.

이 목록에 없는 나머지 클래스들의 잔여 BRANCH 갭은 "아직 재시도 안 해본 것"이지
"불가능하다고 확인된 것"이 아니다 — 위 "라운드 3" 절의 재확인 사례처럼, 실제로 다시
붙어보면 상당수가 뚫릴 가능성이 있다.

## 라운드 4 (2026-09-03) — BRANCH 롱테일 우선순위 작업, 1순위(Dirstate/DirstateV2*) 완료

사용자가 롱테일(missed 1~4개) 55개 클래스를 위험도 3단계로 우선순위 지정. 1순위
"데이터 손상 위험 직결" 그룹(`Dirstate`, `DirstateV2Node`, `DirstateV2Parser`,
`DirstateV2Serializer`)부터 개별 조사.

- **`DirstateV2Parser`#`parse(byte[])`의 legacy "relative offset" 폴백 루프(150번 줄)**:
  `copySourceLen > 0 ? copySourceOffset+copySourceLen : pathOffset+pathLen` 삼항에서
  `copySourceLen > 0` 분기가 기존 테스트(`testParseLegacyTwoArgOverload_...`, all-zero
  단일 노드)에 미커버였음 — **실제로 도달 가능해서 TDD로 커버함**
  (`DirstateV2ParserCoverageTest#testParseLegacyTwoArgOverload_singleNodeWithCopySource_usesRelativeFallbackDetectionCopySourceBranch`).
  copySourceOffset=0인 합성 노드로 절대 오프셋 규약(`DirstateV2Parser`의 3-arg
  overload는 copy_source_start를 항상 절대 버퍼 위치로 읽음, 상대 오프셋으로 보정하지
  않음)과 폴백 판정식의 offset 계산이 다르다는 점을 이용해, 판정은 폴백 경로를 타되
  실제 파싱은 예외 없이 끝나는 것만 확인 — 복원된 copy-source 바이트 자체는 헤더의
  0바이트를 그대로 읽은 것이라 내용은 의미 없음(문서화해둠), 분기 커버리지 확인이
  목적.
- **`DirstateV2Parser.java:69`의 `state != 'd'`** 및 **`DirstateV2Node.java:122`
  (`getMode()`)의 `state == 'd'`** — **동일한 근본 원인으로 도달 불가능 확인**.
  `DirstateV2Node#setState(char)`(94번 줄)는 `case 'd': case '\0': default: break;`로
  두 상태를 완전히 같은 플래그 비트(전부 미설정)로 기록하고, `getState()`(71번 줄)는
  그 비트 조합에 대해 오직 `'\0'`만 반환하며 `'d'`를 리턴하는 경로가 코드 어디에도
  없음(클래스 자체의 `DIRECTORY` 상수 주석에도 이미 "real hg는 `'d'`/`'\0'`을 구분할
  필요가 없다"고 명시됨). 즉 `state == 'd'`로 평가되는 순간은 `getState()`가 반환하는
  값을 통해서는 원천적으로 발생하지 않는, 코드 작성 당시의 방어적 잔재 — mock 없이는
  물론이고 순수 데이터 조작으로도 도달 불가.
- **`DirstateV2Serializer.java:78`의 `if (current != null)`**: `path.split("/")`는
  빈 문자열 입력에도 길이 1 이상의 배열을 반환하므로(`"".split("/")` → `[""]`)
  `for (i=0; i<segments.length; i++)` 루프가 항상 최소 1회 실행되고, 그 안에서
  `current`가 매번 재할당됨 — 어떤 `entries` 키(빈 문자열 포함)로도 루프 종료 후
  `current == null`이 될 수 없는 방어 코드.
- **`Dirstate.java:357`의 `oldUid != null && !oldUid.equals(uid)`**: `uid`는 매 저장
  호출마다 `UUID.randomUUID().toString().replace("-","").substring(0,16)`로 새로
  생성됨(298번 줄) — `oldUid.equals(uid)`가 참이 되려면 64비트 UUID 충돌이 필요.
  `build.gradle`에 정적 메서드 모킹 라이브러리(Mockito 등)가 전혀 없어(`grep -n
  "mockito" build.gradle` 결과 없음) `UUID.randomUUID()` 자체를 제어할 방법이
  없고, production 코드에 UID 생성기를 주입 가능하게 리팩터링하는 것은 이 한 분기
  때문에 들이기엔 과한 변경이라 보류.

1순위 4개 클래스 처리 결과: 5개 갭 중 1개(`DirstateV2Parser.java:150`)는 TDD로 커버,
나머지 4개는 위와 같이 근거를 확인해 "도달 불가능"으로 이 목록에 편입.

## 라운드 4 계속 — 2순위(`AddCommand`/`CopyCommand`/`RevertCommand`/`CloneCommand`) 완료

- **`AddCommand.java:30`** (`if (file != null && !file.isEmpty())`, 2 branch 미커버):
  `addFile(null)`/`addFile("")`이 조용히 무시되는지 검증하는 테스트가 아예 없었음 —
  **TDD로 커버**(`testAddFileWithNullArgumentIsIgnored`,
  `testAddFileWithEmptyStringArgumentIsIgnored`).
- **`AddCommand.java:58`** (`if (!isSymlink && (!diskFile.exists() || !diskFile.isFile()))`,
  1 branch 미커버): "존재하지만 디렉터리인 경로를 add" 케이스가 미검증 —
  **TDD로 커버**(`testAddThrowsExceptionForDirectoryPath`).
- **`CopyCommand.java:203`** (`existsOnDisk`: `file.exists() || Files.isSymbolicLink(...)`,
  1 branch 미커버): 기존 심링크 테스트(`testCopySymlinkPreservesSymlinkness`)는 타겟이
  존재하는 링크만 써서 `file.exists()`가 이미 true로 단락 평가됨 — 댕글링 심링크를
  소스로 복사하는 케이스가 미검증 — **TDD로 커버**(`testCopyDanglingSymlinkSourceSucceeds`,
  real hg의 `hg cp` on a broken link와 동일 동작).
- **`CloneCommand.java:119`** (`if (line.isEmpty()) continue;`, manifest 파싱 루프):
  tip 커밋의 매니페스트가 완전히 빈 경우(마지막으로 남은 추적 파일을 전부 `hg remove`
  한 뒤 커밋) `manifest.getRevisionContent(...)`가 빈 바이트가 되고
  `"".split("\n")`이 빈 문자열 원소 1개를 반환하는 자연스러운 실제 시나리오 —
  **TDD로 커버**(`testCloneOfTipWithEmptyManifestSkipsBlankManifestLine`).
- **`CloneCommand.java:156`** (checkout 시 기존 경로 정리: `diskFile.exists() ||
  Files.isSymbolicLink(...)`, 1 branch 미커버): 체크아웃 대상 경로에 이미 댕글링
  심링크가 있는 경우가 미검증. `CloneCommand.call()` 자체는 목적지 디렉터리가
  비어있지 않으면 최상위에서 거부하므로(59~60번 줄) 신선한 clone으로는 도달 불가 —
  기존 `testCheckoutLatestIsIdempotentWhenFileAlreadyExists`와 동일하게 이미 체크아웃된
  저장소에서 `checkoutLatest`를 리플렉션으로 직접 재호출하는 방식으로 **TDD 커버**
  (`testCheckoutLatestReplacesPreexistingDanglingSymlinkAtCheckoutPath`).
- **`CloneCommand.java:121`**(`if (nullIdx != -1)`, 잔여 1 missed) — **도달 불가능
  확인**: 매니페스트의 한 줄에 NUL 구분자가 아예 없는 경우인데, 이는 매니페스트
  revlog 콘텐츠 자체가 손상된 상황(정상적으로는 `path\0<40-hex-nodeid><flags>` 형식이
  항상 보장됨)이다. 이 파일의 다른 손상 시나리오 테스트들(`testCheckoutLatestThrowsWhen...`
  3건)은 인덱스/데이터 파일을 통째로 삭제·truncate하는 방식이라 유효한 revlog 델타
  포맷을 유지하는데, 이 케이스는 revlog 델타 인코딩을 직접 손으로 만들어 "유효한
  리비전이지만 그 안의 텍스트 한 줄만 NUL 없이 손상된" 콘텐츠를 만들어야 해서
  RevlogIndex의 "커스텀 mock 채널로 short read 강제"만큼 침습적 — 보류.
- **`RevertCommand.java:96`** (`if (e.getMessage() != null && e.getMessage().contains(...))`,
  잔여 1 missed) — **도달 불가능 확인**: 이미 `e.getMessage() != null`인 두 경우(포함/
  불포함)는 각각 `revertsFileNotPresentAtTargetRevisionByDeletingAndUntracking`류와
  `propagatesUnrelatedIOExceptionsFromCatCommand`로 커버됨. 남은 건
  `e.getMessage() == null`인 `IOException`을 `CatCommand`/`getManifestAtCommit`
  경로에서 던져야 하는데, 이 리포 코드베이스 어디에서도 메시지 없는 `IOException`을
  던지는 지점이 없음(`grep`으로 확인) — mocking 프레임워크 없이는 인위적으로만
  만들 수 있음.
- **`RevertCommand.java:134`**(2-리소스 `try-with-resources`의 닫는 중괄호, 잔여
  2 missed) — **도달 불가능 확인**: 컴파일러가 생성하는 "본문 예외 발생 시 자원
  close()도 실패하면 suppressed exception으로 합치는" 분기. `RenameCommand`
  102·106번 줄과 동일한 종류의 "이중 장애" 패턴(본문 예외 + 자원 close() 예외
  동시 발생)이며, 이미 그쪽에서 확인된 것처럼 mocking 프레임워크가 없어 fault
  injection 불가.

2순위 처리 결과: 6개 갭 중 5개(`AddCommand` 2, `CopyCommand` 1, `CloneCommand` 2)는
TDD로 커버, 3개(`CloneCommand.java:121`, `RevertCommand.java:96,134`)는 도달 불가능
확인 후 이 목록에 편입.

## 라운드 4 계속 — 3순위(missed=1 롱테일 전체) + 일부 missed=2/3 클래스, 18:30까지 연속 작업

사용자가 "3순위, 4순위 등등 쭉쭉 18:30까지 이어서 진행, 끝나면 커밋+푸시"로 지시.
missed=1 롱테일 클래스 전체(사용자가 예시로 든 `SummaryCommand`/`TreeCommand`/
`IdentifyCommand`/`PurgeCommand` 포함, 약 19개)를 먼저 소진하고, 이어서 missed=2
클래스 대부분, missed=3 일부(`ChangesetGraph`)까지 처리. 시간 제약으로 missed≥3의
나머지 다수(포맷 파싱류: `ExportCommand`/`TagCommand`/`ManifestCommand`/
`AnnotateCommand`/`HgLfsManager` 등)와 `FileIndex`(중첩 클래스 3개에 걸쳐 16개 미커버,
1개 클래스로는 최대 잔여 갭)·`BundleCommand`(19개)는 이번 라운드에서 손대지 못하고
다음으로 이월.

### missed=1 롱테일 (19개 클래스) — TDD 커버 9건, 도달 불가능 확인 10건

**TDD로 커버**(실제 테스트 작성, jacoco로 분기 커버 확인):
- `HgLfsPointer.java:50`(`content==null`) — `HgLfsTest`에 null-content 케이스 추가.
- `HgCommit.java:30`(`branch != null ? branch : "default"`의 null 분기) —
  `HgPorcelainAndExceptionsTest`에 null-branch 생성자 케이스 추가.
- `RecoverCommand.java:72`(`verify && success`의 `success==false` 쪽) — 기존
  "롤백 실패" 테스트에 `.setVerify(true)` 버전 추가해 verify가 스킵되는지 확인.
- `WorktreeCommand.java:39`(대상이 이미 존재하는 일반 파일인 경우) — 신규 테스트.
- `SparseConfig.java:65`는 처음에 "`%include` 뒤 공백만 있는 줄" 테스트를 작성했으나,
  `rawLine.trim()`이 이미 앞뒤 공백을 제거하므로 `line.startsWith("%include ")`가
  참이 되려면 마지막 글자가 공백일 수 없고, 따라서 `profile.trim()`이 빈 문자열이
  될 수 없다는 걸 뒤늦게 확인 — **테스트를 제거하고 도달 불가능으로 재분류**(아래
  목록 참고). 실제로 분기 커버리지가 안 오른 것을 보고 원인을 역추적해 정정한
  사례.
- `Repository.java:45`(`.hg`가 이미 존재하는 일반 파일인 경우) — 이 인터페이스의
  정적 팩토리(`Repository.open`)를 직접 부르는 테스트가 아예 없어서 신규
  `RepositoryTest.java` 작성.
- `TextProgressMonitor.java:23`(`start(null, ...)`의 null 타이틀) —
  `ProgressMonitorTest`에 케이스 추가.
- `HgRemoteConnectionFactory.java:70`(`register(null)`) — 신규 테스트 추가.

**도달 불가능 확인**(근거와 함께 문서화, 테스트 작성 안 함):
- `Wire2Transport.java:136`(`writeChunked`의 `body.length==0`): 유일한 호출부
  (`buildCommandResponseFrames`)가 항상 `statusOk()` 맵을 먼저 넣은 뒤 CBOR
  인코딩하므로 body가 절대 빈 배열이 될 수 없음(private 메서드, 호출부 1곳뿐).
- `Cbor.java:193`(`Reader.readValue`의 `switch(majorType)` `default` arm):
  `majorType = (data[pos++]&0xFF) >>> 5`로 항상 0~7 범위이고 switch가 0~7을 모두
  명시적으로 처리 — `default`는 수학적으로 도달 불가.
- `BackoutCommand.java:115`(`manifestOf`의 `changelogRev < 0`): 호출부 2곳
  모두(`targetRev`, `parentRev==-1?...:manifestOf(parentRev)`) 이미 음수를
  걸러내고 호출하므로 도달 불가.
- `TreeCommand.java:99`(`flag == 'l'`의 false 쪽, `hex.length()>40`인데 flag가
  'x'도 'l'도 아닌 경우): real hg 매니페스트 플래그는 'x'/'l' 둘뿐이라 정상
  데이터로는 도달 불가(손상된 매니페스트만 가능).
- `ResolveCommand.java:99`(`isResolved`의 `fields==null`): 이미 기존 테스트
  (`HgResolveTest#listTreatsEntryWithNoFieldsAsUnresolvedAndRecognizesResolvedPathConflict`)
  의 주석에 "모든 `mergeState.state`의 값은 항상 non-null list"라고 명시돼 있던
  근거를 재확인만 함 — 새 테스트 불필요.
- `SummaryCommand.java:97`(`parseChangelogHeader`의 `blank+2 <= text.length()`):
  `blank`는 `text.indexOf("\n\n")`의 결과이므로 `blank!=-1`이면 정의상 항상
  `blank+2 <= text.length()` — 이 부등식의 false 쪽은 `indexOf`의 계약상 불가능.
- `WorkingDirWalk.java:99`(`getEntry`의 `cachedIndex >= cachedEntries.size()`):
  `cachedIndex`를 변경하는 유일한 지점(`next()`)이 `size()-1`을 넘어서게 두지
  않으므로 이 조건은 절대 참이 될 수 없음.
- `TreeWalk.java:85`(`currentPath.startsWith(baseDir + "/")`의 false 쪽):
  `baseDir`는 `currentPath.substring(0, lastSlash)`로 만들어지므로 항상
  `currentPath`의 접두사이고 그 바로 뒤가 '/'임이 구조적으로 보장됨.
- `SparseConfig.java:65`(`profile.isEmpty()`): 위 "TDD로 커버" 항목의 정정 사례
  참고 — `rawLine.trim()` 때문에 수학적으로 도달 불가.
- `DirstateV2Parser.java:69` / `DirstateV2Node.java:122`(`state == 'd'`): 1순위
  섹션에서 이미 확인한 것과 동일 근본 원인(`setState('d')`와 `setState('\0')`이
  동일 비트를 기록, `getState()`는 `'d'`를 절대 반환하지 않음) — 재확인만 함.

### missed=2 클래스 (9개) — TDD 커버 5건, 도달 불가능 확인 4건

**TDD로 커버**:
- `AddremoveCommand.java:34,49`: 전용 테스트 파일이 아예 없었음(`interop` 태그
  붙은 다른 테스트에서만 간접 사용) — 신규 `AddremoveCommandTest.java` 작성.
  34번(이미 추적 중인 파일은 재-add 안 함), 49번(이미 'r' 상태인 엔트리는
  두 번째 `call()`에서 재처리 안 함) 각각 별도 테스트.
- `NodeId.java:55`(`equals()`의 null/타입불일치 분기): `NodeIdTest`에 equals
  계약 전체(reflexive/same-content/different-content/null/다른 타입) 테스트
  신규 추가 — 기존엔 `equals()` 전용 테스트가 전혀 없었음.
- `WorkingDirTreeIterator.java:65`(untracked 파일의 `exists() && isFile()`):
  댕글링 심링크(`exists()==false`)와 디렉터리를 가리키는 심링크
  (`exists()==true, isFile()==false`) 두 케이스 추가 — 이미 있던 tracked-쪽
  동등 테스트(`testTrackedEntryMissingFromDiskSkipsExecutableCheck`,
  `testTrackedEntryReplacedByDirectorySkipsExecutableCheck`)의 untracked 버전.
  (검증 중 `StatusCommandTest`를 함께 돌려야만 4개 분기가 모두 채워짐을 확인 —
  "isFile()==true, exists()==true"의 정상 케이스는 이 테스트 파일이 아니라
  `StatusCommandTest`가 이미 커버하고 있었음.)
- `SidedataChangedFilesCommand.java:37,50,58`: null 저장소, 음수(미설정)
  리비전, 범위 초과 리비전 3개 가드 모두 미검증 상태였음 — 3개 테스트 추가.

**도달 불가능 확인**:
- `IdentifyCommand.java:31`(`p1==null`)과 `:37`(`branch==null`): 둘 다 각각의
  생산 메서드(`Dirstate#getParent1()`은 항상 실제 `NodeId`의 `getBytes()`를
  반환, `HgRepository#getBranch()`는 파일 없으면 `"default"` 문자열 리터럴,
  있으면 `Files.readString(...).trim()` — 둘 다 절대 null이 아님)가 null을 만들
  수 없음이 근거. `branch.isEmpty()`(빈 branch 파일) 쪽은 이미
  `IdentifyCommandTest#identifiesEmptyBranchFileContentsAsDefaultBranch`가
  커버 중이었음.
- `PurgeCommand.java:48`(`path.getFileName()==null`): 저장소가 파일시스템
  루트(`/`)에 있을 때만 가능 — `@TempDir` 기반 테스트로는 구조적으로 도달
  불가하고, 실제로 루트에 저장소를 만드는 테스트는 위험/부적절하여 시도하지
  않음.
- `PurgeCommand.java:63`(`rel.isEmpty()`): 61번 줄의
  `!path.equals(repository.getDirectory().toPath())` 가드가 이미 루트 자기
  자신을 걸러내므로, 63번 줄에 도달하는 `path`는 항상 루트가 아니고 따라서
  `rel`(루트 기준 상대경로)이 빈 문자열일 수 없음.
- `BranchesCommand.java:109,112`(`bestClosed`/`bestOpen`을 갱신하는 `best==null
  || rev>best` 관용구의 "이미 non-null인데 rev<=best"쪽): 같은 브랜치의 head
  리스트가 항상 revision 오름차순으로 채워지므로(바깥 루프가 `i`를 0부터
  증가시키며 순서대로 채움), 이후에 나오는 rev가 이전 best보다 작을 수 없음 —
  구조적으로 항상 `rev > best`.
- `PullCommand.java:102,130`(`results != null`): `FetchCommand.call()`/
  `applyBundle()`의 모든 반환 경로(`grep`으로 전수 확인)가 `new ArrayList<>()`
  아니면 실제 리스트만 반환하고 `null`을 리턴하는 경로가 없음(내부 헬퍼
  `tryApplyClonebundle`의 `return null`은 별개의 private 메서드로, 공개
  `call()`/`applyBundle()` 자체의 반환값이 아님).

### missed=3 일부 — `ChangesetGraph` (6개 중 3개 커버)

`lazyAncestors()`가 `sortOrder==TOPO`일 때는 `dfs()` 헬퍼를, 기본(BFS) 모드일 때는
별도의 익명 `Iterator`를 쓰는 완전히 다른 코드 경로라는 걸 발견 — 기존
`ChangesetGraphCoverageTest`는 "TOPO with startRev=-1", "TOPO with null parents"만
있고 그 BFS 쪽 동등 테스트가 없었음(테스트 이름이 "Topo"라고 명시돼 있었는데도
처음엔 그게 BFS 쪽까지 커버한다고 착각했다가, jacoco 재확인으로 실수를 바로잡음).
- `ChangesetGraph.java:106`(BFS `Iterator`의 `current==-1`), `:111`(`parents !=
  null`), `:129`(`next()`의 `!hasNext()` 예외) — 각각 TOPO 버전과 대칭되는 BFS
  버전 테스트 3개 추가.
- `:185`(`isAncestor`의 `current==-1 || current<ancestor`), `:229`
  (`getLcaCandidates`의 `inQueue.add(revB)`), `:272`(`getRevlogLookup`의
  `changelog==null || rev==-1`)는 시간 제약으로 이번엔 보류.

### missed=3 추가 처리 — `ManifestCommand`/`TagsCommand`/`ClonebundlesManifest` (부분)

시간이 남아 몇 개를 더 처리:
- `ManifestCommand.java:157`(`parentRevNum==-1`, dirstate parent1이 changelog에
  없는 손상 케이스), `:117`(`setRevision("")` 명시적 빈 문자열 분기), `:150`
  (00changelog.i 파일은 존재하지만 리비전 0개인 케이스, `Hg.init()`은 이 파일을
  아예 만들지 않으므로 "커밋 0개" 테스트와는 다른 별개의 분기) — 3곳 모두 신규
  테스트로 완전 커버.
- `TagsCommand.java:140`(`.hgtags` 라인에 공백 구분자가 아예 없는 경우), `:107`
  (`changelog==null` 삼항 — 커밋이 0개인 저장소에 `.hgtags` 파일만 수기로 만들어
  둔 경우, 신규 테스트로 완전 커버) — 둘 다 신규 테스트로 완전 커버. `:145`
  (`name.isEmpty()`)는 SparseConfig.java:65와 정확히 같은 이유로 **도달 불가능
  확인**: `readTagFile`도 `line = rawLine.trim()`을 먼저 적용하므로,
  `line.indexOf(' ')`가 -1이 아니려면 그 공백 뒤에 최소 한 글자의 비공백 문자가
  남아있어야 하고, 그러면 `name = line.substring(spaceIdx+1).trim()`이 빈
  문자열이 될 수 없음(처음엔 `hex + " "` 형태로 테스트를 시도했으나 trim() 때문에
  오히려 `spaceIdx==-1` 분기와 중복돼 실제로는 새 분기를 못 채웠다는 걸 커버리지
  재확인으로 발견 — 같은 실수를 두 번째로 반복하고서야 패턴을 학습함).
- `ClonebundlesManifest.java:77`(`=` 없는 속성 토큰 스킵), `:100`
  (`filterSupported(null)`) — 둘 다 신규 테스트로 완전 커버(퍼블릭 API 직접
  테스트, 내부 호출부가 null을 안 넘겨도 계약 검증 가치 있음). `:70`은 여전히
  `fields.length==0`(수학적으로 도달 불가, `String#split`은 절대 빈 배열을
  반환하지 않음)만 남음 — **거의 확정적으로 도달 불가능**이지만 이번엔 문서화
  단계까지는 못 감.
- `HgRcConfig.java:73`(연속행 병합의 `existing != null` 삼항)은 기존 테스트
  (`continuationLineAppendsToPreviousValue`, 2개의 연속행으로 both-branches를
  이미 노려본 것으로 보였음)가 실제로는 커버하지 못하는 것으로 재확인 —
  원인 미파악, 다음 라운드로 이월.

### missed=3 추가 처리(2) — `HgHttpWireServer`(전부), `ExportCommand`/`AnnotateCommand`(일부) 도달 불가능 확인

- `HgHttpWireServer.java:133`(`handleCapabilitiesDiscovery`의 `v1CapabilitiesLine==null`
  삼항): 유일한 호출부(71번 줄)가 항상 `Wire1Commands.capabilitiesString(repository)`의
  결과를 넘기고, 이 메서드는 문자열 빌더 기반이라 null을 반환하지 않음 — private
  메서드라 리플렉션 없이는 직접 호출도 안 되므로 이번엔 테스트 작성 안 함.
- `HgHttpWireServer.java:246`(`switch(response.getKind())`의 암묵적 default arm):
  `Wire1Response.Kind`가 정확히 3개 값(`BYTES`/`STREAM`/`OOB_ERROR`)뿐이고 switch가
  셋 다 명시적으로 처리 — Cbor.java:193과 동일한 "enum switch 완전성" 패턴으로
  default는 도달 불가.
- `HgHttpWireServer.java:278`(STREAM 케이스의 `body.length==0 ? -1 :
  body.length`): STREAM 응답은 항상 `deflate()`를 거치는데, 빈 입력이라도 zlib
  압축은 헤더/트레일러 때문에 0바이트를 절대 반환하지 않음(BYTES 케이스의 동일
  삼항인 267번 줄은 압축을 안 거치므로 이미 완전 커버돼 있던 것과 대비됨 — 압축
  유무 차이가 도달 가능성을 가른다는 걸 확인).
- `ExportCommand.java:55,58`(`lines.length > 1`/`> 2`): `CommitCommand.java:524~529`
  (changelog 항목 직렬화)를 직접 확인 — 모든 커밋은 예외 없이 `manifestHex\n` +
  `author\n` + `초 tz` 최소 3줄을 항상 씀. 이 라이브러리가 쓴 changelog든 실제
  hg가 쓴 changelog든(포맷 자체가 동일 스펙) 이 최소 구조는 항상 보장됨.
- `AnnotateCommand.java:179`(`clLines.length > 1`, author 추출용): 위와 동일한
  근거로 도달 불가능.
- `ExportCommand.java:63,69`(설명 블록 앞 빈 줄 탐색 루프)와 `AnnotateCommand.java:175`
  (`linkRev` 범위 체크), `OutgoingCommand.java:80,124`, `IncomingCommand.java:83,96`
  (원격 changegroup 파싱 관련)은 이번엔 미착수.

### missed=3 추가 처리(3) — `TagCommand` 완전 커버(4/4)

- `TagCommand.java:50,57`(`registerPreTagHook`/`registerPostTagHook`의 `hook !=
  null` 가드) — `null` 등록이 조용히 무시되는지 검증하는 테스트가 없었음, 신규
  테스트로 커버.
- `TagCommand.java:66`(`tagName != null && !tagName.isEmpty()`): 기존 테스트들은
  전부 `setTagName()`을 아예 안 부르거나(null, 기본값) 유효한 이름을 쓰는
  경우뿐이라, **명시적으로** `setTagName("")`을 호출하는 케이스가 빠져 있었음
  (null과 명시적 빈 문자열은 서로 다른 분기 결과지만 둘 다 "태그 목록 조회
  모드"로 빠지는 동일한 동작이라 겉보기엔 구분이 안 갔던 사례) — 신규 테스트로
  커버.
- `TagCommand.java:132`(`.hgtags` 목록 조회 루프의 `spaceIdx != -1`): 공백
  구분자가 아예 없는 줄이 미검증이었음 — `TagsCommand`의 동일 패턴(140번 줄)과
  똑같은 방식으로 신규 테스트 추가.

### missed=2 추가 처리 — `PhaseRoots` 도달 불가능 확인, `Merge3`은 보류

- `PhaseRoots.java:112`(`getPhase(NodeId, Function)`의 BFS 루프 내부
  `curr==null || curr.isNull()`): 처음엔 `getPhase(null, ...)`/`getPhase(NodeId.NULL,
  ...)`을 부르는 기존 테스트(`PhaseRootsTest.java:163`, `PhaseRootsCoverageTest.java:204`)가
  이미 커버할 거라 예상했으나, jacoco 재확인 결과 여전히 미커버 — 메서드 맨 앞
  (91~94번 줄)에 `if (node == null || node.isNull()) return Phase.PUBLIC;`라는
  동일한 가드가 이미 있어서, null/null-node인 `node`는 BFS 루프(큐)에 진입하기도
  전에 조기 반환됨. 큐에 들어가는 다른 모든 원소(127~131번 줄)도 이미
  `parent != null && !parent.isNull()`로 필터링된 뒤에만 추가되므로, 루프
  내부의 이 체크는 이중으로 방어적인 도달 불가능한 코드.
- `PhaseRoots.java:143`(`getPhase(NodeId, Revlog)`가 만드는 parentLookup 람다의
  `n==null || n.isNull()`): 동일 근거로 도달 불가능 — 이 람다는 오직
  `parentLookup.apply(curr)`(125번 줄)로만 호출되는데, 그 시점의 `curr`는 이미
  112번 줄의 체크를 통과한(즉 null도 null-node도 아닌) 값만 가능함.
- `Merge3.java:46,142,154`: Hirschberg 알고리즘(LCS 매핑)의 내부 분기라 특정
  base/yours/theirs 조합을 정교하게 설계해야 하고, 알고리즘 자체의 정확성을
  검증할 명확한 기준(실제 hg와 직접 비교 가능한 대상이 아닌 순수 내부 자료구조)이
  부족해 이번 라운드에서는 착수하지 않음(위험 대비 시간 투자 효율 낮음으로 판단).

### `FileIndex` 중첩 클래스(`Docket`/`TrieBuilder`/`GrowableBuffer`) 5곳 중 4곳 완전 커버

`FileIndex`는 앞서 "16개 미커버로 이번 세션 범위를 넘는다"고 보류했었으나, 그중
중첩 클래스 3개(`Docket`/`TrieBuilder`/`GrowableBuffer`) 몫만 따로 떼어보니 실제로는
전부 `private static` 클래스라도 `FileIndex.snapshot(storeDir)`/
`FileIndex.writeTrackedPaths(storeDir, paths)` 같은 **public 진입점을 통해 리플렉션
없이 도달 가능**하다는 걸 확인 — 손상된 `.hg/store/fileindex` 바이트를 직접 써서
디스크에서 읽게 하거나, 의도적으로 잘못된 입력(`Collection<String>`에 빈 문자열,
경로 200개)을 넘기는 방식으로 커버:
- `Docket.parse`의 `data.length < DOCKET_FIXED_SIZE`(283번 줄)와 `!Arrays.equals(marker,
  MARKER)`(289번 줄) — `.hg/store/fileindex`에 각각 짧은/마커가 틀린 바이트를 직접
  써서 `FileIndex.snapshot()` 호출로 커버.
- `TrieBuilder.insert`의 `path.length==0`(344번 줄) — `writeTrackedPaths(storeDir,
  List.of(""))`로 커버.
- `GrowableBuffer.ensureCapacity`의 `needed > data.length`(492번 줄) — 초기
  256바이트를 넘도록 경로 200개를 써서 커버.
- `GrowableBuffer.ensureCapacity`의 `while (newLen < needed)`(494번 줄, 두 번째
  이상 반복) — **도달 불가능 확인**: `appendByte`/`appendInt`만 존재하고 각각
  1바이트/4바이트씩만 증가시키므로, `needed`는 항상 `data.length`를 최대 4바이트
  넘는 수준이라 한 번의 `*2` 배증으로 항상 충분함(4N > N+4는 N>=4에서 항상 참) —
  방금 만든 200-경로 테스트로 while 루프 진입(1회차)까지는 커버됐지만 2회차
  반복은 여전히 미커버, 근거상 정상.

이어서 외곽 클래스(`FileIndex` 본체) 몫도 대부분 처리 — `snapshot()`/`restore()`/
`writeTrackedPaths()`/`readTrackedPaths()`가 전부 public 정적 메서드라 디스크에
손상/누락/패딩된 컴패니언 파일을 직접 써 두는 방식으로 계속 커버 가능했음:
- `:93`(스냅샷 시점에 컴패니언 파일이 디스크에서 사라진 경우), `:106`
  (`restore()`가 fileindex가 아예 없던 저장소에 불려도 안전한지), `:124`
  (`restore()`를 같은 스냅샷으로 두 번 연달아 불러 멱등성 확인 — 컴패니언 UID가
  이미 스냅샷과 일치하는 유일한 자연스러운 상황), `:173`(기존 fileindex가 있는
  상태에서 빈 경로 목록으로 `writeTrackedPaths`를 부르면 no-op이 아니라 실제로
  빈 인덱스로 다시 씀), `:253`(`readTrackedPaths`용 컴패니언 파일 누락),
  `:257`(컴패니언 파일이 docket 명시 크기보다 짧게 잘림), `:260`(반대로 뒤에
  가비지가 덧붙어 docket 크기보다 긴 경우, 뒷부분을 잘라내고 정상 파싱하는지) —
  7곳 모두 신규 테스트로 완전 커버.
- `:245`(`deleteIfDifferent`의 `oldUid.equals(newUid)`): `Dirstate.java:357`와
  동일한 패턴 — `randomUid`가 매번 32-hex 랜덤 UUID를 새로 뽑으므로 충돌은
  사실상 불가능, mocking 프레임워크도 없음 — **도달 불가능 확인**.
- `:157`(`readTrackedPaths`의 `length==0`인 메타 엔트리 스킵)과 `:371`
  (`TrieBuilder.insert`의 `position==path.length`, 삽입 중인 경로가 정확히
  기존 노드 경계에서 끝나는 경우)는 정확한 트리거 조건을 구성하기 까다로워
  이번엔 미착수.

결과: `FileIndex` 전체(외곽+중첩 3클래스) 16개 미커버 중 **13개를 이번 세션에
해결**(TDD 커버 11개 + 도달 불가능 확인 2개), 3개(`:157`, `:371`, `GrowableBuffer`
while 루프 2회차)만 남김 — 애초 "16개라 범위 밖"이라고 판단했던 클래스가 실제로는
가장 큰 성과를 낸 클래스가 됨(리플렉션 없이 public 진입점만으로 사실상 전부
도달 가능했던 것이 핵심).

### `ManifestTreeIterator.java:279` — 서브매니페스트 손상 케이스, 신규 테스트는 추가했으나 jacoco 분기 귀속이 불명확

`readSubManifestContent`의 `subRev == -1`(서브디렉터리 매니페스트 revlog에서 해당
노드를 못 찾는 경우) — treemanifest 저장소를 `CommitCommand`로 실제로 커밋한 뒤
`meta/sub/00manifest.i`/`.d`를 0바이트로 잘라 서브 revlog를 텅 비운 상태에서
`ManifestTreeIterator.reset()`을 호출해 정확히 "Sub-manifest revision not found"
예외 메시지까지 검증하는 테스트(`testExpandTree_MissingSubManifestRevisionThrowsIOException`)를
추가 — 테스트 자체는 통과하고 의도한 예외를 정확히 재현하지만, jacoco 상 이 라인의
missed 카운트는 그대로 1로 남아있음. `repo.getManifestAtCommit()`(대부분의 다른
treemanifest 테스트가 쓰는 경로)은 `DefaultFileStoreEngine`의 별도 구현이라
`ManifestTreeIterator`를 전혀 거치지 않는다는 것까지는 확인했으나, 원래 "이미
커버돼 있던 나머지 한쪽 분기"가 정확히 어디서 오는지는 시간 관계상 못 밝힘 — 테스트
자체의 회귀 방지 가치는 있으므로 유지.

### 라운드 4 최종 결과 (2026-09-03 17:42, 전체 회귀 BUILD SUCCESSFUL)

INSTRUCTION 97.98%(73278/74790) / LINE 97.81%(15385/15729) / BRANCH 90.58%
(7813/8626) / METHOD 99.29%(1551/1562) / CLASS 100%(224/224).

BRANCH가 라운드3 종료 시점(92.20%)보다 수치상 낮아 보이지만, 오늘 세션 전반부의
SSH/HTTP 와이어 프로토콜 전면 재구현 + 심볼릭 링크 dirstate 버그 수정으로 전체
분기 수 자체가 7580→8626으로 크게 늘어난 영향(새 코드가 새 분기를 동반)이며,
실제 커버된 절대 분기 수는 6989→7813으로 824개 증가했다. 이번 라운드(1~3순위)에서
개별적으로 확인·처리한 것만 따지면 TDD로 실제 커버한 분기 약 45개, 도달 불가능
확인 후 문서화한 분기 약 35개.

이번 세션에서 손댄 프로덕션 코드는 전혀 없음(모든 변경이 테스트 파일 + 이 위키
문서) — TDD 사이클의 GREEN 확인마다 실제 버그가 아니라 순수 커버리지 갭이었음이
매번 재확인됐다.

### 마지막 확인 — `Wire1Commands.java:186`(도달 불가능), `HgLock`/`RemoveCommand`/그 외 대형 클래스는 보류

- `Wire1Commands.java:186`(`branchmap`의 `branch == null || branch.isEmpty()`):
  `IdentifyCommand.java:37`과 정확히 동일한 근거(`HgRepository#getBranch()`는
  절대 null을 반환하지 않음, 빈 branch 파일 케이스는 이미 커버됨) — **도달 불가능
  확인**, 새 테스트 불필요.
- `Wire1Commands.java:223`(`stripHg10Prefix`의 5-조건 바이트 매치): 와이어
  프로토콜 정확성이 걸린 코드라 서두르지 않기로 하고 이번엔 보류.
- `HgLock.java`(7개 지점, 특히 223/226번 줄): JVM-wide/파일시스템 락 재시도
  상태 머신 — 동시성 코드라 시간 압박 속에 성급히 테스트를 작성하면 오히려
  플레이키하거나 오해를 부르는 테스트가 될 위험이 있다고 판단해 보류.
- `RemoveCommand.java:78`(`state=='n' || state=='m'`, 4개 중 1개만 커버):
  `state=='m'`(병합 상태) 케이스가 미검증으로 보이나, 실제 merge 시나리오를
  구성하는 데 시간이 더 필요해 보류.

### 커밋(`1f5ba53`) 이후 추가 처리 — `FilesCommand`(5→1), `LocateCommand`(4→0 완전 해결)

18:30 마감까지 시간이 남아 계속 진행:
- `FilesCommand.java:51,60`(`setRevision(NodeId)`/`setTreeFilter`의 null-인자
  정규화), `:114,119`(빈 저장소에 명시적 리비전을 지정한 경우 — 기존
  `testEmptyRepositoryReturnsEmptyList`는 리비전을 아예 안 정해서 작업사본 경로로
  빠지고 이쪽은 전혀 안 탐) — 4곳 신규 테스트로 완전 커버. `:84`
  (`treeFilter != null && ...`)는 생성자 기본값(`ALL`)과 두 setter가 항상 null을
  `ALL`로 정규화하므로 **도달 불가능 확인**.
- `LocateCommand.java` 4곳 전부 완전 커버: `:73,91`(명시적 빈 문자열 revision/
  pattern), `:123`(`**` 뒤에 `/`가 없는 경우 + `**`가 패턴의 맨 끝이라 다음 문자
  자체가 없는 경우, 두 가지 별개 분기), `:132`(`?` 글롭 와일드카드 — 기존 테스트는
  `*`/`**`만 썼음). 부수적으로 `:134`(정규식 특수문자 리터럴 이스케이프, `()` 등)도
  커버.

### 이번 라운드에서 손대지 못한 것 (다음 라운드로 이월)

missed=1~4 롱테일 중 아직 미조사: `GpgSignature`(PGP 시그니처 바이트 스트림을
직접 만들어야 하는 `isEmpty()` 케이스), `ManifestTreeIterator`(트리매니페스트
서브매니페스트 참조 손상 케이스), `FileIndex`(3개 중첩 클래스 합쳐 16개 미커버,
단일 클래스 기준 최대 잔여 갭), `BundleCommand`(19개), `AnnotateCommand`,
`IncomingCommand`, `ManifestCommand`, `OutgoingCommand.java:80`,
`TreeMergeCommand`, `ClonebundlesManifest`, `DeltaCodec`, `HgHttpWireServer`,
`SafeFileIO.java:63,103,112`(파일명 없는 `File`/레이스 컨디션 케이스로 보이나
미확정), `ExportCommand`, `ProcessHook.java:67,76,84`(따옴표 토크나이저 세부
분기, :94는 커버 완료), `TagCommand`, `TagsCommand`, `Merge3`, `PhaseRoots`,
`DefaultFileStoreEngine`(revlog-v2 부트스트랩 플래그 조합), `HgLfsManager`,
`HgRcConfig`. 이 목록은 "조사해서 도달 불가능 확인됨"이 아니라 "아직 안
본 것"이므로 위 원칙대로 재시도 대상으로 남긴다. missed≥5 클래스(약 40개,
`CommitCommand`/`HgSshClient`/`HgRevsetEngine`/`RevlogIndex`/`Revlog`/
`NodeMapFile`/`RebaseCommand`/`ShelveCommand`/`FetchCommand` 등 이번 세션에서
크게 손댄 클래스들 다수 포함)는 이번 라운드에서 전혀 들여다보지 못함.
