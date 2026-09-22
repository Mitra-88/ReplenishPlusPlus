package dev.replenishplusplus.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockKeyTest {

    @Test
    void serializeAndParseRoundTrip() {
        BlockKey key = new BlockKey("world", 10, -64, 300);
        assertEquals(key, BlockKey.parse(key.serialize()));
    }

    @Test
    void malformedKeysParseToNull() {
        assertNull(BlockKey.parse(null));
        assertNull(BlockKey.parse(""));
        assertNull(BlockKey.parse("world;1;2"));
        assertNull(BlockKey.parse("world;1;2;x"));
    }
}
