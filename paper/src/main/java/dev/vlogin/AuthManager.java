package dev.vlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class AuthManager {

    public record PendingCode(String name, String display, long expires) {}

    private final VLogin plugin;
    private final Database db;
    private final WebhookLogger hook;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> fails = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final HttpClient http = HttpClient.newHttpClient();
    private DialogUI ui;

    public AuthManager(VLogin plugin, Database db, WebhookLogger hook) {
        this.plugin = plugin; this.db = db; this.hook = hook;
    }

    void setUi(DialogUI ui) { this.ui = ui; }

    public boolean isAuthed(Player p) { return authed.contains(p.getUniqueId()); }

    public void markAuthed(Player p) {
        authed.add(p.getUniqueId());
        fails.remove(p.getUniqueId());
        if (plugin.velocity()) plugin.proxy().sendAuth(p, true); // lets the proxy release the login gate
    }

    public void forget(UUID id) { authed.remove(id); fails.remove(id); }

    public static String key(Player p) { return p.getName().toLowerCase(); }
    public static String ip(Player p) {
        return p.getAddress() == null ? "unknown" : p.getAddress().getAddress().getHostAddress();
    }
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Random hash nobody knows the password of, for accounts created automatically (Bedrock, online-mode). */
    public String randomHash() { return BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt(4)); }

    // ---------- who is trusted without a password ----------

    /** Bedrock player authenticated by Floodgate/Geyser (Xbox account). */
    public boolean isBedrock(Player p) {
        UUID id = p.getUniqueId();
        boolean floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");
        if (!floodgate) {
            // Floodgate only on the proxy: its UUIDs (most significant bits = 0) reach us through modern forwarding
            return plugin.velocity() && id.getMostSignificantBits() == 0L;
        }
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, id);
        } catch (Throwable t) {
            return id.getMostSignificantBits() == 0L;
        }
    }

    /** Bedrock players, and everyone on a standalone online-mode server (Mojang already verified the name). */
    public boolean isTrustedIdentity(Player p) {
        if (plugin.getConfig().getBoolean("bedrock.auto-login", true) && isBedrock(p)) return true;
        return plugin.getConfig().getBoolean("premium.online-mode-auto-login", true) && Bukkit.getOnlineMode();
    }

    /** Trusted identity, premium (Mojang via Velocity), verified IP, then the normal IP session. May run off the main thread. */
    public boolean canAutoLogin(Database.Account a, String ip, Player p) {
        if (isTrustedIdentity(p)) return true;
        if (premiumVerified(a, p)) return true;
        boolean always = "ALWAYS".equalsIgnoreCase(plugin.getConfig().getString("verified.bypass", "IP"));
        if (a.verified() && always) return true;
        if (a.lastIp() == null || !a.lastIp().equals(ip)) return false;
        if (a.verified()) return true;
        long window = (long) (plugin.getConfig().getDouble("session.hours", 5) * 3_600_000L);
        return System.currentTimeMillis() - a.lastLogin() < window;
    }

    private boolean premiumVerified(Database.Account a, Player p) {
        // Behind Velocity: the proxy forced online mode, so the player has a real Mojang UUID (not the offline one).
        if (!plugin.velocity() || !a.premium()) return false;
        UUID id = p.getUniqueId();
        if (id.equals(offlineUuid(p.getName()))) return false;
        if (a.premiumUuid() == null) { db.setPremiumUuid(a.name(), id.toString()); return true; }
        return a.premiumUuid().equals(id.toString());
    }

    // ---------- register / login (called from dialogs or commands) ----------

    public void attemptRegister(Player p, String pw, String confirm) {
        int min = plugin.getConfig().getInt("password.min-length", 6);
        if (pw == null || pw.length() < min) { retry(p, true, "Password must be at least " + min + " characters."); return; }
        if (!pw.equals(confirm)) { retry(p, true, "Passwords do not match."); return; }

        String name = key(p), ip = ip(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = BCrypt.hashpw(pw, BCrypt.gensalt(10));
            boolean ok = db.create(name, hash, ip);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (!ok) { retry(p, false, "That name is already registered. Please log in."); return; }
                markAuthed(p);
                p.sendMessage(Component.text("Registered and logged in!", NamedTextColor.GREEN));
                hook.log("Register", "`" + p.getName() + "` registered from `" + ip + "`", 0x57F287);
            });
        });
    }

    public void attemptLogin(Player p, String pw) {
        String name = key(p), ip = ip(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account a = db.get(name);
            boolean ok = a != null && pw != null && BCrypt.checkpw(pw, a.hash());
            if (ok) db.setLogin(name, ip);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (a == null) { retry(p, true, "You are not registered."); return; }
                if (ok) {
                    markAuthed(p);
                    p.sendMessage(Component.text("Logged in!", NamedTextColor.GREEN));
                    hook.log("Login", "`" + p.getName() + "` logged in from `" + ip + "`", 0x5865F2);
                } else {
                    int n = fails.merge(p.getUniqueId(), 1, Integer::sum);
                    hook.log("Failed login", "`" + p.getName() + "` from `" + ip + "` (attempt " + n + ")", 0xED4245);
                    if (n >= plugin.getConfig().getInt("max-failed-attempts", 5)) {
                        p.kick(Component.text("Too many failed login attempts."));
                    } else {
                        retry(p, false, "Wrong password.");
                    }
                }
            });
        });
    }

    private void retry(Player p, boolean register, String msg) {
        p.sendMessage(Component.text(msg, NamedTextColor.RED));
        if (ui != null) ui.show(p, register, msg);
    }

    // ---------- /freemium (code generated in game, confirmed in Discord) ----------

    public void requestCode(Player p) {
        String name = key(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account a = db.get(name);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (a == null) { red(p, "Register first, then run /freemium."); return; }

                long now = System.currentTimeMillis();
                codes.values().removeIf(c -> c.name().equals(name) || c.expires() < now);
                String code;
                do { code = String.format("%07X", rng.nextInt(0x10000000)); } while (codes.containsKey(code));
                int mins = plugin.getConfig().getInt("code-expire-minutes", 10);
                codes.put(code, new PendingCode(name, p.getName(), now + mins * 60_000L));

                p.sendMessage(Component.text("Your verification code: ", NamedTextColor.GREEN)
                        .append(Component.text(code, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                                .clickEvent(ClickEvent.copyToClipboard(code))));
                p.sendMessage(Component.text("Run /freemium " + code + " in Discord within " + mins
                        + " minutes (click the code to copy).", NamedTextColor.GRAY));
                hook.log("Freemium code generated", "`" + p.getName() + "` requested a code in game", 0xFEE75C);
            });
        });
    }

    public PendingCode peekCode(String code) {
        PendingCode pc = codes.get(code.trim().toUpperCase());
        return (pc == null || pc.expires() < System.currentTimeMillis()) ? null : pc;
    }

    /** Discord "Yes, that's me" button. Off the main thread. Null = success, else an error text. */
    public String confirmLink(String code, String discordId, String discordName) {
        PendingCode pc = codes.remove(code.trim().toUpperCase());
        if (pc == null || pc.expires() < System.currentTimeMillis()) return "That code is invalid or expired. Run /freemium in Minecraft for a new one.";
        if (db.get(pc.name()) == null) return "That Minecraft account no longer exists.";
        if (db.isLinked(pc.name(), discordId)) return "That Minecraft account is already linked to your Discord.";
        int max = plugin.discordConfig().getInt("max-accounts-per-discord", 5);
        if (db.accountsOf(discordId).size() >= max) return "You already have " + max + " accounts linked. Unlink one with /unfreemium first.";
        db.addLink(pc.name(), discordId, discordName);

        hook.log("Freemium verified", "`" + pc.display() + "` linked to Discord `" + discordName + "` (`" + discordId + "`)", 0x57F287);
        boolean always = "ALWAYS".equalsIgnoreCase(plugin.getConfig().getString("verified.bypass", "IP"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player on = Bukkit.getPlayerExact(pc.display());
            if (on != null) on.sendMessage(Component.text("Verified! Linked to Discord user " + discordName + ". "
                    + (always ? "You no longer need to log in." : "You no longer need to log in from this IP."), NamedTextColor.GREEN));
        });
        return null;
    }

    // ---------- /unfreemium [discord user] ----------

    public String label(Database.Link l) {
        return (l.discordName() == null || l.discordName().isBlank()) ? l.discordId() : l.discordName();
    }

    private String names(List<Database.Link> links) {
        return links.stream().map(this::label).collect(Collectors.joining(", "));
    }

    public void requestUnlink(Player p, String arg) {
        String name = key(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.Link> links = db.linksOf(name);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (links.isEmpty()) { red(p, "Your account is not linked to any Discord account."); return; }

                Database.Link target = null;
                if (arg == null) {
                    if (links.size() > 1) { red(p, "Several Discord users are linked: " + names(links) + ". Use /unfreemium <discord user>."); return; }
                    target = links.get(0);
                } else {
                    for (Database.Link l : links) {
                        if (l.discordId().equals(arg) || label(l).equalsIgnoreCase(arg)) { target = l; break; }
                    }
                    if (target == null) { red(p, "No linked Discord user matches \"" + arg + "\". Linked: " + names(links)); return; }
                }

                final Database.Link t = target;
                if (plugin.bot() == null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> unlink(name, t.discordId()));
                } else {
                    // that Discord user must confirm, so someone else on the account can't strip the link
                    plugin.bot().askUnlink(t.discordId(), p.getName(),
                            () -> red(p, "Could not DM " + label(t) + ". Ask them to run /unfreemium in Discord."));
                    p.sendMessage(Component.text("Check Discord: " + label(t) + " has to confirm the unlink in their DMs.", NamedTextColor.YELLOW));
                }
            });
        });
    }

    /** Removes one Discord link. Safe from any thread. */
    public void unlink(String name, String discordId) {
        db.removeLink(name, discordId);
        hook.log("Unfreemium", "`" + name + "` unlinked from Discord id `" + discordId + "`", 0xED4245);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player on = Bukkit.getPlayerExact(name);
            if (on != null) on.sendMessage(Component.text("A Discord link was removed from your account.", NamedTextColor.YELLOW));
        });
    }

    // ---------- /playersocial ----------

    public void requestSocial(Player viewer, String targetArg) {
        String targetName = targetArg == null ? viewer.getName() : targetArg;
        String key = targetName.toLowerCase();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account a = db.get(key);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                if (a == null) { red(viewer, "No account found for " + targetName + "."); return; }
                Player online = Bukkit.getPlayerExact(targetName);
                ui.showSocial(viewer, online != null ? online.getName() : targetName, a);
            });
        });
    }

    // ---------- premium login ----------

    public void requestPremium(Player p, boolean on) {
        String name = key(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String err = setPremium(name, on);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (err != null) { red(p, err.replace("`", "")); return; }
                p.sendMessage(Component.text(on
                        ? "Premium login enabled. Rejoin to verify your account with Mojang. If you cannot join afterwards, turn it off with /premium off in Discord."
                        : "Premium login disabled. You will use your password again.", NamedTextColor.GREEN));
            });
        });
    }

    /** Off the main thread. Returns an error text or null. */
    public String setPremium(String name, boolean on) {
        if (on) {
            if (!plugin.velocity() && !Bukkit.getOnlineMode()) {
                return "Premium login needs `velocity: true` with the VLogin Velocity plugin installed on your proxy.";
            }
            String err = checkMojang(name);
            if (err != null) return err;
        }
        db.setPremium(name, on);
        if (plugin.velocity()) plugin.proxy().syncPremiumAsync();
        hook.log(on ? "Premium enabled" : "Premium disabled", "`" + name + "`", 0xFEE75C);
        return null;
    }

    /** A name must exist as a paid Minecraft account, otherwise the proxy would lock the player out. */
    private String checkMojang(String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 200) return null;
            if (code == 204 || code == 404) return "That username is not a premium (paid) Minecraft account.";
            return "Mojang lookup failed (HTTP " + code + "). Try again later.";
        } catch (Exception e) {
            return "Could not reach Mojang. Try again later.";
        }
    }

    // ---------- Discord password change ----------

    /** Off the main thread. The caller has already checked the Discord user is linked to this account. */
    public void changePassword(String name, String newPw) {
        db.setHash(name, BCrypt.hashpw(newPw, BCrypt.gensalt(10)));
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) online.kick(Component.text("Your password was changed from Discord. Please rejoin."));
        });
        hook.log("Password changed (Discord)", "Account `" + name + "`", 0xFEE75C);
    }

    private static void red(Player p, String msg) { p.sendMessage(Component.text(msg, NamedTextColor.RED)); }
}
