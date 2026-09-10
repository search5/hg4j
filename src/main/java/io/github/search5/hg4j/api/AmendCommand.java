package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to amend the tip commit (replace with modified changes and message).
 * Seamlessly registers the obsolescence marker in .hg/store/obsstore for evolve compatibility.
 *
 * <p>Mirrors real hg's {@code hg commit --amend} ({@code mercurial/cmdutil.py}'s {@code amend()}):
 * the amended commit REPLACES the amended-away commit as a sibling on the SAME parent(s)
 * ({@code base = old.p1()}), rather than becoming its child, and defaults its author/message/
 * close-branch state to the amended-away commit's own values when the caller doesn't override
 * them via {@link #setAuthor}/{@link #setMessage}/{@link #setCloseBranch}: real {@code hg commit
 * --amend} with no {@code -u}/{@code -m} reuses the original commit's user and message unchanged.
 *
 * @apiNote Typically obtained via {@link Hg#amend()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public final class AmendCommand {
    private final HgRepository repository;
    private String author;
    private String message;
    private Boolean closeBranch;

    /**
     * Creates an amend command bound to the given repository.
     *
     * @param repository the repository whose tip commit will be amended
     * @throws IllegalArgumentException if {@code repository} is {@code null}
     */
    public AmendCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * Overrides the author of the amended commit. When not called, the amended-away commit's own
     * author is reused.
     *
     * @param author the {@code user} value to record on the amended commit
     * @return this command, for chaining
     */
    public AmendCommand setAuthor(String author) {
        this.author = author;
        return this;
    }

    /**
     * Overrides the commit message of the amended commit. When not called, the amended-away
     * commit's own message is reused.
     *
     * @param message the commit message to record on the amended commit
     * @return this command, for chaining
     */
    public AmendCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    /** {@code hg commit --amend --close-branch}: overrides whether the amended commit closes its
     * named branch. When not called, the amended-away commit's own close state is preserved
     * (real hg: {@code extra.update(old.extra())}).
     *
     * @param closeBranch whether the amended commit should close its named branch
     * @return this command, for chaining
     */
    public AmendCommand setCloseBranch(boolean closeBranch) {
        this.closeBranch = closeBranch;
        return this;
    }

    /**
     * Executes the commit amend and obsstore marker serializing.
     *
     * @return NodeId byte array of the new amended commit
     * @throws IOException if commit or obsstore serialization fails
     * @throws HgLockException if the store lock cannot be acquired for the new commit
     */
    public byte[] call() throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            throw new IllegalStateException("No commits exist to amend (empty repository)");
        }

        int obsoleteRev = count - 1;
        Revlog.IndexRecord obsoleteRec = changelog.getIndexRecord(obsoleteRev);
        byte[] obsoleteNode = obsoleteRec.getNodeId();

        // The amended commit keeps the OLD commit's own parent(s) -- it replaces old as a
        // sibling rather than becoming its child (real hg: `base = old.p1()`).
        int baseP1Rev = obsoleteRec.getParent1();
        int baseP2Rev = obsoleteRec.getParent2();
        NodeId baseParent1 = baseP1Rev >= 0 ? new NodeId(changelog.getIndexRecord(baseP1Rev).getNodeId()) : NodeId.NULL;
        NodeId baseParent2 = baseP2Rev >= 0 ? new NodeId(changelog.getIndexRecord(baseP2Rev).getNodeId()) : NodeId.NULL;

        // Default author/message to the amended-away commit's own values when the caller didn't
        // override them (real hg: `user = opts.get('user') or old.user()`; message likewise
        // reuses old's description via the pre-filled commit editor when -m isn't given).
        String effectiveAuthor = this.author;
        String effectiveMessage = this.message;
        if (effectiveAuthor == null || effectiveMessage == null) {
            byte[] oldContent = changelog.getRevisionContent(obsoleteRev);
            String oldText = new String(oldContent, StandardCharsets.UTF_8);
            String[] lines = oldText.split("\n", -1);
            if (effectiveAuthor == null && lines.length > 1) {
                effectiveAuthor = lines[1];
            }
            if (effectiveMessage == null) {
                int emptyLineIdx = -1;
                for (int i = 3; i < lines.length; i++) {
                    if (lines[i].isEmpty()) {
                        emptyLineIdx = i;
                        break;
                    }
                }
                if (emptyLineIdx != -1) {
                    effectiveMessage = String.join("\n", Arrays.asList(lines).subList(emptyLineIdx + 1, lines.length));
                }
            }
        }

        boolean effectiveCloseBranch = (this.closeBranch != null)
                ? this.closeBranch
                : CommitCommand.isRevisionClosingBranch(changelog, obsoleteRev);

        // 1. Create a new amended commit that replaces `old`, keeping old's own parent(s)
        CommitCommand commitCmd = new CommitCommand(repository);
        if (effectiveAuthor != null) commitCmd.setAuthor(effectiveAuthor);
        if (effectiveMessage != null) commitCmd.setMessage(effectiveMessage);
        commitCmd.setCloseBranch(effectiveCloseBranch);
        commitCmd.setAmendDeclaredParents(baseParent1, baseParent2);

        byte[] newCommitNode = commitCmd.call();

        // 2. Generate and append obsolescence marker in obsstore
        HgObsMarker.writeMarker(repository.getStoreDir(), obsoleteNode, List.of(newCommitNode), "amend");

            return newCommitNode;
    }
}
