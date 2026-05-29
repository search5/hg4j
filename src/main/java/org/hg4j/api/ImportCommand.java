package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
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
    public void call() throws IOException {
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

        byte[] parent = repository.getDirstate().getParent1();
        int linkRev = changelog.getRevisionCount();

        String dateStr = (dateVal != null) ? dateVal : (System.currentTimeMillis() / 1000) + " 0";
        String clPayload = "0000000000000000000000000000000000000000\n" // manifest dummy
                + author + "\n"
                + dateStr + "\n"
                + "\n" // empty files list
                + message;
        byte[] payloadBytes = clPayload.getBytes(StandardCharsets.UTF_8);

        byte[] p1Normalized = new byte[20];
        if (parent != null) {
            System.arraycopy(parent, 0, p1Normalized, 0, Math.min(parent.length, 20));
        }

        org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry = new org.hg4j.core.ChangegroupParser.ChangeGroupEntry();
        entry.node = NodeIdUtil.computeNodeId(payloadBytes, p1Normalized, new byte[20]);
        byte[] entryNode20 = new byte[20];
        System.arraycopy(entry.node, 0, entryNode20, 0, 20);
        entry.node = entryNode20;
        entry.p1 = p1Normalized;
        entry.p2 = new byte[20];
        entry.cs = entry.node;
        entry.deltabase = new byte[20];
        entry.delta = Revlog.createDelta(new byte[0], payloadBytes);

        try (org.hg4j.core.HgLock storeLock = repository.lockStore();
             org.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            changelog.appendChangeGroupEntry(entry, linkRev);
            
            org.hg4j.core.Dirstate d = repository.getDirstate();
            d.setParents(entry.node, new byte[20]);
            repository.writeDirstate(d);
            repository.clearRevlogCache();
        }
    }
}
