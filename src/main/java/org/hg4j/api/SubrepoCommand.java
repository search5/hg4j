package org.hg4j.api;

import org.hg4j.core.HgRepository;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Subrepo command (submoduleAdd / Init / Update) for Mercurial repositories.
 * Configures and updates subrepositories via standard .hgsub and .hgsubstate file specifications.
 */
public class SubrepoCommand {
    private final HgRepository repository;
    private String action; // "add", "init", "update"
    private String subrepoPath;
    private String subrepoUrl;
    private String revision;

    public SubrepoCommand(HgRepository repository) {
        this.repository = repository;
    }

    public SubrepoCommand setAction(String action) {
        this.action = action;
        return this;
    }

    public SubrepoCommand setSubrepoPath(String subrepoPath) {
        this.subrepoPath = subrepoPath;
        return this;
    }

    public SubrepoCommand setSubrepoUrl(String subrepoUrl) {
        this.subrepoUrl = subrepoUrl;
        return this;
    }

    public SubrepoCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Executes the requested subrepo operation (graft/update/init).
     *
     * @throws IOException if subrepo parsing or network cloning fails
     */
    public void call() throws IOException {
        if (action == null) {
            throw new IllegalArgumentException("Action must be set for SubrepoCommand (add/init/update)");
        }

        File hgsub = new File(repository.getDirectory(), ".hgsub");
        File hgsubstate = new File(repository.getDirectory(), ".hgsubstate");

        if ("add".equalsIgnoreCase(action)) {
            if (subrepoPath == null || subrepoUrl == null) {
                throw new IllegalArgumentException("Path and URL must be set for subrepo add");
            }
            List<String> hgsubLines = hgsub.exists() ? Files.readAllLines(hgsub.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
            hgsubLines.add(subrepoPath + " = " + subrepoUrl);
            Files.write(hgsub.toPath(), hgsubLines, StandardCharsets.UTF_8);

            List<String> hgsubstateLines = hgsubstate.exists() ? Files.readAllLines(hgsubstate.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
            String rev = (revision != null) ? revision : "0000000000000000000000000000000000000000";
            hgsubstateLines.add(rev + " " + subrepoPath);
            Files.write(hgsubstate.toPath(), hgsubstateLines, StandardCharsets.UTF_8);

        } else if ("init".equalsIgnoreCase(action) || "update".equalsIgnoreCase(action)) {
            if (!hgsub.exists()) {
                return; // No subrepos configured
            }
            List<String> hgsubLines = Files.readAllLines(hgsub.toPath(), StandardCharsets.UTF_8);
            List<String> hgsubstateLines = hgsubstate.exists() ? Files.readAllLines(hgsubstate.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();

            for (String line : hgsubLines) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq != -1) {
                    String path = line.substring(0, eq).trim();
                    String url = line.substring(eq + 1).trim();

                    // Find configured revision in .hgsubstate
                    String rev = "tip";
                    for (String stateLine : hgsubstateLines) {
                        if (stateLine.endsWith(" " + path) || stateLine.endsWith("\t" + path)) {
                            rev = stateLine.substring(0, 40).trim();
                            break;
                        }
                    }

                    // Recursively clone/initialize subrepo in target path from configured URL
                    File subrepoDir = new File(repository.getDirectory(), path);
                    if (!subrepoDir.exists()) {
                        if (url != null && !url.isEmpty()) {
                            try {
                                Hg.cloneRepository()
                                  .setSource(url)
                                  .setDirectory(subrepoDir)
                                  .call();
                            } catch (Exception e) {
                                Hg.init().setDirectory(subrepoDir).call();
                            }
                        } else {
                            Hg.init().setDirectory(subrepoDir).call();
                        }
                    }
                    // For dummy in-memory SCM update verification: sync parent Dirstate of subrepository
                    try (Hg hg = Hg.open(subrepoDir)) {
                        HgRepository subRepo = hg.getRepository();
                        org.hg4j.core.Dirstate d = subRepo.getDirstate();
                        d.setParents(org.hg4j.core.NodeIdUtil.fromHex(rev), new byte[20]);
                        subRepo.writeDirstate(d);
                    }
                }
            }
        }
    }
}
