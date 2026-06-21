package pl.skinstudio.converter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Przygotowanie folderów/ZIP-ów ze skina do konwersji. */
public final class PackFolderUtil {

    private PackFolderUtil() {}

    /** Znajduje katalog {@code assets/} w drzewie (root lub podfolder IA). */
    public static File findAssetsRoot(File dir) throws IOException {
        if (!dir.isDirectory()) throw new IOException("Nie jest katalogiem: " + dir);
        File direct = new File(dir, "assets");
        if (direct.isDirectory()) return dir;

        // ItemAdder: contents/<ns>/resourcepack/assets
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if ("assets".equals(child.getName())) return dir;
                File nested = new File(child, "resourcepack/assets");
                if (nested.isDirectory()) return nested.getParentFile().getParentFile();
                File nested2 = new File(child, "assets");
                if (nested2.isDirectory()) return child;
            }
            for (File child : children) {
                try {
                    return findAssetsRoot(child);
                } catch (IOException ignored) {
                    // szukaj dalej
                }
            }
        }
        throw new IOException("Brak folderu assets/ w: " + dir.getAbsolutePath());
    }

    /** Pakuje katalog z assets/ do ZIP (forward-slash). */
    public static File zipDirectory(File sourceDir, File outputZip) throws IOException {
        File assetsRoot = findAssetsRoot(sourceDir);
        outputZip.getParentFile().mkdirs();
        if (outputZip.exists()) Files.delete(outputZip.toPath());

        Path base = assetsRoot.toPath();
        Path assetsFolder = base.resolve("assets");
        if (!Files.isDirectory(assetsFolder)) {
            throw new IOException("Brak folderu assets/ w: " + sourceDir.getAbsolutePath());
        }

        // assets/<ns>/ lub ItemAdder: assets/assets/<ns>/
        Path walkRoot = Files.isDirectory(assetsFolder.resolve("assets"))
            ? assetsFolder.resolve("assets")
            : assetsFolder;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZip))) {
            Files.walk(walkRoot).filter(p -> !Files.isDirectory(p)).forEach(p -> {
                try {
                    String rel = walkRoot.relativize(p).toString().replace('\\', '/');
                    String entryName = "assets/" + rel;
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (FileInputStream in = new FileInputStream(p.toFile())) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return outputZip;
    }
}
