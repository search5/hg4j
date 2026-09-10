package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.treewalk.SparseConfig;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.treewalk.WorkingDirWalk;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Porcelain API for Mercurial commands, similar to JGit's Git class.
 * Designed with elegant instance-level encapsulation and strict resource management (AutoCloseable).
 *
 * <p><strong>Thread Safety:</strong> Hg instances are fully thread-safe and support both parallel concurrent read
 * and concurrent write operations. Multiple threads can safely execute commands concurrently, and complex sequence
 * of operations (e.g. status followed by commit) can be executed with 100% thread/process atomicity using the
 * {@link #runTransaction(Runnable)} API.
 *
 * @apiNote The main library entry point: obtain an instance via {@link #open(File)}/{@link
 *     #open(String)} (an existing repository) or {@link #wrap(HgRepository)}, then call one of
 *     its per-command factory methods (e.g. {@link #commit()}, {@link #status()}, {@link
 *     #log()}) to get a pre-configured, ready-to-{@code call()} command object — mirroring
 *     JGit's {@code Git.foo()} pattern. {@link #init()}/{@link #cloneRepository()} are the two
 *     static entry points for creating a repository that doesn't exist yet.
 */
public class Hg implements AutoCloseable {
    
    private final HgRepository repository;
    private final Map<HgHookType, List<HgHook>> hooks = new ConcurrentHashMap<>();
    
    private Hg(HgRepository repository) {
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
            hooks.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(hook);
        }
        return this;
    }

    /**
     * Returns the list of all registered Java hooks for a specific phase.
     *
     * @param type the hook execution phase to look up
     * @return the hooks registered for {@code type}, or an empty list if none were registered
     */
    public List<HgHook> getHooks(HgHookType type) {
        return hooks.getOrDefault(type, Collections.emptyList());
    }

    /**
     * Provides atomic execution of complex operation sequences (e.g., status followed by commit) across threads and processes.
     * Acquires exclusive store and working copy locks to prevent concurrent write contention during execution.
     *
     * @param action the operation sequence to run while holding the store and working copy locks
     * @throws Exception if {@code action} itself throws, or if the locks cannot be acquired
     */
    public void runTransaction(Runnable action) throws Exception {
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {
            action.run();
        }
    }

    /**
     * Wraps an existing {@link io.github.search5.hg4j.lib.HgRepository} instance into the {@link Hg} facade.
     * 
     * @param repository the repository instance to wrap
     * @return the {@link Hg} facade instance
     */
    public static Hg wrap(HgRepository repository) {
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
    public static Hg open(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }
        return open(new File(path));
    }

    /**
     * Opens an existing Mercurial repository.
     * Checks repository requirements in both .hg/requires and .hg/store/requires for safety.
     * 
     * @param directory the repository directory
     * @return the {@link Hg} instance wrapping the repository
     * @throws java.io.IOException if repository not found or invalid
     */
    public static Hg open(File directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null");
        }
        File hgDir = new File(directory, ".hg");
        if (!hgDir.exists() || !hgDir.isDirectory()) {
            throw new HgRepositoryNotFoundException("Repository not found at: " + directory.getAbsolutePath());
        }

        // Robustness: Validate repository requirements format to prevent silent data corruption.
        // This allowlist must stay in sync with real hg's actual requirement strings AND with
        // what HgRepository.loadRequires() itself already understands, or even a vanilla
        // `hg init` repository with no special config could be rejected outright.
        Set<String> SUPPORTED = Set.of(
            "dotencode", "fncache", "generaldelta", "revlogv1", "store", "dirstate-v2", "share-safe",
            "sparserevlog", "revlog-compression-zstd",
            // The 6 advanced-format requirements HgRepository.loadRequires() already fully
            // supports (real strings, per mercurial/requirements.py).
            "exp-changelog-v2", "exp-revlogv2.2", "persistent-nodemap", "fileindex-v1",
            "treemanifest", "exp-copies-sidedata-changeset",
            // The real narrow-clone requirement token (NarrowCloneCommand writes exactly this) --
            // "narrowspec" (the old value here) is actually the on-disk *filename* of the
            // narrowspec data file, not a requirement string; never a valid entry in requires.
            "narrowhg-experimental"
        );

        File[] requiresFiles = {
            new File(hgDir, "requires"),
            new File(new File(hgDir, "store"), "requires")
        };

        for (File reqFile : requiresFiles) {
            if (reqFile.exists()) {
                for (String line : Files.readAllLines(reqFile.toPath())) {
                    String r = line.trim();
                    if (r.isEmpty()) continue;
                    String key = r.contains("=") ? r.substring(0, r.indexOf('=')) : r;
                    if (!SUPPORTED.contains(r) && !SUPPORTED.contains(key)) {
                        throw new HgValidationException("unsupported repository requirement: " + r);
                    }
                }
            }
        }

        return new Hg(new HgRepository(directory));
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

    /**
     * Returns the underlying repository this facade wraps.
     *
     * @return the wrapped repository
     */
    public HgRepository getRepository() {
        return this.repository;
    }

    /**
     * Creates an {@link AddCommand} bound to this repository.
     *
     * @return a new {@link AddCommand} instance
     */
    public AddCommand add() {
        return new AddCommand(this.repository);
    }

    /**
     * Creates a {@link CommitCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_COMMIT}/{@link HgHookType#POST_COMMIT} {@link HgHook}s already wired in.
     *
     * @return a new {@link CommitCommand} instance
     */
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

    /**
     * Creates a {@link StatusCommand} bound to this repository.
     *
     * @return a new {@link StatusCommand} instance
     */
    public StatusCommand status() {
        return new StatusCommand(this.repository);
    }

    /**
     * Creates a {@link LogCommand} bound to this repository.
     *
     * @return a new {@link LogCommand} instance
     */
    public LogCommand log() {
        return new LogCommand(this.repository);
    }

    /**
     * Creates a {@link BranchCommand} bound to this repository.
     *
     * @return a new {@link BranchCommand} instance
     */
    public BranchCommand branch() {
        return new BranchCommand(this.repository);
    }

    /**
     * Creates a {@link TagCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_TAG}/{@link HgHookType#POST_TAG} {@link HgHook}s already wired in.
     *
     * @return a new {@link TagCommand} instance
     */
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


    /**
     * Creates a {@link BookmarkCommand} bound to this repository.
     *
     * @return a new {@link BookmarkCommand} instance
     */
    public BookmarkCommand bookmark() {
        return new BookmarkCommand(this.repository);
    }

    /**
     * Writes a {@link TreeMergeCommand.TreeMergeResult} directly to the store as a real
     * 2-parent changeset, without a working directory/dirstate involved. See
     * {@link MergeCommitCommand}'s own javadoc for the full contract.
     *
     * @return a new {@link MergeCommitCommand} instance
     */
    public MergeCommitCommand mergeCommit() {
        return new MergeCommitCommand(this.repository);
    }

    /**
     * Creates a {@link MergeCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_MERGE}/{@link HgHookType#POST_MERGE} {@link HgHook}s already wired in.
     *
     * @return a new {@link MergeCommand} instance
     */
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

    /**
     * Creates a {@link PullCommand} bound to this repository.
     *
     * @return a new {@link PullCommand} instance
     */
    public PullCommand pull() {
        return new PullCommand(this.repository);
    }

    /**
     * Creates a {@link FetchCommand} bound to this repository.
     *
     * @return a new {@link FetchCommand} instance
     */
    public FetchCommand fetch() {
        return new FetchCommand(this.repository);
    }

    /**
     * Creates a {@link WorktreeCommand} bound to this repository.
     *
     * @return a new {@link WorktreeCommand} instance
     */
    public WorktreeCommand worktree() {
        return new WorktreeCommand(this.repository);
    }

    /**
     * Creates a {@link ShelveCommand} bound to this repository.
     *
     * @return a new {@link ShelveCommand} instance
     */
    public ShelveCommand shelve() {
        return new ShelveCommand(this.repository);
    }

    /**
     * Creates a {@link RebaseCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_REBASE}/{@link HgHookType#POST_REBASE} {@link HgHook}s already wired in.
     *
     * @return a new {@link RebaseCommand} instance
     */
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

    /**
     * Creates an {@link UpdateCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_UPDATE}/{@link HgHookType#POST_UPDATE} {@link HgHook}s already wired in.
     *
     * @return a new {@link UpdateCommand} instance
     */
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


    /**
     * Creates a {@link PushCommand} bound to this repository, with any registered
     * {@link HgHookType#PRE_PUSH}/{@link HgHookType#POST_PUSH} {@link HgHook}s already wired in.
     *
     * @return a new {@link PushCommand} instance
     */
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

    /**
     * Rolls back the last transaction, discarding the most recent commit/pull/etc. Equivalent to
     * {@code new RollbackCommand(getRepository()).call()}.
     *
     * @throws IOException if the rollback information is missing or the store cannot be rewritten
     */
    public void rollback() throws IOException {
        new RollbackCommand(this.repository).call();
    }

    /**
     * Creates a {@link CatCommand} bound to this repository.
     *
     * @return a new {@link CatCommand} instance
     */
    public CatCommand cat() {
        return new CatCommand(this.repository);
    }

    /**
     * Creates a {@link RevertCommand} bound to this repository.
     *
     * @return a new {@link RevertCommand} instance
     */
    public RevertCommand revert() {
        return new RevertCommand(this.repository);
    }

    /**
     * Creates a {@link RemoveCommand} bound to this repository.
     *
     * @return a new {@link RemoveCommand} instance
     */
    public RemoveCommand remove() {
        return new RemoveCommand(this.repository);
    }

    /**
     * Creates a {@link DiffCommand} bound to this repository.
     *
     * @return a new {@link DiffCommand} instance
     */
    public DiffCommand diff() {
        return new DiffCommand(this.repository);
    }

    /**
     * Creates a {@link TreeCommand} bound to this repository.
     *
     * @return a new {@link TreeCommand} instance
     */
    public TreeCommand tree() {
        return new TreeCommand(this.repository);
    }

    /**
     * Creates a {@link RenameCommand} bound to this repository.
     *
     * @return a new {@link RenameCommand} instance
     */
    public RenameCommand rename() {
        return new RenameCommand(this.repository);
    }

    /**
     * Creates an {@link AnnotateCommand} bound to this repository.
     *
     * @return a new {@link AnnotateCommand} instance
     */
    public AnnotateCommand annotate() {
        return new AnnotateCommand(this.repository);
    }

    /**
     * Creates a {@link ResolveCommand} bound to this repository.
     *
     * @return a new {@link ResolveCommand} instance
     */
    public ResolveCommand resolve() {
        return new ResolveCommand(this.repository);
    }

    /**
     * Creates an {@link AmendCommand} bound to this repository.
     *
     * @return a new {@link AmendCommand} instance
     */
    public AmendCommand amend() {
        return new AmendCommand(this.repository);
    }

    /**
     * Creates a {@link BisectCommand} bound to this repository.
     *
     * @return a new {@link BisectCommand} instance
     */
    public BisectCommand bisect() {
        return new BisectCommand(this.repository);
    }

    /**
     * Creates a {@link GrepCommand} bound to this repository.
     *
     * @return a new {@link GrepCommand} instance
     */
    public GrepCommand grep() {
        return new GrepCommand(this.repository);
    }

    /**
     * Creates a {@link HisteditCommand} bound to this repository.
     *
     * @return a new {@link HisteditCommand} instance
     */
    public HisteditCommand histedit() {
        return new HisteditCommand(this.repository);
    }

    /**
     * Creates an {@link ExportCommand} bound to this repository.
     *
     * @return a new {@link ExportCommand} instance
     */
    public ExportCommand export() {
        return new ExportCommand(this.repository);
    }

    /**
     * Creates an {@link ImportCommand} bound to this repository.
     *
     * @return a new {@link ImportCommand} instance
     */
    public ImportCommand importPatch() {
        return new ImportCommand(this.repository);
    }

    /**
     * Creates a {@link GraftCommand} bound to this repository, with any registered
     * {@link HgHookType#POST_GRAFT} {@link HgHook}s already wired in.
     *
     * @return a new {@link GraftCommand} instance
     */
    public GraftCommand graft() {
        GraftCommand command = new GraftCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.POST_GRAFT)) {
            command.registerPostGraftHook(hook);
        }
        return command;
    }

    /**
     * Creates a {@link PurgeCommand} bound to this repository.
     *
     * @return a new {@link PurgeCommand} instance
     */
    public PurgeCommand purge() {
        return new PurgeCommand(this.repository);
    }

    /**
     * Creates an {@link ArchiveCommand} bound to this repository.
     *
     * @return a new {@link ArchiveCommand} instance
     */
    public ArchiveCommand archive() {
        return new ArchiveCommand(this.repository);
    }

    /**
     * Creates a {@link GcCommand} bound to this repository.
     *
     * @return a new {@link GcCommand} instance
     */
    public GcCommand gc() {
        return new GcCommand(this.repository);
    }

    /**
     * Creates a {@link SubrepoCommand} bound to this repository.
     *
     * @return a new {@link SubrepoCommand} instance
     */
    public SubrepoCommand subrepo() {
        return new SubrepoCommand(this.repository);
    }

    /**
     * Creates an {@link IncomingCommand} bound to this repository.
     *
     * @return a new {@link IncomingCommand} instance
     */
    public IncomingCommand incoming() {
        return new IncomingCommand(this.repository);
    }

    /**
     * Creates an {@link OutgoingCommand} bound to this repository.
     *
     * @return a new {@link OutgoingCommand} instance
     */
    public OutgoingCommand outgoing() {
        return new OutgoingCommand(this.repository);
    }

    /**
     * Creates a {@link DescribeCommand} bound to this repository.
     *
     * @return a new {@link DescribeCommand} instance
     */
    public DescribeCommand describe() {
        return new DescribeCommand(this.repository);
    }

    /**
     * Creates a {@link PhaseCommand} bound to this repository.
     *
     * @return a new {@link PhaseCommand} instance
     */
    public PhaseCommand phase() {
        return new PhaseCommand(this.repository);
    }

    /**
     * Creates a {@link RevsetCommand} bound to this repository.
     *
     * @return a new {@link RevsetCommand} instance
     */
    public RevsetCommand revset() {
        return new RevsetCommand(this.repository);
    }

    /**
     * Creates a {@link HeadsCommand} bound to this repository.
     *
     * @return a new {@link HeadsCommand} instance
     */
    public HeadsCommand heads() {
        return new HeadsCommand(this.repository);
    }

    /**
     * Creates an {@link IdentifyCommand} bound to this repository.
     *
     * @return a new {@link IdentifyCommand} instance
     */
    public IdentifyCommand identify() {
        return new IdentifyCommand(this.repository);
    }

    /**
     * Creates a {@link StripCommand} bound to this repository.
     *
     * @return a new {@link StripCommand} instance
     */
    public StripCommand strip() {
        return new StripCommand(this.repository);
    }

    /**
     * Creates a {@link VerifyCommand} bound to this repository.
     *
     * @return a new {@link VerifyCommand} instance
     */
    public VerifyCommand verify() {
        return new VerifyCommand(this.repository);
    }

    /**
     * Creates a {@link RootCommand} bound to this repository.
     *
     * @return a new {@link RootCommand} instance
     */
    public RootCommand root() {
        return new RootCommand(this.repository);
    }

    /**
     * Creates a {@link TipCommand} bound to this repository.
     *
     * @return a new {@link TipCommand} instance
     */
    public TipCommand tip() {
        return new TipCommand(this.repository);
    }

    /**
     * Creates a {@link ParentsCommand} bound to this repository.
     *
     * @return a new {@link ParentsCommand} instance
     */
    public ParentsCommand parents() {
        return new ParentsCommand(this.repository);
    }

    /**
     * Creates a {@link SummaryCommand} bound to this repository.
     *
     * @return a new {@link SummaryCommand} instance
     */
    public SummaryCommand summary() {
        return new SummaryCommand(this.repository);
    }

    /**
     * Creates a {@link ForgetCommand} bound to this repository.
     *
     * @return a new {@link ForgetCommand} instance
     */
    public ForgetCommand forget() {
        return new ForgetCommand(this.repository);
    }

    /**
     * Creates an {@link AddremoveCommand} bound to this repository.
     *
     * @return a new {@link AddremoveCommand} instance
     */
    public AddremoveCommand addremove() {
        return new AddremoveCommand(this.repository);
    }

    /**
     * Creates a {@link BackoutCommand} bound to this repository.
     *
     * @return a new {@link BackoutCommand} instance
     */
    public BackoutCommand backout() {
        return new BackoutCommand(this.repository);
    }

    /**
     * Creates an {@link UnbundleCommand} bound to this repository.
     *
     * @return a new {@link UnbundleCommand} instance
     */
    public UnbundleCommand unbundle() {
        return new UnbundleCommand(this.repository);
    }

    /**
     * Creates a {@link BranchesCommand} bound to this repository.
     *
     * @return a new {@link BranchesCommand} instance
     */
    public BranchesCommand branches() {
        return new BranchesCommand(this.repository);
    }

    /**
     * Creates a {@link TreeMergeCommand} bound to this repository.
     *
     * @return a new {@link TreeMergeCommand} instance
     */
    public TreeMergeCommand treeMerge() {
        return new TreeMergeCommand(this.repository);
    }

    /**
     * Creates a {@link CensorCommand} bound to this repository.
     *
     * @return a new {@link CensorCommand} instance
     */
    public CensorCommand censor() {
        return new CensorCommand(this.repository);
    }

    /**
     * Creates a {@link TagsCommand} bound to this repository.
     *
     * @return a new {@link TagsCommand} instance
     */
    public TagsCommand tags() {
        return new TagsCommand(this.repository);
    }

    /**
     * Creates a {@link PathsCommand} bound to this repository.
     *
     * @return a new {@link PathsCommand} instance
     */
    public PathsCommand paths() {
        return new PathsCommand(this.repository);
    }

    /**
     * Creates a {@link FilesCommand} bound to this repository.
     *
     * @return a new {@link FilesCommand} instance
     */
    public FilesCommand files() {
        return new FilesCommand(this.repository);
    }

    /**
     * Creates a {@link LocateCommand} bound to this repository.
     *
     * @return a new {@link LocateCommand} instance
     */
    public LocateCommand locate() {
        return new LocateCommand(this.repository);
    }

    /**
     * Creates a {@link ManifestCommand} bound to this repository.
     *
     * @return a new {@link ManifestCommand} instance
     */
    public ManifestCommand manifest() {
        return new ManifestCommand(this.repository);
    }

    /**
     * Creates a {@link CopyCommand} bound to this repository.
     *
     * @return a new {@link CopyCommand} instance
     */
    public CopyCommand copy() {
        return new CopyCommand(this.repository);
    }

    /**
     * Creates a {@link BundleCommand} bound to this repository.
     *
     * @return a new {@link BundleCommand} instance
     */
    public BundleCommand bundle() {
        return new BundleCommand(this.repository);
    }

    /**
     * Creates a {@link RecoverCommand} bound to this repository.
     *
     * @return a new {@link RecoverCommand} instance
     */
    public RecoverCommand recover() {
        return new RecoverCommand(this.repository);
    }

    /**
     * Downloads a clonebundle from {@code url} (a plain HTTP(S) GET, no wire-protocol framing)
     * and applies it — the client "bypass" half of real hg's Clonebundles mechanism. See
     * {@link ClonebundlesCommand} for the full contract, including that a failure here never
     * silently falls back to a normal pull.
     *
     * @param url the clonebundle URL to download from
     * @return the commits imported from the bundle
     * @throws IOException if the download fails or the bundle cannot be applied
     * @throws HgLockException if the store lock cannot be acquired while applying the bundle
     */
    public List<byte[]> clonebundle(String url) throws IOException, HgLockException {
        return ClonebundlesCommand.downloadAndApply(this.repository, url);
    }

    /**
     * Resolves the effective {@code .hg/sparse} rules (including {@code %include}-referenced
     * profiles tracked at {@code changelogRev}) into an include/exclude pattern set, matching
     * real hg's {@code sparse.patternsforrev}. See {@link io.github.search5.hg4j.treewalk.SparseConfig}.
     *
     * @param changelogRev the changelog revision the sparse profile files should be read from
     * @return the resolved sparse include/exclude configuration for {@code changelogRev}
     * @throws IOException if the sparse profile files cannot be read
     */
    public SparseConfig sparseConfig(int changelogRev) throws IOException {
        return SparseConfig.resolveForRevision(this.repository, changelogRev);
    }

    /**
     * Expose HgRcConfig directly on the facade.
     * Incorporates Mercurial's priority load order:
     * 1. System global hgrc (/etc/mercurial/hgrc)
     * 2. User global hgrc (~/.hgrc or ~/mercurial.ini)
     * 3. Local repository hgrc (.hg/hgrc)
     *
     * @return the merged configuration from the system, user, and repository hgrc files
     */
    public HgRcConfig config() {
        HgRcConfig cfg = new HgRcConfig();
        try {
            // 1. System-wide configuration
            File systemHgrc = new File("/etc/mercurial/hgrc");
            if (systemHgrc.exists()) {
                cfg.load(systemHgrc);
            }
            
            // 2. User-wide configuration
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                File userHgrc = new File(userHome, ".hgrc");
                if (userHgrc.exists()) {
                    cfg.load(userHgrc);
                } else {
                    File userIni = new File(userHome, "mercurial.ini");
                    if (userIni.exists()) {
                        cfg.load(userIni);
                    }
                }
            }
            
            // 3. Local repository configuration
            // (this.repository is never null: the only constructor path is the private one,
            // reached exclusively via wrap()/open(), both of which reject a null repository)
            File localHgrc = new File(this.repository.getHgDir(), "hgrc");
            if (localHgrc.exists()) {
                cfg.load(localHgrc);
            }
        } catch (IOException e) {
            // ignore
        }
        return cfg;
    }

    /**
     * Returns a command object to perform a narrow clone (checking out only a subset of paths).
     *
     * @return a new, unconfigured {@link NarrowCloneCommand} instance
     */
    public static NarrowCloneCommand narrowClone() {
        return new NarrowCloneCommand();
    }

    /**
     * Helper method to directly compute diff between two revisions.
     *
     * @param oldRevision the base revision number to diff from
     * @param newRevision the target revision number to diff to
     * @return the list of per-file differences between {@code oldRevision} and {@code newRevision}
     * @throws IOException if either revision's content cannot be read
     */
    public List<DiffCommand.DiffEntry> getDiff(int oldRevision, int newRevision) throws IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly compute diff between two revisions using NodeId.
     *
     * @param oldRevision the base revision node id to diff from
     * @param newRevision the target revision node id to diff to
     * @return the list of per-file differences between {@code oldRevision} and {@code newRevision}
     * @throws IOException if either revision's content cannot be read
     */
    public List<DiffCommand.DiffEntry> getDiff(NodeId oldRevision, NodeId newRevision) throws IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision.
     *
     * @param revision the revision number to list the file tree of
     * @return the file tree entries recorded at {@code revision}
     * @throws IOException if the revision's manifest cannot be read
     */
    public List<TreeCommand.TreeEntry> getTree(int revision) throws IOException {
        return tree().setRevision(revision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision using NodeId.
     *
     * @param revision the revision node id to list the file tree of
     * @return the file tree entries recorded at {@code revision}
     * @throws IOException if the revision's manifest cannot be read
     */
    public List<TreeCommand.TreeEntry> getTree(NodeId revision) throws IOException {
        return tree().setNodeId(revision).call();
    }

    /**
     * Creates a {@link ManifestWalk} for iterating the tracked files of a specific revision's manifest.
     *
     * @param revision the revision identifier (revision number, hex node id/prefix, or {@code "tip"}) to walk
     * @return a new {@link ManifestWalk} instance
     */
    public ManifestWalk walkManifest(String revision) {
        return new ManifestWalk(this.repository, revision);
    }

    /**
     * Creates a {@link WorkingDirWalk} for iterating the working directory's files.
     *
     * @return a new {@link WorkingDirWalk} instance
     */
    public WorkingDirWalk walkWorkingDir() {
        return new WorkingDirWalk(this.repository);
    }

    /**
     * Returns a new TreeWalk instance for advanced parallel sorted traversal of multiple trees.
     *
     * @return a new, unconfigured {@link TreeWalk} instance
     */
    public TreeWalk walkTree() {
        return new TreeWalk();
    }

    @Override
    public void close() {
        if (this.repository != null) {
            this.repository.close();
        }
    }
}
