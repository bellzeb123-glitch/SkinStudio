package pl.skinstudio.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
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
import pl.skinstudio.config.LangManager;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class AdminGUI implements Listener {

    private static class SkinAdminHolder implements InventoryHolder {
        private final String tabId;
        private Inventory inv;
        SkinAdminHolder(String tabId) { this.tabId = tabId; }
        @Override public Inventory getInventory() { return inv; }
        void setInventory(Inventory inv) { this.inv = inv; }
        String getTabId() { return tabId; }
    }

    private record TierDef(String id, String displayName, Material material) {}

    private static final String TAB_TOKENS = "__tokens__";
    private static final String TAB_UNIQUE = "__unique__";

    private static final String NBT_SKIN_ID   = "ss_skin_id";
    private static final String NBT_IS_CHANGE = "ss_is_change";
    private static final String NBT_TAB_ID    = "ss_tab_id";
    private static final String NBT_LANGUAGE  = "ss_language_toggle";
    private static final String NBT_TAB_PAGE  = "ss_tab_page_nav";
    private static final String NBT_SKIN_PAGE = "ss_skin_page_nav";

    private static final int GUI_SIZE = 54;
    /** Zakładki: sloty 0–6 (7 widocznych), 7 = poprzednia strona, 8 = następna. */
    private static final int TAB_SLOTS = 7;
    private static final int SLOT_TAB_PREV = 7;
    private static final int SLOT_TAB_NEXT = 8;
    /** Skiny: sloty 9–44, nawigacja w 45 i 53. */
    private static final int SKIN_SLOT_START = 9;
    private static final int SKIN_SLOT_END = 44;
    private static final int SKINS_PER_PAGE = SKIN_SLOT_END - SKIN_SLOT_START + 1;
    private static final int SLOT_SKIN_PREV = 45;
    private static final int SLOT_SKIN_NEXT = 53;

    private final SkinStudio plugin;
    private final Map<UUID, String> currentTab     = new HashMap<>();
    private final Map<UUID, Integer> tabPage         = new HashMap<>();
    private final Map<UUID, Integer> skinPage        = new HashMap<>();
    private final Map<UUID, String> awaitingAmount = new HashMap<>();
    private List<TierDef> tiers = new ArrayList<>();

    public AdminGUI(SkinStudio plugin) {
        this.plugin = plugin;
        loadTiers();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private LangManager lang() { return plugin.getLang(); }

    public void loadTiers() {
        tiers = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tiers");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection ts = section.getConfigurationSection(id);
                if (ts == null) continue;
                String name = ts.getString("display-name", id);
                Material mat = Material.getMaterial(
                    ts.getString("material", "GRAY_STAINED_GLASS_PANE").toUpperCase());
                if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
                tiers.add(new TierDef(id, name, mat));
            }
        }
    }

    public void openFor(Player player) {
        String tab = currentTab.get(player.getUniqueId());
        if (tab == null) tab = tiers.isEmpty() ? TAB_TOKENS : tiers.get(0).id;
        openTab(player, tab);
    }

    private void openTab(Player player, String tabId) {
        openTab(player, tabId, tabPageFor(player.getUniqueId(), tabId), skinPage.getOrDefault(player.getUniqueId(), 0));
    }

    private void openTab(Player player, String tabId, int tabPg, int skinPg) {
        UUID uid = player.getUniqueId();
        currentTab.put(uid, tabId);
        tabPage.put(uid, tabPg);
        skinPage.put(uid, skinPg);

        SkinAdminHolder holder = new SkinAdminHolder(tabId);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE,
            lang().raw("admin.title"));
        holder.setInventory(inv);

        buildGui(inv, tabId, tabPg, skinPg);
        player.openInventory(inv);
    }

    private int tabPageFor(UUID uid, String activeTabId) {
        List<String> allTabIds = allTabIds();
        int idx = allTabIds.indexOf(activeTabId);
        int current = tabPage.getOrDefault(uid, 0);
        if (idx < 0) return 0;
        int maxPage = Math.max(0, (allTabIds.size() - 1) / TAB_SLOTS);
        if (idx < current * TAB_SLOTS || idx >= (current + 1) * TAB_SLOTS) {
            return idx / TAB_SLOTS;
        }
        return Math.min(current, maxPage);
    }

    private List<String> allTabIds() {
        List<String> ids = new ArrayList<>();
        for (TierDef t : tiers) ids.add(t.id);
        ids.add(TAB_UNIQUE);
        ids.add(TAB_TOKENS);
        return ids;
    }

    private void buildGui(Inventory inv, String activeTabId, int tabPg, int skinPg) {
        for (int i = 0; i < GUI_SIZE; i++)
            inv.setItem(i, glass(Material.GRAY_STAINED_GLASS_PANE));

        List<String> allTabIds = allTabIds();
        int maxTabPage = Math.max(0, (allTabIds.size() - 1) / TAB_SLOTS);
        tabPg = Math.max(0, Math.min(tabPg, maxTabPage));

        int tabFrom = tabPg * TAB_SLOTS;
        int tabTo = Math.min(tabFrom + TAB_SLOTS, allTabIds.size());
        for (int i = tabFrom; i < tabTo; i++) {
            String tid = allTabIds.get(i);
            inv.setItem(i - tabFrom, tabItem(tid, tid.equals(activeTabId)));
        }

        if (tabPg > 0) {
            inv.setItem(SLOT_TAB_PREV, pageNavItem(
                Material.ARROW, lang().getRaw("admin.tab-prev"), tabPg - 1, true));
        }
        if (tabPg < maxTabPage) {
            inv.setItem(SLOT_TAB_NEXT, pageNavItem(
                Material.ARROW, lang().getRaw("admin.tab-next"), tabPg + 1, true));
        }

        if (TAB_TOKENS.equals(activeTabId)) {
            buildTokensTab(inv);
        } else if (TAB_UNIQUE.equals(activeTabId)) {
            buildUniqueTab(inv, skinPg);
        } else {
            buildTierTab(inv, activeTabId, skinPg);
        }
    }

    private void buildTierTab(Inventory inv, String tierId, int page) {
        renderSkinPage(inv, getSortedSkins(tierId), page);
    }

    private void buildUniqueTab(Inventory inv, int page) {
        List<SkinDefinition> skins = new ArrayList<>();
        for (String id : plugin.getConfig().getStringList("unique-skins")) {
            SkinDefinition s = plugin.getSkinConfig().getSkin(id);
            if (s != null) skins.add(s);
        }
        renderSkinPage(inv, skins, page);
    }

    private void renderSkinPage(Inventory inv, List<SkinDefinition> skins, int page) {
        int maxPage = Math.max(0, (skins.size() - 1) / SKINS_PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        int from = page * SKINS_PER_PAGE;
        int to = Math.min(from + SKINS_PER_PAGE, skins.size());

        int slot = SKIN_SLOT_START;
        for (int i = from; i < to; i++) {
            inv.setItem(slot++, skinItem(skins.get(i)));
        }

        if (page > 0) {
            inv.setItem(SLOT_SKIN_PREV, pageNavItem(
                Material.SPECTRAL_ARROW, lang().getRaw("admin.skin-prev"), page - 1, false));
        }
        if (page < maxPage) {
            inv.setItem(SLOT_SKIN_NEXT, pageNavItem(
                Material.SPECTRAL_ARROW, lang().getRaw("admin.skin-next"), page + 1, false));
        }
    }

    private ItemStack pageNavItem(Material mat, String name, int targetPage, boolean tabNav) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(lang().getRaw("admin.page-click")));
        meta.lore(lore);
        NamespacedKey key = new NamespacedKey(plugin, tabNav ? NBT_TAB_PAGE : NBT_SKIN_PAGE);
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, targetPage);
        item.setItemMeta(meta);
        return item;
    }

    private void buildTokensTab(Inventory inv) {
        inv.setItem(9, changeTokenItem());
        inv.setItem(11, infoItem());
        inv.setItem(13, languageItem());
    }

    private ItemStack skinItem(SkinDefinition skin) {
        Material mat = skin.getAllowedTypes().isEmpty()
            ? Material.PAPER : skin.getAllowedTypes().get(0);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(skin.getDisplayName()));

        NamespacedKey mk = parseKey(skin.getItemModel());
        if (mk != null) meta.setItemModel(mk);

        List<Component> lore = new ArrayList<>();
        lore.add(colorize(lang().getRaw("admin.skin-id", "id", skin.getId())));
        if (skin.hasEquipmentAsset())
            lore.add(colorize(lang().getRaw("admin.skin-texture", "asset", skin.getEquipmentAsset())));
        lore.add(Component.empty());
        lore.add(colorize(lang().getRaw("admin.skin-left-click")));
        lore.add(colorize(lang().getRaw("admin.skin-right-click")));
        meta.lore(lore);

        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_SKIN_ID),
            PersistentDataType.STRING, skin.getId());

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack changeTokenItem() {
        ItemStack item = new ItemStack(plugin.getSkinConfig().getChangeTokenMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(lang().getRaw("admin.change-token-name")));
        List<Component> lore = new ArrayList<>();
        for (String l : lang().getList("admin.change-token-lore")) lore.add(colorize(l));
        lore.add(Component.empty());
        lore.add(colorize(lang().getRaw("admin.change-left-click")));
        lore.add(colorize(lang().getRaw("admin.change-right-click")));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_IS_CHANGE),
            PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tabItem(String tabId, boolean active) {
        Material mat;
        String label;
        if (TAB_TOKENS.equals(tabId)) {
            mat = Material.NETHER_STAR;
            label = lang().getRaw("admin.tab-tokens");
        } else if (TAB_UNIQUE.equals(tabId)) {
            mat = Material.RED_STAINED_GLASS_PANE;
            label = lang().getRaw("admin.tab-unique");
        } else {
            TierDef tier = findTier(tabId);
            mat = tier != null ? tier.material : Material.GRAY_STAINED_GLASS_PANE;
            label = tier != null ? tier.displayName : tabId;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(active ? boldLabel(label) : label));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(active
            ? lang().getRaw("admin.tab-active")
            : lang().getRaw("admin.tab-click")));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_TAB_ID),
            PersistentDataType.STRING, tabId);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack languageItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(lang().getRaw("admin.language-button",
            "lang", lang().getLanguage().toUpperCase())));
        List<Component> lore = new ArrayList<>();
        for (String line : lang().getList("admin.language-lore")) lore.add(colorize(line));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, NBT_LANGUAGE),
            PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(lang().getRaw("admin.info-title")));
        List<Component> lore = new ArrayList<>();
        for (String line : lang().getList("admin.info-lore")) lore.add(colorize(line));
        meta.lore(lore);
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

    private boolean isOurGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof SkinAdminHolder;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory topInv = event.getInventory();
        if (!isOurGui(topInv)) return;

        event.setCancelled(true);

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= GUI_SIZE) return;

        ItemStack clicked = topInv.getItem(raw);
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (!clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        NamespacedKey tabKey    = new NamespacedKey(plugin, NBT_TAB_ID);
        NamespacedKey skinKey   = new NamespacedKey(plugin, NBT_SKIN_ID);
        NamespacedKey changeKey = new NamespacedKey(plugin, NBT_IS_CHANGE);
        NamespacedKey langKey   = new NamespacedKey(plugin, NBT_LANGUAGE);
        NamespacedKey tabPageKey = new NamespacedKey(plugin, NBT_TAB_PAGE);
        NamespacedKey skinPageKey = new NamespacedKey(plugin, NBT_SKIN_PAGE);

        if (pdc.has(langKey, PersistentDataType.BYTE)) {
            cycleLanguage(player);
            return;
        }

        if (pdc.has(tabPageKey, PersistentDataType.INTEGER)) {
            int newTabPg = pdc.get(tabPageKey, PersistentDataType.INTEGER);
            player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            String tab = currentTab.getOrDefault(player.getUniqueId(), TAB_TOKENS);
            Bukkit.getScheduler().runTask(plugin, () -> openTab(player, tab, newTabPg, skinPage.getOrDefault(player.getUniqueId(), 0)));
            return;
        }

        if (pdc.has(skinPageKey, PersistentDataType.INTEGER)) {
            int newSkinPg = pdc.get(skinPageKey, PersistentDataType.INTEGER);
            player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            String tab = currentTab.getOrDefault(player.getUniqueId(), TAB_TOKENS);
            Bukkit.getScheduler().runTask(plugin, () -> openTab(player, tab, tabPage.getOrDefault(player.getUniqueId(), 0), newSkinPg));
            return;
        }

        if (pdc.has(tabKey, PersistentDataType.STRING)) {
            String newTabId = pdc.get(tabKey, PersistentDataType.STRING);
            String current = currentTab.getOrDefault(player.getUniqueId(), "");
            if (!newTabId.equals(current)) {
                player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                Bukkit.getScheduler().runTask(plugin, () -> openTab(player, newTabId, tabPageFor(player.getUniqueId(), newTabId), 0));
            }
            return;
        }

        if (pdc.has(changeKey, PersistentDataType.BYTE)) {
            handleClick(player, event.getClick(), null);
            return;
        }

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
    public void onInventoryClose(InventoryCloseEvent event) {}

    private void handleClick(Player player, ClickType click, SkinDefinition skin) {
        if (click == ClickType.LEFT) {
            ItemStack token = (skin == null)
                ? TokenUtil.createChangeToken()
                : TokenUtil.createSkinToken(skin);
            giveItem(player, token);
            String name = (skin == null)
                ? plugin.getSkinConfig().getChangeTokenName()
                : skin.getDisplayName();
            player.sendMessage(lang().component("admin.gave-skin-token", "name", name));
            player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

        } else if (click == ClickType.RIGHT) {
            String id = (skin == null) ? "__change__" : skin.getId();
            awaitingAmount.put(player.getUniqueId(), id);
            player.closeInventory();
            player.sendMessage(lang().component("admin.prompt-amount"));
        }
    }

    public boolean handleChatInput(Player player, String message) {
        if (!awaitingAmount.containsKey(player.getUniqueId())) return false;
        String id = awaitingAmount.remove(player.getUniqueId());

        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage(lang().component("admin.prompt-cancelled"));
            Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
            return true;
        }

        int amount;
        try { amount = Integer.parseInt(message.trim()); }
        catch (NumberFormatException e) {
            player.sendMessage(lang().component("admin.prompt-invalid"));
            awaitingAmount.put(player.getUniqueId(), id);
            return true;
        }

        if (amount < 1 || amount > 64) {
            player.sendMessage(lang().component("admin.prompt-range"));
            awaitingAmount.put(player.getUniqueId(), id);
            return true;
        }

        ItemStack token;
        String name;
        if ("__change__".equals(id)) {
            token = TokenUtil.createChangeToken();
            name = plugin.getSkinConfig().getChangeTokenName();
        } else {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
            if (skin == null) {
                player.sendMessage(lang().component("admin.prompt-skin-error"));
                return true;
            }
            token = TokenUtil.createSkinToken(skin);
            name = skin.getDisplayName();
        }
        token.setAmount(amount);
        giveItem(player, token);
        player.sendMessage(lang().component("admin.gave-amount", "amount", amount, "name", name));
        player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
        Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
        return true;
    }

    public boolean isAwaitingInput(Player player) {
        return awaitingAmount.containsKey(player.getUniqueId());
    }

    public int getTierCount() { return tiers.size(); }

    private void cycleLanguage(Player player) {
        List<String> langs = LangManager.AVAILABLE_LANGUAGES;
        int idx = langs.indexOf(lang().getLanguage());
        String next = langs.get((idx + 1) % langs.size());
        plugin.getLang().setLanguage(next);

        player.sendMessage(lang().component("admin.language-changed", "lang", next.toUpperCase()));
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        Bukkit.getScheduler().runTask(plugin, () -> openTab(player, currentTab.get(player.getUniqueId()), tabPage.getOrDefault(player.getUniqueId(), 0), skinPage.getOrDefault(player.getUniqueId(), 0)));
    }

    private TierDef findTier(String id) {
        for (TierDef t : tiers) if (t.id.equals(id)) return t;
        return null;
    }

    private List<SkinDefinition> getSortedSkins(String tierId) {
        List<SkinDefinition> skins = new ArrayList<>();
        for (SkinDefinition s : plugin.getSkinConfig().getAllSkins().values())
            if (s.getId().startsWith(tierId + "_"))
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

    /**
     * Inserts &l (bold) right after a leading color code, since a color code
     * resets bold formatting in legacy text — "&f&l&6Foo" would render unbold.
     */
    private String boldLabel(String label) {
        if (label.length() >= 2 && label.charAt(0) == '&') {
            return label.substring(0, 2) + "&l" + label.substring(2);
        }
        return "&l" + label;
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
