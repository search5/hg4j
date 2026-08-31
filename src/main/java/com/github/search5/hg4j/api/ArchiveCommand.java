package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archive command for extracting a repository revision snapshot
 * directly to a directory or a compressed ZIP archive.
 */
public class ArchiveCommand {
    private final HgRepository repository;
    private String revision = "tip";
    private File destination;

    public ArchiveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ArchiveCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public ArchiveCommand setDestination(File destination) {
        this.destination = destination;
        return this;
    }

    /**
     * Executes the archive snapshot extraction.
     * Support both folder copy and .zip packaging.
     *
     * @throws IOException if extraction fails
     */
    public void call() throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("Destination target must be specified for archive extraction");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        byte[] node = com.github.search5.hg4j.util.NodeIdUtil.resolveRevision(changelog, revision);
        if (node == null) {
            throw new IOException("Archive target revision not found: " + revision);
        }

        java.util.Map<String, String> manifestMap = getManifestForCommit(changelog, manifestRevlog, node);

        boolean zipOutput = destination.getName().endsWith(".zip");

        if (zipOutput) {
            destination.getParentFile().mkdirs();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destination))) {
                for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
                    String path = entry.getKey();
                    String hexAndFlag = entry.getValue();
                    String fileHex = hexAndFlag.substring(0, 40);

                    byte[] content = getFileRevisionContent(repository, path, fileHex);
                    ZipEntry ze = new ZipEntry(path);
                    zos.putNextEntry(ze);
                    zos.write(content);
                    zos.closeEntry();
                }
            }
        } else {
            // Directory output
            for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
                String path = entry.getKey();
                String hexAndFlag = entry.getValue();
                String fileHex = hexAndFlag.substring(0, 40);

                byte[] content = getFileRevisionContent(repository, path, fileHex);
                File targetFile = new File(destination, path);
                targetFile.getParentFile().mkdirs();
                java.nio.file.Files.write(targetFile.toPath(), content);
            }
        }
    }

    private java.util.Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        java.util.Map<String, String> manifestMap = new java.util.LinkedHashMap<>();
        if (commitNode == null || com.github.search5.hg4j.util.NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;

        String manifestHex = lines[0].trim();
        byte[] manifestNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }

    private byte[] getFileRevisionContent(com.github.search5.hg4j.core.HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }
}
