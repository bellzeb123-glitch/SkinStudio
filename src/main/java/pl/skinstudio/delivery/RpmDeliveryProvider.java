package pl.skinstudio.delivery;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.util.RpmBridge;

/** Dostawa przez ResourcePackManager (domyślny provider gdy RPM jest zainstalowany). */
public final class RpmDeliveryProvider implements PackDeliveryProvider {

    private static final String RPM_PLUGIN = "ResourcePackManager";

    private final SkinStudio plugin;

    public RpmDeliveryProvider(SkinStudio plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "ResourcePackManager";
    }

    @Override
    public boolean available() {
        Plugin rpm = Bukkit.getPluginManager().getPlugin(RPM_PLUGIN);
        return rpm != null && rpm.isEnabled();
    }

    @Override
    public void deliver(boolean force) {
        Runnable task = () -> RpmBridge.reloadRaw(plugin);
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
