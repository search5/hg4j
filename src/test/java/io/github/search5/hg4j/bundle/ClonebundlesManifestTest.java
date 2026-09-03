package io.github.search5.hg4j.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the clonebundles manifest parser against real hg's actual format
 * (mercurial/bundlecaches.py's {@code parseclonebundlesmanifest}): whitespace-split lines, first
 * field is the URL verbatim (never percent-decoded), remaining fields are {@code key=value} pairs
 * split on the first {@code =} with both key and value percent-decoded.
 */
public class ClonebundlesManifestTest {

    @Test
    public void parsesAUrlWithNoAttributes() {
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse("https://example.com/bundle.hg\n");
        assertEquals(1, entries.size());
        assertEquals("https://example.com/bundle.hg", entries.get(0).getUrl());
        assertTrue(entries.get(0).getAttributes().isEmpty());
    }

    @Test
    public void parsesMultipleSpaceDelimitedAttributes() {
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(
                "https://example.com/bundle.hg BUNDLESPEC=none-v2 REQUIRESNI=true\n");
        assertEquals(1, entries.size());
        ClonebundlesManifest.Entry entry = entries.get(0);
        assertEquals("https://example.com/bundle.hg", entry.getUrl());
        assertEquals("none-v2", entry.getAttributes().get("BUNDLESPEC"));
        assertEquals("true", entry.getAttributes().get("REQUIRESNI"));
    }

    @Test
    public void percentDecodesBothKeysAndValuesButNeverTheUrl() {
        // Real hg unquotes (percent-decodes) both key and value of each attribute, but the URL
        // field itself is used verbatim -- it is already a valid URL, not itself percent-encoded
        // as a whole.
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(
                "https://example.com/b%20undle.hg datacenter=us%2Deast\n");
        ClonebundlesManifest.Entry entry = entries.get(0);
        assertEquals("https://example.com/b%20undle.hg", entry.getUrl());
        assertEquals("us-east", entry.getAttributes().get("datacenter"));
    }

    @Test
    public void parsesMultipleLinesAndSkipsBlankLines() {
        String manifest = "https://a.example.com/1.hg BUNDLESPEC=none-v1\n"
                + "\n"
                + "https://b.example.com/2.hg BUNDLESPEC=zstd-v2\n";
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(manifest);
        assertEquals(2, entries.size());
        assertEquals("https://a.example.com/1.hg", entries.get(0).getUrl());
        assertEquals("https://b.example.com/2.hg", entries.get(1).getUrl());
    }

    @Test
    public void handlesNullOrEmptyManifestAsNoEntries() {
        assertTrue(ClonebundlesManifest.parse(null).isEmpty());
        assertTrue(ClonebundlesManifest.parse("").isEmpty());
        assertTrue(ClonebundlesManifest.parse("   \n  \n").isEmpty());
    }

    @Test
    public void parseSkipsAttributeTokenWithNoEqualsSign() {
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(
                "https://example.com/bundle.hg badtoken BUNDLESPEC=none-v2\n");
        assertEquals(1, entries.size());
        ClonebundlesManifest.Entry entry = entries.get(0);
        assertEquals("none-v2", entry.getAttributes().get("BUNDLESPEC"));
        assertEquals(1, entry.getAttributes().size(), "the equals-less token must not have been recorded");
    }

    @Test
    public void filterSupportedOfNullEntriesReturnsEmptyList() {
        assertTrue(ClonebundlesManifest.filterSupported(null).isEmpty());
    }

    @Test
    public void filterSupportedKeepsOnlyBundlespecsHg4jCanActuallyConsume() {
        String manifest = String.join("\n",
                "https://a.example.com/1.hg BUNDLESPEC=none-v1",
                "https://b.example.com/2.hg BUNDLESPEC=gzip-v1",
                "https://c.example.com/3.hg BUNDLESPEC=zstd-v2",
                "https://d.example.com/4.hg BUNDLESPEC=bzip2-v1",
                "https://e.example.com/5.hg BUNDLESPEC=lz4-v3", // real but hg4j has no bundle v3/lz4 support
                "https://f.example.com/6.hg" // no BUNDLESPEC at all -- real hg keeps these too (can't pre-filter)
        );
        List<ClonebundlesManifest.Entry> supported = ClonebundlesManifest.filterSupported(ClonebundlesManifest.parse(manifest));

        List<String> urls = supported.stream().map(ClonebundlesManifest.Entry::getUrl).toList();
        assertTrue(urls.contains("https://a.example.com/1.hg"));
        assertTrue(urls.contains("https://b.example.com/2.hg"));
        assertTrue(urls.contains("https://c.example.com/3.hg"));
        assertTrue(urls.contains("https://d.example.com/4.hg"));
        assertFalse(urls.contains("https://e.example.com/5.hg"), "Unsupported bundle spec must be filtered out");
        assertTrue(urls.contains("https://f.example.com/6.hg"), "An entry with no BUNDLESPEC can't be pre-filtered, matching real hg");
    }

    @Test
    public void filterSupportedNeverFiltersOnRequiresSniSinceJavaHttpUrlConnectionSupportsIt() {
        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(
                "https://example.com/bundle.hg BUNDLESPEC=none-v1 REQUIRESNI=true\n");
        assertEquals(1, ClonebundlesManifest.filterSupported(entries).size());
    }
}
