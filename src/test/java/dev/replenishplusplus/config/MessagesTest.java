package dev.replenishplusplus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessagesTest {

    @Test
    void everyCommandFacingKeyResolvesToNonBlankText() {
        for (String key : new String[] {
                "prefix",
                "menu.main",
                "menu.help",
                "harvest.wrong-tool",
                "harvest.need-seed",
                "harvest.inventory-full",
                "toggle.on",
                "toggle.off",
                "toggle.global-on",
                "toggle.global-off",
                "toggle.global-not-saved",
                "pad.given",
                "pad.placed",
                "pad.picked-up",
                "pad.not-yours",
                "pad.no-destination",
                "pad.destination-set",
                "pad.destination-gone",
                "pad.renamed",
                "pad.name-cleared",
                "pad.name-prompt",
                "pad.limit",
                "pad.warped",
                "update.available"}) {
            String text = Messages.text(key);
            assertNotNull(text, key + " must have a built-in default, or /rpp crashes with NPE");
            assertFalse(text.isBlank(), key + " default must not be blank");
        }
    }
}
