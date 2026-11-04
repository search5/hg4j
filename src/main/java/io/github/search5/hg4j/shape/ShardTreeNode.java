package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A normalized tree of nested include and exclude paths ({@code shape.py ShardTreeNode}, {@code narrow/shape.rs ShardTreeNode}):
 * semantically equivalent rules give the same tree, hence the same {@linkplain #fingerprint() fingerprint}. A node's {@code included} flag
 * always differs from its children's; the root is the empty path. Paths are repository relative, {@code /} separated, no leading or trailing
 * slash; they are compared and measured as UTF-8 bytes.
 *
 * <p>See {@code llm-wiki/concepts/store-shapes.md} for the analysis behind this class.</p>
 */
public final class ShardTreeNode {

    private static final int PATTERN_INCLUDED_FLAG = 1 << 15;

    private final String path;
    private final boolean included;
    private final List<ShardTreeNode> children;

    private ShardTreeNode(String path, boolean included, List<ShardTreeNode> children) {
        this.path = path;
        this.included = included;
        this.children = children;
    }

    /**
     * Returns the node's path.
     *
     * @return the repository-relative path, {@code /} separated; the empty string for the root
     */
    public String path() {
        return path;
    }

    /**
     * Tells whether the files under this node's path are included.
     *
     * @return {@code true} if included, {@code false} if excluded
     */
    public boolean included() {
        return included;
    }

    /**
     * Returns the nested nodes.
     *
     * @return the immutable list of child nodes in tree order; empty for a leaf
     */
    public List<ShardTreeNode> children() {
        return children;
    }

    // ---------------------------------------------------------------- building

    private static final class Temp {
        final String path;
        final byte[] zero;
        final boolean included;
        final List<Temp> children = new ArrayList<>();

        Temp(String path, boolean included) throws HgValidationException {
            this.path = path;
            this.zero = zeroPath(path);
            this.included = included;
        }

        boolean subPathOf(Temp other) {
            return startsWith(zero, other.zero);
        }

        ShardTreeNode freeze() {
            List<ShardTreeNode> frozen = new ArrayList<>(children.size());
            for (Temp child : children) {
                frozen.add(child.freeze());
            }
            return new ShardTreeNode(path, included, List.copyOf(frozen));
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    /**
     * The sort key of a path ({@code ZeroPath}): {@code /} becomes a NUL and the path is wrapped in NULs, so a directory sorts right before its
     * contents and {@code foo} never gets mixed with {@code foo-x}.
     *
     * @throws HgValidationException for a leading or trailing slash
     */
    public static byte[] zeroPath(String path) throws HgValidationException {
        if (path.startsWith("/")) {
            throw new HgValidationException("invalid HgPath '" + byteList(path) + "': has a leading slash.");
        }
        if (path.endsWith("/")) {
            throw new HgValidationException("path '" + path + "': ends with a slash");
        }
        if (path.isEmpty()) {
            return new byte[]{0};
        }
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length + 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i + 1] = bytes[i] == '/' ? 0 : bytes[i];
        }
        return out;
    }

    /** Rust's {@code {:?}} of a byte vector, which is how {@code errors.rs} prints the bytes of an invalid path: {@code [97, 98]}. */
    static String byteList(String path) {
        StringBuilder sb = new StringBuilder("[");
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(bytes[i] & 0xFF);
        }
        return sb.append(']').toString();
    }

    private static final Comparator<byte[]> BYTES = Arrays::compareUnsigned;

    /**
     * {@code ShardTreeNode.from_paths} of the Rust code: {@code paths} are all the paths that matter (the root {@code ""} must be one of them),
     * {@code includes} the ones that are included. The result is the minimal tree: a node that repeats its parent's inclusion is dropped.
     */
    public static ShardTreeNode fromPaths(Collection<String> paths, Set<String> includes) throws HgValidationException {
        List<Temp> nodes = new ArrayList<>(paths.size());
        for (String p : paths) {
            nodes.add(new Temp(p, includes.contains(p)));
        }
        return build(nodes);
    }

    private static ShardTreeNode build(List<Temp> nodes) {
        nodes.sort((a, b) -> BYTES.compare(a.zero, b.zero));
        List<Temp> stack = new ArrayList<>();
        for (Temp node : nodes) {
            while (!stack.isEmpty() && !node.subPathOf(stack.get(stack.size() - 1))) {
                stack.remove(stack.size() - 1);
            }
            if (!stack.isEmpty()) {
                Temp last = stack.get(stack.size() - 1);
                if (last.included != node.included) { // an equal inclusion would be redundant
                    last.children.add(node);
                    stack.add(node);
                }
            } else {
                stack.add(node);
            }
        }
        return stack.get(0).freeze();
    }

    /**
     * {@code ShardTreeNode.from_patterns} of {@code shape.py}: the tree of old style narrow patterns (already stripped of {@code path:}).
     * Includes that are empty, {@code .}, or missing mean "everything"; a path that is both included and excluded is excluded.
     */
    public static ShardTreeNode fromPatterns(Set<String> includePatterns, Set<String> excludePatterns) throws HgValidationException {
        Set<String> includes = new LinkedHashSet<>(includePatterns);
        Set<String> excludes = new LinkedHashSet<>(excludePatterns);
        List<String> root = List.of("");
        if (includes.contains("") || includes.contains(".") || includes.isEmpty()) {
            includes.remove(".");
            includes.add("");
            root = List.of();
        }
        includes.removeAll(excludes);
        List<Temp> nodes = new ArrayList<>();
        for (String p : root) {
            nodes.add(new Temp(p, includes.contains(p)));
        }
        for (String p : includes) {
            nodes.add(new Temp(p, true));
        }
        for (String p : excludes) {
            nodes.add(new Temp(p, includes.contains(p)));
        }
        return build(nodes);
    }

    // ---------------------------------------------------------------- reading the tree

    /** The tree as (includes, excludes), self first and then the children in order. */
    public Patterns flat() {
        Set<String> includes = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        collect(includes, excludes);
        return new Patterns(includes, excludes);
    }

    private void collect(Set<String> includes, Set<String> excludes) {
        (included ? includes : excludes).add(path);
        for (ShardTreeNode child : children) {
            child.collect(includes, excludes);
        }
    }

    /** Includes and excludes of a tree or a patterns block. */
    public record Patterns(Set<String> includes, Set<String> excludes) {
    }

    /**
     * {@code deepest_prefix_node}: the deepest node whose path is a directory prefix of {@code path} (or equals it); the root when none is.
     * At most one child can match, because the children of a node never contain each other.
     */
    public ShardTreeNode deepestPrefixNode(String candidate) {
        if (candidate.isEmpty()) {
            return this;
        }
        ShardTreeNode found = deepest(candidate);
        return found == null ? this : found;
    }

    private ShardTreeNode deepest(String candidate) {
        if (!path.isEmpty() && !(candidate.startsWith(path)
                && (candidate.length() == path.length() || candidate.charAt(path.length()) == '/'))) {
            return null;
        }
        for (ShardTreeNode child : children) {
            ShardTreeNode match = child.deepest(candidate);
            if (match != null) {
                return match;
            }
        }
        return this;
    }

    // ---------------------------------------------------------------- the patterns block and the fingerprint

    /**
     * The "patterns block": {@code <COUNT:u32>[<SIZE:u16>...][<PATH>...]}, big endian, one entry per path sorted by {@linkplain #zeroPath
     * zero path}. A size has its top bit set when the path is included.
     *
     * @throws HgValidationException {@code path is too long: N bytes} for a path of 32768 bytes or more
     */
    public static byte[] serializePatterns(Collection<String> includes, Collection<String> excludes) throws HgValidationException {
        record Entry(byte[] key, byte[] bytes, boolean included) {
        }
        List<Entry> entries = new ArrayList<>();
        for (String p : includes) {
            entries.add(new Entry(zeroPath(p), p.getBytes(StandardCharsets.UTF_8), true));
        }
        for (String p : excludes) {
            entries.add(new Entry(zeroPath(p), p.getBytes(StandardCharsets.UTF_8), false));
        }
        entries.sort((a, b) -> BYTES.compare(a.key(), b.key()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(entries.size()).array());
        for (Entry e : entries) {
            int size = e.bytes().length;
            if (size >= PATTERN_INCLUDED_FLAG) {
                throw new HgValidationException("path is too long: " + size + " bytes");
            }
            out.writeBytes(ByteBuffer.allocate(2).putShort((short) (e.included() ? size | PATTERN_INCLUDED_FLAG : size)).array());
        }
        for (Entry e : entries) {
            out.writeBytes(e.bytes());
        }
        return out.toByteArray();
    }

    /** The block of this tree. */
    public byte[] serializePatterns() throws HgValidationException {
        Patterns flat = flat();
        return serializePatterns(flat.includes(), flat.excludes());
    }

    private static void checkLength(byte[] data, int expected, boolean exact) throws HgValidationException {
        boolean invalid = exact ? data.length != expected : data.length < expected;
        if (invalid) {
            throw new HgValidationException("error deserializing shape: invalid length (expected " + expected + ", got " + data.length + ")");
        }
    }

    /**
     * The reverse of {@link #serializePatterns(Collection, Collection)}. Lengths are checked in this order: the count, then the size table,
     * then the paths, which must account for every remaining byte.
     */
    public static Patterns deserializePatterns(byte[] data) throws HgValidationException {
        checkLength(data, 4, false);
        long count = ByteBuffer.wrap(data, 0, 4).getInt() & 0xFFFFFFFFL;
        long pathsStart = 4 + count * 2;
        if (pathsStart > Integer.MAX_VALUE) {
            throw new HgValidationException("error deserializing shape: invalid length (expected " + pathsStart + ", got " + data.length + ")");
        }
        checkLength(data, (int) pathsStart, false);
        int[] sizes = new int[(int) count];
        boolean[] included = new boolean[(int) count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            int raw = ByteBuffer.wrap(data, 4 + i * 2, 2).getShort() & 0xFFFF;
            included[i] = (raw & PATTERN_INCLUDED_FLAG) != 0;
            sizes[i] = raw & ~PATTERN_INCLUDED_FLAG;
            total += sizes[i];
        }
        checkLength(data, (int) (pathsStart + total), true);
        Set<String> includes = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        int cursor = (int) pathsStart;
        for (int i = 0; i < count; i++) {
            byte[] raw = Arrays.copyOfRange(data, cursor, cursor + sizes[i]);
            cursor += sizes[i];
            String path = new String(raw, StandardCharsets.UTF_8);
            if (path.indexOf('\0') >= 0 || path.contains("//") || path.startsWith("/") || path.endsWith("/")) {
                throw new HgValidationException("error deserializing shape: invalid path '" + path + "'");
            }
            (included[i] ? includes : excludes).add(path);
        }
        return new Patterns(includes, excludes);
    }

    /** The shape fingerprint: SHA-256 of the patterns block, version {@value Fingerprint#CURRENT_VERSION}. */
    public Fingerprint fingerprint() throws HgValidationException {
        try {
            return new Fingerprint(Fingerprint.Kind.SHAPE, Fingerprint.CURRENT_VERSION,
                    MessageDigest.getInstance("SHA-256").digest(serializePatterns()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return (included ? "+" : "-") + "'" + path + "'" + (children.isEmpty() ? "" : children.toString());
    }
}
