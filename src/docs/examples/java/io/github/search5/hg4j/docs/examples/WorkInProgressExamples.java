package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Snippets for the "Work in Progress: Shelving &amp; Worktrees" chapter: shelve, unshelve,
 * worktree, resolve.
 */
final class WorkInProgressExamples {

    private WorkInProgressExamples() {
    }

    void shelveChanges() throws IOException, HgLockException, HgMergeConflictException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::shelve-changes[]
            // Saves every modified/added/removed working-copy file under the given name, then
            // reverts the working copy back to its parent commit -- a clean slate to switch tasks
            // on. A plain call() with no setName() shelves under the name "default".
            hg.shelve().setName("wip-feature").call();
            // end::shelve-changes[]
        }
    }

    void unshelveChanges() throws IOException, HgLockException, HgMergeConflictException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::unshelve-changes[]
            try {
                // Restores a previously shelved set of changes as pending, uncommitted edits.
                // Internally this replays the shelve as a throwaway commit and rebases it onto
                // whatever the working copy's parent has become in the meantime (a no-op if
                // nothing has landed there since the shelve was taken); every trace of that
                // throwaway commit is erased once the restore succeeds.
                hg.shelve().setName("wip-feature").setUnshelve(true).call();
            } catch (HgMergeConflictException e) {
                // Paused exactly like `hg rebase` on a real conflict: resolve the reported files
                // (see resolveConflicts() below), then continue or abandon the attempt. A fresh
                // ShelveCommand instance is fine for either call -- both are driven purely by
                // persisted on-disk state, not by anything held in this object.
                hg.shelve().unshelveContinue();
                // Or, to give up and restore the pre-unshelve state while keeping the shelve
                // itself intact for a later attempt:
                // hg.shelve().unshelveAbort();
            }
            // end::unshelve-changes[]
        }
    }

    void createWorktree() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::create-worktree[]
            // Creates a second working copy backed by the SAME shared store as this repository
            // (Mercurial's `hg share`, exposed here under a more Git-familiar name) -- commits
            // made from either working copy are immediately visible to the other, since there is
            // only one store underneath. The target directory must be empty or not yet exist.
            HgRepository worktreeRepo = hg.worktree()
                    .setNewWorktreeDir(new File("/path/to/repo-worktree-hotfix"))
                    .call();
            // end::create-worktree[]
            worktreeRepo.close();
        }
    }

    void resolveConflicts() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::resolve-conflicts[]
            // After a merge()/graft()/rebase()/backout() leaves conflict markers on disk (see
            // the error-handling and merging chapters), list() reports every file's resolution
            // state without changing anything.
            Map<String, Boolean> states = hg.resolve().list(true).call();
            for (Map.Entry<String, Boolean> entry : states.entrySet()) {
                if (!entry.getValue()) {
                    System.out.println("Still unresolved: " + entry.getKey());
                }
            }

            // Once a conflicting file's content has been hand-edited (or overwritten by some
            // other tool) to remove its <<<<<<< / ======= / >>>>>>> markers, mark it resolved so
            // the merge/rebase/graft can be committed or continued.
            hg.resolve().setFile("src/main/java/Config.java").markResolved(true).call();
            // end::resolve-conflicts[]
        }
    }
}
