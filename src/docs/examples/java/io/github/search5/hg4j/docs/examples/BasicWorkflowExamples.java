package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.DiffCommand.DiffEntry;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.Status;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Basic Workflow" chapter: add, status, commit, log, diff.
 */
final class BasicWorkflowExamples {

    private BasicWorkflowExamples() {
    }

    void addFiles() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::add-files[]
            hg.add()
                    .addFile("README.md")
                    .addFile("src/main/java/App.java")
                    .call();
            // end::add-files[]
        }
    }

    void checkStatus() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::check-status[]
            Status status = hg.status().call();
            status.getAdded();      // newly tracked, not yet committed
            status.getModified();   // tracked and changed since the last commit
            status.getRemoved();    // tracked but deleted (hg remove/rm)
            status.getUntracked();  // present on disk but not tracked at all
            // end::check-status[]
        }
    }

    void commitChanges() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::commit-changes[]
            byte[] newNodeId = hg.commit()
                    .setMessage("Add initial application skeleton")
                    .setAuthor("Jane Doe <jane@example.com>")
                    .call();
            // end::commit-changes[]
            int ignore = newNodeId.length;
        }
    }

    void viewLog() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::view-log[]
            List<HgCommit> history = hg.log().call();
            for (HgCommit commit : history) {
                System.out.printf("%s %s %s%n",
                        commit.getNodeId().toHex().substring(0, 12),
                        commit.getAuthor(),
                        commit.getMessage());
            }
            // end::view-log[]
        }
    }

    void viewDiff() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::view-diff[]
            // With no revisions set, diffs the tip against its parent.
            List<DiffEntry> changes = hg.diff().call();
            for (DiffEntry entry : changes) {
                System.out.println(entry.getPath() + " (" + entry.getChangeType() + ")");
                System.out.println(entry.getDiffContent());
            }
            // end::view-diff[]
        }
    }
}
