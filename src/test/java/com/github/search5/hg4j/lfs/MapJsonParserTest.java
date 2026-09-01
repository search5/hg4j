package com.github.search5.hg4j.lfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MapJsonParserTest {

    private Object parse(String json) throws IOException {
        return new HgLfsManager.MapJsonParser(json).parse();
    }

    @Test
    public void testParseEmptyObjectAndArray() throws IOException {
        Object obj = parse("{}");
        assertTrue(obj instanceof Map);
        assertTrue(((Map<?, ?>) obj).isEmpty());

        Object arr = parse("[]");
        assertTrue(arr instanceof List);
        assertTrue(((List<?>) arr).isEmpty());
    }

    @Test
    public void testParseNestedStructuresAndMultipleElements() throws IOException {
        String json = "{\"a\":[1,2,3],\"b\":{\"c\":\"d\"},\"e\":[{\"f\":1},{\"g\":2}]}";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parse(json);

        List<?> a = (List<?>) map.get("a");
        assertEquals(List.of(1L, 2L, 3L), a);

        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) map.get("b");
        assertEquals("d", b.get("c"));

        List<?> e = (List<?>) map.get("e");
        assertEquals(2, e.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> e0 = (Map<String, Object>) e.get(0);
        assertEquals(1L, e0.get("f"));
        @SuppressWarnings("unchecked")
        Map<String, Object> e1 = (Map<String, Object>) e.get(1);
        assertEquals(2L, e1.get("g"));
    }

    @Test
    public void testParseStringEscapeSequences() throws IOException {
        String jsonInput = "\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\\u0041j\"";
        String expected = "a\"b\\c/d\be\ff\ng\rh\tiAj";
        assertEquals(expected, parse(jsonInput));
    }

    @Test
    public void testParseUnknownEscapeFallsThroughLiterally() throws IOException {
        assertEquals("q", parse("\"\\q\""));
    }

    @Test
    public void testParseWithSurroundingWhitespace() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parse("  \n\t {  \"a\"  :  1  }  \n ");
        assertEquals(1L, map.get("a"));
    }

    @Test
    public void testParseNumberFormats() throws IOException {
        assertEquals(42L, parse("42"));
        assertEquals(-17L, parse("-17"));
        assertEquals(3.14, parse("3.14"));
        assertEquals(1e10, parse("1e10"));
        assertEquals(-2.5E-3, parse("-2.5E-3"));
    }

    @Test
    public void testParseBooleansAndNull() throws IOException {
        assertEquals(Boolean.TRUE, parse("true"));
        assertEquals(Boolean.FALSE, parse("false"));
        assertNull(parse("null"));
    }

    @Test
    public void testParseUnexpectedEndOfInputThrows() {
        assertThrows(IOException.class, () -> parse(""));
        assertThrows(IOException.class, () -> parse("   "));
    }

    @Test
    public void testParseUnexpectedCharacterThrows() {
        assertThrows(IOException.class, () -> parse("@"));
    }

    @Test
    public void testParseObjectKeyNotStringThrows() {
        assertThrows(IOException.class, () -> parse("{123:1}"));
    }

    @Test
    public void testParseObjectMissingColonThrows() {
        assertThrows(IOException.class, () -> parse("{\"a\" 1}"));
    }

    @Test
    public void testParseObjectMissingCommaOrBraceThrows() {
        assertThrows(IOException.class, () -> parse("{\"a\":1 \"b\":2}"));
    }

    @Test
    public void testParseArrayMissingCommaOrBracketThrows() {
        assertThrows(IOException.class, () -> parse("[1 2]"));
    }

    @Test
    public void testParseUnterminatedStringThrows() {
        assertThrows(IOException.class, () -> parse("\"abc"));
    }

    @Test
    public void testParseUnterminatedStringEscapeThrows() {
        assertThrows(IOException.class, () -> parse("\"abc\\"));
    }

    @Test
    public void testParseUnterminatedUnicodeEscapeThrows() {
        assertThrows(IOException.class, () -> parse("\"\\u12\""));
    }

    @Test
    public void testParseInvalidBooleanThrows() {
        assertThrows(IOException.class, () -> parse("tx"));
        assertThrows(IOException.class, () -> parse("fx"));
    }

    @Test
    public void testParseInvalidNullThrows() {
        assertThrows(IOException.class, () -> parse("nx"));
    }
}
