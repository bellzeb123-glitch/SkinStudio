package pl.skinstudio.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkinConfig {

    private final SkinStudio plugin;
    private final Map<String, SkinDefinition> skins = new HashMap<>();

    public SkinConfig(SkinStudio plugin) {
        this.plugin = plugin;
    }

    public void load() {
        skins.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("skins");
        if (section == null) {
            plugin.getLogger().warning("Brak sekcji 'skins' w config.yml!");
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;

            String displayName    = s.getString("display-name", "&fToken Skina");
            String itemModel      = s.getString("item-model", "");
            String equipmentAsset = s.getString("equipment-asset", "");
            List<String> typeNames = s.getStringList("item-types");

            List<Material> materials = new ArrayList<>();
            for (String typeName : typeNames) {
                Material mat = Material.getMaterial(typeName.toUpperCase());
                if (mat != null) materials.add(mat);
                else plugin.getLogger().warning("Nieznany materiał '" + typeName + "' w skinie: " + id);
            }

            skins.put(id, new SkinDefinition(id, displayName, itemModel, equipmentAsset, materials));
        }

        plugin.getLogger().info("Załadowano " + skins.size() + " definicji skinów.");
    }

    public SkinDefinition getSkin(String id) { return skins.get(id); }
    public Map<String, SkinDefinition> getAllSkins() { return skins; }

    public String getChangeTokenName() {
        return plugin.getConfig().getString("change-token.display-name", "&6✦ Token Zmiany");
    }

    public Material getChangeTokenMaterial() {
        String matName = plugin.getConfig().getString("change-token.material", "NETHER_STAR");
        Material mat = Material.getMaterial(matName);
        return mat != null ? mat : Material.NETHER_STAR;
    }

    public List<String> getChangeTokenLore() {
        return plugin.getConfig().getStringList("change-token.lore");
    }

    public Material getSkinTokenMaterial() {
        String matName = plugin.getConfig().getString("skin-token-material", "PAPER");
        Material mat = Material.getMaterial(matName);
        return mat != null ? mat : Material.PAPER;
    }
}
