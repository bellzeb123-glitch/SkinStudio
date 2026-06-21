package pl.skinstudio.delivery;

import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Centralny dyspozytor dostawy packa. Wybiera providera (RPM / self-host / external),
 * a wspólnie obsługuje boot-grace i hash-guard, więc providerzy robią tylko surową dostawę.
 *
 * <ul>
 *   <li><b>auto</b> — RPM jeśli zainstalowany, inaczej self-host.</li>
 *   <li><b>rpm</b> / <b>selfhost</b> / <b>external</b> / <b>none</b> — wymuszone.</li>
 * </ul>
 */
public final class PackDelivery {

    private static volatile PackDeliveryProvider active;

    private PackDelivery() {}

    public static synchronized void init(SkinStudio plugin) {
        shutdown();
        if (!plugin.getConfig().getBoolean("delivery.enabled", true)) {
            active = null;
            plugin.getLogger().info("Pack delivery wyłączone (delivery.enabled=false).");
            return;
        }
        String mode = plugin.getConfig().getString("delivery.mode", "auto").toLowerCase(Locale.ROOT);
        active = resolve(plugin, mode);
        if (active == null) {
            plugin.getLogger().info("Pack delivery: brak providera (mode=" + mode + ").");
            return;
        }
        active.start();
        plugin.getLogger().info("Pack delivery provider: " + active.name() + " (mode=" + mode + ").");
    }

    private static PackDeliveryProvider resolve(SkinStudio plugin, String mode) {
        RpmDeliveryProvider rpm = new RpmDeliveryProvider(plugin);
        return switch (mode) {
            case "rpm" -> rpm.available() ? rpm : null;
            case "selfhost" -> new SelfHostDeliveryProvider(plugin);
            case "external" -> new ExternalFolderDeliveryProvider(plugin);
            case "none" -> null;
            default -> rpm.available() ? rpm : new SelfHostDeliveryProvider(plugin);
        };
    }

    /**
     * Dostarcza pack wg aktywnego providera.
     *
     * @param force {@code true} = jawna akcja admina (pomija boot-grace).
     */
    public static void deliver(SkinStudio plugin, boolean force) {
        PackDeliveryProvider provider = active;
        if (provider == null) return;

        if (!force && !plugin.isBootGracePassed()) {
            plugin.getLogger().info("Delivery pominięty (start serwera) — provider zadziała po boot-grace.");
            return;
        }
        if (!packChangedSinceLastDelivery(plugin)) {
            plugin.getLogger().info("Delivery pominięty — pack bez zmian (hash-guard).");
            return;
        }
        provider.deliver(force);
    }

    /** Wymusza ponowną dostawę do graczy, pomijając hash-guard i boot-grace (np. komenda repush / cache CDN). */
    public static void redeliver(SkinStudio plugin) {
        PackDeliveryProvider provider = active;
        if (provider == null) {
            plugin.getLogger().warning("Repush: brak aktywnego providera dostawy.");
            return;
        }
        provider.deliver(true);
    }

    public static synchronized void shutdown() {
        if (active != null) {
            active.shutdown();
            active = null;
        }
    }

    public static String activeProviderName() {
        PackDeliveryProvider p = active;
        return p == null ? "none" : p.name();
    }

    private static boolean packChangedSinceLastDelivery(SkinStudio plugin) {
        try {
            String outputFolder = plugin.getConfig().getString("converter.output-folder", "pack");
            File pack = new File(plugin.getDataFolder(), outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME);
            if (!pack.isFile()) return true;
            String hash = sha1(pack);
            File hashFile = new File(plugin.getDataFolder(), ".last-pack-hash");
            String prev = hashFile.isFile()
                ? Files.readString(hashFile.toPath(), StandardCharsets.UTF_8).trim() : "";
            if (hash.equals(prev)) return false;
            Files.writeString(hashFile.toPath(), hash, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("hash-guard: " + e.getMessage() + " — dostarczam mimo to.");
            return true;
        }
    }

    private static String sha1(File file) throws Exception {
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
}
