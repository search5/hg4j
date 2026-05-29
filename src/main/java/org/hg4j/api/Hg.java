package org.hg4j.api;

/**
 * Porcelain API for Mercurial commands, similar to JGit's Git class.
 * Designed with elegant instance-level encapsulation and strict resource management (AutoCloseable).
 */
public class Hg implements AutoCloseable {
    
    private final org.hg4j.core.HgRepository repository;
    
    private Hg(org.hg4j.core.HgRepository repository) {
        this.repository = repository;
    }
    
    /**
     * Opens an existing Mercurial repository.
     * 
     * @param directory the repository directory
     * @return the {@link Hg} instance wrapping the repository
     * @throws java.io.IOException if repository not found or invalid
     */
    public static Hg open(java.io.File directory) throws java.io.IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null");
        }
        java.io.File hgDir = new java.io.File(directory, ".hg");
        if (!hgDir.exists() || !hgDir.isDirectory()) {
            throw new org.hg4j.errors.HgRepositoryNotFoundException("Repository not found at: " + directory.getAbsolutePath());
        }
        return new Hg(new org.hg4j.core.HgRepository(directory));
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
     * Returns a command object to clone a remote repository.
     * 
     * @return a {@link CloneCommand} instance
     */
    public static CloneCommand cloneRepository() {
        return new CloneCommand();
    }

    public org.hg4j.core.HgRepository getRepository() {
        return this.repository;
    }

    public AddCommand add() {
        return new AddCommand(this.repository);
    }

    public CommitCommand commit() {
        return new CommitCommand(this.repository);
    }

    public StatusCommand status() {
        return new StatusCommand(this.repository);
    }

    public LogCommand log() {
        return new LogCommand(this.repository);
    }

    public BranchCommand branch() {
        return new BranchCommand(this.repository);
    }

    public TagCommand tag() {
        return new TagCommand(this.repository);
    }

    public BookmarkCommand bookmark() {
        return new BookmarkCommand(this.repository);
    }

    public MergeCommand merge() {
        return new MergeCommand(this.repository);
    }

    public PullCommand pull() {
        return new PullCommand(this.repository);
    }

    public ShelveCommand shelve() {
        return new ShelveCommand(this.repository);
    }

    public RebaseCommand rebase() {
        return new RebaseCommand(this.repository);
    }

    public UpdateCommand update() {
        return new UpdateCommand(this.repository);
    }

    public PushCommand push() {
        return new PushCommand(this.repository);
    }

    public CatCommand cat() {
        return new CatCommand(this.repository);
    }

    public RevertCommand revert() {
        return new RevertCommand(this.repository);
    }

    public RemoveCommand remove() {
        return new RemoveCommand(this.repository);
    }

    public DiffCommand diff() {
        return new DiffCommand(this.repository);
    }

    public TreeCommand tree() {
        return new TreeCommand(this.repository);
    }

    /**
     * Helper method to directly compute diff between two revisions.
     */
    public java.util.List<DiffCommand.DiffEntry> getDiff(int oldRevision, int newRevision) throws java.io.IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly compute diff between two revisions using NodeId.
     */
    public java.util.List<DiffCommand.DiffEntry> getDiff(org.hg4j.lib.NodeId oldRevision, org.hg4j.lib.NodeId newRevision) throws java.io.IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision.
     */
    public java.util.List<TreeCommand.TreeEntry> getTree(int revision) throws java.io.IOException {
        return tree().setRevision(revision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision using NodeId.
     */
    public java.util.List<TreeCommand.TreeEntry> getTree(org.hg4j.lib.NodeId revision) throws java.io.IOException {
        return tree().setNodeId(revision).call();
    }

    public org.hg4j.treewalk.ManifestWalk walkManifest(String revision) {
        return new org.hg4j.treewalk.ManifestWalk(this.repository, revision);
    }

    public org.hg4j.treewalk.WorkingDirWalk walkWorkingDir() {
        return new org.hg4j.treewalk.WorkingDirWalk(this.repository);
    }

    @Override
    public void close() {
        if (this.repository != null) {
            this.repository.close();
        }
    }
}
