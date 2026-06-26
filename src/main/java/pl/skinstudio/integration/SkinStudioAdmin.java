package pl.skinstudio.integration;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.bell.hub.api.ActionResult;
import pl.bell.hub.api.Actor;
import pl.bell.hub.api.HubAction;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.converter.OraxenImporter;
import pl.skinstudio.delivery.PackDelivery;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.pack.SkinPackBuilder;
import pl.skinstudio.util.BuiltPackWriter;
import pl.skinstudio.util.CommandTargets;
import pl.skinstudio.util.PendingPackApplier;
import pl.skinstudio.util.TokenUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Logika admina SkinStudio dla panelu BellHub — operacje jak /skintoken. */
public final class SkinStudioAdmin {

    private static final Set<String> BOOL_CONFIG_KEYS = Set.of(
            "converter.enabled",
            "converter.process-on-startup",
            "converter.bundle-only",
            "converter.auto-tier",
            "delivery.enabled",
            "scanner.auto-normalize",
            "scanner.auto-rpm-reload",
            "scanner.push-after-reload",
            "scanner.auto-build-pack");

    private final SkinStudio plugin;

    public SkinStudioAdmin(SkinStudio plugin) {
        this.plugin = plugin;
    }

    public int inboxPendingCount() {
        var inbox = plugin.getInboxService();
        if (inbox == null) return 0;
        File dir = inbox.getConverter().inboxDir();
        File[] pending = dir.listFiles(f ->
                (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        || (f.isDirectory() && !f.getName().startsWith(".")));
        return pending == null ? 0 : pending.length;
    }

    // ── Widoki ──────────────────────────────────────────────

    public String viewOverview() {
        var cfg = plugin.getConfig();
        String deliveryMode = cfg.getString("delivery.mode", "auto");
        boolean converterOn = cfg.getBoolean("converter.enabled", true);
        boolean deliveryOn = cfg.getBoolean("delivery.enabled", true);
        return "{\"skins\":" + plugin.getSkinConfig().getAllSkins().size()
                + ",\"tiers\":" + plugin.getAdminGUI().getTierCount()
                + ",\"unique\":" + cfg.getStringList("unique-skins").size()
                + ",\"inboxPending\":" + inboxPendingCount()
                + ",\"converterEnabled\":" + converterOn
                + ",\"deliveryEnabled\":" + deliveryOn
                + ",\"deliveryMode\":\"" + esc(deliveryMode) + "\""
                + ",\"bundleOnly\":" + cfg.getBoolean("converter.bundle-only", true)
                + ",\"language\":\"" + esc(plugin.getLang().getLanguage()) + "\"}";
    }

    public String viewSkins(Map<String, String> params) {
        int page = parseInt(params.get("page"), 0);
        int limit = Math.min(100, Math.max(1, parseInt(params.get("limit"), 40)));
        String q = params.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        String tier = params.getOrDefault("tier", "").trim().toLowerCase(Locale.ROOT);

        List<Map.Entry<String, SkinDefinition>> all = plugin.getSkinConfig().getAllSkins().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> {
                    if (!q.isEmpty() && !e.getKey().toLowerCase(Locale.ROOT).contains(q)
                            && !e.getValue().getItemModel().toLowerCase(Locale.ROOT).contains(q)) {
                        return false;
                    }
                    if (!tier.isEmpty()) {
                        String skinTier = tierOf(e.getKey());
                        if (!skinTier.equalsIgnoreCase(tier) && !"unique".equals(tier)) return false;
                        if ("unique".equals(tier) && !plugin.getConfig().getStringList("unique-skins")
                                .contains(e.getKey())) return false;
                    }
                    return true;
                })
                .toList();

        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) limit));
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * limit;
        int to = Math.min(from + limit, all.size());

        List<String> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var e = all.get(i);
            SkinDefinition s = e.getValue();
            rows.add("{\"id\":\"" + esc(e.getKey()) + "\""
                    + ",\"tier\":\"" + esc(tierOf(e.getKey())) + "\""
                    + ",\"displayName\":\"" + esc(stripColor(s.getDisplayName())) + "\""
                    + ",\"itemModel\":\"" + esc(s.getItemModel()) + "\""
                    + ",\"category\":\"" + esc(s.getCategory().name().toLowerCase(Locale.ROOT)) + "\""
                    + ",\"types\":" + s.getAllowedTypes().size() + "}");
        }
        return "{\"page\":" + page + ",\"pages\":" + pages + ",\"total\":" + all.size()
                + ",\"skins\":[" + String.join(",", rows) + "]}";
    }

    public String viewSkin(String id) {
        if (id == null || id.isBlank()) return "{\"found\":false}";
        SkinDefinition s = plugin.getSkinConfig().getSkin(id.trim().toLowerCase(Locale.ROOT));
        if (s == null) return "{\"found\":false}";
        List<String> types = new ArrayList<>();
        for (Material m : s.getAllowedTypes()) types.add(m.name());
        List<String> typeJson = new ArrayList<>();
        for (String t : types) typeJson.add("\"" + esc(t) + "\"");
        return "{\"found\":true,\"skin\":{"
                + "\"id\":\"" + esc(s.getId()) + "\""
                + ",\"displayName\":\"" + esc(stripColor(s.getDisplayName())) + "\""
                + ",\"itemModel\":\"" + esc(s.getItemModel()) + "\""
                + ",\"sourceItemModel\":\"" + esc(s.getSourceItemModel()) + "\""
                + ",\"sourceModel\":\"" + esc(s.getSourceModel()) + "\""
                + ",\"equipmentAsset\":\"" + esc(s.getEquipmentAsset()) + "\""
                + ",\"category\":\"" + esc(s.getCategory().name().toLowerCase(Locale.ROOT)) + "\""
                + ",\"itemTypes\":[" + String.join(",", typeJson) + "]"
                + "}}";
    }

    public String viewTiers() {
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("tiers");
        List<String> rows = new ArrayList<>();
        if (tiers != null) {
            for (String id : tiers.getKeys(false)) {
                ConfigurationSection t = tiers.getConfigurationSection(id);
                if (t == null) continue;
                rows.add("{\"id\":\"" + esc(id) + "\""
                        + ",\"displayName\":\"" + esc(stripColor(t.getString("display-name", id))) + "\""
                        + ",\"material\":\"" + esc(t.getString("material", "PAPER")) + "\"}");
            }
        }
        List<String> unique = plugin.getConfig().getStringList("unique-skins");
        List<String> uniqueJson = new ArrayList<>();
        for (String u : unique) uniqueJson.add("\"" + esc(u) + "\"");
        return "{\"tiers\":[" + String.join(",", rows) + "],\"uniqueSkins\":["
                + String.join(",", uniqueJson) + "]}";
    }

    public String viewSettings() {
        var c = plugin.getConfig();
        return "{\"language\":\"" + esc(plugin.getLang().getLanguage()) + "\""
                + ",\"converterEnabled\":" + c.getBoolean("converter.enabled", true)
                + ",\"converterBundleOnly\":" + c.getBoolean("converter.bundle-only", true)
                + ",\"converterAutoTier\":" + c.getBoolean("converter.auto-tier", true)
                + ",\"converterProcessOnStartup\":" + c.getBoolean("converter.process-on-startup", true)
                + ",\"deliveryEnabled\":" + c.getBoolean("delivery.enabled", true)
                + ",\"deliveryMode\":\"" + esc(c.getString("delivery.mode", "auto")) + "\""
                + ",\"scannerAutoNormalize\":" + c.getBoolean("scanner.auto-normalize", true)
                + ",\"scannerAutoRpmReload\":" + c.getBoolean("scanner.auto-rpm-reload", true)
                + ",\"scannerPushAfterReload\":" + c.getBoolean("scanner.push-after-reload", true)
                + ",\"scannerAutoBuildPack\":" + c.getBoolean("scanner.auto-build-pack", true)
                + ",\"mixerFolder\":\"" + esc(c.getString("scanner.mixer-folder", "ResourcePackManager/mixer")) + "\""
                + "}";
    }

    // ── Akcje ───────────────────────────────────────────────

    public ActionResult invoke(HubAction action, Actor actor) {
        if (actor == null || (!actor.admin() && !actor.has("bellhub.module.skinstudio"))) {
            return ActionResult.error("Brak uprawnień.");
        }
        return switch (action.name()) {
            case "token.give" -> tokenGive(action.param("player"), action.param("skinId"), action.param("amount"));
            case "token.giveRemove" -> tokenGiveRemove(action.param("player"), action.param("amount"));
            case "token.giveItem" -> tokenGiveItem(action.param("player"), action.param("skinId"));
            case "pack.convert" -> packConvert();
            case "pack.build" -> packBuild();
            case "pack.apply" -> packApply();
            case "pack.repush" -> packRepush();
            case "import.oraxen" -> importOraxen(action.param("namespace"), action.param("overwrite"));
            case "settings.language" -> setLanguage(action.param("value"));
            case "settings.setBoolean" -> setBooleanConfig(action.param("key"), action.param("enabled"));
            case "settings.reload" -> {
                plugin.reloadSkinCatalog();
                yield ActionResult.ok("Przeładowano katalog (" + plugin.getSkinConfig().getAllSkins().size() + " skinów).");
            }
            default -> ActionResult.error("Nieznana akcja: " + action.name());
        };
    }

    private ActionResult tokenGive(String playerName, String skinId, String amountStr) {
        Player target = resolvePlayer(playerName);
        if (target == null) return ActionResult.error("Nie znaleziono gracza: " + nullToEmpty(playerName));
        if (skinId == null || skinId.isBlank()) return ActionResult.error("Podaj ID skina.");
        String id = skinId.trim().toLowerCase(Locale.ROOT);
        SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
        if (skin == null) return ActionResult.error("Nieznany skin: " + id);
        int amount = clampAmount(amountStr, 1);
        ItemStack token = TokenUtil.createSkinToken(skin);
        token.setAmount(amount);
        giveItem(target, token);
        return ActionResult.ok("Dano " + amount + "× token " + id + " → " + target.getName());
    }

    private ActionResult tokenGiveRemove(String playerName, String amountStr) {
        Player target = resolvePlayer(playerName);
        if (target == null) return ActionResult.error("Nie znaleziono gracza: " + nullToEmpty(playerName));
        int amount = clampAmount(amountStr, 1);
        ItemStack token = TokenUtil.createChangeToken();
        token.setAmount(amount);
        giveItem(target, token);
        return ActionResult.ok("Dano " + amount + "× token zmiany → " + target.getName());
    }

    private ActionResult tokenGiveItem(String playerName, String skinId) {
        Player target = resolvePlayer(playerName);
        if (target == null) return ActionResult.error("Nie znaleziono gracza: " + nullToEmpty(playerName));
        if (skinId == null || skinId.isBlank()) return ActionResult.error("Podaj ID skina.");
        String id = skinId.trim().toLowerCase(Locale.ROOT);
        SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
        if (skin == null) return ActionResult.error("Nieznany skin: " + id);
        if (skin.getAllowedTypes().isEmpty()) return ActionResult.error("Skin bez item-types: " + id);
        Material mat = skin.getAllowedTypes().get(0);
        ItemStack item = TokenUtil.applySkin(new ItemStack(mat), skin);
        giveItem(target, item);
        return ActionResult.ok("Dano " + mat.name() + " ze skinem " + id + " → " + target.getName());
    }

    private ActionResult packConvert() {
        if (plugin.getInboxService() == null) return ActionResult.error("Konwerter wyłączony.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                plugin.getInboxService().processInboxNow(null));
        return ActionResult.ok("Konwersja inbox uruchomiona — sprawdź log serwera.");
    }

    private ActionResult packBuild() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var report = new SkinPackBuilder(plugin).build();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (report.success()) {
                    PackDelivery.deliver(plugin, true);
                    plugin.getLogger().info("BellHub: pack build OK — " + report.skinsIncluded() + " skinów.");
                } else {
                    plugin.getLogger().warning("BellHub: pack build failed: " + report.error());
                }
            });
        });
        return ActionResult.ok("Budowanie packa uruchomione w tle.");
    }

    private ActionResult packApply() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
            var result = PendingPackApplier.applyAll(plugin.getServer().getPluginsFolder(), mixerPath, plugin.getLogger());
            var built = BuiltPackWriter.applyPending(plugin, plugin.getLogger());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result.applied() + built.applied() > 0) {
                    PackDelivery.deliver(plugin, true);
                }
            });
        });
        return ActionResult.ok("Zastosowanie pending packów uruchomione w tle.");
    }

    private ActionResult packRepush() {
        Bukkit.getScheduler().runTask(plugin, () -> PackDelivery.redeliver(plugin));
        return ActionResult.ok("Wymuszono ponowną dostawę packa online.");
    }

    private ActionResult importOraxen(String namespace, String overwriteStr) {
        String ns = namespace == null || namespace.isBlank() ? "all" : namespace.trim();
        boolean overwrite = !"false".equalsIgnoreCase(nullToEmpty(overwriteStr));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var importer = new OraxenImporter(plugin);
            var result = importer.importFromOraxen(ns, overwrite);
            importer.syncOraxenAssetsToSkinStudioPack(ns);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!result.skinIds().isEmpty() || result.added() > 0 || result.updated() > 0) {
                    plugin.reloadSkinCatalog();
                }
                plugin.getLogger().info("BellHub Oraxen import: dodano=" + result.added()
                        + " zaktualizowano=" + result.updated());
            });
        });
        return ActionResult.ok("Import Oraxen uruchomiony (namespace=" + ns + ").");
    }

    private ActionResult setLanguage(String code) {
        if (code == null || !LangManager.AVAILABLE_LANGUAGES.contains(code.toLowerCase(Locale.ROOT))) {
            return ActionResult.error("Język: pl lub en.");
        }
        plugin.getLang().setLanguage(code.toLowerCase(Locale.ROOT));
        return ActionResult.ok("Język = " + code.toLowerCase(Locale.ROOT) + ".");
    }

    private ActionResult setBooleanConfig(String key, String enabledStr) {
        if (key == null || key.isBlank() || !BOOL_CONFIG_KEYS.contains(key.trim())) {
            return ActionResult.error("Nieobsługiwany klucz ustawienia.");
        }
        boolean enabled = parseBool(enabledStr, true);
        plugin.getConfig().set(key.trim(), enabled);
        plugin.saveConfig();
        return ActionResult.ok("Zapisano " + key + " = " + enabled + ".");
    }

    // ── helpers ─────────────────────────────────────────────

    private static Player resolvePlayer(String name) {
        return CommandTargets.resolvePlayer(Bukkit.getConsoleSender(), name);
    }

    private static void giveItem(Player target, ItemStack item) {
        target.getInventory().addItem(item).values()
                .forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
    }

    private static int clampAmount(String raw, int def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String tierOf(String skinId) {
        int idx = skinId.indexOf('_');
        return idx > 0 ? skinId.substring(0, idx) : skinId;
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBool(String s, boolean def) {
        if (s == null || s.isBlank()) return def;
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "tak".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "nie".equalsIgnoreCase(s)) return false;
        return def;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
