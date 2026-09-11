package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Snippets for the "Working with Remotes" chapter: push, pull, fetch.
 */
final class RemoteOperationExamples {

    private RemoteOperationExamples() {
    }

    void pushChanges() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::push-changes[]
            String result = hg.push()
                    .setDestination("https://example.com/hg/some-project")
                    .call();
            System.out.println(result);
            // end::push-changes[]
        }
    }

    void pullChanges() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::pull-changes[]
            // Pulls new changesets from the remote into the local repository, WITHOUT touching
            // the working copy -- follow with hg.update() to actually check out the new tip.
            List<byte[]> importedNodeIds = hg.pull()
                    .setSource("https://example.com/hg/some-project")
                    .call();
            // end::pull-changes[]
            importedNodeIds.size();
        }
    }

    void fetchChanges() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::fetch-changes[]
            // Like pull(), but also synchronizes bookmarks and phases atomically with the
            // remote. Neither pull() nor fetch() touches the working copy -- both still need a
            // separate hg.update() call to check the new tip out.
            List<byte[]> importedNodeIds = hg.fetch()
                    .setSource("https://example.com/hg/some-project")
                    .call();
            // end::fetch-changes[]
            importedNodeIds.size();
        }
    }

    void listConfiguredPaths() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-paths[]
            // The [paths] aliases (e.g. "default", "default-push") from .hg/hgrc, alphabetically
            // ordered by alias name. Empty if the repository has no [paths] section configured.
            Map<String, String> paths = hg.paths().call();
            String defaultUrl = paths.get("default");
            // end::list-paths[]
            defaultUrl.length();
        }
    }
}
