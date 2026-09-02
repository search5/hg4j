# persistent-nodemap fixture

Real bytes for the `persistent-nodemap` requirement's on-disk trie, produced by an actual
Rust-enabled Mercurial (this environment's system `/usr/bin/hg` 7.2.2 has no Rust extension and
refuses to even *create* a persistent-nodemap repository: `abort: accessing persistent-nodemap
repository without associated fast implementation`).

## How it was generated

```
docker build -t hg-rust-7.2.4 docker/hg-rust-7.2.4   # Mercurial 7.2.4 built with Rust extensions

mkdir -p /tmp/pn-repo
docker run --rm -v /tmp/pn-repo:/repo hg-rust-7.2.4 \
  hg --config format.use-persistent-nodemap=true init /repo

# 40 commits, one small text file edit each (see gen.sh below), all as ui.username="Test User <test@example.com>"
docker run --rm -v /tmp/pn-repo:/repo -v /path/to/gen.sh:/gen.sh hg-rust-7.2.4 sh /gen.sh

docker run --rm -v /tmp/pn-repo:/repo hg-rust-7.2.4 hg -R /repo debuginstall
# must print: checking Rust extensions (installed)
```

`gen.sh`:
```sh
set -e
cd /repo
for i in $(seq 1 40); do
  echo "line $i" >> file$((i % 5)).txt
  hg --config ui.username="Test User <test@example.com>" add file$((i % 5)).txt 2>/dev/null || true
  hg --config ui.username="Test User <test@example.com>" commit -m "commit $i" -q
done
```

`.hg/store/requires` after `init` already contains `persistent-nodemap` (store-level, not the
top-level `.hg/requires`).

Only `00changelog.i`/`.n`/`-<uid>.nd` are checked in here (the manifest and both filelogs stayed
*inline* at this size — real hg's own `nodemap.py:persist_nodemap`/`setup_persistent_nodemap`
skip inline revlogs entirely: "inlined revlog are too small for this to be relevant" — so they
never got a `.n` file at all; only `00changelog.d` existed separately, confirming the changelog is
the only non-inline revlog in this fixture and thus the only one persistent-nodemap actually
applies to here).

## Files

- `00changelog.i` — real index, 40 revisions, 2560 bytes (`40 * 64`), non-inline.
- `00changelog.n` — real docket, 62 bytes. Hex: `010800000000000000270000000000000440000000
  0000000040000000000000001464 6238336266 3634` + tip node bytes.
  Parsed fields (verified with `struct.unpack(">B", ...)` + `struct.unpack(">BQQQQ", ...)` against
  `mercurial/revlogutils/nodemap.py`'s own `S_VERSION`/`S_HEADER`, executed against these exact
  bytes):
  - `version` = 1 (`ONDISK_VERSION`)
  - `uid_size` = 8, `tip_rev` = 39, `data_length` = 1088, `data_unused` = 64, `tip_node_size` = 20
  - `uid` = `"db83bf64"` (ASCII)
  - `tip_node` = `f55ecffa87e48e1d1d3ec8d0860986c83a90ff2e` (rev 39's real node hash)
- `00changelog-db83bf64.nd` — real raw trie data, 1088 bytes = 17 blocks of 64 bytes
  (16 × big-endian **4-byte** signed ints per block — the doc comment in `nodemap.py` says
  "signed 64bit integer" but the actual struct format is `S_BLOCK = struct.Struct(">" + "l"*16)`,
  and `struct.calcsize(">l")` is 4 under the `>` standard-size prefix, confirmed both by
  `struct.calcsize` and by this file's real size: `1088 / 16 / 4 == 17` blocks exactly). The root
  block is always the **last** block in the file (`nodemap.py:_walk_trie` yields children before
  their parent), i.e. block index 16 here.
- `revs.txt` — `hg log -r 0:39 --template '{rev} {node}\n'` output (via the same Rust-enabled
  container; the host's Rust-less `hg` cannot open this repo at all) — the full rev→node ground
  truth used to validate the trie.

## Verification performed

1. **Structural**: parsed `00changelog.n` byte-for-byte by hand with `python3 -c "import struct;
   ..."` and cross-checked every field against `mercurial/revlogutils/nodemap.py`'s
   `persisted_data()`/`NodeMapDocket`/`S_VERSION`/`S_HEADER` (this machine's system
   `/usr/lib/python3/dist-packages/mercurial/revlogutils/nodemap.py`, Mercurial 7.2, pure Python —
   readable/importable even though it can't itself *create* this repo format).
2. **Semantic**: fed `00changelog-db83bf64.nd` into the real `mercurial.revlogutils.nodemap`
   module's own `parse_data()` + `_find_node()` (import works fine on the host's Python-only
   Mercurial install — only *repository access* requires Rust, not the standalone nodemap trie
   codec) and resolved all 40 real node hashes from `revs.txt` against it: **40/40 matched** the
   real `hg log` revision numbers.
3. This independent, reference-implementation-based verification is what `NodeMapFileFixtureTest`
   and `NodeMapAcceleratedLookupTest` re-assert in Java against the exact same fixture bytes.
