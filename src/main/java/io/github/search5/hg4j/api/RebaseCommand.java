package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.merge.Merge3;
import io.github.search5.hg4j.merge.MergeState;
import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.channels.FileChannel;

/**
 * Porcelain command to rebase revisions on top of another base revision.
 *
 * <p>Follows {@code hg rebase} ({@code rebase.py}): the revisions are chosen with source, base or revision revsets, copied onto the
 * destination one at a time, and the state is kept in {@code .hg/rebasestate} so that a stop on a conflict can be continued with
 * {@link #continueRebase()} or undone with {@link #abort()}. When the rebase is finished the original changesets are removed
 * (stripped, as {@code repair.delayedstrip} does; the removed changesets are kept in a backup bundle under
 * {@code .hg/strip-backup}) unless {@link #setKeep(boolean) keep} is set. No obsolescence markers are written, because evolution is
 * not supported: like the original without evolution, a rebase of changesets that have descendants outside the rebased set is
 * refused unless {@code keep} is set.
 *
 * <p>The locks are taken like the original: the working copy lock, then the store lock, for the whole command. The dirstate is
 * changed inside a {@code changing_parents} context.
 *
 * <p>The cherry-pick path performs a real 3-way merge (via {@link Merge3}, the same
 * engine {@link MergeCommand} uses) whenever the destination and the revision being cherry-picked
 * have both changed the same file differently since the revision's own parent. A genuine conflict
 * writes standard {@code <<<<<<< dest ... ======= ... >>>>>>> source} markers into the working
 * file (byte-for-byte matching real hg's default {@code internal:merge} tool, verified live
 * against real hg 7.2) and leaves the file's conflict bookkeeping in {@code .hg/merge/state2} --
 * the exact same real-hg-compatible format {@link MergeCommand} already writes, so real
 * {@code hg resolve --list} can see it -- and pauses the whole rebase in a resumable state: call
 * {@link #continueRebase()} after resolving (and re-staging the resolved content on disk) to
 * finish that revision's commit and proceed to the next queued one, or {@link #abort()} to
 * discard the entire in-progress rebase and return the repository/working copy to exactly its
 * pre-rebase state.
 */
public class RebaseCommand {
    private static final Logger LOGGER = Logger.getLogger(RebaseCommand.class.getName());

    private final HgRepository repository;
    private byte[] sourceNode;
    private byte[] targetNode;
    private final List<HgHook> preRebaseHooks = new ArrayList<>();
    private final List<HgHook> postRebaseHooks = new ArrayList<>();

    /** Small, purely-metadata snapshot of a changelog revision -- author/message/date/branch. */
    private static final class RevisionMeta {
        String author;
        String message;
        long time;
        int offsetSeconds;
        String branch;
    }

    /**
     * Result of {@link #attemptThreeWayMerge}: whether the path ended up genuinely conflicted,
     * and the accumulated {@link MergeState} to persist so a paused caller can resume via a plain
     * {@code hg resolve} session -- pass a call's returned {@code mergeState} back in as the next
     * call's {@code existingMergeState} so every conflicted path of the same cherry-pick/graft
     * attempt lands in one shared {@code .hg/merge/state2}.
     */
    static final class ThreeWayMergeOutcome {
        final boolean conflicted;
        final MergeState mergeState;

        ThreeWayMergeOutcome(boolean conflicted, MergeState mergeState) {
            this.conflicted = conflicted;
            this.mergeState = mergeState;
        }
    }

    /**
     * Creates the command.
     * @param repository the repository to rebase in; must not be {@code null}
     */
    public RebaseCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a hook run before the rebase starts changing anything.
     * <p>
     * The hook gets a context map with {@code repository}, {@code sourceNode} and {@code targetNode}. If it returns
     * {@code false} the rebase fails with a validation error.
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public RebaseCommand registerPreRebaseHook(HgHook hook) {
        if (hook != null) {
            preRebaseHooks.add(hook);
        }
        return this;
    }

    /**
     * Registers a hook run after a rebase has completed.
     * <p>
     * The hook gets a context map with {@code sourceNode}, {@code targetNode}, {@code rebasedTipNode} (hex of the last
     * new changeset, or {@code null}) and {@code repository}. Its return value is ignored and exceptions it throws
     * are logged and swallowed.
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public RebaseCommand registerPostRebaseHook(HgHook hook) {
        if (hook != null) {
            postRebaseHooks.add(hook);
        }
        return this;
    }

    /**
     * Sets the changeset to rebase for the node-style form (together with {@link #setTarget(byte[])}).
     * <p>
     * When both source and target nodes are set, the source changeset and its descendants are rebased onto the target
     * (like {@code hg rebase -s SRC -d DEST}); both must exist in the changelog.
     * @param sourceNode the 20-byte node id of the source changeset; {@code null} (the default) means the sources
     *         come from the revset options instead
     * @return {@code this}
     */
    public RebaseCommand setSource(byte[] sourceNode) {
        this.sourceNode = sourceNode;
        return this;
    }

    /**
     * Sets the destination for the node-style form of the rebase (together with {@link #setSource(byte[])}).
     * @param targetNode the 20-byte node id of the destination changeset; {@code null} (the default) means the
     *         destination comes from the destination option instead
     * @return {@code this}
     */
    public RebaseCommand setTarget(byte[] targetNode) {
        this.targetNode = targetNode;
        return this;
    }

    private final List<String> sourceSpecs = new ArrayList<>();
    private final List<String> baseSpecs = new ArrayList<>();
    private final List<String> revSpecs = new ArrayList<>();
    private String destSpec;

    /** {@code -s REV}: the changesets and their descendants (may be given more than once). */
    public RebaseCommand addSourceRevset(String revset) {
        sourceSpecs.add(revset);
        return this;
    }

    /** {@code -b REV}: the branches that contain these changesets (may be given more than once). */
    public RebaseCommand addBaseRevset(String revset) {
        baseSpecs.add(revset);
        return this;
    }

    /** {@code -r REV}: exactly these changesets (may be given more than once). */
    public RebaseCommand addRevisionRevset(String revset) {
        revSpecs.add(revset);
        return this;
    }

    /** {@code -d REV}: the destination; may use {@code SRC} (the changeset being placed) and {@code ALLSRC} (all of them). */
    public RebaseCommand setDestination(String revset) {
        this.destSpec = revset;
        return this;
    }

    // ------------------------------------------------------------------ runtime state (rebaseruntime)

    private boolean keep;
    private boolean collapse;
    private String collapseMessage;
    private int external = -1;
    private boolean keepBranches;
    private final List<String> messages = new ArrayList<>();
    private RebasePlanner planner;
    private Revlog changelog;
    private Map<Integer, Integer> destMap = new LinkedHashMap<>();
    private Map<Integer, Integer> state = new LinkedHashMap<>();
    private Set<Integer> skipped = new HashSet<>();
    private int originalWd;
    private String activeBookmark = "";
    private boolean resume;
    private Boolean singleTransactionOption;
    private StoreRollback transaction;

    /** {@code rebase.singletransaction}: all the commits in one transaction (default: the setting in {@code hgrc}, else off). */
    public RebaseCommand setSingleTransaction(boolean single) {
        this.singleTransactionOption = single;
        return this;
    }

    private boolean singleTransaction() {
        if (singleTransactionOption != null) {
            return singleTransactionOption;
        }
        String configured = repository.getConfig().get("rebase", "singletransaction", "false");
        return configured != null && java.util.Set.of("1", "yes", "true", "on", "always").contains(configured.trim().toLowerCase());
    }

    /** {@code --collapse}: the rebased changesets become one. */
    public RebaseCommand setCollapse(boolean collapse) {
        this.collapse = collapse;
        return this;
    }

    /** {@code -m}: the message of the collapsed changeset (default: {@code Collapsed revision} and one line per changeset). */
    public RebaseCommand setMessage(String message) {
        this.collapseMessage = message;
        return this;
    }

    private boolean edit;
    private java.util.function.UnaryOperator<String> messageEditor;

    /** {@code -e/--edit}: the message of each new changeset is passed through the editor ({@link #setMessageEditor}) before it is committed. */
    public RebaseCommand setEdit(boolean edit) {
        this.edit = edit;
        return this;
    }

    /**
     * The commit message editor ({@code cmdutil.getcommiteditor}): given the proposed message it returns the one to commit, an empty
     * result aborts with {@code empty commit message}. A collapse without {@code -m} always runs it ("Collapsed revision" and one line per
     * changeset is only the proposal), other commits run it when {@link #setEdit} is on. Without an editor the proposal is used as it is.
     */
    public RebaseCommand setMessageEditor(java.util.function.UnaryOperator<String> editor) {
        this.messageEditor = editor;
        return this;
    }

    private String edited(String proposal, boolean force) throws HgValidationException {
        if (messageEditor == null || !(edit || force)) {
            return proposal;
        }
        String result = messageEditor.apply(proposal);
        if (result == null || result.strip().isEmpty()) {
            throw new HgValidationException("empty commit message");
        }
        return result;
    }

    /** {@code --keep}: keep the original changesets. */
    public RebaseCommand setKeep(boolean keep) {
        this.keep = keep;
        return this;
    }

    /** {@code --keepbranches}: the new changesets keep the branch of the originals. */
    public RebaseCommand setKeepBranches(boolean keepBranches) {
        this.keepBranches = keepBranches;
        return this;
    }

    /** What the command printed ({@code rebasing ...}, notes), in order. */
    public List<String> getMessages() {
        return messages;
    }

    private void loadChangelog() throws IOException {
        repository.clearRevlogCache();
        changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                new File(repository.getStoreDir(), "00changelog.d"));
        planner = new RebasePlanner(new RebasePlanner.Graph() {
            public int size() {
                return changelog.getRevisionCount();
            }

            public int[] parents(int rev) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
                return new int[] {rec.getParent1(), rec.getParent2()};
            }
        }).withNamer(r -> {
            return shortHex(changelog.getIndexRecord(r).getNodeId());
        });
    }

    private static String shortHex(byte[] node) {
        return NodeIdUtil.toHex(node).substring(0, 12);
    }

    private byte[] nodeOf(int rev) throws IOException {
        byte[] n = changelog.getIndexRecord(rev).getNodeId();
        return n.length == 20 ? n : java.util.Arrays.copyOf(n, 20);
    }

    private String hexOf(int rev) throws IOException {
        return NodeIdUtil.toHex(nodeOf(rev));
    }

    private int revOf(String hex) throws IOException {
        return changelog.findRevision(NodeIdUtil.fromHex(hex));
    }

    /** {@code scmutil.bail_if_changed}. */
    private void bailIfChanged() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        byte[] p2 = dirstate.getParent2();
        if (p2 != null && !NodeIdUtil.isAllZero(p2)) {
            throw new HgValidationException("outstanding uncommitted merge");
        }
        Status status = new StatusCommand(repository).call();
        if (!status.getModified().isEmpty() || !status.getAdded().isEmpty() || !status.getRemoved().isEmpty()) {
            throw new HgValidationException("uncommitted changes");
        }
    }

    /** {@code storestatus}: the state in the file and format of Mercurial. */
    private void storeStatus() throws IOException {
        RebaseState file = new RebaseState();
        file.originalWd = hexOf(originalWd);
        file.keep = keep;
        file.collapse = collapse;
        file.external = external >= 0 ? hexOf(external) : RebaseState.NULL_HEX;
        file.keepBranches = keepBranches;
        file.activeBookmark = activeBookmark;
        for (Map.Entry<Integer, Integer> e : state.entrySet()) {
            int newRev = e.getValue();
            file.state.put(hexOf(e.getKey()), new RebaseState.Entry(newRev >= 0 ? hexOf(newRev) : RebaseState.TODO, hexOf(destMap.get(e.getKey()))));
        }
        file.store(repository);
    }

    /** {@code restorestatus}. */
    private void restoreStatus() throws IOException {
        RebaseState file = RebaseState.read(repository);
        keep = file.keep;
        collapse = file.collapse;
        external = file.external.equals(RebaseState.NULL_HEX) ? -1 : revOf(file.external);
        keepBranches = file.keepBranches;
        activeBookmark = file.activeBookmark;
        originalWd = revOf(file.originalWd);
        destMap = new LinkedHashMap<>();
        state = new LinkedHashMap<>();
        for (Map.Entry<String, RebaseState.Entry> e : file.state.entrySet()) {
            int rev = revOf(e.getKey());
            destMap.put(rev, revOf(e.getValue().destNode()));
            state.put(rev, e.getValue().newNode().equals(RebaseState.TODO) ? RebasePlanner.TODO : revOf(e.getValue().newNode()));
        }
        skipped = new HashSet<>();
        for (String hex : file.skipped()) {
            skipped.add(revOf(hex));
        }
    }

    /** {@code cmdutil.checkunfinished}. */
    private void checkUnfinished() throws HgValidationException {
        if (RebaseState.file(repository).isFile()) {
            throw new HgValidationException("rebase in progress\n(use 'hg rebase --continue' or 'hg rebase --abort')");
        }
    }

    /** {@code _ctxdesc}: {@code REV:NODE}, its bookmarks and tags (the branch is left out), and the first line of the message. */
    private String describe(int rev) throws IOException {
        String firstLine = readRevisionMeta(rev, changelog).message.split("\n", 2)[0];
        String node = NodeIdUtil.toHex(nodeOf(rev));
        List<String> names = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (Map.Entry<String, String> bookmark : new BookmarkCommand(repository).list().entrySet()) {
            if (bookmark.getValue().equals(node)) {
                marks.add(bookmark.getKey());
            }
        }
        java.util.Collections.sort(marks);
        names.addAll(marks);
        List<String> tags = new ArrayList<>();
        for (TagsCommand.Tag tag : new TagsCommand(repository).call()) {
            if (java.util.Arrays.equals(tag.getNode(), nodeOf(rev)) && !tags.contains(tag.getName())) {
                tags.add(tag.getName());
            }
        }
        if (rev == changelog.getRevisionCount() - 1 && !tags.contains("tip")) {
            tags.add("tip");
        }
        java.util.Collections.sort(tags);
        names.addAll(tags);
        return rev + ":" + shortHex(nodeOf(rev)) + (names.isEmpty() ? "" : " " + String.join(" ", names)) + " \"" + firstLine + "\"";
    }

    // ------------------------------------------------------------------ rebase

    /**
     * {@code hg rebase -s SOURCE -d TARGET}. As in Mercurial ({@code _origrebase}), the working copy lock and then the store lock are taken
     * once for the whole command and released when it ends; each changeset is merged in the working copy and committed in a transaction of
     * its own, and the state is written to {@code .hg/rebasestate} before the first changeset and after each one.
     *
     * @return the new node of the last changeset rebased, {@code null} when there was nothing to rebase
     */
    public byte[] call() throws IOException, HgLockException, HgMergeConflictException {
        boolean nodeStyle = sourceNode != null && targetNode != null;
        if (!nodeStyle && sourceSpecs.isEmpty() && baseSpecs.isEmpty() && revSpecs.isEmpty()) {
            throw new IllegalStateException("Source and Target nodes must be specified for rebase.");
        }
        messages.clear();
        return run();
    }

    private boolean dryRun;
    private java.util.function.BooleanSupplier confirm;

    /** {@code -n/--dry-run}: the rebase is done in memory and undone; the repository and the working copy are left as they were. */
    public RebaseCommand setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /** {@code --confirm}: the rebase is done in memory first; the answer decides whether it is applied (asked after "rebase completed successfully"). */
    public RebaseCommand setConfirm(java.util.function.BooleanSupplier apply) {
        this.confirm = apply;
        return this;
    }

    private byte[] run() throws IOException, HgLockException, HgMergeConflictException {
        boolean nodeStyle = sourceNode != null && targetNode != null;
        boolean inMemory = dryRun || confirm != null;
        if (inMemory) {
            messages.add(confirm != null ? "starting in-memory rebase\n" : "starting dry-run rebase; repository will not be changed\n");
        }
        // `with repo.wlock(), repo.lock():` takes both before anything is read
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
            loadChangelog();
            checkUnfinished();
            if (!inMemory) {
                bailIfChanged();
            }
            List<String> src = new ArrayList<>(sourceSpecs);
            String dst = destSpec;
            if (nodeStyle) {
                if (NodeIdUtil.findRevisionByNodeId(changelog, sourceNode) == -1 || NodeIdUtil.findRevisionByNodeId(changelog, targetNode) == -1) {
                    throw new HgRevisionNotFoundException("Source or target revision not found in history.");
                }
                src.add(NodeIdUtil.toHex(sourceNode));
                dst = NodeIdUtil.toHex(targetNode);
            }
            if (!defineDestMap(src, baseSpecs, revSpecs, dst)) {
                return null;
            }
            if (inMemory && destMap.containsKey(changelog.findRevision(repository.getDirstate().getParent1()))) {
                bailIfChanged(); // the working copy parent is rebased: it has to be clean after all
            }
            if (!buildState()) {
                messages.add("nothing to rebase\n");
                return null;
            }
            if (collapse) {
                Set<Integer> dests = new HashSet<>(destMap.values());
                if (dests.size() != 1) {
                    throw new HgValidationException("--collapse does not work with multiple destinations");
                }
                external = externalParent(state, planner.ancestors(dests.iterator().next()));
            }
            if (!keep) {
                // rewriteutil.precheck: nothing public, no merge in progress, no descendants left behind
                try {
                    ChangesetRewrite.precheck(repository, changelog, new ArrayList<>(destMap.keySet()), "rebase");
                } catch (HgValidationException e) {
                    throw new HgValidationException(e.getMessage() + "\n(use --keep to keep original changesets)");
                }
            }
            if (!preRebaseHooks.isEmpty()) {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("repository", repository);
                ctx.put("sourceNode", sourceNode);
                ctx.put("targetNode", targetNode);
                for (HgHook hook : preRebaseHooks) {
                    if (!hook.run(ctx)) {
                        throw new HgValidationException("Rebase rejected by PRE_REBASE hook");
                    }
                }
            }
            if (inMemory) {
                byte[] result = rebaseInMemory();
                return pendingApply ? applyConfirmed() : result;
            }
            if (keepBranches && collapse) {
                Set<String> branches = new HashSet<>();
                for (int rev : state.keySet()) {
                    branches.add(CommitCommand.getBranchOfRevision(changelog, rev));
                    if (branches.size() > 1) {
                        throw new HgValidationException("cannot collapse multiple named branches");
                    }
                }
            }
            skipped = new HashSet<>();
            resume = false;
            // the bookmark that was active is put away while rebasing and comes back at the end
            File current = new File(repository.getHgDir(), "bookmarks.current");
            activeBookmark = current.isFile() ? Files.readString(current.toPath(), StandardCharsets.UTF_8).trim() : "";
            if (!activeBookmark.isEmpty()) {
                Files.deleteIfExists(current.toPath());
            }
            RebaseState.storeCollapseMessage(repository, collapseMessage);
            // stored before the first changeset so that `--abort` works if anything fails from here on
            storeStatus();
            return performAndFinish();
        }
    }



    /**
     * {@code _dryrunrebase}: every changeset is merged in memory and committed in a transaction that is always aborted, so the repository
     * and the working copy stay as they were. A conflict ends it with {@code hit a merge conflict}. With {@code --confirm} the answer
     * decides whether the same rebase is then done for real.
     */
    private byte[] rebaseInMemory() throws IOException, HgLockException, HgMergeConflictException {
        StoreRollback tr = new StoreRollback(repository);
        boolean applied = false;
        try {
            GraftInMemory committer = new GraftInMemory(repository);
            for (List<Integer> batch : RebasePlanner.sortSource(destMap)) {
                for (int rev : planner.topoOrder(batch)) {
                    String desc = describe(rev);
                    int current = state.get(rev);
                    if (current == rev) {
                        messages.add("already rebased " + desc + "\n");
                        continue;
                    }
                    if (current != RebasePlanner.TODO) {
                        messages.add("already rebased " + desc + " as " + shortHex(nodeOf(current)) + "\n");
                        continue;
                    }
                    messages.add("rebasing " + desc + "\n");
                    RebasePlanner.Parents parents = planner.defineParents(rev, destMap, state, skipped, Set.of());
                    byte[] p1 = nodeOf(parents.p1());
                    byte[] p2 = parents.p2() < 0 ? null : nodeOf(parents.p2());
                    messages.addAll(committer.mergedFiles(changelog, p1, nodeOf(rev), parents.base() < 0 ? null : nodeOf(parents.base())));
                    TreeMergeCommand.TreeMergeResult merged = new TreeMergeCommand(repository).setOurs(p1).setTheirs(nodeOf(rev))
                            .setBase(parents.base() < 0 ? null : nodeOf(parents.base())).call();
                    if (merged.isConflicted()) {
                        messages.add("hit a merge conflict\n");
                        return null;
                    }
                    if (p2 == null && !skipEmptyDisabled() && merged.getChangedFiles().isEmpty() && merged.getRemovedFiles().isEmpty()) {
                        messages.add("note: not rebasing " + desc + ", its destination already has all its changes\n");
                        skipped.add(rev);
                        state.put(rev, parents.p1());
                        continue;
                    }
                    final String sourceHex = hexOf(rev);
                    final boolean keepBranch = keepBranches;
                    byte[] node = committer.commit(tr, changelog, p1, p2, nodeOf(rev), rev, merged, (entry, both) -> {
                        entry.extra.put("rebase_source", sourceHex);
                        ChangesetRewrite.Entry branchFrom = keepBranch ? both[0] : both[1];
                        if (branchFrom.extra.containsKey("branch")) {
                            entry.extra.put("branch", branchFrom.extra.get("branch"));
                        }
                    });
                    state.put(rev, changelog.findRevision(node));
                }
            }
            if (confirm != null) {
                messages.add("rebase completed successfully\n");
                applied = confirm.getAsBoolean();
            } else {
                messages.add("dry-run rebase completed successfully; run without -n/--dry-run to perform this rebase\n");
            }
            return null;
        } finally {
            tr.abort(); // `_prepareabortorcontinue(isabort=True, backup=False, ...)`: nothing of the rebase stays, and no backup is kept
            repository.clearRevlogCache();
            loadChangelog();
            pendingApply = applied;
        }
    }

    private boolean pendingApply;

    /** After a confirmed {@code --confirm}: the same rebase is done on disk (the working copy must be clean for that). */
    private byte[] applyConfirmed() throws IOException, HgLockException, HgMergeConflictException {
        List<String> first = new ArrayList<>(messages);
        confirm = null;
        dryRun = false;
        pendingApply = false;
        messages.clear();
        try {
            return run();
        } finally {
            List<String> second = new ArrayList<>(messages);
            messages.clear();
            messages.addAll(first);
            for (String line : second) {
                if (!line.startsWith("rebasing ") && !line.startsWith("already rebased ")) {
                    messages.add(line);
                }
            }
        }
    }

    // ------------------------------------------------------------------ _definedestmap

    private int single(String spec) throws IOException {
        TreeSet<Integer> revs = ChangesetRewrite.revrange(repository, changelog, spec);
        if (revs.isEmpty()) {
            throw new HgValidationException("empty revision set");
        }
        return revs.last();
    }

    private TreeSet<Integer> union(List<String> specs) throws IOException {
        TreeSet<Integer> out = new TreeSet<>();
        for (String spec : specs) {
            out.addAll(ChangesetRewrite.revrange(repository, changelog, spec));
        }
        return out;
    }

    private Set<Integer> descendantsOf(Set<Integer> roots) {
        Set<Integer> out = new TreeSet<>(roots);
        for (int r = 0; r < changelog.getRevisionCount(); r++) {
            int[] parents = planner.parentsOf(r);
            if (out.contains(parents[0]) || out.contains(parents[1])) {
                out.add(r);
            }
        }
        return out;
    }

    /** {@code ancestor(a, b)}: the common ancestor that is not an ancestor of another common ancestor; the lowest revision if several. */
    private int commonAncestor(int a, int b) {
        Set<Integer> common = new TreeSet<>(planner.ancestors(a));
        common.retainAll(planner.ancestors(b));
        int best = -1;
        for (int c : common) {
            boolean head = true;
            for (int d : common) {
                if (d > c && planner.isAncestor(c, d)) {
                    head = false;
                    break;
                }
            }
            if (head && (best == -1 || c < best)) {
                best = c;
            }
        }
        return best;
    }

    private static HgValidationException noDestination(String message, String hint) {
        return new HgValidationException(hint == null ? message : message + "\n(" + hint + ")");
    }

    /** {@code destutil.destmerge(action="rebase", onheadcheck=False)}: the destination when {@code -d} is not given. */
    private int defaultDestination(Set<Integer> sourceSet) throws IOException {
        File currentFile = new File(repository.getHgDir(), "bookmarks.current");
        String active = currentFile.isFile() ? Files.readString(currentFile.toPath(), StandardCharsets.UTF_8).trim() : "";
        Map<String, String> bookmarks = new BookmarkCommand(repository).list();
        if (!active.isEmpty() && bookmarks.containsKey(active)) {
            // _destmergebook: the heads of the active bookmark (divergent ones, name@something, too)
            List<String> heads = new ArrayList<>();
            String current = bookmarks.get(active);
            for (Map.Entry<String, String> bookmark : bookmarks.entrySet()) {
                if ((bookmark.getKey().equals(active) || bookmark.getKey().startsWith(active + "@")) && !heads.contains(bookmark.getValue())) {
                    heads.add(bookmark.getValue());
                }
            }
            String hint = "run 'hg heads' to see all heads, specify destination with -d";
            if (heads.size() == 2) {
                return revOf(current.equals(heads.get(0)) ? heads.get(1) : heads.get(0));
            } else if (heads.size() > 2) {
                throw noDestination("multiple matching bookmarks to rebase - please rebase to an explicit rev or bookmark", hint);
            }
            throw noDestination("no matching bookmark to rebase - please rebase to an explicit rev or bookmark", hint);
        }
        // _destmergebranch
        if (sourceSet.isEmpty()) {
            throw noDestination("source set is empty", null);
        }
        String branch = null;
        for (int r : sourceSet) {
            int[] parents = planner.parentsOf(r);
            if (sourceSet.contains(parents[0]) || sourceSet.contains(parents[1])) {
                continue; // not a root of the set
            }
            String b = CommitCommand.getBranchOfRevision(changelog, r);
            if (branch != null && !b.equals(branch)) {
                throw noDestination("rebaseset is rooted in multiple named branches", "specify an explicit destination with --dest");
            }
            branch = b;
        }
        int count = changelog.getRevisionCount();
        boolean[] hasChildInBranch = new boolean[count];
        for (int r = 0; r < count; r++) {
            if (CommitCommand.getBranchOfRevision(changelog, r).equals(branch)) {
                for (int p : planner.parentsOf(r)) {
                    if (p >= 0) {
                        hasChildInBranch[p] = true;
                    }
                }
            }
        }
        List<Integer> branchHeads = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            if (CommitCommand.getBranchOfRevision(changelog, r).equals(branch) && !hasChildInBranch[r]
                    && !CommitCommand.isRevisionClosingBranch(changelog, r)) {
                branchHeads.add(r);
            }
        }
        boolean onHead = false;
        for (int h : branchHeads) {
            onHead |= sourceSet.contains(h);
        }
        Set<Integer> sourceDescendants = descendantsOf(sourceSet);
        branchHeads.removeIf(sourceDescendants::contains);
        Set<Integer> bookmarked = new HashSet<>();
        for (String node : bookmarks.values()) {
            bookmarked.add(revOf(node));
        }
        List<Integer> unbookmarked = new ArrayList<>(branchHeads);
        unbookmarked.removeIf(bookmarked::contains);
        if (unbookmarked.size() > 1) {
            throw noDestination("branch '" + branch + "' has " + (branchHeads.size() + 1) + " heads - please rebase to an explicit rev",
                    "run 'hg heads .' to see heads, specify destination with -d");
        } else if (unbookmarked.isEmpty()) {
            String hint = "run 'hg heads' to see all heads, specify destination with -d";
            if (!branchHeads.isEmpty()) {
                throw noDestination("heads are bookmarked - please rebase to an explicit rev", hint);
            }
            int topological = 0;
            boolean[] hasChild = new boolean[count];
            for (int r = 0; r < count; r++) {
                for (int p : planner.parentsOf(r)) {
                    if (p >= 0) {
                        hasChild[p] = true;
                    }
                }
            }
            for (int r = 0; r < count; r++) {
                topological += hasChild[r] ? 0 : 1;
            }
            if (topological > 1) {
                throw noDestination("branch '" + branch + "' has one head - please rebase to an explicit rev", hint);
            } else if (!onHead) {
                throw noDestination("nothing to rebase", "use 'hg update' instead");
            }
            throw noDestination("nothing to rebase", null);
        }
        return unbookmarked.get(0);
    }

    /** `%s` of a context: the short hash. */
    private String desc(int rev) throws IOException {
        return shortHex(nodeOf(rev));
    }

    /**
     * {@code _definedestmap}: the sources from {@code -r}, {@code -s} or {@code -b} (in that order of priority) and where each goes.
     *
     * @return {@code false} when there is nothing to rebase (the reason is in the messages)
     */
    private boolean defineDestMap(List<String> srcf, List<String> basef, List<String> revf, String destf) throws IOException {
        Set<Integer> rebaseSet;
        Integer dest = null;
        if (destf == null && "true".equalsIgnoreCase(repository.getConfig().get("commands", "rebase.requiredest", "false"))) {
            throw new HgValidationException("you must specify a destination\n(use: hg rebase -d REV)");
        }
        if (!revf.isEmpty()) {
            rebaseSet = union(revf);
            if (rebaseSet.isEmpty()) {
                messages.add("empty \"rev\" revision set - nothing to rebase\n");
                return false;
            }
        } else if (!srcf.isEmpty()) {
            Set<Integer> src = union(srcf);
            if (src.isEmpty()) {
                messages.add("empty \"source\" revision set - nothing to rebase\n");
                return false;
            }
            rebaseSet = descendantsOf(src);
        } else {
            TreeSet<Integer> base = union(basef.isEmpty() ? List.of(".") : basef);
            if (base.isEmpty()) {
                messages.add("empty \"base\" revision set - can't compute rebase set\n");
                return false;
            }
            dest = destf == null ? defaultDestination(base) : single(destf);
            Map<Integer, List<Integer>> bpbase = new LinkedHashMap<>();
            for (int b : base) {
                bpbase.computeIfAbsent(commonAncestor(b, dest), k -> new ArrayList<>()).add(b);
            }
            if (bpbase.containsKey(-1)) {
                bpbase.clear(); // no branching point: "nothing to rebase"
            }
            Set<Integer> roots = new TreeSet<>();
            for (Map.Entry<Integer, List<Integer>> e : bpbase.entrySet()) {
                Set<Integer> up = new HashSet<>();
                for (int b : e.getValue()) {
                    up.addAll(planner.ancestors(b));
                }
                for (int r = 0; r < changelog.getRevisionCount(); r++) {
                    int[] parents = planner.parentsOf(r);
                    if ((parents[0] == e.getKey() || parents[1] == e.getKey()) && up.contains(r)) {
                        roots.add(r);
                    }
                }
            }
            rebaseSet = descendantsOf(roots);
            if (roots.isEmpty()) {
                rebaseSet = new TreeSet<>();
            }
            if (rebaseSet.isEmpty()) {
                boolean explicit = !basef.isEmpty();
                final int finalDest = dest;
                String baseNames = String.join("+", base.stream().map(r -> {
                    try {
                        return desc(r);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }).toList());
                if (new ArrayList<>(base).equals(List.of(dest))) {
                    messages.add(explicit ? "nothing to rebase - " + desc(dest) + " is both \"base\" and destination\n"
                            : "nothing to rebase - working directory parent is also destination\n");
                } else if (base.stream().allMatch(b -> planner.isAncestor(b, finalDest))) {
                    messages.add(explicit ? "nothing to rebase - \"base\" " + baseNames + " is already an ancestor of destination " + desc(dest) + "\n"
                            : "nothing to rebase - working directory parent is already an ancestor of destination " + desc(dest) + "\n");
                } else {
                    messages.add("nothing to rebase from " + baseNames + " to " + desc(dest) + "\n");
                }
                return false;
            }
        }
        if (dest == null && destf == null) {
            dest = defaultDestination(rebaseSet);
        }
        destMap = new LinkedHashMap<>();
        if (dest == null) {
            String allSrc = "(" + String.join("+", rebaseSet.stream().map(String::valueOf).toList()) + ")";
            if (destf.matches(".*\\bSRC\\b.*")) {
                // multi-destination: the destination is worked out for each source separately
                for (int r : rebaseSet) {
                    String one = destf.replaceAll("\\bALLSRC\\b", allSrc).replaceAll("\\bSRC\\b", String.valueOf(r));
                    TreeSet<Integer> found = ChangesetRewrite.revrange(repository, changelog, one);
                    if (found.size() == 1) {
                        destMap.put(r, found.first());
                    } else if (found.isEmpty()) {
                        messages.add("skipping " + desc(r) + " - empty destination\n");
                    } else {
                        throw new HgValidationException("rebase destination for " + desc(r) + " is not unique");
                    }
                }
            } else {
                dest = single(destf.replaceAll("\\bALLSRC\\b", allSrc));
            }
        }
        if (dest != null) {
            for (int r : rebaseSet) {
                destMap.put(r, dest);
            }
        }
        if (destMap.isEmpty()) {
            messages.add("nothing to rebase - empty destination\n");
            return false;
        }
        return true;
    }

    /**
     * {@code buildstate}: {@code state} starts as "to do" for every source, a source that is already a child of its destination (same
     * branch) is done, and so is anything whose parents are all done.
     *
     * @return {@code false} when nothing is left to rebase
     */
    private boolean buildState() throws IOException {
        originalWd = changelog.findRevision(repository.getDirstate().getParent1());
        List<List<Integer>> batches = RebasePlanner.sortSource(destMap);
        List<Integer> roots = new ArrayList<>();
        for (int rev : batches.get(0)) {
            int[] parents = planner.parentsOf(rev);
            if (!(destMap.containsKey(parents[0]) || destMap.containsKey(parents[1]))) {
                roots.add(rev);
            }
        }
        if (roots.isEmpty()) {
            throw new HgValidationException("no matching revisions");
        }
        state = new LinkedHashMap<>();
        for (int rev : destMap.keySet()) {
            state.put(rev, RebasePlanner.TODO);
        }
        boolean empty = batches.size() == 1;
        for (int root : roots) {
            int dest = destMap.get(root);
            if (planner.isAncestor(root, dest)) {
                throw new HgValidationException("source is ancestor of destination");
            }
            if (planner.isAncestor(dest, root)) {
                String rootBranch = CommitCommand.getBranchOfRevision(changelog, root);
                boolean sameBranch = dest == originalWd ? rootBranch.equals(repository.getBranch())
                        : rootBranch.equals(CommitCommand.getBranchOfRevision(changelog, dest));
                int[] parents = planner.parentsOf(root);
                if (!collapse && sameBranch && (parents[0] == dest || parents[1] == dest)) {
                    state.put(root, root);
                    continue;
                }
            }
            empty = false;
        }
        if (empty) {
            return false;
        }
        for (int rev : new TreeSet<>(state.keySet())) {
            int[] parents = planner.parentsOf(rev);
            boolean any = false;
            boolean all = true;
            for (int p : parents) {
                if (p >= 0) {
                    any = true;
                    all &= state.containsKey(p) && state.get(p) == p;
                }
            }
            if (any && all) {
                state.put(rev, rev);
            }
        }
        return true;
    }

    /** {@code externalparent}: the one parent outside the set and the destination's ancestors, which a collapse takes as second parent. */
    private int externalParent(Map<Integer, Integer> state, Set<Integer> destAncestors) throws HgValidationException {
        Set<Integer> parents = new TreeSet<>();
        int source = Collections.min(state.keySet());
        for (int rev : state.keySet()) {
            if (rev == source) {
                continue;
            }
            for (int p : planner.parentsOf(rev)) {
                if (p >= 0 && !state.containsKey(p) && !destAncestors.contains(p)) {
                    parents.add(p);
                }
            }
        }
        if (parents.isEmpty()) {
            return -1;
        }
        if (parents.size() == 1) {
            return parents.iterator().next();
        }
        throw new HgValidationException("unable to collapse on top of " + Collections.max(destAncestors)
                + ", there is more than one external parent: "
                + String.join(", ", parents.stream().map(String::valueOf).toList()));
    }

    /** {@code _performrebase} then {@code _finishrebase}. */
    private byte[] performAndFinish() throws IOException, HgLockException, HgMergeConflictException {
        // `tr = repo.transaction("rebase") if rebase.singletransaction`, held through `_finishrebase`; the old changesets are removed when
        // it has closed (`repair.delayedstrip`). `util.acceptintervention(tr)` closes it when the rebase stops for the user (a conflict)
        // and aborts it on any other failure
        transaction = singleTransaction() ? new StoreRollback(repository) : null;
        deferredReplacements = null;
        try {
            for (List<Integer> batch : RebasePlanner.sortSource(destMap)) {
                for (int rev : planner.topoOrder(batch)) {
                    rebaseNode(rev);
                }
            }
            byte[] result = finishRebase();
            if (transaction != null) {
                StoreRollback tr = transaction;
                transaction = null;
                tr.commit(); // the status file was removed by the finish, nothing to write
                if (deferredReplacements != null) {
                    Map<String, String> replacements = deferredReplacements;
                    deferredReplacements = null;
                    ChangesetRewrite.cleanupNodes(repository, replacements, "rebase", deferredSkipped);
                    loadChangelog();
                }
            }
            return result;
        } catch (HgMergeConflictException e) {
            closeTransaction();
            throw e; // the state stays for `--continue` and `--abort`
        } catch (IOException | RuntimeException failed) {
            if (transaction != null) {
                StoreRollback tr = transaction;
                transaction = null;
                tr.abort();
                repository.clearRevlogCache();
            }
            throw failed;
        }
    }

    private Map<String, String> deferredReplacements;
    private Set<String> deferredSkipped = Set.of();

    /** The transaction closes and its state file is written ({@code storestatus(tr)} is a file generator of the transaction). */
    private void closeTransaction() throws IOException {
        if (transaction != null) {
            StoreRollback tr = transaction;
            transaction = null;
            tr.commit();
            storeStatus();
        }
    }

    /** {@code _rebasenode}. */
    private void rebaseNode(int rev) throws IOException, HgLockException, HgMergeConflictException {
        String desc = describe(rev);
        int current = state.get(rev);
        if (current == rev) {
            messages.add("already rebased " + desc + "\n");
        } else if (current == RebasePlanner.TODO) {
            messages.add("rebasing " + desc + "\n");
            RebasePlanner.Parents parents = planner.defineParents(rev, destMap, state, skipped, Set.of());
            int p1 = parents.p1();
            boolean resuming = resume && changelog.findRevision(repository.getDirstate().getParent1()) == p1;
            resume = false;
            byte[] p2Node = parents.p2() < 0 ? new byte[20] : nodeOf(parents.p2());
            if (!resuming) {
                // rebasenode: update to the destination (unless already there), then merge the source against the base
                if (changelog.findRevision(repository.getDirstate().getParent1()) != p1) {
                    cleanUpdate(p1);
                }
                List<String> conflicts = mergeRevision(rev, nodeOf(p1), parents.base(), p2Node);
                if (!conflicts.isEmpty()) {
                    throw new HgMergeConflictException(conflicts,
                            "unresolved conflicts (see 'hg resolve', then 'hg rebase --continue')");
                }
            }
            final byte[] newP1 = nodeOf(p1);
            repository.changingParents(() -> {
                repository.getDirstate().setParents(newP1, p2Node);
                repository.writeDirstate(repository.getDirstate());
                return null;
            });
            if (collapse) {
                // no commit while collapsing: the working copy keeps what was merged, and this changeset "became" its parent
                state.put(rev, p1);
                if (transaction == null) {
                    storeStatus();
                }
                return;
            }
            if (parents.p2() < 0 && !skipEmptyDisabled() && workingCopyUnchanged(p1)) {
                // rewrite.empty-successor=skip (the default): nothing to commit, the destination has all the changes already
                messages.add("note: not rebasing " + desc + ", its destination already has all its changes\n");
                skipped.add(rev);
                state.put(rev, p1);
            } else {
                byte[] newNode = concludeNode(rev, null);
                state.put(rev, changelog.findRevision(newNode));
            }
        } else {
            messages.add("already rebased " + desc + " as " + shortHex(nodeOf(current)) + "\n");
        }
        // after each changeset is done, not when it stopped for a conflict; with one transaction the state is written when it closes
        if (transaction == null) {
            storeStatus();
        }
    }

    /** {@code mergemod.clean_update}: the working copy becomes the revision, local changes discarded. */
    private void cleanUpdate(int rev) throws IOException, HgLockException {
        restoreWorkingCopyCleanTo(repository, nodeOf(rev));
        repository.setBranch(CommitCommand.getBranchOfRevision(changelog, rev));
    }

    /** {@code rewrite.empty-successor=keep}: an empty successor is created instead of the changeset being dropped. */
    private boolean skipEmptyDisabled() {
        return "keep".equalsIgnoreCase(repository.getConfig().get("rewrite", "empty-successor", "skip"));
    }

    /**
     * Whether the working copy holds exactly what the first parent holds (what `repo.commit` returns `None` for): the same files with the
     * same content. (The dirstate cannot say: the merge marks the files it wrote as normal.)
     */
    private boolean workingCopyUnchanged(int p1) throws IOException {
        MergeCommand helper = new MergeCommand(repository);
        Map<String, String> manifest = helper.loadManifestAtCommit(changelog, repository.getManifestRevlog(), p1);
        Set<String> tracked = new HashSet<>();
        for (Map.Entry<String, Dirstate.Entry> e : repository.getDirstate().getEntries().entrySet()) {
            if (e.getValue().getState() != 'r') {
                tracked.add(e.getKey());
            }
        }
        if (!tracked.equals(manifest.keySet())) {
            return false;
        }
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            File onDisk = new File(repository.getDirectory(), e.getKey());
            byte[] disk = Files.isSymbolicLink(onDisk.toPath())
                    ? Files.readSymbolicLink(onDisk.toPath()).toString().getBytes(StandardCharsets.UTF_8)
                    : (onDisk.isFile() ? Files.readAllBytes(onDisk.toPath()) : null);
            if (disk == null || !java.util.Arrays.equals(disk, helper.getFileRevisionContent(e.getKey(), e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameManifest(int a, int b) throws IOException {
        String ma = new String(changelog.getRevisionContent(a), StandardCharsets.UTF_8).split("\n", 2)[0];
        String mb = new String(changelog.getRevisionContent(b), StandardCharsets.UTF_8).split("\n", 2)[0];
        return ma.equals(mb);
    }

    /** {@code _concludenode}/{@code commitnode}: the commit, in a transaction of its own, with the user, date and message of the source. */
    private byte[] concludeNode(int rev, String message) throws IOException, HgLockException {
        RevisionMeta meta = readRevisionMeta(rev, changelog);
        if (keepBranches) {
            repository.setBranch(meta.branch == null || meta.branch.isEmpty() ? "default" : meta.branch);
        }
        byte[] node = new CommitCommand(repository).setTransaction(transaction)
                .setAuthor(meta.author)
                .setMessage(message != null ? message : edited(meta.message, false))
                .setDate(meta.time, meta.offsetSeconds)
                .setExtra("rebase_source", hexOf(rev)) // `rebase.store-source` is on by default
                .call();
        cleanMergeDir(repository);
        loadChangelog();
        return node;
    }

    /** {@code _finishrebase}. */
    private byte[] finishRebase() throws IOException, HgLockException {
        if (collapse) {
            RebasePlanner.Parents parents = planner.defineParents(Collections.min(state.keySet()), destMap, state, skipped, Set.of());
            String message = collapseMessage;
            boolean proposed = message == null || message.isEmpty();
            if (proposed) {
                StringBuilder text = new StringBuilder("Collapsed revision");
                for (int rev : new TreeSet<>(state.keySet())) {
                    if (!skipped.contains(rev)) {
                        text.append("\n* ").append(readRevisionMeta(rev, changelog).message);
                    }
                }
                message = text.toString();
            }
            message = edited(message, proposed);
            final byte[] collapseP1 = nodeOf(parents.p1());
            final byte[] collapseP2 = external >= 0 ? nodeOf(external) : new byte[20];
            repository.changingParents(() -> {
                Dirstate dirstate = repository.getDirstate();
                dirstate.setParents(collapseP1, collapseP2);
                repository.writeDirstate(dirstate);
                return null;
            });
            byte[] collapsed = concludeNode(Collections.max(state.keySet()), message);
            int collapsedRev = changelog.findRevision(collapsed);
            for (int rev : new ArrayList<>(state.keySet())) {
                state.put(rev, collapsedRev);
            }
        }
        // the working copy goes back to the original parent (or what it became), before anything is stripped
        int newWd = state.getOrDefault(originalWd, originalWd);
        if (newWd < 0) {
            newWd = originalWd;
        }
        if (changelog.findRevision(repository.getDirstate().getParent1()) != newWd) {
            cleanUpdate(newWd);
        }
        byte[] last = null;
        int lastRev = -1;
        Map<String, String> replacements = new LinkedHashMap<>();
        Set<String> skippedHex = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(state).entrySet()) {
            int newRev = e.getValue();
            if (newRev >= 0 && newRev != e.getKey()) {
                replacements.put(hexOf(e.getKey()), hexOf(newRev));
                if (skipped.contains(e.getKey())) {
                    skippedHex.add(hexOf(e.getKey()));
                }
                if (e.getKey() > lastRev) {
                    lastRev = e.getKey();
                    last = nodeOf(newRev);
                }
            }
        }
        if (keep) {
            ChangesetRewrite.moveBookmarks(repository, replacements);
        } else {
            if (transaction != null) {
                // `repair.delayedstrip`: inside a transaction the removal waits until it has closed (it needs none to be running)
                deferredReplacements = replacements;
                deferredSkipped = skippedHex;
            } else {
                ChangesetRewrite.cleanupNodes(repository, replacements, "rebase", skippedHex);
                loadChangelog();
            }
        }
        RebaseState.clear(repository);
        RebaseState.clearCollapseMessage(repository);
        Files.deleteIfExists(new File(repository.getStoreDir(), "undo").toPath());
        if (!activeBookmark.isEmpty()) {
            Map<String, String> bookmarks = new BookmarkCommand(repository).list();
            String marked = bookmarks.get(activeBookmark);
            if (marked != null && marked.equals(NodeIdUtil.toHex(repository.getDirstate().getParent1()))) {
                Files.writeString(new File(repository.getHgDir(), "bookmarks.current").toPath(), activeBookmark, StandardCharsets.UTF_8);
            }
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sourceNode", sourceNode);
        ctx.put("targetNode", targetNode);
        ctx.put("rebasedTipNode", last == null ? null : NodeIdUtil.toHex(last));
        ctx.put("repository", repository);
        for (HgHook hook : postRebaseHooks) {
            try {
                hook.run(ctx);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Post-rebase hook execution failed", e);
            }
        }
        return last;
    }

    /**
     * {@code hg rebase --continue}: the locks first, then the state is read under them. The changeset the rebase stopped on is committed
     * from the working copy (it is the one still to do whose destination is the working copy parent), then the rest goes on.
     */
    public byte[] continueRebase() throws IOException, HgLockException, HgMergeConflictException {
        messages.clear();
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
            loadChangelog();
            if (!RebaseState.file(repository).isFile()) {
                throw new HgValidationException("no rebase in progress");
            }
            restoreStatus();
            collapseMessage = RebaseState.restoreCollapseMessage(repository, false);
            MergeState ms = MergeState.read(mergeStateFile(repository));
            if (!ms.unresolvedFiles().isEmpty()) {
                throw new HgValidationException("unresolved merge conflicts (see 'hg resolve --list'): " + ms.unresolvedFiles());
            }
            resume = true;
            return performAndFinish();
        }
    }

    /**
     * {@code hg rebase --stop}: under both locks, the state is restored; the stopped rebase can only be kept (not undone) when the
     * original changesets stay ({@code --keep}), since without obsolescence markers removing them would orphan the unrebased
     * descendants. The working copy goes back to the original parent (clearing the interrupted merge) and the rebase is finished with
     * what it has done so far.
     */
    public void stop() throws IOException, HgLockException {
        messages.clear();
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
            loadChangelog();
            if (!RebaseState.file(repository).isFile()) {
                throw new HgValidationException("no rebase in progress");
            }
            restoreStatus();
            if (collapse) {
                throw new HgValidationException("cannot stop in --collapse session");
            }
            if (!keep) {
                throw new HgValidationException("cannot remove original changesets with unrebased descendants\n"
                        + "(either enable obsmarkers to allow unstable revisions or use --keep to keep original changesets)");
            }
            cleanUpdate(originalWd);
            cleanMergeDir(repository);
            finishRebase();
        }
    }

    /**
     * {@code hg rebase --abort} ({@code _abort}): the rebased changesets are stripped (not when one of them is public or has gained
     * descendants that are not part of the rebase), the working copy goes back to the original parent, and the state files go.
     */
    public void abort() throws IOException, HgLockException {
        messages.clear();
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
            loadChangelog();
            if (!RebaseState.file(repository).isFile()) {
                throw new HgValidationException("no rebase in progress");
            }
            restoreStatus();
            try {
                List<Integer> rebased = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : state.entrySet()) {
                    int newRev = e.getValue();
                    if (newRev >= 0 && newRev != e.getKey() && newRev != destMap.get(e.getKey())) {
                        rebased.add(newRev);
                    }
                }
                boolean cleanup = true;
                if (!rebased.isEmpty()) {
                    PhaseRoots phases = repository.getPhaseRoots();
                    for (int r : rebased) {
                        if (phases.getPhase(new NodeId(nodeOf(r)), changelog) == PhaseRoots.Phase.PUBLIC) {
                            messages.add("warning: can't clean up public changesets " + shortHex(nodeOf(r)) + "\n");
                            cleanup = false;
                        }
                    }
                    Set<Integer> rebasedSet = new HashSet<>(rebased);
                    for (int r = 0; r < changelog.getRevisionCount() && cleanup; r++) {
                        int[] parents = planner.parentsOf(r);
                        if (!rebasedSet.contains(r) && (rebasedSet.contains(parents[0]) || rebasedSet.contains(parents[1]))) {
                            messages.add("warning: new changesets detected on destination branch, can't strip\n");
                            cleanup = false;
                        }
                    }
                }
                if (cleanup && !rebased.isEmpty()) {
                    int wd = changelog.findRevision(repository.getDirstate().getParent1());
                    if (rebased.contains(wd) || destMap.containsValue(wd)) {
                        restoreWorkingCopyCleanTo(repository, nodeOf(originalWd));
                        repository.setBranch(CommitCommand.getBranchOfRevision(changelog, originalWd));
                    }
                    Set<Integer> rebasedSet = new HashSet<>(rebased);
                    for (int r : new TreeSet<>(rebasedSet)) {
                        int[] parents = planner.parentsOf(r);
                        if (!rebasedSet.contains(parents[0]) && !rebasedSet.contains(parents[1])) {
                            StripCommand strip = new StripCommand(repository).setRevision(hexOf(r)).setKeep(true).setTopic("rebase");
                            strip.call();
                            loadChangelog();
                        }
                    }
                } else {
                    restoreWorkingCopyCleanTo(repository, nodeOf(originalWd));
                }
                cleanMergeDir(repository);
                if (!activeBookmark.isEmpty() && new BookmarkCommand(repository).list().containsKey(activeBookmark)) {
                    Files.writeString(new File(repository.getHgDir(), "bookmarks.current").toPath(), activeBookmark, StandardCharsets.UTF_8);
                }
            } finally {
                RebaseState.clear(repository);
                RebaseState.clearCollapseMessage(repository);
            }
            messages.add("rebase aborted\n");
        }
    }

    // ------------------------------------------------------------------
    // Cherry-pick of a single original revision, with real 3-way-merge conflict detection
    // ------------------------------------------------------------------

    /**
     * {@code mergemod._update(rev, branchmerge=True, force=True, ancestor=base)} into the working copy that stands on {@code currentBase}:
     * the source is merged against the base, the working copy gets the result, the dirstate gets both new parents, and the paths that could
     * not be merged are returned with their bookkeeping in {@code .hg/merge/state2}.
     */
    private List<String> mergeRevision(int origRev, byte[] currentBase, int baseRev, byte[] p2Node) throws IOException, HgLockException {
        // rebasenode: the merge runs in `repo.dirstate.changing_parents(repo)`
        return repository.changingParents(() -> mergeRevisionBody(origRev, currentBase, baseRev, p2Node));
    }

    private List<String> mergeRevisionBody(int origRev, byte[] currentBase, int baseRev, byte[] p2Node) throws IOException, HgLockException {
        if (collapse) {
            return mergeIntoWorkingCopy(origRev, currentBase, baseRev, p2Node);
        }
        Revlog manifestRevlog = repository.getManifestRevlog();
        MergeCommand helper = new MergeCommand(repository);
        byte[] originalNode = nodeOf(origRev);
        Dirstate dirstate = repository.getDirstate();

        int currentBaseRev = NodeIdUtil.findRevisionByNodeId(changelog, currentBase);
        Map<String, String> localManifest = helper.loadManifestAtCommit(changelog, manifestRevlog, currentBaseRev);
        Map<String, String> otherManifest = helper.loadManifestAtCommit(changelog, manifestRevlog, origRev);
        Map<String, String> ancestorManifest = baseRev == -1
                ? Collections.emptyMap()
                : helper.loadManifestAtCommit(changelog, manifestRevlog, baseRev);

        List<String> conflicts = new ArrayList<>();
        MergeState mergeState = null;

        Set<String> changed = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Set<String> unionKeys = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        unionKeys.addAll(ancestorManifest.keySet());
        unionKeys.addAll(otherManifest.keySet());
        for (String path : unionKeys) {
            if (!Objects.equals(ancestorManifest.get(path), otherManifest.get(path))) {
                changed.add(path);
            }
        }

        for (String path : changed) {
            String hAnc = ancestorManifest.get(path);
            String hOther = otherManifest.get(path);
            String hLocal = localManifest.get(path);

            if (hOther == null) {
                // The source removed this path.
                if (hLocal != null && Objects.equals(hLocal, hAnc)) {
                    deleteFileFromWorkingCopy(repository, path);
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                }
                continue;
            }
            if (hLocal == null || Objects.equals(hLocal, hAnc)) {
                applyResolvedContent(repository, dirstate, helper, path, hOther, 'n');
                continue;
            }
            if (Objects.equals(hLocal, hOther)) {
                continue;
            }
            byte[] ancestorLinkNode = baseRev != -1 ? nodeOf(baseRev) : new byte[20];
            messages.add("merging " + path + "\n");
            ThreeWayMergeOutcome mergeOutcome = attemptThreeWayMerge(repository, helper, path,
                    hAnc, hLocal, hOther, ancestorLinkNode, currentBase, originalNode, dirstate, mergeState);
            mergeState = mergeOutcome.mergeState;
            if (mergeOutcome.conflicted) {
                conflicts.add(path);
            }
        }

        dirstate.setParents(currentBase, p2Node);
        repository.writeDirstate(dirstate);
        if (!conflicts.isEmpty()) {
            mergeState.write(mergeStateFile(repository));
        } else {
            cleanMergeDir(repository);
        }
        return conflicts;
    }

    /**
     * The merge of a collapse: the working copy already holds the changes merged from the earlier changesets, so the local side of each
     * file is what is on disk (not the destination's committed version).
     */
    private List<String> mergeIntoWorkingCopy(int origRev, byte[] currentBase, int baseRev, byte[] p2Node) throws IOException, HgLockException {
        return repository.changingParents(() -> mergeIntoWorkingCopyBody(origRev, currentBase, baseRev, p2Node));
    }

    private List<String> mergeIntoWorkingCopyBody(int origRev, byte[] currentBase, int baseRev, byte[] p2Node) throws IOException, HgLockException {
        Revlog manifestRevlog = repository.getManifestRevlog();
        MergeCommand helper = new MergeCommand(repository);
        byte[] originalNode = nodeOf(origRev);
        Dirstate dirstate = repository.getDirstate();
        Map<String, String> otherManifest = helper.loadManifestAtCommit(changelog, manifestRevlog, origRev);
        Map<String, String> ancestorManifest = baseRev == -1 ? Collections.emptyMap()
                : helper.loadManifestAtCommit(changelog, manifestRevlog, baseRev);
        List<String> conflicts = new ArrayList<>();
        MergeState mergeState = null;
        Set<String> changed = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Set<String> union = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        union.addAll(ancestorManifest.keySet());
        union.addAll(otherManifest.keySet());
        for (String path : union) {
            if (!Objects.equals(ancestorManifest.get(path), otherManifest.get(path))) {
                changed.add(path);
            }
        }
        for (String path : changed) {
            String hAnc = ancestorManifest.get(path);
            String hOther = otherManifest.get(path);
            Dirstate.Entry entry = dirstate.getEntries().get(path);
            File onDisk = new File(repository.getDirectory(), path);
            boolean localPresent = entry != null && entry.getState() != 'r' && (onDisk.exists() || Files.isSymbolicLink(onDisk.toPath()));
            byte[] localContent = localPresent ? (Files.isSymbolicLink(onDisk.toPath())
                    ? Files.readSymbolicLink(onDisk.toPath()).toString().getBytes(StandardCharsets.UTF_8) : Files.readAllBytes(onDisk.toPath())) : null;
            byte[] ancestorContent = hAnc == null ? null : helper.getFileRevisionContent(path, hAnc);
            if (hOther == null) {
                if (localPresent && ancestorContent != null && java.util.Arrays.equals(localContent, ancestorContent)) {
                    deleteFileFromWorkingCopy(repository, path);
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                }
                continue;
            }
            byte[] otherContent = helper.getFileRevisionContent(path, hOther);
            if (!localPresent || (ancestorContent != null && java.util.Arrays.equals(localContent, ancestorContent))) {
                applyResolvedContent(repository, dirstate, helper, path, hOther, entry != null && entry.getState() == 'a' ? 'a' : 'n');
                continue;
            }
            if (java.util.Arrays.equals(localContent, otherContent)) {
                continue;
            }
            byte[] ancestorLinkNode = baseRev != -1 ? nodeOf(baseRev) : new byte[20];
            messages.add("merging " + path + "\n");
            ThreeWayMergeOutcome outcome = attemptThreeWayMerge(repository, helper, path, hAnc, localContent, entry.getMode(),
                    flagOf(hOther), null, hOther, ancestorLinkNode, currentBase, originalNode, dirstate, mergeState);
            mergeState = outcome.mergeState;
            if (outcome.conflicted) {
                conflicts.add(path);
            }
        }
        dirstate.setParents(currentBase, p2Node);
        repository.writeDirstate(dirstate);
        if (!conflicts.isEmpty()) {
            mergeState.write(mergeStateFile(repository));
        } else {
            cleanMergeDir(repository);
        }
        return conflicts;
    }

    private static void applyResolvedContent(HgRepository repository, Dirstate dirstate, MergeCommand helper,
            String path, String hexFlag, char state) throws IOException {
        byte[] content = helper.getFileRevisionContent(path, hexFlag);
        int mode = helper.getModeFromManifestHex(hexFlag);
        writeFileToWorkingCopy(repository, path, content, mode);
        dirstate.addEntry(path, new Dirstate.Entry(state, mode, content.length,
                SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));
    }

    /**
     * Attempts a real 3-way text merge (via {@link Merge3}, the same engine {@link MergeCommand}
     * uses) for one path where {@code localHex} (the destination/working-copy side) and {@code
     * otherHex} (the revision being cherry-picked/grafted) changed the same file differently since
     * their common ancestor {@code ancestorHex}. Shared by {@link RebaseCommand} and {@link
     * GraftCommand} -- both hit the exact same "dest and the incoming revision both touched this
     * path since their common point of reference" decision, and this is the hardened, real-hg-
     * verified (2026-09-04, see this class's own javadoc) implementation of it; {@code
     * GraftCommand} must reuse it rather than re-implement its own, weaker version (which -- before
     * this fix -- silently discarded the destination's own diverged content instead of merging or
     * flagging a conflict, verified live as a real data-loss bug against real {@code hg graft} 7.2).
     *
     * <p>Writes the merged (or, if genuinely conflicted, conflict-marker-laden -- byte-for-byte
     * matching real hg's default {@code internal:merge} tool) content to the working copy and
     * stages it in {@code dirstate} as {@code 'm'}. On a genuine conflict, also backs up the
     * pre-merge local content under {@code .hg/merge/<localkey>} and folds the conflict into
     * {@code existingMergeState} (or a freshly-created one, keyed to {@code localCommitNode}/
     * {@code otherCommitNode}, if {@code existingMergeState} is {@code null}) -- the exact same
     * real-hg-compatible {@code .hg/merge/state2} format {@link MergeCommand#call()} itself
     * already writes, so real {@code hg resolve --list} can see it.
     *
     * @param ancestorHex      the ancestor's manifest hex+flag for this path, or {@code null} if
     *                         the ancestor didn't have it
     * @param localHex         the destination/working-copy side's manifest hex+flag, or
     *                         {@code null} if it never had this path
     * @param otherHex         the incoming revision's manifest hex+flag (never {@code null} --
     *                         callers only reach this method when the incoming side has content)
     * @param ancestorLinkNode the changelog node of the ancestor revision (or all-zero if there is
     *                         none), recorded into the merge state's {@code ancestorlinknode} extra
     * @param localCommitNode  the destination commit node, recorded as {@code mergeState.local}
     * @param otherCommitNode  the incoming revision's own node, recorded as {@code mergeState.other}
     */
    static ThreeWayMergeOutcome attemptThreeWayMerge(HgRepository repository, MergeCommand helper, String path,
            String ancestorHex, String localHex, String otherHex, byte[] ancestorLinkNode,
            byte[] localCommitNode, byte[] otherCommitNode, Dirstate dirstate, MergeState existingMergeState)
            throws IOException {
        return attemptThreeWayMerge(repository, helper, path, ancestorHex, helper.getFileRevisionContent(path, localHex),
                helper.getModeFromManifestHex(localHex != null ? localHex : otherHex), flagOf(localHex != null ? localHex : otherHex),
                localHex, otherHex, ancestorLinkNode, localCommitNode, otherCommitNode, dirstate, existingMergeState);
    }

    /** The same with the local side given as content (a working copy that already holds merged changes). */
    static ThreeWayMergeOutcome attemptThreeWayMerge(HgRepository repository, MergeCommand helper, String path,
            String ancestorHex, byte[] localContent, int mode, String flag, String localHex, String otherHex, byte[] ancestorLinkNode,
            byte[] localCommitNode, byte[] otherCommitNode, Dirstate dirstate, MergeState existingMergeState)
            throws IOException {
        byte[] baseContent = ancestorHex == null ? new byte[0] : helper.getFileRevisionContent(path, ancestorHex);
        byte[] otherContent = helper.getFileRevisionContent(path, otherHex);

        List<String> baseLines = helper.readLines(baseContent);
        List<String> localLines = helper.readLines(localContent);
        List<String> otherLines = helper.readLines(otherContent);

        Merge3.MergeResult mergeRes = Merge3.merge(baseLines, localLines, otherLines, "dest", "source");
        StringBuilder sb = new StringBuilder();
        for (String line : mergeRes.getMergedLines()) {
            sb.append(line).append('\n');
        }
        byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        writeFileToWorkingCopy(repository, path, mergedBytes, mode);
        dirstate.addEntry(path, new Dirstate.Entry('m', mode, mergedBytes.length,
                SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));

        if (!mergeRes.isConflicted()) {
            return new ThreeWayMergeOutcome(false, existingMergeState);
        }

        // filemerge: the local version is saved as `<file>.orig` before the tool runs, and kept when the merge is not resolved
        Files.write(new File(repository.getDirectory(), path + ".orig").toPath(), localContent);
        MergeState mergeState = existingMergeState;
        if (mergeState == null) {
            mergeState = new MergeState();
            mergeState.local = localCommitNode;
            mergeState.other = otherCommitNode;
        }
        String localKey = MergeState.getLocalKey(path);
        File localBackup = new File(repository.getHgDir(), "merge/" + localKey);
        localBackup.getParentFile().mkdirs();
        Files.write(localBackup.toPath(), localContent);

        mergeState.addMergedFile(path, localKey, path, path, cleanHexOf(ancestorHex), path, cleanHexOf(otherHex),
                flag);
        mergeState.stateExtras
                .computeIfAbsent(path, k -> new LinkedHashMap<>())
                .put("ancestorlinknode", NodeIdUtil.toHex(ancestorLinkNode));

        return new ThreeWayMergeOutcome(true, mergeState);
    }

    private static String cleanHexOf(String manifestHex) {
        if (manifestHex == null) {
            return MergeState.NULL_HEX;
        }
        return manifestHex.length() > 40 ? manifestHex.substring(0, 40) : manifestHex;
    }

    private static String flagOf(String manifestHex) {
        if (manifestHex != null && manifestHex.length() > 40) {
            return String.valueOf(manifestHex.charAt(40));
        }
        return "";
    }

    private RevisionMeta readRevisionMeta(int rev, Revlog changelog) throws IOException {
        RevisionMeta meta = new RevisionMeta();

        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String[] clLines = clText.split("\n");

        meta.author = clLines[1];

        String dateLine = clLines[2].trim();
        long time = 0;
        int offset = 0;
        int firstSpace = dateLine.indexOf(' ');
        if (firstSpace != -1) {
            try {
                time = Long.parseLong(dateLine.substring(0, firstSpace));
            } catch (NumberFormatException ignored) {
            }
            int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
            if (secondSpace != -1) {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1, secondSpace));
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            try {
                time = Long.parseLong(dateLine);
            } catch (NumberFormatException ignored) {
            }
        }
        meta.time = time;
        meta.offsetSeconds = offset;
        meta.branch = CommitCommand.getBranchOfRevision(changelog, rev);

        int msgStartIdx = 3;
        while (msgStartIdx < clLines.length && !clLines[msgStartIdx].isEmpty()) {
            msgStartIdx++;
        }
        msgStartIdx++; // Skip empty line separator

        StringBuilder msgSb = new StringBuilder();
        for (int i = msgStartIdx; i < clLines.length; i++) {
            if (i > msgStartIdx) msgSb.append("\n");
            msgSb.append(clLines[i]);
        }
        meta.message = msgSb.toString();

        return meta;
    }

    // ------------------------------------------------------------------
    // Working copy helpers
    // ------------------------------------------------------------------

    private static void writeFileToWorkingCopy(HgRepository repository, String path, byte[] content, int mode)
            throws IOException {
        File f = new File(repository.getDirectory(), path);
        f.getParentFile().mkdirs();
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
        if (mode == 0120000) {
            String target = new String(content, StandardCharsets.UTF_8).trim();
            try {
                Files.createSymbolicLink(f.toPath(), Path.of(target));
            } catch (Exception e) {
                // Fallback for OS/filesystem limits (e.g. target exceeding PATH_MAX) or missing
                // privilege -- verified against real hg 7.2, which does the same.
                Files.write(f.toPath(), content);
            }
        } else {
            Files.write(f.toPath(), content);
            f.setExecutable(mode == 0755, false);
        }
    }

    private static void deleteFileFromWorkingCopy(HgRepository repository, String path) throws IOException {
        File f = new File(repository.getDirectory(), path);
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
    }

    /**
     * Restores the working copy and dirstate to exactly what {@code targetNode} had checked out,
     * removing any path that is tracked now but isn't part of {@code targetNode}'s manifest. Unlike
     * {@link #checkoutNode}/{@link #applyManifestToWorkingCopy} (which only ever add/overwrite
     * paths, since every other caller only ever moves forward across cherry-picks that never leave
     * stray added paths behind), {@link #abort()} must also clean up any path that only exists
     * because of the aborted rebase's already-applied-but-uncommitted cherry-pick.
     *
     * <p>Package-private so {@link GraftCommand#abort()} can reuse it too -- a paused, conflicted
     * graft leaves exactly the same kind of stray already-staged-but-uncommitted working-copy
     * state behind as a paused rebase does.
     */
    static void restoreWorkingCopyCleanTo(HgRepository repository, byte[] targetNode) throws IOException {
        // mergemod.clean_update: a changing_parents context under the caller's wlock
        try {
            repository.changingParents(() -> {
                restoreWorkingCopyCleanToBody(repository, targetNode);
                return null;
            });
        } catch (io.github.search5.hg4j.errors.HgLockException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void restoreWorkingCopyCleanToBody(HgRepository repository, byte[] targetNode) throws IOException {
        Map<String, String> targetManifest;
        if (targetNode == null || NodeIdUtil.isAllZero(targetNode)) {
            targetManifest = Collections.emptyMap();
        } else {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int rev = NodeIdUtil.findRevisionByNodeId(changelog, targetNode);
            if (rev == -1) {
                throw new HgRevisionNotFoundException("Pre-rebase working directory parent not found: "
                        + NodeIdUtil.toHex(targetNode));
            }
            targetManifest = new MergeCommand(repository).loadManifestAtCommit(changelog, repository.getManifestRevlog(), rev);
        }

        Dirstate dirstate = repository.getDirstate();
        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(dirstate.getEntries().keySet());
        allPaths.addAll(targetManifest.keySet());

        MergeCommand helper = new MergeCommand(repository);
        for (String path : allPaths) {
            String hexFlag = targetManifest.get(path);
            if (hexFlag == null) {
                deleteFileFromWorkingCopy(repository, path);
                dirstate.removeEntry(path);
            } else {
                byte[] content = helper.getFileRevisionContent(path, hexFlag);
                int mode = helper.getModeFromManifestHex(hexFlag);
                writeFileToWorkingCopy(repository, path, content, mode);
                dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length,
                        SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));
            }
        }

        byte[] parent1 = targetNode == null ? new byte[20] : targetNode;
        dirstate.setParents(parent1, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    private static void applyManifestToWorkingCopy(HgRepository repository, byte[] manifestNode) throws IOException {
        Revlog manifest = repository.getManifestRevlog();

        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, manifestNode);
        if (mfRev == -1) {
            throw new HgRevisionNotFoundException("Manifest revision not found for node: " + NodeIdUtil.toHex(manifestNode));
        }

        Map<String, String> entries = new HashMap<>();
        ManifestWalk mw = new ManifestWalk(repository, manifestNode);
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            String flag = entry.isExecutable() ? "x" : (entry.isSymlink() ? "l" : "");
            entries.put(entry.getPath(), entry.getNodeIdHex() + flag);
        }

        Dirstate dirstate = repository.getDirstate();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hexNode = nodeWithFlags.substring(0, 40);
            String flags = nodeWithFlags.length() > 40 ? nodeWithFlags.substring(40) : "";

            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(flIdx, flDat);

            int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
            byte[] fileContent = io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().readContent(repository, path, filelog, fileRev);

            File diskFile = new File(repository.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }

            int mode = 0644;
            if (flags.contains("l")) {
                mode = 0120000;
                String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                } catch (Exception e) {
                    Files.write(diskFile.toPath(), fileContent);
                }
            } else {
                Files.write(diskFile.toPath(), fileContent);
                if (flags.contains("x")) {
                    diskFile.setExecutable(true, false);
                    mode = 0755;
                } else {
                    diskFile.setExecutable(false, false);
                    mode = 0644;
                }
            }

            dirstate.addEntry(path, new Dirstate.Entry('n', mode, fileContent.length, SafeFileIO.lastModifiedSeconds(diskFile)));
        }

        repository.writeDirstate(dirstate);
    }

    private static void checkoutNode(HgRepository repository, byte[] node) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int rev = NodeIdUtil.findRevisionByNodeId(changelog, node);
        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0];
        byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

        applyManifestToWorkingCopy(repository, mfNode);

        Dirstate dirstate = repository.getDirstate();
        dirstate.setParents(node, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    /** Package-private so {@link GraftCommand} can write/read the same {@code .hg/merge/state2}
     * file for its own paused-on-conflict graft state. */
    static File mergeStateFile(HgRepository repository) {
        return new File(repository.getHgDir(), "merge/state2");
    }

    /**
     * Fully clears {@code .hg/merge} (both {@code state2} and any per-file local-content backups
     * -- and, importantly, real hg's own legacy {@code .hg/merge/state} v1 file, which a real
     * {@code hg resolve --mark} run against an hg4j-paused rebase writes alongside {@code state2}).
     * A plain {@link MergeState#clean} of just {@code state2} is not enough: real hg's own {@code
     * mergestate.read()} falls back to parsing the v1 {@code state} file whenever {@code state2}
     * is absent, so leaving it behind made a completed rebase's {@code hg resolve --list} keep
     * reporting the just-resolved file as {@code R <path>} instead of nothing at all (caught live
     * against real hg 7.2 in {@code RebaseRealHgInteropTest}).
     *
     * <p>Package-private so {@link GraftCommand} can reuse the exact same real-hg-compat cleanup
     * for its own paused-on-conflict graft state instead of a second, easy-to-forget-the-v1-fallback
     * re-implementation.
     */
    static void cleanMergeDir(HgRepository repository) {
        deleteDirRecursively(new File(repository.getHgDir(), "merge"));
    }

    private static void deleteDirRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        file.delete();
    }
}
