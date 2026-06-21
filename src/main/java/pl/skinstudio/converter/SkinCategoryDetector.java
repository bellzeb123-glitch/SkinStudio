package pl.skinstudio.converter;

import pl.skinstudio.model.SkinCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Heurystyki rozpoznawania kategorii skina po ścieżce / nazwie pliku. */
public final class SkinCategoryDetector {

    private static final Map<String, List<String>> WEAPON_SUFFIXES = weaponSuffixes();
    private static final Map<String, List<String>> ARMOR_SUFFIXES = armorSuffixes();

    private static Map<String, List<String>> weaponSuffixes() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("_sword", List.of("DIAMOND_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "STONE_SWORD", "WOODEN_SWORD", "NETHERITE_SWORD"));
        m.put("_axe", List.of("DIAMOND_AXE", "IRON_AXE", "GOLDEN_AXE", "STONE_AXE", "WOODEN_AXE", "NETHERITE_AXE"));
        m.put("_bow", List.of("BOW"));
        m.put("_crossbow", List.of("CROSSBOW"));
        m.put("_scythe", List.of("DIAMOND_HOE", "IRON_HOE", "GOLDEN_HOE", "STONE_HOE", "WOODEN_HOE", "NETHERITE_HOE"));
        m.put("_trident", List.of("TRIDENT"));
        m.put("_spear", List.of("TRIDENT"));
        m.put("_mace", List.of("MACE", "DIAMOND_AXE", "IRON_AXE", "NETHERITE_AXE"));
        m.put("_toothpick", List.of("DIAMOND_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "NETHERITE_SWORD", "STICK"));
        m.put("_gladius", List.of("DIAMOND_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "NETHERITE_SWORD"));
        m.put("_dagger", List.of("DIAMOND_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "NETHERITE_SWORD"));
        m.put("_staff", List.of("DIAMOND_HOE", "IRON_HOE", "GOLDEN_HOE", "STICK"));
        m.put("_wand", List.of("DIAMOND_HOE", "IRON_HOE", "GOLDEN_HOE", "STICK"));
        m.put("_hammer", List.of("MACE", "DIAMOND_AXE", "IRON_AXE", "NETHERITE_AXE"));
        m.put("_greatsword", List.of("DIAMOND_SWORD", "IRON_SWORD", "NETHERITE_SWORD"));
        m.put("_katana", List.of("DIAMOND_SWORD", "IRON_SWORD", "NETHERITE_SWORD"));
        m.put("_lance", List.of("TRIDENT"));
        m.put("_pike", List.of("TRIDENT"));
        return Map.copyOf(m);
    }

    private static Map<String, List<String>> armorSuffixes() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("_helmet", List.of("DIAMOND_HELMET", "IRON_HELMET", "GOLDEN_HELMET", "LEATHER_HELMET", "NETHERITE_HELMET", "CHAINMAIL_HELMET"));
        m.put("_chestplate", List.of("DIAMOND_CHESTPLATE", "IRON_CHESTPLATE", "GOLDEN_CHESTPLATE", "LEATHER_CHESTPLATE", "NETHERITE_CHESTPLATE", "CHAINMAIL_CHESTPLATE"));
        m.put("_leggings", List.of("DIAMOND_LEGGINGS", "IRON_LEGGINGS", "GOLDEN_LEGGINGS", "LEATHER_LEGGINGS", "NETHERITE_LEGGINGS", "CHAINMAIL_LEGGINGS"));
        m.put("_boots", List.of("DIAMOND_BOOTS", "IRON_BOOTS", "GOLDEN_BOOTS", "LEATHER_BOOTS", "NETHERITE_BOOTS", "CHAINMAIL_BOOTS"));
        return Map.copyOf(m);
    }

    private static final List<String> MOUNT_PATH_SEGMENTS = List.of(
        "mounts", "mount", "vehicles", "vehicle", "pets", "pet", "ridables", "ridable"
    );

    private static final List<String> MOUNT_NAME_HINTS = List.of(
        "_mount", "_horse", "_pegasus", "_dragon", "_griffin", "_wyvern", "_saddle",
        "_steed", "_raptor", "_unicorn", "_phantom_mount", "_vehicle"
    );

    private static final List<String> DEFAULT_MOUNT_MATERIALS = List.of(
        "SADDLE", "CARROT_ON_A_STICK", "GOLDEN_CARROT", "LEAD", "NAME_TAG"
    );

    private SkinCategoryDetector() {}

    public record Detection(SkinCategory category, List<String> itemTypes) {}

    public static Detection detect(String fullPath, String fileName, boolean isEquipmentPiece) {
        String lowerPath = fullPath.toLowerCase(Locale.ROOT);
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        if (isMountPath(lowerPath, lowerName)) {
            return new Detection(SkinCategory.MOUNT, DEFAULT_MOUNT_MATERIALS);
        }
        if (isEquipmentPiece) {
            for (var e : ARMOR_SUFFIXES.entrySet()) {
                if (lowerName.contains(e.getKey())) {
                    return new Detection(SkinCategory.ARMOR, e.getValue());
                }
            }
            return new Detection(SkinCategory.ARMOR, List.of("LEATHER_CHESTPLATE"));
        }
        for (var e : WEAPON_SUFFIXES.entrySet()) {
            if (lowerName.contains(e.getKey())) {
                return new Detection(SkinCategory.WEAPON, e.getValue());
            }
        }
        if (lowerName.contains("_pickaxe") || lowerName.contains("_shovel") || lowerName.contains("_hoe")) {
            return new Detection(SkinCategory.TOOL, List.of("DIAMOND_PICKAXE", "IRON_PICKAXE", "NETHERITE_PICKAXE"));
        }
        return new Detection(SkinCategory.UNKNOWN, List.of());
    }

    private static boolean isMountPath(String lowerPath, String lowerName) {
        for (String seg : MOUNT_PATH_SEGMENTS) {
            if (lowerPath.contains("/" + seg + "/") || lowerPath.contains("/items/" + seg + "/")) {
                return true;
            }
        }
        for (String hint : MOUNT_NAME_HINTS) {
            if (lowerName.contains(hint)) return true;
        }
        return false;
    }

    public static List<String> deduceItemTypes(String fileName) {
        Detection d = detect(fileName, fileName, false);
        if (!d.itemTypes().isEmpty()) return d.itemTypes();
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (var e : WEAPON_SUFFIXES.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        for (var e : ARMOR_SUFFIXES.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return List.of();
    }
}
