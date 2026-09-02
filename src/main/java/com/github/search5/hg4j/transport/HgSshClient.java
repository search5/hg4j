package com.github.search5.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgProtocolException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

            // Stdio protocol begins by immediate server output of its capabilities
            readCapabilities();

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

    private void readCapabilities() throws IOException {
        String header = readLine();
        if (!header.startsWith("capabilities:")) {
            throw new HgProtocolException(sshUrl, "Remote SSH server did not output valid Mercurial stdio capabilities header. Received: " + header);
        }

        String capString = header.substring("capabilities:".length()).trim();
        capabilities.clear();
        if (!capString.isEmpty()) {
            for (String cap : capString.split("\\s+")) {
                capabilities.add(cap);
            }
        }

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

        writeLine("heads");
        writeLine("");

        // Read response line (space separated 40-char hex hashes)
        String resp = readLine();
        List<String> heads = new ArrayList<>();
        if (!resp.isEmpty()) {
            for (String head : resp.split("\\s+")) {
                heads.add(head.trim());
            }
        }
        return heads;
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        ensureConnected();

        writeLine("changegroup");

        if (roots != null && !roots.isEmpty()) {
            String rootsStr = String.join(" ", roots);
            writeLine("roots " + rootsStr);
        }
        writeLine("");

        return readBinaryResponse();
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        ensureConnected();

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

        // 실제 스펙(wireprototypes.GETBUNDLE_ARGUMENTS): bundlecaps는 "scsv" 타입 — 최상위
        // 토큰 구분자가 콤마다(스페이스 아님, HgRemoteClient에서 실측한 것과 동일한 문제).
        // 그리고 changegroup 버전 목록은 평평한 "changegroup=..." 토큰이 아니라
        // "bundle2=<blob>" 토큰 안에 중첩돼야만 urlutil.b2_caps_from_bundle_caps()가 읽는다
        // (Bundle2Parser#buildChangegroupBundleCaps 주석 참고, HTTP 경로에서 실측·수정
        // 확인됨(2026-09-03) — SSH 쪽은 hg4j의 자체 단순화된 텍스트 라인 프로토콜이 실제
        // hg의 바이너리 length-prefixed 인자 프레이밍과 애초에 다르므로, 이 문자열 수정이
        // 실제로 SSH 경로에서 bundle2/cg4/cg5 협상을 활성화하는지는 별도 검증 못 함 — 적어도
        // 회귀는 없다: 실제 hg 서버가 이 인자를 못 알아들으면 기존과 동일하게 무시되고
        // bundle1로 폴백될 뿐이다).
        if (bundleCaps != null && !bundleCaps.isEmpty()) {
            writeLine("bundlecaps " + String.join(",", bundleCaps));
        } else {
            writeLine("bundlecaps "
                    + com.github.search5.hg4j.bundle.Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05")
                    + ",compression=GZ,BZ,ZS");
        }

        writeLine("");

        return readBinaryResponse();
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        ensureConnected();

        writeLine("unbundle");
        if (heads != null && !heads.isEmpty()) {
            writeLine("heads " + String.join(" ", heads));
        }
        writeLine("");

        if (protocolVersion == 2) {
            ByteBuffer header = ByteBuffer.allocate(5);
            header.put((byte) 1);
            header.putInt(bundleBytes.length);
            out.write(header.array());
        }
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

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // In hg stdio 1.0, binary stream command responses are delivered in chunks
        // Decode chunk by chunk until terminal empty chunk (length 0) is met
        while (true) {
            byte[] lenBytes = new byte[4];
            int read = 0;
            while (read < 4) {
                int got = in.read(lenBytes, read, 4 - read);
                if (got == -1) {
                    if (read == 0) {
                        return baos.toByteArray(); // EOF
                    }
                    throw new HgProtocolException(sshUrl, "Unexpected EOF while reading Mercurial SSH binary chunk size");
                }
                read += got;
            }
            int len = ((lenBytes[0] & 0xFF) << 24) |
                      ((lenBytes[1] & 0xFF) << 16) |
                      ((lenBytes[2] & 0xFF) << 8)  |
                      (lenBytes[3] & 0xFF);
            if (len == 0) {
                break; // Empty chunk signals end of payload
            }
            if (len < 0) {
                throw new HgProtocolException(sshUrl, "Invalid negative binary chunk size: " + len);
            }

            byte[] chunkBuf = new byte[len];
            int chunkRead = 0;
            while (chunkRead < len) {
                int got = in.read(chunkBuf, chunkRead, len - chunkRead);
                if (got == -1) {
                    throw new HgProtocolException(sshUrl, "Unexpected EOF inside Mercurial SSH binary chunk payload");
                }
                chunkRead += got;
            }
            baos.write(chunkBuf);
        }
        return baos.toByteArray();
    }

    @Override
    public Map<String, String> listKeys(String namespace) throws IOException {
        ensureConnected();
        writeLine("listkeys");
        writeLine("namespace " + namespace);
        writeLine("");

        String resp = readLine();
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
        writeLine("between");
        writeLine("pairs " + String.join(" ", pairs));
        writeLine("");

        String resp = readLine();
        List<String> list = new ArrayList<>();
        if (!resp.isEmpty()) {
            for (String val : resp.split("\\s+")) {
                list.add(val.trim());
            }
        }
        return list;
    }

    @Override
    public String known(List<String> nodes) throws IOException {
        ensureConnected();
        writeLine("known");
        writeLine("nodes " + String.join(" ", nodes));
        writeLine("");

        return readLine().trim();
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        ensureConnected();
        writeLine("pushkey");
        writeLine("namespace " + namespace);
        writeLine("key " + key);
        writeLine("old " + (oldVal != null ? oldVal : ""));
        writeLine("new " + (newVal != null ? newVal : ""));
        writeLine("");

        String resp = readLine().trim();
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
