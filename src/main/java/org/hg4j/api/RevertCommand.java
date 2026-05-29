package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Porcelain command to revert changes to files in the working directory.
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

    public boolean call() throws IOException {
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
                // If target node is empty, and file was added, we simply delete it
                Dirstate.Entry entry = dirstate.getEntries().get(file);
                if (entry != null && entry.getState() == 'a') {
                    File diskFile = new File(repository.getDirectory(), file);
                    if (diskFile.exists()) {
                        Files.delete(diskFile.toPath());
                    }
                    dirstate.removeEntry(file);
                    repository.writeDirstate(dirstate);
                    return true;
                }
                throw new org.hg4j.errors.HgValidationException("Cannot revert file when parent commit is zero and file is not tracked.");
            }

            // Retrieve the historical version of this file
            byte[] targetContent = null;
            boolean tracked = false;
            int mode = 0644;

            try {
                CatCommand cat = new CatCommand(repository).setFile(file).setRevision(NodeIdUtil.toHex(targetNodeId));
                targetContent = cat.call();
                tracked = true;
                
                // Get flags from manifest
                java.util.Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
                String nodeWithFlags = manifestMap.get(file);
                if (nodeWithFlags != null && nodeWithFlags.substring(Math.min(40, nodeWithFlags.length())).contains("x")) {
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

            if (tracked && targetContent != null) {
                diskFile.getParentFile().mkdirs();
                Files.write(diskFile.toPath(), targetContent);
                boolean executable = (mode == 0755);
                diskFile.setExecutable(executable, false);
                
                long time = diskFile.lastModified() / 1000;
                dirstate.addEntry(file, new Dirstate.Entry('n', mode, targetContent.length, time));
            } else {
                if (diskFile.exists()) {
                    Files.delete(diskFile.toPath());
                }
                dirstate.removeEntry(file);
            }

            repository.writeDirstate(dirstate);
            return true;
        }
    }

}
