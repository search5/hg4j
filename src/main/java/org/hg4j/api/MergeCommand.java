package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.hg4j.core.Merge3;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Performs a 3-way merge of a target revision into the working copy.
 */
public class MergeCommand {
    private final HgRepository repository;
    private byte[] targetNodeId;
    private int targetRev = -1;
    // 개선 권고 2번(LCA 탐색 성능 개선)을 위한 메모이즈 캐시
    private final Map<Long, Boolean> ancestorCache = new HashMap<>();

    public static class MergeResult {
        private final boolean conflicted;
        private final List<String> conflicts;

        public MergeResult(boolean conflicted, List<String> conflicts) {
            this.conflicted = conflicted;
            this.conflicts = conflicts;
        }

        public boolean isConflicted() {
            return conflicted;
        }

        public List<String> getConflicts() {
            return conflicts;
        }
    }

    public static class MergeBase {
        final int rev; // -1 if virtual
        final Map<String, String> manifest;
        final Map<String, byte[]> fileContents; // cached virtual file contents

        MergeBase(int rev, Map<String, String> manifest) {
            this.rev = rev;
            this.manifest = manifest;
            this.fileContents = new java.util.HashMap<>();
        }

        MergeBase(Map<String, String> manifest, Map<String, byte[]> fileContents) {
            this.rev = -1;
            this.manifest = manifest;
            this.fileContents = fileContents;
        }

        byte[] getFileContent(MergeCommand cmd, String path, String hex) throws IOException {
            if (rev == -1) {
                if (fileContents.containsKey(path)) {
                    return fileContents.get(path);
                }
            }
            if (hex == null || hex.isEmpty()) {
                return new byte[0];
            }
            return cmd.getFileRevisionContent(path, hex);
        }
    }

    public MergeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public MergeCommand setNodeId(byte[] targetNodeId) {
        this.targetNodeId = targetNodeId;
        return this;
    }

    public MergeCommand setRevision(int targetRev) {
        this.targetRev = targetRev;
        return this;
    }

    private int getModeFromManifestHex(String hex) {
        if (hex != null && hex.length() > 40) {
            char flag = hex.charAt(40);
            if (flag == 'x') {
                return 0755;
            } else if (flag == 'l') {
                return 0120000;
            }
        }
        return 0644;
    }

    private byte[] hashBytes(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return md.digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private MergeBase getMergeBase(Revlog changelog, Revlog manifestRevlog, int revA, int revB, int depth) throws IOException {
        Set<Integer> ancestorsA = getAllAncestors(changelog, revA);
        Set<Integer> ancestorsB = getAllAncestors(changelog, revB);

        Set<Integer> common = new HashSet<>(ancestorsA);
        common.retainAll(ancestorsB);

        if (common.isEmpty()) {
            return new MergeBase(-1, Collections.emptyMap());
        }

        Set<Integer> candidates = new HashSet<>(common);
        for (int c : common) {
            for (int other : common) {
                if (c != other && isAncestor(changelog, c, other)) {
                    candidates.remove(c);
                }
            }
        }

        if (candidates.isEmpty()) {
            return new MergeBase(-1, Collections.emptyMap());
        }

        if (candidates.size() == 1 || depth > 3) {
            int bestRev = Collections.max(candidates);
            return new MergeBase(bestRev, loadManifestAtCommit(changelog, manifestRevlog, bestRev));
        }

        // Criss-cross LCA recursive base synthesis
        List<Integer> sortedCandidates = new ArrayList<>(candidates);
        sortedCandidates.sort(Collections.reverseOrder());
        int c1 = sortedCandidates.get(0);
        int c2 = sortedCandidates.get(1);

        MergeBase virtualBase = getMergeBase(changelog, manifestRevlog, c1, c2, depth + 1);

        Map<String, String> manifestC1 = loadManifestAtCommit(changelog, manifestRevlog, c1);
        Map<String, String> manifestC2 = loadManifestAtCommit(changelog, manifestRevlog, c2);

        Map<String, String> virtualManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Map<String, byte[]> virtualFileContents = new java.util.HashMap<>();

        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(virtualBase.manifest.keySet());
        allPaths.addAll(manifestC1.keySet());
        allPaths.addAll(manifestC2.keySet());

        for (String path : allPaths) {
            String hBase = virtualBase.manifest.get(path);
            String hC1 = manifestC1.get(path);
            String hC2 = manifestC2.get(path);

            if (Objects.equals(hC1, hC2)) {
                if (hC1 != null) {
                    virtualManifest.put(path, hC1);
                }
                continue;
            }

            if (hC1 == null && hC2 != null) {
                if (hBase == null) {
                    virtualManifest.put(path, hC2);
                }
            } else if (hC1 != null && hC2 == null) {
                if (hBase == null) {
                    virtualManifest.put(path, hC1);
                }
            } else if (hC1 != null && hC2 != null) {
                byte[] baseContent = virtualBase.getFileContent(this, path, hBase);
                byte[] c1Content = getFileRevisionContent(path, hC1);
                byte[] c2Content = getFileRevisionContent(path, hC2);

                List<String> baseLines = readLines(baseContent);
                List<String> c1Lines = readLines(c1Content);
                List<String> c2Lines = readLines(c2Content);

                Merge3.MergeResult mergeRes = Merge3.merge(baseLines, c1Lines, c2Lines);
                StringBuilder sb = new StringBuilder();
                for (String line : mergeRes.getMergedLines()) {
                    sb.append(line).append('\n');
                }
                byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

                byte[] virtualHash = hashBytes(mergedBytes);
                String virtualHex = NodeIdUtil.toHex(virtualHash);
                String flag = hC1.length() > 40 ? hC1.substring(40) : "";
                virtualManifest.put(path, virtualHex + flag);
                virtualFileContents.put(path, mergedBytes);
            }
        }

        return new MergeBase(virtualManifest, virtualFileContents);
    }

    public MergeResult call() throws IOException {
        try (org.hg4j.core.HgLock storeLock = repository.lockStore();
             org.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            
            Dirstate dirstate = repository.getDirstate();
            org.hg4j.lib.NodeId p1CommitNode = dirstate.getParent1Node();
            if (p1CommitNode == null || p1CommitNode.isNull()) {
                throw new IllegalStateException("Cannot merge in an empty repository.");
            }

            // 1. Load changelog
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            // Resolve target revision index and Node ID
            int p1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1CommitNode.getBytes());
            if (p1Rev == -1) {
                throw new IOException("Current parent commit not found: " + p1CommitNode.toHex());
            }

            int p2Rev = targetRev;
            byte[] p2CommitNode = targetNodeId;
            if (p2CommitNode != null) {
                p2Rev = NodeIdUtil.findRevisionByNodeId(changelog, p2CommitNode);
                if (p2Rev == -1) {
                    throw new IOException("Target commit not found: " + NodeIdUtil.toHex(p2CommitNode));
                }
            } else if (p2Rev != -1) {
                if (p2Rev < 0 || p2Rev >= changelog.getRevisionCount()) {
                    throw new IllegalArgumentException("Target revision index out of bounds: " + p2Rev);
                }
                p2CommitNode = new byte[20];
                System.arraycopy(changelog.getIndexRecord(p2Rev).getNodeId(), 0, p2CommitNode, 0, 20);
            } else {
                throw new IllegalArgumentException("Target NodeID or revision index must be specified.");
            }

            if (p1Rev == p2Rev) {
                return new MergeResult(false, Collections.emptyList());
            }

            // 3. Load manifest revlog
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");
            Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

            // 2. Find Merge Base (LCA)
            MergeBase lca = getMergeBase(changelog, manifestRevlog, p1Rev, p2Rev, 0);
            if (lca.manifest.isEmpty() && lca.rev == -1) {
                throw new IOException("No common ancestor found between " + p1Rev + " and " + p2Rev);
            }

            if (lca.rev == p2Rev) {
                // Target is already merged (ancestor of P1)
                return new MergeResult(false, Collections.emptyList());
            }

            if (lca.rev == p1Rev) {
                // Fast-forward update: P1 is an ancestor of P2. Cleanly advance P1 to P2.
                Map<String, String> manifestP1 = loadManifestAtCommit(changelog, manifestRevlog, p1Rev);
                Map<String, String> manifestP2 = loadManifestAtCommit(changelog, manifestRevlog, p2Rev);

                for (String path : manifestP1.keySet()) {
                    if (!manifestP2.containsKey(path)) {
                        deleteFileFromWorkingCopy(path);
                        dirstate.removeEntry(path);
                    }
                }
                for (Map.Entry<String, String> entry : manifestP2.entrySet()) {
                    String path = entry.getKey();
                    String hex = entry.getValue();
                    byte[] content = getFileRevisionContent(path, hex);
                    int mode = getModeFromManifestHex(hex);
                    writeFileToWorkingCopy(path, content, mode);
                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                }
                dirstate.setParents(new org.hg4j.lib.NodeId(p2CommitNode), org.hg4j.lib.NodeId.NULL);
                repository.writeDirstate(dirstate);
                return new MergeResult(false, Collections.emptyList());
            }

            Map<String, String> manifestLca = lca.manifest;
            Map<String, String> manifestP1 = loadManifestAtCommit(changelog, manifestRevlog, p1Rev);
            Map<String, String> manifestP2 = loadManifestAtCommit(changelog, manifestRevlog, p2Rev);

            // 4. Reconcile manifests
            Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            allPaths.addAll(manifestLca.keySet());
            allPaths.addAll(manifestP1.keySet());
            allPaths.addAll(manifestP2.keySet());

            List<String> conflicts = new ArrayList<>();
            boolean conflicted = false;

            for (String path : allPaths) {
                String hLca = manifestLca.get(path);
                String hP1 = manifestP1.get(path);
                String hP2 = manifestP2.get(path);

                if (Objects.equals(hP1, hP2)) {
                    continue;
                }

                if (hP1 == null && hP2 != null) {
                    if (hLca == null) {
                        int mode = getModeFromManifestHex(hP2);
                        byte[] content = getFileRevisionContent(path, hP2);
                        writeFileToWorkingCopy(path, content, mode);
                        dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                    } else {
                        deleteFileFromWorkingCopy(path);
                        dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                    }
                } else if (hP1 != null && hP2 == null) {
                    if (hLca == null) {
                        // Added in P1, keep P1 version (no action needed)
                    } else {
                        deleteFileFromWorkingCopy(path);
                        dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                    }
                } else if (hP1 != null && hP2 != null) {
                    if (Objects.equals(hP1, hLca)) {
                        int mode = getModeFromManifestHex(hP2);
                        byte[] content = getFileRevisionContent(path, hP2);
                        writeFileToWorkingCopy(path, content, mode);
                        dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                    } else if (Objects.equals(hP2, hLca)) {
                        // P2 is unmodified, P1 modified it. Keep P1 version
                    } else {
                        // Both modified! Perform 3-way merge
                        byte[] baseContent = lca.getFileContent(this, path, hLca);
                        byte[] mineContent = getFileRevisionContent(path, hP1);
                        byte[] theirsContent = getFileRevisionContent(path, hP2);

                        List<String> baseLines = readLines(baseContent);
                        List<String> mineLines = readLines(mineContent);
                        List<String> theirsLines = readLines(theirsContent);

                        Merge3.MergeResult mergeRes = Merge3.merge(baseLines, mineLines, theirsLines);
                        StringBuilder sb = new StringBuilder();
                        for (String line : mergeRes.getMergedLines()) {
                            sb.append(line).append('\n');
                        }
                        byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                        int mode = getModeFromManifestHex(hP1);
                        writeFileToWorkingCopy(path, mergedBytes, mode);

                        if (mergeRes.isConflicted()) {
                            conflicted = true;
                            conflicts.add(path);
                        }
                        dirstate.addEntry(path, new Dirstate.Entry('m', mode, mergedBytes.length, System.currentTimeMillis() / 1000));
                    }
                }
            }

            // 5. Update dirstate parent headers to P1 and P2
            dirstate.setParents(p1CommitNode, new org.hg4j.lib.NodeId(p2CommitNode));
            repository.writeDirstate(dirstate);

            return new MergeResult(conflicted, conflicts);
        }
    }

    private Set<Integer> getAllAncestors(Revlog changelog, int startRev) {
        Set<Integer> ancestors = new HashSet<>();
        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(startRev);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == -1) continue;
            if (ancestors.add(current)) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(current);
                queue.add(rec.getParent1());
                queue.add(rec.getParent2());
            }
        }
        return ancestors;
    }

    private boolean isAncestor(Revlog changelog, int ancestor, int descendant) {
        if (ancestor == descendant) return true;
        if (ancestor > descendant) return false; // In revlogs, ancestors always have smaller revision numbers
        
        long cacheKey = ((long) ancestor << 32) | (descendant & 0xFFFFFFFFL);
        if (ancestorCache.containsKey(cacheKey)) {
            return ancestorCache.get(cacheKey);
        }

        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(descendant);
        Set<Integer> visited = new HashSet<>();
        boolean result = false;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == ancestor) {
                result = true;
                break;
            }
            if (current == -1 || current < ancestor) continue;
            if (visited.add(current)) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(current);
                queue.add(rec.getParent1());
                queue.add(rec.getParent2());
            }
        }
        
        ancestorCache.put(cacheKey, result);
        return result;
    }

    private Map<String, String> loadManifestAtCommit(Revlog changelog, Revlog manifestRevlog, int commitRev) throws IOException {
        byte[] commitContent = changelog.getRevisionContent(commitRev);
        String clText = new String(commitContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0].trim();
        if (firstLine.length() > 40) {
            firstLine = firstLine.substring(0, 40);
        }
        byte[] prevManifestNode = NodeIdUtil.fromHex(firstLine);

        int manifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, prevManifestNode);
        Map<String, String> manifestMap = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        if (manifestRev != -1) {
            byte[] manifestContent = manifestRevlog.getRevisionContent(manifestRev);
            parseManifest(new String(manifestContent, StandardCharsets.UTF_8), manifestMap);
        }
        return manifestMap;
    }

    private byte[] getFileRevisionContent(String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new IOException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        String cleanHex = nodeHex.length() > 40 ? nodeHex.substring(0, 40) : nodeHex;
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(cleanHex));
        if (rev == -1) {
            throw new IOException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }

    private void writeFileToWorkingCopy(String path, byte[] content, int mode) throws IOException {
        File f = new File(repository.getDirectory(), path);
        f.getParentFile().mkdirs();
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
        if (mode == 0120000) {
            String target = new String(content, StandardCharsets.UTF_8).trim();
            try {
                Files.createSymbolicLink(f.toPath(), java.nio.file.Path.of(target));
            } catch (Exception e) {
                // Fallback if symbolic links aren't supported on OS/filesystem without privilege
                Files.write(f.toPath(), content);
            }
        } else {
            Files.write(f.toPath(), content);
            if (mode == 0755) {
                f.setExecutable(true, false);
            } else {
                f.setExecutable(false, false);
            }
        }
    }

    private void deleteFileFromWorkingCopy(String path) {
        File f = new File(repository.getDirectory(), path);
        if (f.exists()) {
            f.delete();
        }
    }

    private List<String> readLines(byte[] content) {
        if (content == null || content.length == 0) {
            return new ArrayList<>();
        }
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        List<String> list = new ArrayList<>();
        int len = lines.length;
        if (text.endsWith("\n") && len > 0) {
            len--;
        }
        for (int i = 0; i < len; i++) {
            list.add(lines[i]);
        }
        return list;
    }

    private void parseManifest(String text, Map<String, String> result) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx != -1) {
                String path = line.substring(0, nullIdx);
                String hex = line.substring(nullIdx + 1);
                result.put(path, hex.trim());
            }
        }
    }
}
