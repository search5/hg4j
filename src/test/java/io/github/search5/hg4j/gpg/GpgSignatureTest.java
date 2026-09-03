package io.github.search5.hg4j.gpg;
import io.github.search5.hg4j.api.*;

import io.github.search5.hg4j.gpg.GpgSignature;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
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

    @Test
    public void testCommitWithGpgSignatureInMetadata(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo_gpg").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // KeyPair generation
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Gpg signed commit");
        new AddCommand(repo).call();

        byte[] contentToSign = "Commit Msg".getBytes(StandardCharsets.UTF_8);
        GpgSignature signature = GpgSignature.sign(contentToSign, keyPair.getPrivate(), "F123456");

        // 1. Commit with GPG signature attached
        CommitCommand commitCmd = new CommitCommand(repo)
                .setAuthor("Gpg Signer <gpg@example.com>")
                .setMessage("Commit Msg")
                .setGpgSignature(signature);

        byte[] commitNode = commitCmd.call();
        assertNotNull(commitNode);

        // 2. Read changelog directly and check metadata integration
        Revlog cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        int rev = cl.findRevision(commitNode);
        assertTrue(rev != -1);

        // 3. Assert on-disk metadata keys are correctly injected and matched
        Map<String, String> meta = cl.getRevisionMetadata(rev);
        assertTrue(meta.containsKey("gpgsig"));
        assertTrue(meta.containsKey("gpgfingerprint"));
        assertEquals(signature.toAsciiArmored().replace("\n", "\\n"), meta.get("gpgsig"));
        assertEquals("F123456", meta.get("gpgfingerprint"));

        // 4. Verify roundtrip reconstruction via fromAsciiArmored and successful verification
        GpgSignature restored = GpgSignature.fromAsciiArmored(meta.get("gpgsig"), meta.get("gpgfingerprint"));
        assertTrue(restored.verify(contentToSign, keyPair.getPublic()), "Armored block에서 복원된 서명도 반드시 참이어야 합니다.");
    }
}
