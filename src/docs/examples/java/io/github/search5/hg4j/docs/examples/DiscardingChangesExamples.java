package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.ManifestCommand;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Discarding Changes: Strip, Rollback &amp; Censor" chapter. Every command
 * here is permanently destructive (or recoverable only in a very narrow window) -- use with
 * caution.
 */
final class DiscardingChangesExamples {

    private DiscardingChangesExamples() {
    }

    void stripRevision() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::strip-revision[]
            // Physically truncates revision 23 and every descendant out of the changelog/
            // manifest/filelog revlogs. Unlike rebase()/histedit() (which only ever record an
            // obsolescence marker, leaving the originals visible via `hg log --hidden`), a
            // stripped revision is gone from the store entirely and cannot be recovered through
            // this library. A successful strip also invalidates any pending rollback() -- see
            // below.
            hg.strip().setRevision("23").call();
            // end::strip-revision[]
        }
    }

    void rollbackLastTransaction() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::rollback-last-transaction[]
            // A plain method, NOT a builder -- there is no rollback().call(). Undoes only the
            // MOST RECENT commit or pull, using the undo.* files that transaction left behind;
            // a second commit (or a strip()) overwrites/erases that undo information, after
            // which rollback() throws IllegalStateException ("no rollback information
            // available") instead of reaching further back.
            hg.rollback();
            // end::rollback-last-transaction[]
        }
    }

    void censorASecretAccidentallyCommitted() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::censor-file-revision[]
            // censor() targets one exact FILE revision, identified by its own filelog node hex
            // -- not the changeset's commit hash. Resolve it via manifest() at the offending
            // changeset first.
            List<ManifestCommand.ManifestEntry> entries = hg.manifest().setRevision("55").call();
            String fileNodeHex = entries.stream()
                    .filter(e -> e.getPath().equals("config/secrets.properties"))
                    .findFirst()
                    .orElseThrow()
                    .getNodeHex();

            // Scrubs just that one revision's content (replacing it with a tombstone) while
            // keeping its node identity, parents, and linkrev intact -- history shape is
            // unaffected, and other revisions of the same file are untouched. Refuses (unless
            // setCheckHeads(false)) if this exact content is still live at a head or a
            // working-directory parent.
            hg.censor()
                    .setFile("config/secrets.properties")
                    .setRevision(fileNodeHex)
                    .setTombstone("removed leaked credentials")
                    .call();
            // end::censor-file-revision[]
        }
    }
}
