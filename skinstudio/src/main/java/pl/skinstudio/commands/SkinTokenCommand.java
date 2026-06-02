package pl.skinstudio.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SkinTokenCommand implements CommandExecutor, TabCompleter {

    private final SkinStudio plugin;

    public SkinTokenCommand(SkinStudio plugin) {
        this.plugin = plugin;
        plugin.getCommand("skintoken").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skinstudio.admin")) {
            sender.sendMessage(c("&cNie masz uprawnień!"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "give" -> {
                // /skintoken give <gracz> <skin_id> [ilość]
                if (args.length < 3) {
                    sender.sendMessage(c("&cUżycie: /skintoken give <gracz> <skin_id> [ilość]"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(c("&cNie znaleziono gracza: &e" + args[1]));
                    return true;
                }
                String skinId = args[2];
                SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
                if (skin == null) {
                    sender.sendMessage(c("&cNieznany skin ID: &e" + skinId));
                    sender.sendMessage(c("&7Użyj &f/skintoken list &7aby zobaczyć dostępne."));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(c("&cNieprawidłowa ilość: &e" + args[3]));
                        return true;
                    }
                }
                var token = TokenUtil.createSkinToken(skin);
                token.setAmount(amount);
                var leftover = target.getInventory().addItem(token);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(i ->
                        target.getWorld().dropItemNaturally(target.getLocation(), i));
                    sender.sendMessage(c("&eEkwipunek gracza był pełny — przedmioty upuszczono na ziemię."));
                }
                sender.sendMessage(c("&aDano &f" + amount + "x Token Skina (" + skinId + ")&a graczowi &f" + target.getName()));
                target.sendMessage(c("&aOtrzymałeś Token Skina: &f" + skin.getDisplayName()));
            }

            case "giveremove" -> {
                // /skintoken giveremove <gracz> [ilość]
                if (args.length < 2) {
                    sender.sendMessage(c("&cUżycie: /skintoken giveremove <gracz> [ilość]"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(c("&cNie znaleziono gracza: &e" + args[1]));
                    return true;
                }
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(c("&cNieprawidłowa ilość."));
                        return true;
                    }
                }
                var token = TokenUtil.createChangeToken();
                token.setAmount(amount);
                var leftover = target.getInventory().addItem(token);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(i ->
                        target.getWorld().dropItemNaturally(target.getLocation(), i));
                }
                sender.sendMessage(c("&aDano &f" + amount + "x Token Zmiany&a graczowi &f" + target.getName()));
                target.sendMessage(c("&aOtrzymałeś Token Zmiany!"));
            }

            case "list" -> {
                sender.sendMessage(c("&6&l=== Dostępne Skiny ==="));
                Map<String, SkinDefinition> all = plugin.getSkinConfig().getAllSkins();
                if (all.isEmpty()) {
                    sender.sendMessage(c("&cBrak zdefiniowanych skinów."));
                    return true;
                }
                String lastTier = "";
                for (Map.Entry<String, SkinDefinition> entry : all.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                    String id = entry.getKey();
                    SkinDefinition skin = entry.getValue();
                    // Nagłówek tieru (pierwsza część przed _)
                    String tier = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
                    if (!tier.equals(lastTier)) {
                        sender.sendMessage(c("&8&m        &r &7" + tier.toUpperCase() + " &8&m        "));
                        lastTier = tier;
                    }
                    sender.sendMessage(c("  &f" + id + " &8→ &7" + skin.getItemModel()));
                }
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getSkinConfig().load();
                sender.sendMessage(c("&aSkinStudio przeładowany. Załadowano &f"
                    + plugin.getSkinConfig().getAllSkins().size() + "&a skinów."));
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(List.of("give", "giveremove", "list", "reload"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("giveremove"))) {
            Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            completions.addAll(plugin.getSkinConfig().getAllSkins().keySet());
        }
        String filter = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(filter));
        return completions;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(c("&6&l=== SkinToken — Pomoc ==="));
        sender.sendMessage(c("&e/skintoken give <gracz> <skin_id> [ilość] &7— daj Token Skina"));
        sender.sendMessage(c("&e/skintoken giveremove <gracz> [ilość] &7— daj Token Zmiany"));
        sender.sendMessage(c("&e/skintoken list &7— lista dostępnych skinów"));
        sender.sendMessage(c("&e/skintoken reload &7— przeładuj konfigurację"));
    }

    private Component c(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
