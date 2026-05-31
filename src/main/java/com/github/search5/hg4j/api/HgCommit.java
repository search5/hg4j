package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.NodeId;
import java.util.List;

/**
 * Represents a Mercurial commit (revision in the changelog).
 */
public class HgCommit {
    private final int revision;
    private final NodeId nodeId;
    private final NodeId manifestNodeId;
    private final String author;
    private final long timestamp;
    private final int timezoneOffset;
    private final List<String> files;
    private final String message;
    private final String branch;

    public HgCommit(int revision, NodeId nodeId, NodeId manifestNodeId, String author, 
                    long timestamp, int timezoneOffset, List<String> files, String message, String branch) {
        this.revision = revision;
        this.nodeId = nodeId;
        this.manifestNodeId = manifestNodeId;
        this.author = author;
        this.timestamp = timestamp;
        this.timezoneOffset = timezoneOffset;
        this.files = files;
        this.message = message;
        this.branch = branch != null ? branch : "default";
    }

    public int getRevision() {
        return revision;
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public NodeId getManifestNodeId() {
        return manifestNodeId;
    }

    public String getAuthor() {
        return author;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getTimezoneOffset() {
        return timezoneOffset;
    }

    public List<String> getFiles() {
        return files;
    }

    public String getMessage() {
        return message;
    }

    public String getBranch() {
        return branch;
    }
}
