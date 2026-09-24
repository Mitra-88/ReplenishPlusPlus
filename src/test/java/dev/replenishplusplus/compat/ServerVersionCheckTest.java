package dev.replenishplusplus.compat;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionCheckTest {

    @Test
    void parsesPlainVersions() {
        assertArrayEquals(new int[] {26, 3}, ServerVersionCheck.parse("26.3"));
        assertArrayEquals(new int[] {1, 21, 4}, ServerVersionCheck.parse(" 1.21.4 "));
    }

    @Test
    void blankAndNonNumericVersionsYieldNull() {
        assertNull(ServerVersionCheck.parse(null));
        assertNull(ServerVersionCheck.parse(""));
        assertNull(ServerVersionCheck.parse("26.4-pre"));
    }

    @Test
    void lineDiffersDetectsMismatchInEitherDirection() {
        assertTrue(ServerVersionCheck.lineDiffers(new int[] {26, 3}, new int[] {26, 2}));
        assertTrue(ServerVersionCheck.lineDiffers(new int[] {26, 2}, new int[] {26, 3}));
        assertFalse(ServerVersionCheck.lineDiffers(new int[] {26, 3}, new int[] {26, 3}));
        assertFalse(ServerVersionCheck.lineDiffers(new int[] {26, 3}, new int[] {26, 3, 0}));
        assertFalse(ServerVersionCheck.lineDiffers(new int[] {26, 3}, new int[] {26, 3, 2}));
        assertTrue(ServerVersionCheck.lineDiffers(new int[] {26, 3}, new int[] {26, 4}));
    }

    @Test
    void declaredApiVersionWarnsOnEveryOtherMcLine() {
        int[] plugin = ServerVersionCheck.parse(readDeclaredApiVersion());
        int major = plugin[0];
        int minor = plugin[1];
        for (int m = 1; m <= minor + 1; m++) {
            if (m == minor) continue;
            String line = major + "." + m;
            assertTrue(ServerVersionCheck.lineDiffers(plugin, ServerVersionCheck.parse(line)), line);
            assertTrue(ServerVersionCheck.lineDiffers(plugin, ServerVersionCheck.parse(line + ".2")), line + ".2");
        }
        String own = major + "." + minor;
        assertFalse(ServerVersionCheck.lineDiffers(plugin, ServerVersionCheck.parse(own)), own);
        assertFalse(ServerVersionCheck.lineDiffers(plugin, ServerVersionCheck.parse(own + ".0")), own + ".0");
        assertFalse(ServerVersionCheck.lineDiffers(plugin, ServerVersionCheck.parse(own + ".2")), own + ".2");
    }

    private static String readDeclaredApiVersion() {
        InputStream in = ServerVersionCheckTest.class.getResourceAsStream("/paper-plugin.yml");
        if (in == null) throw new AssertionError("bundled paper-plugin.yml not on the test classpath");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.startsWith("api-version:")) {
                    return trimmed.substring("api-version:".length()).trim().replace("\"", "").replace("'", "");
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read bundled paper-plugin.yml", e);
        }
        throw new AssertionError("paper-plugin.yml declares no api-version");
    }

    @Test
    void renderTrimsTrailingZeros() {
        assertEquals("26.3", ServerVersionCheck.render(new int[] {26, 3, 0}));
        assertEquals("26.3", ServerVersionCheck.render(new int[] {26, 3}));
        assertEquals("1.21.4", ServerVersionCheck.render(new int[] {1, 21, 4}));
    }
}
