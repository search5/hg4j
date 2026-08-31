package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;

/**
 * Initializes a new Mercurial repository.
 */
public class InitCommand {
    private File directory;
    private boolean dirstateV2 = false;
    private boolean useZstd = false;

    public InitCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public InitCommand setDirstateV2(boolean dirstateV2) {
        this.dirstateV2 = dirstateV2;
        return this;
    }

    public InitCommand setUseZstd(boolean useZstd) {
        this.useZstd = useZstd;
        return this;
    }

    public HgRepository call() throws IOException {
        if (directory == null) {
            throw new IllegalStateException("Repository directory must be specified.");
        }

        // Try to create the directory if it doesn't exist
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Failed to create repository directory: " + directory);
            }
        } else if (!directory.isDirectory()) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Path exists and is not a directory: " + directory);
        }

        File hgDir = new File(directory, ".hg");
        if (hgDir.exists() && !hgDir.isDirectory()) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Path exists and is not a directory: " + hgDir);
        }
        if (!hgDir.exists()) {
            if (!hgDir.mkdir()) {
                throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Failed to create .hg directory in: " + directory);
            }
        }

        File storeDir = new File(hgDir, "store");
        if (storeDir.exists() && !storeDir.isDirectory()) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Path exists and is not a directory: " + storeDir);
        }
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Failed to create .hg/store directory");
            }
        }

        File requiresFile = new File(hgDir, "requires");
        java.util.List<String> requirements = new java.util.ArrayList<>(java.util.List.of(
                "dotencode",
                "fncache",
                "generaldelta",
                "revlogv1",
                "store"
        ));
        if (dirstateV2) {
            requirements.add("dirstate-v2");
        }
        if (useZstd) {
            requirements.add("revlog-compression=zstd");
        }
        
        try {
            SafeFileIO.writeLinesAtomic(requiresFile, requirements);
        } catch (IOException e) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Failed to write .hg/requires file", e);
        }

        return new HgRepository(directory);
    }
}
