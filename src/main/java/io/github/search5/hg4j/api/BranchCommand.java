package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code hg branch}: shows the working directory's branch, marks it as a (new) branch, or with {@link #setClean} resets it to the
 * branch of its parent.
 *
 * <p>Follows {@code commands.branch}: the label is stripped, a name check ({@code scmutil.checknewlabel}) and the test for a branch
 * of the same name happen under the working copy lock, and showing the branch takes no lock.</p>
 */
public class BranchCommand {
    private static final Pattern PYTHON_INTEGER = Pattern.compile("[+-]?[0-9]+");

    private final HgRepository repository;
    private String branchName;
    private boolean clean;
    private boolean force;
    private String[] revisions = new String[0];
    private int changedChangesets;
    private final java.util.List<File> backupBundles = new java.util.ArrayList<>();

    /**
     * Creates a branch command for the given repository.
     *
     * @param repository the repository to operate on; must not be {@code null}
     */
    public BranchCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the name of the branch to switch the working directory to ({@code hg branch NAME}). The name is stripped of surrounding whitespace. Without a name, {@link #call()} only reports the current branch.
     *
     * @param branchName the new branch name
     * @return {@code this}
     */
    public BranchCommand setBranchName(String branchName) {
        this.branchName = branchName;
        return this;
    }

    /** {@code hg branch --clean}: the working directory goes back to the branch of its first parent. */
    public BranchCommand setClean(boolean clean) {
        this.clean = clean;
        return this;
    }

    /** {@code hg branch --force}: a branch of the same name that already exists may be used. */
    public BranchCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /**
     * {@code hg branch --rev REV... NAME}: the branch of these changesets is changed to NAME. Each changeset is replaced by a copy
     * of itself on the new branch (real hg's {@code cmdutil.changebranch}); they have to be a linear stack of drafts that ends in a
     * head, and the working directory must be clean.
     */
    public BranchCommand setRevisions(String... revisions) {
        this.revisions = revisions == null ? new String[0] : revisions;
        return this;
    }

    /** How many changesets {@code --rev} replaced ({@code changed branch on N changesets}). */
    public int getChangedChangesets() {
        return changedChangesets;
    }

    /** The bundles of the replaced changesets that {@code --rev} saved. */
    public java.util.List<File> getBackupBundles() {
        return backupBundles;
    }

    /** @return the branch the working directory is on afterwards (or, without a name and {@code clean}, is on now) */
    public String call() throws IOException, HgLockException {
        String label = branchName == null ? null : branchName.strip(); // commands.branch: label = label.strip()
        if (!clean && (label == null || label.isEmpty()) && revisions.length > 0) {
            throw new HgValidationException("no branch name specified for the revisions");
        }
        if (!clean && (label == null || label.isEmpty())) {
            if (branchName != null) {
                throw new IllegalArgumentException("Branch name cannot be empty");
            }
            return repository.getBranch(); // showing the branch takes no lock
        }
        // Like real hg's `with repo.wlock()`: everything that checks or changes the branch is under the working copy lock.
        try (HgLock wlock = repository.lockWorkingCopy(repository.resolvePushLockTimeoutMs())) {
            if (clean) {
                String reset = branchOfFirstParent();
                repository.setBranch(reset);
                return reset;
            }
            checkNewLabel(label);
            if (revisions.length > 0) {
                changeBranch(label);
                return label;
            }
            if (!force && allBranches().contains(label) && !parentBranches().contains(label)) {
                throw new HgValidationException("a branch of the same name already exists");
            }
            repository.setBranch(label);
            return label;
        }
    }

    /**
     * Real hg's {@code cmdutil.changebranch}: {@code with repo.wlock(), repo.lock(), repo.transaction(b'branches')}. The working copy
     * lock is held by the caller ({@code commands.branch}); the store lock is taken here and kept until the old changesets are gone.
     */
    private void changeBranch(String label) throws IOException, HgLockException {
        if (repository.isTreemanifest() || repository.isSidedataCopies()) {
            throw new HgValidationException("--rev is not supported for treemanifest or changeset-centric copy repositories");
        }
        try (HgLock storeLock = repository.lockStore(repository.resolvePushLockTimeoutMs())) {
            // scmutil.bail_if_changed
            Dirstate wd = repository.getDirstate();
            byte[] wdP2 = wd.getParent2();
            if (wdP2 != null && !NodeIdUtil.isAllZero(wdP2)) {
                throw new HgValidationException("outstanding uncommitted merge");
            }
            Status st = new StatusCommand(repository).call();
            if (!st.getModified().isEmpty() || !st.getAdded().isEmpty() || !st.getRemoved().isEmpty()) {
                throw new HgValidationException("uncommitted changes");
            }
            repository.clearRevlogCache();
            Revlog changelog = changelog();
            java.util.TreeSet<Integer> revs = new java.util.TreeSet<>();
            for (String spec : revisions) {
                revs.addAll(ChangesetRewrite.revrange(repository, changelog, spec));
            }
            if (revs.isEmpty()) {
                throw new HgValidationException("empty revision set");
            }
            java.util.List<Integer> roots = new java.util.ArrayList<>();
            for (int r : revs) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                if (!revs.contains(rec.getParent1()) && !revs.contains(rec.getParent2())) {
                    roots.add(r);
                }
            }
            if (roots.size() > 1) {
                throw new HgValidationException("cannot change branch of non-linear revisions");
            }
            ChangesetRewrite.precheck(repository, changelog, new java.util.ArrayList<>(revs), "change branch of");
            Revlog.IndexRecord rootRecord = changelog.getIndexRecord(roots.get(0));
            java.util.Set<String> rootParentBranches = new HashSet<>();
            for (int parent : new int[] {rootRecord.getParent1(), rootRecord.getParent2()}) {
                if (parent >= 0) {
                    rootParentBranches.add(CommitCommand.getBranchOfRevision(changelog, parent));
                }
            }
            if (!force && !rootParentBranches.contains(label) && allBranches().contains(label)) {
                throw new HgValidationException("a branch of the same name already exists");
            }
            // heads(revs) - head(): a changeset of the set with a child outside it would be left in the middle of a stack
            for (int r : revs) {
                for (int c = r + 1; c < changelog.getRevisionCount(); c++) {
                    Revlog.IndexRecord child = changelog.getIndexRecord(c);
                    if ((child.getParent1() == r || child.getParent2() == r) && !revs.contains(c)) {
                        throw new HgValidationException("cannot change branch in middle of a stack");
                    }
                }
            }
            changedChangesets = 0;
            java.util.Map<String, String> replacements = new java.util.LinkedHashMap<>();
            for (int r : revs) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                ChangesetRewrite.Entry entry = ChangesetRewrite.read(changelog, r);
                String oldBranch = entry.extra.getOrDefault("branch", "default");
                boolean maybeSkip;
                if (oldBranch.equals(label)) {
                    maybeSkip = true;
                } else {
                    changedChangesets++;
                    maybeSkip = false;
                }
                byte[] p1 = rec.getParent1() >= 0 ? changelog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                byte[] p2 = rec.getParent2() >= 0 ? changelog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
                String mapped1 = replacements.get(NodeIdUtil.toHex(p1));
                if (mapped1 != null) {
                    p1 = NodeIdUtil.fromHex(mapped1);
                    maybeSkip = false;
                }
                String mapped2 = replacements.get(NodeIdUtil.toHex(p2));
                if (mapped2 != null) {
                    p2 = NodeIdUtil.fromHex(mapped2);
                    maybeSkip = false;
                }
                if (maybeSkip) {
                    continue;
                }
                entry.extra.put("branch_change", NodeIdUtil.toHex(rec.getNodeId()));
                if (label.equals("default")) {
                    entry.extra.remove("branch");
                } else {
                    entry.extra.put("branch", label);
                }
                byte[] newNode = ChangesetRewrite.append(changelog, entry, p1, p2);
                replacements.put(NodeIdUtil.toHex(rec.getNodeId()), NodeIdUtil.toHex(newNode));
                repository.clearRevlogCache();
                changelog = changelog();
            }
            // the working directory follows its parent (update_impl.update(repo, newid, quietempty=True)), then the old nodes go
            String wdP1 = NodeIdUtil.toHex(wd.getParent1());
            if (replacements.containsKey(wdP1)) {
                new UpdateCommand(repository).setRevision(replacements.get(wdP1)).call();
            }
            backupBundles.addAll(ChangesetRewrite.cleanupNodes(repository, replacements, "branch-change"));
        }
    }

    /** Real hg's {@code scmutil.checknewlabel(repo, label, b'branch')} (the whitespace test cannot fail after the strip above). */
    static void checkNewLabel(String label) throws HgValidationException {
        if (label.equals("tip") || label.equals(".") || label.equals("null")) {
            throw new HgValidationException("the name '" + label + "' is reserved");
        }
        String[][] forbidden = {{":", "':'"}, {"\0", "'\\x00'"}, {"\n", "'\\n'"}, {"\r", "'\\r'"}};
        for (String[] c : forbidden) {
            if (label.contains(c[0])) {
                throw new HgValidationException(c[1] + " cannot be used in a name");
            }
        }
        // int(label) accepts surrounding whitespace and a sign; hg refuses names that contain "_" from this test (PEP 515)
        if (!label.contains("_") && PYTHON_INTEGER.matcher(label.strip()).matches()) {
            throw new HgValidationException("cannot use an integer as a name");
        }
        if (!label.strip().equals(label)) {
            throw new HgValidationException("leading or trailing whitespace in name " + label);
        }
    }

    private Revlog changelog() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        return clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;
    }

    private Set<String> allBranches() throws IOException {
        Set<String> branches = new HashSet<>();
        Revlog changelog = changelog();
        if (changelog != null) {
            for (int rev = 0; rev < changelog.getRevisionCount(); rev++) {
                branches.add(CommitCommand.getBranchOfRevision(changelog, rev));
            }
        }
        return branches;
    }

    private String branchOf(byte[] node) throws IOException {
        if (node == null || NodeIdUtil.isAllZero(node)) {
            return "default";
        }
        Revlog changelog = changelog();
        int rev = changelog == null ? -1 : changelog.findRevision(node);
        return rev < 0 ? "default" : CommitCommand.getBranchOfRevision(changelog, rev);
    }

    private String branchOfFirstParent() throws IOException {
        return branchOf(repository.getDirstate().getParent1());
    }

    private Set<String> parentBranches() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Set<String> branches = new HashSet<>();
        branches.add(branchOf(dirstate.getParent1()));
        byte[] p2 = dirstate.getParent2();
        if (p2 != null && !NodeIdUtil.isAllZero(p2)) {
            branches.add(branchOf(p2));
        }
        return branches;
    }
}
