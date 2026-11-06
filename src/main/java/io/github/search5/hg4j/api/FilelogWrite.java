package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lfs.HgLfsFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Adds a file revision to a filelog the way the original's {@code filelog.add} does with the lfs extension loaded: a file that
 * {@code lfs.track} (or {@code .hglfs}) selects is stored as a pointer with the external-storage flag, and its node is the hash of the
 * real content with the copy metadata, not of the pointer. A revision that is already stored is not added again.
 */
final class FilelogWrite {
    private FilelogWrite() {
    }

    /**
     * @param meta the {@code copy}/{@code copyrev} metadata, or {@code null}
     * @param p1 the first parent file revision node, all zeros for none
     * @param p2 the second parent file revision node, all zeros for none
     * @return the 20-byte node of the revision
     */
    static byte[] add(HgRepository repository, Revlog filelog, String path, byte[] content, Map<String, String> meta, byte[] p1,
                      byte[] p2, int linkrev) throws IOException {
        byte[] wrapped = Revlog.wrapMetadata(content, meta);
        byte[] node = Arrays.copyOf(NodeIdUtil.computeNodeId(wrapped, p1, p2), 20);
        if (filelog.findRevision(node) >= 0) {
            return node;
        }
        int p1Rev = NodeIdUtil.isAllZero(p1) ? -1 : filelog.findRevision(p1);
        int p2Rev = NodeIdUtil.isAllZero(p2) ? -1 : filelog.findRevision(p2);
        HgLfsFactory.Cleaned cleaned = HgLfsFactory.getInstance().applyCleanFilter(repository, path, content, meta);
        byte[] added;
        if (cleaned.isLfs()) {
            added = filelog.appendRevision(cleaned.stored(), null, p1Rev, p2Rev, p1, p2, linkrev, null, Revlog.REVIDX_EXTSTORED, wrapped);
        } else {
            added = filelog.appendRevision(content, meta, p1Rev, p2Rev, p1, p2, linkrev);
        }
        return added.length == 20 ? added : Arrays.copyOf(added, 20);
    }
}
