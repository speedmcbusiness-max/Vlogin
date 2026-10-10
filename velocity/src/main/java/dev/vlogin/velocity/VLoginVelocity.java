package dev.vlogin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VLogin for Velocity.
 *  - forces Mojang (online) login for names the Paper plugin marked as premium
 *  - login gate: until the Paper plugin reports "logged in", the player may only be on the auth server
 *  - /ping on the proxy
 * Passwords, Discord and the database all live in the Paper plugin on the auth server.
 */
@Plugin(id = "vlogin", name = "VLogin", version = "1.0.0",
        description = "Login gate and premium login for VLogin", authors = {"VLogin"})
public final class VLoginVelocity {

    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("vlogin", "main");

    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.create("minecraft", "register");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dir;

    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private volatile Set<String> premium = new HashSet<>(); // lowercase usernames

    private String authServer = "lobby";
    private boolean gate = true;

    @Inject
    public VLoginVelocity(ProxyServer server, Logger logger, @DataDirectory Path dir) {
        this.server = server;
        this.logger = logger;
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dir);
            loadConfig();
            loadPremium();
        } catch (IOException ex) {
            logger.error("Could not read VLogin files: {}", ex.getMessage());
        }
        server.getChannelRegistrar().register(CHANNEL);

        CommandManager cm = server.getCommandManager();
        cm.register(cm.metaBuilder("ping").plugin(this).build(), new PingCommand());
        logger.info("VLogin loaded. auth-server={}, gate={}, premium names={}", authServer, gate, premium.size());
    }

    // ---------- premium: force Mojang login ----------

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent e) {
        if (premium.contains(e.getUsername().toLowerCase(Locale.ROOT))) {
            e.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
        }
    }

    // ---------- login gate ----------

    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent e) {
        if (!gate) return;
        server.getServer(authServer).ifPresent(e::setInitialServer);
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent e) {
        if (!gate || authed.contains(e.getPlayer().getUniqueId())) return;
        String target = e.getOriginalServer().getServerInfo().getName();
        if (!target.equalsIgnoreCase(authServer)) {
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
            e.getPlayer().sendMessage(Component.text("Log in first.", NamedTextColor.RED));
        }
    }

    /**
     * Paper only sends plugin messages on channels the connection has registered ("minecraft:register").
     * Vanilla clients never register ours, so the proxy does it for every backend connection.
     * Without this the backend can never tell us a player logged in and the gate never opens.
     */
    @Subscribe
    public void onPostConnect(ServerPostConnectEvent e) {
        e.getPlayer().getCurrentServer().ifPresent(conn ->
                conn.sendPluginMessage(REGISTER, CHANNEL.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent e) {
        authed.remove(e.getPlayer().getUniqueId());
    }

    // ---------- messages from the Paper plugin ----------

    @Subscribe
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getIdentifier().equals(CHANNEL)) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled()); // never forward to clients or other servers
        if (!(e.getSource() instanceof ServerConnection conn)) return; // ignore anything a client tries to send

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            switch (in.readUTF()) {
                case "AUTH" -> {
                    UUID id = conn.getPlayer().getUniqueId();
                    boolean ok = in.readBoolean();
                    if (ok) authed.add(id); else authed.remove(id);
                    logger.info("{} is {} (reported by {})", conn.getPlayer().getUsername(),
                            ok ? "logged in, gate released" : "logged out", conn.getServerInfo().getName());
                }
                case "PREMIUM" -> {
                    Set<String> names = new HashSet<>();
                    for (String n : in.readUTF().split(",")) {
                        if (!n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
                    }
                    premium = names;
                    savePremium();
                }
                default -> {}
            }
        } catch (IOException ex) {
            logger.warn("Bad VLogin message: {}", ex.getMessage());
        }
    }

    // ---------- /ping ----------

    private final class PingCommand implements SimpleCommand {
        @Override
        public void execute(Invocation inv) {
            CommandSource src = inv.source();
            String[] args = inv.arguments();
            Player target;
            if (args.length > 0) {
                if (!src.hasPermission("vlogin.ping.others")) {
                    src.sendMessage(Component.text("You can't check other players' ping.", NamedTextColor.RED));
                    return;
                }
                target = server.getPlayer(args[0]).orElse(null);
                if (target == null) {
                    src.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return;
                }
            } else if (src instanceof Player self) {
                target = self;
            } else {
                src.sendMessage(Component.text("Usage: ping <player>", NamedTextColor.RED));
                return;
            }
            long ping = target.getPing();
            src.sendMessage(Component.text(target.getUsername() + "'s ping: "
                    + (ping < 0 ? "unknown" : ping + " ms"), NamedTextColor.GREEN));
        }
    }

    // ---------- files ----------

    private void loadConfig() throws IOException {
        Path f = dir.resolve("config.properties");
        if (!Files.exists(f)) {
            Files.writeString(f, """
                    # VLogin Velocity
                    # Name (from velocity.toml [servers]) of the server that runs the VLogin Paper plugin.
                    auth-server=lobby
                    # Block players who are not logged in from joining any other server.
                    gate=true
                    """);
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(f)) { p.load(in); }
        authServer = p.getProperty("auth-server", "lobby").trim();
        gate = Boolean.parseBoolean(p.getProperty("gate", "true").trim());
    }

    private void loadPremium() throws IOException {
        Path f = dir.resolve("premium.txt");
        Set<String> names = new HashSet<>();
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f)) {
                if (!line.isBlank()) names.add(line.trim().toLowerCase(Locale.ROOT));
            }
        }
        premium = names;
    }

    private void savePremium() {
        try {
            Files.write(dir.resolve("premium.txt"), List.copyOf(premium));
        } catch (IOException ex) {
            logger.warn("Could not save premium.txt: {}", ex.getMessage());
        }
    }
}
