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
     */
    public List<HgHook> getHooks(HgHookType type) {
        return hooks.getOrDefault(type, Collections.emptyList());
    }
    
    /**
     * Provides atomic execution of complex operation sequences (e.g., status followed by commit) across threads and processes.
     * Acquires exclusive store and working copy locks to prevent concurrent write contention during execution.
     */
    public void runTransaction(Runnable action) throws Exception {
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
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
        // 2026-09-04: this allowlist had drifted badly out of sync with real hg's actual
        // requirement strings AND with what HgRepository.loadRequires() itself already
        // understands -- see HgOpenRequirementValidationTest for the empirical proof (a vanilla
        // `hg init` repository, with no special config at all, was being rejected outright).
        Set<String> SUPPORTED = Set.of(
            "dotencode", "fncache", "generaldelta", "revlogv1", "store", "dirstate-v2", "share-safe",
            "sparserevlog", "revlog-compression-zstd",
            // The 6 advanced-format requirements HgRepository.loadRequires() already fully
            // supports (real strings confirmed empirically against actual hg 7.2/hg-rust-7.2.4
            // output throughout this session, mercurial/requirements.py).
            "exp-changelog-v2", "exp-revlogv2.2", "persistent-nodemap", "fileindex-v1",
            "treemanifest", "exp-copies-sidedata-changeset",
            // The real narrow-clone requirement token (NarrowCloneCommand writes exactly this) --
            // "narrowspec" (the old value here) is actually the on-disk *filename* of the
            // narrowspec data file, not a requirement string; never a valid entry in requires.
            "narrowhg-experimental",
            // Written by hg4j itself: `lfs` once an LFS revision arrives (HgLfsConfig.addRequirement), `shared` by
            // WorktreeCommand, and `relshared` by real hg's `share --relative`.
            "lfs", "shared", "relshared",
            // bookmarks live in .hg/store/bookmarks (HgRepository#getBookmarksFile, bookmarks.bookmarksvfs); without this entry an
            // existing bookmarks-in-store repository was refused here although every bookmarks access follows the requirement now.
            "bookmarksinstore"
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
     * Returns the underlying repository handle this facade wraps.
     * @return the repository; never {@code null}
     */
    public HgRepository getRepository() {
        return this.repository;
    }

    /**
     * Creates a new {@link AddCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public AddCommand add() {
        return new AddCommand(this.repository);
    }

    /**
     * Creates a new {@link CommitCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_COMMIT} and {@link HgHookType#POST_COMMIT} are attached to the returned command.</p>
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
     * Creates a new {@link StatusCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public StatusCommand status() {
        return new StatusCommand(this.repository);
    }

    /**
     * Creates a new {@link LogCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public LogCommand log() {
        return new LogCommand(this.repository);
    }

    /**
     * Creates a new {@link BranchCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BranchCommand branch() {
        return new BranchCommand(this.repository);
    }

    /** {@code hg sign}: signs a changeset into {@code .hgsigs} (or {@code localsigs}), see {@link SignCommand}. */
    public SignCommand sign() {
        return new SignCommand(this.repository);
    }

    /** {@code hg sigs}: lists signed changesets, see {@link SigsCommand}. */
    public SigsCommand sigs() {
        return new SigsCommand(this.repository);
    }

    /** {@code hg sigcheck}: verifies the signatures of one changeset, see {@link SigCheckCommand}. */
    public SigCheckCommand sigcheck() {
        return new SigCheckCommand(this.repository);
    }

    /**
     * Creates a new {@link TagCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_TAG} and {@link HgHookType#POST_TAG} are attached to the returned command.</p>
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
     * Creates a new {@link BookmarkCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BookmarkCommand bookmark() {
        return new BookmarkCommand(this.repository);
    }

    /**
     * Creates a new {@link MergeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_MERGE} and {@link HgHookType#POST_MERGE} are attached to the returned command.</p>
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
     * Creates a new {@link PullCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public PullCommand pull() {
        return new PullCommand(this.repository);
    }

    /**
     * Creates a new {@link FetchCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public FetchCommand fetch() {
        return new FetchCommand(this.repository);
    }

    /**
     * Creates a new {@link WorktreeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public WorktreeCommand worktree() {
        return new WorktreeCommand(this.repository);
    }

    /**
     * Creates a new {@link ShelveCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ShelveCommand shelve() {
        return new ShelveCommand(this.repository);
    }

    /**
     * Creates a new {@link RebaseCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_REBASE} and {@link HgHookType#POST_REBASE} are attached to the returned command.</p>
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
     * Creates a new {@link UpdateCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_UPDATE} and {@link HgHookType#POST_UPDATE} are attached to the returned command.</p>
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
     * Creates a new {@link PushCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#PRE_PUSH} and {@link HgHookType#POST_PUSH} are attached to the returned command.</p>
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
     * Rolls back the last transaction using the repository's undo information (the equivalent of {@code hg rollback}). It runs a {@link RollbackCommand} on this repository.
     * <p>It takes the working copy lock and the store lock.</p>
     * @throws IOException if the undo information cannot be read or applied
     * @throws io.github.search5.hg4j.errors.HgLockException if a lock cannot be acquired
     * @throws IllegalStateException if no rollback information is available
     */
    public void rollback() throws IOException, io.github.search5.hg4j.errors.HgLockException {
        new RollbackCommand(this.repository).call();
    }

    /**
     * Creates a new {@link CatCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public CatCommand cat() {
        return new CatCommand(this.repository);
    }

    /**
     * Creates a new {@link RevertCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public RevertCommand revert() {
        return new RevertCommand(this.repository);
    }

    /**
     * Creates a new {@link RemoveCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public RemoveCommand remove() {
        return new RemoveCommand(this.repository);
    }

    /**
     * Creates a new {@link DiffCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public DiffCommand diff() {
        return new DiffCommand(this.repository);
    }

    /**
     * Creates a new {@link TreeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public TreeCommand tree() {
        return new TreeCommand(this.repository);
    }

    /**
     * Creates a new {@link RenameCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public RenameCommand rename() {
        return new RenameCommand(this.repository);
    }

    /**
     * Creates a new {@link AnnotateCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public AnnotateCommand annotate() {
        return new AnnotateCommand(this.repository);
    }

    /**
     * Creates a new {@link ResolveCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ResolveCommand resolve() {
        return new ResolveCommand(this.repository);
    }

    /**
     * Creates a new {@link AmendCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public AmendCommand amend() {
        return new AmendCommand(this.repository);
    }

    /**
     * Creates a new {@link BisectCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BisectCommand bisect() {
        return new BisectCommand(this.repository);
    }

    /**
     * Creates a new {@link GrepCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public GrepCommand grep() {
        return new GrepCommand(this.repository);
    }

    /**
     * Creates a new {@link HisteditCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public HisteditCommand histedit() {
        return new HisteditCommand(this.repository);
    }

    /**
     * Creates a new {@link ExportCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ExportCommand export() {
        return new ExportCommand(this.repository);
    }

    /**
     * Creates a new {@link ImportCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ImportCommand importPatch() {
        return new ImportCommand(this.repository);
    }

    /**
     * Creates a new {@link GraftCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     * <p>Hooks registered on this facade for {@link HgHookType#POST_GRAFT} are attached to the returned command.</p>
     */
    public GraftCommand graft() {
        GraftCommand command = new GraftCommand(this.repository);
        for (HgHook hook : getHooks(HgHookType.POST_GRAFT)) {
            command.registerPostGraftHook(hook);
        }
        return command;
    }

    /**
     * Creates a new {@link PurgeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public PurgeCommand purge() {
        return new PurgeCommand(this.repository);
    }

    /**
     * Creates a new {@link ArchiveCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ArchiveCommand archive() {
        return new ArchiveCommand(this.repository);
    }

    /**
     * Creates a new {@link GcCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public GcCommand gc() {
        return new GcCommand(this.repository);
    }

    /**
     * Creates a new {@link SubrepoCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public SubrepoCommand subrepo() {
        return new SubrepoCommand(this.repository);
    }

    /**
     * Creates a new {@link IncomingCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public IncomingCommand incoming() {
        return new IncomingCommand(this.repository);
    }

    /**
     * Creates a new {@link OutgoingCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public OutgoingCommand outgoing() {
        return new OutgoingCommand(this.repository);
    }

    /**
     * Creates a new {@link DescribeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public DescribeCommand describe() {
        return new DescribeCommand(this.repository);
    }

    /**
     * Creates a new {@link PhaseCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public PhaseCommand phase() {
        return new PhaseCommand(this.repository);
    }

    /**
     * Creates a new {@link RevsetCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public RevsetCommand revset() {
        return new RevsetCommand(this.repository);
    }

    /**
     * Creates a new {@link HeadsCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public HeadsCommand heads() {
        return new HeadsCommand(this.repository);
    }

    /**
     * Creates a new {@link IdentifyCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public IdentifyCommand identify() {
        return new IdentifyCommand(this.repository);
    }

    /**
     * Creates a new {@link StripCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public StripCommand strip() {
        return new StripCommand(this.repository);
    }

    /**
     * Creates a new {@link VerifyCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public VerifyCommand verify() {
        return new VerifyCommand(this.repository);
    }

    /**
     * Creates a new {@link RootCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public RootCommand root() {
        return new RootCommand(this.repository);
    }

    /**
     * Creates a new {@link TipCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public TipCommand tip() {
        return new TipCommand(this.repository);
    }

    /**
     * Creates a new {@link ParentsCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ParentsCommand parents() {
        return new ParentsCommand(this.repository);
    }

    /**
     * Creates a new {@link SummaryCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public SummaryCommand summary() {
        return new SummaryCommand(this.repository);
    }

    /**
     * Creates a new {@link ForgetCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ForgetCommand forget() {
        return new ForgetCommand(this.repository);
    }

    /**
     * Creates a new {@link AddremoveCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public AddremoveCommand addremove() {
        return new AddremoveCommand(this.repository);
    }

    /**
     * Creates a new {@link BackoutCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BackoutCommand backout() {
        return new BackoutCommand(this.repository);
    }

    /**
     * Creates a new {@link UnbundleCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public UnbundleCommand unbundle() {
        return new UnbundleCommand(this.repository);
    }

    /**
     * Creates a new {@link BranchesCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BranchesCommand branches() {
        return new BranchesCommand(this.repository);
    }

    /**
     * Creates a new {@link TreeMergeCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public TreeMergeCommand treeMerge() {
        return new TreeMergeCommand(this.repository);
    }

    /**
     * Creates a new {@link MergeCommitCommand} bound to this repository: it merges two heads and commits the result as one changeset
     * with two parents, without a working copy. Each call returns a fresh, unconfigured command object.
     */
    public MergeCommitCommand mergeCommit() {
        return new MergeCommitCommand(this.repository);
    }

    /**
     * Creates a new {@link CensorCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public CensorCommand censor() {
        return new CensorCommand(this.repository);
    }

    /**
     * Creates a new {@link TagsCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public TagsCommand tags() {
        return new TagsCommand(this.repository);
    }

    /**
     * Creates a new {@link PathsCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public PathsCommand paths() {
        return new PathsCommand(this.repository);
    }

    /**
     * Creates a new {@link FilesCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public FilesCommand files() {
        return new FilesCommand(this.repository);
    }

    /**
     * Creates a new {@link LocateCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public LocateCommand locate() {
        return new LocateCommand(this.repository);
    }

    /**
     * Creates a new {@link ManifestCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public ManifestCommand manifest() {
        return new ManifestCommand(this.repository);
    }

    /**
     * Creates a new {@link CopyCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public CopyCommand copy() {
        return new CopyCommand(this.repository);
    }

    /**
     * Creates a new {@link BundleCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
     */
    public BundleCommand bundle() {
        return new BundleCommand(this.repository);
    }

    /**
     * Creates a new {@link RecoverCommand} bound to this repository. Each call returns a fresh, unconfigured command object.
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
     * @return the commits imported from the bundle
     */
    public List<byte[]> clonebundle(String url) throws IOException, HgLockException {
        return ClonebundlesCommand.downloadAndApply(this.repository, url);
    }

    /**
     * Resolves the effective {@code .hg/sparse} rules (including {@code %include}-referenced
     * profiles tracked at {@code changelogRev}) into an include/exclude pattern set, matching
     * real hg's {@code sparse.patternsforrev}. See {@link io.github.search5.hg4j.treewalk.SparseConfig}.
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
     * Creates a new {@link NarrowCloneCommand} for cloning a repository with a narrowed set of paths. It is not tied to an existing repository.
     * @return a new narrow-clone command
     */
    public static NarrowCloneCommand narrowClone() {
        return new NarrowCloneCommand();
    }

    /**
     * Helper method to directly compute diff between two revisions.
     */
    public List<DiffCommand.DiffEntry> getDiff(int oldRevision, int newRevision) throws IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly compute diff between two revisions using NodeId.
     */
    public List<DiffCommand.DiffEntry> getDiff(NodeId oldRevision, NodeId newRevision) throws IOException {
        return diff().setOldRevision(oldRevision).setNewRevision(newRevision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision.
     */
    public List<TreeCommand.TreeEntry> getTree(int revision) throws IOException {
        return tree().setRevision(revision).call();
    }

    /**
     * Helper method to directly retrieve the file tree of a revision using NodeId.
     */
    public List<TreeCommand.TreeEntry> getTree(NodeId revision) throws IOException {
        return tree().setNodeId(revision).call();
    }

    /**
     * Creates a walker over the manifest of the given revision.
     * @param revision the revision identifier whose manifest is walked
     * @return a new {@link ManifestWalk} bound to this repository
     */
    public ManifestWalk walkManifest(String revision) {
        return new ManifestWalk(this.repository, revision);
    }

    /**
     * Creates a walker over the files of the working directory.
     * @return a new {@link WorkingDirWalk} bound to this repository
     */
    public WorkingDirWalk walkWorkingDir() {
        return new WorkingDirWalk(this.repository);
    }

    /**
     * Returns a new TreeWalk instance for advanced parallel sorted traversal of multiple trees.
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
