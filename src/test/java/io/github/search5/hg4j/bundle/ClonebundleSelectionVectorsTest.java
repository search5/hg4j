package io.github.search5.hg4j.bundle;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code parseclonebundlesmanifest}, {@code filterclonebundleentries} and {@code best_clonebundles} of Mercurial (the Rust build, which
 * knows the shape fields) on 400 generated manifests: schemes, bundle specifications, digests, delta compression, stream clone wishes,
 * store fingerprints, sets of shards and {@code ui.clonebundleprefers}. Every line of the fixture is an answer of the original.
 */
class ClonebundleSelectionVectorsTest {

    @Test
    void theSelectionIsThatOfTheOriginal() throws Exception {
        int checked = 0;
        int sharded = 0;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/shape/clonebundle-selection-vectors.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                String manifest = f[0].replace("\\n", "\n");
                Boolean streamRequested = f[1].equals("none") ? null : Boolean.valueOf(f[1]);
                List<String> storeFingerprints = f[2].equals("None") ? null : f[2].equals("-") ? new ArrayList<>() : List.of(f[2].split(","));
                List<Set<String>> shardsSets = null;
                if (!f[3].equals("None")) {
                    shardsSets = new ArrayList<>();
                    if (!f[3].equals("-")) {
                        for (String group : f[3].split(";")) {
                            shardsSets.add(new HashSet<>(List.of(group.split(","))));
                        }
                    }
                }
                List<String> prefers = f[4].isEmpty() ? List.of() : List.of(f[4].split(","));
                List<String> debug = new ArrayList<>();
                List<ClonebundleSelection.Entry> entries = ClonebundleSelection.parseManifest(manifest, debug);
                assertEquals(Integer.parseInt(f[5]), entries.size(), "parsed entries: " + line);
                String actual;
                try {
                    List<ClonebundleSelection.Entry> filtered = ClonebundleSelection.filter(entries, streamRequested, false, storeFingerprints,
                            shardsSets, debug);
                    String urls = String.join(",", filtered.stream().map(ClonebundleSelection.Entry::url).toList());
                    String best = filtered.isEmpty() ? "" : String.join(",",
                            ClonebundleSelection.best(prefers, filtered).stream().map(ClonebundleSelection.Entry::url).toList());
                    actual = "OK|" + urls + "|" + best;
                } catch (IllegalStateException e) {
                    actual = "ASSERT";
                }
                assertEquals(f[6], actual, line);
                checked++;
                sharded += manifest.contains("SHARD-ID") && f[6].startsWith("OK|http") ? 1 : 0;
            }
        }
        assertEquals(400, checked);
        System.out.println("clonebundle vectors: " + checked + ", with sharded entries kept: " + sharded);
    }
}
