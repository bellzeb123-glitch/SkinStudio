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
import pl.skinstudio.util.ResourcePackScanner;
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
                    sender.sendMessage(c("&7Użyj &f/skintoken list &7lub &f/skintoken scan &7aby znaleźć dostępne."));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(c("&cNieprawidłowa ilość."));
                        return true;
                    }
                }
                var token = TokenUtil.createSkinToken(skin);
                token.setAmount(amount);
                var leftover = target.getInventory().addItem(token);
                if (!leftover.isEmpty()) leftover.values().forEach(i ->
                    target.getWorld().dropItemNaturally(target.getLocation(), i));
                sender.sendMessage(c("&aDano &f" + amount + "x Token Skina (" + skinId + ")&a graczowi &f" + target.getName()));
                target.sendMessage(c("&aOtrzymałeś Token Skina: &f" + skin.getDisplayName()));
            }

            case "giveremove" -> {
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
                    try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(c("&cNieprawidłowa ilość."));
                        return true;
                    }
                }
                var token = TokenUtil.createChangeToken();
                token.setAmount(amount);
                target.getInventory().addItem(token).values().forEach(i ->
                    target.getWorld().dropItemNaturally(target.getLocation(), i));
                sender.sendMessage(c("&aDano &f" + amount + "x Token Zmiany &agraczowi &f" + target.getName()));
                target.sendMessage(c("&aOtrzymałeś Token Zmiany!"));
            }

            case "list" -> {
                sender.sendMessage(c("&6&l=== Dostępne Skiny (" + plugin.getSkinConfig().getAllSkins().size() + ") ==="));
                Map<String, SkinDefinition> all = plugin.getSkinConfig().getAllSkins();
                if (all.isEmpty()) {
                    sender.sendMessage(c("&cBrak zdefiniowanych skinów. Użyj /skintoken scan"));
                    return true;
                }
                String lastTier = "";
                for (Map.Entry<String, SkinDefinition> entry : all.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                    String id = entry.getKey();
                    SkinDefinition skin = entry.getValue();
                    String tier = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
                    if (!tier.equals(lastTier)) {
                        sender.sendMessage(c("&8&m        &r &7" + tier.toUpperCase() + " &8&m        "));
                        lastTier = tier;
                    }
                    sender.sendMessage(c("  &f" + id + " &8→ &7" + skin.getItemModel()));
                }
            }

            case "scan" -> {
                sender.sendMessage(c("&eSkanowanie resource packów..."));
                // Uruchom w osobnym wątku żeby nie blokować serwera
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ResourcePackScanner scanner = new ResourcePackScanner(plugin);
                    int added = scanner.scan();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (added < 0) {
                            sender.sendMessage(c("&cBłąd skanowania — sprawdź konsolę."));
                        } else if (added == 0) {
                            sender.sendMessage(c("&7Nie znaleziono nowych skinów (wszystkie już są w config.yml)."));
                        } else {
                            sender.sendMessage(c("&aDodano &f" + added + "&a nowych skinów do config.yml!"));
                            // Przeładuj config
                            plugin.reloadConfig();
                            plugin.getSkinConfig().load();
                            sender.sendMessage(c("&aKonfiguracja przeładowana. Użyj &f/skinstudio admin &aaby zobaczyć nowe skiny."));
                        }
                    });
                });
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
            completions.addAll(List.of("give", "giveremove", "list", "scan", "reload"));
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
        sender.sendMessage(c("&e/skintoken scan &7— skanuj resource packi i dodaj nowe skiny"));
        sender.sendMessage(c("&e/skintoken reload &7— przeładuj konfigurację"));
    }

    private Component c(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
