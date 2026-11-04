`tree-vectors.tsv`: made with the Python implementation of Mercurial 7.2 (`mercurial.shape`, seed 20261104). Columns, tab separated:
includes (`x` + hex per path, comma separated; the `x` keeps an empty path distinct from an empty set), excludes, fingerprint text of `ShardTreeNode.from_patterns(includes, excludes)`, the serialized patterns block (hex) of
its `flat()`, sample paths (hex), and for each sample `1`/`0` whether `node.matcher()` matches it. An empty path is an empty hex field.

`narrowspec-vectors.tsv`: made with `mercurial.narrowspec` of Mercurial 7.2 (script kept with the session, 28 patterns to normalize with their result or Abort message,
7 `format` cases, 9 `parseconfig` documents, 6 `restrictpatterns`, 8 matcher cases over 12 paths). Line types: `N` normalize, `F` format, `P` parseconfig,
`R` restrictpatterns, `X` the 12 paths, `M` matcher bits. Fields are `x` + hex UTF-8; lists are comma separated.
