package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.TagsCommand.Tag;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Undoing Things &amp; Tagging" chapter: remove, revert, tag.
 */
final class HousekeepingExamples {

    private HousekeepingExamples() {
    }

    void removeFiles() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::remove-files[]
            // Untracks the file AND deletes it from disk (matching plain `hg remove`).
            hg.remove().setFile("obsolete/OldService.java").call();
            // end::remove-files[]
        }
    }

    void revertFile() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::revert-files[]
            // Restores a file's working-copy content back to the current parent revision. A
            // dirty file gets its pre-revert bytes backed up to "<file>.orig" first.
            hg.revert().setFile("src/main/java/App.java").call();

            // Or revert to a specific historical revision instead of the current parent.
            hg.revert().setFile("src/main/java/App.java").setRevision("42").call();
            // end::revert-files[]
        }
    }

    void createTag() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::tag-a-revision[]
            // Creates a tag pointing at the working copy's current parent and commits the
            // updated .hgtags file (like plain `hg tag`).
            hg.tag().setTagName("v1.0.0").call();

            // A local tag is written to .hg/localtags instead, and is never committed.
            hg.tag().setTagName("wip-checkpoint").setLocal(true).call();
            // end::tag-a-revision[]
        }
    }

    void listTags() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-tags[]
            List<Tag> tags = hg.tags().call();
            for (Tag t : tags) {
                System.out.printf("%-20s %5d:%s%n", t.getName(), t.getRev(), t.isLocal());
            }
            // end::list-tags[]
        }
    }
}
