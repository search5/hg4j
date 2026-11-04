package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.NodeId;
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
    /**
     * Creates an immutable commit description.
     * @param revision local revision number in the changelog
     * @param nodeId node id of the changeset
     * @param manifestNodeId node id of the manifest revision the changeset points to
     * @param author author string as stored in the changelog (typically {@code Name <email>})
     * @param timestamp commit time in seconds since the Unix epoch
     * @param timezoneOffset time zone offset as stored in the changelog (seconds west of UTC, Mercurial's convention)
     * @param files paths of the files changed by the changeset; stored as given, not copied
     * @param message commit message
     * @param branch branch name; {@code null} is replaced by {@code "default"}
     */
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

    /**
     * Returns the local revision number of this changeset in the changelog.
     * @return the revision number
     */
    public int getRevision() {
        return revision;
    }

    /**
     * Returns the node id (hash) of this changeset.
     * @return the changeset node id
     */
    public NodeId getNodeId() {
        return nodeId;
    }

    /**
     * Returns the node id of the manifest revision this changeset points to.
     * @return the manifest node id
     */
    public NodeId getManifestNodeId() {
        return manifestNodeId;
    }

    /**
     * Returns the author string as stored in the changelog.
     * @return the author
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Returns the commit time.
     * @return seconds since the Unix epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the time zone offset as stored in the changelog.
     * @return the offset in seconds west of UTC (Mercurial's convention)
     */
    public int getTimezoneOffset() {
        return timezoneOffset;
    }

    /**
     * Returns the paths of the files changed by this changeset, as listed in the changelog entry.
     * @return the list of file paths; this is the list passed to the constructor, not a copy
     */
    public List<String> getFiles() {
        return files;
    }

    /**
     * Returns the commit message.
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the branch this changeset belongs to.
     * @return the branch name; {@code "default"} when none was recorded
     */
    public String getBranch() {
        return branch;
    }
}
