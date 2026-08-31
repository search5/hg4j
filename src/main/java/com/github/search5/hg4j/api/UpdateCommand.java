package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.core.HgLock;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.errors.HgLockException;

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
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(UpdateCommand.class.getName());

    private final HgRepository repository;
    private String targetRevision;
    private boolean force = false;
    private com.github.search5.hg4j.core.HgTreeFilter treeFilter = com.github.search5.hg4j.core.HgTreeFilter.ALL;
    private final java.util.List<HgHook> preUpdateHooks = new java.util.ArrayList<>();
    private final java.util.List<HgHook> postUpdateHooks = new java.util.ArrayList<>();

    public UpdateCommand(HgRepository repository) {
        this.repository = repository;
    }

    public UpdateCommand registerPreUpdateHook(HgHook hook) {
        if (hook != null) {
            preUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand registerPostUpdateHook(HgHook hook) {
        if (hook != null) {
            postUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand setTreeFilter(com.github.search5.hg4j.core.HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public UpdateCommand setRevision(String targetRevision) {
        this.targetRevision = targetRevision;
        return this;
    }

    public UpdateCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            // PRE_UPDATE hooks trigger
            if (!preUpdateHooks.isEmpty()) {
                Map<String, Object> ctx = new java.util.HashMap<>();
                ctx.put("repository", repository);
                ctx.put("targetRevision", targetRevision);
                ctx.put("force", force);
                for (HgHook hook : preUpdateHooks) {
                    if (!hook.run(ctx)) {
                        throw new com.github.search5.hg4j.errors.HgValidationException("Update rejected by PRE_UPDATE hook");
                    }
                }
            }

            Dirstate dirstate = repository.getDirstate();
            if (!force) {
                if (!dirstate.getEntries().isEmpty()) {
                    Status status = new StatusCommand(repository).call();
                    if (!status.getAdded().isEmpty() || !status.getModified().isEmpty() || !status.getRemoved().isEmpty()) {
                        throw new com.github.search5.hg4j.errors.HgValidationException("Working directory has uncommitted changes. Use force to update.");
                    }
                }
            }

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNodeId = resolveTargetNodeId(changelog);
            if (targetNodeId == null) {
                throw new com.github.search5.hg4j.errors.HgValidationException("Repository is empty, cannot update.");
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException(NodeIdUtil.toHex(targetNodeId));
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

            com.github.search5.hg4j.treewalk.TreeWalk tw = new com.github.search5.hg4j.treewalk.TreeWalk();
            tw.addTree(new com.github.search5.hg4j.treewalk.ManifestTreeIterator(repository, parentRev)); // Tree 0: Current Parent Manifest
            tw.addTree(new com.github.search5.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(commitRev))); // Tree 1: Target Commit Manifest

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                if (treeFilter != null && !treeFilter.accept(path)) {
                    continue;
                }
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
                        throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
                    }

                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, targetNode);
                    if (fileRev == -1) {
                        throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
                    }

                    byte[] fileContent = filelog.getRevisionContent(fileRev);

                    boolean symlink = tw.isSymlink(1);

                    File diskFile = new File(repository.getDirectory(), path);
                    boolean needsWrite = true;

                    if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                        if (!symlink) {
                            try {
                                byte[] existingContent = Files.readAllBytes(diskFile.toPath());
                                if (java.util.Arrays.equals(existingContent, fileContent)) {
                                    needsWrite = false;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (needsWrite) {
                            Files.delete(diskFile.toPath());
                        }
                    }

                    int mode = 0644;
                    if (needsWrite) {
                        diskFile.getParentFile().mkdirs();
                        if (symlink) {
                            mode = 0120000;
                            String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                            try {
                                Files.createSymbolicLink(diskFile.toPath(), java.nio.file.Path.of(target));
                            } catch (Exception e) {
                                Files.write(diskFile.toPath(), fileContent);
                            }
                        } else {
                            Files.write(diskFile.toPath(), fileContent);
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    } else {
                        if (symlink) {
                            mode = 0120000;
                        } else {
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    }

                    int size = fileContent.length;
                    long time = diskFile.lastModified() / 1000;

                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }

            // 4. Conclude checkout parent node updates
            dirstate.setParents(targetNodeId, new byte[20]);
            repository.writeDirstate(dirstate);

            // 5. Recursive Subrepo Checkout (JGit-like subrepository checkout support)
            File hgsubFile = new File(repository.getDirectory(), ".hgsub");
            File hgsubstateFile = new File(repository.getDirectory(), ".hgsubstate");
            if (hgsubFile.exists() && hgsubstateFile.exists()) {
                try {
                    byte[] hgsubBytes = Files.readAllBytes(hgsubFile.toPath());
                    byte[] hgsubstateBytes = Files.readAllBytes(hgsubstateFile.toPath());
                    java.util.Map<String, com.github.search5.hg4j.core.HgSubrepoEntry> subrepos = com.github.search5.hg4j.core.HgSubrepoParser.parseSubrepositories(hgsubBytes, hgsubstateBytes);
                    
                    for (com.github.search5.hg4j.core.HgSubrepoEntry subEntry : subrepos.values()) {
                        if (subEntry.isGit()) {
                            continue; // Skip Git subrepos
                        }
                        
                        File subDir = new File(repository.getDirectory(), subEntry.getPath());
                        com.github.search5.hg4j.core.HgRepository subRepo;
                        if (!new File(subDir, ".hg").exists()) {
                            subRepo = Hg.init().setDirectory(subDir).call();
                        } else {
                            subRepo = new com.github.search5.hg4j.core.HgRepository(subDir);
                        }

                        try (Hg hgSub = Hg.wrap(subRepo)) {
                            if (subEntry.getSourceUrl() != null && !subEntry.getSourceUrl().isEmpty()) {
                                try {
                                    hgSub.pull().setSource(subEntry.getSourceUrl()).call();
                                } catch (Exception e) {
                                    LOGGER.log(java.util.logging.Level.WARNING, "Failed to pull subrepo from: " + subEntry.getSourceUrl() + ", error: " + e.getMessage(), e);
                                }
                            }
                            
                            if (subEntry.getRevision() != null && !subEntry.getRevision().isEmpty()) {
                                hgSub.update().setRevision(subEntry.getRevision()).setForce(true).call();
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Failed to perform recursive subrepo checkout", e);
                }
            }

            // POST_UPDATE hooks trigger
            java.util.Map<String, Object> ctx = new java.util.HashMap<>();
            ctx.put("targetNode", NodeIdUtil.toHex(targetNodeId));
            ctx.put("repository", repository);
            for (HgHook hook : postUpdateHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Post-update hook execution failed", e);
                }
            }

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
                throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException(e.getMessage());
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

        throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Unable to resolve revision identifier: " + targetRevision);
    }
}
