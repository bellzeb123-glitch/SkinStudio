package pl.skinstudio.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Wymusza przebudowę merged packa w ResourcePackManager po normalizacji / buildzie. */
public final class RpmBridge {

    private static final String RPM_PLUGIN = "ResourcePackManager";
    private static final String RPM_API = "com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI";
    private static final String RPM_AUTOHOST = "com.magmaguy.resourcepackmanager.autohost.AutoHost";

    private RpmBridge() {}

    /** Kontekst automatyczny (startup / inbox) — respektuje flagę i grace startu. */
    public static void reloadMergedPack(SkinStudio plugin) {
        reloadMergedPack(plugin, false);
    }

    /**
     * Bezpieczne z dowolnego wątku — reload przez API (preferowane), fallback na komendę z main thread.
     * <p>
     * Optymalizacja (A+C+D):
     * <ul>
     *   <li><b>A</b> — {@code force=true} (jawna komenda admina) reloaduje zawsze, pomijając grace i flagę.</li>
     *   <li><b>C</b> — hash-guard: jeśli {@code pack.zip} bez zmian, pomijamy kosztowny re-mix + upload.</li>
     *   <li><b>D</b> — po reloadzie auto-push do graczy online (bez rejoina) wg {@code scanner.push-after-reload}.</li>
     * </ul>
     *
     * @param force {@code true} dla jawnej akcji admina; {@code false} dla kontekstu auto (respektuje grace startu).
     */
    public static void reloadMergedPack(SkinStudio plugin, boolean force) {
        Plugin rpm = Bukkit.getPluginManager().getPlugin(RPM_PLUGIN);
        if (rpm == null || !rpm.isEnabled()) return;

        if (!force) {
            if (!plugin.getConfig().getBoolean("scanner.auto-rpm-reload", true)) return;
            if (!plugin.isBootGracePassed()) {
                plugin.getLogger().info("RPM reload pominięty (start serwera) — RPM zmiksuje SkinStudio sam przy boocie.");
                return;
            }
        }

        Runnable reload = () -> {
            ensureRpmEnvironment(plugin);
            registerBuiltPack(plugin);

            if (!packChangedSinceLastReload(plugin)) {
                plugin.getLogger().info("RPM reload pominięty — pack bez zmian (hash-guard).");
                return;
            }
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

    /**
     * Surowa dostawa RPM bez boot-grace/hash-guard (te robi {@code PackDelivery}).
     * MUSI być wołane z głównego wątku.
     */
    public static void reloadRaw(SkinStudio plugin) {
        ensureRpmEnvironment(plugin);
        registerBuiltPack(plugin);
        if (reloadViaApi(plugin)) {
            schedulePushToPlayers(plugin);
            return;
        }
        dispatchReloadCommand(plugin);
        schedulePushToPlayers(plugin);
    }

    /** Hash-guard: porównuje SHA-1 zbudowanego packa z ostatnim reloadem. */
    private static boolean packChangedSinceLastReload(SkinStudio plugin) {
        try {
            String outputFolder = plugin.getConfig().getString("converter.output-folder", "pack");
            File pack = new File(plugin.getDataFolder(), outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME);
            if (!pack.isFile()) return true; // brak packa → nie blokuj reloadu
            String hash = sha1(pack);
            File hashFile = new File(plugin.getDataFolder(), ".last-pack-hash");
            String prev = hashFile.isFile()
                ? Files.readString(hashFile.toPath(), StandardCharsets.UTF_8).trim() : "";
            if (hash.equals(prev)) return false;
            Files.writeString(hashFile.toPath(), hash, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("hash-guard: " + e.getMessage() + " — reloaduję mimo to.");
            return true; // przy błędzie bezpieczniej zreloadować
        }
    }

    private static String sha1(File file) throws IOException, java.security.NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
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

    private static void cleanPriorityOrder(SkinStudio plugin) {
        File config = new File(plugin.getServer().getPluginsFolder(), "ResourcePackManager/config.yml");
        if (!config.isFile()) return;
        try {
            List<String> lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            boolean changed = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equals("- SkinStudio-skins.zip") || trimmed.equals("- SkinStudio_resource_pack.zip")) {
                    changed = true;
                    continue;
                }
                out.add(line);
            }
            if (changed) {
                Files.write(config.toPath(), out, StandardCharsets.UTF_8);
                plugin.getLogger().info("RPM config: usunięto martwe wpisy priorityOrder (SkinStudio zip).");
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
