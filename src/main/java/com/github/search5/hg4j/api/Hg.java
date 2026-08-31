package com.github.search5.hg4j.api;

/**
 * Porcelain API for Mercurial commands, similar to JGit's Git class.
 * Designed with elegant instance-level encapsulation and strict resource management (AutoCloseable).
 * 
 * <p><strong>Thread Safety:</strong> Hg instances are fully thread-safe and support both parallel concurrent read
 * and concurrent write operations. Multiple threads can safely execute commands concurrently, and complex sequence
 * of operations (e.g. status followed by commit) can be executed with 100% thread/process atomicity using the
 * {@link #runTransaction(Runnable)} API.
 */
public class Hg implements AutoCloseable {
    
    private final com.github.search5.hg4j.lib.HgRepository repository;
    private final java.util.Map<HgHookType, java.util.List<HgHook>> hooks = new java.util.concurrent.ConcurrentHashMap<>();
    
    private Hg(com.github.search5.hg4j.lib.HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Dynamically registers SCM Java hooks.
     *
     * @param type The execution phase of the hook
     * @param hook The HgHook instance containing the hook logic
     * @return The current instance (for method chaining)
     */
    public Hg registerHook(HgHookType type, HgHook hook) {
        if (type != null && hook != null) {
            hooks.computeIfAbsent(type, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(hook);
        }
        return this;
    }

    /**
     * Returns the list of all registered Java hooks for a specific phase.
     */
    public java.util.List<HgHook> getHooks(HgHookType type) {
        return hooks.getOrDefault(type, java.util.Collections.emptyList());
    }
    
    /**
     * Provides atomic execution of complex operation sequences (e.g., status followed by commit) across threads and processes.
     * Acquires exclusive store and working copy locks to prevent concurrent write contention during execution.
     */
    public void runTransaction(Runnable action) throws Exception {
        try (com.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore();
             com.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            action.run();
        }
    }

    /**
     * Wraps an existing {@link com.github.search5.hg4j.lib.HgRepository} instance into the {@link Hg} facade.
     * 
     * @param repository the repository instance to wrap
     * @return the {@link Hg} facade instance
     */
    public static Hg wrap(com.github.search5.hg4j.lib.HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        return new Hg(repository);
    }

    /**
     * Opens an existing Mercurial repository from a path string.
     * 
     * @param path the repository directory path
     * @return the {@link Hg} instance wrapping the repository
     * @throws java.io.IOException if repository not found or invalid
     */
    public static Hg open(String path) throws java.io.IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }
        return open(new java.io.File(path));
    }

    /**
     * Opens an existing Mercurial repository.
     * Checks repository requirements in both .hg/requires and .hg/store/requires for safety.
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
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Repository not found at: " + directory.getAbsolutePath());
        }

        // Robustness: Validate repository requirements format to prevent silent data corruption
        java.util.Set<String> SUPPORTED = java.util.Set.of(
            "dotencode", "fncache", "generaldelta", "revlogv1", "store", "dirstate-v2", "share-safe",
            "revlog-compression", "narrowspec"
        );

        java.io.File[] requiresFiles = {
            new java.io.File(hgDir, "requires"),
            new java.io.File(new java.io.File(hgDir, "store"), "requires")
        };

        for (java.io.File reqFile : requiresFiles) {
            if (reqFile.exists()) {
                for (String line : java.nio.file.Files.readAllLines(reqFile.toPath())) {
                    String r = line.trim();
                    if (r.isEmpty()) continue;
                    String key = r.contains("=") ? r.substring(0, r.indexOf('=')) : r;
                    if (!SUPPORTED.contains(r) && !SUPPORTED.contains(key)) {
                        throw new com.github.search5.hg4j.errors.HgValidationException("unsupported repository requirement: " + r);
                    }
                }
            }
        }

        return new Hg(new com.github.search5.hg4j.lib.HgRepository(directory));
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

    public com.github.search5.hg4j.lib.HgRepository getRepository() {
        return this.repository;
    }

    public AddCommand add() {
        return new AddCommand(this.repository);
    }

    public CommitCommand commit() {
        CommitCommand command = new CommitCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_COMMIT)) {
            command.registerPreCommitHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_COMMIT)) {
            command.registerPostCommitHook(hook);
        }
        return command;
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
        TagCommand command = new TagCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_TAG)) {
            command.registerPreTagHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_TAG)) {
            command.registerPostTagHook(hook);
        }
        return command;
    }


    public BookmarkCommand bookmark() {
        return new BookmarkCommand(this.repository);
    }

    public MergeCommand merge() {
        MergeCommand command = new MergeCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_MERGE)) {
            command.registerPreMergeHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_MERGE)) {
            command.registerPostMergeHook(hook);
        }
        return command;
    }

    public PullCommand pull() {
        return new PullCommand(this.repository);
    }

    public FetchCommand fetch() {
        return new FetchCommand(this.repository);
    }

    public WorktreeCommand worktree() {
        return new WorktreeCommand(this.repository);
    }

    public ShelveCommand shelve() {
        return new ShelveCommand(this.repository);
    }

    public RebaseCommand rebase() {
        RebaseCommand command = new RebaseCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_REBASE)) {
            command.registerPreRebaseHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_REBASE)) {
            command.registerPostRebaseHook(hook);
        }
        return command;
    }

    public UpdateCommand update() {
        UpdateCommand command = new UpdateCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_UPDATE)) {
            command.registerPreUpdateHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_UPDATE)) {
            command.registerPostUpdateHook(hook);
        }
        return command;
    }


    public PushCommand push() {
        PushCommand command = new PushCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_PUSH)) {
            command.registerPrePushHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_PUSH)) {
            command.registerPostPushHook(hook);
        }
        return command;
    }

    public void rollback() throws java.io.IOException {
        new RollbackCommand(this.repository).call();
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

    public RenameCommand rename() {
        return new RenameCommand(this.repository);
    }

    public AnnotateCommand annotate() {
        return new AnnotateCommand(this.repository);
    }

    public ResolveCommand resolve() {
        return new ResolveCommand(this.repository);
    }

    public AmendCommand amend() {
        return new AmendCommand(this.repository);
    }

    public BisectCommand bisect() {
        return new BisectCommand(this.repository);
    }

    public GrepCommand grep() {
        return new GrepCommand(this.repository);
    }

    public HisteditCommand histedit() {
        return new HisteditCommand(this.repository);
    }

    public ExportCommand export() {
        return new ExportCommand(this.repository);
    }

    public ImportCommand importPatch() {
        return new ImportCommand(this.repository);
    }

    public GraftCommand graft() {
        GraftCommand command = new GraftCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.POST_GRAFT)) {
            command.registerPostGraftHook(hook);
        }
        return command;
    }

    public PurgeCommand purge() {
        return new PurgeCommand(this.repository);
    }

    public ArchiveCommand archive() {
        return new ArchiveCommand(this.repository);
    }

    public GcCommand gc() {
        return new GcCommand(this.repository);
    }

    public SubrepoCommand subrepo() {
        return new SubrepoCommand(this.repository);
    }

    public IncomingCommand incoming() {
        return new IncomingCommand(this.repository);
    }

    public OutgoingCommand outgoing() {
        return new OutgoingCommand(this.repository);
    }

    public DescribeCommand describe() {
        return new DescribeCommand(this.repository);
    }

    public PhaseCommand phase() {
        return new PhaseCommand(this.repository);
    }

    public RevsetCommand revset() {
        return new RevsetCommand(this.repository);
    }

    public HeadsCommand heads() {
        return new HeadsCommand(this.repository);
    }

    public IdentifyCommand identify() {
        return new IdentifyCommand(this.repository);
    }

    public StripCommand strip() {
        return new StripCommand(this.repository);
    }

    public VerifyCommand verify() {
        return new VerifyCommand(this.repository);
    }

    public RootCommand root() {
        return new RootCommand(this.repository);
    }

    public TipCommand tip() {
        return new TipCommand(this.repository);
    }

    public ParentsCommand parents() {
        return new ParentsCommand(this.repository);
    }

    public SummaryCommand summary() {
        return new SummaryCommand(this.repository);
    }

    public ForgetCommand forget() {
        return new ForgetCommand(this.repository);
    }

    public AddremoveCommand addremove() {
        return new AddremoveCommand(this.repository);
    }

    public BackoutCommand backout() {
        return new BackoutCommand(this.repository);
    }

    public UnbundleCommand unbundle() {
        return new UnbundleCommand(this.repository);
    }

    /**
     * Resolves the effective {@code .hg/sparse} rules (including {@code %include}-referenced
     * profiles tracked at {@code changelogRev}) into an include/exclude pattern set, matching
     * real hg's {@code sparse.patternsforrev}. See {@link com.github.search5.hg4j.treewalk.SparseConfig}.
     */
    public com.github.search5.hg4j.treewalk.SparseConfig sparseConfig(int changelogRev) throws java.io.IOException {
        return com.github.search5.hg4j.treewalk.SparseConfig.resolveForRevision(this.repository, changelogRev);
    }

    /**
     * Expose HgRcConfig directly on the facade.
     * Incorporates Mercurial's priority load order:
     * 1. System global hgrc (/etc/mercurial/hgrc)
     * 2. User global hgrc (~/.hgrc or ~/mercurial.ini)
     * 3. Local repository hgrc (.hg/hgrc)
     */
    public com.github.search5.hg4j.lib.HgRcConfig config() {
        com.github.search5.hg4j.lib.HgRcConfig cfg = new com.github.search5.hg4j.lib.HgRcConfig();
        try {
            // 1. System-wide configuration
            java.io.File systemHgrc = new java.io.File("/etc/mercurial/hgrc");
            if (systemHgrc.exists()) {
                cfg.load(systemHgrc);
            }
            
            // 2. User-wide configuration
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                java.io.File userHgrc = new java.io.File(userHome, ".hgrc");
                if (userHgrc.exists()) {
                    cfg.load(userHgrc);
                } else {
                    java.io.File userIni = new java.io.File(userHome, "mercurial.ini");
                    if (userIni.exists()) {
                        cfg.load(userIni);
                    }
                }
            }
            
            // 3. Local repository configuration
            if (this.repository != null && this.repository.getHgDir() != null) {
                java.io.File localHgrc = new java.io.File(this.repository.getHgDir(), "hgrc");
                if (localHgrc.exists()) {
                    cfg.load(localHgrc);
                }
            }
        } catch (java.io.IOException e) {
            // ignore
        }
        return cfg;
    }

    public static NarrowCloneCommand narrowClone() {
        return new NarrowCloneCommand();
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
    public java.util.List<DiffCommand.DiffEntry> getDiff(com.github.search5.hg4j.lib.NodeId oldRevision, com.github.search5.hg4j.lib.NodeId newRevision) throws java.io.IOException {
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
    public java.util.List<TreeCommand.TreeEntry> getTree(com.github.search5.hg4j.lib.NodeId revision) throws java.io.IOException {
        return tree().setNodeId(revision).call();
    }

    public com.github.search5.hg4j.treewalk.ManifestWalk walkManifest(String revision) {
        return new com.github.search5.hg4j.treewalk.ManifestWalk(this.repository, revision);
    }

    public com.github.search5.hg4j.treewalk.WorkingDirWalk walkWorkingDir() {
        return new com.github.search5.hg4j.treewalk.WorkingDirWalk(this.repository);
    }

    /**
     * Returns a new TreeWalk instance for advanced parallel sorted traversal of multiple trees.
     */
    public com.github.search5.hg4j.treewalk.TreeWalk walkTree() {
        return new com.github.search5.hg4j.treewalk.TreeWalk();
    }

    @Override
    public void close() {
        if (this.repository != null) {
            this.repository.close();
        }
    }
}
