package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Secret changesets are not served: the same raw wire requests go to a real {@code hg serve} and to hg4j's HTTP server over equal
 * copies of a repository with secret changesets, and the answers (status and body) must agree for {@code heads},
 * {@code branchmap}, {@code known}, {@code lookup}, {@code listkeys} and the legacy {@code changegroup} /
 * {@code changegroupsubset}; a push leaves the secret changesets at home like Mercurial's does.
 */
@Tag("interop")
class SecretServedViewParityInteropTest {

    @TempDir
    Path tempDir;

    private ParitySupport.RealServe real;
    private Server own;
    private int ownPort;
    private File base;
    private File realRepo;
    private File ownRepo;
    private final HttpClient client = HttpClient.newHttpClient();
    private String s1;
    private String s2;
    private String c2;

    private static void write(File d, String n) throws Exception {
        Files.writeString(new File(d, n).toPath(), n + "\n");
    }

    @BeforeEach
    void bothServers() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        base = tempDir.resolve("base").toFile();
        base.mkdirs();
        HgTestUtils.hg(base, "init");
        Files.writeString(new File(base, ".hg/hgrc").toPath(), "[phases]\npublish = false\n[web]\npush_ssl = false\nallow-push = *\n");
        write(base, "c1");
        HgTestUtils.hg(base, "add");
        HgTestUtils.hg(base, "commit", "-m", "c1", "-u", "t");
        write(base, "c2");
        HgTestUtils.hg(base, "add");
        HgTestUtils.hg(base, "commit", "-m", "c2", "-u", "t");
        HgTestUtils.hg(base, "bookmark", "visible");
        write(base, "s1");
        HgTestUtils.hg(base, "add");
        HgTestUtils.hg(base, "commit", "-m", "s1", "-u", "t");
        write(base, "s2");
        HgTestUtils.hg(base, "add");
        HgTestUtils.hg(base, "commit", "-m", "s2", "-u", "t");
        HgTestUtils.hg(base, "bookmark", "onsecret");
        HgTestUtils.hg(base, "phase", "--secret", "--force", "-r", "desc(s1)");
        c2 = HgTestUtils.hg(base, "log", "-r", "desc(c2)", "-T", "{node}").strip();
        s1 = HgTestUtils.hg(base, "log", "-r", "desc(s1)", "-T", "{node}").strip();
        s2 = HgTestUtils.hg(base, "log", "-r", "desc(s2)", "-T", "{node}").strip();
        realRepo = tempDir.resolve("real").toFile();
        ownRepo = tempDir.resolve("own").toFile();
        realRepo.mkdirs();
        ownRepo.mkdirs();
        ParitySupport.copyTree(base.toPath(), realRepo.toPath());
        ParitySupport.copyTree(base.toPath(), ownRepo.toPath());
        real = new ParitySupport.RealServe(realRepo.toPath());
        own = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(ownRepo)));
        ownPort = HgTestUtils.port(own);
    }

    @AfterEach
    void stop() {
        if (real != null) {
            real.close();
        }
        if (own != null) {
            HgTestUtils.stop(own);
        }
    }

    private record Answer(int status, String body) {
    }

    private Answer get(int port, String query) throws Exception {
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/?" + query)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), new String(r.body(), StandardCharsets.ISO_8859_1));
    }

    private void same(String query) throws Exception {
        Answer expected = get(real.port, query);
        Answer actual = get(ownPort, query);
        // hex nodes differ between the copies only if the copies differ; they are byte-equal copies
        assertEquals(expected, actual, query);
    }

    @Test
    void theReadingCommandsAnswerLikeMercurial() throws Exception {
        same("cmd=heads");
        same("cmd=branchmap");
        same("cmd=known&nodes=" + s1 + "+" + c2 + "+" + s2);
        same("cmd=lookup&key=" + s1);
        same("cmd=lookup&key=" + s1.substring(0, 12));
        same("cmd=lookup&key=" + c2);
        same("cmd=lookup&key=tip");
        same("cmd=lookup&key=onsecret");
        same("cmd=lookup&key=visible");
        same("cmd=listkeys&namespace=bookmarks");
        same("cmd=listkeys&namespace=phases");
        same("cmd=batch&cmds=heads+;known+nodes=" + s1 + "+" + c2);
    }

    private TreeSet<String> changesetNodes(HgRemoteClient c) throws Exception {
        byte[] bytes = c.getChangegroup(List.of("0".repeat(40)));
        TreeSet<String> nodes = new TreeSet<>();
        for (ChangegroupParser.ChangeGroupEntry e : ChangegroupParser.parseBundle(new ByteArrayInputStream(bytes)).changelogEntries) {
            nodes.add(io.github.search5.hg4j.util.NodeIdUtil.toHex(e.node));
        }
        return nodes;
    }

    @Test
    void theLegacyChangegroupCommandsLeaveTheSecretChangesetsOut() throws Exception {
        HgRemoteClient realClient = new HgRemoteClient("http://127.0.0.1:" + real.port);
        HgRemoteClient ownClient = new HgRemoteClient("http://127.0.0.1:" + ownPort);
        TreeSet<String> expected = changesetNodes(realClient);
        assertFalse(expected.contains(s1) || expected.contains(s2), "real hg serves no secret changeset: " + expected);
        assertEquals(expected, changesetNodes(ownClient), "changegroup");
    }

    /** The changesets of a raw {@code changegroupsubset} / {@code changegroup} answer (an uncompressed or zlib changegroup stream). */
    private TreeSet<String> rawChangegroupNodes(int port, String query) throws Exception {
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/?" + query)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode(), query);
        byte[] body = r.body();
        java.io.InputStream in = new ByteArrayInputStream(body);
        if (body.length > 1 && (body[0] & 0xff) == 0x78) { // zlib
            in = new java.util.zip.InflaterInputStream(in);
        } else if (body.length > 6 && body[0] == 'H' && body[1] == 'G' && body[2] == '1' && body[3] == '0') {
            in = new ByteArrayInputStream(body, 6, body.length - 6); // "HG10UN"
        }
        in = new ByteArrayInputStream(in.readAllBytes()); // whole stream in memory: the parser reads from a plain byte array
        TreeSet<String> nodes = new TreeSet<>();
        try {
            for (ChangegroupParser.ChangeGroupEntry e : ChangegroupParser.parseBundle(in).changelogEntries) {
                nodes.add(io.github.search5.hg4j.util.NodeIdUtil.toHex(e.node));
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the answer of " + (port == ownPort ? "hg4j" : "real hg") + " to " + query
                    + " (" + body.length + " bytes, starts " + java.util.HexFormat.of().formatHex(body, 0, Math.min(8, body.length)) + "): " + e, e);
        }
        return nodes;
    }

    @Test
    void changegroupsubsetLeavesTheSecretChangesetsOutToo() throws Exception {
        String nullNode = "0".repeat(40);
        // everything below the visible heads; and a request that names a secret head
        for (String query : new String[]{
                "cmd=changegroupsubset&bases=" + nullNode + "&heads=" + c2,
                "cmd=changegroup&roots=" + nullNode}) {
            TreeSet<String> expected = rawChangegroupNodes(real.port, query);
            assertFalse(expected.contains(s1) || expected.contains(s2), "real hg: " + expected);
            assertEquals(expected, rawChangegroupNodes(ownPort, query), query);
        }
    }

    private static TreeSet<String> descs(File dir) throws Exception {
        TreeSet<String> set = new TreeSet<>();
        for (String line : HgTestUtils.hg(dir, "log", "-T", "{desc}\n").split("\n")) {
            if (!line.isBlank()) {
                set.add(line.strip());
            }
        }
        return set;
    }

    @Test
    void aPushLeavesTheSecretChangesetsAtHome() throws Exception {
        File dstReal = tempDir.resolve("dst-real").toFile();
        File dstOwn = tempDir.resolve("dst-own").toFile();
        File srcReal = tempDir.resolve("src-real").toFile();
        File srcOwn = tempDir.resolve("src-own").toFile();
        for (File d : new File[]{dstReal, dstOwn, srcReal, srcOwn}) {
            d.mkdirs();
        }
        HgTestUtils.hg(dstReal, "init");
        HgTestUtils.hg(dstOwn, "init");
        ParitySupport.copyTree(base.toPath(), srcReal.toPath());
        ParitySupport.copyTree(base.toPath(), srcOwn.toPath());

        String realOutput = HgTestUtils.hg(srcReal, "push", dstReal.getAbsolutePath());
        assertTrue(realOutput.contains("pushing"), realOutput);
        String ownOutput = new io.github.search5.hg4j.api.PushCommand(new HgRepository(srcOwn)).setDestination(dstOwn.getAbsolutePath()).call();

        assertEquals(new TreeSet<>(List.of("c1", "c2")), descs(dstReal), "real hg pushes no secret changeset");
        assertEquals(descs(dstReal), descs(dstOwn), "hg4j leaves them at home too, silently: " + ownOutput);
    }
}
