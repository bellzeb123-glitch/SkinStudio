package pl.skinstudio.util;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC 1.19.3+ wymaga rejestracji tekstur w atlasie. W 26.1 itemy używają osobnego
 * atlasu {@code items} — katalogi typu {@code textures/dark_queen/} bez wpisu w atlasie
 * dają model 3D ze szachownicą (brak tekstury).
 */
public final class AtlasPatchUtil {

    private static final Set<String> KNOWN_TEXTURE_DIRS = Set.of(
        "block", "blocks", "item", "items", "entity", "gui", "ui", "environment",
        "font", "misc", "mob_effect", "painting", "particle", "effect", "map",
        "trim", "colormap", "models", "armor", "shield", "celestial"
    );

    private static final Pattern TEXTURE_DIR = Pattern.compile(
        "^assets/[^/]+/textures/([^/]+)/", Pattern.CASE_INSENSITIVE);

    /** Tekstury bezpośrednio w {@code textures/*.png} (np. asura) — wymagają wpisu {@code single} w atlasie. */
    private static final Pattern FLAT_TEXTURE = Pattern.compile(
        "^assets/([^/]+)/textures/([^/]+)\\.png$", Pattern.CASE_INSENSITIVE);

    private AtlasPatchUtil() {}

    public static Set<String> detectCustomTextureDirs(Collection<String> assetPaths) {
        Set<String> dirs = new LinkedHashSet<>();
        for (String raw : assetPaths) {
            if (raw == null) continue;
            Matcher m = TEXTURE_DIR.matcher(raw.replace('\\', '/'));
            if (!m.find()) continue;
            String dir = m.group(1).toLowerCase(Locale.ROOT);
            if (!KNOWN_TEXTURE_DIRS.contains(dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /** Zasoby {@code namespace:name} dla płaskich PNG w {@code assets/<ns>/textures/<name>.png}. */
    public static Set<String> detectFlatTextureResources(Collection<String> assetPaths) {
        Set<String> resources = new LinkedHashSet<>();
        for (String raw : assetPaths) {
            if (raw == null) continue;
            Matcher m = FLAT_TEXTURE.matcher(raw.replace('\\', '/'));
            if (!m.matches()) continue;
            String ns = m.group(1).toLowerCase(Locale.ROOT);
            if ("minecraft".equals(ns)) continue;
            String name = m.group(2);
            resources.add(ns + ":" + name);
        }
        return resources;
    }

    /**
     * Dla packów typu Blockbench bez {@code items/*.json} — generuje definicje itemów
     * wskazujące na modele {@code assets/<ns>/models/...}.
     */
    public static void ensureItemDefinitionsFromModels(Map<String, byte[]> packFiles) {
        java.util.regex.Pattern modelPath = java.util.regex.Pattern.compile(
            "^assets/([^/]+)/models/(.+)\\.json$", Pattern.CASE_INSENSITIVE);
        for (String path : new LinkedHashSet<>(packFiles.keySet())) {
            Matcher m = modelPath.matcher(path.replace('\\', '/'));
            if (!m.matches()) continue;
            String ns = m.group(1);
            String modelRel = m.group(2);
            String itemPath = "assets/" + ns + "/items/" + modelRel + ".json";
            if (packFiles.containsKey(itemPath)) continue;
            String modelRef = ns + ":" + modelRel;
            packFiles.put(itemPath, modelItemDefinition(modelRef));
        }
    }

    private static byte[] modelItemDefinition(String modelRef) {
        return ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
            + escapeJson(modelRef) + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Dodaje brakujące katalogi tekstur do {@code items.json} i {@code blocks.json}. */
    public static void patchPackAtlases(Map<String, byte[]> packFiles) {
        ensureItemDefinitionsFromModels(packFiles);

        Set<String> customDirs = detectCustomTextureDirs(packFiles.keySet());
        Set<String> flatResources = detectFlatTextureResources(packFiles.keySet());
        if (customDirs.isEmpty() && flatResources.isEmpty()) return;

        packFiles.put(
            "assets/minecraft/atlases/items.json",
            mergeAtlasSources(packFiles.get("assets/minecraft/atlases/items.json"), customDirs, flatResources));
        packFiles.put(
            "assets/minecraft/atlases/blocks.json",
            mergeAtlasSources(packFiles.get("assets/minecraft/atlases/blocks.json"), customDirs, flatResources));
    }

    static byte[] mergeAtlasSources(byte[] existing, Set<String> newDirs, Set<String> flatResources) {
        byte[] withDirs = mergeDirectorySources(existing, newDirs);
        return mergeSingleSources(withDirs, flatResources);
    }

    static byte[] mergeSingleSources(byte[] existing, Set<String> resources) {
        if (resources.isEmpty()) return existing;
        String json = existing != null && existing.length > 0
            ? new String(existing, StandardCharsets.UTF_8)
            : "{\"sources\":[]}";

        for (String resource : resources) {
            if (hasSingleSource(json, resource)) continue;
            String entry = "{\"type\":\"single\",\"resource\":\"" + escapeJson(resource) + "\"}";
            json = appendSource(json, entry);
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasSingleSource(String json, String resource) {
        String needle = "\"resource\":\"" + resource + "\"";
        if (json.contains(needle)) return true;
        return json.contains("\"resource\": \"" + resource + "\"");
    }

    static byte[] mergeDirectorySources(byte[] existing, Set<String> newDirs) {
        String json = existing != null && existing.length > 0
            ? new String(existing, StandardCharsets.UTF_8)
            : "{\"sources\":[]}";

        for (String dir : newDirs) {
            if (hasDirectorySource(json, dir)) continue;
            String entry = "{\"type\":\"directory\",\"source\":\"" + escapeJson(dir)
                + "\",\"prefix\":\"" + escapeJson(dir) + "/\"}";
            json = appendSource(json, entry);
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasDirectorySource(String json, String dir) {
        String needle = "\"source\":\"" + dir + "\"";
        if (json.contains(needle)) return true;
        return json.contains("\"source\": \"" + dir + "\"");
    }

    private static String appendSource(String json, String entry) {
        int idx = json.lastIndexOf(']');
        if (idx < 0) {
            return "{\"sources\":[" + entry + "]}";
        }
        String before = json.substring(0, idx).stripTrailing();
        boolean empty = before.endsWith("[");
        return before + (empty ? "" : ",") + entry + "]}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
