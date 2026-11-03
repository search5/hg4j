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
