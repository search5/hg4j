package org.hg4j.core;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 1단계: dirstate-v2 고성능 바이너리 노드 레이아웃의 필드 정밀 맵핑 및 제어 단위 테스트
 */
public class DirstateV2LayoutTest {

    @Test
    public void testSingleNodeParsingAndModification() {
        // Given: 32바이트의 가상 dirstate-v2 노드 바이너리 데이터 준비
        byte[] buffer = new byte[32];
        ByteBuffer wrapper = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);

        // 노드 필드 정보 기입 (Offset 기반 정밀 바이너리 라이팅)
        wrapper.put(0, (byte) 'n');         // state: 'n' (normal)
        wrapper.put(1, (byte) 0x03);        // flags: 0x03
        wrapper.putInt(4, 0755);            // mode: 0755 (executable)
        wrapper.putInt(8, 12345);           // size: 12345 bytes
        wrapper.putInt(12, 1680000000);     // mtime: epoch seconds
        wrapper.putInt(16, 500);            // path_offset: 500 in data block
        wrapper.putShort(20, (short) 12);   // path_len: 12 bytes
        wrapper.putInt(24, 5);              // children_start: index 5
        wrapper.putInt(28, 2);              // children_count: 2 children

        // When: DirstateV2Node 구조체 매퍼 바인딩
        DirstateV2Node node = new DirstateV2Node(buffer, 0);

        // Then: 바이너리 해독 값에 대한 엄격한 필드 정밀성 단언
        assertEquals('n', node.getState());
        assertEquals(0x03, node.getFlags());
        assertEquals(0755, node.getMode());
        assertEquals(12345, node.getSize());
        assertEquals(1680000000L, node.getMtime());
        assertEquals(500, node.getPathOffset());
        assertEquals(12, node.getPathLen());
        assertEquals(5, node.getChildrenStart());
        assertEquals(2, node.getChildrenCount());

        // When: Java API를 이용해 노드 정보 수정
        node.setState('a');                 // 'a' (added)
        node.setMode(0644);                 // 0644 (normal file)
        node.setSize(9999);
        node.setMtime(1700000000L);
        node.setPathOffset(800);
        node.setPathLen((short) 15);
        node.setChildrenStart(10);
        node.setChildrenCount(4);

        // Then: 원본 바이트 버퍼에 실시간 및 정합하게 반영되었는지 교차 검증
        assertEquals('a', (char) wrapper.get(0));
        assertEquals(0644, wrapper.getInt(4));
        assertEquals(9999, wrapper.getInt(8));
        assertEquals(1700000000L, wrapper.getInt(12) & 0xFFFFFFFFL);
        assertEquals(800, wrapper.getInt(16));
        assertEquals((short) 15, wrapper.getShort(20));
        assertEquals(10, wrapper.getInt(24));
        assertEquals(4, wrapper.getInt(28));
    }
}
