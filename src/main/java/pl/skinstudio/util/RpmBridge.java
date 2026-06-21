package pl.skinstudio.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Wymusza przebudowę merged packa w ResourcePackManager po normalizacji / buildzie. */
public final class RpmBridge {

    private static final String RPM_PLUGIN = "ResourcePackManager";
    private static final String RPM_API = "com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI";
    private static final String RPM_AUTOHOST = "com.magmaguy.resourcepackmanager.autohost.AutoHost";

    private RpmBridge() {}

    /**
     * Bezpieczne z dowolnego wątku — reload przez API (preferowane), fallback na komendę z main thread.
     */
    public static void reloadMergedPack(SkinStudio plugin) {
        if (!plugin.getConfig().getBoolean("scanner.auto-rpm-reload", true)) return;
        Plugin rpm = Bukkit.getPluginManager().getPlugin(RPM_PLUGIN);
        if (rpm == null || !rpm.isEnabled()) return;

        Runnable reload = () -> {
            ensureRpmEnvironment(plugin);
            registerBuiltPack(plugin);
            if (reloadViaApi(plugin)) {
                schedulePushToPlayers(plugin);
                return;
            }
            dispatchReloadCommand(plugin);
            schedulePushToPlayers(plugin);
        };

        if (Bukkit.isPrimaryThread()) {
            reload.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, reload);
        }
    }

    /** Wymusza ponowne wysłanie merged packa do graczy online (CDN cache / brak rejoin). */
    public static boolean pushToOnlinePlayers(SkinStudio plugin) {
        Plugin rpm = Bukkit.getPluginManager().getPlugin(RPM_PLUGIN);
        if (rpm == null || !rpm.isEnabled()) {
            plugin.getLogger().warning("RPM push: ResourcePackManager wyłączony.");
            return false;
        }
        try {
            Class<?> autoHost = Class.forName(RPM_AUTOHOST);
            Method broadcast = autoHost.getDeclaredMethod("broadcastResourcePackSync");
            broadcast.setAccessible(true);
            broadcast.invoke(null);
            plugin.getLogger().info("RPM: wymuszono wysłanie resource packa do graczy online.");
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("RPM push nie powiódł się — wyloguj się z serwera i wejdź ponownie: "
                + e.getMessage());
            return false;
        }
    }

    /**
     * Rejestruje SkinStudio-skins.zip bezpośrednio w RPM, zamiast liczyć tylko na manualny mixer.
     * RPM po reloadzie sam odtwarza packi FMM/EliteMobs, więc sam plik w mixerze nie wystarcza.
     */
    public static void registerBuiltPack(SkinStudio plugin) {
        try {
            Class<?> api = Class.forName(RPM_API);
            String outputFolder = plugin.getConfig().getString("converter.output-folder", "pack");
            String relativePath = ("SkinStudio/" + outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME).replace('\\', '/');
            File built = new File(plugin.getDataFolder(), outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME);
            if (!built.isFile()) {
                plugin.getLogger().warning("RPM register: brak " + built.getPath());
                return;
            }
            writeCompatiblePluginConfig(plugin, relativePath);

            try {
                Method m = api.getMethod("registerLocalResourcePack",
                    String.class, String.class, boolean.class, boolean.class, boolean.class, String.class);
                m.invoke(null, "SkinStudio", relativePath, false, true, true, null);
                plugin.getLogger().info("ResourcePackManager: zarejestrowano " + relativePath + " przez registerLocalResourcePack().");
                return;
            } catch (NoSuchMethodException ignored) {
                // starszy wariant API poniżej
            }

            try {
                Method m = api.getMethod("registerResourcePack",
                    String.class, String.class, boolean.class, boolean.class, boolean.class, boolean.class, String.class);
                m.invoke(null, "SkinStudio", relativePath, false, true, true, true, null);
                plugin.getLogger().info("ResourcePackManager: zarejestrowano " + relativePath + " przez registerResourcePack().");
            } catch (NoSuchMethodException e) {
                plugin.getLogger().warning("ResourcePackManager API nie ma metody rejestracji packa; używam samego mixer/priorityOrder.");
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().fine("RPM API niedostępne — używam samego mixer/priorityOrder.");
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "RPM register SkinStudio-skins.zip nie powiódł się: " + e.getMessage());
        }
    }

    /** Blueprint RPM (format 34) + martwe wpisy priorityOrder psują merged pack na 26.1.x. */
    public static void ensureRpmEnvironment(SkinStudio plugin) {
        int packFormat = plugin.getConfig().getInt("scanner.pack-format", 84);
        fixBlueprintMcmeta(plugin, packFormat);
        cleanPriorityOrder(plugin);
    }

    private static void fixBlueprintMcmeta(SkinStudio plugin, int packFormat) {
        File blueprint = new File(plugin.getServer().getPluginsFolder(),
            "ResourcePackManager/blueprint/pack.mcmeta");
        if (!blueprint.isFile()) return;
        try {
            String json = Files.readString(blueprint.toPath(), StandardCharsets.UTF_8);
            String current = PackMcmetaUtil.readFormat(json);
            String target = String.valueOf(packFormat);
            if (target.equals(current)) return;
            byte[] fixed = PackMcmetaUtil.fixFormatIfNeeded(json.getBytes(StandardCharsets.UTF_8), packFormat);
            if (fixed != null) {
                Files.write(blueprint.toPath(), fixed);
                plugin.getLogger().warning("RPM blueprint/pack.mcmeta: pack_format "
                    + current + " → " + target + " (wymagane na Purpur 26.1.x)");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się naprawić RPM blueprint: " + e.getMessage());
        }
    }

    private static final String SKINSTUDIO_MIXER_ZIP = "SkinStudio_resource_pack.zip";

    private static void cleanPriorityOrder(SkinStudio plugin) {
        File config = new File(plugin.getServer().getPluginsFolder(), "ResourcePackManager/config.yml");
        if (!config.isFile()) return;
        try {
            List<String> lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            boolean changed = false;
            boolean hasSkinStudioMixer = false;
            boolean inPriority = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("priorityOrder:")) {
                    inPriority = true;
                    out.add(line);
                    continue;
                }
                if (inPriority && trimmed.startsWith("- ") && !trimmed.startsWith("- #")) {
                    if (trimmed.equals("- SkinStudio-skins.zip")) {
                        changed = true;
                        continue;
                    }
                    if (trimmed.equals("- " + SKINSTUDIO_MIXER_ZIP)) {
                        hasSkinStudioMixer = true;
                    }
                    // Koniec listy priorityOrder (następna sekcja top-level bez wcięcia)
                } else if (inPriority && !trimmed.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    if (!hasSkinStudioMixer) {
                        out.add("- " + SKINSTUDIO_MIXER_ZIP);
                        hasSkinStudioMixer = true;
                        changed = true;
                    }
                    inPriority = false;
                }
                out.add(line);
            }
            if (inPriority && !hasSkinStudioMixer) {
                out.add("- " + SKINSTUDIO_MIXER_ZIP);
                changed = true;
            }
            if (changed) {
                Files.write(config.toPath(), out, StandardCharsets.UTF_8);
                plugin.getLogger().info("RPM config: zaktualizowano priorityOrder (SkinStudio mixer zip).");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się oczyścić RPM priorityOrder: " + e.getMessage());
        }
    }

    private static void writeCompatiblePluginConfig(SkinStudio plugin, String relativePath) {
        File compat = new File(plugin.getServer().getPluginsFolder(),
            "ResourcePackManager/compatible_plugins/skinstudio.yml");
        try {
            compat.getParentFile().mkdirs();
            String fixed = """
                isEnabled: true
                pluginName: SkinStudio
                zips: true
                reloadCommand: ''
                localPath: %s
                cluster: false
                additionalLocalPath: ''
                """.formatted(relativePath.replace('/', File.separatorChar));
            Files.writeString(compat.toPath(), fixed, StandardCharsets.UTF_8);
            plugin.getLogger().info("ResourcePackManager: zapisano compatible_plugins/skinstudio.yml → " + relativePath);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się naprawić skinstudio.yml RPM: " + e.getMessage());
        }
    }

    private static boolean reloadViaApi(SkinStudio plugin) {
        try {
            Class<?> api = Class.forName(RPM_API);
            Method reload = api.getMethod("reloadResourcePack");
            reload.invoke(null);
            plugin.getLogger().info("ResourcePackManager: reloadResourcePack() (API) po buildzie packa.");
            return true;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().fine("RPM API niedostępne — fallback na komendę.");
            return false;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "RPM API reload nie powiódł się: " + e.getMessage());
            return false;
        }
    }

    private static void dispatchReloadCommand(SkinStudio plugin) {
        String cmd = plugin.getConfig().getString("scanner.rpm-reload-command", "resourcepackmanager reload");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        plugin.getLogger().info("ResourcePackManager: wykonano '" + cmd + "' po buildzie packa.");
    }

    private static void schedulePushToPlayers(SkinStudio plugin) {
        if (!plugin.getConfig().getBoolean("scanner.push-after-reload", true)) return;
        int delay = plugin.getConfig().getInt("scanner.push-delay-ticks", 80);
        Bukkit.getScheduler().runTaskLater(plugin, () -> pushToOnlinePlayers(plugin), delay);
    }
}
