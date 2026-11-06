package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Commands;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Response;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real hg's SSH wireprotocol v1 line-based protocol, server side — pure transport glue over
 * {@link Wire1Commands}, the same protocol-agnostic core {@link HgHttpWireServer} uses. Analogous
 * to JGit's approach of wiring {@code UploadPack}/{@code ReceivePack} into an SSH {@code Command}
 * (JGit itself ships no SSH server — see the jgit-parity discussion this class's plan came out
 * of); this class only implements the protocol over a plain {@link InputStream}/{@link
 * OutputStream} pair, so it can be plugged into whatever SSH channel implementation the actual
 * {@code hg serve}-equivalent production entry point uses.
 *
 * <p>Framing verified against {@code mercurial/wireprotoserver.py}'s {@code sshv1protocolhandler}
 * (Mercurial 6.0): a command is one line ({@code "<cmdname>\n"}), followed by one line per
 * declared argument name for that command, {@code "<argname> <byte-length>\n"} then exactly that
 * many raw bytes (a {@code "*"} argument name introduces a variable-length dict: a
 * {@code "* <count>\n"} line, then {@code count} more {@code "<name> <len>\n<bytes>"} triples).
 * Responses are {@code "<len>\n<bytes>"} for {@link Wire1Response.Kind#BYTES}, or the raw bytes
 * with no framing at all for {@link Wire1Response.Kind#STREAM} (the changegroup format is
 * self-framed). An incoming {@code unbundle} payload is read as repeated
 * {@code "<chunk-size>\n<chunk bytes>"} terminated by a {@code "0\n"} chunk.</p>
 */
public class HgSshWireServer {

    /** Real hg's per-command argument-name spec (`@wireprotocommand(name, args)`'s second field), space-separated. */
    private static final Map<String, String> ARG_SPECS = Map.ofEntries(
            Map.entry("capabilities", ""),
            Map.entry("hello", ""),
            Map.entry("heads", ""),
            Map.entry("branchmap", ""),
            Map.entry("between", "pairs"),
            Map.entry("known", "nodes *"),
            Map.entry("listkeys", "namespace"),
            Map.entry("lookup", "key"),
            Map.entry("pushkey", "namespace key old new"),
            Map.entry("changegroup", "roots"),
            Map.entry("changegroupsubset", "bases heads"),
            Map.entry("getbundle", "*"),
            Map.entry("unbundle", "heads"),
            Map.entry("batch", "cmds *"),
            Map.entry("clonebundles", ""),
            Map.entry("branches", "nodes"),
            Map.entry("debugwireargs", "one two *"),
            Map.entry("protocaps", "caps"),
            Map.entry("clonebundles_manifest", "*"),
            Map.entry("get_cached_bundle_inline", "path"),
            Map.entry("stream_out", ""),
            Map.entry("heads_buckets", "*"),
            Map.entry("store_shape", "names *")
    );

    private final HgRepository repository;
    private final List<HgHook> preChangegroupHooks = new ArrayList<>();
    private final List<HgHook> preTxnChangegroupHooks = new ArrayList<>();
    private final List<HgHook> postChangegroupHooks = new ArrayList<>();
    // yona-wiki P3-21/P3-22 — see HgHttpWireServer's identical fields / Wire1Commands#pushkey.
    private final List<HgHook> prePushkeyHooks = new ArrayList<>();
    private final List<HgHook> postPushkeyHooks = new ArrayList<>();

    /**
     * Creates a server for the given repository. It holds no connection state, so it can serve several
     * sessions through {@link #handleConnection(InputStream, OutputStream)}. Register hooks before
     * serving; the hook lists are not synchronized.
     * @param repository the repository to serve; must not be null
     */
    public HgSshWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /** Registers a hook run before an incoming {@code unbundle} (push) is applied — returning
     * {@code false} aborts it before anything is written, real hg's {@code pretxnchangegroup}. */
    public HgSshWireServer registerPreChangegroupHook(HgHook hook) {
        preChangegroupHooks.add(hook);
        return this;
    }

    /**
     * Registers a hook run AFTER the incoming changegroup has been applied, abort-capable: returning {@code false} undoes the push, real hg's
     * {@code pretxnchangegroup} (the hook seen by {@link #registerPreChangegroupHook} runs before anything is written, like {@code prechangegroup}).
     */
    public HgSshWireServer registerPreTxnChangegroupHook(HgHook hook) {
        preTxnChangegroupHooks.add(hook);
        return this;
    }

    /**
     * Registers a notification-only hook run after an incoming push has been applied, like real hg's
     * {@code changegroup} hook. It runs only if the push brought at least one new changeset; the context key {@code "nodes"} lists the
     * new changesets (see {@link HgHook}).
     * @param hook the hook to add; must not be null
     * @return this server
     */
    public HgSshWireServer registerPostChangegroupHook(HgHook hook) {
        postChangegroupHooks.add(hook);
        return this;
    }

    /**
     * Registers a hook run before an incoming {@code pushkey} (e.g. a bookmark move) is applied —
     * returning {@code false} aborts it, surfaced to the client as the real hg pushkey failure response.
     * In a bundle2 push the hook runs after the changegroup of the same push was applied, so the repository in the context
     * (clear its revlog cache first) already holds the pushed changesets, like the original's pending view ({@code HG_PENDING});
     * a refusal then undoes the whole push.
     * @param hook the hook; it gets {@code namespace}, {@code key}, {@code old}, {@code new} and {@code repository}
     * @return {@code this} server
     */
    public HgSshWireServer registerPrePushkeyHook(HgHook hook) {
        prePushkeyHooks.add(hook);
        return this;
    }

    /** Registers a notification-only hook run after an incoming {@code pushkey} has been applied successfully. */
    public HgSshWireServer registerPostPushkeyHook(HgHook hook) {
        postPushkeyHooks.add(hook);
        return this;
    }

    /**
     * Serves commands from {@code in} to {@code out} until the input stream ends (real hg's SSH
     * peer keeps the channel open across multiple sequential commands within one session, unlike
     * one-shot HTTP requests).
     */
    public void handleConnection(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String cmd = readLine(in);
            if (cmd == null || cmd.isEmpty()) {
                return;
            }
            try {
                repository.refreshIfChangedOnDisk();
                if ("unbundle".equals(cmd)) {
                    handleUnbundle(in, out);
                    continue;
                }
                Wire1Response response = dispatch(cmd, in);
                writeResponse(out, response);
            } catch (Exception e) {
                writeResponse(out, Wire1Response.bytes(("").getBytes(StandardCharsets.US_ASCII)));
                return;
            }
        }
    }

    /**
     * Refuses a connection without ever opening a repository, for a caller that decides on access before it has one (for example an
     * SSH front end that checks the user's permission first). A real hg SSH client sends {@code hello} and {@code between} at once and
     * reads nothing, not even an error line, until the answer to {@code between} has come; writing only an error and closing leaves it
     * waiting for ever. So both handshake commands are answered as an empty server would answer them (the default capabilities, one
     * empty line for each pair of {@code between}), and then {@code reason} is sent as the out-of-band error that the client's next
     * command receives as its answer.
     *
     * @param in the stream the client writes to
     * @param out the stream the client reads from
     * @param reason the message of the refusal
     * @throws IOException if the streams fail or the client closes before the handshake is complete
     */
    public static void rejectConnection(InputStream in, OutputStream out, String reason) throws IOException {
        readLine(in); // "hello": real hg's SSH client always sends it first
        readArgs(in, ARG_SPECS.get("hello"));
        writeResponse(out, Wire1Response.bytes(
                ("capabilities: " + sshCapabilityLine(Wire1Commands.capabilitiesString()) + "\n").getBytes(StandardCharsets.UTF_8)));

        readLine(in); // "between"
        Map<String, String> betweenArgs = readArgs(in, ARG_SPECS.get("between"));
        writeResponse(out, Wire1Commands.between(betweenArgs));

        // The client starts reading only after the "1\n\n" marker of the answer to between; the error is what its next command reads.
        writeResponse(out, Wire1Response.oobError(reason));
    }

    /**
     * {@code unbundle}'s real hg wire shape is distinct from every other command's simple
     * one-request/one-response exchange ({@code mercurial/wireprotoserver.py}'s {@code
     * getpayload()} + {@code sshserver}'s push handling, confirmed against Mercurial 7.2.4 source
     * 2026-09-03): after reading the {@code heads} arg, the server must send an <em>empty framed
     * response first</em> — this is the real protocol's "OK to start streaming the payload"
     * signal a real hg client (and, since this fix, {@link HgSshClient}) waits for before writing
     * a single byte of bundle data; skipping it deadlocks both sides (server blocked reading a
     * payload the client hasn't been told it may send, client blocked reading a response the
     * server hasn't been told to send). Only after that does the server read the payload, then
     * reply with up to two more framed responses: an error-or-empty check, and — only if that was
     * empty — the actual result value.
     */
    private void handleUnbundle(InputStream in, OutputStream out) throws Exception {
        Map<String, String> args = readArgs(in, ARG_SPECS.get("unbundle"));
        Wire1Response raced = Wire1Commands.preCheckUnbundleHeads(repository, args);
        if (raced != null) {
            // refused before the payload is read: this frame takes the place of the "OK to continue" one
            writeLengthPrefixed(out, raced.getPayload());
            return;
        }
        writeLengthPrefixed(out, new byte[0]); // "OK to continue" -- real hg sends this before any payload byte
        byte[] bundleBytes = readPayload(in);

        Wire1Response resp = Wire1Commands.unbundle(repository, bundleBytes, args, preChangegroupHooks, preTxnChangegroupHooks, postChangegroupHooks, prePushkeyHooks, postPushkeyHooks, false);

        switch (resp.getKind()) {
            case PUSH_RESULT -> {
                // sshv1: pushres is an empty frame and then the result code
                writeLengthPrefixed(out, new byte[0]);
                writeLengthPrefixed(out, String.valueOf(resp.getPushResult()).getBytes(StandardCharsets.US_ASCII));
            }
            case PUSH_ERROR -> writeLengthPrefixed(out, resp.getPayload()); // sshv1: pusherr is the message alone
            default -> writeResponse(out, resp); // a bundle2 answer is a raw stream on SSH (no compression, no framing)
        }
    }

    private Wire1Response dispatch(String cmd, InputStream in) throws Exception {
        String spec = ARG_SPECS.get(cmd);
        if (spec == null) {
            // A command this server does not offer: real hg's SSH server answers with an EMPTY framed response and
            // goes back to waiting for the next command (wireprotoserver.py: "This command isn't available. Send
            // an empty response and go back to waiting for a new command."). There is no declared arg spec to
            // consume either. (hg4j used to answer with an "unsupported command: x" text, which a client probing an
            // optional command would read as data.)
            return Wire1Response.bytes(new byte[0]);
        }
        Map<String, String> args = readArgs(in, spec);
        if ("protocaps".equals(cmd)) {
            // SSH-only: the client announces what it can handle (e.g. "comp=zstd,zlib"). Nothing here depends on it
            // yet, so it is acknowledged like real hg does and not stored.
            return Wire1Response.bytes("OK".getBytes(StandardCharsets.US_ASCII));
        }
        if ("capabilities".equals(cmd) || "hello".equals(cmd)) {
            return sshCapabilities(cmd);
        }

        if ("batch".equals(cmd)) {
            return Wire1Commands.batch(repository, args);
        }
        // yona-wiki P3-21/P3-22 — see HgHttpWireServer's identical branch for why pushkey is
        // handled directly rather than via Wire1Commands#dispatch (which has no hook-aware overload).
        if ("pushkey".equals(cmd)) {
            return Wire1Commands.pushkey(repository, args, prePushkeyHooks, postPushkeyHooks);
        }
        return Wire1Commands.dispatch(repository, cmd, args);
    }


    /** The capabilities an SSH server sends: the given ones plus {@code protocaps}, the whole line sorted. */
    private static String sshCapabilityLine(String capabilities) {
        java.util.SortedSet<String> sorted = new java.util.TreeSet<>(java.util.Arrays.asList(capabilities.split(" ")));
        sorted.add("protocaps"); // real hg's sshv1 protocol handler adds it, and the whole line is sorted
        return String.join(" ", sorted);
    }

    /** SSH adds {@code protocaps} to the capabilities it advertises (real hg's sshv1 protocol handler does the same). */
    private Wire1Response sshCapabilities(String cmd) {
        String caps = sshCapabilityLine(Wire1Commands.capabilitiesString(repository));
        if (!"hello".equals(cmd)) {
            return Wire1Response.bytes(caps.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        hello.writeBytes(("capabilities: " + caps + "\n").getBytes(StandardCharsets.UTF_8));
        if (caps.contains(" heads_fingerprints")) {
            // the SSH handshake also carries the bucket fingerprints so a client can use them without a round trip
            try {
                byte[] line = Wire1Commands.headsBucketsHelloLine(repository);
                if (line != null) {
                    hello.writeBytes(line);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Wire1Response.bytes(hello.toByteArray());
    }

    private static Map<String, String> readArgs(InputStream in, String spec) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        String[] keys = spec.isEmpty() ? new String[0] : spec.split(" ");
        for (int i = 0; i < keys.length; i++) {
            String argLine = readLine(in);
            int sp = argLine.indexOf(' ');
            String argName = argLine.substring(0, sp);
            int len = Integer.parseInt(argLine.substring(sp + 1));
            if ("*".equals(argName)) {
                int count = len;
                for (int j = 0; j < count; j++) {
                    String extraLine = readLine(in);
                    int esp = extraLine.indexOf(' ');
                    String extraName = extraLine.substring(0, esp);
                    int extraLen = Integer.parseInt(extraLine.substring(esp + 1));
                    args.put(extraName, new String(readExactly(in, extraLen), StandardCharsets.UTF_8));
                }
            } else {
                args.put(argName, new String(readExactly(in, len), StandardCharsets.UTF_8));
            }
        }
        return args;
    }

    private byte[] readPayload(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true) {
            int count = Integer.parseInt(readLine(in));
            if (count == 0) {
                break;
            }
            baos.write(readExactly(in, count));
        }
        return baos.toByteArray();
    }

    private static void writeResponse(OutputStream out, Wire1Response response) throws IOException {
        switch (response.getKind()) {
            case BYTES -> writeLengthPrefixed(out, response.getPayload());
            case STREAM, STREAM_UNCOMPRESSED -> {
                // 실제 스펙(mercurial/wireprotoserver.py의 sshserver 루프): streamres와
                // streamreslegacy(백로그 26번의 bundle2 unbundle 회신) 둘 다 SSH에서는 동일하게
                // _sshv1respondstream()으로 프레이밍 없이 원시 바이트만 쓴다 -- HTTP와 달리
                // SSH 쪽엔 이 둘을 구분하는 압축 단계 자체가 없다(SSH 전송 자체가 애초에
                // 비압축).
                out.write(response.getPayload());
                out.flush();
            }
            case OOB_ERROR -> writeLengthPrefixed(out, response.getErrorMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeLengthPrefixed(OutputStream out, byte[] payload) throws IOException {
        out.write((payload.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(b);
        }
        return any ? line.toString(StandardCharsets.UTF_8) : null;
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n == -1) {
                throw new IOException("Unexpected EOF while reading " + len + " bytes (got " + read + ")");
            }
            read += n;
        }
        return buf;
    }
}
