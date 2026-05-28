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
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            if (!force) {
                Status status = new StatusCommand(repository).call();
                if (!status.getAdded().isEmpty() || !status.getModified().isEmpty() || !status.getRemoved().isEmpty()) {
                    throw new IOException("Working directory has uncommitted changes. Use force to update.");
                }
            }

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNodeId = resolveTargetNodeId(changelog);
            if (targetNodeId == null) {
                throw new IOException("Repository is empty, cannot update.");
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new IOException("Resolved target commit not found in history: " + NodeIdUtil.toHex(targetNodeId));
            }

            // Extract manifest node hex from changelog content
            byte[] clContent = changelog.getRevisionContent(commitRev);
            String clText = new String(clContent, StandardCharsets.UTF_8);
            String firstLine = clText.split("\n")[0];
            byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

            Revlog manifest = repository.getRevlog(mfIdx, mfDat);
            int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, mfNode);
            if (mfRev == -1) {
                throw new IOException("Manifest revision not found: " + NodeIdUtil.toHex(mfNode));
            }

            byte[] mfContent = manifest.getRevisionContent(mfRev);
            String mfText = new String(mfContent, StandardCharsets.UTF_8);

            // 1. Parse target manifest entries
            Map<String, String> targetEntries = new HashMap<>();
            String[] lines = mfText.split("\n");
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    String path = line.substring(0, nullIdx);
                    String nodeWithFlags = line.substring(nullIdx + 1);
                    targetEntries.put(path, nodeWithFlags.trim());
                }
            }

            // 2. Read current dirstate
            Dirstate dirstate = repository.getDirstate();
            Map<String, Dirstate.Entry> activeEntries = new HashMap<>(dirstate.getEntries());

            // 3. Reconcile workspace and dirstate
            // 3a. Process deletions: Files in current dirstate that are NOT in target manifest
            for (String path : activeEntries.keySet()) {
                if (!targetEntries.containsKey(path)) {
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
                }
            }

            // 3b. Process updates and creations: Files in target manifest
            for (Map.Entry<String, String> targetEntry : targetEntries.entrySet()) {
                String path = targetEntry.getKey();
                String nodeWithFlags = targetEntry.getValue();
                String hexNode = nodeWithFlags.substring(0, 40);
                String flags = nodeWithFlags.substring(40);

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                if (!flIdx.exists()) {
                    throw new IOException("Filelog index not found for tracked file: " + path);
                }

                Revlog filelog = repository.getRevlog(flIdx, flDat);
                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
                if (fileRev == -1) {
                    throw new IOException("File version not found in filelog: " + path + " rev hex " + hexNode);
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
                boolean executable = flags.contains("x");
                diskFile.setExecutable(executable, false);

                int mode = executable ? 0755 : 0644;
                int size = fileContent.length;
                long time = diskFile.lastModified() / 1000;

                dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
            }

            // 4. Conclude checkout parent node updates
            dirstate.setParents(targetNodeId, new byte[20]);
            repository.writeDirstate(dirstate);

            return targetNodeId;
        }
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (targetRevision == null || targetRevision.isEmpty()) {
            int count = changelog.getRevisionCount();
            if (count == 0) return null;
            return changelog.getIndexRecord(count - 1).getNodeId();
        }

        // 1. Try revision number
        try {
            int rev = Integer.parseInt(targetRevision);
            if (rev >= 0 && rev < changelog.getRevisionCount()) {
                return changelog.getIndexRecord(rev).getNodeId();
            }
        } catch (NumberFormatException ignored) {}

        // 2. Try hex node ID prefix
        byte[] matchNode = null;
        for (int i = 0; i < changelog.getRevisionCount(); i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            String hex = NodeIdUtil.toHex(node);
            if (hex.startsWith(targetRevision.toLowerCase())) {
                if (matchNode != null) {
                    throw new IOException("Ambiguous revision identifier: " + targetRevision);
                }
                matchNode = node;
            }
        }
        if (matchNode != null) {
            return matchNode;
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

        throw new IOException("Unable to resolve revision identifier: " + targetRevision);
    }
}
