package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.charset.StandardCharsets;

/**
 * Porcelain command to perform narrow/sparse clone.
 * Limits the cloned repository's metadata track to specific include/exclude path patterns.
 */
public final class NarrowCloneCommand {
    private String sourceUrl;
    private File directory;
    private final List<String> includePaths = new ArrayList<>();
    private final List<String> excludePaths = new ArrayList<>();

    public NarrowCloneCommand() {}

    public NarrowCloneCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    public NarrowCloneCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public NarrowCloneCommand addIncludePath(String prefix) {
        if (prefix != null) {
            includePaths.add(prefix);
        }
        return this;
    }

    public NarrowCloneCommand addExcludePath(String prefix) {
        if (prefix != null) {
            excludePaths.add(prefix);
        }
        return this;
    }

    /**
     * Executes the narrow clone and checkout.
     *
     * @return cloned repository facade
     * @throws IOException if network or sparse file writing fails
     */
    public Hg call() throws IOException, HgLockException {
        if (sourceUrl == null || directory == null) {
            throw new IllegalStateException("Source URL and directory must be specified.");
        }

        // 1. Initialize empty repository
        HgRepository repo = Hg.init().setDirectory(directory).call();
        Hg hg = Hg.wrap(repo);

        // 2. Normalize/validate patterns exactly like real hg's narrowspec.normalizepattern():
        // default to the "path:" kind, strip a trailing "/", reject "."/".."/empty components
        // and unsupported kind prefixes (glob:/re:/etc are not legal in a narrowspec).
        List<HgTreeFilter.NarrowPattern> normalizedIncludes = new ArrayList<>();
        for (String inc : includePaths) {
            normalizedIncludes.add(HgTreeFilter.normalizeNarrowPattern(inc));
        }
        List<HgTreeFilter.NarrowPattern> normalizedExcludes = new ArrayList<>();
        for (String ex : excludePaths) {
            normalizedExcludes.add(HgTreeFilter.normalizeNarrowPattern(ex));
        }

        // 3. Mark the repository as a narrow clone. Real hg (verified against hg 7.2's "narrow"
        // extension) records this as the "narrowhg-experimental" requirement in .hg/requires --
        // NOT a "narrowspec" requirement.
        File requiresFile = new File(repo.getHgDir(), "requires");
        List<String> requirements = new ArrayList<>(Files.readAllLines(requiresFile.toPath(), StandardCharsets.UTF_8));
        requirements.add("narrowhg-experimental");
        SafeFileIO.writeLinesAtomic(requiresFile, requirements);

        // 4. Write the narrowspec itself. Real hg keeps two copies: the authoritative one in
        // .hg/store/narrowspec (server/store side, format() in mercurial/narrowspec.py: a
        // "[include]"/"[exclude]" ini-like format, singular section names, sorted "kind:path"
        // patterns with includes-minus-excludes written under [include]) and a working-copy
        // mirror at .hg/narrowspec.dirstate (mercurial/narrowspec.py:copytoworkingcopy) which
        // starts out identical right after clone.
        String specText = formatNarrowSpec(normalizedIncludes, normalizedExcludes);
        File storeNarrowSpecFile = new File(repo.getStoreDir(), "narrowspec");
        SafeFileIO.writeStringAtomic(storeNarrowSpecFile, specText);
        File dirstateNarrowSpecFile = new File(repo.getHgDir(), "narrowspec.dirstate");
        SafeFileIO.writeStringAtomic(dirstateNarrowSpecFile, specText);

        // 5. Establish pull with TreeFilter integration (emulates narrow clone segment mapping).
        // Uses the narrow-spec-correct matcher (path:/rootfilesin: with component-boundary
        // matching, "no includes" == match nothing) rather than the generic
        // createPathPrefixFilter, which intentionally has different (non-narrow) defaults for
        // its other callers.
        HgTreeFilter pathFilter = HgTreeFilter.createNarrowSpecFilter(normalizedIncludes, normalizedExcludes);

        // Perform the standard SCM clone/pull
        hg.pull().setSource(sourceUrl).setTreeFilter(pathFilter).call();

        // Pre-existing bug found while working on backlog 30 (2026-09-04): without this, the
        // manifest Revlog instance the pull just wrote to stays cached from before the write
        // (or from an earlier incidental read during Hg.init()), and the very next `hg.update()`
        // reads it with stale index/offset state -- deterministically throwing
        // HgCorruptDataException ("Failed to read complete hunk ... at offset 64") while decoding
        // the manifest it just fetched. 100% reproducible on unmodified code (bisected via git
        // stash against a pristine checkout), affecting every narrow clone, not anything
        // backlog-30-specific. FetchCommand's own clonebundle path already does this same
        // invalidation after writing (see its `repository.clearRevlogCache()` call) -- this was
        // simply the one write-then-immediately-read call site that had been missed.
        repo.clearRevlogCache();

        // 6. sparse working copy update
        hg.update().setTreeFilter(pathFilter).call();

        return hg;
    }

    /**
     * Renders the narrowspec text exactly like real hg's {@code narrowspec.format()}: a
     * "[include]" section (only emitted when includes is non-empty) listing
     * {@code sorted(includes - excludes)}, followed by an "[exclude]" section (only emitted when
     * excludes is non-empty) listing the sorted excludes.
     */
    private static String formatNarrowSpec(List<HgTreeFilter.NarrowPattern> includes, List<HgTreeFilter.NarrowPattern> excludes) {
        List<String> excludeStrings = new ArrayList<>();
        for (HgTreeFilter.NarrowPattern ex : excludes) {
            excludeStrings.add(ex.toSpecString());
        }

        StringBuilder sb = new StringBuilder();
        if (!includes.isEmpty()) {
            List<String> includeStrings = new ArrayList<>();
            for (HgTreeFilter.NarrowPattern inc : includes) {
                String s = inc.toSpecString();
                if (!excludeStrings.contains(s)) {
                    includeStrings.add(s);
                }
            }
            includeStrings.sort(null);
            sb.append("[include]\n");
            for (String s : includeStrings) {
                sb.append(s).append("\n");
            }
        }
        if (!excludeStrings.isEmpty()) {
            List<String> sortedExcludes = new ArrayList<>(excludeStrings);
            sortedExcludes.sort(null);
            sb.append("[exclude]\n");
            for (String s : sortedExcludes) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }
}
