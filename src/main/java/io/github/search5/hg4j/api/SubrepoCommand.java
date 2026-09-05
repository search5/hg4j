package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.submodule.GitSubrepoUtil;
import io.github.search5.hg4j.submodule.SvnSubrepoUtil;
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

                    boolean isGitSub = false;
                    boolean isSvnSub = false;
                    if (url.startsWith("[git]")) {
                        isGitSub = true;
                        url = url.substring("[git]".length()).trim();
                    } else if (url.startsWith("[svn]")) {
                        isSvnSub = true;
                        url = url.substring("[svn]".length()).trim();
                    }

                    // Find configured revision in .hgsubstate; null means "no pinned revision"
                    // (leave the freshly cloned subrepo's own tip dirstate untouched).
                    //
                    // Backlog 41 bugfix: this used to grab a fixed-width `substring(0, 40)`,
                    // which assumed every recorded revision is a 40-hex-char hg/git sha. A svn
                    // subrepo's .hgsubstate revision is instead a plain (variable-length, often
                    // much shorter) revision number -- e.g. "1 sub" -- so the fixed-width read
                    // would either grab trailing garbage past the real revision or throw
                    // StringIndexOutOfBoundsException outright on a short line. Split on the
                    // first whitespace instead, matching real hg's own `.hgsubstate` line format
                    // ("<revision> <path>") for every subrepo type uniformly.
                    String rev = null;
                    for (String stateLine : hgsubstateLines) {
                        String trimmedState = stateLine.trim();
                        if (trimmedState.isEmpty()) {
                            continue;
                        }
                        int sep = -1;
                        for (int i = 0; i < trimmedState.length(); i++) {
                            char c = trimmedState.charAt(i);
                            if (c == ' ' || c == '\t') {
                                sep = i;
                                break;
                            }
                        }
                        if (sep == -1) {
                            continue;
                        }
                        if (trimmedState.substring(sep + 1).trim().equals(path)) {
                            rev = trimmedState.substring(0, sep).trim();
                            break;
                        }
                    }

                    File subrepoDir = new File(repository.getDirectory(), path);

                    // Backlog 41: a [git]-prefixed entry previously fell straight through to the
                    // plain-hg branches below, which would try to `hg clone` the literal string
                    // "[git]<url>" as an hg source (or, once the prefix above is stripped, an
                    // https URL that happens to be a GIT remote, silently producing a broken/
                    // empty hg-format checkout) -- git subrepos were never actually usable via
                    // this command despite CommitCommand/UpdateCommand/MergeCommand/CloneCommand
                    // already fully supporting them. Dispatch git (and the new svn) entries to
                    // their own type-aware checkout helpers instead, matching those commands.
                    if (isGitSub) {
                        checkoutGitEntry(subrepoDir, url, rev);
                        continue;
                    }
                    if (isSvnSub) {
                        checkoutSvnEntry(subrepoDir, url, rev);
                        continue;
                    }

                    // Recursively clone/initialize subrepo in target path from configured URL
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

    /**
     * Checks out a {@code [git]}-prefixed {@code .hgsub} entry (backlog 41 fix -- see the class
     * comment above): clones it if not already a git checkout, then checks out the pinned
     * revision (fetching first if it isn't available locally yet).
     */
    private static void checkoutGitEntry(File subrepoDir, String url, String rev) throws IOException {
        if (!GitSubrepoUtil.isGitCheckout(subrepoDir)) {
            if (url.isEmpty()) {
                throw new IOException("Subrepo URL cannot be null or empty for path: " + subrepoDir.getName());
            }
            GitSubrepoUtil.clone(subrepoDir.getParentFile(), url, subrepoDir);
        }
        if (rev != null && !rev.isEmpty()) {
            if (!GitSubrepoUtil.hasLocally(subrepoDir, rev)) {
                try {
                    GitSubrepoUtil.fetch(subrepoDir);
                } catch (IOException e) {
                    // Best-effort, matching UpdateCommand.checkoutGitSubrepo's own tolerance.
                }
            }
            GitSubrepoUtil.checkout(subrepoDir, rev);
        }
    }

    /**
     * Checks out a {@code [svn]}-prefixed {@code .hgsub} entry (backlog 41): {@code svn checkout
     * --force <url>@<rev>} when a revision is pinned, matching real hg's own {@code
     * svnsubrepo.get()} (see {@link SvnSubrepoUtil#get}); a plain HEAD checkout when the entry
     * has been declared but not yet recorded in {@code .hgsubstate} (no pinned revision exists
     * yet).
     */
    private static void checkoutSvnEntry(File subrepoDir, String url, String rev) throws IOException {
        if (rev != null && !rev.isEmpty()) {
            if (url.isEmpty() && !SvnSubrepoUtil.isSvnCheckout(subrepoDir)) {
                throw new IOException("Subrepo URL cannot be null or empty for path: " + subrepoDir.getName());
            }
            SvnSubrepoUtil.get(subrepoDir.getParentFile(), url, rev, subrepoDir);
        } else if (!SvnSubrepoUtil.isSvnCheckout(subrepoDir)) {
            if (url.isEmpty()) {
                throw new IOException("Subrepo URL cannot be null or empty for path: " + subrepoDir.getName());
            }
            SvnSubrepoUtil.checkoutHead(subrepoDir.getParentFile(), url, subrepoDir);
        }
    }
}
