package pl.skinstudio.model;

import org.bukkit.Material;
import java.util.List;

public class SkinDefinition {

    private final String id;
    private final String displayName;
    private final String itemModel;
    private final List<Material> allowedTypes;

    public SkinDefinition(String id, String displayName, String itemModel, List<Material> allowedTypes) {
        this.id = id;
        this.displayName = displayName;
        this.itemModel = itemModel;
        this.allowedTypes = allowedTypes;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getItemModel() {
        return itemModel;
    }

    public List<Material> getAllowedTypes() {
        return allowedTypes;
    }

    public boolean isCompatibleWith(Material material) {
        return allowedTypes.contains(material);
    }
}
