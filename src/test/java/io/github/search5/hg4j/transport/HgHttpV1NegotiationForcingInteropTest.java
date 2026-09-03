package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog 22, "범위(포함)" groups 1/4 (HTTP half): individually forces each of real hg's three
 * v1 argument-transport tiers ({@code httppostargs} / {@code httpheader=N} / legacy query-string
 * GET) and each of its wire-compression choices ({@code zlib}/{@code zstd}/{@code none}) against
 * a real, host-installed {@code hg serve} process -- not the single combination the rest of the
 * suite happens to exercise by default.
 *
 * <p><b>What was already covered before this test existed</b> (see
 * {@link HgHttpV1LiveServerInteropTest}, {@link HgHttpV1LiveServerCgNegotiationInteropTest}): a
 * default-configured real hg server (no {@code experimental.httppostargs}) always falls onto the
 * {@code httpheader=N} tier, and its default compression priority (zstd(50) &gt; zlib(20) &gt;
 * bz2 &gt; none, filtered to "available") picks zstd whenever the C extension is present -- which
 * turned out to be the case here (real hg ships its own bundled {@code mercurial.zstd} C
 * extension independent of the pip {@code zstandard} package; confirmed 2026-09-03 by inspecting
 * {@code compression.compengines.supportedwireengines()} directly against the host install). So
 * the previously-existing tests already incidentally exercised httpheader+zstd. This file closes
 * the remaining gaps: {@code httppostargs} forced on, the legacy 3rd tier forced (real hg has no
 * config to turn off its unconditional {@code httpheader=} advertisement, so this uses
 * {@link CapabilityStrippingHttpProxy} to simulate what a client sees against a server old enough
 * not to advertise it -- real hg itself is untouched), zlib forced, none forced, and
 * {@code unbundlehash} forced off (same proxy technique, same reason: unconditional in real hg).
 */
@Tag("interop")
public class HgHttpV1NegotiationForcingInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private File seedRepo(Path tempDir, String name) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve(".hg/hgrc"),
                "[web]\nallow-push = *\npush_ssl = false\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "second content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit", "-u", "dev");
        return repoDir;
    }

    private void assertPulledBothSeedCommits(HgRepository local) throws Exception {
        List<HgCommit> log = new LogCommand(local).call();
        assertTrue(log.size() >= 2, "expected at least the 2 seeded commits, got: " + log.size());
        assertTrue(log.stream().anyMatch(c -> "first commit".equals(c.getMessage())));
        assertTrue(log.stream().anyMatch(c -> "second commit".equals(c.getMessage())));
    }

    private void pushAndVerifyOnServer(File repoDir, String url, Path tempDir, String label) throws Exception {
        File pushSideDir = tempDir.resolve("push-side-" + label).toFile();
        HgRepository pushSide = Hg.init().setDirectory(pushSideDir).call();
        new PullCommand(pushSide).setSource(url).call();

        String marker = "forced-" + label + "-" + System.nanoTime();
        Files.writeString(pushSideDir.toPath().resolve(marker + ".txt"), "pushed via forced tier " + label);
        new AddCommand(pushSide).addFile(marker + ".txt").call();
        new CommitCommand(pushSide).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();

        new PushCommand(pushSide).setDestination(url).call();

        String serverLog = HgTestUtils.hg(repoDir, "log", "-T", "{desc}\n");
        assertTrue(serverLog.contains(marker),
                "real hg server (label=" + label + ") must see the pushed commit, log was: " + serverLog);
    }

    // ------------------------------------------------------------------
    // Group 1a: httppostargs tier forced ON via real config
    // ------------------------------------------------------------------

    @Test
    public void httppostargsForcedAdvertisedAndUsedForRealPullAndPush(@TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "httppostargs-server");
        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir,
                "--config", "experimental.httppostargs=True")) {

            HgRemoteClient probe = new HgRemoteClient(serve.url);
            List<String> caps = probe.getCapabilities();
            assertTrue(caps.contains("httppostargs"),
                    "sanity: server must actually advertise httppostargs once the config is on, got: " + caps);

            HgRepository local = Hg.init().setDirectory(tempDir.resolve("pull-side").toFile()).call();
            new PullCommand(local).setSource(serve.url).call();
            assertPulledBothSeedCommits(local);

            pushAndVerifyOnServer(repoDir, serve.url, tempDir, "httppostargs");
        }
    }

    // ------------------------------------------------------------------
    // Group 1c / 4: legacy 3rd tier forced (real hg has no toggle for httpheader=, so we simulate
    // an old/minimal server via a stripping proxy that never touches real hg itself)
    // ------------------------------------------------------------------

    @Test
    public void legacyGetTierForcedWhenNeitherHttppostargsNorHttpheaderAdvertised(@TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "legacy-tier-server");
        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir);
             CapabilityStrippingHttpProxy proxy = new CapabilityStrippingHttpProxy(serve.url, Set.of("httpheader="))) {

            HgRemoteClient probe = new HgRemoteClient(proxy.url);
            List<String> caps = probe.getCapabilities();
            assertFalse(caps.stream().anyMatch(c -> c.startsWith("httpheader=")),
                    "sanity: the proxy must actually have stripped httpheader= from what hg4j sees, got: " + caps);
            assertFalse(caps.contains("httppostargs"),
                    "sanity: real hg (no experimental.httppostargs config) must not advertise it either, got: " + caps);

            // Same capabilities list a real client would have seen against a genuinely very old
            // server -- exercise a real getbundle (which IS tier-sensitive, via executeArgsCommand)
            // through it end to end.
            HgRepository local = Hg.init().setDirectory(tempDir.resolve("pull-side").toFile()).call();
            new PullCommand(local).setSource(proxy.url).call();
            assertPulledBothSeedCommits(local);
        }
    }

    // ------------------------------------------------------------------
    // Group 1: x-hgproto-1 compression negotiation, each engine forced individually
    // ------------------------------------------------------------------

    @Test
    public void compressionZlibForcedRealRoundTrip(@TempDir Path tempDir) throws Exception {
        assertCompressionEngineRoundTrips(tempDir, "zlib");
    }

    @Test
    public void compressionZstdForcedRealRoundTrip(@TempDir Path tempDir) throws Exception {
        assertCompressionEngineRoundTrips(tempDir, "zstd");
    }

    @Test
    public void compressionNoneForcedRealRoundTrip(@TempDir Path tempDir) throws Exception {
        assertCompressionEngineRoundTrips(tempDir, "none");
    }

    private void assertCompressionEngineRoundTrips(Path tempDir, String engine) throws Exception {
        File repoDir = seedRepo(tempDir, "comp-" + engine + "-server");
        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir,
                "--config", "server.compressionengines=" + engine)) {

            HgRemoteClient probe = new HgRemoteClient(serve.url);
            List<String> caps = probe.getCapabilities();
            String compressionToken = caps.stream().filter(c -> c.startsWith("compression=")).findFirst()
                    .orElseThrow(() -> new AssertionError("server must advertise a compression= token, got: " + caps));
            assertEquals("compression=" + engine, compressionToken,
                    "sanity: server.compressionengines=" + engine + " must make the server advertise ONLY that engine");

            // The actual round trip: getBundle's response is framed as application/mercurial-0.2
            // with a 1-byte compression-name header (see HgRemoteClient#unwrapResponseStream) --
            // decoding it correctly for whichever engine the server actually used is exactly what
            // "협상 결과로 올바른 코덱이 선택되는지" (backlog 22's in-scope compression check) means.
            HgRepository local = Hg.init().setDirectory(tempDir.resolve("pull-side").toFile()).call();
            new PullCommand(local).setSource(serve.url).call();
            assertPulledBothSeedCommits(local);
        }
    }

    // ------------------------------------------------------------------
    // Group 2 (HTTP half): unbundlehash forced OFF (real hg has no toggle -- it's an unconditional
    // entry in wireprotov1server.wireprotocaps -- so this uses the same stripping-proxy technique)
    // ------------------------------------------------------------------

    @Test
    public void unbundlehashOffForcedRealHttpPushStillSucceeds(@TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "unbundlehash-off-server");
        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir);
             CapabilityStrippingHttpProxy proxy = new CapabilityStrippingHttpProxy(serve.url, Set.of("unbundlehash"))) {

            HgRemoteClient probe = new HgRemoteClient(proxy.url);
            List<String> caps = probe.getCapabilities();
            assertFalse(caps.contains("unbundlehash"),
                    "sanity: the proxy must actually have stripped unbundlehash, got: " + caps);

            // With the capability hidden, HgRemoteClient#push must fall back to sending the
            // LITERAL heads list (NodeIdUtil#computeUnbundleHeadsWireValue's "off" branch) instead
            // of the sha1-hashed sentinel -- and the real server's own check_heads() must still
            // accept that literal form (it's the pre-optimization default behavior, not a
            // degraded/rejected path).
            pushAndVerifyOnServer(repoDir, proxy.url, tempDir, "unbundlehash-off");
        }
    }
}
