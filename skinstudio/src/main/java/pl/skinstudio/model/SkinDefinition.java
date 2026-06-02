package pl.skinstudio.model;

import org.bukkit.Material;
import java.util.List;

public class SkinDefinition {

    private final String id;
    private final String displayName;
    private final String itemModel;
    private final String equipmentAsset; // np. "elitemobs:bronze" dla zbroi, "" dla broni
    private final List<Material> allowedTypes;

    public SkinDefinition(String id, String displayName, String itemModel, String equipmentAsset, List<Material> allowedTypes) {
        this.id = id;
        this.displayName = displayName;
        this.itemModel = itemModel;
        this.equipmentAsset = equipmentAsset;
        this.allowedTypes = allowedTypes;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getItemModel() { return itemModel; }
    public String getEquipmentAsset() { return equipmentAsset; }
    public List<Material> getAllowedTypes() { return allowedTypes; }

    public boolean hasEquipmentAsset() {
        return equipmentAsset != null && !equipmentAsset.isEmpty();
    }

    public boolean isCompatibleWith(Material material) {
        return allowedTypes.contains(material);
    }
}
