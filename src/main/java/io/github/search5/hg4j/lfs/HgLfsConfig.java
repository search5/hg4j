package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lib.HgRepository;

import java.util.Locale;

/** Reads the LFS related switches of a repository's configuration. */
public final class HgLfsConfig {

    private HgLfsConfig() {
    }

    /**
     * Whether the {@code lfs} extension is enabled for the repository ({@code [extensions] lfs =}). hg4j has no extension
     * system; this is the switch real hg's server uses to decide whether it speaks LFS at all.
     */
    public static boolean isExtensionEnabled(HgRepository repository) {
        String value = repository.getConfig().get("extensions", "lfs");
        return value != null && !value.strip().startsWith("!");
    }

    /** {@code [experimental] lfs.serve}: whether {@code hg serve} answers LFS requests (default true). */
    public static boolean isServing(HgRepository repository) {
        String value = repository.getConfig().get("experimental", "lfs.serve");
        if (value == null) {
            return true;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "0", "no", "false", "off", "never" -> false;
            default -> true;
        };
    }

    /** Whether the repository holds LFS revisions (the {@code lfs} requirement). */
    public static boolean requiresLfs(HgRepository repository) {
        return repository.getRequirements().contains("lfs");
    }

    /**
     * Adds {@code lfs} to {@code .hg/requires} if it is not there yet (real hg does this on the first commit or push that brings an
     * LFS revision: without the requirement a client without the extension would read pointers as file content).
     */
    /**
     * Records the {@code lfs} requirement. Like real hg's {@code checkrequireslfs} hook, which runs {@code with repo.lock()}, this
     * takes the store lock itself (re-entrant when the caller, a commit or a push, already holds it).
     */
    public static void addRequirement(HgRepository repository) throws java.io.IOException {
        try (io.github.search5.hg4j.lib.HgLock storeLock = repository.lockStoreWithoutRecovery(repository.resolvePushLockTimeoutMs())) {
            java.io.File requiresFile = new java.io.File(repository.getHgDir(), "requires");
            java.util.List<String> requirements = new java.util.ArrayList<>(
                    java.nio.file.Files.readAllLines(requiresFile.toPath(), java.nio.charset.StandardCharsets.UTF_8));
            if (!requirements.contains("lfs")) {
                requirements.add("lfs");
                io.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(requiresFile, requirements);
            }
        } catch (io.github.search5.hg4j.errors.HgLockException e) {
            throw new java.io.IOException(e.getMessage(), e);
        }
    }
}
