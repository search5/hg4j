package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Commands;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Response;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Transport;
import java.io.ByteArrayOutputStream;
import java.util.zip.DeflaterOutputStream;

/**
 * Production HTTP server for real hg's wireprotocol v1 ({@code ?cmd=} GET/POST) and v2
 * (capability-upgrade discovery + {@code /api/<namespace>/<ro|rw>/<command>} framing), analogous
 * to JGit's {@code org.eclipse.jgit.http.server} module ({@code GitServlet}, {@code
 * UploadPackServlet}, {@code ReceivePackServlet}): pure transport glue over the protocol-agnostic
 * cores, {@link Wire1Commands} (v1) and {@code Wire2Commands}/{@code Wire2Transport} (v2).
 *
 * <p>A plain {@link HttpServlet} — deploy it in whatever servlet container the embedding
 * application already uses (e.g. {@code ServletContextHandler.addServlet(new
 * ServletHolder(new HgHttpWireServer(repo)), "/*")} on Jetty), matching JGit's own
 * {@code GitServlet} being a servlet rather than owning its own listening socket.</p>
 */
public class HgHttpWireServer extends HttpServlet {

    private final HgRepository repository;
    private final List<HgHook> preChangegroupHooks = new ArrayList<>();
    private final List<HgHook> postChangegroupHooks = new ArrayList<>();

    public HgHttpWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /** Registers a hook run before an incoming {@code unbundle} (push) is applied — returning
     * {@code false} aborts it before anything is written, real hg's {@code pretxnchangegroup}. */
    public HgHttpWireServer registerPreChangegroupHook(HgHook hook) {
        preChangegroupHooks.add(hook);
        return this;
    }

    /** Registers a notification-only hook run after an incoming push has been applied — real hg's {@code changegroup}. */
    public HgHttpWireServer registerPostChangegroupHook(HgHook hook) {
        postChangegroupHooks.add(hook);
        return this;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            repository.refreshIfChangedOnDisk();
            String path = request.getRequestURI();
            String query = request.getQueryString();

            if ("/".equals(path) && query != null && query.contains("cmd=capabilities")
                    && request.getHeader("X-HgUpgrade-1") != null) {
                response.setStatus(200);
                response.setHeader("Content-Type", "application/mercurial-cbor");
                try (OutputStream out = response.getOutputStream()) {
                    handleCapabilitiesDiscovery(Wire1Commands.capabilitiesString(repository), out);
                }
                return;
            }

            if (path.startsWith("/api/")) {
                String[] parts = path.substring("/api/".length()).split("/");
                if (parts.length == 3) {
                    // Buffer the whole response before sending headers -- unlike a v1 command
                    // (whose response is likewise fully assembled in memory before any header is
                    // written), sending headers first here would mean a request-parsing failure
                    // (e.g. a truncated multi-frame COMMAND_REQUEST) throws only after the
                    // response has already been committed as chunked, leaving the outer catch
                    // below unable to send its fallback "abort:" body -- the client would see an
                    // empty response instead of a diagnosable error.
                    byte[] responseBody;
                    try (InputStream in = request.getInputStream()) {
                        responseBody = handleWire2Request(parts[1], parts[2], in);
                    }
                    response.setStatus(200);
                    response.setHeader("Content-Type", Wire2Transport.FRAMINGTYPE);
                    response.setContentLength(responseBody.length);
                    try (OutputStream out = response.getOutputStream()) {
                        out.write(responseBody);
                    }
                    return;
                }
            }

            String cmd = queryParam(query, "cmd");
            if (cmd == null) {
                response.setStatus(404);
                return;
            }
            handleV1Command(request, response, cmd, query);
        } catch (Exception e) {
            byte[] body = ("abort: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            response.setStatus(200);
            response.setHeader("Content-Type", "application/mercurial-0.1");
            response.setContentLength(body.length);
            try (OutputStream out = response.getOutputStream()) {
                out.write(body);
            }
        }
    }

    /**
     * Real hg's capability-discovery handshake response, sent from the root URL
     * ({@code /?cmd=capabilities}) when the request carries {@code X-HgUpgrade-1}/
     * {@code X-HgProto-1} headers (checked by the caller) — {@code {apibase, apis:
     * {<namespace>: {commands, framingmediatypes}}, v1capabilities}}, verified against a real
     * Mercurial 6.0 server (the last release with a working wireprotocol v2 implementation).
     *
     * @param v1CapabilitiesLine the same string the v1 {@code capabilities} command would return,
     *                           embedded verbatim as {@code v1capabilities}
     */
    private void handleCapabilitiesDiscovery(String v1CapabilitiesLine, OutputStream out) throws IOException {
        Map<String, Object> apis = new LinkedHashMap<>();
        apis.put(Wire2Commands.NAMESPACE,
                Wire2Commands.namespaceDescriptor());

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", apis);
        descriptor.put("v1capabilities", v1CapabilitiesLine == null ? "" : v1CapabilitiesLine);

        out.write(Cbor.encode(descriptor));
        out.flush();
    }

    /**
     * Real hg's per-command wireprotocol v2 HTTP handler, serving
     * {@code POST /api/<namespace>/<ro|rw>/<command>}: reads the frame-based
     * {@code application/mercurial-exp-framing-0006} command-request body, dispatches to
     * {@link io.github.search5.hg4j.transport.wireprotov2.Wire2Commands}, and writes back a
     * framed {@code {status: ok, ...}} (or {@code error}) response — the real wire shape,
     * verified against a live Mercurial 6.0 server.
     *
     * @param permission the {@code ro}/{@code rw} URL segment; the caller is responsible for
     *                   authenticating/authorizing it (real hg maps {@code ro}→pull, {@code rw}→push)
     * @param urlCommand the command name from the URL, which must match the frame's own command name
     * @return the fully assembled response body, for the caller to send after committing headers
     */
    private byte[] handleWire2Request(String permission, String urlCommand, InputStream in) throws IOException {
        boolean isMultirequest = "multirequest".equals(urlCommand);
        List<Wire2Transport.ParsedCommandRequest> commands =
                Wire2Transport.readAllCommandRequests(in);

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        if (!commands.isEmpty()) {
            // 실제 hg는 응답 스트림 전체에 stream-settings 프레임을 딱 한 번만 보낸다 —
            // multirequest로 여러 명령을 한 번에 처리할 때도 명령마다 다시 보내지 않는다
            // (real Mercurial 6.0 서버의 heads+known 배치 clone 요청으로 직접 확인, 2026-09-01).
            combined.write(Wire2Transport.buildStreamSettingsFrame(commands.get(0).requestId));
        }
        for (Wire2Transport.ParsedCommandRequest cmd : commands) {
            if (!isMultirequest && !cmd.name.equals(urlCommand)) {
                combined.write(Wire2Transport.buildCommandErrorResponse(
                        cmd.requestId, "command in frame must match command in URL"));
                continue;
            }
            try {
                List<Object> responseObjects = dispatchWire2Command(cmd.name, cmd.args);
                combined.write(Wire2Transport.buildCommandResponseFrames(cmd.requestId, responseObjects));
            } catch (HgProtocolException e) {
                combined.write(Wire2Transport.buildCommandErrorResponse(cmd.requestId, e.getMessage()));
            } catch (Exception e) {
                combined.write(Wire2Transport.buildCommandErrorResponse(cmd.requestId, String.valueOf(e.getMessage())));
            }
        }
        return combined.toByteArray();
    }

    private List<Object> dispatchWire2Command(String command, Map<String, Object> args) throws IOException {
        switch (command) {
            case "capabilities":
                return Wire2Commands.capabilities();
            case "heads":
                return Wire2Commands.heads(repository);
            case "known":
                return Wire2Commands.known(repository, args);
            case "listkeys":
                return Wire2Commands.listkeys(repository, args);
            case "lookup":
                return Wire2Commands.lookup(repository, args);
            case "pushkey":
                return Wire2Commands.pushkey(repository, args);
            case "branchmap":
                return Wire2Commands.branchmap(repository);
            case "changesetdata":
                return Wire2Commands.changesetdata(repository, args);
            case "manifestdata":
                return Wire2Commands.manifestdata(repository, args);
            case "filesdata":
                return Wire2Commands.filesdata(repository, args);
            default:
                throw new HgProtocolException("wireprotov2", "unsupported wire protocol v2 command: " + command);
        }
    }

    private void handleV1Command(HttpServletRequest request, HttpServletResponse response, String cmd, String query) throws Exception {
        Map<String, String> args = parseQueryParams(query);
        // Real hg's primary v1 arg transport for GET requests: the urlencoded arg string is split
        // across X-HgArg-1, X-HgArg-2, ... request headers (see HgRemoteClient#executeArgsCommand)
        // rather than appended to the query string. Reassemble and merge them in first so a
        // present query string or POST body (below) can still take precedence on collision.
        String reassembledArgHeaders = reassembleHeaderChain(request, "X-HgArg");
        if (!reassembledArgHeaders.isEmpty()) {
            args.putAll(parseQueryParams(reassembledArgHeaders));
        }

        Wire1Response wireResponse;
        if ("unbundle".equals(cmd)) {
            byte[] bundleBytes;
            try (InputStream in = request.getInputStream()) {
                bundleBytes = in.readAllBytes();
            }
            wireResponse = Wire1Commands.unbundle(repository, bundleBytes, args, preChangegroupHooks, postChangegroupHooks);
        } else {
            // Real hg sends some commands' args over GET query string, others as a POST
            // form-encoded body (and hg4j's own HgRemoteClient doesn't always agree with real hg
            // on which); accepting args from either location for every command (merged, POST body
            // taking precedence on collision) makes this server correct against both without
            // needing a per-command GET/POST table.
            if ("POST".equals(request.getMethod())) {
                byte[] body;
                try (InputStream in = request.getInputStream()) {
                    body = in.readAllBytes();
                }
                // Same urlencoded-args format whether this is the legacy form-encoded body or the
                // httppostargs tier's raw arg string (signalled by X-HgArgs-Post) -- one parse
                // covers both.
                args.putAll(parseQueryParams(new String(body, StandardCharsets.UTF_8)));
            }
            wireResponse = dispatch(cmd, args);
        }

        switch (wireResponse.getKind()) {
            case OOB_ERROR -> {
                // Real hg's ooberror uses a distinct content type and an always-uncompressed body
                // (wireprotoserver.py's _callhttp: setresponse(HTTP_OK, HGERRTYPE, bodybytes=...)).
                response.setStatus(200);
                response.setHeader("Content-Type", "application/hg-error");
                byte[] body = wireResponse.getErrorMessage().getBytes(StandardCharsets.UTF_8);
                response.setContentLength(body.length);
                try (OutputStream out = response.getOutputStream()) {
                    out.write(body);
                }
            }
            case BYTES, STREAM_UNCOMPRESSED -> {
                // Real hg only compresses `streamres` (Kind.STREAM) responses -- `bytesresponse`
                // (Kind.BYTES: heads/known/listkeys/lookup/pushkey/branchmap/capabilities) is
                // always sent as plain, uncompressed bytes under the same -0.1 content type
                // (wireprotoserver.py's _callhttp: "elif isinstance(rsp, bytesresponse):
                // setresponse(HTTP_OK, HGTYPE, bodybytes=rsp.data)" -- no compression call at
                // all). Confirmed by real-hg-as-client clone aborting with "unexpected response"
                // on a compressed `heads` response before this fix. `streamreslegacy` (Kind.
                // STREAM_UNCOMPRESSED -- real hg's bundle2 `unbundle` reply, backlog item 26)
                // gets the exact same uncompressed treatment ("elif isinstance(rsp,
                // streamreslegacy): setresponse(HTTP_OK, HGTYPE, bodygen=rsp.gen)" -- no
                // compression call either, unlike the plain `streamres` case just below).
                response.setStatus(200);
                response.setHeader("Content-Type", "application/mercurial-0.1");
                byte[] body = wireResponse.getPayload();
                response.setContentLength(body.length);
                try (OutputStream out = response.getOutputStream()) {
                    out.write(body);
                }
            }
            case STREAM -> {
                // Real hg's server contract: `streamres` (changegroup/getbundle/unbundle) is the
                // ONLY response kind that gets zlib-compressed under -0.1 (wireprotoserver.py's
                // comment: "we only compress streamres at the moment").
                response.setStatus(200);
                response.setHeader("Content-Type", "application/mercurial-0.1");
                byte[] body = deflate(wireResponse.getPayload());
                response.setContentLength(body.length);
                try (OutputStream out = response.getOutputStream()) {
                    out.write(body);
                }
            }
        }
    }

    private Wire1Response dispatch(String cmd, Map<String, String> args) throws Exception {
        if ("batch".equals(cmd)) {
            return Wire1Commands.batch(repository, args);
        }
        return Wire1Commands.dispatch(repository, cmd, args);
    }

    /** Real hg's application/mercurial-0.1 contract (see class doc) — plain zlib deflate, no gzip wrapper. */
    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Reassembles a real hg {@code encodevalueinheaders}-split header chain ({@code <prefix>-1},
     * {@code <prefix>-2}, ...) back into the single urlencoded string it was split from. Headers
     * are read in order starting at 1 and stop at the first missing index (real hg never leaves a
     * gap). {@code HttpServletRequest.getHeader} is case-insensitive, matching real HTTP semantics.
     */
    private static String reassembleHeaderChain(HttpServletRequest request, String prefix) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        while (true) {
            String chunk = request.getHeader(prefix + "-" + n);
            if (chunk == null) {
                break;
            }
            sb.append(chunk);
            n++;
        }
        return sb.toString();
    }

    private static String queryParam(String query, String name) {
        Map<String, String> params = parseQueryParams(query);
        return params.get(name);
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq == -1) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }
}
