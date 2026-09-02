# treemanifest 읽기 픽스처

Docker `hg6-v2server`(Mercurial 6.0, pure Python, Rust 확장 없음 — `hg --version`/
`hg debuginstall`로 정상 동작 확인됨)로 만든 실제 `experimental.treemanifest=1` 저장소의
`.hg` 디렉터리 원본(`cache/`, `wcache/`, `undo*`, `last-message.txt`는 불필요해 제거).

생성 명령:
```
hg --config experimental.treemanifest=1 init /repo
cd /repo
echo hello > a.txt
mkdir -p sub/deep
echo world > sub/b.txt
echo deepcontent > sub/deep/c.txt
hg --config experimental.treemanifest=1 add
hg --config experimental.treemanifest=1 commit -u test -d "1788400000 0" \
    -m "c1: add a.txt, sub/b.txt, sub/deep/c.txt"

echo hello2 > a.txt
echo world2 > sub/b.txt
hg --config experimental.treemanifest=1 commit -u test -d "1788400001 0" \
    -m "c2: modify a.txt, sub/b.txt"

mkdir -p sub2
echo another > sub2/d.txt
hg --config experimental.treemanifest=1 add sub2/d.txt
hg --config experimental.treemanifest=1 commit -u test -d "1788400002 0" \
    -m "c3: add sub2/d.txt"
```

`.hg/requires`:
```
dotencode
fncache
generaldelta
revlog-compression-zstd
revlogv1
sparserevlog
store
treemanifest
```
(share-safe가 아님 — `.hg/store/requires`는 없고 `.hg/requires`에 전부 기록된다. 리비전은
전부 `revlog-compression-zstd`이므로 `.d` 압축 청크가 `0x28`(zstd 프레임 매직 첫 바이트)로
시작한다 — hg4j `DeltaCodec.decompress()`가 이미 zstd를 지원해 별도 대응 불필요.)

## 저장소 구조

```
.hg/store/00manifest.i                    루트 매니페스트 revlog (3 리비전)
.hg/store/meta/sub/00manifest.i           sub/ 서브매니페스트 revlog (2 리비전)
.hg/store/meta/sub/deep/00manifest.i      sub/deep/ 서브매니페스트 revlog (1 리비전)
.hg/store/meta/sub2/00manifest.i          sub2/ 서브매니페스트 revlog (1 리비전, rev2에서만 등장)
.hg/store/data/a.txt.i
.hg/store/data/sub/b.txt.i
.hg/store/data/sub/deep/c.txt.i
.hg/store/data/sub2/d.txt.i
```

모든 revlog가 inline(v1, 64바이트 인덱스 레코드 뒤에 압축 데이터가 바로 이어짐) — `.d` 파일이
따로 없다.

## 커밋별 changeset/매니페스트 노드

Docker Mercurial 6.0의 `hg log --debug`/`hg debugindex --debug -m`/`hg debugindex --debug --dir <d> -m`로
직접 확인:

| rev | changeset node | root manifest node | 설명 |
|---|---|---|---|
| 0 | `9cd0fbb1eaf3a47cdee68e508a7d1b3fb524f452` | `c88eb11f3786008e49b7f85da0d645bda353d592` | c1 |
| 1 | `26072b563966a6daa8e78160db4f4bc05bd0d0fa` | `3eb787a2160ab044881bc264d592330ebc489fee` | c2 |
| 2 | `d09f2bbaed180fe6992489d2b2c11363a34e3ccf` | `db77e5d5302ae0742b43fa8baab6eb955ba9e588` | c3 |

## `t`(subdirectory-pointer) 플래그 바이너리 인코딩 — 실측

각 매니페스트 리비전의 압축 해제된 콘텐츠는 다른 revlog 콘텐츠와 완전히 동일한 형식이다:
줄 하나당 `<path>\0<40자 hex 노드ID><flag>\n`. 파일 플래그(`''`/`x`/`l`)와 디렉터리 포인터
플래그(`t`)는 완전히 같은 위치·형식으로 인코딩된다 — 구분자나 별도 마커는 없고, 오직 이
한 글자 플래그 값만으로 "이 항목은 파일이 아니라 서브디렉터리 매니페스트 포인터"임을
나타낸다(`mercurial/manifest.py`의 `_manifestflags = {'', 'l', 't', 'x'}`,
`treemanifest.parse()`: `if fl == b't': f = f + b'/'; selflazy[f] = (n, readsubtree, False)`).

**루트 매니페스트** (`hg debugdata -m <rev>`로 실측):

- rev0 (47+46바이트, cat -A로 확인, `$`=개행):
  ```
  a.txt\0 2c186c8c5bc0df5af5b951afe407d803f9e6b8c9
  sub\0 69d5a5d76646b2ecadf92b2e2ee04dc1e1c4d3f4 t
  ```
  (공백은 표기 편의상 삽입한 것 — 실제 바이트는 `\0` 뒤에 바로 40자 hex, 그 뒤 바로 `t`,
  구분자 없음. `sub` 항목은 트레일링 슬래시 없이 `sub`로만 기록되고, 뒤에 붙는 `t` 플래그가
  이게 디렉터리 포인터임을 나타낸다 — 파싱하는 쪽에서 `f + '/'`로 복원한다.)
- rev1: `a.txt\0c093b423870d8b2114889160cb1dee55fd1cca9b\n` +
  `sub\09b2bba1b64fa76542db7250efa2c14be8abd78b7t\n`
- rev2: rev1과 동일한 두 줄 + `sub2\01fe1b432242ffd6a0b7b48a8dfa607a2e5afeae8t\n`

**`meta/sub/00manifest.i`** (경로는 `sub/`를 기준으로 한 상대 경로 — `b.txt`, `deep`이지
`sub/b.txt`, `sub/deep`이 아니다):
- rev0 (linkrev=0): `b.txt\0cc68520d565d6565e36765b4ff03f05c5f57d080\n` +
  `deep\0fe2f31ebf20e6f56bfdf4bb82d9e67062dc95135t\n`
- rev1 (linkrev=1, p1=rev0): `b.txt\0f15e686805cc5feaa9c87472965c59681c96df0c\n` +
  `deep\0fe2f31ebf20e6f56bfdf4bb82d9e67062dc95135t\n` (deep 서브트리는 rev1에서 변경 없음 —
  노드ID가 rev0과 동일하게 재사용됨)

**`meta/sub/deep/00manifest.i`** (경로는 `sub/deep/` 기준 상대 경로):
- rev0 (linkrev=0): `c.txt\0955d535bc0132492b4e999bd8024f776a8242b25\n`

**`meta/sub2/00manifest.i`** (경로는 `sub2/` 기준 상대 경로, linkrev=2 — c3에서 처음 등장):
- rev0: `d.txt\0f55729ef0544575a598e7a746756782f6dcf3a3a\n`

## 재귀적 펼침이 맞다는 것을 확인하는 기준값 — `hg manifest -r <rev> --debug`

파일 노드ID와 실제 경로(hg4j `ManifestTreeIterator.expandTree()`가 만들어야 하는 최종
flat 결과와 동일해야 함):

**rev0**:
```
2c186c8c5bc0df5af5b951afe407d803f9e6b8c9 644   a.txt
cc68520d565d6565e36765b4ff03f05c5f57d080 644   sub/b.txt
955d535bc0132492b4e999bd8024f776a8242b25 644   sub/deep/c.txt
```

**rev1**:
```
c093b423870d8b2114889160cb1dee55fd1cca9b 644   a.txt
f15e686805cc5feaa9c87472965c59681c96df0c 644   sub/b.txt
955d535bc0132492b4e999bd8024f776a8242b25 644   sub/deep/c.txt
```

**rev2**:
```
c093b423870d8b2114889160cb1dee55fd1cca9b 644   a.txt
f15e686805cc5feaa9c87472965c59681c96df0c 644   sub/b.txt
955d535bc0132492b4e999bd8024f776a8242b25 644   sub/deep/c.txt
f55729ef0544575a598e7a746756782f6dcf3a3a 644   sub2/d.txt
```

(모든 파일이 644 — 실행 비트/심볼릭 링크 없음. 이 픽스처는 `t` 플래그 경로만 다루며,
`x`/`l` 플래그와 `t`가 같은 라인에서 함께 나타나는 경우는 실제 hg 자체가 만들지 않는다 —
`_manifestflags`가 단일 문자 집합이기 때문.)

## hg4j 통합 지점

`ManifestTreeIterator.loadEntries()`가 루트 매니페스트 콘텐츠를 얻은 뒤
`expandTree(mfContent, "")`를 호출한다. `expandTree()`는 `parseManifestContent()`로 한 레벨만
파싱한 뒤, `t` 플래그가 붙은 항목마다 `meta/<누적경로>/00manifest.i`를 열어(`readSubManifestContent()`)
해당 노드ID의 리비전 콘텐츠를 재귀적으로 다시 `expandTree()`에 넘긴다 — 서브트리 콘텐츠의
경로는 그 서브트리 기준 상대 경로이므로, 재귀 호출마다 누적된 `dirPrefix`를 붙여 저장소
루트 기준 전체 경로로 복원한다. 최종 `entries` 목록에는 디렉터리 포인터 항목이 하나도
남지 않고 실제 파일만 남으므로, `ManifestWalk`/`DefaultFileStoreEngine.getManifestAtCommit()`을
거쳐 이 결과를 소비하는 `LogCommand`/`StatusCommand`/`UpdateCommand` 등 기존 명령은 전혀
수정 없이 flat 매니페스트와 동일하게 동작한다.

## 범위 밖으로 남긴 것

- treemanifest **쓰기**(생성/커밋)는 이 작업 범위 밖 — 읽기만 구현했다.
- `HgRemoteClientV2.getBundle()`의 wireprotocol v2 재귀적 `tree=<dir>` fetch는 범위 밖으로
  남겼다(별도 사용자 지시). wireprotocol v2는 실제 hg 6.1부터 제거된 프로토콜이라 실사용
  노출면이 좁다. 이 픽스처는 로컬 저장소를 직접 여는 경로(`HgRepository`/`ManifestTreeIterator`/
  `getManifestAtCommit()`)만 검증한다.
