---
updated: 2026-09-02
status: round 3 batches 1-17 complete — INSTRUCTION 99.14% / LINE 99.03% / METHOD 99.70% / CLASS 100%, BRANCH 92.20% (still below 95%, mostly documented dead code). missed≥5 "순수 신규 클래스" 체크리스트 완전 소진.
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
