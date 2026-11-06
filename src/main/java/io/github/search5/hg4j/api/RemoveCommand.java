package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Porcelain command to untrack and remove files from the working directory and staging area.
 */
public class RemoveCommand {

    private final HgRepository repository;
    private String file;
    private boolean force = false;

    /**
     * Creates the command.
     * @param repository the repository to remove files from; must not be {@code null}
     */
    public RemoveCommand(HgRepository repository) {
        this.repository = repository;
    }


    private FileSelection selection = new FileSelection();
    private boolean subrepos;

    /**
     * {@code hg remove -S}: also remove the files of every hg subrepository the patterns reach. Without it only subrepositories that
     * are named (the path itself or a file inside it) are entered ({@code cmdutil.remove}).
     */
    public RemoveCommand setSubrepos(boolean subrepos) {
        this.subrepos = subrepos;
        return this;
    }

    /** The selection a parent hands to a subrepository: its matcher seen from inside. */
    RemoveCommand forSubrepo(FileSelection inside, boolean subrepos, boolean force) {
        this.selection = inside;
        this.subrepos = subrepos;
        this.force = force;
        return this;
    }
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** The files to work on, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}); a bare name is relative to the current directory. */
    public RemoveCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code -I}: only files matching these patterns (globs by default). */
    public RemoveCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code -X}: not the files matching these patterns (globs by default). */
    public RemoveCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public RemoveCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** What the last {@link #callSelected()} left undone, in Mercurial's words. */
    public java.util.List<String> getWarnings() {
        return warnings;
    }

    /**
     * {@code hg remove [FILE]...}: removes the tracked files the patterns select, from the disk and from tracking. A file that
     * cannot be removed safely (marked for add, modified) is left and reported in {@link #getWarnings()} unless forced; the
     * others are removed. Needs at least one pattern.
     *
     * @return the removed files
     */
    public java.util.List<String> callSelected() throws IOException, HgLockException {
        if (selection.patternCount() == 0) {
            throw new HgValidationException("no files specified");
        }
        warnings.clear();
        // commands.remove: `with repo.wlock()` around the whole of cmdutil.remove (the status it reads included); the
        // per-file locks below are the same thread's, so reentrant.
        try (HgLock wlock = repository.lockWorkingCopy()) {
            return repository.changingFiles(() -> {
            io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
            // cmdutil.remove reads `wctx.substate` before it removes anything: removing .hgsub must not hide the subrepositories
            java.util.List<SubrepoRecursion.Sub> subs = SubrepoRecursion.hgSubrepos(repository);
            Dirstate dirstate = repository.getDirstate();
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Dirstate.Entry> e : new java.util.TreeMap<>(dirstate.getEntries()).entrySet()) {
                if (e.getValue().getState() != 'r' && matcher.matches(e.getKey())) {
                    candidates.add(e.getKey());
                }
            }
            java.util.List<String> removed = new java.util.ArrayList<>();
            for (String path : candidates) {
                try {
                    if (new RemoveCommand(repository).setFile(path).setForce(force).removeOne()) {
                        removed.add(path);
                    }
                } catch (HgValidationException refused) {
                    warnings.add("not removing " + path + ": " + refused.getMessage());
                }
            }
            removeInSubrepos(subs, matcher, removed);
            return removed;
            });
        }
    }

    /**
     * {@code cmdutil.remove}: a subrepository is entered when {@code -S} was given, the pattern names it, or names a file inside it;
     * it removes under its own working copy lock. A subrepository that is not on the disk is reported and skipped.
     */
    private void removeInSubrepos(java.util.List<SubrepoRecursion.Sub> subs, io.github.search5.hg4j.match.Matcher matcher,
                                  java.util.List<String> removed) throws IOException, HgLockException {
        for (SubrepoRecursion.Sub sub : subs) {
            io.github.search5.hg4j.match.Matcher inside = SubrepoRecursion.subdir(sub.path(), matcher);
            if (!(subrepos || matcher.exact(sub.path()) || !inside.files().isEmpty())) {
                continue;
            }
            if (sub.repository() == null) {
                warnings.add(SubrepoRecursion.missingMessage(sub.path()));
                continue;
            }
            try (HgRepository subRepository = sub.repository()) {
                RemoveCommand nested = new RemoveCommand(subRepository).forSubrepo(FileSelection.of(inside), subrepos, force);
                for (String file : nested.callSelected()) {
                    removed.add(SubrepoRecursion.display(sub.path(), file));
                }
                warnings.addAll(nested.getWarnings());
            }
        }
    }

    /**
     * Sets the single file for {@link #call()}.
     * @param file the repository-relative path with {@code /} separators; must be set (non-empty) before
     *         {@link #call()}
     * @return {@code this}
     */
    public RemoveCommand setFile(String file) {
        this.file = file;
        return this;
    }

    /**
     * Sets whether files with uncommitted changes are removed anyway.
     * @param force {@code true} to remove added or modified files (like {@code hg remove -f}); the default
     *         {@code false} refuses them
     * @return {@code this}
     */
    public RemoveCommand setForce(boolean force) {
        this.force = force;
        return this;
    }


    /**
     * Removes the single file set by {@link #setFile}.
     * <p>
     * Takes the working copy lock and runs inside a dirstate files-changing context. A file added but not committed
     * is only forgotten (its disk file is kept); any other tracked file is deleted from disk and marked removed
     * ({@code r}) in the dirstate. Without {@link #setForce}, files that are added or modified are refused. "Modified" means that the
     * file on disk differs from the revision the first parent of the working copy has (checked by size and time, and by content when
     * those agree), not from the newest revision of the file in the store.
     * @return {@code true} when the file was removed
     * @throws IllegalStateException if no file was set
     * @throws io.github.search5.hg4j.errors.HgValidationException (an {@link IOException}) if the file is not tracked,
     *         or, without force, has uncommitted changes (added or modified)
     * @throws IOException if reading or writing the dirstate or deleting the file fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public boolean call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        try (HgLock wlock = repository.lockWorkingCopy()) {
            // commands.remove: `with repo.wlock(), repo.dirstate.changing_files(repo)`
            return repository.changingFiles(this::removeOne);
        }
    }

    /** The removal of {@link #file} itself, for a caller that already holds the lock and a {@code changing_files} context. */
    private boolean removeOne() throws IOException, HgLockException {
        {

                Dirstate dirstate = repository.getDirstate();
                Dirstate.Entry entry = dirstate.getEntries().get(file);

                if (entry == null) {
                    throw new HgValidationException("File is not tracked: " + file);
                }

                if (!force) {
                    char state = entry.getState();
                    if (state == 'a') {
                        throw new HgValidationException("File has uncommitted changes (added): " + file + ". Use force to remove.");
                    } else if (state == 'n' || state == 'm') {
                        File diskFile = new File(repository.getDirectory(), file);
                        // A symlink's own lstat size/mtime/content, not whatever it points at --
                        // File#isFile()/length()/lastModified() all follow the link, which
                        // compared the TARGET's size against the symlink's own tracked size and
                        // spuriously flagged an untouched symlink as modified whenever its target
                        // happened to be a different length (matches AddCommand/StatusCommand's
                        // already-established lstat-aware convention).
                        boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                        if (isSymlink || (diskFile.exists() && diskFile.isFile())) {
                            long diskSize = isSymlink
                                    ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                                    : diskFile.length();
                            long diskTime = SafeFileIO.lastModifiedSeconds(diskFile);
                            // An entry whose cached stat is ambiguous (real hg's own "unset"
                            // sentinel -- see Dirstate.Entry#isStatAmbiguous(), most commonly hit
                            // when the file was committed within the same wall-clock second as
                            // the dirstate write) can never be trusted via a raw size/mtime
                            // comparison: its sentinel size (-1) never equals a real on-disk size,
                            // which previously made EVERY such entry look permanently "modified"
                            // and made `remove` (without --force) wrongly refuse a genuinely
                            // untouched file -- confirmed live against a real hg-authored
                            // dirstate produced by an add+commit that landed in the same second.
                            boolean isDirty = !entry.isStatAmbiguous()
                                    && (entry.getSize() != diskSize || entry.getTime() != diskTime);
                            if (!isDirty) {
                                // Racy-hg check: content level comparison
                                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
                                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                                if (flIdx.exists()) {
                                    try {
                                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                                        // The revision to compare with is the one the working copy parent has, not the newest revision
                                        // of the file in the store: after an update to an older revision (another branch has a newer
                                        // one) a clean file would look modified.
                                        int parentRev = parentFileRevision(filelog, file);
                                        if (parentRev >= 0) {
                                            byte[] fileContent = isSymlink
                                                    ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8)
                                                    : Files.readAllBytes(diskFile.toPath());
                                            byte[] parentContent = io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().readContent(repository, file, filelog, parentRev);
                                            if (!Arrays.equals(fileContent, parentContent)) {
                                                isDirty = true;
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                            if (isDirty) {
                                throw new HgValidationException("File has uncommitted changes (modified): " + file + ". Use force to remove.");
                            }
                        }
                    }
                }

                File diskFile = new File(repository.getDirectory(), file);
                // File#exists() follows a symlink and returns false for a dangling target, which
                // silently skipped deleting the link itself -- it was marked removed in the
                // dirstate but physically left on disk.
                // An added file that is removed with force is only forgotten: real hg leaves the file on the disk (it shows as
                // unknown afterwards); everything else is deleted from the disk too.
                if (entry.getState() != 'a'
                        && (Files.isSymbolicLink(diskFile.toPath()) || (diskFile.exists() && diskFile.isFile()))) {
                    Files.delete(diskFile.toPath());
                }

                if (entry.getState() == 'a') {
                    // If it was newly added, we just untrack it completely
                    dirstate.removeEntry(file);
                } else {
                    // Mark as removed for the next commit
                    dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
                }

                repository.writeDirstate(dirstate);

                // Clean up crash backups on success

                return true;
        }
    }

    /**
     * The revision of {@code file}'s filelog that the first parent of the working copy has, or -1 if that changeset does not have the
     * file (a file added by a merge, or an empty working copy).
     */
    private int parentFileRevision(Revlog filelog, String file) throws IOException {
        byte[] parent = repository.getDirstate().getParent1();
        if (io.github.search5.hg4j.util.NodeIdUtil.isAllZero(parent)) {
            return -1;
        }
        String entry = repository.getManifestAtCommit(parent).get(file);
        if (entry == null) {
            return -1;
        }
        return filelog.findRevision(io.github.search5.hg4j.util.NodeIdUtil.fromHex(entry.substring(0, 40)));
    }
}
