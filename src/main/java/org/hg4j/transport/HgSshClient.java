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
            this.username = authority.substring(0, atIdx);
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

    private void readCapabilities() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            baos.write(b);
        }
        String header = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
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
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        ensureConnected();
        return capabilities;
    }

    @Override
    public List<String> getHeads() throws IOException {
        ensureConnected();

        // Command 'heads' has no arguments in stdio protocol
        out.write("heads\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read response line (space separated 40-char hex hashes)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            baos.write(b);
        }
        String resp = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
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

        // command name
        out.write("changegroup\n".getBytes(StandardCharsets.UTF_8));

        // command arguments: key value pairs followed by a blank line
        if (roots != null && !roots.isEmpty()) {
            String rootsStr = String.join(" ", roots);
            out.write(("roots " + rootsStr + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write("\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read binary changegroup bundle back (formatted as standard chunks)
        // Let's decode it fully using a standard buffer
        return readBinaryResponse();
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        ensureConnected();

        out.write("getbundle\n".getBytes(StandardCharsets.UTF_8));

        if (common != null && !common.isEmpty()) {
            out.write(("common " + String.join(" ", common) + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            out.write("common \n".getBytes(StandardCharsets.UTF_8));
        }

        if (heads != null && !heads.isEmpty()) {
            out.write(("heads " + String.join(" ", heads) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        out.write("cg true\n".getBytes(StandardCharsets.UTF_8));

        if (bundleCaps != null && !bundleCaps.isEmpty()) {
            out.write(("bundlecaps " + String.join(" ", bundleCaps) + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            out.write("bundlecaps bundle2 HG20 changegroup=01,02,03\n".getBytes(StandardCharsets.UTF_8));
        }

        out.write("\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        return readBinaryResponse();
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        ensureConnected();

        out.write("unbundle\n".getBytes(StandardCharsets.UTF_8));
        if (heads != null && !heads.isEmpty()) {
            out.write(("heads " + String.join(" ", heads) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write("\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Stdio protocol expects the raw binary bundle to be immediately written right after the command header block
        out.write(bundleBytes);
        out.flush();

        // Read text response response
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

    private byte[] readBinaryResponse() throws IOException {
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
                    throw new IOException("Unexpected EOF while reading Mercurial SSH binary chunk size");
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
                throw new IOException("Invalid negative binary chunk size: " + len);
            }

            byte[] chunkBuf = new byte[len];
            int chunkRead = 0;
            while (chunkRead < len) {
                int got = in.read(chunkBuf, chunkRead, len - chunkRead);
                if (got == -1) {
                    throw new IOException("Unexpected EOF inside Mercurial SSH binary chunk payload");
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
