package org.hg4j.api;

/**
 * Porcelain API for Mercurial commands, similar to JGit's Git class.
 */
public class Hg {
    
    // Private constructor to prevent instantiation of utility class
    private Hg() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
    
    /**
     * Returns a command object to initialize a new repository.
     * 
     * @return an {@link InitCommand} instance
     */
    public static InitCommand init() {
        return new InitCommand();
    }

    /**
     * Returns a command object to add untracked files to the repository.
     * 
     * @param repository the local repository
     * @return an {@link AddCommand} instance
     */
    public static AddCommand add(org.hg4j.core.HgRepository repository) {
        return new AddCommand(repository);
    }

    /**
     * Returns a command object to commit changes to the repository history.
     * 
     * @param repository the local repository
     * @return a {@link CommitCommand} instance
     */
    public static CommitCommand commit(org.hg4j.core.HgRepository repository) {
        return new CommitCommand(repository);
    }

    /**
     * Returns a command object to compute status of the working copy.
     * 
     * @param repository the local repository
     * @return a {@link StatusCommand} instance
     */
    public static StatusCommand status(org.hg4j.core.HgRepository repository) {
        return new StatusCommand(repository);
    }

    /**
     * Returns a command object to traverse commit log history.
     * 
     * @param repository the local repository
     * @return a {@link LogCommand} instance
     */
    public static LogCommand log(org.hg4j.core.HgRepository repository) {
        return new LogCommand(repository);
    }

    /**
     * Returns a command object to manage branches.
     * 
     * @param repository the local repository
     * @return a {@link BranchCommand} instance
     */
    public static BranchCommand branch(org.hg4j.core.HgRepository repository) {
        return new BranchCommand(repository);
    }

    /**
     * Returns a command object to manage tags.
     * 
     * @param repository the local repository
     * @return a {@link TagCommand} instance
     */
    public static TagCommand tag(org.hg4j.core.HgRepository repository) {
        return new TagCommand(repository);
    }

    /**
     * Returns a command object to manage bookmarks.
     * 
     * @param repository the local repository
     * @return a {@link BookmarkCommand} instance
     */
    public static BookmarkCommand bookmark(org.hg4j.core.HgRepository repository) {
        return new BookmarkCommand(repository);
    }

    /**
     * Returns a command object to merge a target revision into the working copy.
     * 
     * @param repository the local repository
     * @return a {@link MergeCommand} instance
     */
    public static MergeCommand merge(org.hg4j.core.HgRepository repository) {
        return new MergeCommand(repository);
    }

    /**
     * Returns a command object to pull changes from a remote repository.
     * 
     * @param repository the local repository
     * @return a {@link PullCommand} instance
     */
    public static PullCommand pull(org.hg4j.core.HgRepository repository) {
        return new PullCommand(repository);
    }

    /**
     * Returns a command object to clone a remote repository.
     * 
     * @return a {@link CloneCommand} instance
     */
    public static CloneCommand cloneRepository() {
        return new CloneCommand();
    }

    /**
     * Returns a command object to shelve/unshelve local modifications.
     * 
     * @param repository the local repository
     * @return a {@link ShelveCommand} instance
     */
    public static ShelveCommand shelve(org.hg4j.core.HgRepository repository) {
        return new ShelveCommand(repository);
    }

    /**
     * Returns a command object to rebase revisions onto another base.
     * 
     * @param repository the local repository
     * @return a {@link RebaseCommand} instance
     */
    public static RebaseCommand rebase(org.hg4j.core.HgRepository repository) {
        return new RebaseCommand(repository);
    }

    /**
     * Returns a command object to update/checkout workspace to a target revision.
     */
    public static UpdateCommand update(org.hg4j.core.HgRepository repository) {
        return new UpdateCommand(repository);
    }

    /**
     * Returns a command object to push changes to a remote repository.
     */
    public static PushCommand push(org.hg4j.core.HgRepository repository) {
        return new PushCommand(repository);
    }

    /**
     * Returns a command object to retrieve historical file content.
     */
    public static CatCommand cat(org.hg4j.core.HgRepository repository) {
        return new CatCommand(repository);
    }

    /**
     * Returns a command object to revert local modifications to files.
     */
    public static RevertCommand revert(org.hg4j.core.HgRepository repository) {
        return new RevertCommand(repository);
    }

    /**
     * Returns a command object to untrack and remove files.
     */
    public static RemoveCommand remove(org.hg4j.core.HgRepository repository) {
        return new RemoveCommand(repository);
    }
}
