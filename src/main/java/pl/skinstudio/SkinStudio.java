package pl.skinstudio;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.skinstudio.commands.SkinStudioCommand;
import pl.skinstudio.commands.SkinTokenCommand;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.gui.AdminChatListener;
import pl.skinstudio.gui.AdminGUI;
import pl.skinstudio.gui.SkinStudioGUI;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
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
    private SkinConfig skinConfig;
    private LangManager langManager;
    private SkinStudioGUI skinStudioGUI;
    private AdminGUI adminGUI;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();

        for (String line : BANNER) {
            Bukkit.getConsoleSender().sendMessage(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(line));
        }

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

        getLogger().info("v" + getDescription().getVersion() + " | Language: "
            + langManager.getLanguage() + " | Skins: " + skinConfig.getAllSkins().size()
            + " | Tiers: " + adminGUI.getTierCount());
    }

    @Override
    public void onDisable() {
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
        assignConfig(loaded);
    }

    private void assignConfig(YamlConfiguration loaded) {
        try {
            Field field = JavaPlugin.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(this, loaded);
        } catch (ReflectiveOperationException e) {
            getLogger().log(Level.SEVERE, "Nie można przypisać config.yml po wczytaniu UTF-8", e);
        }
    }

    @Override
    public void saveConfig() {
        if (getConfig() == null) return;
        File file = new File(getDataFolder(), "config.yml");
        file.getParentFile().mkdirs();
        try {
            Files.writeString(file.toPath(), getConfig().saveToString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Nie można zapisać config.yml (UTF-8): " + file, e);
        }
    }

    public static SkinStudio getInstance() { return instance; }
    public SkinConfig getSkinConfig() { return skinConfig; }
    public LangManager getLang() { return langManager; }
    public AdminGUI getAdminGUI() { return adminGUI; }
}
