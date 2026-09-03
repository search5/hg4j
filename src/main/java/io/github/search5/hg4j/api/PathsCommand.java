package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.util.Map;
import java.util.TreeMap;

/**
 * Porcelain equivalent of {@code hg paths}: lists the {@code [paths]} aliases registered in
 * the repository's {@code .hg/hgrc} (e.g. {@code default}, {@code default-push}).
 *
 * <p>Real hg (7.2) prints these as {@code name = url} lines sorted alphabetically by name
 * (see {@code mercurial/commands.py}'s {@code paths()} and
 * {@code mercurial/utils/urlutil.py}'s {@code list_paths()}, which does
 * {@code sorted(ui.paths.items())}); a repo with no {@code [paths]} section prints nothing
 * and exits 0. This command mirrors that: {@link #call()} returns the alias-to-URL pairs in
 * that same alphabetical order.</p>
 *
 * <p>Real hg also supports per-path sub-options such as {@code default:pushurl = ...},
 * displayed as their own {@code name:subopt = value} lines. That is a separate, larger
 * feature ({@code mercurial/ui.py}'s {@code configsuboptions}/path-suboption handling) and is
 * out of scope here; only the base {@code name = url} aliases are exposed.</p>
 */
public class PathsCommand {
    private final HgRepository repository;

    public PathsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns all {@code [paths]} aliases defined in the repository configuration.
     *
     * @return an alphabetically-ordered (by alias name) map of alias name to URL; empty
     *         (never {@code null}) if no {@code [paths]} section is configured
     */
    public Map<String, String> call() {
        Map<String, String> section = repository.getConfig().getSection("paths");
        return new TreeMap<>(section);
    }
}
