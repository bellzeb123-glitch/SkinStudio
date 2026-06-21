package pl.skinstudio.converter;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import pl.skinstudio.SkinStudio;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Automatycznie przetwarza nowe packi wrzucone do {@code inbox/}.
 */
public final class SkinInboxService {

    private final SkinStudio plugin;
    private final SkinConverter converter;
    private final Logger log;
    private BukkitTask pollTask;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public SkinInboxService(SkinStudio plugin) {
        this.plugin = plugin;
        this.converter = new SkinConverter(plugin);
        this.log = plugin.getLogger();
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("converter.enabled", true)) return;

        ensureFolders();
        long delay = 60L;
        long interval = plugin.getConfig().getLong("converter.poll-interval-ticks", 200L);

        if (plugin.getConfig().getBoolean("converter.process-on-startup", true)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::processInboxSilent, delay);
        }

        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, this::processInboxSilent, delay + interval, interval);
        log.info("Konwerter skinów: inbox=" + converter.inboxDir().getPath()
            + " (poll co " + interval + " ticków)");
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    public SkinConverter.BatchResult processInboxNow(CommandSender notify) {
        if (!processing.compareAndSet(false, true)) {
            if (notify != null) {
                notify.sendMessage(plugin.getLang().component("command.convert-busy"));
            }
            return new SkinConverter.BatchResult(List.of(), 0, false, 0, 0);
        }
        try {
            SkinConverter.BatchResult result = converter.processInbox();
            if (notify != null) {
                Bukkit.getScheduler().runTask(plugin, () -> reportTo(notify, result));
            }
            return result;
        } finally {
            processing.set(false);
        }
    }

    private void processInboxSilent() {
        if (!processing.compareAndSet(false, true)) return;
        try {
            File inbox = converter.inboxDir();
            File[] pending = inbox.listFiles(f ->
                (f.isFile() && f.getName().toLowerCase().endsWith(".zip"))
                    || (f.isDirectory() && !f.getName().startsWith(".")));
            if (pending == null || pending.length == 0) return;

            log.info("Konwerter: wykryto " + pending.length + " pack(ów) w inbox — konwersja...");
            SkinConverter.BatchResult result = converter.processInbox();
            SkinConverterResult.summarize(result.items(), log);
            if (result.totalSkinsAdded() > 0) {
                log.info("Konwerter: dodano " + result.totalSkinsAdded() + " skinów. Pack "
                    + (result.packBuilt() ? "zbudowany" : "NIE zbudowany") + ".");
            }
        } finally {
            processing.set(false);
        }
    }

    private void reportTo(CommandSender sender, SkinConverter.BatchResult result) {
        if (result.items().isEmpty()) {
            sender.sendMessage(plugin.getLang().component("command.convert-none"));
            return;
        }
        for (String line : SkinConverterResult.summarize(result.items(), null)) {
            sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize("&7" + line));
        }
        if (result.packBuilt()) {
            sender.sendMessage(plugin.getLang().component("command.convert-rebuilt",
                "skins", result.skinsInPack(), "assets", result.assetsCopied()));
            sender.sendMessage(plugin.getLang().component("command.scan-normalized-reload"));
        } else if (result.totalSkinsAdded() > 0) {
            sender.sendMessage(plugin.getLang().component("command.convert-done",
                "count", result.totalSkinsAdded()));
        }
    }

    private void ensureFolders() {
        converter.inboxDir().mkdirs();
        converter.stagingDir().mkdirs();
        converter.processedDir().mkdirs();
        converter.failedDir().mkdirs();
    }

    public SkinConverter getConverter() {
        return converter;
    }
}
