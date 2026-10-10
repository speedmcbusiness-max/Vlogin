package dev.vlogin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Social platforms a player can attach to their account (from Discord) and show in /playersocial. */
public final class Social {

    private Social() {}

    public static final List<String> PLATFORMS = List.of("youtube", "tiktok", "instagram");

    public static String label(String platform) {
        return switch (platform) {
            case "youtube" -> "Youtube";
            case "tiktok" -> "Tiktok";
            case "instagram" -> "Instagram";
            default -> platform;
        };
    }

    private static List<String> domains(String platform) {
        return switch (platform) {
            case "youtube" -> List.of("youtube.com", "youtu.be");
            case "tiktok" -> List.of("tiktok.com");
            case "instagram" -> List.of("instagram.com");
            default -> List.of();
        };
    }

    /**
     * Returns a cleaned https URL, or null if it is not a link to that platform.
     * Only the platform's own domains are accepted, so nobody can put a phishing link in the dialog.
     * This does NOT prove the person owns the profile.
     */
    public static String normalize(String platform, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 200) return null;
        for (char ch : s.toCharArray()) if (ch <= 32 || ch == 127) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return null;
            if (u.getUserInfo() != null || u.getHost() == null) return null;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            boolean ok = domains(platform).stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
            if (!ok) return null;
            String path = u.getRawPath();
            if (path == null || path.length() <= 1) return null; // must point at a profile, not the homepage
            return "https://" + host + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
