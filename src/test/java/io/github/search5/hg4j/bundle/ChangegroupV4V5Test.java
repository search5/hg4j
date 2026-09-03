package io.github.search5.hg4j.bundle;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.transport.HgRemoteClient;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrow interop coverage for the cg4/cg5 changegroup support added 2026-09-03 (see backlog item
 * 11 in {@code llm-wiki/decisions/mercurial-spec-compliance-requirement.md}):
 *
 * <ol>
 * <li>{@link #parsesRealHgCg4Fixture()}/{@link #parsesRealHgCg5Fixture()} — hg4j correctly parses
 * real cg4/cg5 changegroup bytes produced by host native hg 7.2.2 (fixtures under
 * {@code src/test/resources/fixtures/changegroup-v4-v5/}, see that directory's README for exact
 * generation commands and byte-level verification notes).</li>
 * <li>{@link #roundTripsHg4jPackedCg4ThroughRealHg()}/{@link #roundTripsHg4jPackedCg5ThroughRealHg()}
 * — bytes hg4j itself packs via {@link ChangegroupParser#writeBundle} are accepted by real hg's
 * own {@code unbundle} and reconstruct the exact same repository content.</li>
 * <li>{@link #negotiationPicksCg5WhenRealHgAdvertisesIt()}/{@link #negotiationPicksCg4FromDefaultRealHgRepoOnPull()}
 * — hg4j's now-correctly-encoded {@code bundlecaps} advertisement (see {@link
 * Bundle2Parser#buildChangegroupBundleCaps}) causes a real hg getbundle server to pick the
 * highest common version ({@code version = max(intersection)}, exactly real hg's own {@code
 * exchange.py} rule): cg5 when the server opts in via {@code experimental.changegroup5}, and cg4
 * — not cg3 — even against a completely default, unconfigured repo, since {@code
 * changegroup.supportedoutgoingversions()} (the server-sends-to-client direction used by
 * pull/getbundle) includes cg4 unconditionally; only the opposite push/unbundle direction gates
 * cg4 behind {@code experimental.changegroup4} (verified against Mercurial 7.2.2, 2026-09-03 —
 * corrects this backlog item's original background summary, which conflated the two
 * directions).</li>
 * </ol>
 */
public class ChangegroupV4V5Test {

    private static byte[] loadFixture(String name) throws IOException {
        try (InputStream in = ChangegroupV4V5Test.class.getResourceAsStream(
                "/fixtures/changegroup-v4-v5/" + name)) {
            assertNotNull(in, "fixture resource not found: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    public void parsesRealHgCg4Fixture() throws Exception {
        byte[] data = loadFixture("cg04-payload.bin");
        ChangegroupParser.ChangegroupBundle bundle =
                ChangegroupParser.parseBundle(new ByteArrayInputStream(data), "04");

        assertEquals(3, bundle.changelogEntries.size());
        assertEquals("9e54c4614e4d49fb429320031672db550c732ebf",
                NodeIdUtil.toHex(bundle.changelogEntries.get(2).node));
        // rev0 (no parent): real hg sends cg4's raw-full-text mode (protocol_flags & CG_FLAG_FULL_TEXT).
        ChangegroupParser.ChangeGroupEntry cl0 = bundle.changelogEntries.get(0);
        assertTrue(cl0.fullText, "changelog rev0 must be transmitted as cg4 full text");
        assertEquals(0, cl0.flags, "REVIDX_DELTA_INFO_FLAGS bits must be masked out of flags");

        // Root manifest bug-fix check: real hg's cg3+/cg4/cg5 envelope has NO path chunk before
        // the root ("") manifest group -- all 3 revisions must land there, not be misparsed as a
        // bogus subdirectory path with the first entry eaten.
        assertNotNull(bundle.manifestGroups);
        assertEquals(1, bundle.manifestGroups.size(), "flat repo: root group only, no subdirs");
        assertEquals("", bundle.manifestGroups.get(0).path);
        assertEquals(3, bundle.manifestGroups.get(0).entries.size());

        // Manifest rev2 is a genuine delta (real parent, not full text).
        ChangegroupParser.ChangeGroupEntry mf2 = bundle.manifestGroups.get(0).entries.get(2);
        assertFalse(mf2.fullText);
        assertEquals("b1e0860752d52d9fe6164d01e52371b9a740f1db",
                NodeIdUtil.toHex(mf2.deltabase));

        assertEquals(2, bundle.fileGroups.size());
        assertEquals("a.txt", bundle.fileGroups.get(0).path);
        assertEquals(2, bundle.fileGroups.get(0).entries.size());
        assertEquals("sub/b.txt", bundle.fileGroups.get(1).path);
        assertEquals(1, bundle.fileGroups.get(1).entries.size());
    }

    @Test
    public void parsesRealHgCg5Fixture() throws Exception {
        byte[] data = loadFixture("cg05-payload.bin");
        ChangegroupParser.ChangegroupBundle bundle =
                ChangegroupParser.parseBundle(new ByteArrayInputStream(data), "05");

        assertEquals(3, bundle.changelogEntries.size());
        assertEquals("9e54c4614e4d49fb429320031672db550c732ebf",
                NodeIdUtil.toHex(bundle.changelogEntries.get(2).node));
        // This fixture's source repo is flat/plain revlogv1 -- no sidedata exists anywhere, so
        // every entry's protocol_flags must be 0 (cg5's only per-entry bit, CG_FLAG_SIDEDATA).
        for (ChangegroupParser.ChangeGroupEntry e : bundle.changelogEntries) {
            assertEquals(0, e.protocolFlags);
            assertEquals(0, e.encodedCompression); // cg5 doesn't even carry this field; must stay default
        }

        assertNotNull(bundle.manifestGroups);
        assertEquals(1, bundle.manifestGroups.size());
        assertEquals("", bundle.manifestGroups.get(0).path);
        assertEquals(3, bundle.manifestGroups.get(0).entries.size());

        assertEquals(2, bundle.fileGroups.size());
        assertEquals("a.txt", bundle.fileGroups.get(0).path);
        assertEquals(2, bundle.fileGroups.get(0).entries.size());
        assertEquals("sub/b.txt", bundle.fileGroups.get(1).path);
        assertEquals(1, bundle.fileGroups.get(1).entries.size());
    }

    @Test
    public void roundTripsHg4jPackedCg4ThroughRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed.");
        roundTripThroughRealHg(tempDir, "04", "cg04-payload.bin");
    }

    @Test
    public void roundTripsHg4jPackedCg5ThroughRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed.");
        roundTripThroughRealHg(tempDir, "05", "cg05-payload.bin");
    }

    /**
     * Parses a real-hg-produced fixture, re-serializes it with hg4j's own {@link
     * ChangegroupParser#writeBundle} (the exact bytes hg4j would send as a cg4/cg5 changegroup),
     * wraps it in a minimal HG20 envelope, and confirms real hg's own {@code unbundle} accepts it
     * and reconstructs the expected repository (proving hg4j's packer output is spec-correct, not
     * just self-consistent with its own parser).
     */
    private void roundTripThroughRealHg(Path tempDir, String version, String fixtureName) throws Exception {
        byte[] fixtureBytes = loadFixture(fixtureName);
        ChangegroupParser.ChangegroupBundle bundle =
                ChangegroupParser.parseBundle(new ByteArrayInputStream(fixtureBytes), version);

        ByteArrayOutputStream repacked = new ByteArrayOutputStream();
        ChangegroupParser.writeBundle(repacked, bundle, version);
        byte[] envelope = Bundle2Parser.wrapChangegroupInBundle2(repacked.toByteArray(), version);

        File bundleFile = tempDir.resolve("repacked-cg" + version + ".hg").toFile();
        Files.write(bundleFile.toPath(), envelope);

        File targetRepoDir = tempDir.resolve("target-cg" + version).toFile();
        targetRepoDir.mkdirs();
        HgTestUtils.hg(targetRepoDir, "init");
        HgTestUtils.hg(targetRepoDir, "unbundle", bundleFile.getAbsolutePath());
        HgTestUtils.hg(targetRepoDir, "update");

        String log = HgTestUtils.hg(targetRepoDir, "log", "-T", "{node}\\n");
        assertTrue(log.contains("9e54c4614e4d49fb429320031672db550c732eb"),
                "real hg must see the c3 commit after unbundling hg4j's repacked cg" + version + ": " + log);

        String manifest = HgTestUtils.hg(targetRepoDir, "manifest", "-r", "tip");
        assertTrue(manifest.contains("a.txt") && manifest.contains("sub/b.txt"), manifest);

        String content = HgTestUtils.hg(targetRepoDir, "cat", "-r", "tip", "a.txt");
        assertEquals("hello\nworld", content);
    }

    @Test
    public void negotiationPicksCg5WhenRealHgAdvertisesIt(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed.");
        String cgVersion = negotiateWithRealHg(tempDir, true);
        assertEquals("05", cgVersion,
                "with experimental.changegroup5=yes on the server, hg4j's broadened bundlecaps "
                        + "(01..05) must make the server pick version=max(intersection)=05");
    }

    @Test
    public void negotiationPicksCg4FromDefaultRealHgRepoOnPull(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed.");
        String cgVersion = negotiateWithRealHg(tempDir, false);
        // 실측 정정(2026-09-03): 이 백로그 항목의 원래 배경 요약은 "기본 설정 저장소끼리는
        // cg3로 자동 폴백" — changegroup.supportedincomingversions()가 cg4를 기본 필터링
        //한다는 근거였다. 그런데 supportedincomingversions()는 **push/unbundle로 받는
        // 방향**에서만 쓰이는 게이트다. pull/getbundle처럼 서버가 클라이언트에게 "보내는"
        // 방향은 changegroup.supportedoutgoingversions()가 대신 쓰이고, 이건
        // treemanifest/narrow/lfs 저장소가 아닌 한 04를 무조건(설정 없이도) 포함한다 —
        // 실제 hg 7.2.2로 직접 재현·확인(GET ?cmd=getbundle 요청을 hexdump/zlib로 직접
        // 대조, "version04" 응답 확인). 즉 hg4j가 (이번 세션에 고친) 올바른 bundlecaps로
        // 기본 설정의 real hg 서버에서 그냥 pull만 해도 **cg3가 아니라 cg4가 기본으로
        // 선택된다** — cg4 지원이 "당장 안 깨지는 opt-in 확장"이 아니라 이 fix 이후
        // 즉시 실사용 경로가 된다는 뜻. 아래 문서 업데이트에도 반영.
        assertEquals("04", cgVersion,
                "a default (unconfigured) real hg repo's OUTGOING changegroup support "
                        + "unconditionally includes cg4 (only INCOMING/push support gates it behind "
                        + "experimental.changegroup4) -- a plain pull must negotiate cg4, not cg3");
    }

    private String negotiateWithRealHg(Path tempDir, boolean enableCg5) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        HgTestUtils.hg(remoteRepoDir, "init");
        Files.writeString(new File(remoteRepoDir, "a.txt").toPath(), "hello\n");
        HgTestUtils.hg(remoteRepoDir, "add", "a.txt");
        HgTestUtils.hg(remoteRepoDir, "commit", "-m", "c1", "-u", "test");

        List<String> serveCmd = enableCg5
                ? List.of("hg", "--config", "experimental.changegroup5=yes", "serve", "-p", "0", "--address", "127.0.0.1")
                : List.of("hg", "serve", "-p", "0", "--address", "127.0.0.1");
        ProcessBuilder servePb = new ProcessBuilder(serveCmd);
        servePb.directory(remoteRepoDir);
        servePb.redirectErrorStream(true);
        Process serveProcess = servePb.start();

        InputStream rawIn = serveProcess.getInputStream();
        InputStream nonCloseableIn = new FilterInputStream(rawIn) {
            @Override
            public void close() {
                // keep underlying process stream open
            }
        };
        String remoteUrl = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(nonCloseableIn, StandardCharsets.UTF_8));
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && line.contains("listening at")) {
                    int idx = line.indexOf("http://");
                    if (idx != -1) {
                        int end = line.indexOf("/", idx + 7);
                        remoteUrl = end != -1 ? line.substring(idx, end + 1) : line.substring(idx).trim();
                        remoteUrl = remoteUrl.replaceAll("http://[^:]+:", "http://127.0.0.1:");
                        break;
                    }
                }
            }
            Thread.sleep(50);
        }
        assertNotNull(remoteUrl, "Failed to parse remote URL from hg serve output");

        try {
            HgRemoteClient client = new HgRemoteClient(remoteUrl);
            List<String> remoteHeads = client.getHeads();
            String bundleCapsValue = Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05") + ",compression=GZ,BZ,ZS";

            // NOTE: deliberately NOT client.getBundle() here -- that goes through
            // HgRemoteClient#executePostBinary (HTTP POST with a form-urlencoded body). Real hg's
            // OWN client always issues getbundle as an HTTP GET (verified 2026-09-03 by capturing
            // a real `hg clone`'s request through a logging proxy); host native hg 7.2.2's `hg
            // serve` dev server was empirically observed to silently ignore POST-body args for
            // `?cmd=getbundle` entirely (falls back to a default/legacy bundle1 response
            // regardless of the bundlecaps/common/heads sent) while honoring the identical args
            // correctly via GET query-string. That GET-vs-POST mismatch is a separate,
            // pre-existing transport gap in HgRemoteClient#getBundle unrelated to the cg4/cg5
            // delta-header work this session focused on -- flagged in the backlog as a remaining
            // gap rather than fixed here. This test uses a minimal direct GET so it exercises only
            // what this session actually changed: whether hg4j's (now bundle2=-nested)
            // bundlecaps VALUE makes a real hg getbundle server pick the correct max version.
            byte[] bundleBytes = fetchGetBundleViaHttpGet(remoteUrl, remoteHeads, bundleCapsValue);

            assertTrue(bundleBytes.length >= 4, "empty getbundle response");
            Bundle2Parser.ExtractedBundle2 extracted =
                    Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));

            // Sanity: whatever version the server picked, hg4j's parser must actually be able to
            // read it (this is the whole point of the cg4/cg5 parsing work).
            ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                    new ByteArrayInputStream(extracted.changegroupBytes), extracted.cgVersion);
            assertEquals(1, bundle.changelogEntries.size());

            return extracted.cgVersion;
        } finally {
            serveProcess.destroy();
            serveProcess.waitFor();
        }
    }

    /**
     * Minimal {@code GET ?cmd=getbundle} request mirroring the shape a real hg client actually
     * sends (see the long comment at this method's call site). Handles the same {@code
     * application/mercurial-0.1} auto-detected raw-zlib response encoding {@code
     * HgRemoteClient#unwrapResponseStream} already handles in production for the same content
     * type -- duplicated narrowly here only because {@code HgRemoteClient#getBundle} always issues
     * a POST (via the private {@code executePostBinary}) and isn't reusable directly from a test.
     */
    private static byte[] fetchGetBundleViaHttpGet(String remoteUrl, List<String> heads, String bundleCapsValue)
            throws Exception {
        StringBuilder url = new StringBuilder(remoteUrl);
        url.append("?cmd=getbundle&common=&cg=true");
        url.append("&heads=").append(java.net.URLEncoder.encode(String.join(" ", heads), StandardCharsets.UTF_8));
        url.append("&bundlecaps=").append(java.net.URLEncoder.encode(bundleCapsValue, StandardCharsets.UTF_8));

        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URI(url.toString()).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
        byte[] raw;
        try (InputStream in = conn.getInputStream()) {
            raw = in.readAllBytes();
        }

        // application/mercurial-0.1 responses are raw-zlib-deflate when compressed (auto-detected
        // by the standard zlib 2-byte header), otherwise passed through as-is.
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x78
                && ((raw[1] & 0xFF) == 0x9C || (raw[1] & 0xFF) == 0x01 || (raw[1] & 0xFF) == 0x5E || (raw[1] & 0xFF) == 0xDA)) {
            try (java.util.zip.InflaterInputStream iis =
                         new java.util.zip.InflaterInputStream(new ByteArrayInputStream(raw))) {
                return iis.readAllBytes();
            }
        }
        return raw;
    }
}
