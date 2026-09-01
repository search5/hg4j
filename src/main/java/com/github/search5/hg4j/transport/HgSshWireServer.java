package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.api.HgHook;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.transport.wireprotov1.Wire1Commands;
import com.github.search5.hg4j.transport.wireprotov1.Wire1Response;

import java.io.ByteArrayOutputStream;
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
            Map.entry("clonebundles", "")
    );

    private final HgRepository repository;
    private final List<HgHook> preChangegroupHooks = new ArrayList<>();
    private final List<HgHook> postChangegroupHooks = new ArrayList<>();

    public HgSshWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /** Registers a hook run before an incoming {@code unbundle} (push) is applied — returning
     * {@code false} aborts it before anything is written, real hg's {@code pretxnchangegroup}. */
    public HgSshWireServer registerPreChangegroupHook(HgHook hook) {
        preChangegroupHooks.add(hook);
        return this;
    }

    /** Registers a notification-only hook run after an incoming push has been applied — real hg's {@code changegroup}. */
    public HgSshWireServer registerPostChangegroupHook(HgHook hook) {
        postChangegroupHooks.add(hook);
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
                Wire1Response response = dispatch(cmd, in);
                writeResponse(out, response);
            } catch (Exception e) {
                writeResponse(out, Wire1Response.bytes(("").getBytes(StandardCharsets.US_ASCII)));
                return;
            }
        }
    }

    private Wire1Response dispatch(String cmd, InputStream in) throws Exception {
        String spec = ARG_SPECS.get(cmd);
        if (spec == null) {
            // Unknown command: real hg's SSH peer has no declared arg spec to consume, so there
            // is nothing more to read off the wire for it either -- just report the error.
            return Wire1Response.oobError("unsupported command: " + cmd);
        }
        Map<String, String> args = readArgs(in, spec);

        if ("unbundle".equals(cmd)) {
            byte[] bundleBytes = readPayload(in);
            return Wire1Commands.unbundle(repository, bundleBytes, args, preChangegroupHooks, postChangegroupHooks);
        }
        if ("batch".equals(cmd)) {
            return Wire1Commands.batch(repository, args);
        }
        return Wire1Commands.dispatch(repository, cmd, args);
    }

    private Map<String, String> readArgs(InputStream in, String spec) throws IOException {
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

    private void writeResponse(OutputStream out, Wire1Response response) throws IOException {
        switch (response.getKind()) {
            case BYTES -> writeLengthPrefixed(out, response.getPayload());
            case STREAM -> {
                out.write(response.getPayload());
                out.flush();
            }
            case OOB_ERROR -> writeLengthPrefixed(out, response.getErrorMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeLengthPrefixed(OutputStream out, byte[] payload) throws IOException {
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
