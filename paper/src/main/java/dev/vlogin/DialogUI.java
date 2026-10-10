package dev.vlogin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Register / Login forms using Paper's Dialog API (server and client 1.21.6+). */
@SuppressWarnings("UnstableApiUsage")
public final class DialogUI {

    private final AuthManager auth;

    public DialogUI(AuthManager auth) {
        this.auth = auth;
        auth.setUi(this);
    }

    public void show(Player p, boolean register, String message) {
        p.showDialog(register ? registerDialog(message) : loginDialog(message));
    }

    private ClickCallback.Options once() {
        return ClickCallback.Options.builder().uses(1).build();
    }

    private Dialog registerDialog(String message) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Register"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                message == null ? "Create a password for your account." : message))))
                        .inputs(List.of(
                                DialogInput.text("pass", Component.text("Set Pass")).maxLength(64).build(),
                                DialogInput.text("confirm", Component.text("Confirm Pass")).maxLength(64).build()))
                        .build())
                .type(DialogType.notice(ActionButton.builder(Component.text("Register", NamedTextColor.GREEN))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p) {
                                auth.attemptRegister(p, view.getText("pass"), view.getText("confirm"));
                            }
                        }, once()))
                        .build())));
    }

    private Dialog loginDialog(String message) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Login"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                message == null ? "Enter your password." : message))))
                        .inputs(List.of(
                                DialogInput.text("pass", Component.text("Password")).maxLength(64).build()))
                        .build())
                .type(DialogType.notice(ActionButton.builder(Component.text("Login", NamedTextColor.GREEN))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p) {
                                auth.attemptLogin(p, view.getText("pass"));
                            }
                        }, once()))
                        .build())));
    }

    /**
     * /playersocial dialog:
     *      <player>'s social
     *         <head>
     *     [Youtube] [Tiktok]
     *   [Instagram] [Close]
     * Buttons for platforms that are not linked are greyed out and do nothing.
     */
    public void showSocial(Player viewer, String display, Database.Account acc) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            Player online = Bukkit.getPlayerExact(display);
            OfflinePlayer owner = online != null ? online : Bukkit.getOfflinePlayerIfCached(display);
            if (owner != null) meta.setOwningPlayer(owner);
            head.setItemMeta(meta);
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (String platform : Social.PLATFORMS) {
            String url = acc == null ? null : acc.social(platform);
            var b = ActionButton.builder(Component.text(Social.label(platform),
                            url != null ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY))
                    .width(120);
            if (url != null) {
                b.tooltip(Component.text("Open " + url)).action(DialogAction.staticAction(ClickEvent.openUrl(url)));
            } else {
                b.tooltip(Component.text("Not linked"));
            }
            buttons.add(b.build());
        }
        buttons.add(ActionButton.builder(Component.text("Close")).width(120).build()); // no action = just closes

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(display + "'s social"))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.item(head, null, false, false, 32, 32),
                                DialogBody.plainMessage(Component.text(" "))))
                        .build())
                .type(DialogType.multiAction(buttons, null, 2)));
        viewer.showDialog(dialog);
    }
}
