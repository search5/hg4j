package io.github.search5.hg4j.gpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Format rules of {@code .hgsigs} / {@code localsigs} (see hgext/gpg.py {@code sigwalk}, {@code node2txt}). */
public class HgSigsFileTest {

    private static final String NODE_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String NODE_B = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String B64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});

    @Test
    @DisplayName("정상 줄을 파싱하고 빈 줄은 건너뛰며 줄 번호에 세지 않는다")
    void parse_skipsEmptyLinesWithoutCountingThem() throws Exception {
        String content = NODE_A + " 0 " + B64 + "\n\n" + NODE_B + " 0 " + B64 + "\n";
        List<HgSigsFile.Line> lines = HgSigsFile.parse(content, ".hgsigs|abc");
        assertEquals(2, lines.size());
        assertEquals(NODE_A, lines.get(0).entry().getNodeHex());
        assertEquals(1, lines.get(0).lineNumber());
        assertEquals(NODE_B, lines.get(1).entry().getNodeHex());
        assertEquals(2, lines.get(1).lineNumber(), "empty lines are not counted, like hgext/gpg.py parsefile");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, lines.get(0).entry().getSignature());
        assertEquals("0", lines.get(0).entry().getVersion());
    }

    @Test
    @DisplayName("CRLF 줄바꿈도 읽는다")
    void parse_acceptsCrlf() throws Exception {
        List<HgSigsFile.Line> lines = HgSigsFile.parse(NODE_A + " 0 " + B64 + "\r\n" + NODE_B + " 0 " + B64 + "\r\n", "x");
        assertEquals(2, lines.size());
    }

    @Test
    @DisplayName("필드가 3개 미만인 줄은 파일 이름과 줄 번호를 담은 예외")
    void parse_tooFewFields_throwsWithLocation() {
        HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                () -> HgSigsFile.parse(NODE_A + " 0 " + B64 + "\n" + NODE_B + " 0\n", "localsigs"));
        assertTrue(ex.getMessage().startsWith("localsigs:2 "), ex.getMessage());
    }

    @Test
    @DisplayName("노드가 40자리 소문자 16진수가 아니면 예외")
    void parse_badNode_throws() {
        assertThrows(HgSigFormatException.class, () -> HgSigsFile.parse("xyz 0 " + B64 + "\n", "f"));
        assertThrows(HgSigFormatException.class,
                () -> HgSigsFile.parse(NODE_A.toUpperCase() + " 0 " + B64 + "\n", "f"));
        assertThrows(HgSigFormatException.class, () -> HgSigsFile.parse(NODE_A.substring(1) + " 0 " + B64 + "\n", "f"));
    }

    @Test
    @DisplayName("base64가 아닌 서명은 예외")
    void parse_badBase64_throws() {
        HgSigFormatException ex = assertThrows(HgSigFormatException.class,
                () -> HgSigsFile.parse(NODE_A + " 0 !!!not-base64!!!\n", "f"));
        assertTrue(ex.getMessage().contains("base64"), ex.getMessage());
    }

    @Test
    @DisplayName("공백만 있는 줄은 빈 줄이 아니라 형식 오류")
    void parse_whitespaceOnlyLine_isMalformed() {
        assertThrows(HgSigFormatException.class, () -> HgSigsFile.parse("   \n", "f"));
    }

    @Test
    @DisplayName("직렬화하면 줄마다 '<노드> <버전> <base64>' 와 개행 하나")
    void format_roundTrips() throws Exception {
        HgSigEntry e = new HgSigEntry(NODE_A, "0", new byte[]{9, 8, 7});
        byte[] bytes = HgSigsFile.format(List.of(e, new HgSigEntry(NODE_B, "0", new byte[]{1})));
        String text = new String(bytes, StandardCharsets.US_ASCII);
        assertEquals(NODE_A + " 0 " + Base64.getEncoder().encodeToString(new byte[]{9, 8, 7}) + "\n"
                + NODE_B + " 0 " + Base64.getEncoder().encodeToString(new byte[]{1}) + "\n", text);
        List<HgSigsFile.Line> back = HgSigsFile.parse(text, "f");
        assertEquals(e, back.get(0).entry());
    }

    @Test
    @DisplayName("서명 대상은 버전 0에서 '<소문자 hex>\\n', 다른 버전은 예외")
    void signedText_version0Only() throws Exception {
        assertArrayEquals((NODE_A + "\n").getBytes(StandardCharsets.US_ASCII), HgSigEntry.signedText(NODE_A, "0"));
        HgSigFormatException ex = assertThrows(HgSigFormatException.class, () -> HgSigEntry.signedText(NODE_A, "1"));
        assertTrue(ex.getMessage().contains("unknown signature version"), ex.getMessage());
    }
}
