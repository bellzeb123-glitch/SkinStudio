package pl.skinstudio.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class AdminChatListener implements Listener {

    private final AdminGUI adminGUI;

    public AdminChatListener(AdminGUI adminGUI) {
        this.adminGUI = adminGUI;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!adminGUI.isAwaitingInput(player)) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Wykonaj na głównym wątku
        player.getServer().getScheduler().runTask(
            player.getServer().getPluginManager().getPlugin("SkinStudio"),
            () -> adminGUI.handleChatInput(player, message)
        );
    }
}
