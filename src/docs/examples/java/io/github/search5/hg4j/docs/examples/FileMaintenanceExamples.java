package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Copying, Renaming &amp; Cleanup" chapter: copy, rename, addremove, forget,
 * purge, gc.
 */
final class FileMaintenanceExamples {

    private FileMaintenanceExamples() {
    }

    void copyFile() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::copy-a-file[]
            // Duplicates a tracked file: the source is left untouched and still tracked, and the
            // destination is added as a new file with copy-source metadata recorded in the
            // dirstate, so `hg log --follow`/`hg annotate` on the destination follows through to
            // the source's own history.
            hg.copy().setSource("src/main/resources/app.properties")
                    .setDestination("src/main/resources/app-staging.properties")
                    .call();

            // Refused by default if the destination already exists on disk; setForce(true)
            // overwrites it, mirroring `hg copy --force`.
            hg.copy().setSource("src/main/resources/app.properties")
                    .setDestination("src/main/resources/app-staging.properties")
                    .setForce(true)
                    .call();
            // end::copy-a-file[]
        }
    }

    void renameFile() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::rename-a-file[]
            // Moves a tracked file on disk, marks the old path removed, and records the new path
            // as a copy-of-the-old-path in the dirstate copyMap -- equivalent to plain `hg rename`
            // (`hg mv`), which is itself just `hg copy` + `hg remove` in one step.
            hg.rename().setSource("src/main/java/App.java")
                    .setTarget("src/main/java/Application.java")
                    .call();
            // end::rename-a-file[]
        }
    }

    void addremoveWorkingCopy() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::addremove-untracked-and-missing[]
            // Scans the working copy in one pass: every untracked file is added, and every
            // tracked file that is missing on disk is marked removed. Returns status-style lines
            // ("A path" / "R path") describing exactly what changed.
            List<String> affected = hg.addremove().call();
            for (String line : affected) {
                System.out.println(line);
            }
            // end::addremove-untracked-and-missing[]
        }
    }

    void forgetFile() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::forget-a-file[]
            // Stops tracking a file WITHOUT touching it on disk -- the opposite of remove(), which
            // both untracks and deletes. A file that was never committed is simply dropped from
            // the dirstate; an already-committed file is instead recorded as removed at the next
            // commit, while the working copy keeps its content untouched.
            hg.forget().setFile("secrets/local-only.env").call();
            // end::forget-a-file[]
        }
    }

    void purgeWorkingCopy() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::purge-untracked-files[]
            // Deletes every untracked, non-ignored file plus the empty directories left behind --
            // matching plain `hg purge`'s own default scope. Ignored files (matched by
            // .hgignore) are NEVER touched, and a declared subrepo's own working copy is treated
            // as an opaque boundary and never walked into.
            hg.purge().call();

            // setPurgeDirectories(false) restricts the sweep to files only, leaving any leftover
            // empty untracked directories in place.
            hg.purge().setPurgeDirectories(false).call();
            // end::purge-untracked-files[]
        }
    }

    void gcRepositoryStore() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::gc-compact-store[]
            // Compacts and re-deltas every revlog in the store, rebuilds fncache, and removes
            // orphaned temp/backup files -- a housekeeping pass with no equivalent single stock
            // `hg` command, closer to `hg debugupgraderepo`/manual store maintenance in spirit.
            String summary = hg.gc().call();
            System.out.println(summary);
            // end::gc-compact-store[]
        }
    }
}
