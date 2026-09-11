package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.AnnotateCommand.BlameLine;
import io.github.search5.hg4j.api.GrepCommand.GrepResult;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.ManifestCommand.ManifestEntry;
import io.github.search5.hg4j.api.TreeCommand.TreeEntry;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Searching & Browsing History" chapter: annotate, cat, files, locate,
 * manifest, tree, grep, revset, phase.
 */
final class HistorySearchExamples {

    private HistorySearchExamples() {
    }

    void annotateFile() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::annotate-file[]
            List<BlameLine> lines = hg.annotate()
                    .setPath("src/main/java/App.java")
                    .call();
            for (BlameLine line : lines) {
                System.out.printf("%4d rev %-4d %-20s %s%n",
                        line.getLineNumber(), line.getRevision(), line.getAuthor(), line.getContent());
            }
            // end::annotate-file[]
        }
    }

    void catFile() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::cat-file[]
            byte[] content = hg.cat()
                    .setFile("README.md")
                    .setRevision("tip")
                    .call();
            System.out.println(new String(content, java.nio.charset.StandardCharsets.UTF_8));
            // end::cat-file[]
        }
    }

    void listFiles() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-files[]
            // With no revision set, this reflects the working copy's dirstate (added-but-
            // uncommitted files included, removed-but-uncommitted files excluded).
            List<String> trackedFiles = hg.files().call();

            // Restrict to files matching a glob pattern.
            List<String> javaFiles = hg.files().setPattern("src/main/java/**/*.java").call();
            // end::list-files[]
            int ignore = trackedFiles.size() + javaFiles.size();
        }
    }

    void locateFiles() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::locate-files[]
            // Unlike files(), locate() searches the dirstate directly and still reports
            // files marked removed (hg rm'd) but not yet committed.
            List<String> matches = hg.locate().setPattern("*.md").call();
            // end::locate-files[]
            int ignore = matches.size();
        }
    }

    void listManifest() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-manifest[]
            // With no revision set, this lists the working directory's first parent.
            List<ManifestEntry> entries = hg.manifest().setRevision("tip").call();
            for (ManifestEntry entry : entries) {
                System.out.println(entry.getPath() + " " + entry.getNodeHex()
                        + (entry.isExecutable() ? " (executable)" : "")
                        + (entry.isSymlink() ? " (symlink)" : ""));
            }
            // end::list-manifest[]
        }
    }

    void walkTree() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::walk-tree[]
            // With no revision set, tree() defaults to the tip.
            List<TreeEntry> entries = hg.tree().call();
            for (TreeEntry entry : entries) {
                System.out.printf("%06o %s %s%n", entry.getMode(), entry.getNodeId(), entry.getPath());
            }
            // end::walk-tree[]
        }
    }

    void grepHistory() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::grep-history[]
            // Searches every historical revision of every tracked filelog, not just the
            // working copy or tip.
            List<GrepResult> matches = hg.grep()
                    .setQuery("TODO")
                    .setCaseInsensitive(true)
                    .call();
            for (GrepResult match : matches) {
                System.out.printf("%s:%d [%s] %s%n",
                        match.path, match.lineNumber, match.hexNode.substring(0, 12), match.lineContent);
            }
            // end::grep-history[]
        }
    }

    void queryRevset() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::query-revset[]
            List<String> allRevisions = hg.revset().setExpression("all()").call();
            List<String> tipParents = hg.revset().setExpression("parents(tip)").call();
            // end::query-revset[]
            int ignore = allRevisions.size() + tipParents.size();
        }
    }

    void queryAndSetPhase() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::query-phase[]
            // 0 = public, 1 = draft, 2 = secret.
            int phase = hg.phase().setRevision("tip").call();
            // end::query-phase[]

            // tag::set-phase[]
            // Moving towards public (a lower number) is unconditional; moving towards
            // secret (a higher number) requires setForce(true), like real hg's --force gate.
            int newPhase = hg.phase().setRevision("tip").setPhase(0).call();
            // end::set-phase[]
            int ignore = phase + newPhase;
        }
    }
}
