package dev.replenishplusplus.update;

import dev.replenishplusplus.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    private static final String REPO = "Mitra-88/ReplenishPlusPlus";

    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final String API_URL     = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

    private final Plugin plugin;
    private final String  currentVersion;
    private final boolean enabled;

    private volatile String  latestVersion   = "Unknown";
    private volatile boolean updateAvailable = false;
    private volatile boolean checkCompleted  = false;

    private final List<Runnable> completionActions = new CopyOnWriteArrayList<>();

    public UpdateChecker(Plugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.currentVersion = normalize(plugin.getPluginMeta().getVersion());
    }

    public boolean isEnabled()         { return enabled; }
    public boolean isCheckPending()    { return !checkCompleted; }
    public boolean isUpdateAvailable() { return updateAvailable; }

    public void onCheckCompleted(Runnable action) {
        if (checkCompleted) {
            action.run();
            return;
        }
        completionActions.add(action);
    }
    public boolean isLocalNewer() {
        return checkCompleted && compareVersions(currentVersion, latestVersion) > 0;
    }
    public String getCurrentVersion()  { return currentVersion; }
    public String getLatestVersion()   { return latestVersion; }

    public void check() {
        if (!enabled) return;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(this::handleResponse)
                .exceptionally(this::handleError);
    }

    private void handleResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            parseLatestVersion(response.body());
            return;
        }
        String reason = switch (status) {
            case 403 -> "GitHub API rate-limited.";
            case 404 -> "No releases found on GitHub.";
            default  -> "HTTP " + status;
        };
        console("<red>Update check failed: " + reason);
    }

    private void parseLatestVersion(String body) {
        Matcher matcher = TAG_PATTERN.matcher(body);
        if (!matcher.find()) {
            console("<red>Update check failed: Malformed GitHub response.");
            return;
        }
        latestVersion   = normalize(matcher.group(1));
        updateAvailable = compareVersions(currentVersion, latestVersion) < 0;
        checkCompleted  = true;
        logResult();
        fireCompletionActions();
    }

    private void fireCompletionActions() {
        List<Runnable> actions = List.copyOf(completionActions);
        completionActions.clear();
        for (Runnable action : actions) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, action);
            }
        }
    }

    private Void handleError(Throwable error) {
        console("<red>Update check failed: <gray>Could not reach GitHub. <dark_gray>(<gray>"
                + error.getClass().getSimpleName() + "<dark_gray>)");
        return null;
    }

    private void logResult() {
        int comparison = compareVersions(currentVersion, latestVersion);
        if (comparison < 0) {
            console("<gray>Update available: <yellow>" + latestVersion
                    + " <gray>(you're on <white>" + currentVersion + "<gray>).");
            console("<gray>Download: <aqua>" + RELEASES_URL);
        } else if (comparison > 0) {
            console("<gray>Update Status: <light_purple>Running unreleased/dev build "
                    + "<dark_gray>(<white>" + currentVersion + "<dark_gray>)");
        } else {
            console("<gray>Update Status: <green>Up to date "
                    + "<dark_gray>(<white>" + currentVersion + "<dark_gray>)");
        }
    }

    private void console(String message) {
        Bukkit.getConsoleSender().sendMessage(Messages.MINI_MESSAGE.deserialize(Messages.prefixed(message)));
    }

    private static String normalize(String version) {
        if (version == null) return "";
        String v = version.trim();
        while (!v.isEmpty() && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) {
            v = v.substring(1);
        }
        return v.split("[-+]", 2)[0];
    }

    private static int compareVersions(String left, String right) {
        if (left.equals(right)) return 0;

        String[] leftParts  = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);

        for (int i = 0; i < length; i++) {
            int leftValue  = i < leftParts.length  ? parseNumeric(leftParts[i])  : 0;
            int rightValue = i < rightParts.length ? parseNumeric(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static int parseNumeric(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
