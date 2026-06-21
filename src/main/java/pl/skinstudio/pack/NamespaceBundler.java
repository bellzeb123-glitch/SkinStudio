package pl.skinstudio.pack;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Dołącza całe namespace packa do builda — jak Nexo przy imporcie IA.
 * Eliminuje szachownicę gdy tekstury są w nietypowych ścieżkach lub {@code _iainternal}.
 */
public final class NamespaceBundler {

    private static final String IA_INTERNAL = "_iainternal";

    private NamespaceBundler() {}

    public static Set<String> bundleForSkin(String itemModel, PackIndex index,
                                            boolean includeNamespaceAssets,
                                            boolean includeIaInternal) {
        Set<String> paths = new LinkedHashSet<>();
        ResourceLocation item = ResourceLocation.parse(itemModel);
        if (item == null) return paths;

        if (includeNamespaceAssets) {
            addNamespaceAssets(index, item.namespace(), paths);
        }

        if (includeIaInternal) {
            addNamespaceAssets(index, IA_INTERNAL, paths);
        }

        return paths;
    }

    private static void addNamespaceAssets(PackIndex index, String namespace, Set<String> paths) {
        if (namespace == null || namespace.isBlank()) return;
        String prefix = "assets/" + namespace.toLowerCase(Locale.ROOT) + "/";
        paths.addAll(index.pathsWithPrefix(prefix));
    }
}
