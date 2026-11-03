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

    public record Result(String nodeHex, List<HgSigResult> validSignatures, List<String> warnings) {
        public boolean isSigned() {
            return !validSignatures.isEmpty();
        }
    }

    private final HgRepository repository;
    private byte[] nodeId;
    private GpgKeyring keyring;
    private Instant now;

    public SigCheckCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The changeset to check (20-byte node). */
    public SigCheckCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public SigCheckCommand setKeyring(GpgKeyring keyring) {
        this.keyring = keyring;
        return this;
    }

    /** The time expiry is judged against; default: now. */
    public SigCheckCommand setNow(Instant now) {
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
