package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.lib.ProgressMonitor;
import com.github.search5.hg4j.lib.NullProgressMonitor;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to clone a remote Mercurial repository into a local directory.
 * Built with full revision update/checkout and dirstate reconstruction capabilities.
 */
public class CloneCommand {

    private String sourceUrl;
    private File directory;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

    public CloneCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    public CloneCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    public CloneCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public HgRepository call() throws IOException, HgLockException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }
        if (directory == null) {
            throw new IllegalStateException("Local destination directory must be specified.");
        }

        if (directory.exists() && directory.list() != null && directory.list().length > 0) {
            throw new com.github.search5.hg4j.errors.HgValidationException("Destination directory is not empty: " + directory.getAbsolutePath());
        }

        monitor.start("Cloning repository", 3);

        // 1. Initialize empty repository
        HgRepository repo = Hg.init().setDirectory(directory).call();
        monitor.update(1);

        // 2. Pull changes from remote source
        PullCommand pullCmd = new PullCommand(repo);
        pullCmd.setSource(sourceUrl);
        pullCmd.setProgressMonitor(monitor);
        List<byte[]> importedCommits = pullCmd.call();

        if (importedCommits.isEmpty()) {
            monitor.end();
            return repo; // Empty repository pulled
        }

        // 3. Checkout (Update) the latest revision to working directory
        checkoutLatest(repo);
        monitor.update(1);

        monitor.end();
        return repo;
    }

    private void checkoutLatest(HgRepository repo) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");

        Revlog changelog = repo.getRevlog(clIdx, clDat);
        int lastRev = changelog.getRevisionCount() - 1;
        if (lastRev < 0) {
            return;
        }

        byte[] latestCommit = changelog.getIndexRecord(lastRev).getNodeId();

        // Extract manifest node hex from changelog content
        byte[] clContent = changelog.getRevisionContent(lastRev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0];
        byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

        Revlog manifest = repo.getManifestRevlog();
        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, mfNode);
        if (mfRev == -1) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Manifest revision not found: " + NodeIdUtil.toHex(mfNode));
        }

        byte[] mfContent = manifest.getRevisionContent(mfRev);
        String mfText = new String(mfContent, StandardCharsets.UTF_8);

        // Parse manifest entries
        Map<String, String> manifestEntries = new HashMap<>();
        String[] lines = mfText.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx != -1) {
                String path = line.substring(0, nullIdx);
                String nodeWithFlags = line.substring(nullIdx + 1);
                manifestEntries.put(path, nodeWithFlags.trim());
            }
        }

        // Restore files to disk and record in Dirstate
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(latestCommit, new byte[20]);

        for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hexNode = nodeWithFlags.substring(0, 40);
            String flags = nodeWithFlags.substring(40);

            File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

            if (!flIdx.exists()) {
                throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
            }

            Revlog filelog = repo.getRevlog(flIdx, flDat);
            int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
            if (fileRev == -1) {
                throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
            }

            byte[] fileContent = filelog.getRevisionContent(fileRev);

            // Write to working copy
            File diskFile = new File(repo.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }

            int mode = 0644;
            if (flags.contains("l")) {
                mode = 0120000;
                String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), java.nio.file.Path.of(target));
                } catch (Exception e) {
                    Files.write(diskFile.toPath(), fileContent);
                }
            } else {
                Files.write(diskFile.toPath(), fileContent);
                // Apply executable flag if 'x'
                boolean executable = flags.contains("x");
                diskFile.setExecutable(executable, false);
                mode = executable ? 0755 : 0644;
            }

            int size = fileContent.length;
            long time = diskFile.lastModified() / 1000;

            dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
        }

        repo.writeDirstate(dirstate);
    }
}
