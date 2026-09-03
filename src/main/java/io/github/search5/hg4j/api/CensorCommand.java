package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command for {@code hg censor} — erases a single file revision's content in place
 * (replacing it with a tombstone and marking it via {@link Revlog#REVIDX_ISCENSORED}) while
 * preserving that revision's node identity, parents, and linkrev, so history/DAG shape is
 * untouched. Real hg's tombstone wire format (see {@code mercurial/utils/storageutil.py}'s
 * {@code packmeta}/{@code iscensoredtext}) is the same {@code \x01\n key: value\n\x01\n} metadata
 * header hg4j already uses for filelog copy records, with a single {@code censored} key:
 * {@code "\x01\ncensored: <message>\n\x01\n"}.
 */
public final class CensorCommand {
    private final HgRepository repository;
    private String path;
    private String nodeHex;
    private String tombstone = "";

    public CensorCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public CensorCommand setFile(String path) {
        this.path = path;
        return this;
    }

    public CensorCommand setRevision(String nodeHex) {
        this.nodeHex = nodeHex;
        return this;
    }

    /** Optional free-text reason recorded in the tombstone (empty by default, matching real hg). */
    public CensorCommand setTombstone(String tombstone) {
        this.tombstone = tombstone == null ? "" : tombstone;
        return this;
    }

    public void call() throws IOException {
        if (path == null || path.isEmpty()) {
            throw new HgValidationException("File path is required for censor");
        }
        if (nodeHex == null || nodeHex.isEmpty()) {
            throw new HgValidationException("Revision is required for censor");
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        byte[] nodeId = NodeIdUtil.fromHex(nodeHex);
        int rev = filelog.findRevision(nodeId);
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }

        filelog.censorRevision(rev, buildTombstone(tombstone));
        repository.clearRevlogCache();
    }

    static byte[] buildTombstone(String message) {
        String text = "\u0001\ncensored: " + message + "\n\u0001\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
