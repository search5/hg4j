package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential tests of {@link HgSshWireServer}: the same request goes to the REAL {@code hg -R repo serve
 * --stdio} and to hg4j, and the answers (or what a real {@code hg} client ends up doing with them) must be the
 * same. This is how the gaps listed in {@code llm-wiki/backlog/wire-server-command-gaps.md} were found.
 *
 * <p>The enabled tests pin down what already matches. The {@link Disabled} tests are KNOWN failures, kept
 * as the failing test of the fix to come (red/green): each states in its reason which gap it covers.
 * Enable one together with its fix.</p>
 */
@Tag("interop")
public class WireServerParityInteropTest {

    private static final String GAPS = "known gap, see llm-wiki/backlog/wire-server-command-gaps.md: ";

    @TempDir
    Path tmp;
    private Path repo;
    private String n0;
    private String n1;
    private String n2;

    @BeforeEach
    void createServerRepo() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        repo = Files.createDirectories(tmp.resolve("server"));
        LocalHg.hgIn(tmp, "server", "init", ".");
        for (int i = 0; i < 3; i++) {
            Files.writeString(repo.resolve("a.txt"), "a" + i + "\n");
            if (i == 0) {
                LocalHg.hgIn(tmp, "server", "add", "a.txt");
            }
            LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "c" + i);
        }
        LocalHg.hgIn(tmp, "server", "bookmark", "bm1", "-r", "1");
        LocalHg.hgIn(tmp, "server", "tag", "-u", "dev", "-r", "1", "v1");
        LocalHg.hgIn(tmp, "server", "update", "-r", "1");
        LocalHg.hgIn(tmp, "server", "branch", "feature");
        Files.writeString(repo.resolve("a.txt"), "f\n");
        LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "on feature");
        LocalHg.hgIn(tmp, "server", "update", "-r", "default");
        n0 = LocalHg.hgIn(tmp, "server", "log", "-r", "0", "--template", "{node}");
        n1 = LocalHg.hgIn(tmp, "server", "log", "-r", "1", "--template", "{node}");
        n2 = LocalHg.hgIn(tmp, "server", "log", "-r", "2", "--template", "{node}");
    }

    // ───────────────────────── raw SSH-v1 exchange ─────────────────────────

    /** One request: command line, then {@code name len\nvalue} per argument; {@code "*"} sends an empty star. */
    private static byte[] request(String cmd, String... args) {
        StringBuilder sb = new StringBuilder(cmd).append('\n');
        for (String a : args) {
            if (a.equals("*")) {
                sb.append("* 0\n");
                continue;
            }
            if (a.startsWith("*")) { // "*three=x&four=y": a star argument carrying values
                String[] pairs = a.substring(1).split("&");
                sb.append("* ").append(pairs.length).append('\n');
                for (String pair : pairs) {
                    int eq2 = pair.indexOf('=');
                    sb.append(pair, 0, eq2).append(' ').append(pair.substring(eq2 + 1).getBytes(StandardCharsets.UTF_8).length)
                            .append('\n').append(pair.substring(eq2 + 1));
                }
                continue;
            }
            int eq = a.indexOf('=');
            String v = a.substring(eq + 1);
            sb.append(a, 0, eq).append(' ').append(v.getBytes(StandardCharsets.UTF_8).length).append('\n').append(v);
        }
        return sb.append('\n').toString().getBytes(StandardCharsets.UTF_8); // trailing empty line ends the session
    }

    private byte[] ask(boolean real, String cmd, String... args) throws Exception {
        if (real) {
            // real hg only serves 'hg -R <repo> serve --stdio' (it refuses a bare 'serve --stdio' as unsafe)
            ProcessBuilder pb = LocalHg.processBuilder(repo, "-R", repo.toString(), "serve", "--stdio");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(request(cmd, args));
            }
            byte[] out = p.getInputStream().readAllBytes();
            assertTrue(p.waitFor(30, TimeUnit.SECONDS));
            return out;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new HgSshWireServer(new HgRepository(repo.toFile()))
                .handleConnection(new ByteArrayInputStream(request(cmd, args)), out);
        return out.toByteArray();
    }

    private void assertSameAnswer(String cmd, String... args) throws Exception {
        assertEquals(show(ask(true, cmd, args)), show(ask(false, cmd, args)), cmd + " " + String.join(" ", args));
    }

    private static String show(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1).replace("\n", "\\n");
    }

    // ───────────────────────── already identical ─────────────────────────

    @Test
    @DisplayName("heads, known, 알려진 이름의 lookup, 없는 이름의 lookup, 없는 네임스페이스의 listkeys, clonebundles, between(null-null)은 원본과 바이트까지 같다")
    void alreadyIdentical() throws Exception {
        assertSameAnswer("known", "nodes=" + n0 + " " + "ab".repeat(20), "*");
        assertSameAnswer("lookup", "key=" + n1);
        assertSameAnswer("lookup", "key=tip");
        assertSameAnswer("lookup", "key=nosuchthing");
        assertSameAnswer("listkeys", "namespace=nosuch");
        assertSameAnswer("clonebundles");
        assertSameAnswer("between", "pairs=" + "0".repeat(40) + "-" + "0".repeat(40));
    }

    @Test
    @DisplayName("heads 는 헤드가 둘 이상이어도 원본과 같은 집합을 준다")
    void headsAreTheSameSet() throws Exception {
        java.util.function.Function<byte[], java.util.Set<String>> set = b -> {
            String body = new String(b, StandardCharsets.ISO_8859_1);
            return new java.util.TreeSet<>(java.util.Arrays.asList(body.substring(body.indexOf('\n') + 1).trim().split(" ")));
        };
        assertEquals(set.apply(ask(true, "heads")), set.apply(ask(false, "heads")));
        assertEquals(2, set.apply(ask(true, "heads")).size(), "the fixture really has two heads");
    }

    @Test
    @DisplayName("debugwireargs 와 protocaps 가 원본과 같다 (선택 인자 three/four 포함)")
    void debugwireargsAndProtocaps() throws Exception {
        assertSameAnswer("debugwireargs", "one=a", "two=b", "*");
        assertSameAnswer("debugwireargs", "one=a", "two=b", "*three=x&four=y");
        assertSameAnswer("debugwireargs", "one=a", "two=b", "*four=only4");
        assertSameAnswer("protocaps", "caps=comp=zstd,zlib");
    }

    @Test
    @DisplayName("긴 선형 이력에서 between 이 원본과 같은 이분 탐색 결과를 준다")
    void betweenOnALongHistory() throws Exception {
        Path longRepo = Files.createDirectories(tmp.resolve("long"));
        LocalHg.hgIn(tmp, "long", "init", ".");
        Files.writeString(longRepo.resolve("f.txt"), "x");
        LocalHg.hgIn(tmp, "long", "add", "f.txt");
        for (int i = 0; i < 20; i++) {
            Files.writeString(longRepo.resolve("f.txt"), "x" + i);
            LocalHg.hgIn(tmp, "long", "commit", "-u", "dev", "-m", "c" + i);
        }
        String tip = LocalHg.hgIn(tmp, "long", "log", "-r", "tip", "--template", "{node}");
        String r3 = LocalHg.hgIn(tmp, "long", "log", "-r", "3", "--template", "{node}");
        String r19 = LocalHg.hgIn(tmp, "long", "log", "-r", "19", "--template", "{node}");
        Path saved = repo;
        repo = longRepo;
        try {
            assertSameAnswer("between", "pairs=" + tip + "-" + "0".repeat(40));
            assertSameAnswer("between", "pairs=" + tip + "-" + r3);
            assertSameAnswer("between", "pairs=" + tip + "-" + r19);
            assertSameAnswer("between", "pairs=" + tip + "-" + r3 + " " + r19 + "-" + "0".repeat(40));
            assertSameAnswer("branches", "nodes=" + tip);
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("병합이 있는 이력에서 branches 가 원본과 같다")
    void branchesAcrossAMerge() throws Exception {
        Path m = Files.createDirectories(tmp.resolve("merge"));
        LocalHg.hgIn(tmp, "merge", "init", ".");
        Files.writeString(m.resolve("a.txt"), "a\n");
        LocalHg.hgIn(tmp, "merge", "add", "a.txt");
        LocalHg.hgIn(tmp, "merge", "commit", "-u", "dev", "-m", "c0");
        Files.writeString(m.resolve("a.txt"), "a1\n");
        LocalHg.hgIn(tmp, "merge", "commit", "-u", "dev", "-m", "c1");
        LocalHg.hgIn(tmp, "merge", "update", "-r", "0");
        Files.writeString(m.resolve("b.txt"), "b\n");
        LocalHg.hgIn(tmp, "merge", "add", "b.txt");
        LocalHg.hgIn(tmp, "merge", "commit", "-u", "dev", "-m", "c2");
        LocalHg.hgIn(tmp, "merge", "merge", "-r", "1");
        LocalHg.hgIn(tmp, "merge", "commit", "-u", "dev", "-m", "merge");
        Files.writeString(m.resolve("a.txt"), "a3\n");
        LocalHg.hgIn(tmp, "merge", "commit", "-u", "dev", "-m", "c4");
        Path saved = repo;
        repo = m;
        try {
            for (String rev : List.of("0", "1", "2", "3", "4")) {
                String node = LocalHg.hgIn(tmp, "merge", "log", "-r", rev, "--template", "{node}");
                assertSameAnswer("branches", "nodes=" + node);
            }
            assertSameAnswer("branches", "nodes=");
        } finally {
            repo = saved;
        }
    }

    private void writeManifest(String... lines) throws Exception {
        Files.writeString(repo.resolve(".hg/clonebundles.manifest"), String.join("\n", lines) + "\n");
    }

    @Test
    @DisplayName("clonebundles 와 clonebundles_manifest 가 여러 형태의 매니페스트 줄에 대해 원본과 바이트까지 같다")
    void clonebundlesFamily() throws Exception {
        writeManifest(
                "http://example.com/a.hg BUNDLESPEC=gzip-v2",
                "http://example.com/b.hg BUNDLESPEC=none-v2;STREAM=v2",
                "http://example.com/c.hg BUNDLESPEC=none-v2;SHARD-ID=ab12",
                "http://example.com/d.hg BUNDLESPEC=none-v2;store-fingerprint=zz REQUIRESNI=true",
                "peer-bundle-cache://inline-one.hg BUNDLESPEC=none-v2",
                "http://example.com/e.hg",
                "",
                "http://example.com/f.hg BUNDLESPEC=none-v2;requirements%3Dgeneraldelta%2Crevlogv1",
                "http://example.com/g.hg   BUNDLESPEC=none-v2;STORE-FINGERPRINT%3Dzz;stream=v2",
                "http://example.com/h.hg BUNDLESPEC=none-v2;Mixed=1");
        assertSameAnswer("clonebundles");
        assertSameAnswer("clonebundles_manifest", "*");
        // 7.2.4 knows no arguments for clonebundles_manifest: they are accepted and ignored
        assertSameAnswer("clonebundles_manifest", "*mandatory_params=1&store_fingerprint=1");
    }

    @Test
    @DisplayName("매니페스트가 없을 때 clonebundles, clonebundles_manifest 가 원본과 같다")
    void clonebundlesWithoutAManifest() throws Exception {
        assertSameAnswer("clonebundles");
        assertSameAnswer("clonebundles_manifest", "*");
    }

    @Test
    @DisplayName("get_cached_bundle_inline 이 길이(varint)와 파일 내용을 원본과 같이 보낸다")
    void getCachedBundleInline() throws Exception {
        Files.writeString(repo.resolve(".hg/hgrc"), "[server]\npeer-bundle-cache-root = bundle-cache\n");
        Files.createDirectories(repo.resolve(".hg/bundle-cache"));
        Files.write(repo.resolve(".hg/bundle-cache/small.hg"), "HG20-pretend-bundle".getBytes(StandardCharsets.US_ASCII));
        byte[] big = new byte[300]; // longer than 127 bytes: the length needs two varint bytes
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(repo.resolve(".hg/bundle-cache/big.hg"), big);
        assertSameAnswer("get_cached_bundle_inline", "path=small.hg");
        assertSameAnswer("get_cached_bundle_inline", "path=big.hg");
    }

    @Test
    @DisplayName("매니페스트가 있으면 capabilities 에 clonebundles 와 clonebundles_manifest 를 모두 광고한다")
    void capabilitiesAdvertiseBothClonebundlesTokens() throws Exception {
        writeManifest("http://example.com/a.hg BUNDLESPEC=gzip-v2");
        String mine = show(ask(false, "capabilities"));
        assertTrue(mine.contains(" clonebundles ") || mine.endsWith(" clonebundles") || mine.contains(" clonebundles clonebundles_manifest"), mine);
        assertTrue(mine.contains("clonebundles_manifest"), mine);
    }

    // ───────────────────────── development-only commands ─────────────────────────
    // heads_buckets and store_shape are not in the 7.2.4 release; they are compared with an oracle built from the
    // development checkout (see LocalHg#isDevAvailable).

    private byte[] askDev(String cmd, String... args) throws Exception {
        ProcessBuilder pb = LocalHg.devProcessBuilder(repo, "-R", repo.toString(), "serve", "--stdio");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(request(cmd, args));
        }
        byte[] out = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return out;
    }

    private void assertSameAsDevOracle(String cmd, String... args) throws Exception {
        assertEquals(show(askDev(cmd, args)), show(ask(false, cmd, args)), cmd + " " + String.join(" ", args));
    }

    /** Builds a repository of {@code n} linear commits, plus extra heads on top of the listed revisions. */
    private Path buildHistory(String name, int linear, int... extraHeadsOn) throws Exception {
        Path r = Files.createDirectories(tmp.resolve(name));
        LocalHg.hgIn(tmp, name, "init", ".");
        Files.writeString(r.resolve("f.txt"), "x");
        LocalHg.hgIn(tmp, name, "add", "f.txt");
        for (int i = 0; i < linear; i++) {
            Files.writeString(r.resolve("f.txt"), "v" + i);
            LocalHg.hgIn(tmp, name, "commit", "-u", "dev", "-m", "c" + i);
        }
        for (int base : extraHeadsOn) {
            LocalHg.hgIn(tmp, name, "update", "-r", String.valueOf(base));
            Files.writeString(r.resolve("f.txt"), "branch-from-" + base);
            LocalHg.hgIn(tmp, name, "commit", "-u", "dev", "-m", "head-on-" + base);
        }
        return r;
    }

    @Test
    @DisplayName("heads_buckets 가 개발판 원본과 바이트까지 같다 (빈 저장소, 1개, 짝수/홀수 개수, 여러 head)")
    void headsBuckets() throws Exception {
        Assumptions.assumeTrue(LocalHg.isDevAvailable(), "no development hg oracle (see LocalHg#isDevAvailable)");
        Path saved = repo;
        try {
            repo = Files.createDirectories(tmp.resolve("hb-empty"));
            LocalHg.hgIn(tmp, "hb-empty", "init", ".");
            assertSameAsDevOracle("heads_buckets", "*");

            for (int linear : new int[]{1, 2, 3, 4, 7, 8, 9, 16, 33}) {
                repo = buildHistory("hb-linear-" + linear, linear);
                assertSameAsDevOracle("heads_buckets", "*");
            }
            repo = buildHistory("hb-multi", 12, 2, 5, 9, 10);
            assertSameAsDevOracle("heads_buckets", "*");
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("heads_buckets 의 cached 인자(클라이언트가 기억하는 지문)가 보내는 head 를 줄이는 방식이 개발판 원본과 같다")
    void headsBucketsWithCachedFingerprints() throws Exception {
        Assumptions.assumeTrue(LocalHg.isDevAvailable(), "no development hg oracle (see LocalHg#isDevAvailable)");
        Path saved = repo;
        try {
            repo = buildHistory("hb-cached", 12, 2, 5, 9);
            byte[] first = askDev("heads_buckets", "*");
            // the framed answer starts with "<length>\n"; the payload is: header(7) then per bucket (u32 id, i32 count, 4B fingerprint)
            int nl = indexOf(first, (byte) '\n', 0);
            byte[] payload = java.util.Arrays.copyOfRange(first, nl + 1, first.length);
            int buckets = payload[0] & 0xFF;
            List<String> cachedPieces = new ArrayList<>();
            for (int i = 0; i < buckets; i++) {
                int at = 7 + i * 12;
                long id = java.nio.ByteBuffer.wrap(payload, at, 4).getInt() & 0xFFFFFFFFL;
                StringBuilder hex = new StringBuilder();
                for (int k = 0; k < 4; k++) {
                    hex.append(String.format("%02x", payload[at + 8 + k]));
                }
                cachedPieces.add(id + ":" + hex);
            }
            // the client remembers nothing, the lowest bucket, a middle one, everything
            assertSameAsDevOracle("heads_buckets", "*cached=" + cachedPieces.get(0));
            assertSameAsDevOracle("heads_buckets", "*cached=" + cachedPieces.get(cachedPieces.size() / 2));
            assertSameAsDevOracle("heads_buckets", "*cached=" + String.join(";", cachedPieces));
            assertSameAsDevOracle("heads_buckets", "*cached=99:00000000");
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("SSH hello 의 heads-buckets-info-v01 줄이 개발판 원본과 같다")
    void helloCarriesTheSameBucketLine() throws Exception {
        Assumptions.assumeTrue(LocalHg.isDevAvailable(), "no development hg oracle (see LocalHg#isDevAvailable)");
        Path saved = repo;
        try {
            repo = buildHistory("hb-hello", 10, 3, 6);
            Files.writeString(repo.resolve(".hg/hgrc"), "[experimental]\ndisco.heads-fingerprints = true\n");
            java.util.function.Function<byte[], String> bucketLine = raw -> {
                for (String line : new String(raw, StandardCharsets.ISO_8859_1).split("\n")) {
                    if (line.startsWith("heads-buckets-info-v01:")) {
                        return line;
                    }
                }
                return null;
            };
            String expected = bucketLine.apply(askDev("hello"));
            assertNotNull(expected, "the development server sends a bucket line when the capability is on");
            // The order of the "<tier>:<fingerprint>" tokens is not meaningful (a client reads them into a dictionary); the
            // Rust implementation of the original lists them in hash-map order, so the SET of tokens is compared.
            java.util.Set<String> expectedTokens = new java.util.TreeSet<>(java.util.Arrays.asList(expected.split(" ")));
            java.util.Set<String> actualTokens = new java.util.TreeSet<>(java.util.Arrays.asList(
                    bucketLine.apply(ask(false, "hello")).split(" ")));
            assertEquals(expectedTokens, actualTokens);
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("store_shape 가 정의된 모양이 없을 때 개발판 원본과 같은 SHAPE_NOT_FOUND 를 준다")
    void storeShapeWithoutShapes() throws Exception {
        Assumptions.assumeTrue(LocalHg.isDevAvailable(), "no development hg oracle (see LocalHg#isDevAvailable)");
        assertSameAsDevOracle("store_shape", "names=x", "*");
        assertSameAsDevOracle("store_shape", "names=first,second", "*");
        // names= (an empty name) is left out on purpose: the original dies without any answer there (an unhandled error),
        // hg4j keeps answering with a well-formed SHAPE_NOT_FOUND for the empty name.
        byte[] emptyName = ask(false, "store_shape", "names=", "*");
        int nl = indexOf(emptyName, (byte) '\n', 0);
        byte[] payload = java.util.Arrays.copyOfRange(emptyName, nl + 1, emptyName.length);
        assertEquals(Integer.parseInt(new String(emptyName, 0, nl, StandardCharsets.US_ASCII)), payload.length, "framed");
        assertEquals(2, payload[0], "code SHAPE_NOT_FOUND");
        assertEquals(11 + 8, payload.length, "header + a block holding one empty name");
    }

    @Test
    @DisplayName("heads_fingerprints / exp-shape-1 capability 를 광고하고, 설정으로 끌 수 있다")
    void headsFingerprintsAndShapeCapabilities() throws Exception {
        // like the original: not advertised unless asked for
        String off = capabilityLine(false);
        assertFalse(off.contains("heads_fingerprints"), off);
        assertFalse(off.contains("exp-shape-1"), off);
        assertFalse(off.contains("exp-narrow-1"), off);
        Files.writeString(repo.resolve(".hg/hgrc"),
                "[experimental]\ndisco.heads-fingerprints = true\nadvertise-shapes = true\nnarrow = true\n");
        String on = capabilityLine(false);
        assertTrue(on.contains(" heads_fingerprints"), on);
        assertTrue(on.contains(" exp-shape-1"), on);
        assertTrue(on.contains(" exp-narrow-1"), on);
    }

    // ───────────────────────── streaming clone ─────────────────────────

    /** A parsed stream_out answer: the leading status line plus name -> file content. */
    private record Stream(String status, String counts, java.util.Map<String, byte[]> files) {
    }

    private static Stream parseStream(byte[] raw) {
        int pos = 0;
        int nl = indexOf(raw, (byte) '\n', pos);
        String status = new String(raw, pos, nl - pos, StandardCharsets.US_ASCII);
        pos = nl + 1;
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>(); // keeps the order the files were sent in
        if (!status.equals("0")) {
            return new Stream(status, "", files);
        }
        nl = indexOf(raw, (byte) '\n', pos);
        String counts = new String(raw, pos, nl - pos, StandardCharsets.US_ASCII);
        pos = nl + 1;
        while (pos < raw.length) {
            int nul = indexOf(raw, (byte) 0, pos);
            String name = new String(raw, pos, nul - pos, StandardCharsets.ISO_8859_1);
            int eol = indexOf(raw, (byte) '\n', nul + 1);
            int size = Integer.parseInt(new String(raw, nul + 1, eol - nul - 1, StandardCharsets.US_ASCII));
            files.put(name, java.util.Arrays.copyOfRange(raw, eol + 1, eol + 1 + size));
            pos = eol + 1 + size;
        }
        return new Stream(status, counts, files);
    }

    private static int indexOf(byte[] b, byte v, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == v) {
                return i;
            }
        }
        throw new IllegalStateException("byte " + v + " not found after " + from);
    }

    private static java.util.Set<String> streamTokens(String capabilities) {
        java.util.Set<String> tokens = new java.util.TreeSet<>();
        for (String t : capabilities.replace("\\n", " ").split("\\s+")) {
            if (t.startsWith("stream")) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private String capabilityLine(boolean real) throws Exception {
        String shown = show(ask(real, "capabilities"));
        return shown.substring(shown.indexOf("\\n") + 2);
    }

    @Test
    @DisplayName("stream_out 이 원본과 같은 파일 집합과 내용을 같은 형식으로 보낸다")
    void streamOutSendsTheSameFiles() throws Exception {
        Stream real = parseStream(ask(true, "stream_out"));
        Stream mine = parseStream(ask(false, "stream_out"));
        assertEquals("0", real.status());
        assertEquals(real.status(), mine.status());
        assertEquals(real.counts(), mine.counts(), "file count and byte count line");
        assertEquals(new java.util.ArrayList<>(real.files().keySet()), new java.util.ArrayList<>(mine.files().keySet()),
                "the same store files, under the same wire names, in the same order");
        for (String name : real.files().keySet()) {
            assertArrayEquals(real.files().get(name), mine.files().get(name), name);
        }
    }

    /** A repository with several files: sub-directories, upper-case and long names that the store encodes. */
    private void addManyFiles() throws Exception {
        for (String name : new String[]{"src/Main.java", "src/util/Helper.java", "docs/README.txt", "Zeta.txt", "alpha.txt",
                "dir with space/file.txt", "a/very/deep/directory/structure/with/a/rather/long/file/name/to/force/encoding.txt"}) {
            Path file = repo.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content of " + name + "\n");
            LocalHg.hgIn(tmp, "server", "add", name);
        }
        LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "many files");
        Files.writeString(repo.resolve("src/Main.java"), "changed\n");
        LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "second");
    }

    @Test
    @DisplayName("stream_out 의 파일 순서가 여러 파일(하위 디렉터리, 대문자, 긴 이름) 저장소에서도 원본과 같다")
    void streamOutOrderWithManyFiles() throws Exception {
        addManyFiles();
        Stream real = parseStream(ask(true, "stream_out"));
        Stream mine = parseStream(ask(false, "stream_out"));
        assertEquals(real.counts(), mine.counts());
        assertEquals(new java.util.ArrayList<>(real.files().keySet()), new java.util.ArrayList<>(mine.files().keySet()));
        for (String name : real.files().keySet()) {
            assertArrayEquals(real.files().get(name), mine.files().get(name), name);
        }
    }

    /** The {@code stream2} part of a getbundle answer: its parameters and the files, in order, from {@code s <uvarint> <uvarint> name data}. */
    private record Stream2(java.util.Map<String, String> params, java.util.List<String> names, java.util.Map<String, byte[]> files) {
    }

    private Stream2 stream2(boolean real) throws Exception {
        String bundlecaps = "HG20,bundle2=" + java.net.URLEncoder.encode(B2_BLOB + "\nstream=v2", StandardCharsets.UTF_8).replace("+", "%20");
        byte[] raw = ask(real, "getbundle", "*heads=" + n2 + "&common=" + "0".repeat(40) + "&bundlecaps=" + bundlecaps + "&stream=True&cg=0");
        for (io.github.search5.hg4j.bundle.Bundle2.Part part : io.github.search5.hg4j.bundle.Bundle2.read(raw)) {
            if (!part.type.equals("stream2")) {
                continue;
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(part.payload);
            while (b.hasRemaining()) {
                char source = (char) b.get(); // 's' store, 'c' cache
                long nameLength = uvarint(b);
                long dataLength = uvarint(b);
                byte[] name = new byte[(int) nameLength];
                b.get(name);
                byte[] data = new byte[(int) dataLength];
                b.get(data);
                String n = (source == 'c' ? "cache/" : "") + new String(name, StandardCharsets.ISO_8859_1);
                names.add(n);
                files.put(n, data);
            }
            return new Stream2(part.params, names, files);
        }
        return fail("no stream2 part in the answer");
    }

    private static long uvarint(java.nio.ByteBuffer b) {
        long value = 0;
        int shift = 0;
        while (true) {
            int next = b.get() & 0xFF;
            value |= (long) (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    @Test
    @DisplayName("stream2 부품의 파라미터, 파일 순서, 내용이 원본과 같다 (cache/ 파일 제외)")
    void stream2MatchesTheOriginal() throws Exception {
        addManyFiles();
        Stream2 real = stream2(true);
        Stream2 mine = stream2(false);
        // the original also copies cache/ files (branch and tag caches); hg4j does not send them
        real.names().removeIf(n -> n.startsWith("cache/"));
        assertEquals(real.params().get("requirements"), mine.params().get("requirements"));
        assertEquals(real.names(), mine.names(), "same files in the same order");
        for (String name : real.names()) {
            assertArrayEquals(real.files().get(name), mine.files().get(name), name);
        }
    }

    @Test
    @DisplayName("비게시 저장소의 stream2 가 phaseroots 를 원본과 같은 자리(데이터 파일 뒤, manifest 앞)에 보낸다")
    void stream2WithPhaseroots() throws Exception {
        addManyFiles();
        Files.writeString(repo.resolve(".hg/hgrc"), "[phases]\npublish = false\n");
        Stream2 real = stream2(true);
        Stream2 mine = stream2(false);
        real.names().removeIf(n -> n.startsWith("cache/"));
        assertTrue(real.names().contains("phaseroots"), real.names().toString());
        assertEquals(real.names(), mine.names());
        assertArrayEquals(real.files().get("phaseroots"), mine.files().get("phaseroots"));
    }

    @Test
    @DisplayName("stream / streamreqs capability 가 원본과 같다")
    void streamCapabilitiesMatchTheOriginal() throws Exception {
        java.util.Set<String> real = streamTokens(capabilityLine(true));
        assertFalse(real.isEmpty(), "the original offers a stream for this repository");
        assertEquals(real, streamTokens(capabilityLine(false)));
    }

    @Test
    @DisplayName("server.uncompressed=false 면 둘 다 stream 을 광고하지 않고 1 로 응답한다, preferuncompressed 는 stream-preferred 를 더한다")
    void serverConfigGatesStreaming() throws Exception {
        Files.writeString(repo.resolve(".hg/hgrc"), "[server]\nuncompressed = false\n");
        assertSameAnswer("stream_out");
        assertEquals(java.util.Set.of(), streamTokens(capabilityLine(true)));
        assertEquals(java.util.Set.of(), streamTokens(capabilityLine(false)));

        Files.writeString(repo.resolve(".hg/hgrc"), "[server]\npreferuncompressed = true\n");
        java.util.Set<String> real = streamTokens(capabilityLine(true));
        assertTrue(real.contains("stream-preferred"), real.toString());
        assertEquals(real, streamTokens(capabilityLine(false)));
    }

    @Test
    @DisplayName("secret 변경셋이 있으면 둘 다 1 로 응답하고, uncompressedallowsecret 이면 둘 다 보낸다")
    void secretChangesetsBlockStreamingUnlessAllowed() throws Exception {
        LocalHg.hgIn(tmp, "server", "phase", "--secret", "--force", "-r", "tip");
        assertSameAnswer("stream_out");
        assertEquals("1", parseStream(ask(false, "stream_out")).status());
        Files.writeString(repo.resolve(".hg/hgrc"), "[server]\nuncompressedallowsecret = true\n");
        assertEquals("0", parseStream(ask(true, "stream_out")).status());
        assertEquals("0", parseStream(ask(false, "stream_out")).status());
    }

    @Test
    @DisplayName("지원하지 않는 저장소 형식(exp-changelog-v2)에서는 stream 을 광고하지 않고 stream_out 은 1 로 응답한다")
    void unsupportedLayoutIsNotOffered() throws Exception {
        Path v2 = Files.createDirectories(tmp.resolve("changelogv2-repo"));
        LocalHg.hgIn(tmp, "changelogv2-repo", "init", ".", "--config",
                "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
        Files.writeString(v2.resolve("a.txt"), "x");
        LocalHg.hgIn(tmp, "changelogv2-repo", "add", "a.txt");
        LocalHg.hgIn(tmp, "changelogv2-repo", "commit", "-u", "dev", "-m", "c0");
        Path saved = repo;
        repo = v2;
        try {
            // The original CAN stream this repository (it offers the stream); hg4j deliberately does not, and says so
            // honestly instead of sending a store it has not verified. This is a documented limitation.
            assertFalse(streamTokens(capabilityLine(true)).isEmpty());
            assertEquals(java.util.Set.of(), streamTokens(capabilityLine(false)));
            assertEquals("1", parseStream(ask(false, "stream_out")).status());
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("실제 hg clone --stream 으로 hg4j 서버에서 클론하면 원본 서버에서처럼 hg verify 가 통과하고 이력이 같다")
    void realClientStreamClone() throws Exception {
        List<String> results = new ArrayList<>();
        for (boolean real : new boolean[]{true, false}) {
            Path dest = tmp.resolve("stream-" + real);
            String out = client(real, tmp, "clone", "--stream", "--ssh", ssh(real).toString(), remoteUrl(), dest.toString());
            assertTrue(out.contains("stream-cloned") || out.contains("streaming all changelog and manifest data")
                    || out.contains("transferred"), (real ? "real: " : "hg4j: ") + out);
            String verify = client(real, dest, "verify");
            assertTrue(verify.contains("checked") && !verify.toLowerCase().contains("error"), verify);
            results.add(client(real, dest, "log", "-T", "{rev}:{node|short}:{branch}:{bookmarks} ")
                    + " | " + verify.lines().filter(l -> l.startsWith("checked")).toList());
        }
        assertEquals(results.get(0), results.get(1));
    }

    // ───────────────────────── known gaps (red tests) ─────────────────────────

    @Test
    @DisplayName("heads 의 순서가 원본과 같다")
    void headsOrder() throws Exception {
        assertSameAnswer("heads");
    }

    @Test
    @DisplayName("lookup 이 북마크/태그/브랜치 이름, 숫자, 접두사, 특수 이름을 원본처럼 푼다 (clone -r <이름> 이 실패하던 원인)")
    void lookupResolvesNames() throws Exception {
        for (String key : List.of("bm1", "v1", "default", "feature", ".", "null", "tip", "0", "1", "-1", "-2", "99", "01",
                "+1", n1.substring(0, 12), n1.substring(0, 40).toUpperCase(), "tip ", "")) {
            assertSameAnswer("lookup", "key=" + key);
        }
        // ambiguous and unambiguous one-character prefixes: the exact wording of the error is not guessed at
        for (char c : "0123456789abcdef".toCharArray()) {
            assertSameAnswer("lookup", "key=" + c);
        }
    }

    @Test
    @DisplayName("빈 저장소의 lookup 이 원본과 같다")
    void lookupOnAnEmptyRepository() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty-lookup"));
        LocalHg.hgIn(tmp, "empty-lookup", "init", ".");
        Path saved = repo;
        repo = empty;
        try {
            for (String key : List.of("tip", "null", ".", "0", "-1", "default", "abc")) {
                assertSameAnswer("lookup", "key=" + key);
            }
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("listkeys namespaces 가 원본처럼 bookmarks/namespaces/phases 를 알려 준다")
    void listkeysNamespaces() throws Exception {
        assertSameAnswer("listkeys", "namespace=namespaces");
    }

    @Test
    @DisplayName("listkeys phases 가 draft 루트와 publishing 표시를 원본처럼 준다")
    void listkeysPhases() throws Exception {
        assertSameAnswer("listkeys", "namespace=phases");
    }

    @Test
    @DisplayName("listkeys bookmarks 의 줄바꿈이, 그리고 브랜치가 둘인 저장소의 branchmap 이 원본과 같다")
    void trailingNewlinesAndBranchmap() throws Exception {
        assertSameAnswer("listkeys", "namespace=bookmarks");
        assertSameAnswer("branchmap");
    }

    @Test
    @DisplayName("빈 저장소의 heads, branchmap, listkeys 가 원본과 같다")
    void emptyRepository() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        LocalHg.hgIn(tmp, "empty", "init", ".");
        Path saved = repo;
        repo = empty;
        try {
            assertSameAnswer("heads");
            assertSameAnswer("branchmap");
            assertSameAnswer("listkeys", "namespace=bookmarks");
            assertSameAnswer("listkeys", "namespace=phases");
        } finally {
            repo = saved;
        }
    }

    @Test
    @DisplayName("between 이 서로 다른 두 노드 사이를 원본처럼 이분 탐색한다")
    void betweenBisects() throws Exception {
        assertSameAnswer("between", "pairs=" + n2 + "-" + n0);
    }

    @Test
    @DisplayName("branches 명령이 원본처럼 응답한다")
    void branchesCommand() throws Exception {
        assertSameAnswer("branches", "nodes=" + n2);
    }

    /** The capability tokens, with the {@code bundle2=} blob expanded into {@code bundle2:<part>=<values>} entries. */
    private static java.util.SortedSet<String> capabilityTokens(String line) {
        java.util.SortedSet<String> tokens = new java.util.TreeSet<>();
        for (String token : line.trim().split(" ")) {
            if (token.startsWith("bundle2=")) {
                String blob = java.net.URLDecoder.decode(token.substring("bundle2=".length()), StandardCharsets.ISO_8859_1);
                for (String part : blob.split("\n")) {
                    tokens.add("bundle2:" + part);
                }
            } else {
                tokens.add(token);
            }
        }
        return tokens;
    }

    @Test
    @DisplayName("SSH capabilities 토큰이 7.2.4 원본과 정확히 같다 (hg4j 전용 토큰 없음)")
    void sshCapabilitiesMatchTheOriginal() throws Exception {
        java.util.SortedSet<String> expected = capabilityTokens(new String(ask(true, "capabilities"), StandardCharsets.ISO_8859_1)
                .replaceFirst("^\\d+\\n", ""));
        java.util.SortedSet<String> actual = capabilityTokens(new String(ask(false, "capabilities"), StandardCharsets.ISO_8859_1)
                .replaceFirst("^\\d+\\n", ""));
        java.util.SortedSet<String> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(actual);
        java.util.SortedSet<String> extra = new java.util.TreeSet<>(actual);
        extra.removeAll(expected);
        assertTrue(missing.isEmpty() && extra.isEmpty(), "hg4j does not advertise: " + missing + "; hg4j advertises extra: " + extra);
    }

    private static final String B2_BLOB = "HG20\nbookmarks\nchangegroup=01,02,03\ndigests=md5,sha1,sha512\n"
            + "error=abort,unsupportedcontent,pushraced,pushkey\nhgtagsfnodes\nlistkeys\nphases=heads\npushkey\n"
            + "remote-changegroup=http,https";

    /** Every part but the changegroup (whose deltas may legitimately differ), as {@code type|mandatory|params|payload}. */
    private java.util.List<String> sideParts(boolean real, String... extraArgs) throws Exception {
        String bundlecaps = "HG20,bundle2=" + java.net.URLEncoder.encode(B2_BLOB, StandardCharsets.UTF_8).replace("+", "%20");
        StringBuilder star = new StringBuilder("*heads=" + n2 + "&common=" + "0".repeat(40) + "&bundlecaps=" + bundlecaps);
        for (String arg : extraArgs) {
            star.append('&').append(arg);
        }
        byte[] raw = ask(real, "getbundle", star.toString());
        java.util.List<String> out = new java.util.ArrayList<>();
        for (io.github.search5.hg4j.bundle.Bundle2.Part part : io.github.search5.hg4j.bundle.Bundle2.read(raw)) {
            if (!part.type.equals("changegroup")) {
                out.add(part.type + "|" + part.mandatory + "|" + part.params + "|" + show(part.payload));
            }
        }
        return out;
    }

    @Test
    @DisplayName("getbundle 의 bookmarks, listkeys, phase-heads, hgtagsfnodes 부품이 원본과 같다 (공개 저장소)")
    void getbundleSideParts() throws Exception {
        java.util.List<String> expected = sideParts(true, "bookmarks=1", "listkeys=bookmarks,phases", "phases=1", "cg=1");
        assertFalse(expected.isEmpty());
        assertEquals(expected, sideParts(false, "bookmarks=1", "listkeys=bookmarks,phases", "phases=1", "cg=1"));
    }

    @Test
    @DisplayName("getbundle 의 phase-heads 가 비공개(non-publishing) 저장소에서도 원본과 같다")
    void getbundlePhaseHeadsOfANonPublishingRepository() throws Exception {
        Files.writeString(repo.resolve(".hg/hgrc"), "[phases]\npublish = false\n");
        LocalHg.hgIn(tmp, "server", "phase", "--public", "-r", "0");
        LocalHg.hgIn(tmp, "server", "phase", "--secret", "-r", "tip", "--force");
        for (String requested : new String[]{"phases=1", "listkeys=phases", "bookmarks=1"}) {
            assertEquals(sideParts(true, requested), sideParts(false, requested), requested);
        }
    }

    @Test
    @DisplayName("cg=0 이면 changegroup 부품 없이 나머지 부품만 보낸다")
    void getbundleWithoutChangegroup() throws Exception {
        assertEquals(sideParts(true, "bookmarks=1", "cg=0"), sideParts(false, "bookmarks=1", "cg=0"));
        byte[] raw = ask(false, "getbundle", "*heads=" + n2 + "&common=" + "0".repeat(40) + "&bundlecaps=HG20,bundle2="
                + java.net.URLEncoder.encode(B2_BLOB, StandardCharsets.UTF_8).replace("+", "%20") + "&cg=0");
        for (io.github.search5.hg4j.bundle.Bundle2.Part part : io.github.search5.hg4j.bundle.Bundle2.read(raw)) {
            assertNotEquals("changegroup", part.type);
        }
    }

    @Test
    @DisplayName("batch 가 여러 명령의 응답을 원본처럼 ';' 로 이어 준다")
    void batchCombinesAnswers() throws Exception {
        assertSameAnswer("batch", "cmds=heads ;lookup key=tip;known nodes=" + n0 + ";listkeys namespace=bookmarks", "*");
        assertSameAnswer("batch", "cmds=branchmap ;between pairs=" + n2 + "-" + n0, "*");
    }

    /** Like {@link #assertSameAnswer} but for answers that are binary: names the first differing byte. */
    private void assertSameBytes(String cmd, String... args) throws Exception {
        byte[] expected = ask(true, cmd, args);
        byte[] actual = ask(false, cmd, args);
        int n = Math.min(expected.length, actual.length);
        int at = 0;
        while (at < n && expected[at] == actual[at]) {
            at++;
        }
        if (at == n && expected.length == actual.length) {
            return;
        }
        int from = Math.max(0, at - 24);
        fail(cmd + " " + String.join(" ", args) + ": first difference at byte " + at + " (real " + expected.length
                + " bytes, hg4j " + actual.length + " bytes)\n real: " + hex(expected, from, at + 40)
                + "\n hg4j: " + hex(actual, from, at + 40));
    }

    private static String hex(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(to, b.length); i++) {
            sb.append(String.format("%02x", b[i])).append(i == from + 24 ? " | " : " ");
        }
        return sb.toString();
    }

    /**
     * A changegroup is not compared byte for byte: a delta may legitimately be encoded differently (the real server reuses the
     * delta stored in its revlog, hg4j computes one). What must hold is that the real hg client applies both into a fresh
     * repository, that {@code hg verify} accepts the result and that the history and the file contents are the same.
     */
    private void assertSameChangegroup(String name, String cmd, String... args) throws Exception {
        String[] results = new String[2];
        for (int k = 0; k < 2; k++) {
            byte[] stream = ask(k == 0, cmd, args);
            Path file = tmp.resolve(name + "-" + k + ".hg");
            ByteArrayOutputStream bundle = new ByteArrayOutputStream();
            bundle.writeBytes("HG10UN".getBytes(StandardCharsets.US_ASCII));
            bundle.writeBytes(stream);
            Files.write(file, bundle.toByteArray());
            String dir = name + "-" + k;
            LocalHg.hgIn(tmp, ".", "init", dir);
            LocalHg.hgIn(tmp, dir, "unbundle", file.toString());
            String verify = LocalHg.hgIn(tmp, dir, "verify");
            assertTrue(verify.contains("checking files"), verify);
            results[k] = LocalHg.hgIn(tmp, dir, "log", "-T", "{rev} {node} {branch} {p1node} {p2node}\n")
                    + LocalHg.hgIn(tmp, dir, "debugdata", "-m", "0")
                    + LocalHg.hgIn(tmp, dir, "manifest", "--debug");
        }
        assertEquals(results[0], results[1], name);
    }

    @Test
    @DisplayName("changegroup 과 changegroupsubset 을 실제 hg 로 적용하면 원본과 같은 저장소가 된다")
    void changegroupAppliesToTheSameRepository() throws Exception {
        assertSameChangegroup("cg-all", "changegroup", "roots=" + "0".repeat(40));
        assertSameChangegroup("cg-subset", "changegroupsubset", "bases=" + "0".repeat(40), "heads=" + n1);
    }

    /** pushkey changes the repository, so the bookmark file is put back between the real server and hg4j. */
    private void assertSamePushkey(String... args) throws Exception {
        Path file = repo.resolve(".hg/bookmarks");
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        byte[] expected = ask(true, "pushkey", args);
        restore(file, before);
        byte[] actual = ask(false, "pushkey", args);
        restore(file, before);
        assertEquals(show(expected), show(actual), "pushkey " + String.join(" ", args));
    }

    private static void restore(Path file, byte[] content) throws IOException {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.write(file, content);
        }
    }

    @Test
    @DisplayName("pushkey 가 북마크를 만들고 옮기고 잘못된 요청을 거절하는 응답이 원본과 같다")
    void pushkeyAnswers() throws Exception {
        assertSamePushkey("namespace=bookmarks", "key=newbm", "old=", "new=" + n1);
        assertSamePushkey("namespace=bookmarks", "key=bm1", "old=" + n1, "new=" + n2);
        assertSamePushkey("namespace=bookmarks", "key=bm1", "old=" + n0, "new=" + n2);
        assertSamePushkey("namespace=bookmarks", "key=nobm", "old=" + n0, "new=");
        assertSamePushkey("namespace=nosuchnamespace", "key=k", "old=", "new=v");
    }

    // ───────────────────────── unbundle: a whole recorded push session ─────────────────────────

    private void copyTree(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    /**
     * Records what a REAL hg client sends during {@code hg <clientArgs>} against a real server, then replays those exact bytes
     * to a pristine copy of the server under both the real hg and hg4j and compares everything each of them answers
     * (hello, discovery, listkeys, the unbundle reply, ...). {@code beforeReplay} may change both copies identically first,
     * e.g. to make the push lose a race.
     */
    private void assertSameSession(String name, java.util.function.Consumer<Path> clientWork, String[] clientArgs,
                                   java.util.function.Consumer<Path> beforeReplay) throws Exception {
        assertSameSession(name, clientWork, clientArgs, beforeReplay, server -> { });
    }

    private void assertSameSession(String name, java.util.function.Consumer<Path> clientWork, String[] clientArgs,
                                   java.util.function.Consumer<Path> beforeReplay,
                                   java.util.function.Consumer<HgSshWireServer> mineSetup) throws Exception {
        assertSameSession(repo, name, clientWork, clientArgs, beforeReplay, mineSetup);
    }

    private void assertSameSession(Path baseRepo, String name, java.util.function.Consumer<Path> clientWork, String[] clientArgs,
                                   java.util.function.Consumer<Path> beforeReplay,
                                   java.util.function.Consumer<HgSshWireServer> mineSetup) throws Exception {
        Path recordServer = tmp.resolve(name + "-record");
        Path realServer = tmp.resolve(name + "-real");
        Path mineServer = tmp.resolve(name + "-mine");
        copyTree(baseRepo, recordServer);
        copyTree(baseRepo, realServer);
        copyTree(baseRepo, mineServer);
        Path client = tmp.resolve(name + "-client");
        LocalHg.hgIn(tmp, ".", "clone", baseRepo.toString(), client.toString());
        clientWork.accept(client);

        Path capture = tmp.resolve(name + ".session");
        Path tee = script("ssh-tee-" + name, "tee \"$CAP\" | \"" + LocalHg.executable() + "\" -R \"$SRV\" serve --stdio");
        // `hg push --ssh` is a subcommand option, so the command name comes first
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(clientArgs[0]);
        command.add("--ssh");
        command.add(tee.toString());
        command.addAll(java.util.List.of(clientArgs).subList(1, clientArgs.length));
        command.add("ssh://localhost/" + recordServer.toString().substring(1));
        ProcessBuilder pb = LocalHg.processBuilder(client, command.toArray(new String[0]));
        pb.environment().put("CAP", capture.toString());
        pb.environment().put("SRV", recordServer.toString());
        pb.redirectErrorStream(true);
        Process recorder = pb.start();
        recorder.getInputStream().readAllBytes();
        assertTrue(recorder.waitFor(120, TimeUnit.SECONDS));
        assertTrue(Files.size(capture) > 0, "nothing was recorded");

        beforeReplay.accept(realServer);
        beforeReplay.accept(mineServer);

        ProcessBuilder replay = LocalHg.processBuilder(realServer, "-R", realServer.toString(), "serve", "--stdio");
        replay.redirectInput(capture.toFile());
        replay.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = replay.start();
        byte[] expected = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.io.InputStream in = Files.newInputStream(capture)) {
            HgSshWireServer mine = new HgSshWireServer(new HgRepository(mineServer.toFile()));
            mineSetup.accept(mine);
            mine.handleConnection(in, out);
        }
        byte[] actual = out.toByteArray();

        int n = Math.min(expected.length, actual.length);
        int at = 0;
        while (at < n && expected[at] == actual[at]) {
            at++;
        }
        if (at != n || expected.length != actual.length) {
            fail(name + ": responses differ at byte " + at + " (real " + expected.length + " bytes, hg4j " + actual.length + " bytes)\n"
                    + " real: " + hex(expected, Math.max(0, at - 40), at + 120) + "\n hg4j: " + hex(actual, Math.max(0, at - 40), at + 120)
                    + "\n real text: " + show(java.util.Arrays.copyOfRange(expected, Math.max(0, at - 40), Math.min(expected.length, at + 160)))
                    + "\n hg4j text: " + show(java.util.Arrays.copyOfRange(actual, Math.max(0, at - 40), Math.min(actual.length, at + 160))));
        }
        assertEquals(LocalHg.hgIn(tmp, realServer.getFileName().toString(), "log", "-T", "{node} {phase} {bookmarks}\n"),
                LocalHg.hgIn(tmp, mineServer.getFileName().toString(), "log", "-T", "{node} {phase} {bookmarks}\n"),
                "state of the server after the session");
    }

    private void commitOnClient(Path client, String text) {
        try {
            Files.writeString(client.resolve("a.txt"), text);
            LocalHg.hgIn(tmp, client.getFileName().toString(), "commit", "-A", "-u", "dev", "-m", "client " + text.trim());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("unbundle: 실제 클라이언트가 보낸 push 전체 세션의 응답 바이트가 원본과 같다")
    void unbundleSessionOfAPlainPush() throws Exception {
        assertSameSession("plain", client -> commitOnClient(client, "pushed\n"), new String[]{"push"}, server -> { });
    }

    @Test
    @DisplayName("unbundle: 북마크를 함께 push 하는 세션의 응답 바이트가 원본과 같다")
    void unbundleSessionWithABookmark() throws Exception {
        assertSameSession("bookmark", client -> {
            commitOnClient(client, "pushed\n");
            try {
                LocalHg.hgIn(tmp, client.getFileName().toString(), "bookmark", "pushedbm");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, new String[]{"push", "-B", "pushedbm"}, server -> { });
    }

    private void hgOn(Path client, String... args) {
        try {
            LocalHg.hgIn(tmp, client.getFileName().toString(), args);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("unbundle: 새 head 를 강제로 만드는 push 세션의 응답 바이트가 원본과 같다")
    void unbundleSessionCreatingANewHead() throws Exception {
        assertSameSession("newhead", client -> {
            hgOn(client, "update", "-r", "0");
            commitOnClient(client, "a branch off the root\n");
        }, new String[]{"push", "-f"}, server -> { });
    }

    @Test
    @DisplayName("unbundle: 북마크 이동과 삭제 세션의 응답 바이트가 원본과 같다")
    void unbundleSessionMovingAndDeletingBookmarks() throws Exception {
        assertSameSession("movebm", client -> {
            hgOn(client, "bookmark", "-f", "bm1", "-r", "tip");
            hgOn(client, "bookmark", "gone", "-r", "0");
        }, new String[]{"push", "-B", "bm1", "-B", "gone"}, server -> { });
        assertSameSession("delbm", client -> hgOn(client, "bookmark", "-d", "bm1"), new String[]{"push", "-B", "bm1"}, server -> { });
    }

    @Test
    @DisplayName("unbundle: 비게시 서버에서 phase 를 public 으로 올리는 push 세션의 응답 바이트가 원본과 같다")
    void unbundleSessionPublishingPhases() throws Exception {
        Files.writeString(repo.resolve(".hg/hgrc"), "[phases]\npublish = false\n");
        assertSameSession("phases", client -> {
            commitOnClient(client, "to be published\n");
            hgOn(client, "phase", "--public", "-r", "tip");
        }, new String[]{"push"}, server -> { });
    }

    @Test
    @DisplayName("unbundle: head 가 줄어드는 병합 push 와 브랜치를 닫는 push 의 reply:changegroup 값이 원본과 같다")
    void unbundleSessionsThatRemoveHeads() throws Exception {
        assertSameSession("merge", client -> {
            hgOn(client, "update", "-r", "feature");
            hgOn(client, "merge", "-r", "default", "--tool", "internal:local");
            hgOn(client, "commit", "-u", "dev", "-m", "merge");
        }, new String[]{"push"}, server -> { });
        assertSameSession("closebranch", client -> {
            hgOn(client, "update", "-r", "feature");
            hgOn(client, "commit", "-u", "dev", "--close-branch", "-m", "close feature");
        }, new String[]{"push"}, server -> { });
    }

    @Test
    @DisplayName("unbundle: 번들1 push 세션(성공, 새 head, 경합 거절)의 응답 바이트가 원본과 같다")
    void unbundleSessionsOfABundle1Push() throws Exception {
        String[] bundle1 = {"push", "--config", "devel.legacy.exchange=bundle1"};
        assertSameSession("b1plain", client -> commitOnClient(client, "pushed\n"), bundle1, server -> { });
        assertSameSession("b1newhead", client -> {
            hgOn(client, "update", "-r", "0");
            commitOnClient(client, "a branch off the root\n");
        }, new String[]{"push", "-f", "--config", "devel.legacy.exchange=bundle1"}, server -> { });
        assertSameSession("b1race", client -> commitOnClient(client, "pushed\n"), bundle1, server -> {
            try {
                Files.writeString(server.resolve("a.txt"), "someone else\n");
                LocalHg.hgIn(tmp, server.getFileName().toString(), "commit", "-u", "other", "-d", "2020-01-01 00:00 +0000", "-m", "raced");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** A python hook of the real server that always fails; hg4j gets the equivalent callback registered in code. */
    private Path failingHook() throws IOException {
        Path hook = tmp.resolve("rejecting_hook.py");
        Files.writeString(hook, "def reject(ui, repo, hooktype, **kwargs):\n    return True\n");
        return hook;
    }

    private java.util.function.Consumer<Path> realHook(String hookName) throws IOException {
        Path hook = failingHook();
        return server -> {
            try {
                Files.writeString(server.resolve(".hg/hgrc"), "[hooks]\n" + hookName + " = python:" + hook + ":reject\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Test
    @DisplayName("서버 훅(prechangegroup)이 거절하는 push 세션(번들2, 번들1)의 응답 바이트가 원본과 같다")
    void unbundleSessionsRejectedByAHook() throws Exception {
        assertSameSession("hook-b2", client -> commitOnClient(client, "pushed\n"), new String[]{"push"},
                realHook("prechangegroup"), server -> server.registerPreChangegroupHook(ctx -> false));
        assertSameSession("hook-b1", client -> commitOnClient(client, "pushed\n"),
                new String[]{"push", "--config", "devel.legacy.exchange=bundle1"},
                realHook("prechangegroup"), server -> server.registerPreChangegroupHook(ctx -> false));
    }

    @Test
    @DisplayName("서버 훅(prepushkey)이 거절하는 북마크 push 세션의 응답이 원본과 같다")
    void unbundleSessionRejectedByAPushkeyHook() throws Exception {
        assertSameSession("hook-pushkey", client -> {
            commitOnClient(client, "pushed\n");
            hgOn(client, "bookmark", "hooked");
        }, new String[]{"push", "-B", "hooked"}, realHook("prepushkey"), server -> server.registerPrePushkeyHook(ctx -> false));
    }

    @Test
    @DisplayName("unbundle: 빈 저장소에 처음 push 하는 세션(번들2, 번들1)의 응답 바이트가 원본과 같다")
    void unbundleSessionsIntoAnEmptyRepository() throws Exception {
        Path empty = tmp.resolve("empty-origin");
        LocalHg.hgIn(tmp, ".", "init", empty.toString());
        assertSameSession(empty, "empty-b2", client -> commitOnClient(client, "first\n"), new String[]{"push"}, server -> { }, server -> { });
        assertSameSession(empty, "empty-b1", client -> commitOnClient(client, "first\n"),
                new String[]{"push", "--config", "devel.legacy.exchange=bundle1"}, server -> { }, server -> { });
    }

    @Test
    @DisplayName("unbundle: 경합으로 거절되는 세션의 응답 바이트(error:pushraced)가 원본과 같다")
    void unbundleSessionThatLosesARace() throws Exception {
        assertSameSession("race", client -> commitOnClient(client, "pushed\n"), new String[]{"push"}, server -> {
            try {
                Files.writeString(server.resolve("a.txt"), "someone else\n");
                LocalHg.hgIn(tmp, server.getFileName().toString(), "commit", "-u", "other", "-d", "2020-01-01 00:00 +0000", "-m", "raced");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("지원하지 않는 명령에는 원본처럼 빈 응답을 주고 연결을 유지한다")
    void unavailableCommandGetsEmptyAnswer() throws Exception {
        assertSameAnswer("thiscommanddoesnotexist");
        // the session goes on: a following real command is still answered (here: two commands in one session)
        byte[] real = askTwo(true, "nosuchcommand", "heads");
        byte[] mine = askTwo(false, "nosuchcommand", "heads");
        assertEquals(show(real), show(mine));
    }

    private byte[] askTwo(boolean real, String first, String second) throws Exception {
        byte[] r1 = request(first);
        byte[] r2 = request(second);
        // two requests, then the terminating empty line (request() already appended one: drop it from the first)
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(r1, 0, r1.length - 1);
        both.write(r2);
        if (real) {
            ProcessBuilder pb = LocalHg.processBuilder(repo, "-R", repo.toString(), "serve", "--stdio");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(both.toByteArray());
            }
            byte[] out = p.getInputStream().readAllBytes();
            assertTrue(p.waitFor(30, TimeUnit.SECONDS));
            return out;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new HgSshWireServer(new HgRepository(repo.toFile())).handleConnection(new ByteArrayInputStream(both.toByteArray()), out);
        return out.toByteArray();
    }

    // ───────────────────────── what a real hg client sees ─────────────────────────

    private Path script(String name, String body) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    private Path ssh(boolean real) throws Exception {
        if (real) {
            return script("ssh-real", "exec \"" + LocalHg.executable() + "\" -R \"$SRV\" serve --stdio");
        }
        String java = System.getProperty("java.home") + "/bin/java";
        return script("ssh-hg4j", "exec \"" + java + "\" -cp \"" + System.getProperty("java.class.path")
                + "\" io.github.search5.hg4j.transport.WireServerStdioMain \"$SRV\"");
    }

    /** Runs the real hg client with {@code --ssh} pointing at a script that serves either server. */
    private String client(boolean real, Path cwd, String... args) throws Exception {
        ProcessBuilder pb = LocalHg.processBuilder(cwd, args);
        pb.environment().put("SRV", repo.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        return out.trim();
    }

    private String remoteUrl() {
        return "ssh://localhost/" + repo.toString().substring(1);
    }

    /**
     * {@code hg clone [options]} from the given server; returns the clone's "rev:bookmarks:tags" listing (which
     * revisions arrived) or the error. Phases are compared separately ({@link #clonePhases}).
     */
    private String cloneState(boolean real, String name, String... options) throws Exception {
        return cloneListing(real, name, "{rev}:{bookmarks}:{tags} ", options);
    }

    /** Like {@link #cloneState} but lists "rev:phase". */
    private String clonePhases(boolean real, String name, String... options) throws Exception {
        return cloneListing(real, name, "{rev}:{phase} ", options);
    }

    private String cloneListing(boolean real, String name, String template, String... options) throws Exception {
        Path dest = tmp.resolve("clone-" + name + (real ? "-real" : "-hg4j"));
        List<String> args = new ArrayList<>(List.of("clone", "--ssh", ssh(real).toString()));
        args.addAll(List.of(options));
        args.add(remoteUrl());
        args.add(dest.toString());
        String out = client(real, tmp, args.toArray(new String[0]));
        if (!Files.isDirectory(dest.resolve(".hg"))) {
            return "NO CLONE: " + out.lines().filter(l -> l.startsWith("abort")).findFirst().orElse(out);
        }
        return client(real, dest, "log", "-T", template);
    }

    @Test
    @DisplayName("기본 clone 이 성공하고 변경셋 수가 원본 서버와 같다 (단계 정보는 별도 항목)")
    void plainCloneTransfersEveryChangeset() throws Exception {
        String real = cloneState(true, "plain");
        String mine = cloneState(false, "plain");
        assertEquals(real.split(" ").length, mine.split(" ").length, "real: " + real + " / hg4j: " + mine);
    }

    @Test
    @DisplayName("hg4j 서버에서 clone 한 변경셋이 원본 서버에서처럼 public 이다")
    void cloneIsPublic() throws Exception {
        assertEquals(clonePhases(true, "phase"), clonePhases(false, "phase"));
        assertEquals(clonePhases(true, "phase-r", "-r", "1"), clonePhases(false, "phase-r", "-r", "1"));
    }

    @Test
    @DisplayName("clone -r 북마크/태그/브랜치 이름이 원본 서버에서처럼 동작한다")
    void cloneByName() throws Exception {
        for (String name : List.of("bm1", "v1", "default", "feature")) {
            assertEquals(cloneState(true, "r-" + name, "-r", name), cloneState(false, "r-" + name, "-r", name), name);
        }
    }

    @Test
    @DisplayName("clone -r <리비전> 이 요청한 범위의 변경셋만 받는다")
    void cloneRevisionTransfersOnlyThatHistory() throws Exception {
        assertEquals(cloneState(true, "r1", "-r", "1"), cloneState(false, "r1", "-r", "1"));
    }

    @Test
    @DisplayName("가지가 갈라진 이력에서 clone -r 후 pull -r, pull 을 해도 매번 원본 서버와 같은 변경셋만 받는다 (common/heads 집합)")
    void incrementalPullsOnBranchedHistory() throws Exception {
        List<String> steps = new ArrayList<>();
        for (boolean real : new boolean[]{true, false}) {
            Path dest = tmp.resolve("pulls-" + real);
            String ssh = ssh(real).toString();
            StringBuilder seen = new StringBuilder();
            client(real, tmp, "clone", "--ssh", ssh, "-r", "2", remoteUrl(), dest.toString());
            seen.append("after clone -r 2: ").append(client(real, dest, "log", "-T", "{rev}:{branch} ")).append('\n');
            client(real, dest, "pull", "--ssh", ssh, "-r", "4", remoteUrl()); // the head on branch 'feature'
            seen.append("after pull -r 4: ").append(client(real, dest, "log", "-T", "{rev}:{branch} ")).append('\n');
            client(real, dest, "pull", "--ssh", ssh, remoteUrl());
            seen.append("after pull: ").append(client(real, dest, "log", "-T", "{rev}:{branch} ")).append('\n');
            seen.append("verify: ").append(client(real, dest, "verify").lines().filter(l -> l.startsWith("checked") || l.contains("error")).toList());
            steps.add(seen.toString());
        }
        assertEquals(steps.get(0), steps.get(1));
    }

    @Test
    @DisplayName("incoming -B / outgoing -B 가 원본 서버에서처럼 북마크를 비교한다")
    void incomingBookmarks() throws Exception {
        Map<Boolean, String> out = new java.util.HashMap<>();
        for (boolean real : new boolean[]{true, false}) {
            Path dest = tmp.resolve("inc-" + real);
            client(real, tmp, "clone", repo.toString(), dest.toString());
            LocalHg.hgIn(tmp, "inc-" + real, "bookmark", "-d", "bm1");
            out.put(real, client(real, dest, "incoming", "-B", "--ssh", ssh(real).toString(), remoteUrl())
                    .replace(repo.toString().substring(1), "<repo>"));
        }
        assertEquals(out.get(true), out.get(false));
    }
}
