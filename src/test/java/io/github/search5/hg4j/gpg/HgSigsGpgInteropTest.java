package io.github.search5.hg4j.gpg;

import io.github.search5.hg4j.testsupport.GpgHome;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interoperability of {@link HgSigner}, {@link HgSigVerifier} and {@link GpgKeyring} with the REAL
 * {@code gpg} (and, for the last tests, the real {@code hg} gpg extension). Nothing is mocked: signatures
 * made by gpg are verified by hg4j, signatures made by hg4j are verified by gpg and by {@code hg sigcheck}.
 */
@Tag("interop")
public class HgSigsGpgInteropTest {

    private static final String NODE = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_NODE = "fedcba9876543210fedcba9876543210fedcba98";

    @TempDir
    static Path shared;

    private static GpgHome home;
    private static String rsaFpr;
    private static String edFpr;

    @BeforeAll
    static void createKeys() throws Exception {
        Assumptions.assumeTrue(GpgHome.isAvailable(), "gpg is not available. Skipping the whole class.");
        home = GpgHome.create(shared.resolve("gnupg"));
        rsaFpr = home.generateKey("hg4j rsa <rsa@example.com>", "rsa2048", "never", "");
        edFpr = home.generateKey("hg4j ed <ed@example.com>", "ed25519", "never", "");
    }

    @AfterAll
    static void stopAgent() throws Exception {
        if (home != null) {
            home.close();
        }
    }

    private static String fprFor(String which) {
        return which.equals("rsa") ? rsaFpr : edFpr;
    }

    private static String uidFor(String which) {
        return which.equals("rsa") ? "hg4j rsa <rsa@example.com>" : "hg4j ed <ed@example.com>";
    }

    private static GpgKeyring keyringWithSecrets(GpgHome h, String passphrase) throws Exception {
        return GpgKeyring.fromGnupgHome(h.path()).withSecretKeys("export", h.exportSecret(passphrase));
    }

    private static HgSigEntry gpgSign(GpgHome h, String fpr, String node, String... extra) throws Exception {
        List<String> args = new java.util.ArrayList<>(List.of("--batch", "--pinentry-mode", "loopback",
                "--passphrase", "", "--local-user", fpr));
        args.addAll(List.of(extra));
        args.add("--detach-sign"); // binary output, exactly what hgext/gpg.py asks for
        GpgHome.Result r = h.run((node + "\n").getBytes(StandardCharsets.US_ASCII), args.toArray(new String[0]));
        assertEquals(0, r.exit(), r.err());
        return new HgSigEntry(node, "0", r.out());
    }

    // ───────────────────────── keybox ─────────────────────────

    @Test
    @DisplayName("gpg 2.1+ 의 pubring.kbx 를 직접 파싱한 키가 gpg 가 보고하는 지문과 일치한다")
    void keybox_parsedKeysMatchGpgFingerprints() throws Exception {
        assertTrue(Files.isRegularFile(home.path().resolve("pubring.kbx")), "this gpg writes a keybox");
        GpgKeyring keyring = GpgKeyring.fromGnupgHome(home.path());
        assertEquals(2, keyring.publicKeyRingCount());
        for (String fpr : List.of(rsaFpr, edFpr)) {
            long keyId = Long.parseUnsignedLong(fpr.substring(fpr.length() - 16), 16);
            assertNotNull(keyring.findPublicKey(keyId), "key " + fpr + " must be found in the keybox");
            assertEquals(fpr, GpgKeyring.toHex(keyring.findPublicKeyRing(keyId).getPublicKey().getFingerprint()));
        }
    }

    @Test
    @DisplayName("gpg --export 결과(바이너리)로도 같은 키링을 만든다")
    void exportedPublicKeys_giveTheSameKeyring() throws Exception {
        GpgKeyring keyring = GpgKeyring.fromPublicKeys("gpg --export", home.exportPublic());
        assertEquals(2, keyring.publicKeyRingCount());
    }

    @Test
    @DisplayName("ASCII armor 로 내보낸 공개키도 읽는다")
    void armoredPublicKeys_areRead() throws Exception {
        GpgHome.Result r = home.run(null, "--batch", "--armor", "--export");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.outText().startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
        assertEquals(2, GpgKeyring.fromPublicKeys("armored", r.out()).publicKeyRingCount());
    }

    @Test
    @DisplayName("존재하지 않는 홈과 키링이 없는 홈은 예외")
    void missingHomeOrKeyring_throws(@TempDir Path tmp) {
        assertThrows(IOException.class, () -> GpgKeyring.fromGnupgHome(tmp.resolve("nope")));
        IOException ex = assertThrows(IOException.class, () -> GpgKeyring.fromGnupgHome(tmp));
        assertTrue(ex.getMessage().contains("No public keyring"), ex.getMessage());
    }

    @Test
    @DisplayName("레거시 pubring.gpg/secring.gpg 가 있는 홈에서 공개키와 서명 키를 읽고 서명한다")
    void legacyKeyringFiles_areReadIncludingSecretKeys(@TempDir Path tmp) throws Exception {
        Path legacyHome = Files.createDirectories(tmp.resolve("legacy-home"));
        Files.write(legacyHome.resolve("pubring.gpg"), home.exportPublic());
        Files.write(legacyHome.resolve("secring.gpg"), home.exportSecret(""));

        GpgKeyring keyring = GpgKeyring.fromGnupgHome(legacyHome);
        assertEquals(2, keyring.publicKeyRingCount());
        assertEquals(2, keyring.secretKeyRingCount());
        assertTrue(keyring.getNotes().isEmpty(), keyring.getNotes().toString());
        PGPSecretKey key = keyring.findSigningKey(edFpr);
        assertNotNull(key);
        HgSigEntry entry = HgSigner.sign(NODE, key, new char[0], Instant.now());
        assertEquals(HgSigResult.Status.GOOD, HgSigVerifier.verify(entry, keyring, Instant.now()).getStatus());
    }

    @Test
    @DisplayName("pubring.kbx 와 pubring.gpg 에 같은 키가 있어도 중복 없이 한 번만 센다")
    void sameKeyInKeyboxAndLegacyRing_isCountedOnce(@TempDir Path tmp) throws Exception {
        Path both = Files.createDirectories(tmp.resolve("both"));
        Files.copy(home.path().resolve("pubring.kbx"), both.resolve("pubring.kbx"));
        Files.write(both.resolve("pubring.gpg"), home.exportPublic());
        assertEquals(2, GpgKeyring.fromGnupgHome(both).publicKeyRingCount());
    }

    @Test
    @DisplayName("withSecretKeys 로 비밀키를 넘기면 gpg-agent 안내는 사라진다")
    void withSecretKeys_dropsTheAgentNote() throws Exception {
        GpgKeyring base = GpgKeyring.fromGnupgHome(home.path());
        assertFalse(base.getNotes().isEmpty());
        assertTrue(base.withSecretKeys("export", home.exportSecret("")).getNotes().isEmpty());
    }

    @Test
    @DisplayName("gpg-agent 가 관리하는 개인키는 읽을 수 없다는 안내를 남기고 서명 키는 없다")
    void agentManagedSecretKeys_areReportedNotSilentlyMissing() throws Exception {
        GpgKeyring keyring = GpgKeyring.fromGnupgHome(home.path());
        assertTrue(keyring.getNotes().stream().anyMatch(n -> n.contains("gpg-agent")), keyring.getNotes().toString());
        assertNull(keyring.findSigningKey(null));
    }

    // ───────────────────────── gpg signs, hg4j verifies ─────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rsa", "ed"})
    @DisplayName("gpg 가 만든 서명을 hg4j 가 GOOD 으로 검증하고 주 키 지문과 사용자 id 를 돌려준다")
    void gpgSignature_isGoodInHg4j(String which) throws Exception {
        HgSigEntry entry = gpgSign(home, fprFor(which), NODE);
        HgSigResult r = HgSigVerifier.verify(entry, GpgKeyring.fromGnupgHome(home.path()), Instant.now());
        assertEquals(HgSigResult.Status.GOOD, r.getStatus(), String.valueOf(r.getDetail()));
        assertEquals(fprFor(which), r.getFingerprint());
        assertEquals(uidFor(which), r.getUserId());
        assertTrue(r.isValid());
    }

    @Test
    @DisplayName("서명 후 노드가 바뀌면 BAD")
    void changedNode_isBad() throws Exception {
        HgSigEntry entry = gpgSign(home, rsaFpr, NODE);
        HgSigEntry moved = new HgSigEntry(OTHER_NODE, "0", entry.getSignature());
        HgSigResult r = HgSigVerifier.verify(moved, GpgKeyring.fromGnupgHome(home.path()), Instant.now());
        assertEquals(HgSigResult.Status.BAD, r.getStatus());
        assertFalse(r.isValid());
        assertEquals(uidFor("rsa"), r.getUserId(), "BADSIG still names the claimed signer, like gpg");
    }

    @Test
    @DisplayName("키링에 없는 키의 서명은 UNKNOWN_KEY 이고 키 id 를 알려 준다")
    void unknownKey_isReported() throws Exception {
        HgSigEntry entry = gpgSign(home, rsaFpr, NODE);
        HgSigResult r = HgSigVerifier.verify(entry, GpgKeyring.empty(), Instant.now());
        assertEquals(HgSigResult.Status.UNKNOWN_KEY, r.getStatus());
        assertEquals(rsaFpr.substring(rsaFpr.length() - 16), r.getKeyIdHex());
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("서명 형식이 아닌 바이트는 예외 없이 BAD")
    void undecodableSignature_isBad() throws Exception {
        HgSigEntry junk = new HgSigEntry(NODE, "0", new byte[]{1, 2, 3, 4, 5});
        HgSigResult r = HgSigVerifier.verify(junk, GpgKeyring.fromGnupgHome(home.path()), Instant.now());
        assertEquals(HgSigResult.Status.BAD, r.getStatus());
        assertNotNull(r.getDetail());
    }

    @Test
    @DisplayName("알 수 없는 서명 버전은 예외")
    void unknownVersion_throws() throws Exception {
        HgSigEntry entry = gpgSign(home, rsaFpr, NODE);
        HgSigEntry v1 = new HgSigEntry(NODE, "1", entry.getSignature());
        assertThrows(HgSigFormatException.class,
                () -> HgSigVerifier.verify(v1, GpgKeyring.fromGnupgHome(home.path()), Instant.now()));
    }

    @Test
    @DisplayName("서명 유효기간이 지나면 EXPIRED_SIGNATURE 이지만 hg 처럼 유효 목록에는 포함된다")
    void expiredSignature_isStillListedAsValid() throws Exception {
        HgSigEntry entry = gpgSign(home, rsaFpr, NODE, "--default-sig-expire", "1d");
        GpgKeyring keyring = GpgKeyring.fromGnupgHome(home.path());
        assertEquals(HgSigResult.Status.GOOD,
                HgSigVerifier.verify(entry, keyring, Instant.now()).getStatus(), "within its lifetime");
        HgSigResult later = HgSigVerifier.verify(entry, keyring, Instant.now().plus(2, ChronoUnit.DAYS));
        assertEquals(HgSigResult.Status.EXPIRED_SIGNATURE, later.getStatus());
        assertTrue(later.isValid(), "hgext/gpg.py lists EXPSIG with a note");
    }

    // ───────────────────────── hg4j signs, gpg verifies ─────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rsa", "ed"})
    @DisplayName("hg4j 가 만든 서명을 실제 gpg --verify 가 GOODSIG/VALIDSIG 로 인정한다")
    void hg4jSignature_isGoodSigForRealGpg(String which, @TempDir Path tmp) throws Exception {
        GpgKeyring keyring = keyringWithSecrets(home, "");
        PGPSecretKey key = keyring.findSigningKey(fprFor(which));
        assertNotNull(key, "signing key " + which);
        HgSigEntry entry = HgSigner.sign(NODE, key, new char[0], Instant.now());

        Path sig = tmp.resolve("x.sig");
        Path data = tmp.resolve("x.txt");
        Files.write(sig, entry.getSignature());
        Files.write(data, (NODE + "\n").getBytes(StandardCharsets.US_ASCII));
        GpgHome.Result r = home.run(null, "--batch", "--status-fd", "1", "--verify", sig.toString(), data.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.outText().contains("[GNUPG:] GOODSIG"), r.outText());
        assertTrue(r.outText().contains("[GNUPG:] VALIDSIG"), r.outText());
        assertTrue(r.outText().contains(fprFor(which)), "VALIDSIG carries the primary key fingerprint: " + r.outText());

        // and hg4j agrees with itself
        assertEquals(HgSigResult.Status.GOOD, HgSigVerifier.verify(entry, keyring, Instant.now()).getStatus());
    }

    @Test
    @DisplayName("hg4j 서명의 데이터를 바꾸면 실제 gpg 도 BADSIG")
    void hg4jSignature_withTamperedData_isBadSigForRealGpg(@TempDir Path tmp) throws Exception {
        PGPSecretKey key = keyringWithSecrets(home, "").findSigningKey(rsaFpr);
        HgSigEntry entry = HgSigner.sign(NODE, key, new char[0], Instant.now());
        Path sig = tmp.resolve("x.sig");
        Path data = tmp.resolve("x.txt");
        Files.write(sig, entry.getSignature());
        Files.write(data, (OTHER_NODE + "\n").getBytes(StandardCharsets.US_ASCII));
        GpgHome.Result r = home.run(null, "--batch", "--status-fd", "1", "--verify", sig.toString(), data.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.outText().contains("[GNUPG:] BADSIG"), r.outText());
    }

    @Test
    @DisplayName("passphrase 가 걸린 키: 맞으면 서명, 틀리면 예외")
    void protectedKey_needsTheRightPassphrase(@TempDir Path tmp) throws Exception {
        try (GpgHome h = GpgHome.create(tmp.resolve("gnupg-protected"))) {
            String fpr = h.generateKey("hg4j protected <p@example.com>", "rsa2048", "never", "s3cret");
            GpgKeyring keyring = keyringWithSecrets(h, "s3cret");
            PGPSecretKey key = keyring.findSigningKey(fpr);
            assertNotNull(key);

            HgSigEntry entry = HgSigner.sign(NODE, key, "s3cret".toCharArray(), Instant.now());
            assertEquals(HgSigResult.Status.GOOD, HgSigVerifier.verify(entry, keyring, Instant.now()).getStatus());

            HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                    () -> HgSigner.sign(NODE, key, "wrong".toCharArray(), Instant.now()));
            assertTrue(ex.getMessage().contains("cannot sign"), ex.getMessage());
        }
    }

    // ───────────────────────── key lifetime ─────────────────────────

    @Test
    @DisplayName("만료된 키로 검증하면 EXPIRED_KEY 이지만 유효 목록에는 포함된다")
    void expiredKey_isStillListedAsValid(@TempDir Path tmp) throws Exception {
        try (GpgHome h = GpgHome.create(tmp.resolve("gnupg-expiring"))) {
            String fpr = h.generateKey("hg4j expiring <x@example.com>", "rsa2048", "1d", "");
            GpgKeyring keyring = keyringWithSecrets(h, "");
            HgSigEntry entry = HgSigner.sign(NODE, keyring.findSigningKey(fpr), new char[0], Instant.now());

            assertEquals(HgSigResult.Status.GOOD, HgSigVerifier.verify(entry, keyring, Instant.now()).getStatus());
            HgSigResult later = HgSigVerifier.verify(entry, keyring, Instant.now().plus(2, ChronoUnit.DAYS));
            assertEquals(HgSigResult.Status.EXPIRED_KEY, later.getStatus());
            assertTrue(later.isValid(), "hgext/gpg.py lists EXPKEYSIG with a note");
        }
    }

    @Test
    @DisplayName("취소(revoke)된 키의 서명은 REVOKED_KEY 이고 유효로 치지 않는다")
    void revokedKey_isNotValid(@TempDir Path tmp) throws Exception {
        try (GpgHome h = GpgHome.create(tmp.resolve("gnupg-revoked"))) {
            String fpr = h.generateKey("hg4j revoked <r@example.com>", "rsa2048", "never", "");
            HgSigEntry entry = HgSigner.sign(NODE, keyringWithSecrets(h, "").findSigningKey(fpr), new char[0], Instant.now());

            // gpg writes a ready revocation certificate with a ':' in front of the BEGIN line; remove it and import
            try (var files = Files.list(h.path().resolve("openpgp-revocs.d"))) {
                Path rev = files.filter(p -> p.toString().endsWith(".rev")).findFirst().orElseThrow();
                String text = Files.readString(rev).replace(":-----BEGIN PGP PUBLIC KEY BLOCK-----",
                        "-----BEGIN PGP PUBLIC KEY BLOCK-----");
                Files.writeString(rev, text);
                GpgHome.Result imp = h.run(null, "--batch", "--import", rev.toString());
                assertEquals(0, imp.exit(), imp.err());
            }
            GpgKeyring afterRevocation = GpgKeyring.fromGnupgHome(h.path());
            HgSigResult r = HgSigVerifier.verify(entry, afterRevocation, Instant.now());
            assertEquals(HgSigResult.Status.REVOKED_KEY, r.getStatus());
            assertFalse(r.isValid());
        }
    }

    // ───────────────────────── key selection ─────────────────────────

    @Test
    @DisplayName("서명 키는 지문 끝자리, 키 id, 사용자 id 일부로 고를 수 있고 없으면 null")
    void findSigningKey_selectors() throws Exception {
        GpgKeyring keyring = keyringWithSecrets(home, "");
        assertNotNull(keyring.findSigningKey(null), "no spec: first signing key");
        assertEquals(rsaFpr, GpgKeyring.toHex(keyring.findSigningKey(rsaFpr).getPublicKey().getFingerprint()));
        assertNotNull(keyring.findSigningKey(edFpr.substring(edFpr.length() - 16)), "by key id");
        assertNotNull(keyring.findSigningKey("0x" + edFpr.substring(edFpr.length() - 8)), "0x prefix, short id");
        assertNotNull(keyring.findSigningKey("hg4j ed"), "by part of the user id");
        assertNull(keyring.findSigningKey("nobody-has-this-id"));
        assertNull(keyring.findSigningKey("DEADBEEFDEADBEEF"));
    }

    // ───────────────────────── real hg ─────────────────────────

    private static Map<String, String> hgGpgEnv(GpgHome h) {
        return Map.of("GNUPGHOME", h.path().toString());
    }

    private static String runHgWithGpg(GpgHome h, Path repo, String... args) throws Exception {
        List<String> all = new java.util.ArrayList<>(List.of("--config", "extensions.gpg=", "--config", "gpg.cmd=gpg"));
        all.addAll(List.of(args));
        ProcessBuilder pb = LocalHg.processBuilder(repo, all.toArray(new String[0]));
        pb.environment().putAll(hgGpgEnv(h));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out.trim();
    }

    @Test
    @DisplayName("hg4j 가 만든 .hgsigs 를 실제 hg sigcheck 가 서명자와 함께 인정한다")
    void hg4jHgsigs_isAcceptedByRealHgSigcheck(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        LocalHg.hgIn(tmp, "repo", "init", ".");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        LocalHg.hgIn(tmp, "repo", "add", "a.txt");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "c0");
        String node = LocalHg.hgIn(tmp, "repo", "log", "-r", ".", "--template", "{node}");

        HgSigEntry entry = HgSigner.sign(node, keyringWithSecrets(home, "").findSigningKey(rsaFpr), new char[0], Instant.now());
        Files.write(repo.resolve(".hgsigs"), HgSigsFile.format(List.of(entry)));
        LocalHg.hgIn(tmp, "repo", "add", ".hgsigs");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "sign");

        String out = runHgWithGpg(home, repo, "sigcheck", node);
        assertTrue(out.contains("is signed by:"), out);
        assertTrue(out.contains("hg4j rsa <rsa@example.com>"), out);
    }

    @Test
    @DisplayName("서명을 망가뜨리면 실제 hg sigcheck 도 유효한 서명이 없다고 한다")
    void corruptedSignature_isRejectedByRealHgSigcheck(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        LocalHg.hgIn(tmp, "repo", "init", ".");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        LocalHg.hgIn(tmp, "repo", "add", "a.txt");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "c0");
        String node = LocalHg.hgIn(tmp, "repo", "log", "-r", ".", "--template", "{node}");

        HgSigEntry good = HgSigner.sign(node, keyringWithSecrets(home, "").findSigningKey(rsaFpr), new char[0], Instant.now());
        byte[] sig = good.getSignature();
        sig[sig.length - 3] ^= 0x55; // damage the signature value
        Files.write(repo.resolve(".hgsigs"), HgSigsFile.format(List.of(new HgSigEntry(node, "0", sig))));
        LocalHg.hgIn(tmp, "repo", "add", ".hgsigs");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "sign");

        assertTrue(runHgWithGpg(home, repo, "sigcheck", node).contains("no valid signature for"));
        GpgKeyring keyring = GpgKeyring.fromGnupgHome(home.path());
        assertEquals(HgSigResult.Status.BAD, HgSigVerifier.verify(new HgSigEntry(node, "0", sig), keyring, Instant.now()).getStatus());
    }

    @Test
    @DisplayName("실제 hg sign 이 만든 .hgsigs 줄을 hg4j 가 읽고 GOOD 으로 검증한다")
    void realHgSign_isReadAndVerifiedByHg4j(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        LocalHg.hgIn(tmp, "repo", "init", ".");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        LocalHg.hgIn(tmp, "repo", "add", "a.txt");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "c0");
        String node = LocalHg.hgIn(tmp, "repo", "log", "-r", ".", "--template", "{node}");

        String out = runHgWithGpg(home, repo, "sign", "--no-commit", "--key", edFpr, node);
        assertFalse(out.contains("abort"), out);
        List<HgSigsFile.Line> lines = HgSigsFile.parse(Files.readString(repo.resolve(".hgsigs")), ".hgsigs");
        assertEquals(1, lines.size());
        assertEquals(node, lines.get(0).entry().getNodeHex());

        HgSigResult r = HgSigVerifier.verify(lines.get(0).entry(), GpgKeyring.fromGnupgHome(home.path()), Instant.now());
        assertEquals(HgSigResult.Status.GOOD, r.getStatus(), String.valueOf(r.getDetail()));
        assertEquals(edFpr, r.getFingerprint());
    }
}
