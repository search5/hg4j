---
title: 스토어 shape(샤드, 프로필, 지문)와 sharded 번들: 소스 분석
status: 분석 완료. 포팅 A~E단계 모두 완료 (2026-11-04).
---

# 스토어 shape: 소스 분석

hg 7.2의 "narrow shape" 기능을 Java로 옮기기 전에 소스를 한 번에 분석해 둔 문서다. 코드만 읽고 옮기다가 같은 곳을 다시 옮기는 일을 막으려는 것이 목적이다. **포팅 중 이 문서와 다른 점을 발견하면 코드를 고치기 전에 이 문서를 먼저 고친다.**

관련 계획: [[implementation-plan-core-gaps]] 9번(sharded stream bundles), 13번(`admin::` 명령, `store-shapes-lock`).

## 1. 한 문장 요약과 용어

서버 관리자가 저장소 파일 경로를 **샤드**(shard)로 나누고, 샤드를 묶은 **프로필**(profile)을 정해 두면, 사용자는 프로필 이름(**shape**)으로 narrow 클론을 한다. 모든 shape와 샤드는 포함·제외 경로 트리를 SHA-256으로 접은 **지문**(fingerprint)으로 식별된다. 서버는 샤드마다 stream 번들을 미리 만들어 둘 수 있고(**sharded bundle**), 클라이언트는 지문으로 맞는 번들 묶음을 고른다.

| 용어 | 뜻 |
|---|---|
| shard | 서로 겹치지 않는 경로 집합. 설정의 `[[shards]]` 항목. 암묵 샤드 3개가 항상 추가된다 |
| profile | 샤드나 다른 프로필을 `includes`로 묶은 것. `public = true`면 사용자가 shape로 쓸 수 있다 |
| shape | 사용자 눈에 보이는 이름. 이름 여러 개를 쉼표로 이으면 합성 shape다 |
| fingerprint | 정규화한 경로 트리의 SHA-256. 종류가 `shape`와 `shard` 둘이다 |
| 패턴 블록 | 포함·제외 경로를 직렬화한 이진 덩어리. 지문의 입력이면서 와이어 형식이다 |
| bundle group | 한 번에 만든 sharded 번들 묶음. 같은 `bundle-group-id`를 쓴다 |

## 2. 소스 지도: 어디에 무엇이 있나

hg 소스 루트는 `/home/jiho/yona-convert/hg`.

| 파일 | 내용 | 언어 | 설치된 hg 7.2에서 실행 가능 |
|---|---|---|---|
| `mercurial/shape.py` | 지문 클래스, 이름 검증, **Python 트리·지문·패턴 블록 구현**, 와이어 블록 인코딩, 이전 설정 저장 | Python | 예 (순수 Python 경로) |
| `rust/hg-core/src/narrow/shape.rs` | **설정(TOML) 파싱·검증, StoreShards, Shape, 트리, 지문, 직렬화, 변경 비교** + 단위 test 1500줄 | Rust | 아니오 (Rust 모듈 없음) |
| `rust/hg-core/src/matchers.rs` `ShapeMatcher` | shape용 matcher | Rust | 아니오 |
| `rust/hg-core/src/utils/hg_path.rs` `ZeroPath` | 정렬용 경로 변환 | Rust | 아니오 |
| `mercurial/wireprotov1server.py` `store_shape` | 서버 명령 | Python (Rust 필요) | 아니오 |
| `mercurial/wireprotov1peer.py` `store_shape` | 클라이언트 명령 | Python | 예 |
| `mercurial/wireprototypes.py` | `SHAPECAP`, `STORE_SHAPE_ENCODE`, `ShapeReturnCode` | Python | 예 |
| `mercurial/narrowspec.py` | `store-shape` 파일, `patterns_for_shape`, `to_legacy_patterns` | Python | 일부 |
| `mercurial/cmd_impls/clone.py` | `--shape` 클론 흐름 | Python | 예 |
| `mercurial/exchange.py` | `store_fingerprints`/`shards_sets` 전달, getbundle의 `store_shape_with_fingerprint`, bundlespec 생성 | Python | 일부 |
| `mercurial/bundlecaches.py` | clonebundle 후보 필터링과 sharded 묶음 선택 | Python | 예 |
| `mercurial/bundle2.py` `write_repo_sharded_stream_bundles`, `addpartbundlestream2` | sharded 번들 생성, `stream2` 파트 | Python (Rust 필요) | 아니오 |
| `mercurial/streamclone.py` | `NarrowInfo`, `_entries_walk`, `generatev2` | Python | 일부 |
| `mercurial/admin_commands.py` | `admin::narrow-server`, `admin::narrow-client` | Python (Rust 필요) | 아니오 |
| `mercurial/localrepo.py` `store_shapes_lock` | `store-shapes-lock` 잠금 | Python | 예 |

**중요한 사실**: 트리 정규화, 지문, 패턴 블록 직렬화·역직렬화는 `shape.py`에 **순수 Python 구현이 이미 있고**, 설치된 hg로 실행할 수 있다. 그래서 이 부분은 실제 hg 값과 바이트 단위로 비교할 수 있다. 설정(TOML) 처리와 `StoreShards`만 Rust 전용이다.

## 3. 서버 설정 파일 `server-shapes`

위치: 스토어(`.hg/store/server-shapes`). TOML. 없으면 설정 없음으로 본다(기본 `ShapesConfig`는 샤드 0개 상태로 암묵 샤드만 만든다). 파일이 있는데 `shards`가 비어 있으면 `NoShards` 오류.

```toml
version = 0

[[shards]]
name = "foo-files"
paths = ["foo"]
read-write-access = ["team-a"]   # 선택. 이름 규칙을 따르는 문자열들, 저장 때 정렬

[[profiles]]
name = "foo"
includes = ["foo-files", "foo-deps"]
public = true                    # 기본 false
```

- 알 수 없는 필드는 오류(`deny_unknown_fields`). 최상위 `profiles`는 생략 가능.
- `version != 0`이면 `UnknownVersion`. 파싱이 실패했는데 첫 줄이 `version = N` 모양이면 다른 오류 대신 `UnknownVersion(N)`을 우선한다(정규식 `^version\s*=\s*(\d+)$`, 데이터 전체가 아니라 바이트 기준 단일 매칭).
- 쓰기 때(`admin::narrow-server --shape-update`) 파일 맨 앞에 주석 3줄 헤더를 붙인다(10절).

### 3.1 샤드 이름 규칙 (`ShardName::new`, `shape.py _validate_shape_name`)

순서대로 검사한다.

1. 빈 문자열 -> `EmptyShardName` (Python: `shard names must not be empty`)
2. 전부 `.` 또는 `-` -> `DotOrHyphenOnlyShardName` (Python: `invalid shard name '%s': missing lowercase alphanumeric character`)
3. `^[-.a-z0-9]+$` 불일치 -> `InvalidShardName` (Python: `invalid shard name '%s': only lowercase alphanumeric, hyphen or dot are accepted`)

`canonical_names`: 각 이름을 검증하고 정렬·중복 제거. 첫 번째로 잘못된 이름이 보고된다. Python `parse_shape_names`는 쉼표로 쪼개 같은 검증 후 `sorted(set(...))`.

### 3.2 암묵 샤드 (설정에 항상 추가, 사용자 정의 금지)

설정의 샤드·프로필을 모은 뒤 **이 순서로** 덧붙인다(순서가 의존 목록 계산에 쓰인다).

| 이름 | paths | shape | requires |
|---|---|---|---|
| `base` | `[""]` (루트 전체) | false | (없음) |
| `full` | 없음 | **true** | 위 `base` 추가 시점까지의 **모든 사용자 샤드/프로필 이름 + `base`**, 정렬 |
| `.hg-files` | `.hgignore`, `.hgtags`, `.hgsub`, `.hgsubstate` | false | (없음) |

사용자가 `base`, `full`, `.hg-files`를 쓰면 `ReservedName`. `.hg-files`가 아닌 **모든** 샤드(암묵 포함)는 `requires`에 `.hg-files`가 자동으로 들어간다. 따라서 모든 shape는 그 특수 파일 4개를 포함한다.

### 3.3 검증 순서 (`from_config` -> `validate_shard`)

1. `[[shards]]`는 `paths`가 비면 `ShardMissingPaths`; `[[profiles]]`는 `includes`가 비면 `ProfileMissingIncludes`.
2. 각 샤드에 대해: 예약 이름 검사 -> 경로 정규화(끝의 `/` 하나 제거, `check_state()`로 유효 경로 확인, 실패는 `InvalidPath`) -> `requires` 이름 검증 후 정렬 -> 이름 검증 -> `read-write-access` 값 각각 이름 규칙 검증(`InvalidReadWriteAccess`) -> **경로가 이미 다른 샤드에 있으면 `PathInMultipleShards`** -> 같은 이름이 이미 있으면 `DuplicateShard`.
3. 두 번째 패스: 정렬된 순서로 모든 샤드의 `requires`가 존재하는 이름인지 확인, 아니면 `UnknownInclude { profile, include }`.
4. `all_shapes()`를 한 번 만들어 보며 나머지 오류(특히 `CycleInShards`)를 잡는다.

오류 종류 전체(포팅 시 예외 클래스 또는 코드로 1:1): `EmptyShardName`, `DotOrHyphenOnlyShardName`, `InvalidShardName`, `ReservedName`, `CycleInShards(경로)`, `PathInMultipleShards`, `DuplicateShard`, `ShardMissingPaths`, `ProfileMissingIncludes`, `NoShards`, `UnknownInclude`, `InvalidReadWriteAccess`, `PatternError`, `InvalidPath`, `UnknownVersion`, `ParseError`, `Deserialization(InvalidLength|InvalidPath)`, `UnknownProfile`, `NoProfileNames`. 사용자에게 보이는 문구는 Rust 쪽 `Display`/Python 바인딩(`hg-pyo3/src/shape.rs`)에서 만들어지므로 **문구 포팅 전에 그 파일을 읽을 것**(아직 안 읽음, 12절).

### 3.3.1 사용자에게 보이는 오류 문구 (`rust/hg-core/src/errors.rs`, `{backtrace}` 접두는 비어 있다)

| 종류 | 문구 |
|---|---|
| EmptyShardName | `shard names must not be empty` |
| DotOrHyphenOnlyShardName | `invalid shard name '%s': missing lowercase alphanumeric character` |
| InvalidShardName | `invalid shard name '%s': only lowercase alphanumeric, hyphen or dot are accepted` |
| ReservedName | `shard name '%s' is reserved` |
| CycleInShards | `shards form a cycle: a->b->a` |
| PathInMultipleShards | `path is found in multiple shards: %s` |
| DuplicateShard | `shard '%s' defined multiple times` |
| ShardMissingPaths | `shard '%s' needs `paths`` |
| ProfileMissingIncludes | `profile '%s' needs `includes`` |
| NoShards | ``server-shapes` must include at least one shard` |
| UnknownInclude | `profile '%s' includes unknown name '%s'` |
| InvalidReadWriteAccess | `invalid read-write-access value '%s' for shard '%s': only lowercase alphanumeric, hyphen or dot are accepted, with at least one alphanumeric character` |
| InvalidPath | ``server-shapes` contains an invalid path: ` + 경로 오류 |
| UnknownVersion | `unknown `server-shapes` version '%d'` |
| ParseError | `error parsing `server-shapes`:\n<toml 오류>` (toml 크레이트 문구는 재현하지 않는다) |
| UnknownProfile | `unknown profile '%s'` |
| NoProfileNames | `at least one profile name is needed` |
| Deserialization | `error deserializing shape: invalid length (expected %d, got %d)` / `invalid path '%s'` |

경로 오류 문구: `invalid HgPath '[47, 97]': has a leading slash.`(바이트를 Rust `{:?}`로 찍어 정수 목록이다), `invalid HgPath '[...]': consecutive slashes at pos N.`, `invalid HgPath '[...]': contains null byte at pos N.`, `path '%s': ends with a slash`.

검증 순서에서 주의할 점(`validate_shard`): 예약 이름 검사는 이름 검증보다 먼저, `requires` 이름 검증은 샤드 이름 검증보다 먼저다. 순환 오류의 경로는 `all_shapes()`가 이름 순으로 처음 만나는 shape에서 시작한다(`recursive` 하나만 있어도 `recursive->recursive`).

### 3.4 의존성과 shape 만들기

- `dependencies(shard)`: `requires`를 재귀로 모은다. 경로(`from`)에 이미 있는 이름을 다시 만나면 `CycleInShards(from + [name])`. 자기 자신은 결과에 넣지 않는다(첫 단계에서 `requires`만 넣으므로 순환이 없으면 자기 자신은 안 들어감).
- `all_shapes()`: `shape == true`인 샤드를 **이름 순으로**(구조체 `Ord`: 이름이 첫 필드) 각각 `Shape::new([이름], ...)`.
- `shape(names)`: 비면 `NoProfileNames`; 이름 정규화; 이름이 없거나 `shape == false`면 `UnknownProfile(이름)`; 합성은 각 샤드와 그 의존을 모두 펼친다.
- `Shape::new`: 펼친 샤드들로 `ShardTreeNode::from_shards`. 샤드의 `paths`와 그 의존 샤드의 `paths`를 한 집합으로 모으고(비어 있으면 assert 실패), **전체 경로 목록 = `path_to_shard`의 모든 키(모든 샤드가 선언한 경로)**, 포함 집합 = 방금 모은 경로로 `from_paths`를 부른다.

## 4. 트리 정규화 (핵심 알고리즘)

### 4.1 ZeroPath 정렬 키

경로를 `/` 대신 `\0`으로 구분하고 앞뒤에 `\0`을 붙인 바이트열로 바꿔 정렬한다. 그래서 디렉터리 바로 뒤에 그 하위 항목이 오고, `foo`와 `foo-x`가 섞이지 않는다.

```
""        -> "\0"
"foo"     -> "\0foo\0"
"foo/bar" -> "\0foo\0bar\0"
```

`sub_path_of(other)` = `self.zero.startswith(other.zero)` (바이트 prefix). 이 덕분에 `some/directory`는 `some/dir`의 하위가 아니다(`\0some\0directory\0` 는 `\0some\0dir\0`로 시작하지 않음). 앞에 `/`가 있거나 끝이 `/`면 `LeadingSlash`/`EndsWithSlash` 오류, `\0` 포함은 assert.

Python 구현(`shape.zero_path`): 빈 경로 -> `\0`, 아니면 `b'/%s/' % path`에서 `/`->`\0`. Rust와 같은 결과다.

### 4.2 `from_paths(paths, includes)`

1. 각 경로에서 노드 `{path, included = includes에 있음, children = []}`를 만든다.
2. ZeroPath로 정렬.
3. 스택을 쓴 선형 스캔: 스택 꼭대기의 하위 경로가 아닌 동안 pop. 스택이 비어 있지 않으면 **꼭대기와 `included`가 다를 때만** 자식으로 붙이고 push(같으면 중복이라 버린다). 스택이 비면 push.
4. 스택의 첫 원소가 루트(루트 경로는 항상 존재해야 한다: `""`).

결과 불변식: 부모와 자식의 `included`는 항상 다르다. 자식 사이에는 포함 관계가 없다.

### 4.3 `from_patterns(includes, excludes)` (구식 narrow 패턴용)

- 모든 패턴은 `path:` 문법이어야 한다. 아니면 `PatternError::UnsupportedSyntaxNarrow`.
- 포함 집합이 `""`나 `"."`를 포함하거나 **비어 있으면**: `"."`를 빼고 `""`를 넣고, 루트 노드를 따로 추가하지 않는다. 그렇지 않으면 루트 `""`를 제외 상태로 추가한다.
- 노드 경로 = (루트) + 포함 + 제외, `included` = 포함 집합에 있는가.
- **Python과 다른 점(주의)**: Python `ShardTreeNode.from_patterns`는 `includes -= excludes`로 "같은 경로가 둘 다면 제외가 이긴다"를 적용한다. Rust 쪽 `from_patterns`는 그런 차감이 없고 `included = include_paths.contains(path)`를 쓴다. 같은 경로가 포함과 제외 양쪽에 있으면 Rust는 같은 경로 노드 두 개를 만들고 둘 다 `included = true`다. 실제 입력에서 생기는지는 `--import-rules` 경로를 읽어야 안다(12절). **포팅 결정 필요**: Python 동작(제외 우선)을 기본으로 하고, 이 차이를 테스트로 고정한다.

### 4.4 `deepest_prefix_node(root, path)`와 `ShapeMatcher`

`deepest_node_impl(path, skip)`: 루트(경로 비어 있음)는 항상 일치. 그 외 노드는 `path`가 노드 경로로 시작하고, 길이가 같거나 다음 문자가 `/`일 때 일치. 자식을 차례로 보며 일치하는 자식이 있으면 그 결과를 반환(자식은 최대 하나만 일치한다). 없으면 자신.

`ShapeMatcher`:
- `matches(f)`: 빈 경로면 루트의 `included`; 아니면 `deepest_prefix_node(f).included`.
- `visit_children_set(dir)`: 가장 깊은 노드를 찾아, `included`면 `Recursive`; 정확히 그 디렉터리인 제외 노드면 자식이 없을 때 `Empty`, 있으면 자식 경로들의 **집합**(`Set`, 자식 노드의 전체 경로 그대로); 접두어만 일치하는 제외 노드면 `Empty`.
- `matches_everything` = 루트가 포함이고 자식이 없음. `exact_match`는 항상 false, `file_set`은 없음, `is_exact` false.
- 검증된 기대값(Rust test): shape `bar` 에서 `visit_children_set("")` = `{bar, .hgignore, .hgsubstate, .hgtags, .hgsub}`, `("bar")` = Recursive, `("bar/file")` = Recursive, `("other")` = Empty.

Python의 `ShardTreeNode.matcher()`는 같은 의미를 `differencematcher`/`unionmatcher`로 조립한다(`shape.py` 4.4절 위 코드). hg4j에는 이미 `Matchers.difference/union`이 있으므로, Java는 `ShapeMatcher`를 직접 구현하는 쪽(트리 순회)이 Rust와 같고 빠르다. 두 방식의 결과 동일성은 테스트로 확인한다.

### 4.5 `flat()`와 정렬

`flat()`은 트리를 (포함 경로들, 제외 경로들)로 펼친다(자기 자신, 자식의 재귀 순서). `flat_sorted()`는 포함은 true, 제외는 false로 이어 **ZeroPath로 정렬**한다.

## 5. 패턴 블록과 지문

### 5.1 패턴 블록 (이진, 빅엔디언)

```
<COUNT:u32> <SIZE:u16>*COUNT <PATH bytes>*COUNT
```
- `SIZE`의 최상위 비트(`1<<15`)가 켜지면 포함. 경로 길이는 `< 1<<15`여야 한다(아니면 Python `Abort("path is too long: %d bytes")`, Rust는 panic).
- 경로는 `flat_sorted` 순서.
- **기준값** (Python doctest): `serialize_patterns({b'foo', b'foo-x'}, {b'', b'foo/bar'})` = `00000004 0000 8003 0007 8005 666f6f 666f6f2f626172 666f6f2d78` 곧 `b'\x00\x00\x00\x04\x00\x00\x80\x03\x00\x07\x80\x05foofoo/barfoo-x'`. 빈 입력은 `b'\x00\x00\x00\x00'`.
- 포함과 제외에 같은 경로가 있으면 Python은 `ProgrammingError("duplicated paths in patterns")`.

역직렬화 오류 문구(Python): `error deserializing shape: invalid length (expected %d, got %d)`, `error deserializing shape: invalid path '%s'`. 길이 검사 순서: 최소 4바이트 -> 크기표까지(`4 + 2*count`) -> 경로 합까지 **정확히** 일치. 경로 유효성: `\0` 포함, `//` 포함, `/`로 시작, `/`로 끝나면 무효(Rust는 `check_state` + 끝 `/`). 기준 예: `\x00\x00\x00\x01\x00\x02/f` -> `invalid path '/f'`, `...\x80\x03fo` -> `expected 9, got 8`.

### 5.2 지문

- 다이제스트 = SHA-256(패턴 블록), 32바이트. 버전 0 (`FINGERPRINT_VERSION`).
- 이진 형식(`to_bytes`): 버전 1바이트 + 다이제스트. 알 수 없는 버전은 다이제스트 길이와 무관하게 유효(비어 있지만 않으면).
- 텍스트: `shape%02d-<hex>` / `shard%02d-<hex>`. 정규식 `(shape|shard)([0-9]{2,3})-((?:[0-9a-f]{2})+)`, 버전은 0~255이고 **표기가 `%02d`와 정확히 같아야** 한다(`shape012-` 무효, `shape255-` 유효, `shape0-` 무효).
- **기준값** (Python doctest): `from_patterns({b'foo', b'foo-x'}, {b'foo/bar'})`의 지문 텍스트 = `shape00-c1e75c57728686409c683838de48531c7705eeca5e8bb8d14fe8f9f7cbd0888b`.
- 같은 다이제스트도 종류가 다르면 같은 지문이 아니다. **샤드 지문 = 그 샤드의 경로만으로 만든 "익명 shape"의 shape 지문을 종류만 `shard`로 바꾼 것**(`ShardShape::fingerprint` -> `.as_shard()`).
- 주의: `ShardShape::new`는 `paths`가 비어 있는 샤드(프로필)에는 `None`을 돌려준다. 그래서 `list_shards`, `sharded_bundle_info`, `shard_fingerprints`는 경로가 있는 샤드만 다룬다.
- 샤드 하나의 트리: 전체 경로 목록은 `path_to_shard` 키 전부이고 포함 집합은 그 샤드의 `paths` 뿐이다. 그러므로 **다른 샤드 안에 중첩된 샤드는 바깥 샤드의 파일에서 빠진다**(바깥 샤드의 지문이 중첩 샤드를 추가·삭제하면 바뀐다).

## 6. StoreShards 파생 계산

| 메서드 | 의미 |
|---|---|
| `list_shards()` | (샤드 지문, read-write-access) 쌍, **다이제스트 바이트 순** 정렬. 경로 없는 샤드는 제외 |
| `sharded_bundle_info()` | 샤드를 **이름 순**으로, 경로 있는 것만 `ShardShape`(매처, 지문, top_level)로. `top_level` = 이름이 `.hg-files` |
| `shard_fingerprints_for_shape(shape)` | shape 이름들의 샤드 + 재귀 의존 샤드 -> 경로 있는 것의 샤드 지문 **집합 1개**를 담은 리스트(여러 상태를 담을 자리를 둔 형태) |
| `changed_shapes(new)` | shape 이름->지문 맵의 대칭차(이름·값 쌍), 이름 정렬·중복 제거 |
| `redefined_shapes(new)` | 위 중 **양쪽에 모두 있는** 이름(지문이 바뀜) |
| `changed_shards(new)` | 샤드 **지문** 집합의 대칭차. 순수 이름 변경은 안 잡힌다. 보고용으로 이름을 돌려준다 |

`shape_fingerprints`는 `all_shapes()`의 이름(합성이면 이름 각각)->지문. 이름은 정렬 키가 필요하므로 `ShardName`이 `Ord`.

## 7. 와이어 프로토콜

- 서버 능력: 토큰 `exp-shape-1`(`SHAPECAP`). **`experimental.narrow`가 켜져 있고 `experimental.advertise-shapes`가 켜져 있고 Rust가 있을 때만** 광고. (hg4j는 Rust 대신 자체 구현이므로 "있다"고 본다. 이미 narrow는 항상 광고한다.)
- 명령 `store_shape`: 인자 `names *`(쉼표로 이은 이름), 권한 `pull`. 응답 = 헤더 + 블록들.

```
헤더  = struct ">BHII": 코드(u8) , 지문블록 크기(u16), shards_sets 블록 크기(u32), 패턴블록 크기(u32)
코드  : 1 = OK, 2 = SHAPE_NOT_FOUND
OK    : 헤더 + [지문 블록][shards_sets 블록][패턴 블록]
NOT_FOUND : 헤더(크기1 = 알 수 없는 이름 블록 크기, 나머지 0) + [unknown names 블록]
```

> 헤더 크기 필드의 폭: `>BHII`이므로 **지문 블록 크기는 u16, 나머지 둘은 u32**. 서버는 `header.pack(code, *[len(b) for b in blocks])` 순서로 지문, shards_sets, 패턴 순.

블록 형식(모두 빅엔디언):
- **지문 블록**: `count:u8`, `len_i:u8 * count`, 각 지문의 `to_bytes()`. 서버는 shape 지문 1개를 넣는다.
- **shards_sets 블록**: `sets:u8`, 세트마다 `(id_len:u8, count:u16)`, 이어서 각 세트의 샤드 지문 이진을 **정렬해** 나열. 세트는 1개 이상 원소, 한 세트 안의 id 길이는 같아야 한다.
- **패턴 블록**: 5.1절. 서버는 `shape.serialized()`.
- **unknown names 블록**: `count:u32`, `len_i:u32 * count`, 이름들.
- 서버는 `shard_fingerprints_for_shape` 결과에, 저장된 **이전 설정**(`server-shapes-previous-*`)에서 같은 지문의 shape에 대해 계산한 세트들을 중복 없이 이어 붙인다(재샤딩 과도기에 옛 번들로도 클론하게).
- 클라이언트 검증: 패턴 블록으로 지문을 다시 계산해(`fingerprint_for_patterns`) 서버가 준 지문 목록에 없으면 `fingerprint mismatch for shape '%s'`(상세에 서버/클라이언트 지문 텍스트). NOT_FOUND는 `shape not found on remote: '%s'`(RepoLookupError), 그 밖의 코드는 `unknown shape error code: %d`.
- getbundle 인자 `store_shape_with_fingerprint` = `이름들:지문텍스트`. 서버(`exchange.getbundlechunks`)는 지문을 파싱(잘못되면 `invalid store shape fingerprint: '%s'`)하고 `narrowspec.patterns_for_shape`로 레거시 패턴을 얻어 `includepats`/`excludepats` 인자로 바꾼다. `patterns_for_shape`는 이름이 없으면 `shape not found on remote: '%s'`, 지문이 다르면 `fingerprint mismatch for shape '%s'`(hint `server: %s, client: %s`).
- `to_legacy_patterns`: 경로 `p` -> `path:p`, 빈 경로 -> `path:.`.
- bundle2 파트 `store-fingerprint`(번들 안), `stream2` 파트 매개변수: `bundle-group-id`(mandatory), `shard-id`(샤드 지문 이진), `bundle-group-top-level`(`1`, 최상위 파일 포함 번들일 때만). bundlespec 문자열로 옮길 때(`exchange.py` 190~210줄)는 지문을 **텍스트**로 바꿔 `;store-fingerprint=...;shard-id=...;bundle-group-id=...;bundle-group-top-level=...`로 붙인다. 상수: `BUNDLESPEC_STORE_FINGERPRINT`, `BUNDLESPEC_SHARD_ID`, `BUNDLESPEC_BUNDLE_GROUP_ID`, `BUNDLESPEC_BUNDLE_GROUP_TOP_LEVEL`.
- `bundle` 명령의 `shape` bundlespec 옵션은 Rust가 있을 때만 허용(`cmd_impls/bundle.py` 57~68줄, 아직 안 읽음).

## 8. 클라이언트 쪽

- `hg clone --shape NAMES`: 먼저 `store_shape` 호출 -> (지문들, shards_sets, 패턴) -> 패턴으로 narrow 클론(포함만 있으면 제외 없음; 제외만 있으면 포함 `path:.` 보충), 작업 중 `store-shape` 파일을 저장소 트랜잭션의 파일 생성기로 `.hg/store/store-shape`에 쓴다(`wlock`, `lock`, 트랜잭션 `narrow-clone` 안). 이어서 `exchange.pull(..., store_fingerprints=지문들, shards_sets=shards_sets)`.
- `store-shape` 파일 형식(v0): 줄 단위. 1행 `0`(버전), 2행 이름들(쉼표), 끝에 개행. `splitlines()` 결과가 정확히 2줄이 아니면 `too many lines in store-hape: expected 2, got %d`(오타 `hape` 그대로), 비었으면 `empty store-hape file`, 버전 파싱 실패 `invalid store-hape version line`, 버전 != 0이면 `unknown store-hape version number: %d`, UTF-8이 아니면 `store-shape contains invalid UTF8`. 읽을 때 `txnutil.mayhavepending`이면 `store-shape.pending`을 먼저 본다.
- 풀 때 `store_fingerprints`가 있으면 clonebundle에서 **같은 지문의** 번들만 고른다(bundlecaches).

## 9. sharded 번들

### 9.1 생성 (`bundle2.write_repo_sharded_stream_bundles`)

```
with repo.wlock(), repo.lock():
    store_shards = get_store_shards(repo.root)
    caps = {stream: [v2]} (+ obsmarkers: V1 이면 obsolescence)
    bundle_group_id = docket.make_uid()          # 한 번에 만든 묶음의 공통 ID
    for info in store_shards.sharded_bundle_info():   # 샤드 이름 순, 경로 있는 것만
        fingerprint = ShardFingerprint(...)
        filename = "hg-sharded-<group_id>-<fingerprint 텍스트>.hg"
        narrow_info = NarrowInfo(matcher, fingerprint, bundle_group_id, match_top_level_entries)
        bundle = bundle20(ui, caps); addpartbundlestream2(bundle, repo, narrow_info, stream=True)
        경로 = <server.peer-bundle-cache-root>/filename 에 changegroup.writechunks
```

- **전체 작업이 `wlock` 다음 `lock` 아래**다. 번들마다 `stream2` 파트의 `generatev2`가 다시 `repo.lock()`을 재진입으로 잡는다.
- `match_top_level_entries` = 샤드가 `.hg-files`일 때만 true. 그 번들에만 changelog, manifest, 캐시 등 최상위 파일이 들어간다. 나머지 샤드 번들은 해당 샤드 경로의 filelog만.
- `addpartbundlestream2`: 서버가 stream을 허용하지 않으면 `stream data requested but server does not allow this feature`, 클라이언트 능력에 `stream`이 없으면 `...supported streaming clone versions were not specified`, 공통 버전이 없으면 `no common supported version with the client: %s; %s`, `narrow_info.matcher`가 있는데 `experimental.server.stream-narrow-clones`가 꺼져 있으면 `server does not support narrow stream clones`, 서버에 obsolescence marker가 있는데 클라이언트가 못 받으면 `server has obsolescence markers, but client cannot receive them via stream clone`. v2 파트 매개변수: `bytecount`, `filecount`, `requirements`, 그리고 위 shard 매개변수들.
- `_entries_walk`: `repo.store.walk(matcher, phase, obsolescence, top_entries)`로 파일을 고른 뒤 최상위 번들이면 `cacheutil.cachetocopy` 캐시 파일도 `'c'` 출처로 붙인다.

### 9.2 선택 (`bundlecaches`)

필터링(항목마다, 이유는 debug 로그):
- 풀 클론(`store_fingerprints is None`): 샤드 ID가 있으면 제외(`it is sharded bundle`), store-fingerprint가 있으면 제외(`it uses a store-shape`).
- shape 클론: 샤드 ID가 있고 `shards_sets`가 없으면 제외; 샤드 ID가 없는데 store-fingerprint도 없으면 제외; store-fingerprint가 요청된 것 중에 없으면 제외.
- 이어서 sharded 항목은 그룹별로 "요청된 세트 중 하나를 부분집합으로 포함하는 그룹"만 남기고(`valid_groups`), 그 그룹의 항목 중 **그 세트에 속한 샤드의 번들만** 남긴다. 아니면 `bundle group %s is missing some required shards` / `shard %s is not requested`.
- `best_clonebundles`: 정렬된 첫 항목이 sharded면 같은 그룹에서 **샤드당 하나**를 고르고, `bundle-group-top-level` 번들을 맨 앞에 둔다(없으면 assert).

## 10. 관리 명령 (`admin::narrow-server`, `admin::narrow-client`)

`admin::narrow-client --store-fingerprint`: narrow 클론이 아니면 `this command only makes sense in a narrow clone`; 있으면 저장된 narrowspec 패턴으로 지문을 계산해 텍스트 출력.

`admin::narrow-server`: Rust 필요(`this command needs the Rust extensions`), narrow 저장소에서는 `repo is narrowed, this is a server command`. 하위 명령은 **최대 하나**: `--shape-fingerprints`, `--shape-patterns NAME`, `--shape-narrow-patterns NAME`, `--shape-files NAME`, `--shape-files-hidden NAME`, `--store-shards`, `--shape-update`, `--shape-clear-previous`. 없으면 `need at least one flag`. 읽기 명령은 `store-shapes-lock` 파일이 있으면 경고만 출력하고(`warning: store shapes are currently being updated (lock held by %s)`) 계속한다.

출력 형식:
- `--shape-fingerprints`: 줄마다 `<지문 텍스트> <쉼표로 이은 이름>`
- `--store-shards`: 줄마다 `<샤드 지문>` + (read-write-access가 있으면) ` read-write-access=a,b`
- `--shape-patterns`: ZeroPath 정렬로 `inc:/<path>` / `exc:/<path>`
- `--shape-narrow-patterns`: 빈 경로 제외, `[include]`/`[exclude]` 섹션과 `path:<경로>`
- `--shape-files`/`--shape-files-hidden`: shape 매처에 걸리는 filelog들을 정렬해 출력. 작업 사본(`repo[None].matches`)에 있으면 known, 없으면 hidden.

### 10.1 `--shape-update`와 잠금 (13번 항목의 핵심)

```
with repo.store_shapes_lock(wait=False):                  # 1) store-shapes-lock, 기다리지 않음
    old = get_store_shards(repo.root); old_contents = svfs.tryread("server-shapes")
    새 내용 = --file 또는 편집기(action 'shape-update')
    new = get_store_shards_from_bytes(새 내용)            # 여기서 설정 검증 오류가 난다
    saved = previous_configs(repo)
    _validate_shape_update(old, new, saved, **opts)
    새 내용이 헤더로 시작하지 않으면 헤더를 앞에 붙인다
    with repo.lock():                                      # 2) 그 다음 store lock
        changed_shards = old.changed_shards(new)
        if old_contents and changed_shards: save_previous_config(repo, old_contents)
        svfs.write("server-shapes", 새 내용, atomictemp=True)
```

헤더(정확히):
```
# Do not edit this file by hand!
# Use `hg admin::narrow-server --shape-update` instead,
# which will ensure the update is made safely.
```

`_validate_shape_update` 거부 조건(각 `override_*` 옵션이 없을 때):
1. 기존 shape의 지문을 바꿈: `won't change the fingerprint of existing shapes: "a", "b"` (hint: `see 'hg help "narrow.updating the shapes config"' for how to publish this as a new shape`)
2. shape와 샤드를 동시에 바꿈: `cannot reshape and reshard in the same update\nshapes changed: ...\nshards changed: ...`
3. 저장된 이전 설정이 남아 있는데 shape를 바꿈: `cannot reshape yet: %d previous server-shapes still saved\nshapes changed: ...`

(이름 인용은 `"a", "b"` 형식, 모두 `InputError`.)

`--shape-clear-previous`: `with repo.store_shapes_lock(wait=False), repo.lock():`(**이 순서**) 저장된 이전 설정 파일을 모두 지우고 목록 파일을 빈 내용으로 쓴 뒤 `cleared %d saved server-shapes` 출력.

이전 설정 저장: 파일명 `server-shapes-previous-<sha256 hex 앞 12자>`, 목록 파일 `server-shapes-previous-list`(줄 구분), 모두 `atomictemp`. 호출자는 **스토어 shapes lock을 잡고 있어야** 한다.

### 10.2 `store-shapes-lock`

`localrepo.store_shapes_lock(wait=True)`: 스토어 vfs 아래 `store-shapes-lock` 파일을 쓰는 일반 hg 잠금(재진입은 같은 락 객체의 카운트 증가). hg4j의 `HgLock`은 파일 이름만 바꾸면 쓸 수 있다. 읽기 명령의 경고는 잠금 파일 내용(보유자)을 `readlock`으로 읽는다.

## 11. 잠금 요약 (hg와 맞춰야 할 위치)

| 작업 | 잠금 |
|---|---|
| `--shape-update` | `store-shapes-lock`(대기 없음) -> 안쪽에서 `lock` (쓰기 구간만) |
| `--shape-clear-previous` | `store-shapes-lock`(대기 없음), `lock` 동시 진입 |
| sharded 번들 생성 | `wlock` -> `lock` (번들 생성 내내) |
| 서버 `store_shape` 명령 | 잠금 없음(읽기). 단 `generatev2`는 `repo.lock()` |
| `clone --shape` | 목적지 `wlock` -> `lock` -> 트랜잭션 `narrow-clone` |

## 12. 아직 읽지 않은 곳 (포팅 전에 읽고 이 문서에 채울 것)

1. `rust/hg-pyo3/src/shape.rs`: Python 바인딩. 오류 -> 예외 매핑, 사용자에게 보이는 오류 문구, `get_store_shards`/`get_store_shards_from_bytes` 시그니처, `fingerprint_for_patterns`의 `None` 조건.
2. `rust/hg-core/src/narrow/mod.rs`와 `file_patterns.rs`의 `PatternSyntax`/`UnsupportedSyntaxNarrow` 문구.
3. `mercurial/store.py` `walk(matcher=..., top_entries=...)`/`data_entries(matcher=...)`: 샤드 매처로 filelog를 고르는 규칙(트리매니페스트 `meta/`, `phaseroots`, obsstore 포함 여부).
4. `cmd_impls/bundle.py` 50~80줄 `shape` bundlespec, `debugcommands.py`의 `debug::sharded-stream-bundles`와 `debug::` 쪽 shape 명령.
5. `bundle2_part_handlers.py` 620~650줄 `store-fingerprint` 파트의 서버/클라이언트 처리.
6. `exchange.py` 1973~2000, 3040~3070줄(풀에서 shape가 getbundle 인자로 가는 경로)과 `pull`의 `shards_sets` 사용.
7. Rust test 중 `test_patterns`(1887줄), `test_matcher_simple`(2059줄), `test_config_invalid`(2336줄, 오류별 입력 예), 변경 비교 test(2585~2904줄)의 입력·기대값. **이 test들이 포팅의 명세**이므로 1:1로 JUnit으로 옮긴다.
8. `rust/hg-core/src/utils/hg_path.rs`의 `check_state` 규칙(유효 경로 판정).

## 13. 포팅 설계와 진행

**진행 상태**: A, B, C, D단계 완료. C단계: `ShapeWire`(블록 인코딩·디코딩, 클라이언트 지문 검증), 서버 `store_shape`(실제 구현, 이전 설정의 샤드 세트 병합), `exp-shape-1`을 `experimental.narrow`와 `advertise-shapes`가 모두 켜졌을 때만 광고(이전에는 advertise-shapes만 봤다), getbundle의 `store_shape_with_fingerprint`(shape 조회, 지문 대조, `path:` 레거시 패턴 변환), `HgRemoteConnection.storeShape` HTTP·SSH 구현, `StoreShapeFile`(store-shape 파일), `NarrowCloneCommand.setShape`(`clone --shape`: 먼저 `store_shape`를 불러 실패하면 저장소를 만들지 않음, narrowspec과 `store-shape` 저장, 이후 pull은 패턴 대신 `names:fingerprint`를 보냄), `NarrowScope.storeShapeWithFingerprint`. `StoreShapeWireTest` 7개, `ShapeCloneTest` 7개. **함께 고친 기존 결함 두 가지**: (1) 서버가 와이어로 받은 루트 패턴 `path:.`를 거부하던 것(실제 hg 클론은 "전체 포함"을 `path:.`로 보낸다), (2) hg 7.2는 `path:` 패턴만 있는 narrowspec을 트리로 읽어 제외 안의 포함이 유지되는데 hg4j `NarrowSpecFilter`는 제외 우선으로 읽던 것(`NarrowSpecTreeSemanticsTest` 5개로 고정). **남음(E단계)**: sharded 번들 생성(`write_repo_sharded_stream_bundles`: `wlock`->`lock`, 샤드별 `stream2` 파트에 matcher 적용, `bundle-group-id`/`shard-id`/`bundle-group-top-level` 매개변수), 클론번들 선택(`filterclonebundleentries`의 shape/샤드 규칙, `best_clonebundles`)과, 이를 쓰려면 클라이언트가 클론번들 안의 stream 번들을 적용할 수 있어야 한다(지금 `ClonebundlesCommand.downloadAndApply`는 changegroup만 다룬다).

이전 단계: D단계: `NarrowServerAdmin`(조회 5종, `shapeUpdate`, `shapeClearPrevious`, `validateUpdate`의 세 거부와 override, 문구는 hg와 동일), `ShapeConfigStore`(이전 설정 저장·읽기·삭제, `previousShardsSets`), `HgRepository.lockStoreShapes`/`readStoreShapesLockHolder`. `NarrowServerAdminTest` 11개: shapes lock은 기다리지 않고(`wait=False`) 실패하며 아무것도 쓰지 않음, store lock은 쓰기 구간에만 필요함, 조회 명령은 잠금 없이 경고만 함, 6개 스레드 동시 update에서 설정 파일이 찢어지지 않고 교체된 설정이 한 번씩만 저장됨. 각 잠금을 끈 변형에서 대응 test가 실패함을 확인했다. **실제 동작 메모**: 설정이 전혀 없던 저장소의 첫 update는 shape(`default` 추가)와 샤드(`base`에서 분리)가 동시에 바뀌므로 hg도 `--override-reshape-and-reshard-check` 없이는 거부한다.

B단계: B단계: `ShapeException`(종류 + 사용자 문구), `MiniToml`(TOML 부분집합), `StoreShards`(설정 검증, 암묵 샤드, 의존성, `shape`, `allShapes`, 샤드 지문, `listShards`, `shardedBundleInfo`, `shardFingerprintsForShape`, 변경 비교), `Shape`. `StoreShardsTest` 18개는 `shape.rs` 단위 test(설정 예시, 22개 오류 사례, 의존성, 모든 shape의 패턴과 순서, 58행 파일별 매처 표, 가장 깊은 접두 노드, `visit_children_set`, 변경 비교 전부)를 옮긴 것이다.

A단계: `io.github.search5.hg4j.shape`의 `Fingerprint`, `ShapeNames`, `ShardTreeNode`(ZeroPath, `fromPaths`, `fromPatterns`, `flat`, `deepestPrefixNode`, 패턴 블록 직렬화·역직렬화, 지문), `ShapeMatcher`. 검증: `ShardTreeNodeVectorsTest` 7개, 설치된 hg의 Python 구현으로 만든 400건(`fixtures/shape/tree-vectors.tsv`)의 지문, 패턴 블록, 표본 경로별 매처 결과가 모두 일치하고 doctest 값과 오류 문구도 같다. 경로는 Java `String`이고 정렬과 길이는 UTF-8 바이트로 잰다.

### 설계

**원칙**: Java가 스스로 하는 순수 구현. 네이티브 연결은 쓰지 않는다(사용자 결정 2026-11-04: "Java로 포팅하자").

계층과 순서:

| 단계 | 내용 | 기준(oracle) |
|---|---|---|
| A | `Fingerprint`(이진/텍스트), 이름 검증, 패턴 블록 직렬화·역직렬화, `ZeroPath`, `ShardTreeNode`(정규화, `flat`, `deepest_prefix_node`, 지문), `ShapeMatcher` | **설치된 hg의 Python**(`mercurial.shape`)을 직접 호출해 임의 입력의 지문·블록을 비교 + Rust test 벡터 |
| B | TOML 설정 파싱(`ShapesConfig`), `StoreShards`(검증, 암묵 샤드, 의존성, `shape`, `all_shapes`, 샤드 지문, 변경 비교) | Rust 단위 test를 1:1 포팅 + cargo로 빌드 가능하면 Rust 바이너리 출력 |
| C | 와이어 `store_shape` 서버/클라이언트, `exp-shape-1` 능력, getbundle `store_shape_with_fingerprint`, `clone --shape`, `store-shape` 파일 | 분석된 형식 + hg4j 서버와의 왕복. 실제 Rust hg는 cargo로 만들 수 있으면 비교 |
| D | `admin::narrow-server`/`client`, `store-shapes-lock`, 이전 설정 저장 | 문구는 소스 대조, 잠금은 경쟁 test(잠금을 끄면 실패) |
| E | sharded 번들 생성(`wlock`->`lock`)과 clonebundle 선택 | 위 단계 + 기존 `StreamClone` 서버 구현 확장 |

Java 설계 메모:
- 패키지 `io.github.search5.hg4j.shape`. TOML 파서는 의존성을 늘리지 않으려면 **필요한 부분집합**(테이블, 테이블 배열, 문자열, 문자열 배열, 불리언, 정수, 주석)만 직접 구현한다. 알 수 없는 키는 오류. 오류 위치 문구는 Rust의 `toml` 크레이트와 다르므로 `ParseError`의 상세 문구는 보장하지 않는다(사용자 확인 필요, 아래 결정 대기).
- 경로는 `byte[]`/ISO-8859-1 `String`으로 바이트 정렬을 보존한다(hg4j는 이미 `Arrays.compareUnsigned`를 쓴다).
- 트리는 불변 객체. Rust의 `Arc<RwLock>` 임시 구조는 불필요하다(스택 스캔을 가변 노드로 쓰고 끝에 동결).

### 결정 대기 / 주의

1. 포함과 제외가 같은 경로일 때(4.3절)의 동작: Python(제외 우선)으로 통일할지.
2. `ParseError` 문구: Rust `toml` 크레이트의 메시지를 그대로 재현하는 것은 비현실적이다. 문구는 "설정 파싱 실패: <원인>"으로 두고 종류만 맞출지.
3. `rustmod`가 없는 순수 Python hg는 shape 서버를 **하지 않는다**(`store_shape`는 Rust 없으면 Abort). hg4j가 서버 쪽을 제공하면 hg 대비 상위 기능이 된다. "레거시에 없는 기능은 추가하지 않는다" 원칙과는 별개인 hg 쪽 문제이므로 허용으로 가정한다.

## 14. narrowspec: hg4j를 원본과 같게 맞춘 내용 (2026-11-04 사용자 지시)

`mercurial/narrowspec.py`를 `io.github.search5.hg4j.narrow.NarrowSpec`으로 옮겼고, 설치된 hg의 실제 `mercurial.narrowspec`으로 만든 벡터(`fixtures/shape/narrowspec-vectors.tsv`)로 검증한다(`NarrowSpecVectorsTest` 8개).

| 원본 | hg4j | 비고 |
|---|---|---|
| `normalizepattern`, `normalizesplitpattern`, `_validatepattern` | `NarrowSpec.normalizePattern`, `validatePattern` | 오류 문구 동일(개행, 공백, `.`/`..`, 빈 구성요소). 종류 접두어 판정은 `matchmod._patsplit`(알려진 종류만) |
| `validatepatterns` | `validatePatterns` | `path:`/`rootfilesin:` 접두어만 검사. 내부 표현이라 루트가 `path:.`일 수 있다 |
| `parsepatterns` | `parsePatterns` | 사용자 입력용: 정규화 후 검증. `path:.`는 사용자가 쓸 수 없다 |
| `format` | `format` | `sorted(includes - excludes)`, 포함이 있으면 `[include]` 섹션을 항상 쓴다 |
| `parseconfig` + `sparse.parseconfig` | `parseConfig` | 공백 제거, `#` 주석, 섹션 밖 항목 오류, `[exclude]` 뒤 `[include]` 오류, `/`로 시작하는 항목은 경고 후 무시, `%include` 거부 |
| `restrictpatterns` | `restrictPatterns` | 서버 ACL용 |
| `to_legacy_patterns` | `toLegacyPatterns` | 루트는 `path:.` |
| `match` | `match`, `HgTreeFilter.NarrowSpecFilter` | 포함 없음 = 아무것도 안 맞음. `path:`만 있으면 **트리 매처**(중첩된 포함·제외가 안쪽 규칙을 따름), `rootfilesin:`이 있으면 옛 해석(제외 우선) |
| `checkworkingcopynarrowspec` | `HgTreeFilter.loadForWorkingCopy` (`UpdateCommand`가 사용) | `.hg/narrowspec.dirstate`(없으면 빈 것)가 스토어와 다르면 `working copy's narrowspec is stale` + 힌트 |
| `load` | `HgTreeFilter.loadFromRepository` | `parseConfig`로 읽는다(예전에는 줄마다 사용자 정규화를 해서 `path:.`가 든 파일을 읽지 못했다) |

고친 기존 차이: (1) `path:` 패턴만 있는 narrowspec을 제외 우선으로 읽던 것 -> 트리 해석, (2) 사용자 정규화와 내부 검증을 구분하지 않아 와이어의 `path:.`와 shape 클론의 루트 패턴을 거부하던 것, (3) 정규화 오류 문구가 hg와 달랐던 것(`...: <패턴>` 접미어).

아직 옮기지 않은 것(확장 `hgext/narrow` 범위라 제외 결정): `hg tracked`(widen/narrow), `narrowwirepeer`의 ACL 적용(`restrictPatterns`는 포팅했지만 서버가 ACL을 읽는 부분은 없다), `narrowspec.save`의 트랜잭션 파일 생성기(hg4j는 저장소 잠금 아래 원자적 쓰기).

**E단계 진행 (2026-11-04)**: Rust 확장 hg(`~/.cache/hg4j/hg-rust-dev/bin/hg`, 7.2.4.dev0)가 이미 설치돼 있어 정답 바이트로 검증한다. ① 샤드 번들 **생성** 완료: `ShardedStreamBundlesCommand`(`wlock` 다음 `lock`, `server.peer-bundle-cache-root`(`.hg` 기준 상대, 기본 `bundle-cache`), `hg-sharded-<group>-<shard 지문>.hg`), `StreamClone.stream2`에 `ShardSelection`(경로 매처, 최상위 여부) 추가: 최상위 샤드만 root revlog, phaseroots, 캐시(`'c'`)를 담고 나머지는 매처에 걸리는 `data/` filelog만. `stream2` 매개변수 순서는 `bytecount, filecount, requirements, bundle-group-id, shard-id(이진: 버전 1바이트 + 다이제스트), bundle-group-top-level(최상위만)`. **실제 Rust hg가 만든 번들과 바이트가 같다**(`ShardedStreamBundlesParityTest`: 샤드 3개 이상, 최상위 번들의 캐시 포함). ② clonebundle 선택, ③ 클라이언트의 stream 번들 적용은 남음.

**E단계 2 완료 (2026-11-04)**: clonebundle **선택**을 `bundle/ClonebundleSelection`으로 이식: `parseLine`(bundlespec: 압축·버전 표, v1 압축 제한, 필수(대문자) 매개변수는 알려진 것만, 지문 텍스트 검증, 불리언 검증, 요구사항 확인, `isstreamclonespec`: `none` 압축이고 `packed1`, `streamv2`, `streamv3-exp`), `filter`(스킴, delta-compression, store-shape/샤드 규칙, 스트림 요청 여부, DIGEST는 sha256/sha512만, 샤드 그룹 검증), `sort`(`ui.clonebundleprefers`), `best`(그룹에서 샤드당 하나, 최상위 번들 맨 앞). 실제 Rust hg의 `bundlecaches`가 낸 답 400건(샤드 항목이 남는 경우 65건, assert 1건 포함)과 일치(`ClonebundleSelectionVectorsTest`, 생성 스크립트는 `HGMODULEPOLICY=rust+c`로 실제 모듈을 호출). 알아낸 사실: 다이제스트는 md5/sha1이 아니라 sha256, sha512만 지원. 남음: 3) 클라이언트가 받은 stream 번들 적용과 `FetchCommand`가 이 선택을 쓰도록 연결.

**E단계 3 완료 (2026-11-04)**: 클라이언트가 클론번들로 shape clone을 한다. ① `ClonebundlesCommand.applyEntries`: `best_clonebundles`가 고른 항목을 `with repo.lock(), repo.transaction("clonebundles")`처럼 store lock 아래에서 차례로 받아(HTTP, 또는 `peer-bundle-cache://`는 서버의 `get_cached_bundle_inline`) DIGEST(sha256/sha512)와 크기를 확인하고, stream2 파트가 있으면 `StreamCloneClient.applyBundleFile`로, 아니면 changegroup 번들로 적용한다. 메시지는 원본대로(`applying N clone bundles`, `applying clone bundle from URL`, `finished applying ...`). ② `get_cached_bundle_inline`을 `HgRemoteConnection`에 추가(HTTP, SSH, 로컬). ③ **매니페스트 요청**: 개발 트리의 서버는 옛 `clonebundles`에는 `peer-bundle-cache://` 항목을 빼고 필수 매개변수를 낮춰 주므로, 클라이언트는 `clonebundles_manifest`가 있으면 `mandatory_params=1`로 요청한다. ④ `FetchCommand`가 `ClonebundleSelection`(필터, 정렬, 그룹 선택)을 쓰고, `clone --shape`가 `store_shape` 응답의 지문과 샤드 집합을 `PullCommand.setStoreFingerprints/setShardsSets`로 넘긴다. 검증(`ShardedCloneBundlesTest`): hg4j 서버와 **실제 Rust hg 서버**(`clonebundles` 확장을 켜야 `clonebundles` 능력이 광고된다) 양쪽에서 `clone --shape foo`가 `applying 2 clone bundles`로 샤드 번들을 적용하고, 다른 샤드(bar)의 filelog는 저장하지 않는다. 남은 차이: `REQUIREDRAM` 확인 없음, 인라인 번들의 `pretransmit-inline-clone-bundle` 훅 없음, `ui.clonebundlefallback` 설정은 항상 "실패하면 중단".
