package io.github.search5.hg4j.api;

import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigResult;
import io.github.search5.hg4j.gpg.HgSigner;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.GpgHome;
import io.github.search5.hg4j.testsupport.LocalHg;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signing at commit time with the user's own GnuPG key, and checking the result: the {@code git commit -S}
 * workflow, with the signature carried in the changeset's {@code gpgsig} extra. The oracle is the REAL
 * {@code gpg} (does it accept the signature over the stored bytes?) and the REAL {@code hg} (does it still read,
 * verify and clone the signed repository?).
 */
@Tag("interop")
public class CommitSigningInteropTest {

    @TempDir
    static Path shared;

    private static GpgHome gpg;
    private static String rsaFpr;
    private static String edFpr;
    private static GpgKeyring keyring;

    @TempDir
    Path tmp;
    private Path repoDir;

    @BeforeAll
    static void createKeys() throws Exception {
        Assumptions.assumeTrue(GpgHome.isAvailable(), "gpg is not available. Skipping the whole class.");
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        gpg = GpgHome.create(shared.resolve("gnupg"));
        rsaFpr = gpg.generateKey("Dev Rsa <dev.rsa@example.com>", "rsa2048", "never", "");
        edFpr = gpg.generateKey("Dev Ed <dev.ed@example.com>", "ed25519", "never", "");
        keyring = GpgKeyring.fromGnupgHome(gpg.path()).withSecretKeys("export", gpg.exportSecret(""));
    }

    @AfterAll
    static void stopAgent() throws Exception {
        if (gpg != null) {
            gpg.close();
        }
    }

    @BeforeEach
    void newRepo() throws Exception {
        repoDir = Files.createDirectories(tmp.resolve("repo"));
        LocalHg.hgIn(tmp, "repo", "init", ".");
        Files.writeString(repoDir.resolve("a.txt"), "a0\n");
    }

    private static String fprFor(String which) {
        return which.equals("rsa") ? rsaFpr : edFpr;
    }

    private byte[] commit(String message, CommitCommand.GpgSigner custom, GpgKeyring withKeyring, String spec, char[] pass)
            throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        new AddCommand(repo).call();
        CommitCommand c = new CommitCommand(repo).setAuthor("Dev <dev@example.com>").setMessage(message);
        if (custom != null) {
            c.setGpgSigner("CUSTOM", custom);
        } else if (withKeyring != null) {
            c.setGpgSigner(withKeyring, spec, pass);
        }
        return c.call();
    }

    private VerifyCommitCommand.Result verify(Path repo, byte[] node, GpgKeyring k) throws Exception {
        return new VerifyCommitCommand(new HgRepository(repo.toFile())).setKeyring(k).setNodeId(node).call();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rsa", "ed"})
    @DisplayName("커밋 시점에 내 gpg 키로 서명하면 검증이 GOOD 이고 주 키 지문이 기록된다")
    void signedAtCommit_verifiesGood(String which) throws Exception {
        byte[] node = commit("signed " + which, null, keyring, fprFor(which), new char[0]);
        VerifyCommitCommand.Result r = verify(repoDir, node, GpgKeyring.fromGnupgHome(gpg.path()));
        assertTrue(r.signed());
        assertEquals(HgSigResult.Status.GOOD, r.signature().getStatus(), String.valueOf(r.signature().getDetail()));
        assertEquals(fprFor(which), r.signature().getFingerprint());
        assertEquals(fprFor(which), r.claimedFingerprint(), "gpgfingerprint extra records the primary fingerprint");
        assertTrue(r.isValid());
    }

    @Test
    @DisplayName("사용자 id 일부로 키를 고를 수 있다")
    void keyCanBeChosenByUserId() throws Exception {
        byte[] node = commit("by uid", null, keyring, "Dev Ed", new char[0]);
        assertEquals(edFpr, verify(repoDir, node, keyring).signature().getFingerprint());
    }

    @Test
    @DisplayName("저장된 서명과 서명 대상 바이트를 실제 gpg --verify 가 GOODSIG 로 인정한다")
    void realGpgAcceptsTheStoredSignature() throws Exception {
        byte[] node = commit("for real gpg", null, keyring, edFpr, new char[0]);
        HgCommit c = new LogCommand(new HgRepository(repoDir.toFile())).call().get(0);
        assertEquals(NodeIdUtil.toHex(node), c.getNodeId().toHex());
        String armored = c.getGpgSignature();
        assertTrue(armored.startsWith("-----BEGIN PGP SIGNATURE-----"), armored);
        assertTrue(armored.endsWith("-----END PGP SIGNATURE-----"), armored);
        assertFalse(armored.contains("\r"), "LF line endings only");

        Path sig = tmp.resolve("c.sig");
        Path data = tmp.resolve("c.txt");
        Files.writeString(sig, armored + "\n", StandardCharsets.US_ASCII);
        Files.write(data, c.getUnsignedChangelogText());
        GpgHome.Result r = gpg.run(null, "--batch", "--status-fd", "1", "--verify", sig.toString(), data.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.outText().contains("[GNUPG:] GOODSIG"), r.outText());
        assertTrue(r.outText().contains("[GNUPG:] VALIDSIG") && r.outText().contains(edFpr), r.outText());
    }

    @Test
    @DisplayName("서명된 저장소를 실제 hg 가 정상으로 읽고(hg verify), 노드가 hg4j 가 알려 준 것과 같다")
    void realHgReadsTheSignedRepositoryNormally() throws Exception {
        byte[] node = commit("signed", null, keyring, rsaFpr, new char[0]);
        assertEquals(NodeIdUtil.toHex(node), LocalHg.hgIn(tmp, "repo", "log", "-r", "0", "--template", "{node}"));
        String verifyOut = LocalHg.hgIn(tmp, "repo", "verify");
        assertTrue(verifyOut.contains("checked 1 changesets"), verifyOut);
        assertTrue(LocalHg.hgIn(tmp, "repo", "log", "-r", "0", "--debug").contains("gpgsig"),
                "the signature is in the changeset's extra");
    }

    @Test
    @DisplayName("실제 hg clone 으로 다른 저장소에 옮겨도 서명이 그대로 있고 검증된다(원격에 도착한 뒤에도 서명됨)")
    void signatureSurvivesCloneToAnotherRepository() throws Exception {
        byte[] node = commit("signed", null, keyring, edFpr, new char[0]);
        LocalHg.hgIn(tmp, ".", "clone", repoDir.toString(), tmp.resolve("remote").toString());

        Path remote = tmp.resolve("remote");
        assertEquals(NodeIdUtil.toHex(node), LocalHg.hgIn(tmp, "remote", "log", "-r", "0", "--template", "{node}"),
                "same node hash: the extra, signature included, is part of the changeset");
        VerifyCommitCommand.Result r = verify(remote, node, GpgKeyring.fromGnupgHome(gpg.path()));
        assertTrue(r.signed());
        assertEquals(HgSigResult.Status.GOOD, r.signature().getStatus());
    }

    @Test
    @DisplayName("서명하지 않은 커밋은 signed=false")
    void unsignedCommit_isReportedAsUnsigned() throws Exception {
        byte[] node = commit("plain", null, null, null, null);
        VerifyCommitCommand.Result r = verify(repoDir, node, keyring);
        assertFalse(r.signed());
        assertNull(r.signature());
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("키링에 없는 키의 서명은 UNKNOWN_KEY 이고 키 id 를 알려 준다")
    void unknownSigner_isUnknownKey() throws Exception {
        byte[] node = commit("signed", null, keyring, edFpr, new char[0]);
        VerifyCommitCommand.Result r = verify(repoDir, node, GpgKeyring.empty());
        assertEquals(HgSigResult.Status.UNKNOWN_KEY, r.signature().getStatus());
        assertEquals(edFpr.substring(edFpr.length() - 16), r.signature().getKeyIdHex());
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("다른 내용에 대한 서명을 붙이면 BAD (서명 대상이 바뀐 경우)")
    void signatureOverOtherBytes_isBad() throws Exception {
        PGPSecretKey key = keyring.findSigningKey(rsaFpr);
        byte[] node = commit("forged", payload -> HgSigner.signDetachedArmored(
                "something else entirely".getBytes(StandardCharsets.UTF_8), key, new char[0], Instant.now()), null, null, null);
        VerifyCommitCommand.Result r = verify(repoDir, node, keyring);
        assertTrue(r.signed());
        assertEquals(HgSigResult.Status.BAD, r.signature().getStatus());
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("passphrase 가 걸린 키: 맞으면 서명, 틀리면 커밋이 만들어지지 않는다")
    void protectedKey_wrongPassphraseAbortsTheCommit() throws Exception {
        try (GpgHome h = GpgHome.create(tmp.resolve("gnupg-protected"))) {
            String fpr = h.generateKey("Prot <prot@example.com>", "rsa2048", "never", "s3cret");
            GpgKeyring k = GpgKeyring.fromGnupgHome(h.path()).withSecretKeys("export", h.exportSecret("s3cret"));

            assertThrows(Exception.class, () -> commit("must not exist", null, k, fpr, "wrong".toCharArray()));
            assertEquals("", LocalHg.hgIn(tmp, "repo", "log", "--template", "{rev}"),
                    "no changeset was created by the aborted commit");

            byte[] node = commit("now it works", null, k, fpr, "s3cret".toCharArray());
            assertEquals(HgSigResult.Status.GOOD, verify(repoDir, node, k).signature().getStatus());
        }
    }

    @Test
    @DisplayName("없는 키 지정은 커밋을 시작하기 전에 IllegalArgumentException")
    void unknownKeySpec_failsBeforeCommitting() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CommitCommand(null).setGpgSigner(keyring, "nobody-has-this", new char[0]));
        assertTrue(ex.getMessage().contains("no usable signing key"), ex.getMessage());
    }

    @Test
    @DisplayName("저장소에 없는 노드는 HgRevisionNotFoundException")
    void unknownNode_isRejected() throws Exception {
        commit("one", null, null, null, null);
        assertThrows(io.github.search5.hg4j.errors.HgRevisionNotFoundException.class,
                () -> verify(repoDir, NodeIdUtil.fromHex("ab".repeat(20)), keyring));
    }
}
