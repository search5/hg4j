package org.hg4j.api;

import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Porcelain command to retrieve the content of a specific file version in history.
 */
public class CatCommand {

    private final HgRepository repository;
    private String file;
    private String revision;

    public CatCommand(HgRepository repository) {
        this.repository = repository;
    }

    public CatCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public CatCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public CatCommand setRevision(org.hg4j.lib.NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    public byte[] call() throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        byte[] targetNodeId = resolveTargetNodeId(changelog);
        if (targetNodeId == null) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Unable to resolve revision");
        }

        int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
        if (commitRev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Commit revision not found: " + NodeIdUtil.toHex(targetNodeId));
        }

        // Read Manifest at that commit to find file version node
        byte[] clContent = changelog.getRevisionContent(commitRev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        byte[] mfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifest = repository.getRevlog(mfIdx, mfDat);
        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, mfNode);
        if (mfRev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Manifest not found: " + NodeIdUtil.toHex(mfNode));
        }

        byte[] mfContent = manifest.getRevisionContent(mfRev);
        String mfText = new String(mfContent, StandardCharsets.UTF_8);

        String fileHexNode = null;
        String[] lines = mfText.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx != -1) {
                String path = line.substring(0, nullIdx);
                if (file.equals(path)) {
                    fileHexNode = line.substring(nullIdx + 1).trim().substring(0, 40);
                    break;
                }
            }
        }

        if (fileHexNode == null) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("File not tracked at target revision: " + file);
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new org.hg4j.errors.HgCorruptDataException("Filelog not found for tracked file: " + file);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(fileHexNode));
        if (fileRev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("File version not found in history: " + file + " @ " + fileHexNode);
        }

        return filelog.getRevisionContent(fileRev);
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (revision == null || revision.isEmpty()) {
            int count = changelog.getRevisionCount();
            if (count == 0) return null;
            return changelog.getIndexRecord(count - 1).getNodeId();
        }

        try {
            int rev = Integer.parseInt(revision);
            if (rev >= 0 && rev < changelog.getRevisionCount()) {
                return changelog.getIndexRecord(rev).getNodeId();
            }
        } catch (NumberFormatException ignored) {}

        byte[] matchNode = null;
        for (int i = 0; i < changelog.getRevisionCount(); i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            String hex = NodeIdUtil.toHex(node);
            if (hex.startsWith(revision.toLowerCase())) {
                if (matchNode != null) {
                    throw new org.hg4j.errors.HgRevisionNotFoundException("Ambiguous revision identifier: " + revision);
                }
                matchNode = node;
            }
        }
        return matchNode;
    }
}
