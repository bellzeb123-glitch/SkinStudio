package pl.skinstudio.util;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zapisuje zbudowany resource pack do katalogu SkinStudio/pack.
 * RPM czyta go przez compatible_plugins/skinstudio.yml, tak jak Nexo/Oraxen/FMM.
 */
public final class BuiltPackWriter {

    public static final String PENDING_NAME = SkinPackBuilder.OUTPUT_NAME;

    private BuiltPackWriter() {}

    public record WriteResult(File written, boolean pending, String message) {}

    public static WriteResult write(SkinStudio plugin, File outputDir, Map<String, byte[]> entries,
                                      Logger log) throws IOException {
        outputDir.mkdirs();
        File output = new File(outputDir, SkinPackBuilder.OUTPUT_NAME);
        File tmp = new File(outputDir, SkinPackBuilder.OUTPUT_NAME + ".tmp");

        writeZipFile(tmp, entries);

        if (tryInstall(tmp, output)) {
            audit(plugin, output, log);
            return new WriteResult(output, false, null);
        }

        // Teoretyczny fallback; RPM nie powinien trzymać pliku z plugins/SkinStudio/pack.
        File pendingDir = pendingDir(plugin);
        pendingDir.mkdirs();
        File pending = new File(pendingDir, PENDING_NAME);
        Files.move(tmp.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING);

        String msg = "Nie można podmienić " + SkinPackBuilder.OUTPUT_NAME
            + " — zapisano " + pending.getPath()
            + " (zatrzymaj serwer lub /skintoken apply)";
        log.warning(msg);
        return new WriteResult(pending, true, msg);
    }

    public static File pendingDir(SkinStudio plugin) {
        return new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.pending-built-folder", "pending"));
    }

    /** Podmienia pending → SkinStudio/pack (przy starcie / apply). */
    public static ApplyResult applyPending(SkinStudio plugin, Logger log) {
        File pending = new File(pendingDir(plugin), PENDING_NAME);
        if (!pending.isFile()) {
            return new ApplyResult(0, null);
        }

        File target = new File(plugin.getDataFolder(),
            plugin.getConfig().getString("converter.output-folder", "pack") + "/" + PENDING_NAME);

        try {
            target.getParentFile().mkdirs();
            if (target.exists() && !target.delete()) {
                return new ApplyResult(0, "nie można usunąć starego " + PENDING_NAME + " w SkinStudio/pack");
            }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            audit(plugin, target, log);
            log.info("Podmieniono pending built pack → SkinStudio/pack/" + PENDING_NAME);
            return new ApplyResult(1, null);
        } catch (IOException e) {
            return new ApplyResult(0, e.getMessage());
        }
    }

    public record ApplyResult(int applied, String error) {}

    private static boolean tryInstall(File tmp, File output) {
        try {
            if (output.exists() && !output.delete()) {
                return false;
            }
            return tmp.renameTo(output);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeZipFile(File target, Map<String, byte[]> entries) throws IOException {
        // pack.mcmeta ZAWSZE na końcu — nadpisuje ewentualne kopie ze źródeł
        byte[] mcmeta = entries.remove("pack.mcmeta");
        try (ZipOutputStream zos = new ZipOutputStream(new java.io.FileOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
            if (mcmeta != null) {
                zos.putNextEntry(new ZipEntry("pack.mcmeta"));
                zos.write(mcmeta);
                zos.closeEntry();
            }
        }
        if (mcmeta != null) {
            entries.put("pack.mcmeta", mcmeta);
        }
    }

    /** Weryfikuje zapisany pack — loguje błąd gdy format/rozmiar podejrzany. */
    public static void audit(SkinStudio plugin, File zip, Logger log) {
        if (zip == null || !zip.isFile()) return;
        int expected = plugin.getConfig().getInt("scanner.pack-format", 84);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            var mcmetaEntry = zf.getEntry("pack.mcmeta");
            if (mcmetaEntry == null) {
                log.severe("AUDIT " + zip.getName() + ": brak pack.mcmeta — szachownica gwarantowana!");
                return;
            }
            String json = new String(zf.getInputStream(mcmetaEntry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String fmt = PackMcmetaUtil.readFormat(json);
            if (!String.valueOf(expected).equals(fmt)) {
                log.severe("AUDIT " + zip.getName() + ": pack_format=" + fmt
                    + " (wymagane " + expected + ") — tekstury mogą być złe!");
            }
            long pngs = zf.stream().filter(e -> e.getName().endsWith(".png")).count();
            log.info("AUDIT " + zip.getName() + ": pack_format=" + fmt + ", " + pngs + " PNG, "
                + zf.size() + " wpisów");
        } catch (IOException e) {
            log.warning("AUDIT nie powiódł się: " + e.getMessage());
        }
    }
}
