package io.github.search5.hg4j.submodule;

import java.util.Objects;

/**
 * Represents a single configured subrepository definition from .hgsub or .hgsubstate.
 *
 * @apiNote Produced by {@link HgSubrepoParser#parseSubrepositories}; used by {@code
 *     UpdateCommand} (checking out each subrepo's pinned revision) and {@code MergeCommand}
 *     (resolving a diverged subrepo pin, dispatching to {@link GitSubrepoUtil}/{@link
 *     SvnSubrepoUtil} by {@link #getType()}).
 */
public final class HgSubrepoEntry {

    /** The three subrepo kinds real hg's {@code mercurial/subrepo.py} {@code types} dict
     * supports ({@code hg}/{@code git}/{@code svn}) -- see {@link HgSubrepoParser} for how the
     * {@code [git]}/{@code [svn]} {@code .hgsub} prefixes map onto this. */
    public enum Type {
        /** A nested Mercurial repository. */
        HG,
        /** A nested Git repository. */
        GIT,
        /** A nested Subversion repository. */
        SVN
    }

    private final String path;
    private final String sourceUrl;
    private final String revision;
    private final Type type;

    /**
     * Creates an entry, distinguishing only between a Git and an hg (default) subrepo.
     *
     * @param path repository-relative path of the subrepo
     * @param sourceUrl subrepo's configured source URL (defaulted to {@code ""} if {@code null})
     * @param revision pinned revision string (defaulted to {@code ""} if {@code null})
     * @param isGit whether this is a {@code [git]}-prefixed {@code .hgsub} entry
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit) {
        this(path, sourceUrl, revision, isGit ? Type.GIT : Type.HG);
    }

    /**
     * Three-way constructor mirroring the {@code [git]}/{@code [svn]} {@code .hgsub}
     * prefixes -- {@code isSvn} wins if both flags are somehow set.
     *
     * @param path repository-relative path of the subrepo
     * @param sourceUrl subrepo's configured source URL (defaulted to {@code ""} if {@code null})
     * @param revision pinned revision string (defaulted to {@code ""} if {@code null})
     * @param isGit whether this is a {@code [git]}-prefixed {@code .hgsub} entry
     * @param isSvn whether this is a {@code [svn]}-prefixed {@code .hgsub} entry
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit, boolean isSvn) {
        this(path, sourceUrl, revision, isSvn ? Type.SVN : (isGit ? Type.GIT : Type.HG));
    }

    /**
     * Creates an entry with an explicit subrepo type.
     *
     * @param path repository-relative path of the subrepo
     * @param sourceUrl subrepo's configured source URL (defaulted to {@code ""} if {@code null})
     * @param revision pinned revision string (defaulted to {@code ""} if {@code null})
     * @param type subrepo type (defaulted to {@link Type#HG} if {@code null})
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, Type type) {
        if (path == null) {
            throw new IllegalArgumentException("Subrepo path cannot be null");
        }
        this.path = path;
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
        this.revision = revision != null ? revision : "";
        this.type = type != null ? type : Type.HG;
    }

    /**
     * Returns the subrepo's repository-relative path.
     *
     * @return the subrepo path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the subrepo's configured source URL.
     *
     * @return the source URL, or {@code ""} if none was configured
     */
    public String getSourceUrl() {
        return sourceUrl;
    }

    /**
     * Returns the subrepo's pinned revision.
     *
     * @return the pinned revision string, or {@code ""} if none was configured
     */
    public String getRevision() {
        return revision;
    }

    /**
     * Returns whether this is a {@code [git]}-prefixed {@code .hgsub} entry.
     *
     * @return {@code true} if this entry's type is {@link Type#GIT}
     */
    public boolean isGit() {
        return type == Type.GIT;
    }

    /**
     * Whether this is a {@code [svn]}-prefixed {@code .hgsub} entry.
     *
     * @return {@code true} if this entry's type is {@link Type#SVN}
     */
    public boolean isSvn() {
        return type == Type.SVN;
    }

    /**
     * Returns the subrepo's type.
     *
     * @return the subrepo type
     */
    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HgSubrepoEntry that = (HgSubrepoEntry) o;
        return type == that.type &&
                path.equals(that.path) &&
                sourceUrl.equals(that.sourceUrl) &&
                revision.equals(that.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, sourceUrl, revision, type);
    }

    @Override
    public String toString() {
        return "HgSubrepoEntry{" +
                "path='" + path + '\'' +
                ", sourceUrl='" + sourceUrl + '\'' +
                ", revision='" + revision + '\'' +
                ", isGit=" + isGit() +
                ", isSvn=" + isSvn() +
                '}';
    }
}
