package org.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * pure Java SSH Client communicating with remote Mercurial repositories
 * using the Mercurial SSH Wire Protocol over ssh:// connection.
 * Seamlessly compliant with JGit-style pure Java architecture.
 */
public class HgSshClient implements HgRemoteConnection, AutoCloseable {

    private final String sshUrl;
    private String username;
    private String host;
    private int port = 22;
    private String repoPath;

    private String password;
    private String privateKeyPath;
    private String passphrase;

    private Session session;
    private ChannelExec channel;
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

    private synchronized void ensureConnected() throws IOException {
        if (connected) {
            return;
        }

        try {
            JSch jsch = new JSch();
            if (privateKeyPath != null) {
                if (passphrase != null) {
                    jsch.addIdentity(privateKeyPath, passphrase);
                } else {
                    jsch.addIdentity(privateKeyPath);
                }
            }

            session = jsch.getSession(username, host, port);
            if (password != null) {
                session.setPassword(password);
            }

            // Safe defaults for connection stability
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(15000); // 15 seconds connection timeout

            channel = (ChannelExec) session.openChannel("exec");
            // Execute the standard mercurial stdio server command
            channel.setCommand("hg -R " + repoPath + " serve --stdio");

            this.in = channel.getInputStream();
            this.out = channel.getOutputStream();

            channel.connect(15000);

            // Stdio protocol begins by immediate server output of its capabilities
            readCapabilities();

            connected = true;
        } catch (JSchException e) {
            close();
            String msg = e.getMessage();
            if (msg != null && (msg.toLowerCase().contains("auth fail") || msg.toLowerCase().contains("authentication") || msg.toLowerCase().contains("permission denied"))) {
                throw new org.hg4j.errors.HgAuthException(sshUrl, username, e);
            }
            throw new org.hg4j.errors.HgProtocolException(sshUrl, "Failed to establish SSH connection: " + msg, e);
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
                    throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 frame size");
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
                    throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 frame payload");
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
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(5);
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
            throw new org.hg4j.errors.HgProtocolException(sshUrl, "Remote SSH server did not output valid Mercurial stdio capabilities header. Received: " + header);
        }

        String capString = header.substring("capabilities:".length()).trim();
        capabilities.clear();
        if (!capString.isEmpty()) {
            for (String cap : capString.split("\\s+")) {
                capabilities.add(cap);
            }
        }

        // v2 upgrade 시도
        if (capabilities.contains("exp-ssh-v2-0003")) {
            String token = java.util.UUID.randomUUID().toString().replace("-", "");
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
                // upgrade 거절 시: 이미 upgradeResponse(거절 한 줄)를 다 읽었으므로 스트림이 정상적입니다.
                // 만약 스트림에 잔여 버퍼 데이터가 있다면 sleep 없이 즉시 skip 처리합니다.
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

        if (bundleCaps != null && !bundleCaps.isEmpty()) {
            writeLine("bundlecaps " + String.join(" ", bundleCaps));
        } else {
            writeLine("bundlecaps bundle2 HG20 changegroup=01,02,03");
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
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(5);
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
                        throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 binary frame size");
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
                        throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF while reading SSH V2 binary frame payload");
                    }
                    chunkRead += got;
                }
                if (channelId == 1 || channelId == 2) {
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
                    throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF while reading Mercurial SSH binary chunk size");
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
                throw new org.hg4j.errors.HgProtocolException(sshUrl, "Invalid negative binary chunk size: " + len);
            }

            byte[] chunkBuf = new byte[len];
            int chunkRead = 0;
            while (chunkRead < len) {
                int got = in.read(chunkBuf, chunkRead, len - chunkRead);
                if (got == -1) {
                    throw new org.hg4j.errors.HgProtocolException(sshUrl, "Unexpected EOF inside Mercurial SSH binary chunk payload");
                }
                chunkRead += got;
            }
            baos.write(chunkBuf);
        }
        return baos.toByteArray();
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try {
                channel.disconnect();
            } catch (Exception ignored) {}
            channel = null;
        }
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception ignored) {}
            session = null;
        }
        connected = false;
    }
}
