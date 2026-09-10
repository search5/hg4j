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

    public HgCommit(int revision, NodeId nodeId, NodeId manifestNodeId, String author,
                    long timestamp, int timezoneOffset, List<String> files, String message, String branch) {
        this(revision, nodeId, manifestNodeId, author, timestamp, timezoneOffset, files, message, branch,
                null, null, null);
    }

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

    /** ASCII-armored OpenPGP signature (real newlines, already un-escaped), or {@code null} if unsigned. */
    public String getGpgSignature() {
        return gpgSignature;
    }

    /**
     * Purely informational key-identification bookkeeping stored alongside {@code gpgsig} (see
     * {@code CommitCommand.setGpgSigner()}) -- a verifier should identify the signing key from
     * the OpenPGP signature packet's own issuer key ID instead, exactly as it already does for
     * git. May be {@code null}.
     */
    public String getGpgFingerprint() {
        return gpgFingerprint;
    }

    /**
     * The exact bytes a {@code gpgsig} signature was computed over -- this revision's raw
     * changelog text with only the {@code gpgsig} extra entry removed (every other field,
     * including {@code gpgfingerprint}, stays byte-identical). {@code null} when {@link
     * #getGpgSignature()} is {@code null}.
     */
    public byte[] getUnsignedChangelogText() {
        return unsignedChangelogText;
    }
}
