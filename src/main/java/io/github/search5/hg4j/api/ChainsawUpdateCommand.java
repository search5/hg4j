package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code hg admin::chainsaw-update} ({@code mercurial/admin/chainsaw.py}): pulls and updates to a revision no matter what. DANGEROUS: a
 * directory that is not a repository at the destination is removed, locks left by anybody are broken, local changes and unknown files
 * are thrown away. For unattended use with exclusive access to the repository.
 *
 * <p>Locks, as in the original, in this order: first the two lock files are removed <em>without</em> taking anything
 * ({@code repo.svfs.tryunlink('lock')}, {@code repo.vfs.tryunlink('wlock')}), then {@code wlock} and {@code lock} are taken together for
 * the rest: recovery, pull, purge, update. The pull and the update take the same locks again (re-entrant); {@code purge} takes none of its
 * own.</p>
 */
public final class ChainsawUpdateCommand {

    private String revision = "";
    private String source = "";
    private String destination = "";
    private boolean purgeUnknown = true;
    private boolean purgeIgnored = true;
    private boolean initialCloneMinimal;
    private final List<String> messages = new ArrayList<>();

    /** {@code --rev}. */
    public ChainsawUpdateCommand setRevision(String revision) {
        this.revision = revision == null ? "" : revision;
        return this;
    }

    /** {@code --source}: where to pull from. */
    public ChainsawUpdateCommand setSource(String source) {
        this.source = source == null ? "" : source;
        return this;
    }

    /** {@code --dest}: the repository to update (cloned when it does not exist). */
    public ChainsawUpdateCommand setDestination(String destination) {
        this.destination = destination == null ? "" : destination;
        return this;
    }

    /** {@code --purge-unknown} (default on). */
    public ChainsawUpdateCommand setPurgeUnknown(boolean purgeUnknown) {
        this.purgeUnknown = purgeUnknown;
        return this;
    }

    /** {@code --purge-ignored} (default on). */
    public ChainsawUpdateCommand setPurgeIgnored(boolean purgeIgnored) {
        this.purgeIgnored = purgeIgnored;
        return this;
    }

    /** {@code --initial-clone-minimal}: pull only the revision when the repository is created (so no clone bundle is used). */
    public ChainsawUpdateCommand setInitialCloneMinimal(boolean initialCloneMinimal) {
        this.initialCloneMinimal = initialCloneMinimal;
        return this;
    }

    /** What the command told the user, line by line as hg prints it. */
    public List<String> getMessages() {
        return messages;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(root.toString());
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    /**
     * Brings the destination repository to the target revision no matter what its state is. Destructive: removes a destination that exists but is not a repository, breaks existing store and working copy locks, recovers an interrupted transaction, pulls from the source, purges unknown (and optionally ignored) files, and force-updates. A missing repository is created first.
     * <p>The progress lines are collected and available from {@link #getMessages()}.
     *
     * @throws io.github.search5.hg4j.errors.HgValidationException if the revision, source or destination was not set
     * @throws IOException if any step fails
     * @throws HgLockException if the locks cannot be taken after breaking the old ones
     */
    public void call() throws IOException, HgLockException {
        messages.clear();
        if (revision.isEmpty()) {
            throw new HgValidationException("specify a target revision with --rev");
        }
        if (source.isEmpty()) {
            throw new HgValidationException("specify a pull path with --source");
        }
        if (destination.isEmpty()) {
            throw new HgValidationException("specify a repo path with --dest");
        }
        File path = new File(destination);
        HgRepository repo;
        boolean created;
        if (new File(path, ".hg").isDirectory()) {
            repo = new HgRepository(path);
            created = false;
            messages.add("loaded repository at \"" + destination + "\"\n");
        } else {
            try {
                deleteTree(path.toPath());
                messages.add("removed non-repository file or directory at \"" + destination + "\"");
            } catch (java.nio.file.NoSuchFileException absent) {
                messages.add("no such directory: \"" + destination + "\"\n");
            }
            messages.add("creating repository at \"" + destination + "\"\n");
            repo = Hg.init().setDirectory(path).call();
            created = true;
        }
        try (HgRepository opened = repo) {
            // the locks are broken before anything is taken: whoever held them, in whatever state, is ignored
            if (Files.deleteIfExists(new File(opened.getStoreDir(), "lock").toPath())) {
                messages.add("had to break store lock\n");
            }
            if (Files.deleteIfExists(new File(opened.getHgDir(), "wlock").toPath())) {
                messages.add("had to break working copy lock\n");
            }
            try (HgLock wlock = opened.lockWorkingCopy(); HgLock lock = opened.lockStoreWithoutRecovery(opened.resolvePushLockTimeoutMs())) {
                messages.add("recovering after interrupted transaction, if any\n");
                new RecoverCommand(opened).call();
                messages.add("pulling from " + source + "\n");
                List<String> pullRevs = created && !initialCloneMinimal ? List.of() : List.of(revision);
                new PullCommand(opened).setSource(source).setRevisions(pullRevs).call();
                new PurgeCommand(opened).setPurgeDirectories(true).setPurgeIgnored(purgeIgnored).setPurgeFiles(purgeUnknown).call();
                messages.add("updating to revision '" + revision + "'\n");
                new UpdateCommand(opened).setRevision(revision).setForce(true).call();
                messages.add("chainsaw-update to revision '" + revision + "' for repository at '" + opened.getDirectory().getAbsolutePath() + "' done\n");
            }
        }
    }
}
