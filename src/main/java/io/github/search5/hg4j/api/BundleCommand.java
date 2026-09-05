package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Porcelain command corresponding to {@code hg bundle} -- writes the same changegroup bytes
 * {@link PushCommand} sends over the wire to a local FILE instead, without any network dispatch.
 * Verified against real {@code hg} CLI (v7.2, 2026-09-02 and 2026-09-05) on scratch repos:
 *
 * <ul>
 *   <li>{@code hg bundle out.hg} with no destination and no -a/--base and no
 *       {@code paths.default-push}/{@code paths.default} configured aborts with
 *       "config error: default repository not configured!" -- it never silently defaults to
 *       "bundle everything". Reproduced with {@code hg}, {@code hg -r <rev>}, and plain
 *       {@code hg bundle out.hg}: all three require either an explicit base (or {@code -a}/
 *       {@code --base null}, which {@code hg help bundle} documents as equivalent) or a
 *       resolvable destination. {@link #call()} mirrors only the base/all half of that contract
 *       (see class-level note below on the destination-inference half being out of scope).</li>
 *   <li>{@code hg bundle --all --type none-v1 out.hg} produces a bare "HG10UN" header followed by
 *       the exact same 4-byte-length-prefixed cg1 chunk stream {@link PushCommand} already builds
 *       (confirmed byte-for-byte via {@code xxd}: {@code 4847 3130 554e 0000 00ae ...}), and real
 *       {@code hg unbundle} on a fresh repo reads it back faithfully (round-tripped: 3 changesets
 *       in, 3 changesets, same hashes, out).</li>
 *   <li>{@code hg bundle --base <rev> out.hg} excludes {@code <rev>} and all of its ancestors from
 *       the bundle and includes every one of its *descendants* (verified on a branchy repo: with
 *       heads at revs 1 and 2 both descending from rev 0, {@code --base 0} bundled 1 and 2 both --
 *       not just the linear tip). {@code hg bundle -r <rev> --base null} includes {@code <rev>}
 *       and precisely its ancestors, correctly excluding an unrelated sibling branch.</li>
 * </ul>
 *
 * <p>The revision-set math ({@code missing = ancestors(targets) - ancestors(base)}) matches
 * {@code mercurial/cmd_impls/bundle.py}'s {@code discovery.outgoing(repo, common, heads)} call
 * read directly from the installed Mercurial 7.2 source. The per-entry delta-base choice
 * ({@code CG_DELTAMODE_PREV} in {@code mercurial/revlog.py}'s {@code _emit_revisions}) deltas each
 * packed entry against whichever entry was packed immediately before it in the (revlog-rev-order
 * sorted) selected list, seeding the very first packed entry's delta base with that entry's own
 * parent-1 content (or an empty fulltext base if it has none) -- this generalizes {@link
 * PushCommand}'s existing "delta against the immediately preceding packed entry, or the shared
 * common ancestor for the first one" cg1 rule (see {@code PushCommand.call()}'s own comments on
 * {@code forcedeltaparentprev}) from a contiguous push range to an arbitrary ancestor-closure
 * selection.</p>
 *
 * <p><b>Backlog #39 (2026-09-05) treemanifest/sidedata investigation:</b> extending hg4j's
 * requirement matrix to this command (following the exact same real-hg-CLI-verification process
 * that found and fixed {@link PushCommand}'s equivalent bug -- see its own {@code call()} comments
 * on cg-version negotiation) turned up that {@code BundleCommand} had the SAME root bug as
 * pre-fix {@code PushCommand}: it only ever wrote bare cg1 bytes via now-removed hand-rolled
 * {@code writeEntryChunk}/{@code writePathChunk}/{@code writeTerminalChunk} helpers, which
 * structurally cannot carry a treemanifest subdirectory group at all -- so bundling any
 * treemanifest repo silently produced a bundle missing every subdirectory's file changes. Fixed
 * by switching to {@link ChangegroupParser#writeBundle} at a negotiated version and, for cg3,
 * packing every treemanifest dirlog the same way {@link PushCommand} does (see {@link
 * #findTreemanifestDirs} / {@link #packRevlogForSelectedRevs}). Verified via real {@code hg bundle
 * --type none-v3}/{@code hg unbundle}/{@code hg verify} on a treemanifest scratch repo.
 *
 * <p>Sidedata (cg5, {@code format.exp-use-copies-side-data-changeset=yes}) is deliberately NOT
 * negotiated here, unlike {@link PushCommand} (which does carry it over the wire) -- this is a
 * real {@code hg} limitation, not an hg4j gap, confirmed three independent ways against real
 * Mercurial 7.2.2 on 2026-09-05:
 * <ol>
 *   <li>{@code hg bundle}'s own CLI ({@code mercurial/cmd_impls/bundle.py}) hardcodes {@code
 *       if cgversion == b'01': ... elif cgversion in (b'02', b'03', b'04'): ... else: raise
 *       error.ProgrammingError(b'bundle: unexpected changegroup version %s' % cgversion)} --
 *       there is no {@code --type} spelling (no {@code v4}/{@code v5} bundlespec exists) that can
 *       ever make real {@code hg bundle} emit a cg5 changegroup, on ANY repo format, regardless of
 *       whether that repo's {@code changegroup.supportedoutgoingversions()} would otherwise allow
 *       it. Reproduced directly: {@code hg bundle --type "none-v2;cg.version=05" out.hg} on a
 *       sidedata repo raises exactly that {@code ProgrammingError}.</li>
 *   <li>Even setting that CLI restriction aside, a hand-built real-hg cg5 bundle2 FILE (via the
 *       Python {@code mercurial.changegroup}/{@code bundle2} APIs directly, bypassing the CLI
 *       guard) applied with real {@code hg unbundle} into a matching sidedata destination produces
 *       {@code hg verify} integrity errors ({@code "in manifest but not in changeset"}, {@code
 *       "rev 0 points to unexpected changeset"}).</li>
 *   <li>Critically, this is NOT specific to cg5 or to hand-built bundles: a plain real-hg-CREATED
 *       cg1 {@code none-v1} bundle FILE, applied via real {@code hg unbundle} into a real-hg
 *       {@code exp-use-copies-side-data-changeset=yes} destination, produces the IDENTICAL
 *       integrity errors -- a pure real-hg-to-real-hg control with hg4j nowhere in the loop. The
 *       same format WITHOUT the sidedata flag (plain {@code exp-use-changelog-v2} only) round-trips
 *       through a file-based bundle/unbundle cleanly. Live peer-to-peer exchange (plain {@code hg
 *       push}/{@code hg pull} between two such repos, no bundle FILE involved) is unaffected and
 *       stays clean -- which is exactly why {@link PushCommand} could safely add cg5 support while
 *       this class cannot: {@code hg bundle}/{@code hg unbundle}'s FILE-based path is where real
 *       hg 7.2's {@code exp-copies-sidedata-changeset} implementation itself breaks down, a
 *       pre-existing real-hg limitation for this explicitly experimental ({@code
 *       enable-unstable-format-and-corrupt-my-data}) format that hg4j cannot "fix" without
 *       diverging from real hg's own (broken) bytes.
 * </ol>
 * The requirement-matrix tests for this command therefore still exercise every {@code cl2+sidedata}
 * combo (rather than skipping it) but treat a resulting real-{@code hg verify} integrity error on
 * that specific combo as an expected, control-confirmed real-hg limitation rather than an hg4j
 * regression.
 *
 * <p><b>Scope note:</b> unlike real {@code hg bundle}, this command does not fall back to
 * resolving an implicit base by opening a connection to {@code paths.default-push}/{@code
 * paths.default} and running remote-heads discovery the way {@link PushCommand} does -- that
 * would duplicate {@code PushCommand}'s transport-facing logic for a local-file-writing command
 * and is left as a follow-up. {@link #setBaseRevision(String)} must always be called explicitly;
 * pass {@code "null"} (matching {@code hg}'s own {@code --base null} spelling for "no known
 * ancestor", i.e. bundle everything) when there is no real incremental base.</p>
 */
public class BundleCommand {

    /**
     * The bundle container/compression format to write, matching real {@code hg bundle}'s
     * {@code --type} values. Verified byte-for-byte against real {@code hg} 7.2.2 output
     * ({@code hg bundle --all --type <x> out.hg}, inspected with {@code xxd}):
     *
     * <ul>
     *   <li>{@link #NONE_V1} ({@code none-v1}) -- 6-byte ASCII {@code "HG10UN"} header followed by
     *       the raw (uncompressed) cg1 changegroup bytes.</li>
     *   <li>{@link #GZIP_V1} ({@code gzip-v1}) -- 6-byte ASCII {@code "HG10GZ"} header followed by
     *       the cg1 bytes compressed with plain zlib/DEFLATE ({@code zlib.compressobj()} in
     *       {@code mercurial/utils/compression.py}'s {@code _zlibengine.compressstream} -- a raw
     *       zlib stream with its 2-byte header, e.g. {@code 78 9c}, and trailing Adler-32, NOT the
     *       gzip container format {@code java.util.zip.GZIPOutputStream} would produce). This is
     *       exactly what {@link UnbundleCommand} already decodes an {@code "HG10GZ"} bundle with
     *       via plain {@code java.util.zip.InflaterInputStream} (zlib-format, not gzip-format), so
     *       writing must use {@code java.util.zip.Deflater}/{@code DeflaterOutputStream} in their
     *       default (wrapped/zlib) mode to stay symmetric with that existing read path.</li>
     *   <li>{@link #BZIP2_V1} ({@code bzip2-v1}) -- real {@code hg}'s {@code bundletypes["HG10BZ"]}
     *       entry in {@code mercurial/bundle2.py} writes only a 4-byte {@code "HG10"} header, then
     *       lets the bzip2 stream's own leading {@code "BZh9"} magic supply the rest -- so the file
     *       reads as {@code "HG10" + "BZh9..." = "HG10BZh9..."}, i.e. the on-disk 6-byte prefix
     *       {@code "HG10BZ"} is never written as a literal string, it falls out of concatenating a
     *       4-byte literal with a standard bzip2 stream. Confirmed against real {@code hg}'s output
     *       ({@code 4847 3130 425a 6839 31...} = {@code "HG10BZh91"}) and against {@link
     *       UnbundleCommand}'s existing read path, which reads a 6-byte {@code "HG10BZ"} header and
     *       reconstructs the full bzip2 stream by prepending the literal bytes {@code "BZ"} back
     *       onto everything after byte 6 -- i.e. it already assumes exactly this layout.</li>
     *   <li>{@link #NONE_V3}/{@link #GZIP_V3}/{@link #BZIP2_V3} ({@code none-v3}/{@code gzip-v3}/
     *       {@code bzip2-v3}, backlog #39, 2026-09-05) -- the ONLY {@code --type} family real
     *       {@code hg bundle} can use on a treemanifest repository at all (verified: {@code hg
     *       bundle --all --type none-v1 out.hg} on a treemanifest repo aborts with "repository does
     *       not support bundle version 01", and even the CLI's own DEFAULT type, plain
     *       {@code bzip2} with no {@code -v1}/{@code -v3} suffix, aborts the same way with
     *       "...bundle version 02" -- a treemanifest repo's {@code
     *       changegroup.supportedoutgoingversions()} is exactly {@code {03, 04}}). Produces a
     *       {@code "HG20"} bundle2 envelope wrapping a cg3 changegroup (tree-capable envelope: a
     *       root manifest group plus zero or more per-directory subgroups), with the compression
     *       engine applied at the bundle2 STREAM level rather than to the whole file (see {@link
     *       Bundle2Parser#wrapChangegroupInBundle2(byte[], String, String)}'s javadoc for the
     *       byte-for-byte verification against real {@code hg bundle --all --type bzip2-v3}).
     *       Also fully usable (and real-hg-verified clean via {@code hg unbundle}/{@code hg
     *       verify}) on a perfectly ordinary flat-manifest repo -- cg3's envelope is a strict
     *       superset of cg1's, real {@code hg} itself accepts {@code --type none-v3} unconditionally
     *       on any repo format.</li>
     * </ul>
     */
    public enum BundleType {
        NONE_V1("none-v1"),
        GZIP_V1("gzip-v1"),
        BZIP2_V1("bzip2-v1"),
        NONE_V3("none-v3"),
        GZIP_V3("gzip-v3"),
        BZIP2_V3("bzip2-v3");

        private final String cliName;

        BundleType(String cliName) {
            this.cliName = cliName;
        }

        /** The exact spelling real {@code hg bundle --type <name>} uses for this format. */
        public String cliName() {
            return cliName;
        }

        /** Case-insensitive lookup by {@link #cliName()}, e.g. {@code "gzip-v1"} or {@code "GZIP-V1"}. */
        public static BundleType fromCliName(String name) {
            for (BundleType t : values()) {
                if (t.cliName.equalsIgnoreCase(name)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Unsupported bundle --type: " + name
                    + " (supported: none-v1, gzip-v1, bzip2-v1, none-v3, gzip-v3, bzip2-v3)");
        }

        /** True for the {@code -v3} family -- the tree-capable (cg3-in-bundle2) formats. */
        boolean isV3() {
            return this == NONE_V3 || this == GZIP_V3 || this == BZIP2_V3;
        }

        /** The bundle2 STREAM compression token ({@link Bundle2Parser}'s {@code "GZ"}/{@code "BZ"}),
         * or {@code null} for no compression -- only meaningful for the {@code -v3} family. */
        String bundle2Compression() {
            switch (this) {
                case GZIP_V3: return "GZ";
                case BZIP2_V3: return "BZ";
                default: return null;
            }
        }
    }

    private final HgRepository repository;
    private File outputFile;
    private String revision;
    private String baseRevision;
    private BundleType type = BundleType.NONE_V1;

    public BundleCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The {@code .hg} file the changegroup bytes are written to. Required. */
    public BundleCommand setOutputFile(File outputFile) {
        this.outputFile = outputFile;
        return this;
    }

    /**
     * The container/compression format to write -- {@code hg bundle --type <x>}'s equivalent.
     * Defaults to {@link BundleType#NONE_V1} ({@code none-v1}, uncompressed {@code "HG10UN"}),
     * matching this class's original single-format behavior. Use one of the {@code -v3} values
     * for a treemanifest repository (see {@link BundleType}'s javadoc) -- {@link #call()} mirrors
     * real {@code hg bundle}'s own abort when a {@code -v1} type is requested against a
     * treemanifest repository instead of silently upgrading the format underneath the caller.
     */
    public BundleCommand setType(BundleType type) {
        this.type = (type != null) ? type : BundleType.NONE_V1;
        return this;
    }

    /** Convenience overload accepting real {@code hg}'s own {@code --type} spelling, e.g.
     * {@code "gzip-v1"} (case-insensitive). See {@link BundleType#fromCliName(String)}. */
    public BundleCommand setType(String type) {
        return setType(BundleType.fromCliName(type));
    }

    /**
     * Equivalent of {@code hg bundle -r <revision>}: restricts the bundle to {@code revision} and
     * its ancestors (further narrowed by {@link #setBaseRevision(String)}). When left unset, every
     * revision in the repository is a candidate target, matching {@code hg}'s "no -r" default of
     * bundling from every head.
     */
    public BundleCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Equivalent of {@code hg bundle --base <revision>}: {@code revision} and all of its ancestors
     * are assumed already present at the destination and excluded from the bundle. Pass {@code
     * "null"} (case-insensitive, matching real {@code hg}'s own sentinel) to bundle every reachable
     * changeset, the same as {@code hg bundle -a}/{@code --all}. Required -- {@link #call()} throws
     * {@link IllegalStateException} if this is never set, matching real {@code hg bundle}'s own
     * refusal to guess a base without an explicit {@code --base}/{@code -a} or a configured/given
     * destination (see the class-level scope note on why destination inference isn't implemented).
     */
    public BundleCommand setBaseRevision(String baseRevision) {
        this.baseRevision = baseRevision;
        return this;
    }

    /**
     * Writes the bundle file and returns the number of changesets it contains. When the computed
     * changeset set is empty, no file is written at all (matching real {@code hg bundle}'s "no
     * changes found" / exit-1 behavior, verified: {@code hg bundle --base tip out.hg} on a repo
     * whose tip is already the base prints "no changes found" and leaves no output file behind)
     * and this method simply returns {@code 0}.
     */
    public int call() throws IOException, HgLockException {
        if (outputFile == null) {
            throw new IllegalStateException("Output file must be specified for bundle creation.");
        }
        if (baseRevision == null) {
            throw new IllegalStateException(
                    "A base revision must be specified via setBaseRevision(...) -- pass \"null\" to "
                            + "bundle every changeset (matching real hg's -a/--all or --base null); "
                            + "hg4j's BundleCommand does not resolve an implicit destination the way "
                            + "real 'hg bundle' falls back to paths.default-push/paths.default.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        try (HgLock storeLock = repository.lockStore()) {
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int count = changelog.getRevisionCount();
            if (count == 0) {
                return 0;
            }

            // Backlog #39 (2026-09-05): negotiate a changegroup version from what THIS bundle's
            // repository format needs vs. what the requested BundleType can actually carry,
            // mirroring real hg's own hard split between the "-v1" (cg1-only) and "-v3" (cg3,
            // tree-capable) bundlespec families -- see BundleType's javadoc for the real-hg
            // evidence. Unlike PushCommand, cg5/sidedata is never negotiated here (see this
            // class's own javadoc for why: real hg's "hg bundle" CLI cannot produce a cg5 FILE at
            // all, and even a cg1 FILE round-trip is independently broken by a real-hg bug for
            // that specific format regardless of cg version).
            boolean treemanifest = repository.isTreemanifest();
            if (treemanifest && !type.isV3()) {
                throw new IllegalStateException("abort: repository does not support bundle version 01"
                        + " (this is a treemanifest repository -- use one of BundleType.NONE_V3/"
                        + "GZIP_V3/BZIP2_V3, matching real hg's own 'hg bundle --type none-v3' etc.)");
            }
            String version = type.isV3() ? "03" : "01";
            boolean treeCapable = "03".equals(version);

            Set<Integer> baseAncestors = resolveAncestorClosure(changelog, baseRevision);

            Set<Integer> targetAncestors;
            if (revision == null || revision.isEmpty()) {
                targetAncestors = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    targetAncestors.add(i);
                }
            } else {
                byte[] node = NodeIdUtil.resolveRevision(changelog, revision);
                if (node == null) {
                    throw new IOException("Revision not found: " + revision);
                }
                int rev = changelog.findRevision(node);
                if (rev == -1) {
                    throw new IOException("Revision not found in index: " + revision);
                }
                targetAncestors = ancestorsInclusive(changelog, rev);
            }

            List<Integer> selected = new ArrayList<>();
            for (int r : targetAncestors) {
                if (!baseAncestors.contains(r)) {
                    selected.add(r);
                }
            }
            Collections.sort(selected);

            if (selected.isEmpty()) {
                return 0;
            }

            Set<Integer> selectedSet = new HashSet<>(selected);

            ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
            bundle.changelogEntries = new ArrayList<>();
            bundle.fileGroups = new ArrayList<>();

            // 1a. Changelog entries, delta-encoded against whatever was packed immediately before
            // each one (cg1 forcedeltaparentprev rule) -- the very first packed entry deltas
            // against its own parent-1 content (empty/fulltext when it has none), matching
            // mercurial/revlog.py's _emit_revisions: `prevrev = parents(revs[0])[0]`. deltabase/
            // flags are populated regardless of version (cg1's writer simply ignores them) so the
            // exact same entries serialize correctly whichever version gets negotiated above.
            byte[] prevClContent = ancestorSeedContent(changelog, changelog.getIndexRecord(selected.get(0)).getParent1());
            byte[] prevClNode = (changelog.getIndexRecord(selected.get(0)).getParent1() != -1)
                    ? changelog.getIndexRecord(changelog.getIndexRecord(selected.get(0)).getParent1()).getNodeId()
                    : new byte[20];
            for (int r : selected) {
                Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
                ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
                clEntry.node = clRec.getNodeId();
                clEntry.p1 = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
                clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
                clEntry.cs = clRec.getNodeId();
                clEntry.flags = clRec.getFlags();

                byte[] content = changelog.getRevisionContent(r);
                clEntry.deltabase = prevClNode;
                clEntry.delta = Revlog.createDelta(prevClContent, content);
                bundle.changelogEntries.add(clEntry);
                prevClContent = content;
                prevClNode = clRec.getNodeId();
            }

            // 1b. Manifest entries -- one per selected changeset (same per-changeset lookup
            // PushCommand uses), also collecting the touched-file paths every selected changeset's
            // raw changelog text lists starting at line index 3.
            Revlog manifest = repository.getManifestRevlog();
            Set<String> affectedFiles = new TreeSet<>();
            List<ChangegroupParser.ChangeGroupEntry> rootMfEntries = new ArrayList<>();
            byte[] prevMfContent = null;
            byte[] prevMfNode = new byte[20];
            for (int r : selected) {
                Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
                byte[] clContent = changelog.getRevisionContent(r);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                String[] clLines = clText.split("\n");

                for (int i = 3; i < clLines.length; i++) {
                    String line = clLines[i].trim();
                    if (line.isEmpty()) break;
                    affectedFiles.add(line);
                }

                byte[] mfNode = NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40));
                int mfRev = manifest.findRevision(mfNode);
                if (mfRev == -1) continue;

                Revlog.IndexRecord mfRec = manifest.getIndexRecord(mfRev);
                byte[] mfP1Node = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
                if (rootMfEntries.isEmpty()) {
                    prevMfContent = ancestorSeedContent(manifest, mfRec.getParent1());
                    prevMfNode = mfP1Node;
                }

                ChangegroupParser.ChangeGroupEntry mfEntry = new ChangegroupParser.ChangeGroupEntry();
                mfEntry.node = mfRec.getNodeId();
                mfEntry.p1 = mfP1Node;
                mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
                mfEntry.cs = clRec.getNodeId();
                mfEntry.flags = mfRec.getFlags();

                byte[] content = manifest.getRevisionContent(mfRev);
                mfEntry.deltabase = prevMfNode;
                mfEntry.delta = Revlog.createDelta(prevMfContent, content);
                rootMfEntries.add(mfEntry);
                prevMfContent = content;
                prevMfNode = mfRec.getNodeId();
            }

            if (treeCapable) {
                // cg3 always wraps the manifest in the tree-capable envelope, even for a flat
                // manifest (root group only, no subdirectory groups) -- real hg does the same
                // (see BundleType's javadoc).
                bundle.manifestEntries = null;
                bundle.manifestGroups = new ArrayList<>();
                ChangegroupParser.ManifestGroup rootGroup = new ChangegroupParser.ManifestGroup();
                rootGroup.path = "";
                rootGroup.entries = rootMfEntries;
                bundle.manifestGroups.add(rootGroup);

                if (treemanifest) {
                    // Enumerate every directory manifest ("dirlog") this treemanifest repository
                    // has ever written and pack whichever of its revisions were selected above --
                    // the exact bug PushCommand's own backlog #39 fix found and fixed on the
                    // SENDING side (the RECEIVING side, FetchCommand#applyBundle's
                    // bundle.manifestGroups handling, already fully supports this).
                    List<String> treeDirs = findTreemanifestDirs(repository);
                    Collections.sort(treeDirs);
                    for (String dirPath : treeDirs) {
                        File dirIdx = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.i");
                        File dirDat = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.d");
                        if (!dirIdx.exists()) {
                            continue;
                        }
                        Revlog dirlog = repository.getRevlog(dirIdx, dirDat);
                        List<ChangegroupParser.ChangeGroupEntry> dirEntries =
                                packRevlogForSelectedRevs(dirlog, changelog, selectedSet);
                        if (!dirEntries.isEmpty()) {
                            ChangegroupParser.ManifestGroup mg = new ChangegroupParser.ManifestGroup();
                            mg.path = dirPath;
                            mg.entries = dirEntries;
                            bundle.manifestGroups.add(mg);
                        }
                    }
                }
            } else {
                bundle.manifestEntries = rootMfEntries;
            }

            // 1c. Filelog entries -- every revision of every touched file whose linkRev falls in
            // the selected changeset set (generalizing PushCommand's contiguous "linkRev >=
            // startRev" test to arbitrary-set membership) -- shares packRevlogForSelectedRevs with
            // the treemanifest dirlog packing above (both apply the identical "first packed entry
            // uses its own real parent's content, later ones chain off the previous packed entry"
            // rule).
            for (String path : affectedFiles) {
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                if (!flIdx.exists()) continue;

                Revlog fl = repository.getRevlog(flIdx, flDat);
                List<ChangegroupParser.ChangeGroupEntry> flEntries =
                        packRevlogForSelectedRevs(fl, changelog, selectedSet);

                if (!flEntries.isEmpty()) {
                    ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                    fg.path = path;
                    fg.entries = flEntries;
                    bundle.fileGroups.add(fg);
                }
            }

            // 2. Serialize the changegroup payload at the negotiated version, reusing the same
            // shared writer PushCommand/HgLocalClient#getBundle already rely on (backlog #39,
            // 2026-09-05: BundleCommand used to hand-roll bare cg1 bytes here via now-removed
            // writeEntryChunk/writePathChunk/writeTerminalChunk helpers, which structurally could
            // not carry a treemanifest directory group).
            ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
            ChangegroupParser.writeBundle(cgOut, bundle, version);
            byte[] payloadBytes = cgOut.toByteArray();

            // 3. Wrap the payload in the requested container/compression format (see BundleType's
            // javadoc for exactly how each byte layout was verified against real hg) and write to
            // disk -- this remains the only way BundleCommand differs from PushCommand's
            // changegroup-building logic: no HgRemoteConnection dispatch, just a local file.
            ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
            if (treeCapable) {
                // -v3: HG20/bundle2 envelope, compression applied at the bundle2 STREAM level
                // (Bundle2Parser's job) rather than to the raw payload directly.
                byte[] wrapped = Bundle2Parser.wrapChangegroupInBundle2(payloadBytes, version, type.bundle2Compression());
                fileOut.write(wrapped);
            } else {
                switch (type) {
                    case NONE_V1:
                        fileOut.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
                        fileOut.write(payloadBytes);
                        break;
                    case GZIP_V1:
                        fileOut.write("HG10GZ".getBytes(StandardCharsets.US_ASCII));
                        // Plain zlib/DEFLATE (Deflater's default nowrap=false mode), NOT the gzip
                        // container GZIPOutputStream would produce -- matches real hg's
                        // zlib.compressobj() and UnbundleCommand's existing InflaterInputStream read.
                        try (DeflaterOutputStream defOut = new DeflaterOutputStream(fileOut, new Deflater())) {
                            defOut.write(payloadBytes);
                        }
                        break;
                    case BZIP2_V1:
                        // Only 4 literal header bytes: the bzip2 stream's own "BZh9..." magic supplies
                        // the rest of what reads back as the 6-byte "HG10BZ" prefix (see BundleType's
                        // javadoc).
                        fileOut.write("HG10".getBytes(StandardCharsets.US_ASCII));
                        try (BZip2CompressorOutputStream bzOut = new BZip2CompressorOutputStream(fileOut)) {
                            bzOut.write(payloadBytes);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unhandled bundle type: " + type);
                }
            }
            Files.write(outputFile.toPath(), fileOut.toByteArray());

            return selected.size();
        }
    }

    /**
     * Resolves the ancestor-inclusive revision set a {@code --base}-style argument denotes: the
     * empty set for the {@code "null"} sentinel (real {@code hg}'s spelling for "no known
     * ancestor", equivalent to {@code -a}/{@code --all}), otherwise every ancestor of the resolved
     * revision, itself included.
     */
    private static Set<Integer> resolveAncestorClosure(Revlog changelog, String revStr) throws IOException {
        if (isNullSentinel(revStr)) {
            return Collections.emptySet();
        }
        byte[] node = NodeIdUtil.resolveRevision(changelog, revStr);
        if (node == null) {
            throw new IOException("Base revision not found: " + revStr);
        }
        int rev = changelog.findRevision(node);
        if (rev == -1) {
            throw new IOException("Base revision not found in index: " + revStr);
        }
        return ancestorsInclusive(changelog, rev);
    }

    private static boolean isNullSentinel(String revStr) {
        return revStr.isEmpty() || "-1".equals(revStr) || "null".equalsIgnoreCase(revStr);
    }

    /** BFS over parent1/parent2 from {@code rootRev}, collecting every ancestor (itself included). */
    private static Set<Integer> ancestorsInclusive(Revlog changelog, int rootRev) {
        Set<Integer> result = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(rootRev);
        while (!stack.isEmpty()) {
            int r = stack.pop();
            if (r < 0 || !result.add(r)) continue;
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            if (rec.getParent1() >= 0) stack.push(rec.getParent1());
            if (rec.getParent2() >= 0) stack.push(rec.getParent2());
        }
        return result;
    }

    /** The delta-base content to seed the first packed entry's delta with: parent-1's content, or
     * an empty fulltext base when there is no parent-1 (a root revision). */
    private static byte[] ancestorSeedContent(Revlog revlog, int parent1Rev) throws IOException {
        return (parent1Rev >= 0) ? revlog.getRevisionContent(parent1Rev) : new byte[0];
    }

    /**
     * Packs every revision of {@code revlog} whose {@code linkRev} is a member of {@code
     * selectedRevs} into changegroup entries -- shared by this command's filelog packing and its
     * treemanifest dirlog packing (backlog #39, 2026-09-05: the same rule {@link
     * PushCommand#packRevlogRange} uses for its own contiguous {@code startRev}-based selection,
     * generalized here to BundleCommand's arbitrary ancestor-closure selection). Each new entry's
     * delta basis is its own real parent's content for the first packed revision, and the
     * previously-packed revision for the rest. Content is always read via {@link
     * Revlog#getRawRevisionContent} (never the decoded {@code getRevisionContent}) so a censored
     * revision's tombstone bytes transfer as-is, matching real hg's own changegroup packer.
     */
    private static List<ChangegroupParser.ChangeGroupEntry> packRevlogForSelectedRevs(
            Revlog revlog, Revlog changelog, Set<Integer> selectedRevs) throws IOException {
        List<ChangegroupParser.ChangeGroupEntry> entries = new ArrayList<>();
        byte[] prevContent = null;
        byte[] prevNode = new byte[20];
        for (int i = 0; i < revlog.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = revlog.getIndexRecord(i);
            if (!selectedRevs.contains(rec.getLinkRev())) {
                continue;
            }
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            byte[] p1Node = (rec.getParent1() != -1) ? revlog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p1 = p1Node;
            entry.p2 = (rec.getParent2() != -1) ? revlog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = changelog.getIndexRecord(rec.getLinkRev()).getNodeId();
            entry.flags = rec.getFlags();

            byte[] content = revlog.getRawRevisionContent(i);
            byte[] deltaBasis;
            byte[] deltaBaseNode;
            if (entries.isEmpty()) {
                deltaBasis = (rec.getParent1() != -1) ? revlog.getRawRevisionContent(rec.getParent1()) : new byte[0];
                deltaBaseNode = p1Node;
            } else {
                deltaBasis = prevContent;
                deltaBaseNode = prevNode;
            }
            entry.deltabase = deltaBaseNode;
            entry.delta = Revlog.createDelta(deltaBasis, content);
            entries.add(entry);
            prevContent = content;
            prevNode = rec.getNodeId();
        }
        return entries;
    }

    /**
     * Enumerates every directory manifest ("dirlog") a treemanifest repository has ever written,
     * as plain {@code dir/subdir}-style relative paths (matching {@code
     * CommitCommand#writeTreeManifestDir}'s own unencoded {@code meta/<dir>/00manifest.i}
     * convention exactly -- no fncache lookup needed, since treemanifest dirlogs are never
     * registered there, unlike filelogs). Identical to {@code PushCommand}'s own private helper
     * of the same shape -- kept as a separate copy since the two commands' packing loops differ
     * (contiguous {@code startRev} range vs. arbitrary ancestor-closure set) and don't otherwise
     * share a base class.
     */
    private static List<String> findTreemanifestDirs(HgRepository repository) {
        List<String> dirs = new ArrayList<>();
        File metaRoot = new File(repository.getStoreDir(), "meta");
        if (metaRoot.isDirectory()) {
            collectTreemanifestDirs(metaRoot, "", dirs);
        }
        return dirs;
    }

    private static void collectTreemanifestDirs(File dir, String relPath, List<String> out) {
        if (!relPath.isEmpty() && new File(dir, "00manifest.i").exists()) {
            out.add(relPath);
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String childRel = relPath.isEmpty() ? child.getName() : relPath + "/" + child.getName();
                collectTreemanifestDirs(child, childRel, out);
            }
        }
    }
}
