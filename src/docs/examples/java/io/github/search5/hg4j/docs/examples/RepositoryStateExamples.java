package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.SummaryCommand.ParentInfo;
import io.github.search5.hg4j.api.SummaryCommand.SummaryInfo;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Inspecting Repository State" chapter: root, tip, parents, summary,
 * identify, heads, describe.
 */
final class RepositoryStateExamples {

    private RepositoryStateExamples() {
    }

    void showRoot() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::show-root[]
            String rootPath = hg.root().call();
            System.out.println("Repository root: " + rootPath);
            // end::show-root[]
        }
    }

    void showTip() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::show-tip[]
            byte[] tipNode = hg.tip().call();
            int tipRevisionNumber = hg.tip().getRevisionNumber();
            if (tipNode != null) {
                System.out.println("Tip revision: " + tipRevisionNumber);
            }
            // end::show-tip[]
        }
    }

    void showParents() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::show-parents[]
            // One entry in the common case, two while an uncommitted merge is in progress,
            // and zero only for a brand-new empty repository.
            List<String> parentHexes = hg.parents().call();
            for (String hex : parentHexes) {
                System.out.println("Parent: " + hex);
            }
            // end::show-parents[]
        }
    }

    void showSummary() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::show-summary[]
            SummaryInfo summary = hg.summary().call();
            for (ParentInfo parent : summary.parents()) {
                System.out.println("parent: " + parent.node() + " " + parent.description());
            }
            System.out.println("branch: " + summary.branch());
            System.out.println("bookmark: " + summary.activeBookmark());
            System.out.println("phase: " + summary.currentPhase());
            if (summary.mergeInProgress()) {
                System.out.println("An uncommitted merge is in progress.");
            }
            // end::show-summary[]
        }
    }

    void showIdentify() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::identify-working-copy[]
            // Working copy's own parent(s): a trailing "+" marks a dirty working copy.
            String workingCopyIdentity = hg.identify().call();
            System.out.println(workingCopyIdentity);
            // end::identify-working-copy[]

            // tag::identify-revision[]
            // A fixed revision instead: no dirty marker is ever appended here.
            String tipIdentity = hg.identify().setRevision("tip").call();
            System.out.println(tipIdentity);
            // end::identify-revision[]
        }
    }

    void showHeads() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::list-heads[]
            // Plain hg heads: every named branch's own open head(s).
            List<String> heads = hg.heads().call();

            // hg heads --topo: pure repo-wide topological leaves, ignoring branches.
            List<String> topoHeads = hg.heads().setTopo(true).call();

            // hg heads <branch>: only that branch's own heads.
            List<String> featureHeads = hg.heads().setBranch("feature").call();
            // end::list-heads[]
            int ignore = heads.size() + topoHeads.size() + featureHeads.size();
        }
    }

    void showDescribe() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::describe-revision[]
            // "<tag>-<distance>-g<shortnode>" relative to the nearest ancestral tag,
            // or just "<tag>" when the working copy's parent IS a tagged revision.
            String description = hg.describe().call();
            System.out.println(description);
            // end::describe-revision[]
        }
    }
}
