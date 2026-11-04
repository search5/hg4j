package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.util.SafeFileIO;

/**
 * Adds untracked files to the repository tracking list.
 */
public class AddCommand {

    private final HgRepository repository;
    private final List<String> files = new ArrayList<>();

    /**
     * Creates an add command for the given repository. Nothing is read or written until {@link #call()}.
     *
     * @param repository the repository to operate on; must not be {@code null}
     */
    public AddCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Queues one exact path to add. Backslashes are converted to {@code /}; a {@code null} or empty path is silently ignored.
     * When at least one path was queued with this method, {@link #call()} adds exactly these paths and ignores the pattern selection.
     *
     * @param file repository-relative path of an existing file or symlink; may be {@code null} (ignored)
     * @return {@code this}
     */
    public AddCommand addFile(String file) {
        if (file != null && !file.isEmpty()) {
            files.add(file.replace('\\', '/'));
        }
        return this;
    }

    private FileSelection selection = new FileSelection();
    private boolean subrepos;
    private boolean explicitOnly;
    private final List<String> subrepoMessages = new ArrayList<>();

    /**
     * {@code hg add -S}: also add the unknown files of the hg subrepositories. Without it only files that are named exactly reach into
     * a subrepository ({@code cmdutil.add}: {@code sub.add(..., explicitonly=True)}).
     */
    public AddCommand setSubrepos(boolean subrepos) {
        this.subrepos = subrepos;
        return this;
    }

    /** What {@code hg add} reports about subrepositories (for now: {@code skipping missing subrepository}). */
    public List<String> getSubrepoMessages() {
        return subrepoMessages;
    }

    /** The selection a parent hands to a subrepository: its matcher seen from inside, adding only the files it names when asked. */
    AddCommand forSubrepo(FileSelection inside, boolean subrepos, boolean explicitOnly) {
        this.selection = inside;
        this.subrepos = subrepos;
        this.explicitOnly = explicitOnly;
        return this;
    }

    /** {@code hg add [FILE]...}: the files to add, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}). */
    public AddCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code hg add -I}. */
    public AddCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code hg add -X}. */
    public AddCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public AddCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /**
     * The files {@code hg add} would add for the selection: the unknown files that match, files marked removed that are back on the
     * disk, and an ignored file only when a pattern names it exactly.
     */
    private List<String> selectedFiles() throws IOException {
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        io.github.search5.hg4j.fileset.FileStatus status =
                io.github.search5.hg4j.fileset.RepositoryFilesetContext.workingDirectoryStatus(repository, matcher, true, false, true);
        List<String> selected = new ArrayList<>(status.unknown());
        for (String ignored : status.ignored()) {
            if (matcher.exact(ignored)) {
                selected.add(ignored);
            }
        }
        for (String removed : status.removed()) {
            if (new File(repository.getDirectory(), removed).exists()) {
                selected.add(removed);
            }
        }
        if (explicitOnly) {
            selected.removeIf(f -> !matcher.exact(f));
        }
        java.util.Collections.sort(selected);
        return selected;
    }

    /**
     * Adds the queued paths, or the files chosen by the pattern selection, or (with neither) every untracked file of the working copy,
     * to the dirstate as added ({@code hg add}). Then, for each hg subrepository, adds inside it too (see {@link #setSubrepos(boolean)}).
     * <p>Takes the working copy lock for the whole operation and rewrites the dirstate. A path that already has a dirstate entry is not
     * added again: if it was in the removed state it is restored as a normal entry, if it was already added it is left untouched.
     *
     * @throws IOException if the working copy cannot be scanned or the dirstate cannot be written
     * @throws io.github.search5.hg4j.errors.HgValidationException if a queued path does not exist or is neither a regular file nor a symlink
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public void call() throws IOException, HgLockException {
        try (HgLock wlock = repository.lockWorkingCopy()) {
            // commands.add: `with repo.wlock(), repo.dirstate.changing_files(repo)`
            repository.changingFiles(() -> {
                addTracked();
                addInSubrepos();
                return null;
            });
        }
    }

    private void addTracked() throws IOException {
        {
            Dirstate dirstate = repository.getDirstate();

            List<String> filesToAdd = new ArrayList<>(files);
            if (filesToAdd.isEmpty() && !selection.isEmpty()) {
                filesToAdd.addAll(selectedFiles());
            } else if (filesToAdd.isEmpty()) {
                // Scan for all untracked files
                List<String> allFiles = repository.scanWorkingCopy();
                for (String relPath : allFiles) {
                    if (!dirstate.getEntries().containsKey(relPath)) {
                        filesToAdd.add(relPath);
                    }
                }
            }

            for (String relPath : filesToAdd) {
                File diskFile = new File(repository.getDirectory(), relPath);
                boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                // A symlink is tracked as-is even when its target is missing (dangling) or not
                // a plain file — real hg accepts it (verified live: `ln -s missing.txt l.txt;
                // hg add` -> "A l.txt"). Only reject non-symlink paths that genuinely aren't a
                // regular file.
                if (!isSymlink && (!diskFile.exists() || !diskFile.isFile())) {
                    throw new HgValidationException("File not found or is not a standard file: " + relPath);
                }

                // A path that already carries a dirstate entry was previously tracked (most
                // commonly: removed via ForgetCommand/RemoveCommand, which leave a state 'r'
                // entry behind for the next commit). Verified live against real hg 7.2:
                // explicitly re-`add`ing such a path does NOT reset it to a fresh 'a' (added)
                // entry -- it restores it via dirstate "normallookup", landing back on state
                // 'n' with an ambiguous stat (mode/size/time all sentinel, forcing the next
                // status/commit to fall back to a real content comparison rather than trusting
                // now-stale cached stat info). This matters beyond bit-for-bit fidelity: if the
                // path were instead marked 'a' here (as if brand new), CommitCommand's own 'a'
                // handling has no prior filelog revision to link against, so the file's history
                // would be silently severed (`hg log --follow` afterwards would stop at the
                // re-add instead of continuing into the pre-forget/remove commits) -- confirmed
                // against real hg, which DOES keep the new filelog revision's parent pointed at
                // the pre-forget/remove revision. An entry already in state 'a' (a pending,
                // uncommitted add) is untouched -- real hg's own `hg add` is a silent no-op on
                // an already-added path ("already tracked!").
                Dirstate.Entry existing = dirstate.getEntries().get(relPath);
                if (existing != null) {
                    if (existing.getState() != 'a') {
                        dirstate.addEntry(relPath, new Dirstate.Entry('n', 0, -1, Dirstate.Entry.AMBIGUOUS_TIME));
                    }
                    continue;
                }

                boolean executable = !isSymlink && diskFile.canExecute();
                int mode = executable ? 0755 : 0644;
                // A symlink's tracked "size" is the length of its own target path string
                // (lstat semantics, matching what CommitCommand records for the filelog
                // content), not File.length(), which follows the link (and returns 0 for a
                // dangling target).
                int size = isSymlink
                        ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                        : (int) diskFile.length();
                long time = SafeFileIO.lastModifiedSeconds(diskFile);

                Dirstate.Entry entry = new Dirstate.Entry('a', mode, size, time);
                dirstate.addEntry(relPath, entry);
            }

            repository.writeDirstate(dirstate);
        }
    }

    /**
     * {@code cmdutil.add}: for each subrepository (sorted) the parent's matcher is handed down, and the subrepository adds under its
     * own working copy lock. A subrepository that is not on the disk is skipped with a message. Done while the parent's lock is held.
     */
    private void addInSubrepos() throws IOException, HgLockException {
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        for (SubrepoRecursion.Sub sub : SubrepoRecursion.hgSubrepos(repository)) {
            if (sub.repository() == null) {
                subrepoMessages.add(SubrepoRecursion.missingMessage(sub.path()));
                continue;
            }
            io.github.search5.hg4j.match.Matcher inside = SubrepoRecursion.subdir(sub.path(), matcher);
            if (!subrepos && inside.files().isEmpty()) {
                continue; // explicit-only with no name inside: nothing could be added there
            }
            try (HgRepository subRepository = sub.repository()) {
                AddCommand nested = new AddCommand(subRepository).forSubrepo(FileSelection.of(inside), subrepos, !subrepos);
                nested.call();
                for (String message : nested.subrepoMessages) {
                    subrepoMessages.add(message);
                }
            }
        }
    }
}
