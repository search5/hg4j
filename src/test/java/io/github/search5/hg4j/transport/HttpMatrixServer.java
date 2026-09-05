package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.transport.WireMatrixCombos.HttpCombo;
import io.github.search5.hg4j.transport.WireMatrixCombos.Tier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wraps a real {@code hg serve} process (via {@link RealHgServeSupport}) plus, when the combo
 * needs it, a {@link CapabilityStrippingHttpProxy} in front of it -- the exact HTTP matrix
 * server-side setup {@link HgWireProtocolMatrixTest} established for {@code Clone}/{@code Pull}/
 * {@code Push}, extracted here so the wave-5 sibling matrix tests ({@code Fetch}/{@code Incoming}/
 * {@code Outgoing}/{@code Clonebundles}/{@code NarrowClone}) don't each reimplement it.
 */
final class HttpMatrixServer implements AutoCloseable {

    final RealHgServeSupport.ServeHandle serve;
    private final CapabilityStrippingHttpProxy proxy;
    final String url;

    private HttpMatrixServer(RealHgServeSupport.ServeHandle serve, CapabilityStrippingHttpProxy proxy, String url) {
        this.serve = serve;
        this.proxy = proxy;
        this.url = url;
    }

    /**
     * Starts {@code hg serve} in {@code repoDir} configured for {@code combo}'s compression engine
     * and (if {@link Tier#HTTPPOSTARGS}) {@code experimental.httppostargs}, plus any caller-supplied
     * extra {@code --config} arguments (e.g. {@code extensions.clonebundles=}), then fronts it with
     * a capability-stripping proxy if the combo needs {@code httpheader=} and/or {@code bundle2=}
     * stripped out.
     */
    static HttpMatrixServer start(File repoDir, HttpCombo combo, String... extraServerArgs) throws IOException, InterruptedException {
        List<String> serverArgs = new ArrayList<>(List.of("--config", "server.compressionengines=" + combo.compression()));
        if (combo.tier() == Tier.HTTPPOSTARGS) {
            serverArgs.add("--config");
            serverArgs.add("experimental.httppostargs=True");
        }
        for (String extra : extraServerArgs) {
            serverArgs.add(extra);
        }

        RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir, serverArgs.toArray(new String[0]));
        Set<String> strip = new HashSet<>();
        if (combo.tier() == Tier.LEGACY_GET) {
            strip.add("httpheader=");
        }
        if (!combo.bundle2On()) {
            strip.add("bundle2=");
        }
        CapabilityStrippingHttpProxy proxy = strip.isEmpty() ? null : new CapabilityStrippingHttpProxy(serve.url, strip);
        String effectiveUrl = proxy != null ? proxy.url : serve.url;
        return new HttpMatrixServer(serve, proxy, effectiveUrl);
    }

    /** Sanity-checks that the combo actually produced the capabilities it claims to, exactly like
     * {@link HgWireProtocolMatrixTest#httpMatrixPullAndPushRoundTrip} does. */
    void verifySanity(HttpCombo combo) throws IOException {
        HgRemoteClient probe = new HgRemoteClient(url);
        List<String> caps = probe.getCapabilities();

        if (combo.tier() == Tier.LEGACY_GET) {
            assertFalse(caps.stream().anyMatch(c -> c.startsWith("httpheader=")),
                    "sanity: proxy must have stripped httpheader= for " + combo + ", got: " + caps);
            assertFalse(caps.contains("httppostargs"),
                    "sanity: real hg default server must not advertise httppostargs for " + combo + ", got: " + caps);
        } else if (combo.tier() == Tier.HTTPPOSTARGS) {
            assertTrue(caps.contains("httppostargs"),
                    "sanity: server must advertise httppostargs for " + combo + ", got: " + caps);
        }

        if (!combo.bundle2On()) {
            assertFalse(caps.stream().anyMatch(c -> c.startsWith("bundle2=")),
                    "sanity: proxy must have stripped bundle2= for " + combo + ", got: " + caps);
        } else {
            assertTrue(caps.stream().anyMatch(c -> c.startsWith("bundle2=")),
                    "sanity: real hg default server must advertise bundle2= for " + combo + ", got: " + caps);
        }

        String compressionToken = caps.stream().filter(c -> c.startsWith("compression=")).findFirst()
                .orElseThrow(() -> new AssertionError("server must advertise a compression= token for " + combo + ", got: " + caps));
        assertEquals("compression=" + combo.compression(), compressionToken,
                "sanity: server.compressionengines=" + combo.compression() + " must make the server advertise ONLY that engine, combo=" + combo);
    }

    @Override
    public void close() {
        if (proxy != null) {
            proxy.close();
        }
        serve.close();
    }
}
