package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.merge.Merge3;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Merges two heads of a repository and commits the result as one changeset with two parents, without a working copy: the equivalent
 * of {@code hg merge -r P2} followed by {@code hg commit} on a server repository that has no checkout.
 *
 * <p>The merge is computed by {@link TreeMergeCommand} (the common ancestor is found like {@code hg merge} finds it, or given with
 * {@link #setBase(byte[])}), and the new file revisions, manifest and changeset are written like the original's
 * {@code commit._prepare_files}: a file that the merge takes unchanged from the second parent keeps that parent's file revision, a file
 * both sides changed gets a new file revision with two parents, and the changeset lists only the files that were really touched. For the
 * same two heads, user, date and message the result is the same changeset (same node) that {@code hg merge} and {@code hg commit} create.
 * Repositories with changeset sidedata (changelog-v2) get the changed-files sidedata.</p>
 *
 * <p>A conflict is not an exception: {@link #call()} returns a {@link MergeCommitResult} that lists the conflicting files and nothing
 * has been written. A file that one side deleted and the other changed counts as a conflict.</p>
 *
 * <p>Copies and renames are followed as {@code hg merge} follows them: a file that one side copied or renamed (found through the
 * {@code copy} metadata of the file revisions) and whose old name the other side changed gets that change merged into the new name,
 * and the new file revision records the copy. A copy or rename on one side with a deletion of the old name on the other, and two
 * different renames of one file, keep both results, as the original does. Directory renames are not followed: a file that the other
 * side added to a directory that was renamed away is reported as a conflict (the original moves it to the new directory).</p>
 *
 * <p>Locks and transaction: only the store lock and a rollback transaction, no working copy lock and no dirstate, as for
 * {@link GraftInMemory}; when anything fails the repository is left as it was. The parents are stored in the order given: {@code p1} is the
 * side merged into (the receiving branch), {@code p2} the side merged in, which is what {@code hg log -r NODE --template '{p1node}
 * {p2node}'} reports.</p>
 *
 * <p>Limits: repositories with the {@code treemanifest} requirement are refused, a file whose merge needs Mercurial's file-level merge
 * tools is a conflict, and large files kept in LFS are written as their content, not as pointers. By default the two heads must be
 * different branches of history, like {@code hg merge}; {@link #setAllowAncestorMerge(boolean)} also allows a merge where one side is
 * an ancestor of the other (a merge changeset in the style of {@code git merge --no-ff}), which the original refuses.</p>
 */
public class MergeCommitCommand {
    private final HgRepository repository;
    private byte[] p1;
    private String p1Spec;
    private byte[] p2;
    private String p2Spec;
    private byte[] base;
    private String baseSpec;
    private String user;
    private String date;
    private String message;
    private String branch;
    private final TreeMap<String, String> extra = new TreeMap<>();
    private boolean allowAncestorMerge;

    /**
     * Creates the command.
     * @param repository the repository to merge in; must not be {@code null}
     */
    public MergeCommitCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the first parent, the head that the merge is committed on (the receiving side).
     * @param node the 20-byte node id of the changeset; must be set (with this or {@link #setP1(String)}) before {@link #call()}
     * @return {@code this}
     */
    public MergeCommitCommand setP1(byte[] node) {
        this.p1 = node;
        this.p1Spec = null;
        return this;
    }

    /**
     * Sets the first parent by revision identifier.
     * @param revision a revision number, a (prefix of a) node hex, or a name the changelog resolves
     * @return {@code this}
     */
    public MergeCommitCommand setP1(String revision) {
        this.p1Spec = revision;
        this.p1 = null;
        return this;
    }

    /**
     * Sets the second parent, the head that is merged in (the sending side).
     * @param node the 20-byte node id of the changeset; must be set (with this or {@link #setP2(String)}) before {@link #call()}
     * @return {@code this}
     */
    public MergeCommitCommand setP2(byte[] node) {
        this.p2 = node;
        this.p2Spec = null;
        return this;
    }

    /**
     * Sets the second parent by revision identifier.
     * @param revision a revision number, a (prefix of a) node hex, or a name the changelog resolves
     * @return {@code this}
     */
    public MergeCommitCommand setP2(String revision) {
        this.p2Spec = revision;
        this.p2 = null;
        return this;
    }

    /**
     * Sets the ancestor to merge against instead of the common ancestor of the two parents.
     * @param node the 20-byte node id of the changeset, or {@code null} to use the common ancestor
     * @return {@code this}
     */
    public MergeCommitCommand setBase(byte[] node) {
        this.base = node;
        this.baseSpec = null;
        return this;
    }

    /**
     * Sets the ancestor by revision identifier.
     * @param revision the revision identifier, or {@code null} to use the common ancestor
     * @return {@code this}
     */
    public MergeCommitCommand setBase(String revision) {
        this.baseSpec = revision;
        this.base = null;
        return this;
    }

    /**
     * Sets the committer.
     * @param user the name as stored in the changeset, for example {@code Name <mail@example.com>}; required
     * @return {@code this}
     */
    public MergeCommitCommand setUser(String user) {
        this.user = user;
        return this;
    }

    /**
     * Sets the commit date.
     * @param date {@code "<unix seconds> <offset>"} where the offset is the number of seconds west of UTC, as Mercurial stores it;
     *         {@code null} (the default) uses the current time and time zone
     * @return {@code this}
     */
    public MergeCommitCommand setDate(String date) {
        this.date = date;
        return this;
    }

    /**
     * Sets the commit message. Trailing white space of each line and leading and trailing empty lines are removed, as {@code hg commit}
     * does.
     * @param message the message; required, must not be blank
     * @return {@code this}
     */
    public MergeCommitCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Sets the named branch of the merge changeset.
     * @param branch the branch name; {@code null} (the default) keeps the branch of the first parent
     * @return {@code this}
     */
    public MergeCommitCommand setBranch(String branch) {
        this.branch = branch;
        return this;
    }

    /**
     * Adds an {@code extra} entry to the changeset.
     * @param key the key; must not be {@code branch} (use {@link #setBranch(String)})
     * @param value the value
     * @return {@code this}
     */
    public MergeCommitCommand putExtra(String key, String value) {
        if ("branch".equals(key)) {
            throw new IllegalArgumentException("use setBranch for the branch");
        }
        extra.put(key, value);
        return this;
    }

    /**
     * Allows a merge in which one parent is an ancestor of the other. {@code hg merge} refuses it ("nothing to merge"); with this set a
     * merge changeset is created anyway, with the tree of the descendant, like {@code git merge --no-ff}.
     * @param allow {@code true} to allow it; the default is {@code false}
     * @return {@code this}
     */
    public MergeCommitCommand setAllowAncestorMerge(boolean allow) {
        this.allowAncestorMerge = allow;
        return this;
    }

    /**
     * Merges the two parents and commits the result.
     * @return the new changeset, or the conflicting files if the merge could not be completed (nothing is written then)
     * @throws HgValidationException if the user or message is missing, a parent is the same as the other, one is an ancestor of the
     *         other (unless allowed), or the repository uses treemanifest
     * @throws HgRevisionNotFoundException (an {@link IOException}) if a parent or the base is not in the changelog
     * @throws HgLockException if the store lock cannot be taken
     * @throws IOException if reading or writing the store fails; the repository is rolled back then
     */
    public MergeCommitResult call() throws IOException, HgLockException {
        if (user == null || user.trim().isEmpty()) {
            throw new HgValidationException("empty username");
        }
        if (user.contains("\n")) {
            throw new HgValidationException("username " + user + " contains a newline");
        }
        String description = stripDescription(message);
        if (description.isEmpty()) {
            throw new HgValidationException("empty commit message");
        }
        String[] dateParts = parseDate(date);
        if (repository.isTreemanifest()) {
            throw new HgValidationException("merge commit is not supported in a treemanifest repository");
        }
        File changelogIndex = new File(repository.getStoreDir(), "00changelog.i");
        File changelogData = new File(repository.getStoreDir(), "00changelog.d");
        // `with repo.lock(), repo.transaction("commit")`: the store lock only, there is no working copy here
        try (HgLock lock = repository.lockStore()) {
            Revlog changelog = repository.getRevlog(changelogIndex, changelogData);
            byte[] node1 = resolve(changelog, p1, p1Spec, "p1");
            byte[] node2 = resolve(changelog, p2, p2Spec, "p2");
            byte[] baseNode = base != null || baseSpec != null ? resolve(changelog, base, baseSpec, "base") : null;
            int rev1 = changelog.findRevision(node1);
            int rev2 = changelog.findRevision(node2);
            if (rev1 == rev2) {
                throw new HgValidationException("cannot merge a changeset with itself");
            }
            ChangesetGraph graph = new ChangesetGraph(changelog);
            if (!allowAncestorMerge && (graph.isAncestor(rev1, rev2) || graph.isAncestor(rev2, rev1))) {
                throw new HgValidationException("nothing to merge: one parent is an ancestor of the other");
            }

            TreeMergeCommand tree = new TreeMergeCommand(repository).setOurs(node1).setTheirs(node2);
            if (baseNode != null) {
                tree.setBase(baseNode);
            }
            TreeMergeCommand.TreeMergeResult merged = tree.call();
            Plan plan = plan(changelog, graph, node1, node2, rev1, rev2, baseNode, merged);
            if (!plan.conflicts.isEmpty()) {
                return MergeCommitResult.conflicted(plan.conflicts);
            }

            StoreRollback transaction = new StoreRollback(repository);
            try {
                byte[] node = write(transaction, changelog, graph, node1, node2, rev1, rev2, plan, description, dateParts);
                transaction.commit();
                return MergeCommitResult.merged(node);
            } catch (IOException | RuntimeException failed) {
                transaction.abort();
                throw failed;
            }
        }
    }

    /** {@code [seconds, offset]} of {@code "<unix seconds> <offset>"}, or of the current time when no date is given. */
    private static String[] parseDate(String text) throws IOException {
        if (text == null) {
            return new String[] {String.valueOf(System.currentTimeMillis() / 1000),
                    String.valueOf(-TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)};
        }
        String[] parts = text.trim().split("\\s+");
        try {
            long seconds = Long.parseLong(parts[0]);
            int offset = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (parts.length > 2 || seconds < 0) {
                throw new NumberFormatException(text);
            }
            return new String[] {String.valueOf(seconds), String.valueOf(offset)};
        } catch (NumberFormatException e) {
            throw new HgValidationException("invalid date: '" + text + "' (expected '<unix seconds> <offset>')");
        }
    }

    private byte[] resolve(Revlog changelog, byte[] node, String spec, String what) throws IOException {
        if (node != null) {
            if (changelog.findRevision(node) < 0) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(node));
            }
            return node;
        }
        if (spec == null) {
            throw new HgValidationException("the " + what + " changeset is not set");
        }
        return NodeIdUtil.resolveRevision(changelog, spec);
    }

    /** {@code stringutil.stripdesc}: trailing white space of every line goes, and so do empty lines at both ends. */
    private static String stripDescription(String text) {
        if (text == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.replaceAll("[ \\t\\r\\f\\x0B]+$", ""));
        }
        String joined = String.join("\n", lines);
        int start = 0;
        int end = joined.length();
        while (start < end && joined.charAt(start) == '\n') {
            start++;
        }
        while (end > start && joined.charAt(end - 1) == '\n') {
            end--;
        }
        return joined.substring(start, end);
    }

    private static String flagsOf(int mode) {
        return mode == 0120000 ? "l" : (mode & 0111) != 0 ? "x" : "";
    }

    private static String flagOfEntry(String entry) {
        return entry != null && entry.length() > 40 ? entry.substring(40) : "";
    }

    private static byte[] node20(byte[] field) {
        return field.length == 20 ? field : Arrays.copyOf(field, 20);
    }

    private static String hex40(String entry) {
        return entry.substring(0, 40);
    }

    /** The file a merge follows from the name one side gave it to the name the other side still changes. */
    private static final class CopyMerge {
        String source;
        String copyRevHex;
        String sideParentHex;
    }

    /** What the merge commits: the tree merge result, completed with the files that were followed through copies and renames. */
    private static final class Plan {
        Map<String, String> m1;
        Map<String, String> m2;
        Map<String, String> ancestorManifest;
        List<Map<String, String>> ancestors = new ArrayList<>();
        Map<String, byte[]> changed;
        Map<String, Integer> modes;
        Set<String> removed;
        final Map<String, CopyMerge> copyMerges = new java.util.LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int modeOf(String flags) {
        return "l".equals(flags) ? 0120000 : "x".equals(flags) ? 0755 : 0644;
    }

    /**
     * {@code copies.mergecopies}: a file that a side copied or renamed and the other side changed under its old name gets the change
     * merged into the new name, as {@code hg merge} does. Directory renames are not followed: a file the other side added to a
     * directory that was renamed away is reported as a conflict, because the original would move it to the new directory.
     */
    private Plan plan(Revlog changelog, ChangesetGraph graph, byte[] node1, byte[] node2, int rev1, int rev2, byte[] baseNode,
                      TreeMergeCommand.TreeMergeResult merged) throws IOException {
        Revlog manifestRevlog = repository.getManifestRevlog();
        MergeCommand helper = new MergeCommand(repository);
        Plan plan = new Plan();
        plan.m1 = repository.getManifestAtCommit(node1);
        plan.m2 = repository.getManifestAtCommit(node2);
        plan.ancestorManifest = baseNode != null
                ? repository.getManifestAtCommit(baseNode)
                : helper.getMergeBase(changelog, manifestRevlog, rev1, rev2, 0).manifest;
        // the manifests of the heads of the common ancestors, which the original's removal filter looks at
        for (int ancestor : graph.getLcaCandidates(rev1, rev2)) {
            plan.ancestors.add(repository.getManifestAtCommit(changelog.getIndexRecord(ancestor).getNodeId()));
        }
        if (plan.ancestors.isEmpty()) {
            plan.ancestors.add(Map.of());
        }
        plan.changed = new java.util.LinkedHashMap<>(merged.getChangedFiles());
        plan.modes = new java.util.LinkedHashMap<>(merged.getChangedModes());
        plan.removed = new LinkedHashSet<>(merged.getRemovedFiles());
        Set<String> conflicts = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        conflicts.addAll(merged.getConflicts());

        MergeCopies tracer = new MergeCopies(repository);
        for (int side = 1; side <= 2; side++) {
            Map<String, String> mine = side == 1 ? plan.m1 : plan.m2;
            Map<String, String> other = side == 1 ? plan.m2 : plan.m1;
            Map<String, String> copies = tracer.trace(mine, plan.ancestorManifest);
            for (Map.Entry<String, String> copy : copies.entrySet()) {
                String dst = copy.getKey();
                String src = copy.getValue();
                boolean renamed = !mine.containsKey(src);
                String baseSrc = plan.ancestorManifest.get(src);
                String otherSrc = other.get(src);
                if (renamed && !dirOf(src).equals(dirOf(dst)) && !dirOf(src).isEmpty()) {
                    for (String added : other.keySet()) {
                        if (!plan.ancestorManifest.containsKey(added) && !mine.containsKey(added)
                                && added.startsWith(dirOf(src) + "/")) {
                            conflicts.add(added);
                        }
                    }
                }
                if (otherSrc == null || otherSrc.equals(baseSrc) || other.containsKey(dst)) {
                    continue; // the other side deleted or did not change the source: the file-by-file merge is what the original does
                }
                byte[] baseContent = helper.getFileRevisionContent(src, baseSrc);
                byte[] first = helper.getFileRevisionContent(side == 1 ? dst : src, side == 1 ? plan.m1.get(dst) : plan.m1.get(src));
                byte[] second = helper.getFileRevisionContent(side == 1 ? src : dst, side == 1 ? plan.m2.get(src) : plan.m2.get(dst));
                Merge3.MergeResult result = Merge3.merge(helper.readLines(baseContent), helper.readLines(first), helper.readLines(second));
                if (renamed && merged.getChangeDeleteConflicts().contains(src)) {
                    conflicts.remove(src); // the change of the old name is merged into the new one, not lost
                }
                if (result.isConflicted()) {
                    conflicts.add(dst);
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (String line : result.getMergedLines()) {
                    text.append(line).append('\n');
                }
                String dstFlag = flagOfEntry(mine.get(dst));
                String flags = dstFlag.equals(flagOfEntry(baseSrc)) ? flagOfEntry(otherSrc) : dstFlag;
                CopyMerge followed = new CopyMerge();
                followed.source = src;
                followed.copyRevHex = hex40(side == 1 ? plan.m2.get(src) : plan.m1.get(src));
                followed.sideParentHex = hex40(mine.get(dst));
                plan.copyMerges.put(dst, followed);
                plan.changed.put(dst, text.toString().getBytes(StandardCharsets.UTF_8));
                plan.modes.put(dst, modeOf(flags));
                if (side == 2 && renamed) {
                    plan.removed.add(src); // the second parent moved the file: the first parent's version goes with the move
                }
            }
        }
        plan.conflicts = new ArrayList<>(conflicts);
        return plan;
    }

    /** The file revisions, manifest and changeset of the merge, as {@code commit._prepare_files} and {@code commitctx} write them. */
    private byte[] write(StoreRollback transaction, Revlog changelog, ChangesetGraph graph, byte[] node1, byte[] node2, int rev1,
                         int rev2, Plan plan, String description, String[] dateParts) throws IOException {
        Revlog manifestRevlog = repository.getManifestRevlog();
        File manifestIndex = new File(repository.getStoreDir(), "00manifest.i");
        File manifestData = new File(repository.getStoreDir(), "00manifest.d");
        File changelogIndex = new File(repository.getStoreDir(), "00changelog.i");
        File changelogData = new File(repository.getStoreDir(), "00changelog.d");
        transaction.trackRevlog(changelogIndex, changelogData);
        transaction.trackRevlog(manifestIndex, manifestData);
        transaction.trackFile(new File(repository.getStoreDir(), "fncache"));
        transaction.trackFile(new File(repository.getStoreDir(), "phaseroots"));

        ChangesetRewrite.Entry entry1 = ChangesetRewrite.read(changelog, rev1);
        ChangesetRewrite.Entry entry2 = ChangesetRewrite.read(changelog, rev2);
        MergeCommand helper = new MergeCommand(repository);
        Map<String, String> m1 = plan.m1;
        Map<String, String> m2 = plan.m2;
        Map<String, String> ancestorManifest = plan.ancestorManifest;
        List<Map<String, String>> ancestorManifests = plan.ancestors;
        Map<String, String> sdCopiesP1 = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Map<String, String> sdCopiesP2 = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);

        int linkrev = changelog.getRevisionCount();
        Map<String, String> manifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        manifest.putAll(m1);
        Set<String> added = new LinkedHashSet<>();
        Set<String> mergedFiles = new LinkedHashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();

        for (String path : new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR) {{ addAll(plan.changed.keySet()); }}) {
            byte[] content = plan.changed.get(path);
            String flags = flagsOf(plan.modes.getOrDefault(path, 0644));
            String old1 = m1.get(path);
            String old2 = m2.get(path);
            if (old1 != null && flags.equals(flagOfEntry(old1)) && Arrays.equals(content, helper.getFileRevisionContent(path, old1))) {
                continue; // the merge leaves the file as the first parent has it: not part of the commit
            }
            File index = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File data = new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d");
            transaction.trackRevlog(index, data);
            Revlog filelog = repository.getRevlog(index, data);
            CopyMerge copyMerge = plan.copyMerges.get(path);
            if (copyMerge != null) {
                // commit._filecommit for a file the merge copied from another name: the copy data stands for the first parent, so the
                // file revision keeps the revision of the side that made the copy as its only real parent (stored as the second)
                Map<String, String> meta = new java.util.LinkedHashMap<>();
                meta.put("copy", copyMerge.source);
                meta.put("copyrev", copyMerge.copyRevHex);
                byte[] sideParent = NodeIdUtil.fromHex(copyMerge.sideParentHex);
                byte[] zero = new byte[20];
                byte[] candidate = NodeIdUtil.computeNodeId(Revlog.wrapMetadata(content, meta), zero, sideParent);
                byte[] copied = node20(filelog.findRevision(candidate) >= 0 ? candidate
                        : filelog.appendRevision(content, meta, -1, filelog.findRevision(sideParent), zero, sideParent, linkrev));
                manifest.put(path, NodeIdUtil.toHex(copied) + flags);
                touched.add(path);
                (m1.containsKey(copyMerge.source) ? sdCopiesP1 : sdCopiesP2).put(path, copyMerge.source);
                continue;
            }
            byte[] fparent1 = old1 == null ? new byte[20] : NodeIdUtil.fromHex(hex40(old1));
            byte[] fparent2 = old2 == null ? new byte[20] : NodeIdUtil.fromHex(hex40(old2));
            boolean addedByMerge = old1 == null && old2 == null;

            // commit._filecommit, for a working copy file that the merge took from the other side ("filenode-source: other")
            boolean takenFromP2 = old2 != null && flags.equals(flagOfEntry(old2))
                    && Arrays.equals(content, helper.getFileRevisionContent(path, old2))
                    && (old1 == null || old1.equals(ancestorManifest.get(path)));
            byte[] fnode;
            String touchedAs = null;
            if (addedByMerge) {
                touchedAs = "added";
            }
            boolean flagsOnlyDiffer;
            if (takenFromP2) {
                fnode = fparent2;
                flagsOnlyDiffer = old1 != null && !flagOfEntry(old1).equals(flags);
                if (flagsOnlyDiffer) {
                    touchedAs = "modified";
                }
            } else {
                if (NodeIdUtil.isAllZero(fparent1)) {
                    fparent1 = fparent2;
                    fparent2 = new byte[20];
                } else if (!NodeIdUtil.isAllZero(fparent2)) {
                    // is one parent an ancestor of the other?
                    int r1 = filelog.findRevision(fparent1);
                    int r2 = filelog.findRevision(fparent2);
                    java.util.Set<Integer> heads = new ChangesetGraph(filelog).getLcaCandidates(r1, r2);
                    if (heads.contains(r1)) {
                        fparent1 = fparent2;
                        fparent2 = new byte[20];
                    } else if (heads.contains(r2)) {
                        fparent2 = new byte[20];
                    }
                }
                boolean changedText = !NodeIdUtil.isAllZero(fparent2) || NodeIdUtil.isAllZero(fparent1)
                        || !Arrays.equals(content, helper.getFileRevisionContent(path, NodeIdUtil.toHex(fparent1)));
                if (changedText) {
                    if (touchedAs == null) {
                        touchedAs = NodeIdUtil.isAllZero(fparent2) ? "modified" : "merged";
                    }
                    int fp1Rev = NodeIdUtil.isAllZero(fparent1) ? -1 : filelog.findRevision(fparent1);
                    int fp2Rev = NodeIdUtil.isAllZero(fparent2) ? -1 : filelog.findRevision(fparent2);
                    byte[] candidate = NodeIdUtil.computeNodeId(Revlog.wrapMetadata(content, null), fparent1, fparent2);
                    fnode = node20(filelog.findRevision(candidate) >= 0 ? candidate
                            : filelog.appendRevision(content, (Map<String, String>) null, fp1Rev, fp2Rev, fparent1, fparent2, linkrev));
                } else {
                    fnode = fparent1;
                    if (old1 != null && !flagOfEntry(old1).equals(flags)) {
                        touchedAs = "modified";
                    }
                }
            }
            manifest.put(path, NodeIdUtil.toHex(fnode) + flags);
            if ("added".equals(touchedAs)) {
                added.add(path);
            } else if ("merged".equals(touchedAs)) {
                mergedFiles.add(path);
            } else if (touchedAs != null) {
                touched.add(path);
            }
        }

        // the files the merge deletes; only those that are not simply a deletion made on one side (`metadata.get_removal_filter`)
        for (String path : plan.removed) {
            if (manifest.remove(path) == null) {
                continue;
            }
            if (!deletionFromParent(path, m1, m2, ancestorManifests)) {
                removed.add(path);
            }
        }

        Set<String> filesSet = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        filesSet.addAll(added);
        filesSet.addAll(mergedFiles);
        filesSet.addAll(touched);
        filesSet.addAll(removed);

        byte[] manifestNode1 = NodeIdUtil.fromHex(entry1.manifestHex);
        byte[] manifestNode2 = NodeIdUtil.fromHex(entry2.manifestHex);
        byte[] manifestNode;
        if (filesSet.isEmpty() && manifest.equals(new TreeMap<>(m1))) {
            manifestNode = manifestNode1; // `reusing manifest from p1 (listed files actually unchanged)`
        } else {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                text.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
            }
            manifestNode = node20(manifestRevlog.appendRevision(text.toString().getBytes(StandardCharsets.UTF_8),
                    manifestRevlog.findRevision(manifestNode1), manifestRevlog.findRevision(manifestNode2),
                    manifestNode1, manifestNode2, linkrev));
        }

        ChangesetRewrite.Entry entry = new ChangesetRewrite.Entry();
        entry.manifestHex = NodeIdUtil.toHex(manifestNode);
        entry.user = user.trim();
        entry.time = dateParts[0];
        entry.offset = dateParts[1];
        String branchName = branch != null ? branch : entry1.extra.getOrDefault("branch", "default");
        if (!"default".equals(branchName) && !branchName.isEmpty()) {
            entry.extra.put("branch", branchName);
        }
        entry.extra.putAll(extra);
        entry.files = new ArrayList<>(filesSet);
        entry.message = description;

        byte[] sidedata = null;
        if (repository.isSidedataCopies()) {
            byte[] sdFiles = ChangingFiles.encode(added, removed, mergedFiles, Set.of(), touched, sdCopiesP1, sdCopiesP2);
            if (sdFiles.length > 0) {
                sidedata = io.github.search5.hg4j.storage.SidedataCodec.serialize(
                        Map.of(io.github.search5.hg4j.storage.SidedataCodec.SD_FILES, sdFiles));
            }
        }
        // changelog.add: a changeset that already exists is not added again
        byte[] existing = node20(NodeIdUtil.computeNodeId(entry.toBytes(), node1, node2));
        if (changelog.findRevision(existing) >= 0) {
            return existing;
        }
        byte[] node = node20(ChangesetRewrite.append(changelog, entry, node1, node2, sidedata));

        // phases.registernew: a new changeset is draft unless a parent is secret
        PhaseRoots phaseRoots = repository.getPhaseRoots();
        NodeId id = new NodeId(node);
        if (phaseRoots.getPhase(id, changelog) == PhaseRoots.Phase.PUBLIC) {
            phaseRoots.setPhase(id, PhaseRoots.Phase.DRAFT, changelog);
        }
        return node;
    }

    /**
     * {@code metadata.get_removal_filter}: whether the absence of {@code path} in the merge is a deletion that came from a parent (the
     * other parent still has the file the way every common ancestor has it) rather than a deletion made by the merge itself.
     */
    private static boolean deletionFromParent(String path, Map<String, String> m1, Map<String, String> m2,
                                              List<Map<String, String>> ancestors) {
        if (m1.containsKey(path)) {
            return !m2.containsKey(path) && ancestors.stream().allMatch(ma -> m1.get(path).equals(ma.get(path)));
        }
        if (m2.containsKey(path)) {
            return ancestors.stream().allMatch(ma -> m2.get(path).equals(ma.get(path)));
        }
        return true;
    }
}
