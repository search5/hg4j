package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses {@code .hg/sparse} and resolves it (including {@code %include}-referenced profile
 * files tracked in the repository) into the effective include/exclude pattern sets, matching
 * real hg's {@code mercurial/sparse.py} ({@code parseconfig()}/{@code patternsforrev()})
 * exactly. {@code .hg/sparse} itself is a plain, untracked working-copy file (read directly off
 * disk, like {@code .hg/hgrc}); a profile named via {@code %include <path>} is instead a
 * <em>tracked</em> file, resolved from the manifest of a specific revision — so switching
 * revisions can change which sparse rules apply.
 */
public final class SparseConfig {
    private static final Logger LOGGER = Logger.getLogger(SparseConfig.class.getName());

    public final Set<String> includes;
    public final Set<String> excludes;
    public final Set<String> profiles;

    private SparseConfig(Set<String> includes, Set<String> excludes, Set<String> profiles) {
        this.includes = includes;
        this.excludes = excludes;
        this.profiles = profiles;
    }

    /**
     * Parses one {@code .hg/sparse}-formatted document in isolation (no {@code %include}
     * resolution) — mirrors real hg's {@code sparse.parseconfig(ui, raw, action)}.
     *
     * @throws HgValidationException on a malformed document, matching real hg's two abort
     *                                cases: a pattern line before any {@code [include]}/
     *                                {@code [exclude]} section, or an {@code [include]} section
     *                                appearing after an {@code [exclude]} section
     */
    public static SparseConfig parse(String raw) throws HgValidationException {
        Set<String> includes = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        Set<String> profiles = new LinkedHashSet<>();
        Set<String> current = null;
        boolean haveSection = false;

        if (raw != null) {
            for (String rawLine : raw.split("\n", -1)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("%include ")) {
                    String profile = line.substring("%include ".length()).trim();
                    if (!profile.isEmpty()) {
                        profiles.add(profile);
                    }
                } else if (line.equals("[include]")) {
                    if (haveSection && current != includes) {
                        throw new HgValidationException("sparse config cannot have includes after excludes");
                    }
                    haveSection = true;
                    current = includes;
                } else if (line.equals("[exclude]")) {
                    haveSection = true;
                    current = excludes;
                } else {
                    if (current == null) {
                        throw new HgValidationException(
                                "sparse config entry outside of section: " + line
                                        + " (add an [include] or [exclude] line to declare the entry type)");
                    }
                    if (line.startsWith("/")) {
                        LOGGER.log(Level.WARNING, "sparse profile cannot use paths starting with /, ignoring {0}", line);
                        continue;
                    }
                    current.add(line);
                }
            }
        }
        return new SparseConfig(includes, excludes, profiles);
    }

    /**
     * Resolves the effective sparse rules active for {@code changelogRev}: parses
     * {@code .hg/sparse} off disk, then recursively follows {@code %include} profiles by
     * reading each one's tracked content from that revision's manifest (real hg's
     * {@code readprofile}/{@code patternsforrev}). A profile missing from the manifest at this
     * revision is skipped with a warning, not an error (matching real hg's
     * {@code ManifestLookupError} handling). If the resolved includes end up non-empty, real
     * hg's {@code .hg*} auto-include rule is applied so dotfiles like {@code .hgtags} stay
     * visible.
     */
    public static SparseConfig resolveForRevision(HgRepository repository, int changelogRev) throws IOException {
        File sparseFile = new File(repository.getHgDir(), "sparse");
        if (!sparseFile.exists()) {
            return new SparseConfig(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }
        String raw = Files.readString(sparseFile.toPath(), StandardCharsets.UTF_8);
        SparseConfig root = parse(raw);

        Set<String> includes = new LinkedHashSet<>(root.includes);
        Set<String> excludes = new LinkedHashSet<>(root.excludes);
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(root.profiles);

        while (!queue.isEmpty()) {
            String profile = queue.poll();
            if (!visited.add(profile)) {
                continue;
            }
            String content = readTrackedFileAtRevision(repository, profile, changelogRev);
            if (content == null) {
                LOGGER.log(Level.WARNING, "sparse profile '{0}' not found in rev {1} - ignoring it",
                        new Object[]{profile, changelogRev});
                continue;
            }
            SparseConfig sub = parse(content);
            includes.addAll(sub.includes);
            excludes.addAll(sub.excludes);
            for (String subProfile : sub.profiles) {
                if (!visited.contains(subProfile)) {
                    queue.add(subProfile);
                }
            }
        }

        if (!includes.isEmpty()) {
            includes.add(".hg*");
        }
        return new SparseConfig(includes, excludes, visited);
    }

    private static String readTrackedFileAtRevision(HgRepository repository, String path, int changelogRev) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        if (!flIdx.exists()) {
            return null;
        }
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        for (int rev = filelog.getRevisionCount() - 1; rev >= 0; rev--) {
            if (filelog.getIndexRecord(rev).getLinkRev() <= changelogRev) {
                return new String(filelog.getRevisionContent(rev), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Builds a {@link PathFilter} from these resolved rules: a path is accepted when it matches
     * an include rule (or no include rules are configured — real hg treats an empty include set
     * as "everything") and does not match an exclude rule. When both sets are empty (sparse
     * inactive), everything is accepted, matching real hg's {@code matchmod.always()} fallback.
     */
    public PathFilter toPathFilter() {
        if (includes.isEmpty() && excludes.isEmpty()) {
            return path -> true;
        }
        SparsePathFilter includeFilter = includes.isEmpty() ? null : new SparsePathFilter(includes.toArray(new String[0]));
        SparsePathFilter excludeFilter = excludes.isEmpty() ? null : new SparsePathFilter(excludes.toArray(new String[0]));
        return path -> {
            if (includeFilter != null && !includeFilter.accept(path)) {
                return false;
            }
            return excludeFilter == null || !excludeFilter.accept(path);
        };
    }
}
