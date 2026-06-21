package pl.skinstudio.model;

import org.bukkit.Material;
import pl.skinstudio.model.SkinCategory;

import java.util.List;

public class SkinDefinition {

    private final String id;
    private final String displayName;
    private final String itemModel;
    private final String sourceItemModel;
    private final String sourceModel;
    private final String equipmentAsset; // np. "elitemobs:bronze" dla zbroi, "" dla broni
    private final List<Material> allowedTypes;
    private final SkinCategory category;

    public SkinDefinition(String id, String displayName, String itemModel, String equipmentAsset,
                          List<Material> allowedTypes, SkinCategory category) {
        this(id, displayName, itemModel, itemModel, equipmentAsset, allowedTypes, category);
    }

    public SkinDefinition(String id, String displayName, String itemModel, String sourceItemModel,
                          String equipmentAsset, List<Material> allowedTypes, SkinCategory category) {
        this(id, displayName, itemModel, sourceItemModel, "", equipmentAsset, allowedTypes, category);
    }

    public SkinDefinition(String id, String displayName, String itemModel, String sourceItemModel, String sourceModel,
                          String equipmentAsset, List<Material> allowedTypes, SkinCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.itemModel = itemModel;
        this.sourceItemModel = (sourceItemModel == null || sourceItemModel.isBlank()) ? itemModel : sourceItemModel;
        this.sourceModel = sourceModel == null ? "" : sourceModel;
        this.equipmentAsset = equipmentAsset;
        this.allowedTypes = allowedTypes;
        this.category = category == null ? SkinCategory.UNKNOWN : category;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getItemModel() { return itemModel; }
    public String getSourceItemModel() { return sourceItemModel; }
    public String getSourceModel() { return sourceModel; }
    public String getEquipmentAsset() { return equipmentAsset; }
    public List<Material> getAllowedTypes() { return allowedTypes; }
    public SkinCategory getCategory() { return category; }

    public boolean hasEquipmentAsset() {
        return equipmentAsset != null && !equipmentAsset.isEmpty();
    }

    public boolean isCompatibleWith(Material material) {
        return allowedTypes.contains(material);
    }
}
