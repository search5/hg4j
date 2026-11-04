package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Shard and shape names: {@code ShardName::new} and {@code shape._validate_shape_name}. */
public final class ShapeNames {

    private static final Pattern VALID = Pattern.compile("^[-.a-z0-9]+$");

    private ShapeNames() {
    }

    /**
     * Checks one name, in this order: empty, only dots and hyphens, any character that is not a lowercase letter, digit, hyphen or dot.
     * The messages are those of {@code shape.py}.
     */
    public static void validate(String name) throws HgValidationException {
        if (name.isEmpty()) {
            throw new HgValidationException("shard names must not be empty");
        }
        if (name.chars().allMatch(c -> c == '.' || c == '-')) {
            throw new HgValidationException("invalid shard name '" + name + "': missing lowercase alphanumeric character");
        }
        if (!VALID.matcher(name).matches()) {
            throw new HgValidationException("invalid shard name '" + name
                    + "': only lowercase alphanumeric, hyphen or dot are accepted");
        }
    }

    /** {@code parse_shape_names}: {@code a,b,c} split, each validated (the first invalid one is reported), sorted, without duplicates. */
    public static List<String> parse(String spec) throws HgValidationException {
        return canonical(List.of(spec.split(",", -1)));
    }

    /** {@code ShardName::canonical_names}. */
    public static List<String> canonical(List<String> names) throws HgValidationException {
        TreeSet<String> sorted = new TreeSet<>();
        for (String name : names) {
            validate(name);
            sorted.add(name);
        }
        return new ArrayList<>(sorted);
    }
}
