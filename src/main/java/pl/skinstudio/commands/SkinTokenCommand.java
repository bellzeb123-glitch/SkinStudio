package pl.skinstudio.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.CommandTargets;
import pl.skinstudio.util.NormalizeReport;
import pl.skinstudio.util.PendingPackApplier;
import pl.skinstudio.util.BuiltPackWriter;
import pl.skinstudio.util.ResourcePackDiagnostics;
import pl.skinstudio.util.ResourcePackScanner;
import pl.skinstudio.util.RpmBridge;
import pl.skinstudio.util.ScanResult;
import pl.skinstudio.util.TokenUtil;

import java.io.File;
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

            case "giveitem" -> {
                GiveArgs give = parseGiveArgs(sender, args, true);
                if (give.error() != null) {
                    sender.sendMessage(give.error());
                    if (give.showHint()) sender.sendMessage(lang().component("command.skin-not-found-hint"));
                    return true;
                }
                if (give.skin().getAllowedTypes().isEmpty()) {
                    sender.sendMessage(lang().component("command.giveitem-no-types", "skin", give.skinId()));
                    return true;
                }
                Material mat = give.skin().getAllowedTypes().get(0);
                ItemStack item = TokenUtil.applySkin(new ItemStack(mat), give.skin());
                give.target().getInventory().addItem(item).values().forEach(i ->
                    give.target().getWorld().dropItemNaturally(give.target().getLocation(), i));
                sender.sendMessage(lang().component("command.gave-item",
                    "skin", give.skinId(), "player", give.target().getName(), "item", mat.name()));
                give.target().sendMessage(lang().component("command.received-item",
                    "skin", give.skin().getDisplayName()));
            }

            case "repush" -> {
                sender.sendMessage(lang().component("command.repush-start"));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    RpmBridge.ensureRpmEnvironment(plugin);
                    RpmBridge.reloadMergedPack(plugin);
                    sender.sendMessage(lang().component("command.repush-done"));
                });
            }

            case "give" -> {
                GiveArgs give = parseGiveArgs(sender, args, false);
                if (give.error() != null) {
                    sender.sendMessage(give.error());
                    if (give.showHint()) sender.sendMessage(lang().component("command.skin-not-found-hint"));
                    return true;
                }
                var token = TokenUtil.createSkinToken(give.skin());
                token.setAmount(give.amount());
                var leftover = give.target().getInventory().addItem(token);
                if (!leftover.isEmpty()) leftover.values().forEach(i ->
                    give.target().getWorld().dropItemNaturally(give.target().getLocation(), i));
                sender.sendMessage(lang().component("command.gave-skin",
                    "amount", give.amount(), "skin", give.skinId(), "player", give.target().getName()));
                give.target().sendMessage(lang().component("command.received-skin",
                    "skin", give.skin().getDisplayName()));
            }

            case "giveremove" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().component("command.token-giveremove-usage"));
                    return true;
                }
                Player target = CommandTargets.resolvePlayer(sender, args[1]);
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
                if (plugin.getConfig().getBoolean("converter.bundle-only", true)) {
                    sender.sendMessage(lang().component("command.scan-disabled-bundle"));
                    sender.sendMessage(lang().component("command.scan-none-hint"));
                    return true;
                }
                sender.sendMessage(lang().component("command.scan-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ResourcePackScanner scanner = new ResourcePackScanner(plugin);
                    ScanResult result = scanner.scan();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (result.isError()) {
                            sender.sendMessage(lang().component("command.scan-error"));
                            return;
                        }
                        if (result.packsNormalized() > 0) {
                            sender.sendMessage(lang().component("command.scan-normalized",
                                "count", result.packsNormalized()));
                            RpmBridge.reloadMergedPack(plugin);
                        }
                        if (result.skinsAdded() > 0) {
                            sender.sendMessage(lang().component("command.scan-added",
                                "count", result.skinsAdded()));
                            plugin.reloadConfig();
                            plugin.getSkinConfig().load();
                            sender.sendMessage(lang().component("command.scan-reloaded"));
                            if (plugin.getConfig().getBoolean("scanner.auto-build-pack", true)) {
                                autoBuildPack(sender);
                            }
                        } else if (result.packsNormalized() > 0) {
                            sender.sendMessage(lang().component("command.scan-normalized-reload"));
                        } else {
                            sender.sendMessage(lang().component("command.scan-none"));
                            sender.sendMessage(lang().component("command.scan-none-hint"));
                        }
                    });
                });
            }

            case "normalize" -> {
                if (plugin.getConfig().getBoolean("converter.bundle-only", true)) {
                    sender.sendMessage(lang().component("command.normalize-disabled-bundle"));
                    sender.sendMessage(lang().component("command.scan-none-hint"));
                    return true;
                }
                sender.sendMessage(lang().component("command.normalize-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ResourcePackScanner scanner = new ResourcePackScanner(plugin);
                    NormalizeReport report = scanner.normalizeMixerPacks();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (report.isError()) {
                            sender.sendMessage(lang().component("command.scan-error"));
                            return;
                        }
                        if (report.changed() > 0) {
                            sender.sendMessage(lang().component("command.scan-normalized",
                                "count", report.changed()));
                            RpmBridge.reloadMergedPack(plugin);
                            sender.sendMessage(lang().component("command.scan-normalized-reload"));
                        } else if (!report.pendingFiles().isEmpty()) {
                            sender.sendMessage(lang().component("command.normalize-pending-hint"));
                            for (String p : report.pendingFiles()) {
                                sender.sendMessage(lang().component("command.normalize-pending", "file", p));
                            }
                            sender.sendMessage(lang().component("command.normalize-apply-hint"));
                        } else if (!report.errors().isEmpty()) {
                            sender.sendMessage(lang().component("command.normalize-failed"));
                            for (String err : report.errors()) {
                                sender.sendMessage(colorize("&c  " + err));
                            }
                            for (String p : report.pendingFiles()) {
                                sender.sendMessage(lang().component("command.normalize-pending", "file", p));
                            }
                        } else {
                            sender.sendMessage(lang().component("command.normalize-none"));
                        }
                    });
                });
            }

            case "apply" -> {
                sender.sendMessage(lang().component("command.apply-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
                    var result = PendingPackApplier.applyAll(
                        plugin.getServer().getPluginsFolder(), mixerPath, plugin.getLogger());
                    var built = BuiltPackWriter.applyPending(plugin, plugin.getLogger());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        int total = result.applied() + built.applied();
                        if (total > 0) {
                            sender.sendMessage(lang().component("command.apply-done", "count", total));
                            RpmBridge.reloadMergedPack(plugin);
                            sender.sendMessage(lang().component("command.scan-normalized-reload"));
                        } else if (!result.failed().isEmpty()) {
                            sender.sendMessage(lang().component("command.apply-failed"));
                            for (String f : result.failed()) {
                                sender.sendMessage(colorize("&c  " + f));
                            }
                            if (built.error() != null) {
                                sender.sendMessage(colorize("&c  SkinStudio-skins.zip: " + built.error()));
                            }
                            sender.sendMessage(lang().component("command.apply-restart-hint"));
                        } else {
                            sender.sendMessage(lang().component("command.apply-none"));
                        }
                    });
                });
            }

            case "convert" -> {
                sender.sendMessage(lang().component("command.convert-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    if (plugin.getInboxService() == null) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(lang().component("command.convert-disabled")));
                        return;
                    }
                    plugin.getInboxService().processInboxNow(sender);
                });
            }

            case "prepare" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().component("command.prepare-usage"));
                    return true;
                }
                String skinId = args[1].toLowerCase();
                sender.sendMessage(lang().component("command.prepare-start", "skin", skinId));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        var result = new pl.skinstudio.converter.SkinBundleExporter(plugin)
                            .prepareToInbox(skinId);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(lang().component("command.prepare-done",
                                "skin", skinId,
                                "path", result.bundleDir().getPath(),
                                "assets", result.assets(),
                                "pngs", result.pngs()));
                            sender.sendMessage(lang().component("command.prepare-next"));
                        });
                    } catch (Exception e) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(lang().component("command.prepare-failed",
                                "error", e.getMessage())));
                    }
                });
            }

            case "build" -> {
                sender.sendMessage(lang().component("command.build-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var report = new pl.skinstudio.pack.SkinPackBuilder(plugin).build();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!report.success()) {
                            sender.sendMessage(lang().component("command.build-failed", "error",
                                report.error() == null ? "?" : report.error()));
                            return;
                        }
                        sender.sendMessage(lang().component("command.build-done",
                            "skins", report.skinsIncluded(), "assets", report.assetsCopied()));
                        if (!report.incompleteSkins().isEmpty()) {
                            sender.sendMessage(lang().component("command.build-incomplete",
                                "count", report.incompleteSkins().size()));
                            report.incompleteSkins().forEach((id, missing) -> {
                                sender.sendMessage(colorize("&c  " + id + ":"));
                                for (String m : missing) sender.sendMessage(colorize("&8    - &7" + m));
                            });
                        }
                        RpmBridge.reloadMergedPack(plugin);
                        sender.sendMessage(lang().component("command.scan-normalized-reload"));
                    });
                });
            }

            case "diagnose" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().component("command.diagnose-usage"));
                    return true;
                }
                String skinId = args[1];
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<String> lines = new ResourcePackDiagnostics(plugin).diagnose(skinId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(lang().component("command.diagnose-title", "skin", skinId));
                        for (String line : lines) {
                            sender.sendMessage(colorize("&7" + line));
                        }
                    });
                });
            }

            case "language" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().component("command.language-current",
                        "lang", plugin.getLang().getLanguage().toUpperCase()));
                    sender.sendMessage(lang().component("command.token-language-usage"));
                    return true;
                }
                String code = args[1].toLowerCase();
                if (!LangManager.AVAILABLE_LANGUAGES.contains(code)) {
                    sender.sendMessage(lang().component("command.language-invalid",
                        "languages", String.join(", ", LangManager.AVAILABLE_LANGUAGES)));
                    return true;
                }
                plugin.getLang().setLanguage(code);
                sender.sendMessage(lang().component("command.language-changed", "lang", code.toUpperCase()));
            }

            case "importoraxen" -> {
                String nsFilter = args.length >= 2 ? args[1] : "dark_queen";
                boolean overwrite = args.length < 3 || !args[2].equalsIgnoreCase("skip");
                sender.sendMessage(colorize("&7Oraxen import: namespace=&f" + nsFilter
                    + " &7overwrite=&f" + overwrite));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var importer = new pl.skinstudio.converter.OraxenImporter(plugin);
            var result = importer.importFromOraxen(nsFilter, overwrite);
            int packAction = importer.syncOraxenAssetsToSkinStudioPack(nsFilter);
            Bukkit.getScheduler().runTask(plugin, () -> {
                        if (result.skinIds().isEmpty() && result.added() == 0 && result.updated() == 0) {
                            sender.sendMessage(colorize("&cOraxen import: brak skinów."));
                            for (String w : result.warnings()) {
                                sender.sendMessage(colorize("&e  " + w));
                            }
                            return;
                        }
                        sender.sendMessage(colorize("&aOraxen import OK &7(źródło: &f" + result.packSource() + "&7)"));
                        sender.sendMessage(colorize("&7  dodano: &f" + result.added()
                            + " &7| zaktualizowano: &f" + result.updated()
                            + " &7| pominięto: &f" + result.skipped()));
                        if (packAction > 0) {
                            sender.sendMessage(colorize("&7  skopiowano &f" + packAction
                                + " &7assetów Oraxen → SkinStudio/pack (RPM merge)"));
                        }
                        if (result.textureWarnings() > 0) {
                            sender.sendMessage(colorize("&e  Ostrzeżenie PNG: &f" + result.textureWarnings()
                                + " &e— placeholder w oryginalnym packu"));
                        }
                        for (String id : result.skinIds()) {
                            sender.sendMessage(colorize("  &f" + id));
                        }
                        for (String w : result.warnings()) {
                            sender.sendMessage(colorize("&e  " + w));
                        }
                        RpmBridge.reloadMergedPack(plugin);
                        sender.sendMessage(colorize("&7RPM reload — wyjdź i wejdź, potem &f/skintoken giveitem @s dark_queen_axe"));
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
            completions.addAll(List.of("give", "giveitem", "giveremove", "list", "scan", "convert", "importoraxen", "prepare", "normalize", "build", "apply", "repush", "diagnose", "reload", "language"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("giveitem") || args[0].equalsIgnoreCase("giveremove"))) {
            Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("giveitem"))) {
            completions.addAll(plugin.getSkinConfig().getAllSkins().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("importoraxen")) {
            completions.addAll(List.of("dark_queen", "all"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("prepare")) {
            completions.addAll(plugin.getSkinConfig().getAllSkins().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("diagnose")) {
            completions.addAll(plugin.getSkinConfig().getAllSkins().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("language")) {
            completions.addAll(LangManager.AVAILABLE_LANGUAGES);
        }
        String filter = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(filter));
        return completions;
    }

    private record GiveArgs(Player target, String skinId, SkinDefinition skin, int amount,
                            net.kyori.adventure.text.Component error, boolean showHint) {}

    private GiveArgs parseGiveArgs(CommandSender sender, String[] args, boolean giveItem) {
        if (args.length < 2) {
            return new GiveArgs(null, null, null, 0,
                lang().component(giveItem ? "command.token-giveitem-usage" : "command.token-give-usage"), false);
        }

        Player target;
        String skinId;
        int amountIndex;

        if (args.length == 2) {
            if (!(sender instanceof Player self)) {
                return new GiveArgs(null, null, null, 0,
                    lang().component("command.give-needs-player"), false);
            }
            target = self;
            skinId = args[1];
            amountIndex = 2;
        } else {
            target = CommandTargets.resolvePlayer(sender, args[1]);
            skinId = args[2];
            amountIndex = 3;

            if (target == null && plugin.getSkinConfig().getSkin(args[1]) != null) {
                target = CommandTargets.resolvePlayer(sender, args[2]);
                skinId = args[1];
                amountIndex = 3;
            }

            if (target == null) {
                return new GiveArgs(null, null, null, 0,
                    lang().component("command.player-not-found", "player", args[1]), false);
            }
        }

        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            return new GiveArgs(null, skinId, null, 0,
                lang().component("command.skin-not-found", "skin", skinId), true);
        }

        int amount = 1;
        if (args.length > amountIndex) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[amountIndex])));
            } catch (NumberFormatException e) {
                return new GiveArgs(null, skinId, null, 0,
                    lang().component("command.invalid-amount"), false);
            }
        }
        return new GiveArgs(target, skinId, skin, amount, null, false);
    }

    /** Buduje czysty self-contained pack po skanie (asynchronicznie) i przeładowuje RPM. */
    private void autoBuildPack(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var report = new pl.skinstudio.pack.SkinPackBuilder(plugin).build();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (report.success()) {
                    sender.sendMessage(lang().component("command.build-done",
                        "skins", report.skinsIncluded(), "assets", report.assetsCopied()));
                    RpmBridge.reloadMergedPack(plugin);
                    sender.sendMessage(lang().component("command.scan-normalized-reload"));
                } else {
                    sender.sendMessage(lang().component("command.build-failed", "error",
                        report.error() == null ? "?" : report.error()));
                }
            });
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang().component("command.token-help-title"));
        sender.sendMessage(lang().component("command.token-give-usage"));
        sender.sendMessage(lang().component("command.token-giveitem-usage"));
        sender.sendMessage(lang().component("command.token-giveremove-usage"));
        sender.sendMessage(lang().component("command.token-list-usage"));
        sender.sendMessage(lang().component("command.token-scan-usage"));
        sender.sendMessage(lang().component("command.token-convert-usage"));
        sender.sendMessage(lang().component("command.token-prepare-usage"));
        sender.sendMessage(lang().component("command.token-normalize-usage"));
        sender.sendMessage(lang().component("command.token-build-usage"));
        sender.sendMessage(lang().component("command.token-apply-usage"));
        sender.sendMessage(lang().component("command.token-diagnose-usage"));
        sender.sendMessage(lang().component("command.token-reload-usage"));
        sender.sendMessage(lang().component("command.token-language-usage"));
    }

    private net.kyori.adventure.text.Component colorize(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacyAmpersand().deserialize(text);
    }
}
