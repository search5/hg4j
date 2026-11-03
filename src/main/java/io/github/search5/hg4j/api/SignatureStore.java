package io.github.search5.hg4j.api;

import io.github.search5.hg4j.gpg.HgSigEntry;
import io.github.search5.hg4j.gpg.HgSigFormatException;
import io.github.search5.hg4j.gpg.HgSigsFile;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads every signature a repository carries, exactly as {@code hgext/gpg.py}'s {@code sigwalk} does:
 * first the content of EVERY head of the {@code .hgsigs} file log (newest revision first, so signatures
 * added on different branches are all seen), then {@code .hg/localsigs}.
 */
final class SignatureStore {

    /** One signature line with where it was found, e.g. {@code .hgsigs|1a2b3c4d5e6f:3}. */
    record Located(HgSigEntry entry, String context, int lineNumber) {
        String where() {
            return context + ":" + lineNumber;
        }
    }

    private SignatureStore() {
    }

    static List<Located> readAll(HgRepository repository) throws IOException {
        List<Located> all = new ArrayList<>();

        File filelogIndex = CommitCommand.getFilelogIndex(repository.getStoreDir(), ".hgsigs");
        if (filelogIndex.exists()) {
            File filelogData = new File(filelogIndex.getPath().substring(0, filelogIndex.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(filelogIndex, filelogData);
            List<Integer> heads = heads(filelog);
            for (int i = heads.size() - 1; i >= 0; i--) { // reversed(fl.heads())
                int rev = heads.get(i);
                String context = ".hgsigs|" + NodeIdUtil.toHex(filelog.getIndexRecord(rev).getNodeId()).substring(0, 12);
                String text = new String(filelog.getRevisionContent(rev), StandardCharsets.UTF_8);
                add(all, HgSigsFile.parse(text, context), context);
            }
        }

        File local = new File(repository.getHgDir(), "localsigs");
        if (local.isFile()) {
            add(all, HgSigsFile.parse(Files.readString(local.toPath(), StandardCharsets.UTF_8), "localsigs"), "localsigs");
        }
        return all;
    }

    private static void add(List<Located> into, List<HgSigsFile.Line> lines, String context) {
        for (HgSigsFile.Line line : lines) {
            into.add(new Located(line.entry(), context, line.lineNumber()));
        }
    }

    /** Revisions of the revlog that no other revision has as a parent, in ascending order. */
    static List<Integer> heads(Revlog filelog) throws HgSigFormatException {
        int count = filelog.getRevisionCount();
        boolean[] hasChild = new boolean[count];
        for (int rev = 0; rev < count; rev++) {
            Revlog.IndexRecord r = filelog.getIndexRecord(rev);
            for (int parent : new int[]{r.getParent1(), r.getParent2()}) {
                if (parent >= 0 && parent < count) {
                    hasChild[parent] = true;
                }
            }
        }
        List<Integer> heads = new ArrayList<>();
        for (int rev = 0; rev < count; rev++) {
            if (!hasChild[rev]) {
                heads.add(rev);
            }
        }
        return heads;
    }

    /** @return the changelog revision number of {@code nodeHex}, or -1 if the repository has no such changeset */
    static int changelogRevision(HgRepository repository, String nodeHex) throws IOException {
        File idx = new File(repository.getStoreDir(), "00changelog.i");
        File dat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(idx, dat);
        return NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(nodeHex));
    }
}
