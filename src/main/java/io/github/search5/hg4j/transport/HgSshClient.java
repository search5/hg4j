package io.github.search5.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.net.URLDecoder;

/**
 * pure Java SSH Client communicating with remote Mercurial repositories
 * using the Mercurial SSH Wire Protocol over ssh:// connection.
 * Seamlessly compliant with JGit-style pure Java architecture.
 */
public class HgSshClient implements HgRemoteConnection {

    private final String sshUrl;
    private String username;
    private String host;
    private int port = 22;
    private String repoPath;

    private String password;
    private String privateKeyPath;
    private String passphrase;

    private static SshSessionFactory sshSessionFactory = new JschSessionFactory();

    public static void setSshSessionFactory(SshSessionFactory factory) {
        if (factory != null) {
            sshSessionFactory = factory;
        }
    }

    public static SshSessionFactory getSshSessionFactory() {
        return sshSessionFactory;
    }

    private SshSession sshSession;
    private InputStream in;
    private OutputStream out;
    private List<String> capabilities = new ArrayList<>();
    private boolean connected = false;
    private int protocolVersion = 1;

    public HgSshClient(String sshUrl) {
        this.sshUrl = sshUrl;
        parseSshUrl(sshUrl);
    }

    private void parseSshUrl(String url) {
        // Expected format: ssh://[user@]host[:port]/path
        if (!url.startsWith("ssh://")) {
            throw new IllegalArgumentException("Invalid SSH URL protocol: " + url);
        }

        String content = url.substring(6);
        int slashIdx = content.indexOf('/');
        if (slashIdx == -1) {
            throw new IllegalArgumentException("Invalid SSH URL format: repository path must be specified");
        }

        this.repoPath = content.substring(slashIdx);
        String authority = content.substring(0, slashIdx);

        int atIdx = authority.indexOf('@');
        if (atIdx != -1) {
            String userPart = authority.substring(0, atIdx);
            int colonIdx = userPart.indexOf(':');
            if (colonIdx != -1) {
                this.username = userPart.substring(0, colonIdx);
                this.password = userPart.substring(colonIdx + 1);
            } else {
                this.username = userPart;
            }
            authority = authority.substring(atIdx + 1);
        } else {
            this.username = System.getProperty("user.name");
        }

        int colonIdx = authority.indexOf(':');
        if (colonIdx != -1) {
            this.host = authority.substring(0, colonIdx);
            try {
                this.port = Integer.parseInt(authority.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                this.port = 22;
            }
        } else {
            this.host = authority;
            this.port = 22;
        }
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setPrivateKey(String privateKeyPath, String passphrase) {
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    @Override
    public void setCredentialsProvider(CredentialsProvider provider) {
        if (provider != null) {
            CredentialItem.Username u = new CredentialItem.Username();
            CredentialItem.Password p = new CredentialItem.Password();
            CredentialItem.SshKeyPath k = new CredentialItem.SshKeyPath();
            CredentialItem.SshPassphrase pass = new CredentialItem.SshPassphrase();

            // Try loading SSH key details first
            if (provider.get(this.sshUrl, k, pass)) {
                String keyPath = k.getValue();
                char[] passphraseChars = pass.getValue();
                String passphraseStr = passphraseChars != null ? new String(passphraseChars) : null;
                if (keyPath != null) {
                    setPrivateKey(keyPath, passphraseStr);
                }
            }

            // Try loading SSH password/username credentials as well
            if (provider.get(this.sshUrl, u, p)) {
                String user = u.getValue();
                char[] passChars = p.getValue();
                String passwordStr = passChars != null ? new String(passChars) : null;
                if (passwordStr != null) {
                    setPassword(passwordStr);
                }
                if (user != null && !user.isEmpty()) {
                    this.username = user;
                }
            }
        }
    }

    private synchronized void ensureConnected() throws IOException {
        if (connected) {
            return;
        }

        try {
            sshSession = sshSessionFactory.createSession(host, port, username, password, privateKeyPath, passphrase);
            sshSession.connect(15000); // 15 seconds connection timeout
            sshSession.executeCommand("hg -R " + repoPath + " serve --stdio", 15000);

            this.in = sshSession.getInputStream();
            this.out = sshSession.getOutputStream();

            performHandshake();

            connected = true;
        } catch (Exception e) {
            close();
            String msg = e.getMessage();
            if (e instanceof JSchException || (msg != null && (msg.toLowerCase().contains("auth fail") || msg.toLowerCase().contains("authentication") || msg.toLowerCase().contains("permission denied")))) {
                throw new HgAuthException(sshUrl, username, e);
            }
            throw new HgProtocolException(sshUrl, "Failed to establish SSH connection: " + msg, e);
        }
    }

    private String readLine() throws IOException {
        if (protocolVersion == 2) {
            int channelId = in.read();
            if (channelId == -1) {
                return "";
            }
            byte[] lenBytes = new byte[4];
            int read = 0;
            while (read < 4) {
                int got = in.read(lenBytes, read, 4 - read);
                if (got == -1) {
                    throw new HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 frame size");
                }
                read += got;
            }
            int len = ((lenBytes[0] & 0xFF) << 24) |
                      ((lenBytes[1] & 0xFF) << 16) |
                      ((lenBytes[2] & 0xFF) << 8)  |
                      (lenBytes[3] & 0xFF);
            if (len <= 0) {
                return "";
            }
            byte[] buf = new byte[len];
            int total = 0;
            while (total < len) {
                int got = in.read(buf, total, len - total);
                if (got == -1) {
                    throw new HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 frame payload");
                }
                total += got;
            }
            return new String(buf, StandardCharsets.UTF_8).trim();
        } else {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                baos.write(b);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    private void writeLine(String line) throws IOException {
        if (protocolVersion == 2) {
            byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer header = ByteBuffer.allocate(5);
            header.put((byte) 1);
            header.putInt(data.length);
            out.write(header.array());
            out.write(data);
        } else {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    /**
     * Real hg's actual v1 SSH argument-transport format ({@code mercurial/sshpeer.py}'s {@code
     * _sendrequest()}: {@code "<cmd>\n"} followed by, for each declared argument name <b>sorted
     * alphabetically</b> ({@code sorted(wireargs.items())}), {@code "<name> <byte-length>\n"} then
     * exactly that many raw bytes — no trailing newline after the value itself. A {@code
     * fixedArgs} entry named {@code "*"} would be nonsensical (real hg never declares a fixed arg
     * literally named {@code "*"}); the wildcard/extra-args bucket real commands like {@code
     * getbundle} use instead is {@code extraArgs} here, written as {@code "* <count>\n"} then
     * {@code count} more {@code "<name> <len>\n<bytes>"} triples (one per {@code extraArgs}
     * entry, in whatever order the map iterates — real hg doesn't sort those either). Pass {@code
     * null} for {@code extraArgs} when the command's spec has no {@code "*"} entry at all;
     * pass an empty map when it does but there happen to be no extra values this call (real hg's
     * {@code known} command always has to send {@code "* 0\n"} for exactly this reason).
     */
    private void sendCommand(String cmd, Map<String, String> fixedArgs) throws IOException {
        sendCommand(cmd, fixedArgs, null);
    }

    private void sendCommand(String cmd, Map<String, String> fixedArgs, Map<String, String> extraArgs) throws IOException {
        out.write((cmd + "\n").getBytes(StandardCharsets.US_ASCII));
        List<String> keys = new ArrayList<>(fixedArgs.keySet());
        if (extraArgs != null) {
            keys.add("*");
        }
        Collections.sort(keys);
        for (String k : keys) {
            if ("*".equals(k)) {
                writeArgLine("*", extraArgs.size());
                for (Map.Entry<String, String> e : extraArgs.entrySet()) {
                    byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
                    writeArgLine(e.getKey(), v.length);
                    out.write(v);
                }
            } else {
                byte[] v = fixedArgs.get(k).getBytes(StandardCharsets.UTF_8);
                writeArgLine(k, v.length);
                out.write(v);
            }
        }
        out.flush();
    }

    private void writeArgLine(String name, int number) throws IOException {
        out.write((name + " " + number + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Real hg's "framed" v1 response ({@code mercurial/sshpeer.py}'s {@code _getamount()} +
     * {@code cappedreader}, used by every simple (non-streaming) command): a line with the byte
     * count, then exactly that many raw bytes — which may themselves contain embedded newlines
     * (e.g. a multi-key {@code listkeys} response), so this must NOT be read with a plain
     * line-reader the way an earlier version of this client incorrectly did.
     */
    private byte[] readFramedResponse() throws IOException {
        String lenLine = readLine();
        int len;
        try {
            len = Integer.parseInt(lenLine.trim());
        } catch (NumberFormatException e) {
            throw new HgProtocolException(sshUrl, "Expected a framed SSH response length, got: " + lenLine);
        }
        if (len <= 0) {
            return new byte[0];
        }
        byte[] buf = new byte[len];
        int total = 0;
        while (total < len) {
            int got = in.read(buf, total, len - total);
            if (got == -1) {
                throw new HgProtocolException(sshUrl, "Unexpected EOF while reading " + len + "-byte framed SSH response (got " + total + ")");
            }
            total += got;
        }
        return buf;
    }

    /**
     * Real hg's payload-streaming format used by {@code unbundle} ({@code sshpeer.py}'s {@code
     * _writeframed()}, called in a loop by {@code _callpush}/{@code _calltwowaystream}): the data
     * split into arbitrarily-sized {@code "<len>\n<bytes>"} chunks, terminated by an empty
     * {@code "0\n"} chunk.
     */
    private void writeFramedChunks(byte[] data) throws IOException {
        int chunkSize = 4096;
        for (int off = 0; off < data.length; off += chunkSize) {
            int len = Math.min(chunkSize, data.length - off);
            out.write((len + "\n").getBytes(StandardCharsets.US_ASCII));
            out.write(data, off, len);
        }
        out.write("0\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Real hg's actual v1 SSH bootstrap ({@code mercurial/sshpeer.py}'s {@code
     * _performhandshake()}, confirmed directly against Mercurial 7.2.4 source, 2026-09-03): the
     * client sends {@code "hello\n"} + {@code "between\n"} + a {@code "pairs <len>\n"} header and
     * the 81-byte null-range value ({@code "0"*40 + "-" + "0"*40}) in a single write, then reads
     * the two <em>framed</em> responses that follow in order — {@code hello}'s (containing the
     * {@code "capabilities: ..."} line) and then {@code between}'s (a single {@code "\n"} byte for
     * the null range; its only purpose here is to flush any banner noise ahead of it, so its
     * content is simply discarded).
     *
     * <p>This replaces an earlier version that assumed the server would proactively write {@code
     * "capabilities: ...\n"} as its very first bytes with no command sent at all — both a real hg
     * server and hg4j's own {@code HgSshWireServer} (which blocks reading a command before writing
     * anything) would simply hang forever waiting for the other side to speak first under that
     * assumption; the two had literally never been connected to each other successfully.</p>
     */
    private void performHandshake() throws IOException {
        String pairsArg = "0".repeat(40) + "-" + "0".repeat(40);
        byte[] pairsBytes = pairsArg.getBytes(StandardCharsets.US_ASCII);
        out.write(("hello\nbetween\npairs " + pairsBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(pairsBytes);
        out.flush();

        String helloText = new String(readFramedResponse(), StandardCharsets.UTF_8);
        capabilities.clear();
        for (String line : helloText.split("\n", -1)) {
            if (line.startsWith("capabilities:")) {
                String capString = line.substring("capabilities:".length()).trim();
                if (!capString.isEmpty()) {
                    for (String cap : capString.split("\\s+")) {
                        capabilities.add(cap);
                    }
                }
                break;
            }
        }
        if (capabilities.isEmpty()) {
            throw new HgProtocolException(sshUrl, "Remote SSH server did not advertise capabilities via hello. Received: " + helloText);
        }
        readFramedResponse(); // between's null-range response -- content unused, just flushing it off the wire

        // Attempt to upgrade to v2 (disabled by default, can be enabled using JVM option -Dhg4j.ssh.v2.enabled=true if necessary)
        if (Boolean.getBoolean("hg4j.ssh.v2.enabled") && capabilities.contains("exp-ssh-v2-0003")) {
            String token = UUID.randomUUID().toString().replace("-", "");
            writeLine("upgrade " + token + " proto=exp-ssh-v2-0003");
            String upgradeResponse = readLine();

            if (upgradeResponse.startsWith("upgraded " + token)) {
                this.protocolVersion = 2;
                String v2CapsHeader = readLine();
                if (v2CapsHeader.startsWith("capabilities:")) {
                    String v2CapString = v2CapsHeader.substring("capabilities:".length()).trim();
                    capabilities.clear();
                    if (!v2CapString.isEmpty()) {
                        for (String cap : v2CapString.split("\\s+")) {
                            capabilities.add(cap);
                        }
                    }
                }
            } else {
                // If upgrade is rejected: Since we have already read the upgradeResponse (a single rejection line), the stream remains in a valid state.
                // If there is residual data in the buffer, skip it immediately without sleeping.
                int avail = in.available();
                if (avail > 0) {
                    in.skip(avail);
                }
            }
        }
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        ensureConnected();
        return capabilities;
    }

    @Override
    public List<String> getHeads() throws IOException {
        ensureConnected();

        if (protocolVersion == 2) {
            writeLine("heads");
            writeLine("");
            String resp = readLine();
            List<String> heads = new ArrayList<>();
            if (!resp.isEmpty()) {
                for (String head : resp.split("\\s+")) {
                    heads.add(head.trim());
                }
            }
            return heads;
        }

        // Real hg spec: no declared args at all for "heads".
        sendCommand("heads", Map.of());
        String resp = new String(readFramedResponse(), StandardCharsets.UTF_8);
        List<String> heads = new ArrayList<>();
        if (!resp.isBlank()) {
            for (String head : resp.trim().split("\\s+")) {
                if (!head.isEmpty()) {
                    heads.add(head);
                }
            }
        }
        return heads;
    }

    /**
     * SSH counterpart of {@link HgRemoteClient#getBranchHeads()} -- backlog 33
     * (mercurial-spec-compliance-requirement.md): without this, {@code PushCommand}'s
     * checkheads safety net silently degrades to a topological-only check whenever the
     * remote is SSH (the {@link HgRemoteConnection#getBranchHeads()} default returns
     * {@code null}). {@code branchmap} is a no-arg v1 wire command (same shape as
     * {@code heads}/{@code listkeys}), real hg's server-side handler already exists on
     * the hg4j server too ({@code Wire1Commands.branchmap}) -- this was purely a missing
     * client-side call.
     */
    @Override
    public Map<String, List<String>> getBranchHeads() throws IOException {
        ensureConnected();

        String resp;
        if (protocolVersion == 2) {
            writeLine("branchmap");
            writeLine("");
            resp = readLine();
        } else {
            // Real hg spec: no declared args at all for "branchmap".
            sendCommand("branchmap", Map.of());
            resp = new String(readFramedResponse(), StandardCharsets.UTF_8);
        }

        Map<String, List<String>> map = new HashMap<>();
        if (resp == null || resp.trim().isEmpty()) {
            return map;
        }
        for (String line : resp.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp == -1) {
                continue;
            }
            String branch;
            try {
                branch = URLDecoder.decode(line.substring(0, sp), "UTF-8");
            } catch (Exception e) {
                branch = line.substring(0, sp);
            }
            List<String> heads = new ArrayList<>();
            for (String h : line.substring(sp + 1).trim().split("\\s+")) {
                if (!h.isEmpty()) {
                    heads.add(h);
                }
            }
            map.put(branch, heads);
        }
        return map;
    }

    /**
     * SSH counterpart of {@link HgRemoteClient#supportsClonebundles()}/{@link
     * HgRemoteClient#fetchClonebundlesManifest()} -- backlog item 39 wave 5 (wire-matrix track):
     * real hg's own client attempts the clonebundles bypass over any transport ({@code
     * remote.capable(b'clonebundles')} in {@code mercurial/exchange.py} is transport-agnostic), so
     * an SSH-served repository with the {@code clonebundles} extension enabled must be able to
     * serve this too, not just HTTP. {@code clonebundles} is a no-arg v1 wire command with a
     * simple framed text response -- the exact same shape as {@code branchmap} above, just a
     * different verb.
     */
    @Override
    public boolean supportsClonebundles() {
        return capabilities.contains("clonebundles");
    }

    /**
     * SSH counterpart of {@link HgRemoteClient#supportsNarrow()} -- see that method's doc for the
     * real hg source citation ({@code exp-narrow-1}, unconditional once the server's {@code
     * narrow} extension is loaded, regardless of transport).
     */
    @Override
    public boolean supportsNarrow() {
        return capabilities.contains("exp-narrow-1");
    }

    @Override
    public String fetchClonebundlesManifest() throws IOException {
        ensureConnected();
        if (protocolVersion == 2) {
            writeLine("clonebundles");
            writeLine("");
            return readLine();
        }
        sendCommand("clonebundles", Map.of());
        return new String(readFramedResponse(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        ensureConnected();

        if (protocolVersion == 2) {
            writeLine("changegroup");
            if (roots != null && !roots.isEmpty()) {
                writeLine("roots " + String.join(" ", roots));
            }
            writeLine("");
            return readBinaryResponse();
        }

        // Real hg's "roots" is a required fixed arg (spec: "roots") -- always sent, empty string
        // when there are no roots (unlike the v2 branch above, which omits the line entirely).
        String rootsStr = (roots != null && !roots.isEmpty()) ? String.join(" ", roots) : "";
        sendCommand("changegroup", Map.of("roots", rootsStr));
        return readBinaryResponse();
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        return getBundle(common, heads, bundleCaps, null);
    }

    /**
     * SSH counterpart of {@link HgRemoteClient#getBundle(List, List, List,
     * HgRemoteConnection.NarrowScope)} -- see that method's doc for the real-hg-verified wire
     * shape ({@code narrow=1}/{@code includepats}/{@code excludepats}, only sent when
     * {@code narrowScope} is non-{@code null}; include/excludepats individually omitted rather
     * than sent empty, matching real hg's own client).
     */
    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps,
                             HgRemoteConnection.NarrowScope narrowScope) throws IOException {
        ensureConnected();

        if (protocolVersion == 2) {
            writeLine("getbundle");
            if (common != null && !common.isEmpty()) {
                writeLine("common " + String.join(" ", common));
            } else {
                writeLine("common ");
            }
            if (heads != null && !heads.isEmpty()) {
                writeLine("heads " + String.join(" ", heads));
            }
            writeLine("cg true");
            if (bundleCaps != null && !bundleCaps.isEmpty()) {
                writeLine("bundlecaps " + String.join(",", bundleCaps));
            } else {
                writeLine("bundlecaps "
                        + io.github.search5.hg4j.bundle.Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05")
                        + ",compression=GZ,BZ,ZS");
            }
            if (narrowScope != null) {
                writeLine("narrow 1");
                if (!narrowScope.includePatterns.isEmpty()) {
                    writeLine("includepats " + String.join(",", narrowScope.includePatterns));
                }
                if (!narrowScope.excludePatterns.isEmpty()) {
                    writeLine("excludepats " + String.join(",", narrowScope.excludePatterns));
                }
            }
            writeLine("");
            return readBinaryResponse();
        }

        // Real hg spec: getbundle declares NO fixed args at all -- common/heads/cg/bundlecaps all
        // travel through the "*" wildcard bucket (mercurial/wireprotov1server.py:
        // "@wireprotocommand(b'getbundle', b'*')").
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("common", (common != null && !common.isEmpty()) ? String.join(" ", common) : "");
        if (heads != null && !heads.isEmpty()) {
            extra.put("heads", String.join(" ", heads));
        }
        extra.put("cg", "true");
        if (narrowScope != null) {
            extra.put("narrow", "1");
            if (!narrowScope.includePatterns.isEmpty()) {
                extra.put("includepats", String.join(",", narrowScope.includePatterns));
            }
            if (!narrowScope.excludePatterns.isEmpty()) {
                extra.put("excludepats", String.join(",", narrowScope.excludePatterns));
            }
        }

        // 실제 스펙(wireprototypes.GETBUNDLE_ARGUMENTS): bundlecaps는 "scsv" 타입 — 최상위
        // 토큰 구분자가 콤마다(스페이스 아님, HgRemoteClient에서 실측한 것과 동일한 문제).
        // 그리고 changegroup 버전 목록은 평평한 "changegroup=..." 토큰이 아니라
        // "bundle2=<blob>" 토큰 안에 중첩돼야만 urlutil.b2_caps_from_bundle_caps()가 읽는다
        // (Bundle2Parser#buildChangegroupBundleCaps 주석 참고, HTTP 경로에서 실측·수정
        // 확인됨(2026-09-03)). SSH 쪽 wire framing 자체도 이번에 실측·수정 완료 — 이제
        // sendCommand()가 real hg의 length-prefixed 인자 전송을 그대로 구현하므로, 이
        // bundlecaps 문자열이 실제로 서버에 도달함이 hg-rust-7.2.4 상대 라이브 interop
        // 테스트로 검증됨(HgSshClientRealHgInteropTest).
        if (bundleCaps != null && !bundleCaps.isEmpty()) {
            extra.put("bundlecaps", String.join(",", bundleCaps));
        } else {
            extra.put("bundlecaps",
                    io.github.search5.hg4j.bundle.Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05")
                            + ",compression=GZ,BZ,ZS");
        }

        sendCommand("getbundle", Map.of(), extra);
        return readBinaryResponse();
    }

    /**
     * Real hg's {@code unbundlehash} wire-encoding optimization for {@code unbundle}'s
     * {@code heads} argument ({@code mercurial/wireprotov1peer.py}'s {@code unbundle()},
     * confirmed against Mercurial 7.2.4 source 2026-09-03):
     * <pre>
     * if heads != [b'force'] and self.capable(b'unbundlehash'):
     *     heads = [b'hashed', sha1(b''.join(sorted(heads))).digest()]
     * </pre>
     * When the server advertises {@code unbundlehash}, a SHA1 digest of the sorted, concatenated
     * raw (20-byte) head node ids is sent instead of the literal list — the server's own {@code
     * exchange.py} {@code check_heads()} accepts a push whose digest matches its actual current
     * heads exactly as if the literal list had been sent, so this is purely a wire-size
     * optimization, not a behavior change. Falls back to the literal list whenever the
     * capability isn't present, {@code heads} is empty, or it's literally the {@code ["force"]}
     * sentinel (real hg never hashes a force-push).
     */
    private List<String> computeUnbundleHeadsArg(List<String> heads) {
        return NodeIdUtil.computeUnbundleHeadsWireValue(heads, capabilities.contains("unbundlehash"));
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        ensureConnected();

        if (protocolVersion == 2) {
            writeLine("unbundle");
            if (heads != null && !heads.isEmpty()) {
                writeLine("heads " + String.join(" ", heads));
            }
            writeLine("");

            ByteBuffer header = ByteBuffer.allocate(5);
            header.put((byte) 1);
            header.putInt(bundleBytes.length);
            out.write(header.array());
            out.write(bundleBytes);
            out.flush();

            // Read unbundle status line first (Mercurial stdio 1.0 protocol specification)
            String statusLine = readLine();
            StringBuilder sb = new StringBuilder(statusLine);
            try {
                int linesToRead = Integer.parseInt(statusLine.trim());
                for (int i = 0; i < linesToRead; i++) {
                    sb.append("\n").append(readLine());
                }
            } catch (NumberFormatException e) {
                // If the response is not a number (e.g. "push ok"), just return it immediately
            }
            return sb.toString().trim();
        }

        // Real hg's actual bundle10/legacy unbundle sequence (mercurial/sshpeer.py's
        // _callpush(), reached via wireprotov1peer.py's unbundle() for a non-bundle2 payload --
        // hg4j's own PushCommand always writes an "HG10UN" bundle1 stream): send the command with
        // its one fixed "heads" arg, then read ONE framed response immediately -- a real server
        // sends an empty frame there to mean "OK to continue" (wireprotoserver.py's getpayload():
        // "We initially send an empty response... If a client sees any other response, it
        // interprets it as an error"); a non-empty frame here means the push was rejected before
        // any payload was even read (e.g. a pretxnchangegroup-style hook) and nothing more should
        // be sent. Otherwise the payload streams as "<len>\n<bytes>" chunks terminated by an empty
        // "0\n" chunk, followed by up to two more framed reads: an error-or-empty check, then (if
        // empty) the actual integer result.
        List<String> wireHeads = computeUnbundleHeadsArg(heads);
        String headsStr = wireHeads.isEmpty() ? "" : String.join(" ", wireHeads);
        sendCommand("unbundle", Map.of("heads", headsStr));

        byte[] precheck = readFramedResponse();
        if (precheck.length > 0) {
            throw new HgProtocolException(sshUrl, new String(precheck, StandardCharsets.UTF_8).trim());
        }

        writeFramedChunks(bundleBytes);

        byte[] errorOrEmpty = readFramedResponse();
        if (errorOrEmpty.length > 0) {
            throw new HgProtocolException(sshUrl, new String(errorOrEmpty, StandardCharsets.UTF_8).trim());
        }
        byte[] result = readFramedResponse();
        return new String(result, StandardCharsets.UTF_8).trim();
    }

    private byte[] readBinaryResponse() throws IOException {
        if (protocolVersion == 2) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while (true) {
                int channelId = in.read();
                if (channelId == -1) {
                    break; // EOF
                }
                byte[] lenBytes = new byte[4];
                int read = 0;
                while (read < 4) {
                    int got = in.read(lenBytes, read, 4 - read);
                    if (got == -1) {
                        throw new HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 binary frame size");
                    }
                    read += got;
                }
                int len = ((lenBytes[0] & 0xFF) << 24) |
                          ((lenBytes[1] & 0xFF) << 16) |
                          ((lenBytes[2] & 0xFF) << 8)  |
                          (lenBytes[3] & 0xFF);
                if (len <= 0) {
                    break; // Empty frame signals end
                }
                byte[] chunkBuf = new byte[len];
                int chunkRead = 0;
                while (chunkRead < len) {
                    int got = in.read(chunkBuf, chunkRead, len - chunkRead);
                    if (got == -1) {
                        throw new HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 binary frame payload");
                    }
                    chunkRead += got;
                }
                if (channelId == 1) {
                    baos.write(chunkBuf);
                } else if (channelId == 2) {
                    String errMessage = new String(chunkBuf, StandardCharsets.UTF_8).trim();
                    if (!errMessage.isEmpty()) {
                        System.err.println("[HgSshClient Channel 2 Warning]: " + errMessage);
                    }
                    baos.write(chunkBuf);
                }
            }
            return baos.toByteArray();
        }

        // Real hg's cg1 changegroup byte format is itself a sequence of THREE independently
        // 4-byte-length-prefixed-chunk-terminated sections -- changelog group, manifest group,
        // then file groups (each file: a length-prefixed filename "chunk" followed by that file's
        // own zero-terminated revision-chunk sequence, the whole file-groups section itself ending
        // at an empty/zero-length "next filename"). SSH's STREAM-kind response carries this with
        // NO extra outer framing at all (mercurial/sshpeer.py's _callcompressable/_callstream:
        // the raw pipe is handed back to the caller as-is) -- the changegroup's OWN structure is
        // the only signal for "where does the response end". An earlier version of this method
        // stopped at the FIRST zero-length chunk it saw, which is only the end of the CHANGELOG
        // group, not the end of the response -- it would then try to read the manifest/file data
        // that followed as if it were the start of the NEXT command's response, blocking forever
        // waiting for bytes a real (or hg4j's own) server had already sent and moved past. Fixed
        // 2026-09-03 after this deadlocked HgSshClientTransportTest's getbundle/changegroup tests
        // against a real HgSshWireServer for the first time (see also HgSshWireServerRealHgInteropTest,
        // which had only ever exercised the read-only clone path, never getbundle over a live SSH
        // channel with actual repository content).
        //
        // getbundle's response may ALSO be a bundle2-wrapped stream (starts with the "HG20"
        // magic, chosen whenever the client's bundlecaps request it and the server supports it --
        // real hg's default for any modern repository) rather than raw cg1: a completely
        // different self-delimiting structure (magic + params + a sequence of parts, each with
        // its own header block and EXCLUSIVE-length-prefixed payload chunks, the whole stream
        // ending at a zero-length part-header marker) that needs its own walk here, since bundle2
        // isn't itself a cg1 changegroup at the top level. Found via a real hg SSH server
        // interop test (HgSshClientRealHgInteropTest) hanging the same way the cg1 bug above did.
        byte[] magic = new byte[4];
        int magicRead = 0;
        while (magicRead < 4) {
            int got = in.read(magic, magicRead, 4 - magicRead);
            if (got == -1) {
                throw new HgProtocolException(sshUrl, "Unexpected EOF while reading getbundle/changegroup response");
            }
            magicRead += got;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(magic);
        if (magic[0] == 'H' && magic[1] == 'G' && magic[2] == '2' && magic[3] == '0') {
            readBundle2StreamInto(baos);
            return baos.toByteArray();
        }
        // Not bundle2 -- these 4 "magic" bytes were actually the changelog group's first chunk's
        // own inclusive-length field (already written to baos above), not a separate token to
        // discard; feed that already-read length into the changelog group reader instead of
        // letting it read a fresh (and now misaligned-by-4-bytes) one.
        int firstChangelogChunkLen = ((magic[0] & 0xFF) << 24) | ((magic[1] & 0xFF) << 16) | ((magic[2] & 0xFF) << 8) | (magic[3] & 0xFF);
        copyGroupFromFirstChunkLength(baos, firstChangelogChunkLen); // changelog group
        copyGroupUntilTerminator(baos); // manifest group
        while (true) {
            int nameLen = readLenPrefixedChunkInto(baos);
            if (nameLen <= 0) {
                break; // empty/zero-length "filename" -- no more files
            }
            copyGroupUntilTerminator(baos); // this file's own revision chunks
        }
        return baos.toByteArray();
    }

    /**
     * Copies 4-byte-length-prefixed chunks (each written verbatim, header included, to {@code
     * out}) until a zero-length terminator chunk is copied. Real hg never leaves the SSH channel
     * closed mid-session, so unlike an HTTP body there is no other "end of response" signal here.
     */
    private void copyGroupUntilTerminator(ByteArrayOutputStream out) throws IOException {
        while (readLenPrefixedChunkInto(out) > 0) {
            // keep going until the zero-length terminator chunk
        }
    }

    /**
     * Same as {@link #copyGroupUntilTerminator}, but for a group whose first chunk's
     * inclusive-length field has ALREADY been read (and written to {@code out}) by the caller --
     * used for the changelog group specifically, since {@link #readBinaryResponse} has to read 4
     * bytes up front to sniff for the bundle2 {@code "HG20"} magic before it can know whether
     * those bytes were that magic or actually the changelog group's own first length field.
     */
    private void copyGroupFromFirstChunkLength(ByteArrayOutputStream out, int firstChunkLen) throws IOException {
        if (firstChunkLen < 0) {
            throw new HgProtocolException(sshUrl, "Invalid negative binary chunk size: " + firstChunkLen);
        }
        if (firstChunkLen == 0) {
            return; // the group's very first chunk was already the terminator -- empty group
        }
        readChunkDataInto(out, firstChunkLen);
        copyGroupUntilTerminator(out);
    }

    /**
     * Consumes (copying every byte, header included, to {@code out}) a bundle2 stream body
     * following its already-consumed {@code "HG20"} magic: a 4-byte params-size + that many
     * params bytes, then a sequence of parts (each: 4-byte header-size + that many header bytes,
     * then a sequence of EXCLUSIVE-length-prefixed payload chunks ending at a zero-length one),
     * the whole stream ending at a zero-length part-header. This is the SAME structural walk
     * {@link io.github.search5.hg4j.bundle.Bundle2Parser#extractChangegroupDetailed} performs
     * for semantic extraction (mercurial/bundle2.py); this copy is purely about knowing where the
     * raw byte stream ends, since SSH's unframed STREAM responses (real hg's {@code
     * _callcompressable}/{@code _callstream}) have no separate outer length to rely on instead.
     */
    private void readBundle2StreamInto(ByteArrayOutputStream out) throws IOException {
        int paramsSize = readInt32Into(out);
        if (paramsSize > 0) {
            readExactlyInto(out, paramsSize);
        }
        while (true) {
            int partHeaderSize = readInt32Into(out);
            if (partHeaderSize <= 0) {
                break; // zero-length part header = end of bundle2 stream
            }
            readExactlyInto(out, partHeaderSize);
            while (true) {
                int chunkSize = readInt32Into(out);
                if (chunkSize <= 0) {
                    break; // end of this part's payload
                }
                readExactlyInto(out, chunkSize);
            }
        }
    }

    /** Reads a plain (not-self-inclusive) 4-byte big-endian int, writing it verbatim to {@code out}. */
    private int readInt32Into(ByteArrayOutputStream out) throws IOException {
        byte[] b = new byte[4];
        int read = 0;
        while (read < 4) {
            int got = in.read(b, read, 4 - read);
            if (got == -1) {
                throw new HgProtocolException(sshUrl, "Unexpected EOF while reading a bundle2 length field");
            }
            read += got;
        }
        out.write(b);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /** Reads exactly {@code len} bytes, writing them verbatim to {@code out}. */
    private void readExactlyInto(ByteArrayOutputStream out, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int got = in.read(buf, read, len - read);
            if (got == -1) {
                throw new HgProtocolException(sshUrl, "Unexpected EOF while reading " + len + " bytes of a bundle2 stream");
            }
            read += got;
        }
        out.write(buf);
    }

    /** Reads one {@code <4-byte length><that many bytes>} unit, writing both (header included)
     * to {@code out}. Returns the length (0 for a terminator chunk). */
    private int readLenPrefixedChunkInto(ByteArrayOutputStream out) throws IOException {
        int len = readInt32Into(out);
        if (len < 0) {
            throw new HgProtocolException(sshUrl, "Invalid negative binary chunk size: " + len);
        }
        if (len == 0) {
            return 0;
        }
        readChunkDataInto(out, len);
        return len;
    }

    /**
     * Reads the data body that follows an already-read, already-written cg1 chunk length field
     * ({@code inclusiveLen}), writing it to {@code out}. Real hg's chunk-length convention
     * (confirmed against {@code HgLocalClient}'s own writer, which this must stay byte-compatible
     * with): the 4-byte length is INCLUSIVE of itself, i.e. {@code len = 4 + dataLength}, not just
     * the data length that follows. Reading {@code len} more bytes here (as an earlier version of
     * this class did) over-reads by 4 bytes into the next chunk's own length header every time,
     * which is what actually caused the deadlock this rewrite fixes: the misread "length" of the
     * next chunk is essentially random garbage, and the read then blocks forever trying to read
     * however many bytes that garbage value claims.
     */
    private void readChunkDataInto(ByteArrayOutputStream out, int inclusiveLen) throws IOException {
        int dataLen = inclusiveLen - 4;
        byte[] chunkBuf = new byte[dataLen];
        int chunkRead = 0;
        while (chunkRead < dataLen) {
            int got = in.read(chunkBuf, chunkRead, dataLen - chunkRead);
            if (got == -1) {
                throw new HgProtocolException(sshUrl, "Unexpected EOF inside Mercurial SSH binary chunk payload");
            }
            chunkRead += got;
        }
        out.write(chunkBuf);
    }

    @Override
    public Map<String, String> listKeys(String namespace) throws IOException {
        ensureConnected();
        if (protocolVersion == 2) {
            writeLine("listkeys");
            writeLine("namespace " + namespace);
            writeLine("");
            return parseListKeysResponse(readLine());
        }
        sendCommand("listkeys", Map.of("namespace", namespace));
        return parseListKeysResponse(new String(readFramedResponse(), StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseListKeysResponse(String resp) {
        Map<String, String> map = new HashMap<>();
        if (!resp.isEmpty()) {
            for (String line : resp.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab != -1) {
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return map;
    }

    @Override
    public List<String> between(List<String> pairs) throws IOException {
        ensureConnected();
        if (protocolVersion == 2) {
            writeLine("between");
            writeLine("pairs " + String.join(" ", pairs));
            writeLine("");
            return parseBetweenResponse(readLine());
        }
        sendCommand("between", Map.of("pairs", String.join(" ", pairs)));
        return parseBetweenResponse(new String(readFramedResponse(), StandardCharsets.UTF_8));
    }

    private static List<String> parseBetweenResponse(String resp) {
        List<String> list = new ArrayList<>();
        if (!resp.isEmpty()) {
            for (String val : resp.trim().split("\\s+")) {
                if (!val.isEmpty()) {
                    list.add(val);
                }
            }
        }
        return list;
    }

    @Override
    public String known(List<String> nodes) throws IOException {
        ensureConnected();
        if (protocolVersion == 2) {
            writeLine("known");
            writeLine("nodes " + String.join(" ", nodes));
            writeLine("");
            return readLine().trim();
        }
        // Real hg spec: "nodes *" -- known() always sends the "*" wildcard bucket too, empty
        // (mercurial/wireprotov1peer.py's known() only ever passes the "nodes" kwarg, so nothing
        // is left over for the wildcard -- but the header line for it, "* 0\n", is still sent).
        sendCommand("known", Map.of("nodes", String.join(" ", nodes)), Map.of());
        return new String(readFramedResponse(), StandardCharsets.UTF_8).trim();
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        ensureConnected();
        if (protocolVersion == 2) {
            writeLine("pushkey");
            writeLine("namespace " + namespace);
            writeLine("key " + key);
            writeLine("old " + (oldVal != null ? oldVal : ""));
            writeLine("new " + (newVal != null ? newVal : ""));
            writeLine("");
            String resp = readLine().trim();
            return "1".equals(resp) || "true".equalsIgnoreCase(resp) || resp.isEmpty();
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("namespace", namespace);
        args.put("key", key);
        args.put("old", oldVal != null ? oldVal : "");
        args.put("new", newVal != null ? newVal : "");
        sendCommand("pushkey", args);
        String resp = new String(readFramedResponse(), StandardCharsets.UTF_8).trim();
        return "1".equals(resp) || "true".equalsIgnoreCase(resp) || resp.isEmpty();
    }

    @Override
    public synchronized void close() {
        if (sshSession != null) {
            try {
                sshSession.close();
            } catch (Exception ignored) {}
            sshSession = null;
        }
        connected = false;
    }
}
