package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live wire-protocol-v1 interop test that specifically verifies changegroup-version
 * <b>negotiation</b> — not just that hg4j's advertisement string looks correct, but that a real
 * server actually converges on a non-cg1 version during a live conversation. Runs against the
 * Rust-enabled real Mercurial 7.2.4 image ({@code docker run -d --rm -p 18199:8099
 * hg-rust-7.2.4 sleep infinity}, then {@code hg serve} started inside it against a seeded
 * 2-commit repo — see {@code decisions/mercurial-spec-compliance-requirement.md}).
 *
 * <p>This closes the gap found on 2026-09-03: {@link HgRemoteClient} previously sent
 * {@code getbundle}'s arguments (including {@code bundlecaps}) as an HTTP POST body, which a real
 * server's v1 arg parser never reads for that command — the server silently saw an empty argument
 * set and fell back to legacy bundle1 (cg1) no matter what version list hg4j advertised. The fix
 * (GET + {@code X-HgArg-N} headers, matching a real {@code hg --debug clone} capture) is verified
 * here by inspecting the ACTUAL changegroup version the server chose to send back, not merely the
 * outgoing request shape (that part is covered by {@link HgArgProtocolTest} against a mock
 * server).</p>
 *
 * <p>Skipped automatically if the container is not reachable.</p>
 */
@Tag("interop")
public class HgHttpV1LiveServerCgNegotiationInteropTest {

    private static final String SERVER_URL = "http://localhost:18199/";

    private static boolean isServerReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(SERVER_URL + "?cmd=capabilities").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void getBundleActuallyNegotiatesAnAdvertisedChangegroupVersionInsteadOfSilentlyDegradingToCg1() throws Exception {
        assumeTrue(isServerReachable(), "Live Rust-enabled Mercurial 7.2.4 test server not reachable at " + SERVER_URL);

        HgRemoteClient client = new HgRemoteClient(SERVER_URL);
        List<String> caps = client.getCapabilities();
        assertTrue(caps.stream().anyMatch(c -> c.startsWith("httpheader=")),
                "sanity: the real server must advertise httpheader= for the GET+X-HgArg-N tier to even be exercised here "
                        + "(got: " + caps + ")");

        byte[] bundleBytes = client.getBundle(List.of(), null, null);
        assertTrue(bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G'
                        && bundleBytes[2] == '2' && bundleBytes[3] == '0',
                "expected a bundle2-framed response (HG20 magic)");

        Bundle2Parser.ExtractedBundle2 ext = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
        assertNotEquals("01", ext.cgVersion,
                "negotiation must not silently degrade to legacy bundle1/cg1 -- the real server's own "
                        + "capabilities response advertises changegroup=01,02,03 nested in its bundle2= token");
        // Confirmed by decompressing the raw response and reading the CHANGEGROUP bundle2 part's
        // own "version" parameter directly (2026-09-03): the real server actually chose "04", a
        // version *higher* than its flat capabilities-string changegroup=01,02,03 token would
        // suggest -- real hg's server-side version selection isn't simply "max of the advertised
        // list", it's a live decision the server makes per-request. Asserting the exact literal
        // observed value (rather than a looser "not 01") is deliberate: it's the strongest
        // evidence available that this is a genuine, request-specific negotiation outcome and not
        // some hardcoded/stale value.
        assertEquals("04", ext.cgVersion,
                "the real server actually negotiated changegroup version 04 for this request (confirmed by "
                        + "decompressing the raw response and reading the CHANGEGROUP part's own version= parameter)");
    }

    @Test
    public void pullEndToEndAgainstTheRustEnabledServerStillProducesACorrectRepository(@TempDir Path tempDir) throws Exception {
        assumeTrue(isServerReachable(), "Live Rust-enabled Mercurial 7.2.4 test server not reachable at " + SERVER_URL);

        HgRepository local = Hg.init().setDirectory(tempDir.toFile()).call();
        new PullCommand(local).setSource(SERVER_URL).call();

        List<HgCommit> log = new LogCommand(local).call();
        assertTrue(log.size() >= 2, "Expected at least the 2 seeded commits, got: " + log.size());
        assertTrue(log.stream().anyMatch(c -> "second commit".equals(c.getMessage())));
        assertTrue(log.stream().anyMatch(c -> "first commit".equals(c.getMessage())));
    }
}
