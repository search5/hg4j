package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                targetNodeId = resolveTargetNodeId(changelog);
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
                File clIdx = new File(repository.getStoreDir(), "00changelog.i");
                File clDat = new File(repository.getStoreDir(), "00changelog.d");
                Revlog changelog = repository.getRevlog(clIdx, clDat);
                int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
                if (commitRev != -1) {
                    byte[] clContent = changelog.getRevisionContent(commitRev);
                    String clText = new String(clContent, StandardCharsets.UTF_8);
                    byte[] mfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));

                    File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
                    File mfDat = new File(repository.getStoreDir(), "00manifest.d");
                    Revlog manifest = repository.getRevlog(mfIdx, mfDat);
                    int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, mfNode);
                    if (mfRev != -1) {
                        byte[] mfContent = manifest.getRevisionContent(mfRev);
                        String mfText = new String(mfContent, StandardCharsets.UTF_8);
                        for (String line : mfText.split("\n")) {
                            if (line.isEmpty()) continue;
                            int nullIdx = line.indexOf('\0');
                            if (nullIdx != -1) {
                                String path = line.substring(0, nullIdx);
                                if (file.equals(path)) {
                                    String nodeWithFlags = line.substring(nullIdx + 1).trim();
                                    if (nodeWithFlags.length() > 40 && nodeWithFlags.substring(40).contains("x")) {
                                        mode = 0755;
                                    }
                                    break;
                                }
                            }
                        }
                    }
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

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (revision == null || revision.isEmpty()) {
            return null;
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
