package org.hg4j.api;

import org.hg4j.core.ChangegroupParser;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to shelve and unshelve local working copy changes.
 * Supports saving modified, added, and removed files and restoring them with full dirstate fidelity.
 */
public class ShelveCommand {

    private final HgRepository repository;
    private String name = "default";
    private boolean unshelve = false;

    public ShelveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ShelveCommand setName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        return this;
    }

    public ShelveCommand setUnshelve(boolean unshelve) {
        this.unshelve = unshelve;
        return this;
    }

    public void call() throws IOException {
        File shelvedDir = new File(repository.getHgDir(), "shelved");
        shelvedDir.mkdirs();
        File stateFile = new File(shelvedDir, name + ".state");

        if (unshelve) {
            performUnshelve(stateFile);
        } else {
            performShelve(stateFile);
        }
    }

    private byte[] getBaselineContent(String path) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        if (!clIdx.exists()) {
            return null;
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int lastRev = changelog.getRevisionCount() - 1;
        if (lastRev < 0) {
            return null;
        }

        org.hg4j.treewalk.ManifestWalk mw = new org.hg4j.treewalk.ManifestWalk(repository, String.valueOf(lastRev));
        while (mw.next()) {
            org.hg4j.treewalk.ManifestWalk.Entry entry = mw.getEntry();
            if (entry.getPath().equals(path)) {
                byte[] nodeId = entry.getNodeId();
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                if (!flIdx.exists()) {
                    return null;
                }
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, nodeId);
                if (fileRev == -1) {
                    return null;
                }
                return filelog.getRevisionContent(fileRev);
            }
        }
        return null;
    }

    private String generateDiff(String path, char state, byte[] content, String parentHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff -r ").append(parentHex).append(" ").append(path).append("\n");
        if (state == 'a') {
            sb.append("--- /dev/null\n");
            sb.append("+++ b/").append(path).append("\n");
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            int lineCount = lines.length;
            if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
                lineCount--;
            }
            sb.append("@@ -0,0 +1,").append(lineCount).append(" @@\n");
            for (int i = 0; i < lineCount; i++) {
                sb.append("+").append(lines[i]).append("\n");
            }
        } else if (state == 'r') {
            sb.append("--- a/").append(path).append("\n");
            sb.append("+++ /dev/null\n");
            byte[] baseBytes;
            try {
                baseBytes = getBaselineContent(path);
            } catch (Exception e) {
                baseBytes = new byte[0];
            }
            if (baseBytes == null) baseBytes = new byte[0];
            String text = new String(baseBytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            int lineCount = lines.length;
            if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
                lineCount--;
            }
            sb.append("@@ -1,").append(lineCount).append(" +0,0 @@\n");
            for (int i = 0; i < lineCount; i++) {
                sb.append("-").append(lines[i]).append("\n");
            }
        } else { // 'm' or 'n'
            sb.append("--- a/").append(path).append("\n");
            sb.append("+++ b/").append(path).append("\n");
            byte[] baseBytes;
            try {
                baseBytes = getBaselineContent(path);
            } catch (Exception e) {
                baseBytes = new byte[0];
            }
            if (baseBytes == null) baseBytes = new byte[0];

            String baseText = new String(baseBytes, StandardCharsets.UTF_8);
            String newText = new String(content, StandardCharsets.UTF_8);
            String[] baseLines = baseText.split("\r?\n", -1);
            String[] newLines = newText.split("\r?\n", -1);

            int baseLen = baseLines.length;
            if (baseLen > 0 && baseLines[baseLen - 1].isEmpty()) baseLen--;
            int newLen = newLines.length;
            if (newLen > 0 && newLines[newLen - 1].isEmpty()) newLen--;

            sb.append("@@ -1,").append(baseLen).append(" +1,").append(newLen).append(" @@\n");
            for (int i = 0; i < baseLen; i++) {
                sb.append("-").append(baseLines[i]).append("\n");
            }
            for (int i = 0; i < newLen; i++) {
                sb.append("+").append(newLines[i]).append("\n");
            }
        }
        return sb.toString();
    }

    private void writeEntryChunk(DataOutputStream dos, ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private void writePathChunk(DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + pathBytes.length;
        dos.writeInt(totalLen);
        dos.write(pathBytes);
    }

    private void writeTerminalChunk(DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }

    private void performShelve(File stateFile) throws IOException {
        File shelvedDir = stateFile.getParentFile();
        File patchFile = new File(shelvedDir, name + ".patch");
        File hgBundleFile = new File(shelvedDir, name + ".hg");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();
            Map<String, Dirstate.Entry> entries = dirstate.getEntries();

            List<ShelvedFile> shelvedFiles = new ArrayList<>();

            for (Map.Entry<String, Dirstate.Entry> item : entries.entrySet()) {
                String path = item.getKey();
                Dirstate.Entry entry = item.getValue();

                if (entry.getState() == 'm' || entry.getState() == 'a') {
                    File file = new File(repository.getDirectory(), path);
                    if (file.exists() && file.isFile()) {
                        byte[] content = Files.readAllBytes(file.toPath());
                        shelvedFiles.add(new ShelvedFile(path, entry.getState(), content));
                    }
                } else if (entry.getState() == 'r') {
                    shelvedFiles.add(new ShelvedFile(path, 'r', new byte[0]));
                } else if (entry.getState() == 'n') {
                    // Check if modified on disk without being added (uncommitted modification)
                    File file = new File(repository.getDirectory(), path);
                    if (file.exists() && file.isFile()) {
                        long diskSize = file.length();
                        long diskTime = file.lastModified() / 1000;
                        if (entry.getSize() != diskSize || entry.getTime() != diskTime) {
                            byte[] content = Files.readAllBytes(file.toPath());
                            shelvedFiles.add(new ShelvedFile(path, 'n', content));
                        }
                    }
                }
            }

            if (shelvedFiles.isEmpty()) {
                return; // Nothing to shelve
            }

            // Get parent nodes for standard metadata
            byte[] p1 = dirstate.getParent1();
            byte[] p2 = dirstate.getParent2();
            String p1Hex = NodeIdUtil.toHex(p1);
            String p2Hex = NodeIdUtil.toHex(p2);

            // 1. Write standard .patch file
            StringBuilder patchSb = new StringBuilder();
            patchSb.append("# HG changeset patch\n");
            patchSb.append("# User hg4j <hg4j@example.com>\n");
            patchSb.append("# Date ").append(System.currentTimeMillis() / 1000).append(" 0\n");
            patchSb.append("# Parent ").append(p1Hex).append("\n");
            patchSb.append("shelve: ").append(name).append("\n\n");

            for (ShelvedFile sf : shelvedFiles) {
                patchSb.append(generateDiff(sf.path, sf.state, sf.content, p1Hex));
            }

            Files.writeString(patchFile.toPath(), patchSb.toString(), StandardCharsets.UTF_8);

            // 2. Commit temporary revision to capture exact working copy delta
            CommitCommand commitCmd = new CommitCommand(repository)
                    .setAuthor("hg4j <hg4j@example.com>")
                    .setMessage("[shelve] " + name)
                    .setSkipLockAndJournal(true);
            
            byte[] tempCommitNode = commitCmd.call();

            // 3. Construct and write native .hg binary bundle from the temporary commit
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");

            Revlog cl = repository.getRevlog(clIdx, clDat);
            int tempRev = cl.findRevision(tempCommitNode);
            if (tempRev == -1) {
                throw new org.hg4j.errors.HgRevisionNotFoundException("Failed to resolve temporary shelve commit.");
            }

            ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
            bundle.changelogEntries = new ArrayList<>();
            bundle.manifestEntries = new ArrayList<>();
            bundle.fileGroups = new ArrayList<>();

            // Changelog entry
            Revlog.IndexRecord clRec = cl.getIndexRecord(tempRev);
            ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
            clEntry.node = clRec.getNodeId();
            clEntry.p1 = p1;
            clEntry.p2 = p2;
            clEntry.cs = clRec.getNodeId();
            byte[] rawClContent = cl.getRevisionContent(tempRev);
            clEntry.delta = Revlog.createSimpleDelta(new byte[0], rawClContent);
            bundle.changelogEntries.add(clEntry);

            // Manifest entry
            String clText = new String(rawClContent, StandardCharsets.UTF_8);
            byte[] mfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
            Revlog mf = repository.getRevlog(mfIdx, mfDat);
            int mfRev = mf.findRevision(mfNode);
            Revlog.IndexRecord mfRec = mf.getIndexRecord(mfRev);

            ChangegroupParser.ChangeGroupEntry mfEntry = new ChangegroupParser.ChangeGroupEntry();
            mfEntry.node = mfRec.getNodeId();
            byte[] prevMfNode = new byte[20];
            int p1CommitRev = cl.findRevision(p1);
            if (p1CommitRev != -1) {
                byte[] p1CommitContent = cl.getRevisionContent(p1CommitRev);
                String p1ClText = new String(p1CommitContent, StandardCharsets.UTF_8);
                prevMfNode = NodeIdUtil.fromHex(p1ClText.split("\n")[0].trim().substring(0, 40));
            }
            mfEntry.p1 = prevMfNode;
            mfEntry.p2 = new byte[20];
            mfEntry.cs = clRec.getNodeId();
            byte[] rawMfContent = mf.getRevisionContent(mfRev);
            mfEntry.delta = Revlog.createSimpleDelta(new byte[0], rawMfContent);
            bundle.manifestEntries.add(mfEntry);

            // FileGroups entries
            for (ShelvedFile sf : shelvedFiles) {
                if (sf.state == 'r') {
                    continue;
                }

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), sf.path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog fl = repository.getRevlog(flIdx, flDat);
                int flRev = fl.getRevisionCount() - 1;

                Revlog.IndexRecord flRec = fl.getIndexRecord(flRev);

                ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                fg.path = sf.path;
                fg.entries = new ArrayList<>();

                ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                flEntry.node = flRec.getNodeId();
                byte[] prevFlNode = new byte[20];
                if (flRev > 0) {
                    prevFlNode = fl.getIndexRecord(flRev - 1).getNodeId();
                }
                flEntry.p1 = prevFlNode;
                flEntry.p2 = new byte[20];
                flEntry.cs = clRec.getNodeId();
                flEntry.delta = Revlog.createSimpleDelta(new byte[0], sf.content);

                fg.entries.add(flEntry);
                bundle.fileGroups.add(fg);
            }

            // Write to native .hg file
            try (FileOutputStream fos = new FileOutputStream(hgBundleFile);
                 DataOutputStream dos = new DataOutputStream(fos)) {
                
                // Changelog group
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                // Manifest group
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                // File groups
                for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                    writePathChunk(dos, fg.path);
                    for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                        writeEntryChunk(dos, entry);
                    }
                    writeTerminalChunk(dos);
                }
                writeTerminalChunk(dos);
            }

            // 4. Clean and strip the temporary commit immediately (Repository remains pure)
            stripRevisionsFrom(tempRev);
            repository.clearRevlogCache();

            // 5. Write metadata .state file
            StringBuilder stateSb = new StringBuilder();
            stateSb.append("# HG shelve state\n");
            stateSb.append(name).append("\n");
            stateSb.append(p1Hex).append("\n");
            stateSb.append(p2Hex).append("\n");
            stateSb.append(shelvedFiles.size()).append("\n");
            for (ShelvedFile sf : shelvedFiles) {
                stateSb.append(sf.path).append(" ").append(sf.state).append("\n");
            }

            Files.writeString(stateFile.toPath(), stateSb.toString(), StandardCharsets.UTF_8);

            // Revert changes in working directory to clean state
            revertToLatestCommit(dirstate, shelvedFiles);
        }
    }

    private void performUnshelve(File stateFile) throws IOException {
        File shelvedDir = stateFile.getParentFile();
        File patchFile = new File(shelvedDir, name + ".patch");
        File hgBundleFile = new File(shelvedDir, name + ".hg");

        if (!stateFile.exists() || !hgBundleFile.exists()) {
            throw new org.hg4j.errors.HgRepositoryNotFoundException("Shelve file not found: " + name);
        }

        List<String> stateLines = Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8);
        String shelveName = stateLines.get(1).trim();
        String p1Hex = stateLines.get(2).trim();
        String p2Hex = stateLines.get(3).trim();

        if (!shelveName.equals(name)) {
            throw new org.hg4j.errors.HgValidationException("Cannot unshelve: Shelve name mismatch. State file has '" + shelveName 
                + "' but expected '" + name + "'");
        }

        int shelvedFilesCount = Integer.parseInt(stateLines.get(4).trim());

        Map<String, Character> fileStates = new HashMap<>();
        for (int i = 0; i < shelvedFilesCount; i++) {
            String line = stateLines.get(5 + i).trim();
            int space = line.lastIndexOf(' ');
            String path = line.substring(0, space);
            char state = line.substring(space + 1).charAt(0);
            fileStates.put(path, state);
        }

        ChangegroupParser.ChangegroupBundle bundle;
        try (FileInputStream fis = new FileInputStream(hgBundleFile)) {
            bundle = ChangegroupParser.parseBundle(fis);
        }

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();

            // Validate parent hash consistency (W1)
            String currentP1Hex = NodeIdUtil.toHex(dirstate.getParent1());
            if (!currentP1Hex.equalsIgnoreCase(p1Hex)) {
                throw new org.hg4j.errors.HgValidationException("Cannot unshelve: Working directory parent (" + currentP1Hex 
                    + ") does not match shelved parent (" + p1Hex + ")");
            }
            String currentP2Hex = NodeIdUtil.toHex(dirstate.getParent2());
            if (!currentP2Hex.equalsIgnoreCase(p2Hex)) {
                throw new org.hg4j.errors.HgValidationException("Cannot unshelve: Working directory parent2 (" + currentP2Hex 
                    + ") does not match shelved parent2 (" + p2Hex + ")");
            }

            // Restore files from bundle
            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                Character state = fileStates.get(path);
                if (state == null) state = 'm';

                ChangegroupParser.ChangeGroupEntry flEntry = fg.entries.get(0);
                byte[] content = Revlog.applyDelta(new byte[0], flEntry.delta);

                File diskFile = new File(repository.getDirectory(), path);
                diskFile.getParentFile().mkdirs();
                Files.write(diskFile.toPath(), content);

                int mode = diskFile.canExecute() ? 0755 : 0644;
                int size = content.length;
                long time = diskFile.lastModified() / 1000;

                dirstate.addEntry(path, new Dirstate.Entry(state, mode, size, time));
            }

            // Restore deleted files
            for (Map.Entry<String, Character> entry : fileStates.entrySet()) {
                if (entry.getValue() == 'r') {
                    File diskFile = new File(repository.getDirectory(), entry.getKey());
                    Files.deleteIfExists(diskFile.toPath());
                    dirstate.addEntry(entry.getKey(), new Dirstate.Entry('r', 0, 0, 0));
                }
            }

            repository.writeDirstate(dirstate);
            
            Files.deleteIfExists(stateFile.toPath());
            Files.deleteIfExists(hgBundleFile.toPath());
            Files.deleteIfExists(patchFile.toPath());
        }
    }

    private void revertToLatestCommit(Dirstate dirstate, List<ShelvedFile> shelvedFiles) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int lastRev = changelog.getRevisionCount() - 1;

        if (lastRev < 0) {
            // No commits yet, just delete added/modified files
            for (ShelvedFile sf : shelvedFiles) {
                File diskFile = new File(repository.getDirectory(), sf.path);
                Files.deleteIfExists(diskFile.toPath());
                dirstate.removeEntry(sf.path);
            }
            repository.writeDirstate(dirstate);
            return;
        }

        Map<String, String> manifestEntries = new HashMap<>();
        org.hg4j.treewalk.ManifestWalk mw = new org.hg4j.treewalk.ManifestWalk(repository, String.valueOf(lastRev));
        while (mw.next()) {
            org.hg4j.treewalk.ManifestWalk.Entry entry = mw.getEntry();
            String flag = entry.isExecutable() ? "x" : "";
            manifestEntries.put(entry.getPath(), entry.getNodeIdHex() + flag);
        }

        for (ShelvedFile sf : shelvedFiles) {
            File diskFile = new File(repository.getDirectory(), sf.path);
            String manifestNodeWithFlags = manifestEntries.get(sf.path);

            if (manifestNodeWithFlags == null) {
                // File was not in latest commit (i.e. was added), so delete it
                Files.deleteIfExists(diskFile.toPath());
                dirstate.removeEntry(sf.path);
            } else {
                // File was in latest commit, so restore it
                String hexNode = manifestNodeWithFlags.substring(0, 40);
                String flags = manifestNodeWithFlags.substring(40);

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), sf.path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
                byte[] originalContent = filelog.getRevisionContent(fileRev);

                diskFile.getParentFile().mkdirs();
                Files.write(diskFile.toPath(), originalContent);

                boolean exec = flags.contains("x");
                diskFile.setExecutable(exec, false);

                int mode = exec ? 0755 : 0644;
                int size = originalContent.length;
                long time = diskFile.lastModified() / 1000;

                dirstate.addEntry(sf.path, new Dirstate.Entry('n', mode, size, time));
            }
        }

        repository.writeDirstate(dirstate);
    }

    private static class ShelvedFile {
        String path;
        char state;
        byte[] content;

        ShelvedFile(String path, char state, byte[] content) {
            this.path = path;
            this.state = state;
            this.content = content;
        }
    }

    private void stripRevisionsFrom(int startRev) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifest = repository.getRevlog(mfIdx, mfDat);

        // Calculate truncate boundaries
        long clIdxSize = (long) startRev * 64;
        long clDatSize = 0;
        if (startRev > 0) {
            clDatSize = changelog.getIndexRecord(startRev).getOffset();
        }

        // We also truncate manifest starting from the linkRev mapping to startRev
        int minMfRev = -1;
        for (int i = 0; i < manifest.getRevisionCount(); i++) {
            if (manifest.getIndexRecord(i).getLinkRev() >= startRev) {
                minMfRev = i;
                break;
            }
        }

        long mfIdxSize = manifest.getRevisionCount() * 64L;
        long mfDatSize = 0;
        if (minMfRev != -1) {
            mfIdxSize = (long) minMfRev * 64;
            if (minMfRev > 0) {
                mfDatSize = manifest.getIndexRecord(minMfRev).getOffset();
            }
        }

        // Truncate filelogs registered in fncache
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String relPath : fncachePaths) {
                if (relPath.endsWith(".i")) {
                    File flIdx = new File(repository.getStoreDir(), relPath);
                    String datPath = relPath.substring(0, relPath.length() - 2) + ".d";
                    File flDat = new File(repository.getStoreDir(), datPath);

                    if (flIdx.exists()) {
                        try {
                            Revlog filelog = repository.getRevlog(flIdx, flDat);
                            int minFileRev = -1;
                            for (int i = 0; i < filelog.getRevisionCount(); i++) {
                                if (filelog.getIndexRecord(i).getLinkRev() >= startRev) {
                                    minFileRev = i;
                                    break;
                                }
                            }
                            if (minFileRev != -1) {
                                long flIdxSize = (long) minFileRev * 64;
                                long flDatSize = 0;
                                if (minFileRev > 0) {
                                    flDatSize = filelog.getIndexRecord(minFileRev).getOffset();
                                }
                                truncateFile(flIdx, flIdxSize);
                                truncateFile(flDat, flDatSize);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        // Perform truncate physically
        truncateFile(clIdx, clIdxSize);
        truncateFile(clDat, clDatSize);
        truncateFile(mfIdx, mfIdxSize);
        truncateFile(mfDat, mfDatSize);
    }

    private void truncateFile(File file, long size) throws IOException {
        if (!file.exists()) return;
        if (size == 0) {
            Files.deleteIfExists(file.toPath());
        } else {
            try (java.nio.channels.FileChannel outChan = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                outChan.truncate(size);
                outChan.force(true);
            }
        }
    }
}
