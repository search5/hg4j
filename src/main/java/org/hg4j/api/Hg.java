package org.hg4j.api;

/**
 * Porcelain API for Mercurial commands, similar to JGit's Git class.
 * Designed with elegant instance-level encapsulation and strict resource management (AutoCloseable).
 * 
 * <p><strong>Thread Safety:</strong> Hg instances are designed for sequential use in a single thread.
 * For concurrent access across multiple threads, each thread should open its own Hg instance,
 * relying on underlying repository locks to coordinate safety.
 */
public class Hg implements AutoCloseable {
    
    private final org.hg4j.core.HgRepository repository;
    private final java.util.Map<HgHookType, java.util.List<HgHook>> hooks = new java.util.concurrent.ConcurrentHashMap<>();
    
    private Hg(org.hg4j.core.HgRepository repository) {
        this.repository = repository;
    }

    /**
     * SCM 자바 훅을 동적으로 등록합니다.
     *
     * @param type 훅의 실행 시점 종류
     * @param hook 훅 동작을 구현한 HgHook 인스턴스
     * @return 자기 자신 (Chaining 지원)
     */
    public Hg registerHook(HgHookType type, HgHook hook) {
        if (type != null && hook != null) {
            hooks.computeIfAbsent(type, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(hook);
        }
        return this;
    }

    /**
     * 특정 시점에 등록된 모든 자바 훅 리스트를 반환합니다.
     */
    public java.util.List<HgHook> getHooks(HgHookType type) {
        return hooks.getOrDefault(type, java.util.Collections.emptyList());
    }
    
    /**
     * Wraps an existing {@link org.hg4j.core.HgRepository} instance into the {@link Hg} facade.
     * 
     * @param repository the repository instance to wrap
     * @return the {@link Hg} facade instance
     */
    public static Hg wrap(org.hg4j.core.HgRepository repository) {
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
            throw new org.hg4j.errors.HgRepositoryNotFoundException("Repository not found at: " + directory.getAbsolutePath());
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
                        throw new org.hg4j.errors.HgValidationException("unsupported repository requirement: " + r);
                    }
                }
            }
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
        PushCommand command = new PushCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.PRE_PUSH)) {
            command.registerPrePushHook(hook);
        }
        for (HgHook hook : getHooks(HgHookType.POST_PUSH)) {
            command.registerPostPushHook(hook);
        }
        return command;
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

    public ImportCommand importCommand() {
        return new ImportCommand(this.repository);
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

    /**
     * Returns a new TreeWalk instance for advanced parallel sorted traversal of multiple trees.
     */
    public org.hg4j.treewalk.TreeWalk walkTree() {
        return new org.hg4j.treewalk.TreeWalk();
    }

    @Override
    public void close() {
        if (this.repository != null) {
            this.repository.close();
        }
    }
}
