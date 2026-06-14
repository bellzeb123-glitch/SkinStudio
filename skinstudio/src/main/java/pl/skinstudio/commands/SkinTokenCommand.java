package pl.skinstudio.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.LangManager;
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

    private LangManager lang() { return plugin.getLang(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skinstudio.admin")) {
            sender.sendMessage(lang().component("command.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "give" -> {
                if (args.length < 3) {
                    sender.sendMessage(lang().component("command.token-give-usage"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(lang().component("command.player-not-found", "player", args[1]));
                    return true;
                }
                String skinId = args[2];
                SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
                if (skin == null) {
                    sender.sendMessage(lang().component("command.skin-not-found", "skin", skinId));
                    sender.sendMessage(lang().component("command.skin-not-found-hint"));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(lang().component("command.invalid-amount"));
                        return true;
                    }
                }
                var token = TokenUtil.createSkinToken(skin);
                token.setAmount(amount);
                var leftover = target.getInventory().addItem(token);
                if (!leftover.isEmpty()) leftover.values().forEach(i ->
                    target.getWorld().dropItemNaturally(target.getLocation(), i));
                sender.sendMessage(lang().component("command.gave-skin",
                    "amount", amount, "skin", skinId, "player", target.getName()));
                target.sendMessage(lang().component("command.received-skin",
                    "skin", skin.getDisplayName()));
            }

            case "giveremove" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().component("command.token-giveremove-usage"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(lang().component("command.player-not-found", "player", args[1]));
                    return true;
                }
                int amount = 1;
                if (args.length >= 3) {
                    try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(lang().component("command.invalid-amount"));
                        return true;
                    }
                }
                var token = TokenUtil.createChangeToken();
                token.setAmount(amount);
                target.getInventory().addItem(token).values().forEach(i ->
                    target.getWorld().dropItemNaturally(target.getLocation(), i));
                sender.sendMessage(lang().component("command.gave-change",
                    "amount", amount, "player", target.getName()));
                target.sendMessage(lang().component("command.received-change"));
            }

            case "list" -> {
                Map<String, SkinDefinition> all = plugin.getSkinConfig().getAllSkins();
                sender.sendMessage(lang().component("command.list-title", "count", all.size()));
                if (all.isEmpty()) {
                    sender.sendMessage(lang().component("command.list-empty"));
                    return true;
                }
                String lastTier = "";
                for (Map.Entry<String, SkinDefinition> entry : all.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                    String id = entry.getKey();
                    SkinDefinition skin = entry.getValue();
                    String tier = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
                    if (!tier.equals(lastTier)) {
                        sender.sendMessage(colorize("&8&m        &r &7" + tier.toUpperCase() + " &8&m        "));
                        lastTier = tier;
                    }
                    sender.sendMessage(colorize("  &f" + id + " &8→ &7" + skin.getItemModel()));
                }
            }

            case "scan" -> {
                sender.sendMessage(lang().component("command.scan-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ResourcePackScanner scanner = new ResourcePackScanner(plugin);
                    int added = scanner.scan();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (added < 0) {
                            sender.sendMessage(lang().component("command.scan-error"));
                        } else if (added == 0) {
                            sender.sendMessage(lang().component("command.scan-none"));
                        } else {
                            sender.sendMessage(lang().component("command.scan-added", "count", added));
                            plugin.reloadConfig();
                            plugin.getSkinConfig().load();
                            sender.sendMessage(lang().component("command.scan-reloaded"));
                        }
                    });
                });
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getSkinConfig().load();
                plugin.getLang().reload();
                plugin.getAdminGUI().loadTiers();
                sender.sendMessage(lang().component("command.reloaded",
                    "count", plugin.getSkinConfig().getAllSkins().size()));
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
        sender.sendMessage(lang().component("command.token-help-title"));
        sender.sendMessage(lang().component("command.token-give-usage"));
        sender.sendMessage(lang().component("command.token-giveremove-usage"));
        sender.sendMessage(lang().component("command.token-list-usage"));
        sender.sendMessage(lang().component("command.token-scan-usage"));
        sender.sendMessage(lang().component("command.token-reload-usage"));
    }

    private net.kyori.adventure.text.Component colorize(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacyAmpersand().deserialize(text);
    }
}
