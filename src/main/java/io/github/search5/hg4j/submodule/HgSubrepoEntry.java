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

    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit) {
        this(path, sourceUrl, revision, isGit ? Type.GIT : Type.HG);
    }

    /** Backlog 41 (SVN subrepo support): three-way constructor mirroring the {@code [git]}/
     * {@code [svn]} {@code .hgsub} prefixes -- {@code isSvn} wins if both flags are somehow set. */
    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit, boolean isSvn) {
        this(path, sourceUrl, revision, isSvn ? Type.SVN : (isGit ? Type.GIT : Type.HG));
    }

    public HgSubrepoEntry(String path, String sourceUrl, String revision, Type type) {
        if (path == null) {
            throw new IllegalArgumentException("Subrepo path cannot be null");
        }
        this.path = path;
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
        this.revision = revision != null ? revision : "";
        this.type = type != null ? type : Type.HG;
    }

    public String getPath() {
        return path;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getRevision() {
        return revision;
    }

    public boolean isGit() {
        return type == Type.GIT;
    }

    /** Whether this is a {@code [svn]}-prefixed {@code .hgsub} entry (backlog 41). */
    public boolean isSvn() {
        return type == Type.SVN;
    }

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
