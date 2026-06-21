package pl.skinstudio.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parser vanilla item model definition (MC 1.21.4+, format ItemAdder/Nexo/Oraxen modern).
 * <p>
 * Plik {@code assets/<ns>/items/<id>.json} może mieć dowolny z typów:
 * {@code model}, {@code composite}, {@code condition}, {@code select},
 * {@code range_dispatch}, {@code bundle/selected_item}, {@code special}, {@code empty}.
 * Wyciągamy WSZYSTKIE referencje do modeli (rekurencyjnie), nie tylko prosty przypadek.
 */
public final class ItemModelDefinition {

    private ItemModelDefinition() {}

    /** Zwraca wszystkie modele (resource locations) wskazywane przez definicję itemu. */
    public static Set<String> extractModelRefs(byte[] itemJson) {
        Set<String> out = new LinkedHashSet<>();
        if (itemJson == null || itemJson.length == 0) return out;
        try {
            JsonElement root = JsonParser.parseString(new String(itemJson, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return out;
            JsonElement model = root.getAsJsonObject().get("model");
            collect(model, out);
        } catch (Exception ignored) {
            // niepoprawny JSON — brak modeli
        }
        return out;
    }

    private static void collect(JsonElement node, Set<String> out) {
        if (node == null) return;

        if (node.isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray()) collect(e, out);
            return;
        }
        if (!node.isJsonObject()) return;

        JsonObject obj = node.getAsJsonObject();
        String type = stripNs(asString(obj.get("type")));

        // Prosty model: { type: model, model: "ns:path" }
        if ("model".equals(type) || type == null) {
            JsonElement m = obj.get("model");
            if (m != null && m.isJsonPrimitive()) {
                String ref = m.getAsString();
                if (ref != null && !ref.isBlank()) out.add(ref);
            } else {
                // czasem "model" jest zagnieżdżonym węzłem
                collect(obj.get("model"), out);
            }
        }

        // composite: { models: [ node, ... ] }
        collect(obj.get("models"), out);

        // condition: on_true / on_false
        collect(obj.get("on_true"), out);
        collect(obj.get("on_false"), out);

        // select / range_dispatch: cases/entries[].model + fallback
        JsonArray cases = asArray(obj.get("cases"));
        if (cases != null) for (JsonElement c : cases) {
            if (c.isJsonObject()) collect(c.getAsJsonObject().get("model"), out);
        }
        JsonArray entries = asArray(obj.get("entries"));
        if (entries != null) for (JsonElement c : entries) {
            if (c.isJsonObject()) collect(c.getAsJsonObject().get("model"), out);
        }
        collect(obj.get("fallback"), out);

        // bundle/selected_item: { model: node }
        if ("bundle/selected_item".equals(type)) {
            collect(obj.get("model"), out);
        }
        // special, empty → brak modelu w katalogu models/
    }

    private static String asString(JsonElement e) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static JsonArray asArray(JsonElement e) {
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String stripNs(String type) {
        if (type == null) return null;
        int idx = type.indexOf(':');
        return idx >= 0 ? type.substring(idx + 1) : type;
    }
}
