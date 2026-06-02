package pl.skinstudio;

import org.bukkit.plugin.java.JavaPlugin;
import pl.skinstudio.commands.SkinStudioCommand;
import pl.skinstudio.commands.SkinTokenCommand;
import pl.skinstudio.config.SkinConfig;
import pl.skinstudio.gui.SkinStudioGUI;

public class SkinStudio extends JavaPlugin {

    private static SkinStudio instance;
    private SkinConfig skinConfig;
    private SkinStudioGUI skinStudioGUI;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        skinConfig = new SkinConfig(this);
        skinConfig.load();

        skinStudioGUI = new SkinStudioGUI(this);

        getCommand("skinstudio").setExecutor(new SkinStudioCommand(this, skinStudioGUI));
        getCommand("skintoken").setExecutor(new SkinTokenCommand(this));

        getLogger().info("SkinStudio v" + getDescription().getVersion() + " uruchomiony!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SkinStudio wyłączony.");
    }

    public static SkinStudio getInstance() {
        return instance;
    }

    public SkinConfig getSkinConfig() {
        return skinConfig;
    }
}
