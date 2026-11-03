package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigResult;
import io.github.search5.hg4j.gpg.HgSigVerifier;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Verifies the signature a changeset carries in its {@code gpgsig} extra (made by {@link
 * CommitCommand#setGpgSigner(GpgKeyring, String, char[])} or any signer following the same convention).
 *
 * <p>The signed bytes are the changeset's raw text with only the {@code gpgsig} entry removed, exactly what
 * {@link HgCommit#getUnsignedChangelogText()} returns, so a signature stays valid after the changeset is
 * pushed, pulled or cloned: the node hash and the extras travel unchanged. As with Git, "valid" only says that
 * the key in {@code keyring} made the signature; deciding whether that key belongs to the committer is up to
 * the caller (a server compares the key's user ids with the committer, like GitHub's "verified" badge).</p>
 */
public class VerifyCommitCommand {

    /**
     * @param nodeHex   the changeset
     * @param signed    whether the changeset carries a {@code gpgsig} at all
     * @param signature the verification result; null exactly when {@code signed} is false
     * @param claimedFingerprint the informational {@code gpgfingerprint} extra, or null; never trusted
     */
    public record Result(String nodeHex, boolean signed, HgSigResult signature, String claimedFingerprint) {
        /** @return true if the changeset is signed and the signature counts as valid (see HgSigResult.Status#isValid) */
        public boolean isValid() {
            return signed && signature.isValid();
        }
    }

    private final HgRepository repository;
    private byte[] nodeId;
    private GpgKeyring keyring;
    private Instant now;

    public VerifyCommitCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The changeset to check (20-byte node). */
    public VerifyCommitCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public VerifyCommitCommand setKeyring(GpgKeyring keyring) {
        this.keyring = keyring;
        return this;
    }

    /** The time expiry is judged against; default: now. */
    public VerifyCommitCommand setNow(Instant now) {
        this.now = now;
        return this;
    }

    public Result call() throws IOException {
        if (nodeId == null) {
            throw new IllegalStateException("A node must be set");
        }
        if (keyring == null) {
            throw new IllegalStateException("A keyring must be set");
        }
        repository.refreshIfChangedOnDisk();
        NodeId wanted = new NodeId(nodeId);
        String hex = wanted.toHex();
        if (SignatureStore.changelogRevision(repository, hex) < 0) {
            throw new HgRevisionNotFoundException(hex);
        }

        HgCommit commit = null;
        List<HgCommit> history = new LogCommand(repository).setStartRev(hex).call();
        for (HgCommit c : history) {
            if (c.getNodeId().equals(wanted)) {
                commit = c;
                break;
            }
        }
        if (commit == null) {
            throw new HgRevisionNotFoundException(hex);
        }
        if (commit.getGpgSignature() == null) {
            return new Result(hex, false, null, null);
        }
        HgSigResult result = HgSigVerifier.verifyDetached(commit.getUnsignedChangelogText(),
                commit.getGpgSignature().getBytes(StandardCharsets.US_ASCII), keyring, now != null ? now : Instant.now());
        return new Result(hex, true, result, commit.getGpgFingerprint());
    }
}
