package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.NodeId;
import java.util.List;

/**
 * Represents a Mercurial commit (revision in the changelog).
 *
 * @apiNote Returned by {@link LogCommand#call()} (obtained via {@link Hg#log()}); the GPG
 *     signature fields are populated for a signed commit and can be verified with {@link
 *     io.github.search5.hg4j.gpg.GpgSignature}.
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
    // Mercurial commit GPG signature verification, embedded in the changelog `extra`
    // dictionary (git `gpgsig` commit header equivalent shape; see CommitCommand.setGpgSigner()).
    // All three are null when this revision has no gpgsig extra.
    private final String gpgSignature;
    private final String gpgFingerprint;
    private final byte[] unsignedChangelogText;

    /**
     * Creates a commit with no GPG signature information (equivalent to calling the full
     * constructor with {@code null} signature, fingerprint, and unsigned text).
     *
     * @param revision the local (repository-relative) revision number
     * @param nodeId this commit's node ID
     * @param manifestNodeId the node ID of the manifest revision this commit points to
     * @param author the commit's author string, as stored in the changelog
     * @param timestamp the commit time, as seconds since the Unix epoch (UTC)
     * @param timezoneOffset the commit's recorded timezone offset from UTC, in seconds
     * @param files the paths touched by this commit
     * @param message the commit message
     * @param branch the named branch this commit belongs to, or {@code null} for {@code "default"}
     */
    public HgCommit(int revision, NodeId nodeId, NodeId manifestNodeId, String author,
                    long timestamp, int timezoneOffset, List<String> files, String message, String branch) {
        this(revision, nodeId, manifestNodeId, author, timestamp, timezoneOffset, files, message, branch,
                null, null, null);
    }

    /**
     * Creates a commit, optionally carrying GPG signature information.
     *
     * @param revision the local (repository-relative) revision number
     * @param nodeId this commit's node ID
     * @param manifestNodeId the node ID of the manifest revision this commit points to
     * @param author the commit's author string, as stored in the changelog
     * @param timestamp the commit time, as seconds since the Unix epoch (UTC)
     * @param timezoneOffset the commit's recorded timezone offset from UTC, in seconds
     * @param files the paths touched by this commit
     * @param message the commit message
     * @param branch the named branch this commit belongs to, or {@code null} for {@code "default"}
     * @param gpgSignature the ASCII-armored OpenPGP signature, or {@code null} if unsigned
     * @param gpgFingerprint the informational signing key fingerprint, or {@code null}
     * @param unsignedChangelogText the raw changelog text the signature was computed over, or {@code null} if unsigned
     */
    public HgCommit(int revision, NodeId nodeId, NodeId manifestNodeId, String author,
                    long timestamp, int timezoneOffset, List<String> files, String message, String branch,
                    String gpgSignature, String gpgFingerprint, byte[] unsignedChangelogText) {
        this.revision = revision;
        this.nodeId = nodeId;
        this.manifestNodeId = manifestNodeId;
        this.author = author;
        this.timestamp = timestamp;
        this.timezoneOffset = timezoneOffset;
        this.files = files;
        this.message = message;
        this.branch = branch != null ? branch : "default";
        this.gpgSignature = gpgSignature;
        this.gpgFingerprint = gpgFingerprint;
        this.unsignedChangelogText = unsignedChangelogText;
    }

    /**
     * Returns the local (repository-relative) revision number.
     *
     * @return the local revision number
     */
    public int getRevision() {
        return revision;
    }

    /**
     * Returns this commit's node ID.
     *
     * @return this commit's node ID
     */
    public NodeId getNodeId() {
        return nodeId;
    }

    /**
     * Returns the node ID of the manifest revision this commit points to.
     *
     * @return the manifest node ID
     */
    public NodeId getManifestNodeId() {
        return manifestNodeId;
    }

    /**
     * Returns the commit's author string, as stored in the changelog.
     *
     * @return the author string
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Returns the commit time.
     *
     * @return the commit time, as seconds since the Unix epoch (UTC)
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the commit's recorded timezone offset from UTC.
     *
     * @return the timezone offset, in seconds
     */
    public int getTimezoneOffset() {
        return timezoneOffset;
    }

    /**
     * Returns the paths touched by this commit.
     *
     * @return the paths touched by this commit
     */
    public List<String> getFiles() {
        return files;
    }

    /**
     * Returns the commit message.
     *
     * @return the commit message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the named branch this commit belongs to.
     *
     * @return the branch name (never {@code null}; {@code "default"} when none was recorded)
     */
    public String getBranch() {
        return branch;
    }

    /**
     * ASCII-armored OpenPGP signature (real newlines, already un-escaped), or {@code null} if unsigned.
     *
     * @return the ASCII-armored OpenPGP signature, or {@code null} if unsigned
     */
    public String getGpgSignature() {
        return gpgSignature;
    }

    /**
     * Purely informational key-identification bookkeeping stored alongside {@code gpgsig} (see
     * {@code CommitCommand.setGpgSigner()}) -- a verifier should identify the signing key from
     * the OpenPGP signature packet's own issuer key ID instead, exactly as it already does for
     * git. May be {@code null}.
     *
     * @return the informational signing key fingerprint, or {@code null}
     */
    public String getGpgFingerprint() {
        return gpgFingerprint;
    }

    /**
     * The exact bytes a {@code gpgsig} signature was computed over -- this revision's raw
     * changelog text with only the {@code gpgsig} extra entry removed (every other field,
     * including {@code gpgfingerprint}, stays byte-identical). {@code null} when {@link
     * #getGpgSignature()} is {@code null}.
     *
     * @return the raw changelog text the signature was computed over, or {@code null} if unsigned
     */
    public byte[] getUnsignedChangelogText() {
        return unsignedChangelogText;
    }
}
