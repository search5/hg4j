# Real Mercurial 7.2.4 CLI, built WITH the Rust extensions (`mercurial.pyo3_rustext`), so it can
# actually create/read `exp-revlogv2.2` (general revlog v2, manifest/filelog) and
# `persistent-nodemap` repositories. The plain `hg` on a pure-Python install (and every PyPI
# wheel/apt package checked as of 2026-09-02) refuses both with:
#   abort: accessing `fileindex` repository without associated fast implementation.
#   abort: accessing `persistent-nodemap` repository without associated fast implementation.
# See llm-wiki/decisions/revlog-v2-support-plan.md for the byte-level spec this image was used to
# reverse-engineer, and for why this exists at all.
#
# Build:  docker build -t hg-rust-7.2.4 docker/hg-rust-7.2.4
# Verify: docker run --rm hg-rust-7.2.4 hg debuginstall   # look for "checking Rust extensions (installed)"
# Use:    docker run --rm -v "$PWD":/repo -w /repo hg-rust-7.2.4 hg <command...>

FROM python:3.11-slim-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential curl pkg-config git ca-certificates gettext \
    && rm -rf /var/lib/apt/lists/*

# rustc/cargo via rustup: edition 2024 (Mercurial's rust/hg-pyo3 crate) needs a stable toolchain
# newer than what's in Debian's apt repo circa this image's release.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
ENV PATH="/root/.cargo/bin:${PATH}"

# setuptools/setuptools-scm are needed to invoke setup.py directly (see below); docutils is an
# optional doc-build dependency setup.py checks for.
RUN pip install --no-cache-dir docutils "setuptools>=77" "setuptools-scm>=8.1.0"

WORKDIR /build
# `pip download --no-binary` fetches the real sdist (not a prebuilt wheel -- no PyPI wheel bundles
# the Rust extension) without installing it, so we can drive setup.py ourselves.
RUN pip download --no-binary :all: --no-deps -d . mercurial==7.2.4 \
    && tar xzf mercurial-7.2.4.tar.gz \
    && rm mercurial-7.2.4.tar.gz
WORKDIR /build/mercurial-7.2.4

# `--rust` is a *global* setup.py option (must precede the subcommand) that enables compiling the
# Cargo-based Rust extension alongside the C ones. `pip install`/`setup.py install` both fail on
# this codebase under modern setuptools (a `build_py`/`bdist_egg` incompatibility unrelated to
# Rust -- `TypeError: cannot unpack non-iterable NoneType object` from `copy_file`), so build the
# extensions in place instead and run `hg` straight out of the source tree.
RUN python3 setup.py --rust build_ext --inplace
# `build_ext --inplace`'s normal copy-back step only handles plain C `Extension` objects
# (`self.extensions`) -- `RustStandaloneExtension` is deliberately filtered out of that list right
# before the copy runs (see `hgbuildext.build_extensions()` in setup.py), so its compiled .so lands
# in `build/lib.*/mercurial/` but is never copied into the source `mercurial/` package the way the
# C extensions are. Copy it manually.
RUN find build/lib.*/mercurial -maxdepth 1 -iname 'pyo3_rustext*.so' -exec cp {} mercurial/ \;

# `HGMODULEPOLICY=rust+c` is required at run time too: the module-loading policy defaults to
# `allow` (pure Python, no Rust) unless a `mercurial/__modulepolicy__.py` file is present, which
# only `setup.py install` (not the `build_ext --inplace` path used above) generates. Setting the
# env var directly is simpler than reproducing that file.
RUN printf '#!/bin/sh\nexec env PYTHONPATH=/build/mercurial-7.2.4 HGMODULEPOLICY=rust+c python3 /build/mercurial-7.2.4/hg "$@"\n' > /usr/local/bin/hg \
    && chmod +x /usr/local/bin/hg

WORKDIR /repo
