package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Snippets for the "Bisecting to Find a Regression" chapter: hg.bisect().
 */
final class BisectExamples {

    private BisectExamples() {
    }

    void findFirstCandidate() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::bisect-first-candidate[]
            // A known-good revision (here, simply the oldest commit -- in practice this would
            // usually be a known-good tag or release) and a known-bad one (the current tip,
            // where the regression is visible).
            List<HgCommit> history = hg.log().call(); // newest first
            byte[] goodNode = history.get(history.size() - 1).getNodeId().getBytes();
            byte[] badNode = history.get(0).getNodeId().getBytes();

            // next() picks the revision that best splits the good..bad range in half, AND
            // checks it out into the working directory -- there is no separate update() call
            // needed before building/testing it.
            byte[] candidate = hg.bisect()
                    .setGood(goodNode)
                    .setBad(badNode)
                    .next();
            System.out.println("Now build/test the working copy, then mark it good or bad.");
            // end::bisect-first-candidate[]
            Arrays.toString(candidate);
        }
    }

    void narrowDownToTheCulprit() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::bisect-narrow-loop[]
            List<HgCommit> history = hg.log().call();
            HgCommit goodCommit = history.get(history.size() - 1);
            HgCommit badCommit = history.get(0);

            byte[] good = goodCommit.getNodeId().getBytes();
            byte[] bad = badCommit.getNodeId().getBytes();
            int goodRev = goodCommit.getRevision();
            int badRev = badCommit.getRevision();

            // BisectCommand keeps no state of its own on disk (unlike real hg's
            // ".hg/bisect.state") -- every next() call is a fresh good/bad split computed from
            // whatever nodes you pass it, so the CALLER tracks the current good/bad range across
            // iterations, exactly as this loop does. Bisection is done once good and bad become
            // adjacent revisions: at that point "bad" itself is the culprit.
            while (badRev - goodRev > 1) {
                byte[] candidate = hg.bisect().setGood(good).setBad(bad).next();
                int candidateRev = findRevisionNumber(history, candidate);

                boolean isGood = testCurrentCheckout(); // e.g. run the project's test suite
                if (isGood) {
                    good = candidate;
                    goodRev = candidateRev;
                } else {
                    bad = candidate;
                    badRev = candidateRev;
                }
            }
            System.out.println("First bad revision: " + badRev);
            // end::bisect-narrow-loop[]
        }
    }

    private static int findRevisionNumber(List<HgCommit> history, byte[] node) {
        for (HgCommit commit : history) {
            if (Arrays.equals(commit.getNodeId().getBytes(), node)) {
                return commit.getRevision();
            }
        }
        throw new IllegalStateException("Candidate revision not found in the fetched history");
    }

    private boolean testCurrentCheckout() {
        // Stand-in for whatever step determines if the checked-out candidate already has the
        // regression -- running the project's build/test suite against the working copy, for
        // instance.
        return true;
    }
}
