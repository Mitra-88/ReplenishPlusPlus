package dev.replenishplusplus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageStyleTest {

    @Test
    void parseIsCaseAndSpaceInsensitive() {
        assertEquals(MessageStyle.CHAT, MessageStyle.parse("chat"));
        assertEquals(MessageStyle.ACTION_BAR, MessageStyle.parse(" action_bar "));
        assertEquals(MessageStyle.NONE, MessageStyle.parse("NONE"));
    }

    @Test
    void parseDefaultsMissingAndRejectsUnknown() {
        assertEquals(MessageStyle.CHAT, MessageStyle.parse(null));
        assertNull(MessageStyle.parse(""));
        assertNull(MessageStyle.parse("popup"));
        assertNull(MessageStyle.parse("ActionBar"));
    }
}
