package dev.replenishplusplus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextUtilTest {

    @Test
    void prettyNameCapitalizesWordsAndSplitsUnderscores() {
        assertEquals("Wheat", TextUtil.prettyName("WHEAT"));
        assertEquals("Nether Wart", TextUtil.prettyName("NETHER_WART"));
        assertEquals("Carrots", TextUtil.prettyName("CARROTS"));
    }

    @Test
    void prettyNameHandlesLowercaseAndEmptyInput() {
        assertEquals("Cocoa", TextUtil.prettyName("cocoa"));
        assertEquals("", TextUtil.prettyName(""));
        assertEquals("A B C", TextUtil.prettyName("A_B_C"));
    }

    @Test
    void prettyNameRendersTheSeedNamesPlayersSee() {
        assertEquals("Wheat Seeds", TextUtil.prettyName("WHEAT_SEEDS"));
        assertEquals("Cocoa Beans", TextUtil.prettyName("COCOA_BEANS"));
        assertEquals("Beetroot Seeds", TextUtil.prettyName("BEETROOT_SEEDS"));
    }
}
