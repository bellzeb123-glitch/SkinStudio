package pl.skinstudio.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;

import java.util.ArrayList;
import java.util.List;

public class TokenUtil {

    private static final String KEY_TOKEN_TYPE     = "skinstudio_token_type";
    private static final String KEY_SKIN_ID        = "skinstudio_skin_id";
    private static final String KEY_ORIGINAL_MODEL = "skinstudio_original_model";
    private static final String KEY_ORIGINAL_EQUIP = "skinstudio_original_equip";
    private static final String KEY_HAD_EQUIPPABLE = "skinstudio_had_equippable";

    private static final String TOKEN_TYPE_CHANGE = "change";
    private static final String TOKEN_TYPE_SKIN   = "skin";

    public static ItemStack createChangeToken() {
        SkinStudio plugin = SkinStudio.getInstance();
        ItemStack item = new ItemStack(plugin.getSkinConfig().getChangeTokenMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getSkinConfig().getChangeTokenName()));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getSkinConfig().getChangeTokenLore()) lore.add(colorize(line));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, KEY_TOKEN_TYPE), PersistentDataType.STRING, TOKEN_TYPE_CHANGE);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack createSkinToken(SkinDefinition skin) {
        SkinStudio plugin = SkinStudio.getInstance();
        ItemStack item = new ItemStack(plugin.getSkinConfig().getSkinTokenMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(skin.getDisplayName()));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Model: &f" + skin.getItemModel()));
        lore.add(colorize("&7ID: &f" + skin.getId()));
        lore.add(Component.empty());
        lore.add(colorize("&eUżyj w Skin Studio aby"));
        lore.add(colorize("&enałożyć skin na przedmiot."));
        meta.lore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, KEY_TOKEN_TYPE), PersistentDataType.STRING, TOKEN_TYPE_SKIN);
        pdc.set(new NamespacedKey(plugin, KEY_SKIN_ID),    PersistentDataType.STRING, skin.getId());
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isChangeToken(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return TOKEN_TYPE_CHANGE.equals(item.getItemMeta().getPersistentDataContainer()
            .get(new NamespacedKey(SkinStudio.getInstance(), KEY_TOKEN_TYPE), PersistentDataType.STRING));
    }

    public static boolean isSkinToken(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return TOKEN_TYPE_SKIN.equals(item.getItemMeta().getPersistentDataContainer()
            .get(new NamespacedKey(SkinStudio.getInstance(), KEY_TOKEN_TYPE), PersistentDataType.STRING));
    }

    public static String getSkinIdFromToken(ItemStack item) {
        if (!isSkinToken(item)) return null;
        return item.getItemMeta().getPersistentDataContainer()
            .get(new NamespacedKey(SkinStudio.getInstance(), KEY_SKIN_ID), PersistentDataType.STRING);
    }

    public static boolean hasCustomSkin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(new NamespacedKey(SkinStudio.getInstance(), KEY_ORIGINAL_MODEL), PersistentDataType.STRING);
    }

    public static ItemStack applySkin(ItemStack item, SkinDefinition skin) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey originalModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey originalEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);
        NamespacedKey hadEquippableKey = new NamespacedKey(plugin, KEY_HAD_EQUIPPABLE);

        // Zapisz oryginał item_model
        if (!pdc.has(originalModelKey, PersistentDataType.STRING)) {
            NamespacedKey currentModel = meta.getItemModel();
            pdc.set(originalModelKey, PersistentDataType.STRING,
                currentModel != null ? currentModel.toString() : "");
        }

        // Ustaw nowy item_model
        meta.setItemModel(parseKey(skin.getItemModel()));

        // Obsługa equippable tylko dla zbroi z equipment-asset
        if (skin.hasEquipmentAsset()) {
            EquipmentSlot eqSlot = getEquipmentSlot(item.getType());
            if (eqSlot != null) {
                if (!pdc.has(hadEquippableKey, PersistentDataType.BYTE)) {
                    pdc.set(hadEquippableKey, PersistentDataType.BYTE,
                        meta.hasEquippable() ? (byte) 1 : (byte) 0);
                }
                if (!pdc.has(originalEquipKey, PersistentDataType.STRING)) {
                    String currentEquipModel = "";
                    if (meta.hasEquippable()) {
                        NamespacedKey m = meta.getEquippable().getModel();
                        if (m != null) currentEquipModel = m.toString();
                    }
                    pdc.set(originalEquipKey, PersistentDataType.STRING, currentEquipModel);
                }

                EquippableComponent eq = meta.getEquippable();
                // KLUCZOWE: zawsze ustaw slot — bez tego hełm/inne zbroje nie będą zakładalne
                eq.setSlot(eqSlot);
                eq.setModel(parseKey(skin.getEquipmentAsset()));
                meta.setEquippable(eq);
            }
        }

        result.setItemMeta(meta);
        return result;
    }

    public static ItemStack removeSkin(ItemStack item) {
        if (!hasCustomSkin(item)) return null;
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey originalModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey originalEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);
        NamespacedKey hadEquippableKey = new NamespacedKey(plugin, KEY_HAD_EQUIPPABLE);

        String originalModel = pdc.get(originalModelKey, PersistentDataType.STRING);
        String originalEquip = pdc.getOrDefault(originalEquipKey, PersistentDataType.STRING, "");
        byte   hadEquippable = pdc.getOrDefault(hadEquippableKey, PersistentDataType.BYTE, (byte) 0);

        pdc.remove(originalModelKey);
        pdc.remove(originalEquipKey);
        pdc.remove(hadEquippableKey);

        meta.setItemModel(originalModel != null && !originalModel.isEmpty()
            ? parseKey(originalModel) : null);

        if (hadEquippable == 1 && meta.hasEquippable()) {
            EquippableComponent eq = meta.getEquippable();
            eq.setModel(originalEquip.isEmpty() ? null : parseKey(originalEquip));
            meta.setEquippable(eq);
        } else if (hadEquippable == 0 && meta.hasEquippable()) {
            meta.setEquippable(null);
        }

        result.setItemMeta(meta);
        return result;
    }

    private static NamespacedKey parseKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] parts = key.split(":", 2);
        if (parts.length == 2) return new NamespacedKey(parts[0], parts[1]);
        return NamespacedKey.minecraft(key);
    }

    private static EquipmentSlot getEquipmentSlot(Material material) {
        String name = material.name();
        if (name.endsWith("_HELMET"))     return EquipmentSlot.HEAD;
        if (name.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (name.endsWith("_LEGGINGS"))   return EquipmentSlot.LEGS;
        if (name.endsWith("_BOOTS"))      return EquipmentSlot.FEET;
        return null;
    }

    private static Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
