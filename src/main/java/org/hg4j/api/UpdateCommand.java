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
import java.util.HashMap;
import java.util.Map;

/**
 * Porcelain command to update (checkout) working copy to a specified target revision.
 * Built with full transaction isolation and strict dirstate state transitions.
 */
public class UpdateCommand {

    private final HgRepository repository;
    private String targetRevision;
    private boolean force = false;

    public UpdateCommand(HgRepository repository) {
        this.repository = repository;
    }

    public UpdateCommand setRevision(String targetRevision) {
        this.targetRevision = targetRevision;
        return this;
    }

    public UpdateCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public byte[] call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();
            if (!force) {
                if (!dirstate.getEntries().isEmpty()) {
                    Status status = new StatusCommand(repository).call();
                    if (!status.getAdded().isEmpty() || !status.getModified().isEmpty() || !status.getRemoved().isEmpty()) {
                        throw new org.hg4j.errors.HgValidationException("Working directory has uncommitted changes. Use force to update.");
                    }
                }
            }

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNodeId = resolveTargetNodeId(changelog);
            if (targetNodeId == null) {
                throw new org.hg4j.errors.HgValidationException("Repository is empty, cannot update.");
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new org.hg4j.errors.HgRevisionNotFoundException(NodeIdUtil.toHex(targetNodeId));
            }

            // Resolve current parent revision for TreeWalk
            dirstate = repository.getDirstate();
            String parentRev = "";
            if (changelog.getRevisionCount() > 0) {
                byte[] parentNode = dirstate.getParent1();
                int parentRevNum = NodeIdUtil.findRevisionByNodeId(changelog, parentNode);
                if (parentRevNum != -1) {
                    parentRev = String.valueOf(parentRevNum);
                }
            }

            org.hg4j.treewalk.TreeWalk tw = new org.hg4j.treewalk.TreeWalk();
            tw.addTree(new org.hg4j.treewalk.ManifestTreeIterator(repository, parentRev)); // Tree 0: Current Parent Manifest
            tw.addTree(new org.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(commitRev))); // Tree 1: Target Commit Manifest

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                boolean inParent = tw.isTracked(0);
                boolean inTarget = tw.isTracked(1);

                if (inParent && !inTarget) {
                    // Process deletions: Files in current manifest that are NOT in target manifest
                    File diskFile = new File(repository.getDirectory(), path);
                    if (diskFile.exists()) {
                        Files.delete(diskFile.toPath());
                        // Delete empty parent directories if clean
                        File parent = diskFile.getParentFile();
                        while (parent != null && !parent.equals(repository.getDirectory())) {
                            File[] files = parent.listFiles();
                            if (files == null || files.length == 0) {
                                Files.delete(parent.toPath());
                                parent = parent.getParentFile();
                            } else {
                                break;
                            }
                        }
                    }
                    dirstate.removeEntry(path);
                } else if (inTarget) {
                    // Process updates and creations: Files in target manifest
                    byte[] targetNode = tw.getNodeId(1);
                    String hexNode = NodeIdUtil.toHex(targetNode);
                    boolean executable = tw.isExecutable(1);

                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                    if (!flIdx.exists()) {
                        throw new org.hg4j.errors.HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
                    }

                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, targetNode);
                    if (fileRev == -1) {
                        throw new org.hg4j.errors.HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
                    }

                    byte[] fileContent = filelog.getRevisionContent(fileRev);

                    File diskFile = new File(repository.getDirectory(), path);
                    boolean needsWrite = true;

                    if (diskFile.exists()) {
                        // Optimized content checksum match comparison to bypass disk writes
                        byte[] existingContent = Files.readAllBytes(diskFile.toPath());
                        if (java.util.Arrays.equals(existingContent, fileContent)) {
                            needsWrite = false;
                        }
                    }

                    if (needsWrite) {
                        diskFile.getParentFile().mkdirs();
                        Files.write(diskFile.toPath(), fileContent);
                    }

                    // Apply executable flag if 'x'
                    diskFile.setExecutable(executable, false);

                    int mode = executable ? 0755 : 0644;
                    int size = fileContent.length;
                    long time = diskFile.lastModified() / 1000;

                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }

            // 4. Conclude checkout parent node updates
            dirstate.setParents(targetNodeId, new byte[20]);
            repository.writeDirstate(dirstate);

            return targetNodeId;
        }
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (changelog.getRevisionCount() == 0) {
            return null;
        }
        try {
            byte[] resolved = NodeIdUtil.resolveRevision(changelog, targetRevision);
            if (resolved != null) {
                return resolved;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Ambiguous")) {
                throw new org.hg4j.errors.HgRevisionNotFoundException(e.getMessage());
            }
            throw e;
        }

        // 3. Try named branch head
        byte[] branchHead = null;
        for (int i = changelog.getRevisionCount() - 1; i >= 0; i--) {
            byte[] content = changelog.getRevisionContent(i);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            String dateLine = lines[2].trim();

            String branch = "default";
            int firstSpace = dateLine.indexOf(' ');
            if (firstSpace != -1) {
                int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
                String extraPart = (secondSpace != -1) ? dateLine.substring(secondSpace + 1) : null;
                if (extraPart != null && !extraPart.isEmpty()) {
                    String[] extraItems = extraPart.split("\0", -1);
                    for (String part : extraItems) {
                        int colonIdx = CommitCommand.findUnescapedColon(part);
                        if (colonIdx != -1) {
                            String key = part.substring(0, colonIdx);
                            String val = part.substring(colonIdx + 1);
                            if ("branch".equals(CommitCommand.decodeExtraKey(key))) {
                                branch = CommitCommand.decodeExtraKey(val);
                            }
                        }
                    }
                }
            }
            if (targetRevision.equals(branch)) {
                branchHead = changelog.getIndexRecord(i).getNodeId();
                break;
            }
        }
        if (branchHead != null) {
            return branchHead;
        }

        throw new org.hg4j.errors.HgRevisionNotFoundException("Unable to resolve revision identifier: " + targetRevision);
    }
}
