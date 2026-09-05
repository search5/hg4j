package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
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

    public RevertCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RevertCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public RevertCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public boolean call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

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
            io.github.search5.hg4j.api.Status status = new StatusCommand(repository).call();
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
