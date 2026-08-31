package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java transport that provides a connection to a Mercurial repository on the local filesystem.
 * Directly parses and merges the remote repository without invoking a native hg subprocess.
 */
public class HgLocalClient implements HgRemoteConnection {

    private final File repoDir;
    private final HgRepository remoteRepo;

    public HgLocalClient(String path) throws IOException {
        String cleanPath = path.startsWith("file://") ? path.substring(7) : path;
        this.repoDir = new File(cleanPath);
        this.remoteRepo = new HgRepository(repoDir);
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        return List.of("changegroup", "getbundle", "lookup", "pushkey", "branchmap");
    }

    @Override
    public List<String> getHeads() throws IOException {
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return new ArrayList<>();
        }
        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return new ArrayList<>();
        }

        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
        }

        List<String> heads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                heads.add(com.github.search5.hg4j.util.NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return heads;
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        return getBundle(roots, null, null);
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(remoteRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(remoteRepo.getStoreDir(), "00manifest.d");

        if (!clIdx.exists()) {
            return new byte[0];
        }

        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return new byte[0];
        }

        // Calculate startRev based on common bases
        int startRev = 0;
        List<String> validCommon = new ArrayList<>();
        if (common != null) {
            for (String c : common) {
                if (c != null && !c.equals("0000000000000000000000000000000000000000")) {
                    validCommon.add(c);
                }
            }
        }

        if (!validCommon.isEmpty()) {
            boolean[] commonKnown = new boolean[count];
            for (String c : validCommon) {
                int rev = changelog.findRevision(com.github.search5.hg4j.util.NodeIdUtil.fromHex(c));
                if (rev != -1) {
                    commonKnown[rev] = true;
                }
            }
            // Propagate descendants/ancestors
            for (int i = count - 1; i >= 0; i--) {
                if (commonKnown[i]) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                    if (rec.getParent1() >= 0) commonKnown[rec.getParent1()] = true;
                    if (rec.getParent2() >= 0) commonKnown[rec.getParent2()] = true;
                }
            }
            int firstNewRev = count;
            for (int i = 0; i < count; i++) {
                if (!commonKnown[i]) {
                    firstNewRev = i;
                    break;
                }
            }
            startRev = firstNewRev;
        }

        if (startRev >= count) {
            return new byte[0];
        }

        // Build bundle
        com.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle bundle = new com.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        // 1a. Pack Changelogs
        for (int r = startRev; r < count; r++) {
            Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
            com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry clEntry = new com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry();
            clEntry.node = clRec.getNodeId();
            clEntry.p1 = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
            clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
            clEntry.cs = clRec.getNodeId();

            byte[] content = changelog.getRevisionContent(r);
            byte[] baseContent = new byte[0];
            if (clRec.getParent1() != -1) {
                baseContent = changelog.getRevisionContent(clRec.getParent1());
            }
            clEntry.delta = Revlog.createDelta(baseContent, content);
            bundle.changelogEntries.add(clEntry);
        }

        // 1b. Pack Manifests
        Revlog manifest = remoteRepo.getRevlog(mfIdx, mfDat);
        java.util.Set<String> affectedFiles = new java.util.HashSet<>();
        for (int r = startRev; r < count; r++) {
            byte[] clContent = changelog.getRevisionContent(r);
            String clText = new String(clContent, java.nio.charset.StandardCharsets.UTF_8);
            String[] clLines = clText.split("\n");
            for (int i = 3; i < clLines.length; i++) {
                String line = clLines[i].trim();
                if (line.isEmpty()) break;
                affectedFiles.add(line);
            }

            byte[] mfNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40));
            int mfRev = manifest.findRevision(mfNode);
            if (mfRev == -1) continue;

            Revlog.IndexRecord mfRec = manifest.getIndexRecord(mfRev);
            com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry mfEntry = new com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry();
            mfEntry.node = mfRec.getNodeId();
            mfEntry.p1 = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
            mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
            mfEntry.cs = changelog.getIndexRecord(r).getNodeId();

            byte[] content = manifest.getRevisionContent(mfRev);
            byte[] baseContent = new byte[0];
            if (mfRec.getParent1() != -1) {
                baseContent = manifest.getRevisionContent(mfRec.getParent1());
            }
            mfEntry.delta = Revlog.createDelta(baseContent, content);
            bundle.manifestEntries.add(mfEntry);
        }

        // 1c. Pack Filelogs
        for (String path : affectedFiles) {
            File flIdx = com.github.search5.hg4j.api.CommitCommand.getFilelogIndex(remoteRepo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            if (!flIdx.exists()) continue;

            Revlog fl = remoteRepo.getRevlog(flIdx, flDat);
            List<com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

            for (int i = 0; i < fl.getRevisionCount(); i++) {
                Revlog.IndexRecord flRec = fl.getIndexRecord(i);
                if (flRec.getLinkRev() >= startRev) {
                    com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry flEntry = new com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry();
                    flEntry.node = flRec.getNodeId();
                    flEntry.p1 = (flRec.getParent1() != -1) ? fl.getIndexRecord(flRec.getParent1()).getNodeId() : new byte[20];
                    flEntry.p2 = (flRec.getParent2() != -1) ? fl.getIndexRecord(flRec.getParent2()).getNodeId() : new byte[20];
                    flEntry.cs = changelog.getIndexRecord(flRec.getLinkRev()).getNodeId();

                    byte[] content = fl.getRevisionContent(i);
                    byte[] baseContent = new byte[0];
                    if (flRec.getParent1() != -1) {
                        baseContent = fl.getRevisionContent(flRec.getParent1());
                    }
                    flEntry.delta = Revlog.createDelta(baseContent, content);
                    flEntries.add(flEntry);
                }
            }

            if (!flEntries.isEmpty()) {
                com.github.search5.hg4j.bundle.ChangegroupParser.FileGroup fg = new com.github.search5.hg4j.bundle.ChangegroupParser.FileGroup();
                fg.path = path;
                fg.entries = flEntries;
                bundle.fileGroups.add(fg);
            }
        }

        // Serialize to binary bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {
            dos.write("HG10UN".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

            for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                writeEntryChunk(dos, entry);
            }
            writeTerminalChunk(dos);

            for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                writeEntryChunk(dos, entry);
            }
            writeTerminalChunk(dos);

            for (com.github.search5.hg4j.bundle.ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                writePathChunk(dos, fg.path);
                for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);
            }
            writeTerminalChunk(dos);
        }

        return baos.toByteArray();
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException, HgLockException {
        if (bundleBytes == null || bundleBytes.length == 0) {
            return "no changes found";
        }

        // Backup remote dirstate before push to preserve bare status
        File dirstateFile = new File(remoteRepo.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? java.nio.file.Files.readAllBytes(dirstateFile.toPath()) : null;

        byte[] changegroupBytes = bundleBytes;
        String cgVersion = "01";
        if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
            String comp = new String(bundleBytes, 4, 2, java.nio.charset.StandardCharsets.US_ASCII);
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
            if ("UN".equals(comp)) {
                changegroupBytes = bais.readAllBytes();
            } else {
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Unsupported compression format in local push: HG10" + comp);
            }
        }

        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(changegroupBytes)) {
            com.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle bundle = 
                    com.github.search5.hg4j.bundle.ChangegroupParser.parseBundle(bais, cgVersion);

            // Apply bundle natively to remoteRepo using transactional API of PullCommand
            com.github.search5.hg4j.api.PullCommand pullApi = new com.github.search5.hg4j.api.PullCommand(remoteRepo);
            List<byte[]> imported = pullApi.applyBundle(bundle);

            // Restore remote dirstate to preserve bare repo status
            if (dirstateBackup != null) {
                java.nio.file.Files.write(dirstateFile.toPath(), dirstateBackup);
            } else {
                java.nio.file.Files.deleteIfExists(dirstateFile.toPath());
            }

            return "push successful, imported " + imported.size() + " changesets natively";
        }
    }

    private void writeEntryChunk(java.io.DataOutputStream dos, com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private void writePathChunk(java.io.DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalLen = 4 + pathBytes.length;
        dos.writeInt(totalLen);
        dos.write(pathBytes);
    }

    private void writeTerminalChunk(java.io.DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }

    @Override
    public java.util.Map<String, String> listKeys(String namespace) throws IOException {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if ("bookmarks".equals(namespace)) {
            File bkFile = new File(remoteRepo.getHgDir(), "bookmarks");
            if (bkFile.exists()) {
                List<String> lines = java.nio.file.Files.readAllLines(bkFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int spaceIdx = line.indexOf(' ');
                    if (spaceIdx != -1) {
                        String node = line.substring(0, spaceIdx).trim();
                        String name = line.substring(spaceIdx + 1).trim();
                        map.put(name, node);
                    }
                }
            }
        } else if ("phases".equals(namespace)) {
            com.github.search5.hg4j.phase.PhaseRoots phaseRoots = remoteRepo.getPhaseRoots();
            File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
            if (clIdx.exists()) {
                Revlog cl = remoteRepo.getRevlog(clIdx, clDat);
                for (int i = 0; i < cl.getRevisionCount(); i++) {
                    byte[] nodeBytes = cl.getIndexRecord(i).getNodeId();
                    com.github.search5.hg4j.lib.NodeId nodeId = new com.github.search5.hg4j.lib.NodeId(nodeBytes);
                    com.github.search5.hg4j.phase.PhaseRoots.Phase phase = phaseRoots.getPhase(nodeId, cl);
                    if (phase != com.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC) {
                        map.put(com.github.search5.hg4j.util.NodeIdUtil.toHex(nodeBytes), String.valueOf(phase.getValue()));
                    }
                }
            }
        }
        return map;
    }

    @Override
    public void close() {
        // No resource release required
    }
}
