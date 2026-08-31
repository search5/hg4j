package com.github.search5.hg4j.submodule;

import java.util.Objects;

/**
 * Represents a single configured subrepository definition from .hgsub or .hgsubstate.
 */
public final class HgSubrepoEntry {
    private final String path;
    private final String sourceUrl;
    private final String revision;
    private final boolean isGit;

    public HgSubrepoEntry(String path, String sourceUrl, String revision, boolean isGit) {
        if (path == null) {
            throw new IllegalArgumentException("Subrepo path cannot be null");
        }
        this.path = path;
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
        this.revision = revision != null ? revision : "";
        this.isGit = isGit;
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
        return isGit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HgSubrepoEntry that = (HgSubrepoEntry) o;
        return isGit == that.isGit &&
                path.equals(that.path) &&
                sourceUrl.equals(that.sourceUrl) &&
                revision.equals(that.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, sourceUrl, revision, isGit);
    }

    @Override
    public String toString() {
        return "HgSubrepoEntry{" +
                "path='" + path + '\'' +
                ", sourceUrl='" + sourceUrl + '\'' +
                ", revision='" + revision + '\'' +
                ", isGit=" + isGit +
                '}';
    }
}
