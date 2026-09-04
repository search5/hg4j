---
updated: 2026-09-04
status: current
---

# 개념: Revlog

Mercurial의 파일별 히스토리 저장 포맷. Git의 pack-file과 달리 **파일마다 독립된 append-only
로그**(`.i` 인덱스 + `.d` 데이터, 또는 작은 파일은 inline으로 `.i`에 데이터 포함)를 사용한다.
→ [[storage]] 모듈의 `Revlog`/`RevlogIndex`/`DeltaCodec`, [[diff]] 모듈의 `DeltaEngine`이
구현체 (Track A 패키지 분리로 `core`에서 이관됨).

## 왜 Git과 다른가
- Git: 모든 객체를 pack-file로 묶고 주기적으로 GC(압축) 필요.
- Mercurial: revlog 자체가 자기완결적 로그라서 GC가 불필요 — README의
  "No Garbage Collection" 설계 원칙 참고 (`GcCommand`가 있긴 하지만 Git의 GC와 성격이 다름).

## 델타 생성
- Myers-diff / LCS 알고리즘으로 이전 리비전과의 델타를 계산 (`DeltaEngine.createDelta`).
- generaldelta 지원: 델타 베이스를 반드시 직전 리비전이 아니라 임의의 이전 리비전으로 선택
  가능 → 저장 공간 최적화.
- 압축은 zstd(`useZstdCompression`) 또는 기본 압축 중 선택.

## 2026-09-01: zstd requirement 문자열 버그 (발견·수정됨)
Revlog v2(changelog-v2) 작업을 실제 `hg` CLI로 검증하던 방법론을 v1에도 적용해보니
`InitCommand`(쓰기)와 `HgRepository.loadRequires()`(읽기)가 **둘 다 동일하게 잘못된**
requirement 문자열 `revlog-compression=zstd`(등호)를 쓰고 있었다. 실제 Mercurial
(`mercurial/requirements.py`)이 요구하는 문자열은 `revlog-compression-zstd`(하이픈)다.

- **영향**: `Hg.init().setUseZstd(true)`로 hg4j가 만든 저장소를 실제 `hg`로 열면
  `abort: repository requires features unknown to this Mercurial:
  revlog-compression=zstd`로 **완전히 거부됨**(재현 확인). 반대로 실제 hg가 만든
  zstd 저장소를 hg4j로 열면 `isUseZstdCompression()`이 항상 `false`를 반환해 hg4j가
  쓰는 새 리비전이 (틀린 압축 방식이지만 매직 바이트 자체 판별 덕에 읽기는 가능한)
  zlib/무압축으로 저장됨 — 데이터 손상은 아니지만 저장소가 선언한 압축 방식을
  따르지 않음.
- **왜 지금까지 못 잡았나**: `HgTestUtils.hg()`(네이티브 hg 상호운용 테스트 헬퍼)가
  호출할 때마다 `--config format.usezstd=false --config format.revlog-compression=zlib`를
  강제로 주입한다 — 즉 기존의 모든 "네이티브 hg 상호운용" 테스트가 **구조적으로
  zstd 경로를 피해가도록 설계되어 있었다.** 과거 어느 시점에 zstd 관련 상호운용
  문제를 만나고, 근본 수정 대신 테스트에서 zstd를 꺼버리는 식으로 우회한 것으로
  보인다.
- **수정**: `InitCommand`/`HgRepository` 양쪽의 문자열을 하이픈 버전으로 통일.
  `HgTestUtils.hg()`를 우회해 실제 `hg` 프로세스를 직접 호출하는 신규 테스트
  `HgPorcelainAndExceptionsTest#testZstdRepoIsReadableByRealHgCli`(`@Tag("interop")`)를
  추가해 회귀 방지 — 옛 버그 문자열로 되돌리면 이 테스트가 실패하는 것까지 확인함.
- **교훈**: "네이티브 hg 상호운용 테스트가 있다"는 사실이 "모든 경로가 실제 hg와
  검증됐다"는 뜻은 아니다 — 테스트 헬퍼 자체가 특정 기능(zstd)을 구조적으로
  회피하고 있을 수 있으니, 새로운 영역을 감사할 때는 helper의 고정 설정도 의심할 것.

## 알려졌던 이슈 (git log 기반, 이미 수정됨)
- **BUG-04**: Myers Diff 백트래킹 시 대각선(diagonal) 계산이 어긋나는 버그. 여러 차례 커밋에서
  반복 등장(`7f0c42d`, `7f08749`, `babdb2a`) — 다중 훈크(hunk) 델타에서 엣지 케이스가 많았던
  영역이라는 뜻이므로, 델타 관련 코드를 건드릴 때는 `DeltaEngineTest`/`DeltaCodecTest`를
  반드시 함께 확인할 것.
- **BUG-01/02/10**: 인라인 revlog 오프셋 계산 및 zstd 프레임 매직 바이트 오감지 버그.
- **BUG-10 계열**: Zstd 압축 바이트(`0x00` prefix) 표준화 문제 — raw 데이터와 zstd 프레임을
  구분하는 매직 바이트 판별 로직이 원인이었음 (`e865b1e` 커밋).
- 최근(`56b1988`) fncache 레이어 불일치 수정 — 파일명 인코딩(`encodeFname`, dh/ 하이브리드
  인코딩)과 revlog 물리 파일 경로 매핑 관련.

## 2026-09-02~03: 일반 revlog v2(`exp-revlogv2.2`) + fileindex-v1 + persistent-nodemap 완료
changelog-v2에 이어, 매니페스트/파일로그에도 쓰이는 **일반 revlog v2** 포맷을 완료했다.
기존 `RevlogIndex`/`Revlog` **읽기** 경로는 이미 general v2를 올바르게 지원하고 있었으나
(코드 주석은 "changelog-v2만 지원"이라 적혀 있었지만 실제로는 stale한 주석이었음),
**쓰기** 경로(`Revlog.appendRevisionV2`)는 미구현이었다 — 항상 `COMP_MODE_PLAIN` 풀텍스트로
쓰도록 확장(델타 압축은 하지 않음, 스펙상 유효하나 실제 hg보다 저장 효율이 낮음).

이 과정에서 `exp-revlogv2.2` 저장소가 fncache 대신 쓰는 **fileindex-v1**(방사 트라이 기반
바이너리 포맷, docket + list/meta/tree 3개 컴패니언 파일)이 새로 발견돼 `storage.FileIndex`로
구현했다 — 이 개발 환경의 Rust 미포함 Mercurial 소스 트리에 있는 pure-Python 참조 구현
(`store_utils/file_index_util.py`)을 직접 포트. hg4j의 쓰기 전략은 실제 hg의 증분
copy-on-write append 대신 매번 전체를 새 UUID로 재빌드하는 단순화된 방식을 쓴다(실제 hg
자신의 주기적 "vacuum" 결과와 바이트 단위로 동일한 형태라 스펙상 유효).

persistent-nodemap(`.n` 트라이 파일) 읽기 가속도 함께 완료됐다. 상세·픽스처는
`src/test/resources/fixtures/revlogv2-general/README.md`,
[[mercurial-spec-compliance-requirement]]의 백로그 4번/15번 참고.

## 2026-09: cg1 changegroup 델타 베이스 규칙 오류 (발견·수정됨, push를 깨뜨리던 버그)
Bookmark 작업(B-3) 검증 중, 다중 head 저장소를 pull하면 콘텐츠가 깨지는 문제를 재현하다가
cg1 changegroup을 만들 때 쓰는 델타 베이스 선택 규칙 자체가 틀려 있던 것을 발견했다. 실제
`cg1unpacker._deltaheader`의 규칙은 "그룹의 첫 엔트리는 직전에 패킹된 엔트리가 아니라
**자신의 진짜 p1**에 대해 델타를 뜬다"(`prevnode is None → deltabase = p1`)인데, hg4j는
"항상 직전 패킹 엔트리 기준"으로 잘못 구현되어 있어 실제 hg 서버에 push하면 HTTP 500으로
재현되는 심각한 버그였다. 상세는 [[mercurial-spec-compliance-requirement]] 참고.

## 관련 페이지
- [[dirstate]] — revlog에 커밋되기 전 워킹카피 상태
- [[bundle2-changegroup]] — 네트워크로 revlog 델타를 주고받는 포맷
- [[storage]], [[diff]] 모듈 페이지
- [[revlog-v2-support-plan]] — changelog-v2 지원 (2026-09-01 완료)
