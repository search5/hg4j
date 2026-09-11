package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.BranchesCommand.BranchHead;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Branches, Bookmarks &amp; Updating" chapter. Mercurial's named branches are a
 * permanent, sticky label recorded in every commit (unlike Git branches, which are just movable
 * pointers) -- bookmarks are hg's closer analogue to a Git branch.
 */
final class BranchingExamples {

    private BranchingExamples() {
    }

    void createNamedBranch() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::create-branch[]
            // Marks the working copy so the NEXT commit records this branch name.
            hg.branch().setBranchName("feature/payments").call();
            // With no argument, reports the working copy's current branch instead.
            String currentBranch = hg.branch().call();
            // end::create-branch[]
            currentBranch.length();
        }
    }

    void manageBookmarks() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::bookmarks[]
            // Creates (or moves) a bookmark to point at the working copy's current parent.
            hg.bookmark().setBookmarkName("release-1.0").call();

            // Lists every bookmark as a name -> hex-node-id map.
            hg.bookmark().call().forEach((name, nodeHex) ->
                    System.out.println(name + " -> " + nodeHex));

            // Deletes a bookmark.
            hg.bookmark().setBookmarkName("release-1.0").setDelete(true).call();
            // end::bookmarks[]
        }
    }

    void updateWorkingCopy() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::update-working-copy[]
            // setRevision() accepts "tip", a revision number, a hex node id (or unambiguous
            // prefix), or a named branch name -- NOT a bookmark name, so a bookmark must be
            // resolved to its node id first.
            hg.update().setRevision("feature/payments").call();

            String bookmarkNodeHex = hg.bookmark().call().get("release-1.0");
            hg.update().setRevision(bookmarkNodeHex).call();
            // end::update-working-copy[]
        }
    }

    void listBranches() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-branches[]
            // One entry per named branch's head -- closed branches are excluded by default.
            List<BranchHead> heads = hg.branches().call();
            for (BranchHead head : heads) {
                System.out.printf("%s %d:%s%n", head.getBranch(), head.getRev(), head.isActive());
            }

            // Include closed branches too.
            List<BranchHead> allHeads = hg.branches().setIncludeClosed(true).call();
            // end::list-branches[]
            allHeads.size();
        }
    }
}
