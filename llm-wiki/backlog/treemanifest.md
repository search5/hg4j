---
updated: 2026-09-03
status: completed
---

# 백로그 8, 18, 20: Treemanifest 읽기/쓰기와 wireprotocol v2 트리 fetch

관련 항목: 8(treemanifest 읽기 — `ManifestTreeIterator`가 유일한 병목점이었음), 18(treemanifest
쓰기/커밋 — `mercurial/manifest.py`의 bottom-up 재귀 기록 실측), 20(wireprotocol v2
`getBundle()`의 재귀적 `tree=<dir>` fetch 미구현, 서버 측 `Wire2Commands.manifestdata`도
비어있지 않은 `tree` 인자를 무조건 거부하던 별도 갭).

## 원문
8. ~~**트리매니페스트(`treemanifest`) 읽기 지원** — 조사 결과 미구현으로 확정(단순
   "미검증"이 아니라 관련 파싱 로직 자체가 없었음).~~ — ✅ **읽기 경로 완료(2026-09-03)**.
   Docker Mercurial 6.0(Rust 확장 없음 — treemanifest에 불필요, `hg --version`/
   `hg debuginstall`로 정상 동작 확인)으로 `experimental.treemanifest=1` 저장소를
   만들어(`a.txt`, `sub/b.txt`, `sub/deep/c.txt`, 이후 `sub2/d.txt` 3커밋, 2단계 중첩)
   `.hg/store/00manifest.i`(루트)와 각 `.hg/store/meta/<dir>/00manifest.i`(서브디렉터리)의
   실제 바이트를 `hg debugdata -m`/`hg debugindex --debug -m`(`--dir <path>`)로 직접
   대조. **실측 결과**: 매니페스트 콘텐츠 포맷은 파일/디렉터리 항목이 완전히 동일하다 —
   `<path>\0<40자 hex 노드ID><flag>\n` 한 줄, `flag`는 `''`/`x`/`l`/`t` 중 하나(구분자 없이
   노드ID 바로 뒤에 붙음, `mercurial/manifest.py`의 `_manifestflags`/`treemanifest.parse()`와
   일치). `t` 플래그가 붙은 항목의 노드ID는 파일 콘텐츠가 아니라
   `meta/<누적경로>/00manifest.i`(`manifestrevlog.dirlog()`의 `radix = "meta/" + tree +
   "00manifest"`와 동일 규칙)에 있는 서브매니페스트 revlog 리비전을 가리키고, 그 리비전의
   콘텐츠 안 경로는 **그 서브디렉터리 기준 상대경로**다(`treemanifest._subpath()`가
   호출부에서 접두사를 붙이는 구조) — 그래서 재귀 펼침 시 누적된 디렉터리 접두사를
   직접 복원해야 한다. `ManifestTreeIterator.loadEntries()`가 이 로직의 유일한 병목점임을
   확인(`ManifestWalk`/`getManifestAtCommit()`을 포함해 `StatusCommand`/`UpdateCommand`가
   직접 쓰는 것도 결국 다 이 클래스를 거침) — `parseManifestContent()`(순수 라인 파싱
   함수, 기존 동작 그대로 유지 + `Entry.isTreeDir()` 추가)와 새로 추가한
   `expandTree()`/`readSubManifestContent()`(재귀적으로 `meta/<dir>/00manifest.i`를 열어
   펼침)로 구현. 이 지점 하나만 고치는 것으로 `LogCommand`/`StatusCommand`/`UpdateCommand`
   등 매니페스트를 소비하는 기존 명령 전부가 수정 없이 flat 매니페스트와 동일하게
   동작한다. 픽스처는 `src/test/resources/fixtures/treemanifest/`(README.md에 생성 명령·
   전체 노드 해시·raw 매니페스트 바이트 기록), TDD는
   `ManifestTreeIteratorCoverageTest`(순수 파싱 레벨 `t` 플래그 인식 2건) +
   `TreemanifestRealFixtureTest`(`getManifestAtCommit()`/`ManifestWalk`/`LogCommand`
   레벨에서 3커밋 전부 재귀 펼침이 실제 hg `hg manifest --debug` 기준값과 정확히
   일치함을 확인, 디렉터리 포인터 항목이 flat 결과에 전혀 남지 않음도 검증). 전체
   회귀 클린(217개 테스트 클래스 전부 failures=0 errors=0).

   **범위 밖으로 명시적으로 남긴 것**: treemanifest **쓰기**(생성/커밋)는 이번에 다루지
   않았다 — 백로그 제목대로 읽기만 구현. `HgRemoteClientV2.getBundle()`이 wireprotocol v2
   서버에 항상 `"tree": ""`(루트 매니페스트)만 요청하고 서브디렉터리별 `tree=<dir>` 재귀
   fetch를 하지 않는 문제(2026-09-02 확인)도 사용자 지시로 그대로 남겨뒀다 — 이건 원격
   wireprotocol v2 경로 전용 문제이고(hg4j 자체 저장소는 전부 flat이라 무관, 로컬 저장소를
   직접 여는 이번 구현과는 별개), wireprotocol v2는 실제 hg 6.1부터 제거된 프로토콜이라
   실사용 노출면이 좁다.
18. ~~**Treemanifest 쓰기(생성/커밋)**~~ — ✅ **완료(2026-09-03)**. 실제
    `mercurial/manifest.py`의 `manifestlog._addtree`/`treemanifest.writesubtrees`/
    `dirtext()`를 실측(자식 디렉터리부터 bottom-up 재귀 기록, 각 레벨의 파일+
    서브디렉터리 포인터를 파일명 하나의 정렬 리스트로 합쳐 `sorted(dirs+files)`
    순서로 직렬화 — 파일 먼저, 디렉터리 나중이 아님)해서 `CommitCommand`에
    `writeTreeManifestDir` 재귀 메서드로 구현. `repository.isTreemanifest()`일
    때만 활성화(새 `HgRepository.isTreemanifest()` — 실제 hg 픽스처로 확인한 대로
    `treemanifest` requirement는 `.hg/store/requires`가 아니라 **최상위
    `.hg/requires`**에 있음, persistent-nodemap 등 store 포맷 플래그와는 다른
    분류), 기존 평면 매니페스트 경로는 완전히 무수정·무영향. 부모 커밋의 트리에서
    각 디렉터리의 기존 노드를 찾기 위한 `collectDirNodes`(`ManifestTreeIterator`의
    재귀 펼치기와 동일한 순회를 쓰되 디렉터리 노드 자체를 보존 — 이를 위해
    `ManifestTreeIterator.Entry`에 `getPath()`/`getNodeId()` public getter 추가)도
    신설. **의도적 단순화(문서화, 정확성엔 무관)**: 실제 hg는 서브트리 전체가
    부모와 바이트 단위로 동일하면 그 디렉터리의 새 리비전 작성을 건너뛰고
    부모 노드를 재사용하는 최적화(`m.unmodifiedsince(m1)`)가 있는데, hg4j는
    이 최적화 없이 커밋마다 변경된 파일 경로상의 모든 디렉터리에 항상 새
    리비전을 쓴다(루트 레벨 평면 매니페스트가 원래도 매번 새로 쓰던 것과 동일한
    수준) — 결과 바이트는 여전히 완전히 유효한 부모/콘텐츠 해시이고 실제 hg가
    문제없이 읽지만, 저장 중복 제거가 덜 됨.
    검증(`TreeManifestWriteTest`, 2건 GREEN): (1) 중첩 디렉터리(`sub/`,
    `sub/deep/`, `sub2/`) 커밋 후 자체 `ManifestTreeIterator`/`getManifestAtCommit`
    왕복 정확 + 두 번째(증분) 커밋에서 안 건드린 서브디렉터리의 부모-링크 정확성
    확인, (2) **hg4j로 커밋한 저장소를 실제 hg-rust-7.2.4(Docker)에 넘겨 `hg
    verify`/`hg log`/`hg cat -r 0 sub/deep/c.txt`(3단 중첩 경로)/`hg cat -r 1
    sub2/d.txt`(안 건드린 서브트리) 전부 성공** — real hg가 hg4j가 쓴
    `meta/sub/00manifest.i`+`meta/sub/deep/00manifest.i`를 실제로 타고 내려가며
    파일을 정확히 찾아냄을 증명. CommitCommand/treewalk 패키지 전체 회귀로 기존
    평면 매니페스트 동작 무손상 확인.
20. ~~**Wireprotocol v2 `getBundle()`의 재귀적 `tree=<dir>` fetch 미구현**~~ — ✅
    **완료(2026-09-03)**. `HgRemoteClientV2.getBundle()`이 루트 매니페스트에서
    `t`플래그 서브디렉터리 포인터를 발견하면 BFS로 `manifestdata`를
    `tree=<dir>`(트레일링 슬래시 없는 bare 경로, 예: `"sub"`/`"sub/deep"`)를 재귀
    호출해 더 깊이 중첩된 포인터까지 전부 수집, `ChangegroupParser.ChangegroupBundle`
    의 기존 `manifestGroups`(cg3/4/5용 봉투, 이미 구현돼 있었음) 필드로 조립한 뒤
    `writeBundle(..., "04")`+`Bundle2Parser.wrapChangegroupInBundle2`로 HG20/cg4
    번들을 생성한다(cg1/HG10UN은 트리 봉투 자체가 없어 flat 매니페스트일 때만
    그대로 사용, 서브디렉터리 발견 시에만 전환 — 기존 flat 경로 완전 무영향).
    **발견한 실제 갭 하나 더**: 백로그 문서가 "manifestdata 명령이 이미 있어서
    재귀 배선만 추가하면 됨"이라고 적어놨던 전제가 틀렸음을 확인 —
    `Wire2Commands.manifestdata`(hg4j 자체 wire2 **서버** 측)가 실제로는 비어있지
    않은 `tree` 인자를 무조건 거부하고 있었음(`"tree manifests are not
    supported"`). 클라이언트 수정을 실제로 검증할 방법이 없어서 서버 측도 대칭적으로
    `meta/<tree>/00manifest.i`를 찾아 서빙하도록 같이 고쳤다(존재하지 않는 tree는
    여전히 명확한 에러로 거부). 실제 Mercurial 6.0 wireprotocol v2 서버(6.1부터
    폐기돼 이 환경의 Docker 이미지들엔 남아있지 않음)로는 검증 못 했고, 대신
    hg4j↔hg4j 자기 일관성 왕복으로 검증: 서버 측에 treemanifest 저장소(`sub/`,
    `sub/deep/`, `sub2/` 3단 중첩, 백로그 18번 쓰기 지원으로 실제 커밋)를
    `HgHttpWireServer`로 띄우고, 클라이언트가 v1→v2 자동 업그레이드를 거쳐
    `FetchCommand`로 pull한 뒤 로컬에 `meta/sub/00manifest.i`+
    `meta/sub/deep/00manifest.i`+`meta/sub2/00manifest.i`가 실제로 생성되고
    4개 파일 콘텐츠가 정확히 재구성됨을 확인(`Wire2TreeManifestFetchTest`, GREEN).
    **의도적 단순화(문서화)**: 서브디렉터리의 linknode는 wire2 응답이 별도로
    안 실어주므로 그 서브디렉터리 포인터를 처음 발견한 상위 엔트리의 linknode로
    근사(cg1 포지셔널 디코딩엔 유효한 changelog rev이기만 하면 되므로 정확성엔
    영향 없음), 증분(common-root seeding) 최적화는 루트 경로만 적용하고
    서브디렉터리 델타체인은 매번 처음부터 구성(정확하지만 최적은 아님).
    transport/bundle/FetchCommand 패키지 전체 회귀(540개+ 테스트) GREEN.
