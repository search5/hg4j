package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown on data integrity errors, such as revlog checksum mismatch or corrupted delta restoration.
 *
 * @apiNote The most widely thrown exception in hg4j's plumbing layer: revlog/delta/index parsing
 *     ({@code storage.Revlog}, {@code storage.RevlogIndex}, {@code storage.DeltaCodec}, {@code
 *     storage.SidedataCodec}, {@code storage.FileIndex}), bundle/changegroup parsing ({@code
 *     bundle.Bundle2Parser}, {@code bundle.ChangegroupParser}), dirstate parsing ({@code
 *     dirstate.Dirstate}, {@code dirstate.DirstateV2Parser}), diff application ({@code
 *     diff.DeltaEngine}), LFS pointer/manager handling, and subrepo parsing all throw it when the
 *     on-disk format does not match what hg4j expects. It always indicates the source data
 *     itself is malformed or truncated, not a transient I/O failure, so retrying without
 *     re-fetching or repairing the source is pointless.
 */
public class HgCorruptDataException extends IOException {
    private static final long serialVersionUID = 1L;

    public HgCorruptDataException(String message) {
        super(message);
    }

    public HgCorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
