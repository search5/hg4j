package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.dirstate.Dirstate;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

/**
 * Purge command (equivalent to git clean, and real hg's own {@code hg purge}/{@code hg clean}
 * extension command) for Mercurial repositories. Deletes all untracked, non-ignored files and
 * (by default) the empty directories left behind, matching real hg's own default ({@code hg help
 * purge}: "This means that purge will delete the following by default: - Unknown files... - Empty
 * directories..." -- {@code --dirs}/{@code --files} only ever *restrict* which of the two
 * categories get deleted, they don't opt either one in).
 *
 * <p>Two points worth calling out explicitly:
 * <ol>
 *   <li><b>Default directory removal:</b> {@link #setPurgeDirectories}'s backing field defaults to
 *   {@code true}, so a plain {@code new PurgeCommand(repo).call()} removes untracked empty
 *   directories, matching real hg's own default with zero flags needed.</li>
 *   <li><b>Symlink traversal:</b> directory-existence checks use {@link Files#isDirectory(Path,
 *   LinkOption...)} with {@code LinkOption.NOFOLLOW_LINKS}, so a working copy containing a symlink
 *   to a directory -- even one entirely outside the repository -- is never walked <em>through</em>
 *   the symlink; only the link itself is a candidate for deletion, never anything reachable through
 *   it. This matches real hg's own purge, which treats a directory symlink as a single opaque
 *   unknown entry. Likewise, {@link Files#exists(Path, LinkOption...)} is called with {@code
 *   NOFOLLOW_LINKS} so that a <em>broken</em> (dangling-target) symlink is still visible to the
 *   top-of-method existence guard and gets deleted -- real hg deletes broken symlinks too.</li>
 * </ol>
 * Also treats a declared subrepo path ({@code .hgsub}, via {@link
 * HgRepository#loadSubrepoPaths()} -- the same boundary {@link HgRepository#scanWorkingCopy()}
 * already applies) as an opaque boundary, never walked into: a checked-out subrepo's own files are
 * legitimately "untracked" from the parent dirstate's point of view, and purging them would destroy
 * the subrepo's own working copy.
 *
 * @apiNote Typically obtained via {@link Hg#purge()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class PurgeCommand {
    private final HgRepository repository;
    private boolean purgeDirectories = true;

    /**
     * Creates a command bound to the given repository, with directory purging enabled by default.
     *
     * @param repository the repository whose working copy is purged
     */
    public PurgeCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Controls whether empty untracked directories are deleted in addition to untracked files.
     *
     * @param purgeDirectories {@code true} to also delete empty untracked directories
     * @return this command, for chaining
     */
    public PurgeCommand setPurgeDirectories(boolean purgeDirectories) {
        this.purgeDirectories = purgeDirectories;
        return this;
    }

    /**
     * Executes the purge operation by scanning workspace and physically deleting untracked entities.
     *
     * @throws IOException if workspace scanning or deletion fails
     */
    public void call() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Set<String> trackedFiles = new HashSet<>(dirstate.getEntries().keySet());
        Set<String> subrepoPaths = repository.loadSubrepoPaths();

        File root = repository.getDirectory();
        purgePath(root.toPath(), trackedFiles, subrepoPaths);
    }

    private void purgePath(Path path, Set<String> trackedFiles, Set<String> subrepoPaths) throws IOException {
        // NOFOLLOW_LINKS: a dangling symlink must still be visible here (real hg deletes it), and
        // resolving through a symlink at all would defeat the isSymbolicLink() opaque-boundary
        // check just below for a *working* symlink.
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        // Ensure the metadata .hg directory is never traversed or deleted
        if (path.getFileName() != null && path.getFileName().toString().equals(".hg")) {
            return;
        }

        String rel = repository.getDirectory().toPath().relativize(path).toString().replace('\\', '/');
        if (!rel.isEmpty() && subrepoPaths.contains(rel)) {
            // Declared subrepo boundary -- its contents belong to the subrepo's own dirstate,
            // never to the parent's (same rule HgRepository#scanWorkingCopy() applies).
            return;
        }

        // A symlink -- whether to a file, to a directory, or dangling -- is always treated as a
        // single opaque leaf, exactly like real hg: NEVER traversed into, even when it resolves to
        // a directory (see class javadoc for the real cross-filesystem data-loss bug this fixes).
        boolean isRealDirectory = !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);

        if (isRealDirectory) {
            // Traverse children
            try (Stream<Path> stream = Files.list(path)) {
                List<Path> children = stream.toList();
                for (Path child : children) {
                    purgePath(child, trackedFiles, subrepoPaths);
                }
            }
            // Check if directory is untracked and empty, and if we should purge directories
            if (purgeDirectories && !path.equals(repository.getDirectory().toPath())) {
                if (!rel.isEmpty() && !trackedFiles.contains(rel) && !repository.isIgnored(rel)) {
                    try (Stream<Path> stream = Files.list(path)) {
                        if (stream.findAny().isEmpty()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        } else {
            // File node (or a symlink to anything, or a dangling symlink) -- deleting a symlink
            // path removes the link itself, not whatever it points at (Files.delete's documented
            // POSIX-unlink-like semantics for symbolic links).
            if (!trackedFiles.contains(rel) && !repository.isIgnored(rel)) {
                Files.delete(path);
            }
        }
    }
}
