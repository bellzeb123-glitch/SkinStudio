package pl.skinstudio.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.skinstudio.SkinStudio;

/**
 * Fired after SkinStudio reloads its skin catalog (config + SkinConfig).
 * BellItems listens to refresh SkinStudioBridge without server restart.
 */
public final class SkinStudioCatalogReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SkinStudio plugin;
    private final int skinCount;

    public SkinStudioCatalogReloadEvent(SkinStudio plugin, int skinCount) {
        this.plugin = plugin;
        this.skinCount = skinCount;
    }

    public SkinStudio getPlugin() {
        return plugin;
    }

    public int getSkinCount() {
        return skinCount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
