package pl.skinstudio.converter;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.AssetResolver;
import pl.skinstudio.pack.PackIndex;
import pl.skinstudio.util.AtlasPatchUtil;
import pl.skinstudio.util.ResourcePackScanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Importuje skiny z packa Oraxena (master: item_model + assety w {@code Oraxen/pack/}).
 * SkinStudio nie duplikuje assetów — zapisuje {@code item-model: namespace:id} z packa Oraxena.
 */
public final class OraxenImporter {

    private static final Set<String> DEFAULT_SKIP = Set.of(
        "minecraft", "oraxen", "_iainternal", "freeminecraftmodels", "elitemobs"
    );

    private final SkinStudio plugin;
    private final Logger log;

    public OraxenImporter(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record ImportResult(
        int added,
        int updated,
        int skipped,
        int textureWarnings,
        List<String> skinIds,
        List<String> warnings,
        String packSource
    ) {}

    /** Skanuje pack Oraxena i zapisuje / nadpisuje wpisy w {@code skins.*}. */
    public ImportResult importFromOraxen(String namespaceFilter, boolean overwrite) {
        File packZip = resolveOraxenPack();
        if (packZip == null) {
            return new ImportResult(0, 0, 0, 0, List.of(), List.of(
                "Brak packa Oraxena. Uruchom serwer z Oraxen lub /o reload all, potem spróbuj ponownie."
            ), "");
        }

        ResourcePackScanner scanner = new ResourcePackScanner(plugin);
        Map<String, ResourcePackScanner.SkinCandidate> found = discoverFromPack(packZip, namespaceFilter);
        if (found.isEmpty()) {
            return new ImportResult(0, 0, 0, 0, List.of(),
                List.of("Nie znaleziono skinów w " + packZip.getName()
                    + (namespaceFilter != null && !namespaceFilter.isBlank()
                    ? " (filtr: " + namespaceFilter + ")" : "")),
                packZip.getName());
        }

        enrichMaterialsFromOraxenItems(found);

        int added = 0;
        int updated = 0;
        int skipped = 0;
        int textureWarnings = 0;
        List<String> skinIds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        PackIndex index = PackIndex.of(log, packZip);
        AssetResolver resolver = new AssetResolver(index);

        for (var entry : found.entrySet()) {
            String skinId = entry.getKey();
            ResourcePackScanner.SkinCandidate c = entry.getValue();

            AssetResolver.Resolution res = c.sourceModel() != null && !c.sourceModel().isBlank()
                ? resolver.resolveModelReference(c.sourceModel())
                : resolver.resolve(c.itemModel());
            if (!res.complete()) {
                warnings.add(skinId + ": brakujące assety — " + String.join(", ", res.missing()));
                skipped++;
                continue;
            }

            int badPng = countPlaceholderTextures(res.requiredPaths(), index);
            if (badPng > 0) {
                textureWarnings += badPng;
                warnings.add(skinId + ": " + badPng + " PNG wygląda na placeholder (szachownica / za mały plik)");
            }

            boolean exists = plugin.getSkinConfig().getSkin(skinId) != null;
            if (exists && !overwrite) {
                skipped++;
                continue;
            }

            persistOraxenSkin(skinId, c);
            skinIds.add(skinId);
            if (exists) updated++;
            else added++;

            scanner.ensureTierForNamespace(c.namespace());
        }

        if (added > 0 || updated > 0) {
            plugin.saveConfig();
            plugin.reloadSkinCatalog();
        }

        log.info("Oraxen import: +" + added + " ~" + updated + " pominięto " + skipped
            + " z " + packZip.getName());
        return new ImportResult(added, updated, skipped, textureWarnings, skinIds, warnings, packZip.getName());
    }

    /** Kopiuje assety namespace ze źródła (SkinStudio/source lub Oraxen/pack) do SkinStudio/pack/pack.zip (RPM czyta SkinStudio). */
    public int syncOraxenAssetsToSkinStudioPack(String namespace) {
        File oraxenPack = resolveOraxenPack();
        if (oraxenPack == null || !oraxenPack.isFile()) return 0;

        String prefix = "assets/" + (namespace == null || namespace.isBlank() || "all".equalsIgnoreCase(namespace)
            ? "" : namespace.toLowerCase(Locale.ROOT) + "/");

        File skinPack = new File(plugin.getDataFolder(), "pack/pack.zip");
        skinPack.getParentFile().mkdirs();

        Map<String, byte[]> merged = new LinkedHashMap<>();
        try {
            ingestZip(oraxenPack, prefix, merged);
            if (skinPack.isFile()) {
                ingestZip(skinPack, "", merged);
            }
        } catch (IOException ex) {
            log.warning("syncOraxenAssetsToSkinStudioPack read: " + ex.getMessage());
            return 0;
        }

        AtlasPatchUtil.patchPackAtlases(merged);

        int format = plugin.getConfig().getInt("scanner.pack-format", 84);
        merged.put("pack.mcmeta", ("{\"pack\":{\"pack_format\":" + format
            + ",\"description\":\"SkinStudio + Oraxen\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        File temp = new File(skinPack.getParentFile(), skinPack.getName() + ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            for (var e : merged.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException ex) {
            log.warning("syncOraxenAssetsToSkinStudioPack: " + ex.getMessage());
            if (temp.exists()) temp.delete();
            return 0;
        }
        try {
            Files.deleteIfExists(skinPack.toPath());
            Files.move(temp.toPath(), skinPack.toPath());
        } catch (IOException ex) {
            log.warning("syncOraxenAssetsToSkinStudioPack move: " + ex.getMessage());
            if (temp.exists()) temp.delete();
            return 0;
        }

        int copied = (int) merged.keySet().stream().filter(k -> k.startsWith("assets/")).count();
        log.info("Skopiowano assety źródłowe → SkinStudio/pack/pack.zip (" + copied + " plików)");
        return copied;
    }

    private static void ingestZip(File zipFile, String prefixFilter, Map<String, byte[]> out) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("./")) name = name.substring(2);
                if (!prefixFilter.isEmpty() && !name.startsWith(prefixFilter)) continue;
                if ("pack.mcmeta".equalsIgnoreCase(name)) continue;
                out.put(name, in.readAllBytes());
            }
        }
    }

    /** @deprecated używaj {@link #syncOraxenAssetsToSkinStudioPack} */
    public int stripNamespaceFromSkinStudioPack(String namespace) {
        File pack = new File(plugin.getDataFolder(), "pack/pack.zip");
        if (!pack.isFile() || namespace == null || namespace.isBlank()) return 0;

        String prefix = "assets/" + namespace.toLowerCase(Locale.ROOT) + "/";
        String skinstudioPrefix = "assets/skinstudio/items/" + namespace.toLowerCase(Locale.ROOT) + "_";

        int removed = 0;
        File temp = new File(pack.getParentFile(), pack.getName() + ".tmp");
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(pack));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith(prefix) || name.startsWith(skinstudioPrefix)) {
                    removed++;
                    continue;
                }
                out.putNextEntry(new ZipEntry(name));
                in.transferTo(out);
                out.closeEntry();
            }
        } catch (IOException e) {
            log.warning("stripNamespaceFromSkinStudioPack: " + e.getMessage());
            if (temp.exists()) temp.delete();
            return 0;
        }

        if (removed == 0) {
            temp.delete();
            return 0;
        }
        try {
            Files.delete(pack.toPath());
            Files.move(temp.toPath(), pack.toPath());
        } catch (IOException e) {
            log.warning("stripNamespaceFromSkinStudioPack move: " + e.getMessage());
            return 0;
        }
        log.info("Usunięto " + removed + " wpisów namespace '" + namespace + "' z SkinStudio/pack/pack.zip");
        return removed;
    }

    private void persistOraxenSkin(String skinId, ResourcePackScanner.SkinCandidate c) {
        String path = "skins." + skinId;
        plugin.getConfig().set(path + ".display-name", c.displayName());
        plugin.getConfig().set(path + ".source-item-model", c.itemModel());
        plugin.getConfig().set(path + ".item-model", c.itemModel());
        plugin.getConfig().set(path + ".source-model", c.sourceModel() == null ? "" : c.sourceModel());
        plugin.getConfig().set(path + ".equipment-asset", c.equipmentAsset() == null ? "" : c.equipmentAsset());
        plugin.getConfig().set(path + ".item-types", c.itemTypes());
        plugin.getConfig().set(path + ".category", c.category().configKey());
    }

    private Map<String, ResourcePackScanner.SkinCandidate> discoverFromPack(File packZip, String namespaceFilter) {
        // Pełne skanowanie przez tymczasowe wyłączenie bundle-only nie jest potrzebne — używamy PackIndex + logiki scannera
        ResourcePackScanner scanner = new ResourcePackScanner(plugin);
        return scanner.discoverCandidates(new File[] { packZip }, shouldInclude(namespaceFilter));
    }

    private java.util.function.Predicate<String> shouldInclude(String namespaceFilter) {
        if (namespaceFilter == null || namespaceFilter.isBlank() || "all".equalsIgnoreCase(namespaceFilter)) {
            return ns -> !DEFAULT_SKIP.contains(ns.toLowerCase(Locale.ROOT));
        }
        String want = namespaceFilter.toLowerCase(Locale.ROOT);
        return ns -> want.equals(ns.toLowerCase(Locale.ROOT));
    }

    private void enrichMaterialsFromOraxenItems(Map<String, ResourcePackScanner.SkinCandidate> found) {
        File itemsDir = new File(Bukkit.getServer().getPluginsFolder(), "Oraxen/items");
        if (!itemsDir.isDirectory()) return;

        File[] ymls = itemsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".yml"));
        if (ymls == null) return;

        for (File yml : ymls) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yml);
            for (String itemId : cfg.getKeys(false)) {
                ConfigurationSection sec = cfg.getConfigurationSection(itemId);
                if (sec == null) continue;

                String itemModel = sec.getString("components.item_model", "");
                if (itemModel.isEmpty()) continue;

                String[] parts = itemModel.split(":", 2);
                if (parts.length != 2) continue;
                String ns = parts[0];
                String itemPath = parts[1];
                String skinId = itemPath.startsWith(ns + "_")
                    ? itemPath
                    : ns + "_" + itemPath.replace('/', '_');

                ResourcePackScanner.SkinCandidate existing = found.get(skinId);
                if (existing == null) continue;

                String material = sec.getString("material", "");
                if (material.isEmpty()) continue;
                // item-types z Oraxen yaml — nadpisz tylko gdy scanner zgadł źle
                found.put(skinId, existing.withItemTypes(List.of(material)));
            }
        }
    }

    private static int countPlaceholderTextures(Set<String> paths, PackIndex index) {
        int bad = 0;
        for (String path : paths) {
            if (!path.endsWith(".png")) continue;
            byte[] data = index.get(path);
            if (data == null) continue;
            if (data.length <= 900) bad++; // vanilla missing texture ~770B; ostrzeżenie
        }
        return bad;
    }

    /** Namespace'y wykryte w źródłach (SkinStudio/source/assets + Oraxen/pack/assets) — do tab-completion. */
    public java.util.List<String> listSourceNamespaces() {
        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        collectNamespaces(new File(sourceDir(), "assets"), namespaces);
        collectNamespaces(new File(Bukkit.getServer().getPluginsFolder(), "Oraxen/pack/assets"), namespaces);
        namespaces.removeAll(DEFAULT_SKIP);
        return new ArrayList<>(namespaces);
    }

    private static void collectNamespaces(File assetsDir, java.util.Set<String> out) {
        if (!assetsDir.isDirectory()) return;
        File[] dirs = assetsDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) out.add(d.getName().toLowerCase(Locale.ROOT));
    }

    /** Własny folder źródłowy SkinStudio — auto-tworzony przy starcie. Tu admin wrzuca assets/<ns>/... */
    public File sourceDir() {
        String folder = plugin.getConfig().getString("converter.source-folder", "source");
        return new File(plugin.getDataFolder(), folder);
    }

    /**
     * Zakłada folder źródłowy SkinStudio z krótkim README, jeśli go nie ma.
     * Wołane przy starcie pluginu, więc admin nie musi ręcznie tworzyć ścieżek.
     */
    public void ensureSourceFolder() {
        File src = sourceDir();
        File assets = new File(src, "assets");
        assets.mkdirs();
        File readme = new File(src, "README.txt");
        if (!readme.isFile()) {
            String text = "SkinStudio — folder zrodlowy assetow\n"
                + "====================================\n\n"
                + "Wrzuc tutaj assety w formacie resource packa, np.:\n"
                + "  source/assets/<namespace>/items/<id>.json\n"
                + "  source/assets/<namespace>/models/<namespace>/<model>.json\n"
                + "  source/assets/<namespace>/textures/<namespace>/<tekstura>.png\n\n"
                + "Mozesz tez wrzucic gotowy pack:  source/pack.zip\n\n"
                + "Potem w grze:  /skintoken importoraxen <namespace>\n"
                + "SkinStudio zarejestruje skiny, skopiuje assety do swojego packa\n"
                + "i automatycznie dopisze atlas (items.json). Plugin Oraxen NIE jest wymagany.\n";
            try {
                Files.writeString(readme.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // README to wygoda — brak nie blokuje importu
            }
        }
    }

    /**
     * Rozwiązuje źródło assetów w kolejności:
     *   1. SkinStudio/source/pack.zip
     *   2. SkinStudio/source/assets/  → spakowane do tymczasowego zip
     *   3. Oraxen/pack/pack.zip  (gdy ktoś realnie używa Oraxena)
     *   4. Oraxen/pack/assets/   → spakowane
     *   5. fallback dev (pulpit)
     */
    private File resolveOraxenPack() {
        File plugins = Bukkit.getServer().getPluginsFolder();

        File ownPackZip = new File(sourceDir(), "pack.zip");
        if (ownPackZip.isFile() && ownPackZip.length() > 0) return ownPackZip;

        File ownZip = zipAssetsRootIfPresent(sourceDir(), "skinstudio-source-temp.zip");
        if (ownZip != null) return ownZip;

        File oraxenPack = new File(plugins, "Oraxen/pack/pack.zip");
        if (oraxenPack.isFile() && oraxenPack.length() > 0) return oraxenPack;

        File oraxenZip = zipAssetsRootIfPresent(new File(plugins, "Oraxen/pack"), "oraxen-scan-temp.zip");
        if (oraxenZip != null) return oraxenZip;

        // Fallback: sklep na pulpicie (dev / pierwszy setup)
        File desktop = new File("C:/Users/user/Desktop/textures/Dark_Queen.zip");
        if (desktop.isFile()) return desktop;
        return null;
    }

    /** Pakuje {@code <root>/assets/...} do tymczasowego zip w staging. Zwraca null gdy brak assets/. */
    private File zipAssetsRootIfPresent(File assetsRoot, String tempName) {
        File assetsDir = new File(assetsRoot, "assets");
        if (!assetsDir.isDirectory()) return null;

        File temp = new File(plugin.getDataFolder(), "staging/" + tempName);
        temp.getParentFile().mkdirs();
        try {
            zipFolder(assetsRoot, temp);
            return temp.isFile() && temp.length() > 0 ? temp : null;
        } catch (IOException e) {
            log.warning("OraxenImporter temp zip (" + tempName + "): " + e.getMessage());
            return null;
        }
    }

    private static void zipFolder(File root, File outZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
            zipDir(root, root, zos);
        }
    }

    private static void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
            } else {
                String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }
    }
}
