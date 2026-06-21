package pl.skinstudio.delivery;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.pack.SkinPackBuilder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Samodzielna dostawa bez RPM: mini-serwer HTTP serwuje {@code pack.zip},
 * a pack jest wysyłany do graczy przez API Paper (Adventure). Nowoczesny MC
 * obsługuje wiele nałożonych packów (po UUID), więc pack SkinStudio dokłada się
 * obok innych pluginów (replace=false) — bez mergowania po stronie serwera.
 */
public final class SelfHostDeliveryProvider implements PackDeliveryProvider, Listener {

    private static final String CONTEXT_PATH = "/skinstudio/pack.zip";
    private final UUID packId = UUID.nameUUIDFromBytes("skinstudio-pack".getBytes(StandardCharsets.UTF_8));

    private final SkinStudio plugin;
    private HttpServer server;
    private volatile byte[] packBytes;
    private volatile String shaHex;
    private volatile String url;

    public SelfHostDeliveryProvider(SkinStudio plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "SelfHost";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public synchronized void start() {
        if (server != null) return;
        int port = plugin.getConfig().getInt("delivery.selfhost.port", 25567);
        try {
            loadPack();
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(CONTEXT_PATH, exchange -> {
                byte[] body = packBytes;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            Bukkit.getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("Self-host HTTP na porcie " + port + " → " + url
                + " (pamiętaj o otwarciu portu w firewallu).");
        } catch (IOException e) {
            plugin.getLogger().warning("Self-host start nie powiódł się (port " + port + "): " + e.getMessage());
            server = null;
        }
    }

    @Override
    public void deliver(boolean force) {
        try {
            loadPack();
        } catch (IOException e) {
            plugin.getLogger().warning("Self-host: nie udało się wczytać pack.zip: " + e.getMessage());
            return;
        }
        if (packBytes == null) {
            plugin.getLogger().warning("Self-host: brak pack.zip do wysłania.");
            return;
        }
        ResourcePackRequest req = buildRequest(force);
        Runnable push = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) p.sendResourcePacks(req);
            plugin.getLogger().info("Self-host: wysłano pack do " + Bukkit.getOnlinePlayers().size()
                + " graczy (url=" + url + ").");
        };
        if (Bukkit.isPrimaryThread()) push.run();
        else Bukkit.getScheduler().runTask(plugin, push);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (packBytes == null || url == null) return;
        event.getPlayer().sendResourcePacks(buildRequest(false));
    }

    @Override
    public synchronized void shutdown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        HandlerList.unregisterAll(this);
    }

    private ResourcePackRequest buildRequest(boolean force) {
        String prompt = plugin.getConfig().getString("delivery.selfhost.prompt", "Resource pack: custom skiny");
        boolean required = force || plugin.getConfig().getBoolean("delivery.selfhost.force", false);
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, URI.create(url), shaHex);
        return ResourcePackRequest.resourcePackRequest()
            .packs(info)
            .replace(false) // nie usuwaj packów innych pluginów — dokładamy swój
            .required(required)
            .prompt(Component.text(prompt))
            .build();
    }

    private void loadPack() throws IOException {
        String outputFolder = plugin.getConfig().getString("converter.output-folder", "pack");
        File pack = new File(plugin.getDataFolder(), outputFolder + "/" + SkinPackBuilder.OUTPUT_NAME);
        if (!pack.isFile()) {
            packBytes = null;
            return;
        }
        packBytes = Files.readAllBytes(pack.toPath());
        shaHex = sha1Hex(packBytes);
        url = buildUrl();
    }

    private String buildUrl() {
        String host = plugin.getConfig().getString("delivery.selfhost.public-host", "");
        if (host == null || host.isBlank()) {
            host = Bukkit.getIp();
            if (host == null || host.isBlank()) host = "127.0.0.1";
            plugin.getLogger().warning("delivery.selfhost.public-host puste — używam " + host
                + ". Ustaw publiczny host/IP, by zdalni gracze pobrali pack.");
        }
        int port = plugin.getConfig().getInt("delivery.selfhost.port", 25567);
        return "http://" + host + ":" + port + CONTEXT_PATH;
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
