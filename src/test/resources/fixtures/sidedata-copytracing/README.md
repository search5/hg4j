실제 `hg` CLI(Mercurial 7.2, 시스템 설치본, **Rust 불필요** — 순수 Python 구현으로 생성됨.
자세한 내용은 아래 "Rust 필요 여부" 절 참고)로 아래 절차로 만든 changelog-v2(docket 기반) +
`exp-copies-sidedata-changeset` sidedata가 실제로 채워진 저장소에서 얻은 파일 4종.

```
hg --config format.exp-use-copies-side-data-changeset=yes init repo1
cd repo1
echo "hello world" > a.txt
hg add a.txt
hg commit -u "search5@mz.co.kr" -m "add a.txt" -d "2026-09-01 00:00:00 +0000"
hg mv a.txt b.txt
echo "more content" >> b.txt
hg commit -u "search5@mz.co.kr" -m "rename a to b" -d "2026-09-01 00:01:00 +0000"
echo "second file" > c.txt
hg add c.txt
hg cp b.txt d.txt
hg commit -u "search5@mz.co.kr" -m "add c.txt, copy b to d" -d "2026-09-01 00:02:00 +0000"
```

`format.exp-use-copies-side-data-changeset=yes`는 `.hg/store/requires`에
`exp-changelog-v2` + `exp-copies-sidedata-changeset`를 함께 추가한다
(`mercurial/repo/creation.py`: 이 옵션이 켜지면 changelog-v2도 자동으로 강제된다 — copies
sidedata는 changelog-v2 없이는 저장할 곳이 없기 때문).

## 파일 목록 (원본 파일명 → 이 디렉터리의 파일명)

- `00changelog.i` → `docket.i`: 실제 docket(59바이트 S_HEADER + 3개 UUID, 83바이트 총합)
- `00changelog-53306201.idx` → `data.idx`: INDEX_ENTRY_CL_V2, 레코드당 96바이트, 3개 레코드(288바이트)
- `00changelog-eb558592.dat` → `data.dat`: changelog 텍스트(리비전별 독립 프레임, PLAIN 또는 zstd)
- `00changelog-2ddfce29.sda` → `data.sda`: sidedata(180바이트, 3개 리비전 모두 채워짐 — 기존
  `revlogv2-changelog` 픽스처와 달리 이 저장소는 실제 sidedata 내용을 담고 있음)

테스트에서 로드할 때는 `RevlogV2ParserTest`와 동일한 관례로, 실제 파일명(UUID 접미사 포함)으로
임시 디렉터리에 배치한다: `docket.i`→`00changelog.i`, `data.idx`→`00changelog-53306201.idx`,
`data.dat`→`00changelog-eb558592.dat`, `data.sda`→`00changelog-2ddfce29.sda`.

## Rust 필요 여부

**불필요함을 직접 실험으로 확인했다.** 로컬 `/usr/bin/hg`(Mercurial 7.2, 시스템 설치본,
Rust 확장 없음)로 위 `init`/`commit` 명령을 그대로 실행했고, "accessing ... without associated
fast implementation" 같은 에러 없이 정상적으로 changelog-v2 + sidedata가 채워진 저장소가
만들어졌다. `exp-copies-sidedata-changeset`는 changelog-v2(자체 revlog 버전 매직값
`CHANGELOGV2 = 0xD34D`)와 그 sidedata 컨테이너 포맷을 요구할 뿐, 저장소 **생성**이나
**커밋** 경로 어디에도 Rust 전용 코드가 관여하지 않는다(`mercurial/repo/creation.py`,
`mercurial/metadata.py`의 `copies_sidedata_computer`/`encode_files_sidedata`는 모두 순수
Python). 이 세션에서 이미 만들어 둔 `hg-rust-7.2.4` Docker 이미지는 사용하지 않았다.

## 커밋된 노드 해시

- rev0: `bd83d96939b4fb5469828a746b8ad5d1bcdfaa85` — "add a.txt"
- rev1: `eb5101eb369951ce575c86ec85e6e694e3fe0e53` — "rename a to b"
- rev2: `06114b45a4a6503ab80e67d0d3b6837e3d5ade3a` — "add c.txt, copy b to d"

## docket(S_HEADER, 59바이트) 실측값

`S_HEADER = struct.Struct('>IBBBBBBQQQQQQc')`(mercurial/revlogutils/docket.py 실측):

| 필드 | 값 |
|---|---|
| version_header | `0xD34D` (CHANGELOGV2) |
| index_uuid_size / data_uuid_size / sidedata_uuid_size | 8 / 8 / 8 |
| older_index_uuid_count / older_data_uuid_count / older_sidedata_uuid_count | 0 / 0 / 0 |
| index_end (pending 동일) | 288 (=3레코드×96바이트) |
| data_end (pending 동일) | 286 |
| sidedata_end (pending 동일) | 180 (= `data.sda` 파일 전체 길이) |
| default_compression_header | `0x28` (`'('`, zstd) |
| index_uuid / data_uuid / sidedata_uuid | `53306201` / `eb558592` / `2ddfce29` |

## 인덱스 레코드(`INDEX_ENTRY_CL_V2 = '>Qiiii20s12xQiBi23x'`, 96바이트/레코드) 실측값

python struct로 `data.idx`를 직접 대조 검증:

| rev | data offset | flags | complen | uncomplen | p1 | p2 | sidedata offset | sidedata complen | compression byte | data_comp_mode(byte&3) | sidedata_comp_mode((byte>>2)&3) | rank |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0 | `0x0` (HASCOPIESINFO 없음 — 최초 커밋이라 copy 관계 없음) | 87 | 87 | -1 | -1 | 0 | 46 | `0x00` | 0 (PLAIN) | 0 (PLAIN) | 1 |
| 1 | 87 | `0x1000` (REVIDX_HASCOPIESINFO) | 97 | 97 | 0 | -1 | 46 | 60 | `0x00` | 0 (PLAIN) | 0 (PLAIN) | 2 |
| 2 | 184 | `0x1000` (REVIDX_HASCOPIESINFO) | 102 | 106 | 1 | -1 | 106 | 74 | `0x01` | 1 (DEFAULT/zstd) | 0 (PLAIN) | 3 |

주의: rev0은 `REVIDX_HASCOPIESINFO`(0x1000) 플래그가 **꺼져** 있지만(첫 커밋이라 copy 관계
자체가 없음) sidedata 자체는 여전히 존재한다(46바이트, "add a.txt"라는 파일 변경 정보를 담음).
이 플래그는 "copy 정보가 있는지"의 빠른 판별용 힌트일 뿐, sidedata 존재 여부와는 별개다
(`mercurial/copies.py`의 `_revinfo_getter`가 이 플래그로 sidedata 조회 자체를 건너뛸지 결정하지만,
sidedata는 flag와 무관하게 매 리비전마다 기록된다 — `hg debugchangedfiles`는 flag를 보지 않고
항상 sidedata를 직접 읽는다).

이 세 리비전 모두 sidedata 자체는 압축 이득이 없어(46/60/74바이트로 원래 작음) `COMP_MODE_PLAIN`으로
저장됐다. 반면 rev2의 changelog **본문**(`data.dat`)은 `COMP_MODE_DEFAULT`(zstd)로 저장되어 있다
— sidedata 압축 모드와 본문 압축 모드는 압축 바이트의 서로 다른 비트(하위 2비트=본문,
그 다음 2비트=sidedata)에 독립적으로 인코딩되므로 서로 다를 수 있다.

## sidedata 컨테이너 포맷 (2단계)

### 1단계: 바깥 컨테이너 (`mercurial/revlogutils/sidedata.py`)

```
<개수: uint16 BE>
(반복, 개수만큼) <key: uint16 BE> <length: uint32 BE> <sha1(value): 20 bytes>
(반복, 위와 같은 순서로) <value 바이트들 그대로 이어붙임>
```

이 저장소의 3개 리비전 모두 엔트리가 정확히 1개(`SD_FILES` 키=12)뿐이다.

### 2단계: `SD_FILES`(key=12) 내부 페이로드 (`mercurial/metadata.py` `encode_files_sidedata`/`decode_files_sidedata`)

```
<파일 개수: uint32 BE>
(반복, 개수만큼) <flag: signed byte> <file_end(누적 오프셋): uint32 BE> <copy_idx: uint32 BE>
(반복, 위와 같은 순서로) <파일명 바이트들 그대로 이어붙임, 길이는 file_end 경계로 결정>
```

`flag` 비트 레이아웃 (`mercurial/metadata.py` 실측):
- 비트 2-4(`ACTION_MASK=0b11100`): `0b00100`=added, `0b01000`=merged, `0b01100`=removed,
  `0b10000`=salvaged, `0b10100`=touched, `0b00000`=미변경(copy source로만 등장)
- 비트 0-1(`COPIED_MASK=0b11`): `0b10`=p1으로부터 copy, `0b11`=p2으로부터 copy, `0b00`=copy 아님
- `copy_idx`는 copy일 때만 의미 있음 — 같은 엔트리 목록(파일명은 인코딩 시 정렬된 순서) 안의
  인덱스로, copy source 파일명을 가리킴

## 리비전별 실측 sidedata 바이트 (python struct로 직접 대조 검증됨)

### rev0 (`sidedata offset=0, complen=46`, PLAIN)

```
0001 000c 00000012 665775377cf5d18cb9c79fa9a07cd394b7ec4978 00000001 04 00000005 00000000 612e747874
```
- 바깥 컨테이너: 엔트리 1개, key=12(`SD_FILES`), length=18, sha1=`665775...`
- `SD_FILES` 내부: 파일 1개 — `[flag=0x04(added), file_end=5, copy_idx=0]` + 파일명 `"a.txt"`
- 의미: `added={a.txt}`, copy 없음 (rev0은 최초 커밋)

### rev1 (`sidedata offset=46, complen=60`, PLAIN)

```
0001 000c 00000020 39f0927c09bf1618d9a47ecccc6b85a559adc76b 00000002 0c 00000005 00000000 06 0000000a 00000000 612e747874622e747874
```
- `SD_FILES` 내부: 파일 2개
  - `[flag=0x0c(removed), file_end=5, copy_idx=0]` → `"a.txt"`
  - `[flag=0x06(added|copied_from_p1), file_end=10, copy_idx=0]` → `"b.txt"`, copy source =
    `all_files[0]` = `"a.txt"`
- 의미: `removed={a.txt}`, `added={b.txt}`, `copied_from_p1={b.txt: a.txt}`
- `hg log --copies` / `hg debugchangedfiles 1` 실측 출력과 일치:
  ```
  removed    : a.txt, ;
  added    p1: b.txt, a.txt;
  ```

### rev2 (`sidedata offset=106, complen=74`, PLAIN)

```
0001 000c 0000002e 64ebfc004f36daef7dd4918c1103c7f79b956cb8 00000003 00 00000004 00000000 04 0000000a 00000000 06 0000000f 00000000 622e747874632e747874642e747874
```
- `SD_FILES` 내부: 파일 3개
  - `[flag=0x00(미변경, copy source 전용), file_end=4, copy_idx=0]` → `"b.txt"`
  - `[flag=0x04(added), file_end=10, copy_idx=0]` → `"c.txt"`
  - `[flag=0x06(added|copied_from_p1), file_end=15, copy_idx=0]` → `"d.txt"`, copy source =
    `all_files[0]` = `"b.txt"`
- 의미: `added={c.txt, d.txt}`, `copied_from_p1={d.txt: b.txt}` (b.txt 자신은 이 리비전에서
  안 바뀌었지만 copy source로만 등장하므로 엔트리에는 포함되되 `touched`에는 안 들어감)
- `hg debugchangedfiles 2` 실측 출력과 일치:
  ```
  added      : c.txt, ;
  added    p1: d.txt, b.txt;
  ```

## 검증에 사용한 명령

- `hg debugchangedfiles <rev>` — 저장된 sidedata를 그대로 읽어(재계산 아님) 위 필드별 요약을 출력
- `hg log --copies --template "{rev}:{node|short}\n  files: {files}\n  copies: {file_copies}\n"`
- python3 `struct`로 `data.idx`/`data.sda` 바이트를 직접 언패킹해 위 표/바이트를 대조
