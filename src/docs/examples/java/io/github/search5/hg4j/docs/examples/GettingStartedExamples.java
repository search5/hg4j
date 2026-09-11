package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;

/**
 * Snippets for the "Getting Started" chapter of the hg4j manual. Compiled by the
 * {@code docExamples} source set so the manual's example code can never silently drift out of
 * sync with the real API.
 */
final class GettingStartedExamples {

    private GettingStartedExamples() {
    }

    void initRepository() throws IOException {
        // tag::init-repository[]
        HgRepository repository = Hg.init()
                .setDirectory(new File("/path/to/repo"))
                .call();
        // end::init-repository[]
        repository.getDirectory();
    }

    void cloneRepository() throws IOException, HgLockException {
        // tag::clone-repository[]
        HgRepository repository = Hg.cloneRepository()
                .setSource("https://example.com/hg/some-project")
                .setDirectory(new File("/path/to/local-clone"))
                .call();
        // end::clone-repository[]
        repository.getDirectory();
    }

    void openRepository() throws IOException {
        // tag::open-repository[]
        try (Hg hg = Hg.open("/path/to/repo")) {
            HgRepository repository = hg.getRepository();
            // ... run commands via hg.xxx().call() ...
        }
        // end::open-repository[]
    }

    void wrapExistingRepository() throws IOException {
        HgRepository repository = Hg.init().setDirectory(new File("/path/to/repo")).call();
        // tag::wrap-repository[]
        try (Hg hg = Hg.wrap(repository)) {
            // ... run commands via hg.xxx().call() ...
        }
        // end::wrap-repository[]
    }
}
