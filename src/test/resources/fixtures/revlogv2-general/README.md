실제 Rust 확장이 활성화된 Mercurial 7.2.4 빌드(`docker/hg-rust-7.2.4/Dockerfile`, 이미지 태그
`hg-rust-7.2.4`)로 생성한 `exp-revlogv2.2` + `fileindex-v1` + `persistent-nodemap` 저장소의 실제 바이트.
시스템에 설치된 순정 파이썬 `hg`(Rust 확장 없음)는 이 조합의 저장소를 만들지도 못한다
("accessing `fileindex` repository without associated fast implementation").

생성 명령:
```
hg --config experimental.revlogv2=enable-unstable-format-and-corrupt-my-data \
   --config format.use-persistent-nodemap=true init
```
후 3회 커밋:
1. `a.txt` = "hello\n" 추가 (commit message "c1")
2. `a.txt` = "hello world\n" 로 수정 (commit message "c2")
3. `sub/b.txt` = "sub_content\n" 추가 (commit message "c3")

author는 모두 `test`(실제 hg 기본 사용자).

## 중요: changelog도 general v2(0xDEAD)이지 changelog-v2(0xD34D)가 아니다

`exp-revlogv2.2`와 `exp-changelog-v2`는 서로 독립된 requirement다. 이 픽스처는
`experimental.revlogv2=enable-unstable-format-and-corrupt-my-data`만 켰으므로 changelog를
포함한 **모든** revlog(changelog/manifest/filelog)가 general v2 포맷(매직 `0xDEAD`,
`INDEX_ENTRY_V2` 96바이트 레코드: baseRev/linkRev/parent1/parent2 모두 명시적으로 저장,
node는 오프셋 32, rank 필드 없음)을 쓴다. `src/test/resources/fixtures/revlogv2-changelog/`는
반대로 `format.exp-use-changelog-v2`만 켜서 changelog 전용 changelog-v2 포맷(매직 `0xD34D`,
`INDEX_ENTRY_CL_V2`, node는 오프셋 24, rank 필드 있음, baseRev/linkRev는 저장 안 하고
rev 값으로 합성)을 담고 있는 별개의 픽스처다. 두 포맷 모두 docket 헤더(59바이트 `S_HEADER`
+ `{radix}-{uuid}` 컴패니언 파일 3개)는 동일하다.

## 디렉터리 구성

각 서브디렉터리는 원본 `.hg/store/*-{uuid}.{idx,dat,sda}` 파일들을 단순한 이름
(`docket.i`/`data.idx`/`data.dat`/`data.sda`)으로 복사한 것. `docket.i`가 83바이트 docket
(59바이트 고정 헤더 + 3개 8자리 hex UUID)이고, 나머지 3개가 그 docket이 가리키는 실제
index/data/sidedata 파일이다.

- `changelog/` — 원본 `00changelog.i`(docket) + `00changelog-{uuid}.{idx,dat,sda}`. 3개 리비전.
- `manifest/` — 원본 `00manifest.i`(docket) + `00manifest-{uuid}.{idx,dat,sda}`. 3개 리비전.
- `data-a.txt/` — 원본 `data/a.txt.i`(docket) + `data/a.txt-{uuid}.{idx,dat,sda}`. 2개 리비전.
- `data-sub-b.txt/` — 원본 `data/sub/b.txt.i`(docket) + `data/sub/b.txt-{uuid}.{idx,dat,sda}`. 1개 리비전.
- `fileindex/` — 원본 `.hg/store/fileindex`(docket, 68바이트) + `fileindex-list.{uuid}` →
  `list`, `fileindex-meta.{uuid}` → `meta`, `fileindex-tree.{uuid}` → `tree`로 단순 개명.
  `fileindex-v1` 포맷 스펙은 `mercurial/store_utils/file_index_util.py`(실제 hg 소스,
  이 컨테이너에는 없지만 시스템 `/usr/lib/python3/dist-packages/mercurial/`에 pure-Python
  구현이 존재해 직접 확인 가능) 참고. `com.github.search5.hg4j.storage.FileIndex`가 이 포맷을
  읽고 쓴다.

## 커밋별 노드 해시와 압축 해제된 내용

모든 리비전이 `compMode & 0x3 == COMP_MODE_PLAIN`(비압축, 실제 파일 콘텐츠 바이트 그대로)이다
— general v2의 `.dat`은 v1처럼 앞에 압축 방식을 나타내는 마커 바이트(`'x'`/`'u'`/zstd 매직)를
붙이지 않고, 압축 방식은 오직 index 레코드의 `compMode` 필드로만 결정된다.

**a.txt** (`data-a.txt/`)
- rev0: node `2c186c8c5bc0df5af5b951afe407d803f9e6b8c9`, base=0, link=0, p1=-1, p2=-1, 내용 `"hello\n"`
- rev1: node `faa62ea5d798c6624f63d25f2e64f1c107815f20`, base=1(=rev, 풀텍스트), link=1, p1=0, p2=-1, 내용 `"hello world\n"`

**sub/b.txt** (`data-sub-b.txt/`)
- rev0: node `7098398881fece10a8cdcdff1d8fc714570d14e1`, base=0, link=2, p1=-1, p2=-1, 내용 `"sub_content\n"`

**manifest** (`manifest/`)
- rev0: node `12a740b79149c7c4c9d8d90d0dc06746e2bdcf80`, base=0, link=0. 내용(47바이트, NUL 구분자):
  `a.txt\0` + hex(2c186c8c...) + `\n`
- rev1: node `3006e93cfc3d789805f10de06361add993d692dd`, base=1(풀텍스트), link=1. 내용(47바이트):
  `a.txt\0` + hex(faa62ea5...) + `\n`
- rev2: node `1f37e160857746cf89f3ce8a808ffe34d4733b6f`, base=1(rev1에 대한 델타!), link=2,
  p1=1. 압축 해제 후 풀텍스트(98바이트) 자체는 `a.txt\0`+hex(faa62ea5...)+`\n`+
  `sub/b.txt\0`+hex(7098398...)+`\n` 이지만, `.dat`에 저장된 63바이트는 그 풀텍스트가 아니라
  rev1 대비 델타(binary diff, `\x00\x00\x00/\x00\x00\x00/\x00\x00\x003` 헤더로 시작)다. 즉
  실제 hg 자신도 general v2에서 델타를 쓸 수 있음을 보여준다 — hg4j의 쓰기 경로
  (`Revlog.appendRevisionV2`)는 항상 `base=rev`(풀텍스트만)로 단순화해서 쓰지만, 이는
  스펙상 유효한 선택이며 델타 압축 쪽은 아직 구현하지 않았을 뿐이다.

**changelog** (`changelog/`)
- rev0: node `2d6cb5e1bdc8e5a6778002ed341470600fb4f2c7`, base=0, link=0, p1=-1. 내용:
  `12a740b79149c7c4c9d8d90d0dc06746e2bdcf80\ntest\n1788360212 0\na.txt\n\nc1`
- rev1: node `c13520a72e411cdea583bccdc3a844edfc0f73b6`, base=1(풀텍스트), link=1, p1=0. 내용:
  `3006e93cfc3d789805f10de06361add993d692dd\ntest\n1788360213 0\na.txt\n\nc2`
- rev2: node `279f3d37b8d523303e9afe7a2e7672a2c4e7cd39`, base=2(풀텍스트), link=2, p1=1. 내용:
  `1f37e160857746cf89f3ce8a808ffe34d4733b6f\ntest\n1788360213 0\nsub/b.txt\n\nc3`

## fileindex 내용 (`fileindex/`)

docket(68바이트): `list_file_size=16, meta_file_size=24, tree_file_size=27`,
`list_file_id=7b8f14fc, meta_file_id=2b654234, tree_file_id=8ea44169`,
`tree_root_pointer=11, tree_unused_bytes=11, reserved_flags=0`, 빈 garbage list.

- `list`(16바이트): `b'a.txt\x00sub/b.txt\x00'`
- `meta`(24바이트, 3개 엔트리 = 8바이트 `>IHH`): token0(root, 전부 0) / token1=(offset=0,
  length=5, dirnameLength=0) → `a.txt` / token2=(offset=6, length=9, dirnameLength=3) →
  `sub/b.txt`
- `tree`(27바이트): 루트 노드(offset 0) + `a.txt`/`sub/b.txt`용 리프 참조를 담은 트라이.
  루트가 offset 0이 아니라 `tree_root_pointer=11`을 가리키는 것은 실제 hg의 `MutableTree`가
  증분(copy-on-write append) 알고리즘으로 트리를 자라게 하기 때문 — hg4j의 쓰기 경로
  (`FileIndex.writeTrackedPaths`)는 대신 매번 전체를 새로 빌드하는 "vacuum" 전략을 쓰므로,
  hg4j가 쓴 fileindex는 항상 `tree_root_pointer=0`이 된다(둘 다 스펙상 유효 — 실제 hg 자신도
  주기적으로 이 vacuum 경로를 탄다).

## 검증

- `RevlogV2GeneralParserTest` — changelog/manifest/filelog 공용 docket/index 파싱, real fixture
  바이트 기준.
- `FileIndexTest` — 이 `fileindex/` 픽스처를 hg4j `FileIndex.readTrackedPaths()`로 읽어
  `{a.txt, sub/b.txt}`가 나오는지 확인 + hg4j가 새로 쓴 fileindex의 왕복(read-after-write),
  스냅샷/롤백을 단위 테스트로 검증.
- 실제 hg round-trip(수동, Docker): 이 픽스처를 쓰기 가능한 복사본으로 만들어 hg4j
  `CommitCommand`로 새 파일(`brand-new-file.txt`)을 커밋한 뒤, `hg-rust-7.2.4` 컨테이너의
  `hg verify`/`hg log --debug`/`hg cat`/`hg files`가 전부 경고 없이 통과함을 확인함
  (`fileindex-v1` 연동 전에는 `hg verify`가 `"brand-new-file.txt" uses revlog format 1` +
  `"not in file index!"` 두 경고를 냈으나, `FileIndex`/`HgRepository.isFileIndexV1()` 연동 후
  둘 다 사라짐).
