package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.sshd.server.Environment;
import java.time.Duration;
import java.util.Random;

/**
 * Verifies {@link HgSshWireServer} against the real {@code hg} CLI as an SSH client, using an
 * embedded Apache MINA SSHD {@link SshServer} (test-only — see the JGit-restructuring plan: real
 * production SSH serving is expected to be wired up outside hg4j the same way, by attaching
 * {@link HgSshWireServer} to whatever SSH server implementation the actual {@code hg
 * serve}-equivalent entry point uses; hg4j itself does not ship a production SSH server).
 *
 * <p>An earlier attempt at this used a hand-rolled shell-script {@code ui.ssh} override to bypass
 * a real SSH session entirely, which hit real hg misreading stray bytes as SSH banner text. A
 * genuine SSH channel (this test) avoids that: stdout/stderr are cleanly separated by the
 * protocol itself, exactly like a real deployment.</p>
 */
@Tag("interop")
public class HgSshWireServerRealHgInteropTest {

    private SshServer sshServer;
    private int port;
    private File sshKeyFile;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        sshKeyFile = tempDir.resolve("id_test").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.getAbsolutePath(), "-N", "");

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        // Test-only: accept any client key. A real deployment authenticates properly; that's the
        // separate production entry point's concern, not HgSshWireServer's.
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setCommandFactory((channel, command) -> new HgWireCommand(command));
        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError(String.join(" ", cmd) + " failed (" + code + "): " + new String(out));
        }
    }

    /** Real hg's ssh:// URL path convention: a single leading slash is relative to the remote
     * user's home directory, a double slash makes it absolute -- needed since these test
     * repositories live under an arbitrary temp directory, not anyone's home. */
    private String sshUrl(File repoDir) {
        return "ssh://127.0.0.1:" + port + "/" + repoDir.getAbsolutePath();
    }

    private String remoteCmdForTest(Path tempDir) {
        return "ssh -i " + sshKeyFile.getAbsolutePath()
                + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                + " -o IdentitiesOnly=yes -p " + port;
    }

    @Test
    public void realHgClonesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        File f = new File(serverRepoDir, "a.txt");
        Files.writeString(f.toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        byte[] commit = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n");
        assertEquals(NodeIdUtil.toHex(commit), log.trim());
        assertEquals("hello ssh interop", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    /**
     * SSH counterpart of {@code HgHttpWireServerEmptyRepoRealHgInteropTest
     * #realHgClonesAFreshZeroCommitRepositoryServedByHg4jOverHttp}: same zero-commit-repository
     * regression (see that class's Javadoc for the full root-cause writeup -- {@link
     * HgLocalClient#getBundle} used to short-circuit with a bare {@code new byte[0]} whenever
     * there was nothing to send, which a real hg client can't distinguish from "response not sent
     * yet"), but exercised over the SSH transport instead of HTTP. The HTTP test's own Javadoc
     * calls out that a raw-stream transport like SSH is exactly where the "0 bytes" short-circuit
     * would hang forever rather than merely abort with a clean error, so this scenario deserves
     * its own dedicated SSH regression coverage rather than relying solely on the HTTP test.
     */
    @Test
    public void realHgClonesEmptyRepoFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        // Deliberately zero commits -- this is the exact state a brand-new hg4j/yona-created
        // Mercurial project is in before anyone has pushed anything to it.
        Hg.init().setDirectory(serverRepoDir).call();

        File destDir = tempDir.resolve("client_repo").toFile();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                HgTestUtils.hg(tempDir.toFile(),
                        "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                        "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath()));

        assertTrue(destDir.isDirectory(), "clone must have created the destination directory");
        assertTrue(new File(destDir, ".hg").isDirectory(), "clone must have created a .hg repository");

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n");
        assertEquals("", log.trim(), "a fresh empty repository must clone to an empty working copy, not error out");

        // Real hg's own `hg heads` deliberately exits 1 (with no output) on a repository with
        // zero revisions -- not an error condition here, just real hg's documented convention
        // for "no heads to report" on an empty repo, so HgTestUtils.hg()'s exit-code-0 assertion
        // is deliberately bypassed for this one call, exactly as the HTTP equivalent test does.
        Process headsProcess = new ProcessBuilder("hg", "heads").directory(destDir).redirectErrorStream(true).start();
        String headsOutput = new String(headsProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        headsProcess.waitFor();
        assertEquals(1, headsProcess.exitValue(), "real hg's own documented behavior for `hg heads` on an empty repo");
        assertEquals("", headsOutput, "an empty repository has no heads to list");
    }

    @Test
    public void realHgPullsIncrementalChangesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "second file");
        new AddCommand(serverRepo).call();
        byte[] secondCommit = new CommitCommand(serverRepo).setMessage("v2").setAuthor("dev").call();

        HgTestUtils.hg(destDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir), "pull");
        HgTestUtils.hg(destDir, "update");

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n", "-r", "tip");
        assertEquals(NodeIdUtil.toHex(secondCommit), log.trim());
        assertTrue(new File(destDir, "b.txt").exists());
    }

    @Test
    public void realHgPushesToHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        Files.writeString(new File(destDir, "c.txt").toPath(), "pushed file");
        HgTestUtils.hg(destDir, "add", "c.txt");
        HgTestUtils.hg(destDir, "commit", "-m", "pushed commit");

        HgTestUtils.hg(destDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "push", sshUrl(serverRepoDir));

        serverRepo.clearRevlogCache();
        File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
        var cl = serverRepo.getRevlog(clIdx, clDat);
        assertEquals(2, cl.getRevisionCount(), "The pushed commit must be applied to the hg4j server repository");
    }

    @Test
    public void realHgSeesAnotherRealHgClientsPushImmediatelyOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File clientA = tempDir.resolve("client_a").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientA.getAbsolutePath());
        Files.writeString(new File(clientA, "c.txt").toPath(), "pushed by client A");
        HgTestUtils.hg(clientA, "add", "c.txt");
        HgTestUtils.hg(clientA, "commit", "-m", "pushed commit");
        HgTestUtils.hg(clientA, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "push", sshUrl(serverRepoDir));
        String pushedNode = HgTestUtils.hg(clientA, "log", "-T", "{node}\n", "-r", "tip").trim();

        // Same self-consistency check as the HTTP equivalent: a second, independent real hg
        // client clones fresh from the SAME still-running SSH server session factory afterward.
        File clientB = tempDir.resolve("client_b").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientB.getAbsolutePath());

        String log = HgTestUtils.hg(clientB, "log", "-T", "{node}\n", "-r", "tip");
        assertEquals(pushedNode, log.trim(),
                "A second real-hg client must see the first client's push immediately, without the server needing a restart");
        assertTrue(new File(clientB, "c.txt").exists());
    }

    @Test
    public void realHgClonesMultipleBranchesBookmarksAndTagsFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "on default");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("default v1").setAuthor("dev").call();

        HgTestUtils.hg(serverRepoDir, "branch", "feature");
        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "on feature");
        HgTestUtils.hg(serverRepoDir, "add", "b.txt");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "feature v1");
        HgTestUtils.hg(serverRepoDir, "bookmark", "mybook");
        HgTestUtils.hg(serverRepoDir, "tag", "v1.0");

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        String branches = HgTestUtils.hg(destDir, "branches");
        assertTrue(branches.contains("default"), "default branch missing: " + branches);
        assertTrue(branches.contains("feature"), "feature branch missing: " + branches);

        String bookmarks = HgTestUtils.hg(destDir, "bookmarks");
        assertTrue(bookmarks.contains("mybook"), "bookmark missing: " + bookmarks);

        String tags = HgTestUtils.hg(destDir, "tags");
        assertTrue(tags.contains("v1.0"), "tag missing: " + tags);
    }

    @Test
    public void realHgReceivesUnderstandableErrorForNonexistentRevisionOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        String bogusRev = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

        AssertionError failure = assertThrows(AssertionError.class, () ->
                assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                        HgTestUtils.hg(tempDir.toFile(),
                                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                                "clone", "-r", bogusRev, sshUrl(serverRepoDir), destDir.getAbsolutePath())));
        assertTrue(failure.getMessage().toLowerCase().contains("unknown revision")
                        || failure.getMessage().toLowerCase().contains("abort"),
                "Expected a real-hg-understood error message, got: " + failure.getMessage());
    }

    /**
     * Backlog item 40, server direction over SSH: same scenario as {@code
     * HgHttpWireServerNarrowInteropTest#realHgNarrowClonesFromHg4jServedOverHttp} but over the SSH
     * transport, proving {@link Wire1Commands#getbundle}'s narrow filtering (shared, transport-
     * agnostic code both {@link HgHttpWireServer} and {@link HgSshWireServer} delegate to) is
     * actually reached and applied when the request arrives framed as SSH v1 args rather than
     * HTTP query/header args.
     */
    @Test
    public void realHgNarrowClonesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isNarrowExtensionAvailable(), "Native hg's narrow extension is not available. Skipping.");

        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        File includedDir = new File(serverRepoDir, "included");
        File excludedDir = new File(serverRepoDir, "excluded");
        includedDir.mkdirs();
        excludedDir.mkdirs();
        for (int i = 0; i < 20; i++) {
            Files.writeString(new File(includedDir, "f" + i + ".txt").toPath(), "small line " + i);
        }
        Random rnd = new Random(7);
        for (int i = 0; i < 80; i++) {
            byte[] filler = new byte[20_000];
            rnd.nextBytes(filler);
            Files.write(new File(excludedDir, "big" + i + ".bin").toPath(), filler);
        }
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("seed").setAuthor("dev").call();

        File plainDestDir = tempDir.resolve("plain_client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), plainDestDir.getAbsolutePath());
        long plainStoreSize = directorySize(new File(plainDestDir, ".hg/store"));

        File narrowDestDir = tempDir.resolve("narrow_client_repo").toFile();
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "extensions.narrow=",
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", "--narrow", "--include=included",
                sshUrl(serverRepoDir), narrowDestDir.getAbsolutePath());
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertTrue(code == 0, "real hg --narrow clone over SSH against hg4j server failed: " + out);

        assertTrue(new File(narrowDestDir, "included/f0.txt").exists(), "in-scope file must be checked out");
        assertFalse(new File(narrowDestDir, "excluded").exists(), "out-of-scope dir must not be checked out");

        long narrowStoreSize = directorySize(new File(narrowDestDir, ".hg/store"));
        assertTrue(plainStoreSize > 1_000_000,
                "sanity: the plain clone's on-disk store should be dominated by the 80 x 20KB excluded files, was "
                        + plainStoreSize + " bytes");
        assertTrue(narrowStoreSize < plainStoreSize / 4,
                "hg4j's SSH server must actually send a reduced changegroup to a real hg narrow client "
                        + "(local store " + narrowStoreSize + " bytes) compared to a plain clone against the "
                        + "same repository (" + plainStoreSize + " bytes)");
    }

    /**
     * 백로그 48번: 저장소를 아예 만들지 않고 연결을 거부하는 {@link HgSshWireServer#rejectConnection}
     * 경로. 인가 실패 시 {@code hello}+{@code between} 핸드셰이크에 응답하지 않고 에러 한 줄만 쓰고
     * 끊으면, 실제 hg SSH 클라이언트는 {@code between}의 정상 응답을 영원히 기다리며 멈춘다(hang) --
     * 이 테스트는 거부가 20초 안에 끝나는지(hang하지 않는지)와, {@code rejectConnection}이 실제로
     * hello/between 핸드셰이크를 완료하는지를 검증한다.
     *
     * <p>RED 확인 중 발견한 사실(2026-09-09): 이 임베디드 MINA SSHD 기반 하네스는 실제 프로덕션의
     * "유닉스 도메인 소켓 릴레이 + forced-command 쉘 스크립트" 한 홉이 없어서, hello/between을
     * 생략한 원래 버그 코드도 이 하네스 안에서는 문자 그대로 hang하지는 않는다(MINA가 커맨드
     * 종료 시 채널을 곧바로 정리해 클라이언트 쪽 read가 곧 EOF를 보게 된다) -- 실제 hang은 그
     * 추가 릴레이 홉에서만 재현되는 것으로 보인다(hg4j/yona 이 두 파일의 수정 범위 밖). 그래도
     * {@code assertTimeoutPreemptively}는 향후 회귀(문자 그대로 hang하는 변경)를 여전히 잡아준다.
     *
     * <p>더 결정적인 RED/GREEN 신호는 {@code --debug}로 드러나는 실제 핸드셰이크 성공 여부다:
     * 클라이언트는 {@code between} 응답의 {@code "1\n\n"} 마커를 보기 전까지는 자신이 읽은 모든
     * 줄을 (일치 여부와 무관하게) {@code remote: <line>} 형태로 그대로 에코한다({@code
     * sshpeer.py}의 handshake-scanning 루프) -- {@code hello} 응답으로 보낸 우리 자신의 {@code
     * capabilities: ...} 문자열이 그 에코에 나타난다는 것은 곧 between 마커까지 정상적으로
     * 완주했다는 뜻이다. hello/between을 생략한 원래 버그는 이 줄을 절대 만들어내지 못하고
     * (직접 확인: 2026-09-09 RED 실행에서 이 문자열 없이 곧장 실패), 반면 이 fix는 매번
     * 만들어낸다 -- 이게 이 티켓이 실제로 보장해야 하는 계약이므로 이를 직접 검증한다.</p>
     *
     * <p>추가로 발견한 사실: hello/between 완주 이후 실제로 어떤 명령이 다음으로 오는지는 hg4j의
     * 통제 밖이다 -- 이 서버가 광고하는 capabilities(bundle2= 블롭 안에 {@code listkeys} 서브
     * 토큰이 없음, {@code Wire1Commands}는 이 티켓의 수정 범위 밖)로는 real hg가 discovery의
     * 첫 단계로 {@code listkeys namespace=bookmarks}를 (batch 없이) 직접 보낸다(실측,
     * 2026-09-09). {@code listkeys}는 lookup/unbundle과 달리 "0/1 접두" 같은 에러 관례가 없는
     * tab-분리 포맷 전용이라, 우리의 범용 {@link Wire1Response#oobError(String)} 텍스트는 그
     * 자리에서 real hg 쪽 {@code pushkey.decodekeys}의 {@code ValueError}로 이어진다 -- 이는
     * hang이 아니라 매번 즉시(수 초 내) 종료되는 크래시이므로 원래 버그(무한 대기)보다는 명백한
     * 개선이지만, 클라이언트가 우리 reason 문자열을 사람이 읽기 좋게 보여주지는 못한다. 그래서
     * 이 테스트는 (백로그 48 문서가 명시한 그대로) "hang 없이 빠르게 거부되는지"만 검증하고,
     * 정확한 실패 문구까지는 단정하지 않는다.</p>
     */
    @Test
    public void realHgFailsFastRatherThanHangingWhenDeniedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        String denyReason = "PRIVATE 저장소 접근이 거부되었습니다";
        // 다른 테스트에 영향 없게 이 테스트 메서드 안에서만 커맨드 팩토리를 교체한다.
        sshServer.setCommandFactory((channel, command) -> new DenyingHgWireCommand(denyReason));

        File destDir = tempDir.resolve("client_repo").toFile();
        AssertionError failure = assertThrows(AssertionError.class, () ->
                assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                        HgTestUtils.hg(tempDir.toFile(),
                                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                                "--debug",
                                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath())));
        assertTrue(failure.getMessage().contains("capabilities:"),
                "Expected rejectConnection to complete the hello+between handshake (visible via "
                        + "real hg's --debug echo of our hello response) before the client sees "
                        + "any rejection, got: " + failure.getMessage());
        assertFalse(failure.getMessage().toLowerCase().contains("timed out"),
                "Expected a fast rejection, not the assertTimeoutPreemptively timeout (i.e. a hang), "
                        + "got: " + failure.getMessage());
    }

    private static long directorySize(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File c : children) {
            total += c.isDirectory() ? directorySize(c) : c.length();
        }
        return total;
    }

    private static boolean isNarrowExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.narrow=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void realHgSeesExternalRepoChangesAcrossConnectionsOnALongLivedSshServer(@TempDir Path tempDir) throws Exception {
        // Every other test in this class opens a brand-new HgRepository per SSH connection
        // (see HgWireCommand.run() below), which incidentally always reads the repository fresh
        // and so can never exercise backlog 24 (a long-lived server reusing ONE HgRepository
        // across many connections/requests must notice out-of-band repository changes). This
        // test deliberately mirrors HgHttpWireServerRealHgInteropTest's persistent-server setup
        // instead: one HgRepository, one HgSshWireServer-backed command factory, reused across
        // two separate real-hg SSH sessions.
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        sshServer.setCommandFactory((channel, command) -> new SharedRepoHgWireCommand(command, serverRepo));

        File clientA = tempDir.resolve("client_a").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientA.getAbsolutePath());
        assertEquals("hello ssh interop", Files.readString(new File(clientA, "a.txt").toPath()));

        // Out-of-band mutation: a bare real hg CLI call against the server's repo directory,
        // not going through hg4j / serverRepo at all -- exactly the "another process touched the
        // repo while the server kept running" scenario backlog 24 is about.
        HgTestUtils.hg(serverRepoDir, "branch", "feature");
        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "on feature");
        HgTestUtils.hg(serverRepoDir, "add", "b.txt");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "feature v1");
        // No explicit serverRepo.clearRevlogCache() here on purpose -- HgSshWireServer's
        // handleConnection() loop now calls HgRepository.refreshIfChangedOnDisk() at the top of
        // every command, so the second, independent SSH session below must see this without it.

        File clientB = tempDir.resolve("client_b").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientB.getAbsolutePath());

        // NOTE: only the changelog/branch metadata is asserted here, not b.txt's *content* --
        // that's a separate, pre-existing bug (file content added on an externally-committed
        // revision doesn't reach the client, reproduces even with an explicit
        // clearRevlogCache() call, i.e. unrelated to backlog 24) tracked separately so this test
        // stays scoped to what backlog 24 is actually about: does the server's long-lived
        // HgRepository handle notice the external write at all.
        String log = HgTestUtils.hg(clientB, "log", "-T", "{rev}:{branch}\n");
        assertTrue(log.contains("1:feature"),
                "second SSH connection on the same long-lived server must see the externally-added commit, log was:\n" + log);
        String branches = HgTestUtils.hg(clientB, "branches");
        assertTrue(branches.contains("feature"), "feature branch missing: " + branches);
    }

    /** Like {@link HgWireCommand}, but wired to a single {@link HgRepository} instance shared
     * across every connection the factory creates -- for tests that need to reproduce a
     * long-lived server process, as opposed to every other test in this class re-opening the
     * repository fresh per connection. */
    private static class SharedRepoHgWireCommand implements Command, Runnable {
        private final String command;
        private final HgRepository repo;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        SharedRepoHgWireCommand(String command, HgRepository repo) {
            this.command = command;
            this.repo = repo;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-shared-repo-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }

    /** Reproduces the authorization-denied path: no {@link HgRepository} is ever created (exactly
     * like {@code SshRelayServer.kt}'s rejection branch, which checks authorization before it ever
     * touches the repository) -- just {@link HgSshWireServer#rejectConnection}. */
    private static class DenyingHgWireCommand implements Command, Runnable {
        private final String reason;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        DenyingHgWireCommand(String reason) {
            this.reason = reason;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-deny-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                HgSshWireServer.rejectConnection(in, out, reason);
                // Give the client a moment to actually read our buffered responses before MINA
                // tears the channel down -- calling onExit() immediately after the last flush()
                // races the channel teardown against delivery of that last write over the network
                // (observed 2026-09-09: without this, the SSH exit-status message sometimes never
                // reaches the client, and OpenSSH then reports a transport-level failure (exit 255)
                // instead of passing our command's own exit code through cleanly).
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                callback.onExit(1);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }

    /** Server-side {@code Command} adapter -- exactly the shape a real production SSH server
     * entry point would use to attach {@link HgSshWireServer} to whatever SSH library it picks. */
    private static class HgWireCommand implements Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        HgWireCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                Matcher m = REPO_PATH.matcher(command == null ? "" : command);
                if (!m.find()) {
                    err.write(("bad command: " + command + "\n").getBytes());
                    err.flush();
                    callback.onExit(1);
                    return;
                }
                HgRepository repo = new HgRepository(new File(m.group(1)));
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }
}
