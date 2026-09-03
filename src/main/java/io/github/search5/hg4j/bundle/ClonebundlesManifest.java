package io.github.search5.hg4j.bundle;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and filters a {@code .hg/clonebundles.manifest} file — the "Clonebundles" mechanism real
 * hg uses to offload large clones to externally-hosted, pre-generated bundle files, bypassing the
 * wire protocol entirely for the actual data transfer (only the manifest lookup itself, {@code
 * ?cmd=clonebundles}, goes over the wire; the bundle download is a plain HTTP(S) GET to whatever
 * URL the manifest lists).
 *
 * <p>Format verified against Mercurial 6.0's {@code mercurial/bundlecaches.py}
 * ({@code parseclonebundlesmanifest}/{@code filterclonebundleentries}) and {@code
 * mercurial/wireprotov1server.py} ({@code clonebundles()}, which just returns the raw manifest
 * file content for {@code ?cmd=clonebundles}). Each line is
 * {@code <URL> [<key>=<value> ...]}: the URL is used verbatim (it is a URL, not itself
 * percent-encoded as a whole), and each following {@code key=value} pair is split on the first
 * {@code =} with both key and value percent-decoded.</p>
 */
public final class ClonebundlesManifest {

    /** Bundle specifications hg4j can actually consume, matching {@link UnbundleCommand}/{@link Bundle2Parser}'s supported formats. */
    private static final Set<String> SUPPORTED_BUNDLESPECS = Set.of(
            "none-v1", "gzip-v1", "bzip2-v1",
            "none-v2", "gzip-v2", "bzip2-v2", "zstd-v2");

    private ClonebundlesManifest() {
    }

    public static final class Entry {
        private final String url;
        private final Map<String, String> attributes;

        Entry(String url, Map<String, String> attributes) {
            this.url = url;
            this.attributes = attributes;
        }

        public String getUrl() {
            return url;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public String getBundlespec() {
            return attributes.get("BUNDLESPEC");
        }
    }

    /**
     * Parses the raw text of a clonebundles manifest (the exact bytes {@code ?cmd=clonebundles}
     * returns) into a list of entries in file order.
     */
    public static List<Entry> parse(String manifestText) {
        List<Entry> entries = new ArrayList<>();
        if (manifestText == null) {
            return entries;
        }
        for (String line : manifestText.split("\n", -1)) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 0 || fields[0].isEmpty()) {
                continue;
            }
            String url = fields[0];
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 1; i < fields.length; i++) {
                int eq = fields[i].indexOf('=');
                if (eq == -1) {
                    continue;
                }
                String key = urlDecode(fields[i].substring(0, eq));
                String value = urlDecode(fields[i].substring(eq + 1));
                attrs.put(key, value);
            }
            entries.add(new Entry(url, attrs));
        }
        return entries;
    }

    /**
     * Keeps only entries whose {@code BUNDLESPEC} (if present) names a format hg4j's own {@link
     * UnbundleCommand}/{@link Bundle2Parser} can actually decode — real hg does the same
     * client-side filtering so it never attempts to download a bundle it can't apply. An entry
     * with no {@code BUNDLESPEC} at all is kept as-is (matching real hg: it can't be pre-filtered
     * without the hint, so real hg just attempts it). {@code REQUIRESNI}/{@code REQUIREDRAM}
     * (real hg's other two filter criteria) are not filtered here: modern {@code
     * HttpURLConnection} always supports SNI, and hg4j has no reliable JVM-side memory estimate
     * equivalent to real hg's {@code ui.estimatememory()} to check against.
     */
    public static List<Entry> filterSupported(List<Entry> entries) {
        if (entries == null) {
            return Collections.emptyList();
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            String spec = entry.getBundlespec();
            if (spec == null || SUPPORTED_BUNDLESPECS.contains(spec)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
