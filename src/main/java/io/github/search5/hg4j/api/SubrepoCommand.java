package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;
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
    public void call() throws IOException, HgLockException {
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

                    // Find configured revision in .hgsubstate; null means "no pinned revision"
                    // (leave the freshly cloned subrepo's own tip dirstate untouched).
                    String rev = null;
                    for (String stateLine : hgsubstateLines) {
                        if (stateLine.endsWith(" " + path) || stateLine.endsWith("\t" + path)) {
                            rev = stateLine.substring(0, 40).trim();
                            break;
                        }
                    }

                    // Recursively clone/initialize subrepo in target path from configured URL
                    File subrepoDir = new File(repository.getDirectory(), path);
                    if (!subrepoDir.exists()) {
                        if (!url.isEmpty()) {
                            Hg.cloneRepository()
                              .setSource(url)
                              .setDirectory(subrepoDir)
                              .call();
                            // A plain clone checks out its own tip, which may not be the revision
                            // pinned in .hgsubstate (e.g. the source has advanced past the pin) --
                            // force the working copy to the exact pinned revision, matching real
                            // hg's `hg update -S`. Previously this only rewrote the subrepo's
                            // dirstate parent pointer without touching any working-copy file,
                            // leaving file content silently mismatched with the recorded pin
                            // whenever it wasn't already the clone's default tip checkout.
                            if (rev != null) {
                                try (Hg hg = Hg.open(subrepoDir)) {
                                    new UpdateCommand(hg.getRepository()).setRevision(rev).setForce(true).call();
                                }
                            }
                        } else {
                            throw new IOException("Subrepo URL cannot be null or empty for path: " + path);
                        }
                    } else if (new File(subrepoDir, ".hg").exists()) {
                        // Already an initialized subrepo checkout -- bring its working copy in
                        // line with the pinned revision, exactly like real hg's `hg update -S`
                        // (pulling first if the pin hasn't been fetched locally yet, e.g. after
                        // .hgsubstate was bumped to a revision produced elsewhere). Previously
                        // this branch only rewrote the subrepo's dirstate parent pointer -- it
                        // never pulled the new pin nor actually checked out matching file
                        // content, so a bumped .hgsubstate left the subrepo's working copy
                        // silently stale.
                        if (rev != null) {
                            try (Hg hg = Hg.open(subrepoDir)) {
                                HgRepository subRepo = hg.getRepository();
                                if (!url.isEmpty() && !UpdateCommand.isRevisionPresentLocally(subRepo, rev)) {
                                    try {
                                        hg.pull().setSource(url).call();
                                    } catch (Exception e) {
                                        // Best-effort, matching
                                        // UpdateCommand.checkoutSubrepoEntry's own tolerance for
                                        // an unreachable/stale source URL.
                                    }
                                }
                                new UpdateCommand(subRepo).setRevision(rev).setForce(true).call();
                            }
                        }
                    }
                    // else: an existing non-hg directory already occupies the path -- leave it
                    // untouched rather than overwriting arbitrary unrelated content.
                }
            }
        }
    }
}
