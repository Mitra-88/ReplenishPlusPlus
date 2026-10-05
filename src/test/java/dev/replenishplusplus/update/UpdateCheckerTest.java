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
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RELEASE, 0),
                UpdateChecker.parseVersion("1.0.0-26.3"));
        assertEquals(new UpdateChecker.Version(1, 0, 0, UpdateChecker.Channel.RELEASE, 0),
                UpdateChecker.parseVersion("1.0.0-26.3.2"));
        assertEquals(new UpdateChecker.Version(7, 0, 0, UpdateChecker.Channel.RELEASE, 0),
                UpdateChecker.parseVersion("v7.0.0-26.3"));
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
    void extractModrinthVersionsPicksTheNewestReleaseAndPreRelease() {
        String body = "[{\"name\":\"One\",\"version_number\":\"1.0.0-26.3\",\"version_type\":\"release\",\"game_versions\":[\"26.3\"]},"
                + "{\"name\":\"Two\",\"version_number\":\"v2.0.0-beta.1+26.3\",\"version_type\":\"beta\",\"game_versions\":[\"26.3\"]},"
                + "{\"name\":\"Three\",\"version_number\":\"not-a-version\",\"version_type\":\"release\"}]";
        UpdateChecker.ModrinthVersions found = UpdateChecker.extractModrinthVersions(body);
        assertEquals("1.0.0-26.3", found.release());
        assertEquals("v2.0.0-beta.1+26.3", found.preRelease());
    }

    @Test
    void extractModrinthVersionsReadsTheLiveModrinthShape() {
        String body = "[{\"name\":\"Replenish++ 7.0.0 - 26.3\",\"version_number\":\"7.0.0-26.3\","
                + "\"game_versions\":[\"26.3\"],\"loaders\":[\"paper\",\"purpur\"],\"version_type\":\"release\"}]";
        UpdateChecker.ModrinthVersions found = UpdateChecker.extractModrinthVersions(body);
        assertEquals("7.0.0-26.3", found.release());
        assertNull(found.preRelease());
    }

    @Test
    void aNewerBetaNeverWinsTheReleaseSlot() {
        String body = "[{\"version_number\":\"7.1.0-beta.1\",\"version_type\":\"beta\"},"
                + "{\"version_number\":\"7.0.1-26.3\",\"version_type\":\"release\"}]";
        UpdateChecker.ModrinthVersions found = UpdateChecker.extractModrinthVersions(body);
        assertEquals("7.0.1-26.3", found.release());
        assertEquals("7.1.0-beta.1", found.preRelease());
    }

    @Test
    void extractModrinthVersionsIgnoresVersionNumberTextInsideOtherFields() {
        String body = "[{\"name\":\"fake \\\"version_number\\\":\\\"9.9.9\\\" here\","
                + "\"changelog\":\"\\\"version_number\\\":\\\"8.8.8\\\"\","
                + "\"version_number\":\"1.0.0-26.3\",\"version_type\":\"release\"}]";
        UpdateChecker.ModrinthVersions found = UpdateChecker.extractModrinthVersions(body);
        assertEquals("1.0.0-26.3", found.release());
        assertNull(found.preRelease());
    }

    @Test
    void extractorsReturnNullOnMalformedJson() {
        assertNull(UpdateChecker.extractModrinthVersions("not json at all").release());
        assertNull(UpdateChecker.extractModrinthVersions("not json at all").preRelease());
        assertNull(UpdateChecker.extractTagName("{{{"));
        assertNull(UpdateChecker.extractTagName("[\"an array\"]"));
    }

    @Test
    void extractModrinthVersionsYieldNullWithoutUsableVersions() {
        assertNull(UpdateChecker.extractModrinthVersions("[]").release());
        assertNull(UpdateChecker.extractModrinthVersions("[{\"version_number\":\"oops\"}]").release());
        assertNull(UpdateChecker.extractModrinthVersions("").release());
    }

    @Test
    void extractModrinthVersionsSkipUnsafeVersionNumbers() {
        String body = "[{\"version_number\":\"7.0.0'><click:open_url:'https://evil'>x\",\"version_type\":\"release\"},"
                + "{\"version_number\":\"1.1.0-26.3\",\"version_type\":\"release\"}]";
        assertEquals("1.1.0-26.3", UpdateChecker.extractModrinthVersions(body).release());
    }

    @Test
    void extractModrinthVersionsYieldNullWhenOnlyCandidateIsUnsafe() {
        assertNull(UpdateChecker.extractModrinthVersions(
                "[{\"version_number\":\"7.0.0'><click:open_url:'https://evil'>x\",\"version_type\":\"release\"}]").release());
    }

    @Test
    void stripBuildMetadataDropsThePlusSuffix() {
        assertEquals("7.0.0", UpdateChecker.stripBuildMetadata("7.0.0+build.240"));
        assertEquals("7.0.0-rc1", UpdateChecker.stripBuildMetadata("7.0.0-rc1+build.240"));
        assertEquals("v7.0.0", UpdateChecker.stripBuildMetadata("v7.0.0+26.3"));
        assertEquals("7.0.0", UpdateChecker.stripBuildMetadata("7.0.0"));
        assertEquals("", UpdateChecker.stripBuildMetadata(""));
    }

    @Test
    void gameVersionsFilterMatchesTheExactVersionAndItsLine() {
        assertEquals("[\"26.3\"]", UpdateChecker.gameVersionsFilter("26.3"));
        assertEquals("[\"26.3.2\",\"26.3\"]", UpdateChecker.gameVersionsFilter("26.3.2"));
        assertEquals("[\"1.21.11\",\"1.21\"]", UpdateChecker.gameVersionsFilter("1.21.11"));
        assertEquals("[\"weird\"]", UpdateChecker.gameVersionsFilter("weird"));
    }

    @Test
    void displayVersionStripsTheLeadingV() {
        assertEquals("1.2.3", UpdateChecker.displayVersion("v1.2.3"));
        assertEquals("1.2.3", UpdateChecker.displayVersion("V1.2.3"));
        assertEquals("1.2.3", UpdateChecker.displayVersion("1.2.3"));
        assertEquals("", UpdateChecker.displayVersion(null));
    }
}
