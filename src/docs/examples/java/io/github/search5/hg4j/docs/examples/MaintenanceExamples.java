package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.RecoverCommand;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.treewalk.SparseConfig;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Snippets for the "Repository Maintenance &amp; Advanced Configuration" chapter of the hg4j
 * manual. Compiled by the {@code docExamples} source set so the manual's example code can never
 * silently drift out of sync with the real API.
 */
final class MaintenanceExamples {

    private MaintenanceExamples() {
    }

    void verifyRepository() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::verify[]
            List<String> errors = hg.verify().call();
            if (errors.isEmpty()) {
                System.out.println("repository is healthy");
            } else {
                errors.forEach(System.out::println);
            }
            // end::verify[]
        }
    }

    void recoverFromInterruptedTransaction() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::recover[]
            // setVerify(true) additionally runs VerifyCommand, but only when a journal was
            // actually found and successfully rolled back.
            RecoverCommand.RecoverResult result = hg.recover().setVerify(true).call();
            if (!result.wasInterrupted()) {
                // Nothing to do -- matches real hg's "no interrupted transaction available".
                System.out.println("nothing to recover");
            } else if (result.isSuccess()) {
                System.out.println("rolled back an interrupted transaction");
                List<String> verifyErrors = result.getVerifyErrors();
                if (verifyErrors != null) {
                    verifyErrors.forEach(System.out::println);
                }
            } else {
                // The journal is left on disk for a future retry.
                System.out.println("rollback did not complete, journal retained");
            }
            // end::recover[]
        }
    }

    void readConfiguration() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::config[]
            // config() merges system hgrc, user hgrc, and .hg/hgrc, in that priority order.
            HgRcConfig cfg = hg.config();
            String username = cfg.getUsername();
            String defaultRemote = cfg.getPath("default");

            // Enumerate every configured remote, in the order they were parsed.
            Map<String, String> paths = cfg.getSection("paths");
            paths.forEach((name, url) -> System.out.println(name + " -> " + url));
            // end::config[]
            username.length();
            if (defaultRemote != null) {
                defaultRemote.length();
            }
        }
    }

    void manageSubrepos() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::subrepo[]
            // Registers a new entry in .hgsub/.hgsubstate, pinned to a specific revision.
            hg.subrepo()
                    .setAction("add")
                    .setSubrepoPath("libs/vendor")
                    .setSubrepoUrl("https://example.com/hg/vendor-lib")
                    .setRevision("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
                    .call();

            // Clones/updates every subrepo listed in .hgsub to its .hgsubstate-pinned revision --
            // safe to call again later to bring existing subrepo checkouts back in line after
            // .hgsubstate changes (e.g. a pull that advanced the pinned revision).
            hg.subrepo().setAction("update").call();
            // end::subrepo[]
        }
    }

    void narrowClone() throws IOException, HgLockException {
        // tag::narrow-clone[]
        // Hg.narrowClone() is static, like Hg.init()/Hg.cloneRepository() -- it creates a new
        // repository, so there is no existing Hg instance to call it on.
        try (Hg hg = Hg.narrowClone()
                .setSource("https://example.com/hg/monorepo")
                .setDirectory(new File("/path/to/narrow-clone"))
                .addIncludePath("services/billing")
                .addExcludePath("services/billing/vendor")
                .call()) {
            HgRepository repository = hg.getRepository();
            // ... the working copy only contains services/billing, minus its vendor/ subtree ...
            repository.getDirectory();
        }
        // end::narrow-clone[]
    }

    void resolveSparseConfig() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::sparse-config[]
            // Resolves .hg/sparse (including any %include-referenced profile files, read from
            // the given changelog revision's manifest) into the effective include/exclude rules.
            SparseConfig sparse = hg.sparseConfig(0);
            System.out.println("includes: " + sparse.includes);
            System.out.println("excludes: " + sparse.excludes);

            // Turns the resolved rules into a reusable PathFilter, e.g. to restrict a
            // walkWorkingDir()/walkTree() traversal to the active sparse profile.
            boolean included = sparse.toPathFilter().accept("services/billing/pom.xml");
            // end::sparse-config[]
            if (included) {
                sparse.profiles.size();
            }
        }
    }
}
