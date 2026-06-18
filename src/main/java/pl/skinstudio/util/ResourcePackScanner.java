package pl.skinstudio.util;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.SkinConfig;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ResourcePackScanner {

    private static final Pattern GENERIC_ITEM =
        Pattern.compile("assets/([^/]+)/items/([^/]+)\\.json");

    // Mapowanie końcówki nazwy pliku → lista materiałów MC
    private static final Map<String, List<String>> TYPE_MAP = new LinkedHashMap<>();

    static {
        TYPE_MAP.put("_sword",      List.of("DIAMOND_SWORD","IRON_SWORD","GOLDEN_SWORD","STONE_SWORD","WOODEN_SWORD","NETHERITE_SWORD"));
        TYPE_MAP.put("_axe",        List.of("DIAMOND_AXE","IRON_AXE","GOLDEN_AXE","STONE_AXE","WOODEN_AXE","NETHERITE_AXE"));
        TYPE_MAP.put("_bow",        List.of("BOW"));
        TYPE_MAP.put("_crossbow",   List.of("CROSSBOW"));
        TYPE_MAP.put("_scythe",     List.of("DIAMOND_HOE","IRON_HOE","GOLDEN_HOE","STONE_HOE","WOODEN_HOE","NETHERITE_HOE"));
        TYPE_MAP.put("_trident",    List.of("TRIDENT"));
        TYPE_MAP.put("_spear",      List.of("TRIDENT"));
        TYPE_MAP.put("_mace",       List.of("MACE","DIAMOND_AXE","IRON_AXE","NETHERITE_AXE"));
        TYPE_MAP.put("_helmet",     List.of("DIAMOND_HELMET","IRON_HELMET","GOLDEN_HELMET","LEATHER_HELMET","NETHERITE_HELMET","CHAINMAIL_HELMET"));
        TYPE_MAP.put("_chestplate", List.of("DIAMOND_CHESTPLATE","IRON_CHESTPLATE","GOLDEN_CHESTPLATE","LEATHER_CHESTPLATE","NETHERITE_CHESTPLATE","CHAINMAIL_CHESTPLATE"));
        TYPE_MAP.put("_leggings",   List.of("DIAMOND_LEGGINGS","IRON_LEGGINGS","GOLDEN_LEGGINGS","LEATHER_LEGGINGS","NETHERITE_LEGGINGS","CHAINMAIL_LEGGINGS"));
        TYPE_MAP.put("_boots",      List.of("DIAMOND_BOOTS","IRON_BOOTS","GOLDEN_BOOTS","LEATHER_BOOTS","NETHERITE_BOOTS","CHAINMAIL_BOOTS"));
        TYPE_MAP.put("_toothpick",  List.of("DIAMOND_SWORD","IRON_SWORD","GOLDEN_SWORD","NETHERITE_SWORD","STICK"));
        TYPE_MAP.put("_gladius",    List.of("DIAMOND_SWORD","IRON_SWORD","GOLDEN_SWORD","NETHERITE_SWORD"));
        TYPE_MAP.put("_dagger",     List.of("DIAMOND_SWORD","IRON_SWORD","GOLDEN_SWORD","NETHERITE_SWORD"));
        TYPE_MAP.put("_staff",      List.of("DIAMOND_HOE","IRON_HOE","GOLDEN_HOE","STICK"));
        TYPE_MAP.put("_wand",       List.of("DIAMOND_HOE","IRON_HOE","GOLDEN_HOE","STICK"));
        TYPE_MAP.put("_hammer",     List.of("MACE","DIAMOND_AXE","IRON_AXE","NETHERITE_AXE"));
        TYPE_MAP.put("_greatsword", List.of("DIAMOND_SWORD","IRON_SWORD","NETHERITE_SWORD"));
        TYPE_MAP.put("_katana",     List.of("DIAMOND_SWORD","IRON_SWORD","NETHERITE_SWORD"));
        TYPE_MAP.put("_lance",      List.of("TRIDENT"));
        TYPE_MAP.put("_pike",       List.of("TRIDENT"));
    }

    private static final List<String> DEFAULT_EXCLUDED = List.of(
        "freeminecraftmodels", "elitemobs", "minecraft", "_iainternal"
    );

    // Pliki które IGNORUJEMY (ikony, stany animacji, fragmenty bossów)
    private static final List<String> IGNORE_SUFFIXES = List.of(
        "_icon", "_pulling_0", "_pulling_1", "_pulling_2",
        "_draw_start", "_draw_half", "_draw_full",
        "_charged", "_idle"
    );

    // Prefiks namespace → ludzka nazwa do wyświetlenia (domyślne; nadpisywane w scanner.namespace-display)
    private static final Map<String, String> NAMESPACE_DISPLAY = Map.of(
        "elitemobs", "EliteMobs",
        "freeminecraftmodels", "FMM"
    );

    private final SkinStudio plugin;
    private final Logger log;
    private final Set<String> excludedNamespaces;

    public ResourcePackScanner(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.excludedNamespaces = loadExcludedNamespaces();
    }

    /**
     * Skanuje ResourcePackManager/mixer/ i dodaje nowe skiny do config.yml.
     * Zwraca liczbę nowo dodanych skinów.
     */
    public int scan() {
        String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        File mixerDir = new File(plugin.getServer().getPluginsFolder(), mixerPath);

        if (!mixerDir.exists() || !mixerDir.isDirectory()) {
            log.warning("Nie znaleziono folderu " + mixerPath + "/");
            log.warning("Upewnij się że ResourcePackManager jest zainstalowany.");
            return -1;
        }

        File[] zips = mixerDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            log.warning("Brak plików ZIP w " + mixerPath + "/");
            return -1;
        }

        Map<String, SkinCandidate> found = new LinkedHashMap<>();

        for (File zip : zips) {
            log.info("Skanowanie: " + zip.getName());
            scanZip(zip, found);
        }

        if (found.isEmpty()) {
            log.info("Nie znaleziono żadnych skinów w resource packach.");
            return 0;
        }

        SkinConfig skinConfig = plugin.getSkinConfig();
        int added = 0;

        for (Map.Entry<String, SkinCandidate> entry : found.entrySet()) {
            String skinId = entry.getKey();
            SkinCandidate candidate = entry.getValue();

            if (skinConfig.getSkin(skinId) != null) {
                log.fine("Pominięto (już istnieje): " + skinId);
                continue;
            }

            String path = "skins." + skinId;
            plugin.getConfig().set(path + ".display-name", candidate.displayName);
            plugin.getConfig().set(path + ".item-model", candidate.itemModel);
            plugin.getConfig().set(path + ".equipment-asset", candidate.equipmentAsset);
            plugin.getConfig().set(path + ".item-types", candidate.itemTypes);

            added++;
            log.info("Dodano skin: " + skinId + " → " + candidate.itemModel);
        }

        if (added > 0) {
            plugin.saveConfig();
            log.info("Zapisano " + added + " nowych skinów do config.yml");
        }

        return added;
    }

    private void scanZip(File zipFile, Map<String, SkinCandidate> found) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".json")) continue;

                ParsedSkin parsed = parseEntry(name);
                if (parsed == null) continue;

                List<String> itemTypes = deduceItemTypes(name.toLowerCase());
                if (itemTypes.isEmpty()) continue;

                String equipmentAsset = "";
                if (parsed.isEquipment()) {
                    String tier = extractTier(name);
                    if (!tier.isEmpty()) {
                        equipmentAsset = parsed.namespace + ":" + tier;
                    }
                }

                String displayPrefix = resolveNamespaceDisplay(parsed.namespace);
                String humanName = toHumanName(parsed.humanNamePart);
                String displayName = "&8[&6" + displayPrefix + "&8] &fToken Skina: " + humanName;

                found.put(parsed.skinId, new SkinCandidate(displayName, parsed.itemModel, equipmentAsset, itemTypes));
            }
        } catch (IOException e) {
            log.warning("Błąd skanowania " + zipFile.getName() + ": " + e.getMessage());
        }
    }

    private ParsedSkin parseEntry(String path) {
        // EliteMobs: assets/elitemobs/items/gear/xxx.json
        if (path.matches(".*assets/[^/]+/items/gear/[^/]+\\.json")) {
            String[] parts = path.split("/");
            String namespace = parts[parts.length - 4];
            String fileName = parts[parts.length - 1].replace(".json", "");
            if (shouldIgnore(fileName)) return null;

            boolean isEquipment = fileName.contains("helmet") || fileName.contains("chestplate")
                || fileName.contains("leggings") || fileName.contains("boots");
            return new ParsedSkin(
                namespace,
                namespace + "_" + fileName,
                namespace + ":gear/" + fileName,
                isEquipment,
                fileName
            );
        }

        // FMM: assets/freeminecraftmodels/items/display/xxx.json
        if (path.matches(".*assets/freeminecraftmodels/items/display/[^/]+\\.json")) {
            String[] parts = path.split("/");
            String fileName = parts[parts.length - 1].replace(".json", "");
            if (shouldIgnore(fileName)) return null;

            return new ParsedSkin(
                "freeminecraftmodels",
                "fmm_" + fileName,
                "freeminecraftmodels:display/" + fileName,
                false,
                fileName
            );
        }

        // MC 1.21.4+ item models: assets/<namespace>/items/<id>.json
        Matcher generic = GENERIC_ITEM.matcher(path);
        if (!generic.find()) return null;

        String namespace = generic.group(1).toLowerCase(Locale.ROOT);
        if (excludedNamespaces.contains(namespace)) return null;

        String fileName = generic.group(2);
        if (shouldIgnore(fileName)) return null;

        String skinId = fileName.startsWith(namespace + "_")
            ? fileName
            : namespace + "_" + fileName;
        boolean isEquipment = fileName.contains("helmet") || fileName.contains("chestplate")
            || fileName.contains("leggings") || fileName.contains("boots");

        String humanPart = fileName.startsWith(namespace + "_")
            ? fileName.substring(namespace.length() + 1)
            : fileName;

        return new ParsedSkin(
            namespace,
            skinId,
            namespace + ":" + fileName,
            isEquipment,
            humanPart
        );
    }

    private Set<String> loadExcludedNamespaces() {
        List<String> fromConfig = plugin.getConfig().getStringList("scanner.excluded-namespaces");
        List<String> source = fromConfig.isEmpty() ? DEFAULT_EXCLUDED : fromConfig;
        Set<String> out = new HashSet<>();
        for (String ns : source) {
            out.add(ns.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private String resolveNamespaceDisplay(String namespace) {
        String custom = plugin.getConfig().getString("scanner.namespace-display." + namespace);
        if (custom != null && !custom.isBlank()) return custom;
        if (NAMESPACE_DISPLAY.containsKey(namespace)) return NAMESPACE_DISPLAY.get(namespace);
        return toHumanName(namespace.replace('_', ' '));
    }

    private boolean shouldIgnore(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : IGNORE_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    private List<String> deduceItemTypes(String fileName) {
        for (Map.Entry<String, List<String>> entry : TYPE_MAP.entrySet()) {
            if (fileName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private String extractTier(String fileName) {
        String[] tiers = {"bronze", "living", "corrupted", "palladium", "ultimatium",
                          "frost_palace", "primis", "dark_cathedral"};
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String tier : tiers) {
            if (lower.contains(tier)) return tier;
        }
        return "";
    }

    private String toHumanName(String id) {
        return Arrays.stream(id.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b)
            .orElse(id);
    }

    private record ParsedSkin(
        String namespace,
        String skinId,
        String itemModel,
        boolean isEquipment,
        String humanNamePart
    ) {}

    private record SkinCandidate(
        String displayName,
        String itemModel,
        String equipmentAsset,
        List<String> itemTypes
    ) {}
}
