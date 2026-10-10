package dev.vlogin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DiscordBot extends ListenerAdapter {

    private static final String MODAL_PREFIX = "vlogin_pw:";

    private final VLogin plugin;
    private final AuthManager auth;
    private final Database db;
    private final WebhookLogger hook;
    private volatile JDA jda;

    public DiscordBot(VLogin plugin, AuthManager auth, Database db, WebhookLogger hook) {
        this.plugin = plugin; this.auth = auth; this.db = db; this.hook = hook;
    }

    /** Adds the optional "account" option (with autocomplete) used when a Discord user has several accounts. */
    private static SlashCommandData acct(SlashCommandData d) {
        return d.addOption(OptionType.STRING, "account", "Which of your linked Minecraft accounts (if you have several)", false, true);
    }

    public void start(String token) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // DIRECT_MESSAGES lets the bot read "+command" DMs (not a privileged intent)
                JDA j = JDABuilder.createLight(token, GatewayIntent.DIRECT_MESSAGES)
                        .addEventListeners(this).build().awaitReady();
                j.updateCommands().addCommands(
                        Commands.slash("freemium", "Link a Minecraft account using the code from the game")
                                .addOption(OptionType.STRING, "code", "The code you got from /freemium in Minecraft", true),
                        acct(Commands.slash("unfreemium", "Unlink one of your Minecraft accounts from this Discord account")),
                        acct(Commands.slash("password", "Change your Minecraft password")),
                        acct(Commands.slash("ping", "Check your in-game ping")),
                        acct(Commands.slash("account", "Show the status of your linked Minecraft account")),
                        acct(Commands.slash("kick", "Kick your account from the server and clear its saved IP")),
                        acct(Commands.slash("premium", "Turn premium (Mojang) login on or off")
                                .addOptions(new OptionData(OptionType.STRING, "mode", "on or off", true)
                                        .addChoice("on", "on").addChoice("off", "off"))),
                        acct(Commands.slash("mc", "Run an allowed command in game as your player")
                                .addOption(OptionType.STRING, "command", "For example: spawn", true)),
                        acct(Commands.slash("linkyoutube", "Attach your YouTube channel to your account")
                                .addOption(OptionType.STRING, "url", "Your YouTube link, or 'remove'", true)),
                        acct(Commands.slash("linktiktok", "Attach your TikTok profile to your account")
                                .addOption(OptionType.STRING, "url", "Your TikTok link, or 'remove'", true)),
                        acct(Commands.slash("linkinstagram", "Attach your Instagram profile to your account")
                                .addOption(OptionType.STRING, "url", "Your Instagram link, or 'remove'", true))
                ).queue();
                jda = j;
                plugin.getLogger().info("Discord bot connected as " + j.getSelfUser().getName());
            } catch (Exception e) {
                plugin.getLogger().severe("Discord bot failed to start (check the token in discord.yml): " + e.getMessage());
            }
        });
    }

    public void shutdown() { if (jda != null) jda.shutdown(); }

    private <T> CompletableFuture<T> onMain(Supplier<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { f.complete(task.get()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    // ---------- DMs from the bot ----------

    /** DM a linked Discord user that the account joined. Safe from any thread. */
    public void notifyJoin(String discordId, String player, String ip, boolean auto) {
        JDA j = jda;
        FileConfiguration cfg = plugin.discordConfig();
        if (j == null || !cfg.getBoolean("dm-on-join", true)) return;

        StringBuilder sb = new StringBuilder("`" + player + "` just joined the server");
        if (cfg.getBoolean("show-ip-in-dm", true)) sb.append(" from `").append(ip).append("`");
        sb.append(".\n").append(auto ? "They were logged in automatically." : "They still have to enter the password.");
        sb.append("\nIf this wasn't you, press the button below.");

        j.retrieveUserById(discordId)
                .flatMap(User::openPrivateChannel)
                .flatMap(ch -> ch.sendMessage(sb.toString())
                        .addActionRow(Button.danger("vl_kick:" + player.toLowerCase(), "Not me, kick")))
                .queue(null, err -> plugin.getLogger().fine("Could not DM " + discordId + ": " + err.getMessage()));
    }

    /** In-game /unfreemium: ask that Discord user to confirm. onFail runs on the main thread if the DM fails. */
    public void askUnlink(String discordId, String player, Runnable onFail) {
        JDA j = jda;
        if (j == null) { Bukkit.getScheduler().runTask(plugin, onFail); return; }
        j.retrieveUserById(discordId)
                .flatMap(User::openPrivateChannel)
                .flatMap(ch -> ch.sendMessage("`" + player + "` asked to unlink this Discord account (/unfreemium). Confirm?")
                        .addActionRow(Button.danger("vl_unlink:" + player.toLowerCase(), "Yes, unlink"),
                                Button.secondary("vl_no", "Cancel")))
                .queue(null, err -> Bukkit.getScheduler().runTask(plugin, onFail));
    }

    /** "+spawn" in a DM to the bot runs /spawn in game. */
    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        if (e.getAuthor().isBot() || e.isFromGuild()) return;
        String text = e.getMessage().getContentRaw().trim();
        if (!text.startsWith("+") || text.length() < 2) return;

        List<Database.Account> accounts = db.accountsOf(e.getAuthor().getId());
        if (accounts.isEmpty()) {
            e.getChannel().sendMessage("No Minecraft account is linked to your Discord yet. Use /freemium first.").queue();
            return;
        }
        runGameCommand(accounts, text, e.getAuthor().getName(), msg -> e.getChannel().sendMessage(msg).queue());
    }

    // ---------- helpers ----------

    /** Picks the account from the "account" option, or the only one. Replies and returns null if it can't. */
    private Database.Account resolve(SlashCommandInteractionEvent e) {
        List<Database.Account> list = db.accountsOf(e.getUser().getId());
        if (list.isEmpty()) {
            e.reply("No Minecraft account is linked to your Discord yet. Use `/freemium` first.").setEphemeral(true).queue();
            return null;
        }
        var opt = e.getOption("account");
        if (opt != null) {
            String want = opt.getAsString().trim();
            for (Database.Account a : list) if (a.name().equalsIgnoreCase(want)) return a;
            e.reply("None of your linked accounts is named `" + want + "`.").setEphemeral(true).queue();
            return null;
        }
        if (list.size() == 1) return list.get(0);
        e.reply("You have several accounts linked (" + list.stream().map(Database.Account::name).collect(Collectors.joining(", "))
                + "). Pick one with the `account` option.").setEphemeral(true).queue();
        return null;
    }

    /** Shared by /mc and the "+command" DMs. */
    private void runGameCommand(List<Database.Account> candidates, String raw, String who, Consumer<String> reply) {
        FileConfiguration cfg = plugin.discordConfig();
        boolean all = cfg.getBoolean("commands.allow-all", false);
        List<String> allowed = cfg.getStringList("commands.allowed");
        if (!cfg.getBoolean("commands.enabled", false) || (!all && allowed.isEmpty())) {
            reply.accept("Running commands from Discord is turned off on this server.");
            return;
        }
        String t = raw.trim();
        while (t.startsWith("/") || t.startsWith("+")) t = t.substring(1).trim();
        final String cmd = t;
        if (cmd.isEmpty()) { reply.accept("Type a command, for example `+spawn`."); return; }
        final String first = cmd.split("\\s+")[0];
        if (!all && allowed.stream().noneMatch(s -> s.equalsIgnoreCase(first))) {
            reply.accept("That command is not allowed. Allowed: " + String.join(", ", allowed));
            return;
        }

        onMain(() -> {
            Player target = null;
            for (Database.Account a : candidates) {
                Player p = Bukkit.getPlayerExact(a.name());
                if (p != null) { target = p; break; }
            }
            if (target == null) return "None of your linked accounts is online on the server.";
            if (!auth.isAuthed(target)) return "You are not logged in yet.";
            return Bukkit.dispatchCommand(target, cmd) ? "Ran `/" + cmd + "`." : "The server did not recognize that command.";
        }).thenAccept(reply).exceptionally(ex -> { reply.accept("Something went wrong."); return null; });
        hook.log("Discord command", who + " ran `/" + cmd + "` from Discord", 0x5865F2);
    }

    // ---------- autocomplete for the "account" option ----------

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e) {
        if (!e.getFocusedOption().getName().equals("account")) return;
        String typed = e.getFocusedOption().getValue().toLowerCase();
        List<Command.Choice> choices = db.accountsOf(e.getUser().getId()).stream()
                .map(Database.Account::name)
                .filter(n -> n.toLowerCase().startsWith(typed))
                .limit(25)
                .map(n -> new Command.Choice(n, n))
                .collect(Collectors.toList());
        e.replyChoices(choices).queue();
    }

    // ---------- slash commands ----------

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        String id = e.getUser().getId();
        switch (e.getName()) {
            case "freemium" -> {
                String code = e.getOption("code").getAsString().trim().toUpperCase();
                AuthManager.PendingCode pc = auth.peekCode(code);
                if (pc == null) {
                    e.reply("That code is invalid or expired. Run `/freemium` in Minecraft to get a new one.")
                            .setEphemeral(true).queue();
                    return;
                }
                if (db.isLinked(pc.name(), id)) {
                    e.reply("**" + pc.display() + "** is already linked to your Discord.").setEphemeral(true).queue();
                    return;
                }
                e.reply("Link the Minecraft account **" + pc.display() + "** to your Discord account?\n"
                                + "Only confirm if this is your account. After linking you won't need to log in.")
                        .addActionRow(Button.success("vl_ok:" + code, "Yes, that's me"),
                                Button.danger("vl_no", "Cancel"))
                        .setEphemeral(true).queue();
            }
            case "unfreemium" -> {
                List<Database.Account> list = db.accountsOf(id);
                if (list.isEmpty()) {
                    e.reply("No Minecraft account is linked to your Discord.").setEphemeral(true).queue();
                    return;
                }
                var opt = e.getOption("account");
                if (opt == null && list.size() > 1) {
                    // several accounts: let the person choose which one to unlink
                    StringSelectMenu.Builder menu = StringSelectMenu.create("vl_pick_unlink")
                            .setPlaceholder("Choose the account to unlink");
                    for (Database.Account a : list) menu.addOption(a.name(), a.name());
                    e.reply("Which account do you want to unlink?").addActionRow(menu.build()).setEphemeral(true).queue();
                    return;
                }
                Database.Account a = opt == null ? list.get(0)
                        : list.stream().filter(x -> x.name().equalsIgnoreCase(opt.getAsString().trim())).findFirst().orElse(null);
                if (a == null) {
                    e.reply("None of your linked accounts has that name.").setEphemeral(true).queue();
                    return;
                }
                e.reply("Unlink **" + a.name() + "** from your Discord account? You will have to log in with your password again.")
                        .addActionRow(Button.danger("vl_unlink:" + a.name(), "Yes, unlink"),
                                Button.secondary("vl_no", "Cancel"))
                        .setEphemeral(true).queue();
            }
            case "password" -> {
                Database.Account a = resolve(e);
                if (a == null) return;
                Modal modal = Modal.create(MODAL_PREFIX + a.name(), "Change password")
                        .addActionRow(TextInput.create("pw", "New password for " + a.name(), TextInputStyle.SHORT)
                                .setMinLength(plugin.getConfig().getInt("password.min-length", 6))
                                .setMaxLength(64).build())
                        .addActionRow(TextInput.create("pw2", "Confirm new password", TextInputStyle.SHORT)
                                .setMinLength(1).setMaxLength(64).build())
                        .build();
                e.replyModal(modal).queue();
            }
            case "ping" -> {
                Database.Account a = resolve(e);
                if (a == null) return;
                e.deferReply(true).queue();
                onMain(() -> {
                    Player p = Bukkit.getPlayerExact(a.name());
                    return p == null ? -1 : p.getPing();
                }).thenAccept(ping -> e.getHook().sendMessage(ping < 0
                                ? "`" + a.name() + "` is not online on the server right now."
                                : "Ping of `" + a.name() + "`: **" + ping + " ms**").queue())
                  .exceptionally(ex -> { e.getHook().sendMessage("Something went wrong.").queue(); return null; });
            }
            case "account" -> {
                Database.Account a = resolve(e);
                if (a == null) return;
                e.deferReply(true).queue();
                String socials = Social.PLATFORMS.stream().filter(pf -> a.social(pf) != null)
                        .map(Social::label).collect(Collectors.joining(", "));
                int links = db.linksOf(a.name()).size();
                onMain(() -> {
                    Player p = Bukkit.getPlayerExact(a.name());
                    return p == null ? -1 : p.getPing();
                }).thenAccept(ping -> e.getHook().sendMessage(
                        "**Account** `" + a.name() + "`\n"
                                + "Linked Discord users: " + links + "\n"
                                + "Verified (no login on trusted IP): " + (a.verified() ? "yes" : "no") + "\n"
                                + "Premium login: " + (a.premium() ? "on" : "off") + "\n"
                                + "Socials: " + (socials.isEmpty() ? "none" : socials) + "\n"
                                + "Online now: " + (ping < 0 ? "no" : "yes (" + ping + " ms)") + "\n"
                                + "Last login: " + (a.lastLogin() > 0 ? "<t:" + (a.lastLogin() / 1000) + ":R>" : "never / cleared")).queue())
                  .exceptionally(ex -> { e.getHook().sendMessage("Something went wrong.").queue(); return null; });
            }
            case "kick" -> {
                Database.Account a = resolve(e);
                if (a == null) return;
                e.deferReply(true).queue();
                db.clearSession(a.name());
                hook.log("Kicked via Discord", "Account `" + a.name() + "` kicked by its owner", 0xED4245);
                onMain(() -> {
                    Player p = Bukkit.getPlayerExact(a.name());
                    if (p == null) return false;
                    p.kick(Component.text("Kicked from Discord."));
                    return true;
                }).thenAccept(kicked -> e.getHook().sendMessage(kicked
                                ? "Kicked `" + a.name() + "`. The saved IP was cleared."
                                : "`" + a.name() + "` is not online. The saved IP was cleared.").queue())
                  .exceptionally(ex -> { e.getHook().sendMessage("Something went wrong.").queue(); return null; });
            }
            case "premium" -> {
                Database.Account a = resolve(e);
                if (a == null) return;
                boolean on = e.getOption("mode").getAsString().equals("on");
                e.deferReply(true).queue();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String err = auth.setPremium(a.name(), on);
                    e.getHook().sendMessage(err != null ? err
                            : on ? "Premium login enabled for `" + a.name() + "`. Rejoin to verify with Mojang."
                            : "Premium login disabled for `" + a.name() + "`.").queue();
                });
            }
            case "mc" -> {
                var opt = e.getOption("account");
                List<Database.Account> list = db.accountsOf(id);
                if (list.isEmpty()) {
                    e.reply("No Minecraft account is linked to your Discord yet. Use `/freemium` first.").setEphemeral(true).queue();
                    return;
                }
                List<Database.Account> candidates = opt == null ? list
                        : list.stream().filter(x -> x.name().equalsIgnoreCase(opt.getAsString().trim())).collect(Collectors.toList());
                if (candidates.isEmpty()) {
                    e.reply("None of your linked accounts has that name.").setEphemeral(true).queue();
                    return;
                }
                e.deferReply(true).queue();
                runGameCommand(candidates, e.getOption("command").getAsString(), e.getUser().getName(),
                        msg -> e.getHook().sendMessage(msg).queue());
            }
            case "linkyoutube", "linktiktok", "linkinstagram" -> {
                String platform = e.getName().substring("link".length()); // youtube / tiktok / instagram
                Database.Account a = resolve(e);
                if (a == null) return;
                String raw = e.getOption("url").getAsString().trim();
                String nice = Social.label(platform);

                if (raw.equalsIgnoreCase("remove")) {
                    db.setSocial(a.name(), platform, null);
                    hook.log("Social removed", "`" + a.name() + "` removed " + nice, 0xFEE75C);
                    e.reply(nice + " removed from `" + a.name() + "`.").setEphemeral(true).queue();
                    return;
                }
                String clean = Social.normalize(platform, raw);
                if (clean == null) {
                    e.reply("That doesn't look like a " + nice + " link. Use your profile/channel link, for example from the "
                            + nice + " app's share button.").setEphemeral(true).queue();
                    return;
                }
                db.setSocial(a.name(), platform, clean);
                hook.log("Social linked", "`" + a.name() + "` linked " + nice + ": " + clean, 0x5865F2);
                e.reply(nice + " linked to **" + a.name() + "**: <" + clean + ">\nPlayers can see it with `/playersocial " + a.name() + "` in game.")
                        .setEphemeral(true).queue();
            }
        }
    }

    // ---------- dropdown / buttons / modal ----------

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent e) {
        if (!e.getComponentId().equals("vl_pick_unlink")) return;
        String name = e.getValues().get(0);
        if (!db.isLinked(name, e.getUser().getId())) {
            e.reply("You can't do that.").setEphemeral(true).queue();
            return;
        }
        e.editMessage("Unlink **" + name + "** from your Discord account? You will have to log in with your password again.")
                .setComponents(ActionRow.of(Button.danger("vl_unlink:" + name, "Yes, unlink"),
                        Button.secondary("vl_no", "Cancel")))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String cid = e.getComponentId();
        String clicker = e.getUser().getId();

        if (cid.equals("vl_no")) {
            e.editMessage("Cancelled. Nothing was changed.").setComponents().queue();
            return;
        }

        if (cid.startsWith("vl_ok:")) {
            String err = auth.confirmLink(cid.substring("vl_ok:".length()), clicker, e.getUser().getName());
            e.editMessage(err == null
                    ? "Linked! You can now join the server without logging in."
                    : err).setComponents().queue();
            return;
        }

        if (cid.startsWith("vl_unlink:")) {
            String name = cid.substring("vl_unlink:".length());
            if (!db.isLinked(name, clicker)) {
                e.reply("You can't do that.").setEphemeral(true).queue();
                return;
            }
            auth.unlink(name, clicker);
            e.editMessage("Unlinked. `" + name + "` is no longer connected to your Discord.").setComponents().queue();
            return;
        }

        if (cid.startsWith("vl_kick:")) {
            String name = cid.substring("vl_kick:".length());
            if (!db.isLinked(name, clicker)) {
                e.reply("You can't do that.").setEphemeral(true).queue();
                return;
            }
            db.clearSession(name);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p != null) p.kick(Component.text("Kicked from Discord."));
            });
            hook.log("Kicked via Discord", "Account `" + name + "` kicked by its owner", 0xED4245);
            e.editMessage("Done. The player was kicked and the saved IP was cleared.").setComponents().queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent e) {
        if (!e.getModalId().startsWith(MODAL_PREFIX)) return;
        String name = e.getModalId().substring(MODAL_PREFIX.length());
        if (!db.isLinked(name, e.getUser().getId())) {
            e.reply("You can't do that.").setEphemeral(true).queue();
            return;
        }
        String pw = e.getValue("pw").getAsString();
        String pw2 = e.getValue("pw2").getAsString();
        if (!pw.equals(pw2)) {
            e.reply("Passwords do not match.").setEphemeral(true).queue();
            return;
        }
        e.deferReply(true).queue();
        auth.changePassword(name, pw);
        e.getHook().sendMessage("Password changed for `" + name + "`. Use the new one next time you join.").queue();
    }
}
