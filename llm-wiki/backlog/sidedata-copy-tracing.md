---
updated: 2026-09-04
status: completed
---

# 백로그 17, 19, 27: Sidedata copy-tracing (읽기/쓰기) 및 log --follow/annotate 연동

관련 항목: 17(sidedata `SD_FILES` decode/조회 — `SD_P1COPIES` 등 레거시 상수는 실제
hg 소스에 죽은 코드로만 존재함을 확인), 19(`SD_FILES` writer, 커밋 시 hg4j 스스로
sidedata 생성 — v2 docket `sidedata_end` 필드 미갱신 버그, changelog-v2 압축모드 하드코딩
버그 2건 부수 발견), 27(`hg log --follow`/`annotate` 연동 — 조사 결과 real hg의
`--follow`/`annotate` 자체가 기본 설정에서는 `SD_FILES` sidedata를 전혀 읽지 않고,
별도의 filelog 수준 `copy`/`copyrev` 메타데이터가 진짜 메커니즘임이 밝혀져 그 계층에서
연동 완료 — 상세는 item 17 본문 "남은 gap" 절 참고, 27번은 독립된 문서 항목 없이 이
사실이 발견 당시 17번 항목에 직접 기록됨).

## 원문
17. ~~**Sidedata 실제 활용(copy-tracing 등)**~~ — ✅ **decode/조회 완료(2026-09-03)**.
    `mercurial/revlogutils/sidedata.py`/`metadata.py` 실측 결과, 백로그 노트가
    언급한 `SD_P1COPIES`/`SD_P2COPIES`/`SD_FILESADDED`/`SD_FILESREMOVED`는 실제
    hg 7.2 소스에 정의만 있고 아무 데서도 생성/소비되지 않는 죽은 코드임을 확인 —
    실제로 쓰이는 건 `exp-copies-sidedata-changeset` requirement 하의 단일
    `SD_FILES` 키(추가/삭제/병합/salvage/touch 플래그 + p1/p2 복사 출처, `hg
    debugchangedfiles`로 노출)뿐이었다. LFS는 `mercurial/lfs/` 소스 확인 결과
    포인터 파일 확장이라 sidedata와 무관해 범위에서 제외. 바이트 레이아웃: index
    레코드의 sidedata offset/complen/compression-mode(3비트, PLAIN/DEFAULT-zstd/
    INLINE) 필드(hg4j가 필드는 갖고 있었지만 값을 파싱 안 하고 있었음) → outer
    컨테이너(`count:u16` + entry별 `key:u16,length:u32,sha1:20B`) → `SD_FILES`
    payload(`totalFiles:u32` + entry별 `flag:byte,fileEnd:u32,copyIdx:u32` +
    파일명들). `RevlogIndex`/`Revlog`가 세 필드를 실제로 파싱하도록 수정, 신규
    `storage.SidedataCodec`(컨테이너 디코드) + `api.ChangingFiles`(`SD_FILES`
    디코드 + `getCopySource(path)`) + 포셀린 `api.SidedataChangedFilesCommand`
    (dirstate 기반 `CopyCommand`의 과거-리비전 조회 보완). 로컬 hg만으로 검증
    (`exp-copies-sidedata-changeset`은 Rust 불필요), 실제 3커밋 저장소를
    `src/test/resources/fixtures/sidedata-copytracing/`에 fixture로 확보해
    `hg debugchangedfiles <rev>` 출력과 대조 검증(`SidedataCopyTracingTest`,
    `SidedataChangedFilesCommandTest`, 8건). **남은 gap**: ~~커밋 시점에 hg4j가
    `SD_FILES`를 직접 쓰는 writer는 미구현~~ — ✅ 백로그 19번에서 완료. ~~`hg log
    --follow`/annotate 연동은 여전히 미배선~~ — ✅ 백로그 27번에서 완료(2026-09-04)
    — 단, 백로그 27번 조사 결과 real hg의 `--follow`/`annotate` 자체가 기본
    설정에서는 이 `SD_FILES` sidedata를 전혀 읽지 않는다는 것이 밝혀져(별도의
    filelog 수준 `copy`/`copyrev` 메타데이터가 진짜 메커니즘), 실제 연동은 그
    계층으로 이뤄졌다 — 상세는 백로그 27번.
19. ~~**Sidedata `SD_FILES` writer(커밋 시 쓰기)**~~ — ✅ **완료(2026-09-03)**.
    `SidedataCodec.serialize`(바깥 컨테이너 인코딩, 기존 `deserialize`의 역방향)와
    `ChangingFiles.encode`(added/removed/touched + copiedFromP1/P2 → `SD_FILES`
    페이로드, 파일명 알파벳 정렬 + flag 비트 조합, 기존 `decode`의 역방향) 신설,
    `CommitCommand`에서 `repository.isSidedataCopies()`(신규 accessor,
    `exp-copies-sidedata-changeset` requirement)일 때 기존 파일 추적 루프에서
    added/removed/touched/copy 정보를 수집해 인코딩 후 `Revlog.appendRevision`의
    새 `sidedataContainer` 파라미터로 전달. `Revlog.appendRevisionV2`가
    `.sda` 파일에 append하고 인덱스 레코드의 sidedata offset/length 필드를 채운다.
    **의도적 단순화(문서화)**: `merged`/`salvaged` 두 액션 분류(병합 상태 전용
    세부 구분)는 `CommitCommand`가 현재 별도로 추적하지 않아 항상 빈 집합으로
    전달 — added/removed/touched 3분류와 copy-tracing(이 백로그의 핵심 목적)은
    완전히 지원됨.
    **버그 발견·수정**: 구현 중 real hg로 검증하다가 실제 결함을 하나 발견 —
    v2 docket 헤더의 `sidedata_end`/`pending_sidedata_end` 필드(offset 42~58,
    기존 `updateV2DocketSizes`가 index_end/data_end만 갱신하고 이 두 필드는
    손도 안 대고 있었음)를 갱신하지 않으면, `.sda` 파일에 바이트를 실제로 다
    append했어도 real hg가 **파일 자체 크기가 아니라 이 docket 필드를 신뢰**해서
    `"cannot read from revlog ...sda; expected N bytes from offset M, data size
    is <stale값>"`로 거부한다(hg4j 자체 read 경로는 이 필드를 안 쓰고 파일을
    직접 읽어서 자기 자신에게는 정상으로 보였음 — 자기일관성만으로는 못 잡는
    전형적 사례). `updateV2DocketSizes`에 3-인자 오버로드를 추가해 수정.
    **발견 당시 인접 갭이었으나 같은 날 별도로 완료됨**: hg4j는 `exp-changelog-v2`
    저장소를 처음부터(`Hg.init()`) 생성하는 경로가 당시엔 전혀 없어서, 이 항목
    검증은 실제 local hg CLI로 rev0을 만들고 그 위에 hg4j가 rev1을 이어붙이는
    방식으로 진행했다(백로그 19가 실제로 다루는 시나리오와는 일치하지만, hg4j
    스스로 저장소를 처음부터 만드는 건 별도 갭으로 문서화해뒀었다) — ✅ 바로 이어서
    **완료(2026-09-03)**. `RevlogIndex.initializeNewV2Docket(boolean
    asChangelogV2)`로 기존 `exp-revlogv2.2` 부트스트랩 로직을 일반화(도켓/컴패니언
    파일 바이트 구조는 완전히 동일, magic값과 `isChangelogV2()`만 다름),
    `DefaultFileStoreEngine.getRevlog()`가 파일명이 정확히 `00changelog.i`이고
    `repository.isChangelogV2()`이며 파일이 아직 없을 때만 이 경로를 요청하도록
    배선(`exp-changelog-v2`는 매니페스트/파일로그가 아니라 changelog에만 적용되는
    좁은 requirement이므로).
    **이 작업 중 실제 심각한 버그를 하나 더 발견·수정**: `appendRevisionV2`의
    CL_V2(changelog-v2) 분기가 압축 모드를 항상 `COMP_MODE_DEFAULT`(zstd)로
    하드코딩하고 있었는데, 실제 hg는 리비전마다 **동적으로** zstd 압축이 실제로
    콘텐츠를 줄였을 때만 DEFAULT를, 안 줄었으면(작은 커밋 메시지 등) 원본 그대로
    `COMP_MODE_PLAIN`(마커 바이트 없이)을 쓴다 — 기존 hg4j 코드는 이때
    `DeltaCodec.compress`의 **v1 revlog 전용 관례**(`'u'`+원본바이트 폴백 마커)를
    그대로 재사용하면서 레코드의 압축모드 바이트는 여전히 DEFAULT로 잘못 표시해,
    real hg가 `'u'`로 시작하는 바이트열을 zstd 프레임으로 오인해 `"zstd
    decompressor error: Unknown frame descriptor"`로 거부하는 상태였다(실제 hg
    픽스처 `sidedata-copytracing/data.idx`의 3개 리비전 중 2개가 compbyte=0x00
    PLAIN임을 직접 바이트 단위로 대조해 확정). 이전 세션들의 changelog-v2 관련
    테스트가 전부 우연히 "충분히 긴" 콘텐츠만 다뤄서 이 버그를 피해갔던 것 — 이번
    작업의 짧은 첫 커밋(rev0)에서 처음 노출됨. `Zstd.compress` 결과가 원본보다
    작을 때만 그 결과+COMP_MODE_DEFAULT(1)를, 아니면 원본 그대로+COMP_MODE_PLAIN(0)를
    쓰도록 동적 선택으로 수정.
    검증(`ChangelogV2BootstrapTest`, GREEN): (1) hg4j가 처음부터 만든
    `exp-changelog-v2` 저장소에 2회 커밋(자체 read 경로로 왕복 확인), (2) **real
    local hg가 hg4j로 A부터 Z까지 만든 저장소에서 `hg verify`/`hg log` 둘 다 성공**
    (수정 전엔 정확히 이 시나리오에서 압축 버그가 터졌었음). storage/api/transport
    패키지 전체 회귀(963개 중 3개 실패 — 전부 기존에도 있던 무관한 동시성/타이밍
    플레이키로 단독 재실행 시 통과 확인) GREEN.
    검증(`SidedataFilesWriteTest`, GREEN): local hg(Rust 불필요, 백로그 17에서
    이미 확인된 대로)로 `format.exp-use-copies-side-data-changeset=yes`
    저장소를 만들고 rev0 커밋 → hg4j가 rename(copy+remove)+신규파일 추가로 rev1
    커밋 → hg4j 자체 `SidedataChangedFilesCommand`로 정확히 디코드 확인 +
    **real hg `hg debugchangedfiles 1`/`hg verify`가 hg4j가 쓴 바이트를 그대로
    정확히 읽음**(`removed: a.txt; added p1: b.txt, a.txt; added: c.txt` 실측
    출력 일치). storage/CommitCommand/sidedata 관련 패키지 전체 회귀+api 패키지
    전체 회귀(963개 중 962개 GREEN, 유일한 실패는 기존에도 있던 무관한 타이밍
    플레이키 `ProcessHookTest`) 확인.
