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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.model.SkinDefinition;
import pl.skinstudio.util.TokenUtil;

import java.util.*;

public class AdminGUI implements Listener {

    // Zakładki
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
        final String prefix; // prefix ID skina, null = specjalna zakładka

        Tab(String label, Material mat, String prefix) {
            this.label = label;
            this.mat = mat;
            this.prefix = prefix;
        }
    }

    // Sloty zakładek (rząd górny: 0-8)
    private static final int[] TAB_SLOTS = {0, 1, 2, 3, 4, 5, 6, 8};
    // Sloty na skiny (rzędy 2-5: 9-44, bez górnego rzędu i bez nawigacji)
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END   = 44;
    private static final int GUI_SIZE      = 54;

    // Numer zakładki w TAB_SLOTS
    private static final Map<Integer, Tab> SLOT_TO_TAB = new LinkedHashMap<>();
    static {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length && i < TAB_SLOTS.length; i++) {
            SLOT_TO_TAB.put(TAB_SLOTS[i], tabs[i]);
        }
    }

    private final SkinStudio plugin;
    // UUID gracza → aktualnie otwarta zakładka
    private final Map<UUID, Tab> currentTab = new HashMap<>();
    // UUID gracza → inwentarz
    private final Map<UUID, Inventory> openGuis = new HashMap<>();
    // UUID gracza → oczekuje na wpisanie ilości
    private final Map<UUID, String> awaitingAmount = new HashMap<>();

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
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, glass);

        // Zakładki w górnym rzędzie
        for (Map.Entry<Integer, Tab> entry : SLOT_TO_TAB.entrySet()) {
            Tab tab = entry.getValue();
            boolean isActive = tab == activeTab;
            List<String> lore = isActive
                ? List.of("&7▶ Aktualnie wybrana")
                : List.of("&7Kliknij aby otworzyć");
            String name = (isActive ? "&f&l" : "&7") + tab.label;
            inv.setItem(entry.getKey(), makeItem(tab.mat, name, lore));
        }

        // Separator slot 7
        inv.setItem(7, makeItem(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        // Zawartość zakładki
        if (activeTab == Tab.TOKENS) {
            buildTokensTab(inv);
        } else if (activeTab == Tab.UNIQUE) {
            buildUniqueTab(inv);
        } else {
            buildTierTab(inv, activeTab);
        }
    }

    private void buildTierTab(Inventory inv, Tab tab) {
        List<SkinDefinition> skins = getSkinsByPrefix(tab.prefix);
        // Kolejność wyświetlania
        String[] order = {"sword","axe","bow","crossbow","scythe","trident",
                          "helmet","chestplate","leggings","boots"};

        List<SkinDefinition> sorted = new ArrayList<>();
        for (String suffix : order) {
            for (SkinDefinition s : skins) {
                if (s.getId().endsWith("_" + suffix)) { sorted.add(s); break; }
            }
        }
        // Dorzuć pozostałe których nie ma w kolejności
        for (SkinDefinition s : skins) {
            if (!sorted.contains(s)) sorted.add(s);
        }

        int slot = CONTENT_START;
        for (SkinDefinition skin : sorted) {
            if (slot > CONTENT_END) break;
            inv.setItem(slot, makeSkinItem(skin));
            slot++;
        }
    }

    private void buildUniqueTab(Inventory inv) {
        String[] uniqueIds = {
            "frost_palace_fire_sword", "frost_palace_ice_sword",
            "primis_gladius_sword", "magmaguys_toothpick"
        };
        int slot = CONTENT_START;
        for (String id : uniqueIds) {
            SkinDefinition skin = plugin.getSkinConfig().getSkin(id);
            if (skin != null && slot <= CONTENT_END) {
                inv.setItem(slot, makeSkinItem(skin));
                slot++;
            }
        }
    }

    private void buildTokensTab(Inventory inv) {
        // Token Zmiany
        ItemStack changeToken = TokenUtil.createChangeToken();
        ItemMeta meta = changeToken.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() != null ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj 1 szt."));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);
        changeToken.setItemMeta(meta);
        inv.setItem(CONTENT_START, changeToken);

        // Info
        inv.setItem(CONTENT_START + 2, makeItem(Material.BOOK,
            "&eℹ Jak używać tokenów",
            List.of(
                "&7Token Zmiany — wymagany zawsze",
                "&7przy nakładaniu/zdejmowaniu skina.",
                "",
                "&7Token Skina — określa wygląd.",
                "&7Każdy skin ma osobny token.",
                "",
                "&7Gracz potrzebuje obu tokenów",
                "&7oraz przedmiotu w /skinstudio."
            )));
    }

    private ItemStack makeSkinItem(SkinDefinition skin) {
        // Bazowy materiał na ikonę — bierzemy pierwszy z listy dopuszczalnych
        Material iconMat = skin.getAllowedTypes().isEmpty()
            ? Material.PAPER
            : skin.getAllowedTypes().get(0);

        ItemStack item = new ItemStack(iconMat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(skin.getDisplayName()));

        // Ustaw item_model żeby ikona pokazywała skin
        try {
            meta.setItemModel(org.bukkit.NamespacedKey.fromString(
                skin.getItemModel().replace(":", ":")));
        } catch (Exception ignored) {}

        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7ID: &f" + skin.getId()));
        lore.add(colorize("&7Model: &f" + skin.getItemModel()));
        if (skin.hasEquipmentAsset()) {
            lore.add(colorize("&7Tekstura zbroi: &f" + skin.getEquipmentAsset()));
        }
        lore.add(Component.empty());
        lore.add(colorize("&aLewy klik &7→ daj sobie 1 Token Skina"));
        lore.add(colorize("&ePrawy klik &7→ wybierz ilość"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── Eventy ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv == null || !event.getInventory().equals(inv)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GUI_SIZE) return;

        // Kliknięcie w zakładkę
        if (SLOT_TO_TAB.containsKey(slot)) {
            Tab newTab = SLOT_TO_TAB.get(slot);
            Tab current = currentTab.get(player.getUniqueId());
            if (newTab != current) {
                player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                openTab(player, newTab);
            }
            return;
        }

        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE
            || clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;

        // Kliknięcie w Token Zmiany (zakładka Tokeny)
        if (TokenUtil.isChangeToken(clicked)) {
            handleTokenClick(player, event.getClick(), null);
            return;
        }

        // Kliknięcie w skin
        // Znajdź skin po item_model z ikony
        SkinDefinition skin = findSkinBySlot(player, slot);
        if (skin != null) {
            handleTokenClick(player, event.getClick(), skin);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        openGuis.remove(player.getUniqueId());
    }

    // ── Logika dawania tokenów ────────────────────────────────

    private void handleTokenClick(Player player, ClickType clickType, SkinDefinition skin) {
        if (clickType == ClickType.LEFT) {
            // Daj 1 od razu
            ItemStack token = (skin == null)
                ? TokenUtil.createChangeToken()
                : TokenUtil.createSkinToken(skin);
            giveItem(player, token);
            String name = (skin == null) ? "Token Zmiany" : skin.getDisplayName();
            msg(player, "&aDodano do ekwipunku: &f" + name);
            player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

        } else if (clickType == ClickType.RIGHT) {
            // Zamknij GUI i czekaj na wpisanie ilości
            String skinId = (skin == null) ? "__change__" : skin.getId();
            awaitingAmount.put(player.getUniqueId(), skinId);
            player.closeInventory();
            msg(player, "&eWpisz ilość tokenów na czacie &7(1-64):");
            msg(player, "&7Wpisz &ccancel &7aby anulować.");
        }
    }

    /**
     * Wywołaj z listenera ChatEvent gdy gracz czeka na ilość.
     * Zwraca true jeśli wiadomość została obsłużona.
     */
    public boolean handleChatInput(Player player, String message) {
        if (!awaitingAmount.containsKey(player.getUniqueId())) return false;

        String skinId = awaitingAmount.remove(player.getUniqueId());

        if (message.equalsIgnoreCase("cancel")) {
            msg(player, "&7Anulowano.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(message.trim());
        } catch (NumberFormatException e) {
            msg(player, "&cNieprawidłowa liczba. Wpisz liczbę od 1 do 64.");
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
        msg(player, "&aDodano &f" + amount + "x &f" + name + " &ado ekwipunku.");
        player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

        // Otwórz GUI z powrotem
        Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
        return true;
    }

    public boolean isAwaitingInput(Player player) {
        return awaitingAmount.containsKey(player.getUniqueId());
    }

    // ── Pomocnicze ───────────────────────────────────────────

    private SkinDefinition findSkinBySlot(Player player, int slot) {
        Tab tab = currentTab.get(player.getUniqueId());
        if (tab == null) return null;

        // Odtwórz listę skinów dla aktywnej zakładki
        List<SkinDefinition> skins;
        if (tab == Tab.UNIQUE) {
            skins = new ArrayList<>();
            for (String id : new String[]{"frost_palace_fire_sword","frost_palace_ice_sword",
                                          "primis_gladius_sword","magmaguys_toothpick"}) {
                SkinDefinition s = plugin.getSkinConfig().getSkin(id);
                if (s != null) skins.add(s);
            }
        } else if (tab.prefix != null) {
            skins = getSkinsByPrefix(tab.prefix);
            String[] order = {"sword","axe","bow","crossbow","scythe","trident",
                              "helmet","chestplate","leggings","boots"};
            List<SkinDefinition> sorted = new ArrayList<>();
            for (String suffix : order)
                for (SkinDefinition s : skins)
                    if (s.getId().endsWith("_" + suffix)) { sorted.add(s); break; }
            for (SkinDefinition s : skins) if (!sorted.contains(s)) sorted.add(s);
            skins = sorted;
        } else {
            return null;
        }

        int index = slot - CONTENT_START;
        if (index >= 0 && index < skins.size()) return skins.get(index);
        return null;
    }

    private List<SkinDefinition> getSkinsByPrefix(String prefix) {
        List<SkinDefinition> result = new ArrayList<>();
        for (SkinDefinition s : plugin.getSkinConfig().getAllSkins().values()) {
            if (s.getId().startsWith(prefix + "_")) result.add(s);
        }
        return result;
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
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
