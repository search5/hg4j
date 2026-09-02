package com.github.search5.hg4j.api;

import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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

/**
 * Porcelain command corresponding to {@code hg bundle} -- writes the same "HG10UN"-prefixed cg1
 * changegroup bytes {@link PushCommand} sends over the wire to a local FILE instead, without any
 * network dispatch. Verified against real {@code hg} CLI (v7.2, 2026-09-02) on scratch repos:
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
 * <p><b>Scope note:</b> unlike real {@code hg bundle}, this command does not fall back to
 * resolving an implicit base by opening a connection to {@code paths.default-push}/{@code
 * paths.default} and running remote-heads discovery the way {@link PushCommand} does -- that
 * would duplicate {@code PushCommand}'s transport-facing logic for a local-file-writing command
 * and is left as a follow-up. {@link #setBaseRevision(String)} must always be called explicitly;
 * pass {@code "null"} (matching {@code hg}'s own {@code --base null} spelling for "no known
 * ancestor", i.e. bundle everything) when there is no real incremental base.</p>
 */
public class BundleCommand {
    private final HgRepository repository;
    private File outputFile;
    private String revision;
    private String baseRevision;

    public BundleCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The {@code .hg} file the changegroup bytes are written to. Required. */
    public BundleCommand setOutputFile(File outputFile) {
        this.outputFile = outputFile;
        return this;
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
            bundle.manifestEntries = new ArrayList<>();
            bundle.fileGroups = new ArrayList<>();

            // 1a. Changelog entries, delta-encoded against whatever was packed immediately before
            // each one (cg1 forcedeltaparentprev rule) -- the very first packed entry deltas
            // against its own parent-1 content (empty/fulltext when it has none), matching
            // mercurial/revlog.py's _emit_revisions: `prevrev = parents(revs[0])[0]`.
            byte[] prevClContent = ancestorSeedContent(changelog, changelog.getIndexRecord(selected.get(0)).getParent1());
            for (int r : selected) {
                Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
                ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
                clEntry.node = clRec.getNodeId();
                clEntry.p1 = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
                clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
                clEntry.cs = clRec.getNodeId();

                byte[] content = changelog.getRevisionContent(r);
                clEntry.delta = Revlog.createDelta(prevClContent, content);
                bundle.changelogEntries.add(clEntry);
                prevClContent = content;
            }

            // 1b. Manifest entries -- one per selected changeset (same per-changeset lookup
            // PushCommand uses), also collecting the touched-file paths every selected changeset's
            // raw changelog text lists starting at line index 3.
            Revlog manifest = repository.getManifestRevlog();
            Set<String> affectedFiles = new TreeSet<>();
            byte[] prevMfContent = null;
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
                if (prevMfContent == null) {
                    prevMfContent = ancestorSeedContent(manifest, mfRec.getParent1());
                }

                ChangegroupParser.ChangeGroupEntry mfEntry = new ChangegroupParser.ChangeGroupEntry();
                mfEntry.node = mfRec.getNodeId();
                mfEntry.p1 = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
                mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
                mfEntry.cs = clRec.getNodeId();

                byte[] content = manifest.getRevisionContent(mfRev);
                mfEntry.delta = Revlog.createDelta(prevMfContent, content);
                bundle.manifestEntries.add(mfEntry);
                prevMfContent = content;
            }

            // 1c. Filelog entries -- every revision of every touched file whose linkRev falls in
            // the selected changeset set (generalizing PushCommand's contiguous "linkRev >=
            // startRev" test to arbitrary-set membership).
            for (String path : affectedFiles) {
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                if (!flIdx.exists()) continue;

                Revlog fl = repository.getRevlog(flIdx, flDat);
                List<ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

                byte[] prevFlContent = null;
                for (int i = 0; i < fl.getRevisionCount(); i++) {
                    Revlog.IndexRecord flRec = fl.getIndexRecord(i);
                    if (!selectedSet.contains(flRec.getLinkRev())) continue;

                    if (prevFlContent == null) {
                        prevFlContent = (flRec.getParent1() >= 0) ? fl.getRawRevisionContent(flRec.getParent1()) : new byte[0];
                    }

                    ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                    flEntry.node = flRec.getNodeId();
                    flEntry.p1 = (flRec.getParent1() != -1) ? fl.getIndexRecord(flRec.getParent1()).getNodeId() : new byte[20];
                    flEntry.p2 = (flRec.getParent2() != -1) ? fl.getIndexRecord(flRec.getParent2()).getNodeId() : new byte[20];
                    flEntry.cs = changelog.getIndexRecord(flRec.getLinkRev()).getNodeId();

                    // Raw (as-stored) content, not getRevisionContent(): a filelog revision can be
                    // censored (Revlog.REVIDX_ISCENSORED), and bundling must transfer its tombstone
                    // bytes as-is -- same reasoning as PushCommand's identical choice here.
                    byte[] content = fl.getRawRevisionContent(i);
                    flEntry.delta = Revlog.createDelta(prevFlContent, content);
                    flEntries.add(flEntry);
                    prevFlContent = content;
                }

                if (!flEntries.isEmpty()) {
                    ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                    fg.path = path;
                    fg.entries = flEntries;
                    bundle.fileGroups.add(fg);
                }
            }

            // 2. Serialize to the exact "HG10UN" bundle1 container PushCommand sends over the wire
            // (verified byte-for-byte against `hg bundle --all --type none-v1 out.hg`'s output).
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));

                for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                    writePathChunk(dos, fg.path);
                    for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                        writeEntryChunk(dos, entry);
                    }
                    writeTerminalChunk(dos);
                }
                writeTerminalChunk(dos);
            }

            // 3. Write to disk -- this is the only way BundleCommand differs from PushCommand's
            // changegroup-building logic: no HgRemoteConnection dispatch, just a local file.
            Files.write(outputFile.toPath(), baos.toByteArray());

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

    private static void writeEntryChunk(DataOutputStream dos, ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private static void writePathChunk(DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + pathBytes.length;
        dos.writeInt(totalLen);
        dos.write(pathBytes);
    }

    private static void writeTerminalChunk(DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }
}
