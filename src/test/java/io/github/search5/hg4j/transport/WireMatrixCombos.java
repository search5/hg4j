package io.github.search5.hg4j.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared axis definitions for the wire protocol matrix (see {@code
 * llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §2 and §4-2). Extracted out of {@link
 * HgWireProtocolMatrixTest} (which pioneered this exact 21-combo matrix for {@code
 * Clone}/{@code Pull}/{@code Push}) so the backlog item 39 wave-5 sibling test classes for {@code
 * Fetch}/{@code Incoming}/{@code Outgoing}/{@code Clonebundles}/{@code NarrowClone} can reuse the
 * same combos and server-setup boilerplate instead of re-declaring 18+3 combos five more times.
 *
 * <p>HTTP: 3 arg tiers ({@code httppostargs}/{@code httpheader=N}/legacy GET) x 3 compression
 * engines (zlib/zstd/none) x 2 bundle2 states (on/off) = 18 combinations. SSH: 3 compression
 * engines only (SSH has no arg-tier concept, and bundle2-off forcing over SSH is not yet wired up,
 * matching {@link HgWireProtocolMatrixTest}'s own scope note). HTTP 18 + SSH 3 = 21.
 */
final class WireMatrixCombos {

    private WireMatrixCombos() {
    }

    enum Tier { HTTPPOSTARGS, HTTPHEADER, LEGACY_GET }

    record HttpCombo(Tier tier, String compression, boolean bundle2On) {
        String label() {
            return tier + "-" + compression + "-bundle2" + (bundle2On ? "on" : "off");
        }

        @Override
        public String toString() {
            return label();
        }
    }

    static Stream<HttpCombo> httpCombos() {
        List<HttpCombo> out = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            for (String compression : List.of("zlib", "zstd", "none")) {
                for (boolean bundle2On : List.of(true, false)) {
                    out.add(new HttpCombo(tier, compression, bundle2On));
                }
            }
        }
        return out.stream();
    }

    static final List<String> SSH_COMPRESSIONS = List.of("zlib", "zstd", "none");
}
