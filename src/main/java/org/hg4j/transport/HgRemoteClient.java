package org.hg4j.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for communicating with remote Mercurial repositories using the HTTP Wire Protocol v1.
 */
public class HgRemoteClient implements HgRemoteConnection {
    private final String baseUrl;
    private int connectTimeout = 10000; // 10 seconds
    private int readTimeout = 30000;    // 30 seconds
    private String username;
    private String password;
    private boolean forceTls = false;
    private java.net.Proxy proxy = java.net.Proxy.NO_PROXY;

    public HgRemoteClient(String url) {
        if (url.endsWith("/")) {
            this.baseUrl = url.substring(0, url.length() - 1);
        } else {
            this.baseUrl = url;
        }
    }

    public void setTimeouts(int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void setForceTls(boolean forceTls) {
        this.forceTls = forceTls;
    }

    public void setProxy(java.net.Proxy proxy) {
        if (proxy != null) {
            this.proxy = proxy;
        }
    }

    /**
     * Executes the 'capabilities' command on the remote server.
     */
    public List<String> getCapabilities() throws IOException {
        String resp = executeGet("capabilities");
        List<String> caps = new ArrayList<>();
        if (resp != null && !resp.trim().isEmpty()) {
            for (String cap : resp.split("\\s+")) {
                caps.add(cap.trim());
            }
        }
        return caps;
    }

    /**
     * Executes the 'heads' command on the remote server.
     */
    public List<String> getHeads() throws IOException {
        String resp = executeGet("heads");
        List<String> heads = new ArrayList<>();
        if (resp != null && !resp.trim().isEmpty()) {
            for (String head : resp.split("\\n")) {
                String clean = head.trim();
                if (!clean.isEmpty()) {
                    heads.add(clean);
                }
            }
        }
        return heads;
    }

    /**
     * Downloads a changegroup bundle for specified head revisions.
     */
    public byte[] getChangegroup(List<String> roots) throws IOException {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        if (roots != null && !roots.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(roots.get(i));
            }
            params.put("roots", sb.toString());
        }
        return executePostBinary("changegroup", params);
    }

    /**
     * Executes the 'getbundle' command to download a bundle (changelog, manifest, filelogs).
     * Supports both bundle1 and bundle2 protocols with custom capabilities.
     *
     * @param common list of node IDs known locally (hashes as hex strings)
     * @param heads list of remote head node IDs requested (hashes as hex strings)
     * @param bundleCaps list of client bundle capabilities (e.g. "bundle2", "HG20", "changegroup=01,02")
     * @return the raw binary bundle data
     * @throws IOException if execution fails
     */
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        
        if (common != null && !common.isEmpty()) {
            params.put("common", String.join(" ", common));
        } else {
            params.put("common", "");
        }
        
        if (heads != null && !heads.isEmpty()) {
            params.put("heads", String.join(" ", heads));
        }
        
        params.put("cg", "true"); // include changegroup
        
        if (bundleCaps != null && !bundleCaps.isEmpty()) {
            params.put("bundlecaps", String.join(" ", bundleCaps));
        } else {
            // Default capabilities compatible with bundle2 and legacy changegroups
            params.put("bundlecaps", "bundle2 HG20 changegroup=01,02,03");
        }
        
        return executePostBinary("getbundle", params);
    }

    private String executeGet(String cmd) throws IOException {
        byte[] bytes = executeGetBinary(cmd);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] executeGetBinary(String cmd) throws IOException {
        String fullUrl = baseUrl + "?cmd=" + cmd;
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        java.net.URL url;
        try {
            url = java.net.URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new org.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new org.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new org.hg4j.errors.HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int count;
            int totalBytes = 0;
            int maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new org.hg4j.errors.HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
                }
                out.write(buf, 0, count);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private byte[] executePostBinary(String cmd, java.util.Map<String, String> params) throws IOException {
        String fullUrl = baseUrl + "?cmd=" + cmd;
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        java.net.URL url;
        try {
            url = java.net.URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new org.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        StringBuilder bodyBuilder = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append("&");
            }
            bodyBuilder.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            bodyBuilder.append("=");
            bodyBuilder.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        byte[] postData = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new org.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new org.hg4j.errors.HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int count;
            int totalBytes = 0;
            int maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new org.hg4j.errors.HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
                }
                out.write(buf, 0, count);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static class MercurialChunkedInputStream extends InputStream {
        private final InputStream in;
        private int remaining = 0;
        private boolean eof = false;

        public MercurialChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (eof) return -1;
            if (remaining == 0) {
                if (!readNextChunk()) {
                    return -1;
                }
            }
            int b = in.read();
            if (b == -1) {
                eof = true;
                throw new org.hg4j.errors.HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0) {
                if (!readNextChunk()) {
                    return -1;
                }
            }
            int toRead = Math.min(len, remaining);
            int read = in.read(b, off, toRead);
            if (read == -1) {
                eof = true;
                throw new org.hg4j.errors.HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
            }
            remaining -= read;
            return read;
        }

        private boolean readNextChunk() throws IOException {
            byte[] lenBytes = new byte[4];
            int read = 0;
            while (read < 4) {
                int count = in.read(lenBytes, read, 4 - read);
                if (count == -1) {
                    if (read == 0) {
                        eof = true;
                        return false;
                    }
                    throw new org.hg4j.errors.HgProtocolException("", "Unexpected EOF while reading mercurial-0.2 chunk length");
                }
                read += count;
            }
            int len = ((lenBytes[0] & 0xFF) << 24) |
                      ((lenBytes[1] & 0xFF) << 16) |
                      ((lenBytes[2] & 0xFF) << 8)  |
                      (lenBytes[3] & 0xFF);
            if (len == 0) {
                eof = true;
                return false;
            }
            if (len < 0) {
                throw new org.hg4j.errors.HgProtocolException("", "Invalid negative chunk length: " + len);
            }
            remaining = len;
            return true;
        }
    }

    private InputStream unwrapResponseStream(InputStream in, String contentType) throws IOException {
        if (contentType != null && contentType.contains("application/mercurial-0.2")) {
            int compNameLen = in.read();
            if (compNameLen == -1) {
                return in;
            }
            byte[] compNameBytes = new byte[compNameLen];
            int read = 0;
            while (read < compNameLen) {
                int count = in.read(compNameBytes, read, compNameLen - read);
                if (count == -1) {
                    throw new org.hg4j.errors.HgProtocolException("", "Unexpected EOF while reading compression header in application/mercurial-0.2 stream");
                }
                read += count;
            }
            String compName = new String(compNameBytes, StandardCharsets.US_ASCII).trim();
            
            // application/mercurial-0.2 ALWAYS uses chunked framing for the rest of the payload
            InputStream chunkedIn = new MercurialChunkedInputStream(in);

            if ("zlib".equalsIgnoreCase(compName) || "deflate".equalsIgnoreCase(compName)) {
                return new java.util.zip.InflaterInputStream(chunkedIn);
            } else if ("none".equalsIgnoreCase(compName) || compName.isEmpty()) {
                return chunkedIn;
            } else {
                throw new org.hg4j.errors.HgProtocolException("", "Unsupported compression format in application/mercurial-0.2 framing: " + compName);
            }
        } else if (contentType != null && contentType.contains("application/mercurial-0.1")) {
            // Automatically detect and decompress application/mercurial-0.1 raw deflate (zlib) streams
            java.io.PushbackInputStream pbis = new java.io.PushbackInputStream(in, 2);
            int b1 = pbis.read();
            int b2 = pbis.read();
            if (b1 == 0x78 && (b2 == 0x9C || b2 == 0x01 || b2 == 0x5E || b2 == 0xDA)) {
                pbis.unread(b2);
                pbis.unread(b1);
                return new java.util.zip.InflaterInputStream(pbis);
            } else {
                if (b2 != -1) pbis.unread(b2);
                if (b1 != -1) pbis.unread(b1);
                return pbis;
            }
        }
        return in;
    }

    /**
     * Pushes a changegroup bundle to the remote repository using the 'unbundle' command.
     *
     * @param bundleBytes the binary changegroup bundle payload
     * @param heads the local heads being pushed
     * @return the remote server response output string
     * @throws IOException if network or push execution fails
     */
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        String fullUrl = baseUrl + "?cmd=unbundle";
        if (heads != null && !heads.isEmpty()) {
            fullUrl += "&heads=" + String.join("+", heads);
        }
        
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        java.net.URL url;
        try {
            url = java.net.URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new org.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/mercurial-0.1");
        conn.setRequestProperty("Content-Type", "application/mercurial-0.1");
        conn.setRequestProperty("Content-Length", String.valueOf(bundleBytes.length));

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(bundleBytes);
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new org.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new org.hg4j.errors.HgProtocolException(fullUrl, "Remote server returned HTTP " + status + " for unbundle: " + fullUrl);
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int count;
            long totalRead = 0;
            while ((count = in.read(buf)) != -1) {
                totalRead += count;
                if (totalRead > 10 * 1024 * 1024) { // 10MB Limit Guard
                    throw new org.hg4j.errors.HgProtocolException(fullUrl, "HTTP response size exceeded 10MB safety threshold under push");
                }
                out.write(buf, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public void close() {
        // HTTP 커넥션은 메서드 레벨에서 차단 및 닫기 완료됨
    }
}
