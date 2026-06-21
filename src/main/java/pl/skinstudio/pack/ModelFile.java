package pl.skinstudio.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parser modelu blockbench/vanilla ({@code assets/<ns>/models/<path>.json}).
 * Rozwiązuje {@code #ref} w mapie {@code textures} (typowe w Blockbench / IA).
 */
public final class ModelFile {

    private final String parent;
    private final Set<String> textureRefs = new LinkedHashSet<>();

    private ModelFile(String parent) {
        this.parent = parent;
    }

    public String parent() {
        return parent;
    }

    public Set<String> textureRefs() {
        return textureRefs;
    }

    public static ModelFile parse(byte[] json) {
        if (json == null || json.length == 0) return new ModelFile(null);
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new ModelFile(null);
            JsonObject obj = root.getAsJsonObject();

            String parent = null;
            JsonElement p = obj.get("parent");
            if (p != null && p.isJsonPrimitive()) parent = p.getAsString();

            ModelFile mf = new ModelFile(parent);

            JsonElement textures = obj.get("textures");
            if (textures != null && textures.isJsonObject()) {
                Map<String, String> raw = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : textures.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String value = e.getValue().getAsString();
                    if (value != null && !value.isBlank()) raw.put(e.getKey(), value);
                }
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    String resolved = resolveRef(e.getValue(), raw, new LinkedHashSet<>());
                    if (resolved != null && !resolved.startsWith("#")) {
                        mf.textureRefs.add(resolved);
                    }
                }
            }
            return mf;
        } catch (Exception ignored) {
            return new ModelFile(null);
        }
    }

    private static String resolveRef(String value, Map<String, String> map, Set<String> visiting) {
        if (value == null || value.isBlank()) return null;
        if (!value.startsWith("#")) return value;
        String key = value.substring(1);
        if (!visiting.add(key)) return null;
        String target = map.get(key);
        if (target == null) return null;
        return resolveRef(target, map, visiting);
    }
}
