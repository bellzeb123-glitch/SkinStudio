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
import org.bukkit.event.inventory.ClickType;
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

    private static final int SLOT_CHANGE_TOKEN = 10;
    private static final int SLOT_ITEM         = 13;
    private static final int SLOT_SKIN_TOKEN   = 16;
    private static final int SLOT_APPLY        = 22;
    private static final int SLOT_REMOVE_INFO  = 20;
    private static final int SLOT_INFO         = 24;

    // Sloty robocze — tylko tu gracz może wkładać/wyjmować przedmioty
    private static final Set<Integer> WORKING_SLOTS = Set.of(SLOT_CHANGE_TOKEN, SLOT_ITEM, SLOT_SKIN_TOKEN);

    private final SkinStudio plugin;
    private final Map<UUID, Inventory> openGuis = new HashMap<>();

    public SkinStudioGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openFor(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&8✦ &6Skin Studio &8✦"));
        fillDecoration(inv);
        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private void fillDecoration(Inventory inv) {
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        inv.setItem(SLOT_CHANGE_TOKEN, makeItem(Material.LIME_STAINED_GLASS_PANE,
            "&a▶ Slot: Token Zmiany", List.of("&7Wrzuć tutaj", "&6Token Zmiany")));

        inv.setItem(SLOT_ITEM, makeItem(Material.BLUE_STAINED_GLASS_PANE,
            "&b▶ Slot: Przedmiot", List.of("&7Wrzuć tutaj broń", "&7lub zbroję")));

        inv.setItem(SLOT_SKIN_TOKEN, makeItem(Material.PURPLE_STAINED_GLASS_PANE,
            "&d▶ Slot: Token Skina", List.of("&7Wrzuć tutaj Token Skina", "&7(zostaw pusty aby zdjąć skin)")));

        inv.setItem(SLOT_APPLY, makeItem(Material.EMERALD,
            "&a✔ Zastosuj", List.of("&7Kliknij aby nałożyć skin", "&7lub zdjąć skin z przedmiotu")));

        inv.setItem(SLOT_REMOVE_INFO, makeItem(Material.REDSTONE,
            "&c✘ Zdejmij Skin", List.of("&7Token Zmiany + Przedmiot", "&7(bez Tokenu Skina)")));

        inv.setItem(SLOT_INFO, makeItem(Material.BOOK,
            "&eℹ Informacja", List.of(
                "&7Token Zmiany zawsze wymagany.",
                "&7Bez Tokenu Skina — skin zostanie zdjęty.",
                "&eKażdy slot przyjmuje tylko 1 sztukę."
            )));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        int rawSlot = event.getRawSlot();
        boolean isGuiSlot    = rawSlot >= 0 && rawSlot < 27;
        boolean isWorkingSlot = WORKING_SLOTS.contains(rawSlot);
        boolean isPlayerSlot  = rawSlot >= 27;

        // Przycisk Zastosuj
        if (rawSlot == SLOT_APPLY) {
            event.setCancelled(true);
            handleApply(player, inv);
            return;
        }

        // Kliknięcie w dekorację GUI (nie w sloty robocze) — zawsze blokuj
        if (isGuiSlot && !isWorkingSlot) {
            event.setCancelled(true);
            return;
        }

        // Shift+click z ekwipunku gracza — sprawdź czy item trafi do slotu roboczego
        if (isPlayerSlot && event.getClick() == ClickType.SHIFT_LEFT || 
            isPlayerSlot && event.getClick() == ClickType.SHIFT_RIGHT) {
            // Blokuj shift+click z ekwipunku — gracze muszą ręcznie wkładać
            event.setCancelled(true);
            return;
        }

        // Slot roboczy — obsługa wkładania/wyjmowania
        if (isWorkingSlot) {
            // Pozwól na kliknięcie ale po zdarzeniu ogranicz do 1 sztuki
            Bukkit.getScheduler().runTask(plugin, () -> enforceMaxOne(player, inv, rawSlot));
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        // Zablokuj przeciąganie do slotów GUI (poza roboczymi)
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

    /**
     * Wymusza max 1 sztukę w slocie roboczym.
     * Nadmiar oddaje graczowi.
     */
    private void enforceMaxOne(Player player, Inventory inv, int slot) {
        ItemStack current = inv.getItem(slot);
        if (current == null || current.getType() == Material.AIR) return;
        if (isDecoration(current)) {
            // Dekoracja wpadła do slotu roboczego — usuń i oddaj graczowi
            inv.setItem(slot, null);
            returnToPlayer(player, current);
            restoreSlotDecoration(inv, slot);
            return;
        }
        if (current.getAmount() > 1) {
            ItemStack excess = current.clone();
            excess.setAmount(current.getAmount() - 1);
            current.setAmount(1);
            inv.setItem(slot, current);
            returnToPlayer(player, excess);
        }
    }

    private void restoreSlotDecoration(Inventory inv, int slot) {
        switch (slot) {
            case SLOT_CHANGE_TOKEN -> inv.setItem(slot, makeItem(Material.LIME_STAINED_GLASS_PANE,
                "&a▶ Slot: Token Zmiany", List.of("&7Wrzuć tutaj", "&6Token Zmiany")));
            case SLOT_ITEM -> inv.setItem(slot, makeItem(Material.BLUE_STAINED_GLASS_PANE,
                "&b▶ Slot: Przedmiot", List.of("&7Wrzuć tutaj broń", "&7lub zbroję")));
            case SLOT_SKIN_TOKEN -> inv.setItem(slot, makeItem(Material.PURPLE_STAINED_GLASS_PANE,
                "&d▶ Slot: Token Skina", List.of("&7Wrzuć tutaj Token Skina", "&7(zostaw pusty aby zdjąć skin)")));
        }
    }

    private void handleApply(Player player, Inventory inv) {
        ItemStack changeToken = getWorkingItem(inv, SLOT_CHANGE_TOKEN);
        ItemStack targetItem  = getWorkingItem(inv, SLOT_ITEM);
        ItemStack skinToken   = getWorkingItem(inv, SLOT_SKIN_TOKEN);

        if (!TokenUtil.isChangeToken(changeToken)) {
            msg(player, "&cBrak Tokenu Zmiany w lewym slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        if (targetItem == null) {
            msg(player, "&cBrak przedmiotu w środkowym slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        if (skinToken == null) {
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

    private void handleApplySkin(Player player, Inventory inv, ItemStack targetItem, ItemStack skinToken) {
        String skinId = TokenUtil.getSkinIdFromToken(skinToken);
        if (skinId == null) { msg(player, "&cNieprawidłowy Token Skina!"); return; }

        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) { msg(player, "&cSkin '&e" + skinId + "&c' nie istnieje!"); return; }

        if (!skin.isCompatibleWith(targetItem.getType())) {
            msg(player, "&cTen skin nie pasuje do tego typu przedmiotu!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack modified = TokenUtil.applySkin(targetItem, skin);

        // Usuń tokeny, wstaw zmodyfikowany przedmiot, przywróć dekoracje
        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_SKIN_TOKEN, null);
        inv.setItem(SLOT_ITEM, modified);
        restoreSlotDecoration(inv, SLOT_CHANGE_TOKEN);
        restoreSlotDecoration(inv, SLOT_SKIN_TOKEN);

        msg(player, "&aSkin &f" + skin.getDisplayName() + " &azostał nałożony!");
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    private void handleRemoveSkin(Player player, Inventory inv, ItemStack targetItem) {
        if (!TokenUtil.hasCustomSkin(targetItem)) {
            msg(player, "&cTen przedmiot nie ma nałożonego skina!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack restored = TokenUtil.removeSkin(targetItem);
        if (restored == null) { msg(player, "&cBłąd podczas zdejmowania skina!"); return; }

        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, restored);
        restoreSlotDecoration(inv, SLOT_CHANGE_TOKEN);

        msg(player, "&aSkin został zdjęty z przedmiotu.");
        player.playSound(player, Sound.BLOCK_ANVIL_USE, 1f, 1f);
    }

    private void returnItems(Player player, Inventory inv) {
        for (int slot : WORKING_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && !isDecoration(item)) {
                returnToPlayer(player, item);
            }
        }
    }

    private void returnToPlayer(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    /** Zwraca item ze slotu roboczego lub null jeśli slot zawiera dekorację/pusty */
    private ItemStack getWorkingItem(Inventory inv, int slot) {
        ItemStack item = inv.getItem(slot);
        if (item == null || item.getType() == Material.AIR || isDecoration(item)) return null;
        return item;
    }

    private boolean isDecoration(ItemStack item) {
        if (item == null) return true;
        // Dekoracje to przedmioty bez NBT tokenów
        if (TokenUtil.isChangeToken(item) || TokenUtil.isSkinToken(item)) return false;
        return switch (item.getType()) {
            case GRAY_STAINED_GLASS_PANE, LIME_STAINED_GLASS_PANE,
                 BLUE_STAINED_GLASS_PANE, PURPLE_STAINED_GLASS_PANE,
                 EMERALD, REDSTONE, BOOK -> true;
            default -> false;
        };
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> loreComponents = new ArrayList<>();
        for (String line : lore) loreComponents.add(colorize(line));
        meta.lore(loreComponents);
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
