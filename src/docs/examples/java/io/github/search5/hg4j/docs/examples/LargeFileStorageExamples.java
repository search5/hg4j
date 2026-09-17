package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Snippets for the "Large File Storage (LFS)" chapter of the hg4j manual. Compiled by the {@code
 * docExamples} source set so the manual's example code can never silently drift out of sync with
 * the real API.
 */
final class LargeFileStorageExamples {

    private LargeFileStorageExamples() {
    }

    void enableLfsThreshold() throws IOException, HgLockException {
        File repoDir = new File("/path/to/repo");
        // tag::configure-threshold[]
        // [lfs] threshold lives in the repository's own .hg/hgrc, exactly like real hg -- there
        // is no hg4j API for it, since it's plain repository configuration, not a command.
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[lfs]\nthreshold = 10MB\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        try (Hg hg = Hg.open(repoDir)) {
            // Any file added or modified past the threshold is committed as an LFS pointer
            // instead of its literal bytes -- commit() itself needs no LFS-specific call.
            hg.add().call();
            hg.commit().setAuthor("dev").setMessage("add a large asset").call();
        }
        // end::configure-threshold[]
    }

    void checkoutAndReadLfsFile() throws IOException, HgLockException {
        File repoDir = new File("/path/to/repo");
        try (Hg hg = Hg.open(repoDir)) {
            // tag::checkout-and-read[]
            // update() and cat() both transparently resolve an LFS pointer back to the real
            // bytes -- neither returns the pointer text itself.
            hg.update().setRevision("tip").call();
            byte[] realBytes = hg.cat().setFile("big-asset.bin").setRevision("tip").call();
            // end::checkout-and-read[]
            System.out.println(realBytes.length);
        }
    }

    void fetchMissingObjectFromRemote(HgLfsPointer pointer) throws IOException, InterruptedException {
        HgRepository repository = new HgRepository(new File("/path/to/repo/.hg"));
        // tag::fetch-remote[]
        HgLfsManager manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
        if (!manager.isCached(pointer)) {
            // Derived from [paths] default by appending ".git/info/lfs" (the same
            // git-lfs server-discovery convention real hg reuses), unless [lfs] url overrides it.
            String serverUrl = HgLfsManager.resolveServerUrl(repository);
            manager.fetchObject(pointer, serverUrl);
        }
        byte[] content = manager.getCachedObject(pointer);
        // end::fetch-remote[]
        System.out.println(content.length);
    }

    void inspectUserCache() {
        HgRepository repository = new HgRepository(new File("/path/to/repo/.hg"));
        // tag::usercache[]
        // Shared across every repository on the machine (real hg's [lfs] usercache /
        // experimental.lfs.disableusercache) -- null means the user-level cache is disabled, so
        // only the per-repository .hg/store/lfs/objects/ store is ever consulted.
        HgLfsManager manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
        File userCacheDir = manager.getUserCacheDir();
        // end::usercache[]
        System.out.println(userCacheDir);
    }
}
