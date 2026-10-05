package dev.replenishplusplus.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.replenishplusplus.compat.ServerVersionCheck;
import dev.replenishplusplus.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    private static final String REPO = "Mitra-88/ReplenishPlusPlus";

    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final String API_URL     = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final String MODRINTH_SLUG = "replenishplusplus";
    private static final String MODRINTH_PAGE = "https://modrinth.com/plugin/" + MODRINTH_SLUG;
    private static final String MODRINTH_API  = "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta)\\.(\\d+)|-rc(\\d+))?(?:-mc(\\d+)\\.(\\d+)-paper|-(\\d+)\\.(\\d+)(?:\\.(\\d+))?)?$");

    private static final Pattern SAFE_VERSION_CHARS = Pattern.compile("[A-Za-z0-9._~+\\-]+");

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

    private final Plugin plugin;
    private final boolean enabled;
    private final String  currentVersion;
    private final Version current;

    private volatile String  latestVersion   = "Unknown";
    private volatile Version latest;
    private volatile String  versionSlug;
    private volatile String  latestPreRelease;
    private volatile String  preReleaseSlug;
    private volatile boolean updateAvailable = false;
    private volatile boolean checkCompleted  = false;
    private volatile boolean checkFailed     = false;
    private volatile Source  updateSource    = Source.GITHUB;

    private final List<Runnable> completionActions = new ArrayList<>();

    private enum Source { MODRINTH, GITHUB }

    public UpdateChecker(Plugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        String raw = plugin.getPluginMeta().getVersion();
        this.currentVersion = displayVersion(raw);
        this.current = parseVersion(raw);
    }

    public boolean isEnabled()         { return enabled; }
    public boolean isCheckPending()    { return !checkCompleted; }
    public boolean isCheckFailed()     { return checkFailed; }
    public boolean isUpdateAvailable() { return updateAvailable; }
    public boolean isPreReleaseAvailable() { return latestPreRelease != null; }
    public String getLatestPreRelease()    { return latestPreRelease; }

    public synchronized void onCheckCompleted(Runnable action) {
        if (checkCompleted) {
            if (!checkFailed) action.run();
            return;
        }
        completionActions.add(action);
    }
    public boolean isLocalNewer() {
        return checkCompleted && !checkFailed && current != null && latest != null && compare(current, latest) > 0;
    }
    public String getCurrentVersion()  { return currentVersion; }
    public String getLatestVersion()   { return latestVersion; }

    public String downloadLink() {
        return "<aqua><click:open_url:'" + pageUrl() + "'><hover:show_text:'<gray>Click to open download page'>"
                + "<u>" + pageLabel() + "</u></click>";
    }

    private String pageUrl() {
        return updateSource == Source.MODRINTH && versionSlug != null
                ? MODRINTH_PAGE + "/version/" + versionSlug
                : RELEASES_URL;
    }

    private String pageLabel() {
        return updateSource == Source.MODRINTH ? "modrinth.com/plugin/" + MODRINTH_SLUG : "github.com/" + REPO;
    }

    public String preReleaseDownloadLink() {
        return "<aqua><click:open_url:'" + MODRINTH_PAGE + "/version/" + preReleaseSlug + "'><hover:show_text:'<gray>Click to open the pre-release page'>"
                + "<u>modrinth.com/plugin/" + MODRINTH_SLUG + "</u></click>";
    }

    public void check() {
        if (!enabled) return;
        checkModrinth();
    }

    private void checkModrinth() {
        console("<gradient:#FFD700:#FF9D00>Update check</gradient> <dark_gray>· <gray>using <green>Modrinth</green>");
        String gameVersions = gameVersionsFilter(Bukkit.getMinecraftVersion());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODRINTH_API
                        + "?game_versions=" + URLEncoder.encode(gameVersions, StandardCharsets.UTF_8)
                        + "&include_changelog=false"))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", "Mitra-88/ReplenishPlusPlus/" + currentVersion
                        + " (+https://github.com/" + REPO + ")")
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(this::handleModrinthResponse)
                .exceptionally(error -> {
                    fallbackToGitHub();
                    return null;
                });
    }

    private void handleModrinthResponse(HttpResponse<InputStream> response) {
        String body;
        try {
            body = boundedBody(response);
        } catch (IOException tooLargeOrBroken) {
            fallbackToGitHub();
            return;
        }
        if (response.statusCode() != 200) {
            fallbackToGitHub();
            return;
        }
        ModrinthVersions found = extractModrinthVersions(body);
        if (found.release() == null) {
            fallbackToGitHub();
            return;
        }
        Version parsed = parseVersion(stripBuildMetadata(found.release()));
        if (parsed == null) {
            fallbackToGitHub();
            return;
        }
        if (found.preRelease() != null) {
            Version preRelease = parseVersion(stripBuildMetadata(found.preRelease()));
            if (preRelease != null && compare(preRelease, parsed) > 0) {
                latestPreRelease = displayVersion(found.preRelease());
                preReleaseSlug = found.preRelease();
            }
        }
        updateSource = Source.MODRINTH;
        completeCheck(parsed, found.release());
    }

    private void fallbackToGitHub() {
        if (checkCompleted) return;
        console("<gray>Modrinth check failed, falling back to <white>GitHub</white>.");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(this::handleResponse)
                .exceptionally(this::handleError);
    }

    private void handleResponse(HttpResponse<InputStream> response) {
        String body;
        try {
            body = boundedBody(response);
        } catch (IOException tooLargeOrBroken) {
            failCheck();
            console("<red>Update check failed: <gray>" + (tooLargeOrBroken instanceof HttpTimeoutException
                    ? "Timed out waiting for the response."
                    : "Could not read the response <dark_gray>(<gray>" + tooLargeOrBroken.getClass().getSimpleName() + "<dark_gray>)."));
            return;
        }
        int status = response.statusCode();
        if (status == 200) {
            parseLatestVersion(body);
            return;
        }
        String reason = switch (status) {
            case 403 -> "GitHub API rate-limited.";
            case 404 -> "No releases found on GitHub.";
            default  -> "HTTP " + status;
        };
        failCheck();
        console("<red>Update check failed: " + reason);
    }

    private void parseLatestVersion(String body) {
        String tag = extractTagName(body);
        if (tag == null) {
            failCheck();
            console("<red>Update check failed: Malformed GitHub response.");
            return;
        }
        Version parsed = parseVersion(tag);
        if (parsed == null) {
            failCheck();
            console("<red>Update check failed: Unsupported release tag format '<white>" + tag + "<red>'.");
            return;
        }
        updateSource = Source.GITHUB;
        completeCheck(parsed, tag);
    }

    private void completeCheck(Version parsed, String rawVersion) {
        latest          = parsed;
        latestVersion   = displayVersion(rawVersion);
        versionSlug     = rawVersion;
        updateAvailable = current != null && compare(current, parsed) < 0;
        checkCompleted  = true;
        logResult();
        fireCompletionActions();
    }

    private static String boundedBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw new IOException("Response body exceeds the " + MAX_BODY_BYTES + " byte cap");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void failCheck() {
        if (checkCompleted) return;
        checkFailed = true;
        checkCompleted = true;
    }

    private synchronized void fireCompletionActions() {
        if (completionActions.isEmpty()) return;
        List<Runnable> actions = new ArrayList<>(completionActions);
        completionActions.clear();
        for (Runnable action : actions) {
            if (plugin.isEnabled()) {
                try {
                    Bukkit.getScheduler().runTask(plugin, action);
                } catch (IllegalPluginAccessException disabled) {
                }
            }
        }
    }

    private Void handleError(Throwable error) {
        failCheck();
        console("<red>Update check failed: <gray>Could not reach GitHub. <dark_gray>(<gray>"
                + error.getClass().getSimpleName() + "<dark_gray>)");
        return null;
    }

    private void logResult() {
        if (current == null) {
            console("<gradient:#FFD700:#FF9D00>Update Status</gradient> <dark_gray>· <yellow>Local version <white>v" + currentVersion
                    + "<yellow> is not a parseable release tag, update status unknown");
            return;
        }
        int comparison = compare(current, latest);
        if (comparison < 0) {
            console("<gradient:#FFD700:#FF9D00>Update available</gradient> <dark_gray>· <gray>Replenish++ <gold>v"
                    + latestVersion + " <yellow>is out <dark_gray>· <gray>you're on <white>v" + currentVersion);
            console("<gray>Download: <aqua>" + pageUrl());
        } else if (comparison > 0) {
            console("<gradient:#FFD700:#FF9D00>Update Status</gradient> <dark_gray>· <light_purple>Running unreleased/dev build "
                    + "<dark_gray>(<white>" + currentVersion + "<dark_gray>)");
        } else {
            console("<gradient:#FFD700:#FF9D00>Update Status</gradient> <dark_gray>· <green>Up to date "
                    + "<dark_gray>(<white>" + currentVersion + "<dark_gray>)");
        }
    }

    private void console(String message) {
        Bukkit.getConsoleSender().sendMessage(Messages.prefixedRaw(message));
    }

    static Version parseVersion(String raw) {
        if (raw == null) return null;
        Matcher matcher = VERSION_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) return null;

        try {
            Channel channel;
            int preNumber = 0;
            if (matcher.group(4) != null) {
                channel = Channel.valueOf(matcher.group(4).toUpperCase(Locale.ROOT));
                preNumber = Integer.parseInt(matcher.group(5));
            } else if (matcher.group(6) != null) {
                channel = Channel.RC;
                preNumber = Integer.parseInt(matcher.group(6));
            } else {
                channel = Channel.RELEASE;
            }
            return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), channel, preNumber);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    static String extractTagName(String body) {
        JsonElement tag;
        try {
            tag = JsonParser.parseString(body).getAsJsonObject().get("tag_name");
        } catch (IllegalStateException | JsonParseException error) {
            return null;
        }
        return tag != null && tag.isJsonPrimitive() ? tag.getAsString() : null;
    }

    record ModrinthVersions(String release, String preRelease) {}

    static ModrinthVersions extractModrinthVersions(String body) {
        JsonArray versions;
        try {
            versions = JsonParser.parseString(body).getAsJsonArray();
        } catch (IllegalStateException | JsonParseException error) {
            return new ModrinthVersions(null, null);
        }
        String bestRelease = null;
        Version bestReleaseParsed = null;
        String bestPreRelease = null;
        Version bestPreReleaseParsed = null;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            JsonElement number = entry.get("version_number");
            if (number == null || !number.isJsonPrimitive()) continue;
            String raw = number.getAsString();
            if (!SAFE_VERSION_CHARS.matcher(raw).matches()) continue;
            Version parsed = parseVersion(stripBuildMetadata(raw));
            if (parsed == null) continue;
            JsonElement type = entry.get("version_type");
            boolean isRelease = type != null && type.isJsonPrimitive() && "release".equalsIgnoreCase(type.getAsString());
            if (isRelease) {
                if (bestReleaseParsed == null || compare(parsed, bestReleaseParsed) > 0) {
                    bestReleaseParsed = parsed;
                    bestRelease = raw;
                }
            } else if (bestPreReleaseParsed == null || compare(parsed, bestPreReleaseParsed) > 0) {
                bestPreReleaseParsed = parsed;
                bestPreRelease = raw;
            }
        }
        return new ModrinthVersions(bestRelease, bestPreRelease);
    }

    static String stripBuildMetadata(String raw) {
        int plus = raw.indexOf('+');
        return plus < 0 ? raw : raw.substring(0, plus);
    }

    static String gameVersionsFilter(String mcVersion) {
        int[] parts = ServerVersionCheck.parse(mcVersion);
        if (parts == null || parts.length < 3) return "[\"" + mcVersion + "\"]";
        return "[\"" + mcVersion + "\",\"" + parts[0] + "." + parts[1] + "\"]";
    }

    static int compare(Version left, Version right) {
        int result;
        if ((result = Integer.compare(left.major(), right.major())) != 0) return result;
        if ((result = Integer.compare(left.minor(), right.minor())) != 0) return result;
        if ((result = Integer.compare(left.patch(), right.patch())) != 0) return result;
        if ((result = Integer.compare(left.channel().ordinal(), right.channel().ordinal())) != 0) return result;
        return Integer.compare(left.preNumber(), right.preNumber());
    }

    static String displayVersion(String raw) {
        if (raw == null) return "";
        return raw.startsWith("v") || raw.startsWith("V") ? raw.substring(1) : raw;
    }

    enum Channel { ALPHA, BETA, RC, RELEASE }

    record Version(int major, int minor, int patch, Channel channel, int preNumber) {}
}
