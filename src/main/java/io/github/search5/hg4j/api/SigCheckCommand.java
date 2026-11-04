package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigResult;
import io.github.search5.hg4j.gpg.HgSigVerifier;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code hg sigcheck REV}: verifies every signature recorded for one changeset (behaviour of
 * {@code hgext/gpg.py}'s {@code sigcheck}). An empty {@link Result#validSignatures()} is what real hg reports
 * as {@code no valid signature for <short>}.
 */
public class SigCheckCommand {

    /**
     * The outcome of checking one changeset.
     * @param nodeHex the 40-character hex node id that was checked
     * @param validSignatures the signatures that verified, possibly empty
     * @param warnings messages about signatures that did not verify or have problems, in Mercurial's wording
     */
    public record Result(String nodeHex, List<HgSigResult> validSignatures, List<String> warnings) {
        /**
         * Tells whether at least one valid signature was found.
         * @return {@code true} if {@code validSignatures} is not empty
         */
        public boolean isSigned() {
            return !validSignatures.isEmpty();
        }
    }

    private final HgRepository repository;
    private byte[] nodeId;
    private GpgKeyring keyring;
    private Instant now;

    /**
     * Creates the command.
     * @param repository the repository to check in; must not be {@code null}
     */
    public SigCheckCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The changeset to check (20-byte node). */
    public SigCheckCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets the keyring holding the public keys used to verify the signatures.
     * @param keyring the keyring; must be non-null before {@link #call()}
     * @return {@code this}
     */
    public SigCheckCommand setKeyring(GpgKeyring keyring) {
        this.keyring = keyring;
        return this;
    }

    /** The time expiry is judged against; default: now. */
    public SigCheckCommand setNow(Instant now) {
        this.now = now;
        return this;
    }

    /**
     * Verifies all signatures recorded for the changeset set with {@link #setNodeId}.
     * <p>
     * Reads every signature from the signature files (committed and local), picks those for the node and verifies each
     * against the keyring at the time given by {@link #setNow} (default: now).
     * @return the result; its valid signatures are empty when no signature verifies
     * @throws IllegalStateException if the node or the keyring was not set
     * @throws io.github.search5.hg4j.errors.HgRevisionNotFoundException (an {@link IOException}) if the node is not in
     *         the changelog
     * @throws IOException if reading the repository fails
     */
    public Result call() throws IOException {
        if (nodeId == null) {
            throw new IllegalStateException("A node must be set");
        }
        if (keyring == null) {
            throw new IllegalStateException("A keyring must be set");
        }
        repository.refreshIfChangedOnDisk();
        String hex = new NodeId(nodeId).toHex();
        if (SignatureStore.changelogRevision(repository, hex) < 0) {
            throw new HgRevisionNotFoundException(hex);
        }
        Instant at = now != null ? now : Instant.now();
        List<String> warnings = new ArrayList<>();
        List<HgSigResult> valid = new ArrayList<>();
        for (SignatureStore.Located located : SignatureStore.readAll(repository)) {
            if (!located.entry().getNodeHex().equals(hex)) {
                continue;
            }
            HgSigResult result = HgSigVerifier.verify(located.entry(), keyring, at);
            SigWarnings.addFor(warnings, located.where(), result);
            if (result.isValid()) {
                valid.add(result);
            }
        }
        return new Result(hex, valid, warnings);
    }
}
