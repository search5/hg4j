package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code hg graft --to REV} ({@code cmd_impls/graft._graft_revisions_in_memory}, EXPERIMENTAL): the changesets are grafted onto
 * {@code REV} without touching the working copy. Each one is merged in memory with its first parent as the ancestor and committed on top of
 * the previous result; a conflict aborts everything ({@code cannot graft in memory: merge conflicts}). The hint line is worded as the
 * original words it: {@code (in-memory merge does not support file conflicts)} when the first conflicting file was deleted on one side and
 * changed on the other, {@code (in-memory merge does not support external merge tools)} for any other conflict.
 *
 * <p>Locks, as in the original: only {@code repo.lock()} (the store lock) and a transaction named {@code graft}; there is no
 * {@code wlock} because neither the working copy nor the dirstate is read or written. When anything fails the transaction is rolled back,
 * so changesets already created for earlier revisions of the same call disappear too. {@link #call()} throws for a conflict, as the
 * command aborts; {@link #execute()} reports the outcome as a {@link GraftResult}. A new file revision of a file that the lfs extension
 * tracks is written as a pointer.</p>
 */
public final class GraftInMemory {

    private final HgRepository repository;
    private String to;
    private final List<String> revisions = new ArrayList<>();
    private boolean dryRun;
    private boolean log;
    private String base;
    private String user;
    private String date;
    private final List<String> messages = new ArrayList<>();

    /**
     * Creates an in-memory graft command for the given repository.
     * @param repository the repository to graft in
     */
    public GraftInMemory(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * {@code --to REV}: where the changesets are grafted.
     *
     * @param to the destination revision; required
     * @return this command
     */
    public GraftInMemory setTo(String to) {
        this.to = to;
        return this;
    }

    /**
     * The changesets to graft, in the order given.
     *
     * @param revision a revision spec (a single revision or a revset range) to graft; may be called repeatedly
     * @return this command
     */
    public GraftInMemory addRevision(String revision) {
        revisions.add(revision);
        return this;
    }

    /**
     * {@code -n/--dry-run}: only say what would be grafted.
     *
     * @param dryRun {@code true} to write nothing; default {@code false}
     * @return this command
     */
    public GraftInMemory setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * {@code -l/--log}: append {@code (grafted from <hash>)} to the message.
     *
     * @param log {@code true} to append the origin to each message; default {@code false}
     * @return this command
     */
    public GraftInMemory setLog(boolean log) {
        this.log = log;
        return this;
    }

    /**
     * {@code --base REV}: the ancestor, instead of the first parent of each changeset.
     *
     * @param base the revision to use as merge ancestor, or {@code null} for each changeset's first parent
     * @return this command
     */
    public GraftInMemory setBase(String base) {
        this.base = base;
        return this;
    }

    /**
     * {@code -u/--user}.
     *
     * @param user the author of the new changesets, or {@code null} to keep each original author
     * @return this command
     */
    public GraftInMemory setUser(String user) {
        this.user = user;
        return this;
    }

    /**
     * {@code -d/--date}: {@code "seconds offset"}.
     *
     * @param date the date of the new changesets as {@code "seconds offset"}, or {@code null} to keep each original date
     * @return this command
     */
    public GraftInMemory setDate(String date) {
        this.date = date;
        return this;
    }

    /**
     * What the command printed ({@code grafting 3:abc123def456 "message"}).
     *
     * @return the messages, in the order they were printed
     */
    public List<String> getMessages() {
        return messages;
    }

    private static String shortHex(byte[] node) {
        return NodeIdUtil.toHex(node).substring(0, 12);
    }

    /**
     * Grafts the revisions onto the {@code --to} revision.
     *
     * @return the nodes (hex) of the new changesets, empty for a dry run
     * @throws IOException if the repository cannot be read or written, or a conflict makes an in-memory graft impossible
     * @throws HgLockException if the store lock cannot be acquired
     */
    public List<String> call() throws IOException, HgLockException {
        return run(false).created;
    }

    /**
     * Grafts like {@link #call()} but reports the outcome as a value. A conflict is a returned {@link GraftResult} (nothing is written,
     * including the changesets already made for earlier revisions of the call) instead of the {@code cannot graft in memory} exception,
     * and a dry run does not stop at printing: it merges every revision in memory, in order, and reports the conflicts and the nodes
     * the changesets would get (with the same user and date) while the transaction is rolled back, so nothing is written either way.
     * Usage errors (no {@code --to}, no revisions, unknown revisions) are still exceptions.
     * @return the new changesets in order, or the conflicting files
     * @throws IOException if the repository cannot be read or written
     * @throws HgLockException if the store lock cannot be acquired
     */
    public GraftResult execute() throws IOException, HgLockException {
        Outcome outcome = run(true);
        return outcome.conflicts == null ? GraftResult.grafted(outcome.created, dryRun) : GraftResult.conflicted(outcome.conflicts, dryRun);
    }

    private static final class Outcome {
        List<String> created = new ArrayList<>();
        List<String> conflicts;
    }

    private Outcome run(boolean report) throws IOException, HgLockException {
        messages.clear();
        if (to == null || to.isEmpty()) {
            throw new IllegalStateException("--to is required");
        }
        if (revisions.isEmpty()) {
            throw new HgValidationException("no revisions specified");
        }
        File changelogIndex = new File(repository.getStoreDir(), "00changelog.i");
        File changelogData = new File(repository.getStoreDir(), "00changelog.d");
        // `with repo.lock(), repo.transaction("graft")`: the store lock first, nothing before it
        try (HgLock lock = repository.lockStore()) {
            Revlog changelog = repository.getRevlog(changelogIndex, changelogData);
            byte[] target = NodeIdUtil.resolveRevision(changelog, to);
            List<byte[]> sources = new ArrayList<>();
            for (String spec : revisions) {
                for (int rev : ChangesetRewrite.revrange(repository, changelog, spec)) {
                    sources.add(changelog.getIndexRecord(rev).getNodeId());
                }
            }
            StoreRollback transaction = new StoreRollback(repository);
            List<String> created = new ArrayList<>();
            try {
                for (byte[] source : sources) {
                    int sourceRev = changelog.findRevision(source);
                    messages.add(progress(changelog, sourceRev, source));
                    if (dryRun && !report) {
                        continue;
                    }
                    byte[] ancestor;
                    if (base != null) {
                        ancestor = NodeIdUtil.resolveRevision(changelog, base);
                    } else {
                        int p1 = changelog.getIndexRecord(sourceRev).getParent1();
                        ancestor = p1 < 0 ? null : changelog.getIndexRecord(p1).getNodeId();
                    }
                    messages.addAll(mergedFiles(changelog, target, source, ancestor));
                    TreeMergeCommand.TreeMergeResult merged = new TreeMergeCommand(repository).setOurs(target).setTheirs(source)
                            .setBase(ancestor).call();
                    if (merged.isConflicted() && report) {
                        transaction.abort();
                        Outcome conflicted = new Outcome();
                        conflicted.conflicts = new ArrayList<>(merged.getConflicts());
                        return conflicted;
                    }
                    if (merged.isConflicted()) {
                        // filemerge: a file that cannot be merged cleanly falls to a merge tool, which an in-memory merge does not support
                        // the original words the hint after the first file that cannot be merged: a deletion against a change is
                        // a "file conflict" (the prompt tool), anything else needs a merge tool
                        String first = merged.getConflicts().get(0);
                        String reason = merged.getChangeDeleteConflicts().contains(first) ? "file conflicts" : "external merge tools";
                        throw new HgValidationException("cannot graft in memory: merge conflicts\n"
                                + "(in-memory merge does not support " + reason + ")");
                    }
                    target = commit(transaction, changelog, target, source, sourceRev, merged);
                    created.add(NodeIdUtil.toHex(target));
                }
                if (report && dryRun) {
                    transaction.abort();
                } else {
                    transaction.commit();
                }
                Outcome done = new Outcome();
                done.created = created;
                return done;
            } catch (IOException | RuntimeException failed) {
                transaction.abort();
                throw failed;
            }
        }
    }

    /** {@code merging PATH}, for each file both sides changed since the ancestor (what {@code filemerge} announces before merging it). */
    List<String> mergedFiles(Revlog changelog, byte[] target, byte[] source, byte[] ancestor) throws IOException {
        Map<String, String> ours = repository.getManifestAtCommit(target);
        Map<String, String> theirs = repository.getManifestAtCommit(source);
        Map<String, String> common = ancestor == null || NodeIdUtil.isAllZero(ancestor) ? Map.of() : repository.getManifestAtCommit(ancestor);
        List<String> out = new ArrayList<>();
        for (String path : new java.util.TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR) {{ addAll(ours.keySet()); }}) {
            String o = ours.get(path);
            String t = theirs.get(path);
            String b = common.get(path);
            if (t != null && !o.equals(t) && !o.equals(b) && !t.equals(b)) {
                out.add("merging " + path + "\n");
            }
        }
        return out;
    }

    /** {@code _build_progress}: {@code grafting REV:NODE "first line"} and the tags and bookmarks of the changeset. */
    private String progress(Revlog changelog, int rev, byte[] node) throws IOException {
        ChangesetRewrite.Entry entry = ChangesetRewrite.read(changelog, rev);
        String firstLine = entry.message.split("\n", 2)[0];
        StringBuilder text = new StringBuilder("grafting " + rev + ":" + shortHex(node) + " \"" + firstLine + "\"");
        List<String> names = new ArrayList<>();
        for (TagsCommand.Tag tag : new TagsCommand(repository).call()) {
            if (java.util.Arrays.equals(tag.getNode(), node)) {
                names.add(tag.getName());
            }
        }
        java.util.Collections.sort(names);
        File bookmarks = new File(repository.getHgDir(), "bookmarks");
        if (bookmarks.isFile()) {
            List<String> marks = new ArrayList<>();
            for (String line : Files.readAllLines(bookmarks.toPath(), StandardCharsets.UTF_8)) {
                int space = line.indexOf(' ');
                if (space > 0 && line.substring(0, space).equals(NodeIdUtil.toHex(node))) {
                    marks.add(line.substring(space + 1));
                }
            }
            java.util.Collections.sort(marks);
            names.addAll(marks);
        }
        if (!names.isEmpty()) {
            text.append(" (").append(String.join(" ", names)).append(")");
        }
        return text.append("\n").toString();
    }

    /** A revlog hands back the 32 byte field of its index; a node is its first 20 bytes. */
    private static byte[] node20(byte[] field) {
        return field.length == 20 ? field : java.util.Arrays.copyOf(field, 20);
    }

    private static String flagsOf(int mode) {
        return mode == 0120000 ? "l" : (mode & 0111) != 0 ? "x" : "";
    }

    private File filelogIndex(String path) {
        return CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
    }

    /** {@code repo.commitctx(wctx.tomemctx(...))}: new file revisions, a new manifest, a new changeset on top of {@code target}. */
    private byte[] commit(StoreRollback transaction, Revlog changelog, byte[] target, byte[] source, int sourceRev,
                          TreeMergeCommand.TreeMergeResult merged) throws IOException {
        return commit(transaction, changelog, target, null, source, sourceRev, merged, null);
    }

    /**
     * The commit of a merge result on top of {@code target} (and {@code secondParent}, if any). {@code customize} fills the metadata of
     * the new changeset from the source's (given the new entry, the source's and the target's); without it the changeset is a graft's.
     */
    byte[] commit(StoreRollback transaction, Revlog changelog, byte[] target, byte[] secondParent, byte[] source, int sourceRev,
                  TreeMergeCommand.TreeMergeResult merged,
                  java.util.function.BiConsumer<ChangesetRewrite.Entry, ChangesetRewrite.Entry[]> customize) throws IOException {
        byte[] p2 = secondParent == null ? new byte[20] : secondParent;
        Revlog manifestRevlog = repository.getManifestRevlog();
        File manifestIndex = new File(repository.getStoreDir(), "00manifest.i");
        File manifestData = new File(repository.getStoreDir(), "00manifest.d");
        File changelogIndex = new File(repository.getStoreDir(), "00changelog.i");
        File changelogData = new File(repository.getStoreDir(), "00changelog.d");
        transaction.trackRevlog(changelogIndex, changelogData);
        transaction.trackRevlog(manifestIndex, manifestData);
        transaction.trackFile(new File(repository.getStoreDir(), "fncache"));
        transaction.trackFile(new File(repository.getStoreDir(), "phaseroots"));

        int targetRev = changelog.findRevision(target);
        ChangesetRewrite.Entry targetEntry = ChangesetRewrite.read(changelog, targetRev);
        Map<String, String> manifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        manifest.putAll(repository.getManifestAtCommit(target));
        Map<String, String> targetManifest = new TreeMap<>(manifest);
        int linkrev = changelog.getRevisionCount();
        List<String> files = new ArrayList<>();
        // the files the changeset adds, removes and touches, which a repository with changeset sidedata stores next to the changelog entry
        java.util.Set<String> sdAdded = new java.util.LinkedHashSet<>();
        java.util.Set<String> sdRemoved = new java.util.LinkedHashSet<>();
        java.util.Set<String> sdTouched = new java.util.LinkedHashSet<>();

        for (String path : merged.getRemovedFiles()) {
            if (manifest.remove(path) != null) {
                files.add(path);
                sdRemoved.add(path);
            }
        }
        for (Map.Entry<String, byte[]> e : merged.getChangedFiles().entrySet()) {
            String path = e.getKey();
            String flags = flagsOf(merged.getChangedModes().getOrDefault(path, 0644));
            File index = filelogIndex(path);
            File data = new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d");
            transaction.trackRevlog(index, data);
            Revlog filelog = repository.getRevlog(index, data);
            // localrepo._filecommit: the first parent is the file revision of the parent changeset, if it has the file; no second parent
            String old = targetManifest.get(path);
            byte[] fp1 = old == null ? new byte[20] : NodeIdUtil.fromHex(old.substring(0, 40));
            byte[] node = FilelogWrite.add(repository, filelog, path, e.getValue(), null, fp1, new byte[20], linkrev);
            String entry = NodeIdUtil.toHex(node) + flags;
            if (!entry.equals(old)) {
                manifest.put(path, entry);
                files.add(path);
                (old == null ? sdAdded : sdTouched).add(path);
            }
        }

        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            text.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
        }
        byte[] targetManifestNode = NodeIdUtil.fromHex(targetEntry.manifestHex);
        int targetManifestRev = manifestRevlog.findRevision(targetManifestNode);
        byte[] manifestNode = node20(manifestRevlog.appendRevision(text.toString().getBytes(StandardCharsets.UTF_8), targetManifestRev, -1,
                targetManifestNode, new byte[20], linkrev));

        ChangesetRewrite.Entry sourceEntry = ChangesetRewrite.read(changelog, sourceRev);
        ChangesetRewrite.Entry entry = new ChangesetRewrite.Entry();
        entry.manifestHex = NodeIdUtil.toHex(manifestNode);
        entry.user = user != null ? user : sourceEntry.user;
        if (date != null) {
            String[] parts = date.trim().split("\\s+");
            entry.time = parts[0];
            entry.offset = parts.length > 1 ? parts[1] : "0";
        } else {
            entry.time = sourceEntry.time;
            entry.offset = sourceEntry.offset;
        }
        if (customize != null) {
            customize.accept(entry, new ChangesetRewrite.Entry[] {sourceEntry, targetEntry});
        } else {
            // _build_meta: the source of a changeset that was itself grafted is kept, the changeset becomes the intermediate source
            String originalSource = sourceEntry.extra.get("source");
            if (originalSource != null && !originalSource.isEmpty()) {
                entry.extra.put("source", originalSource);
                entry.extra.put("intermediate-source", NodeIdUtil.toHex(source));
            } else {
                entry.extra.put("source", NodeIdUtil.toHex(source));
            }
            if (targetEntry.extra.containsKey("branch")) { // tomemctx: the branch of the base the changes are applied to
                entry.extra.put("branch", targetEntry.extra.get("branch"));
            }
        }
        entry.files = new ArrayList<>(new TreeMap<String, Boolean>(NodeIdUtil.UTF8_STRING_COMPARATOR) {{
            files.forEach(f -> put(f, true));
        }}.keySet());
        if (customize == null) {
            entry.message = sourceEntry.message + (log ? "\n(grafted from " + NodeIdUtil.toHex(source) + ")" : "");
        } else {
            entry.message = sourceEntry.message;
        }
        // changelog.add: a changeset that already exists is not added again
        byte[] existing = node20(NodeIdUtil.computeNodeId(entry.toBytes(), target, p2));
        if (changelog.findRevision(existing) >= 0) {
            return existing;
        }
        byte[] sidedata = null;
        if (repository.isSidedataCopies()) {
            byte[] sdFiles = ChangingFiles.encode(sdAdded, sdRemoved, java.util.Set.of(), java.util.Set.of(), sdTouched, Map.of(), Map.of());
            if (sdFiles.length > 0) {
                sidedata = io.github.search5.hg4j.storage.SidedataCodec.serialize(
                        Map.of(io.github.search5.hg4j.storage.SidedataCodec.SD_FILES, sdFiles));
            }
        }
        byte[] node = node20(ChangesetRewrite.append(changelog, entry, target, secondParent, sidedata));

        // phases.registernew: a new changeset is draft unless its parent is already secret/draft
        PhaseRoots phaseRoots = repository.getPhaseRoots();
        io.github.search5.hg4j.lib.NodeId id = new io.github.search5.hg4j.lib.NodeId(node);
        if (phaseRoots.getPhase(id, changelog) == PhaseRoots.Phase.PUBLIC) {
            phaseRoots.setPhase(id, PhaseRoots.Phase.DRAFT, changelog);
        }
        return node;
    }
}
