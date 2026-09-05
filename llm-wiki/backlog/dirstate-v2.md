---
updated: 2026-09-06
status: completed
---

# 백로그 5, 37: Dirstate v2 바이트 레이아웃과 트리 구조 버그

관련 항목: 5(44바이트 NODE 구조체 정확한 바이트 레이아웃 — 실제 hg CLI로 만든 진짜
dirstate-v2 저장소 바이트를 직접 캡처해 대조, 3건의 실제 버그 발견), 37(dirstate-v2
저장소에서 hg4j 커밋이 기존 파일을 트리 구조에서 유실시키던 4번째 버그 — real hg의
Rust 리더가 자식 노드를 정렬된 이진 탐색으로 찾는데 hg4j가 정렬 없이 쓰던 문제).

**후속 발견(2026-09-05, 백로그 39 wave 5 작업트리 그룹)**: 위 4건과 별개로 **5번째
실제 버그**를 발견·수정 — `DirstateV2Node`/`DirstateV2Serializer`가 `MODE_EXEC_PERM`과
`MODE_IS_SYMLINK`를 상호 배타로 취급하고 있었다. real hg 7.2.4의 Rust 소스
(`mode_changed()`, `EXEC_BIT_MASK=0o100`)를 대조한 결과, 실제 심볼릭 링크의 `lstat`
모드는 항상 전체 권한 비트를 포함하므로 모든 심볼릭 링크는 항상 "실행 비트 있음"으로
관측된다 — 즉 심볼릭 링크 dirstate 항목은 두 플래그를 **항상 함께** 켜야 한다. hg4j가
상호 배타로 처리해 심볼릭 링크의 실행 비트를 항상 꺼버린 탓에, dirstate-v2 저장소에서
real hg의 `hg status`가 모든 심볼릭 링크를 "M"으로 오판하는 버그였다 — `AddCommand`/
`CommitCommand`/`MergeCommand`/`RebaseCommand` 등 dirstate-v2 + 심볼릭 링크를 다루는
기존 완료 명령에도 동일하게 영향을 미쳤을 수 있는 공유 계층 버그(병합 후 전체
비-interop 테스트 재확인 결과 회귀 없음 확인). 상세는 [[39-exhaustive-interop-matrix]]
및 [[symlink-handling]] 참고. 이 발견은 `known-bugs-registry.md`의
`DirstateV2Node`/`DirstateV2Serializer` 항목에도 색인돼 있다.

## 원문
5. ~~**Dirstate v2(44바이트 노드) 정확한 바이트 레이아웃 검증**~~ — ✅ **완료(2026-09-01)**,
   그리고 실제로 검증해보니 **3가지 진짜 버그**가 나왔다(실제 hg와 전혀 상호운용
   불가능한 수준):
   1. `DirstateV2Node`의 44바이트 NODE 구조체 필드 오프셋이 전부 틀림 — 실제 spec은
      `mercurial/dirstateutils/v2.py`의 `NODE = struct.Struct('>LHHLHLLLLHlll')`
      (path_start@0, path_len@4, basename_start@6, copy_source_start@8,
      copy_source_len@12, children_start@14, children_count@18,
      descendants_with_entry@22, tracked_descendants@26, flags@30, size@32,
      mtime_s@36, mtime_ns@40)인데, hg4j는 완전히 다른(한 번도 검증 안 된, 지어낸)
      오프셋을 쓰고 있었다 — hg4j 자기 자신과의 라운드트립만 우연히 통과할 뿐 실제 hg가
      만든 파일은 전혀 못 읽는 상태였다.
   2. flags 비트 값도 틀림 — 실제(`mercurial/pure/parsers.py`)는
      `HAS_MODE_AND_SIZE=1<<10`, `HAS_MTIME=1<<11`, `MODE_EXEC_PERM=1<<3`,
      `MODE_IS_SYMLINK=1<<4`인데 hg4j는 각각 `1<<3`/`1<<4`/`1<<5`/`1<<6`을 쓰고 있었다.
   3. **데이터 파일명 패턴이 `dirstate.d.<uid>`였는데 실제는 `dirstate.<uid>`**(".d"가
      끼어들 자리가 없음, `docket.py`의 `data_filename_pattern = b'dirstate.%s'`로 확인)
      — 이 하나만으로도 hg4j가 쓴 파일을 실제 hg가 못 찾고, 실제 hg가 쓴 파일을 hg4j가
      못 찾는 완전 단절 상태였다.

   검증 방법: Docker Mercurial 6.0(및 호스트 native hg 7.2.2 둘 다) +
   `--config format.exp-rc-dirstate-v2=1`(6.0) / `format.use-dirstate-v2=true`(7.2.2)
   `--config storage.dirstate-v2.slow-path=allow`(Rust 확장 없이 pure-Python 경로 강제
   허용)로 실제 dirstate-v2 저장소를 만들어 `.hg/dirstate`+`.hg/dirstate.<uid>`를 직접
   캡처, 바이트 단위로 역산해 위 3건을 확정. 캡처한 실제 바이트를 그대로 박아넣은
   회귀 테스트(`DirstateV2RealFixtureTest`) 신설. 기존 interop 테스트
   (`CHgDirstateV2Test`)도 `hg init` **이후에** hgrc로 `use-dirstate-v2`를 설정하던
   순서 버그(설정이 너무 늦게 적용돼 항상 v1로 초기화됨) + `slow-path=allow` 누락으로
   requires 파일에 `dirstate-v2`가 없어 매번 조용히 skip되던 것을 발견·수정 — 실제로
   한 번도 통과된 적 없던 인터롭 테스트가 이제 실제로 실행되고 통과한다.
37. ~~**dirstate-v2 저장소에서 hg4j 커밋이 기존 파일을 트리 구조에서 유실시킴**~~ —
    ✅ **완료(2026-09-04)**. 신규 발견([[exhaustive-interop-matrix-plan]]의
    requirement 매트릭스 Docker 30조합 TDD 중, real hg가 이미 커밋해둔 파일이 있는
    dirstate-v2 저장소에 hg4j `CommitCommand`로 새 파일을 추가 커밋하는 시나리오에서
    100% 결정적으로 재현). `hg debugstate`(플랫 덤프)는 기존 파일을 정상 표시하지만
    `hg status`/`hg files`/`hg verify`(트리 순회 기반, `children_start`/`count`
    사용)는 못 찾고, `hg verify`가 `"<file> in manifest1, but not marked as
    tracked in p1"` + `"dirstate inconsistent with current parent's manifest"`로
    실패했다.

    **근본 원인 확정**(`hg-rust-7.2.4` 컨테이너 내부의 실제 hg 소스
    `/build/mercurial-7.2.4/rust/hg-core/src/dirstate/dirstate_map.rs` 직접 대조로
    확인): real hg의 Rust dirstate-v2 리더는 부모 노드의 자식 배열에서 특정 파일을
    찾을 때 `binary_search_by(|node| node.base_name(on_disk).cmp(base_name))`
    (`dirstate_map.rs:278`)로 **이진 탐색**을 쓰고, 쓰기 경로(`on_disk.rs:327
    sorted()`)는 자식 노드를 반드시 basename 오름차순으로 정렬해서 쓴다. hg4j의
    `DirstateV2Serializer`는 `LinkedHashMap` 삽입 순서 그대로(정렬 없이) 썼다 —
    삽입 순서가 우연히 내림차순이 되면 오름차순을 가정하는 real hg의 이진 탐색이
    해당 파일을 못 찾는다. hg4j 자신의 리더는 스택 기반 DFS라 순서 무관이었기
    때문에 자기 자신과의 라운드트립만 우연히 통과해온 것 — 처음에 조사했던
    `children_start` 오프셋 가설은 틀렸고, 진짜 원인은 정렬 여부였다.

    **수정**: `DirstateV2Serializer.java`에서 root list와 각 디렉터리의 children
    list 양쪽 모두 basename의 UTF-8 바이트 기준 오름차순으로 정렬(Java `String`의
    UTF-16 비교가 아니라 실제 인코딩 바이트로 비교해 real hg의 `&[u8]`/`HgPath`
    순서와 정확히 일치시킴). `DirstateV2SerializerCoverageTest`에 회귀 유닛
    테스트 2개(루트 레벨/중첩 레벨 정렬) 추가. `RequirementMatrixDockerRoundTripTest`
    의 18개 스킵 처리를 제거하고 60개 케이스(30읽기+30쓰기) 전부 GREEN 확인,
    전체 회귀도 새 실패 없음(`BUILD SUCCESSFUL`).

