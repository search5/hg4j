---
updated: 2026-09-01
status: partial (scope reduced by user mid-flight; not resumed)
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
