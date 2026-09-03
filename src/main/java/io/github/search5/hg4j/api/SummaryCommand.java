package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;
import io.github.search5.hg4j.lib.NodeId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Porcelain command corresponding to {@code hg summary} — a condensed overview of the working
 * copy: parent(s), branch, active bookmark, working-copy status counts, and phase.
 *
 * <p>hg4j is a library rather than a CLI, so this returns a structured {@link SummaryInfo} record
 * instead of formatted text (unlike the real {@code hg summary} command's terminal output).</p>
 */
public class SummaryCommand {
    private final HgRepository repository;

    public SummaryCommand(HgRepository repository) {
        this.repository = repository;
    }

    public record ParentInfo(int revision, String node, String description) {}

    public record SummaryInfo(
            List<ParentInfo> parents,
            String branch,
            String activeBookmark,
            int modified,
            int added,
            int removed,
            int unknown,
            boolean mergeInProgress,
            PhaseRoots.Phase currentPhase) {}

    public SummaryInfo call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;

        List<String> parentHexes = new ParentsCommand(repository).call();
        List<ParentInfo> parents = new ArrayList<>();
        for (String hex : parentHexes) {
            if (changelog != null) {
                int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
                String desc = "";
                if (rev != -1) {
                    try {
                        Map<String, String> lines = parseChangelogHeader(changelog.getRevisionContent(rev));
                        desc = lines.getOrDefault("desc", "");
                    } catch (Exception ignored) {}
                }
                parents.add(new ParentInfo(rev, hex, desc));
            } else {
                parents.add(new ParentInfo(-1, hex, ""));
            }
        }

        String branch = repository.getBranch();
        String activeBookmark = new BookmarkCommand(repository).getActiveBookmark();

        Status status = new StatusCommand(repository).call();

        PhaseRoots.Phase phase = PhaseRoots.Phase.PUBLIC;
        if (!parents.isEmpty() && changelog != null) {
            try {
                PhaseRoots phaseRoots = repository.getPhaseRoots();
                byte[] p1 = NodeIdUtil.fromHex(parents.get(0).node());
                phase = phaseRoots.getPhase(new NodeId(p1), changelog);
            } catch (Exception ignored) {}
        }

        return new SummaryInfo(
                parents,
                branch,
                activeBookmark,
                status.getModified().size(),
                status.getAdded().size(),
                status.getRemoved().size(),
                status.getUntracked().size(),
                parents.size() > 1,
                phase);
    }

    private Map<String, String> parseChangelogHeader(byte[] content) {
        Map<String, String> result = new HashMap<>();
        String text = new String(content, StandardCharsets.UTF_8);
        int blank = text.indexOf("\n\n");
        if (blank != -1 && blank + 2 <= text.length()) {
            result.put("desc", text.substring(blank + 2).trim());
        }
        return result;
    }
}
