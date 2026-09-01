package com.github.search5.hg4j.transport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.transport.wireprotov2.Cbor;
import com.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.errors.HgTransportException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.zip.InflaterInputStream;

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
    private Proxy proxy = Proxy.NO_PROXY;
    
    private int maxHttpHeaderLimit = 1024; // 기본 1024바이트 제한
    private boolean supportsV2 = false;
    private boolean supportsClonebundles = false;
    private boolean hasNegotiated = false;
    private HgRemoteClientV2 delegate = null;

    public HgRemoteClient(String url) {
        if (url.endsWith("/")) {
            this.baseUrl = url.substring(0, url.length() - 1);
        } else {
            this.baseUrl = url;
        }
    }

    /**
     * Parses the real v1 {@code httpheader=NNNN} capability token for the HTTP header size limit.
     * v2 availability is <b>not</b> inferred from this list — a real v1 {@code capabilities}
     * response never contains a "v2 is available" flag (no such token exists in the actual
     * wire protocol; earlier code here matched a fictional {@code "http-v2"}/{@code "api-v2"}
     * token that real hg never sends, so the auto-upgrade could never trigger). The real signal
     * is the separate {@code X-HgUpgrade-1}/{@code X-HgProto-1} header handshake performed in
     * {@link #tryEstablishV2FromDiscoveryResponse(byte[])}.
     */
    public boolean negotiateV2(List<String> capabilities) {
        if (capabilities == null) return this.supportsV2;
        for (String cap : capabilities) {
            if (cap.startsWith("httpheader=")) {
                try {
                    this.maxHttpHeaderLimit = Integer.parseInt(cap.substring("httpheader=".length()).trim());
                } catch (NumberFormatException ignored) {}
            }
            if ("clonebundles".equals(cap)) {
                this.supportsClonebundles = true;
            }
        }
        return this.supportsV2;
    }

    /**
     * Attempts real hg's capability-upgrade handshake using the same {@code ?cmd=capabilities}
     * response the v1 path would otherwise parse as plain text: the request carries
     * {@code X-HgUpgrade-1: <namespace>} and {@code X-HgProto-1: cbor}, and a server that
     * supports the upgrade replies with a CBOR {@code {apibase, apis: {<namespace>: {...}}}}
     * descriptor instead of the plain-text v1 capabilities line. A real v1-only server simply
     * ignores the unrecognized headers and returns its normal plain-text response, which fails
     * to CBOR-decode into that shape here and is then parsed as ordinary v1 capabilities by the
     * caller — so this is a single request that serves either outcome, exactly like a real
     * client's opportunistic upgrade attempt.
     *
     * @return true if the server advertised wireprotocol v2 and {@link #delegate} was wired up
     */
    private boolean tryEstablishV2FromDiscoveryResponse(byte[] discoveryResponseBytes) {
        try {
            List<Object> objs = Cbor.decodeAll(discoveryResponseBytes);
            if (objs.isEmpty()) {
                return false;
            }
            Map<String, Object> descriptor = Cbor.asMap(objs.get(0));
            String discoveredApibase = Cbor.asString(descriptor.get("apibase"));
            Map<String, Object> apis = Cbor.asMap(descriptor.get("apis"));
            if (discoveredApibase == null || !apis.containsKey(Wire2Commands.NAMESPACE)) {
                return false;
            }
            this.supportsV2 = true;
            // The discovery response still embeds the real v1 capabilities line verbatim (see
            // HgHttpWireServer#handleCapabilitiesDiscovery) even when the client upgrades to v2 --
            // v1-only tokens like "clonebundles" have no v2 equivalent, so they must still be
            // parsed out here rather than silently lost on upgrade.
            String v1CapabilitiesLine = Cbor.asString(descriptor.get("v1capabilities"));
            if (v1CapabilitiesLine != null && !v1CapabilitiesLine.isEmpty()) {
                negotiateV2(Arrays.asList(v1CapabilitiesLine.split(" ")));
            }
            HgRemoteClientV2 v2 = new HgRemoteClientV2(this.baseUrl);
            v2.setTimeouts(this.connectTimeout, this.readTimeout);
            if (this.username != null && this.password != null) {
                v2.setCredentials(this.username, this.password);
            }
            v2.setForceTls(this.forceTls);
            v2.setProxy(this.proxy);
            v2.primeDiscovery(discoveredApibase, Wire2Commands.NAMESPACE);
            this.delegate = v2;
            return true;
        } catch (Exception notAV2DiscoveryResponse) {
            return false;
        }
    }

    public int getMaxHttpHeaderLimit() {
        return maxHttpHeaderLimit;
    }

    /**
     * Whether the remote advertised the real {@code "clonebundles"} v1 capability token
     * (available only after {@link #getCapabilities()} has been called at least once).
     */
    public boolean supportsClonebundles() {
        return supportsClonebundles;
    }

    /**
     * Fetches the raw text of the remote's clonebundles manifest via {@code ?cmd=clonebundles} —
     * real hg's {@code wireprotov1server.py} {@code clonebundles()} command, which just returns
     * the server repository's {@code .hg/clonebundles.manifest} file content verbatim. Parse the
     * result with {@link com.github.search5.hg4j.bundle.ClonebundlesManifest#parse(String)}.
     *
     * <p>This call itself still goes over the wire protocol (it's a normal {@code ?cmd=} request);
     * the actual "bypass" happens afterward, when the caller downloads the bundle from whatever
     * URL an entry in the manifest names — a plain HTTP(S) GET with no wire-protocol framing at
     * all, see {@code decisions/mercurial-spec-compliance-requirement.md}'s Clonebundles plan.</p>
     */
    public String fetchClonebundlesManifest() throws IOException {
        byte[] bytes = executeGetBinary("clonebundles");
        return new String(bytes, StandardCharsets.UTF_8);
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

    public void setProxy(Proxy proxy) {
        if (proxy != null) {
            this.proxy = proxy;
        }
    }

    /**
     * Executes the 'capabilities' command on the remote server.
     */
    public List<String> getCapabilities() throws IOException {
        if (delegate != null) {
            return delegate.getCapabilities();
        }
        byte[] bytes;
        boolean firstNegotiation = !hasNegotiated;
        if (firstNegotiation) {
            hasNegotiated = true;
            bytes = executeGetBinaryWithV2UpgradeHeaders("capabilities");
            if (tryEstablishV2FromDiscoveryResponse(bytes)) {
                return delegate.getCapabilities();
            }
        } else {
            bytes = executeGetBinary("capabilities");
        }
        List<String> caps = new ArrayList<>();
        String resp = new String(bytes, StandardCharsets.UTF_8);
        if (resp != null && !resp.trim().isEmpty()) {
            for (String cap : resp.split("\\s+")) {
                String clean = cap.trim();
                caps.add(clean);
            }
        }
        negotiateV2(caps);
        return caps;
    }

    /**
     * Executes the 'heads' command on the remote server.
     */
    public List<String> getHeads() throws IOException {
        if (delegate != null) {
            return delegate.getHeads();
        }
        byte[] bytes = executeGetBinary("heads");
        List<String> heads = new ArrayList<>();
        String resp = new String(bytes, StandardCharsets.UTF_8);
        if (resp != null && !resp.trim().isEmpty()) {
            for (String head : resp.split("\\s+")) {
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
        if (delegate != null) {
            return delegate.getChangegroup(roots);
        }
        Map<String, String> params = new HashMap<>();
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
        if (delegate != null) {
            return delegate.getBundle(common, heads, bundleCaps);
        }
        Map<String, String> params = new HashMap<>();
        
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
        return executeGetBinary(cmd, Map.of());
    }

    /**
     * Same GET as {@link #executeGetBinary(String)} but with extra request headers — used once
     * per client, on the first {@code capabilities} call, to carry the real
     * {@code X-HgUpgrade-1}/{@code X-HgProto-1} v2 upgrade-handshake headers alongside the
     * normal request.
     */
    private byte[] executeGetBinaryWithV2UpgradeHeaders(String cmd) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-HgUpgrade-1", Wire2Commands.NAMESPACE);
        headers.put("X-HgProto-1", "cbor");
        return executeGetBinary(cmd, headers);
    }

    private byte[] executeGetBinary(String cmd, Map<String, String> extraHeaders) throws IOException {
        String fullUrl = baseUrl;
        if (cmd.contains("?")) {
            fullUrl += "?cmd=" + cmd.substring(0, cmd.indexOf("?")) + "&" + cmd.substring(cmd.indexOf("?") + 1);
        } else {
            fullUrl += "?cmd=" + cmd;
        }
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        URL url;
        try {
            url = URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            conn.setRequestProperty(header.getKey(), header.getValue());
        }

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        File tempFile = File.createTempFile("hg4j-get-", ".tmp");
        tempFile.deleteOnExit();

        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int count;
            long totalBytes = 0;
            long maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
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

    private byte[] executePostBinary(String cmd, Map<String, String> params) throws IOException {
        String fullUrl = baseUrl + "?cmd=" + cmd;
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        URL url;
        try {
            url = URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
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
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        byte[] postData;
        StringBuilder bodyBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append("&");
            }
            bodyBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            bodyBuilder.append("=");
            bodyBuilder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        postData = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new HgProtocolException(fullUrl, "Remote Mercurial server returned HTTP " + status + " for URL: " + fullUrl);
        }

        String contentType = conn.getContentType();
        File tempFile = File.createTempFile("hg4j-post-", ".tmp");
        tempFile.deleteOnExit();

        try (InputStream in = unwrapResponseStream(conn.getInputStream(), contentType);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int count;
            long totalBytes = 0;
            long maxResponseBytes = 100 * 1024 * 1024; // 100MB response size guard to prevent DoS OOM
            while ((count = in.read(buf)) != -1) {
                totalBytes += count;
                if (totalBytes > maxResponseBytes) {
                    throw new HgProtocolException(fullUrl, "Security Guard: Remote server response size exceeds maximum allowed limit (100MB)");
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
                throw new HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
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
                throw new HgProtocolException("", "Unexpected EOF inside mercurial-0.2 chunk payload");
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
                    throw new HgProtocolException("", "Unexpected EOF while reading mercurial-0.2 chunk length");
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
                throw new HgProtocolException("", "Invalid negative chunk length: " + len);
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
                    throw new HgProtocolException("", "Unexpected EOF while reading compression header in application/mercurial-0.2 stream");
                }
                read += count;
            }
            String compName = new String(compNameBytes, StandardCharsets.US_ASCII).trim();
            
            // application/mercurial-0.2 ALWAYS uses chunked framing for the rest of the payload
            InputStream chunkedIn = new MercurialChunkedInputStream(in);

            if ("zlib".equalsIgnoreCase(compName) || "deflate".equalsIgnoreCase(compName)) {
                return new InflaterInputStream(chunkedIn);
            } else if ("none".equalsIgnoreCase(compName) || compName.isEmpty()) {
                return chunkedIn;
            } else {
                throw new HgProtocolException("", "Unsupported compression format in application/mercurial-0.2 framing: " + compName);
            }
        } else if (contentType != null && contentType.contains("application/mercurial-0.1")) {
            // Automatically detect and decompress application/mercurial-0.1 raw deflate (zlib) streams
            PushbackInputStream pbis = new PushbackInputStream(in, 2);
            int b1 = pbis.read();
            int b2 = pbis.read();
            if (b1 == 0x78 && (b2 == 0x9C || b2 == 0x01 || b2 == 0x5E || b2 == 0xDA)) {
                pbis.unread(b2);
                pbis.unread(b1);
                return new InflaterInputStream(pbis);
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
    /**
     * Pushes a changegroup bundle to the remote repository using the 'unbundle' command.
     *
     * @param bundleBytes the binary changegroup bundle payload
     * @param heads the local heads being pushed
     * @return the remote server response output string
     * @throws IOException if network or push execution fails
     */
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        if (delegate != null) {
            return delegate.push(bundleBytes, heads);
        }
        String fullUrl = baseUrl + "?cmd=unbundle";
        if (heads != null && !heads.isEmpty()) {
            fullUrl += "&heads=" + String.join("+", heads);
        }
        
        if (forceTls && !fullUrl.toLowerCase().startsWith("https://")) {
            throw new SecurityException("TLS is enforced but the URL is not secure: " + fullUrl);
        }

        URL url;
        try {
            url = URI.create(fullUrl).toURL();
        } catch (Exception e) {
            throw new HgTransportException(fullUrl, "Malformed URL: " + fullUrl, e);
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
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bundleBytes);
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new HgAuthException(fullUrl, username != null ? username : "anonymous");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new HgProtocolException(fullUrl, "Remote server returned HTTP " + status + " for unbundle: " + fullUrl);
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int count;
            long totalRead = 0;
            while ((count = in.read(buf)) != -1) {
                totalRead += count;
                if (totalRead > 10 * 1024 * 1024) { // 10MB Limit Guard
                    throw new HgProtocolException(fullUrl, "HTTP response size exceeded 10MB safety threshold under push");
                }
                out.write(buf, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public Map<String, String> listKeys(String namespace) throws IOException {
        if (delegate != null) {
            return delegate.listKeys(namespace);
        }
        // Fallback to GET for V1
        String resp = executeGet("listkeys?namespace=" + namespace);
        Map<String, String> map = new HashMap<>();
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
        String resp = executeGet("between?pairs=" + URLEncoder.encode(String.join(" ", pairs), "UTF-8"));
        List<String> list = new ArrayList<>();
        if (resp != null && !resp.trim().isEmpty()) {
            for (String val : resp.trim().split("\\s+")) {
                list.add(val.trim());
            }
        }
        return list;
    }

    @Override
    public String known(List<String> nodes) throws IOException {
        return executeGet("known?nodes=" + URLEncoder.encode(String.join(" ", nodes), "UTF-8"));
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        if (delegate != null) {
            return delegate.pushkey(namespace, key, oldVal, newVal);
        }
        Map<String, String> params = new HashMap<>();
        params.put("namespace", namespace);
        params.put("key", key);
        params.put("old", oldVal != null ? oldVal : "");
        params.put("new", newVal != null ? newVal : "");
        byte[] bytes = executePostBinary("pushkey", params);
        String resp = new String(bytes, StandardCharsets.UTF_8).trim();
        return "1".equals(resp) || "true".equalsIgnoreCase(resp) || resp.isEmpty();
    }

    @Override
    public void close() {
        // HTTP connections are managed and closed at the method level
    }
}
