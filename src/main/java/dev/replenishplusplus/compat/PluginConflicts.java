package dev.replenishplusplus.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class PluginConflicts {

    public static final Set<String> KNOWN = Set.of();

    private PluginConflicts() {}

    public static List<String> scan(Collection<String> known, Collection<String> installed) {
        List<String> matches = new ArrayList<>();
        for (String candidate : known) {
            for (String installedName : installed) {
                if (candidate.equalsIgnoreCase(installedName)) {
                    matches.add(installedName);
                    break;
                }
            }
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return matches;
    }
}
