package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigFormatException;
import io.github.search5.hg4j.gpg.HgSigResult;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.GpgHome;
import io.github.search5.hg4j.testsupport.LocalHg;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SignCommand}, {@link SigsCommand} and {@link SigCheckCommand} against the REAL {@code hg} (with its
 * {@code gpg} extension) and the REAL {@code gpg}: what hg4j writes, real {@code hg sigcheck} must accept,
 * and what real {@code hg sign} writes, hg4j must read and verify.
 */
@Tag("interop")
public class SignCommandsInteropTest {

    @TempDir
    static Path shared;

    private static GpgHome gpg;
    private static String fpr;
    private static GpgKeyring keyring;

    @TempDir
    Path tmp;
    private Path repoDir;

    @BeforeAll
    static void createKey() throws Exception {
        Assumptions.assumeTrue(GpgHome.isAvailable(), "gpg is not available. Skipping the whole class.");
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        gpg = GpgHome.create(shared.resolve("gnupg"));
        fpr = gpg.generateKey("hg4j signer <signer@example.com>", "ed25519", "never", "");
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
        LocalHg.hgIn(tmp, "repo", "add", "a.txt");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "c0");
    }

    private String tipNode() throws Exception {
        return LocalHg.hgIn(tmp, "repo", "log", "-r", "0", "--template", "{node}");
    }

    private String realHg(String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("--config", "extensions.gpg=", "--config", "gpg.cmd=gpg"));
        all.addAll(List.of(args));
        ProcessBuilder pb = LocalHg.processBuilder(repoDir, all.toArray(new String[0]));
        pb.environment().put("GNUPGHOME", gpg.path().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out.trim();
    }

    private SignCommand sign() throws Exception {
        return new SignCommand(new HgRepository(repoDir.toFile())).setKeyring(keyring);
    }

    @Test
    @DisplayName("sign 은 .hgsigs 를 쓰고 커밋하며 실제 hg sigcheck/verify 가 인정한다")
    void sign_commitsHgsigs_andRealHgAccepts() throws Exception {
        String node = tipNode();
        sign().setNodeId(NodeIdUtil.fromHex(node)).call();

        assertEquals("Added signature for changeset " + node.substring(0, 12),
                LocalHg.hgIn(tmp, "repo", "log", "-r", "1", "--template", "{desc}"));
        assertEquals(".hgsigs", LocalHg.hgIn(tmp, "repo", "log", "-r", "1", "--template", "{files}"),
                "the signing commit contains .hgsigs only");
        assertEquals("", LocalHg.hgIn(tmp, "repo", "status"), "working copy is clean afterwards");

        String out = realHg("sigcheck", node);
        assertTrue(out.contains("is signed by:"), out);
        assertTrue(out.contains("hg4j signer <signer@example.com>"), out);
        assertFalse(realHg("verify").contains("error"), "hg verify finds no problem");
    }

    @Test
    @DisplayName("대상을 주지 않으면 작업 디렉터리의 부모를 서명한다")
    void sign_defaultsToWorkingDirectoryParent() throws Exception {
        String node = tipNode();
        sign().call();
        assertTrue(realHg("sigcheck", node).contains("is signed by:"));
    }

    @Test
    @DisplayName("setCommit(false)는 .hgsigs 를 추적만 하고 커밋하지 않는다")
    void sign_noCommit_leavesAddedFile() throws Exception {
        sign().setCommit(false).call();
        assertEquals("A .hgsigs", LocalHg.hgIn(tmp, "repo", "status"));
        assertEquals("c0", LocalHg.hgIn(tmp, "repo", "log", "-r", "tip", "--template", "{desc}"));
    }

    @Test
    @DisplayName("setLocal(true)는 .hg/localsigs 에만 쓰고 커밋하지 않으며 실제 hg 가 읽는다")
    void sign_local_writesLocalsigsOnly() throws Exception {
        String node = tipNode();
        sign().setLocal(true).call();
        assertTrue(Files.isRegularFile(repoDir.resolve(".hg/localsigs")));
        assertFalse(Files.exists(repoDir.resolve(".hgsigs")));
        assertEquals("", LocalHg.hgIn(tmp, "repo", "status"));
        assertTrue(realHg("sigcheck", node).contains("is signed by:"));
    }

    @Test
    @DisplayName("다른 파일에 커밋되지 않은 변경이 있어도 원본처럼 .hgsigs 만 커밋하고 나머지는 그대로 둔다")
    void sign_commitsOnlyHgsigs_whenOtherFilesAreDirty() throws Exception {
        Files.writeString(repoDir.resolve("a.txt"), "changed\n");
        sign().call();
        assertEquals("M a.txt", LocalHg.hgIn(tmp, "repo", "status"), "other changes stay uncommitted");
        assertEquals(".hgsigs", LocalHg.hgIn(tmp, "repo", "log", "-r", "tip", "--template", "{files}"));
    }

    @Test
    @DisplayName(".hgsigs 가 수정 중이면 거부하고, setForce(true)면 서명한다(원본 --force 는 크래시)")
    void sign_hgsigsModified_needsForce() throws Exception {
        sign().call(); // creates and commits .hgsigs
        Files.writeString(repoDir.resolve(".hgsigs"), Files.readString(repoDir.resolve(".hgsigs")) + "\n");
        HgValidationException ex = assertThrows(HgValidationException.class, () -> sign().setCommit(false).call());
        assertTrue(ex.getMessage().contains(".hgsigs is changed"), ex.getMessage());

        assertDoesNotThrow(() -> sign().setCommit(false).setForce(true).call());
        long lines = Files.readAllLines(repoDir.resolve(".hgsigs")).stream().filter(l -> !l.isEmpty()).count();
        assertEquals(2, lines, "the forced signature was appended");
    }

    @Test
    @DisplayName("없는 키 지정은 거부하고 아무것도 쓰지 않는다")
    void sign_unknownKeySpec_isRejected() throws Exception {
        HgValidationException ex = assertThrows(HgValidationException.class, () -> sign().setKeySpec("nobody").call());
        assertTrue(ex.getMessage().contains("no usable signing key"), ex.getMessage());
        assertFalse(Files.exists(repoDir.resolve(".hgsigs")));
    }

    @Test
    @DisplayName("공개키만 있는 키링으로는 서명할 수 없다")
    void sign_withoutSecretKey_isRejected() throws Exception {
        GpgKeyring publicOnly = GpgKeyring.fromGnupgHome(gpg.path());
        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new SignCommand(new HgRepository(repoDir.toFile())).setKeyring(publicOnly).call());
        assertTrue(ex.getMessage().contains("no usable signing key"), ex.getMessage());
    }

    @Test
    @DisplayName("저장소에 없는 노드는 HgRevisionNotFoundException")
    void sign_unknownNode_isRejected() throws Exception {
        assertThrows(io.github.search5.hg4j.errors.HgRevisionNotFoundException.class,
                () -> sign().setNodeId(NodeIdUtil.fromHex("ab".repeat(20))).call());
        assertFalse(Files.exists(repoDir.resolve(".hgsigs")));
    }

    @Test
    @DisplayName("sigs/sigcheck 가 hg4j 서명을 GOOD 으로 보고하고 지문을 준다")
    void sigsAndSigcheck_reportHg4jSignature() throws Exception {
        String node = tipNode();
        sign().call();
        HgRepository repo = new HgRepository(repoDir.toFile());

        SigCheckCommand.Result check = new SigCheckCommand(repo).setKeyring(keyring)
                .setNodeId(NodeIdUtil.fromHex(node)).call();
        assertTrue(check.isSigned());
        assertEquals(1, check.validSignatures().size());
        assertEquals(fpr, check.validSignatures().get(0).getFingerprint());
        assertEquals(HgSigResult.Status.GOOD, check.validSignatures().get(0).getStatus());

        SigsCommand.Result sigs = new SigsCommand(repo).setKeyring(keyring).call();
        assertEquals(1, sigs.signatures().size());
        assertEquals(0, sigs.signatures().get(0).revision());
        assertEquals(node, sigs.signatures().get(0).nodeHex());
        assertTrue(sigs.signatures().get(0).source().startsWith(".hgsigs|"), sigs.signatures().get(0).source());
    }

    @Test
    @DisplayName("서명이 없는 변경셋은 sigcheck 결과가 비어 있다")
    void sigcheck_unsignedChangeset_isNotSigned() throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        SigCheckCommand.Result r = new SigCheckCommand(repo).setKeyring(keyring)
                .setNodeId(NodeIdUtil.fromHex(tipNode())).call();
        assertFalse(r.isSigned());
        assertEquals("no valid signature for " + tipNode().substring(0, 12), realHg("sigcheck", tipNode()));
    }

    @Test
    @DisplayName("실제 hg sign 이 커밋까지 만든 결과를 hg4j sigs/sigcheck 가 읽는다")
    void realHgSign_isReadByHg4j() throws Exception {
        String node = tipNode();
        String out = realHg("sign", "--key", fpr, node);
        assertFalse(out.contains("abort") || out.contains("error"), out);

        HgRepository repo = new HgRepository(repoDir.toFile());
        SigCheckCommand.Result r = new SigCheckCommand(repo).setKeyring(keyring)
                .setNodeId(NodeIdUtil.fromHex(node)).call();
        assertTrue(r.isSigned(), r.warnings().toString());
        assertEquals(fpr, r.validSignatures().get(0).getFingerprint());
    }

    @Test
    @DisplayName(".hgsigs 파일로그의 모든 head 를 읽는다(갈라진 두 서명 이력)")
    void allFilelogHeads_areRead() throws Exception {
        String node = tipNode();
        // Different signing times matter: ed25519/RSA signatures are deterministic, so two signatures made in the
        // same second would be byte-identical, and the identical .hgsigs content would be ONE file revision (real
        // hg stores it once too), not two heads.
        Instant t = Instant.now();
        sign().setSigningTime(t).call();                                    // rev 1 adds .hgsigs
        LocalHg.hgIn(tmp, "repo", "update", "-r", "0");                     // back before .hgsigs existed
        sign().setSigningTime(t.plusSeconds(60)).call();                    // rev 2 adds a DIFFERENT .hgsigs: 2nd head

        HgRepository repo = new HgRepository(repoDir.toFile());
        SigCheckCommand.Result r = new SigCheckCommand(repo).setKeyring(keyring)
                .setNodeId(NodeIdUtil.fromHex(node)).call();
        assertEquals(2, r.validSignatures().size(), "one signature from each .hgsigs head: " + r.warnings());
        assertEquals(2, new SigsCommand(repo).setKeyring(keyring).call().signatures().size());
        // real hg reads the same two heads
        assertEquals(2, realHg("sigcheck", node).lines().filter(l -> l.contains("hg4j signer")).count());
    }

    @Test
    @DisplayName("키링에 없는 서명자는 경고 'Unknown key ID' 로 알리고 유효 목록에서 뺀다")
    void unknownSigner_isWarnedAndExcluded() throws Exception {
        String node = tipNode();
        sign().call();
        HgRepository repo = new HgRepository(repoDir.toFile());
        SigCheckCommand.Result r = new SigCheckCommand(repo).setKeyring(GpgKeyring.empty())
                .setNodeId(NodeIdUtil.fromHex(node)).call();
        assertFalse(r.isSigned());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Unknown key ID")), r.warnings().toString());
    }

    @Test
    @DisplayName("만료된 키의 서명은 'Note: This key has expired' 경고가 붙지만 유효 목록에 남는다")
    void expiredKey_warnsButStaysListed() throws Exception {
        String expiringFpr = gpg.generateKey("hg4j expiring <exp@example.com>", "ed25519", "1d", "");
        GpgKeyring withExpiring = GpgKeyring.fromGnupgHome(gpg.path()).withSecretKeys("export", gpg.exportSecret(""));
        String node = tipNode();
        sign().setKeyring(withExpiring).setKeySpec(expiringFpr).call();

        HgRepository repo = new HgRepository(repoDir.toFile());
        SigCheckCommand.Result now = new SigCheckCommand(repo).setKeyring(withExpiring)
                .setNodeId(NodeIdUtil.fromHex(node)).call();
        assertTrue(now.isSigned());
        assertTrue(now.warnings().isEmpty(), now.warnings().toString());

        SigCheckCommand.Result later = new SigCheckCommand(repo).setKeyring(withExpiring)
                .setNodeId(NodeIdUtil.fromHex(node)).setNow(Instant.now().plus(2, ChronoUnit.DAYS)).call();
        assertTrue(later.isSigned(), "expired keys are still listed, like hgext/gpg.py");
        assertEquals(HgSigResult.Status.EXPIRED_KEY, later.validSignatures().get(0).getStatus());
        assertEquals(1, later.warnings().size(), later.warnings().toString());
        assertTrue(later.warnings().get(0).contains("Note: This key has expired (signed by: \"hg4j expiring"),
                later.warnings().get(0));
    }

    @Test
    @DisplayName("깨진 .hgsigs 줄은 건너뛰지 않고 예외")
    void corruptHgsigs_isAnError() throws Exception {
        Files.writeString(repoDir.resolve(".hgsigs"), "this is not a signature line\n");
        LocalHg.hgIn(tmp, "repo", "add", ".hgsigs");
        LocalHg.hgIn(tmp, "repo", "commit", "-u", "dev", "-m", "bad sigs");
        HgRepository repo = new HgRepository(repoDir.toFile());
        HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                () -> new SigsCommand(repo).setKeyring(keyring).call());
        assertTrue(ex.getMessage().contains(".hgsigs|"), ex.getMessage());
    }
}
