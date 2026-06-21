package pl.skinstudio.util;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Przenosi zbędne ZIP-y z mixer RPM do kwarantanny.
 * Stary Dark_Queen.zip w mixerze nadpisywał poprawny SkinStudio-skins.zip w merge → szachownica.
 */
public final class MixerQuarantine {

    private MixerQuarantine() {}

    public record Result(int moved, java.util.List<String> movedNames) {}

    public static Result quarantineExtraMixerZips(SkinStudio plugin, Logger log) {
        if (!plugin.getConfig().getBoolean("converter.quarantine-mixer-sources", true)) {
            return new Result(0, java.util.List.of());
        }

        String mixerPath = plugin.getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        File mixerDir = new File(plugin.getServer().getPluginsFolder(), mixerPath);
        if (!mixerDir.isDirectory()) return new Result(0, java.util.List.of());

        File quarantine = new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.quarantine-folder", "quarantine/mixer"));
        quarantine.mkdirs();

        java.util.List<String> moved = new java.util.ArrayList<>();
        File[] zips = mixerDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".zip")
                && !lower.endsWith(".bak")
                && !name.equals(SkinPackBuilder.OUTPUT_NAME)
                && !lower.endsWith(".skinstudio-fixed.zip");
        });
        if (zips == null) return new Result(0, moved);

        for (File zip : zips) {
            File dest = uniqueFile(quarantine, zip.getName());
            try {
                Files.move(zip.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved.add(zip.getName());
                log.info("Kwarantanna mixer: przeniesiono " + zip.getName()
                    + " → " + quarantine.getPath() + " (zostaw tylko " + SkinPackBuilder.OUTPUT_NAME + " w mixer)");
            } catch (IOException e) {
                log.warning("Kwarantanna mixer: nie można przenieść " + zip.getName()
                    + " (RPM trzyma plik?) — zatrzymaj serwer i usuń ręcznie z mixer/");
            }
        }
        return new Result(moved.size(), moved);
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (f.exists()) {
            f = new File(dir, base + "_" + i + ext);
            i++;
        }
        return f;
    }
}
