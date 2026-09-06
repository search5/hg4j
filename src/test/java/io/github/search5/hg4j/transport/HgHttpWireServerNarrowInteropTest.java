package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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

    private Server server;
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
        server = HgTestUtils.startServlet(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                boolean isGetbundle = request.getQueryString() != null
                        && request.getQueryString().contains("cmd=getbundle");
                if (!isGetbundle) {
                    delegate.service(request, response);
                    return;
                }
                delegate.service(request, new ResponseCapturingHttpServletResponse(response, capturedGetbundleResponse));
            }
        });
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
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
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
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
     * A thin {@link HttpServletResponseWrapper} that tees everything written to the response body
     * into {@code capture} as well as the real client socket -- the servlet-API equivalent of the
     * old {@code HttpExchange} subclass this replaced (that had to override every delegating
     * method by hand; {@code HttpServletResponseWrapper} does that forwarding for free, leaving
     * only {@link #getOutputStream()} to override).
     */
    private static final class ResponseCapturingHttpServletResponse extends HttpServletResponseWrapper {
        private final AtomicReference<byte[]> capture;
        private ServletOutputStream teeStream;

        ResponseCapturingHttpServletResponse(HttpServletResponse response, AtomicReference<byte[]> capture) {
            super(response);
            this.capture = capture;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (teeStream == null) {
                ServletOutputStream real = super.getOutputStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                teeStream = new ServletOutputStream() {
                    @Override public void write(int b) throws IOException { real.write(b); buffer.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        real.write(b, off, len);
                        buffer.write(b, off, len);
                    }
                    @Override public void flush() throws IOException { real.flush(); }
                    @Override public void close() throws IOException {
                        real.close();
                        capture.set(buffer.toByteArray());
                    }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener writeListener) { }
                };
            }
            return teeStream;
        }
    }
}
