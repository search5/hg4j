package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.StoreCopy;
import io.github.search5.hg4j.transport.wireprotov1.RepoSupport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code hg unshare}: turns a repository created with {@code share} (see {@link WorktreeCommand}) into a normal one by copying the
 * store it points to into the repository itself and dropping the {@code sharedpath}.
 *
 * <p>Follows {@code cmd_impls.clone.unshare}: the store lock (of the shared store) is held for the whole operation, the store is
 * copied ({@link StoreCopy}) and the lock of the new store is held while the share is cut off. Afterwards the subrepositories that are
 * shared are unshared too.</p>
 */
public class UnshareCommand {
    private final HgRepository repository;

    public UnshareCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** @return a new repository object for the unshared repository; the one passed to the constructor is not to be used any more */
    public HgRepository call() throws IOException, HgLockException {
        File hgDir = repository.getHgDir();
        File sharedPathFile = new File(hgDir, "sharedpath");
        if (!sharedPathFile.exists()) {
            throw new HgValidationException("this is not a shared repo");
        }
        boolean publishing = RepoSupport.isPublishing(repository);
        try (HgLock sharedStoreLock = repository.lockStoreWithoutRecovery(repository.resolvePushLockTimeoutMs())) {                          // with repo.lock():
            HgLock destLock = StoreCopy.copyStore(repository, hgDir, publishing);          //   destlock = copy_store(...)
            try {
                if (repository.getRequirements().contains("share-safe")) {                 //   _prependsourcehgrc(repo)
                    prependSourceHgrc(new File(Files.readString(sharedPathFile.toPath(), StandardCharsets.UTF_8).trim()), hgDir);
                }
                Files.move(sharedPathFile.toPath(), new File(hgDir, "sharedpath.old").toPath(), StandardCopyOption.REPLACE_EXISTING);
                dropSharedRequirements(new File(hgDir, "requires"));
            } finally {
                if (destLock != null) {
                    destLock.close();
                }
            }
        }
        HgRepository unshared = new HgRepository(repository.getDirectory());
        unshareSubrepositories(unshared);
        return unshared;
    }

    /** Real hg's {@code _prependsourcehgrc}: the source's config goes in front of the repository's own, under a marker line. */
    private static void prependSourceHgrc(File sourceHgDir, File hgDir) throws IOException {
        File source = new File(sourceHgDir, "hgrc");
        if (!source.exists()) {
            return;
        }
        File own = new File(hgDir, "hgrc");
        String current = own.exists() ? Files.readString(own.toPath(), StandardCharsets.UTF_8) : "";
        Files.writeString(own.toPath(), "# Config copied from shared source\n"
                + Files.readString(source.toPath(), StandardCharsets.UTF_8) + "\n" + current, StandardCharsets.UTF_8);
    }

    private static void dropSharedRequirements(File requires) throws IOException {
        if (!requires.exists()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(requires.toPath(), StandardCharsets.UTF_8)) {
            if (!line.trim().equals("shared") && !line.trim().equals("relshared")) {
                kept.add(line);
            }
        }
        Files.write(requires.toPath(), (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code for s in sorted(subs): c.sub(s).unshare()}: only a subrepository that is itself shared is changed. */
    private static void unshareSubrepositories(HgRepository repo) throws IOException, HgLockException {
        File substate = new File(repo.getDirectory(), ".hgsubstate");
        if (!substate.exists()) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (String line : Files.readAllLines(substate.toPath(), StandardCharsets.UTF_8)) {
            int space = line.indexOf(' ');
            if (space > 0) {
                paths.add(line.substring(space + 1).trim());
            }
        }
        java.util.Collections.sort(paths);
        for (String path : paths) {
            File sub = new File(repo.getDirectory(), path);
            if (new File(sub, ".hg/sharedpath").exists()) {
                new UnshareCommand(new HgRepository(sub)).call();
            }
        }
    }
}
