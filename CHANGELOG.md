# Changelog

All notable changes to hg4j are listed here, newest first. The reference for behaviour is Mercurial 7.2 / 7.2.4; "the original" means that implementation. The version in `build.gradle` is `1.1.1`. 1.1.0 is published (Gradle Plugin Portal and Maven Central); 1.1.1 has not been published yet. Items marked **BC** change behaviour that a caller may rely on.

## Unreleased

### Added
- `CloneCommand.setUpdate(false)` (`hg clone -U`): brings in the changesets without creating working files; no `.hg/dirstate` file is written.
- `TagsCommand.setReadFromHeads(true)` and `TagsCommand.setRevision(...)`: read the global tags from the `.hgtags` of every head (or of one changeset) instead of the working copy, as `hg tags` does, so a repository filled by push or pull (no working files) lists its tags. Heads are read oldest first, a later definition wins unless the earlier head's history supersedes it (the original's `_updatetags`), and removed or unknown nodes are left out. The default reading is unchanged. Requested for the server side of Yona.

- `MergeCommitCommand.setSquash(true)`: commits the merged tree as one changeset with `p1` as its only parent (`hg merge`, `hg debugsetparents P1`, `hg commit`); same node as the real hg. Requested for squash merges of pull requests.
- `GraftInMemory.execute()` returns a `GraftResult` (conflicting files, or the new changesets in order; nothing is written for a conflict), also for a dry run, which merges every revision in memory and rolls back. `call()` is unchanged. Requested for rebase merges of pull requests.
- `HgHookAbortException`: a server hook (`prechangegroup`, `pretxnchangegroup`) that throws it refuses the push with a reason; the client sees `error: <hook> hook failed: <reason>` and `abort: <reason>` as with a Python hook raising `error.Abort` (compared byte for byte with the real server). Requested for rejection messages of protected branches.
- `HgHttpWireServer.setBasePath("/owner/project")`: serves the repository under a path, so the LFS endpoints are recognized (and the wire protocol served) there and every request outside it gets 404; the default is the root. Requested for servers that hold several repositories. The transfer links (`href`) of an LFS batch response start with this path too, so a real `hg` client uploads and downloads under it.
- `BundleCommand` resolves what to bundle as `hg bundle` does: `setAll(true)` (`-a`), `setDestination(...)` (`FILE DEST`, a local repository or a `[paths]` name), `setForce(true)` (`-f`), and without a base, `--all` or a destination `paths.default-push`, then `paths.default`; none of them is the original's "default repository not configured!" config error. The bundle bytes are the real hg's (interop test). A base is no longer required. A remote destination is not supported.

### Fixed
- `PullCommand` (and the server side of a push) no longer moves the working directory parent of an empty repository to the pulled tip: like `hg pull` it leaves the dirstate alone, so a repository without working files keeps the null parent and has no `.hg/dirstate` file, and `hg verify` passes on it. It was an hg4j addition that real hg does not have.
- Server `prepushkey` hooks of a bundle2 push (`registerPrePushkeyHook`) now run after the changegroup of the same push was applied, like the original, so they can read the pushed changesets (the repository of the hook context, after `clearRevlogCache()`); a refusing hook undoes the whole push, with the same output as before. A refusing `prepushkey` hook on a `pushkey` part fails a mandatory part with `error:pushkey` and answers an advisory one with `return=0`, as the original does (it was an `error:abort` before).
- Files under a top-level `data/` (or `meta/`) directory could not be read or committed correctly: the store path of a tracked file was taken as already prefixed. `NodeIdUtil.encodeFname` now always adds the store `data/` prefix; `encodeStorePath` is the variant for paths that already have their store directory.
- Merge, squash and `GraftInMemory` wrote a file revision of a file that the lfs extension tracks as plain content. They now write a pointer with the external-storage flag (node = hash of the real content), as the original does for every new file revision; the interop tests compare the changesets with the real `hg` with lfs enabled.
- The `tag()` revset read only the working copy's `.hgtags`, so a repository without working files (filled by push or pull) found no tag, a removed tag was still found, a moved tag matched its old nodes, and `.hg/localtags` was ignored. It now uses the tags `hg tags` lists (the heads' `.hgtags` plus local tags). **BC:** the name is case sensitive as in the original, an uncommitted `.hgtags` is not read (the original ignores it too), and `tag()` without argument and `re:`/`literal:` names work. A name that matches no tag still gives an empty result (the original aborts); this is the same as `bookmark()` here.

## 1.1.1 (2026-11-06)

### Added
- `MergeCommitCommand` (also `Hg.mergeCommit()`): merges two heads and commits the result as one changeset with two parents, without a working copy, under the store lock only. For the same heads, user, date and message it creates the same changeset (same node) as `hg merge` and `hg commit`, with changed-files sidedata in changelog-v2 repositories. A conflict is a returned value (`MergeCommitResult`), not an exception, and nothing is written then. `setAllowAncestorMerge(true)` also merges when one parent is an ancestor of the other (like `git merge --no-ff`), which the original refuses. Copies and renames are followed as `hg merge` follows them (a change to the old name is merged into the new name, and the copy is recorded). Limits: directory renames are not followed (a file added to a renamed directory is reported as a conflict), treemanifest repositories are refused. Requested for the server side of Yona.

### Fixed
- Server `prepushkey` hooks of a bundle2 push (`registerPrePushkeyHook`) now run after the changegroup of the same push was applied, like the original, so they can read the pushed changesets (the repository of the hook context, after `clearRevlogCache()`); a refusing hook undoes the whole push, with the same output as before. A refusing `prepushkey` hook on a `pushkey` part fails a mandatory part with `error:pushkey` and answers an advisory one with `return=0`, as the original does (it was an `error:abort` before).
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
- Server `prepushkey` hooks of a bundle2 push (`registerPrePushkeyHook`) now run after the changegroup of the same push was applied, like the original, so they can read the pushed changesets (the repository of the hook context, after `clearRevlogCache()`); a refusing hook undoes the whole push, with the same output as before. A refusing `prepushkey` hook on a `pushkey` part fails a mandatory part with `error:pushkey` and answers an advisory one with `return=0`, as the original does (it was an `error:abort` before).
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
- Server `prepushkey` hooks of a bundle2 push (`registerPrePushkeyHook`) now run after the changegroup of the same push was applied, like the original, so they can read the pushed changesets (the repository of the hook context, after `clearRevlogCache()`); a refusing hook undoes the whole push, with the same output as before. A refusing `prepushkey` hook on a `pushkey` part fails a mandatory part with `error:pushkey` and answers an advisory one with `return=0`, as the original does (it was an `error:abort` before).
- Server answers corrected against the real `hg`: `listkeys` (`namespaces`, `phases`, `heads`, `branchmap`), `lookup` for bookmarks, tags and branches, `getbundle` with `heads`, stream file order, `unbundle` replies, HTTP session and error texts.
- LFS: pull and clone of LFS repositories, `[paths] default` after clone, unchanged LFS files reported as modified, `verify` reporting every LFS revision as corrupt, downloaded blobs not checked against SHA-256.

## 2026-09

### Added
- Requirement matrix: 60 commands across 36 repository-format combinations, compared with the real `hg` (68 of 68 matrix targets done, including the wire matrix).
- Narrow clone negotiation on the wire (bandwidth reduction), SVN subrepositories, revlog inline-to-split growth, treemanifest dirlogs, pushkey hooks.

### Fixed
- Server `prepushkey` hooks of a bundle2 push (`registerPrePushkeyHook`) now run after the changegroup of the same push was applied, like the original, so they can read the pushed changesets (the repository of the hook context, after `clearRevlogCache()`); a refusing hook undoes the whole push, with the same output as before. A refusing `prepushkey` hook on a `pushkey` part fails a mandatory part with `error:pushkey` and answers an advisory one with `return=0`, as the original does (it was an `error:abort` before).
- `DeltaCodec.decompressZstd` (found four times by independent waves), `RevlogIndex` record visibility, exec/symlink flags in dirstate-v2, symlink `lstat` mismatches in twelve places, `getBundle` returning no bytes when there was nothing to send, `DiffCommand` sentinel clash, credentials embedded in URLs, `GpgSignature` fixed to RSA, `HisteditCommand` losing the branch, stale caches in the wire servers, `.d` files missing from `fncache`, v2-docket undo and journal handling, `CensorCommand` on general-v2 filelogs. Details per class: `llm-wiki/known-bugs-registry.md`.

## 2026-05 to 2026-08

- First implementation: plumbing (`HgRepository`, `Dirstate` v1/v2, `Revlog`, `TreeWalk`, `RevWalk`), the porcelain facade, local, HTTP and SSH transports, journaling and rollback, Myers/LCS delta generation, and the first interop tests against the real `hg`.
