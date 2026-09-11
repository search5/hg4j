package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;

import java.io.IOException;

/**
 * Snippets for the "Error Handling" chapter. hg4j's checked-exception hierarchy is deliberate:
 * {@link HgLockException} and {@link HgMergeConflictException} are NOT {@link IOException}
 * subtypes (they extend the library's own {@code HgException}), so a bare {@code catch
 * (IOException e)} will NOT catch either of them -- most other hg4j exceptions (validation,
 * repository-not-found, transport failures) DO extend {@code IOException}.
 */
final class ErrorHandlingExamples {

    private ErrorHandlingExamples() {
    }

    void handleLockContention() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::handle-lock-exception[]
            try {
                hg.commit().setMessage("Automated checkpoint").call();
            } catch (HgLockException e) {
                // Another process (or another Hg instance) currently holds .hg/wlock or
                // .hg/store/lock -- retry after a delay, or surface it to the caller as
                // "repository is locked by another process".
                System.err.println("Repository busy, try again later: " + e.getMessage());
            }
            // end::handle-lock-exception[]
        }
    }

    void handleMergeConflicts() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::handle-merge-conflict[]
            try {
                hg.graft().setSource("42").call();
            } catch (HgMergeConflictException e) {
                // The graft/rebase/backout is paused; the conflicting files already have
                // <<<<<<< / ======= / >>>>>>> markers written to disk. Resolve them by hand
                // (or via ResolveCommand), then call the same command's continue-style method.
                for (String conflictedPath : e.getConflictPaths()) {
                    System.err.println("Unresolved: " + conflictedPath);
                }
            }
            // end::handle-merge-conflict[]
        }
    }
}
