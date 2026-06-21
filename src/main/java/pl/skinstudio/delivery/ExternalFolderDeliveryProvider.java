package pl.skinstudio.delivery;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Eksport zbudowanego {@code pack.zip} do zewnętrznego folderu, skąd inny plugin
 * (Oraxen, ItemsAdder, PackSquash, dowolny merger z watch-folderem) go przejmie.
 */
public final class ExternalFolderDeliveryProvider implements PackDeliveryProvider {

    private final SkinStudio plugin;

    public ExternalFolderDeliveryProvider(SkinStudio plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "ExternalFolder";
    }

    @Override
    public boolean available() {
        String out = plugin.getConfig().getString("delivery.external.output-path", "");
        return out != null && !out.isBlank();
    }

    @Override
    public void deliver(boolean force) {
        String out = plugin.getConfig().getString("delivery.external.output-path", "");
        if (out == null || out.isBlank()) {
            plugin.getLogger().warning("Delivery external: delivery.external.output-path puste.");
            return;
        }
        String outputFolder = plugin.getConfig().getString("converter.output-folder", "pack");
        File src = new File(plugin.getDataFolder(), outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME);
        if (!src.isFile()) {
            plugin.getLogger().warning("Delivery external: brak " + src.getPath());
            return;
        }
        File dest = resolveDest(out);
        try {
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Delivery external: skopiowano pack → " + dest.getPath());
        } catch (Exception e) {
            plugin.getLogger().warning("Delivery external: kopiowanie nie powiodło się: " + e.getMessage());
        }
    }

    /** Ścieżka względna liczona od folderu pluginów; bezwzględna używana wprost. */
    private File resolveDest(String out) {
        File asFile = new File(out);
        if (asFile.isAbsolute()) {
            return asFile.isDirectory() ? new File(asFile, SkinPackBuilder.OUTPUT_NAME) : asFile;
        }
        File base = new File(plugin.getServer().getPluginsFolder(), out);
        return out.endsWith(".zip") ? base : new File(base, SkinPackBuilder.OUTPUT_NAME);
    }
}
