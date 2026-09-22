package dev.replenishplusplus.update;

import dev.replenishplusplus.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta)\\.(\\d+)|-rc(\\d+))?(?:-mc(\\d+)\\.(\\d+)-paper)?$");

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
    private volatile boolean updateAvailable = false;
    private volatile boolean checkCompleted  = false;
    private volatile boolean checkFailed     = false;

    private final List<Runnable> completionActions = new ArrayList<>();

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

    public synchronized void onCheckCompleted(Runnable action) {
        if (checkCompleted) {
            action.run();
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
        return "<aqua><click:open_url:'" + RELEASES_URL + "'><hover:show_text:'<gray>Click to open release page'>"
                + "<u>github.com/" + REPO + "</u></click>";
    }

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
        latest          = parsed;
        latestVersion   = displayVersion(tag);
        updateAvailable = current != null && compare(current, parsed) < 0;
        checkCompleted  = true;
        logResult();
        fireCompletionActions();
    }

    private void failCheck() {
        checkFailed = true;
        checkCompleted = true;
    }

    private synchronized void fireCompletionActions() {
        if (completionActions.isEmpty()) return;
        List<Runnable> actions = new ArrayList<>(completionActions);
        completionActions.clear();
        for (Runnable action : actions) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, action);
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
        int comparison = current == null || latest == null ? 0 : compare(current, latest);
        if (comparison < 0) {
            console("<gray>Replenish++ <gold>v" + latestVersion
                    + " <yellow>is out! <gray>(you're on <white>v" + currentVersion + "<gray>)");
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
        Matcher matcher = TAG_PATTERN.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
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
