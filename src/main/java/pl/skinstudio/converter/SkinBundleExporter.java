package pl.skinstudio.converter;

import org.bukkit.configuration.file.YamlConfiguration;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.pack.AssetResolver;
import pl.skinstudio.pack.PackIndex;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Przygotowuje folder bundle z istniejącego skina + staging ZIP. */
public final class SkinBundleExporter {

    private final SkinStudio plugin;
    private final Logger log;

    public SkinBundleExporter(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record PrepareResult(File bundleDir, int assets, int pngs) {}

    /**
     * Tworzy gotowy folder bundle w inbox/{skinId}/ z assetów ze staging.
     * Wymaga wpisu w config (source-item-model lub source-model) oraz ZIP w staging.
     */
    public PrepareResult prepareToInbox(String skinId) throws IOException {
        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            throw new IOException("Skin nie istnieje w config: " + skinId
                + " — utwórz skin.yml ręcznie lub zaimportuj bundle");
        }

        SkinConverter conv = new SkinConverter(plugin);
        File[] zips = conv.listStagingZips();
        if (zips.length == 0) {
            throw new IOException("Brak ZIP-ów w staging — wrzuć pack do inbox lub użyj bundle z assets/");
        }

        PackIndex index = PackIndex.of(log, zips);
        AssetResolver resolver = new AssetResolver(index);

        String sourceModel = skin.getSourceModel();
        String sourceItem = skin.getSourceItemModel();
        AssetResolver.Resolution res = (sourceModel != null && !sourceModel.isBlank())
            ? resolver.resolveModelReference(sourceModel)
            : resolver.resolve(sourceItem);

        if (!res.complete()) {
            throw new IOException("Niekompletne assety w staging: " + res.missing());
        }

        File bundleDir = new File(conv.inboxDir(), skinId);
        if (bundleDir.exists()) {
            deleteRecursive(bundleDir);
        }
        bundleDir.mkdirs();

        Set<String> written = new LinkedHashSet<>();
        int pngs = 0;
        for (String path : res.requiredPaths()) {
            if ("pack.mcmeta".equalsIgnoreCase(path)) continue;
            byte[] data = index.get(path);
            if (data == null) continue;
            File target = new File(bundleDir, path.replace('/', File.separatorChar));
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), data);
            written.add(path);
            if (path.endsWith(".png")) pngs++;
        }

        writeSkinYml(bundleDir, skin, sourceModel, sourceItem);
        log.info("Prepare bundle: " + bundleDir.getPath() + " (" + written.size() + " plików, " + pngs + " PNG)");
        return new PrepareResult(bundleDir, written.size(), pngs);
    }

    private void writeSkinYml(File bundleDir, SkinDefinition skin, String sourceModel, String sourceItem)
        throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("id", skin.getId());
        yml.set("display-name", skin.getDisplayName());
        String tier = skin.getId().contains("_")
            ? skin.getId().substring(0, skin.getId().indexOf('_')) : skin.getId();
        yml.set("tier", tier);
        yml.set("category", skin.getCategory().configKey());
        yml.set("item-types", skin.getAllowedTypes().stream().map(Enum::name).toList());
        if (sourceModel != null && !sourceModel.isBlank()) {
            yml.set("source-model", sourceModel);
        } else {
            yml.set("item-model", sourceItem);
        }
        if (skin.hasEquipmentAsset()) {
            yml.set("equipment-asset", skin.getEquipmentAsset());
        }
        yml.save(new File(bundleDir, "skin.yml"));
    }

    private static void deleteRecursive(File dir) throws IOException {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(dir.toPath());
    }
}
