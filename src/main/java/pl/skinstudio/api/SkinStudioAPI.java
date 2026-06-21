package pl.skinstudio.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import pl.skinstudio.model.SkinCategory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Public API for SkinStudio — used by BellItems and other companion plugins.
 * Obtain via {@link #get()} when {@link #isAvailable()} is true.
 */
public interface SkinStudioAPI {

    /**
     * Returns the API instance, or {@code null} if SkinStudio is not loaded.
     */
    static SkinStudioAPI get() {
        return SkinStudioAPIProvider.get();
    }

    /** Whether SkinStudio is enabled and skin config is loaded. */
    boolean isAvailable();

    Collection<String> getAllSkinIds();

    Optional<SkinDefinitionDTO> getSkin(String id);

    /** Applies a configured skin to the item (item_model + equippable when needed). */
    ItemStack applySkinToItem(ItemStack item, String skinId);

    /** Skins compatible with the given base material. */
    List<SkinDefinitionDTO> listSkinsForMaterial(Material material);

    /** Skins of a given category (e.g. MOUNT for BellMount). */
    List<SkinDefinitionDTO> listSkinsForCategory(SkinCategory category);

    boolean isCompatible(String skinId, Material material);
}
