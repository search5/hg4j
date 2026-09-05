package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog item 40, server direction: a REAL hg CLIENT narrow-cloning from an {@link
 * HgHttpWireServer}-served hg4j repository must (a) get a correct, narrow-filtered working copy,
 * exactly like {@link HgHttpWireServerRealHgInteropTest}'s other scenarios verify for plain
 * clone/pull/push, and (b) actually receive a reduced {@code getbundle} response -- i.e. hg4j's
 * server side must generate the filtered changegroup itself, not just happen to produce a correct
 * end result because the real hg client discarded the excess after the fact (it doesn't: a narrow
 * client trusts includepats/excludepats were honored server-side and never re-filters).
 */
@Tag("interop")
public class HgHttpWireServerNarrowInteropTest {

    private HttpServer server;
    private HgRepository serverRepo;
    private File serverRepoDir;
    private final AtomicReference<byte[]> capturedGetbundleResponse = new AtomicReference<>();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isNarrowExtensionAvailable(), "Native hg's narrow extension is not available. Skipping.");

        serverRepoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(serverRepoDir).call();

        File includedDir = new File(serverRepoDir, "included");
        File excludedDir = new File(serverRepoDir, "excluded");
        includedDir.mkdirs();
        excludedDir.mkdirs();
        for (int i = 0; i < 20; i++) {
            Files.writeString(new File(includedDir, "f" + i + ".txt").toPath(), "small line " + i);
        }
        // Deliberately high-entropy (not a repeated byte): the server's HTTP response is
        // zlib-deflated on the wire (HgHttpWireServer#deflate), and a repeated-byte filler would
        // compress away to almost nothing, making the "full vs narrow response size" comparison
        // below meaningless regardless of whether narrow filtering actually did anything.
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 80; i++) {
            byte[] filler = new byte[20_000];
            rnd.nextBytes(filler);
            Files.write(new File(excludedDir, "big" + i + ".bin").toPath(), filler);
        }
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("seed").setAuthor("dev").call();

        HgHttpWireServer delegate = new HgHttpWireServer(serverRepo);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            boolean isGetbundle = exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("cmd=getbundle");
            if (!isGetbundle) {
                delegate.handle(exchange);
                return;
            }
            delegate.handle(new ResponseCapturingHttpExchange(exchange, capturedGetbundleResponse));
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static boolean isNarrowExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.narrow=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    public void realHgNarrowClonesFromHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        // First, a plain (non-narrow) real hg clone against the very same server/content, to get
        // a baseline getbundle response size for this repository.
        File plainDestDir = tempDir.resolve("plain_client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), plainDestDir.getAbsolutePath());
        byte[] fullResponse = capturedGetbundleResponse.getAndSet(null);
        assertNotNull(fullResponse, "expected the plain clone to have issued a ?cmd=getbundle request");

        // Now a real hg --narrow clone against the same hg4j server.
        File narrowDestDir = tempDir.resolve("narrow_client_repo").toFile();
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "extensions.narrow=",
                "clone", "--narrow", "--include=included", baseUrl(), narrowDestDir.getAbsolutePath());
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertTrue(code == 0, "real hg --narrow clone against hg4j server failed: " + out);

        assertTrue(new File(narrowDestDir, "included/f0.txt").exists(), "in-scope file must be checked out");
        assertFalse(new File(narrowDestDir, "excluded").exists(), "out-of-scope dir must not be checked out");

        byte[] narrowResponse = capturedGetbundleResponse.get();
        assertNotNull(narrowResponse, "expected the narrow clone to have issued a ?cmd=getbundle request");

        assertTrue(fullResponse.length > 200_000,
                "sanity: the full response should be dominated by the 80 x 20KB excluded files, was "
                        + fullResponse.length + " bytes");
        assertTrue(narrowResponse.length < fullResponse.length / 4,
                "hg4j's server must actually send a reduced getbundle response to a real hg narrow "
                        + "client's request (" + narrowResponse.length + " bytes) compared to a plain "
                        + "clone's response (" + fullResponse.length + " bytes) against the same repository "
                        + "-- not just rely on the client re-filtering what it received");
    }

    /**
     * Delegates every {@link HttpExchange} method to a wrapped real exchange except {@link
     * #getResponseBody()}, which tees whatever bytes get written through it into {@code capture}
     * as well -- copied from {@link HgHttpWireServerRealHgInteropTest} (kept private there), same
     * approach: lets a test observe exactly what {@link HgHttpWireServer} sent for one specific
     * request without any production-code changes.
     */
    private static final class ResponseCapturingHttpExchange extends HttpExchange {
        private final HttpExchange delegate;
        private final AtomicReference<byte[]> capture;
        private java.io.OutputStream teeStream;

        ResponseCapturingHttpExchange(HttpExchange delegate, AtomicReference<byte[]> capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public java.io.OutputStream getResponseBody() {
            if (teeStream == null) {
                java.io.OutputStream real = delegate.getResponseBody();
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                teeStream = new java.io.OutputStream() {
                    @Override public void write(int b) throws java.io.IOException { real.write(b); buffer.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                        real.write(b, off, len);
                        buffer.write(b, off, len);
                    }
                    @Override public void flush() throws java.io.IOException { real.flush(); }
                    @Override public void close() throws java.io.IOException {
                        real.close();
                        capture.set(buffer.toByteArray());
                    }
                };
            }
            return teeStream;
        }

        @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
        @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
        @Override public java.net.URI getRequestURI() { return delegate.getRequestURI(); }
        @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return delegate.getHttpContext(); }
        @Override public void close() { delegate.close(); }
        @Override public java.io.InputStream getRequestBody() { return delegate.getRequestBody(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws java.io.IOException {
            delegate.sendResponseHeaders(rCode, responseLength);
        }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
        @Override public int getResponseCode() { return delegate.getResponseCode(); }
        @Override public java.net.InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public String getProtocol() { return delegate.getProtocol(); }
        @Override public Object getAttribute(String name) { return delegate.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
        @Override public void setStreams(java.io.InputStream i, java.io.OutputStream o) { delegate.setStreams(i, o); }
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
    }
}
