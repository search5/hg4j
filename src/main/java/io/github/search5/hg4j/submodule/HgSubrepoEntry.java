package io.github.search5.hg4j.submodule;

import java.util.Objects;

/**
 * Represents a single configured subrepository definition from .hgsub or .hgsubstate.
 */
public final class HgSubrepoEntry {

    /** The three subrepo kinds real hg's {@code mercurial/subrepo.py} {@code types} dict
     * supports ({@code hg}/{@code git}/{@code svn}) -- see {@link HgSubrepoParser} for how the
     * {@code [git]}/{@code [svn]} {@code .hgsub} prefixes map onto this. */
    public enum Type {
        HG, GIT, SVN
    }

    private final String path;
    private final String sourceUrl;
    private final String revision;
    private final Type type;

    /**
     * Creates an entry of kind {@link Type#GIT} or {@link Type#HG}.
     *
     * @param path the subrepo path relative to the parent repository; must not be {@code null}
     * @param sourceUrl the source location; {@code null} is stored as the empty string
     * @param revision the pinned revision; {@code null} is stored as the empty string
     * @param isGit {@code true} for a git subrepo, {@code false} for a Mercurial one
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit) {
        this(path, sourceUrl, revision, isGit ? Type.GIT : Type.HG);
    }

    /** Backlog 41 (SVN subrepo support): three-way constructor mirroring the {@code [git]}/
     * {@code [svn]} {@code .hgsub} prefixes -- {@code isSvn} wins if both flags are somehow set. */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit, boolean isSvn) {
        this(path, sourceUrl, revision, isSvn ? Type.SVN : (isGit ? Type.GIT : Type.HG));
    }

    /**
     * Creates an entry.
     *
     * @param path the subrepo path relative to the parent repository; must not be {@code null}
     * @param sourceUrl the source location; {@code null} is stored as the empty string
     * @param revision the pinned revision; {@code null} is stored as the empty string
     * @param type the subrepo kind; {@code null} is stored as {@link Type#HG}
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
     * Returns the subrepo path.
     *
     * @return the path relative to the parent repository, never {@code null}
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the source location.
     *
     * @return the source, never {@code null} (empty if none was given)
     */
    public String getSourceUrl() {
        return sourceUrl;
    }

    /**
     * Returns the pinned revision.
     *
     * @return the revision, never {@code null} (empty if none was given)
     */
    public String getRevision() {
        return revision;
    }

    /**
     * Tells whether this is a git subrepo.
     *
     * @return {@code true} if the type is {@link Type#GIT}
     */
    public boolean isGit() {
        return type == Type.GIT;
    }

    /** Whether this is a {@code [svn]}-prefixed {@code .hgsub} entry (backlog 41). */
    public boolean isSvn() {
        return type == Type.SVN;
    }

    /**
     * Returns the subrepo kind.
     *
     * @return the type, never {@code null}
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
