# Changelog

All notable changes to hg4j are listed here, newest first. The reference for behaviour is Mercurial 7.2 / 7.2.4; "the original" means that implementation. The version in `build.gradle` is `1.1.1`. 1.1.0 is published (Gradle Plugin Portal and Maven Central); 1.1.1 has not been published yet. Items marked **BC** change behaviour that a caller may rely on.

## 1.1.1 (2026-11-06)

### Added
- `MergeCommitCommand` (also `Hg.mergeCommit()`): merges two heads and commits the result as one changeset with two parents, without a working copy, under the store lock only. For the same heads, user, date and message it creates the same changeset (same node) as `hg merge` and `hg commit`, with changed-files sidedata in changelog-v2 repositories. A conflict is a returned value (`MergeCommitResult`), not an exception, and nothing is written then. `setAllowAncestorMerge(true)` also merges when one parent is an ancestor of the other (like `git merge --no-ff`), which the original refuses. Copies and renames are followed as `hg merge` follows them (a change to the old name is merged into the new name, and the copy is recorded). Limits: directory renames are not followed (a file added to a renamed directory is reported as a conflict), treemanifest repositories are refused. Requested for the server side of Yona.

### Fixed
- The `changegroup` and `pretxnchangegroup` hooks of the wire servers and of `HgLocalClient.pushWithHooks` ran, and listed the nodes, for a bundle whose changesets the repository already had. The original runs them only when at least one changeset is new. `nodes` now holds the new changesets only, and the push result counts only them.
- `TreeMergeCommand` let a deletion win silently when one side deleted a file and the other changed it, dropping the change. It reports a conflict now, as `hg` does, so the in-memory `graft` (`graft --to`) and `rebase --dry-run` / `--confirm` stop with a conflict instead of losing the edit. The `graft --to` hint is worded as the original words it: `(in-memory merge does not support file conflicts)` for a deletion against a change, `(in-memory merge does not support external merge tools)` otherwise (it used to start with `hint: `).
- `RemoveCommand` refused a clean file as "modified" after an `update` to an older revision of it, because it compared the file with the newest revision in the filelog instead of the one the working copy parent has.

## 1.1.0 (2026-11-05)

### Changed (BC)
- **Locking follows the original.** A command takes `wlock`, then the store `lock`, for the whole command and releases them in reverse order. Locks wait up to `[ui] timeout` (600 s by default) for another holder.
- **Taking the store lock no longer recovers an interrupted transaction.** It throws `HgAbandonedTransactionException`; run `RecoverCommand`. `recover` and `rollback` never touch the dirstate.
- **Dirstate is written once, after the transaction**, inside `changing_files` / `changing_parents` contexts that need the working copy lock. A failed command writes nothing. `dirstate.backup` and the `dirstate` journal entry are gone (old journals that still carry the entry are read without error). Direct changes to the dirstate without `wlock` throw `IllegalStateException`.
- `DirstateBody` (the body of a dirstate context) may throw `IOException` and `HgLockException`.
- Methods that now declare the checked `HgLockException`: `BisectCommand.next()`, `BookmarkCommand.call()`, `BranchCommand.call()`, `CensorCommand.call()`, `GcCommand.call()`, `RecoverCommand.call()`, `ResolveCommand.call()`, `RollbackCommand.call()`, `VerifyCommand.call()`, `Hg.rollback()`. The migration table is in the manual ("API Changes for Callers").
- Behaviour taken from the original after checking it with the real `hg`:
  - `.orig` files remain after `rebase --abort`, `unshelve --abort` and a conflicting `backout`;
  - `rebase` without evolution strips the original changesets;
  - reusing a branch name that exists on another line of history needs `-f`;
  - `phase` refuses to move to a higher phase with exit status 1 (`cannot move N changesets to a higher phase, use --force`) instead of throwing;
  - `sign` commits `.hgsigs` only, does not restore the file when the commit fails, and refuses when `.hgsigs` is changed or untracked;
  - `unshelve` leaves no restore commit in the store.

### Added
- dirstate-v2 stored like the original: memory-mapped read, lookup in place, append-only writes, docket replaced last, rewrite when half of the data file is unreachable.
- Lock contention tests between hg4j and the real `hg` (`RealHgLockContentionInteropTest`), each scenario with the locks on (no damage) and with hg4j's locks switched off (damage shows); `LockScopeConcurrencyTest` for the scope of the locks. `HgLock.disabledForTests` is the test hook.
- `HgSshWireServer.rejectConnection(in, out, reason)`: refuses an SSH connection without a repository after answering the `hello` and `between` handshake, so a real hg client fails fast instead of waiting for ever (taken from the remote repository's `952cde9` and adapted: the repository-less `Wire1Commands.between(Map)` was added, and the read/write helpers became static). Regression tests for it, for the SSH clone of an empty repository, for pushing only a merge commit (`9647575`; the bug itself was already fixed here by `ChangegroupParser`'s `impliedDeltaBase`) and a JIT warm-up with a wider limit in `PerformanceBenchmarkTest` (`72fc316`).
- `-PignoreTestFailures` for a full run that still produces the coverage report.
- Documentation: refreshed manuals (English and Korean) with a command reference, this file, and English Javadoc for about 750 public members that had none.

### Fixed
- Rebase in a changelog-v2 + sidedata repository left changesets without changed-file sidedata (`hg verify` integrity error). Re-applied changesets and in-memory commits now get it.
- Sidedata recomputation overwrote the copy information of a changegroup 05 bundle; it now runs only for changesets that arrived without sidedata.
- `strip` in an LFS repository lost the LFS flag of the kept changesets (hash mismatch on re-apply); it now keeps them in a changegroup 03 bundle.
- `unshelve` left the rebased restore commit behind; `unshelve --abort` did not remove `.hg/rebasestate` and the merge state.
- A rejected push could undo a push that arrived at the same time; concurrent `tag` commands could lose a tag. Both were scope problems of the locks.
- Interop tests that pinned behaviour the original does not have were corrected after comparison with the real `hg`.

### Known issues
- `TransportE2EMatrixInteropTest` (real client, real server, "a push that creates a head is refused") fails now and then in full runs and passes alone; cause unknown, no hg4j code involved as far as is known.
- Not implemented: `upgrade` (planned for the next major version with a CLI), wire protocol v2, `largefiles`, `dirstate-tracked-hint`.
- Copy information is not derived for changesets that arrive in a changegroup without sidedata (versions 01 to 04) in a repository that keeps changed-file sidedata.
- Only Mercurial 7.2 / 7.2.4 is tested. Older releases are expected to read repositories created with default settings but this is not verified.

## 2026-11-04 and earlier in November

### Added
- Large File Storage in phases: pointers, `lfs.track` and `.hglfs`, `size()` and `lfs()` filesets, reading (`cat`, `grep`, `diff`, `archive`, `status`), `verify`, bulk download, clone and pull of LFS repositories, push with blob upload, an LFS server (Batch API), transfer robustness (messages, hints, retries, parallel transfer), progress reporting, `[web]` access rules.
- Pattern matcher (`match` package), fileset language and 24 predicates, and the `-I` / `-X` / `set:` selection in the read and change commands.
- Bookmark push (`-B`), `outgoing` set computation, secret changesets hidden and refused like 7.2.4.
- Wire protocol server: `heads_buckets`, `store_shape`, `stream_out`, `clonebundles_manifest`, `get_cached_bundle_inline`, `between`, `branches`, `debugwireargs`, `protocaps`, pushkey namespaces, capabilities as 7.2.4 advertises them; bundle2 push over SSH; `[auth]` credentials sent after a 401 challenge.
- Signing with `.hgsigs` (`sign`, `sigs`, `sigcheck`); ssh-agent authentication; SSH moved from JSch to Apache MINA SSHD with `~/.ssh/config`.
- Sharded stream bundles and clone bundles on the client side.

### Changed
- Wire protocol v2 removed; shared helpers moved to `RepoSupport`.
- Interop oracle is a local build of Mercurial (with the Rust extensions) instead of a Docker image.
- Signing at commit time (`gpgsig`) was removed; `.hgsigs` is the only signing method, as in Mercurial.

### Fixed
- Server answers corrected against the real `hg`: `listkeys` (`namespaces`, `phases`, `heads`, `branchmap`), `lookup` for bookmarks, tags and branches, `getbundle` with `heads`, stream file order, `unbundle` replies, HTTP session and error texts.
- LFS: pull and clone of LFS repositories, `[paths] default` after clone, unchanged LFS files reported as modified, `verify` reporting every LFS revision as corrupt, downloaded blobs not checked against SHA-256.

## 2026-09

### Added
- Requirement matrix: 60 commands across 36 repository-format combinations, compared with the real `hg` (68 of 68 matrix targets done, including the wire matrix).
- Narrow clone negotiation on the wire (bandwidth reduction), SVN subrepositories, revlog inline-to-split growth, treemanifest dirlogs, pushkey hooks.

### Fixed
- `DeltaCodec.decompressZstd` (found four times by independent waves), `RevlogIndex` record visibility, exec/symlink flags in dirstate-v2, symlink `lstat` mismatches in twelve places, `getBundle` returning no bytes when there was nothing to send, `DiffCommand` sentinel clash, credentials embedded in URLs, `GpgSignature` fixed to RSA, `HisteditCommand` losing the branch, stale caches in the wire servers, `.d` files missing from `fncache`, v2-docket undo and journal handling, `CensorCommand` on general-v2 filelogs. Details per class: `llm-wiki/known-bugs-registry.md`.

## 2026-05 to 2026-08

- First implementation: plumbing (`HgRepository`, `Dirstate` v1/v2, `Revlog`, `TreeWalk`, `RevWalk`), the porcelain facade, local, HTTP and SSH transports, journaling and rollback, Myers/LCS delta generation, and the first interop tests against the real `hg`.
