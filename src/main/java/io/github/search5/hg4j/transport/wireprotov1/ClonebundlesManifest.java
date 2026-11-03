package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clone bundles manifest as Mercurial 7.2.4 serves it: a port of {@code exchanges/bundle_cache.py
 * get_manifest_lines()}, the two server commands of {@code wireprotov1server.py} ({@code clonebundles},
 * {@code clonebundles_manifest}) and the parts of {@code bundlecaches.py} they need
 * ({@code parse_clonebundle_manifest_line}, {@code parsebundlespec}).
 *
 * <p><b>Version note.</b> This follows the 7.2.4 RELEASE, which is what the interop tests run against. The development
 * tree under {@code ../hg} (after 2026-08) adds bundlespec "mandatory parameters", a {@code downgrade_manifest_lines}
 * step and {@code mandatory_params}/{@code store_fingerprint} arguments to {@code clonebundles_manifest}; none of that is
 * in a released Mercurial yet and it is deliberately not implemented here.</p>
 *
 * <p>The manifest is bytes and the original's behaviour is defined on bytes (ASCII whitespace splitting, byte-wise
 * percent decoding). Every line is therefore held as a Latin-1 string, which maps each byte to one char and back.</p>
 */
final class ClonebundlesManifest {

    static final String FILE_NAME = "clonebundles.manifest";
    static final String PEER_BUNDLE_CACHE_SCHEME = "peer-bundle-cache://";

    /** util.compengines.supportedbundlenames of a Rust-enabled build with zstd. */
    private static final Set<String> BUNDLE_COMPRESSIONS = Set.of("bzip2", "gzip", "none", "zstd");
    /** Compression engines allowed in bundle version 1 (never changes). */
    private static final Set<String> V1_COMPRESSIONS = Set.of("gzip", "bzip2", "none");
    /** bundlecaches._bundlespeccontentopts keys. */
    private static final Set<String> BUNDLE_VERSIONS = Set.of("v1", "v2", "v3", "streamv2", "streamv3-exp", "packed1", "bundle2");
    /** Parameters whose value must be a boolean (bundle_spec_param_processing). */
    private static final Set<String> BOOLEAN_PARAMS = Set.of("obsolescence", "obsolescence-mandatory", "phases",
            "changegroup", "tagsfnodescache", "revbranchcache");
    /** requirementsmod.STREAM_IGNORABLE_REQUIREMENTS: do not matter for applying a stream bundle. */
    private static final Set<String> STREAM_IGNORABLE_REQUIREMENTS = Set.of("store", "fncache", "dotencode", "share-safe",
            "shared", "relshared", "exp-sharesafe", "bookmarksinstore", "dirstate-v2", "dirstate-tracked-hint-v1");
    /** Requirements a server of this implementation knows how to apply (a superset of what streams need). */
    private static final Set<String> SUPPORTED_REQUIREMENTS = Set.of("revlogv1", "generaldelta", "sparserevlog",
            "revlog-compression-zstd", "treemanifest", "persistent-nodemap", "exp-revlogv2.2", "exp-changelog-v2",
            "plain-encoding", "narrowheads", "exp-copies-sidedata-changeset", "fileindex-v1",
            "store", "fncache", "dotencode", "share-safe", "dirstate-v2");

    /** The spec is not usable (InvalidBundleSpecification / UnsupportedBundleSpecification): the line is simply not filtered. */
    private static final class SpecRejected extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private ClonebundlesManifest() {
    }

    /** {@code get_manifest_lines}: one entry per line of the manifest file, each ending in {@code \n}. */
    static List<String> getManifestLines(HgRepository repo) throws IOException {
        File file = new File(repo.getHgDir(), FILE_NAME);
        byte[] raw = file.exists() ? Files.readAllBytes(file.toPath()) : new byte[0];
        List<String> lines = new ArrayList<>();
        for (String line : splitLines(new String(raw, StandardCharsets.ISO_8859_1))) {
            lines.add(line + "\n"); // url [attrs] -> the same, newline-terminated; alter_bundle_url() is an identity hook
        }
        return lines;
    }

    /**
     * The legacy {@code clonebundles} answer: entries with the {@code peer-bundle-cache://} scheme and entries whose
     * bundlespec carries a {@code store-fingerprint} are left out (an old client cannot use them). Python appends
     * {@code b''} and joins with a newline, so every kept line (which ends in a newline already) is followed by an empty line.
     */
    static byte[] legacyAnswer(List<String> manifestLines) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : manifestLines) {
            if (line.startsWith(PEER_BUNDLE_CACHE_SCHEME)) {
                continue;
            }
            if (hasStoreFingerprint(line)) {
                continue;
            }
            kept.add(line);
        }
        kept.add("");
        return String.join("\n", kept).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The {@code clonebundles_manifest} answer of 7.2.4: all lines as they are. */
    static byte[] manifestAnswer(List<String> manifestLines) {
        return String.join("", manifestLines).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * {@code parse_clonebundle_manifest_line(...).get(b'STORE-FINGERPRINT')}. An attribute without "=" makes the original
     * raise, which this reports as an {@link IOException} as well.
     */
    static boolean hasStoreFingerprint(String line) throws IOException {
        List<String> fields = splitWhitespace(line);
        if (fields.isEmpty()) {
            return false;
        }
        Map<String, String> attrs = new HashMap<>();
        for (int i = 1; i < fields.size(); i++) {
            String raw = fields.get(i);
            int eq = raw.indexOf('=');
            if (eq < 0) {
                throw new IOException("malformed clone bundles manifest attribute (no '='): " + raw);
            }
            String key = unquote(raw.substring(0, eq));
            String value = unquote(raw.substring(eq + 1));
            attrs.put(key, value);
            if (key.equals("BUNDLESPEC")) {
                try {
                    String fingerprint = parseBundlespecParams(value).get("store-fingerprint");
                    if (fingerprint != null && !fingerprint.isEmpty()) {
                        attrs.put("STORE-FINGERPRINT", fingerprint);
                    }
                } catch (SpecRejected ignored) {
                    // InvalidBundleSpecification / UnsupportedBundleSpecification: the original ignores the spec
                }
            }
        }
        String fp = attrs.get("STORE-FINGERPRINT");
        return fp != null && !fp.isEmpty();
    }

    /**
     * The validation part of {@code parsebundlespec(repo, spec)} (strict mode) and the resulting parameters.
     *
     * @throws SpecRejected for what the original reports as an invalid or unsupported specification
     */
    private static Map<String, String> parseBundlespecParams(String spec) throws SpecRejected, IOException {
        if (spec.indexOf('-') < 0) {
            throw new SpecRejected(); // strict mode: must be prefixed with a compression
        }
        int semi = spec.indexOf(';');
        String preArgs = semi < 0 ? spec : spec.substring(0, semi);
        if (preArgs.indexOf('-') < 0) {
            // the '-' only occurs in the parameters: the original hits `assert not strict` and crashes
            throw new IOException("bundlespec without a compression before its parameters: " + spec);
        }
        int dash = spec.indexOf('-');
        String compression = spec.substring(0, dash);
        String rest = spec.substring(dash + 1);
        if (!BUNDLE_COMPRESSIONS.contains(compression)) {
            throw new SpecRejected();
        }

        Map<String, String> params = new HashMap<>();
        String version = rest;
        int restSemi = rest.indexOf(';');
        if (restSemi >= 0) {
            version = rest.substring(0, restSemi);
            for (String p : rest.substring(restSemi + 1).split(";", -1)) {
                int eq = p.indexOf('=');
                if (eq < 0) {
                    throw new SpecRejected(); // missing "=" in parameter
                }
                String key = unquote(p.substring(0, eq));
                String value = unquote(p.substring(eq + 1));
                if (BOOLEAN_PARAMS.contains(key) && parseBool(value) == null) {
                    throw new SpecRejected();
                }
                params.put(key, BOOLEAN_PARAMS.contains(key) ? String.valueOf(parseBool(value)) : value);
            }
        }
        if (!BUNDLE_VERSIONS.contains(version)) {
            throw new SpecRejected();
        }
        if (version.equals("v1") && !V1_COMPRESSIONS.contains(compression)) {
            throw new SpecRejected();
        }
        String requirements = params.get("requirements");
        if (requirements != null) {
            for (String req : requirements.split(",", -1)) {
                if (!STREAM_IGNORABLE_REQUIREMENTS.contains(req) && !SUPPORTED_REQUIREMENTS.contains(req)) {
                    throw new SpecRejected(); // missing support for repository features
                }
            }
        }
        return params;
    }

    /** {@code stringutil.parsebool}. */
    private static Boolean parseBool(String s) {
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "1": case "yes": case "true": case "on": case "always":
                return Boolean.TRUE;
            case "0": case "no": case "false": case "off": case "never":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    // ---- Python bytes semantics

    /** {@code bytes.splitlines()}: lines are separated by \n, \r or \r\n; a final terminator does not add an empty line. */
    static List<String> splitLines(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(s.substring(start, i));
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < s.length()) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /** {@code bytes.split()}: runs of ASCII whitespace separate fields, none are empty. */
    static List<String> splitWhitespace(String s) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && isAsciiSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && !isAsciiSpace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                fields.add(s.substring(start, i));
            }
        }
        return fields;
    }

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c;
    }

    /** {@code urllib.parse.unquote_to_bytes}: valid %XX escapes are decoded, anything else stays. */
    static String unquote(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && hex(s, i + 1) >= 0 && hex(s, i + 2) >= 0) {
                sb.append((char) (hex(s, i + 1) * 16 + hex(s, i + 2)));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int hex(String s, int i) {
        return i < s.length() ? Character.digit(s.charAt(i), 16) : -1;
    }
}
