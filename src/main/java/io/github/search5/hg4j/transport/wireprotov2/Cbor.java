package io.github.search5.hg4j.transport.wireprotov2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, purpose-built CBOR (RFC 8949) encoder/decoder for real hg's wireprotocol v2.
 *
 * <p>This exists instead of using Jackson's CBOR module because real hg encodes essentially
 * every string — map keys included — as a CBOR <em>byte string</em> (major type 2), never as a
 * CBOR text string (major type 3): Mercurial's internal strings are all Python {@code bytes}, and
 * {@code mercurial/utils/cborutil.py}'s {@code streamencodebytestring} is what backs its string
 * encoding. In a real Mercurial capabilities response, every key, e.g. {@code b'apibase'}, is
 * major-type-2.
 * Jackson's {@code CBORGenerator.writeFieldName} only ever emits major-type-3 field names, so it
 * cannot produce (or transparently consume as lookups) this shape — hence this standalone
 * implementation.</p>
 *
 * <p>Encoding rules used here: {@code null}→CBOR null, {@code Boolean}→true/false,
 * {@code byte[]}→byte string, {@code String}→byte string (UTF-8 bytes — matching real hg, which
 * never emits a genuine text string in this protocol), any {@code Number}→unsigned/negative
 * integer, {@code Map}→map (keys encoded the same as any other value), {@code List}→array.</p>
 *
 * <p>Decoding rules: a byte string decodes to {@code byte[]}; when it is a <em>map key</em> it
 * is instead decoded to a {@code String} (UTF-8) for ergonomic {@code map.get("name")} lookups —
 * safe because every real key in this protocol is a short ASCII identifier. A CBOR tag (major
 * type 6) is transparently unwrapped (its tag number is discarded) since none of the commands
 * this client/server pair implements need tag semantics (e.g. the "set" tag on capability
 * descriptor fields, which callers here never need to consume). Only definite-length items are
 * supported for decoding — confirmed sufficient because {@code cborutil.streamencode}, which
 * backs every command request/response this implementation talks to, always uses definite
 * lengths (indefinite-length encoding exists in real hg only for specialized iterator/streaming
 * paths that none of the implemented commands use).</p>
 */
public final class Cbor {
    private Cbor() {
    }

    /**
     * Encodes a single value as CBOR, following the encoding rules documented on this class
     * (strings as byte strings, maps/lists recursively encoded, etc.).
     *
     * @param value the value to encode ({@code null}, {@code Boolean}, {@code byte[]},
     *              {@code String}, {@code Number}, {@code Map}, or {@code List})
     * @return the CBOR-encoded bytes of {@code value}
     * @throws IllegalArgumentException if {@code value} is of an unsupported type
     */
    public static byte[] encode(Object value) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeValue(out, value);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Encodes each value in {@code values} independently and concatenates the resulting CBOR
     * byte sequences, matching how real hg streams multiple top-level CBOR items back to back.
     *
     * @param values the values to encode, in order
     * @return the concatenation of each value's individual CBOR encoding
     */
    public static byte[] encodeAll(List<Object> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object v : values) {
            byte[] b = encode(v);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    /**
     * Decodes a byte buffer containing zero or more concatenated, definite-length CBOR items
     * (the shape real hg's {@code cborutil.streamencode} produces).
     *
     * @param data the raw bytes containing one or more back-to-back CBOR-encoded values
     * @return the decoded values, in the order they appeared in {@code data}
     * @throws IllegalArgumentException if {@code data} contains an unsupported or malformed CBOR item
     */
    public static List<Object> decodeAll(byte[] data) {
        List<Object> result = new ArrayList<>();
        Reader r = new Reader(data);
        while (r.pos < data.length) {
            result.add(r.readValue(false));
        }
        return result;
    }

    /**
     * Casts a decoded CBOR value to a string-keyed map, treating anything else as empty.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @return {@code o} cast to {@code Map<String, Object>} if it is a map, otherwise an empty map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return Map.of();
    }

    /**
     * Casts a decoded CBOR value to a list, treating anything else as empty.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @return {@code o} cast to {@code List<Object>} if it is a list, otherwise an empty list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List) {
            return (List<Object>) o;
        }
        return List.of();
    }

    /**
     * Casts a decoded CBOR value to a byte array.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @return {@code o} cast to {@code byte[]} if it is a byte array, otherwise {@code null}
     */
    public static byte[] asBytes(Object o) {
        return o instanceof byte[] ? (byte[]) o : null;
    }

    /**
     * Converts a decoded CBOR value to a string, decoding a raw byte string as UTF-8 (matching
     * real hg's convention of encoding strings as CBOR byte strings) and falling back to
     * {@link String#valueOf} for any other non-null type.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @return the string representation of {@code o}, or {@code null} if {@code o} is {@code null}
     */
    public static String asString(Object o) {
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof byte[]) {
            return new String((byte[]) o, StandardCharsets.UTF_8);
        }
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Casts a decoded CBOR value to a {@code long}, falling back to a default when it is not a
     * number.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @param defaultValue the value to return if {@code o} is not a {@link Number}
     * @return {@code o}'s long value if it is a {@link Number}, otherwise {@code defaultValue}
     */
    public static long asLong(Object o, long defaultValue) {
        return o instanceof Number ? ((Number) o).longValue() : defaultValue;
    }

    /**
     * Casts a decoded CBOR value to a {@code boolean}, falling back to a default when it is not
     * a boolean.
     *
     * @param o a value previously produced by {@link #decodeAll(byte[])}
     * @param defaultValue the value to return if {@code o} is not a {@link Boolean}
     * @return {@code o}'s boolean value if it is a {@link Boolean}, otherwise {@code defaultValue}
     */
    public static boolean asBoolean(Object o, boolean defaultValue) {
        return o instanceof Boolean ? (Boolean) o : defaultValue;
    }

    // ==================== encoding ====================

    private static void writeValue(OutputStream out, Object value) throws IOException {
        if (value == null) {
            out.write(0xf6);
        } else if (value instanceof Boolean) {
            out.write(((Boolean) value) ? 0xf5 : 0xf4);
        } else if (value instanceof byte[]) {
            byte[] b = (byte[]) value;
            writeHead(out, 2, b.length);
            out.write(b);
        } else if (value instanceof String) {
            byte[] b = ((String) value).getBytes(StandardCharsets.UTF_8);
            writeHead(out, 2, b.length);
            out.write(b);
        } else if (value instanceof Number) {
            writeInt(out, ((Number) value).longValue());
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            writeHead(out, 5, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                writeValue(out, e.getKey());
                writeValue(out, e.getValue());
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            writeHead(out, 4, list.size());
            for (Object item : list) {
                writeValue(out, item);
            }
        } else {
            throw new IllegalArgumentException("Cannot CBOR-encode value of type " + value.getClass());
        }
    }

    private static void writeInt(OutputStream out, long v) throws IOException {
        if (v >= 0) {
            writeHead(out, 0, v);
        } else {
            writeHead(out, 1, -1 - v);
        }
    }

    private static void writeHead(OutputStream out, int majorType, long length) throws IOException {
        int mt = majorType << 5;
        if (length < 24) {
            out.write(mt | (int) length);
        } else if (length <= 0xFF) {
            out.write(mt | 24);
            out.write((int) length);
        } else if (length <= 0xFFFF) {
            out.write(mt | 25);
            out.write((int) (length >>> 8));
            out.write((int) length);
        } else if (length <= 0xFFFFFFFFL) {
            out.write(mt | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (length >>> shift));
            }
        } else {
            out.write(mt | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (length >>> shift));
            }
        }
    }

    // ==================== decoding ====================

    private static final class Reader {
        final byte[] data;
        int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        Object readValue(boolean asKey) {
            int first = data[pos++] & 0xFF;
            int majorType = first >>> 5;
            int info = first & 0x1F;

            switch (majorType) {
                case 0:
                    return readLength(info);
                case 1:
                    return -1L - readLength(info);
                case 2: {
                    int len = (int) readLength(info);
                    byte[] b = readBytes(len);
                    return asKey ? new String(b, StandardCharsets.UTF_8) : b;
                }
                case 3: {
                    int len = (int) readLength(info);
                    byte[] b = readBytes(len);
                    return new String(b, StandardCharsets.UTF_8);
                }
                case 4: {
                    int len = (int) readLength(info);
                    List<Object> list = new ArrayList<>(len);
                    for (int i = 0; i < len; i++) {
                        list.add(readValue(false));
                    }
                    return list;
                }
                case 5: {
                    int len = (int) readLength(info);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < len; i++) {
                        Object key = readValue(true);
                        String keyStr = key instanceof String ? (String) key : String.valueOf(key);
                        Object v = readValue(false);
                        map.put(keyStr, v);
                    }
                    return map;
                }
                case 6:
                    readLength(info); // discard tag number; return the tagged value unwrapped
                    return readValue(asKey);
                case 7:
                    if (info == 20) return Boolean.FALSE;
                    if (info == 21) return Boolean.TRUE;
                    if (info == 22 || info == 23) return null;
                    throw new IllegalArgumentException("Unsupported CBOR simple/float type, info=" + info);
                default:
                    throw new IllegalArgumentException("Unreachable CBOR major type " + majorType);
            }
        }

        long readLength(int info) {
            if (info < 24) {
                return info;
            }
            if (info == 24) {
                return readBytes(1)[0] & 0xFFL;
            }
            if (info == 25) {
                byte[] b = readBytes(2);
                return ((b[0] & 0xFFL) << 8) | (b[1] & 0xFFL);
            }
            if (info == 26) {
                byte[] b = readBytes(4);
                long v = 0;
                for (byte x : b) v = (v << 8) | (x & 0xFFL);
                return v;
            }
            if (info == 27) {
                byte[] b = readBytes(8);
                long v = 0;
                for (byte x : b) v = (v << 8) | (x & 0xFFL);
                return v;
            }
            throw new IllegalArgumentException("Indefinite-length CBOR items are not supported (info=" + info + ")");
        }

        byte[] readBytes(int n) {
            byte[] b = new byte[n];
            System.arraycopy(data, pos, b, 0, n);
            pos += n;
            return b;
        }
    }
}
