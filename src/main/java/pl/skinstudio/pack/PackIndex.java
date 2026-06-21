package pl.skinstudio.pack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * In-memory index of one or more resource pack ZIPs.
 * <p>
 * Łączy wiele ZIP-ów z mixera w jeden logiczny pack (jak robi to RPM przy merge),
 * dzięki czemu walidacja widzi assety współdzielone między packami
 * (np. tekstury w {@code _iainternal} z innego ZIP-a niż item definition).
 * Ścieżki są normalizowane do forward-slash i indeksowane case-insensitive.
 */
public final class PackIndex {

    /** normalized lower-case path -> original normalized path (zachowuje oryginalną wielkość liter). */
    private final Map<String, String> lookup = new LinkedHashMap<>();
    /** original normalized path -> bytes. */
    private final Map<String, byte[]> data = new LinkedHashMap<>();
    /** original normalized path -> źródłowy plik ZIP (do raportu). */
    private final Map<String, String> source = new LinkedHashMap<>();

    private PackIndex() {}

    public static PackIndex of(Logger log, File... zips) {
        PackIndex index = new PackIndex();
        for (File zip : zips) {
            if (zip == null || !zip.isFile()) continue;
            index.ingest(zip, log);
        }
        return index;
    }

    private void ingest(File zipFile, Logger log) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = normalize(entry.getName());
                if (path.isEmpty() || path.endsWith("/")) continue;
                // Pierwszy ZIP wygrywa (kolejność = priorytet), nie nadpisujemy.
                if (data.containsKey(path)) continue;
                byte[] bytes = readEntry(zip, entry);
                data.put(path, bytes);
                source.put(path, zipFile.getName());
                lookup.putIfAbsent(path.toLowerCase(Locale.ROOT), path);
            }
        } catch (IOException e) {
            if (log != null) log.warning("PackIndex: błąd odczytu " + zipFile.getName() + ": " + e.getMessage());
        }
    }

    public boolean contains(String path) {
        String norm = normalize(path);
        if (data.containsKey(norm)) return true;
        return lookup.containsKey(norm.toLowerCase(Locale.ROOT));
    }

    public byte[] get(String path) {
        String norm = normalize(path);
        byte[] direct = data.get(norm);
        if (direct != null) return direct;
        String resolved = lookup.get(norm.toLowerCase(Locale.ROOT));
        return resolved == null ? null : data.get(resolved);
    }

    public String sourceOf(String path) {
        String norm = normalize(path);
        String resolved = data.containsKey(norm) ? norm : lookup.get(norm.toLowerCase(Locale.ROOT));
        return resolved == null ? null : source.get(resolved);
    }

    public Map<String, byte[]> entries() {
        return data;
    }

    public int size() {
        return data.size();
    }

    /** Wszystkie ścieżki zaczynające się od prefix (case-insensitive). */
    public Set<String> pathsWithPrefix(String prefix) {
        String norm = normalize(prefix).toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (String key : data.keySet()) {
            if (key.toLowerCase(Locale.ROOT).startsWith(norm)) {
                out.add(key);
            }
        }
        return out;
    }

    static String normalize(String path) {
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
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, (int) Math.max(0, entry.getSize())));
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
