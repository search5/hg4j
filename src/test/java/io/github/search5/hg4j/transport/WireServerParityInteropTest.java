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

    // ───────────────────────── known gaps (red tests) ─────────────────────────

    @Test
    @Disabled(GAPS + "B9 with several heads, heads lists them in another order")
    @DisplayName("heads 의 순서가 원본과 같다")
    void headsOrder() throws Exception {
        assertSameAnswer("heads");
    }

    @Test
    @Disabled(GAPS + "B1 lookup cannot resolve bookmark, tag and branch names")
    @DisplayName("lookup 이 북마크/태그/브랜치 이름을 원본처럼 푼다 (clone -r <이름> 이 실패하는 원인)")
    void lookupResolvesNames() throws Exception {
        for (String key : List.of("bm1", "v1", "default", "feature", ".", "null", "1", n1.substring(0, 12))) {
            assertSameAnswer("lookup", "key=" + key);
        }
    }

    @Test
    @Disabled(GAPS + "B2 listkeys namespaces is empty")
    @DisplayName("listkeys namespaces 가 원본처럼 bookmarks/namespaces/phases 를 알려 준다")
    void listkeysNamespaces() throws Exception {
        assertSameAnswer("listkeys", "namespace=namespaces");
    }

    @Test
    @Disabled(GAPS + "B3 listkeys phases lacks 'publishing' and lists every changeset")
    @DisplayName("listkeys phases 가 draft 루트와 publishing 표시를 원본처럼 준다")
    void listkeysPhases() throws Exception {
        assertSameAnswer("listkeys", "namespace=phases");
    }

    @Test
    @Disabled(GAPS + "B4 bookmark listing ends with a newline the original does not send")
    @DisplayName("listkeys bookmarks / branchmap 의 줄바꿈이 원본과 같다")
    void trailingNewlines() throws Exception {
        assertSameAnswer("listkeys", "namespace=bookmarks");
        assertSameAnswer("branchmap");
    }

    @Test
    @Disabled(GAPS + "B5 between returns no intermediate nodes for a real pair")
    @DisplayName("between 이 서로 다른 두 노드 사이를 원본처럼 이분 탐색한다")
    void betweenBisects() throws Exception {
        assertSameAnswer("between", "pairs=" + n2 + "-" + n0);
    }

    @Test
    @Disabled(GAPS + "B6 branches is not implemented")
    @DisplayName("branches 명령이 원본처럼 응답한다")
    void branchesCommand() throws Exception {
        assertSameAnswer("branches", "nodes=" + n2);
    }

    @Test
    @Disabled(GAPS + "B7 an unavailable command gets an error text instead of an empty answer")
    @DisplayName("지원하지 않는 명령에는 원본처럼 빈 응답을 준다")
    void unavailableCommandGetsEmptyAnswer() throws Exception {
        assertSameAnswer("thiscommanddoesnotexist");
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
    @Disabled(GAPS + "B3 a clone from hg4j marks every changeset draft; a publishing server makes them public")
    @DisplayName("hg4j 서버에서 clone 한 변경셋이 원본 서버에서처럼 public 이다")
    void cloneIsPublic() throws Exception {
        assertEquals(clonePhases(true, "phase"), clonePhases(false, "phase"));
        assertEquals(clonePhases(true, "phase-r", "-r", "1"), clonePhases(false, "phase-r", "-r", "1"));
    }

    @Test
    @Disabled(GAPS + "B1 clone -r <name> aborts with 'unknown revision'")
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
    @Disabled(GAPS + "B2 incoming/outgoing -B report 'remote doesn't support bookmarks'")
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
