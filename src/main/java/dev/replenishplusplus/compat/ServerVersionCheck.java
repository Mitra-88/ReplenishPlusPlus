package dev.replenishplusplus.compat;

public final class ServerVersionCheck {

    private ServerVersionCheck() {}

    public static int[] parse(String version) {
        if (version == null || version.isBlank()) return null;
        String[] parts = version.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    public static boolean lineDiffers(int[] declared, int[] server) {
        for (int i = 0; i < 2; i++) {
            int x = i < declared.length ? declared[i] : 0;
            int y = i < server.length ? server[i] : 0;
            if (x != y) return true;
        }
        return false;
    }

    public static String render(int[] parts) {
        int end = parts.length;
        while (end > 2 && parts[end - 1] == 0) end--;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) out.append('.');
            out.append(parts[i]);
        }
        return out.toString();
    }
}
