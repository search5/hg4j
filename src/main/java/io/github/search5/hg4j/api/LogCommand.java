package io.github.search5.hg4j.api;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.Set;

/**
 * Traverses changelog revlog and retrieves commit history.
 */
public class LogCommand {
    private static final Logger LOGGER = Logger.getLogger(LogCommand.class.getName());
    private final HgRepository repository;
    private boolean followAncestors = false;
    private String startRev = null;
    private String followPath = null;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    public LogCommand(HgRepository repository) {
        this.repository = repository;
    }

    public LogCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public LogCommand setFollowAncestors(boolean follow) {
        this.followAncestors = follow;
        return this;
    }

    public LogCommand setStartRev(String startRev) {
        this.startRev = startRev;
        return this;
    }

    /**
     * Scopes the log to {@code hg log --follow <path>} semantics: starting from {@code path} at
     * the log's starting revision (see {@link #setStartRev(String)}, or the tip when unset),
     * walk backward through changelog ancestry restricted to revisions that actually touched the
     * file, crossing rename/copy boundaries along the way.
     *
     * <p>Rename-crossing uses the same mechanism real {@code hg} itself uses by default for this
     * feature: the {@code copy}/{@code copyrev} metadata embedded directly in the destination
     * file's own filelog revision 0 (written by {@link CommitCommand} whenever a commit follows
     * an {@code hg copy}/{@code hg rename}, and read back via {@link Revlog#getRevisionMetadata}),
     * <em>not</em> the changelog-level {@code SD_FILES} sidedata from backlog items 17/19. Real
     * hg's own {@code copies.usechangesetcentricalgo()} only switches to sidedata-backed copy
     * tracing when a repository was created with {@code format.use-changelog-v2} and the
     * {@code exp-copies-sidedata-changeset} requirement -- verified against a live {@code hg}
     * 7.2-created repository (plain {@code hg init}), whose {@code hg debugformat} reports
     * {@code copies-sdc: no} and {@code changelog-v2: no}, i.e. the ordinary/default case. Setting
     * this option implies {@link #setFollowAncestors(boolean)}.
     */
    public LogCommand setFollowPath(String path) {
        this.followPath = path;
        this.followAncestors = true;
        return this;
    }

    public List<HgCommit> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        if (!clIdx.exists()) {
            return Collections.emptyList();
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int totalRevisions = changelog.getRevisionCount();
        List<HgCommit> commits = new ArrayList<>();

        Set<Integer> allowedRevs = null;
        if (followPath != null) {
            int startRevNum;
            if (startRev != null) {
                byte[] resolvedNode = NodeIdUtil.resolveRevision(changelog, startRev);
                startRevNum = NodeIdUtil.findRevisionByNodeId(changelog, resolvedNode);
            } else {
                startRevNum = totalRevisions - 1;
            }
            if (startRevNum != -1 && startRevNum < totalRevisions) {
                allowedRevs = computeFollowPathRevs(changelog, startRevNum, followPath);
            } else {
                allowedRevs = Collections.emptySet();
            }
        } else if (followAncestors && startRev != null) {
            byte[] resolvedNode = NodeIdUtil.resolveRevision(changelog, startRev);
            int startRevNum = NodeIdUtil.findRevisionByNodeId(changelog, resolvedNode);
            if (startRevNum != -1) {
                ChangesetGraph graph = new ChangesetGraph(changelog);
                allowedRevs = graph.getAllAncestors(startRevNum);
            }
        }

        // Return newest commits first
        for (int rev = totalRevisions - 1; rev >= 0; rev--) {
            if (allowedRevs != null && !allowedRevs.contains(rev)) {
                continue;
            }
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            byte[] nodeId = rec.getNodeId();

            byte[] content = changelog.getRevisionContent(rev);
            String text = new String(content, StandardCharsets.UTF_8);

            // Parse text
            int firstNewline = text.indexOf('\n');
            if (firstNewline == -1) {
                LOGGER.log(Level.WARNING, "Warning: Malformed commit text at revision {0}: first newline not found", rev);
                continue;
            }
            String manifestHex = text.substring(0, firstNewline).trim();
            if (manifestHex.length() != 40) {
                LOGGER.log(Level.WARNING, "Warning: Malformed commit text at revision {0}: invalid manifest hex length", rev);
                continue;
            }
            byte[] manifestNodeId = NodeIdUtil.fromHex(manifestHex);

            int secondNewline = text.indexOf('\n', firstNewline + 1);
            if (secondNewline == -1) {
                LOGGER.log(Level.WARNING, "Warning: Malformed commit text at revision {0}: second newline not found", rev);
                continue;
            }
            String author = text.substring(firstNewline + 1, secondNewline);

            int thirdNewline = text.indexOf('\n', secondNewline + 1);
            if (thirdNewline == -1) {
                LOGGER.log(Level.WARNING, "Warning: Malformed commit text at revision {0}: third newline not found", rev);
                continue;
            }
            String dateLine = text.substring(secondNewline + 1, thirdNewline).trim();
            long timestamp = 0;
            int tzOffset = 0;
            String branch = "default";
            
            String datePart;
            String extraPart = null;
            int firstSpace = dateLine.indexOf(' ');
            if (firstSpace != -1) {
                int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
                if (secondSpace != -1) {
                    datePart = dateLine.substring(0, secondSpace);
                    extraPart = dateLine.substring(secondSpace + 1);
                } else {
                    datePart = dateLine;
                }
            } else {
                datePart = dateLine;
            }

            String[] dateParts = datePart.split(" ");
            if (dateParts.length >= 1) {
                try {
                    timestamp = Long.parseLong(dateParts[0]);
                } catch (NumberFormatException ignored) {}
            }
            if (dateParts.length >= 2) {
                try {
                    tzOffset = Integer.parseInt(dateParts[1]);
                } catch (NumberFormatException ignored) {}
            }

            if (extraPart != null && !extraPart.isEmpty()) {
                String[] extraItems = extraPart.split("\0", -1);
                for (String part : extraItems) {
                    int colonIdx = CommitCommand.findUnescapedColon(part);
                    if (colonIdx != -1) {
                        String key = part.substring(0, colonIdx);
                        String val = part.substring(colonIdx + 1);
                        key = CommitCommand.decodeExtraKey(key);
                        val = CommitCommand.decodeExtraKey(val);
                        if ("branch".equals(key)) {
                            branch = val;
                        }
                    }
                }
            }

            int doubleNewline = text.indexOf("\n\n", thirdNewline + 1);
            List<String> files = new ArrayList<>();
            String message = "";
            if (thirdNewline + 1 < text.length() && text.charAt(thirdNewline + 1) == '\n') {
                message = text.substring(thirdNewline + 2);
            } else if (doubleNewline != -1) {
                String filesPart = text.substring(thirdNewline + 1, doubleNewline);
                for (String line : filesPart.split("\n")) {
                    if (!line.isEmpty()) {
                        files.add(line);
                    }
                }
                message = text.substring(doubleNewline + 2);
            } else {
                message = text.substring(thirdNewline + 1);
            }

            if (treeFilter != null && treeFilter != HgTreeFilter.ALL) {
                boolean matched = false;
                for (String file : files) {
                    if (treeFilter.accept(file)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
            }

            commits.add(new HgCommit(rev, new NodeId(nodeId), new NodeId(manifestNodeId), author, timestamp, tzOffset, files, message, branch));
        }

        return commits;
    }

    /**
     * Computes the set of changelog revisions {@code hg log --follow initialPath} would show,
     * starting from {@code startRevNum}: every revision -- restricted to ancestors of
     * {@code startRevNum} -- that touched {@code initialPath}'s filelog, plus (once the walk
     * reaches back to that filelog's very first revision, where real hg's rename metadata always
     * lives) every revision that touched whatever path it was renamed/copied from, transitively.
     *
     * <p>Crossing a rename boundary reads the {@code copy} key filelog revision 0 carries in its
     * own metadata header (see {@link Revlog#getRevisionMetadata}) -- the classic, filelog-level
     * mechanism real hg's default (non-changeset-centric) copy tracing itself uses, not the
     * changelog {@code SD_FILES} sidedata from backlog items 17/19 (see {@link #setFollowPath}).
     */
    private Set<Integer> computeFollowPathRevs(Revlog changelog, int startRevNum, String initialPath) throws IOException {
        ChangesetGraph graph = new ChangesetGraph(changelog);
        Set<Integer> ancestorsOfStart = graph.getAllAncestors(startRevNum);

        Set<Integer> matched = new LinkedHashSet<>();
        Set<String> visitedPaths = new HashSet<>();
        String currentPath = initialPath;

        while (currentPath != null && visitedPaths.add(currentPath)) {
            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), currentPath);
            if (!flIdx.exists()) {
                break;
            }
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(flIdx, flDat);
            int fileRevCount = filelog.getRevisionCount();
            if (fileRevCount == 0) {
                break;
            }

            boolean anyMatched = false;
            for (int fr = 0; fr < fileRevCount; fr++) {
                int linkRev = filelog.getIndexRecord(fr).getLinkRev();
                if (ancestorsOfStart.contains(linkRev)) {
                    matched.add(linkRev);
                    anyMatched = true;
                }
            }
            if (!anyMatched) {
                break;
            }

            // Real hg's filelog.renamed() only ever finds copy metadata on the revision whose
            // filelog parent is null -- which, for a rename/copy destination, is always revision
            // 0 of its (brand new) filelog. Only cross the boundary once the walk has actually
            // reached back that far within the requested ancestry.
            int firstRevLinkRev = filelog.getIndexRecord(0).getLinkRev();
            if (!ancestorsOfStart.contains(firstRevLinkRev)) {
                break;
            }

            Map<String, String> meta = filelog.getRevisionMetadata(0);
            String copySource = meta.get("copy");
            if (copySource == null || copySource.isEmpty()) {
                break;
            }
            currentPath = copySource;
        }

        return matched;
    }
}
