package io.github.search5.hg4j.util;

import java.io.ByteArrayOutputStream;

/**
 * Mercurial's base85 ({@code mercurial/base85.py}, used by pushkey for obsolescence markers and by git style binary patches): the
 * alphabet {@code 0-9A-Za-z} followed by {@code !#$%&()*+-;<=>?@^_`{|}~}, four bytes to five characters, big endian. Without
 * padding, a last group of n bytes (1 to 3) is written as n+1 characters.
 */
public final class Base85 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~";
    private static final int[] DECODE = new int[256];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE[ALPHABET.charAt(i)] = i;
        }
    }

    private Base85() {
    }

    /**
     * Encodes bytes in Mercurial's base85 alphabet, four bytes to five characters, without padding (a last group of n bytes gives n+1 characters).
     * @param data the bytes to encode; must not be {@code null}
     * @return the encoded text, empty for an empty array
     */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < data.length; i += 4) {
            int n = Math.min(4, data.length - i);
            long acc = 0;
            for (int j = 0; j < 4; j++) {
                acc = (acc << 8) | (j < n ? data[i + j] & 0xFF : 0);
            }
            char[] group = new char[5];
            for (int j = 4; j >= 0; j--) {
                group[j] = ALPHABET.charAt((int) (acc % 85));
                acc /= 85;
            }
            out.append(group, 0, n == 4 ? 5 : n + 1);
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException for a character outside the alphabet or an impossible group length */
    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i += 5) {
            int n = Math.min(5, text.length() - i);
            if (n == 1) {
                throw new IllegalArgumentException("bad base85 length");
            }
            long acc = 0;
            for (int j = 0; j < 5; j++) {
                int value = 84; // a short last group is padded with the highest digit
                if (j < n) {
                    char c = text.charAt(i + j);
                    value = c < 256 ? DECODE[c] : -1;
                    if (value < 0) {
                        throw new IllegalArgumentException("bad base85 character: " + c);
                    }
                }
                acc = acc * 85 + value;
            }
            if (acc > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("base85 overflow");
            }
            for (int j = 0; j < (n == 5 ? 4 : n - 1); j++) {
                out.write((int) (acc >> (24 - 8 * j)) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
