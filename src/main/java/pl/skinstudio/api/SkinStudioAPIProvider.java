package pl.skinstudio.api;

import pl.skinstudio.SkinStudio;

/**
 * Holds the live API instance — registered by SkinStudio on enable, cleared on disable.
 */
public final class SkinStudioAPIProvider {

    private static volatile SkinStudioAPI instance;

    private SkinStudioAPIProvider() {}

    public static SkinStudioAPI get() {
        return instance;
    }

    public static void register(SkinStudio plugin) {
        instance = new SkinStudioAPIImpl(plugin);
    }

    public static void unregister() {
        instance = null;
    }
}
