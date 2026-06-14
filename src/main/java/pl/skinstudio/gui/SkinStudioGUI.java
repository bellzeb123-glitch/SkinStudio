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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class SkinStudioGUI implements Listener {

    private static class StudioHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
        void setInventory(Inventory inv) { this.inv = inv; }
    }

    private static final int SLOT_CHANGE_LABEL = 9;
    private static final int SLOT_CHANGE_TOKEN = 10;
    private static final int SLOT_ITEM_LABEL   = 12;
    private static final int SLOT_ITEM         = 13;
    private static final int SLOT_SKIN_LABEL   = 15;
    private static final int SLOT_SKIN_TOKEN   = 16;
    private static final int SLOT_APPLY        = 22;

    private static final Set<Integer> WORKING_SLOTS = Set.of(SLOT_CHANGE_TOKEN, SLOT_ITEM, SLOT_SKIN_TOKEN);

    private final SkinStudio plugin;

    public SkinStudioGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private LangManager lang() { return plugin.getLang(); }

    public void openFor(Player player) {
        StudioHolder holder = new StudioHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, lang().raw("gui.title"));
        holder.setInventory(inv);
        buildGui(inv);
        player.openInventory(inv);
    }

    private void buildGui(Inventory inv) {
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, null);
        inv.setItem(SLOT_SKIN_TOKEN, null);

        inv.setItem(SLOT_CHANGE_LABEL, makeItem(Material.RED_STAINED_GLASS_PANE,
            lang().getRaw("gui.label-change-token"), lang().getList("gui.label-change-lore")));
        inv.setItem(SLOT_ITEM_LABEL, makeItem(Material.BLUE_STAINED_GLASS_PANE,
            lang().getRaw("gui.label-item"), lang().getList("gui.label-item-lore")));
        inv.setItem(SLOT_SKIN_LABEL, makeItem(Material.PURPLE_STAINED_GLASS_PANE,
            lang().getRaw("gui.label-skin-token"), lang().getList("gui.label-skin-lore")));
        inv.setItem(SLOT_APPLY, makeItem(Material.EMERALD,
            lang().getRaw("gui.apply-button"), lang().getList("gui.apply-lore")));
    }

    private boolean isOurGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof StudioHolder;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isOurGui(event.getInventory())) return;

        int raw = event.getRawSlot();
        boolean isGuiSlot  = raw >= 0 && raw < 27;
        boolean isWorkSlot = WORKING_SLOTS.contains(raw);

        if (raw == SLOT_APPLY) {
            event.setCancelled(true);
            handleApply(player, event.getInventory());
            return;
        }

        if (isGuiSlot && !isWorkSlot) {
            event.setCancelled(true);
            return;
        }

        if (!isGuiSlot && event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (isWorkSlot) {
            Bukkit.getScheduler().runTask(plugin, () -> enforceMaxOne(player, event.getInventory(), raw));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isOurGui(event.getInventory())) return;
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
        if (!isOurGui(event.getInventory())) return;
        returnItems(player, event.getInventory());
    }

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

    private void handleApply(Player player, Inventory inv) {
        ItemStack changeToken = inv.getItem(SLOT_CHANGE_TOKEN);
        ItemStack targetItem  = inv.getItem(SLOT_ITEM);
        ItemStack skinToken   = inv.getItem(SLOT_SKIN_TOKEN);

        if (targetItem == null || targetItem.getType() == Material.AIR) {
            player.sendMessage(lang().component("gui.no-item"));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        boolean hasChangeToken = changeToken != null
            && changeToken.getType() != Material.AIR
            && TokenUtil.isChangeToken(changeToken);
        boolean hasSkinToken = skinToken != null
            && skinToken.getType() != Material.AIR
            && TokenUtil.isSkinToken(skinToken);

        if (hasChangeToken && !hasSkinToken) {
            handleRemoveSkin(player, inv, targetItem);
            return;
        }
        if (hasSkinToken && !hasChangeToken) {
            handleApplySkin(player, inv, targetItem, skinToken);
            return;
        }
        if (hasChangeToken && hasSkinToken) {
            player.sendMessage(lang().component("gui.both-tokens"));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        player.sendMessage(lang().component("gui.no-tokens"));
        player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    private void handleApplySkin(Player player, Inventory inv, ItemStack targetItem, ItemStack skinToken) {
        String skinId = TokenUtil.getSkinIdFromToken(skinToken);
        if (skinId == null) {
            player.sendMessage(lang().component("gui.invalid-skin-token"));
            return;
        }

        SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
        if (skin == null) {
            player.sendMessage(lang().component("gui.skin-not-found", "skin", skinId));
            return;
        }

        if (!skin.isCompatibleWith(targetItem.getType())) {
            player.sendMessage(lang().component("gui.incompatible-type"));
            String typeName = skin.getAllowedTypes().get(0).name()
                .replace("DIAMOND_", "").replace("IRON_", "");
            player.sendMessage(lang().component("gui.incompatible-hint", "type", typeName));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemStack modified = TokenUtil.applySkin(targetItem, skin);
        inv.setItem(SLOT_SKIN_TOKEN, null);
        inv.setItem(SLOT_ITEM, modified);

        player.sendMessage(lang().component("gui.skin-applied", "skin", skin.getDisplayName()));
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    private void handleRemoveSkin(Player player, Inventory inv, ItemStack targetItem) {
        if (!TokenUtil.hasCustomSkin(targetItem)) {
            player.sendMessage(lang().component("gui.no-skin-on-item"));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        String skinId = TokenUtil.getSkinIdFromItem(targetItem);
        ItemStack restored = TokenUtil.removeSkin(targetItem);
        if (restored == null) {
            player.sendMessage(lang().component("gui.remove-error"));
            return;
        }

        inv.setItem(SLOT_CHANGE_TOKEN, null);
        inv.setItem(SLOT_ITEM, restored);

        if (skinId != null && !skinId.isEmpty()) {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
            if (skin != null) {
                returnToPlayer(player, TokenUtil.createSkinToken(skin));
                player.sendMessage(lang().component("gui.skin-removed", "skin", skin.getDisplayName()));
            } else {
                player.sendMessage(lang().component("gui.skin-removed-simple"));
            }
        } else {
            player.sendMessage(lang().component("gui.skin-removed-simple"));
        }
        player.playSound(player, Sound.BLOCK_ANVIL_USE, 1f, 1f);
    }

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
        player.getInventory().addItem(item).values()
            .forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
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

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
