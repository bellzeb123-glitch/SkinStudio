package pl.skinstudio.converter;

import org.bukkit.configuration.file.YamlConfiguration;
import pl.skinstudio.model.SkinCategory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Manifest {@code skin.yml} — jawna definicja skina (jak wpis EliteMobs, bez zgadywania). */
public record SkinBundleManifest(
    String id,
    String displayName,
    String tier,
    SkinCategory category,
    List<String> itemTypes,
    String itemModel,
    String sourceModel,
    String equipmentAsset
) {
    public static SkinBundleManifest load(File skinYml) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(skinYml);
        String id = required(yml.getString("id"), "id");
        String displayName = required(yml.getString("display-name"), "display-name");

        List<String> types = new ArrayList<>();
        types.addAll(yml.getStringList("item-types"));
        types.addAll(yml.getStringList("materials"));
        if (types.isEmpty()) {
            throw new IllegalArgumentException("skin.yml: wymagane item-types lub materials");
        }

        String itemModel = blankToNull(yml.getString("item-model"));
        String sourceModel = blankToNull(yml.getString("source-model"));
        if (itemModel == null && sourceModel == null) {
            throw new IllegalArgumentException("skin.yml: podaj item-model LUB source-model");
        }

        String tier = yml.getString("tier");
        if (tier == null || tier.isBlank()) {
            tier = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
        }

        SkinCategory category = SkinCategory.fromConfig(yml.getString("category", "unknown"));

        return new SkinBundleManifest(
            id.toLowerCase(Locale.ROOT),
            displayName,
            tier.toLowerCase(Locale.ROOT),
            category,
            types,
            itemModel,
            sourceModel,
            blankToNull(yml.getString("equipment-asset"))
        );
    }

    public String resolveSourceItemModel() {
        if (itemModel != null) return itemModel;
        return null;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("skin.yml: brak wymaganego pola '" + field + "'");
        }
        return value;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
