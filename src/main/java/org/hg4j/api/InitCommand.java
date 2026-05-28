package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Initializes a new Mercurial repository.
 */
public class InitCommand {
    private File directory;

    public InitCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public HgRepository call() throws IOException {
        if (directory == null) {
            throw new IllegalStateException("Repository directory must be specified.");
        }

        // Try to create the directory if it doesn't exist
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Failed to create repository directory: " + directory);
            }
        } else if (!directory.isDirectory()) {
            throw new IOException("Path exists and is not a directory: " + directory);
        }

        File hgDir = new File(directory, ".hg");
        if (hgDir.exists() && !hgDir.isDirectory()) {
            throw new IOException("Path exists and is not a directory: " + hgDir);
        }
        if (!hgDir.exists()) {
            if (!hgDir.mkdir()) {
                throw new IOException("Failed to create .hg directory in: " + directory);
            }
        }

        File storeDir = new File(hgDir, "store");
        if (storeDir.exists() && !storeDir.isDirectory()) {
            throw new IOException("Path exists and is not a directory: " + storeDir);
        }
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                throw new IOException("Failed to create .hg/store directory");
            }
        }

        File requiresFile = new File(hgDir, "requires");
        List<String> requirements = List.of(
                "dotencode",
                "fncache",
                "generaldelta",
                "revlogv1",
                "store"
        );
        
        try {
            SafeFileIO.writeLinesAtomic(requiresFile, requirements);
        } catch (IOException e) {
            throw new IOException("Failed to write .hg/requires file", e);
        }

        return new HgRepository(directory);
    }
}
