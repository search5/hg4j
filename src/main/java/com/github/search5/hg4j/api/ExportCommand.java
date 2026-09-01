package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Porcelain command for exporting Mercurial changeset commits
 * into standard patch file formats.
 */
public class ExportCommand {
    private final HgRepository repository;
    private String revision;

    public ExportCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ExportCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Exports the changeset in unified patch format.
     *
     * @return unified diff patch text
     * @throws IOException if revision metadata extraction fails
     */
    public String call() throws IOException {
        if (revision == null || revision.isEmpty()) {
            throw new IllegalArgumentException("Revision target must be specified for changeset export");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, revision);
        int rev = changelog.findRevision(nodeBytes);
        if (rev == -1) {
            throw new IOException("Revision not found in repository changelog: " + revision);
        }

        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");

        String author = "unknown";
        String date = "0 0";
        if (lines.length > 1) {
            author = lines[1].trim();
        }
        if (lines.length > 2) {
            date = lines[2].trim();
        }
        StringBuilder desc = new StringBuilder();
        int descStart = -1;
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                descStart = i + 1;
                break;
            }
        }
        if (descStart != -1) {
            for (int i = descStart; i < lines.length; i++) {
                if (desc.length() > 0) desc.append("\n");
                desc.append(lines[i]);
            }
        }

        // Use the changeset's actual first parent revision, not rev - 1: revision numbers are
        // commit order, not DAG order, so a changeset's parent is not necessarily the immediately
        // preceding revision (e.g. a second head committed on top of an earlier ancestor). Verified
        // against real `hg export` on a branched history: it reports and diffs against the true
        // parent, not rev - 1.
        int parentRev = changelog.getIndexRecord(rev).getParent1();

        StringBuilder sb = new StringBuilder();
        sb.append("# HG changeset patch\n");
        sb.append("# User ").append(author).append("\n");
        sb.append("# Date ").append(date).append("\n");
        sb.append("# Node ID ").append(NodeIdUtil.toHex(nodeBytes)).append("\n");
        if (parentRev != -1) {
            sb.append("# Parent  ").append(NodeIdUtil.toHex(changelog.getIndexRecord(parentRev).getNodeId())).append("\n");
        }
        sb.append(desc.toString()).append("\n\n");

        DiffCommand diffCmd = new DiffCommand(repository);
        diffCmd.setNewRevision(rev);
        if (parentRev != -1) {
            diffCmd.setOldRevision(parentRev);
        } else {
            diffCmd.setOldRevision(-2);
        }

        List<DiffCommand.DiffEntry> diffs = diffCmd.call();
        for (DiffCommand.DiffEntry diff : diffs) {
            sb.append("diff -r ");
            if (parentRev != -1) {
                sb.append(NodeIdUtil.toHex(changelog.getIndexRecord(parentRev).getNodeId()).substring(0, 12));
            } else {
                sb.append("000000000000");
            }
            sb.append(" -r ").append(NodeIdUtil.toHex(nodeBytes).substring(0, 12)).append(" ").append(diff.getPath()).append("\n");
            sb.append(diff.getDiffContent());
        }

        return sb.toString();
    }
}
