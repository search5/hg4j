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

    /**
     * Creates a narrow-clone command. The source and the target directory must be set before {@link #call()}.
     */
    public NarrowCloneCommand() {}

    /**
     * Sets the URL of the repository to clone. There is no default; {@link #call()} throws {@link IllegalStateException} if it is not set.
     * @param sourceUrl URL of the source repository
     * @return {@code this}
     */
    public NarrowCloneCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * Sets the directory in which the new repository is created. There is no default; {@link #call()} throws {@link IllegalStateException} if it is not set.
     * @param directory the working directory of the new repository
     * @return {@code this}
     */
    public NarrowCloneCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    private String shape;
    /** The patterns come from a shape (internal representation, {@code path:.} for the root), not from the user. */
    private boolean internalPatterns;

    /**
     * {@code hg clone --shape NAMES}: clone the store shape of these names (comma separated) of a server that advertises
     * {@code exp-shape-1}. The server is asked for the shape's patterns and fingerprint first ({@code store_shape}); they become the
     * narrowspec, the names are kept in {@code store-shape}, and every later pull names the shape and its fingerprint instead of sending
     * patterns.
     */
    public NarrowCloneCommand setShape(String names) {
        this.shape = names;
        return this;
    }

    /**
     * Adds a path pattern to the narrowspec includes ({@code hg clone --include}). A pattern without a kind prefix is a {@code path:} pattern. Patterns are normalized and validated when {@link #call()} runs, which rejects unsupported kinds and invalid components.
     * <p>When a shape is set with {@link #setShape(String)}, the includes and excludes added here are replaced by the shape's patterns.</p>
     * @param prefix the pattern to include; {@code null} is ignored
     * @return {@code this}
     */
    public NarrowCloneCommand addIncludePath(String prefix) {
        if (prefix != null) {
            includePaths.add(prefix);
        }
        return this;
    }

    /**
     * Adds a path pattern to the narrowspec excludes ({@code hg clone --exclude}). Normalization and validation are the same as for {@link #addIncludePath(String)}.
     * @param prefix the pattern to exclude; {@code null} is ignored
     * @return {@code this}
     */
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

        // clone: the shape is asked for before anything is created, so a refusal leaves no repository behind
        List<String> shapeNames = null;
        if (shape != null) {
            shapeNames = io.github.search5.hg4j.shape.ShapeNames.parse(shape);
            try (io.github.search5.hg4j.transport.HgRemoteConnection connection =
                         io.github.search5.hg4j.transport.HgRemoteConnectionFactory.createConnection(sourceUrl)) {
                if (!connection.getCapabilities().contains("exp-shape-1")) {
                    throw new io.github.search5.hg4j.errors.HgValidationException(
                            "cannot use store shapes; remote repository does not support the 'exp-shape-1' capability");
                }
                io.github.search5.hg4j.shape.ShapeWire.Reply reply = connection.storeShape(shapeNames);
                // the peer converts the plain paths of the shape to the legacy patterns (the root is path:.), which go to the narrowspec as
                // they are: they are not user input, so they are not normalized again
                io.github.search5.hg4j.narrow.NarrowSpec.Patterns legacy = io.github.search5.hg4j.narrow.NarrowSpec.toLegacyPatterns(
                        reply.patterns().includes(), reply.patterns().excludes());
                shapeFingerprints = reply.fingerprints().stream().map(io.github.search5.hg4j.shape.Fingerprint::toText).toList();
                shapeShardsSets = reply.shardsSets().stream()
                        .map(set -> set.stream().map(io.github.search5.hg4j.shape.Fingerprint::toText)
                                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)))
                        .collect(java.util.stream.Collectors.<java.util.Set<String>>toList());
                includePaths.clear();
                excludePaths.clear();
                includePaths.addAll(legacy.includes());
                excludePaths.addAll(legacy.excludes());
                if (!excludePaths.isEmpty() && includePaths.isEmpty()) {
                    includePaths.add("path:."); // include everything by default if only exclusion patterns are defined
                }
                internalPatterns = true;
            }
        }

        // 1. Initialize empty repository
        HgRepository repo = Hg.init().setDirectory(directory).call();
        Hg hg = Hg.wrap(repo);

        // 2. Normalize/validate patterns exactly like real hg's narrowspec.normalizepattern():
        // default to the "path:" kind, strip a trailing "/", reject "."/".."/empty components
        // and unsupported kind prefixes (glob:/re:/etc are not legal in a narrowspec).
        List<HgTreeFilter.NarrowPattern> normalizedIncludes = new ArrayList<>();
        for (String inc : includePaths) {
            normalizedIncludes.add(internalPatterns ? HgTreeFilter.internalNarrowPattern(inc) : HgTreeFilter.normalizeNarrowPattern(inc));
        }
        List<HgTreeFilter.NarrowPattern> normalizedExcludes = new ArrayList<>();
        for (String ex : excludePaths) {
            normalizedExcludes.add(internalPatterns ? HgTreeFilter.internalNarrowPattern(ex) : HgTreeFilter.normalizeNarrowPattern(ex));
        }

        // 3. Mark the repository as a narrow clone. Real hg (verified against hg 7.2's "narrow"
        // extension) records this as the "narrowhg-experimental" requirement in .hg/requires --
        // NOT a "narrowspec" requirement.
        // Like real hg's clone (destwlock = destrepo.wlock()): the destination is locked from the moment it exists until the last update.
        try (io.github.search5.hg4j.lib.HgLock destWlock = repo.lockWorkingCopy()) {
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
            // the narrowspec in the store is changed under the store lock too (hg: `with repo.wlock(), repo.lock()`)
            try (io.github.search5.hg4j.lib.HgLock storeLock = repo.lockStore()) {
                SafeFileIO.writeStringAtomic(storeNarrowSpecFile, specText);
            }
            File dirstateNarrowSpecFile = new File(repo.getHgDir(), "narrowspec.dirstate");
            SafeFileIO.writeStringAtomic(dirstateNarrowSpecFile, specText);

            if (shapeNames != null) {
                // narrow-clone transaction: the names of the shape, written under the store lock before the pull that depends on them
                try (io.github.search5.hg4j.lib.HgLock storeLock = repo.lockStore()) {
                    io.github.search5.hg4j.shape.StoreShapeFile.write(repo, shapeNames);
                }
            }

            // 5. Establish pull with TreeFilter integration (emulates narrow clone segment mapping).
            // Uses the narrow-spec-correct matcher (path:/rootfilesin: with component-boundary
            // matching, "no includes" == match nothing) rather than the generic
            // createPathPrefixFilter, which intentionally has different (non-narrow) defaults for
            // its other callers.
            HgTreeFilter pathFilter = HgTreeFilter.createNarrowSpecFilter(normalizedIncludes, normalizedExcludes);

            // Perform the standard SCM clone/pull
            PullCommand pull = hg.pull().setSource(sourceUrl).setTreeFilter(pathFilter).setStoreFingerprints(shapeFingerprints).setShardsSets(shapeShardsSets);
            pull.call();
            messages.addAll(pull.getMessages());

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
    }

    private final java.util.List<String> messages = new java.util.ArrayList<>();

    /** What the clone reported (the clone bundles applied, ...). */
    public java.util.List<String> getMessages() {
        return messages;
    }

    private java.util.List<String> shapeFingerprints;
    private java.util.List<java.util.Set<String>> shapeShardsSets;

    /** {@code narrowspec.format}. */
    private static String formatNarrowSpec(List<HgTreeFilter.NarrowPattern> includes, List<HgTreeFilter.NarrowPattern> excludes) {
        return io.github.search5.hg4j.narrow.NarrowSpec.format(includes.stream().map(HgTreeFilter.NarrowPattern::toSpecString).toList(),
                excludes.stream().map(HgTreeFilter.NarrowPattern::toSpecString).toList());
    }
}
