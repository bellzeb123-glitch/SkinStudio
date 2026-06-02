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

    private static final String TOKEN_TYPE_CHANGE = "change";
    private static final String TOKEN_TYPE_SKIN   = "skin";

    // ── Tworzenie tokenów ────────────────────────────────────

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

    // ── Odczyt tokenów ───────────────────────────────────────

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

    // ── Operacje na przedmiotach ─────────────────────────────

    public static boolean hasCustomSkin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(new NamespacedKey(SkinStudio.getInstance(), KEY_ORIGINAL_MODEL), PersistentDataType.STRING);
    }

    /**
     * Nakłada skin — ustawia item_model i opcjonalnie equippable model (tekstura zbroi na ciele).
     */
    public static ItemStack applySkin(ItemStack item, SkinDefinition skin) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey originalModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey originalEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);

        // Zapisz oryginalny item_model (tylko raz — przy pierwszym nałożeniu)
        if (!pdc.has(originalModelKey, PersistentDataType.STRING)) {
            NamespacedKey currentModel = meta.getItemModel();
            pdc.set(originalModelKey, PersistentDataType.STRING,
                currentModel != null ? currentModel.toString() : "");
        }

        // Zapisz oryginalny equippable model (tylko raz)
        if (!pdc.has(originalEquipKey, PersistentDataType.STRING)) {
            String currentEquip = "";
            if (meta.hasEquippable()) {
                EquippableComponent eq = meta.getEquippable();
                NamespacedKey modelKey = eq.getModel();
                if (modelKey != null) currentEquip = modelKey.toString();
            }
            pdc.set(originalEquipKey, PersistentDataType.STRING, currentEquip);
        }

        // Ustaw nowy item_model (miniaturka)
        meta.setItemModel(parseKey(skin.getItemModel()));

        // Ustaw equippable model (tekstura na ciele gracza) — tylko dla zbroi
        if (skin.hasEquipmentAsset()) {
            EquipmentSlot slot = getEquipmentSlot(item.getType());
            if (slot != null) {
                EquippableComponent eq = meta.hasEquippable()
                    ? meta.getEquippable()
                    : meta.getEquippable(); // getEquippable() tworzy nowy jeśli nie istnieje
                eq.setModel(parseKey(skin.getEquipmentAsset()));
                meta.setEquippable(eq);
            }
        }

        result.setItemMeta(meta);
        return result;
    }

    /**
     * Zdejmuje skin — przywraca oryginalny item_model i equippable model.
     */
    public static ItemStack removeSkin(ItemStack item) {
        if (!hasCustomSkin(item)) return null;
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey originalModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey originalEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);

        String originalModel = pdc.get(originalModelKey, PersistentDataType.STRING);
        String originalEquip = pdc.getOrDefault(originalEquipKey, PersistentDataType.STRING, "");

        pdc.remove(originalModelKey);
        pdc.remove(originalEquipKey);

        // Przywróć item_model
        meta.setItemModel(originalModel != null && !originalModel.isEmpty()
            ? parseKey(originalModel) : null);

        // Przywróć equippable model
        if (meta.hasEquippable()) {
            EquippableComponent eq = meta.getEquippable();
            eq.setModel(originalEquip.isEmpty() ? null : parseKey(originalEquip));
            meta.setEquippable(eq);
        }

        result.setItemMeta(meta);
        return result;
    }

    // ── Pomocnicze ───────────────────────────────────────────

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
