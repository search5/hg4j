package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Worktree command supporting Git-like 'worktree' and Mercurial-like 'share' mechanism.
 * Creates a new working copy that points to the main repository's shared store directory.
 */
public class WorktreeCommand {
    private final HgRepository repository;
    private File newWorktreeDir;
    private boolean shareBookmarks;
    private boolean relative;
    private boolean update = true;
    private String checkout;

    /**
     * Creates the command.
     * @param repository the repository whose store the new working copy shares; must not be {@code null}
     */
    public WorktreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the directory of the new working copy.
     * @param newWorktreeDir the directory to create; must not exist or be empty. Required before {@link #call()}
     * @return {@code this}
     */
    public WorktreeCommand setNewWorktreeDir(File newWorktreeDir) {
        this.newWorktreeDir = newWorktreeDir;
        return this;
    }

    /**
     * {@code hg share -B}: the new working copy shares the bookmarks of the source too ({@code .hg/shared} lists {@code bookmarks}).
     * @param shareBookmarks {@code true} to share the bookmarks; the default is {@code false}, like the command line
     * @return {@code this}
     */
    public WorktreeCommand setShareBookmarks(boolean shareBookmarks) {
        this.shareBookmarks = shareBookmarks;
        return this;
    }

    /**
     * {@code hg share --relative}: {@code .hg/sharedpath} holds the path of the source relative to the new {@code .hg}, and the
     * {@code relshared} requirement is written next to {@code shared}.
     * @param relative {@code true} for a relative {@code sharedpath}; the default is {@code false}
     * @return {@code this}
     */
    public WorktreeCommand setRelative(boolean relative) {
        this.relative = relative;
        return this;
    }

    /**
     * Whether the new working copy is brought up to date (the opposite of {@code hg share -U}).
     * @param update {@code false} leaves the working copy empty with no dirstate; the default is {@code true}
     * @return {@code this}
     */
    public WorktreeCommand setUpdate(boolean update) {
        this.update = update;
        return this;
    }

    /**
     * The revision checked out first, like the branch of {@code hg share SOURCE#BRANCH}; if it cannot be found, the head of branch
     * {@code default} and then the tip are tried, as the original does.
     * @param checkout a revision, branch or hash prefix; {@code null} (the default) tries {@code default} and {@code tip} only
     * @return {@code this}
     */
    public WorktreeCommand setCheckout(String checkout) {
        this.checkout = checkout;
        return this;
    }

    /**
     * Executes the worktree / share creation.
     * Generates a separate .hg working metadata directory linking to the shared central store.
     *
     * @return the newly instantiated HgRepository representing the worktree
     * @throws IOException if directory creation, linking, or the post-share checkout fails
     * @throws HgLockException if the post-share checkout cannot acquire the shared repository's locks
     */
    public HgRepository call() throws IOException, HgLockException {
        if (newWorktreeDir == null) {
            throw new IllegalStateException("New worktree directory must be specified.");
        }

        if (newWorktreeDir.exists() && newWorktreeDir.list() != null && newWorktreeDir.list().length > 0) {
            throw new IOException("Target worktree directory must be empty or non-existent: " + newWorktreeDir);
        }

        File newHgDir = new File(newWorktreeDir, ".hg");
        newHgDir.mkdirs();
        // `createrepository` of a shared repository makes `wcache` and no `cache` (the cache of the source is used)
        new File(newHgDir, "wcache").mkdirs();

        // 1. Create sharedpath linking back to the central store's .hg directory. Real hg's own
        // share extension (mercurial/share.py) writes os.path.realpath() of the source .hg dir --
        // i.e. with symlinks fully resolved, not just made absolute (verified live against real hg
        // 7.2 on macOS 2026-09-07: a share created under a temp dir whose path traverses the
        // /var -> /private/var symlink comes out with the /private/var form in .hg/sharedpath).
        // getAbsolutePath() alone does NOT resolve symlinks, so it silently diverged from real hg
        // on any host where the repository lives under a symlinked path -- getCanonicalPath() is
        // the Java equivalent of realpath().
        // `createrepository`: the shared repository's own `sharedpath` is used, so a share of a share points at the store itself
        File mainHgDir = repository.getSharedHgDir() != null ? repository.getSharedHgDir() : repository.getHgDir();
        File sharedpathFile = new File(newHgDir, "sharedpath");
        String sharedPath = mainHgDir.getCanonicalPath().replace('\\', '/');
        if (relative) {
            sharedPath = newHgDir.getCanonicalFile().toPath().relativize(mainHgDir.getCanonicalFile().toPath()).toString().replace('\\', '/');
        }
        SafeFileIO.writeStringAtomic(sharedpathFile, sharedPath);
        if (shareBookmarks) {
            // `share.py`: the items shared besides the store, one per line
            SafeFileIO.writeStringAtomic(new File(newHgDir, "shared"), "bookmarks\n");
        }

        // 2. Clone main requires specification to new worktree's requires -- plus the "shared"
        // marker line real hg's own `hg share` (mercurial/share.py) always adds to the new
        // repository's top-level requires alongside whatever it copied (verified live against
        // real hg 7.2: a shared clone's `.hg/requires` reads "share-safe\nshared\n", never just
        // a verbatim copy of the source's "share-safe\n").
        File mainRequires = repository.getHgDir().toPath().resolve("requires").toFile();
        File newRequires = new File(newHgDir, "requires");
        if (mainRequires.exists()) {
            // `writerequires` writes the sorted set: the source's, plus `shared`, and `relshared` for a relative share
            java.util.TreeSet<String> requireLines = new java.util.TreeSet<>();
            for (String line : Files.readAllLines(mainRequires.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    requireLines.add(line.trim());
                }
            }
            // `createrepository`: `relshared` for a relative share, `shared` otherwise (both only when the source had `shared` already)
            requireLines.add(relative ? "relshared" : "shared");
            StringBuilder sb = new StringBuilder();
            for (String line : requireLines) {
                sb.append(line).append('\n');
            }
            Files.writeString(newRequires.toPath(), sb.toString(), StandardCharsets.UTF_8);
        }

        // `post_share`: the source's default path is written to the new .hg/hgrc
        String defaultPath = repository.getConfig().get("paths", "default");
        if (defaultPath != null && !defaultPath.isEmpty()) {
            Files.writeString(new File(newHgDir, "hgrc").toPath(), "[paths]\ndefault = " + defaultPath + "\n", StandardCharsets.UTF_8);
        }

        // 3. Return new repository handle bound to this worktree
        HgRepository worktreeRepo = new HgRepository(newWorktreeDir);

        // Backlog #39: real hg's own `hg share` actually checks out the shared store's tip into
        // the new working directory (verified live: `hg share src dst` prints "updating working
        // directory" and populates dst's files with tip's content) -- hg4j previously left the
        // new worktree with zero files (only a raw 40-byte V1-style dirstate parent-pointer stub),
        // which was a real functional gap: the "worktree" was unusable as an actual working copy
        // until a caller separately, and non-obviously, ran UpdateCommand on the returned handle.
        //
        // Only attempt the checkout once the shared store actually has a revision to check out --
        // an empty store has nothing to share, matching real hg's own harmless "0 files updated"
        // no-op share of an empty repository (verified live).
        //
        // Backlog #39 second fix: no dirstate file is written here at all before the checkout
        // (previously an unconditional 40-byte "p1+p2 node" stub, hardcoding dirstate-v1's raw
        // layout) -- for a dirstate-v2 shared store this stub is not a valid V2 docket at all,
        // and UpdateCommand.call() immediately re-reads the dirstate to resolve the "current
        // parent" for its tree-walk diff, so the very next line after writing that stub threw
        // BufferUnderflowException trying to parse it as V2 (reproduced live against a real
        // Rust-hg-written dirstate-v2 repository, 2026-09-05). HgRepository#getDirstate() already
        // returns a correct, empty Dirstate object when the file simply doesn't exist (the same
        // path a freshly-`hg init`ed repository takes before its first commit) -- letting
        // UpdateCommand create the dirstate from scratch, in whatever format (v1 or v2) the shared
        // store actually uses, is both simpler and correct for every combo.
        // Like real hg's share + update: the new working copy is locked while its dirstate is created and it is brought up to date.
        if (repository.getRequirements().contains("narrowhg-experimental")) {
            // `post_share` for a narrow source: under wlock and lock the new working copy gets the narrowspec of the store
            // (`narrowspec.copytoworkingcopy`, written as `.hg/narrowspec.dirstate`)
            try (io.github.search5.hg4j.lib.HgLock destWlock = worktreeRepo.lockWorkingCopy();
                 io.github.search5.hg4j.lib.HgLock storeLock = worktreeRepo.lockStore()) {
                File storeSpec = new File(worktreeRepo.getStoreDir(), "narrowspec");
                String spec = "";
                if (storeSpec.isFile()) {
                    try {
                        io.github.search5.hg4j.narrow.NarrowSpec.Patterns parsed = io.github.search5.hg4j.narrow.NarrowSpec.parseConfig(
                                Files.readString(storeSpec.toPath(), StandardCharsets.UTF_8), null);
                        spec = io.github.search5.hg4j.narrow.NarrowSpec.format(parsed.includes(), parsed.excludes());
                    } catch (io.github.search5.hg4j.errors.HgValidationException e) {
                        throw new IOException(e.getMessage(), e);
                    }
                }
                SafeFileIO.writeStringAtomic(new File(newHgDir, "narrowspec.dirstate"), spec);
            }
        }

        // `_post_share_update` (nothing with -U): the lookup order is the checkout revision, "default", "tip"; the update takes the
        // working copy lock of the new repository only
        if (!update) {
            return worktreeRepo;
        }
        try (io.github.search5.hg4j.lib.HgLock destWlock = worktreeRepo.lockWorkingCopy()) {
            File clIdx = new File(worktreeRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(worktreeRepo.getStoreDir(), "00changelog.d");
            Revlog changelog = worktreeRepo.getRevlog(clIdx, clDat);
            if (changelog.getRevisionCount() > 0) {
                String[] candidates = checkout == null ? new String[]{"default", "tip"} : new String[]{checkout, "default", "tip"};
                for (int i = 0; i < candidates.length; i++) {
                    try {
                        new UpdateCommand(worktreeRepo).setForce(true).setRevision(candidates[i]).call();
                        break;
                    } catch (io.github.search5.hg4j.errors.HgRevisionNotFoundException e) {
                        if (i == candidates.length - 1) {
                            throw e;
                        }
                    }
                }
            } else {
                // Nothing to check out -- fall back to the same plain 40-byte (p1+p2 node) stub as
                // before, matching real hg's own no-op share of an empty repository and preserving
                // WorktreeCommandTest's existing "defaults dirstate when main has none" expectations.
                File newDirstate = new File(newHgDir, "dirstate");
                byte[] mainDirstateBytes = new File(repository.getHgDir(), "dirstate").exists() ?
                        Files.readAllBytes(new File(repository.getHgDir(), "dirstate").toPath()) : new byte[40];
                byte[] initialDirstate = new byte[40];
                if (mainDirstateBytes.length >= 40) {
                    System.arraycopy(mainDirstateBytes, 0, initialDirstate, 0, 40);
                }
                Files.write(newDirstate.toPath(), initialDirstate);
            }

            return worktreeRepo;
        }
    }
}
