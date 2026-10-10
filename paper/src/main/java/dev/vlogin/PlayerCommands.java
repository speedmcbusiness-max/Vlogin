package dev.vlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /login and /register are fallbacks for clients older than 1.21.6 (no dialogs). */
public final class PlayerCommands implements CommandExecutor {

    private final AuthManager auth;

    public PlayerCommands(AuthManager auth) { this.auth = auth; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        String name = c.getName().toLowerCase();

        switch (name) {
            case "login" -> {
                if (auth.isAuthed(p)) { p.sendMessage(Component.text("Already logged in.", NamedTextColor.YELLOW)); return true; }
                if (a.length != 1) return false;
                auth.attemptLogin(p, a[0]);
            }
            case "register" -> {
                if (auth.isAuthed(p)) { p.sendMessage(Component.text("Already logged in.", NamedTextColor.YELLOW)); return true; }
                if (a.length != 2) return false;
                auth.attemptRegister(p, a[0], a[1]);
            }
            case "ping" -> {
                Player target = p;
                if (a.length > 0) {
                    if (!p.hasPermission("vlogin.ping.others")) {
                        p.sendMessage(Component.text("You can't check other players' ping.", NamedTextColor.RED));
                        return true;
                    }
                    target = Bukkit.getPlayerExact(a[0]);
                    if (target == null) { p.sendMessage(Component.text("Player not found.", NamedTextColor.RED)); return true; }
                }
                p.sendMessage(Component.text(target.getName() + "'s ping: " + target.getPing() + " ms", NamedTextColor.GREEN));
            }
            default -> {
                // freemium, unfreemium, premium, unpremium: only for logged-in players
                if (!auth.isAuthed(p)) { p.sendMessage(Component.text("Log in first.", NamedTextColor.RED)); return true; }
                switch (name) {
                    case "freemium" -> auth.requestCode(p);
                    case "unfreemium" -> auth.requestUnlink(p, a.length > 0 ? a[0] : null);
                    case "playersocial" -> auth.requestSocial(p, a.length > 0 ? a[0] : null);
                    case "premium" -> auth.requestPremium(p, true);
                    case "unpremium" -> auth.requestPremium(p, false);
                    default -> { return false; }
                }
            }
        }
        return true;
    }
}
