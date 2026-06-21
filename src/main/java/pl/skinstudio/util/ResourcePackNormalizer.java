package pl.skinstudio.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Naprawia typowe błędy ZIP-ów kupowanych packów (Windows backslash, brak pack.mcmeta).
 * Uruchamiane automatycznie przed skanem — admin nie musi ręcznie poprawiać plików.
 */
public final class ResourcePackNormalizer {

    private static final String PACK_MCMETA = "pack.mcmeta";

    private ResourcePackNormalizer() {}

    public record Result(boolean changed, int pathsFixed, boolean mcmetaAdded, boolean mcmetaUpdated, String error) {
        public Result(boolean changed, int pathsFixed, boolean mcmetaAdded, boolean mcmetaUpdated) {
            this(changed, pathsFixed, mcmetaAdded, mcmetaUpdated, null);
        }
    }

    public enum PackStatus { OK, MISSING_MCMETA, WRONG_FORMAT, BAD_PATHS, UNREADABLE }

    /** Sprawdza pack bez modyfikacji. */
    public static PackStatus audit(File zipFile, int packFormat) {
        if (zipFile == null || !zipFile.isFile()) return PackStatus.UNREADABLE;
        try (ZipFile zip = new ZipFile(zipFile)) {
            boolean hasMcmeta = false;
            boolean badPaths = false;
            String format = null;
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String raw = entry.getName();
                if (raw == null) continue;
                String normalized = normalizePath(raw);
                if (!raw.equals(normalized)) badPaths = true;
                if (entry.isDirectory() || normalized.endsWith("/")) continue;
                if (PACK_MCMETA.equalsIgnoreCase(normalized)) {
                    hasMcmeta = true;
                    format = PackMcmetaUtil.readFormat(readString(zip, entry));
                }
            }
            if (badPaths) return PackStatus.BAD_PATHS;
            if (!hasMcmeta) return PackStatus.MISSING_MCMETA;
            if (format == null || !String.valueOf(packFormat).equals(format)) return PackStatus.WRONG_FORMAT;
            return PackStatus.OK;
        } catch (IOException e) {
            return PackStatus.UNREADABLE;
        }
    }

    private static String readString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Normalizuje ZIP w miejscu (opcjonalny backup .zip.bak).
     *
     * @return {@code changed=false} gdy pack był już poprawny
     */
    public static Result normalize(File zipFile, Logger log, boolean backup, int packFormat) {
        if (zipFile == null || !zipFile.isFile()) {
            return new Result(false, 0, false, false, "plik nie istnieje");
        }

        PackStatus before = audit(zipFile, packFormat);
        try (ZipFile zip = new ZipFile(zipFile)) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            boolean hasMcmeta = false;
            int pathsFixed = 0;

            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String raw = entry.getName();
                if (raw == null || raw.isEmpty()) continue;

                String normalized = normalizePath(raw);
                if (!raw.equals(normalized)) pathsFixed++;

                if (entry.isDirectory() || normalized.endsWith("/")) continue;

                if (PACK_MCMETA.equalsIgnoreCase(normalized)) {
                    hasMcmeta = true;
                }

                byte[] data = readEntry(zip, entry);
                entries.put(normalized, data);
            }

            boolean mcmetaAdded = false;
            boolean mcmetaUpdated = false;

            if (!hasMcmeta) {
                entries.put(PACK_MCMETA,
                    PackMcmetaUtil.defaultMcmeta(packFormat).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                mcmetaAdded = true;
            } else {
                byte[] current = entries.get(PACK_MCMETA);
                byte[] fixed = PackMcmetaUtil.fixFormatIfNeeded(current, packFormat);
                if (fixed != null) {
                    entries.put(PACK_MCMETA, fixed);
                    mcmetaUpdated = true;
                }
            }

            if (pathsFixed == 0 && !mcmetaAdded && !mcmetaUpdated) {
                return new Result(false, 0, false, false);
            }

            File temp = new File(zipFile.getParentFile(), zipFile.getName() + ".skinstudio.tmp");
            writeZip(temp, entries);

            if (backup) {
                File bak = new File(zipFile.getParentFile(), zipFile.getName() + ".bak");
                if (!bak.exists()) {
                    Files.copy(zipFile.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Backup packa: " + bak.getName());
                }
            }

            try {
                Files.move(temp.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                File pending = new File(zipFile.getParentFile(), zipFile.getName() + ".skinstudio-fixed.zip");
                Files.move(temp.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING);
                String msg = "zablokowany przez RPM — zapisano " + pending.getName()
                    + " (zatrzymaj serwer, podmień plik w mixer/)";
                log.warning("Nie można nadpisać " + zipFile.getName() + ": " + msg);
                return new Result(false, pathsFixed, mcmetaAdded, mcmetaUpdated, msg);
            }

            String mcmetaMsg = mcmetaAdded ? "dodano" : (mcmetaUpdated ? "zaktualizowano format" : "OK");
            log.info("Znormalizowano pack: " + zipFile.getName()
                + " (ścieżki: " + pathsFixed + ", pack.mcmeta: " + mcmetaMsg
                + ", format: " + packFormat + ")");

            return new Result(true, pathsFixed, mcmetaAdded, mcmetaUpdated);
        } catch (IOException e) {
            log.warning("Nie udało się znormalizować " + zipFile.getName() + ": " + e.getMessage());
            File temp = new File(zipFile.getParentFile(), zipFile.getName() + ".skinstudio.tmp");
            if (temp.exists()) temp.delete();
            if (before != PackStatus.OK) {
                return new Result(false, 0, false, false,
                    "błąd zapisu (RPM trzyma plik?) — zatrzymaj serwer i powtórz normalize");
            }
            return new Result(false, 0, false, false, e.getMessage());
        }
    }

    /** Normalizuje ścieżkę wpisu ZIP (czytanie / dopasowanie bez zapisu pliku). */
    public static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').replaceAll("/+", "/");
        int assets = normalized.indexOf("assets/");
        if (assets > 0) {
            return normalized.substring(assets);
        }
        if (normalized.endsWith("/pack.mcmeta") && normalized.indexOf('/') > 0) {
            return "pack.mcmeta";
        }
        return normalized;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void writeZip(File target, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(System.currentTimeMillis());
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }
}
