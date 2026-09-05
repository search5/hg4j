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

    public WorktreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public WorktreeCommand setNewWorktreeDir(File newWorktreeDir) {
        this.newWorktreeDir = newWorktreeDir;
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

        // 1. Create sharedpath linking back to the central store's .hg directory
        File mainHgDir = repository.getHgDir();
        File sharedpathFile = new File(newHgDir, "sharedpath");
        SafeFileIO.writeStringAtomic(sharedpathFile, mainHgDir.getAbsolutePath().replace('\\', '/'));

        // 2. Clone main requires specification to new worktree's requires -- plus the "shared"
        // marker line real hg's own `hg share` (mercurial/share.py) always adds to the new
        // repository's top-level requires alongside whatever it copied (verified live against
        // real hg 7.2: a shared clone's `.hg/requires` reads "share-safe\nshared\n", never just
        // a verbatim copy of the source's "share-safe\n").
        File mainRequires = new File(mainHgDir, "requires");
        File newRequires = new File(newHgDir, "requires");
        if (mainRequires.exists()) {
            List<String> requireLines = new ArrayList<>(Files.readAllLines(mainRequires.toPath(), StandardCharsets.UTF_8));
            if (!requireLines.contains("shared")) {
                requireLines.add("shared");
            }
            StringBuilder sb = new StringBuilder();
            for (String line : requireLines) {
                sb.append(line).append('\n');
            }
            Files.writeString(newRequires.toPath(), sb.toString(), StandardCharsets.UTF_8);
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
        File clIdx = new File(worktreeRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(worktreeRepo.getStoreDir(), "00changelog.d");
        Revlog changelog = worktreeRepo.getRevlog(clIdx, clDat);
        if (changelog.getRevisionCount() > 0) {
            new UpdateCommand(worktreeRepo).setForce(true).setRevision("tip").call();
        } else {
            // Nothing to check out -- fall back to the same plain 40-byte (p1+p2 node) stub as
            // before, matching real hg's own no-op share of an empty repository and preserving
            // WorktreeCommandTest's existing "defaults dirstate when main has none" expectations.
            File newDirstate = new File(newHgDir, "dirstate");
            byte[] mainDirstateBytes = new File(mainHgDir, "dirstate").exists() ?
                    Files.readAllBytes(new File(mainHgDir, "dirstate").toPath()) : new byte[40];
            byte[] initialDirstate = new byte[40];
            if (mainDirstateBytes.length >= 40) {
                System.arraycopy(mainDirstateBytes, 0, initialDirstate, 0, 40);
            }
            Files.write(newDirstate.toPath(), initialDirstate);
        }

        return worktreeRepo;
    }
}
