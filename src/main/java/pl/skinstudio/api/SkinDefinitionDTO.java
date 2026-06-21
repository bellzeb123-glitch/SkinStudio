package pl.skinstudio.api;

import org.bukkit.Material;
import pl.skinstudio.model.SkinCategory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Public snapshot of a skin definition for companion plugins (BellItems, BellMarket).
 */
public final class SkinDefinitionDTO {

    private final String id;
    private final String displayName;
    private final String itemModel;
    private final String equipmentAsset;
    private final List<String> itemTypes;
    private final SkinCategory category;

    public SkinDefinitionDTO(String id, String displayName, String itemModel,
                             String equipmentAsset, List<String> itemTypes, SkinCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.itemModel = itemModel == null ? "" : itemModel;
        this.equipmentAsset = equipmentAsset == null ? "" : equipmentAsset;
        this.itemTypes = itemTypes == null
            ? List.of()
            : List.copyOf(itemTypes);
        this.category = category == null ? SkinCategory.UNKNOWN : category;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getItemModel() { return itemModel; }
    public String getEquipmentAsset() { return equipmentAsset; }
    public List<String> getItemTypes() { return itemTypes; }
    public SkinCategory getCategory() { return category; }

    public boolean hasEquipmentAsset() {
        return !equipmentAsset.isEmpty();
    }

    public boolean isCompatibleWith(Material material) {
        if (material == null) return false;
        return itemTypes.stream().anyMatch(t -> material.name().equalsIgnoreCase(t));
    }

    public static SkinDefinitionDTO fromInternal(pl.skinstudio.model.SkinDefinition skin) {
        List<String> types = skin.getAllowedTypes().stream()
            .map(Material::name)
            .collect(Collectors.toList());
        return new SkinDefinitionDTO(
            skin.getId(),
            skin.getDisplayName(),
            skin.getItemModel(),
            skin.getEquipmentAsset(),
            types,
            skin.getCategory()
        );
    }
}
