package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TreeIterator implementation that traverses historical repository manifests.
 */
public class ManifestTreeIterator implements TreeIterator {

    private final HgRepository repository;
    private final String revision;
    private final byte[] directManifestNode;
    private final List<Entry> entries = new ArrayList<>();
    private int index = -1;

    public static class Entry {
        final String path;
        final byte[] nodeId;
        final boolean executable;
        final boolean symlink;

        Entry(String path, byte[] nodeId, boolean executable, boolean symlink) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
            this.symlink = symlink;
        }
    }

    public ManifestTreeIterator(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
        this.directManifestNode = null;
    }

    public ManifestTreeIterator(HgRepository repository, byte[] manifestNode) {
        this.repository = repository;
        this.revision = null;
        this.directManifestNode = manifestNode;
    }

    @Override
    public void reset() throws IOException {
        entries.clear();
        index = -1;
        loadEntries();
    }

    public static List<Entry> parseManifestContent(byte[] mfContent) {
        List<Entry> result = new ArrayList<>();
        int start = 0;
        int len = mfContent.length;
        while (start < len) {
            int end = start;
            while (end < len && mfContent[end] != '\n') {
                end++;
            }

            if (end > start) {
                int nullIdx = -1;
                for (int i = start; i < end; i++) {
                    if (mfContent[i] == '\0') {
                        nullIdx = i;
                        break;
                    }
                }

                if (nullIdx != -1) {
                    String path = new String(mfContent, start, nullIdx - start, java.nio.charset.StandardCharsets.UTF_8);
                    int valStart = nullIdx + 1;
                    int valLen = end - valStart;

                    if (valLen >= 40) {
                        boolean isHexText = true;
                        for (int i = 0; i < 40; i++) {
                            char c = (char) mfContent[valStart + i];
                            if (Character.digit(c, 16) == -1) {
                                isHexText = false;
                                break;
                            }
                        }

                        if (isHexText) {
                            String hexNodeId = new String(mfContent, valStart, 40, java.nio.charset.StandardCharsets.UTF_8);
                            String flag = "";
                            if (valLen > 40) {
                                flag = new String(mfContent, valStart + 40, valLen - 40, java.nio.charset.StandardCharsets.UTF_8).trim();
                            }
                            boolean executable = flag.contains("x");
                            boolean symlink = flag.contains("l");
                            result.add(new Entry(path, NodeIdUtil.fromHex(hexNodeId), executable, symlink));

                            start = end + 1;
                            continue;
                        }
                    }

                    if (valStart + 20 <= end) {
                        byte[] hashBytes = new byte[20];
                        System.arraycopy(mfContent, valStart, hashBytes, 0, 20);
                        String hexNodeId = NodeIdUtil.toHex(hashBytes);

                        int flagStart = valStart + 20;
                        String flag = "";
                        if (flagStart < end) {
                            flag = new String(mfContent, flagStart, end - flagStart, java.nio.charset.StandardCharsets.UTF_8).trim();
                        }
                        boolean executable = flag.contains("x");
                        boolean symlink = flag.contains("l");
                        result.add(new Entry(path, NodeIdUtil.fromHex(hexNodeId), executable, symlink));
                    }
                }
            }
            start = end + 1;
        }
        return result;
    }

    private void loadEntries() throws IOException {
        byte[] mfNode = null;

        if (directManifestNode != null) {
            mfNode = directManifestNode;
        } else {
            if (revision == null || "".equals(revision) || "-1".equals(revision) || "null".equalsIgnoreCase(revision)) {
                return;
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
            if (targetNodeId == null) {
                throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Revision not found in changelog: " + revision);
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Commit not found in changelog for node: " + NodeIdUtil.toHex(targetNodeId));
            }

            byte[] clContent = changelog.getRevisionContent(commitRev);

            int firstNewLine = -1;
            for (int i = 0; i < clContent.length; i++) {
                if (clContent[i] == '\n') {
                    firstNewLine = i;
                    break;
                }
            }

            if (firstNewLine >= 40) {
                boolean isHexText = true;
                for (int i = 0; i < 40; i++) {
                    char c = (char) clContent[i];
                    if (Character.digit(c, 16) == -1) {
                        isHexText = false;
                        break;
                    }
                }

                if (isHexText) {
                    String hexNode = new String(clContent, 0, 40, java.nio.charset.StandardCharsets.UTF_8);
                    mfNode = NodeIdUtil.fromHex(hexNode);
                }
            }

            if (mfNode == null) {
                if (clContent.length >= 20) {
                    mfNode = new byte[20];
                    System.arraycopy(clContent, 0, mfNode, 0, 20);
                } else {
                    throw new IOException("Changelog content too short to extract manifest node ID");
                }
            }
        }

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);
        int mfRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, mfNode);
        if (mfRev == -1) {
            throw new IOException("Manifest not found: " + NodeIdUtil.toHex(mfNode));
        }

        byte[] mfContent = manifestRevlog.getRevisionContent(mfRev);
        entries.addAll(parseManifestContent(mfContent));
        
        entries.sort((e1, e2) -> NodeIdUtil.UTF8_STRING_COMPARATOR.compare(e1.path, e2.path));
    }



    @Override
    public String getEntryPath() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).path;
        }
        return null;
    }

    @Override
    public byte[] getEntryNodeId() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).nodeId;
        }
        return null;
    }

    @Override
    public boolean isExecutable() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).executable;
        }
        return false;
    }

    public boolean isSymlink() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).symlink;
        }
        return false;
    }

    @Override
    public char getEntryState() {
        // Manifest is tracked, so normally active 'n'
        return 'n';
    }

    @Override
    public boolean next() throws IOException {
        if (index < entries.size() - 1) {
            index++;
            return true;
        }
        index = entries.size(); // mark as finished
        return false;
    }
}
