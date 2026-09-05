---
updated: 2026-09-06
status: completed (4/15/21/35/43 전부 완료; 45번은 43번 작업 중 발견된 신규 미착수
  항목)
---

# 백로그 4, 15, 21, 35, 43, 45: Revlog 저장 포맷 확장 (v2 general/persistent-nodemap/fileindex-v1/inline)

관련 항목: 4(Revlog v2 일반 + persistent-nodemap + fileindex-v1 읽기/쓰기 — 이 개발
환경의 Rust 확장 없는 hg로는 저장소 생성 자체가 안 되는 포맷들, `hg-rust-7.2.4` Docker
이미지로 해결), 15(persistent-nodemap `.n` 트라이 파일 가속 읽기), 21(persistent-nodemap
`.n` 파일 쓰기), 35(revlog가 항상 non-inline으로만 쓰여 `hg verify` 경고를 내던 문제 —
inline 레이아웃 지원), 43(revlog가 성장해도 inline→non-inline 전환을 안 함,
`_enforceinlinesize` 상당 로직 구현, ✅ 완료), 45(43번 작업 중 발견 — `CommitCommand`가
treemanifest 하위 manifest를 fncache에 등록 안 함, 미착수).

## 원문
4. ~~**Revlog v2 일반(`exp-revlogv2.2`, 매니페스트/파일로그) + persistent-nodemap**~~
   — ✅ **완료(2026-09-02, persistent-nodemap 읽기 가속은 2026-09-03에 추가 완료 —
   백로그 15번 참고)**.
   이 개발 환경의 hg 바이너리가 Rust 확장 없이는 이 포맷의 저장소 자체를 생성하지
   못해(`abort: accessing ... without associated fast implementation`) 막혀
   있었는데, `docker/hg-rust-7.2.4/Dockerfile`로 Rust 확장이 활성화된 실제
   Mercurial 7.2.4를 직접 빌드해(pip sdist + `setup.py --rust build_ext --inplace`
   + `HGMODULEPOLICY=rust+c`) 이 문제를 해결하고 진행했다. 구현하며 알게 된 것:
   - hg4j의 기존 `RevlogIndex`/`Revlog` **읽기** 경로는 이미 general v2를 올바르게
     지원하고 있었다(코드 주석은 "changelog-v2만 지원"이라고 돼 있었지만 stale) —
     changelog-v2와 general v2는 docket 헤더(59바이트 `S_HEADER`)와
     `{radix}-{uuid}` 컴패니언 파일 규약은 동일하고, 96바이트 index 레코드
     레이아웃만 다르다(general v2는 baseRev/linkRev/parent1/parent2를 전부
     명시적으로 저장, node@32, rank 없음; changelog-v2는 baseRev/linkRev를
     저장 안 하고 rev 값으로 합성, node@24, rank 있음).
   - **쓰기** 경로는 실제로 미구현이었다 — `Revlog.appendRevisionV2`가
     `index.isChangelogV2()`로 분기해 general v2도 처리하도록 확장(항상
     `COMP_MODE_PLAIN` 풀텍스트로 쓰고 델타는 안 함 — 스펙상 유효하나 실제 hg보다
     덜 효율적), `RevlogIndex`에 브랜드 뉴(한 번도 존재한 적 없는) revlog가
     저장소 요구사항상 v2여야 할 때 처음부터 v2 docket으로 초기화하는 생성자
     추가.
   - **`fileindex-v1`**은 원래 백로그 문서에 전혀 없던, `exp-revlogv2.2` 저장소가
     fncache 대신 쓰는 완전히 새로운 바이너리 포맷(방사 트라이, docket +
     list/meta/tree 3개 컴패니언 파일)이었다 — 발견 후 사용자에게 범위 확장을
     알리고 "읽기+쓰기 전체 구현" 승인을 받아 진행. 이 개발 환경에 Rust 없는
     Mercurial의 소스 트리(`/usr/lib/python3/dist-packages/mercurial/store_utils/
     file_index_util.py`)가 pure-Python 참조 구현으로 존재해 그것을 직접 포트했다
     (`FileIndex.java`). 다만 쓰기 전략은 실제 hg의 `MutableTree`가 쓰는 증분
     copy-on-write append(기존 트리 파일 뒤에 이어붙이고 주기적으로만 "vacuum"으로
     전체 재빌드) 대신, **매번 전체를 새 UUID로 재빌드하는 단순화된 전략**을 쓴다
     — 이는 실제 hg 자신의 vacuum 결과와 바이트 단위로 동일한 형태이므로 스펙상
     완전히 유효하지만, 커밋을 거듭할수록 실제 hg보다 디스크 낭비가 크다(첫 구현
     범위에서는 정확성을 우선, 최적화는 후속 과제).
   - 검증: `RevlogV2GeneralParserTest`(6 tests, 실제 fixture 바이트 기준
     docket/index 파싱) + `FileIndexTest`(8 tests, 실제 fixture 읽기 +
     hg4j 자체 왕복 + snapshot/restore 롤백) + 수동 Docker round-trip(hg4j
     `CommitCommand`로 기존 파일 수정 커밋과 **브랜드 뉴 파일** 커밋 둘 다 실행 후
     같은 저장소를 `hg-rust-7.2.4` 컨테이너의 `hg verify`/`hg log --debug`/
     `hg cat`/`hg files`로 전부 경고 없이 통과 확인 — fileindex 연동 전에는
     `hg verify`가 "uses revlog format 1; expected 57005" +
     "not in file index!" 두 경고를 냈으나 연동 후 둘 다 사라짐).
   - 픽스처: `src/test/resources/fixtures/revlogv2-general/`
     (`README.md`에 노드 해시/UUID/정확한 바이트 상세 기록).
15. ~~**persistent-nodemap(`.n` 트라이 파일) 가속 조회 — 인식만 하고 실제 조회는 항상
    순차 스캔 fallback**~~ — ✅ **읽기(가속 조회) 완료(2026-09-03)**. Docker
    `hg-rust-7.2.4`(이 환경의 Rust 미포함 시스템 hg는 이 requirement의 저장소
    자체를 못 만듦)로 40커밋 실제 저장소를 만들어 `.n` docket(62바이트)과
    `-<uid>.nd` 트라이(64바이트 블록, 16×4바이트 빅엔디안 signed int, 루트는
    항상 마지막 블록)를 `mercurial/revlogutils/nodemap.py` 실측 레이아웃대로
    파싱하는 `storage.NodeMapFile` 신설 — 실제 hg가 만든 바이트로 40/40 노드
    해시 일치 검증(`NodeMapFileFixtureTest`, 시스템 python의 pure-python
    `mercurial.revlogutils.nodemap` 모듈로 독립 재검증까지 포함). `RevlogIndex`에
    신선한(tip_rev/tip_node가 현재 인덱스와 일치) 비-inline 트라이가 있으면 전체
    레코드 스캔을 건너뛰고 오프셋을 산술 계산(`rev*64`)하는 fast path를 추가,
    `findRevision()`이 트라이를 먼저 조회하되 항상 실제 레코드로 재검증한 뒤
    반환(트라이 자체는 부재 노드를 실재하는 다른 노드로 오인할 수 있다는
    `mercurial/revlogutils/nodemap.py`의 알려진 특성 때문 — 이 재검증이 그 허점을
    막는다), stale/부재 시 기존 순차 스캔으로 안전하게 fallback
    (`RevlogIndexPersistentNodeMapTest`, stale-트라이 케이스 포함). `findByHexPrefix`는
    트라이가 전체 노드 해시를 저장하지 않아(접두사 disambiguation에 필요한 만큼만
    저장) 가속 대상에서 제외 — 최초 호출 시 지연된 맵을 1회 materialize해 이후부턴
    기존 방식과 동일하게 동작. **쓰기(`.n` 갱신)는 이 시점엔 미구현이었으나 이후
    백로그 21번에서 완료됨** — 상세는 이 문서 위쪽의 persistent-nodemap gap table
    행과 백로그 21번 참고. 전체 회귀 2231 테스트, 실패 0(이 완료 시점 기준).
21. ~~**persistent-nodemap `.n` 파일 쓰기(커밋 시 갱신)**~~ — ✅ **완료(2026-09-03)**.
    `mercurial/revlogutils/nodemap.py`를 실측(전체 재빌드 `_build_trie`/`_persist_trie`/
    `_walk_trie`, 증분 갱신 `_update_trie`/`_insert_into_block`, docket 직렬화, 실제
    hg의 "새 길이가 unused*10 이하면 포기하고 전체 재빌드로 폴백" 10% 임계값까지)해서
    `NodeMapFile`에 두 경로 모두 구현. `Revlog`의 리비전 append 진입점 5곳(v1/v2
    changelog/일반 revlog, changegroup 적용, raw/optimized 경로) 전부가 공통으로
    거치는 유일한 지점 `RevlogIndex.addRecord()` 직후에 단일 훅
    (`updatePersistentNodeMapAfterAppend()`)을 걸어 changelog/manifest/filelog 전체에
    자동 적용(개별 명령 코드는 전혀 건드리지 않음 — `DefaultFileStoreEngine`이 이미
    모든 Revlog를 `repository.isPersistentNodemap()` 플래그로 균일하게 생성하고
    있었던 기존 아키텍처 덕분). requirement 미설정이거나 inline revlog면 즉시
    no-op(기존 동작 100% 보존). 검증 3단계: (1) 백로그 15번의 실제 hg-rust-7.2.4
    픽스처(40리비전)로 전체 재빌드 후 40개 노드 해시 전부 정확히 조회됨, (2) 10→25→40
    3단계 증분 확장에서 매 단계 정확 + uid 보존(진짜 증분 경로임을 증명), (3) **hg4j로
    브랜드뉴 저장소를 만들어 persistent-nodemap requirement를 켜고 실제
    `CommitCommand`로 8회 커밋 → 실제 Rust 확장 hg-rust-7.2.4(Docker)에 그대로 넘겨
    `hg verify`/`hg log` 둘 다 성공 확인**(`NodeMapFileWriterTest`, 4건 GREEN). storage
    패키지 전체 회귀 및 CommitCommand 관련 회귀(212개 테스트, 무관한 기존 심볼릭링크
    타이밍 플레이키 1건 제외 전부 GREEN, 단독 재실행 시 그것도 통과)로 기존 동작
    무손상 확인.

35. ~~**Revlog 쓰기 경로가 항상 non-inline이라 `hg verify`가 fncache 경고를 냄**~~
    — ✅ **완료(2026-09-04)**. 신규 발견(백로그 23번 strip 카테고리 검증 중 발견,
    strip과 무관한 사전 존재 이슈라 별도 항목으로 승격). 실제 hg 스펙 확정
    (`mercurial/revlog.py` 소스 직접 대조, 이 호스트 Homebrew 설치본
    `/opt/homebrew/lib/python3.14/site-packages/mercurial/revlog.py`): revlogv1은
    `REVLOG_DEFAULT_FLAGS = FLAG_INLINE_DATA`로 **기본이 inline**이고
    `_enforceinlinesize()`가 총 크기가 `_maxinline = 131072`바이트(128KiB)를
    넘어야만 별도 `.d` 파일로 분리한다. changelog만 유일하게
    `mercurial/changelog.py`에서 `may_inline=False`로 생성돼 항상 non-inline —
    hg4j의 changelog 처리는 이미 이 부분과 일치했다(안 건드림).

    **1차 시도(같은 세션 초반)와 되돌린 이유**: `Revlog` 생성자에서 신규 v1
    filelog/manifest를 `inline=true`로 시작하게만 바꿨더니 전체 회귀에서 60개
    테스트가 깨짐 — `appendChangeGroupEntry()`(pull/push로 원격 changegroup을
    적용하는 경로)가 `appendRevision`과는 별개의 자체 `if (inline)` 분기를 갖고
    있었는데 그게 실제로 `this.inline`을 전혀 확인하지 않고 **항상 non-inline
    레이아웃으로 하드코딩**돼 있어(offset을 `datFile.length()`로 계산, format
    flags도 non-inline 값 고정) inline 저장소에 pull/push로 리비전이 들어오면
    `"Failed to read complete hunk of size 20 at offset 64"`로 데이터가 실제로
    손상됐다. 1차 시도 때는 이 사실만 확인하고 안전하게 원상 복구·문서화만 하고
    끝냈었다.

    **2차 시도(사용자가 명시적으로 "계속하라"고 지시, 2026-09-04, 같은 세션
    후속)**: `appendChangeGroupEntry()`의 v1 수동 바이트 라이팅 경로를
    `appendRevision()`이 이미 쓰던 것과 동일한 inline/non-inline 분기 패턴으로
    재작성(offset을 `prevRec.getOffset()+getCompLen()`으로 계산, inline이면
    64바이트 레코드+dataHunk를 `idxFile`에 이어쓰기, non-inline이면 기존처럼
    `datFile`에 씀). 조사해보니 `appendRawRevision()`/`appendOptimizedRevision()`
    은 이미 세션 초반(RebaseCommand 백업·복원 버그 수정 때) 올바른 inline 분기가
    붙어 있었던 것으로 확인돼 손댈 필요 없었음 — 실제 미해결 지점은
    `appendChangeGroupEntry` 하나뿐이었다. `Revlog` 생성자도 신규 v1 filelog/
    manifest(파일명에 `00changelog` 미포함, 그리고 idxFile이 아직 존재하지 않는
    "진짜 새 리비전 로그"인 경우만)를 `inline=true`로 시작하도록 재적용.

    **검증**: 신규 `RevlogInlineWriteRealHgInteropTest`(pull로 적용된 filelog가
    실제로 inline인지, real `hg verify`가 fncache 경고 없이 깨끗한지) GREEN.
    전체 회귀에서 처음엔 17건 실패 — 전부 "이 store는 항상 `.i`/`.d`로 분리된다"는
    낡은 전제를 하드코딩한 기존 테스트들(`BackoutCommandCoverageTest`,
    `CommitCommandCoverageTest`, `HisteditCommandCoverageTest`,
    `ShelveCommandCoverageTest`, `StatusCommandTest`, `StripCommandCoverageTest`,
    `RevlogTest`)이었지 실제 데이터 정합성 문제는 아니었음 — 각 테스트를 "필요한
    파일의 `.i`를 미리 빈 파일로 touch해서 non-inline을 강제"하거나(대부분),
    "테스트 이름이 원래 의도한 대로 애초에 없어도 되는 시나리오"로 조정(Strip 2건)
    하거나, `Files.delete`를 `deleteIfExists`로(파일이 아예 없어도 되는 경우), 또는
    잘못된 가드 조건(`clDat.exists()`가 아니라 `mfDat.exists()`를 봐야 했던 버그
    1건)을 고쳐서 해결. 128KiB `_enforceinlinesize` 상당 로직(큰 파일은 새로
    커밋해도 non-inline으로 시작)은 이번에도 구현하지 않음(범위 밖, 별도 후속) —
    현재는 "새 revlog는 파일 크기와 무관하게 inline으로 시작"만 구현됨(실사용
    파일 대부분이 128KiB 미만이라 실질적 영향은 낮음). 전체 회귀 4회 재확인,
    최종 2268개 중 `PerformanceBenchmarkTest`(이 세션 내내 존재해온 무관한 2초
    타이밍 SLA 플레이키) 1건만 실패, 그 외 전부 GREEN.

43. ~~**Revlog가 성장해도 inline→non-inline 전환을 안 함 (`_enforceinlinesize`
    상당 로직 없음)**~~ — ✅ **완료(2026-09-06)**. 신규, 2026-09-04 문서 재감사로
    발견(백로그 35번 완료 본문에 번호 없이 있던 캐비어트를 승격).

    **real hg 실측**(호스트 real hg 7.2, `mercurial/revlog.py` 소스 직접 대조 +
    라이브 재현): `_enforceinlinesize(tr)`는 매 리비전 append 직후 호출되며,
    누적 데이터(`start(tiprev)+length(tiprev)`)가 131072바이트(`_maxinline`)
    이상이면서 현재 inline인 경우에만 전환한다. 라이브 재현(10000/30000/
    50000/70000바이트로 커지는 파일을 연속 커밋): 3번째 커밋까지 inline
    유지, 4번째(누적 160000바이트) 직후 즉시 `.d` 분리 확인. 단일 리비전이
    이미 임계값을 넘는 경우도 즉시 전환됨을 확인. `split_inline()`은 기존
    레코드 필드를 전혀 재계산하지 않고 그대로 재직렬화하며 rev0의 packed
    header에서만 inline 비트를 지운다.

    **pre-fix 영향 실측**(정상 결과를 손으로 역전시켜 pre-fix 산출물을 재현
    후 real hg 7.2로 검증): `hg verify` clean, `hg debugindex`/`hg log`/
    `hg cat` 전부 정상(내용 완전 일치), `hg clone`은 오버사이즈 inline
    레이아웃을 그대로 보존, `hg clone --pull`(와이어 경로)은 정상 읽되
    목적지에는 real hg 자신의 쓰기 경로가 재적용되어 "자가 치유"됨. **결론:
    데이터 손상/파싱 오류가 아니라 순수 포맷 충실도 문제**였음 — 웹훅 알림
    기준(데이터 손상/치명적 비호환) 미해당.

    **구현**: `Revlog`의 v1 write path 4개(`appendRevision`/
    `appendChangeGroupEntry`/`appendRawRevision`/`appendOptimizedRevision`,
    `appendRevisionV2`는 대상 아님) 전부에 `enforceInlineSize(rev)` 통일
    적용 → `splitInlineToNonInline()`이 기존 청크를 순서대로 새 `.d`에
    이어쓰고 `.i`를 고정 64바이트 레코드로 원자적 재작성, `RevlogIndex
    .clearCache()`로 재작성된 온디스크 상태를 다시 파싱해 모든 내부 부기
    갱신.

    **fncache 상호작용 버그 발견·수정(범위 축소 없이 완전 대응)**:
    `CommitCommand`/`FetchCommand`가 필드 등록된 `.i`만 fncache에 넣고
    `.d`는 넣지 않던 버그 — inline-by-default 이전에는 항상 처음부터
    non-inline이라 가려져 있었으나 이번 백로그가 처음 노출시킴. 둘 다
    `filelog.isInline() == false`일 때 `.d` 엔트리도 추가하도록 수정
    (`GcCommand`의 fncache 재구축 관례와 통일).

    **검증**: 신규 `RevlogTest`(순수 자바, 3건 — 소규모는 절대 `.d` 안 만듦
    회귀 가드, 4번째 append에서 정확히 전환+재오픈 후 콘텐츠 보존, 단일
    거대 리비전 즉시 전환) 전부 GREEN. 신규 `RevlogInlineTransitionRealHgInteropTest`
    (`@Tag("interop")`, real hg CLI, 2건) — hg4j 전환 결과 real hg
    `verify`/`debugindex` clean, real hg가 만든 이미 전환된 저장소를 hg4j
    `CloneCommand`로 정확히 clone+검증 — 전부 GREEN. 기존
    `RevlogInlineWriteRealHgInteropTest`(백로그 35) 회귀 없음. changelog는
    애초에 inline 대상이 아니므로 이 로직과 무관(확인만).

    **부수 발견, 이번 범위 밖으로 명시적으로 분리**: `CommitCommand`가
    treemanifest 하위 디렉터리 manifest(`meta/<dir>/00manifest.i`/`.d`)를
    fncache에 전혀 등록하지 않는 별개의 사전 존재 gap 발견 — 아래 45번으로
    승격.

45. **`CommitCommand`가 treemanifest 하위 디렉터리 manifest(`meta/<dir>/
    00manifest.i`/`.d`)를 fncache에 전혀 등록하지 않음**. 신규, 2026-09-06
    백로그 43번 작업 중 발견 — 미착수. `CommitCommand`는 파일이 바뀔 때마다
    `data/<path>.i`(+ 43번 수정 이후엔 `.d`도)를 `fncachePaths`에 추가하지만,
    treemanifest 저장소에서 디렉터리별로 쓰이는 `meta/<dir>/00manifest.i`/
    `.d`는 어디에도 등록하는 코드가 없다 — real hg의 `RE_FNCACHE_FILE`은
    `data/`와 `meta/` 둘 다 동일하게 추적 대상으로 삼으므로, treemanifest
    저장소에서 real hg `hg verify`를 돌리면 모든 디렉터리 manifest revlog에
    대해 "not in fncache" 경고가 날 가능성이 높다(코드 리딩으로만 발견,
    이번 백로그에서는 실측하지 않음). real hg CLI로 treemanifest 저장소를
    만들어 실제 fncache 내용과 대조 확인 후 `CommitCommand`(및 `FetchCommand`의
    treemanifest 매니페스트 그룹 적용 경로도 동일 패턴인지 함께 점검)에
    `meta/` 등록 로직을 추가할 것.

