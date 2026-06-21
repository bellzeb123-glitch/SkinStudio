package pl.skinstudio.util;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.converter.SkinCategoryDetector;
import pl.skinstudio.model.SkinCategory;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ResourcePackScanner {

    private static final Pattern GENERIC_ITEM =
        Pattern.compile("assets/([^/]+)/items/(?!gear/)(?!display/)(.+?)\\.json$", Pattern.CASE_INSENSITIVE);

    private static final Pattern EQUIPMENT_ASSET =
        Pattern.compile("assets/([^/]+)/equipment/([^/]+)\\.json$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MODEL_ONLY =
        Pattern.compile("assets/([^/]+)/models/(.+?)\\.json$", Pattern.CASE_INSENSITIVE);

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
        "freeminecraftmodels", "FMM",
        "itemsadder", "ItemAdder"
    );

    private final SkinStudio plugin;
    private final Logger log;
    private final Set<String> excludedNamespaces;

    public ResourcePackScanner(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.excludedNamespaces = loadExcludedNamespaces();
    }

    /** Skanuje wskazane ZIP-y (konwerter inbox) i zapisuje nowe skiny do config. */
    public ScanResult scanFiles(File[] zipFiles) {
        if (zipFiles == null || zipFiles.length == 0) {
            return new ScanResult(0, 0);
        }
        int packsNormalized = normalizePacks(zipFiles, false).changed();
        Map<String, SkinCandidate> found = collectFromZips(zipFiles);
        int added = persistCandidates(found);
        return new ScanResult(added, packsNormalized);
    }

    /** Odkrywa kandydatów na skiny bez zapisu do config (Oraxen import). */
    public Map<String, SkinCandidate> discoverCandidates(File[] zipFiles, Predicate<String> namespaceFilter) {
        if (zipFiles == null || zipFiles.length == 0) return Map.of();
        Map<String, SkinCandidate> found = new LinkedHashMap<>();
        Set<String> equipmentAssets = new HashSet<>();
        for (File zip : zipFiles) {
            if (zip == null || !zip.isFile()) continue;
            scanZipFiltered(zip, found, equipmentAssets, namespaceFilter);
        }
        return found;
    }

    private Map<String, SkinCandidate> collectFromZips(File[] zips) {
        Map<String, SkinCandidate> found = new LinkedHashMap<>();
        Set<String> equipmentAssets = new HashSet<>();
        for (File zip : zips) {
            log.info("Skanowanie: " + zip.getName());
            scanZip(zip, found, equipmentAssets);
        }
        return found;
    }

    private int persistCandidates(Map<String, SkinCandidate> found) {
        if (found.isEmpty()) return 0;

        SkinConfig skinConfig = plugin.getSkinConfig();
        int added = 0;
        Set<String> namespaces = new HashSet<>();

        for (Map.Entry<String, SkinCandidate> entry : found.entrySet()) {
            String skinId = entry.getKey();
            SkinCandidate candidate = entry.getValue();
            namespaces.add(candidate.namespace);

            if (skinConfig.getSkin(skinId) != null) {
                log.fine("Pominięto (już istnieje): " + skinId);
                continue;
            }
            if (hasItemModel(skinConfig, candidate.itemModel)) {
                log.fine("Pominięto (ten sam item-model): " + candidate.itemModel);
                continue;
            }

            String path = "skins." + skinId;
            plugin.getConfig().set(path + ".display-name", candidate.displayName);
            plugin.getConfig().set(path + ".item-model", candidate.itemModel);
            if (candidate.sourceModel != null && !candidate.sourceModel.isBlank()) {
                plugin.getConfig().set(path + ".source-model", candidate.sourceModel);
            }
            plugin.getConfig().set(path + ".equipment-asset", candidate.equipmentAsset);
            plugin.getConfig().set(path + ".item-types", candidate.itemTypes);
            plugin.getConfig().set(path + ".category", candidate.category.configKey());

            added++;
            log.info("Dodano skin: " + skinId + " → " + candidate.itemModel
                + " [" + candidate.category + "]");
        }

        if (added > 0) {
            if (plugin.getConfig().getBoolean("converter.auto-tier", true)) {
                for (String ns : namespaces) ensureTierForNamespace(ns);
            }
            plugin.saveConfig();
            log.info("Zapisano " + added + " nowych skinów do config.yml");
        }
        return added;
    }

    /** Dodaje tier w config gdy namespace packa nie ma jeszcze zakładki. */
    public void ensureTierForNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return;
        String tierId = namespace.toLowerCase(Locale.ROOT).replace('-', '_');
        if (plugin.getConfig().isConfigurationSection("tiers." + tierId)) return;

        String display = plugin.getConfig().getString("scanner.namespace-display." + tierId);
        if (display == null || display.isBlank()) {
            display = toHumanName(tierId.replace('_', ' '));
        }
        plugin.getConfig().set("tiers." + tierId + ".display-name", "&6" + display);
        plugin.getConfig().set("tiers." + tierId + ".material", defaultTierMaterial(tierId));
        log.info("Auto-tier: dodano zakładkę '" + tierId + "'");
    }

    private static String defaultTierMaterial(String tierId) {
        if (tierId.contains("mount") || tierId.contains("horse")) return "SADDLE";
        if (tierId.contains("dark") || tierId.contains("corrupt")) return "PURPLE_STAINED_GLASS_PANE";
        return "LIGHT_BLUE_STAINED_GLASS_PANE";
    }

    /**
     * Normalizuje packi w mixerze (ścieżki, pack.mcmeta), skanuje skiny i zapisuje config.
     */
    public ScanResult scan() {
        if (plugin.getConfig().getBoolean("converter.bundle-only", true)) {
            log.warning("scan() wyłączone (bundle-only). Użyj: wrzuć folder z skin.yml do inbox/ → /skintoken convert");
            return new ScanResult(0, 0);
        }
        File mixerDir = resolveMixerDir();
        if (mixerDir == null) {
            log.warning("Upewnij się że ResourcePackManager jest zainstalowany.");
            return ScanResult.ERROR;
        }

        File[] zips = listMixerZips(mixerDir);
        if (zips == null) return ScanResult.ERROR;

        int packsNormalized = normalizePacks(zips, false).changed();

        Map<String, SkinCandidate> found = collectFromZips(zips);
        if (found.isEmpty()) {
            log.info("Nie znaleziono żadnych skinów w resource packach.");
            return new ScanResult(0, packsNormalized);
        }

        int added = persistCandidates(found);
        return new ScanResult(added, packsNormalized);
    }

    /** Tylko normalizacja ZIP-ów w mixerze (bez skanowania skinów). */
    public NormalizeReport normalizeMixerPacks() {
        if (plugin.getConfig().getBoolean("converter.bundle-only", true)) {
            log.warning("normalize() wyłączone (bundle-only). Nie modyfikuj mixer RPM.");
            return NormalizeReport.of(0, List.of(), List.of());
        }
        File mixerDir = resolveMixerDir();
        if (mixerDir == null) return NormalizeReport.ERROR;
        File[] zips = listMixerZips(mixerDir);
        if (zips == null) return NormalizeReport.ERROR;

        String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        PendingPackApplier.ApplyResult applied = PendingPackApplier.applyAll(
            plugin.getServer().getPluginsFolder(), mixerPath, log);

        NormalizeReport report = normalizePacks(zips, true);
        if (!report.pendingFiles().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingPackApplier.ApplyResult retry = PendingPackApplier.applyAll(
                    plugin.getServer().getPluginsFolder(), mixerPath, log);
                if (retry.applied() > 0) {
                    RpmBridge.reloadMergedPack(plugin);
                }
            }, 60L);
        }
        if (applied.applied() > 0) {
            report = report.withAppliedPending(applied.applied());
        }
        return report;
    }

    private File resolveMixerDir() {
        String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        File mixerDir = new File(plugin.getServer().getPluginsFolder(), mixerPath);
        if (!mixerDir.exists() || !mixerDir.isDirectory()) {
            log.warning("Nie znaleziono folderu " + mixerPath + "/");
            return null;
        }
        return mixerDir;
    }

    private File[] listMixerZips(File mixerDir) {
        File[] zips = mixerDir.listFiles((dir, name) ->
            name.toLowerCase(Locale.ROOT).endsWith(".zip")
                && !name.toLowerCase(Locale.ROOT).endsWith(".bak"));
        if (zips == null || zips.length == 0) {
            log.warning("Brak plików ZIP w " + mixerDir.getPath());
            return null;
        }
        return zips;
    }

    private NormalizeReport normalizePacks(File[] zips, boolean force) {
        if (!force && !plugin.getConfig().getBoolean("scanner.auto-normalize", true)) {
            return NormalizeReport.empty();
        }
        boolean backup = plugin.getConfig().getBoolean("scanner.normalize-backup", true);
        int packFormat = plugin.getConfig().getInt("scanner.pack-format", 84);
        int count = 0;
        List<String> errors = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (File zip : zips) {
            ResourcePackNormalizer.PackStatus before = ResourcePackNormalizer.audit(zip, packFormat);
            ResourcePackNormalizer.Result r =
                ResourcePackNormalizer.normalize(zip, log, backup, packFormat);

            if (r.changed()) {
                count++;
                continue;
            }
            if (r.error() != null) {
                errors.add(zip.getName() + ": " + r.error());
                if (r.error().contains(".skinstudio-fixed.zip")) {
                    pending.add(zip.getName() + ".skinstudio-fixed.zip");
                }
                continue;
            }
            if (before != ResourcePackNormalizer.PackStatus.OK) {
                errors.add(zip.getName() + ": wymaga naprawy (" + before + ") ale zapis się nie udał");
            }
        }

        if (count > 0) {
            log.info("Znormalizowano " + count + " pack(ów) — RPM zostanie przeładowany automatycznie (fallback: /resourcepackmanager reload).");
        }
        return NormalizeReport.of(count, errors, pending);
    }

    private void scanZip(File zipFile, Map<String, SkinCandidate> found, Set<String> equipmentAssets) {
        scanZipFiltered(zipFile, found, equipmentAssets, ns -> !excludedNamespaces.contains(ns));
    }

    private void scanZipFiltered(File zipFile, Map<String, SkinCandidate> found,
                                   Set<String> equipmentAssets, Predicate<String> namespaceFilter) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = ResourcePackNormalizer.normalizePath(entry.getName());

                if (!name.endsWith(".json")) continue;

                Matcher equip = EQUIPMENT_ASSET.matcher(name);
                if (equip.find()) {
                    String ns = equip.group(1).toLowerCase(Locale.ROOT);
                    if (namespaceFilter.test(ns)) {
                        equipmentAssets.add(ns + ":" + equip.group(2));
                    }
                    continue;
                }

                ParsedSkin parsed = parseEntry(name);
                if (parsed == null) {
                    parsed = parseModelOnlyEntry(name);
                }
                if (parsed == null) continue;
                if (!namespaceFilter.test(parsed.namespace)) continue;

                SkinCategoryDetector.Detection detection =
                    SkinCategoryDetector.detect(name, parsed.skinId, parsed.isEquipment);
                List<String> itemTypes = detection.itemTypes();
                if (itemTypes.isEmpty()) {
                    itemTypes = SkinCategoryDetector.deduceItemTypes(parsed.skinId);
                }
                if (itemTypes.isEmpty()) continue;

                String equipmentAsset = resolveEquipmentAsset(parsed, name, equipmentAssets);

                String displayPrefix = resolveDisplayPrefix(parsed, name);
                String humanName = toHumanName(parsed.humanNamePart);
                String categoryLabel = detection.category() == SkinCategory.MOUNT ? "Mount" : "Token Skina";
                String displayName = "&8[&6" + displayPrefix + "&8] &f" + categoryLabel + ": " + humanName;

                found.put(parsed.skinId, new SkinCandidate(
                    parsed.namespace,
                    displayName,
                    parsed.itemModel,
                    parsed.sourceModel,
                    equipmentAsset,
                    itemTypes,
                    detection.category()
                ));
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
                fileName,
                namespace + ":gear/" + fileName,
                "",
                isEquipment,
                humanNameFromGear(fileName)
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
                "",
                false,
                fileName
            );
        }

        // MC 1.21.4+ item models (ItemAdder modern, Dark Queen, custom packs):
        // assets/<namespace>/items/<path>.json  — supports nested folders under items/
        Matcher generic = GENERIC_ITEM.matcher(path);
        if (!generic.find()) return null;

        String namespace = generic.group(1).toLowerCase(Locale.ROOT);
        if (excludedNamespaces.contains(namespace)) return null;

        String itemPath = generic.group(2).replace('\\', '/');
        if (itemPath.contains("/")) {
            // Skip IA block/furniture categories — not wearable gear
            String firstSegment = itemPath.substring(0, itemPath.indexOf('/')).toLowerCase(Locale.ROOT);
            if (IA_SKIP_FOLDERS.contains(firstSegment)) return null;
        }

        String fileName = itemPath.contains("/")
            ? itemPath.substring(itemPath.lastIndexOf('/') + 1)
            : itemPath;
        if (shouldIgnore(fileName)) return null;

        String skinId = toSkinId(namespace, itemPath);
        boolean isEquipment = isArmorPiece(fileName);

        String humanPart = fileName.startsWith(namespace + "_")
            ? fileName.substring(namespace.length() + 1)
            : fileName;

        return new ParsedSkin(
            namespace,
            skinId,
            namespace + ":" + itemPath,
            "",
            isEquipment,
            humanPart
        );
    }

    /** Packi kupowane często mają tylko assets/<ns>/models/*.json + textures/*.png, bez assets/<ns>/items/*.json. */
    private ParsedSkin parseModelOnlyEntry(String path) {
        Matcher model = MODEL_ONLY.matcher(path);
        if (!model.find()) return null;

        String namespace = model.group(1).toLowerCase(Locale.ROOT);
        if (excludedNamespaces.contains(namespace)) return null;

        String modelPath = model.group(2).replace('\\', '/');
        if (modelPath.contains("/")) {
            String firstSegment = modelPath.substring(0, modelPath.indexOf('/')).toLowerCase(Locale.ROOT);
            if (IA_SKIP_FOLDERS.contains(firstSegment)) return null;
        }

        String fileName = modelPath.contains("/")
            ? modelPath.substring(modelPath.lastIndexOf('/') + 1)
            : modelPath;
        if (shouldIgnore(fileName)) return null;

        SkinCategoryDetector.Detection detection =
            SkinCategoryDetector.detect(path, fileName, isArmorPiece(fileName));
        if (detection.itemTypes().isEmpty()) return null;

        String skinId = toSkinId(namespace, modelPath);
        boolean isEquipment = isArmorPiece(fileName);
        String humanPart = fileName.startsWith(namespace + "_")
            ? fileName.substring(namespace.length() + 1)
            : fileName;

        return new ParsedSkin(
            namespace,
            skinId,
            "skinstudio:" + skinId,
            namespace + ":" + modelPath,
            isEquipment,
            humanPart
        );
    }

    /** ItemAdder / third-party folders under items/ that are not gear skins. */
    private static final Set<String> IA_SKIP_FOLDERS = Set.of(
        "blocks", "block", "furniture", "font", "interface", "huds", "guis", "gui"
    );

    private Set<String> loadExcludedNamespaces() {
        List<String> fromConfig = plugin.getConfig().getStringList("scanner.excluded-namespaces");
        List<String> source = fromConfig.isEmpty() ? DEFAULT_EXCLUDED : fromConfig;
        Set<String> out = new HashSet<>();
        for (String ns : source) {
            out.add(ns.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private String resolveDisplayPrefix(ParsedSkin parsed, String path) {
        String tier = extractTier(path);
        if (!tier.isEmpty() && "elitemobs".equals(parsed.namespace)) {
            return toHumanName(tier.replace('_', ' '));
        }
        return resolveNamespaceDisplay(parsed.namespace);
    }

    private static String humanNameFromGear(String fileName) {
        for (String tier : new String[]{"bronze", "living", "corrupted", "palladium", "ultimatium",
            "frost_palace", "primis", "dark_cathedral"}) {
            String prefix = tier + "_";
            if (fileName.startsWith(prefix)) {
                return fileName.substring(prefix.length());
            }
        }
        return fileName;
    }

    private boolean hasItemModel(SkinConfig skinConfig, String itemModel) {
        if (itemModel == null || itemModel.isEmpty()) return false;
        for (var skin : skinConfig.getAllSkins().values()) {
            if (itemModel.equals(skin.getItemModel())) return true;
        }
        return false;
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
        return SkinCategoryDetector.deduceItemTypes(fileName);
    }

    private String resolveEquipmentAsset(ParsedSkin parsed, String path, Set<String> equipmentAssets) {
        if (!parsed.isEquipment()) return "";

        String tier = extractTier(path);
        if (!tier.isEmpty() && "elitemobs".equals(parsed.namespace)) {
            return parsed.namespace + ":" + tier;
        }

        String setName = extractEquipmentSet(parsed.humanNamePart);
        if (setName.isEmpty()) return "";

        String candidate = parsed.namespace + ":" + setName;
        if (equipmentAssets.contains(candidate)) return candidate;

        // ItemAdder / custom: equipment file may omit suffix (ruby vs ruby_armor)
        for (String asset : equipmentAssets) {
            if (!asset.startsWith(parsed.namespace + ":")) continue;
            String id = asset.substring(parsed.namespace.length() + 1);
            if (id.equals(setName) || setName.startsWith(id + "_") || id.startsWith(setName)) {
                return asset;
            }
        }
        return candidate;
    }

    private static String toSkinId(String namespace, String itemPath) {
        String flat = itemPath.replace('/', '_');
        return flat.startsWith(namespace + "_") ? flat : namespace + "_" + flat;
    }

    private static boolean isArmorPiece(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.contains("helmet") || lower.contains("chestplate")
            || lower.contains("leggings") || lower.contains("boots");
    }

    private String extractEquipmentSet(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("_helmet", "_chestplate", "_leggings", "_boots")) {
            if (lower.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return "";
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
        String sourceModel,
        boolean isEquipment,
        String humanNamePart
    ) {}

    public record SkinCandidate(
        String namespace,
        String displayName,
        String itemModel,
        String sourceModel,
        String equipmentAsset,
        List<String> itemTypes,
        SkinCategory category
    ) {
        public SkinCandidate withItemTypes(List<String> types) {
            return new SkinCandidate(namespace, displayName, itemModel, sourceModel,
                equipmentAsset, types, category);
        }
    }
}
