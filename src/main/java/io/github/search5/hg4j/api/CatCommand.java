package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lfs.HgLfsFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.NodeId;
import java.util.Map;

/**
 * Porcelain command to retrieve the content of a specific file version in history.
 */
public class CatCommand {

    private final HgRepository repository;
    private String file;
    private String revision;

    public CatCommand(HgRepository repository) {
        this.repository = repository;
    }

    public CatCommand setFile(String file) {
        this.file = file;
        return this;
    }

    private final FileSelection selection = new FileSelection();

    /** {@code hg cat [FILE]...}: the files to print, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}). */
    public CatCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code hg cat -I}. */
    public CatCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code hg cat -X}. */
    public CatCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public CatCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** The content of every file of the revision the patterns select, in path order (path to content). */
    public java.util.Map<String, byte[]> callAll() throws IOException {
        if (selection.patternCount() == 0) {
            throw new io.github.search5.hg4j.errors.HgValidationException("invalid arguments"); // `hg cat` needs a FILE argument
        }
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, revision);
        java.util.Map<String, byte[]> contents = new java.util.LinkedHashMap<>();
        for (String path : new FilesCommand(repository).setRevision(revision).call()) {
            if (matcher.matches(path)) {
                contents.put(path, new CatCommand(repository).setFile(path).setRevision(revision).call());
            }
        }
        return contents;
    }

    public CatCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public CatCommand setRevision(NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    public byte[] call() throws IOException {
        repository.clearRevlogCache();
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNodeId == null) {
            throw new HgRevisionNotFoundException("Unable to resolve revision");
        }

        int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
        if (commitRev == -1) {
            throw new HgRevisionNotFoundException("Commit revision not found: " + NodeIdUtil.toHex(targetNodeId));
        }

        // Read Manifest at that commit to find file version node
        Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        String fileHexNode = manifestMap.get(file);
        if (fileHexNode != null && fileHexNode.length() > 40) {
            fileHexNode = fileHexNode.substring(0, 40);
        }

        if (fileHexNode == null) {
            throw new HgRevisionNotFoundException("File not tracked at target revision: " + file);
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgCorruptDataException("Filelog not found for tracked file: " + file);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(fileHexNode));
        if (fileRev == -1) {
            throw new HgRevisionNotFoundException("File version not found in history: " + file + " @ " + fileHexNode);
        }

        return HgLfsFactory.getInstance().readContent(repository, file, filelog, fileRev);
    }

}
