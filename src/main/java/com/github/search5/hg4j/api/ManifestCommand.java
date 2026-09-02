package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.treewalk.ManifestTreeIterator;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Porcelain command corresponding to {@code hg manifest} -- lists the files tracked at a given
 * revision, one entry per path.
 *
 * <p>Verified against real {@code hg} 7.2 on scratch repositories:
 * <ul>
 *   <li>with no revision specified, the listing matches the first parent of the working
 *       directory (not necessarily {@code tip} -- after {@code hg update -r 0} on a repo with a
 *       later tip, plain {@code hg manifest} lists rev 0's files), or is empty when no revision
 *       is checked out (a brand-new repo with zero commits, or the null parent);</li>
 *   <li>{@code hg manifest -r <rev>} lists the files tracked at that historical revision, sorted
 *       by path;</li>
 *   <li>{@code hg manifest --debug} (which also implies {@code --verbose} in real hg, per
 *       {@code ui.py}'s {@code self.verbose = self.debugflag or ...}) prints, per file, the full
 *       40-hex manifest node id for that file's content plus its mode/type: {@code "755 * "} for
 *       executable, {@code "644 @ "} for symlink, {@code "644   "} otherwise (this is the
 *       CLI-level rendering built by {@code commands.manifest} in {@code commands.py} -- it is
 *       {@code not} a literal reproduction of the raw manifest revlog line format, which instead
 *       encodes the flag as a trailing {@code x}/{@code l}/empty character with no mode digits);</li>
 *   <li>on a repository with zero commits, plain {@code hg manifest} (and even
 *       {@code hg manifest -r 0}, which real hg resolves to the null revision when the repo is
 *       completely empty) exits 0 with empty output -- never an error.</li>
 * </ul>
 *
 * <p>{@link #call()} returns the underlying data (path, full node hex, executable/symlink flags)
 * rather than pre-formatted text, matching this codebase's porcelain-returns-data convention (see
 * {@link TreeCommand}); callers render it however they like, {@code --debug}-style or otherwise.
 * {@link #setDebug(boolean)} is a pass-through toggle for callers that want to remember which
 * rendering the user asked for -- {@link #call()} always returns the complete entry data (node
 * hex included) regardless of its value, since the porcelain layer never discards information the
 * CLI-formatting layer might need.
 */
public class ManifestCommand {

    private final HgRepository repository;
    private String revision;
    private boolean debug;

    /**
     * One file tracked in a manifest: its repository-relative path, the full 40-hex node id of
     * its content at that revision, and its executable/symlink flags.
     */
    public static class ManifestEntry {
        private final String path;
        private final String nodeHex;
        private final boolean executable;
        private final boolean symlink;

        public ManifestEntry(String path, String nodeHex, boolean executable, boolean symlink) {
            this.path = path;
            this.nodeHex = nodeHex;
            this.executable = executable;
            this.symlink = symlink;
        }

        public String getPath() {
            return path;
        }

        public String getNodeHex() {
            return nodeHex;
        }

        public boolean isExecutable() {
            return executable;
        }

        public boolean isSymlink() {
            return symlink;
        }
    }

    public ManifestCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the revision to list, in any of the forms {@link NodeIdUtil#resolveRevision} accepts
     * (revision number, hex node id/prefix, or {@code "tip"}). When left unset (or set to
     * {@code null}/empty), {@link #call()} defaults to the working directory's first parent,
     * matching real {@code hg manifest} with no {@code -r}.
     */
    public ManifestCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Records whether the caller wants {@code --debug}-style rendering. Purely advisory: the data
     * {@link #call()} returns is unaffected, since it always carries the full node hex and flags
     * needed for either rendering.
     */
    public ManifestCommand setDebug(boolean debug) {
        this.debug = debug;
        return this;
    }

    public boolean isDebug() {
        return debug;
    }

    public List<ManifestEntry> call() throws IOException {
        String effectiveRevision = revision;
        if (effectiveRevision == null || effectiveRevision.isEmpty()) {
            effectiveRevision = resolveWorkingCopyParentRevision();
        }

        ManifestTreeIterator iterator = new ManifestTreeIterator(repository, effectiveRevision);
        iterator.reset();

        List<ManifestEntry> result = new ArrayList<>();
        while (iterator.next()) {
            result.add(new ManifestEntry(
                    iterator.getEntryPath(),
                    NodeIdUtil.toHex(iterator.getEntryNodeId()),
                    iterator.isExecutable(),
                    iterator.isSymlink()));
        }
        return result;
    }

    /**
     * Mirrors the "current parent of the working directory" resolution already used by
     * {@link StatusCommand} and {@link UpdateCommand}: find the changelog revision number for
     * {@code dirstate.getParent1()}, or fall back to the manifest-less "" sentinel (which
     * {@link ManifestTreeIterator} treats as an empty manifest) when the repository has no
     * commits yet or no revision is checked out.
     */
    private String resolveWorkingCopyParentRevision() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return "";
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        if (changelog.getRevisionCount() == 0) {
            return "";
        }

        Dirstate dirstate = repository.getDirstate();
        byte[] parentNode = dirstate.getParent1();
        int parentRevNum = NodeIdUtil.findRevisionByNodeId(changelog, parentNode);
        if (parentRevNum == -1) {
            return "";
        }
        return String.valueOf(parentRevNum);
    }
}
