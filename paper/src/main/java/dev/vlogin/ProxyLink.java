package dev.vlogin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Talks to the VLogin Velocity plugin over the "vlogin:main" plugin channel (only used when velocity: true). */
public final class ProxyLink {

    public static final String CHANNEL = "vlogin:main";

    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private final VLogin plugin;
    private final Database db;

    public ProxyLink(VLogin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    /** Tells the proxy this player is (or is no longer) logged in. Main thread. */
    public void sendAuth(Player p, boolean authed) {
        send(p, out -> { out.writeUTF("AUTH"); out.writeBoolean(authed); });
    }

    /** Sends the list of premium usernames to the proxy so it can force Mojang login for them. */
    public void syncPremiumAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String joined = String.join(",", db.premiumNames());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                if (carrier == null) return; // plugin messages need a connected player; synced again on next join
                send(carrier, out -> { out.writeUTF("PREMIUM"); out.writeUTF(joined); });
            });
        });
    }

    private void send(Player p, Writer w) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            w.write(new DataOutputStream(bytes));
            deliver(p, bytes.toByteArray(), 0);
        } catch (IOException ignored) {}
    }

    /**
     * Paper silently drops plugin messages on a channel the player's connection has not registered.
     * The VLogin Velocity plugin registers "vlogin:main" for the player right after the server connection
     * is made, so wait for that (up to ~10 seconds) instead of sending into the void.
     */
    private void deliver(Player p, byte[] data, int attempt) {
        if (!p.isOnline()) return;
        if (p.getListeningPluginChannels().contains(CHANNEL)) {
            p.sendPluginMessage(plugin, CHANNEL, data);
            return;
        }
        if (attempt >= 40) {
            plugin.getLogger().warning("The proxy never registered the '" + CHANNEL + "' channel for " + p.getName()
                    + ". Is the VLogin Velocity plugin installed on the proxy?");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> deliver(p, data, attempt + 1), 5L);
    }
}
