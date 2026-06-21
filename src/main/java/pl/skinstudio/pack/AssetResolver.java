package pl.skinstudio.pack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rozwiązuje pełny łańcuch zależności skina w merged packu.
 */
public final class AssetResolver {

    private final PackIndex index;

    public AssetResolver(PackIndex index) {
        this.index = index;
    }

    public record Resolution(
        String itemModel,
        Set<String> requiredPaths,
        List<String> missing,
        boolean itemDefinitionPresent
    ) {
        public boolean complete() {
            return itemDefinitionPresent && missing.isEmpty();
        }
    }

    public Resolution resolve(String itemModel) {
        Set<String> required = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();

        ResourceLocation item = ResourceLocation.parse(itemModel);
        if (item == null) {
            return new Resolution(itemModel, required, List.of("Niepoprawny item_model"), false);
        }

        String itemPath = item.itemDefinitionPath();
        if (!index.contains(itemPath)) {
            missing.add(itemPath);
            return new Resolution(itemModel, required, missing, false);
        }
        required.add(itemPath);

        Set<String> modelRefs = ItemModelDefinition.extractModelRefs(index.get(itemPath));
        if (modelRefs.isEmpty()) {
            missing.add("(brak modelu w definicji " + itemPath + ")");
        }

        Set<String> visitedModels = new LinkedHashSet<>();
        for (String ref : modelRefs) {
            resolveModel(ResourceLocation.parse(ref, item.namespace()), required, missing, visitedModels);
        }
        return new Resolution(itemModel, required, missing, true);
    }

    /** Rozwiązuje pack bez pliku assets/<ns>/items/*.json — bezpośrednio z assets/<ns>/models/*.json. */
    public Resolution resolveModelReference(String modelRef) {
        Set<String> required = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        ResourceLocation model = ResourceLocation.parse(modelRef);
        if (model == null) {
            return new Resolution(modelRef, required, List.of("Niepoprawny model ref"), false);
        }
        resolveModel(model, required, missing, new LinkedHashSet<>());
        return new Resolution(modelRef, required, missing, true);
    }

    private void resolveModel(ResourceLocation modelLoc, Set<String> required,
                              List<String> missing, Set<String> visited) {
        if (modelLoc == null) return;
        String modelPath = modelLoc.modelPath();
        if (!visited.add(modelPath)) return;

        String defaultNs = modelLoc.namespace();

        if (!index.contains(modelPath)) {
            if (!"minecraft".equals(defaultNs)) {
                missing.add(modelPath);
            }
            return;
        }
        required.add(modelPath);

        ModelFile model = ModelFile.parse(index.get(modelPath));

        if (model.parent() != null) {
            ResourceLocation parent = ResourceLocation.parse(model.parent(), defaultNs);
            if (parent != null && !isVanillaParent(parent)) {
                resolveModel(parent, required, missing, visited);
            }
        }

        for (String texRef : model.textureRefs()) {
            resolveTexture(texRef, defaultNs, required, missing);
        }
    }

    private void resolveTexture(String texRef, String defaultNs, Set<String> required, List<String> missing) {
        ResourceLocation tex = ResourceLocation.parse(texRef, defaultNs);
        if (tex == null) return;

        String found = findTexturePath(tex);
        if (found != null) {
            required.add(found);
            String mcmeta = found + ".mcmeta";
            if (index.contains(mcmeta)) required.add(mcmeta);
            return;
        }
        if (!"minecraft".equals(tex.namespace())) {
            missing.add(tex.texturePath() + " (ref: " + texRef + ")");
        }
    }

    private String findTexturePath(ResourceLocation tex) {
        for (String candidate : tex.texturePathCandidates()) {
            if (index.contains(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isVanillaParent(ResourceLocation loc) {
        if (!"minecraft".equals(loc.namespace())) return false;
        String p = loc.path();
        return p.startsWith("item/") || p.startsWith("block/") || p.startsWith("builtin/");
    }
}
