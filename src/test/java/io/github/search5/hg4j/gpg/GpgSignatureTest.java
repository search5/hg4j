package io.github.search5.hg4j.gpg;
import io.github.search5.hg4j.api.*;

import io.github.search5.hg4j.gpg.GpgSignature;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GpgSignatureTest {

    @Test
    public void testGpgSigningAndVerification() throws Exception {
        // 1. Generate RSA Key Pair for simulated PGP/GPG parity
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        byte[] content = "Commit content to sign".getBytes(StandardCharsets.UTF_8);
        String fingerprint = "FINGERPRINT_1234567890ABCDEF";

        // 2. Perform signing
        GpgSignature signature = GpgSignature.sign(content, keyPair.getPrivate(), fingerprint);
        assertNotNull(signature.getSignatureHex());
        assertEquals(fingerprint, signature.getKeyFingerprint());

        // 3. Verify signature
        boolean verified = signature.verify(content, keyPair.getPublic());
        assertTrue(verified, "Signature verification must succeed with correct public key and content.");

        // 4. Verify failure with corrupted content
        byte[] corruptedContent = "Corrupted commit content to sign".getBytes(StandardCharsets.UTF_8);
        boolean corruptedVerified = signature.verify(corruptedContent, keyPair.getPublic());
        assertFalse(corruptedVerified, "Signature verification must fail for altered content.");
    }

    // P3-19 -- real end-to-end round trip through CommitCommand.setGpgSigner()/LogCommand: a real
    // BouncyCastle-generated (RSA) key signs the commit, the changeset is read back through the
    // public HgCommit/LogCommand API (not by reaching into changelog internals), and the
    // signature verifies cryptographically against the exact reconstructed unsigned payload.
    // This used to assert the OLD/broken design where gpgsig was routed through
    // changelog.appendRevision's metadata parameter (Revlog.wrapMetadata -- a filelog-only
    // rename/copy metadata format, not a valid changelog revision shape at all); see
    // CommitCommandTest.testGpgSignatureIsStoredInChangelogExtraNotMetadata for the
    // corrected-format assertion and this project's P3-19 design log for the full story.
    @Test
    public void testCommitWithGpgSignerRoundTripsAndVerifies(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo_gpg").toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            String fingerprint = "F123456";

            File f1 = new File(repoDir, "hello.txt");
            Files.writeString(f1.toPath(), "Gpg signed commit");
            new AddCommand(repo).call();

            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("Gpg Signer <gpg@example.com>")
                    .setMessage("Commit Msg")
                    .setGpgSigner(fingerprint, payload -> GpgSignature.sign(payload, keyPair.getPrivate(), fingerprint).toAsciiArmored())
                    .call();
            assertNotNull(commitNode);

            HgCommit readBack = new LogCommand(repo).call().stream()
                    .filter(c -> c.getNodeId().toHex().equals(io.github.search5.hg4j.util.NodeIdUtil.toHex(commitNode)))
                    .findFirst().orElseThrow();

            assertNotNull(readBack.getGpgSignature(), "committed revision must carry a gpgsig extra");
            assertEquals(fingerprint, readBack.getGpgFingerprint());
            assertNotNull(readBack.getUnsignedChangelogText());

            GpgSignature restored = GpgSignature.fromAsciiArmored(readBack.getGpgSignature(), readBack.getGpgFingerprint());
            assertTrue(restored.verify(readBack.getUnsignedChangelogText(), keyPair.getPublic()),
                    "signature must verify against the reconstructed unsigned changelog payload");

            // Negative case 1: tampered payload must not verify.
            byte[] tampered = readBack.getUnsignedChangelogText().clone();
            tampered[0] ^= 0x01;
            assertFalse(restored.verify(tampered, keyPair.getPublic()),
                    "signature must NOT verify against a tampered payload");

            // Negative case 2: wrong public key must not verify.
            KeyPair otherKeyPair = keyGen.generateKeyPair();
            assertFalse(restored.verify(readBack.getUnsignedChangelogText(), otherKeyPair.getPublic()),
                    "signature must NOT verify against an unrelated public key");
        }
    }

    // P3-24 -- GpgSignature.sign()/verify() used to hardcode PGPPublicKey.RSA_GENERAL in every
    // JcaPGPKeyConverter.getPGPPublicKey(...) call, so any non-RSA key (EC, Ed25519, ...) would
    // either fail to sign/verify at all or be coerced into an incorrectly-typed OpenPGP key.
    // This exercises a real sign -> verify round trip for RSA, EC (P-256) and Ed25519 through the
    // sign(byte[], PrivateKey, PublicKey, String) overload, which resolves the correct
    // PublicKeyAlgorithmTags constant from the actual PublicKey instead of assuming RSA -- with
    // the same tampered-content / wrong-key negative coverage as the RSA case above.
    @ParameterizedTest
    @ValueSource(strings = {"RSA", "EC", "Ed25519"})
    public void testSignAndVerifyRoundTripAcrossAlgorithms(String algorithm) throws Exception {
        KeyPair keyPair = generateKeyPairFor(algorithm);
        byte[] content = ("Commit content signed with " + algorithm).getBytes(StandardCharsets.UTF_8);
        String fingerprint = "FPR_" + algorithm;

        GpgSignature signature = GpgSignature.sign(content, keyPair.getPrivate(), keyPair.getPublic(), fingerprint);
        assertNotNull(signature.getSignatureHex());
        assertEquals(fingerprint, signature.getKeyFingerprint());

        assertTrue(signature.verify(content, keyPair.getPublic()),
                algorithm + " signature must verify against the correct public key and content.");

        // Negative case 1: tampered content must not verify.
        byte[] tampered = ("Tampered content signed with " + algorithm).getBytes(StandardCharsets.UTF_8);
        assertFalse(signature.verify(tampered, keyPair.getPublic()),
                algorithm + " signature must NOT verify against altered content.");

        // Negative case 2: an unrelated key pair of the same algorithm must not verify.
        KeyPair otherKeyPair = generateKeyPairFor(algorithm);
        assertFalse(signature.verify(content, otherKeyPair.getPublic()),
                algorithm + " signature must NOT verify against an unrelated public key.");
    }

    private static KeyPair generateKeyPairFor(String algorithm) throws Exception {
        switch (algorithm) {
            case "EC": {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
                keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
                return keyGen.generateKeyPair();
            }
            case "Ed25519": {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
                return keyGen.generateKeyPair();
            }
            default: {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                keyGen.initialize(2048, new SecureRandom());
                return keyGen.generateKeyPair();
            }
        }
    }
}
