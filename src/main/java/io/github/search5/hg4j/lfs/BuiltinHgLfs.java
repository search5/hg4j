package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * hg4j's own LFS implementation (JGit's {@code BuiltinLFS}). Behavior is unchanged from the code that used to sit inside
 * {@code CommitCommand} and {@code UpdateCommand}: the threshold rule, the pointer fields, the {@code lfs} requirement.
 */
public class BuiltinHgLfs extends HgLfsFactory {

    static final String POINTER_VERSION = "https://git-lfs.github.com/spec/v1";

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Cleaned applyCleanFilter(HgRepository repository, String path, byte[] content, Map<String, String> copyMeta)
            throws IOException {
        // lfs.track / .hglfs / the deprecated lfs.threshold, as real hg's _trackedmatcher resolves them
        if (!HgLfsTracker.forRepository(repository).tracks(path, content.length)) {
            return new Cleaned(content, null);
        }
        Map<String, String> extra = new LinkedHashMap<>();
        if (!isBinary(content)) {
            // Only a non-binary file carries x-is-binary (value 0); absence means "assume binary".
            extra.put("x-is-binary", "0");
        }
        if (copyMeta != null) {
            for (Map.Entry<String, String> entry : copyMeta.entrySet()) {
                extra.put("x-hg-" + entry.getKey(), entry.getValue());
            }
        }
        HgLfsPointer pointer = new HgLfsPointer(POINTER_VERSION, HgLfsPointer.sha256Hex(content), content.length, extra);
        new HgLfsManager(repository.getHgDir(), repository.getConfig()).cacheObject(pointer, content);
        requireLfs(repository);
        return new Cleaned(pointer.serialize(), pointer);
    }

    @Override
    public byte[] applySmudgeFilter(HgRepository repository, String path, byte[] stored, boolean extStored)
            throws IOException {
        return HgLfsManager.resolveContent(repository, stored, extStored, path);
    }

    /** Real hg adds {@code lfs} to {@code .hg/requires} on the first commit that holds an LFS file. */
    private static void requireLfs(HgRepository repository) throws IOException {
        File requiresFile = new File(repository.getHgDir(), "requires");
        List<String> requirements = new ArrayList<>(Files.readAllLines(requiresFile.toPath(), StandardCharsets.UTF_8));
        if (!requirements.contains("lfs")) {
            requirements.add("lfs");
            SafeFileIO.writeLinesAtomic(requiresFile, requirements);
        }
    }

    /** Real hg's binary test for LFS: at least one NUL byte. */
    static boolean isBinary(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
