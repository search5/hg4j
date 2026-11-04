package io.github.search5.hg4j.fileset;

import java.util.Locale;
import java.util.function.LongPredicate;

/** {@code util.sizetoint} and {@code fileset.sizematcher}: the expressions of {@code size()}. */
final class Sizes {

    private static final String[] UNIT_NAMES = {"m", "k", "g", "kb", "mb", "gb", "b"};
    private static final long[] UNIT_VALUES = {1L << 20, 1L << 10, 1L << 30, 1L << 10, 1L << 20, 1L << 30, 1};

    private Sizes() {
    }

    private static double parseFloat(String number) {
        double value = Double.parseDouble(number.strip());
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException(number);
        }
        return value;
    }

    /** {@code util.sizetoint}. */
    static long toInt(String spec) throws FilesetParseException {
        String t = spec.strip().toLowerCase(Locale.ROOT);
        try {
            for (int i = 0; i < UNIT_NAMES.length; i++) {
                if (t.endsWith(UNIT_NAMES[i])) {
                    return (long) (parseFloat(t.substring(0, t.length() - UNIT_NAMES[i].length())) * UNIT_VALUES[i]);
                }
            }
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new FilesetParseException("couldn't parse size: " + spec);
        }
    }

    /** {@code fileset._sizetomax}: the largest size that still reads as the given one at its precision. */
    static long toMax(String spec) throws FilesetParseException {
        String s = spec.strip().toLowerCase(Locale.ROOT);
        try {
            for (int i = 0; i < UNIT_NAMES.length; i++) {
                if (s.endsWith(UNIT_NAMES[i])) {
                    String n = s.substring(0, s.length() - UNIT_NAMES[i].length());
                    double inc = 1.0;
                    int dot = n.indexOf('.');
                    if (dot >= 0) {
                        inc /= Math.pow(10, n.length() - dot - 1);
                    }
                    return (long) ((parseFloat(n) + inc) * UNIT_VALUES[i]) - 1;
                }
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new FilesetParseException("couldn't parse size: " + s);
        }
    }

    /** {@code fileset.sizematcher}. */
    static LongPredicate matcher(String expression) throws FilesetParseException {
        String expr = expression.strip();
        if (expr.contains("-")) { // a range
            int dash = expr.indexOf('-');
            long a = toInt(expr.substring(0, dash));
            long b = toInt(expr.substring(dash + 1));
            return x -> x >= a && x <= b;
        } else if (expr.startsWith("<=")) {
            long a = toInt(expr.substring(2));
            return x -> x <= a;
        } else if (expr.startsWith("<")) {
            long a = toInt(expr.substring(1));
            return x -> x < a;
        } else if (expr.startsWith(">=")) {
            long a = toInt(expr.substring(2));
            return x -> x >= a;
        } else if (expr.startsWith(">")) {
            long a = toInt(expr.substring(1));
            return x -> x > a;
        }
        long a = toInt(expr);
        long b = toMax(expr);
        return x -> x >= a && x <= b;
    }
}
