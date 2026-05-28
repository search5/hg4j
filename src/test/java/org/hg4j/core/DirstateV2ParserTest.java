package org.hg4j.core;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 2단계 & 3단계: dirstate-v2 파서와 직렬화기의 양방향 라운드트립(Round-trip) 정합성 TDD 테스트
 */
public class DirstateV2ParserTest {

    @Test
    public void testDirstateV2RoundTrip() throws Exception {
        // Given: 테스트용 부모 해시 해상도 설정
        byte[] parent1 = new byte[20];
        byte[] parent2 = new byte[20];
        for (int i = 0; i < 20; i++) {
            parent1[i] = (byte) (i + 1);
            parent2[i] = (byte) (20 - i);
        }

        // 테스트용 계층형 파일 엔트리 준비
        Map<String, Dirstate.Entry> originalEntries = new HashMap<>();
        originalEntries.put("a.txt", new Dirstate.Entry('n', 0100644, 100, 1680000000L));
        originalEntries.put("src/b.txt", new Dirstate.Entry('a', 0100755, 200, 1680000001L));
        originalEntries.put("src/main/c.txt", new Dirstate.Entry('m', 0100644, 300, 1680000002L));
        originalEntries.put("doc/readme.md", new Dirstate.Entry('r', 0, 0, 0)); // removed file

        // When: DirstateV2Serializer를 통해 dirstate-v2 바이너리로 직렬화
        byte[] v2Bytes = DirstateV2Serializer.serialize(originalEntries);
        assertNotNull(v2Bytes);
        assertTrue(v2Bytes.length > 12); // header보다 커야 함

        // When: DirstateV2Parser를 통해 바이너리를 파싱하여 디코딩
        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(v2Bytes);

        // Then: 엔트리 맵이 누락되거나 손상되지 않고 완전히 일치하는지 단언
        Map<String, Dirstate.Entry> decodedEntries = decoded.getEntries();
        assertEquals(originalEntries.size(), decodedEntries.size());

        for (Map.Entry<String, Dirstate.Entry> expected : originalEntries.entrySet()) {
            String path = expected.getKey();
            Dirstate.Entry expEntry = expected.getValue();
            Dirstate.Entry decEntry = decodedEntries.get(path);

            assertNotNull(decEntry, "Path not found in decoded dirstate: " + path);
            assertEquals(expEntry.getState(), decEntry.getState(), "State mismatch for " + path);
            assertEquals(expEntry.getMode(), decEntry.getMode(), "Mode mismatch for " + path);
            assertEquals(expEntry.getSize(), decEntry.getSize(), "Size mismatch for " + path);
            assertEquals(expEntry.getTime(), decEntry.getTime(), "Time mismatch for " + path);
        }
    }
}
