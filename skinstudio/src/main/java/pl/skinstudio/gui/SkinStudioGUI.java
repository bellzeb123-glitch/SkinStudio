package pl.skinstudio.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class SkinStudioGUI implements Listener {

    /*
     * Układ GUI (3 rzędy = 27 slotów):
     *
     *  [0][1][2][3][4][5][6][7][8]   <- rząd 1
     *  [9][A][B][C][D][E][F][G][H]   <- rząd 2
     *  [I][J][K][L][M][N][O][P][Q]   <- rząd 3
     *
     *  Slot 9  = opis "Token Zmiany"   (zablokowany, tylko do czytania)
     *  Slot 10 = PUSTY slot roboczy    (tu gracz wkłada Token Zmiany)
     *  Slot 12 = opis "Przedmiot"      (zablokowany)
     *  Slot 13 = PUSTY slot roboczy    (tu gracz wkłada broń/zbroję)
     *  Slot 15 = opis "Token Skina"    (zablokowany)
     *  Slot 16 = PUSTY slot roboczy    (tu gracz wkłada Token Skina)
     *  Slot 22 = przycisk Zastosuj
     *  Reszta  = szare szkło (zablokowane)
     */

    private static final int SLOT_CHANGE_LABEL = 9;
    private static final int SLOT_CHANGE_TOKEN = 10;
    private static final int SLOT_ITEM_LABEL   = 12;
    private static final int SLOT_ITEM         = 13;
    private static final int SLOT_SKIN_LABEL   = 15;
    private static final int SLOT_SKIN_TOKEN   = 16;
    private static final int SLOT_APPLY        = 22;

    private static final Set<Integer> WORKING_SLOTS = Set.of(SLOT_CHANGE_TOKEN, SLOT_ITEM, SLOT_SKIN_TOKEN);

    private final SkinStudio plugin;
    private final Map<UUID, Inventory> openGuis = new HashMap<>();

    public SkinStudioGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openFor(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&8✦ &6Skin Studio &8✦"));
        buildGui(inv);
        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private void buildGui(Inventory inv) {
        // Tło — szare szkło wszędzie
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        // Sloty robocze — puste (null)
        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, null);
        inv.setItem(SLOT_SKIN_TOKEN, null);

        // Etykiety obok slotów roboczych
        inv.setItem(SLOT_CHANGE_LABEL, makeItem(Material.LIME_STAINED_GLASS_PANE,
            "&a▼ Token Zmiany",
            List.of("&7Wrzuć tutaj Token Zmiany.", "&7Wymagany zawsze.")));

        inv.setItem(SLOT_ITEM_LABEL, makeItem(Material.BLUE_STAINED_GLASS_PANE,
            "&b▼ Przedmiot",
            List.of("&7Wrzuć tutaj broń", "&7lub zbroję.")));

        inv.setItem(SLOT_SKIN_LABEL, makeItem(Material.PURPLE_STAINED_GLASS_PANE,
            "&d▼ Token Skina",
            List.of("&7Wrzuć Token Skina.", "&7Zostaw puste aby", "&7zdjąć skin.")));

        // Przycisk Zastosuj
        inv.setItem(SLOT_APPLY, makeItem(Material.EMERALD,
            "&a✔ Zastosuj",
            List.of(
                "&7Nakłada lub zdejmuje skin.",
                "",
                "&eBez Tokenu Skina &7— zdejmuje skin.",
                "&eBez Tokenu Zmiany &7— nic nie zrobi."
            )));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        int raw = event.getRawSlot();
        boolean isGuiSlot    = raw >= 0 && raw < 27;
        boolean isWorkSlot   = WORKING_SLOTS.contains(raw);

        // Kliknięcie przycisku Zastosuj
        if (raw == SLOT_APPLY) {
            event.setCancelled(true);
            handleApply(player, inv);
            return;
        }

        // Zablokuj wszystko poza slotami roboczymi w GUI
        if (isGuiSlot && !isWorkSlot) {
            event.setCancelled(true);
            return;
        }

        // Shift+click z ekwipunku gracza — blokuj (gracz musi ręcznie wkładać)
        if (!isGuiSlot) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }
        }

        // Slot roboczy — pozwól, ale po zdarzeniu sprawdź ilość
        if (isWorkSlot) {
            Bukkit.getScheduler().runTask(plugin, () -> enforceMaxOne(player, inv, raw));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        // Zablokuj przeciąganie na sloty GUI które nie są roboczymi
        for (int slot : event.getRawSlots()) {
            if (slot < 27 && !WORKING_SLOTS.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = openGuis.remove(player.getUniqueId());
        if (inv == null) return;
        returnItems(player, inv);
    }

    // ── Logika slotów ────────────────────────────────────────

    private void enforceMaxOne(Player player, Inventory inv, int slot) {
        ItemStack current = inv.getItem(slot);
        if (current == null || current.getType() == Material.AIR) return;
        if (current.getAmount() > 1) {
            ItemStack excess = current.clone();
            excess.setAmount(current.getAmount() - 1);
            current.setAmount(1);
            inv.setItem(slot, current);
            returnToPlayer(player, excess);
        }
    }

    // ── Logika aplikowania skina ─────────────────────────────

    private void handleApply(Player player, Inventory inv) {
        ItemStack changeToken = inv.getItem(SLOT_CHANGE_TOKEN);
        ItemStack targetItem  = inv.getItem(SLOT_ITEM);
        ItemStack skinToken   = inv.getItem(SLOT_SKIN_TOKEN);

        // Walidacja Token Zmiany
        if (!TokenUtil.isChangeToken(changeToken)) {
            msg(player, "&cBrak Tokenu Zmiany w lewym slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Walidacja przedmiotu
        if (targetItem == null || targetItem.getType() == Material.AIR) {
            msg(player, "&cBrak przedmiotu w środkowym slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        boolean hasSkinToken = skinToken != null && skinToken.getType() != Material.AIR;

        if (!hasSkinToken) {
            handleRemoveSkin(player, inv, targetItem);
        } else {
            if (!TokenUtil.isSkinToken(skinToken)) {
                msg(player, "&cPrzedmiot w prawym slocie nie jest Tokenem Skina!");
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            handleApplySkin(player, inv, targetItem, skinToken);
        }
    }

    private void handleApplySkin(Player player, Inventory inv,
                                  ItemStack targetItem, ItemStack skinToken) {
        String skinId = TokenUtil.getSkinIdFromToken(skinToken);
        if (skinId == null) { msg(player, "&cNieprawidłowy Token Skina!"); return; }

        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            msg(player, "&cSkin '&e" + skinId + "&c' nie istnieje w konfiguracji!");
            return;
        }

        if (!skin.isCompatibleWith(targetItem.getType())) {
            msg(player, "&cTen skin nie pasuje do tego typu przedmiotu!");
            msg(player, "&7Wymagany typ: &f" + skin.getAllowedTypes().get(0).name().replace("DIAMOND_", "").replace("IRON_", ""));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack modified = TokenUtil.applySkin(targetItem, skin);

        // Usuń tokeny ze slotów
        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_SKIN_TOKEN, null);
        inv.setItem(SLOT_ITEM, modified);

        msg(player, "&aSkin &f" + skin.getDisplayName() + " &azostał nałożony!");
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    private void handleRemoveSkin(Player player, Inventory inv, ItemStack targetItem) {
        if (!TokenUtil.hasCustomSkin(targetItem)) {
            msg(player, "&cTen przedmiot nie ma nalozonego skina!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Odczytaj skin ID PRZED zdjęciem skina
        String skinId = null;
        if (targetItem.hasItemMeta()) {
            skinId = targetItem.getItemMeta().getPersistentDataContainer()
                .get(new org.bukkit.NamespacedKey(plugin, "skinstudio_skin_id"),
                    org.bukkit.persistence.PersistentDataType.STRING);
        }

        ItemStack restored = TokenUtil.removeSkin(targetItem);
        if (restored == null) { msg(player, "&cBlad podczas zdejmowania skina!"); return; }

        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, restored);

        // Zwróć Token Skina graczowi
        if (skinId != null && !skinId.isEmpty()) {
            pl.skinstudio.model.SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
            if (skin != null) {
                ItemStack skinToken = pl.skinstudio.util.TokenUtil.createSkinToken(skin);
                returnToPlayer(player, skinToken);
                msg(player, "&aSkin zostal zdjety. Otrzymales z powrotem: &f" + skin.getDisplayName());
            } else {
                msg(player, "&aSkin zostal zdjety z przedmiotu.");
            }
        } else {
            msg(player, "&aSkin zostal zdjety z przedmiotu.");
        }

        player.playSound(player, Sound.BLOCK_ANVIL_USE, 1f, 1f);
    }

    // ── Pomocnicze ───────────────────────────────────────────

    private void returnItems(Player player, Inventory inv) {
        for (int slot : WORKING_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                returnToPlayer(player, item);
            }
        }
    }

    private void returnToPlayer(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> components = new ArrayList<>();
        for (String line : lore) components.add(colorize(line));
        meta.lore(components);
        item.setItemMeta(meta);
        return item;
    }

    private void msg(Player player, String message) {
        player.sendMessage(colorize("&8[&6SkinStudio&8] &r" + message));
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
