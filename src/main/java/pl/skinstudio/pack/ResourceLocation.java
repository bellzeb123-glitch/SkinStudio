package pl.skinstudio.pack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rozszerzone parsowanie lokalizacji zasobów MC (namespace, tekstury, warianty ścieżek). */
public record ResourceLocation(String namespace, String path) {

    public static ResourceLocation parse(String raw) {
        return parse(raw, null);
    }

    /**
     * @param defaultNamespace używany gdy ref nie ma {@code ns:} (typowe w modelach IA)
     */
    public static ResourceLocation parse(String raw, String defaultNamespace) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        int idx = s.indexOf(':');
        if (idx < 0) {
            String ns = (defaultNamespace != null && !defaultNamespace.isBlank())
                ? defaultNamespace.toLowerCase(Locale.ROOT)
                : "minecraft";
            return new ResourceLocation(ns, clean(s));
        }
        String ns = s.substring(0, idx).toLowerCase(Locale.ROOT);
        String path = clean(s.substring(idx + 1));
        if (ns.isEmpty()) ns = "minecraft";
        return new ResourceLocation(ns, path);
    }

    private static String clean(String p) {
        return p.replace('\\', '/').replaceAll("^/+", "");
    }

    public String itemDefinitionPath() {
        return "assets/" + namespace + "/items/" + path + ".json";
    }

    public String modelPath() {
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    /** Główna ścieżka tekstury w ZIP. */
    public String texturePath() {
        return texturePathCandidates().get(0);
    }

    /** Warianty ścieżek (IA czasem podaje {@code textures/...} lub {@code .png} w ref). */
    public List<String> texturePathCandidates() {
        List<String> out = new ArrayList<>();
        String p = path;
        if (p.endsWith(".png")) p = p.substring(0, p.length() - 4);

        if (p.startsWith("textures/")) {
            out.add("assets/" + namespace + "/" + p + ".png");
        } else {
            out.add("assets/" + namespace + "/textures/" + p + ".png");
            // czasem ref = "textures/foo" bez prefixu w path
            out.add("assets/" + namespace + "/textures/" + p);
        }
        return out;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
