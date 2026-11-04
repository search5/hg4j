package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

/**
 * An error of the shapes code ({@code narrow/shape.rs ErrorKind}). The {@linkplain #kind() kind} and the {@linkplain #subject() subject}
 * (the offending name or path, the cycle, ...) are for callers and tests; the message is what {@code hg} prints
 * ({@code errors.rs}).
 */
public final class ShapeException extends HgValidationException {

    /** Every kind of error when handling shapes. */
    public enum Kind {
        EMPTY_SHARD_NAME, DOT_OR_HYPHEN_ONLY_SHARD_NAME, INVALID_SHARD_NAME, RESERVED_NAME, CYCLE_IN_SHARDS, PATH_IN_MULTIPLE_SHARDS,
        DUPLICATE_SHARD, SHARD_MISSING_PATHS, PROFILE_MISSING_INCLUDES, NO_SHARDS, UNKNOWN_INCLUDE, INVALID_READ_WRITE_ACCESS,
        INVALID_PATH, UNKNOWN_VERSION, PARSE_ERROR, UNKNOWN_PROFILE, NO_PROFILE_NAMES
    }

    private final Kind kind;
    private final String subject;

    ShapeException(Kind kind, String subject, String message) {
        super(message);
        this.kind = kind;
        this.subject = subject;
    }

    /**
     * Returns the kind of error.
     *
     * @return the kind, never {@code null}
     */
    public Kind kind() {
        return kind;
    }

    /** The name, path, or {@code a->b->a} cycle the error is about; for {@code UNKNOWN_INCLUDE}, {@code profile/include}. */
    public String subject() {
        return subject;
    }

    static ShapeException emptyName() {
        return new ShapeException(Kind.EMPTY_SHARD_NAME, "", "shard names must not be empty");
    }

    static ShapeException dotOrHyphenOnly(String name) {
        return new ShapeException(Kind.DOT_OR_HYPHEN_ONLY_SHARD_NAME, name,
                "invalid shard name '" + name + "': missing lowercase alphanumeric character");
    }

    static ShapeException invalidName(String name) {
        return new ShapeException(Kind.INVALID_SHARD_NAME, name,
                "invalid shard name '" + name + "': only lowercase alphanumeric, hyphen or dot are accepted");
    }

    static ShapeException reserved(String name) {
        return new ShapeException(Kind.RESERVED_NAME, name, "shard name '" + name + "' is reserved");
    }

    static ShapeException cycle(java.util.List<String> names) {
        String cycle = String.join("->", names);
        return new ShapeException(Kind.CYCLE_IN_SHARDS, cycle, "shards form a cycle: " + cycle);
    }

    static ShapeException pathInMultipleShards(String path) {
        return new ShapeException(Kind.PATH_IN_MULTIPLE_SHARDS, path, "path is found in multiple shards: " + path);
    }

    static ShapeException duplicate(String name) {
        return new ShapeException(Kind.DUPLICATE_SHARD, name, "shard '" + name + "' defined multiple times");
    }

    static ShapeException shardMissingPaths(String name) {
        return new ShapeException(Kind.SHARD_MISSING_PATHS, name, "shard '" + name + "' needs `paths`");
    }

    static ShapeException profileMissingIncludes(String name) {
        return new ShapeException(Kind.PROFILE_MISSING_INCLUDES, name, "profile '" + name + "' needs `includes`");
    }

    static ShapeException noShards() {
        return new ShapeException(Kind.NO_SHARDS, "", "`server-shapes` must include at least one shard");
    }

    static ShapeException unknownInclude(String profile, String include) {
        return new ShapeException(Kind.UNKNOWN_INCLUDE, profile + "/" + include,
                "profile '" + profile + "' includes unknown name '" + include + "'");
    }

    static ShapeException invalidReadWriteAccess(String shard, String value) {
        return new ShapeException(Kind.INVALID_READ_WRITE_ACCESS, shard + "/" + value, "invalid read-write-access value '" + value
                + "' for shard '" + shard + "': only lowercase alphanumeric, hyphen or dot are accepted, with at least one alphanumeric character");
    }

    static ShapeException invalidPath(String path, String detail) {
        return new ShapeException(Kind.INVALID_PATH, path, "`server-shapes` contains an invalid path: " + detail);
    }

    static ShapeException unknownVersion(long version) {
        return new ShapeException(Kind.UNKNOWN_VERSION, String.valueOf(version), "unknown `server-shapes` version '" + version + "'");
    }

    static ShapeException parse(String detail) {
        return new ShapeException(Kind.PARSE_ERROR, detail, "error parsing `server-shapes`:\n" + detail);
    }

    static ShapeException unknownProfile(String name) {
        return new ShapeException(Kind.UNKNOWN_PROFILE, name, "unknown profile '" + name + "'");
    }

    static ShapeException noProfileNames() {
        return new ShapeException(Kind.NO_PROFILE_NAMES, "", "at least one profile name is needed");
    }
}
