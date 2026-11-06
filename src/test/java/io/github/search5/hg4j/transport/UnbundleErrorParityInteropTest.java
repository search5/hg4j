package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-made bundle2 pushes that go wrong in different ways, sent to a real hg server and to hg4j's: the answers (the error parts
 * and their wording) and the state each server is left in must be the same. A real client never sends most of these, which is
 * why they are built here part by part.
 */
@Tag("interop")
class UnbundleErrorParityInteropTest {

    @TempDir
    Path tmp;
    private ParitySupport.Fixture fx;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        fx = ParitySupport.createRepo(tmp);
    }

    private static byte[] node(String hex) {
        return io.github.search5.hg4j.util.NodeIdUtil.fromHex(hex);
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** One {@code bookmarks}/{@code check:bookmarks} entry: {@code >20sH} and the name; null means "no such bookmark" (20 x 0xff). */
    private static byte[] bookmark(String name, String nodeHex) {
        byte[] n = nodeHex == null ? new byte[20] : node(nodeHex);
        if (nodeHex == null) {
            java.util.Arrays.fill(n, (byte) 0xFF);
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return join(n, ByteBuffer.allocate(2).putShort((short) nameBytes.length).array(), nameBytes);
    }

    private static byte[] phaseEntry(int phase, String nodeHex) {
        return join(ByteBuffer.allocate(4).putInt(phase).array(), node(nodeHex));
    }

    private static final byte[] REPLYCAPS = "error=abort,unsupportedcontent,pushraced,pushkey\npushkey\nbookmarks\nphases=heads"
            .getBytes(StandardCharsets.US_ASCII);

    private static Bundle2.Writer bundle() {
        Bundle2.Writer w = new Bundle2.Writer();
        w.addPart("replycaps", false, Map.of(), Map.of(), REPLYCAPS);
        return w;
    }

    /** Sends {@code bundle} (with heads {@code force}) to a pristine copy of the fixture under each server and compares. */
    private void assertSameAnswer(String name, byte[] bundle) throws Exception {
        assertSameAnswer(name, bundle, null, server -> { });
    }

    /** With a {@code prepushkey} hook that refuses: a python hook for the real server, the equivalent callback for hg4j's. */
    private void assertSameAnswerWithRefusingHook(String name, byte[] bundle) throws Exception {
        Path hook = tmp.resolve("rejecting_hook.py");
        java.nio.file.Files.writeString(hook, "def reject(ui, repo, hooktype, **kwargs):\n    return True\n");
        assertSameAnswer(name, bundle, "[hooks]\nprepushkey = python:" + hook + ":reject\n", server -> server.registerPrePushkeyHook(ctx -> false));
    }

    private void assertSameAnswer(String name, byte[] bundle, String realHgrc, java.util.function.Consumer<HgSshWireServer> setup)
            throws Exception {
        Path real = tmp.resolve(name + "-real");
        Path mine = tmp.resolve(name + "-mine");
        ParitySupport.copyTree(fx.repo(), real);
        ParitySupport.copyTree(fx.repo(), mine);
        if (realHgrc != null) {
            java.nio.file.Files.writeString(real.resolve(".hg/hgrc"), realHgrc, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        byte[] request = ParitySupport.unbundleRequest("666f726365" /* hex("force"), what a real client sends */, bundle);
        byte[] expected = ParitySupport.sshSession(true, real, request);
        byte[] actual = ParitySupport.sshSession(false, mine, request, setup);
        int n = Math.min(expected.length, actual.length);
        int at = 0;
        while (at < n && expected[at] == actual[at]) {
            at++;
        }
        if (at != n || expected.length != actual.length) {
            fail(name + ": answers differ at byte " + at + " (real " + expected.length + " bytes, hg4j " + actual.length + " bytes)\n"
                    + " real text: " + ParitySupport.text(expected, at - 60, at + 220) + "\n"
                    + " hg4j text: " + ParitySupport.text(actual, at - 60, at + 220));
        }
        String view = "{rev} {node|short} {phase} {bookmarks}\n";
        assertEquals(LocalHg.hgIn(tmp, real.getFileName().toString(), "log", "-T", view),
                LocalHg.hgIn(tmp, mine.getFileName().toString(), "log", "-T", view), name + ": state after the push");
    }

    @Test
    @DisplayName("알 수 없는 필수 부품은 error:unsupportedcontent 로 거절된다")
    void unknownMandatoryPart() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("someunknownpart", true, new LinkedHashMap<>(Map.of("alpha", "1")), Map.of("beta", "2"), new byte[0]);
        assertSameAnswer("unknown-mandatory", w.toBytes(null));
    }

    @Test
    @DisplayName("알 수 없는 선택 부품은 건너뛴다")
    void unknownAdvisoryPart() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("someunknownpart", false, Map.of(), Map.of(), new byte[]{1, 2, 3});
        assertSameAnswer("unknown-advisory", w.toBytes(null));
    }

    @Test
    @DisplayName("분기된(@) 북마크를 bookmarks 부품으로 보내면 거절된다")
    void divergentBookmark() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("bookmarks", true, Map.of(), Map.of(), bookmark("bm1@other", fx.n2()));
        assertSameAnswer("divergent", w.toBytes(null));
    }

    @Test
    @DisplayName("bookmarks 부품이 북마크를 만들고 옮기고 지운다")
    void bookmarksPartApplies() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("bookmarks", true, Map.of(), Map.of(),
                join(bookmark("created", fx.n0()), bookmark("bm1", fx.n2()), bookmark("absent", null)));
        assertSameAnswer("bookmarks-apply", w.toBytes(null));
        Bundle2.Writer delete = bundle();
        delete.addPart("bookmarks", true, Map.of(), Map.of(), bookmark("bm1", null));
        assertSameAnswer("bookmarks-delete", delete.toBytes(null));
    }

    @Test
    @DisplayName("bookmarks 부품이 저장소에 없는 노드를 가리키면 hg4j 는 거절한다 (원본은 그대로 저장하는 의도적 차이)")
    void bookmarkToAnUnknownNodeIsRefused() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("bookmarks", true, Map.of(), Map.of(), bookmark("ghost", "ab".repeat(20)));
        Path mine = tmp.resolve("ghost-mine");
        ParitySupport.copyTree(fx.repo(), mine);
        byte[] answer = ParitySupport.sshSession(false, mine, ParitySupport.unbundleRequest("666f726365", w.toBytes(null)));
        assertTrue(new String(answer, StandardCharsets.ISO_8859_1).contains("could not update bookmark ghost"));
        assertFalse(LocalHg.hgIn(tmp, "ghost-mine", "bookmarks").contains("ghost"), "the dangling bookmark must not be stored");
    }

    @Test
    @DisplayName("필수 pushkey 부품이 실패하면 error:pushkey (클라이언트가 지원할 때)")
    void mandatoryPushkeyFailsWithTheReplyCaps() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("pushkey", true, new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n0(), "new", fx.n2())),
                Map.of(), new byte[0]);
        assertSameAnswer("pushkey-mandatory", w.toBytes(null));
    }

    @Test
    @DisplayName("prepushkey 훅이 pushkey 부품을 거절하면 필수는 error:pushkey, 선택은 reply:pushkey return=0 이다")
    void aRefusingPrepushkeyHookFailsAPushkeyPartLikeAFailedPush() throws Exception {
        Bundle2.Writer mandatory = bundle();
        mandatory.addPart("pushkey", true, new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n1(), "new", fx.n2())),
                Map.of(), new byte[0]);
        assertSameAnswerWithRefusingHook("pushkey-hook-mandatory", mandatory.toBytes(null));
        Bundle2.Writer advisory = bundle();
        advisory.addPart("pushkey", false, Map.of(), new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n1(), "new", fx.n2())),
                new byte[0]);
        assertSameAnswerWithRefusingHook("pushkey-hook-advisory", advisory.toBytes(null));
    }

    @Test
    @DisplayName("prepushkey 훅이 거절하면 실패할 필수 pushkey 보다 훅의 거절이 먼저 처리된다")
    void aRefusingPrepushkeyHookComesBeforeAFailingMandatoryPushkey() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("pushkey", true, new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n0(), "new", fx.n2())),
                Map.of(), new byte[0]);
        assertSameAnswerWithRefusingHook("pushkey-hook-first", w.toBytes(null));
    }

    @Test
    @DisplayName("replycaps 가 없으면 필수 pushkey 실패는 error:abort 가 된다")
    void mandatoryPushkeyFailsWithoutTheReplyCaps() throws Exception {
        Bundle2.Writer w = new Bundle2.Writer();
        w.addPart("pushkey", true, new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n0(), "new", fx.n2())),
                Map.of(), new byte[0]);
        assertSameAnswer("pushkey-mandatory-nocaps", w.toBytes(null));
    }

    @Test
    @DisplayName("선택 pushkey 부품이 실패하면 reply:pushkey return=0")
    void advisoryPushkeyFails() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("pushkey", false, Map.of(), new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "bm1", "old", fx.n0(), "new", fx.n2())),
                new byte[0]);
        assertSameAnswer("pushkey-advisory", w.toBytes(null));
        Bundle2.Writer ok = bundle();
        ok.addPart("pushkey", false, Map.of(), new LinkedHashMap<>(Map.of("namespace", "bookmarks", "key", "fresh", "old", "", "new", fx.n2())),
                new byte[0]);
        assertSameAnswer("pushkey-advisory-ok", ok.toBytes(null));
    }

    @Test
    @DisplayName("check:bookmarks 가 어긋나면 error:pushraced (세 가지 문구)")
    void checkBookmarks() throws Exception {
        for (Object[] c : new Object[][]{
                {"moved", bookmark("bm1", fx.n0())},
                {"missing", bookmark("nothere", fx.n0())},
                {"exists", bookmark("bm1", null)}}) {
            Bundle2.Writer w = bundle();
            w.addPart("check:bookmarks", true, Map.of(), Map.of(), (byte[]) c[1]);
            assertSameAnswer("check-bookmarks-" + c[0], w.toBytes(null));
        }
    }

    @Test
    @DisplayName("check:phases 가 어긋나면 error:pushraced")
    void checkPhases() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("check:phases", true, Map.of(), Map.of(), phaseEntry(1, fx.n0()));
        assertSameAnswer("check-phases", w.toBytes(null));
        Bundle2.Writer fine = bundle();
        fine.addPart("check:phases", true, Map.of(), Map.of(), phaseEntry(1, fx.n2()));
        assertSameAnswer("check-phases-ok", fine.toBytes(null));
    }

    @Test
    @DisplayName("check:heads 와 check:updated-heads 가 어긋나면 error:pushraced")
    void checkHeads() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("check:heads", true, Map.of(), Map.of(), node(fx.n0()));
        assertSameAnswer("check-heads", w.toBytes(null));
        Bundle2.Writer updated = bundle();
        updated.addPart("check:updated-heads", true, Map.of(), Map.of(), node(fx.n0()));
        assertSameAnswer("check-updated-heads", updated.toBytes(null));
        Bundle2.Writer fine = bundle();
        fine.addPart("check:updated-heads", true, Map.of(), Map.of(), node(fx.n2()));
        assertSameAnswer("check-updated-heads-ok", fine.toBytes(null));
    }

    @Test
    @DisplayName("phase-heads 부품이 phase 를 올린다")
    void phaseHeadsPart() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("phase-heads", true, Map.of(), Map.of(), phaseEntry(0, fx.n2()));
        assertSameAnswer("phase-heads", w.toBytes(null));
    }

    @Test
    @DisplayName("changegroup 부품의 내용이 깨져 있으면 원본과 같이 응답한다")
    void corruptChangegroup() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("changegroup", true, new LinkedHashMap<>(Map.of("version", "02")), Map.of(), "this is not a changegroup".getBytes(StandardCharsets.US_ASCII));
        assertSameAnswer("corrupt-changegroup", w.toBytes(null));
    }

    @Test
    @DisplayName("changegroup 이 적용된 뒤 다른 부품이 실패하면 changegroup 까지 모두 되돌려진다")
    void aLateFailureUndoesTheChangegroupToo() throws Exception {
        // a real bundle with one new changeset, plus a bookmarks part that points at a node nobody has: the part fails only when applied
        LocalHg.hgIn(tmp, ".", "clone", fx.repo().toString(), "late-client");
        java.nio.file.Files.writeString(tmp.resolve("late-client/a.txt"), "pushed late\n");
        LocalHg.hgIn(tmp, "late-client", "commit", "-u", "dev", "-m", "late");
        Path bundleFile = tmp.resolve("late.hg");
        LocalHg.hgIn(tmp, "late-client", "bundle", "--type", "none-v2", "-r", "tip", "--base", "tip^", bundleFile.toString());
        Bundle2.Writer w = bundle();
        for (Bundle2.Part part : Bundle2.read(java.nio.file.Files.readAllBytes(bundleFile))) {
            if (part.type.equals("changegroup")) {
                w.addPart("changegroup", true, part.params, Map.of(), part.payload);
            }
        }
        w.addPart("bookmarks", true, Map.of(), Map.of(), bookmark("ghost", "ab".repeat(20)));

        Path mine = tmp.resolve("late-mine");
        ParitySupport.copyTree(fx.repo(), mine);
        String before = LocalHg.hgIn(tmp, "late-mine", "log", "-T", "{node} {phase} {bookmarks}\n");
        String before1 = LocalHg.hgIn(tmp, "late-mine", "bookmarks");
        String answer = new String(ParitySupport.sshSession(false, mine, ParitySupport.unbundleRequest("666f726365", w.toBytes(null))),
                StandardCharsets.ISO_8859_1);
        assertTrue(answer.contains("ERROR:ABORT") && answer.contains("could not update bookmark ghost"), answer);
        assertEquals(before, LocalHg.hgIn(tmp, "late-mine", "log", "-T", "{node} {phase} {bookmarks}\n"),
                "the changegroup must have been rolled back");
        assertEquals(before1, LocalHg.hgIn(tmp, "late-mine", "bookmarks"));
        assertTrue(LocalHg.hgIn(tmp, "late-mine", "verify").contains("checking files"), "and the repository must still be sound");
    }

    @Test
    @DisplayName("알 수 없는 changegroup 버전은 거절한다 (원본은 내부 오류로 연결이 끊긴다)")
    void unknownChangegroupVersionIsRefused() throws Exception {
        Bundle2.Writer w = bundle();
        w.addPart("changegroup", true, new LinkedHashMap<>(Map.of("version", "99")), Map.of(), new byte[]{0, 0, 0, 0});
        Path mine = tmp.resolve("v99-mine");
        ParitySupport.copyTree(fx.repo(), mine);
        String answer = new String(ParitySupport.sshSession(false, mine, ParitySupport.unbundleRequest("666f726365", w.toBytes(null))),
                StandardCharsets.ISO_8859_1);
        assertTrue(answer.contains("unsupported changegroup version 99"), answer);
        assertTrue(answer.contains("ERROR:ABORT"), answer);
    }

    @Test
    @DisplayName("bundle2 가 아닌 내용을 보내면 원본과 같이 응답한다")
    void notABundle() throws Exception {
        assertSameAnswer("not-a-bundle", "this is not a bundle at all".getBytes(StandardCharsets.US_ASCII));
        assertSameAnswer("truncated-bundle2", "HG20\0\0\0\0\0\0\0\u0010".getBytes(StandardCharsets.ISO_8859_1));
    }
}
