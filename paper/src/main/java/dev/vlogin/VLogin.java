package dev.vlogin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class VLogin extends JavaPlugin {

    private Database db;
    private AuthManager auth;
    private DialogUI ui;
    private WebhookLogger webhook;
    private DiscordBot bot;
    private ProxyLink proxy;
    private FileConfiguration discordCfg;
    private boolean velocity;

    public DiscordBot bot() { return bot; }
    public ProxyLink proxy() { return proxy; }
    public boolean velocity() { return velocity; }
    public FileConfiguration discordConfig() { return discordCfg; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            db = new Database(new File(getDataFolder(), "vlogin.db"));
        } catch (Exception e) {
            getLogger().severe("Could not open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        velocity = getConfig().getBoolean("velocity", false);
        if (velocity) {
            proxy = new ProxyLink(this, db);
            getLogger().info("Velocity mode ON (needs the VLogin Velocity plugin on the proxy).");
        }

        webhook = new WebhookLogger(this);
        auth = new AuthManager(this, db, webhook);
        ui = new DialogUI(auth);

        getServer().getPluginManager().registerEvents(new AuthListener(this, auth, ui, db, webhook), this);

        PlayerCommands cmds = new PlayerCommands(auth);
        for (String name : new String[]{"login", "register", "freemium", "unfreemium", "premium", "unpremium", "ping", "playersocial"}) {
            getCommand(name).setExecutor(cmds);
        }

        File discordFile = new File(getDataFolder(), "discord.yml");
        if (!discordFile.exists()) saveResource("discord.yml", false);
        discordCfg = YamlConfiguration.loadConfiguration(discordFile);
        String token = discordCfg.getString("token", "").trim();
        if (discordCfg.getBoolean("enabled", true)) {
            if (token.isEmpty() || token.equals("PUT_BOT_TOKEN_HERE")) {
                getLogger().warning("Discord bot not started: put your bot token in plugins/VLogin/discord.yml");
            } else {
                bot = new DiscordBot(this, auth, db, webhook);
                bot.start(token);
            }
        }
        webhook.log("VLogin started", "Server is online with VLogin enabled" + (velocity ? " (Velocity mode)." : "."), 0x57F287);
    }

    @Override
    public void onDisable() {
        if (bot != null) bot.shutdown();
        if (db != null) db.close();
    }
}
