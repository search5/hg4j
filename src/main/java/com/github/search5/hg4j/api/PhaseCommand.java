package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase command for querying or setting the SCM phase (public, draft, secret)
 * of specific changeset revisions in Mercurial repositories.
 */
public class PhaseCommand {
    private final HgRepository repository;
    private String revision;
    private int forcePhase = -1; // 0=public, 1=draft, 2=secret

    public PhaseCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PhaseCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public PhaseCommand setPhase(int phase) {
        this.forcePhase = phase;
        return this;
    }

    /**
     * Executes phase query or phase modification.
     * Synchronizes updates to the '.hg/store/phaseroots' file standard.
     *
     * @return SCM phase value (0=public, 1=draft, 2=secret)
     * @throws IOException if phase IO fails
     */
    public int call() throws IOException {
        File phaseRootsFile = new File(repository.getStoreDir(), "phaseroots");

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] nodeBytes = com.github.search5.hg4j.util.NodeIdUtil.resolveRevision(changelog, revision);
        if (nodeBytes == null) {
            throw new IOException("Phase error: Revision not found in repository: " + revision);
        }

        List<String> phaseRootsLines = phaseRootsFile.exists() ? Files.readAllLines(phaseRootsFile.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();

        if (forcePhase != -1) {
            // Write/Update phase of this node
            List<String> newLines = new ArrayList<>();
            String targetHex = NodeIdUtil.toHex(nodeBytes);
            
            boolean found = false;
            for (String line : phaseRootsLines) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    int ph = Integer.parseInt(parts[0]);
                    String hex = parts[1];
                    if (hex.equalsIgnoreCase(targetHex)) {
                        newLines.add(forcePhase + " " + targetHex);
                        found = true;
                    } else {
                        newLines.add(line);
                    }
                }
            }
            if (!found) {
                newLines.add(forcePhase + " " + targetHex);
            }
            phaseRootsFile.getParentFile().mkdirs();
            Files.write(phaseRootsFile.toPath(), newLines, StandardCharsets.UTF_8);
            return forcePhase;
        } else {
            // Query phase: default is public (0) if not registered in phaseroots
            String targetHex = NodeIdUtil.toHex(nodeBytes);
            for (String line : phaseRootsLines) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    int ph = Integer.parseInt(parts[0]);
                    String hex = parts[1];
                    if (hex.equalsIgnoreCase(targetHex)) {
                        return ph;
                    }
                }
            }
            return 0; // Public phase default
        }
    }
}
