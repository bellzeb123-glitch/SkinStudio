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
        lore.add(colorize("&eUżyj w Skin Studio aby nałożyć skin."));
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

    /** Odczytuje skin_id zapisane w przedmiocie po nałożeniu skina */
    public static String getSkinIdFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
            .get(new NamespacedKey(SkinStudio.getInstance(), KEY_SKIN_ID), PersistentDataType.STRING);
    }

    public static boolean hasCustomSkin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(new NamespacedKey(SkinStudio.getInstance(), KEY_ORIGINAL_MODEL), PersistentDataType.STRING);
    }

    // ── Aplikowanie skina ────────────────────────────────────

    public static ItemStack applySkin(ItemStack item, SkinDefinition skin) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey origModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey origEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);
        NamespacedKey hadEquipKey  = new NamespacedKey(plugin, KEY_HAD_EQUIPPABLE);

        // Zapisz oryginalny item_model (tylko raz)
        if (!pdc.has(origModelKey, PersistentDataType.STRING)) {
            NamespacedKey cur = meta.getItemModel();
            pdc.set(origModelKey, PersistentDataType.STRING, cur != null ? cur.toString() : "");
        }

        // Zapisz skin_id w przedmiocie (potrzebne do zwrotu tokenu przy zdejmowaniu)
        NamespacedKey skinIdKey = new NamespacedKey(plugin, KEY_SKIN_ID);
        pdc.set(skinIdKey, PersistentDataType.STRING, skin.getId());

        // Ustaw nowy item_model
        meta.setItemModel(parseKey(skin.getItemModel()));

        // Obsługa equipment texture — dla napierśnika, spodni, butów ORAZ hełmu
        // W MC 26.1.2 equippable komponent jest wymagany aby custom model
        // był widoczny na założonej zbroi/hełmie
        if (skin.hasEquipmentAsset()) {
            EquipmentSlot eqSlot = getEquipmentSlot(item.getType());
            if (eqSlot != null) {
                // Zapisz stan oryginalnego equippable (tylko raz)
                if (!pdc.has(hadEquipKey, PersistentDataType.BYTE)) {
                    pdc.set(hadEquipKey, PersistentDataType.BYTE,
                        meta.hasEquippable() ? (byte)1 : (byte)0);
                }
                if (!pdc.has(origEquipKey, PersistentDataType.STRING)) {
                    String origEquip = "";
                    if (meta.hasEquippable()) {
                        NamespacedKey m = meta.getEquippable().getModel();
                        if (m != null) origEquip = m.toString();
                    }
                    pdc.set(origEquipKey, PersistentDataType.STRING, origEquip);
                }

                // Pobierz lub utwórz equippable komponent
                EquippableComponent eq = meta.getEquippable();
                // Zawsze ustaw slot ręcznie — w MC 26.1.2 getEquippable()
                // może zwrócić pusty komponent bez slotu
                eq.setSlot(eqSlot);
                eq.setModel(parseKey(skin.getEquipmentAsset()));
                meta.setEquippable(eq);
            }
        } else {
            // Brak equipment-asset w configu (broń lub hełm bez tekstury)
            // Dla hełmów: upewnij się że equippable z HEAD slotem jest ustawiony
            // żeby item mógł być założony na głowę
            EquipmentSlot eqSlot = getEquipmentSlot(item.getType());
            if (eqSlot == EquipmentSlot.HEAD && !meta.hasEquippable()) {
                if (!pdc.has(hadEquipKey, PersistentDataType.BYTE)) {
                    pdc.set(hadEquipKey, PersistentDataType.BYTE, (byte)0);
                }
                EquippableComponent eq = meta.getEquippable();
                eq.setSlot(EquipmentSlot.HEAD);
                meta.setEquippable(eq);
            }
        }

        result.setItemMeta(meta);
        return result;
    }

    // ── Zdejmowanie skina ────────────────────────────────────

    public static ItemStack removeSkin(ItemStack item) {
        if (!hasCustomSkin(item)) return null;
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey origModelKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        NamespacedKey origEquipKey = new NamespacedKey(plugin, KEY_ORIGINAL_EQUIP);
        NamespacedKey hadEquipKey  = new NamespacedKey(plugin, KEY_HAD_EQUIPPABLE);

        String origModel = pdc.get(origModelKey, PersistentDataType.STRING);
        String origEquip = pdc.getOrDefault(origEquipKey, PersistentDataType.STRING, "");
        byte   hadEquip  = pdc.getOrDefault(hadEquipKey, PersistentDataType.BYTE, (byte)0);

        pdc.remove(origModelKey);
        pdc.remove(origEquipKey);
        pdc.remove(hadEquipKey);
        pdc.remove(new NamespacedKey(plugin, KEY_SKIN_ID));

        // Przywróć item_model
        meta.setItemModel(origModel != null && !origModel.isEmpty() ? parseKey(origModel) : null);

        // Przywróć equippable dla wszystkich typów zbroi włącznie z hełmem
        EquipmentSlot slot = getEquipmentSlot(item.getType());
        if (slot != null) {
            if (hadEquip == 0) {
                if (meta.hasEquippable()) meta.setEquippable(null);
            } else if (meta.hasEquippable()) {
                EquippableComponent eq = meta.getEquippable();
                eq.setModel(origEquip.isEmpty() ? null : parseKey(origEquip));
                meta.setEquippable(eq);
            }
        }

        result.setItemMeta(meta);
        return result;
    }

    // ── Pomocnicze ───────────────────────────────────────────

    private static NamespacedKey parseKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] p = key.split(":", 2);
        return p.length == 2 ? new NamespacedKey(p[0], p[1]) : NamespacedKey.minecraft(key);
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
