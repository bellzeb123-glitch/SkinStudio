package pl.skinstudio.util;

import org.bukkit.Material;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.SkinConfig;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ResourcePackScanner {

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

    // Pliki które IGNORUJEMY (ikony, stany animacji, fragmenty bossów)
    private static final List<String> IGNORE_SUFFIXES = List.of(
        "_icon", "_pulling_0", "_pulling_1", "_pulling_2",
        "_draw_start", "_draw_half", "_draw_full",
        "_charged", "_idle"
    );

    // Prefiks namespace → ludzka nazwa do wyświetlenia
    private static final Map<String, String> NAMESPACE_DISPLAY = Map.of(
        "elitemobs", "EliteMobs",
        "freeminecraftmodels", "FMM"
    );

    private final SkinStudio plugin;
    private final Logger log;

    public ResourcePackScanner(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Skanuje ResourcePackManager/mixer/ i dodaje nowe skiny do config.yml.
     * Zwraca liczbę nowo dodanych skinów.
     */
    public int scan() {
        File mixerDir = new File(plugin.getServer().getPluginsFolder(),
            "ResourcePackManager/mixer");

        if (!mixerDir.exists() || !mixerDir.isDirectory()) {
            log.warning("Nie znaleziono folderu ResourcePackManager/mixer/");
            log.warning("Upewnij się że ResourcePackManager jest zainstalowany.");
            return -1;
        }

        File[] zips = mixerDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            log.warning("Brak plików ZIP w ResourcePackManager/mixer/");
            return -1;
        }

        // Zbierz wszystkie znalezione skiny
        Map<String, SkinCandidate> found = new LinkedHashMap<>();

        for (File zip : zips) {
            log.info("Skanowanie: " + zip.getName());
            scanZip(zip, found);
        }

        if (found.isEmpty()) {
            log.info("Nie znaleziono żadnych skinów w resource packach.");
            return 0;
        }

        // Dodaj tylko nowe (nie nadpisuj istniejących)
        SkinConfig skinConfig = plugin.getSkinConfig();
        int added = 0;

        for (Map.Entry<String, SkinCandidate> entry : found.entrySet()) {
            String skinId = entry.getKey();
            SkinCandidate candidate = entry.getValue();

            if (skinConfig.getSkin(skinId) != null) {
                log.fine("Pominięto (już istnieje): " + skinId);
                continue;
            }

            // Zapisz do config.yml
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

                // Szukaj plików items w folderze gear lub display
                if (!name.endsWith(".json")) continue;

                String skinId = null;
                String namespace = null;
                String modelPath = null;
                boolean isEquipment = false;

                // EliteMobs: assets/elitemobs/items/gear/xxx.json
                if (name.matches(".*assets/[^/]+/items/gear/[^/]+\\.json")) {
                    String[] parts = name.split("/");
                    namespace = parts[parts.length - 4]; // namespace przed /items/
                    String fileName = parts[parts.length - 1].replace(".json", "");

                    if (shouldIgnore(fileName)) continue;

                    skinId = namespace + "_" + fileName;
                    modelPath = namespace + ":gear/" + fileName;
                    isEquipment = fileName.contains("helmet") || fileName.contains("chestplate")
                        || fileName.contains("leggings") || fileName.contains("boots");
                }
                // FMM: assets/freeminecraftmodels/items/display/xxx.json
                else if (name.matches(".*assets/freeminecraftmodels/items/display/[^/]+\\.json")) {
                    String[] parts = name.split("/");
                    String fileName = parts[parts.length - 1].replace(".json", "");

                    if (shouldIgnore(fileName)) continue;

                    namespace = "freeminecraftmodels";
                    skinId = "fmm_" + fileName;
                    modelPath = "freeminecraftmodels:display/" + fileName;
                }

                if (skinId == null || modelPath == null) continue;

                // Dedukuj item-types z nazwy pliku
                List<String> itemTypes = deduceItemTypes(name.toLowerCase());
                if (itemTypes.isEmpty()) continue; // Nieznany typ — pomiń

                // Dedukuj equipment-asset dla zbroi
                String equipmentAsset = "";
                if (isEquipment && namespace != null) {
                    // Wyciągnij tier z nazwy (np. "bronze" z "bronze_chestplate")
                    String tier = extractTier(name);
                    if (!tier.isEmpty()) {
                        equipmentAsset = namespace + ":" + tier;
                    }
                }

                // Generuj display name
                String displayPrefix = NAMESPACE_DISPLAY.getOrDefault(namespace, namespace);
                String humanName = toHumanName(skinId.replaceFirst("^[^_]+_", ""));
                String displayName = "&8[&6" + displayPrefix + "&8] &fToken Skina: " + humanName;

                found.put(skinId, new SkinCandidate(displayName, modelPath, equipmentAsset, itemTypes));
            }
        } catch (IOException e) {
            log.warning("Błąd skanowania " + zipFile.getName() + ": " + e.getMessage());
        }
    }

    private boolean shouldIgnore(String fileName) {
        String lower = fileName.toLowerCase();
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
        String lower = fileName.toLowerCase();
        for (String tier : tiers) {
            if (lower.contains(tier)) return tier;
        }
        return "";
    }

    private String toHumanName(String id) {
        // bronze_sword → Bronze Sword
        return Arrays.stream(id.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b)
            .orElse(id);
    }

    // ── Model danych ─────────────────────────────────────────

    private record SkinCandidate(
        String displayName,
        String itemModel,
        String equipmentAsset,
        List<String> itemTypes
    ) {}
}
