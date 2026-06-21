package pl.skinstudio.util;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.pack.AssetResolver;
import pl.skinstudio.pack.PackIndex;
import pl.skinstudio.pack.SkinPackBuilder;
import pl.skinstudio.converter.SkinConverter;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Weryfikuje łańcuch item_model → model(e) → tekstury w mixerze.
 * <p>
 * Używa {@link PackIndex} (wszystkie ZIP-y traktowane jak jeden merged pack — tak jak RPM),
 * dzięki czemu poprawnie widzi assety współdzielone między packami (np. {@code _iainternal}).
 */
public final class ResourcePackDiagnostics {

    private final SkinStudio plugin;
    private final Logger log;

    public ResourcePackDiagnostics(SkinStudio plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public List<String> diagnose(String skinId) {
        List<String> lines = new ArrayList<>();
        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            lines.add("Skin '" + skinId + "' nie istnieje w config.yml.");
            return lines;
        }

        int expectedFormat = plugin.getConfig().getInt("scanner.pack-format", 84);
        lines.add("Skin: " + skinId + " | item-model: " + skin.getItemModel());
        lines.add("Oczekiwany pack_format: " + expectedFormat + " (Purpur/MC 26.1.x)");

        File merged = findMergedRpmPack();
        if (merged != null) {
            lines.add("--- MERGED pack wysyłany do klientów (RPM) ---");
            lines.add("  Plik: " + merged.getName() + " (" + merged.length() / 1024 + " KB)");
            checkMergedPackForSkin(merged, skin, expectedFormat, lines);
        } else {
            lines.add("! Brak ResourcePackManager/output/ResourcePackManager_RSP.zip");
            lines.add("  → /resourcepackmanager reload");
        }

        File[] zips = listStagingZipsOnly();
        if (zips == null || zips.length == 0) {
            lines.add("Staging: brak *.bundle.zip (OK jeśli merged pack powyżej jest OK)");
        } else {
            // 1) Status pack.mcmeta per staging ZIP (informacyjnie)
            for (File zip : zips) {
                lines.add("--- staging: " + zip.getName() + " ---");
                checkMcmeta(zip, expectedFormat, lines);
            }
        }

        String itemModel = skin.getItemModel();
        if (itemModel == null || !itemModel.contains(":")) {
            lines.add("BŁĄD: niepoprawny item-model w config.");
            return lines;
        }

        // 2) Łańcuch assetów — preferuj merged RPM, fallback staging
        File[] resolveZips = merged != null ? new File[] { merged } : zips;
        if (resolveZips == null || resolveZips.length == 0) {
            lines.add("BŁĄD: brak packa do walidacji assetów.");
            return lines;
        }
        PackIndex index = PackIndex.of(log, resolveZips);
        AssetResolver.Resolution res = new AssetResolver(index).resolve(itemModel);

        lines.add("--- łańcuch assetów (merged) ---");
        if (!res.itemDefinitionPresent()) {
            lines.add("  ! brak definicji itemu: " + itemModel);
            lines.add("    → RPM nie zna tego item_model (sprawdź czy ZIP jest w mixer/priorityOrder)");
            return lines;
        }
        for (String path : res.requiredPaths()) {
            String src = index.sourceOf(path);
            lines.add("  OK " + path + (src != null ? " [" + src + "]" : ""));
        }
        if (res.missing().isEmpty()) {
            lines.add("Łańcuch assetów KOMPLETNY w źródłach (staging/mixer).");
        } else {
            lines.add("BRAKUJĄCE assety w źródłach (← szachownica):");
            for (String m : res.missing()) lines.add("  ! " + m);
        }

        File built = findBuiltPack();
        if (built == null) {
            lines.add("--- SkinStudio-skins.zip ---");
            lines.add("  ! BRAK — uruchom /skintoken build lub wrzuć pack do inbox/");
        } else {
            lines.add("--- SkinStudio/pack/" + SkinPackBuilder.OUTPUT_NAME + " (źródło RPM) ---");
            checkBuiltPack(built, res, lines, expectedFormat);
        }

        lines.add("Jeśli nadal szachownica:");
        lines.add("  1) /skintoken giveitem @s " + skinId + "  (test — gotowy miecz, NIE token)");
        lines.add("  2) /skintoken repush  (wymusza wysłanie packa bez rejoin)");
        lines.add("  3) Zaakceptuj resource pack po wejściu na serwer (RPM prompt)");
        return lines;
    }

    private File findMergedRpmPack() {
        File f = new File(plugin.getServer().getPluginsFolder(),
            "ResourcePackManager/output/ResourcePackManager_RSP.zip");
        return f.isFile() ? f : null;
    }

    private void checkMergedPackForSkin(File merged, SkinDefinition skin, int expectedFormat, List<String> lines) {
        String itemModel = skin.getItemModel();
        if (itemModel == null || !itemModel.contains(":")) return;

        try (ZipFile zf = new ZipFile(merged)) {
            ZipEntry mcmetaEntry = findEntry(zf, "pack.mcmeta");
            if (mcmetaEntry == null) {
                lines.add("  ! BRAK pack.mcmeta w merged pack");
            } else {
                String fmt = PackMcmetaUtil.readFormat(readString(zf, mcmetaEntry));
                if (!String.valueOf(expectedFormat).equals(fmt)) {
                    lines.add("  ! pack_format=" + fmt + " (wymagane " + expectedFormat + ") ← KLIENT ODRZUCI TEKSTURY");
                    lines.add("    → /skintoken repush");
                } else {
                    lines.add("  OK pack_format=" + fmt);
                }
            }

            PackIndex index = PackIndex.of(log, merged);
            AssetResolver.Resolution res = new AssetResolver(index).resolve(itemModel);
            int pngOk = 0;
            int pngMissing = 0;
            for (String path : res.requiredPaths()) {
                if (!path.endsWith(".png")) continue;
                if (findEntry(zf, path) != null) {
                    pngOk++;
                    lines.add("  OK tekstura w merged: " + path);
                } else {
                    pngMissing++;
                    lines.add("  ! BRAK w merged: " + path + " ← SZACHOWNICA");
                }
            }
            if (res.missing().isEmpty() && pngMissing == 0) {
                lines.add("  ✓ Skin " + skin.getId() + " KOMPLETNY w packu wysyłanym do klientów.");
            } else if (!res.missing().isEmpty()) {
                lines.add("  ! Brakujące assety w merged:");
                for (String m : res.missing()) lines.add("    ! " + m);
            }
            lines.add("  Podsumowanie merged: " + pngOk + " PNG OK, " + pngMissing + " braków");
        } catch (Exception e) {
            lines.add("  BŁĄD odczytu merged pack: " + e.getMessage());
        }
    }

    private File[] listStagingZipsOnly() {
        SkinConverter conv = new SkinConverter(plugin);
        File[] staging = conv.listStagingZips();
        if (staging == null || staging.length == 0) return null;
        java.util.List<File> bundles = new java.util.ArrayList<>();
        for (File f : staging) {
            if (f.getName().endsWith(".bundle.zip")) bundles.add(f);
        }
        return bundles.isEmpty() ? staging : bundles.toArray(new File[0]);
    }

    private File findBuiltPack() {
        File f = new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.output-folder", "pack") + "/" + SkinPackBuilder.OUTPUT_NAME);
        return f.isFile() ? f : null;
    }

    private void checkBuiltPack(File built, AssetResolver.Resolution res, List<String> lines, int expectedFormat) {
        try (ZipFile zf = new ZipFile(built)) {
            ZipEntry mcmetaEntry = findEntry(zf, "pack.mcmeta");
            if (mcmetaEntry == null) {
                lines.add("  ! BRAK pack.mcmeta w built pack ← szachownica");
            } else {
                String meta = readString(zf, mcmetaEntry);
                String fmt = PackMcmetaUtil.readFormat(meta);
                if (fmt == null) {
                    lines.add("  ! pack.mcmeta bez pack_format");
                } else if (!String.valueOf(expectedFormat).equals(fmt)) {
                    lines.add("  ! pack_format=" + fmt + " (wymagane " + expectedFormat + ") ← STARY/ZŁY pack!");
                    lines.add("    → RPM wysyła zły format — /skintoken build + /skintoken apply");
                } else {
                    lines.add("  OK pack_format=" + fmt);
                }
            }
            int pngOk = 0;
            int pngMissing = 0;
            for (String path : res.requiredPaths()) {
                if (!path.endsWith(".png")) continue;
                if (findEntry(zf, path) != null) {
                    pngOk++;
                    lines.add("  OK w built: " + path);
                } else {
                    pngMissing++;
                    lines.add("  ! BRAK w built: " + path + " ← szachownica");
                }
            }
            long totalPng = zf.stream().filter(e -> e.getName().endsWith(".png")).count();
            lines.add("  Podsumowanie: " + pngOk + " OK, " + pngMissing + " braków, łącznie "
                + totalPng + " PNG w built pack");
        } catch (Exception e) {
            lines.add("  BŁĄD odczytu built pack: " + e.getMessage());
        }
    }

    private void checkMcmeta(File zip, int expectedFormat, List<String> lines) {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry mcmeta = findEntry(zf, "pack.mcmeta");
            if (mcmeta == null) {
                lines.add("  ! brak pack.mcmeta (uruchom /skintoken normalize)");
                return;
            }
            String meta = readString(zf, mcmeta);
            String fmt = PackMcmetaUtil.readFormat(meta);
            if (fmt == null) {
                lines.add("  ! pack.mcmeta bez pack_format");
            } else if (!String.valueOf(expectedFormat).equals(fmt)) {
                lines.add("  ! pack_format=" + fmt + " (powinno być " + expectedFormat + ")");
            } else {
                lines.add("  OK pack_format=" + fmt);
            }
        } catch (Exception e) {
            lines.add("  BŁĄD odczytu ZIP: " + e.getMessage());
        }
    }

    private File[] listAllSourceZips() {
        SkinConverter conv = new SkinConverter(plugin);
        java.util.List<File> all = new java.util.ArrayList<>();
        all.addAll(java.util.Arrays.asList(conv.listStagingZips()));

        String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        File mixerDir = new File(plugin.getServer().getPluginsFolder(), mixerPath);
        if (mixerDir.isDirectory()) {
            File[] mixer = mixerDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".zip")
                    && !name.toLowerCase(Locale.ROOT).endsWith(".bak"));
            if (mixer != null) all.addAll(java.util.Arrays.asList(mixer));
        }
        return all.isEmpty() ? null : all.toArray(new File[0]);
    }

    private static ZipEntry findEntry(ZipFile zip, String path) {
        ZipEntry e = zip.getEntry(path);
        if (e != null) return e;
        return zip.getEntry(path.replace('/', '\\'));
    }

    private static String readString(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
