package pl.skinstudio.pack;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.converter.SkinConverter;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.BuiltPackWriter;
import pl.skinstudio.util.PackMcmetaUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Buduje czysty, samodzielny resource pack zawierający WYŁĄCZNIE assety potrzebne
 * skinom z config.yml — w analogii do konwerterów Nexo/Oraxen, które importują pack
 * ItemAddera i przebudowują go do własnej, gwarantowanie poprawnej struktury.
 * <p>
 * Dzięki temu RPM dostaje jeden pack z poprawnym {@code pack.mcmeta}, ścieżkami
 * forward-slash i kompletnym łańcuchem item→model→tekstura (również z {@code _iainternal}),
 * co eliminuje szachownicę wynikającą z bałaganu w źródłowym ZIP-ie.
 */
public final class SkinPackBuilder {

    public static final String OUTPUT_NAME = "pack.zip";

    private final SkinStudio plugin;
    private final Logger log;

    public SkinPackBuilder(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record BuildReport(
        boolean success,
        File output,
        int skinsIncluded,
        int assetsCopied,
        Map<String, List<String>> incompleteSkins,
        String error
    ) {
        public static BuildReport error(String msg) {
            return new BuildReport(false, null, 0, 0, Map.of(), msg);
        }
    }

    public BuildReport build() {
        File outputDir = resolveOutputDir();
        File[] sourceZips = collectSourceZips();
        if (sourceZips.length == 0) {
            return BuildReport.error("Brak źródłowych ZIP-ów w staging. Wrzuć pack do inbox/");
        }

        PackIndex index = PackIndex.of(log, sourceZips);
        AssetResolver resolver = new AssetResolver(index);

        Map<String, byte[]> toWrite = new LinkedHashMap<>();
        Map<String, List<String>> incomplete = new LinkedHashMap<>();
        int included = 0;
        boolean migratedConfig = false;
        boolean isolateItemModels = plugin.getConfig().getBoolean("converter.isolate-item-models", true);

        for (SkinDefinition skin : plugin.getSkinConfig().getAllSkins().values()) {
            String itemModel = skin.getItemModel();
            String sourceItemModel = skin.getSourceItemModel();
            String sourceModel = skin.getSourceModel();
            if (sourceItemModel == null || sourceItemModel.isBlank()) sourceItemModel = itemModel;
            if ((sourceItemModel == null || sourceItemModel.isBlank()) && (sourceModel == null || sourceModel.isBlank())) continue;

            AssetResolver.Resolution res = (sourceModel != null && !sourceModel.isBlank())
                ? resolver.resolveModelReference(sourceModel)
                : resolver.resolve(sourceItemModel);
            if (!res.itemDefinitionPresent()) {
                incomplete.put(skin.getId(), res.missing());
                continue;
            }

            Set<String> pathsToCopy = new LinkedHashSet<>(res.requiredPaths());

            boolean bundleNs = plugin.getConfig().getBoolean("converter.include-namespace-assets", true);
            boolean bundleIa = plugin.getConfig().getBoolean("converter.include-iainternal", true);
            pathsToCopy.addAll(NamespaceBundler.bundleForSkin(sourceItemModel, index, bundleNs, bundleIa));

            for (String path : pathsToCopy) {
                if ("pack.mcmeta".equalsIgnoreCase(path)) continue; // zawsze generujemy własny
                byte[] bytes = index.get(path);
                if (bytes != null) toWrite.putIfAbsent(normalizeForOutput(path), bytes);
            }

            if (isolateItemModels) {
                String isolatedModel = "skinstudio:" + skin.getId();
                byte[] isolatedItemJson = isolatedItemDefinition(sourceItemModel, sourceModel, index);
                if (isolatedItemJson != null) {
                    toWrite.put("assets/skinstudio/items/" + skin.getId() + ".json", isolatedItemJson);
                }

                String configPath = "skins." + skin.getId();
                if (!isolatedModel.equals(itemModel)) {
                    plugin.getConfig().set(configPath + ".source-item-model", sourceItemModel);
                    plugin.getConfig().set(configPath + ".item-model", isolatedModel);
                    migratedConfig = true;
                }
            }

            if (!res.missing().isEmpty()) {
                incomplete.put(skin.getId(), res.missing());
                log.warning("Skin " + skin.getId() + " — brakujące assety: " + res.missing());
            } else {
                included++;
            }
        }

        if (toWrite.isEmpty()) {
            return BuildReport.error("Nie znaleziono żadnych kompletnych assetów do zbudowania packa.");
        }

        int packFormat = plugin.getConfig().getInt("scanner.pack-format", 84);
        toWrite.put("pack.mcmeta", PackMcmetaUtil.builtMcmeta(packFormat)
            .getBytes(StandardCharsets.UTF_8));

        File output = new File(outputDir, OUTPUT_NAME);
        try {
            BuiltPackWriter.WriteResult wr = BuiltPackWriter.write(plugin, outputDir, toWrite, log);
            output = wr.written();
            if (wr.pending()) {
                log.warning(wr.message());
            }
        } catch (IOException e) {
            return BuildReport.error("Błąd zapisu " + OUTPUT_NAME + ": " + e.getMessage());
        }

        log.info("Zbudowano " + OUTPUT_NAME + " — " + included + " skinów, "
            + (toWrite.size() - 1) + " assetów.");

        if (migratedConfig) {
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.getSkinConfig().load();
            log.info("Przepięto item_model skinów na izolowany namespace skinstudio:*");
        }

        return new BuildReport(true, output, included, toWrite.size() - 1, incomplete, null);
    }

    private File[] collectSourceZips() {
        SkinConverter conv = new SkinConverter(plugin);
        File[] all = conv.listStagingZips();
        List<File> bundles = new ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(".bundle.zip")) bundles.add(f);
        }
        if (!bundles.isEmpty()) {
            return bundles.toArray(new File[0]);
        }
        if (plugin.getConfig().getBoolean("converter.bundle-only-staging", true)) {
            log.warning("Brak *.bundle.zip w staging — zaimportuj bundle z inbox/");
            return new File[0];
        }
        return all;
    }

    private File resolveOutputDir() {
        File out = new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.output-folder", "pack"));
        out.mkdirs();
        return out;
    }

    private static String normalizeForOutput(String path) {
        return path.replace('\\', '/').replaceAll("/+", "/");
    }

    private static byte[] isolatedItemDefinition(String sourceItemModel, String sourceModel, PackIndex index) {
        if (sourceModel != null && !sourceModel.isBlank()) {
            return modelItemDefinition(sourceModel);
        }

        ResourceLocation source = ResourceLocation.parse(sourceItemModel);
        if (source == null) return null;

        // sourceItemModel jest ID definicji itemu (assets/<ns>/items/*.json), nie modelem.
        // Dlatego izolowany skinstudio:<id> musi dostać kopię oryginalnej definicji itemu.
        byte[] originalItemDefinition = index.get(source.itemDefinitionPath());
        if (originalItemDefinition != null) {
            return originalItemDefinition;
        }

        // Fallback tylko dla nietypowych packów, gdzie source wskazuje bezpośrednio na model.
        return modelItemDefinition(sourceItemModel);
    }

    private static byte[] modelItemDefinition(String modelRef) {
        String escaped = modelRef.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + escaped + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
