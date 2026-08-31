package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Porcelain command for applying unified diff patch files
 * and committing them natively in Mercurial repositories.
 */
public class ImportCommand {
    private final HgRepository repository;
    private String patchText;

    public ImportCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ImportCommand setPatchText(String patchText) {
        this.patchText = patchText;
        return this;
    }

    /**
     * Parses the patch headers, applies contents to workspace, and creates a commit.
     *
     * @throws IOException if patch parsing or commit writing fails
     */
    public void call() throws IOException, HgLockException {
        if (patchText == null || patchText.isEmpty()) {
            throw new IllegalArgumentException("Patch content must not be null or empty for import");
        }

        String[] lines = patchText.split("\n");
        String author = "unknown";
        String dateVal = null;
        StringBuilder descBuilder = new StringBuilder();
        boolean parsingHeaders = true;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (parsingHeaders) {
                if (line.startsWith("# User ")) {
                    author = line.substring(7).trim();
                } else if (line.startsWith("# Date ")) {
                    dateVal = line.substring(7).trim();
                } else if (line.startsWith("# Node ID ")) {
                    // skip
                } else if (line.startsWith("# Parent ")) {
                    // skip
                } else if (line.startsWith("#") || line.isEmpty()) {
                    if (line.isEmpty() && descBuilder.length() > 0) {
                        parsingHeaders = false;
                    }
                } else {
                    if (descBuilder.length() > 0) descBuilder.append("\n");
                    descBuilder.append(line);
                }
            }
        }

        String message = descBuilder.toString().trim();
        if (message.isEmpty()) {
            message = "Imported patch";
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        byte[] parent = repository.getDirstate().getParent1();
        int linkRev = changelog.getRevisionCount();

        // 1. Apply unified diff patches to physical files and collect contents
        java.util.Map<String, String> patchedFiles = parseAndApplyPatch(patchText);
        java.util.List<String> filesModified = new java.util.ArrayList<>(patchedFiles.keySet());

        // 2. Get parent manifest map to initialize new manifest
        java.util.Map<String, String> manifestMap = getManifestForCommit(changelog, manifestRevlog, parent);

        // 3. Update physical files and commit to their filelogs
        for (java.util.Map.Entry<String, String> fEntry : patchedFiles.entrySet()) {
            String path = fEntry.getKey();
            byte[] fileContent = fEntry.getValue().getBytes(StandardCharsets.UTF_8);

            // Write content to working directory file
            File wFile = new File(repository.getDirectory(), path);
            wFile.getParentFile().mkdirs();
            java.nio.file.Files.write(wFile.toPath(), fileContent);

            // Commit to filelog
            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            flIdx.getParentFile().mkdirs();
            Revlog filelog = repository.getRevlog(flIdx, flDat);

            int parent1FileRev = -1;
            byte[] p1FileNode = new byte[20];
            String parentFileHexAndFlag = manifestMap.get(path);
            if (parentFileHexAndFlag != null) {
                String parentFileHex = parentFileHexAndFlag.substring(0, 40);
                p1FileNode = com.github.search5.hg4j.core.NodeIdUtil.fromHex(parentFileHex);
                parent1FileRev = com.github.search5.hg4j.core.NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
            }

            int newCommitRev = changelog.getRevisionCount();
            byte[] newFileNode = filelog.appendRevision(fileContent, null, parent1FileRev, -1, p1FileNode, new byte[20], newCommitRev);

            manifestMap.put(path, com.github.search5.hg4j.core.NodeIdUtil.toHex(newFileNode)); // default flag is empty
        }

        // 4. Serialize and append new manifest revision
        StringBuilder manifestSb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
            manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
        }
        byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);

        int parent1ManifestRev = -1;
        byte[] p1ManifestNode = new byte[20];
        if (parent != null && !com.github.search5.hg4j.core.NodeIdUtil.isAllZero(parent)) {
            int pRev = changelog.findRevision(parent);
            if (pRev != -1) {
                byte[] pContent = changelog.getRevisionContent(pRev);
                String pText = new String(pContent, StandardCharsets.UTF_8);
                String[] pLines = pText.split("\n");
                if (pLines.length > 0) {
                    p1ManifestNode = com.github.search5.hg4j.core.NodeIdUtil.fromHex(pLines[0].trim());
                    parent1ManifestRev = manifestRevlog.findRevision(p1ManifestNode);
                }
            }
        }

        byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, -1, p1ManifestNode, new byte[20], linkRev);

        // 5. Serialize and append new changelog (commit) revision
        StringBuilder clSb = new StringBuilder();
        clSb.append(com.github.search5.hg4j.core.NodeIdUtil.toHex(manifestNode)).append('\n');
        clSb.append(author).append('\n');

        String dateStr = (dateVal != null) ? dateVal : (System.currentTimeMillis() / 1000) + " 0";
        clSb.append(dateStr).append(" branch:default\n");

        java.util.Collections.sort(filesModified, com.github.search5.hg4j.core.NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : filesModified) {
            clSb.append(path).append('\n');
        }
        clSb.append('\n'); // empty line separator
        clSb.append(message);

        byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] p1Normalized = new byte[20];
        if (parent != null) {
            System.arraycopy(parent, 0, p1Normalized, 0, Math.min(parent.length, 20));
        }

        com.github.search5.hg4j.core.ChangegroupParser.ChangeGroupEntry entry = new com.github.search5.hg4j.core.ChangegroupParser.ChangeGroupEntry();
        entry.node = NodeIdUtil.computeNodeId(changelogTextBytes, p1Normalized, new byte[20]);
        byte[] entryNode20 = new byte[20];
        System.arraycopy(entry.node, 0, entryNode20, 0, 20);
        entry.node = entryNode20;
        entry.p1 = p1Normalized;
        entry.p2 = new byte[20];
        entry.cs = entry.node;
        entry.deltabase = new byte[20];
        entry.delta = Revlog.createDelta(new byte[0], changelogTextBytes);

        try (com.github.search5.hg4j.core.HgLock storeLock = repository.lockStore();
             com.github.search5.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            changelog.appendChangeGroupEntry(entry, linkRev);

            com.github.search5.hg4j.dirstate.Dirstate d = repository.getDirstate();
            d.setParents(entry.node, new byte[20]);
            repository.writeDirstate(d);
            repository.clearRevlogCache();
        }
    }

    private java.util.Map<String, String> parseAndApplyPatch(String patchText) throws IOException {
        java.util.Map<String, String> fileContents = new java.util.LinkedHashMap<>();
        String[] lines = patchText.split("\n", -1);
        String currentFile = null;
        java.util.List<String> currentLines = new java.util.ArrayList<>();
        
        int idx = 0;
        while (idx < lines.length) {
            String line = lines[idx];
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring(6).trim();
                File wFile = new File(repository.getDirectory(), currentFile);
                currentLines = new java.util.ArrayList<>();
                if (wFile.exists()) {
                    currentLines.addAll(java.nio.file.Files.readAllLines(wFile.toPath(), StandardCharsets.UTF_8));
                }
                idx++;
                continue;
            }
            
            if (currentFile != null && line.startsWith("@@ ")) {
                idx++;
                java.util.List<String> hunkOld = new java.util.ArrayList<>();
                java.util.List<String> hunkNew = new java.util.ArrayList<>();
                while (idx < lines.length) {
                    String hunkLine = lines[idx];
                    if (hunkLine.startsWith("diff ") || hunkLine.startsWith("--- ") || hunkLine.startsWith("+++ ")) {
                        break;
                    }
                    if (hunkLine.startsWith("-")) {
                        hunkOld.add(hunkLine.substring(1));
                    } else if (hunkLine.startsWith("+")) {
                        hunkNew.add(hunkLine.substring(1));
                    } else if (hunkLine.startsWith(" ")) {
                        hunkOld.add(hunkLine.substring(1));
                        hunkNew.add(hunkLine.substring(1));
                    } else {
                        break;
                    }
                    idx++;
                }
                
                int matchIdx = -1;
                if (hunkOld.isEmpty()) {
                    matchIdx = currentLines.size();
                } else {
                    for (int c = 0; c <= currentLines.size() - hunkOld.size(); c++) {
                        boolean match = true;
                        for (int h = 0; h < hunkOld.size(); h++) {
                            if (!currentLines.get(c + h).equals(hunkOld.get(h))) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            matchIdx = c;
                            break;
                        }
                    }
                }
                
                if (matchIdx != -1) {
                    int oldSize = hunkOld.size();
                    for (int r = 0; r < oldSize; r++) {
                        currentLines.remove(matchIdx);
                    }
                    currentLines.addAll(matchIdx, hunkNew);
                } else {
                    currentLines.addAll(hunkNew);
                }
                
                StringBuilder sb = new StringBuilder();
                for (String cl : currentLines) {
                    sb.append(cl).append("\n");
                }
                fileContents.put(currentFile, sb.toString());
                continue;
            }
            idx++;
        }
        return fileContents;
    }

    private java.util.Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        java.util.Map<String, String> manifestMap = new java.util.LinkedHashMap<>();
        if (commitNode == null || com.github.search5.hg4j.core.NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;
        
        String manifestHex = lines[0].trim();
        byte[] manifestNode = com.github.search5.hg4j.core.NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }
}
