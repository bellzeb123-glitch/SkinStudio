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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class AdminGUI implements Listener {

    // NBT klucze dla ikon GUI
    private static final String NBT_GUI_SKIN_ID    = "ss_admin_skin_id";
    private static final String NBT_GUI_IS_CHANGE  = "ss_admin_is_change";
    private static final String NBT_GUI_TAB        = "ss_admin_tab";
    private static final String NBT_GUI_DECORATION = "ss_admin_decoration";

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

        Tab(String label, Material mat, String prefix) {
            this.label = label; this.mat = mat; this.prefix = prefix;
        }
    }

    private static final int GUI_SIZE = 54;
    private static final int[] TAB_SLOTS = {0, 1, 2, 3, 4, 5, 6, 8};

    private final SkinStudio plugin;
    private final Map<UUID, Tab>       currentTab     = new HashMap<>();
    private final Map<UUID, Inventory> openGuis       = new HashMap<>();
    private final Map<UUID, String>    awaitingAmount = new HashMap<>();

    public AdminGUI(SkinStudio plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openFor(Player player) {
        Tab tab = currentTab.getOrDefault(player.getUniqueId(), Tab.BRONZE);
        openTab(player, tab);
    }

    private void openTab(Player player, Tab tab) {
        currentTab.put(player.getUniqueId(), tab);
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE,
            colorize("&8✦ &6Admin: Skin Studio &8✦"));
        buildTab(inv, tab);
        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private void buildTab(Inventory inv, Tab activeTab) {
        // Tło
        ItemStack glass = decoration(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, glass);

        // Separator
        inv.setItem(7, decoration(Material.BLACK_STAINED_GLASS_PANE, " "));

        // Zakładki
        Tab[] tabs = Tab.values();
        for (int i = 0; i < TAB_SLOTS.length && i < tabs.length; i++) {
            Tab tab = tabs[i];
            boolean active = tab == activeTab;
            String name = (active ? "&f&l" : "&7") + tab.label;
            List<String> lore = active
                ? List.of("&7▶ Aktualnie wybrana")
                : List.of("&7Kliknij aby otworzyć");
            inv.setItem(TAB_SLOTS[i], tabItem(tab.mat, name, lore, tab.name()));
        }

        // Zawartość
        switch (activeTab) {
            case TOKENS  -> buildTokensTab(inv);
            case UNIQUE  -> buildUniqueTab(inv);
            default      -> buildTierTab(inv, activeTab);
        }
    }

    private void buildTierTab(Inventory inv, Tab tab) {
        List<SkinDefinition> sorted = getSortedSkins(tab);
        int slot = 9;
        for (SkinDefinition skin : sorted) {
            if (slot >= GUI_SIZE) break;
            inv.setItem(slot, skinItem(skin));
            slot++;
        }
    }

    private void buildUniqueTab(Inventory inv) {
        String[] ids = {"frost_palace_fire_sword","frost_palace_ice_sword",
                        "primis_gladius_sword","magmaguys_toothpick"};
        int slot = 9;
        for (String id : ids) {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
            if (skin != null && slot < GUI_SIZE) {
                inv.setItem(slot++, skinItem(skin));
            }
        }
    }

    private void buildTokensTab(Inventory inv) {
        // Token Zmiany jako klikalna ikona
        ItemStack ct = changeTokenIcon();
        inv.setItem(9, ct);

        inv.setItem(11, decoration(Material.BOOK,
            "&eℹ Jak używać tokenów",
            "&7Token Zmiany — wymagany zawsze.",
            "&7Token Skina — określa wygląd.",
            "",
            "&7Gracz potrzebuje obu w /skinstudio."));
    }

    // ── Tworzenie itemów GUI ──────────────────────────────────

    /** Ikona skina — ma NBT skin_id, jest zablokowana przed wyciągnięciem */
    private ItemStack skinItem(SkinDefinition skin) {
        Material iconMat = skin.getAllowedTypes().isEmpty()
            ? Material.PAPER : skin.getAllowedTypes().get(0);
        ItemStack item = new ItemStack(iconMat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(skin.getDisplayName()));

        // Ustaw item_model żeby ikona pokazywała wygląd skina
        NamespacedKey modelKey = parseKey(skin.getItemModel());
        if (modelKey != null) meta.setItemModel(modelKey);

        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7ID: &f" + skin.getId()));
        if (skin.hasEquipmentAsset())
            lore.add(colorize("&7Tekstura: &f" + skin.getEquipmentAsset()));
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj sobie 1 Token Skina"));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);

        // NBT — identyfikator skina (kluczowe: po tym rozpoznajemy kliknięcie)
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_GUI_SKIN_ID),
            PersistentDataType.STRING, skin.getId());

        item.setItemMeta(meta);
        return item;
    }

    /** Ikona Tokenu Zmiany */
    private ItemStack changeTokenIcon() {
        ItemStack item = new ItemStack(plugin.getSkinConfig().getChangeTokenMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getSkinConfig().getChangeTokenName()));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getSkinConfig().getChangeTokenLore()) lore.add(colorize(line));
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj sobie 1 szt."));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_GUI_IS_CHANGE),
            PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Zakładka — ma NBT tab */
    private ItemStack tabItem(Material mat, String name, List<String> lore, String tabName) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> c = new ArrayList<>();
        for (String l : lore) c.add(colorize(l));
        meta.lore(c);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_GUI_TAB),
            PersistentDataType.STRING, tabName);
        item.setItemMeta(meta);
        return item;
    }

    /** Dekoracja — ma NBT decoration, nie można jej wyciągnąć */
    private ItemStack decoration(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(colorize(l));
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_GUI_DECORATION),
            PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ── Eventy ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        // Anuluj ZAWSZE — żaden item nie może opuścić GUI
        event.setCancelled(true);

        int slot = event.getRawSlot();
        // Kliknięcie poza GUI (ekwipunek gracza) — ignoruj
        if (slot < 0 || slot >= GUI_SIZE) return;

        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (!clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        NamespacedKey skinKey    = new NamespacedKey(plugin, NBT_GUI_SKIN_ID);
        NamespacedKey changeKey  = new NamespacedKey(plugin, NBT_GUI_IS_CHANGE);
        NamespacedKey tabKey     = new NamespacedKey(plugin, NBT_GUI_TAB);

        // Kliknięcie w zakładkę
        if (pdc.has(tabKey, PersistentDataType.STRING)) {
            String tabName = pdc.get(tabKey, PersistentDataType.STRING);
            Tab newTab = null;
            try { newTab = Tab.valueOf(tabName); } catch (Exception ignored) {}
            if (newTab != null && newTab != currentTab.get(player.getUniqueId())) {
                player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                Tab finalNewTab = newTab;
                // Otwórz nowy inwentarz (nie modyfikuj obecnego)
                Bukkit.getScheduler().runTask(plugin, () -> openTab(player, finalNewTab));
            }
            return;
        }

        // Kliknięcie w skin
        if (pdc.has(skinKey, PersistentDataType.STRING)) {
            String skinId = pdc.get(skinKey, PersistentDataType.STRING);
            SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
            if (skin != null) handleTokenClick(player, event.getClick(), skin);
            return;
        }

        // Kliknięcie w Token Zmiany
        if (pdc.has(changeKey, PersistentDataType.BYTE)) {
            handleTokenClick(player, event.getClick(), null);
            return;
        }

        // Dekoracja lub cokolwiek innego — nic nie rób (już anulowane)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;
        // Zablokuj wszelkie przeciąganie w GUI admina
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        openGuis.remove(player.getUniqueId());
    }

    // ── Logika dawania tokenów ────────────────────────────────

    private void handleTokenClick(Player player, ClickType clickType, SkinDefinition skin) {
        if (clickType == ClickType.LEFT) {
            ItemStack token = (skin == null)
                ? TokenUtil.createChangeToken()
                : TokenUtil.createSkinToken(skin);
            giveItem(player, token);
            String name = (skin == null) ? "Token Zmiany" : skin.getDisplayName();
            msg(player, "&aDodano: &f" + name);
            player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

        } else if (clickType == ClickType.RIGHT) {
            String skinId = (skin == null) ? "__change__" : skin.getId();
            awaitingAmount.put(player.getUniqueId(), skinId);
            player.closeInventory();
            msg(player, "&eWpisz ilość na czacie &7(1-64), lub &ccancel&7 aby anulować:");
        }
    }

    public boolean handleChatInput(Player player, String message) {
        if (!awaitingAmount.containsKey(player.getUniqueId())) return false;
        String skinId = awaitingAmount.remove(player.getUniqueId());

        if (message.equalsIgnoreCase("cancel")) {
            msg(player, "&7Anulowano.");
            Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
            return true;
        }

        int amount;
        try { amount = Integer.parseInt(message.trim()); }
        catch (NumberFormatException e) {
            msg(player, "&cNieprawidłowa liczba — wpisz liczbę 1-64.");
            awaitingAmount.put(player.getUniqueId(), skinId);
            return true;
        }

        if (amount < 1 || amount > 64) {
            msg(player, "&cIlość musi być między 1 a 64.");
            awaitingAmount.put(player.getUniqueId(), skinId);
            return true;
        }

        ItemStack token;
        String name;
        if ("__change__".equals(skinId)) {
            token = TokenUtil.createChangeToken();
            name = "Token Zmiany";
        } else {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(skinId);
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
            if (tab.prefix != null && s.getId().startsWith(tab.prefix + "_")) skins.add(s);

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
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    private NamespacedKey parseKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] parts = key.split(":", 2);
        if (parts.length == 2) return new NamespacedKey(parts[0], parts[1]);
        return NamespacedKey.minecraft(key);
    }

    private void msg(Player player, String message) {
        player.sendMessage(colorize("&8[&6SkinStudio&8] &r" + message));
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
