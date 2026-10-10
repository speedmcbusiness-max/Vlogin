package dev.vlogin;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/** Sends embeds to a Discord webhook. Never pass passwords or codes in here. */
public final class WebhookLogger {

    private final VLogin plugin;
    private final HttpClient http = HttpClient.newHttpClient();

    public WebhookLogger(VLogin plugin) { this.plugin = plugin; }

    public void log(String title, String description, int color) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("webhook.enabled")) return;
        String url = cfg.getString("webhook.url", "");
        if (url.isBlank()) return;

        String json = "{\"username\":\"VLogin\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"title\":\"" + esc(title) + "\","
                + "\"description\":\"" + esc(description) + "\","
                + "\"color\":" + color + ","
                + "\"timestamp\":\"" + Instant.now() + "\"}]}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().warning("Webhook failed: " + e.getMessage());
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                default -> { if (ch < 0x20) b.append(' '); else b.append(ch); }
            }
        }
        return b.toString();
    }
}
