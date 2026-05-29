package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 특정 리비전에서의 전체 파일 구조(디렉터리 트리) 목록을 반환하는 명령입니다.
 */
public class TreeCommand {
    private final HgRepository repository;
    private int revision = -1;
    private byte[] nodeId;

    public static class TreeEntry {
        private final String path;
        private final String nodeId;
        private final int mode;

        public TreeEntry(String path, String nodeId, int mode) {
            this.path = path;
            this.nodeId = nodeId;
            this.mode = mode;
        }

        public String getPath() { return path; }
        public String getNodeId() { return nodeId; }
        public org.hg4j.lib.NodeId getNode() {
            return nodeId != null ? org.hg4j.lib.NodeId.fromHex(nodeId) : null;
        }
        public int getMode() { return mode; }
    }

    public TreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public TreeCommand setRevision(int revision) {
        this.revision = revision;
        return this;
    }

    public TreeCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public TreeCommand setNodeId(org.hg4j.lib.NodeId nodeId) {
        this.nodeId = nodeId != null ? nodeId.getBytes() : null;
        return this;
    }

    public List<TreeEntry> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        
        if (!clIdx.exists()) {
            return Collections.emptyList();
        }
        
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int targetRev = revision;
        if (nodeId != null) {
            targetRev = NodeIdUtil.findRevisionByNodeId(changelog, nodeId);
        }

        if (targetRev == -1) {
            // default to tip (latest revision)
            targetRev = changelog.getRevisionCount() - 1;
        }

        if (targetRev < 0 || targetRev >= changelog.getRevisionCount()) {
            return Collections.emptyList();
        }

        byte[] commitNodeId = changelog.getIndexRecord(targetRev).getNodeId();
        java.util.Map<String, String> manifestMap = repository.getManifestAtCommit(commitNodeId);

        List<TreeEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            String hex = entry.getValue();
            
            int mode = 0644;
            String cleanHex = hex;
            if (hex.length() > 40) {
                char flag = hex.charAt(40);
                if (flag == 'x') {
                    mode = 0755;
                } else if (flag == 'l') {
                    mode = 0120000;
                }
                cleanHex = hex.substring(0, 40);
            }
            
            entries.add(new TreeEntry(path, cleanHex, mode));
        }
        return entries;
    }
}
