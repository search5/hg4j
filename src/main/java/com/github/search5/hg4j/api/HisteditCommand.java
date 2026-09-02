package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.util.SafeFileIO;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.NodeId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.obsolete.HgObsMarker;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Porcelain command for Interactive Rebase (histedit) on Mercurial repositories.
 * Supports rule-based PICK, DROP, FOLD, and ROLL actions to modify history.
 */
public class HisteditCommand implements AutoCloseable {
    public enum Action { PICK, DROP, FOLD, ROLL }

    public static class Rule {
        public final Action action;
        public final String hexNode;

        public Rule(Action action, String hexNode) {
            this.action = action;
            this.hexNode = hexNode;
        }
    }

    private final HgRepository repository;
    private final List<Rule> rules = new ArrayList<>();

    public HisteditCommand(HgRepository repository) {
        this.repository = repository;
    }

    public HisteditCommand addRule(Action action, String hexNode) {
        if (action != null && hexNode != null && !hexNode.isEmpty()) {
            rules.add(new Rule(action, hexNode));
        }
        return this;
    }

    public void call() throws IOException, HgLockException {
        if (rules.isEmpty()) {
            return;
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        // Backup current repository status for rollback in case of error
        Dirstate dirstate = repository.getDirstate();
        byte[] originalParent = dirstate.getParent1();

        // Crash/failure safety: snapshot every revlog this operation may append to before
        // touching anything, journal them (same scheme as CommitCommand/StripCommand), and
        // back up dirstate — so a mid-histedit failure (this method's own catch block, or a
        // real crash recovered via HgRepository.checkAndPerformAutoRollback()) truncates
        // everything back to its pre-histedit state instead of leaving a half-rewritten
        // history behind.
        Map<File, Long> fileSizes = new LinkedHashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");
        File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }
            recordAndJournal(clIdx, fileSizes, journalFile);
            recordAndJournal(clDat, fileSizes, journalFile);
            recordAndJournal(mfIdx, fileSizes, journalFile);
            recordAndJournal(mfDat, fileSizes, journalFile);

            // The base for the very first replayed commit must be the true original parent
            // of the FIRST rule (whatever revision comes right before the edited range) --
            // NOT the current dirstate tip. Real `hg histedit` only ever rewrites a
            // contiguous range of history and rebuilds it on top of that range's original
            // base; using the current tip here would (when some rule in the range is a DROP,
            // or a rule set doesn't extend all the way to the tip) incorrectly carry forward
            // manifest entries that belong to revisions the edited range never touches.
            Rule firstRule = rules.get(0);
            byte[] firstRuleNode = NodeIdUtil.fromHex(firstRule.hexNode);
            int firstRuleRev = changelog.findRevision(firstRuleNode);
            if (firstRuleRev == -1) {
                throw new IOException("Histedit failed: Revision not found for node " + firstRule.hexNode);
            }
            int baseRev = changelog.getIndexRecord(firstRuleRev).getParent1();
            byte[] rewriteBase = (baseRev != -1) ? changelog.getIndexRecord(baseRev).getNodeId() : new byte[20];

            // To support folding/merging revisions, we will parse the target revisions content
            // and perform clean recommits according to actions.
            byte[] lastCommittedNode = rewriteBase;
            String pendingCommitMsg = null;
            // All hex nodes belonging to the currently open pick/fold/roll group, in rule
            // order. Verified against real `hg histedit`: a fold/roll must fold *every*
            // member's file changes into the resulting commit (not just the last one), while
            // the resulting commit's author and branch always stay those of the group's
            // anchor (the pick, or first fold/roll if it opens the group) -- never those of
            // a later folded-in commit.
            List<String> pendingHexNodes = new ArrayList<>();
            String pendingAuthor = null;

            for (Rule rule : rules) {
                byte[] nodeBytes = NodeIdUtil.fromHex(rule.hexNode);
                int rev = changelog.findRevision(nodeBytes);
                if (rev == -1) {
                    throw new IOException("Histedit failed: Revision not found for node " + rule.hexNode);
                }

                byte[] clContent = changelog.getRevisionContent(rev);
                ParsedChangeset parsed = parseChangeset(clContent);

                if (rule.action == Action.PICK) {
                    if (pendingCommitMsg != null) {
                        // Commit folded changes first
                        lastCommittedNode = commitNewRev(lastCommittedNode, pendingAuthor, pendingCommitMsg, pendingHexNodes, fileSizes, journalFile);
                        pendingCommitMsg = null;
                        pendingHexNodes = new ArrayList<>();
                    }
                    pendingCommitMsg = parsed.message;
                    pendingAuthor = parsed.author;
                    pendingHexNodes.add(rule.hexNode);
                } else if (rule.action == Action.FOLD) {
                    if (pendingCommitMsg == null) {
                        pendingCommitMsg = parsed.message;
                        pendingAuthor = parsed.author;
                    } else {
                        pendingCommitMsg = pendingCommitMsg + "\n" + parsed.message;
                    }
                    pendingHexNodes.add(rule.hexNode);
                } else if (rule.action == Action.ROLL) {
                    if (pendingCommitMsg == null) {
                        pendingCommitMsg = parsed.message;
                        pendingAuthor = parsed.author;
                    }
                    // Drop this commit message, keep the accumulated pending CommitMsg
                    pendingHexNodes.add(rule.hexNode);
                } else if (rule.action == Action.DROP) {
                    // Skip completely
                }
            }

            if (pendingCommitMsg != null) {
                lastCommittedNode = commitNewRev(lastCommittedNode, pendingAuthor, pendingCommitMsg, pendingHexNodes, fileSizes, journalFile);
            }

            // Real hg's histedit finishes with an implicit checkout of the new tip: any path
            // that was part of the pre-histedit working copy but is no longer part of the
            // final manifest (its owning commit got DROPped entirely, or was removed partway
            // through a fold/roll group) must disappear from the working directory too.
            // Verified against real `hg histedit`: dropping a commit that added b.txt leaves
            // b.txt off disk (and out of `hg manifest -r tip`) once histedit finishes.
            Revlog manifestRevlogForCleanup = repository.getRevlog(mfIdx, mfDat);
            Map<String, String> oldManifest = getManifestForCommit(changelog, manifestRevlogForCleanup, originalParent);
            Map<String, String> finalManifest = getManifestForCommit(changelog, manifestRevlogForCleanup, lastCommittedNode);
            for (String path : oldManifest.keySet()) {
                if (!finalManifest.containsKey(path)) {
                    Files.deleteIfExists(new File(repository.getDirectory(), path).toPath());
                }
            }

            // Sync workspace to the last committed node
            Dirstate d = repository.getDirstate();
            d.setParents(lastCommittedNode, new byte[20]);
            repository.writeDirstate(d);
            repository.clearRevlogCache();

            // Leave the working branch matching the new tip's branch, the same way real
            // hg's checkout after a history-rewrite does.
            int newTipRev = changelog.findRevision(lastCommittedNode);
            if (newTipRev != -1) {
                repository.setBranch(CommitCommand.getBranchOfRevision(changelog, newTipRev));
            }

            try {
                CommitCommand.writeUndoInfo(repository, fileSizes, dirstateBackup);
            } catch (Exception e) {
                // non-blocking, same as CommitCommand/StripCommand
            }
            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
        } catch (Exception e) {
            // Roll every touched revlog back to its pre-histedit size and restore dirstate,
            // same recovery strategy as CommitCommand/StripCommand.
            for (Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    Files.deleteIfExists(file.toPath());
                } else if (file.exists()) {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    }
                }
            }
            if (dirstateBackup != null) {
                SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
            }
            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
            repository.clearRevlogCache();
            throw e;
        }
    }

    private void recordAndJournal(File file, Map<File, Long> fileSizes, File journalFile) throws IOException {
        if (fileSizes.containsKey(file)) {
            return;
        }
        long size = file.exists() ? file.length() : 0L;
        fileSizes.put(file, size);
        String relPath = "store/" + repository.getStoreDir().toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        appendToJournal(journalFile, relPath + "\t" + size);
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    private byte[] commitNewRev(byte[] parent, String author, String message, List<String> originalHexNodes,
                                 Map<File, Long> fileSizes, File journalFile) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        // The group anchor is the first rule of the pick/fold/roll group (verified against
        // real `hg histedit`: the resulting commit's branch -- and, further below, its
        // author -- always come from the anchor, never from a later folded-in commit).
        // The anchor's revision is never absent here: every hex in originalHexNodes was
        // already resolved successfully by call()'s own rule loop (which throws before ever
        // adding an unresolvable node to the group) against this same changelog, so a second
        // "not found" guard on it is unreachable and was removed as dead code.
        String anchorHex = originalHexNodes.get(0);
        byte[] origNode = NodeIdUtil.fromHex(anchorHex);
        int origRev = changelog.findRevision(origNode);

        // 1. Get parent manifest map to initialize new manifest
        Map<String, String> newManifest = getManifestForCommit(changelog, manifestRevlog, parent);

        // 2. Replay every group member's own file changes, in rule order, onto the running
        // manifest. Verified against real `hg histedit`: folding/rolling several commits
        // together must combine ALL of their file changes (e.g. folding a commit that adds
        // b.txt into one that added a.txt must keep both files), not just the last member's.
        Set<String> filesModifiedSet = new LinkedHashSet<>();
        for (String hex : originalHexNodes) {
            // Same reasoning as the anchor above: hex was already resolved by call()'s rule
            // loop before being added to originalHexNodes, so this can never miss.
            byte[] memberNode = NodeIdUtil.fromHex(hex);
            int memberRev = changelog.findRevision(memberNode);
            byte[] memberClContent = changelog.getRevisionContent(memberRev);
            ParsedChangeset memberParsed = parseChangeset(memberClContent);
            Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, memberNode);

            for (String path : memberParsed.filesModified) {
                filesModifiedSet.add(path);
                String hexAndFlag = originalManifest.get(path);
                if (hexAndFlag == null) {
                    // File deleted in this member's commit. Verified against real hg: a
                    // deletion folded/rolled in behind an earlier group member that (re)wrote
                    // this same path onto disk must actually remove it from the working
                    // directory too, not just from the manifest map.
                    newManifest.remove(path);
                    Files.deleteIfExists(new File(repository.getDirectory(), path).toPath());
                    continue;
                }

                String fileHex = hexAndFlag.substring(0, 40);
                String flag = hexAndFlag.substring(40);

                // Get original file content
                byte[] fileContent = getFileRevisionContent(repository, path, fileHex);

                // Write content to working directory file to physically update workspace!
                File wFile = new File(repository.getDirectory(), path);
                wFile.getParentFile().mkdirs();
                Files.write(wFile.toPath(), fileContent);

                // Commit to new filelog
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                flIdx.getParentFile().mkdirs();
                recordAndJournal(flIdx, fileSizes, journalFile);
                recordAndJournal(flDat, fileSizes, journalFile);
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                int parent1FileRev = -1;
                byte[] p1FileNode = new byte[20];
                String parentFileHexAndFlag = newManifest.get(path);
                if (parentFileHexAndFlag != null) {
                    String parentFileHex = parentFileHexAndFlag.substring(0, 40);
                    p1FileNode = NodeIdUtil.fromHex(parentFileHex);
                    parent1FileRev = NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
                }

                int fileLinkRev = changelog.getRevisionCount();
                byte[] newFileNode = filelog.appendRevision(fileContent, null, parent1FileRev, -1, p1FileNode, new byte[20], fileLinkRev);

                newManifest.put(path, NodeIdUtil.toHex(newFileNode) + flag);
            }
        }
        List<String> filesModified = new ArrayList<>(filesModifiedSet);

        // 3. Serialize and append new manifest revision
        StringBuilder manifestSb = new StringBuilder();
        for (Map.Entry<String, String> entry : newManifest.entrySet()) {
            manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
        }
        byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
        
        int parent1ManifestRev = -1;
        byte[] p1ManifestNode = new byte[20];
        if (parent != null && !NodeIdUtil.isAllZero(parent)) {
            int pRev = changelog.findRevision(parent);
            if (pRev != -1) {
                byte[] pContent = changelog.getRevisionContent(pRev);
                String pText = new String(pContent, StandardCharsets.UTF_8);
                String[] pLines = pText.split("\n");
                if (pLines.length > 0) {
                    p1ManifestNode = NodeIdUtil.fromHex(pLines[0].trim());
                    parent1ManifestRev = manifestRevlog.findRevision(p1ManifestNode);
                }
            }
        }
        
        int newCommitRev = changelog.getRevisionCount();
        byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, -1, p1ManifestNode, new byte[20], newCommitRev);
        
        // 4. Serialize and append new changelog (commit) revision
        StringBuilder clSb = new StringBuilder();
        clSb.append(NodeIdUtil.toHex(manifestNode)).append('\n');
        clSb.append(author).append('\n');
        
        long secs = System.currentTimeMillis() / 1000;
        clSb.append(secs).append(" 0");
        // Preserve the branch the original (rewritten) commit was on — real hg does not
        // write this extra field for the default branch, and never re-branches a picked
        // commit onto whatever branch happens to be active during the histedit.
        String branchName = CommitCommand.getBranchOfRevision(changelog, origRev);
        if (branchName != null && !branchName.isEmpty() && !"default".equals(branchName)) {
            clSb.append(" ").append("branch:").append(CommitCommand.encodeExtraKey(branchName));
        }
        clSb.append("\n");
        
        Collections.sort(filesModified, NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : filesModified) {
            clSb.append(path).append('\n');
        }
        clSb.append('\n'); // empty line separator
        clSb.append(message);
        
        byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);
        
        byte[] p1Normalized = new byte[20];
        if (parent != null) {
            System.arraycopy(parent, 0, p1Normalized, 0, Math.min(parent.length, 20));
        }
        
        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = NodeIdUtil.computeNodeId(changelogTextBytes, p1Normalized, new byte[20]);
        byte[] entryNode20 = new byte[20];
        System.arraycopy(entry.node, 0, entryNode20, 0, 20);
        entry.node = entryNode20;
        entry.p1 = p1Normalized;
        entry.p2 = new byte[20];
        entry.cs = entry.node;
        entry.deltabase = new byte[20];
        entry.delta = Revlog.createDelta(new byte[0], changelogTextBytes);
        
        changelog.appendChangeGroupEntry(entry, newCommitRev);

        // Register an obsolescence marker linking every original commit folded into this
        // group (not just the anchor) to the histedited commit.
        for (String hex : originalHexNodes) {
            try {
                HgObsMarker.writeMarker(repository.getStoreDir(), NodeIdUtil.fromHex(hex), List.of(entry.node), "histedit");
            } catch (Exception e) {
                // non-blocking
            }
        }

        return entry.node;
    }

    private Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        Map<String, String> manifestMap = new LinkedHashMap<>();
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        // String.split(...) on a non-null String always returns at least one element (even
        // "" splits to [""]), so a length-0 result -- and the early return that used to guard
        // against it -- can never happen; removed as dead code.
        String[] lines = text.split("\n");
        String manifestHex = lines[0].trim();
        byte[] manifestNode = NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }

    /** Result of parsing a raw changelog revision's text into its constituent fields. */
    private static final class ParsedChangeset {
        final String author;
        final List<String> filesModified;
        final String message;

        ParsedChangeset(String author, List<String> filesModified, String message) {
            this.author = author;
            this.filesModified = filesModified;
            this.message = message;
        }
    }

    // Changelog revision text layout: manifest-node-hex \n author \n date-extra \n
    // (one line per touched file) \n (blank separator line) \n description.
    private ParsedChangeset parseChangeset(byte[] clContent) {
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String[] clLines = clText.split("\n");

        String author = "unknown";
        if (clLines.length > 1) {
            author = clLines[1].trim();
        }

        List<String> filesModified = new ArrayList<>();
        int msgStartIdx = -1;
        for (int i = 3; i < clLines.length; i++) {
            if (clLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            filesModified.add(clLines[i]);
        }

        StringBuilder msgBuilder = new StringBuilder();
        if (msgStartIdx != -1) {
            for (int i = msgStartIdx; i < clLines.length; i++) {
                if (msgBuilder.length() > 0) msgBuilder.append("\n");
                msgBuilder.append(clLines[i]);
            }
        }
        // else: no blank separator line was found at all. This only happens when the
        // original description was empty (or made only of newlines) and Mercurial's
        // storage layer collapsed the trailing "files...\n\n<empty-desc>" tail entirely --
        // String.split() drops trailing empty tokens. In that case the description really
        // is empty; the lines collected above are the file list, not message text, so they
        // must not be reinterpreted as the message.

        return new ParsedChangeset(author, filesModified, msgBuilder.toString());
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }

    @Override
    public void close() {
        // No persistent resources
    }
}
