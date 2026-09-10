package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.IOException;

/**
 * Commands for branch management (viewing or switching branches).
 *
 * @apiNote Typically obtained via {@link Hg#branch()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class BranchCommand {
    private final HgRepository repository;
    private String branchName;

    /**
     * Creates a branch command bound to the given repository.
     *
     * @param repository the repository to query or switch the branch of
     */
    public BranchCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the branch to switch the working copy to. Leaving this unset makes {@link #call()}
     * only report the current branch instead of changing it.
     *
     * @param branchName the branch name to switch to
     * @return this command, for chaining
     */
    public BranchCommand setBranchName(String branchName) {
        this.branchName = branchName;
        return this;
    }

    /**
     * Reports the working copy's current branch, or switches it when {@link #setBranchName} was called.
     *
     * @return the resulting current branch name
     * @throws IOException if reading/writing the {@code .hg/branch} file fails
     * @throws IllegalArgumentException if {@link #setBranchName} was called with an empty (non-null) name
     */
    public String call() throws IOException {
        if (branchName != null) {
            if (branchName.isEmpty()) {
                throw new IllegalArgumentException("Branch name cannot be empty");
            }
            repository.setBranch(branchName);
            return branchName;
        }
        return repository.getBranch();
    }
}
