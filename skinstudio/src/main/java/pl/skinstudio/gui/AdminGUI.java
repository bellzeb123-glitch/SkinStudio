package pl.skinstudio.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class AdminGUI implements Listener {

    // InventoryHolder pozwala jednoznacznie identyfikować nasze GUI
    // i blokować wszelkie akcje na nim
    private static class SkinAdminHolder implements InventoryHolder {
        private final Tab tab;
        private Inventory inv;
        SkinAdminHolder(Tab tab) { this.tab = tab; }
        @Override public Inventory getInventory() { return inv; }
        void setInventory(Inventory inv) { this.inv = inv; }
        Tab getTab() { return tab; }
    }

    private static final String NBT_SKIN_ID   = "ss_skin_id";
    private static final String NBT_IS_CHANGE = "ss_is_change";
    private static final String NBT_TAB_NAME  = "ss_tab_name";

    private enum Tab {
        BRONZE    ("&6Bronze",      Material.ORANGE_STAINED_GLASS_PANE, "bronze"),
        LIVING    ("&aŻyjący",      Material.LIME_STAINED_GLASS_PANE,   "living"),
        CORRUPTED ("&5Skażony",     Material.PURPLE_STAINED_GLASS_PANE, "corrupted"),
        PALLADIUM ("&bPalladium",   Material.CYAN_STAINED_GLASS_PANE,   "palladium"),
        ULTIMATIUM("&eUltimatium",  Material.YELLOW_STAINED_GLASS_PANE, "ultimatium"),
        UNIQUE    ("&cUnikalne",    Material.RED_STAINED_GLASS_PANE,    null),
        TOKENS    ("&fTokeny",      Material.NETHER_STAR,               null);

        final String label;
        final Material mat;
        final String prefix;
        Tab(String l, Material m, String p) { label=l; mat=m; prefix=p; }
    }

    private static final int GUI_SIZE = 54;
    private static final int[] TAB_SLOTS = {0,1,2,3,4,5,6,8};

    private final SkinStudio plugin;
    private final Map<UUID, Tab>    currentTab     = new HashMap<>();
    private final Map<UUID, String> awaitingAmount = new HashMap<>();

    public AdminGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Otwieranie GUI ───────────────────────────────────────

    public void openFor(Player player) {
        Tab tab = currentTab.getOrDefault(player.getUniqueId(), Tab.BRONZE);
        openTab(player, tab);
    }

    private void openTab(Player player, Tab tab) {
        currentTab.put(player.getUniqueId(), tab);

        SkinAdminHolder holder = new SkinAdminHolder(tab);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE,
            colorize("&8✦ &6Admin: Skin Studio &8✦"));
        holder.setInventory(inv);

        buildGui(inv, tab);
        player.openInventory(inv);
    }

    private void buildGui(Inventory inv, Tab activeTab) {
        // Tło
        for (int i = 0; i < GUI_SIZE; i++)
            inv.setItem(i, glass(Material.GRAY_STAINED_GLASS_PANE));

        inv.setItem(7, glass(Material.BLACK_STAINED_GLASS_PANE));

        // Zakładki
        Tab[] tabs = Tab.values();
        for (int i = 0; i < TAB_SLOTS.length && i < tabs.length; i++) {
            Tab t = tabs[i];
            boolean active = t == activeTab;
            inv.setItem(TAB_SLOTS[i], tabItem(t, active));
        }

        // Zawartość
        switch (activeTab) {
            case TOKENS   -> buildTokensTab(inv);
            case UNIQUE   -> buildUniqueTab(inv);
            default       -> buildTierTab(inv, activeTab);
        }
    }

    private void buildTierTab(Inventory inv, Tab tab) {
        int slot = 9;
        for (SkinDefinition skin : getSortedSkins(tab)) {
            if (slot >= GUI_SIZE) break;
            inv.setItem(slot++, skinItem(skin));
        }
    }

    private void buildUniqueTab(Inventory inv) {
        String[] ids = {"frost_palace_fire_sword","frost_palace_ice_sword",
                        "primis_gladius_sword","magmaguys_toothpick"};
        int slot = 9;
        for (String id : ids) {
            SkinDefinition s = plugin.getSkinConfig().getSkin(id);
            if (s != null && slot < GUI_SIZE) inv.setItem(slot++, skinItem(s));
        }
    }

    private void buildTokensTab(Inventory inv) {
        inv.setItem(9, changeTokenItem());
        inv.setItem(11, infoItem());
    }

    // ── Tworzenie itemów ─────────────────────────────────────

    private ItemStack skinItem(SkinDefinition skin) {
        Material mat = skin.getAllowedTypes().isEmpty()
            ? Material.PAPER : skin.getAllowedTypes().get(0);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(skin.getDisplayName()));

        NamespacedKey mk = parseKey(skin.getItemModel());
        if (mk != null) meta.setItemModel(mk);

        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7ID: &f" + skin.getId()));
        if (skin.hasEquipmentAsset())
            lore.add(colorize("&7Tekstura: &f" + skin.getEquipmentAsset()));
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj 1 Token Skina"));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);

        // NBT — identyfikator skina
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_SKIN_ID),
            PersistentDataType.STRING, skin.getId());

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack changeTokenItem() {
        ItemStack item = new ItemStack(plugin.getSkinConfig().getChangeTokenMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getSkinConfig().getChangeTokenName()));
        List<Component> lore = new ArrayList<>();
        for (String l : plugin.getSkinConfig().getChangeTokenLore()) lore.add(colorize(l));
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj 1 szt."));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_IS_CHANGE),
            PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tabItem(Tab tab, boolean active) {
        ItemStack item = new ItemStack(tab.mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize((active ? "&f&l" : "&7") + tab.label));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(active ? "&7▶ Aktualnie wybrana" : "&7Kliknij aby otworzyć"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_TAB_NAME),
            PersistentDataType.STRING, tab.name());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize("&eℹ Jak używać tokenów"));
        meta.lore(List.of(
            colorize("&7Token Zmiany — wymagany zawsze."),
            colorize("&7Token Skina — określa wygląd."),
            Component.empty(),
            colorize("&7Gracz potrzebuje obu w /skinstudio.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack glass(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    // ── Eventy — kluczowe: sprawdzamy InventoryHolder ────────

    private boolean isOurGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof SkinAdminHolder;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Sprawdź czy kliknięto w nasze GUI (przez InventoryHolder)
        Inventory topInv = event.getInventory();
        if (!isOurGui(topInv)) return;

        // ZAWSZE anuluj — żaden item nie wyjdzie z GUI
        event.setCancelled(true);

        int raw = event.getRawSlot();

        // Kliknięcie w ekwipunku gracza (dolna część) — ignoruj
        if (raw < 0 || raw >= GUI_SIZE) return;

        ItemStack clicked = topInv.getItem(raw);
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (!clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        NamespacedKey tabKey    = new NamespacedKey(plugin, NBT_TAB_NAME);
        NamespacedKey skinKey   = new NamespacedKey(plugin, NBT_SKIN_ID);
        NamespacedKey changeKey = new NamespacedKey(plugin, NBT_IS_CHANGE);

        // Kliknięcie w zakładkę
        if (pdc.has(tabKey, PersistentDataType.STRING)) {
            String tabName = pdc.get(tabKey, PersistentDataType.STRING);
            try {
                Tab newTab = Tab.valueOf(tabName);
                Tab current = currentTab.getOrDefault(player.getUniqueId(), Tab.BRONZE);
                if (newTab != current) {
                    player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                    Tab finalTab = newTab;
                    Bukkit.getScheduler().runTask(plugin, () -> openTab(player, finalTab));
                }
            } catch (Exception ignored) {}
            return;
        }

        // Kliknięcie w Token Zmiany
        if (pdc.has(changeKey, PersistentDataType.BYTE)) {
            handleClick(player, event.getClick(), null);
            return;
        }

        // Kliknięcie w skin
        if (pdc.has(skinKey, PersistentDataType.STRING)) {
            String skinId = pdc.get(skinKey, PersistentDataType.STRING);
            SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
            if (skin != null) handleClick(player, event.getClick(), skin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isOurGui(event.getInventory())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (isOurGui(event.getSource()) || isOurGui(event.getDestination()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!isOurGui(event.getInventory())) return;
        // Nic specjalnego — InventoryHolder automatycznie zarządza życiem inwentarza
    }

    // ── Logika tokenów ───────────────────────────────────────

    private void handleClick(Player player, ClickType click, SkinDefinition skin) {
        if (click == ClickType.LEFT) {
            ItemStack token = (skin == null)
                ? TokenUtil.createChangeToken()
                : TokenUtil.createSkinToken(skin);
            giveItem(player, token);
            String name = (skin == null) ? "Token Zmiany" : skin.getDisplayName();
            msg(player, "&aDodano: &f" + name);
            player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

        } else if (click == ClickType.RIGHT) {
            String id = (skin == null) ? "__change__" : skin.getId();
            awaitingAmount.put(player.getUniqueId(), id);
            player.closeInventory();
            msg(player, "&eWpisz ilość na czacie &7(1-64), lub &ccancel&7 aby anulować:");
        }
    }

    public boolean handleChatInput(Player player, String message) {
        if (!awaitingAmount.containsKey(player.getUniqueId())) return false;
        String id = awaitingAmount.remove(player.getUniqueId());

        if (message.equalsIgnoreCase("cancel")) {
            msg(player, "&7Anulowano.");
            Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
            return true;
        }

        int amount;
        try { amount = Integer.parseInt(message.trim()); }
        catch (NumberFormatException e) {
            msg(player, "&cNieprawidłowa liczba — wpisz 1-64.");
            awaitingAmount.put(player.getUniqueId(), id);
            return true;
        }

        if (amount < 1 || amount > 64) {
            msg(player, "&cIlość musi być między 1 a 64.");
            awaitingAmount.put(player.getUniqueId(), id);
            return true;
        }

        ItemStack token;
        String name;
        if ("__change__".equals(id)) {
            token = TokenUtil.createChangeToken();
            name = "Token Zmiany";
        } else {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
            if (skin == null) { msg(player, "&cBłąd: skin nie istnieje."); return true; }
            token = TokenUtil.createSkinToken(skin);
            name = skin.getDisplayName();
        }
        token.setAmount(amount);
        giveItem(player, token);
        msg(player, "&aDodano &f" + amount + "x &f" + name);
        player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
        Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
        return true;
    }

    public boolean isAwaitingInput(Player player) {
        return awaitingAmount.containsKey(player.getUniqueId());
    }

    // ── Pomocnicze ───────────────────────────────────────────

    private List<SkinDefinition> getSortedSkins(Tab tab) {
        List<SkinDefinition> skins = new ArrayList<>();
        for (SkinDefinition s : plugin.getSkinConfig().getAllSkins().values())
            if (tab.prefix != null && s.getId().startsWith(tab.prefix + "_"))
                skins.add(s);
        String[] order = {"sword","axe","bow","crossbow","scythe","trident",
                          "helmet","chestplate","leggings","boots"};
        List<SkinDefinition> sorted = new ArrayList<>();
        for (String suffix : order)
            for (SkinDefinition s : skins)
                if (s.getId().endsWith("_" + suffix)) { sorted.add(s); break; }
        for (SkinDefinition s : skins) if (!sorted.contains(s)) sorted.add(s);
        return sorted;
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
            .forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    private NamespacedKey parseKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] p = key.split(":", 2);
        return p.length == 2 ? new NamespacedKey(p[0], p[1]) : NamespacedKey.minecraft(key);
    }

    private void msg(Player player, String m) {
        player.sendMessage(colorize("&8[&6SkinStudio&8] &r" + m));
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
