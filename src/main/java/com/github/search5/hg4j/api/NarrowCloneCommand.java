package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.HgTreeFilter;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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

        // 2. Add narrow paths requirements inside .hg/requires to mark as narrow clone
        File requiresFile = new File(repo.getHgDir(), "requires");
        List<String> requirements = new ArrayList<>(Files.readAllLines(requiresFile.toPath(), java.nio.charset.StandardCharsets.UTF_8));
        requirements.add("narrowspec");
        com.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(requiresFile, requirements);

        // 3. Establish pull with TreeFilter integration (emulates narrow clone segment mapping)
        HgTreeFilter pathFilter = HgTreeFilter.createPathPrefixFilter(includePaths, excludePaths);
        
        // Setup narrow paths specifications
        File narrowSpecFile = new File(repo.getHgDir(), "narrowspec");
        StringBuilder sb = new StringBuilder();
        sb.append("[includes]\n");
        for (String inc : includePaths) {
            sb.append(inc).append("\n");
        }
        sb.append("[excludes]\n");
        for (String ex : excludePaths) {
            sb.append(ex).append("\n");
        }
        com.github.search5.hg4j.util.SafeFileIO.writeStringAtomic(narrowSpecFile, sb.toString());

        // Perform the standard SCM clone/pull
        hg.pull().setSource(sourceUrl).setTreeFilter(pathFilter).call();

        // 4. sparse working copy update
        hg.update().setTreeFilter(pathFilter).call();

        return hg;
    }
}
