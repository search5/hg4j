package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.IOException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command corresponding to {@code hg forget} — stops tracking a file without touching
 * it on disk (unlike {@link RemoveCommand}, which deletes the working copy file).
 */
public class ForgetCommand {
    private final HgRepository repository;
    private String file;

    /**
     * Creates a forget command for the given repository.
     *
     * @param repository the repository to operate on; must not be {@code null}
     */
    public ForgetCommand(HgRepository repository) {
        this.repository = repository;
    }


    private FileSelection selection = new FileSelection();
    private boolean explicitOnly;

    /** The selection a parent hands to a subrepository: only the files it names exactly are forgotten there ({@code explicitonly=True}). */
    ForgetCommand forSubrepo(FileSelection inside) {
        this.selection = inside;
        this.explicitOnly = true;
        return this;
    }
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** The files to work on, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}); a bare name is relative to the current directory. */
    public ForgetCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code -I}: only files matching these patterns (globs by default). */
    public ForgetCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code -X}: not the files matching these patterns (globs by default). */
    public ForgetCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public ForgetCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** What the last {@link #callSelected()} left undone, in Mercurial's words. */
    public java.util.List<String> getWarnings() {
        return warnings;
    }

    /**
     * {@code hg forget [FILE]...}: stops tracking the tracked files the patterns select (a file added but not committed becomes
     * unknown, a committed one is marked removed; the files stay on the disk). Needs at least one pattern.
     *
     * @return the forgotten files
     */
    public java.util.List<String> callSelected() throws IOException, HgLockException {
        if (selection.patternCount() == 0) {
            throw new HgValidationException("no files specified");
        }
        warnings.clear();
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        java.util.List<String> forgotten = new java.util.ArrayList<>();
        try (HgLock wlock = repository.lockWorkingCopy()) {
            // commands.forget: `with repo.wlock(), repo.dirstate.changing_files(repo)`
            repository.changingFiles(() -> {
            Dirstate dirstate = repository.getDirstate();
            for (java.util.Map.Entry<String, Dirstate.Entry> e : new java.util.TreeMap<>(dirstate.getEntries()).entrySet()) {
                String path = e.getKey();
                if (e.getValue().getState() == 'r' || !matcher.matches(path) || explicitOnly && !matcher.exact(path)) {
                    continue;
                }
                if (e.getValue().getState() == 'a') {
                    dirstate.removeEntry(path);
                } else {
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                }
                forgotten.add(path);
            }
            repository.writeDirstate(dirstate);
            forgetInSubrepos(matcher, forgotten);
            return null;
            });
        }
        return forgotten;
    }

    /**
     * {@code cmdutil.forget}: each subrepository forgets the files the parent's matcher names inside it, under its own working copy
     * lock; what it forgot is reported with the subrepository's path in front. A subrepository that is not on the disk is skipped.
     */
    private void forgetInSubrepos(io.github.search5.hg4j.match.Matcher matcher, java.util.List<String> forgotten)
            throws IOException, HgLockException {
        for (SubrepoRecursion.Sub sub : SubrepoRecursion.hgSubrepos(repository)) {
            if (sub.repository() == null) {
                warnings.add(SubrepoRecursion.missingMessage(sub.path()));
                continue;
            }
            io.github.search5.hg4j.match.Matcher inside = SubrepoRecursion.subdir(sub.path(), matcher);
            if (inside.files().isEmpty()) {
                continue;
            }
            try (HgRepository subRepository = sub.repository()) {
                ForgetCommand nested = new ForgetCommand(subRepository).forSubrepo(FileSelection.of(inside));
                for (String file : nested.callSelected()) {
                    forgotten.add(SubrepoRecursion.display(sub.path(), file));
                }
                warnings.addAll(nested.getWarnings());
            }
        }
    }

    /**
     * Sets the single file for {@link #call()}. Required there.
     *
     * @param file repository-relative path of the tracked file
     * @return {@code this}
     */
    public ForgetCommand setFile(String file) {
        this.file = file;
        return this;
    }

    /**
     * Stops tracking the single file set with {@link #setFile(String)} ({@code hg forget}). A file that was only added is dropped from the dirstate; a committed file is marked removed for the next commit. The file on disk is never touched. Takes the working copy lock and writes the dirstate.
     *
     * @throws IllegalStateException if no file was set
     * @throws io.github.search5.hg4j.errors.HgValidationException if the file is not tracked
     * @throws IOException if the dirstate cannot be written
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public void call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }
        try (HgLock wlock = repository.lockWorkingCopy()) {
            repository.changingFiles(() -> {
            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);
            if (entry == null) {
                throw new HgValidationException("File is not tracked: " + file);
            }

            if (entry.getState() == 'a') {
                // 아직 커밋된 적 없는 파일 — 그냥 추적을 완전히 해제한다.
                dirstate.removeEntry(file);
            } else {
                // 이미 커밋된 파일 — 다음 커밋 시 제거로 기록하되 작업 사본은 그대로 둔다.
                dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
            }
            repository.writeDirstate(dirstate);
            return null;
            });
        }
    }
}
