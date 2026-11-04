package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import io.github.search5.hg4j.errors.HgValidationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Porcelain command to revert changes to files in the working directory.
 *
 * <p>Verified live against real {@code hg} 7.2 (2026-09-05, backlog #39 wave 4):
 * <ul>
 *   <li>Reverting a file that is currently "modified" (its on-disk content differs from the
 *   current parent's committed content) backs up the pre-revert on-disk bytes to {@code
 *   <file>.orig} before overwriting -- real hg's {@code cmdutil.revert()} default ({@code
 *   ui.origbackuppath} unset). A file that is already clean gets no {@code .orig}, even when
 *   {@code -r} targets a revision whose content differs from what's on disk -- the backup is
 *   about protecting uncommitted work, not about the revert's target.</li>
 *   <li>Reverting a file that was {@code add}ed but never committed just <em>untracks</em> it
 *   (dirstate entry removed) -- the on-disk content is left exactly as-is, now showing as an
 *   untracked {@code ?} file. This class previously deleted the file outright here, a real
 *   data-loss bug (confirmed live: {@code hg revert} on a freshly {@code hg add}ed file keeps
 *   its content on disk).</li>
 *   <li>Reverting a file that <em>was</em> committed at some point but does not exist in the
 *   target revision (parent1 by default, or an explicit {@code -r} target) deletes it from disk
 *   and marks it {@code r} (removed) in dirstate -- distinct from the "never committed" case
 *   above, and confirmed live against real hg (shows as {@code R <file>} in {@code hg status}
 *   afterward, not silently untracked).</li>
 * </ul>
 */
public class RevertCommand {

    private final HgRepository repository;
    private String file;
    private String revision;

    /**
     * Creates the command.
     * @param repository the repository to revert in; must not be {@code null}
     */
    public RevertCommand(HgRepository repository) {
        this.repository = repository;
    }


    private FileSelection selection = new FileSelection();
    private final java.util.List<String> subrepoMessages = new java.util.ArrayList<>();

    /** What the last {@link #callSelected()} said about subrepositories ({@code reverting subrepo NAME}). */
    public java.util.List<String> getSubrepoMessages() {
        return subrepoMessages;
    }
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** The files to work on, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}); a bare name is relative to the current directory. */
    public RevertCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code -I}: only files matching these patterns (globs by default). */
    public RevertCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code -X}: not the files matching these patterns (globs by default). */
    public RevertCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public RevertCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** What the last {@link #callSelected()} left undone, in Mercurial's words. */
    public java.util.List<String> getWarnings() {
        return warnings;
    }
    private boolean all;

    /** {@code hg revert --all}: every changed file. */
    public RevertCommand setAll(boolean all) {
        this.all = all;
        return this;
    }

    /** The hint `commands.revert` gives when nothing says which files to revert: it depends on the merge, the revision and local changes. */
    private String noFilesHint() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        byte[] p2 = dirstate.getParent2();
        if (p2 != null && !NodeIdUtil.isAllZero(p2)) {
            return "uncommitted merge, use --all to discard all changes, or 'hg update -C .' to abort the merge";
        }
        Status status = new StatusCommand(repository).call();
        boolean dirty = !status.getModified().isEmpty() || !status.getAdded().isEmpty() || !status.getRemoved().isEmpty();
        boolean elsewhere = false;
        int rev = -1;
        if (revision != null && !revision.isEmpty()) {
            Revlog changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                    new File(repository.getStoreDir(), "00changelog.d"));
            byte[] node = NodeIdUtil.resolveRevision(changelog, revision);
            if (node != null) {
                rev = changelog.findRevision(node);
                elsewhere = !java.util.Arrays.equals(node, dirstate.getParent1());
            }
        }
        if (elsewhere) {
            return (dirty ? "uncommitted changes, use --all to discard all changes, or 'hg update " + rev + "' to update"
                    : "use --all to revert all files, or 'hg update " + rev + "' to update");
        }
        return dirty ? "uncommitted changes, use --all to discard all changes" : "use --all to revert all files";
    }

    /**
     * Reverts all files selected by the patterns, options and revision set on this command.
     * <p>
     * Without patterns, include options or {@link #setAll}, nothing is selected and the call fails. Takes the working
     * copy lock, reverts every modified, added, removed or deleted file that matches (see {@link #call()} for the
     * per-file behavior), and then reverts the subrepositories whose path matches. {@link #getWarnings()} and
     * {@link #getSubrepoMessages()} are reset at the start.
     * @return the repository-relative paths that were reverted, sorted
     * @throws io.github.search5.hg4j.errors.HgValidationException (an {@link IOException}) if nothing selects any
     *         file, if the working directory has an uncommitted merge and no revision was set, or if a matching
     *         subrepository does not exist in the target revision
     * @throws io.github.search5.hg4j.errors.HgRevisionNotFoundException (an {@link IOException}) if the revision
     *         cannot be resolved
     * @throws IOException if reading or writing fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public java.util.List<String> callSelected() throws IOException, HgLockException {
        if (selection.patternCount() == 0 && !all && selection.isEmpty()) {
            throw new HgValidationException("no files or directories specified\n(" + noFilesHint() + ")");
        }
        warnings.clear();
        subrepoMessages.clear();
        // commands.revert reads the parents and resolves the revision before any lock; `cmdutil.revert` then runs entirely under
        // `repo.wlock()`, the status it reads included
        byte[] secondParent = repository.getDirstate().getParent2();
        boolean noRevision = revision == null || revision.isEmpty();
        if (noRevision && secondParent != null && !NodeIdUtil.isAllZero(secondParent)) {
            throw new HgValidationException("uncommitted merge with no revision specified\n(use 'hg update' or see 'hg help revert')");
        }
        if (!noRevision) {
            Revlog changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                    new File(repository.getStoreDir(), "00changelog.d"));
            if (NodeIdUtil.resolveRevision(changelog, revision) == null) {
                throw new HgRevisionNotFoundException(revision);
            }
        }
        try (HgLock wlock = repository.lockWorkingCopy()) {
            io.github.search5.hg4j.match.Matcher matcher = selection.patternCount() == 0 && selection.isEmpty()
                    ? io.github.search5.hg4j.match.Matchers.always() : selection.matcher(repository, null);
            io.github.search5.hg4j.fileset.FileStatus status =
                    io.github.search5.hg4j.fileset.RepositoryFilesetContext.workingDirectoryStatus(repository, matcher, false, false, false);
            java.util.TreeSet<String> changed = new java.util.TreeSet<>();
            changed.addAll(status.modified());
            changed.addAll(status.added());
            changed.addAll(status.removed());
            changed.addAll(status.deleted());
            java.util.List<String> reverted = new java.util.ArrayList<>();
            for (String path : changed) {
                if (new RevertCommand(repository).setFile(path).setRevision(revision).call()) {
                    reverted.add(path);
                }
            }
            revertSubrepos(matcher);
            return reverted;
        }
    }

    /**
     * {@code cmdutil.revert}: the subrepositories whose own path the matcher selects are reverted in two steps
     * ({@code hgsubrepo.revert}): every changed file of the subrepository goes back to the revision the target's {@code .hgsubstate}
     * records (the old content is backed up), then the subrepository is updated to that revision. The subrepository takes its own
     * working copy lock for the first step; the second one is an update, which takes it again.
     */
    private void revertSubrepos(io.github.search5.hg4j.match.Matcher matcher) throws IOException, HgLockException {
        java.util.List<SubrepoRecursion.Sub> targets = new java.util.ArrayList<>();
        for (SubrepoRecursion.Sub sub : SubrepoRecursion.hgSubrepos(repository)) {
            if (matcher.matches(sub.path())) {
                targets.add(sub);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Dirstate dirstate = repository.getDirstate();
        byte[] target = dirstate.getParent1();
        if (revision != null && !revision.isEmpty()) {
            target = NodeIdUtil.resolveRevision(repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                    new File(repository.getStoreDir(), "00changelog.d")), revision);
        }
        String targetHex = NodeIdUtil.toHex(target);
        java.util.Map<String, io.github.search5.hg4j.submodule.HgSubrepoEntry> recorded = new java.util.HashMap<>();
        try {
            byte[] hgsub = new CatCommand(repository).setFile(".hgsub").setRevision(targetHex).call();
            byte[] hgsubstate = new CatCommand(repository).setFile(".hgsubstate").setRevision(targetHex).call();
            recorded = io.github.search5.hg4j.submodule.HgSubrepoParser.parseSubrepositories(hgsub, hgsubstate);
        } catch (IOException noSubrepos) {
            // the target has no .hgsub: every named subrepository is reported below
        }
        for (SubrepoRecursion.Sub sub : targets) {
            io.github.search5.hg4j.submodule.HgSubrepoEntry entry = recorded.get(sub.path());
            if (entry == null) {
                throw new HgValidationException("subrepository '" + sub.path() + "' does not exist in " + targetHex.substring(0, 12) + "!");
            }
            subrepoMessages.add("reverting subrepo " + sub.path() + "\n");
            if (sub.repository() != null) {
                try (HgRepository subRepository = sub.repository()) {
                    new RevertCommand(subRepository).setAll(true).setRevision(entry.getRevision()).callSelected();
                }
            }
            UpdateCommand.checkoutSubrepoEntry(repository.getDirectory(), entry);
        }
    }

    /**
     * Sets the single file for {@link #call()}.
     * @param file the repository-relative path with {@code /} separators; must be non-empty before {@link #call()}
     * @return {@code this}
     */
    public RevertCommand setFile(String file) {
        this.file = file;
        return this;
    }

    /**
     * Sets the revision to revert to.
     * @param revision a revision identifier resolved against the changelog (hg's {@code -r}); {@code null} or empty
     *         (the default) means the first parent of the working directory
     * @return {@code this}
     */
    public RevertCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Reverts the single file set by {@link #setFile} to its state in the target revision.
     * <p>
     * The target is the revision set by {@link #setRevision}, or the first dirstate parent when unset. Runs under the
     * working copy lock inside a dirstate files-changing context. A file that exists in the target is rewritten (a
     * modified file is first backed up to {@code <file>.orig}); a file added but never committed is only untracked and
     * kept on disk; a file missing from the target but committed earlier is deleted and marked removed.
     * @return {@code true} when the file was reverted
     * @throws IllegalStateException if no file was set
     * @throws io.github.search5.hg4j.errors.HgValidationException (an {@link IOException}) if the repository has no
     *         parent commit and the file is not an added file
     * @throws IOException if reading history or writing the file or dirstate fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public boolean call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        try (HgLock wlock = repository.lockWorkingCopy()) {
            // cmdutil.revert: `with repo.wlock(), repo.dirstate.changing_files(repo)`
            return repository.changingFiles(() -> {
            Dirstate dirstate = repository.getDirstate();
            byte[] parentNodeId = dirstate.getParent1();

            // Resolve target node ID (default to parent1 if not specified)
            byte[] targetNodeId = parentNodeId;
            if (revision != null && !revision.isEmpty()) {
                File clIdx = new File(repository.getStoreDir(), "00changelog.i");
                File clDat = new File(repository.getStoreDir(), "00changelog.d");
                Revlog changelog = repository.getRevlog(clIdx, clDat);
                targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
            }

            if (targetNodeId == null || NodeIdUtil.isAllZero(targetNodeId)) {
                // Empty repository (no commits yet): the only file that can legitimately be
                // reverted here is one that was `hg add`ed but never committed -- real hg keeps
                // its on-disk content untouched, only removing the dirstate "added" bookkeeping
                // (verified live: `hg revert` right after `hg add` in a brand-new repo leaves the
                // file on disk, now shown as untracked "?").
                Dirstate.Entry entry = dirstate.getEntries().get(file);
                if (entry != null && entry.getState() == 'a') {
                    dirstate.removeEntry(file);
                    repository.writeDirstate(dirstate);
                    return true;
                }
                throw new HgValidationException("Cannot revert file when parent commit is zero and file is not tracked.");
            }

            // Retrieve the historical version of this file
            byte[] targetContent = null;
            boolean tracked = false;
            int mode = 0644;

            try {
                CatCommand cat = new CatCommand(repository).setFile(file).setRevision(NodeIdUtil.toHex(targetNodeId));
                targetContent = cat.call();
                tracked = true;

                Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
                String nodeWithFlags = manifestMap.get(file);
                String flags = nodeWithFlags.substring(Math.min(40, nodeWithFlags.length()));
                if (flags.contains("l")) {
                    mode = 0120000;
                } else if (flags.contains("x")) {
                    mode = 0755;
                }
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("File not tracked at target revision")) {
                    // If file is not tracked at target commit, we delete it from disk and untrack
                } else {
                    throw e;
                }
            }

            File diskFile = new File(repository.getDirectory(), file);

            if (tracked) {
                backupIfModified(dirstate, diskFile);

                diskFile.getParentFile().mkdirs();
                if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                    Files.delete(diskFile.toPath());
                }
                if (mode == 0120000) {
                    String target = new String(targetContent, StandardCharsets.UTF_8).trim();
                    try {
                        Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                    } catch (Exception e) {
                        Files.write(diskFile.toPath(), targetContent);
                    }
                } else {
                    Files.write(diskFile.toPath(), targetContent);
                    boolean executable = (mode == 0755);
                    diskFile.setExecutable(executable, false);
                }

                // Real hg's own dirstate ambiguous-mtime sentinel (mercurial/dirstate.py; the
                // 32-bit "-1", i.e. 0xFFFFFFFF) rather than a freshly-stat'd real mtime: this
                // content was just fabricated by revert itself (from history), not genuinely
                // re-typed by a user at this instant, so a same-size revert to different content
                // (e.g. "v1\n" -> "v0\n") executed fast enough to land in the same wall-clock
                // second as the file's own previous recorded state is otherwise indistinguishable
                // from "unmodified" by a naive size+mtime dirstate check alone -- confirmed live
                // (2026-09-05, backlog #39 wave 4): reverting a.txt to an older same-length
                // revision produced a dirstate entry real hg's own `hg status` trusted as clean.
                // See ShelveCommand's matching fix/comment and StatusCommand's AMBIGUOUS_TIME
                // handling -- the sentinel makes every reader always re-verify by content,
                // eliminating the race outright.
                dirstate.addEntry(file, new Dirstate.Entry('n', mode, targetContent.length, 0xFFFFFFFFL));
            } else {
                Dirstate.Entry entry = dirstate.getEntries().get(file);
                if (entry == null) {
                    // Not tracked at all (e.g. an untracked "?" file) -- real hg leaves files
                    // unknown to Mercurial unaffected by revert; nothing to do here.
                } else if (entry.getState() == 'a') {
                    // Added but never committed, and the target revision doesn't have this path
                    // either -- just untrack, keep the on-disk content (see class javadoc).
                    dirstate.removeEntry(file);
                } else {
                    // Was committed at some point but the target revision doesn't have this path
                    // (e.g. it was added after the target revision, or already `hg remove`d) --
                    // real hg deletes it from disk and marks it removed, not merely untracked
                    // (verified live: `hg status` shows "R <file>" afterward).
                    if (diskFile.exists()) {
                        Files.delete(diskFile.toPath());
                    }
                    dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
                }
            }

            repository.writeDirstate(dirstate);
            return true;
            });
        }
    }

    /**
     * Backs up {@code diskFile}'s current bytes to {@code <file>.orig} if -- and only if -- the
     * file is currently "modified" relative to the working copy's own recorded parent (dirstate
     * size/mtime, falling back to a content comparison for the same ambiguous-mtime cases {@link
     * StatusCommand} itself guards against). A clean file never gets a backup, regardless of how
     * different the revert's target revision is (verified live against real hg 7.2).
     */
    private void backupIfModified(Dirstate dirstate, File diskFile) throws IOException {
        if (!diskFile.exists() && !Files.isSymbolicLink(diskFile.toPath())) {
            return;
        }
        Dirstate.Entry entry = dirstate.getEntries().get(file);
        if (entry == null || (entry.getState() != 'n' && entry.getState() != 'm')) {
            return;
        }
        boolean modified;
        if (entry.getState() == 'm') {
            modified = true;
        } else {
            Status status = new StatusCommand(repository).call();
            modified = status.getModified().contains(file);
        }
        if (!modified) {
            return;
        }
        File orig = new File(repository.getDirectory(), file + ".orig");
        orig.getParentFile().mkdirs();
        if (Files.isSymbolicLink(diskFile.toPath())) {
            Path linkTarget = Files.readSymbolicLink(diskFile.toPath());
            if (Files.exists(orig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(orig.toPath());
            }
            try {
                Files.createSymbolicLink(orig.toPath(), linkTarget);
            } catch (Exception e) {
                Files.copy(diskFile.toPath(), orig.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.copy(diskFile.toPath(), orig.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
