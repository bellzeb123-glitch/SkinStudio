package pl.skinstudio.converter;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;
import pl.skinstudio.util.ResourcePackScanner;
import pl.skinstudio.util.RpmBridge;
import pl.skinstudio.util.ScanResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Konwerter skinów — UX „kupiłem pack → wrzucam → gotowe”.
 * <p>
 * Wejście: {@code plugins/SkinStudio/inbox/} (ZIP lub folder z assets/)
 * Wyjście: skiny w config.yml, pack w staging/, czysty SkinStudio-skins.zip w mixer RPM.
 */
public final class SkinConverter {

    private final SkinStudio plugin;
    private final Logger log;

    public SkinConverter(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record BatchResult(List<SkinConverterResult> items, int totalSkinsAdded, boolean packBuilt,
                            int assetsCopied, int skinsInPack) {}

    /** Przetwarza wszystkie oczekujące pliki w inbox. */
    public BatchResult processInbox() {
        File inbox = inboxDir();
        inbox.mkdirs();
        stagingDir().mkdirs();
        processedDir().mkdirs();
        failedDir().mkdirs();

        File[] pending = inbox.listFiles(f -> f.isFile() || f.isDirectory());
        if (pending == null || pending.length == 0) {
            return new BatchResult(List.of(), 0, false, 0, 0);
        }

        List<SkinConverterResult> results = new ArrayList<>();
        int totalAdded = 0;

        for (File input : pending) {
            if (input.getName().startsWith(".")) continue;
            if (input.isFile() && input.getName().endsWith(".processing")) continue;

            SkinConverterResult one = convertOne(input);
            results.add(one);
            if (one.success()) {
                totalAdded += one.skinsAdded();
                archive(input, processedDir(), one.sourceName());
            } else {
                archive(input, failedDir(), one.sourceName());
            }
        }

        boolean packBuilt = false;
        int assetsCopied = 0;
        int skinsInPack = 0;
        if (totalAdded > 0 || plugin.getConfig().getBoolean("converter.rebuild-after-import", true)) {
            var fin = finalizePipeline(totalAdded > 0);
            packBuilt = fin.success();
            assetsCopied = fin.assetsCopied();
            skinsInPack = fin.skinsIncluded();
        }

        return new BatchResult(results, totalAdded, packBuilt, assetsCopied, skinsInPack);
    }

    public record FinalizeResult(boolean success, int assetsCopied, int skinsIncluded) {}

    /** Konwertuje Skin Bundle (folder + skin.yml) lub legacy ZIP. */
    public SkinConverterResult convertOne(File input) {
        String name = input.getName();

        if (SkinBundleImporter.isBundleFolder(input)) {
            return new SkinBundleImporter(plugin).importBundle(input);
        }

        if (plugin.getConfig().getBoolean("converter.bundle-only", true)) {
            if (input.isDirectory()) {
                return SkinConverterResult.failed(name,
                    "Brak skin.yml — wrzuć folder bundle (patrz bundle-template/)");
            }
            return SkinConverterResult.failed(name,
                "Tryb bundle-only: użyj folderu z skin.yml zamiast całego ZIP. /skintoken prepare <id>");
        }

        File workZip;

        try {
            if (input.isDirectory()) {
                File staged = new File(stagingDir(), sanitizeName(name) + ".zip");
                PackFolderUtil.zipDirectory(input, staged);
                workZip = staged;
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                File staged = new File(stagingDir(), sanitizeName(name));
                Files.copy(input.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
                workZip = staged;
            } else {
                return SkinConverterResult.failed(name, "Nieobsługiwany format (oczekiwany .zip lub folder)");
            }

            // normalize tylko w scanFiles — unikaj podwójnego zapisu
            ResourcePackScanner scanner = new ResourcePackScanner(plugin);
            int before = plugin.getSkinConfig().getAllSkins().size();
            ScanResult scan = scanner.scanFiles(new File[] { workZip });
            plugin.reloadConfig();
            plugin.getSkinConfig().load();
            plugin.getAdminGUI().loadTiers();

            int after = plugin.getSkinConfig().getAllSkins().size();
            int added = scan.skinsAdded();
            int skipped = Math.max(0, after - before - added);

            return SkinConverterResult.ok(name, added, skipped, List.of());
        } catch (Exception e) {
            log.warning("Konwerter: błąd " + name + ": " + e.getMessage());
            return SkinConverterResult.failed(name, e.getMessage());
        }
    }

    /** Rebuild pack + kwarantanna starych ZIP w mixer + RPM reload. */
    public FinalizeResult finalizePipeline(boolean reloadConfig) {
        if (reloadConfig) {
            plugin.reloadConfig();
            plugin.getSkinConfig().load();
            plugin.getAdminGUI().loadTiers();
        }

        var build = new SkinPackBuilder(plugin).build();
        if (!build.success()) {
            log.warning("Konwerter: build pack nie powiódł się: " + build.error());
            return new FinalizeResult(false, 0, 0);
        }
        if (!build.incompleteSkins().isEmpty()) {
            build.incompleteSkins().forEach((id, missing) ->
                log.warning("Build: skin " + id + " — braki: " + missing));
        }
        log.info("Build OK: " + build.assetsCopied() + " assetów, " + build.skinsIncluded() + " skinów kompletnych");

        if (plugin.getConfig().getBoolean("scanner.auto-rpm-reload", true)) {
            RpmBridge.reloadMergedPack(plugin);
        }
        return new FinalizeResult(true, build.assetsCopied(), build.skinsIncluded());
    }

    private void archive(File source, File targetDir, String label) {
        try {
            File dest = uniqueFile(targetDir, source.getName());
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Konwerter: nie przeniesiono " + label + " → " + targetDir.getName()
                + ": " + e.getMessage());
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int i = 1;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        while (f.exists()) {
            f = new File(dir, base + "_" + i + ext);
            i++;
        }
        return f;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public File inboxDir() {
        return new File(plugin.getDataFolder(), plugin.getConfig().getString("converter.inbox-folder", "inbox"));
    }

    public File stagingDir() {
        return new File(plugin.getDataFolder(), plugin.getConfig().getString("converter.staging-folder", "staging"));
    }

    public File processedDir() {
        return new File(plugin.getDataFolder(), plugin.getConfig().getString("converter.processed-folder", "processed"));
    }

    public File failedDir() {
        return new File(plugin.getDataFolder(), plugin.getConfig().getString("converter.failed-folder", "failed"));
    }

    /** Wszystkie ZIP-y ze staging (źródła dla buildera). */
    public File[] listStagingZips() {
        File dir = stagingDir();
        File[] zips = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
        return zips == null ? new File[0] : zips;
    }
}
