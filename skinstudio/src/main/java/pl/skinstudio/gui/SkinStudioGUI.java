package pl.skinstudio.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class SkinStudioGUI implements Listener {

    // Sloty w inwentarzu (rozmiar 27 = 3 rzędy)
    private static final int SLOT_CHANGE_TOKEN = 10;   // lewy slot
    private static final int SLOT_ITEM         = 13;   // środkowy slot
    private static final int SLOT_SKIN_TOKEN   = 16;   // prawy slot
    private static final int SLOT_APPLY        = 22;   // przycisk zastosuj (dół środek)
    private static final int SLOT_REMOVE       = 20;   // przycisk zdejmij (dół lewy)
    private static final int SLOT_INFO         = 24;   // info (dół prawy)

    private final SkinStudio plugin;
    // Mapa otwartych GUI: UUID gracza → inwentarz
    private final Map<UUID, Inventory> openGuis = new HashMap<>();

    public SkinStudioGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openFor(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27,
            colorize("&8✦ &6Skin Studio &8✦"));

        // Wypełnij tło szklaną taflą
        ItemStack glass = makeFiller(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        // Slot Token Zmiany
        inv.setItem(SLOT_CHANGE_TOKEN, makeGuideItem(Material.LIME_STAINED_GLASS_PANE,
            "&a▶ Slot: Token Zmiany",
            List.of("&7Wrzuć tutaj", "&6Token Zmiany")));

        // Slot Przedmiotu
        inv.setItem(SLOT_ITEM, makeGuideItem(Material.BLUE_STAINED_GLASS_PANE,
            "&b▶ Slot: Przedmiot",
            List.of("&7Wrzuć tutaj broń", "&7lub zbroję")));

        // Slot Token Skina
        inv.setItem(SLOT_SKIN_TOKEN, makeGuideItem(Material.PURPLE_STAINED_GLASS_PANE,
            "&d▶ Slot: Token Skina",
            List.of("&7Wrzuć tutaj", "&dToken Skina", "&7(zostaw pusty aby zdjąć skin)")));

        // Przycisk Zastosuj
        inv.setItem(SLOT_APPLY, makeButton(Material.EMERALD,
            "&a✔ Zastosuj",
            List.of("&7Kliknij aby nałożyć skin", "&7lub zdjąć skin z przedmiotu")));

        // Przycisk Zdejmij (info)
        inv.setItem(SLOT_REMOVE, makeButton(Material.REDSTONE,
            "&c✘ Zdejmij Skin",
            List.of("&7Wrzuć Token Zmiany + Przedmiot", "&7(bez Tokenu Skina)", "&7i kliknij Zastosuj")));

        // Info
        inv.setItem(SLOT_INFO, makeButton(Material.BOOK,
            "&eℹ Informacja",
            List.of(
                "&7Token Zmiany jest zawsze wymagany.",
                "&7Token Skina określa wygląd.",
                "&7Bez Tokenu Skina — skin zostanie zdjęty.",
                "",
                "&eNadpisanie: wrzuć nowy Token Skina",
                "&ebe usuwania starego."
            )));

        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        int slot = event.getRawSlot();

        // Zablokuj klikanie w sloty dekoracyjne (nie w sloty robocze ani inwentarz gracza)
        boolean isWorkingSlot = (slot == SLOT_CHANGE_TOKEN || slot == SLOT_ITEM || slot == SLOT_SKIN_TOKEN);
        boolean isPlayerInv   = slot >= 27; // sloty inwentarza gracza

        if (!isWorkingSlot && !isPlayerInv) {
            // Kliknięcie w przycisk Zastosuj
            if (slot == SLOT_APPLY) {
                event.setCancelled(true);
                handleApply(player, inv);
                return;
            }
            event.setCancelled(true);
            return;
        }

        // Sloty robocze — pozwól na normalne klikanie (wkładanie/wyjmowanie itemów)
        // ale tylko jeśli to sloty robocze
        if (!isWorkingSlot && !isPlayerInv) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = openGuis.remove(player.getUniqueId());
        if (inv == null) return;

        // Zwróć przedmioty ze slotów roboczych do gracza
        returnItems(player, inv);
    }

    private void handleApply(Player player, Inventory inv) {
        ItemStack changeToken = inv.getItem(SLOT_CHANGE_TOKEN);
        ItemStack targetItem  = inv.getItem(SLOT_ITEM);
        ItemStack skinToken   = inv.getItem(SLOT_SKIN_TOKEN);

        // Walidacja: Token Zmiany musi być zawsze
        if (!TokenUtil.isChangeToken(changeToken)) {
            msg(player, "&cBrak Tokenu Zmiany w odpowiednim slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Walidacja: przedmiot musi być
        if (targetItem == null || targetItem.getType() == Material.AIR) {
            msg(player, "&cBrak przedmiotu w środkowym slocie!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Tryb: zdejmowanie skina (brak Tokenu Skina)
        if (skinToken == null || skinToken.getType() == Material.AIR) {
            handleRemoveSkin(player, inv, changeToken, targetItem);
            return;
        }

        // Tryb: nakładanie skina
        if (!TokenUtil.isSkinToken(skinToken)) {
            msg(player, "&cPrzedmiot w prawym slocie nie jest Tokenem Skina!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        handleApplySkin(player, inv, changeToken, targetItem, skinToken);
    }

    private void handleApplySkin(Player player, Inventory inv,
                                  ItemStack changeToken, ItemStack targetItem, ItemStack skinToken) {
        String skinId = TokenUtil.getSkinIdFromToken(skinToken);
        if (skinId == null) {
            msg(player, "&cNieprawidłowy Token Skina!");
            return;
        }

        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            msg(player, "&cSkin o ID '&e" + skinId + "&c' nie istnieje w konfiguracji!");
            return;
        }

        // Walidacja typu przedmiotu
        if (!skin.isCompatibleWith(targetItem.getType())) {
            msg(player, "&cTen skin nie pasuje do tego typu przedmiotu!");
            msg(player, "&7Skin: &f" + skin.getDisplayName());
            msg(player, "&7Przedmiot: &f" + targetItem.getType().name());
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Nałóż skin
        ItemStack modified = TokenUtil.applySkin(targetItem, skin.getItemModel());

        // Usuń tokeny ze slotów
        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_SKIN_TOKEN, null);
        inv.setItem(SLOT_ITEM, modified);

        // Odśwież podpowiedź slotu
        inv.setItem(SLOT_CHANGE_TOKEN, makeGuideItem(Material.LIME_STAINED_GLASS_PANE,
            "&a▶ Slot: Token Zmiany",
            List.of("&7Wrzuć tutaj", "&6Token Zmiany")));

        inv.setItem(SLOT_SKIN_TOKEN, makeGuideItem(Material.PURPLE_STAINED_GLASS_PANE,
            "&d▶ Slot: Token Skina",
            List.of("&7Wrzuć tutaj", "&dToken Skina", "&7(zostaw pusty aby zdjąć skin)")));

        msg(player, "&aSkin &f" + skin.getDisplayName() + " &azostał nałożony!");
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    private void handleRemoveSkin(Player player, Inventory inv,
                                   ItemStack changeToken, ItemStack targetItem) {
        if (!TokenUtil.hasCustomSkin(targetItem)) {
            msg(player, "&cTen przedmiot nie ma nałożonego skina!");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack restored = TokenUtil.removeSkin(targetItem);
        if (restored == null) {
            msg(player, "&cBłąd podczas zdejmowania skina!");
            return;
        }

        // Usuń Token Zmiany
        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, restored);

        inv.setItem(SLOT_CHANGE_TOKEN, makeGuideItem(Material.LIME_STAINED_GLASS_PANE,
            "&a▶ Slot: Token Zmiany",
            List.of("&7Wrzuć tutaj", "&6Token Zmiany")));

        msg(player, "&aSkin został zdjęty z przedmiotu.");
        player.playSound(player, Sound.BLOCK_ANVIL_USE, 1f, 1f);
    }

    private void returnItems(Player player, Inventory inv) {
        int[] workingSlots = {SLOT_CHANGE_TOKEN, SLOT_ITEM, SLOT_SKIN_TOKEN};
        for (int slot : workingSlots) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                // Sprawdź czy to nie element GUI (szklane kafle itp.)
                if (!isGuiDecoration(item)) {
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                    if (!leftover.isEmpty()) {
                        // Jeśli ekwipunek pełny — upuść na ziemię
                        leftover.values().forEach(i ->
                            player.getWorld().dropItemNaturally(player.getLocation(), i));
                    }
                }
            }
        }
    }

    private boolean isGuiDecoration(ItemStack item) {
        if (item == null) return true;
        return switch (item.getType()) {
            case GRAY_STAINED_GLASS_PANE, LIME_STAINED_GLASS_PANE,
                 BLUE_STAINED_GLASS_PANE, PURPLE_STAINED_GLASS_PANE,
                 EMERALD, REDSTONE, BOOK -> {
                // Sprawdź czy to jest przycisk GUI po braku NBT tokenów
                if (!item.hasItemMeta()) yield true;
                yield !TokenUtil.isChangeToken(item) && !TokenUtil.isSkinToken(item);
            }
            default -> false;
        };
    }

    // ── Pomocnicze ────────────────────────────────────────────

    private ItemStack makeFiller(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeGuideItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> loreComponents = new ArrayList<>();
        for (String line : lore) loreComponents.add(colorize(line));
        meta.lore(loreComponents);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeButton(Material mat, String name, List<String> lore) {
        return makeGuideItem(mat, name, lore);
    }

    private void msg(Player player, String message) {
        player.sendMessage(colorize("&8[&6SkinStudio&8] &r" + message));
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
