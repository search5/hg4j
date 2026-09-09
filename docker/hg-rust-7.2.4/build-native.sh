#!/usr/bin/env bash
# Builds the same Rust-extension-enabled real Mercurial 7.2.4 CLI as this directory's Dockerfile
# (`mercurial.pyo3_rustext`, needed for `persistent-nodemap`/`fileindex-v1`/general revlog v2
# repositories -- see Dockerfile's header comment), but directly on the host instead of inside a
# container. NativeHgRust (src/test/java/io/github/search5/hg4j/api/NativeHgRust.java), which the
# ~50 RequirementMatrix*DockerRoundTripTest classes use, expects the result at
# `<repo root>/.native/hg-rust-7.2.4/hg` (gitignored, machine-specific) -- run this once per
# machine before running `./gradlew interopTest`. There is deliberately no Docker fallback in the
# test code (2026-09-09 decision): every machine that runs the interop suite is expected to have
# run this script.
#
# Requires: python3 (3.11 recommended -- newer CPython 3.13+ has had issues building this old
# setup.py; use `uv python install 3.11` + `uv venv --python 3.11` if your default python3 is
# newer), rustc/cargo (rustup, stable toolchain), a C compiler (Xcode CLT on macOS / build-essential
# on Linux), curl.
#
# Verify after running: .native/hg-rust-7.2.4/hg debuginstall | grep -i rust
#   -> "checking Rust extensions (installed)"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/.native/hg-rust-7.2.4"
BUILD_DIR="$OUT_DIR/build-src"
VERSION="7.2.4"

if [ -x "$OUT_DIR/hg" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
  echo "이미 빌드되어 있습니다: $OUT_DIR/hg (다시 빌드하려면 FORCE_REBUILD=1로 재실행)"
  exit 0
fi

command -v python3 >/dev/null || { echo "python3가 필요합니다" >&2; exit 1; }
command -v rustc >/dev/null || { echo "rustc가 필요합니다 (rustup으로 설치)" >&2; exit 1; }
command -v cargo >/dev/null || { echo "cargo가 필요합니다 (rustup으로 설치)" >&2; exit 1; }

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

PY=python3
"$PY" -m venv venv
source venv/bin/activate
pip install --no-cache-dir --quiet docutils "setuptools>=77" "setuptools-scm>=8.1.0"

SDIST_URL="$(curl -s "https://pypi.org/pypi/mercurial/$VERSION/json" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print([u['url'] for u in d['urls'] if u['packagetype']=='sdist'][0])")"
curl -sL -o "mercurial-$VERSION.tar.gz" "$SDIST_URL"
tar xzf "mercurial-$VERSION.tar.gz"
cd "mercurial-$VERSION"

python3 setup.py --rust build_ext --inplace

RUST_SO="$(find build/lib.*/mercurial -maxdepth 1 -iname 'pyo3_rustext*.so' | head -1)"
if [ -z "$RUST_SO" ]; then
  echo "Rust 확장(.so)이 빌드 결과에 없습니다 -- 빌드 실패" >&2
  exit 1
fi
cp "$RUST_SO" mercurial/

MERC_SRC_DIR="$(pwd)"
mkdir -p "$OUT_DIR"
cat > "$OUT_DIR/hg" <<EOF
#!/bin/sh
exec env PYTHONPATH="$MERC_SRC_DIR" HGMODULEPOLICY=rust+c "$BUILD_DIR/venv/bin/python3" "$MERC_SRC_DIR/hg" "\$@"
EOF
chmod +x "$OUT_DIR/hg"

echo "빌드 완료: $OUT_DIR/hg"
"$OUT_DIR/hg" debuginstall | grep -i rust
