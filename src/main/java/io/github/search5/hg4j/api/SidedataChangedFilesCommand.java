package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.storage.SidedataCodec;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Reads a committed changeset's sidedata-backed file-changes record straight from the changelog
 * — the Java equivalent of real hg's {@code hg debugchangedfiles REV} (without {@code
 * --compute}, i.e. read stored data, don't recompute it). Answers "which paths did revision N
 * touch, and which destinations were copied from which parent-relative source" without walking
 * any ancestor history: the answer was already written into the changelog's revlog-v2 sidedata
 * block at commit time (real hg's {@code exp-copies-sidedata-changeset} requirement / {@code
 * SD_FILES} sidedata key), and this command just decodes it back out.
 *
 * <p>This is the committed-history counterpart to {@link CopyCommand}: {@code CopyCommand}
 * records a copy the user is about to commit (working copy / dirstate, {@code hg copy}); this
 * command instead reports what a revision that is already sitting in history recorded as copied,
 * for {@code hg log --follow}/{@code hg annotate}-style copy tracing that doesn't need to
 * recompute anything from file content.
 *
 * @apiNote Unlike most other porcelain commands, not exposed via a {@link Hg} facade method
 *     today -- construct it directly with a repository obtained from {@link Hg#open}.
 *
 * <p>Works only on a repository whose changelog was written with copies-sidedata support (which
 * itself requires the changelog-v2 revlog format). On a plain v1 repository — or a v2 repository
 * without the copies-sidedata requirement — every revision simply has no {@code SD_FILES}
 * sidedata and {@link #call()} returns {@link ChangingFiles#empty()}, matching real hg's own
 * behavior in that case.
 */
public final class SidedataChangedFilesCommand {
    private final HgRepository repository;
    private int revision = -1;

    /**
     * Creates a new command bound to the given repository.
     *
     * @param repository the repository whose changelog sidedata will be read
     */
    public SidedataChangedFilesCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * The changelog revision number to inspect (0-based, as reported by e.g. {@code hg log -r N}).
     *
     * @param revision the changelog revision number to inspect
     * @return this command, for chaining
     */
    public SidedataChangedFilesCommand setRevision(int revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Decodes and returns the sidedata-backed file-changes record for the configured revision.
     *
     * @return the decoded file-changes record, or {@link ChangingFiles#empty()} if the revision has
     *     no {@code SD_FILES} sidedata (e.g. a plain v1 repository, or a v2 repository without the
     *     copies-sidedata requirement)
     * @throws IOException if the changelog or its sidedata cannot be read
     */
    public ChangingFiles call() throws IOException {
        if (revision < 0) {
            throw new IllegalStateException("Revision must be set to a non-negative value before calling call()");
        }

        // Guards against a long-lived HgRepository handle serving a stale cached changelog-v2
        // revlog after an external process appended a revision -- see DescribeCommand#call()'s
        // javadoc for the full explanation. Especially relevant here since this command only ever
        // exists for changelog-v2+sidedata repositories, whose docket-based revlog would otherwise
        // silently miss external changes. Cheap no-op in the common (freshly-opened-per-call) case.
        repository.refreshIfChangedOnDisk();
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        if (revision >= changelog.getRevisionCount()) {
            throw new IllegalArgumentException("Revision " + revision + " does not exist (changelog has "
                    + changelog.getRevisionCount() + " revisions)");
        }

        Map<Integer, byte[]> sidedata = changelog.getSidedata(revision);
        byte[] rawFiles = sidedata.get(SidedataCodec.SD_FILES);
        return ChangingFiles.decode(rawFiles);
    }
}
