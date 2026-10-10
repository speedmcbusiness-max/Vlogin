package dev.vlogin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

public final class AuthListener implements Listener {

    private final VLogin plugin;
    private final AuthManager auth;
    private final DialogUI ui;
    private final Database db;
    private final WebhookLogger hook;

    public AuthListener(VLogin plugin, AuthManager auth, DialogUI ui, Database db, WebhookLogger hook) {
        this.plugin = plugin; this.auth = auth; this.ui = ui; this.db = db; this.hook = hook;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        auth.forget(p.getUniqueId());
        if (plugin.velocity()) plugin.proxy().syncPremiumAsync();
        String name = AuthManager.key(p), ip = AuthManager.ip(p);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account existing = db.get(name);
            boolean trusted = auth.isTrustedIdentity(p);
            if (existing == null && trusted) {
                // Bedrock / online-mode player without an account yet: create one nobody can password-login to
                db.create(name, auth.randomHash(), ip);
                existing = db.get(name);
            }
            final Database.Account a = existing;
            boolean auto = a != null && auth.canAutoLogin(a, ip, p);

            if (a != null && plugin.bot() != null) {
                for (Database.Link l : db.linksOf(name)) {
                    plugin.bot().notifyJoin(l.discordId(), p.getName(), ip, auto);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (auto) {
                    auth.markAuthed(p);
                    p.sendMessage(Component.text("Automatically logged in.", NamedTextColor.GREEN));
                    String how = trusted ? "Bedrock/online-mode" : a.verified() ? "verified" : "session";
                    hook.log("Auto-login", "`" + p.getName() + "` from `" + ip + "` (" + how + ")", 0x5865F2);
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline() && !auth.isAuthed(p)) ui.show(p, a == null, null);
                    }, 10L);
                    long timeout = plugin.getConfig().getInt("login-timeout-seconds", 60) * 20L;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline() && !auth.isAuthed(p)) p.kick(Component.text("Login timed out."));
                    }, timeout);
                }
            });
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { auth.forget(e.getPlayer().getUniqueId()); }

    private boolean locked(Player p) { return !auth.isAuthed(p); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()
                || e.getTo().getY() > e.getFrom().getY()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String cmd = e.getMessage().toLowerCase().split(" ")[0];
        if (!cmd.equals("/login") && !cmd.equals("/register")) e.setCancelled(true);
    }

    @EventHandler public void onInteract(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onInteractEntity(PlayerInteractEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }
}
