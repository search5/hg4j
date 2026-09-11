package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.BundleCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Snippets for the "Patches, Bundles & Comparing Remotes" chapter: export, importPatch, bundle,
 * unbundle, clonebundle, incoming, outgoing, archive.
 */
final class PatchesAndBundlesExamples {

    private PatchesAndBundlesExamples() {
    }

    void exportPatch() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::export-patch[]
            // Produces a classic (non---git) unified-diff patch with "# HG changeset patch"
            // headers, matching hg export's default output format -- a plain text FILE you could
            // email or store next to the repo, not a binary changegroup (see bundle() below for
            // that).
            String patchText = hg.export().setRevision("tip").call();
            Files.writeString(Path.of("/tmp/my-change.patch"), patchText);
            // end::export-patch[]
        }
    }

    void importPatch() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::import-patch[]
            // Applies a unified-diff patch (as produced by export()/hg export/hg diff) to the
            // working directory and commits it on top of the CURRENT dirstate parent -- like real
            // "hg import" without --exact, the patch's own "# Node ID"/"# Parent" headers are
            // read for author/date only, never used to pick where the patch is applied.
            String patchText = Files.readString(Path.of("/tmp/my-change.patch"));
            hg.importPatch().setPatchText(patchText).call();
            // end::import-patch[]
        }
    }

    void bundleEverything() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::bundle-all[]
            // Equivalent of "hg bundle --all out.hg": writes every changeset as a binary
            // changegroup FILE -- a different format from export()'s patch text, and the base
            // revision is required (pass the "null" sentinel, matching hg's own --base null/-a,
            // to mean "no known ancestor, bundle everything").
            File outputFile = new File("/tmp/full-repo.hg");
            int changesetCount = hg.bundle()
                    .setOutputFile(outputFile)
                    .setBaseRevision("null")
                    .call();
            System.out.println("Bundled " + changesetCount + " changesets");
            // end::bundle-all[]
        }
    }

    void bundleIncremental() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::bundle-incremental[]
            // Equivalent of "hg bundle --base 0 out.hg": excludes revision 0 and all of its
            // ancestors from the bundle, useful for shipping only the delta to a peer already
            // known to have everything up to that point. NOTE: setBaseRevision()/setRevision()
            // resolve only a revision number, a hex node prefix, or "tip" -- NOT a tag or
            // bookmark name, so a tag has to be resolved to its target revision first.
            File outputFile = new File("/tmp/incremental.hg");
            int changesetCount = hg.bundle()
                    .setOutputFile(outputFile)
                    .setBaseRevision("0")
                    .setType(BundleCommand.BundleType.GZIP_V1)
                    .call();
            System.out.println("Bundled " + changesetCount + " changesets since revision 0");
            // end::bundle-incremental[]
        }
    }

    void unbundleChangesets() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::unbundle-changesets[]
            // Applies a local bundle FILE (from bundle(), or received out of band) to this
            // repository -- decodes the HG10UN/HG10GZ/HG10BZ and bundle2/HG20 container formats
            // automatically.
            List<byte[]> importedNodeIds = hg.unbundle()
                    .setBundleFile(new File("/tmp/full-repo.hg"))
                    .call();
            System.out.println("Applied " + importedNodeIds.size() + " changesets from the bundle");
            // end::unbundle-changesets[]
        }
    }

    void downloadClonebundle() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::download-clonebundle[]
            // NOTE: unlike every other command in this manual, this is Hg.clonebundle(url)
            // called directly -- not a *Command obtained via a no-arg factory method and a
            // separate call(). It downloads the bundle at "url" with a plain HTTP(S) GET and
            // applies it in one step.
            List<byte[]> importedNodeIds = hg.clonebundle("https://example.com/hg/bundles/full.hg");

            // Real hg's own clonebundles mechanism still expects a normal pull afterward, to
            // catch up on anything committed to the origin since the bundle was generated.
            hg.pull().setSource("https://example.com/hg/some-project").call();
            // end::download-clonebundle[]
            importedNodeIds.size();
        }
    }

    void previewIncoming() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::preview-incoming[]
            // Equivalent of "hg incoming": contacts the remote and reports changesets it has
            // that this repository does not, WITHOUT pulling them. Returns pre-formatted display
            // lines (a single "no incoming changes found" line when there is nothing new), not
            // structured changeset objects.
            List<String> incomingLines = hg.incoming()
                    .setSource("https://example.com/hg/some-project")
                    .call();
            incomingLines.forEach(System.out::println);
            // end::preview-incoming[]
        }
    }

    void previewOutgoing() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::preview-outgoing[]
            // Equivalent of "hg outgoing": reports local changesets the remote does not have yet,
            // WITHOUT pushing them.
            List<String> outgoingLines = hg.outgoing()
                    .setDestination("https://example.com/hg/some-project")
                    .call();
            outgoingLines.forEach(System.out::println);
            // end::preview-outgoing[]
        }
    }

    void archiveSnapshot() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::archive-snapshot[]
            // Writes an unversioned snapshot of "tip" (no .hg metadata, just a
            // .hg_archival.txt provenance file) -- the archive type is auto-detected from the
            // destination's extension here (.tar.gz -> gzip-compressed tar).
            hg.archive()
                    .setRevision("tip")
                    .setDestination(new File("/tmp/release-snapshot.tar.gz"))
                    .call();
            // end::archive-snapshot[]
        }
    }
}
