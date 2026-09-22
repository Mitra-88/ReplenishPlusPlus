package dev.replenishplusplus.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void parseSupportsEveryDocumentedTagFormat() {
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RELEASE, 0),
                UpdateChecker.parseVersion("1.0.0"));
        assertEquals(UpdateChecker.parseVersion("1.0.0"), UpdateChecker.parseVersion("v1.0.0"));
        assertEquals(UpdateChecker.parseVersion("1.0.0"), UpdateChecker.parseVersion("V1.0.0"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RELEASE, 0),
                UpdateChecker.parseVersion("1.0.0-mc26.2-paper"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.ALPHA, 1),
                UpdateChecker.parseVersion("1.0.0-alpha.1-mc26.2-paper"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.BETA, 1),
                UpdateChecker.parseVersion("1.0.0-beta.1-mc26.2-paper"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RC, 1),
                UpdateChecker.parseVersion("1.0.0-rc1-mc26.2-paper"));
    }

    @Test
    void parseHandlesMultiDigitPreReleaseNumbers() {
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RC, 10),
                UpdateChecker.parseVersion("1.0.0-rc10"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.BETA, 12),
                UpdateChecker.parseVersion("1.0.0-beta.12-mc26.2-paper"));
        assertTrue(UpdateChecker.compare(
                UpdateChecker.parseVersion("1.0.0-rc10"), UpdateChecker.parseVersion("1.0.0-rc9")) > 0);
    }

    @Test
    void parseTrimsWhitespaceAndRejectsMalformedTags() {
        assertNotNull(UpdateChecker.parseVersion("  1.0.0  "));
        assertEquals(UpdateChecker.parseVersion("1.0.0"), UpdateChecker.parseVersion("  1.0.0  "));
        assertNull(UpdateChecker.parseVersion(""));
        assertNull(UpdateChecker.parseVersion("v"));
        assertNull(UpdateChecker.parseVersion("1.0.0-"));
        assertNull(UpdateChecker.parseVersion("1..0"));
        assertNotNull(UpdateChecker.parseVersion("2147483647.2147483647.2147483647"));
    }

    @Test
    void parseRejectsFormatsOutsideTheSupportedList() {
        assertNull(UpdateChecker.parseVersion("1.0.0+build.42"));
        assertNull(UpdateChecker.parseVersion("1.0.0-rc.1"));
        assertNull(UpdateChecker.parseVersion("1.0.0-alpha1"));
        assertNull(UpdateChecker.parseVersion("1.0"));
        assertNull(UpdateChecker.parseVersion("1.0.0-mc26.2"));
        assertNull(UpdateChecker.parseVersion("2147483648.0.0"));
        assertNull(UpdateChecker.parseVersion(null));
    }

    @Test
    void compareFollowsSemverPrecedence() {
        var release = UpdateChecker.parseVersion("1.0.0");
        var rc1 = UpdateChecker.parseVersion("1.0.0-rc1-mc26.2-paper");
        var rc2 = UpdateChecker.parseVersion("1.0.0-rc2-mc26.2-paper");
        var beta1 = UpdateChecker.parseVersion("1.0.0-beta.1-mc26.2-paper");
        var alpha1 = UpdateChecker.parseVersion("1.0.0-alpha.1-mc26.2-paper");
        assertTrue(UpdateChecker.compare(release, rc1) > 0);
        assertTrue(UpdateChecker.compare(rc2, rc1) > 0);
        assertTrue(UpdateChecker.compare(rc1, beta1) > 0);
        assertTrue(UpdateChecker.compare(beta1, alpha1) > 0);
        assertTrue(UpdateChecker.compare(alpha1, UpdateChecker.parseVersion("1.0.0-alpha.2")) < 0);
    }

    @Test
    void compareIsCoreFirstAndClassifierNeutral() {
        assertTrue(UpdateChecker.compare(
                UpdateChecker.parseVersion("2.0.0"), UpdateChecker.parseVersion("1.9.9")) > 0);
        assertEquals(0, UpdateChecker.compare(
                UpdateChecker.parseVersion("1.0.0"), UpdateChecker.parseVersion("1.0.0-mc26.2-paper")));
        assertEquals(0, UpdateChecker.compare(
                UpdateChecker.parseVersion("v1.0.0-mc26.2-paper"), UpdateChecker.parseVersion("1.0.0-mc27.0-paper")));
    }

    @Test
    void extractTagNameReadsTagFromGitHubJson() {
        assertEquals("v1.0.0",
                UpdateChecker.extractTagName("{\"url\":\"...\",\"tag_name\":\"v1.0.0\",\"prerelease\":false}"));
        assertEquals("1.2.3-rc1-mc26.3-paper",
                UpdateChecker.extractTagName("{\"name\":\"x\",\"tag_name\": \"1.2.3-rc1-mc26.3-paper\",\"draft\":false}"));
        assertEquals("2.0.0", UpdateChecker.extractTagName("{\"tag_name\" : \"2.0.0\"}"));
    }

    @Test
    void extractTagNameReturnsNullWithoutTag() {
        assertNull(UpdateChecker.extractTagName("{\"name\":\"no tag here\"}"));
        assertNull(UpdateChecker.extractTagName(""));
    }

    @Test
    void displayVersionStripsTheLeadingV() {
        assertEquals("1.2.3", UpdateChecker.displayVersion("v1.2.3"));
        assertEquals("1.2.3", UpdateChecker.displayVersion("V1.2.3"));
        assertEquals("1.2.3", UpdateChecker.displayVersion("1.2.3"));
        assertEquals("", UpdateChecker.displayVersion(null));
    }
}
