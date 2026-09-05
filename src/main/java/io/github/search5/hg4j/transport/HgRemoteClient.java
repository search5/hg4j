package io.github.search5.hg4j.transport;

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
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.errors.HgTransportException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.Collections;
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
    // Real hg only uses the GET+X-HgArg-N tier when the server actually advertised
    // httpheader=<N>; a server that never mentions it (very old, or minimal/test servers) gets
    // the 3rd, legacy tier -- args appended straight to the query string -- instead. Tracking
    // this separately from maxHttpHeaderLimit (which keeps its 1024 default either way, as a
    // sizing fallback for that legacy tier's own X-HgProto-1 splitting) matters: defaulting to
    // "httpheader capability present" would make hg4j send X-HgArg-N headers a server never
    // asked for and never parses.
    private boolean sawHttpHeaderCap = false;
    private boolean supportsV2 = false;
    private boolean supportsClonebundles = false;
    private boolean supportsHttpPostArgs = false;
    private boolean supportsUnbundleHash = false;
    private List<String> httpMediaTypes = Collections.emptyList();
    private List<String> compressionEngines = Collections.emptyList();
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
                    this.sawHttpHeaderCap = true;
                } catch (NumberFormatException ignored) {}
            }
            if ("clonebundles".equals(cap)) {
                this.supportsClonebundles = true;
            }
            if ("httppostargs".equals(cap)) {
                this.supportsHttpPostArgs = true;
            }
            if ("unbundlehash".equals(cap)) {
                this.supportsUnbundleHash = true;
            }
            if (cap.startsWith("httpmediatype=")) {
                this.httpMediaTypes = Arrays.asList(cap.substring("httpmediatype=".length()).split(","));
            }
            if (cap.startsWith("compression=")) {
                this.compressionEngines = Arrays.asList(cap.substring("compression=".length()).split(","));
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
    @Override
    public boolean supportsClonebundles() {
        return supportsClonebundles;
    }

    /**
     * Fetches the raw text of the remote's clonebundles manifest via {@code ?cmd=clonebundles} —
     * real hg's {@code wireprotov1server.py} {@code clonebundles()} command, which just returns
     * the server repository's {@code .hg/clonebundles.manifest} file content verbatim. Parse the
     * result with {@link io.github.search5.hg4j.bundle.ClonebundlesManifest#parse(String)}.
     *
     * <p>This call itself still goes over the wire protocol (it's a normal {@code ?cmd=} request);
     * the actual "bypass" happens afterward, when the caller downloads the bundle from whatever
     * URL an entry in the manifest names — a plain HTTP(S) GET with no wire-protocol framing at
     * all, see {@code decisions/mercurial-spec-compliance-requirement.md}'s Clonebundles plan.</p>
     */
    @Override
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
        // new String(bytes, ...) never returns null, so the "resp != null" check some earlier
        // version of this code had here was always true -- dead code, removed.
        String resp = new String(bytes, StandardCharsets.UTF_8);
        if (!resp.trim().isEmpty()) {
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
        if (!resp.trim().isEmpty()) {
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
        // Real hg's "roots" is a required fixed arg of the "changegroup" wire command (spec:
        // "roots") -- it must always be present in the request, sent as an empty string when
        // there are no roots (meaning "give me the entire history"), never omitted. Real hg's
        // server-side arg decoder (wireprotoserver.py's getargs()) does a plain dict lookup for
        // every declared arg name with no default, so a request that omits the key entirely
        // raises an uncaught KeyError server-side -- an HTTP 500, not a clean error -- confirmed
        // 2026-09-05 while building HgWireProtocolMatrixIncomingOutgoingTest: IncomingCommand's
        // full-history request (`getChangegroup(Collections.emptyList())`) failed against every
        // single wire combo with exactly this HTTP 500 because this method used to only add
        // "roots" to the params map when the list was non-empty. HgSshClient.getChangegroup()
        // already got this right (see its own comment) -- this brought the HTTP client in line
        // with it.
        Map<String, String> params = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        if (roots != null) {
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(roots.get(i));
            }
        }
        params.put("roots", sb.toString());
        return executeArgsCommand("changegroup", params);
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
            // 실제 스펙(wireprototypes.GETBUNDLE_ARGUMENTS): bundlecaps는 "scsv" 타입 —
            // 최상위 토큰 구분자가 콤마다(스페이스 아님). 예전에 스페이스로 join하던 코드는
            // 콤마가 하나도 없는 토큰 하나로 뭉쳐져 "HG2"로 시작하는 토큰이 하나도 안 남아
            // exchange.bundle2requested()가 항상 false가 되고, 결과적으로 어떤 changegroup
            // 버전을 광고하든 항상 구식 bundle1(cg1)로 조용히 폴백했다(2026-09-03 발견 —
            // 실제 hg 7.2.2로 로깅 프록시를 붙여 실제 클라이언트 요청 바이트를 직접 캡처해
            // 확인, Bundle2Parser#buildChangegroupBundleCaps 주석 참고).
            params.put("bundlecaps", String.join(",", bundleCaps));
        } else {
            // Default capabilities compatible with bundle2 and legacy changegroups.
            // changegroup=01..05: 원격이 max(교집합)으로 버전을 고르므로(exchange.py 실측),
            // hg4j가 cg4/cg5 델타 헤더도 파싱할 수 있게 된 뒤로는(2026-09-03) 04/05까지
            // 광고해야 최신 hg와 최적 포맷으로 주고받는다. changegroup 버전 목록은
            // "bundle2=<blob>" 토큰 안에 중첩돼야만 실제로 반영된다 — 위 주석 참고.
            params.put("bundlecaps",
                    io.github.search5.hg4j.bundle.Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05")
                            + ",compression=GZ,BZ,ZS");
        }
        
        return executeArgsCommand("getbundle", params);
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

    /**
     * Sends an argument-bearing v1 command using real hg's {@code makev1commandrequest()}
     * fallback chain (from {@code mercurial/httppeer.py}), captured directly from a real
     * {@code hg --debug clone} session via a raw TCP logging proxy: hg4j previously always sent
     * these as an HTTP POST with a form body, which a real server's {@code cgi.FieldStorage}-based
     * arg parser never reads for a v1 GET-oriented command like {@code getbundle} — the server
     * silently saw no {@code bundlecaps} argument at all and fell back to legacy bundle1 (cg1)
     * regardless of what version list hg4j advertised (2026-09-03 discovery).
     *
     * <ol>
     * <li>Server advertises {@code httppostargs}: POST body = urlencoded args, header
     * {@code X-HgArgs-Post: <len>}.</li>
     * <li>Otherwise, if the server advertised {@code httpheader=<N>} (the common case for a real
     * hg server): GET request with no query-string args; the urlencoded arg string is instead
     * split across {@code X-HgArg-1}, {@code X-HgArg-2}, ... request headers sized to fit the
     * server's byte budget, exactly matching real hg's actual captured request shape.</li>
     * <li>Otherwise (neither capability was seen — a very old or minimal/test server): the
     * legacy 3rd tier, args appended straight onto the query string as plain GET.</li>
     * </ol>
     *
     * <p>Whichever GET tier is used, the request also carries an {@code X-HgProto-1} header
     * (itself header-split the same way) built from the negotiated {@code httpmediatype=}/
     * {@code compression=} capability tokens, real hg's {@code protoparams} construction.</p>
     */
    private byte[] executeArgsCommand(String cmd, Map<String, String> params) throws IOException {
        String encodedArgs = encodeArgsSorted(params);

        if (supportsHttpPostArgs) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-HgArgs-Post", String.valueOf(encodedArgs.length()));
            return executePostBinaryWithHeaders(cmd, encodedArgs.getBytes(StandardCharsets.UTF_8), headers);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        List<String> varyNames = new ArrayList<>();
        if (sawHttpHeaderCap && !encodedArgs.isEmpty()) {
            varyNames.addAll(splitIntoHeaders("X-HgArg", encodedArgs, maxHttpHeaderLimit, headers));
        }
        String proto1 = buildXHgProto1Header();
        if (!proto1.isEmpty()) {
            varyNames.addAll(splitIntoHeaders("X-HgProto", proto1, maxHttpHeaderLimit, headers));
        }
        if (!varyNames.isEmpty()) {
            headers.put("Vary", String.join(",", varyNames));
        }
        if (sawHttpHeaderCap) {
            return executeGetBinary(cmd, headers);
        }
        // Legacy 3rd tier: no httpheader= capability seen, so append args straight to the query
        // string instead of using X-HgArg-N headers the server never asked for.
        String cmdWithArgs = encodedArgs.isEmpty() ? cmd : cmd + "?" + encodedArgs;
        return executeGetBinary(cmdWithArgs, headers);
    }

    /** {@code urlencode(sorted(args.items()))} — matches real hg's own arg-encoding order. */
    private static String encodeArgsSorted(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(params.get(key), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Real hg's {@code encodevalueinheaders(value, header, limit)}: splits {@code value} into
     * {@code <headerPrefix>-1}, {@code <headerPrefix>-2}, ... chunks sized so each header line
     * (name + {@code ": "} + value + {@code "\r\n"}) stays within {@code limit} bytes, using the
     * same (slightly conservative, single-digit-index) overhead estimate real hg uses.
     */
    private static List<String> splitIntoHeaders(String headerPrefix, String value, int limit,
            Map<String, String> outHeaders) {
        int overhead = (headerPrefix + "-0").length() + 4; // ": " + "\r\n"
        int chunkSize = Math.max(1, limit - overhead);
        List<String> names = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < value.length(); i += chunkSize) {
            n++;
            String name = headerPrefix + "-" + n;
            outHeaders.put(name, value.substring(i, Math.min(i + chunkSize, value.length())));
            names.add(name);
        }
        return names;
    }

    /**
     * Real hg's {@code protoparams} construction (httppeer.py), built only once the server's
     * {@code httpmediatype=} token is known: always {@code "0.1"} + {@code "partial-pull"}, plus
     * {@code "0.2"} (and {@code "comp=<engines>"} if the server also has a {@code compression=}
     * token) when the server's media type list includes {@code "0.2tx"}. Joined sorted, matching
     * real hg's {@code b' '.join(sorted(protoparams))} — confirmed against a captured real
     * request: {@code "0.1 0.2 comp=zstd,zlib,none,bzip2 partial-pull"}.
     */
    private String buildXHgProto1Header() {
        if (httpMediaTypes.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("0.1");
        parts.add("partial-pull");
        if (httpMediaTypes.contains("0.2tx")) {
            parts.add("0.2");
            if (!compressionEngines.isEmpty()) {
                parts.add("comp=" + String.join(",", compressionEngines));
            }
        }
        Collections.sort(parts);
        return String.join(" ", parts);
    }

    /** POST tier of {@link #executeArgsCommand} — raw body + caller-supplied headers, no arg-encoding of its own. */
    private byte[] executePostBinaryWithHeaders(String cmd, byte[] body, Map<String, String> extraHeaders) throws IOException {
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
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/mercurial-0.1, application/mercurial-0.2");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            conn.setRequestProperty(header.getKey(), header.getValue());
        }

        if (username != null && password != null) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        conn.setRequestProperty("Content-Length", String.valueOf(body.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
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

            // Real hg's actual v1 -0.2 wire format (confirmed 2026-09-03 by capturing a real
            // Mercurial 7.2.4 server's raw response bytes): [1-byte namelen][name][compressed
            // payload straight through to end of stream] -- there is NO additional inner
            // chunk-length framing on top; the payload's own magic bytes (zstd's 28 B5 2F FD,
            // zlib's 78 9C) begin immediately after the name. An earlier version of this method
            // assumed an extra 4-byte-length-prefixed chunk layer here that real hg never sends;
            // that was never caught because hg4j's own server (HgHttpWireServer) never emits -0.2
            // responses at all (always -0.1), so the two ends of this codebase were only ever
            // tested against each other -- this path had literally never been exercised against a
            // real server until the X-HgArg-N / X-HgProto-1 fix above made one finally choose -0.2.
            if ("zlib".equalsIgnoreCase(compName) || "deflate".equalsIgnoreCase(compName)) {
                return new InflaterInputStream(in);
            } else if ("zstd".equalsIgnoreCase(compName)) {
                return new com.github.luben.zstd.ZstdInputStream(in);
            } else if ("none".equalsIgnoreCase(compName) || compName.isEmpty()) {
                return in;
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
        List<String> wireHeads = NodeIdUtil.computeUnbundleHeadsWireValue(heads, supportsUnbundleHash);
        if (!wireHeads.isEmpty()) {
            fullUrl += "&heads=" + String.join("+", wireHeads);
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
        if (!resp.trim().isEmpty()) {
            for (String line : resp.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab != -1) {
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return map;
    }

    /** Real hg wireproto v1 {@code branchmap} response: one line per branch, {@code
     * "<url-quoted-branch-name> <hex1> <hex2> ..."}, lines joined by {@code \n} (mercurial's own
     * {@code wireprotov1server.branchmap}). */
    @Override
    public Map<String, List<String>> getBranchHeads() throws IOException {
        if (delegate != null) {
            return delegate.getBranchHeads();
        }
        String resp = executeGet("branchmap");
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

    @Override
    public List<String> between(List<String> pairs) throws IOException {
        String resp = executeGet("between?pairs=" + URLEncoder.encode(String.join(" ", pairs), "UTF-8"));
        List<String> list = new ArrayList<>();
        if (!resp.trim().isEmpty()) {
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
        byte[] bytes = executePushkeyCommand(params);
        String resp = new String(bytes, StandardCharsets.UTF_8).trim();
        return "1".equals(resp) || "true".equalsIgnoreCase(resp) || resp.isEmpty();
    }

    /**
     * {@code pushkey} needs its own request builder rather than the generic {@link
     * #executeArgsCommand}: real hg's HTTP server enforces POST for ANY push-permission command
     * ({@code mercurial/hgweb/common.py checkauthz}: {@code "push requires POST request"}) --
     * NOT conditionally on whether {@code httppostargs} was negotiated, which only controls
     * where the ARGUMENTS travel (POST body vs query string/{@code X-HgArg} headers), not the
     * HTTP method itself. Real hg's own client special-cases exactly this
     * ({@code mercurial/httppeer.py makev1commandrequest}: {@code if cmd == b'pushkey':
     * args[b'data'] = b''}, which forces urllib to POST even with an empty body) -- without it,
     * a push against a real hg server that hasn't negotiated {@code httppostargs} (the common
     * case: it's an experimental, off-by-default feature) would fall back to {@link
     * #executeArgsCommand}'s GET-based tiers, get a 405 from the server, and (since {@link
     * io.github.search5.hg4j.api.PushCommand} treats a failed bookmark-sync as a non-fatal,
     * logged-only warning) silently fail to move the remote's bookmark at all -- reproduced
     * against real hg 7.2.2 over HTTP, 2026-09-04.
     */
    private byte[] executePushkeyCommand(Map<String, String> params) throws IOException {
        String encodedArgs = encodeArgsSorted(params);

        if (supportsHttpPostArgs) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-HgArgs-Post", String.valueOf(encodedArgs.length()));
            return executePostBinaryWithHeaders("pushkey", encodedArgs.getBytes(StandardCharsets.UTF_8), headers);
        }

        // Same arg-placement tiers as executeArgsCommand's non-postargsok branch (X-HgArg
        // headers if the server advertised httpheader=, else the query string), but always
        // POSTed with an empty body instead of GET.
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> varyNames = new ArrayList<>();
        if (sawHttpHeaderCap && !encodedArgs.isEmpty()) {
            varyNames.addAll(splitIntoHeaders("X-HgArg", encodedArgs, maxHttpHeaderLimit, headers));
        }
        String proto1 = buildXHgProto1Header();
        if (!proto1.isEmpty()) {
            varyNames.addAll(splitIntoHeaders("X-HgProto", proto1, maxHttpHeaderLimit, headers));
        }
        if (!varyNames.isEmpty()) {
            headers.put("Vary", String.join(",", varyNames));
        }
        String cmd = "pushkey";
        if (!sawHttpHeaderCap && !encodedArgs.isEmpty()) {
            cmd = cmd + "?" + encodedArgs;
        }
        return executePostBinaryWithHeaders(cmd, new byte[0], headers);
    }

    @Override
    public void close() {
        // HTTP connections are managed and closed at the method level
    }
}
