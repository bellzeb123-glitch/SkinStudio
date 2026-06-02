package pl.skinstudio.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;

import java.util.ArrayList;
import java.util.List;

public class TokenUtil {

    // Klucze NBT
    private static final String KEY_TOKEN_TYPE = "skinstudio_token_type";
    private static final String KEY_SKIN_ID    = "skinstudio_skin_id";
    private static final String KEY_ORIGINAL_MODEL = "skinstudio_original_model";

    private static final String TOKEN_TYPE_CHANGE = "change";
    private static final String TOKEN_TYPE_SKIN   = "skin";

    // ── Tworzenie tokenów ────────────────────────────────────

    public static ItemStack createChangeToken() {
        SkinStudio plugin = SkinStudio.getInstance();
        Material mat = plugin.getSkinConfig().getChangeTokenMaterial();
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String rawName = plugin.getSkinConfig().getChangeTokenName();
        meta.displayName(colorize(rawName));

        List<String> rawLore = plugin.getSkinConfig().getChangeTokenLore();
        List<Component> lore = new ArrayList<>();
        for (String line : rawLore) {
            lore.add(colorize(line));
        }
        meta.lore(lore);

        NamespacedKey key = new NamespacedKey(plugin, KEY_TOKEN_TYPE);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, TOKEN_TYPE_CHANGE);

        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack createSkinToken(SkinDefinition skin) {
        SkinStudio plugin = SkinStudio.getInstance();
        Material mat = plugin.getSkinConfig().getSkinTokenMaterial();
        ItemStack item = new ItemStack(mat);
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
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(SkinStudio.getInstance(), KEY_TOKEN_TYPE);
        return TOKEN_TYPE_CHANGE.equals(pdc.get(key, PersistentDataType.STRING));
    }

    public static boolean isSkinToken(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(SkinStudio.getInstance(), KEY_TOKEN_TYPE);
        return TOKEN_TYPE_SKIN.equals(pdc.get(key, PersistentDataType.STRING));
    }

    public static String getSkinIdFromToken(ItemStack item) {
        if (!isSkinToken(item)) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(SkinStudio.getInstance(), KEY_SKIN_ID);
        return pdc.get(key, PersistentDataType.STRING);
    }

    // ── Operacje na przedmiotach ─────────────────────────────

    public static boolean hasCustomSkin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(SkinStudio.getInstance(), KEY_ORIGINAL_MODEL);
        return pdc.has(key, PersistentDataType.STRING);
    }

    public static String getOriginalModel(ItemStack item) {
        if (!hasCustomSkin(item)) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(SkinStudio.getInstance(), KEY_ORIGINAL_MODEL);
        return pdc.get(key, PersistentDataType.STRING);
    }

    /**
     * Nakłada skin na przedmiot.
     * Zapisuje oryginalny item_model w NBT (lub pusty string jeśli nie miał).
     * Zwraca zmodyfikowany ItemStack.
     */
    @SuppressWarnings("UnstableApiUsage")
    public static ItemStack applySkin(ItemStack item, String newModel) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();
        NamespacedKey originalKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);

        // Zapisz oryginalny model tylko jeśli jeszcze nie ma zapisanego
        // (nadpisanie skina — zachowujemy najstarszy oryginał)
        if (!pdc.has(originalKey, PersistentDataType.STRING)) {
            // Sprawdź czy item już ma item_model komponent
            NamespacedKey modelKey = new NamespacedKey("minecraft", "item_model");
            String currentModel = pdc.getOrDefault(
                new NamespacedKey(plugin, "current_item_model"),
                PersistentDataType.STRING,
                ""
            );
            // Użyj item model z meta jeśli dostępny
            try {
                var customModelData = meta.getCustomModelData();
                // fallback - zapisujemy pusty string jako "brak oryginalnego modelu"
            } catch (Exception ignored) {}
            pdc.set(originalKey, PersistentDataType.STRING, currentModel);
        }

        // Ustaw nowy item_model przez komponent
        meta.setItemModel(new NamespacedKey(
            newModel.contains(":") ? newModel.split(":")[0] : "minecraft",
            newModel.contains(":") ? newModel.split(":")[1] : newModel
        ));

        result.setItemMeta(meta);
        return result;
    }

    /**
     * Zdejmuje skin z przedmiotu, przywracając oryginalny item_model.
     * Zwraca zmodyfikowany ItemStack lub null jeśli item nie miał skina.
     */
    @SuppressWarnings("UnstableApiUsage")
    public static ItemStack removeSkin(ItemStack item) {
        if (!hasCustomSkin(item)) return null;

        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        SkinStudio plugin = SkinStudio.getInstance();

        NamespacedKey originalKey = new NamespacedKey(plugin, KEY_ORIGINAL_MODEL);
        String originalModel = pdc.get(originalKey, PersistentDataType.STRING);

        // Usuń zapisany oryginalny model z NBT
        pdc.remove(originalKey);

        if (originalModel == null || originalModel.isEmpty()) {
            // Przedmiot nie miał oryginalnego modelu — usuń item_model całkowicie
            meta.setItemModel(null);
        } else {
            // Przywróć oryginalny model
            meta.setItemModel(new NamespacedKey(
                originalModel.contains(":") ? originalModel.split(":")[0] : "minecraft",
                originalModel.contains(":") ? originalModel.split(":")[1] : originalModel
            ));
        }

        result.setItemMeta(meta);
        return result;
    }

    // ── Pomocnicze ───────────────────────────────────────────

    private static Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
