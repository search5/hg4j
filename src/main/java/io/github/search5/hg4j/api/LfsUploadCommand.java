package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lfs.HgLfsFiles;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lfs.HgLfsPrePush;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code hg debuglfsupload -r REV}: uploads the LFS blobs the given revisions introduced to the LFS store of the default path
 * (or {@code [lfs] url}), the same upload a push or a bundle does.
 */
public class LfsUploadCommand {

    private final HgRepository repository;
    private final List<String> revisions = new ArrayList<>();

    public LfsUploadCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** Adds a revision whose blobs are uploaded; without any, the working copy parent is used. */
    public LfsUploadCommand addRevision(String revision) {
        revisions.add(revision);
        return this;
    }

    /** @return the number of distinct blobs that were considered (the server skips those it already has) */
    public int call() throws IOException {
        Revlog changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                new File(repository.getStoreDir(), "00changelog.d"));
        List<String> specs = new ArrayList<>(revisions);
        if (specs.isEmpty()) {
            specs.add(NodeIdUtil.toHex(repository.getDirstate().getParent1()));
        }
        Map<String, HgLfsPointer> pointers = new TreeMap<>();
        for (String spec : specs) {
            byte[] node = NodeIdUtil.resolveRevision(changelog, spec);
            int rev = node == null ? -1 : changelog.findRevision(node);
            if (rev == -1) {
                throw new IOException("Revision not found: " + spec);
            }
            for (HgLfsPointer pointer : HgLfsFiles.changedBy(repository, rev, false).values()) {
                pointers.put(pointer.getOid(), pointer);
            }
        }
        HgLfsPrePush.uploadPointers(repository, repository.getConfig().getPath("default"), new ArrayList<>(pointers.values()));
        return pointers.size();
    }
}
