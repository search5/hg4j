호스트 native `hg`(Mercurial 7.2, Rust 확장 없음, `python3 -sys.path`상
`/usr/lib/python3/dist-packages/mercurial`)로 만든 실제 cg4/cg5 changegroup
**페이로드**(HG20/bundle2 봉투 없이, `changegroup.getunbundler()`가 그대로 소비하는
raw 청크 스트림만) 픽스처. `ChangegroupParser.parseBundle(in, "04"/"05")`가 그대로
소비할 수 있는 바이트 그대로다(hg4j의 `Bundle2Parser.extractChangegroupDetailed()`가
실제 HG20 번들에서 뽑아내는 결과물과 동일한 모양).

## 원본 저장소

```
hg init repo
cd repo
echo "hello" > a.txt; hg add a.txt; hg commit -m "c1" -u test
echo "world" >> a.txt; hg commit -m "c2" -u test
mkdir sub; echo "x" > sub/b.txt; hg add sub/b.txt; hg commit -m "c3" -u test
```

`.hg/store/requires`: `dotencode fncache generaldelta revlog-compression-zstd
revlogv1 sparserevlog store` — **treemanifest 요구사항 없음(플랫 매니페스트)**,
cg4/cg5는 `experimental.changegroup4`/`changegroup5` 설정으로만 강제 활성화됐다
(기본 `hg init` 저장소는 cg4/cg5를 광고하지 않는다 — 상세는
`llm-wiki/decisions/mercurial-spec-compliance-requirement.md`의 백로그 11번 참고).

```
2:9e54c4614e4d49fb429320031672db550c732ebf c3
1:2b5dc66069d50b5f90a69020825eaf9eecd3eaa2 c2
0:0adc5734e236131b6ec5eef2aa4e623f7f6ff1e7 c1

hg manifest --debug -r 2:
f57bae649f6e9be3b9063b84cdbcde77a1aca797 644   a.txt
1406e74118627694268417491f018a4a883152f0 644   sub/b.txt
```

## 생성 스크립트 (CLI `hg bundle -t v4/v5`는 지원하지 않아 — "v4 is not a recognized
bundle specification" — Mercurial 내부 API를 직접 호출)

```python
import sys; sys.path.insert(0, '/usr/lib/python3/dist-packages')
from mercurial import hg, ui as uimod, changegroup, discovery

u = uimod.ui.load()
u.setconfig(b'experimental', b'changegroup5', b'yes', source=b'test')
repo = hg.repository(u, b'.')
outgoing = discovery.outgoing(repo, missingroots=None, ancestorsof=repo.changelog.heads())
for ver in (b'04', b'05'):
    cg = changegroup.makechangegroup(repo, outgoing, ver, b'test-source')
    with open('cg%s-payload.bin' % ver.decode(), 'wb') as f:
        for chunk in cg.getchunks():
            f.write(chunk)
```

`cg04-payload.bin` sha1: `900be19895a386e0d603d693ed4e812241eefac3`
`cg05-payload.bin` sha1: `6ee781a2c1148e14c08b4946e452ae68b6b7e0e2`

## 바이트 단위로 대조 확인된 델타 헤더 레이아웃

두 파일 모두 `python3 struct`로 직접 재파싱해 아래 레이아웃과 정확히 일치함을
확인했다(백로그 11번의 소스 기반 요약과 100% 일치, 차이 없음):

- **cg4** (`_CHANGEGROUPV4_DELTA_HEADER`, 130바이트): `node(20) p1(20) p2(20)
  deltabase(20) cs(20) flags(H,2) snapshot_level(b,1,signed) raw_size(I,4)
  encoded_comp(B,1) protocol_flags(B,1) storage_delta_base(20)
  storage_snapshot_level(b,1,signed)`.
  실측: changelog/manifest의 rev0 엔트리(부모 없음)는 `deltabase=all-zero`,
  `protocol_flags=2`(`CG_FLAG_FULL_TEXT`) — 페이로드가 bdiff 델타가 아니라
  **압축되지 않은 원문 그대로**(`raw_size`와 델타 청크 길이가 정확히 같음). 실제
  부모가 있는 manifest rev2 엔트리(`630b0d5f...`)는 `deltabase=b1e08607...`(0이
  아님), `protocol_flags=0`, `snapshot_level=-1`, 델타 청크 길이(63) <
  `raw_size`(98) — 통상적인 bdiff 델타.
- **cg5** (`_CHANGEGROUPV5_DELTA_HEADER`, 103바이트): `protocol_flags(B,1)
  node(20) p1(20) p2(20) deltabase(20) cs(20) flags(H,2)`. 이 픽스처의 모든
  엔트리는 `protocol_flags=0`(sidedata 없음 — 플랫 저장소라 원천적으로 없음) —
  cg1/cg2/cg3와 동일하게 매 엔트리가 bdiff 델타(부모 없는 rev0도 "empty에 대한
  델타"로, 별도의 raw-full-text 모드 없음).
- 두 버전 모두 매니페스트 섹션은 **루트 그룹이 경로 청크 없이 바로 옴**, 그 뒤에
  서브디렉터리가 없으므로 곧바로 `manifestsend`(빈 closechunk) 하나만 옴 — 이
  구조를 상정하지 않은 `ChangegroupParser.parseBundle()`의 기존 cg3 처리 버그를
  cg4/cg5 작업 중 이 픽스처로 직접 발견·수정했다(2026-09-03, 상세는
  `mercurial-spec-compliance-requirement.md` 백로그 11번 참고).
