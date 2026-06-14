package pl.skinstudio;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.skinstudio.commands.SkinStudioCommand;
import pl.skinstudio.commands.SkinTokenCommand;
import pl.skinstudio.config.LangManager;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.gui.AdminChatListener;
import pl.skinstudio.gui.AdminGUI;
import pl.skinstudio.gui.SkinStudioGUI;

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

    public static SkinStudio getInstance() { return instance; }
    public SkinConfig getSkinConfig() { return skinConfig; }
    public LangManager getLang() { return langManager; }
    public AdminGUI getAdminGUI() { return adminGUI; }
}
