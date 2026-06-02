package pl.skinstudio;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.skinstudio.commands.SkinStudioCommand;
import pl.skinstudio.commands.SkinTokenCommand;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.gui.AdminChatListener;
import pl.skinstudio.gui.AdminGUI;
import pl.skinstudio.gui.SkinStudioGUI;

public class SkinStudio extends JavaPlugin {

    private static SkinStudio instance;
    private SkinConfig skinConfig;
    private SkinStudioGUI skinStudioGUI;
    private AdminGUI adminGUI;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        skinConfig = new SkinConfig(this);
        skinConfig.load();

        skinStudioGUI = new SkinStudioGUI(this);
        adminGUI = new AdminGUI(this);

        Bukkit.getPluginManager().registerEvents(new AdminChatListener(adminGUI), this);

        getCommand("skinstudio").setExecutor(new SkinStudioCommand(skinStudioGUI, adminGUI));
        getCommand("skinstudio").setTabCompleter((SkinStudioCommand) getCommand("skinstudio").getExecutor());
        getCommand("skintoken").setExecutor(new SkinTokenCommand(this));

        getLogger().info("SkinStudio v" + getDescription().getVersion() + " uruchomiony!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SkinStudio wyłączony.");
    }

    public static SkinStudio getInstance() { return instance; }
    public SkinConfig getSkinConfig() { return skinConfig; }
}
