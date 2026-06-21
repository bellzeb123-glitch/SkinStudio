package pl.skinstudio.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.model.SkinCategory;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class SkinStudioAPIImpl implements SkinStudioAPI {

    private final SkinStudio plugin;

    SkinStudioAPIImpl(SkinStudio plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return plugin.isEnabled() && plugin.getSkinConfig() != null;
    }

    @Override
    public Collection<String> getAllSkinIds() {
        return plugin.getSkinConfig().getAllSkins().keySet();
    }

    @Override
    public Optional<SkinDefinitionDTO> getSkin(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
        return skin == null ? Optional.empty() : Optional.of(SkinDefinitionDTO.fromInternal(skin));
    }

    @Override
    public ItemStack applySkinToItem(ItemStack item, String skinId) {
        if (item == null || skinId == null || skinId.isEmpty()) return item;
        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) return item;
        return TokenUtil.applySkin(item, skin);
    }

    @Override
    public List<SkinDefinitionDTO> listSkinsForMaterial(Material material) {
        if (material == null) return List.of();
        SkinConfig config = plugin.getSkinConfig();
        List<SkinDefinitionDTO> out = new ArrayList<>();
        for (SkinDefinition skin : config.getAllSkins().values()) {
            if (skin.isCompatibleWith(material)) {
                out.add(SkinDefinitionDTO.fromInternal(skin));
            }
        }
        out.sort(Comparator.comparing(SkinDefinitionDTO::getId));
        return out;
    }

    @Override
    public List<SkinDefinitionDTO> listSkinsForCategory(SkinCategory category) {
        if (category == null) return List.of();
        List<SkinDefinitionDTO> out = new ArrayList<>();
        for (SkinDefinition skin : plugin.getSkinConfig().getAllSkins().values()) {
            if (skin.getCategory() == category) {
                out.add(SkinDefinitionDTO.fromInternal(skin));
            }
        }
        out.sort(Comparator.comparing(SkinDefinitionDTO::getId));
        return out;
    }

    @Override
    public boolean isCompatible(String skinId, Material material) {
        if (skinId == null || material == null) return false;
        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        return skin != null && skin.isCompatibleWith(material);
    }
}
