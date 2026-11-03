package io.github.search5.hg4j.lfs;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Mercurial's {@code util.bytecount}: 1 byte count as "600 bytes", "1.46 KB", "10.0 MB", "123 GB". */
public final class HgByteCount {

    private static final long[][] TABLE = {
            {100, 1L << 30, 0}, {10, 1L << 30, 1}, {1, 1L << 30, 2},
            {100, 1L << 20, 0}, {10, 1L << 20, 1}, {1, 1L << 20, 2},
            {100, 1L << 10, 0}, {10, 1L << 10, 1}, {1, 1L << 10, 2},
    };
    private static final String[] UNIT = {"GB", "GB", "GB", "MB", "MB", "MB", "KB", "KB", "KB"};

    private HgByteCount() {
    }

    public static String format(long bytes) {
        for (int i = 0; i < TABLE.length; i++) {
            long multiplier = TABLE[i][0];
            long divisor = TABLE[i][1];
            if (bytes >= divisor * multiplier) {
                // Python's "%.Nf" rounds the exact binary value half to even
                BigDecimal value = new BigDecimal((double) bytes / divisor).setScale((int) TABLE[i][2], RoundingMode.HALF_EVEN);
                return value.toPlainString() + " " + UNIT[i];
            }
        }
        return bytes + " bytes";
    }
}
