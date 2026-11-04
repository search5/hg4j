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
import java.io.ByteArrayOutputStream;
import java.util.zip.DeflaterOutputStream;

/**
 * Production HTTP server for real hg's wireprotocol v1 ({@code ?cmd=} GET/POST), analogous
 * to JGit's {@code org.eclipse.jgit.http.server} module ({@code GitServlet}, {@code
 * UploadPackServlet}, {@code ReceivePackServlet}): pure transport glue over the protocol-agnostic
 * core {@link Wire1Commands}. Like Mercurial 7.2, which no longer serves wire protocol v2, it answers a v2 upgrade request
 * ({@code X-HgUpgrade-1}) with the ordinary v1 capabilities.
 *
 * <p>A plain {@link HttpServlet} — deploy it in whatever servlet container the embedding
 * application already uses (e.g. {@code ServletContextHandler.addServlet(new
 * ServletHolder(new HgHttpWireServer(repo)), "/*")} on Jetty), matching JGit's own
 * {@code GitServlet} being a servlet rather than owning its own listening socket.</p>
 */
public class HgHttpWireServer extends HttpServlet {

    private final HgRepository repository;
    private final List<HgHook> preChangegroupHooks = new ArrayList<>();
    private final List<HgHook> preTxnChangegroupHooks = new ArrayList<>();
    private final List<HgHook> postChangegroupHooks = new ArrayList<>();
    // yona-wiki P3-21/P3-22 — see Wire1Commands#pushkey(HgRepository, Map, List, List): the
    // changegroup hooks above never see which ref (bookmark) moved, only raw changeset nodes —
    // that only happens in the separate `pushkey` wire command, hence a separate hook list.
    /** When true the {@code [web]} access rules of the repository are enforced like real hgweb does (default: off, all requests allowed). */
    private boolean enforceWebPermissions;
    /** Names the authenticated user of a request (Mercurial's {@code REMOTE_USER}); the container's remote user by default. */
    private java.util.function.Function<HttpServletRequest, String> remoteUser = HttpServletRequest::getRemoteUser;
    private final List<HgHook> prePushkeyHooks = new ArrayList<>();
    private final List<HgHook> postPushkeyHooks = new ArrayList<>();

    /**
     * Creates a servlet serving the given repository over wire protocol v1. Web access rules are not
     * enforced unless {@link #setEnforceWebPermissions(boolean)} is called. Register hooks before the
     * servlet starts serving requests; the hook lists are not synchronized.
     * @param repository the repository to serve; must not be null
     */
    public HgHttpWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /** Registers a hook run before an incoming {@code unbundle} (push) is applied — returning
     * {@code false} aborts it before anything is written, real hg's {@code pretxnchangegroup}. */
    public HgHttpWireServer registerPreChangegroupHook(HgHook hook) {
        preChangegroupHooks.add(hook);
        return this;
    }

    /**
     * Registers a hook run AFTER the incoming changegroup has been applied, abort-capable: returning {@code false} undoes the push, real hg's
     * {@code pretxnchangegroup} (the hook seen by {@link #registerPreChangegroupHook} runs before anything is written, like {@code prechangegroup}).
     */
    public HgHttpWireServer registerPreTxnChangegroupHook(HgHook hook) {
        preTxnChangegroupHooks.add(hook);
        return this;
    }

    /**
     * Registers a notification-only hook run after an incoming push has been applied, like real hg's
     * {@code changegroup} hook.
     * @param hook the hook to add; must not be null
     * @return this server
     */
    public HgHttpWireServer registerPostChangegroupHook(HgHook hook) {
        postChangegroupHooks.add(hook);
        return this;
    }

    /** Registers a hook run before an incoming {@code pushkey} (e.g. a bookmark move — the actual
     * "branch update" wire event, see {@link Wire1Commands#pushkey}) is applied — returning
     * {@code false} aborts it, surfaced to the client as the real hg pushkey failure response. */
    public HgHttpWireServer registerPrePushkeyHook(HgHook hook) {
        prePushkeyHooks.add(hook);
        return this;
    }

    /** Registers a notification-only hook run after an incoming {@code pushkey} has been applied successfully. */
    public HgHttpWireServer registerPostPushkeyHook(HgHook hook) {
        postPushkeyHooks.add(hook);
        return this;
    }

    /**
     * Enforces the {@code [web]} access rules ({@code allow-push}, {@code deny_push}, {@code allow_read}, {@code deny_read},
     * {@code allowpull}, {@code push_ssl}) of the served repository exactly like Mercurial's own web server, which refuses a push
     * unless {@code allow-push} names the user and {@code push_ssl} is off or the request is HTTPS. Off by default: an
     * unconfigured hg4j server accepts every request.
     */
    public HgHttpWireServer setEnforceWebPermissions(boolean enforce) {
        this.enforceWebPermissions = enforce;
        return this;
    }

    /** Sets how the authenticated user of a request is determined (default: {@link HttpServletRequest#getRemoteUser()}). */
    public HgHttpWireServer setRemoteUserResolver(java.util.function.Function<HttpServletRequest, String> resolver) {
        this.remoteUser = resolver;
        return this;
    }

    /** Applies the access rules; answers a refusal like real hg ({@code 0\n<message>\n}) and returns false. */
    private boolean permitted(HttpServletRequest request, HttpServletResponse response, HgWebPermissions.Operation operation)
            throws IOException {
        if (!enforceWebPermissions) {
            return true;
        }
        try {
            HgWebPermissions.check(repository.getConfig(), request.getScheme(), request.getMethod(),
                    remoteUser.apply(request), operation);
            return true;
        } catch (HgWebPermissions.Denied denied) {
            sendPlain(response, denied.status(), "0\n" + denied.getMessage() + "\n");
            return false;
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            repository.refreshIfChangedOnDisk();
            String path = request.getRequestURI();
            String query = request.getQueryString();

            // the LFS endpoints (Batch API and Basic transfer) of an lfs-enabled repository, like real hg's wrapped
            // handlewsgirequest: tried when the request is not a wire protocol request
            String applicationPath = path.startsWith("/") ? path.substring(1) : path;
            if (queryParam(query, "cmd") == null
                    && io.github.search5.hg4j.lfs.server.HgLfsProtocolHandler.handles(repository, applicationPath)) {
                HgWebPermissions.Operation lfsOperation = null;
                if (applicationPath.equals(".git/info/lfs/objects/batch")) {
                    lfsOperation = HgWebPermissions.Operation.PULL; // real hg checks 'pull' even for an upload batch
                } else if (applicationPath.split("/").length == 4) {
                    lfsOperation = "PUT".equals(request.getMethod()) ? HgWebPermissions.Operation.UPLOAD
                            : "GET".equals(request.getMethod()) ? HgWebPermissions.Operation.PULL : null;
                }
                if (lfsOperation != null && !permitted(request, response, lfsOperation)) {
                    return;
                }
                io.github.search5.hg4j.lfs.server.HgLfsProtocolHandler.handle(repository, applicationPath, request, response);
                return;
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

    private void handleV1Command(HttpServletRequest request, HttpServletResponse response, String cmd, String query) throws Exception {
        if (!Wire1Commands.isKnownCommand(cmd)) {
            // wireprotoserver.py / hgweb: an unknown command is HTTP 400, not a protocol-level error body
            sendPlain(response, 400, "no such method: " + cmd);
            return;
        }
        if (!permitted(request, response, Wire1Commands.requiresPush(cmd)
                ? HgWebPermissions.Operation.PUSH : HgWebPermissions.Operation.PULL)) {
            return;
        }
        if (Wire1Commands.requiresPush(cmd) && !"POST".equals(request.getMethod())) {
            // hgweb: a command with push permission is refused unless it is a POST ("0\n" is the usual error prefix)
            sendPlain(response, 405, "0\npush requires POST request\n");
            return;
        }
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
            Wire1Response raced = Wire1Commands.preCheckUnbundleHeads(repository, args);
            if (raced != null) {
                sendPlainHgType(response, "0\n" + raced.getErrorMessage() + "\n");
                return;
            }
            byte[] bundleBytes;
            try (InputStream in = request.getInputStream()) {
                bundleBytes = in.readAllBytes();
            }
            wireResponse = Wire1Commands.unbundle(repository, bundleBytes, args, preChangegroupHooks, preTxnChangegroupHooks, postChangegroupHooks, prePushkeyHooks, postPushkeyHooks, true);
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
            // yona-wiki P3-21/P3-22 — pushkey (bookmark moves) is handled directly rather than via
            // dispatch()/batch() so branch-protection/notification hooks apply the same way
            // unbundle's hooks do above; real hg clients never batch pushkey with other commands
            // anyway (it's the actual write, not discovery — see Wire1Commands#batch's doc).
            wireResponse = "pushkey".equals(cmd)
                    ? Wire1Commands.pushkey(repository, args, prePushkeyHooks, postPushkeyHooks)
                    : dispatch(cmd, args);
        }

        switch (wireResponse.getKind()) {
            case PUSH_RESULT -> sendPlainHgType(response, wireResponse.getPushResult() + "\n" + wireResponse.getServerOutput());
            case PUSH_ERROR -> sendPlainHgType(response, "0\n" + wireResponse.getErrorMessage() + "\n");
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
                // Real hg's server contract: `streamres` (changegroup/getbundle/unbundle) is the ONLY response kind that gets
                // compressed. A client that sent "0.2" in X-HgProto-1 gets media type 0.2 (a length byte and the name of the
                // compression engine, then the compressed stream); any other client, and one that shares no engine with this
                // server, gets 0.1 with zlib (wireprotoserver.py's _httpresponsetype).
                String engine = null;
                List<String> protoCaps = List.of(reassembleHeaderChain(request, "X-HgProto").split(" "));
                if (protoCaps.contains("0.2")) {
                    List<String> clientEngines = List.of("zlib", "none");
                    for (String cap : protoCaps) {
                        if (cap.startsWith("comp=")) {
                            clientEngines = List.of(cap.substring(5).split(","));
                        }
                    }
                    if (wireResponse.isPreferUncompressed()) {
                        engine = "none"; // all clients are expected to support uncompressed data
                    } else {
                        for (String candidate : Wire1Commands.httpCompressionEngines(repository)) {
                            if (clientEngines.contains(candidate)) {
                                engine = candidate;
                                break;
                            }
                        }
                    }
                }
                byte[] body;
                if (engine != null) {
                    ByteArrayOutputStream framed = new ByteArrayOutputStream();
                    framed.write(engine.length());
                    framed.write(engine.getBytes(StandardCharsets.US_ASCII));
                    framed.write(compress(engine, wireResponse.getPayload()));
                    body = framed.toByteArray();
                    response.setHeader("Content-Type", "application/mercurial-0.2");
                } else {
                    body = deflate(wireResponse.getPayload());
                    response.setHeader("Content-Type", "application/mercurial-0.1");
                }
                response.setStatus(200);
                response.setContentLength(body.length);
                try (OutputStream out = response.getOutputStream()) {
                    out.write(body);
                }
            }
        }
    }

    /** One of the HTTP compression engines ({@code zstd}, {@code zlib}, {@code bzip2}, {@code none}) applied to a whole payload. */
    private static byte[] compress(String engine, byte[] data) throws IOException {
        switch (engine) {
            case "none":
                return data;
            case "zlib":
                return deflate(data);
            case "zstd": {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (com.github.luben.zstd.ZstdOutputStream zos = new com.github.luben.zstd.ZstdOutputStream(baos)) {
                    zos.write(data);
                }
                return baos.toByteArray();
            }
            case "bzip2": {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream bos =
                             new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(baos)) {
                    bos.write(data);
                }
                return baos.toByteArray();
            }
            default:
                throw new IOException("unknown compression engine " + engine);
        }
    }

    /** A plain answer in the v1 media type ({@code application/mercurial-0.1}). */
    private static void sendPlainHgType(HttpServletResponse response, String text) throws IOException {
        response.setHeader("Content-Type", "application/mercurial-0.1");
        sendPlain(response, 200, text);
    }

    private static void sendPlain(HttpServletResponse response, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentLength(body.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body);
        }
    }

    private Wire1Response dispatch(String cmd, Map<String, String> args) throws Exception {
        if ("batch".equals(cmd)) {
            return Wire1Commands.batch(repository, args);
        }
        if ("capabilities".equals(cmd)) {
            return Wire1Commands.capabilitiesHttp(repository);
        }
        if ("hello".equals(cmd)) {
            return Wire1Commands.helloHttp(repository);
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
