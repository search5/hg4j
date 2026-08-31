package com.github.search5.hg4j.transport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.github.search5.hg4j.util.NodeIdUtil;

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
    private boolean isV2 = false;

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

    @Override
    public void setCredentialsProvider(CredentialsProvider provider) {
        if (provider != null) {
            CredentialItem.Username u = new CredentialItem.Username();
            CredentialItem.Password p = new CredentialItem.Password();
            if (provider.get(this.baseUrl, u, p)) {
                String user = u.getValue();
                char[] passChars = p.getValue();
                String pass = passChars != null ? new String(passChars) : null;
                setCredentials(user, pass);
            }
        }
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
        byte[] bytes = executeGetBinary("capabilities");
        List<String> caps = new ArrayList<>();
        if (isV2) {
            try {
                Object cbor = new CborDecoder(bytes).readValue();
                if (cbor instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) cbor;
                    Object capsObj = map.get("capabilities");
                    if (capsObj instanceof java.util.List) {
                        for (Object cap : (java.util.List<?>) capsObj) {
                            caps.add(String.valueOf(cap));
                        }
                    } else if (map.get("commands") instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<Object, Object> cmds = (java.util.Map<Object, Object>) map.get("commands");
                        for (Object cmd : cmds.keySet()) {
                            caps.add(String.valueOf(cmd));
                        }
                    }
                } else if (cbor instanceof java.util.List) {
                    for (Object cap : (java.util.List<?>) cbor) {
                        caps.add(String.valueOf(cap));
                    }
                }
            } catch (Exception e) {
                String resp = new String(bytes, StandardCharsets.UTF_8);
                if (!resp.trim().isEmpty()) {
                    for (String cap : resp.split("\\s+")) {
                        caps.add(cap.trim());
                    }
                }
            }
        } else {
            String resp = new String(bytes, StandardCharsets.UTF_8);
            if (resp != null && !resp.trim().isEmpty()) {
                for (String cap : resp.split("\\s+")) {
                    String clean = cap.trim();
                    caps.add(clean);
                    if ("http-v2".equalsIgnoreCase(clean) || "api-v2".equalsIgnoreCase(clean) || clean.startsWith("http-v2")) {
                        this.isV2 = true;
                    }
                }
            }
        }
        return caps;
    }

    public boolean isV2() {
        return isV2;
    }

    public void setV2(boolean isV2) {
        this.isV2 = isV2;
    }

    /**
     * Executes the 'heads' command on the remote server.
     */
    public List<String> getHeads() throws IOException {
        byte[] bytes = executeGetBinary("heads");
        List<String> heads = new ArrayList<>();
        if (isV2) {
            try {
                Object cbor = new CborDecoder(bytes).readValue();
                if (cbor instanceof java.util.List) {
                    for (Object item : (java.util.List<?>) cbor) {
                        if (item instanceof byte[]) {
                            heads.add(NodeIdUtil.toHex((byte[]) item));
                        } else {
                            heads.add(String.valueOf(item));
                        }
                    }
                }
            } catch (Exception e) {
                String resp = new String(bytes, StandardCharsets.UTF_8);
                if (!resp.trim().isEmpty()) {
                    for (String head : resp.split("\\s+")) {
                        String clean = head.trim();
                        if (!clean.isEmpty()) heads.add(clean);
                    }
                }
            }
        } else {
            String resp = new String(bytes, StandardCharsets.UTF_8);
            if (resp != null && !resp.trim().isEmpty()) {
                for (String head : resp.split("\\s+")) {
                    String clean = head.trim();
                    if (!clean.isEmpty()) {
                        heads.add(clean);
                    }
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
        String fullUrl = isV2 ? (baseUrl + "/.hg/api/v2/" + cmd) : (baseUrl + "?cmd=" + cmd);
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        java.net.URL url;
        try {
            url = java.net.URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new com.github.search5.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        if (isV2) {
            conn.setRequestProperty("Accept", "application/mercurial-x-api-v2");
        } else {
            conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
        }

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new com.github.search5.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        File tempFile = File.createTempFile("hg4j-get-", ".tmp");
        tempFile.deleteOnExit();

        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int count;
            long totalBytes = 0;
            long maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
                }
                fos.write(buf, 0, count);
            }
        } catch (Exception e) {
            tempFile.delete();
            throw e;
        } finally {
            conn.disconnect();
        }

        try {
            return Files.readAllBytes(tempFile.toPath());
        } finally {
            tempFile.delete();
        }
    }

    private byte[] executePostBinary(String cmd, java.util.Map<String, String> params) throws IOException {
        String fullUrl = isV2 ? (baseUrl + "/.hg/api/v2/" + cmd) : (baseUrl + "?cmd=" + cmd);
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        java.net.URL url;
        try {
            url = java.net.URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new com.github.search5.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        if (isV2) {
            conn.setRequestProperty("Accept", "application/mercurial-x-api-v2");
            conn.setRequestProperty("Content-Type", "application/mercurial-x-api-v2");
        } else {
            conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        }

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        byte[] postData;
        if (isV2) {
            CborEncoder encoder = new CborEncoder();
            encoder.writeValue(params);
            postData = encoder.build();
        } else {
            StringBuilder bodyBuilder = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                if (bodyBuilder.length() > 0) {
                    bodyBuilder.append("&");
                }
                bodyBuilder.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                bodyBuilder.append("=");
                bodyBuilder.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            postData = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new com.github.search5.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        File tempFile = File.createTempFile("hg4j-post-", ".tmp");
        tempFile.deleteOnExit();

        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int count;
            long totalBytes = 0;
            long maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
                }
                fos.write(buf, 0, count);
            }
        } catch (Exception e) {
            tempFile.delete();
            throw e;
        } finally {
            conn.disconnect();
        }

        try {
            return Files.readAllBytes(tempFile.toPath());
        } finally {
            tempFile.delete();
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
                throw new com.github.search5.hg4j.errors.HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
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
                throw new com.github.search5.hg4j.errors.HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
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
                    throw new com.github.search5.hg4j.errors.HgProtocolException("", "Unexpected EOF while reading mercurial-0.2 chunk length");
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
                throw new com.github.search5.hg4j.errors.HgProtocolException("", "Invalid negative chunk length: " + len);
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
                    throw new com.github.search5.hg4j.errors.HgProtocolException("", "Unexpected EOF while reading compression header in application/mercurial-0.2 stream");
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
                throw new com.github.search5.hg4j.errors.HgProtocolException("", "Unsupported compression format in application/mercurial-0.2 framing: " + compName);
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
            throw new com.github.search5.hg4j.errors.HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
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
            throw new com.github.search5.hg4j.errors.HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "Remote server returned HTTP " + status + " for unbundle: " + fullUrl);
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int count;
            long totalRead = 0;
            while ((count = in.read(buf)) != -1) {
                totalRead += count;
                if (totalRead > 10 * 1024 * 1024) { // 10MB Limit Guard
                    throw new com.github.search5.hg4j.errors.HgProtocolException(fullUrl, "HTTP response size exceeded 10MB safety threshold under push");
                }
                out.write(buf, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public java.util.Map<String, String> listKeys(String namespace) throws IOException {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("namespace", namespace);
        
        byte[] bytes;
        if (isV2) {
            bytes = executePostBinary("listkeys", params);
            try {
                Object cbor = new CborDecoder(bytes).readValue();
                if (cbor instanceof java.util.Map) {
                    java.util.Map<String, String> result = new java.util.HashMap<>();
                    @SuppressWarnings("unchecked")
                    java.util.Map<Object, Object> rawMap = (java.util.Map<Object, Object>) cbor;
                    for (java.util.Map.Entry<Object, Object> entry : rawMap.entrySet()) {
                        result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                    return result;
                }
            } catch (Exception ignored) {}
        }
        
        // Fallback to GET for V1 / V2 failure
        String resp = executeGet("listkeys?namespace=" + namespace);
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (resp != null && !resp.trim().isEmpty()) {
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
        String resp = executeGet("between?pairs=" + java.net.URLEncoder.encode(String.join(" ", pairs), "UTF-8"));
        List<String> list = new java.util.ArrayList<>();
        if (resp != null && !resp.trim().isEmpty()) {
            for (String val : resp.trim().split("\\s+")) {
                list.add(val.trim());
            }
        }
        return list;
    }

    @Override
    public String known(List<String> nodes) throws IOException {
        return executeGet("known?nodes=" + java.net.URLEncoder.encode(String.join(" ", nodes), "UTF-8"));
    }

    @Override
    public void close() {
        // HTTP connections are managed and closed at the method level
    }

    public static class CborDecoder {
        private final byte[] data;
        private int ptr = 0;

        public CborDecoder(byte[] data) {
            this.data = data;
        }

        public Object readValue() throws IOException {
            if (ptr >= data.length) {
                throw new IOException("Unexpected end of CBOR payload");
            }
            int b = data[ptr++] & 0xFF;
            int type = b >> 5;
            int val = b & 0x1F;

            long len = 0;
            if (val < 24) {
                len = val;
            } else if (val == 24) {
                len = data[ptr++] & 0xFF;
            } else if (val == 25) {
                len = ((data[ptr++] & 0xFF) << 8) | (data[ptr++] & 0xFF);
            } else if (val == 26) {
                len = ((long)(data[ptr++] & 0xFF) << 24) | ((data[ptr++] & 0xFF) << 16) 
                    | ((data[ptr++] & 0xFF) << 8) | (data[ptr++] & 0xFF);
            } else if (val == 27) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | (data[ptr++] & 0xFF);
                }
            }

            switch (type) {
                case 0: // Unsigned integer
                    return len;
                case 1: // Negative integer
                    return -1 - len;
                case 2: // Byte string
                    byte[] bstr = new byte[(int) len];
                    System.arraycopy(data, ptr, bstr, 0, (int) len);
                    ptr += len;
                    return bstr;
                case 3: // Text string
                    byte[] tstr = new byte[(int) len];
                    System.arraycopy(data, ptr, tstr, 0, (int) len);
                    ptr += len;
                    return new String(tstr, StandardCharsets.UTF_8);
                case 4: // Array
                    java.util.List<Object> list = new java.util.ArrayList<>();
                    for (int i = 0; i < len; i++) {
                        list.add(readValue());
                    }
                    return list;
                case 5: // Map
                    java.util.Map<Object, Object> map = new java.util.HashMap<>();
                    for (int i = 0; i < len; i++) {
                        Object k = readValue();
                        Object v = readValue();
                        map.put(k, v);
                    }
                    return map;
                case 7: // Simple/Float/Special
                    if (val == 20) return Boolean.FALSE;
                    if (val == 21) return Boolean.TRUE;
                    if (val == 22) return null;
                    return null;
                default:
                    throw new IOException("Unsupported CBOR major type: " + type);
            }
        }
    }

    public static class CborEncoder {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        public byte[] build() {
            return out.toByteArray();
        }

        public void writeValue(Object obj) throws IOException {
            if (obj == null) {
                out.write(0xF6); // null
            } else if (obj instanceof Boolean) {
                out.write(((Boolean) obj) ? 0xF5 : 0xF4);
            } else if (obj instanceof Integer || obj instanceof Long) {
                long val = ((Number) obj).longValue();
                if (val >= 0) {
                    writeHeader(0, val);
                } else {
                    writeHeader(1, -1 - val);
                }
            } else if (obj instanceof byte[]) {
                byte[] bstr = (byte[]) obj;
                writeHeader(2, bstr.length);
                out.write(bstr);
            } else if (obj instanceof String) {
                byte[] tstr = ((String) obj).getBytes(StandardCharsets.UTF_8);
                writeHeader(3, tstr.length);
                out.write(tstr);
            } else if (obj instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) obj;
                writeHeader(4, list.size());
                for (Object item : list) {
                    writeValue(item);
                }
            } else if (obj instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) obj;
                writeHeader(5, map.size());
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    writeValue(entry.getKey());
                    writeValue(entry.getValue());
                }
            } else {
                throw new IOException("Unsupported object type for CBOR encoding: " + obj.getClass());
            }
        }

        private void writeHeader(int type, long val) {
            int major = type << 5;
            if (val < 24) {
                out.write(major | (int) val);
            } else if (val <= 0xFF) {
                out.write(major | 24);
                out.write((int) val);
            } else if (val <= 0xFFFF) {
                out.write(major | 25);
                out.write((int) (val >> 8) & 0xFF);
                out.write((int) val & 0xFF);
            } else if (val <= 0xFFFFFFFFL) {
                out.write(major | 26);
                out.write((int) (val >> 24) & 0xFF);
                out.write((int) (val >> 16) & 0xFF);
                out.write((int) (val >> 8) & 0xFF);
                out.write((int) val & 0xFF);
            } else {
                out.write(major | 27);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (val >> (i * 8)) & 0xFF);
                }
            }
        }
    }
}
