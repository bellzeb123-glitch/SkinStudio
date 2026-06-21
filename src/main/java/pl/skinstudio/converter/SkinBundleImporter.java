package pl.skinstudio.converter;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.model.SkinCategory;
import pl.skinstudio.pack.AssetResolver;
import pl.skinstudio.pack.PackIndex;
import pl.skinstudio.util.ResourcePackScanner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Importuje folder Skin Bundle ({@code skin.yml} + {@code assets/}). */
public final class SkinBundleImporter {

    private final SkinStudio plugin;
    private final Logger log;

    public SkinBundleImporter(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public static boolean isBundleFolder(File dir) {
        return dir.isDirectory() && new File(dir, "skin.yml").isFile();
    }

    public SkinConverterResult importBundle(File bundleDir) {
        String name = bundleDir.getName();
        try {
            SkinBundleManifest manifest = SkinBundleManifest.load(new File(bundleDir, "skin.yml"));
            validateId(name, manifest.id());

            File bundleZip = new File(stagingDir(), manifest.id() + ".bundle.zip");
            PackFolderUtil.zipDirectory(bundleDir, bundleZip);

            PackIndex index = PackIndex.of(log, new File[] { bundleZip });
            AssetResolver resolver = new AssetResolver(index);

            AssetResolver.Resolution res = manifest.sourceModel() != null
                ? resolver.resolveModelReference(manifest.sourceModel())
                : resolver.resolve(manifest.itemModel());

            if (!res.complete()) {
                return SkinConverterResult.failed(name,
                    "Brakujące assety: " + String.join(", ", res.missing()));
            }

            int pngCount = countPngs(res.requiredPaths());
            if (pngCount == 0) {
                return SkinConverterResult.failed(name,
                    "Walidacja: brak tekstur PNG w łańcuchu modelu (szachownica)");
            }

            SkinConfig skinConfig = plugin.getSkinConfig();
            boolean isNew = skinConfig.getSkin(manifest.id()) == null;
            persistManifest(manifest);

            if (plugin.getConfig().getBoolean("converter.auto-tier", true)) {
                new ResourcePackScanner(plugin).ensureTierForNamespace(manifest.tier());
            }
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.getSkinConfig().load();
            plugin.getAdminGUI().loadTiers();

            if (plugin.getConfig().getBoolean("converter.clean-legacy-staging-on-bundle", true)) {
                cleanLegacyStaging(manifest.id());
            }

            log.info("Bundle OK: " + manifest.id() + " — " + res.requiredPaths().size()
                + " assetów, " + pngCount + " PNG");
            return SkinConverterResult.ok(name, isNew ? 1 : 0, isNew ? 0 : 1, List.of(manifest.id()));
        } catch (Exception e) {
            log.warning("Bundle błąd " + name + ": " + e.getMessage());
            return SkinConverterResult.failed(name, e.getMessage());
        }
    }

    private void persistManifest(SkinBundleManifest m) {
        String path = "skins." + m.id();
        plugin.getConfig().set(path + ".display-name", m.displayName());
        plugin.getConfig().set(path + ".item-types", m.itemTypes());
        plugin.getConfig().set(path + ".category", m.category().configKey());
        plugin.getConfig().set(path + ".equipment-asset", m.equipmentAsset() == null ? "" : m.equipmentAsset());

        boolean isolate = plugin.getConfig().getBoolean("converter.isolate-item-models", true);
        if (m.itemModel() != null) {
            plugin.getConfig().set(path + ".source-item-model", m.itemModel());
            plugin.getConfig().set(path + ".item-model", isolate ? "skinstudio:" + m.id() : m.itemModel());
            plugin.getConfig().set(path + ".source-model", "");
        } else {
            plugin.getConfig().set(path + ".source-model", m.sourceModel());
            plugin.getConfig().set(path + ".source-item-model", "skinstudio:" + m.id());
            plugin.getConfig().set(path + ".item-model", isolate ? "skinstudio:" + m.id() : m.sourceModel());
        }
    }

    private static void validateId(String folderName, String manifestId) {
        String normalized = folderName.toLowerCase().replace('-', '_');
        if (!normalized.equals(manifestId) && !folderName.equalsIgnoreCase(manifestId)) {
            // folder name mismatch is a warning only — manifest id wins
        }
    }

    private static int countPngs(java.util.Set<String> paths) {
        int n = 0;
        for (String p : paths) {
            if (p.endsWith(".png")) n++;
        }
        return n;
    }

    private void cleanLegacyStaging(String skinId) {
        File staging = stagingDir();
        File[] files = staging.listFiles();
        if (files == null) return;
        String keep = skinId + ".bundle.zip";
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".zip")) continue;
            if (f.getName().equals(keep)) continue;
            if (f.getName().endsWith(".bundle.zip")) continue;
            if (f.getName().endsWith(".bak") || f.getName().contains(".skinstudio-fixed")) {
                try {
                    Files.delete(f.toPath());
                    log.info("Staging cleanup: usunięto " + f.getName());
                } catch (IOException ignored) {}
            }
        }
    }

    private File stagingDir() {
        return new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.staging-folder", "staging"));
    }
}
