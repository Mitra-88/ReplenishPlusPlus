package dev.replenishplusplus.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiBuildCheck {

    public static final int COMPILED_API_BUILD = 38;
    private static final Pattern API_BUILD_IN_VERSION = Pattern.compile("\\.build\\.(\\d+)");

    private ApiBuildCheck() {}

    public static Integer runningApiBuild(String versionString) {
        Matcher matcher = API_BUILD_IN_VERSION.matcher(versionString == null ? "" : versionString);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    public static String newerApiWarning(String versionString, int compiledBuild) {
        Integer running = runningApiBuild(versionString);
        if (running == null || running <= compiledBuild) return null;
        return "<yellow>Heads up: your server is running Paper API build <white>" + running
                + "</white><yellow>, but Replenish++ was compiled for build <white>" + compiledBuild
                + "</white><yellow>. Paper usually ships a matching update within a few days and a fresh "
                + "Replenish++ build follows right after, so this fixes itself. Until then everything "
                + "should keep working as normal.";
    }
}
