package pl.skinstudio;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.skinstudio.api.SkinStudioAPIProvider;
import pl.skinstudio.commands.SkinStudioCommand;
import pl.skinstudio.commands.SkinTokenCommand;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.gui.AdminChatListener;
import pl.skinstudio.gui.AdminGUI;
import pl.skinstudio.gui.SkinStudioGUI;
import pl.skinstudio.converter.SkinInboxService;
import pl.skinstudio.util.BuiltPackWriter;
import pl.skinstudio.util.PendingPackApplier;
import pl.skinstudio.util.RpmBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;

public class SkinStudio extends JavaPlugin {

    private static final String[] BANNER = {
        "",
        "  &6███████╗&f██╗  ██╗██╗███╗   ██╗",
        "  &6██╔════╝&f██║ ██╔╝██║████╗  ██║",
        "  &6███████╗&f█████╔╝ ██║██╔██╗ ██║",
        "  &6╚════██║&f██╔═██╗ ██║██║╚██╗██║",
        "  &6███████║&f██║  ██╗██║██║ ╚████║",
        "  &6╚══════╝&f╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝",
        "  &6███████╗&f████████╗██╗   ██╗██████╗ ██╗ ██████╗ ",
        "  &6██╔════╝&f╚══██╔══╝██║   ██║██╔══██╗██║██╔═══██╗",
        "  &6███████╗&f   ██║   ██║   ██║██║  ██║██║██║   ██║",
        "  &6╚════██║&f   ██║   ██║   ██║██║  ██║██║██║   ██║",
        "  &6███████║&f   ██║   ╚██████╔╝██████╔╝██║╚██████╔╝",
        "  &6╚══════╝&f   ╚═╝    ╚═════╝ ╚═════╝ ╚═╝ ╚═════╝",
        ""
    };

    private static SkinStudio instance;
    /** UTF-8 config — bez refleksji na JavaPlugin.config (Purpur/Paper 26.x). */
    private YamlConfiguration pluginConfig;
    private SkinConfig skinConfig;
    private LangManager langManager;
    private SkinStudioGUI skinStudioGUI;
    private AdminGUI adminGUI;
    private SkinInboxService inboxService;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveBundleTemplate();
        reloadConfig();

        for (String line : BANNER) {
            Bukkit.getConsoleSender().sendMessage(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(line));
        }
        var c = Bukkit.getConsoleSender();
        c.sendMessage("§7  Version §f" + getDescription().getVersion() + "  §7│  Author §bBellzeb");
        c.sendMessage("§7  Status  §7Custom Skins & Tiers");
        c.sendMessage("§r");

        langManager = new LangManager(this);
        skinConfig = new SkinConfig(this);
        skinConfig.load();

        skinStudioGUI = new SkinStudioGUI(this);
        adminGUI = new AdminGUI(this);

        Bukkit.getPluginManager().registerEvents(new AdminChatListener(adminGUI), this);

        SkinStudioCommand cmd = new SkinStudioCommand(skinStudioGUI, adminGUI);
        getCommand("skinstudio").setExecutor(cmd);
        getCommand("skinstudio").setTabCompleter(cmd);
        getCommand("skintoken").setExecutor(new SkinTokenCommand(this));

        SkinStudioAPIProvider.register(this);

        inboxService = new SkinInboxService(this);
        inboxService.start();

        applyPendingFixedPacksOnStartup();

        getLogger().info("v" + getDescription().getVersion() + " | Language: "
            + langManager.getLanguage() + " | Skins: " + skinConfig.getAllSkins().size()
            + " | Tiers: " + adminGUI.getTierCount()
            + " | Inbox: " + inboxService.getConverter().inboxDir().getPath()
            + " | API: pl.skinstudio.api.SkinStudioAPI");
    }

    @Override
    public void onDisable() {
        if (inboxService != null) inboxService.stop();
        SkinStudioAPIProvider.unregister();
        getLogger().info("SkinStudio disabled.");
    }

    /** Always UTF-8 — domyślny saveConfig() na Windows potrafi zepsuć polskie znaki. */
    @Override
    public void reloadConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            saveDefaultConfig();
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Nie można wczytać config.yml (UTF-8): " + file, e);
        }
        InputStream defaults = getResource("config.yml");
        if (defaults != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaults, StandardCharsets.UTF_8));
            loaded.setDefaults(defConfig);
        }
        loaded.options().copyDefaults(true);
        pluginConfig = loaded;
    }

    @Override
    public FileConfiguration getConfig() {
        if (pluginConfig == null) {
            reloadConfig();
        }
        return pluginConfig;
    }

    @Override
    public void saveConfig() {
        if (pluginConfig == null) return;
        File file = new File(getDataFolder(), "config.yml");
        file.getParentFile().mkdirs();
        try {
            Files.writeString(file.toPath(), pluginConfig.saveToString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Nie można zapisać config.yml (UTF-8): " + file, e);
        }
    }

    public static SkinStudio getInstance() { return instance; }
    public SkinConfig getSkinConfig() { return skinConfig; }
    public LangManager getLang() { return langManager; }
    public AdminGUI getAdminGUI() { return adminGUI; }
    public SkinInboxService getInboxService() { return inboxService; }

    private void saveBundleTemplate() {
        saveResource("bundle-template/dark_queen_sword/skin.yml", false);
    }

    /** Podmiana *.skinstudio-fixed.zip → oryginalne nazwy w mixer (RPM zwykle nie trzyma plików przy starcie). */
    public void applyPendingFixedPacksOnStartup() {
        String mixerPath = getConfig().getString("scanner.mixer-folder", "ResourcePackManager/mixer");
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            var result = PendingPackApplier.applyAll(getServer().getPluginsFolder(), mixerPath, getLogger());
            var built = BuiltPackWriter.applyPending(this, getLogger());
            if (result.applied() > 0 || built.applied() > 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (result.applied() > 0) {
                        getLogger().info("Auto-podmieniono " + result.applied() + " pack(ów) w mixer.");
                    }
                    if (built.applied() > 0) {
                        getLogger().info("Auto-podmieniono pending SkinStudio-skins.zip w mixer.");
                    }
                    RpmBridge.reloadMergedPack(this);
                });
            }
        }, 40L);
    }
}
