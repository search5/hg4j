#!/usr/bin/env bash
# Builds a real Mercurial CLI WITH the Rust extensions (`mercurial.pyo3_rustext`) on the host, so the
# interop tests (@Tag("interop")) no longer need Docker. This is the host-side equivalent of
# docker/hg-rust-7.2.4/Dockerfile and follows the same steps for the same reasons (see the comments
# there): a plain/pure-Python hg refuses `exp-revlogv2.2` and `persistent-nodemap` repositories.
#
# Usage:   scripts/build-local-hg.sh [install-dir]
# Default install dir: ${HG4J_HG_HOME:-$HOME/.cache/hg4j/hg-rust-7.2.4}
# Result:  <install-dir>/bin/hg  (wrapper that sets PYTHONPATH and HGMODULEPOLICY=rust+c)
#
# Requirements: uv, a C compiler, make, gettext (msgfmt), cargo/rustc (stable, edition 2024 capable).
# The tests find the CLI through, in order: $HG4J_HG, <install-dir>/bin/hg above.
set -euo pipefail

HG_VERSION="${HG_VERSION:-7.2.4}"
PYTHON_VERSION="${HG_PYTHON_VERSION:-3.11}"
DEST="${1:-${HG4J_HG_HOME:-$HOME/.cache/hg4j/hg-rust-$HG_VERSION}}"

for tool in uv gcc make msgfmt cargo rustc; do
    command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"

if [ ! -d "$DEST/venv" ]; then
    uv venv --seed --python "$PYTHON_VERSION" "$DEST/venv"
fi
PY="$DEST/venv/bin/python"
"$PY" -m pip install --quiet --no-cache-dir docutils "setuptools>=77" "setuptools-scm>=8.1.0"

SRC="$DEST/mercurial-$HG_VERSION"
if [ ! -d "$SRC" ]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    # --no-binary: the real sdist (no PyPI wheel bundles the Rust extension).
    "$PY" -m pip download --quiet --no-binary :all: --no-deps -d "$TMP" "mercurial==$HG_VERSION"
    tar xzf "$TMP/mercurial-$HG_VERSION.tar.gz" -C "$DEST"
fi

cd "$SRC"
# `--rust` is a global setup.py option and must precede the subcommand. Build in place (see the
# Dockerfile for why `pip install`/`setup.py install` are not used).
"$PY" setup.py --rust build_ext --inplace
# The Rust standalone extension is not copied back by build_ext --inplace; copy it manually.
find build/lib.*/mercurial -maxdepth 1 -iname 'pyo3_rustext*.so' -exec cp {} mercurial/ \;

mkdir -p "$DEST/bin"
cat > "$DEST/bin/hg" <<EOF
#!/bin/sh
exec env PYTHONPATH="$SRC" HGMODULEPOLICY=rust+c "$PY" "$SRC/hg" "\$@"
EOF
chmod +x "$DEST/bin/hg"

echo "--- verification"
"$DEST/bin/hg" --version | head -1
"$DEST/bin/hg" debuginstall | grep -i "rust extensions"
echo "installed: $DEST/bin/hg"
