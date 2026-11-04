package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.api.FilesCommand;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** A {@link FilesetContext} over a repository: the working directory ({@code revision == null}) or one revision. */
public class RepositoryFilesetContext implements FilesetContext {

    private final HgRepository repository;
    private final String revision;
    private Set<String> tracked;

    public RepositoryFilesetContext(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
    }

    public HgRepository repository() {
        return repository;
    }

    /** The revision, or {@code null} for the working directory. */
    public String revision() {
        return revision;
    }

    @Override
    public File root() {
        return repository.getDirectory();
    }

    @Override
    public Set<String> extensions() {
        Set<String> extensions = new HashSet<>();
        if (io.github.search5.hg4j.lfs.HgLfsConfig.isExtensionEnabled(repository)) {
            extensions.add("lfs");
        }
        return extensions;
    }

    @Override
    public boolean contains(String path) throws IOException {
        return trackedFiles().contains(path);
    }

    /** All files tracked in this context. */
    public synchronized Set<String> trackedFiles() throws IOException {
        if (tracked == null) {
            FilesCommand command = new FilesCommand(repository);
            if (revision != null) {
                command.setRevision(revision);
            }
            tracked = new HashSet<>(command.call());
        }
        return tracked;
    }
}
